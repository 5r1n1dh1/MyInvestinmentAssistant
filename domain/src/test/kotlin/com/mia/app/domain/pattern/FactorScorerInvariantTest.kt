package com.mia.app.domain.pattern

import com.mia.app.domain.model.Category
import com.mia.app.domain.model.MatchScorePolicy
import com.mia.app.domain.model.ScoreBreakdown
import com.mia.app.domain.model.Measure
import com.mia.app.domain.model.MeasureBand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Invariants the scorer must hold for *any* input (plan.md §12 property tests, plus the
 * `scoring-spec.md` §2.5/§6.1/§7.2 guarantees).
 */
class FactorScorerInvariantTest {

    @Test
    fun `everyFactorScoreIsInsideZeroToOneHundred`() {
        for (measures in measureGrid()) {
            for (factor in ScoreFactor.ALL) {
                val score = FactorScorer.factor(measures, factor)
                assertTrue(score in 0..100, "factor $factor produced $score for $measures")
            }
            val total = FactorScorer.score(measures)
            assertTrue(total.total in 0..100, "total ${total.total} out of range")
        }
    }

    @Test
    fun `weightsAcrossTheFourFactorsSumToOneHundred`() {
        assertEquals(100, ScoreFactor.TOTAL_WEIGHT)
        assertEquals(
            100,
            MatchScorePolicy.GEOMETRY_WEIGHT + MatchScorePolicy.STRUCTURE_WEIGHT +
                MatchScorePolicy.CONFIRMATION_WEIGHT + MatchScorePolicy.CONTEXT_WEIGHT,
        )
        ScoreFactor.entries.forEach { factor ->
            val fromPolicy = when (factor) {
                ScoreFactor.GEOMETRY -> MatchScorePolicy.GEOMETRY_WEIGHT
                ScoreFactor.STRUCTURE -> MatchScorePolicy.STRUCTURE_WEIGHT
                ScoreFactor.CONFIRMATION -> MatchScorePolicy.CONFIRMATION_WEIGHT
                ScoreFactor.CONTEXT -> MatchScorePolicy.CONTEXT_WEIGHT
            }
            assertEquals(fromPolicy, factor.weight, "${factor.label} weight must come from MatchScorePolicy")
        }
    }

    @Test
    fun `aFactorWithNoMeasuresScoresZeroAndRecordsAReason`() {
        val geometryOnly = listOf(
            Measure.linear("G1", 1.0, 20, ScoreFactor.GEOMETRY, 0.8, 1.0),
        )
        val assessed = FactorScorer.assess(geometryOnly)

        assertEquals(0, assessed.breakdown.structure)
        assertEquals(0, assessed.breakdown.confirmation)
        assertEquals(0, assessed.breakdown.context)
        assertEquals(
            PatternScoringPolicy.UNSCORED_FACTOR_SCORE,
            FactorScorer.context(geometryOnly),
            "a missing factor must never default to 100 or to a fabricated neutral",
        )
        assertEquals(
            setOf(ScoreFactor.STRUCTURE, ScoreFactor.CONFIRMATION, ScoreFactor.CONTEXT),
            assessed.missingReasons.keys,
        )
        assessed.missingReasons.values.forEach { reason ->
            assertTrue(reason.contains("no measures emitted"), reason)
        }
        assertTrue(!assessed.isFullyScored)
    }

    @Test
    fun `aFactorWhoseMeasuresAreAllInapplicableAlsoScoresZeroWithAReason`() {
        val measures = listOf(
            Measure.linear("G1", 0.95, 20, ScoreFactor.GEOMETRY, 0.8, 1.0),
            Measure.notApplicable("C1 breakoutDepth", ScoreFactor.CONFIRMATION),
        )
        val assessed = FactorScorer.assess(measures)
        assertEquals(0, assessed.breakdown.confirmation)
        assertTrue(assessed.missingReasons.getValue(ScoreFactor.CONFIRMATION).contains("C1 breakoutDepth"))
        assertEquals(listOf("C1 breakoutDepth"), assessed.inapplicableMeasures)
    }

    @Test
    fun `droppingAnInapplicableMeasureRedistributesItsWeight`() {
        val full = listOf(
            Measure.linear("G1", 1.0, 20, ScoreFactor.GEOMETRY, 0.8, 1.0), // 100
            Measure.linear("G2", 0.0, 10, ScoreFactor.GEOMETRY, 0.0, 1.0), // 0
        )
        assertEquals(67, FactorScorer.geometry(full)) // (100·20 + 0·10)/30

        val zeroWeighted = full + Measure.notApplicable("G3 timeSym", ScoreFactor.GEOMETRY)
        assertEquals(67, FactorScorer.geometry(zeroWeighted), "a weight-0 measure must not dilute")
    }

    @Test
    fun `measureWeightsAreRelativeWithinTheirFactorNotGlobal`() {
        // The same geometry expressed with doubled weights must score identically: the scorer
        // renormalises, so a detector never has to rescale to hit 1.00.
        val a = listOf(Measure.linear("x", 0.9, 10, ScoreFactor.GEOMETRY, 0.0, 1.0))
        val b = listOf(Measure.linear("x", 0.9, 1000, ScoreFactor.GEOMETRY, 0.0, 1.0))
        assertEquals(FactorScorer.geometry(a), FactorScorer.geometry(b))
        assertEquals(90, FactorScorer.geometry(a))
    }

    @Test
    fun `negativeAndNonFiniteMeasureValuesAreRejected`() {
        assertFailsWith<IllegalArgumentException> {
            Measure.linear("negative", -0.5, 10, ScoreFactor.GEOMETRY, 0.0, 1.0)
        }
        assertFailsWith<IllegalArgumentException> {
            Measure.linear("nan", Double.NaN, 10, ScoreFactor.GEOMETRY, 0.0, 1.0)
        }
        assertFailsWith<IllegalArgumentException> {
            Measure.linear("inf", Double.POSITIVE_INFINITY, 10, ScoreFactor.GEOMETRY, 0.0, 1.0)
        }
        assertFailsWith<IllegalArgumentException> {
            Measure.linear("negativeWeight", 0.5, -1, ScoreFactor.GEOMETRY, 0.0, 1.0)
        }
        assertFailsWith<IllegalArgumentException> {
            Measure.linear("blankName", 0.5, 10, ScoreFactor.GEOMETRY, 0.0, 1.0).copy(name = "  ")
        }
        // And the band anchors themselves cannot be nonsense.
        assertFailsWith<IllegalArgumentException> { MeasureBand.Linear(false, 1.0, 1.0) }
        assertFailsWith<IllegalArgumentException> { MeasureBand.Decay(0.0) }
        assertFailsWith<IllegalArgumentException> { MeasureBand.RatioEqual(1.0, 0.0) }
        assertFailsWith<IllegalArgumentException> { MeasureBand.Exact(1.0, -1.0) }
    }

    @Test
    fun `penaltiesAreCappedAtThirtyAndNeverDriveTheScoreNegative`() {
        val perfect = listOf(
            Measure.linear("g", 1.0, 10, ScoreFactor.GEOMETRY, 0.0, 1.0),
            Measure.linear("s", 1.0, 10, ScoreFactor.STRUCTURE, 0.0, 1.0),
            Measure.linear("c", 1.0, 10, ScoreFactor.CONFIRMATION, 0.0, 1.0),
            Measure.linear("x", 1.0, 10, ScoreFactor.CONTEXT, 0.0, 1.0),
        )
        assertEquals(ScoreBreakdown(100, 100, 100, 100), FactorScorer.score(perfect))

        val many = List(12) { Penalty.of(PenaltyId.FAILED_PATTERN, "failure $it") }
        assertEquals(12 * 18, many.sumOf { it.points })
        assertEquals(PatternScoringPolicy.PENALTY_CAP, FactorScorer.totalPenalty(many))

        val result = FactorScorer.combine(FactorScorer.score(perfect), many)
        assertEquals(70, result.total)
        assertTrue(result.total >= 0)
    }

    @Test
    fun `anExtremePenaltySetStillLeavesANonNegativeScore`() {
        val weak = listOf(
            Measure.linear("g", 0.0, 10, ScoreFactor.GEOMETRY, 0.0, 1.0),
            Measure.linear("s", 0.0, 10, ScoreFactor.STRUCTURE, 0.0, 1.0),
            Measure.linear("c", 0.0, 10, ScoreFactor.CONFIRMATION, 0.0, 1.0),
            Measure.linear("x", 0.0, 10, ScoreFactor.CONTEXT, 0.0, 1.0),
        )
        val result = FactorScorer.combine(
            FactorScorer.score(weak),
            List(20) { Penalty.of(PenaltyId.FAILED_PATTERN, "failure $it") },
        )
        assertEquals(0, result.total)
        assertTrue(result.total >= 0, "plan.md §12: score is always in [0, 100]")
    }

    @Test
    fun `penaltiesOnlyEverLowerAScore`() {
        val breakdown = ScoreBreakdown(80, 60, 55, 40)
        val base = breakdown.total
        for (points in listOf(1, 6, 12, 18, 30)) {
            val penalised = FactorScorer.combine(breakdown, listOf(Penalty("X", points, "reason")))
            assertTrue(penalised.total <= base, "penalty of $points raised the score")
            assertTrue(penalised.total >= 0)
        }
    }

    @Test
    fun `combineTotalEqualsTheIndependentWeightedSumAndTheSpecFormula`() {
        for (measures in measureGrid()) {
            val penalties = if (measures.size % 2 == 0) {
                listOf(Penalty.of(PenaltyId.WEAK_VOLUME, "weak"))
            } else {
                listOf(Penalty.of(PenaltyId.BEAR_TRAP, "trap"), Penalty.of(PenaltyId.NO_DIVERGENCE, "none"))
            }
            val raw = FactorScorer.score(measures)
            val combined = FactorScorer.combine(raw, penalties)
            val independent = (
                combined.geometry * MatchScorePolicy.GEOMETRY_WEIGHT +
                    combined.structure * MatchScorePolicy.STRUCTURE_WEIGHT +
                    combined.confirmation * MatchScorePolicy.CONFIRMATION_WEIGHT +
                    combined.context * MatchScorePolicy.CONTEXT_WEIGHT
                ) / 100
            assertEquals(independent, combined.total, "total != independent recomputation")

            val applied = FactorScorer.totalPenalty(penalties)
            val specFormula = (
                Math.round(
                    raw.geometry * 0.40 + raw.structure * 0.25 + raw.confirmation * 0.20 + raw.context * 0.15,
                ) - applied
                ).coerceIn(0, 100)
            if (ScoreFactor.ALL.all { readingOf(raw, it) >= applied }) {
                // The normal case — no factor hits the floor — reproduces scoring-spec.md §7.2
                // exactly, which is what the three fixtures rely on.
                assertEquals(specFormula.toInt(), combined.total, "spec formula mismatch")
            } else {
                // Degenerate case: a factor smaller than the penalty is floored at 0, so the
                // deduction is *at most* the penalty. It can never be more, and never lifts.
                assertTrue(combined.total >= specFormula, "a floored factor must not over-penalise")
                assertTrue(combined.total <= raw.total)
            }
        }
    }

    private fun readingOf(breakdown: ScoreBreakdown, factor: ScoreFactor): Int = when (factor) {
        ScoreFactor.GEOMETRY -> breakdown.geometry
        ScoreFactor.STRUCTURE -> breakdown.structure
        ScoreFactor.CONFIRMATION -> breakdown.confirmation
        ScoreFactor.CONTEXT -> breakdown.context
    }

    @Test
    fun `theApplicationExposesThePrePenaltyFactorsForTheUi`() {
        val raw = ScoreBreakdown(80, 70, 60, 50)
        val application = FactorScorer.apply(raw, listOf(Penalty.of(PenaltyId.WEAK_VOLUME, "weak volume")))
        assertEquals(80, application.rawFactors.getValue(ScoreFactor.GEOMETRY))
        assertEquals(6, application.appliedPenaltyPoints)
        assertEquals(74, application.breakdown.geometry)
        assertTrue(!application.penaltyCapBound)
    }

    @Test
    fun `normalisationPrimitivesMatchScoringSpecSectionTwo`() {
        // §2.1 LIN_LINEAR
        assertEquals(0.0, MeasureBand.Linear(false, 0.8, 1.0).normalise(0.7), 1e-12)
        assertEquals(50.0, MeasureBand.Linear(false, 0.8, 1.0).normalise(0.9), 1e-12)
        assertEquals(100.0, MeasureBand.Linear(false, 0.8, 1.0).normalise(1.2), 1e-12)
        // §2.2 LIN_DECAY
        assertEquals(100.0, MeasureBand.Decay(10.0).normalise(0.0), 1e-12)
        assertEquals(60.0, MeasureBand.Decay(10.0).normalise(4.0), 1e-12)
        assertEquals(0.0, MeasureBand.Decay(10.0).normalise(10.0), 1e-12)
        assertEquals(0.0, MeasureBand.Decay(10.0).normalise(99.0), 1e-12)
        // §2.3 STEP
        assertEquals(100.0, MeasureBand.Exact(3.0, 0.0).normalise(3.0), 1e-12)
        assertEquals(0.0, MeasureBand.Exact(3.0, 0.0).normalise(2.0), 1e-12)
        // §2.4 RATIO_SATURATING
        assertEquals(100.0 * minOf(1.0, 1.0 / 3.0), MeasureBand.Linear(false, 0.0, 3.0).normalise(1.0), 1e-12)
        assertEquals(100.0, MeasureBand.Linear(false, 0.0, 3.0).normalise(9.0), 1e-12)
        // RatioEqual
        assertEquals(100.0, MeasureBand.RatioEqual(1.0, 0.5).normalise(1.0), 1e-12)
        assertEquals(0.0, MeasureBand.RatioEqual(1.0, 0.5).normalise(0.5), 1e-12)
    }

    @Test
    fun `roundingIsHalfUpAndHappensOncePerFactor`() {
        // 0.5 exactly must round up, both ways.
        assertEquals(68, FactorScorer.geometry(listOf(Measure.identity("g", 0.675, 10, ScoreFactor.GEOMETRY))))
        assertEquals(1, FactorScorer.geometry(listOf(Measure.identity("g", 0.005, 10, ScoreFactor.GEOMETRY))))
        // §5.2's context example: 67.5 → 68.
        val half = listOf(
            Measure.identity("x", 0.5, 10, ScoreFactor.CONTEXT),
            Measure.identity("x2", 1.0, 10, ScoreFactor.CONTEXT),
        )
        assertEquals(75, FactorScorer.context(half))
    }

    @Test
    fun `scoringIsDeterministic`() {
        val measures = ScoringFixtures.exampleA()
        val first = FactorScorer.assess(measures)
        val second = FactorScorer.assess(measures)
        assertEquals(first.breakdown, second.breakdown)
        assertEquals(first.factors, second.factors)
        assertEquals(first.missingReasons, second.missingReasons)
    }

    @Test
    fun `penaltyCatalogueMatchesScoringSpecSectionSevenPointOne`() {
        assertEquals(6, PenaltyId.WEAK_VOLUME.points)
        assertEquals(12, PenaltyId.BULL_TRAP.points)
        assertEquals(12, PenaltyId.BEAR_TRAP.points)
        assertEquals(8, PenaltyId.FAILED_RETEST.points)
        assertEquals(18, PenaltyId.FAILED_PATTERN.points)
        assertEquals(18, PenaltyId.FAILED_CONTINUATION.points)
        assertEquals(14, PenaltyId.STRUCTURE_CONTRADICTS.points)
        assertEquals(10, PenaltyId.CTX_MISMATCH.points)
        assertEquals(20, PenaltyId.CTX_MISMATCH.pointsFor(Category.CANDLESTICK), "×2 for detectors 1–5")
        assertEquals(10, PenaltyId.CTX_MISMATCH.pointsFor(Category.REVERSAL))
        assertEquals(5, PenaltyId.HTF_CONFLICT.points)
        assertEquals(5, PenaltyId.VOLATILE_MARKET.points)
        assertEquals(8, PenaltyId.LOW_LIQUIDITY.points)
        assertEquals(6, PenaltyId.NO_DIVERGENCE.points)
        assertEquals(10, PenaltyId.NO_PRIOR_TREND.points)
        assertEquals(8, PenaltyId.RANGING_REGIME.points)
        assertEquals(6, PenaltyId.EXHAUSTION.points)
        assertEquals(10, PenaltyId.INTRADAY_SPIKE_ONLY.points)
        assertEquals(6, PenaltyId.STALE_PATTERN.points)
        assertEquals(8, PenaltyId.NO_CONFIRMATION.points)
        assertEquals(5, PenaltyId.NO_FOLLOW_THROUGH.points)
        assertEquals(12, PenaltyId.WRONG_LOCATION.points)
        assertEquals(30, PatternScoringPolicy.PENALTY_CAP)
    }

    @Test
    fun `aPenaltyMustCarryAPositiveMagnitude`() {
        assertFailsWith<IllegalArgumentException> { Penalty("X", 0, "reason") }
        assertFailsWith<IllegalArgumentException> { Penalty("X", -6, "reason") }
        assertFailsWith<IllegalArgumentException> { Penalty("  ", 6, "reason") }
    }

    /** A deterministic spread of measure lists: empty factors, single measures, mixed weights. */
    private fun measureGrid(): List<List<Measure>> = listOf(
        emptyList(),
        listOf(Measure.identity("g", 1.0, 10, ScoreFactor.GEOMETRY)),
        listOf(Measure.identity("g", 0.0, 10, ScoreFactor.GEOMETRY)),
        listOf(Measure.identity("g", 1.0, 1, ScoreFactor.GEOMETRY), Measure.identity("g2", 0.0, 99, ScoreFactor.GEOMETRY)),
        listOf(
            Measure.linear("g", 0.99, 20, ScoreFactor.GEOMETRY, 0.80, 1.00),
            Measure.decay("g2", 4.0, 10, ScoreFactor.GEOMETRY, 0.0, 10.0),
            Measure.saturating("s", 3.0, 25, ScoreFactor.STRUCTURE, 3.0),
            Measure.identity("c", 1.0, 35, ScoreFactor.CONFIRMATION),
            Measure.linear("x", 12.0, 35, ScoreFactor.CONTEXT, 0.0, 12.0),
        ),
        listOf(
            Measure.exact("c", 0.0, 20, ScoreFactor.CONFIRMATION, 1.0, 0.0),
            Measure.ratioEqual("x", 1.0, 20, ScoreFactor.CONTEXT, 1.0, 0.5),
        ),
    )
}