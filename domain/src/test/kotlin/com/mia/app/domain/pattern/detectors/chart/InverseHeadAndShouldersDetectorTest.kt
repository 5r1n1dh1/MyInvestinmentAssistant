package com.mia.app.domain.pattern.detectors.chart

import com.mia.app.domain.model.Direction
import com.mia.app.domain.model.PivotKind
import com.mia.app.domain.pattern.FactorScorer
import com.mia.app.domain.pattern.PatternIds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Detector 7 — `Inverse Head & Shoulders`, on `patterns-spec.md` §13's worked fixture.
 *
 * §13 works the fixture forward and then corrects it three times, so the numbers asserted here are
 * the spec's **final** accepted ones:
 *
 * | role | bar | price | kind |
 * |---|---|---|---|
 * | `LS` | 30 | 505 | LOW |
 * | `Q1` | 44 | 600 | HIGH |
 * | `H`  | 60 | 470 | LOW |
 * | `Q2` | 76 | 610 | HIGH |
 * | `RS` | 92 | 495 | LOW |
 * | — | 104 | 640 | HIGH (breakout bar) |
 *
 * with `neckline = lineThrough(Q1, Q2)`, `slope = 10/32 = 0.3125`, `necklineAt(60) = 605`,
 * `ref = 135`, `triggerLevel = neckline.at(104) = 618.75` and `projectedLevel = 640 + 135 = 775`.
 */
class InverseHeadAndShouldersDetectorTest {

    private val detector = InverseHeadAndShouldersDetector()

    @Test
    fun `the_specs_worked_fixture_is_detected_with_its_exact_measured_move`() {
        val series = inverseSeries()
        val matches = detector.detect(series.pivots, series.candles)

        assertEquals(1, matches.size, "one formation in the §13 fixture")
        val match = matches.single()
        assertEquals("Inverse Head & Shoulders", match.name)
        assertEquals(PatternIds.of("Inverse Head & Shoulders"), match.patternId)
        ChartAssertions.nameIsCanonical(match.name)

        // §13's role assignment: `LOW, HIGH, LOW, HIGH, LOW`.
        assertEquals(listOf(30, 44, 60, 76, 92), match.pivots.map { it.barIndex })
        assertEquals(
            listOf(PivotKind.LOW, PivotKind.HIGH, PivotKind.LOW, PivotKind.HIGH, PivotKind.LOW),
            match.pivots.map { it.kind },
        )
        ChartAssertions.pivotsAreRealElements(match, series.pivots)
        ChartAssertions.pivotsAreOrderedOldestFirst(match)
        ChartAssertions.scoreIsTheBreakdownTotal(match)

        val measured = assertNotNull(match.measuredMove)
        assertEquals(135.000, measured.height, 1e-3, "§13: height = neckline.at(H) − head")
        assertEquals(618.750, measured.triggerLevel, 1e-3, "§13: trigger = the neckline at the breakout bar")
        assertEquals(775.000, measured.projectedLevel, 1e-3, "§13: 'Breakout ₹620, Target ₹740'")
        assertEquals(Direction.UP, measured.direction)
        ChartAssertions.measuredMoveIsOnTheCorrectSide(match)
    }

    @Test
    fun `theSpecsShapeRulesAllHoldOnTheCorrectedFixture`() {
        val candidate = singleCandidate(inverseSeries())
        // §13 S1 — "Sellers attempt another decline. They **fail to make a new low**".
        assertTrue(470.0 < 505.0 && 470.0 < 495.0)
        // §13 S2 — `(min(505,495) − 470) / 135 = 25/135 = 0.18519`, spec's printed value.
        assertEquals(0.185185, candidate.measure("G2 headDominance"), 1e-5)
        // §13 S3 — `sym(505, 495) = 0.98020`.
        assertEquals(0.980198, candidate.measure("G1 symShoulder"), 1e-5)
        // §13 S6 — `slopeOverSpan(Q1, Q2, 92 − 30) = 0.3125 × 62 / 600 × 100 = +3.2292 %`.
        assertEquals(3.2292, candidate.measure("G5 |necklineTilt|"), 1e-3)
        // §13 S7–S9 — gaps 14, 16, 16, 16; span 62; `sym(14,16) = 0.875`, `sym(16,16) = 1.0`.
        assertEquals(0.875, candidate.measure("G4 timeSymmetry"), 1e-9)
        assertEquals(1.0, candidate.measure("G6 monoScore"), 1e-12)
    }

    @Test
    fun `theCorrectedFixtureCarriesNoFailureModePenalties`() {
        val candidate = singleCandidate(inverseSeries())
        assertEquals(emptyList(), candidate.penaltyNames(), "§13's accepted fixture satisfies every §13 rule")
    }

    @Test
    fun `anInverseHeadAndShouldersIsNeverAlsoAHeadAndShoulders`() {
        val series = inverseSeries()
        val tops = HeadAndShouldersDetector().detect(series.pivots, series.candles)
        assertTrue(
            tops.none { it.pivots.map { p -> p.barIndex } == listOf(30, 44, 60, 76, 92) },
            "the §13 window is LOW HIGH LOW HIGH LOW, which is not the head & shoulders role sequence",
        )
    }

    @Test
    fun `aLeftShoulderThatBreaksShoulderSymmetryIsNotAMatchAtAll`() {
        // §13 S3 `[derived]` 0.90: `LS = 560` gives `sym = 495/560 = 0.8839 < 0.90`.
        val series = inverseSeries(leftShoulder = 560.0)
        assertTrue(
            detector.detect(series.pivots, series.candles).isEmpty(),
            "shoulder symmetry below 0.90 must produce no match",
        )
    }

    @Test
    fun `aShoulderJustInsideTheSymmetryFloorMatchesButLosesGeometry`() {
        // `LS = 545` ⇒ `sym = 495/545 = 0.9083`, just inside §13 S3's 0.90 floor but far worse on G1
        // than the textbook `sym(505, 495) = 0.9802`. The head stays the lowest pivot, so only the
        // symmetry changes.
        val text = inverseSeries()
        val near = inverseSeries(leftShoulder = 545.0)
        val textGeometry = FactorScorer.geometry(singleCandidate(text).measures)
        val nearGeometry = FactorScorer.geometry(singleCandidate(near).measures)
        assertTrue(
            nearGeometry < textGeometry,
            "sym 0.908 must score below sym 0.980: $nearGeometry vs $textGeometry",
        )
    }

    @Test
    fun `aHeadThatDoesNotExtendPastTheShouldersIsRejected`() {
        // §13 S1 with `H = 500`: the head is above both shoulders, which is a head & shoulders shape.
        val series = inverseSeries(head = 500.0)
        assertTrue(
            detector.detect(series.pivots, series.candles).isEmpty(),
            "§13 S1: the head must be the lowest pivot of the formation",
        )
    }

    @Test
    fun `aSteepNecklineBeyondTheSixPercentBandIsRejected`() {
        // §13 S6 `[derived]` 6.0 %: `Q2 = 640` gives `(40/32) × 62 / 600 × 100 = 12.92 %`. The spec's own
        // walk-through rejected 640 for exactly this reason before it flattened `Q2` to 610.
        val series = inverseSeries(necklinePoint2 = 640.0)
        assertTrue(
            detector.detect(series.pivots, series.candles).isEmpty(),
            "a 12.9 % neckline tilt is a different structure",
        )
    }

    @Test
    fun `aBearTrapReducesTheScoreAgainstTheSameFormationWithoutTheFailure`() {
        // §13 I3 — a close above the neckline then back below it inside TRAP_WINDOW bars.
        val clean = inverseSeries()
        val trapped = inverseSeries(closeAfterBreak = listOf(612.0, 615.0, 612.0, 610.0, 608.0))

        val cleanScore = detector.detect(clean.pivots, clean.candles).single().score
        val trappedMatch = detector.detect(trapped.pivots, trapped.candles).single()
        val trappedCandidate = singleCandidate(trapped)

        assertTrue(
            trappedCandidate.penaltyNames().contains("BEAR_TRAP"),
            "§13 I3 must fire; got ${trappedCandidate.penaltyNames()}",
        )
        assertTrue(trappedMatch.score < cleanScore, "the trap must lower the score")
        val withoutPenalties = FactorScorer.combine(
            FactorScorer.score(trappedCandidate.measures),
            emptyList(),
        )
        assertTrue(withoutPenalties.total > trappedMatch.score)
    }

    @Test
    fun `aFailedRetestAfterTheBreakIsPenalised`() {
        // §13's mirror of §12.3 I5: price fell back to the neckline and closed back through it.
        val series = inverseSeries(closeAfterBreak = listOf(600.0, 605.0, 600.0, 598.0, 596.0))
        val candidate = singleCandidate(series)
        assertTrue(
            candidate.penaltyNames().contains("FAILED_RETEST"),
            "the failed retest must be penalised; got ${candidate.penaltyNames()}",
        )
        assertEquals(0.0, candidate.measure("C4 retestHeld"), 1e-12)
    }

    @Test
    fun `aFailureThatReTakesTheLowIsAFailedPattern`() {
        // §13 I4 — the mirror of the book's "failed Head & Shoulders".
        val series = inverseSeries(closeAfterBreak = listOf(640.0, 600.0, 500.0, 460.0, 440.0))
        val candidate = singleCandidate(series)
        assertTrue(
            candidate.penaltyNames().contains("FAILED_PATTERN"),
            "§13 I4 must fire; got ${candidate.penaltyNames()}",
        )
    }

    @Test
    fun `aStillFormingInverseHeadAndShouldersCarriesNoMeasuredMove`() {
        // §11.1's FORMING row.
        val series = inverseSeries(closeAtBreak = 600.0, breakoutPivot = false, closeAfterBreak = List(9) { 600.0 })
        val match = detector.detect(series.pivots, series.candles).single()
        assertNull(match.measuredMove, "§11.1: FORMING carries no measured move")
        assertEquals(0.0, singleCandidate(series).measure("C1 breakoutDepth"), 1e-12)
    }

    // -------------------------------------------------------------------------------- helpers

    private fun inverseSeries(
        leftShoulder: Double = 505.0,
        head: Double = 470.0,
        rightShoulder: Double = 495.0,
        necklinePoint2: Double = 610.0,
        closeAtBreak: Double = 640.0,
        breakoutPivot: Boolean = true,
        closeAfterBreak: List<Double>? = null,
    ): BuiltSeries {
        val lastBar = 115
        val builder = ChartSeriesBuilder(bars = lastBar + 1)
            // §13 P3 needs a clear prior **downtrend**: `priorTrendPct(30) = (505 − 660) / 660 = −23.5 %`.
            .at(0, 700.0)
            .at(17, 660.0)
            .pivot(30, leftShoulder, PivotKind.LOW)
            .pivot(44, 600.0, PivotKind.HIGH)
            .pivot(60, head, PivotKind.LOW)
            .pivot(76, necklinePoint2, PivotKind.HIGH)
            .pivot(92, rightShoulder, PivotKind.LOW)
            // Walk up to the neckline without closing through it, then break.
            .at(103, 600.0)
            .at(104, closeAtBreak)
            .at(lastBar, closeAfterBreak?.lastOrNull() ?: 660.0)
            // §13's mirror of the §12.2 S10 volume sequence: the decline's low carries the most volume.
            .volumeAt(30, 2_000_000L)
            .volumeAt(60, 1_600_000L)
            .volumeAt(92, 2_000_000L)
            .volumeAt(104, 4_000_000L)
            .deliveryEvery(50.0)
            .deliveryAt(104, 62.0)
        if (breakoutPivot) builder.pivot(104, closeAtBreak, PivotKind.HIGH)
        if (closeAfterBreak != null) {
            closeAfterBreak.forEachIndexed { index, price -> builder.closeAt(105 + index, price) }
        }
        return builder.build()
    }

    private fun singleCandidate(series: BuiltSeries): ChartCandidate {
        val context = ChartContext.of(series.candles)
        return ChartAssertions.single(
            ShouldersEngine.candidates("Inverse Head & Shoulders", top = false, context, context.confirmed(series.pivots)),
            "Inverse Head & Shoulders",
        )
    }
}