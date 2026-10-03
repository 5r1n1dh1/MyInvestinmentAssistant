package com.mia.app.domain.pattern.detectors.chart

import com.mia.app.domain.model.Direction
import com.mia.app.domain.model.PivotKind
import com.mia.app.domain.pattern.FactorScorer
import com.mia.app.domain.pattern.PatternIds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Detector 9 — `Double Bottom`, on `patterns-spec.md` §15's worked fixture.
 *
 * The spec notes that this fixture "also reproduces the book's own worked example prices
 * `[Ch.18-3 §Practical Example]` (₹3,000 → ₹2,100 → ₹2,350 → ₹2,120 → breaks above ₹2,350)", so the
 * prior-trend bar starts at 2,900 and the roles are:
 *
 * | role | bar | price | kind |
 * |---|---|---|---|
 * | `B1` | 20 | 2,100 | LOW |
 * | `R`  | 44 | 2,350 | HIGH |
 * | `B2` | 62 | 2,120 | LOW |
 * | — | 76 | 2,390 | HIGH (breakout bar) |
 *
 * Measured move `[Ch.18-5 §Measuring Price Targets]` verbatim: "Double Bottom — Measure: Resistance ↓
 * Bottom. Project upward." `height = 250`, `triggerLevel = 2,350`, `projectedLevel = 2,640`.
 */
class DoubleBottomDetectorTest {

    private val detector = DoubleBottomDetector()

    @Test
    fun `the_specs_worked_fixture_is_detected_with_its_exact_measured_move`() {
        val series = doubleBottomSeries()
        val matches = detector.detect(series.pivots, series.candles)

        assertEquals(1, matches.size, "one formation in the §15 fixture")
        val match = matches.single()
        assertEquals("Double Bottom", match.name)
        assertEquals(PatternIds.of("Double Bottom"), match.patternId)
        ChartAssertions.nameIsCanonical(match.name)

        assertEquals(listOf(20, 44, 62), match.pivots.map { it.barIndex })
        assertEquals(listOf(PivotKind.LOW, PivotKind.HIGH, PivotKind.LOW), match.pivots.map { it.kind })
        ChartAssertions.pivotsAreRealElements(match, series.pivots)
        ChartAssertions.pivotsAreOrderedOldestFirst(match)
        ChartAssertions.scoreIsTheBreakdownTotal(match)

        val measured = assertNotNull(match.measuredMove)
        assertEquals(250.000, measured.height, 1e-6, "§15: height = R − min(B1,B2)")
        assertEquals(2350.000, measured.triggerLevel, 1e-6, "§15: the confirmation level is R.price")
        assertEquals(2640.000, measured.projectedLevel, 1e-6, "§15: 2,390 + 250 = 2,640")
        assertEquals(Direction.UP, measured.direction)
        ChartAssertions.measuredMoveIsOnTheCorrectSide(match)
    }

    @Test
    fun `theSpecsShapeRulesAllHoldOnTheFixture`() {
        val candidate = singleCandidate(doubleBottomSeries())
        // §15 S1 — `sym(2100, 2120) = 0.99057`.
        assertEquals(0.990566, candidate.measure("G1 symPeaks"), 1e-5)
        // §15 P3 — `priorTrendPct(20) = (2,100 − 2,900) / 2,900 × 100 = −27.586 %`, and §6 X1 scores
        // it in the pattern's direction, i.e. as a magnitude.
        assertEquals(27.586, candidate.measure("X1 priorTrendPct"), 1e-2)
        assertTrue(candidate.measure("G2 troughDepthAtr") > 0.0, "§15 S2 requires a meaningful peak")
        // §15 S5 — "first low high selling volume, second low lower selling volume".
        assertEquals(1.0, candidate.measure("volumeShrink (S5)"), 1e-12)
    }

    @Test
    fun `aDoubleBottomCarriesNoFailureModePenaltiesOnTheTextbookFixture`() {
        val candidate = singleCandidate(doubleBottomSeries())
        assertEquals(emptyList(), candidate.penaltyNames(), "§15's fixture satisfies every §15 rule")
    }

    @Test
    fun `aDoubleBottomIsNeverAlsoADoubleTop`() {
        val series = doubleBottomSeries()
        val tops = DoubleTopDetector().detect(series.pivots, series.candles)
        assertTrue(
            tops.none { it.pivots.map { p -> p.barIndex } == listOf(20, 44, 62) },
            "the §15 window is LOW HIGH LOW, which is not the double-top role sequence",
        )
    }

    @Test
    fun `everyFactorIsScoredAndTheTotalIsTheMatchScore`() {
        val series = doubleBottomSeries()
        val candidate = singleCandidate(series)
        val match = detector.detect(series.pivots, series.candles).single()
        ChartAssertions.everyFactorIsScored(candidate)
        val applied = FactorScorer.apply(FactorScorer.score(candidate.measures), candidate.penalties)
        ChartAssertions.breakdownIs(match, applied.breakdown.geometry, applied.breakdown.structure,
            applied.breakdown.confirmation, applied.breakdown.context)
        ChartAssertions.verdictIsConsistent(match, series.candles.size)
    }

    @Test
    fun `twoBottomsFivePercentApartAreNotADoubleBottom`() {
        // §14.2 S1's mirror, §15's `DT_PEAK_SYM = 0.97`: `B2 = 2,240` ⇒ `sym = 2100/2240 = 0.9375`.
        val series = doubleBottomSeries(secondBottom = 2240.0)
        assertTrue(
            detector.detect(series.pivots, series.candles).isEmpty(),
            "§15 S1: two bottoms 6 % apart are a range, not a double bottom",
        )
    }

    @Test
    fun `aSecondBottomJustInsideTheEqualityFloorMatchesButScoresLower`() {
        val text = doubleBottomSeries()
        val near = doubleBottomSeries(secondBottom = 2160.0)
        val textGeometry = FactorScorer.geometry(singleCandidate(text).measures)
        val nearGeometry = FactorScorer.geometry(singleCandidate(near).measures)
        assertTrue(
            nearGeometry < textGeometry,
            "sym 0.972 must score below sym 0.991: $nearGeometry vs $textGeometry",
        )
    }

    @Test
    fun `aFormationWithNoPriorFallIsNotADoubleBottom`() {
        // §18-1: "Without a prior decline [a double bottom] is simply two similar lows."
        val series = doubleBottomSeries(priorBase = 2000.0)
        assertTrue(
            detector.detect(series.pivots, series.candles).isEmpty(),
            "§15 P3 requires a clear prior downtrend",
        )
    }

    @Test
    fun `aBullTrapReducesTheScoreAgainstTheSameFormationWithoutTheFailure`() {
        val clean = doubleBottomSeries()
        val trapped = doubleBottomSeries(closeAfterBreak = listOf(2342.0, 2360.0, 2370.0, 2375.0, 2380.0))

        val cleanScore = detector.detect(clean.pivots, clean.candles).single().score
        val trappedMatch = detector.detect(trapped.pivots, trapped.candles).single()
        val trappedCandidate = singleCandidate(trapped)

        assertTrue(
            trappedCandidate.penaltyNames().contains("BULL_TRAP"),
            "§15 I3 must fire; got ${trappedCandidate.penaltyNames()}",
        )
        assertTrue(trappedMatch.score < cleanScore, "the bull trap must lower the score")
    }

    @Test
    fun `aStillFormingDoubleBottomCarriesNoMeasuredMove`() {
        val series = doubleBottomSeries(
            closeAtBreak = 2330.0,
            breakoutPivot = false,
            closeAfterBreak = List(9) { 2330.0 },
        )
        val match = detector.detect(series.pivots, series.candles).single()
        assertEquals(null, match.measuredMove, "§11.1: FORMING carries no measured move")
        assertEquals(0.0, singleCandidate(series).measure("C1 breakoutDepth"), 1e-12)
    }

    // ------------------------------------------------------------------------------- helpers

    private fun doubleBottomSeries(
        firstBottom: Double = 2100.0,
        secondBottom: Double = 2120.0,
        peak: Double = 2350.0,
        priorBase: Double = 2900.0,
        closeAtBreak: Double = 2390.0,
        breakoutPivot: Boolean = true,
        closeAfterBreak: List<Double>? = null,
    ): BuiltSeries {
        val lastBar = 95
        val builder = ChartSeriesBuilder(bars = lastBar + 1)
            // §15 P3 — `priorTrendPct(20) = (2,100 − close[0]) / close[0] × 100 = −27.586 %`.
            .at(0, priorBase)
            .at(4, priorBase)
            .pivot(20, firstBottom, PivotKind.LOW)
            .pivot(44, peak, PivotKind.HIGH)
            .pivot(62, secondBottom, PivotKind.LOW)
            // Walk up to the confirmation level without closing through it, then break.
            .at(75, 2330.0)
            .at(76, closeAtBreak)
            .at(lastBar, closeAfterBreak?.lastOrNull() ?: 2420.0)
            .volumeAt(20, 2_000_000L)
            .volumeAt(62, 1_600_000L)
            .volumeAt(76, 3_600_000L)
            .deliveryEvery(50.0)
            .deliveryAt(76, 64.0)
        if (breakoutPivot) builder.pivot(76, closeAtBreak, PivotKind.HIGH)
        if (closeAfterBreak != null) {
            closeAfterBreak.forEachIndexed { index, price -> builder.closeAt(77 + index, price) }
        }
        return builder.build()
    }

    private fun singleCandidate(series: BuiltSeries): ChartCandidate {
        val context = ChartContext.of(series.candles)
        return ChartAssertions.single(
            DoublePatternEngine.candidates("Double Bottom", top = false, context, context.confirmed(series.pivots)),
            "Double Bottom",
        )
    }
}