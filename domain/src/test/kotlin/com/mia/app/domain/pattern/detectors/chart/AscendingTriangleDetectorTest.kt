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
 * Detector 10 — `Ascending Triangle`, on `patterns-spec.md` §16.7's worked fixture.
 *
 * | role | bar | price | kind |
 * |---|---|---|---|
 * | `L₀` | 30 | 900 | LOW |
 * | `H₁` | 42 | 1,000 | HIGH |
 * | `L₁` | 54 | 918 | LOW |
 * | `H₂` | 66 | 1,004 | HIGH |
 * | `L₂` | 78 | 938 | LOW |
 * | `H₃` | 90 | 998 | HIGH |
 * | `L₃` | 102 | 960 | LOW |
 * | — | 114 | 1,015 | HIGH (breakout bar) |
 *
 * `m = 3`, `resistance = (1000 + 1004 + 998)/3 = 1,000.6667`, `ref = 100.6667`,
 * `triggerLevel = 1,000.6667`, `projectedLevel = 1,115.6667`. The measured move is **DD-4**, the one
 * v1 construction the book does not state verbatim.
 */
class AscendingTriangleDetectorTest {

    private val detector = AscendingTriangleDetector()

    @Test
    fun `the_specs_worked_fixture_is_detected_with_its_exact_measured_move`() {
        val series = triangleSeries()
        val matches = detector.detect(series.pivots, series.candles)

        assertEquals(1, matches.size, "one formation in the §16.7 fixture")
        val match = matches.single()
        assertEquals("Ascending Triangle", match.name)
        assertEquals(PatternIds.of("Ascending Triangle"), match.patternId)
        ChartAssertions.nameIsCanonical(match.name)

        // §16.1 P2 — `2m + 1` consecutive pivots starting **and** ending at a LOW.
        assertEquals(listOf(30, 42, 54, 66, 78, 90, 102), match.pivots.map { it.barIndex })
        assertEquals(PivotKind.LOW, match.pivots.first().kind)
        assertEquals(PivotKind.LOW, match.pivots.last().kind)
        ChartAssertions.pivotsAreRealElements(match, series.pivots)
        ChartAssertions.pivotsAreOrderedOldestFirst(match)
        ChartAssertions.scoreIsTheBreakdownTotal(match)

        val measured = assertNotNull(match.measuredMove)
        assertEquals(100.6667, measured.height, 1e-3, "DD-4: height = resistance − lowestLow")
        assertEquals(1000.6667, measured.triggerLevel, 1e-3, "the flat side is the trigger")
        assertEquals(1115.6667, measured.projectedLevel, 1e-3, "DD-4: 1,015 + 100.6667 = 1,115.6667")
        assertEquals(Direction.UP, measured.direction)
        ChartAssertions.measuredMoveIsOnTheCorrectSide(match)
    }

    @Test
    fun `theSpecsShapeRulesAllHoldOnTheFixture`() {
        val candidate = singleCandidate(triangleSeries())
        // §16.7 S1 — `resTolPct = 3.333 / 1,000.667 × 100 = 0.3331 %`, inside `FLAT_BAND_PCT = 1.50 %`,
        // and §3's triangle row reads G1 as `triSym = 1 − resTolPct / FLAT_BAND_PCT` = 0.7779.
        assertEquals(0.777867, candidate.measure("G1 triSym"), 1e-4)
        // §16.7 S2 — `918 > 900`, `938 > 918`, `960 > 938`, so `monoScore = 1.0`.
        assertEquals(1.0, candidate.measure("G6 monoScore"), 1e-12)
        // §16.7 S3 — `progressPct(L₀ → L₃) = (960 − 900)/72 / 900 × 100 = 0.09259 %/bar`, so the whole
        // floor rose 6.667 % over the formation, which is `scoring-spec.md` §3.1's `risePct`.
        assertEquals(6.666667, candidate.measure("G3 risePct"), 1e-5)
        // §4 S6 — "the third touch validates it": `m = 3` reads 100.
        assertEquals(1.0, candidate.measure("S6 touchCount"), 1e-12)
        // §16.7 P3 — `priorTrendPct(30) = (900 − 780) / 780 × 100 = +15.385 %`.
        assertEquals(15.384615, candidate.measure("X1 priorTrendPct"), 1e-4)
    }

    @Test
    fun `theSpecsVolumeReadingsAreReproduced`() {
        val candidate = singleCandidate(triangleSeries())
        // §16.7 I4 — `volumeContraction = 780,000 / 1,050,000 = 0.743`, at or below the 0.80 "ideal".
        val contraction = candidate.measure("C8 volumeContraction")
        assertEquals(0.742857, contraction, 1e-4)
        assertTrue(contraction <= ChartSpec.VOLUME_CONTRACTION_IDEAL, "§3.8: 0.80 is the ideal contraction")
        // §16.7 — `rvol[114] = 3,400,000 / 1,200,000 = 2.833`, which confirms.
        assertTrue(candidate.measure("C2 rvolBreakout") >= ChartSpec.RVOL_CONFIRM)
    }

    @Test
    fun `anAscendingTriangleCarriesNoFailureModePenaltiesOnTheTextbookFixture`() {
        val candidate = singleCandidate(triangleSeries())
        assertEquals(emptyList(), candidate.penaltyNames(), "§16.7's fixture satisfies every §16.3 rule")
    }

    @Test
    fun `anAscendingTriangleIsNeverAlsoADescendingTriangle`() {
        val series = triangleSeries()
        val falling = DescendingTriangleDetector().detect(series.pivots, series.candles)
        assertTrue(
            falling.none { match -> match.pivots.map { it.barIndex } == series.pivots.map { it.barIndex } },
            "the §16.7 window rises into a flat ceiling, which is not a descending triangle",
        )
    }

    @Test
    fun `everyFactorIsScoredAndTheTotalIsTheMatchScore`() {
        val series = triangleSeries()
        val candidate = singleCandidate(series)
        val match = detector.detect(series.pivots, series.candles).single()
        ChartAssertions.everyFactorIsScored(candidate)
        val applied = FactorScorer.apply(FactorScorer.score(candidate.measures), candidate.penalties)
        ChartAssertions.breakdownIs(match, applied.breakdown.geometry, applied.breakdown.structure,
            applied.breakdown.confirmation, applied.breakdown.context)
        ChartAssertions.verdictIsConsistent(match, series.candles)
    }

    // ------------------------------------------------------------------------------ near misses

    @Test
    fun `aFlatSideWiderThanTheBandFailsS1AtEveryWindowLength`() {
        // §16.7's closing note: three peaks 2 % apart is a range, not an ascending triangle. `H₂ = 1,040`
        // is used rather than the spec's 1,030 because with only **two** touches `max|H_i − mean|`
        // is half the peak-to-peak spread, so `H₁, H₂ = 1,000, 1,030` sits 1.477 % from its own mean and
        // passes — see the next test, which documents that consequence. At `H₂ = 1,040` the two-touch
        // window reads 1.961 %, the three-touch window 2.699 % and the four-touch window is impossible,
        // so **every** window length fails §16.2 S1 and there is no match at all.
        val series = triangleSeries(secondHigh = 1040.0)
        assertTrue(
            detector.detect(series.pivots, series.candles).isEmpty(),
            "§16.2 S1: three peaks 2 % apart are a range",
        )
    }

    @Test
    fun `withExactlyTwoTouchesTheSpecFormulaMeasuresHalfTheSpreadAndThatIsReported`() {
        // A documented consequence of §16.2 S1 as written, asserted rather than hidden. `resTolPct` is
        // `max|H_i − resistance|` over a `resistance` that is the **mean** of the touches, so with
        // `m = 2` the maximum deviation is exactly half the spread and the effective tolerance is
        // twice `FLAT_BAND_PCT`. The spec's own near-miss (`H₂ = 1,030`) therefore rejects the
        // three-touch window (2.047 %) while the two-touch window `H₁, H₂` still qualifies (1.477 %),
        // and the detector reports the shorter — earlier — formation.
        val series = triangleSeries(secondHigh = 1030.0)
        val matches = detector.detect(series.pivots, series.candles)
        assertEquals(1, matches.size, "the two-touch sub-window survives")
        assertEquals(listOf(30, 42, 54, 66, 78), matches.single().pivots.map { it.barIndex })
        // `triSym = 1 − resTolPct / FLAT_BAND_PCT` with `resTolPct = 15 / 1,015 × 100 = 1.4778 %`.
        assertEquals(0.014780, singleCandidate(series).measure("G1 triSym"), 1e-4)
    }

    @Test
    fun `aFlatSideJustInsideTheBandMatchesButLosesGeometry`() {
        // `H₂ = 1,020` ⇒ `resistance = 1,006`, `resTolPct = 1.393 %`, inside the 1.50 % band but well
        // worse than the textbook 0.333 %.
        val text = triangleSeries()
        val near = triangleSeries(secondHigh = 1020.0)
        val textGeometry = FactorScorer.geometry(singleCandidate(text).measures)
        val nearGeometry = FactorScorer.geometry(singleCandidate(near).measures)
        assertTrue(
            nearGeometry < textGeometry,
            "a 1.39 % flat band must score below 0.33 %: $nearGeometry vs $textGeometry",
        )
    }

    @Test
    fun `lowsThatDoNotAllRiseAreNotAnAscendingTriangle`() {
        // §16.2 S2 — "Higher Lows. Each correction ends higher." `L₁ = 900 = L₀` is a flat step, and
        // §16.2 S2 is **strictly** `L₁ > L₀`, so the formation is not an ascending triangle.
        val series = triangleSeries(secondLow = 900.0)
        assertTrue(
            detector.detect(series.pivots, series.candles).isEmpty(),
            "§16.2 S2 requires every low to be strictly higher than the one before it",
        )
    }

    @Test
    fun `aRisingFloorThatIsTooShallowIsNotAnAscendingTriangle`() {
        // §16.2 S3 `[derived]` 0.05 %/bar: `L₃ = 920` gives `(920 − 900)/72 / 900 × 100 = 0.0309 %/bar`.
        val series = triangleSeries(
            secondLow = 908.0,
            thirdLow = 914.0,
            lastLow = 920.0,
        )
        assertTrue(
            detector.detect(series.pivots, series.candles).isEmpty(),
            "§16.2 S3 requires a real, rising floor",
        )
    }

    @Test
    fun `aFormationWithNoPriorRiseIsNotAnAscendingTriangle`() {
        // §16.1 P3 / §18-4 §Professional Checklist #1.
        val series = triangleSeries(priorBase = 940.0)
        assertTrue(
            detector.detect(series.pivots, series.candles).isEmpty(),
            "§16.1 P3: a continuation pattern needs the trend before it",
        )
    }

    // -------------------------------------------------------------------- failure modes

    @Test
    fun `aBullTrapReducesTheScoreAgainstTheSameFormationWithoutTheFailure`() {
        val clean = triangleSeries()
        val trapped = triangleSeries(closeAfterBreak = listOf(998.0, 1010.0, 1020.0, 1030.0, 1040.0))

        val cleanScore = detector.detect(clean.pivots, clean.candles).single().score
        val trappedMatch = detector.detect(trapped.pivots, trapped.candles).single()
        val trappedCandidate = singleCandidate(trapped)
        assertTrue(
            trappedCandidate.penaltyNames().contains("BULL_TRAP"),
            "§16.3 I3 must fire; got ${trappedCandidate.penaltyNames()}",
        )
        assertTrue(trappedMatch.score < cleanScore, "the bull trap must lower the score")
    }

    @Test
    fun `volumeThatExpandedThroughTheFormationIsPenalised`() {
        // §16.3 I4 — "Unhealthy continuation: high volume during correction … may indicate
        // institutional selling" [Ch.18-4 §Role of Volume].
        val series = triangleSeries(formationVolume = 2_000_000L, baselineVolume = 1_000_000L)
        val candidate = singleCandidate(series)
        assertTrue(
            candidate.penaltyNames().contains("VOLUME_CONTRACTION_ABSENT"),
            "§16.3 I4 must fire; got ${candidate.penaltyNames()}",
        )
    }

    @Test
    fun `aTriangleWithNoBreakoutForMoreThanTenBarsIsStale`() {
        // §16.3 I5 — "no breakout within 10 bars of the last touch → triangle is expiring".
        val series = triangleSeries(closeAtBreak = 980.0, breakoutPivot = false, closeAfterBreak = List(11) { 980.0 })
        val candidate = singleCandidate(series)
        assertTrue(
            candidate.penaltyNames().contains("STALE_PATTERN"),
            "§16.3 I5 must fire; got ${candidate.penaltyNames()}",
        )
    }

    @Test
    fun `aStillFormingAscendingTriangleCarriesNoMeasuredMove`() {
        val series = triangleSeries(closeAtBreak = 990.0, breakoutPivot = false, closeAfterBreak = List(9) { 990.0 })
        val match = detector.detect(series.pivots, series.candles).single()
        assertEquals(null, match.measuredMove, "§11.1: FORMING carries no measured move")
        assertEquals(0.0, singleCandidate(series).measure("C1 breakoutDepth"), 1e-12)
    }

    // ------------------------------------------------------------------------------- helpers

    private fun triangleSeries(
        priorBase: Double = 780.0,
        firstHigh: Double = 1000.0,
        secondHigh: Double = 1004.0,
        thirdHigh: Double = 998.0,
        firstLow: Double = 900.0,
        secondLow: Double = 918.0,
        thirdLow: Double = 938.0,
        lastLow: Double = 960.0,
        closeAtBreak: Double = 1015.0,
        breakoutPivot: Boolean = true,
        closeAfterBreak: List<Double>? = null,
        formationVolume: Long = 780_000L,
        baselineVolume: Long = 1_050_000L,
    ): BuiltSeries {
        val lastBar = 125
        val builder = ChartSeriesBuilder(bars = lastBar + 1)
            // §16.7 P3 — `priorTrendPct(30) = (900 − close[10]) / close[10] × 100` with `close[10] = 780`.
            .at(0, priorBase)
            .at(10, priorBase)
            .pivot(30, firstLow, PivotKind.LOW)
            .pivot(42, firstHigh, PivotKind.HIGH)
            .pivot(54, secondLow, PivotKind.LOW)
            .pivot(66, secondHigh, PivotKind.HIGH)
            .pivot(78, thirdLow, PivotKind.LOW)
            .pivot(90, thirdHigh, PivotKind.HIGH)
            .pivot(102, lastLow, PivotKind.LOW)
            // Walk up to the flat side without closing through it, then break.
            .at(113, 995.0)
            .at(114, closeAtBreak)
            .at(lastBar, closeAfterBreak?.lastOrNull() ?: 1040.0)
            // §16.7 I4 — the 20 bars before bar 30 carry 1,050,000 and the formation carries 780,000.
            .volumes(10, 29, baselineVolume)
            .volumes(30, 102, formationVolume)
            .volumeAt(114, 3_400_000L)
            .deliveryEvery(50.0)
            .deliveryAt(114, 65.0)
        if (breakoutPivot) builder.pivot(114, closeAtBreak, PivotKind.HIGH)
        if (closeAfterBreak != null) {
            closeAfterBreak.forEachIndexed { index, price -> builder.closeAt(115 + index, price) }
        }
        return builder.build()
    }

    private fun singleCandidate(series: BuiltSeries): ChartCandidate {
        val context = ChartContext.of(series.candles)
        return ChartAssertions.single(
            TriangleEngine.candidates("Ascending Triangle", ascending = true, context, context.confirmed(series.pivots)),
            "Ascending Triangle",
        )
    }
}