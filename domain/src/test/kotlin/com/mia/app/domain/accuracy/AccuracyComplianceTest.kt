package com.mia.app.domain.accuracy

import com.mia.app.domain.compliance.AdviceLanguage
import com.mia.app.domain.compliance.HonestyContract
import com.mia.app.domain.model.HitRate
import com.mia.app.domain.model.MatchScorePolicy
import com.mia.app.domain.model.PatternOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The machine-checked half of the honesty contract (plan.md §1.4 rule 1, §6.1).
 *
 * The point of this file is that the rules are enforced by the suite rather than by a reviewer's
 * memory: every string this package can emit is walked here, and the module also emits a fresh
 * *adversarial* string — a pattern called "guaranteed price target" — to prove the guard is live
 * and not vacuously passing because nothing risky is ever generated.
 */
class AccuracyComplianceTest {

    private val d = AccuracyTestData::date

    @Test
    fun `every string constant in the accuracy package is clean`() {
        val constants = declaredStringConstants(
            OutcomeTracker::class.java,
            HitRateCalculator::class.java,
            AccuracyProjector::class.java,
            TrackRecordContext::class.java,
        )
        assertTrue(constants.size >= 2, "expected the copy constants to be found, found $constants")
        // The two user-facing constants of this package.
        assertEquals(
            listOf("HELD_PREFIX" to AccuracyProjector.HELD_PREFIX, "NOT_A_PREDICTION" to AccuracyProjector.NOT_A_PREDICTION),
            constants.map { it.first.substringAfterLast('.') to it.second }.sortedBy { it.first },
        )

        constants.forEach { (owner, value) ->
            val hits = AdviceLanguage.findForbiddenPhrases(value)
            if (hits.isNotEmpty()) fail("$owner declares advice-like copy \"$value\": $hits")
            HonestyContract.requireNotProbabilityClaim(value)
        }
    }

    @Test
    fun `the guard is live, not vacuous`() {
        // A pattern name is caller-supplied, so a hostile name must be caught at the boundary.
        val hostile = AccuracyTestData.match(name = "Guaranteed price target")

        assertTrue(
            AdviceLanguage.findForbiddenPhrases(hostile.name).contains("guaranteed"),
            "the fixture name must itself be advice-like for this test to mean anything",
        )
        val failure = runCatching {
            AdviceLanguage.requireClean(
                HitRateCalculator.summaryText(HitRate("RELIANCE", hostile.name, hits = 1, samples = 31)),
            )
        }
        assertTrue(failure.isFailure, "summaryText must refuse to render an advice-like pattern name")
    }

    @Test
    fun `every hit rate label this module can produce carries its sample size`() {
        for (samples in 0..80) {
            for (hits in 0..samples) {
                val label = HitRate("RELIANCE", "Bull flag", hits, samples).label()
                HonestyContract.requireHitRateSampleSize(label)
                AdviceLanguage.requireClean(label)
                HonestyContract.requireNotProbabilityClaim(label)
                val text = HitRateCalculator.summaryText(HitRate("RELIANCE", "Bull flag", hits, samples))
                HonestyContract.requireHitRateSampleSize(text)
                AdviceLanguage.requireClean(text)
                HonestyContract.requireNotProbabilityClaim(text)
                // Below the gate the percentage must be absent; at or above it, present.
                if (samples < MatchScorePolicy.MIN_HIT_RATE_SAMPLES) {
                    assertTrue(label.contains("withheld"), "label was $label")
                } else {
                    assertTrue(label.contains("n=$samples"), "label was $label")
                }
            }
        }
    }

    @Test
    fun `every aggregate scope renders clean copy`() {
        val scopes = listOf(
            HitRate("RELIANCE", "Bull flag", 30, 30),
            HitRate(null, "Bull flag", 0, 0),
            HitRate("RELIANCE", null, 1, 1),
            HitRate(null, null, 15, 30),
            HitRate("TATAMOTORSPV", "Head & shoulders", 0, 29),
        )
        scopes.forEach { hitRate ->
            val text = HitRateCalculator.summaryText(hitRate)
            AdviceLanguage.requireClean(text)
            HonestyContract.requireNotProbabilityClaim(text)
            HonestyContract.requireHitRateSampleSize(text)
        }
    }

    @Test
    fun `every projector sentence renders clean copy`() {
        val asOf = d(40)
        val history = listOf(
            // 31 settled cases on the symbol itself.
            *AccuracyTestData.resolvedOutcomes(count = 31, hits = 18, symbol = "RELIANCE", asOf = d(39))
                .toTypedArray(),
            // 31 more elsewhere, so the cross-stock pool is reportable too.
            *AccuracyTestData.resolvedOutcomes(count = 31, hits = 12, symbol = "TCS", asOf = d(39))
                .toTypedArray(),
        )
        val thin = AccuracyTestData.resolvedOutcomes(count = 3, hits = 1, symbol = "INFY", asOf = d(39))

        val sentences = listOf(
            AccuracyProjector.context(AccuracyTestData.match(), "RELIANCE", history, asOf),
            AccuracyProjector.context(AccuracyTestData.match(), "RELIANCE", thin, asOf),
            AccuracyProjector.context(AccuracyTestData.match(), "INFY", history + thin, asOf),
            AccuracyProjector.context(AccuracyTestData.match(), "MARUTI", emptyList(), asOf),
        ).map { it.evidence }

        sentences.forEach { text ->
            AdviceLanguage.requireClean(text)
            HonestyContract.requireNotProbabilityClaim(text)
            // Beyond the two shared guards: no promise, no forecast, no price. "profit" is only
            // allowed as the denial in NOT_A_PREDICTION, so it is checked separately below.
            listOf(
                "guarantee", "will rise", "will fall", "will go", "target",
                "recommend", "expect", "likely", "predict that", "upside",
            ).forEach { banned ->
                assertTrue(
                    !text.contains(banned, ignoreCase = true),
                    "\"$banned\" appeared in \"$text\"",
                )
            }
            if (text.contains("profit", ignoreCase = true)) {
                assertTrue(
                    text.contains("not profits", ignoreCase = true),
                    "profit may only appear as a denial: \"$text\"",
                )
            }
        }
        // Exactly the withheld-thin and reportable shapes, both carrying the disclaimer.
        assertTrue(sentences.any { it.contains("Not enough tracked cases yet") })
        assertTrue(sentences.any { it.contains("previous cases") })
        sentences.filter { it.contains("previous cases") }.forEach {
            assertTrue(it.contains("do not predict what happens next"), it)
        }
    }

    @Test
    fun `copy from the real simulated history is clean too`() {
        val result = RealHistorySimulation.run()
        val today = result.today

        val texts = buildList {
            HitRateCalculator.report(result.outcomes, today)
                .perSymbolAndPattern
                .forEach { add(HitRateCalculator.summaryText(it)) }
            RealHistorySimulation.SYMBOLS.forEach { symbol ->
                RealHistorySimulation.PATTERNS.forEach { pattern ->
                    add(AccuracyProjector.context(symbol, pattern, result.outcomes, today).evidence)
                }
            }
            result.outcomes.forEach { add(HitRateCalculator.summaryText(cellFor(result, it))) }
        }
        assertTrue(texts.isNotEmpty())

        texts.forEach { text ->
            AdviceLanguage.requireClean(text)
            HonestyContract.requireNotProbabilityClaim(text)
        }
    }

    @Test
    fun `no outcome in this package carries a profit or return field`() {
        // A structural check rather than a string check: the model is fixed, so if a future change
        // added an `expectedReturn` or `pnl` field, these tests would fail to compile.
        PatternOutcome::class.java.declaredFields.map { it.name }.let { names ->
            listOf("pnl", "profit", "return", "gain", "loss", "pAndL").forEach { banned ->
                assertTrue(
                    names.none { it.equals(banned, ignoreCase = true) },
                    "PatternOutcome gained a field named $banned",
                )
            }
        }
    }

    private fun cellFor(
        result: RealHistorySimulation.SimulationResult,
        outcome: PatternOutcome,
    ): HitRate = HitRateCalculator.perStockAndPattern(listOf(outcome)).single()

    /**
     * Every `String` constant declared on [types], including the `const val`s inside a Kotlin
     * `object` (which live as static fields on the object's own class).
     */
    private fun declaredStringConstants(vararg types: Class<*>): List<Pair<String, String>> =
        types.flatMap { type -> stringFieldsOf(type, null) + stringFieldsOf(type, singletonOf(type)) }

    private fun stringFieldsOf(type: Class<*>, owner: Any?): List<Pair<String, String>> = type.declaredFields
        .filter { it.type == String::class.java }
        .filter { java.lang.reflect.Modifier.isStatic(it.modifiers) == (owner == null) }
        .map { field ->
            field.isAccessible = true
            val value = if (owner == null) field.get(null) as String else field.get(owner) as String
            "${type.simpleName}.${field.name}" to value
        }

    /** The `INSTANCE` singleton of a Kotlin `object`, or null for a plain class. */
    private fun singletonOf(type: Class<*>): Any? = runCatching {
        type.getDeclaredField("INSTANCE").also { it.isAccessible = true }.get(null)
    }.getOrNull()
}