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
 * Detector 8 — `Double Top`, on `patterns-spec.md` §14.7's worked fixture.
 *
 * | role | bar | price | kind |
 * |---|---|---|---|
 * | `P1` | 24 | 1,200 | HIGH |
 * | `V`  | 48 | 1,050 | LOW |
 * | `P2` | 66 | 1,192 | HIGH |
 * | — | 80 | 1,035 | LOW (breakdown bar) |
 *
 * The book's measured move is verbatim: "Measure: Peak ↓ Support. Peak ₹1,200, Support ₹1,050,
 * Difference ₹150, Breakdown ₹1,050, Estimated target ₹900."
 */
class DoubleTopDetectorTest {

    private val detector = DoubleTopDetector()

    @Test
    fun `the_specs_worked_fixture_is_detected_with_its_exact_measured_move`() {
        val series = doubleTopSeries()
        val matches = detector.detect(series.pivots, series.candles)

        assertEquals(1, matches.size, "one formation in the §14.7 fixture")
        val match = matches.single()
        assertEquals("Double Top", match.name)
        assertEquals(PatternIds.of("Double Top"), match.patternId)
        ChartAssertions.nameIsCanonical(match.name)

        assertEquals(listOf(24, 48, 66), match.pivots.map { it.barIndex })
        assertEquals(listOf(PivotKind.HIGH, PivotKind.LOW, PivotKind.HIGH), match.pivots.map { it.kind })
        ChartAssertions.pivotsAreRealElements(match, series.pivots)
        ChartAssertions.pivotsAreOrderedOldestFirst(match)
        ChartAssertions.scoreIsTheBreakdownTotal(match)

        val measured = assertNotNull(match.measuredMove)
        assertEquals(150.000, measured.height, 1e-6, "§14.4: height = max(P1,P2) − V")
        assertEquals(1050.000, measured.triggerLevel, 1e-6, "§14.2 S4: the confirmation level is V.price")
        assertEquals(885.000, measured.projectedLevel, 1e-6, "§14.4: 1,035 − 150 = 885")
        assertEquals(Direction.DOWN, measured.direction)
        ChartAssertions.measuredMoveIsOnTheCorrectSide(match)
        assertTrue(match.measuredMove!!.projectedLevel < 1050.0, "the projection sits below the confirmation level")
    }

    @Test
    fun `theSpecsShapeRulesAllHoldOnTheFixture`() {
        val candidate = singleCandidate(doubleTopSeries())
        // §14.7 S1 — `sym(1200, 1192) = 0.99333`.
        assertEquals(0.993333, candidate.measure("G1 symPeaks"), 1e-5)
        assertEquals(0.993333, candidate.measure("G3 peakSym"), 1e-5)
        // §14.7 S2 — `1200 − 1050 = 150 ≥ 2 × ATR14(48)`.
        assertTrue(candidate.measure("G2 troughDepthAtr") > 0.0, "a meaningful trough is required by S2")
        // §14.7 S3 — separation 42, inside [5, 80] daily.
        assertEquals(42.0 / 42.0, candidate.measure("S5 durationFit"), 1e-12)
        // §14.7 S5 — `volume[P2] = 0.82 × volume[P1]`, so the volume-shrink measure reads 1.
        assertEquals(1.0, candidate.measure("volumeShrink (S5)"), 1e-12)
        // §14.7 P3 — `priorTrendPct(24) = (1200 − 1000) / 1000 × 100 = +20.000 %`.
        assertEquals(20.0, candidate.measure("X1 priorTrendPct"), 1e-9)
    }

    @Test
    fun `aDoubleTopCarriesNoFailureModePenaltiesOnTheTextbookFixture`() {
        val candidate = singleCandidate(doubleTopSeries())
        assertEquals(emptyList(), candidate.penaltyNames(), "§14.7's fixture satisfies every §14.3 rule")
    }

    @Test
    fun `everyFactorIsScoredAndTheTotalIsTheMatchScore`() {
        val series = doubleTopSeries()
        val candidate = singleCandidate(series)
        val match = detector.detect(series.pivots, series.candles).single()
        ChartAssertions.everyFactorIsScored(candidate)
        val applied = FactorScorer.apply(FactorScorer.score(candidate.measures), candidate.penalties)
        ChartAssertions.breakdownIs(match, applied.breakdown.geometry, applied.breakdown.structure,
            applied.breakdown.confirmation, applied.breakdown.context)
        ChartAssertions.verdictIsConsistent(match, series.candles.size)
    }

    @Test
    fun `aDoubleTopIsNeverAlsoADoubleBottom`() {
        val series = doubleTopSeries()
        val bottoms = DoubleBottomDetector().detect(series.pivots, series.candles)
        assertTrue(
            bottoms.none { it.pivots.map { p -> p.barIndex } == listOf(24, 48, 66) },
            "the §14.7 window is HIGH LOW HIGH, which is not the double-bottom role sequence",
        )
    }

    // ------------------------------------------------------------------------------ near misses

    @Test
    fun `theSpecsOwnNearMissIsRejectedOutright`() {
        // §14.7's closing note: "**Near-miss fixture (must be rejected):** `P2 = 1,140` ⇒
        // `sym = 1140/1200 = 0.9500 < 0.97` ⇒ no match. That is the difference between a double top and
        // two unrelated highs."
        val series = doubleTopSeries(secondPeak = 1140.0)
        assertTrue(
            detector.detect(series.pivots, series.candles).isEmpty(),
            "§14.7: two peaks 5 % apart are a range, not a double top",
        )
    }

    @Test
    fun `aSecondPeakJustInsideTheEqualityFloorMatchesButScoresLower`() {
        // `P2 = 1,160` ⇒ `sym = 1160/1200 = 0.9667`… below the floor. `P2 = 1,170` ⇒ `0.9750`, inside.
        val text = doubleTopSeries()
        val near = doubleTopSeries(secondPeak = 1170.0)
        val textGeometry = FactorScorer.geometry(singleCandidate(text).measures)
        val nearGeometry = FactorScorer.geometry(singleCandidate(near).measures)
        assertTrue(
            nearGeometry < textGeometry,
            "sym 0.975 must score below sym 0.993: $nearGeometry vs $textGeometry",
        )
        assertTrue(textGeometry - nearGeometry >= 3, "the fall must be material")
    }

    @Test
    fun `twoPeaksInsideOneAtrAreOnePeakAndNotAMatch`() {
        // §14.2 S2 `[derived]` 2 × ATR: "two peaks 0.3 ATR apart are one peak, not two opportunities".
        val series = doubleTopSeries(secondPeak = 1195.0, trough = 1190.0)
        assertTrue(
            detector.detect(series.pivots, series.candles).isEmpty(),
            "§14.2 S2 rejects a formation with no meaningful trough",
        )
    }

    @Test
    fun `peaksSeparatedByLessThanFiveDailyBarsAreRejected`() {
        // §14.2 S3 `[derived]`: `DT_MIN_SEP_BARS(D1) = 5` — "5 daily bars ≈ one week, the shortest span
        // in which two distinct 'attempts' can be seen".
        val series = doubleTopSeries(secondPeakBar = 27)
        assertTrue(
            detector.detect(series.pivots, series.candles).isEmpty(),
            "§14.2 S3 rejects two 'attempts' three bars apart",
        )
    }

    @Test
    fun `aFormationWithNoPriorRiseIsNotADoubleTop`() {
        // §18-1: "Without a prior decline … it is simply two similar lows" — and the mirror for a top.
        val series = doubleTopSeries(priorBase = 1300.0)
        assertTrue(
            detector.detect(series.pivots, series.candles).isEmpty(),
            "§14.1 P3 requires a clear prior uptrend",
        )
    }

    // -------------------------------------------------------------------- false breakout

    @Test
    fun `aBearTrapReducesTheScoreAgainstTheSameFormationWithoutTheFailure`() {
        val clean = doubleTopSeries()
        val trapped = doubleTopSeries(closeAfterBreak = listOf(1058.0, 1040.0, 1030.0, 1025.0, 1020.0))

        val cleanScore = detector.detect(clean.pivots, clean.candles).single().score
        val trappedMatch = detector.detect(trapped.pivots, trapped.candles).single()
        val trappedCandidate = singleCandidate(trapped)

        assertTrue(
            trappedCandidate.penaltyNames().contains("BEAR_TRAP"),
            "§14.3 I3 must fire; got ${trappedCandidate.penaltyNames()}",
        )
        assertTrue(trappedMatch.score < cleanScore, "the bear trap must lower the score")
        assertTrue(
            FactorScorer.combine(FactorScorer.score(trappedCandidate.measures), emptyList()).total >
                trappedMatch.score,
            "removing the penalty from the very same measures must restore a higher total",
        )
    }

    @Test
    fun `aPullbackThatClosesBackAboveTheLevelIsAFailedRetest`() {
        // §14.3 I6 — "the old support has become new resistance and held".
        val series = doubleTopSeries(closeAfterBreak = listOf(1085.0, 1060.0, 1055.0, 1050.0, 1045.0))
        val candidate = singleCandidate(series)
        assertTrue(
            candidate.penaltyNames().contains("FAILED_RETEST"),
            "§14.3 I6 must fire; got ${candidate.penaltyNames()}",
        )
        assertEquals(0.0, candidate.measure("C4 retestHeld"), 1e-12)
    }

    @Test
    fun `aDoubleTopThatNeverConfirmsAndThenBreaksAboveIsAStructureContradiction`() {
        // §14.3 I4 — "**Without confirmation, there is no completed pattern.**" [Ch.18-3 §False Double Tops]
        val series = doubleTopSeries(
            closeAtBreak = 1090.0,
            breakoutPivot = false,
            closeAfterBreak = listOf(1210.0, 1230.0, 1240.0, 1250.0, 1260.0),
        )
        val candidate = singleCandidate(series)
        assertTrue(
            candidate.penaltyNames().contains("STRUCTURE_CONTRADICTS"),
            "§14.3 I4 must fire; got ${candidate.penaltyNames()}",
        )
    }

    @Test
    fun `aStillFormingDoubleTopCarriesNoMeasuredMove`() {
        val series = doubleTopSeries(closeAtBreak = 1090.0, breakoutPivot = false, closeAfterBreak = List(9) { 1090.0 })
        val match = detector.detect(series.pivots, series.candles).single()
        assertEquals(null, match.measuredMove, "§11.1: FORMING carries no measured move")
        assertEquals(0.0, singleCandidate(series).measure("C1 breakoutDepth"), 1e-12)
    }

    // ------------------------------------------------------------------------------- helpers

    private fun doubleTopSeries(
        firstPeak: Double = 1200.0,
        secondPeak: Double = 1192.0,
        secondPeakBar: Int = 66,
        trough: Double = 1050.0,
        priorBase: Double = 1000.0,
        closeAtBreak: Double = 1035.0,
        breakoutPivot: Boolean = true,
        closeAfterBreak: List<Double>? = null,
    ): BuiltSeries {
        val lastBar = 100
        val builder = ChartSeriesBuilder(bars = lastBar + 1)
            // §14.7 P3: `priorTrendPct(24) = (1,200 − close[4]) / close[4] × 100` with `close[4] = 1,000`.
            .at(0, priorBase)
            .at(4, priorBase)
            .pivot(24, firstPeak, PivotKind.HIGH)
            .pivot(48, trough, PivotKind.LOW)
            .pivot(secondPeakBar, secondPeak, PivotKind.HIGH)
            // Walk down to the confirmation level without closing through it, then break.
            .at(79, 1070.0)
            .at(80, closeAtBreak)
            .at(lastBar, closeAfterBreak?.lastOrNull() ?: 1010.0)
            // §14.7 S5 — `volume[P2 bar] = 0.82 × volume[P1 bar]`, then a 2.4× breakdown bar.
            .volumeAt(24, 2_000_000L)
            .volumeAt(secondPeakBar, 1_600_000L)
            .volumeAt(80, 3_700_000L)
            .deliveryEvery(50.0)
            .deliveryAt(80, 63.0)
        if (breakoutPivot) builder.pivot(80, closeAtBreak, PivotKind.LOW)
        if (closeAfterBreak != null) {
            closeAfterBreak.forEachIndexed { index, price -> builder.closeAt(81 + index, price) }
        }
        return builder.build()
    }

    private fun singleCandidate(series: BuiltSeries): ChartCandidate {
        val context = ChartContext.of(series.candles)
        return ChartAssertions.single(
            DoublePatternEngine.candidates("Double Top", top = true, context, context.confirmed(series.pivots)),
            "Double Top",
        )
    }
}