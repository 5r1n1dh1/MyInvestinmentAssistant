package com.mia.app.domain.pattern.detectors.chart

import com.mia.app.domain.model.Direction
import com.mia.app.domain.model.PivotKind
import com.mia.app.domain.pattern.FactorScorer
import com.mia.app.domain.pattern.PatternIds
import com.mia.app.domain.model.Measure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Detector 12 — `Bull Flag` / `Bear Flag`, on `patterns-spec.md` §18.7's accepted bull-flag fixture.
 *
 * §18.7 walks five rejected fixtures down to one, and the accepted table is:
 *
 * | role | bar | price | kind |
 * |---|---|---|---|
 * | `B`  | 20 | 500 | LOW |
 * | `T`  | 44 | 620 | HIGH |
 * | `F₁` | 50 | 604 | LOW |
 * | `G₁` | 54 | 610 | HIGH |
 * | `F₂` | 58 | 600 | LOW |
 * | `G₂` | 62 | 606 | HIGH |
 * | — | 70 | 632 | HIGH (breakout bar) |
 *
 * `poleHeight = 120`, `flagHeight = 10`, `depth = 0.0833`, `parallelDev = 0.0000`, `flagBars = 18`,
 * `upperChannel.at(70) = 602.00`, `projectedLevel = 632 + 120 = 752`.
 */
class FlagDetectorTest {

    private val detector = FlagDetector()

    @Test
    fun `theSpecsAcceptedBullFlagFixtureIsDetectedWithItsExactMeasuredMove`() {
        val series = bullFlagSeries()
        val matches = detector.detect(series.pivots, series.candles)
        val bulls = matches.filter { it.name == FlagDetector.BULL_FLAG_NAME }

        assertEquals(1, bulls.size, "one bull flag in the §18.7 fixture, got ${matches.map { it.name to it.score }}")
        val match = bulls.single()
        assertEquals(PatternIds.of("Bull Flag"), match.patternId)
        ChartAssertions.nameIsCanonical(match.name)

        // §18.1 P2 — `B, T, F₁, G₁, F₂, G₂`, in that order.
        assertEquals(listOf(20, 44, 50, 54, 58, 62), match.pivots.map { it.barIndex })
        assertEquals(
            listOf(PivotKind.LOW, PivotKind.HIGH, PivotKind.LOW, PivotKind.HIGH, PivotKind.LOW, PivotKind.HIGH),
            match.pivots.map { it.kind },
        )
        ChartAssertions.pivotsAreRealElements(match, series.pivots)
        ChartAssertions.pivotsAreOrderedOldestFirst(match)
        ChartAssertions.scoreIsTheBreakdownTotal(match)

        val measured = assertNotNull(match.measuredMove)
        assertEquals(120.000, measured.height, 1e-6, "§18.4: 'Flag — Measure: Flagpole'")
        assertEquals(602.000, measured.triggerLevel, 1e-6, "§18.7: upperChannel at bar 70 = 606 − 4 = 602")
        assertEquals(752.000, measured.projectedLevel, 1e-6, "§18.7: 632 + 120 = 752")
        assertEquals(Direction.UP, measured.direction)
        ChartAssertions.measuredMoveIsOnTheCorrectSide(match)
    }

    @Test
    fun `theSpecsShapeRulesAllHoldOnTheFixture`() {
        val candidate = bullCandidate(bullFlagSeries())
        // §18.7 S1 — `poleHeight = 120`, `polePct = 24.00 %`, and `120 ≥ 4 × ATR14(20)`.
        assertEquals(24.0, candidate.measure("G3 polePct"), 1e-9)
        // §18.7 S2 — `flagHeight = 610 − 600 = 10`, `depth = 10/120 = 0.08333`.
        assertEquals(0.083333, candidate.measure("G8 flagDepth"), 1e-5)
        // §18.7 S4 — `sG = (606 − 610)/8 = −0.5`, `sF = (600 − 604)/12 = −0.3333`, `parallelDev = 0`.
        assertEquals(0.0, candidate.measure("G7 parallelDev"), 1e-9)
        assertTrue(candidate.measure("S5 durationFit") > 0.0, "§18.7 S5: FLAG bars = 62 − 44 = 18")
        // §6 / §18.1 P3 — "the pole *is* the prior trend", so X1 reads exactly 1.0.
        assertEquals(1.0, candidate.measure("X1 priorTrendPct (the pole)"), 1e-12)
    }

    @Test
    fun `theSpecsVolumeReadingIsReproduced`() {
        // §18.7 I4 — `volumeContraction = 690,000 / 1,320,000 = 0.5227`, at or below the 0.80 ideal.
        val candidate = bullCandidate(bullFlagSeries())
        assertEquals(0.522727, candidate.measure("C8 volumeContraction"), 1e-5)
        assertTrue(
            candidate.measure("C2 rvolBreakout") >= ChartSpec.RVOL_CONFIRM,
            "§18.7: rvol[70] = 3,600,000 / 944,000 = 3.81 confirms the break",
        )
    }

    @Test
    fun `aBullFlagCarriesNoFailureModePenaltiesOnTheTextbookFixture`() {
        val candidate = bullCandidate(bullFlagSeries())
        assertEquals(emptyList(), candidate.penaltyNames(), "§18.7's fixture satisfies every §18.3 rule")
    }

    @Test
    fun `everyFactorIsScoredAndTheTotalIsTheMatchScore`() {
        val series = bullFlagSeries()
        val candidate = bullCandidate(series)
        val match = detector.detect(series.pivots, series.candles)
            .first { it.name == FlagDetector.BULL_FLAG_NAME }
        ChartAssertions.everyFactorIsScored(candidate)
        val applied = FactorScorer.apply(FactorScorer.score(candidate.measures), candidate.penalties)
        ChartAssertions.breakdownIs(match, applied.breakdown.geometry, applied.breakdown.structure,
            applied.breakdown.confirmation, applied.breakdown.context)
        ChartAssertions.verdictIsConsistent(match, series.candles)
    }

    // ------------------------------------------------------------------------------ polarity

    @Test
    fun `aBullFlagIsNeverAlsoABearFlag`() {
        val series = bullFlagSeries()
        val bears = detector.detect(series.pivots, series.candles)
            .filter { it.name == FlagDetector.BEAR_FLAG_NAME }
        assertTrue(
            bears.none { it.pivots.map { p -> p.barIndex } == listOf(20, 44, 50, 54, 58, 62) },
            "a rising pole with a downward channel is a bull flag only; got " +
                bears.map { it.pivots.map { p -> p.barIndex } },
        )
    }

    @Test
    fun `aBearFlagIsDetectedWithMirroredRolesAndAMirroredMeasuredMove`() {
        val series = bearFlagSeries()
        val bears = detector.detect(series.pivots, series.candles)
            .filter { it.name == FlagDetector.BEAR_FLAG_NAME }
        assertEquals(1, bears.size, "one bear flag in the mirrored fixture")
        val match = bears.single()
        assertEquals(PatternIds.of("Bear Flag"), match.patternId)
        assertEquals(
            listOf(PivotKind.HIGH, PivotKind.LOW, PivotKind.HIGH, PivotKind.LOW),
            match.pivots.map { it.kind }.take(4),
            "§18.1 P2 for a bear flag: `B` is a HIGH and `T` a LOW",
        )

        val measured = assertNotNull(match.measuredMove)
        assertEquals(120.000, measured.height, 1e-6, "the mirror of §18.7's flagpole")
        assertEquals(598.000, measured.triggerLevel, 1e-6, "a bear flag's trigger is the **lower** channel")
        assertEquals(358.000, measured.projectedLevel, 1e-6, "the mirror of §18.7: 478 − 120 = 358")
        assertEquals(Direction.DOWN, measured.direction)
        ChartAssertions.measuredMoveIsOnTheCorrectSide(match)
    }

    // ------------------------------------------------------------------------------ near misses

    @Test
    fun `aPoleThatIsTooWeakToBeAPoleIsRejected`() {
        // §18.2 S1 `[derived]` `POLE_MIN_PCT = 10.0`: `T = 545` gives `polePct = 9.0 %`.
        val series = bullFlagSeries(poleTop = 545.0, firstLower = 500.0, firstUpper = 506.0, secondLower = 496.0, secondUpper = 502.0)
        assertTrue(
            detector.detect(series.pivots, series.candles).none { it.name == FlagDetector.BULL_FLAG_NAME },
            "§18.2 S1: 'Without a strong Flagpole, there is no Flag.'",
        )
    }

    @Test
    fun `aDeepCorrectionIsNotAFlag`() {
        // §18.2 S2 `[derived]` `depth <= 0.50`: the book's own "Mistaking deep corrections for Flags"
        // [Ch.18-4 §Common Beginner Mistakes]. `flagHeight = 625 − 560 = 65` ⇒ `65/120 = 0.542`.
        val series = bullFlagSeries(firstLower = 625.0, firstUpper = 610.0, secondLower = 560.0, secondUpper = 590.0)
        assertTrue(
            detector.detect(series.pivots, series.candles).none { it.name == FlagDetector.BULL_FLAG_NAME },
            "§18.2 S2 rejects a channel deeper than half the pole",
        )
    }

    @Test
    fun `aConvergingChannelIsAPennantAndIsRejectedRatherThanScored`() {
        // §18.2 S4 `[derived]` `parallelDev <= 0.50`: the book contrasts "**Parallel** boundaries"
        // with "**Converging** boundaries" [Ch.18-4 §Difference Between Flag and Pennant], and a
        // pennant is out of scope for v1. `G₂ = 618` gives `sG = (618 − 610)/8 = +1.0` against
        // `sF = (600 − 604)/12 = −0.3333`, so the boundaries converge.
        val series = bullFlagSeries(secondUpper = 618.0)
        assertTrue(
            detector.detect(series.pivots, series.candles).none { it.name == FlagDetector.BULL_FLAG_NAME },
            "§18.2 S4: a converging channel is a pennant, which v1 rejects outright",
        )
    }

    @Test
    fun `aChannelThatRisesAgainstThePoleIsNotABullFlag`() {
        // §18.2 S3 — "After the rally, price moves **slightly downward or sideways**".
        val series = bullFlagSeries(firstUpper = 600.0, secondUpper = 610.0)
        assertTrue(
            detector.detect(series.pivots, series.candles).none { it.name == FlagDetector.BULL_FLAG_NAME },
            "§18.2 S3 rejects a channel that rises with the pole",
        )
    }

    @Test
    fun `aFlagLongerThanTwentyDailyBarsIsRejected`() {
        // §18.2 S5 `[derived]` `FLAG_MAX_BARS(D1) = 20`: "Flags and Pennants are **short-duration**
        // consolidation patterns" [Ch.18-4 §Key Takeaways]. `flagBars = 70 − 44 = 26`.
        val series = bullFlagSeries(secondUpperBar = 70, secondUpper = 604.0)
        assertTrue(
            detector.detect(series.pivots, series.candles).none { it.name == FlagDetector.BULL_FLAG_NAME },
            "§18.2 S5 rejects a flag longer than its duration window",
        )
    }

    // -------------------------------------------------------------------- failure modes

    @Test
    fun `aBullTrapReducesTheScoreAgainstTheSameFormationWithoutTheFailure`() {
        val clean = bullFlagSeries()
        val trapped = bullFlagSeries(closeAfterBreak = listOf(598.0, 606.0, 620.0, 630.0, 640.0))

        val cleanScore = detector.detect(clean.pivots, clean.candles)
            .first { it.name == FlagDetector.BULL_FLAG_NAME }.score
        val trappedMatch = detector.detect(trapped.pivots, trapped.candles)
            .first { it.name == FlagDetector.BULL_FLAG_NAME }
        val trappedCandidate = bullCandidate(trapped)
        assertTrue(
            trappedCandidate.penaltyNames().contains("BULL_TRAP"),
            "a close back inside the channel inside TRAP_WINDOW bars is a bull trap; got " +
                trappedCandidate.penaltyNames(),
        )
        assertTrue(trappedMatch.score < cleanScore, "the bull trap must lower the score")
        assertTrue(
            FactorScorer.combine(FactorScorer.score(trappedCandidate.measures), emptyList()).total >
                trappedMatch.score,
            "removing the penalty from the very same measures must restore a higher total",
        )
    }

    @Test
    fun `aBullFlagThatBreaksDownInsteadOfUpIsAFailedContinuation`() {
        // §18.3 I3 — "[Ch.18-4 §Failed Continuation Patterns]: Bull Flag → price breaks downward →
        // heavy volume → trend reverses." Heavier than a plain trap because the book says the
        // *opposite* move is the likely one.
        val series = bullFlagSeries(closeAfterBreak = listOf(596.0, 594.0, 592.0, 590.0, 588.0))
        val candidate = bullCandidate(series)
        assertTrue(
            candidate.penaltyNames().contains("FAILED_CONTINUATION"),
            "§18.3 I3 must fire; got ${candidate.penaltyNames()}",
        )
    }

    @Test
    fun `volumeThatExpandedThroughTheFlagIsPenalised`() {
        val series = bullFlagSeries(flagVolume = 2_000_000L, baselineVolume = 1_000_000L)
        val candidate = bullCandidate(series)
        assertTrue(
            candidate.penaltyNames().contains("VOLUME_CONTRACTION_ABSENT"),
            "§18.3 I4 must fire; got ${candidate.penaltyNames()}",
        )
    }

    @Test
    fun `aStillFormingFlagCarriesNoMeasuredMove`() {
        val series = bullFlagSeries(
            closeAtBreak = 596.0,
            breakoutPivot = false,
            closeAfterBreak = List(9) { 596.0 },
        )
        val match = detector.detect(series.pivots, series.candles)
            .first { it.name == FlagDetector.BULL_FLAG_NAME }
        assertEquals(null, match.measuredMove, "§11.1: FORMING carries no measured move")
        assertEquals(0.0, bullCandidate(series).measure("C1 breakoutDepth"), 1e-12)
    }

    // ------------------------------------------------------------------------------- helpers

    private fun bullCandidate(series: BuiltSeries): ChartCandidate {
        val context = ChartContext.of(series.candles)
        return ChartAssertions.single(
            FlagEngine.candidates(
                FlagDetector.BULL_FLAG_NAME,
                bull = true,
                context = context,
                confirmed = context.confirmed(series.pivots),
            ),
            FlagDetector.BULL_FLAG_NAME,
        )
    }
}