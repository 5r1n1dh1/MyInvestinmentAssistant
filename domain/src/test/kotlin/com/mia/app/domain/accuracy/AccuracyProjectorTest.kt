package com.mia.app.domain.accuracy

import com.mia.app.domain.compliance.AdviceLanguage
import com.mia.app.domain.compliance.HonestyContract
import com.mia.app.domain.model.MatchScorePolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The forward-looking use of the track record (plan.md §6.1).
 *
 * Everything here is about one sentence: the app may quote what its own history did, and may
 * never turn that into a forecast. The tests assert the wording, the gate behind it, and that
 * the sample size travels with the number.
 */
class AccuracyProjectorTest {

    private val d = AccuracyTestData::date
    private val match = AccuracyTestData.match(name = "Double bottom")

    @Test
    fun `a thin per-stock record is withheld and says how thin it is`() {
        val history = AccuracyTestData.resolvedOutcomes(count = 4, hits = 3, symbol = "RELIANCE", asOf = d(10))

        val context = AccuracyProjector.context(match, "RELIANCE", history, asOf = d(11))

        assertEquals(3, context.priorHits)
        assertEquals(4, context.priorSamples)
        assertNull(context.hitRate, "4 samples must not produce a percentage")
        assertFalse(context.isReportable)
        assertEquals(
            "Not enough tracked cases yet (4 of 30).",
            context.evidence,
        )
        HonestyContract.requireNotProbabilityClaim(context.evidence)
        AdviceLanguage.requireClean(context.evidence)
    }

    @Test
    fun `a settled per-stock record is quoted with its own counts`() {
        val history = AccuracyTestData.resolvedOutcomes(count = 31, hits = 18, symbol = "RELIANCE", asOf = d(40))

        val context = AccuracyProjector.context(match, "RELIANCE", history, asOf = d(41))

        assertEquals(31, context.sampleSize)
        assertEquals(18, context.priorHits)
        assertEquals(18.0 / 31.0 * 100.0, context.hitRate)
        assertTrue(context.isReportable)
        assertEquals(
            "On RELIANCE, this pattern's geometry was reached in 18 of 31 previous cases " +
                "(58.1%). Past outcomes are chart events, not profits, and do not predict what " +
                "happens next.",
            context.evidence,
        )
        HonestyContract.requireNotProbabilityClaim(context.evidence)
        AdviceLanguage.requireClean(context.evidence)
    }

    @Test
    fun `other stocks' cases are never mixed into the per-stock number`() {
        val history = AccuracyTestData.resolvedOutcomes(count = 40, hits = 40, symbol = "TCS", asOf = d(50)) +
            AccuracyTestData.resolvedOutcomes(count = 5, hits = 0, symbol = "RELIANCE", asOf = d(50))

        val context = AccuracyProjector.context(match, "RELIANCE", history, asOf = d(51))

        assertEquals(5, context.priorSamples, "40 cases on TCS are not cases on RELIANCE")
        assertEquals(0, context.priorHits)
        assertEquals(45, context.comparableOutcomes, "the cross-stock pool is offered separately")
        assertEquals(40.0 / 45.0 * 100.0, context.comparableHitRate)
        // The per-stock cell is thin, so the sentence leads with the shortfall.
        assertTrue(context.evidence.startsWith(AccuracyProjector.HELD_PREFIX))
        assertTrue(context.evidence.contains("Not enough tracked cases yet on RELIANCE (5 of 30)."))
        assertTrue(context.evidence.contains("reached in 40 of 45 cases (88.9%)"))
    }

    @Test
    fun `a different formation on the same stock is not comparable`() {
        val history = AccuracyTestData.resolvedOutcomes(
            count = 60,
            hits = 60,
            symbol = "RELIANCE",
            patternName = "Bull flag",
            asOf = d(60),
        )

        val context = AccuracyProjector.context(match, "RELIANCE", history, asOf = d(61))

        assertEquals(0, context.priorSamples)
        assertEquals(0, context.comparableOutcomes)
        assertNull(context.hitRate)
        assertNull(context.comparableHitRate)
        assertEquals("Not enough tracked cases yet (0 of 30).", context.evidence)
    }

    @Test
    fun `open windows and the current bar are not previous cases`() {
        val settled = AccuracyTestData.resolvedOutcomes(count = 31, hits = 31, symbol = "RELIANCE", asOf = d(30))
        val open = AccuracyTestData.outcome(symbol = "RELIANCE", projectedOn = d(5), patternId = 500L)
        // Settled, but projected on the very bar being displayed.
        val sameBar = AccuracyTestData.outcome(
            symbol = "RELIANCE",
            projectedOn = d(30),
            patternId = 600L,
            resolved = true,
            hit = true,
        )

        assertEquals(31, AccuracyProjector.context(match, "RELIANCE", settled + open, asOf = d(30)).priorSamples)
        assertEquals(31, AccuracyProjector.context(match, "RELIANCE", settled, asOf = d(30)).priorSamples)
        assertEquals(
            31,
            AccuracyProjector.context(match, "RELIANCE", settled + sameBar, asOf = d(30)).priorSamples,
            "the current bar is not a previous case",
        )
        assertEquals(32, AccuracyProjector.context(match, "RELIANCE", settled + sameBar, asOf = d(31)).priorSamples)
    }

    @Test
    fun `an empty history says so instead of inventing a rate`() {
        val context = AccuracyProjector.context(match, "RELIANCE", emptyList(), asOf = d(1))

        assertEquals(0, context.priorSamples)
        assertEquals(0, context.comparableOutcomes)
        assertEquals("Not enough tracked cases yet (0 of 30).", context.evidence)
    }

    @Test
    fun `the cross-stock figure is withheld too when it is thin`() {
        val history = AccuracyTestData.resolvedOutcomes(count = 12, hits = 9, symbol = "RELIANCE", asOf = d(20))

        val context = AccuracyProjector.context(match, "RELIANCE", history, asOf = d(21))

        assertEquals(12, context.comparableOutcomes)
        assertNull(context.comparableHitRate, "12 cases across all stocks is still below 30")
        // A sub-threshold count is never quoted as a rate, even in the "comparable" sentence.
        assertFalse(context.evidence.contains("%"))
    }

    @Test
    fun `a 30-case cross-stock pool is exactly reportable`() {
        val history = AccuracyTestData.resolvedOutcomes(count = 30, hits = 21, symbol = "TCS", asOf = d(30))

        val context = AccuracyProjector.context(match, "RELIANCE", history, asOf = d(31))

        assertNull(context.hitRate, "nothing on RELIANCE itself")
        assertEquals(30, context.comparableOutcomes)
        assertEquals(70.0, context.comparableHitRate)
        assertTrue(context.evidence.contains("reached in 21 of 30 cases (70.0%)"))
    }

    @Test
    fun `the gate is read from the shared policy rather than restated`() {
        val below = AccuracyTestData.resolvedOutcomes(
            count = MatchScorePolicy.MIN_HIT_RATE_SAMPLES - 1,
            hits = 10,
            symbol = "RELIANCE",
            asOf = d(40),
        )
        val at = AccuracyTestData.resolvedOutcomes(
            count = MatchScorePolicy.MIN_HIT_RATE_SAMPLES,
            hits = 10,
            symbol = "RELIANCE",
            asOf = d(40),
        )

        assertNull(AccuracyProjector.context(match, "RELIANCE", below, asOf = d(41)).hitRate)
        assertNotNull(AccuracyProjector.context(match, "RELIANCE", at, asOf = d(41)).hitRate)
    }

    @Test
    fun `the symbol overload matches the match overload`() {
        val history = AccuracyTestData.resolvedOutcomes(count = 31, hits = 11, symbol = "TCS", asOf = d(40))

        assertEquals(
            AccuracyProjector.context(match, "TCS", history, asOf = d(41)),
            AccuracyProjector.context("TCS", match.name, history, asOf = d(41)),
        )
    }
}