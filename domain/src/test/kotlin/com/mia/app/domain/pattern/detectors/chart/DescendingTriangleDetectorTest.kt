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
 * Detector 11 — `Descending Triangle`, on `patterns-spec.md` §17.7's worked fixture.
 *
 * | role | bar | price | kind |
 * |---|---|---|---|
 * | `H₀` | 30 | 1,320 | HIGH |
 * | `L₁` | 42 | 1,200 | LOW |
 * | `H₁` | 54 | 1,304 | HIGH |
 * | `L₂` | 66 | 1,196 | LOW |
 * | `H₂` | 78 | 1,288 | HIGH |
 * | `L₃` | 90 | 1,204 | LOW |
 * | — | 102 | 1,186 | LOW (breakdown bar) |
 *
 * `m = 3`, `support = (1200 + 1196 + 1204)/3 = 1,200.00`, `highestHigh = 1,320`, `ref = 120.00`,
 * `projectedLevel = 1,186 − 120 = 1,066.00`.
 *
 * **The window shape.** §17's prose says "Window ends at a `HIGH`" while its own enumeration and its
 * §17.7 fixture both end at a `LOW`. The detector accepts both readings, so the fixture below is the
 * spec's six-pivot form and the odd, mirror form is exercised separately.
 */
class DescendingTriangleDetectorTest {

    private val detector = DescendingTriangleDetector()

    @Test
    fun `the_specs_worked_fixture_is_detected_with_its_exact_measured_move`() {
        val series = triangleSeries()
        val matches = detector.detect(series.pivots, series.candles)

        assertEquals(1, matches.size, "one formation in the §17.7 fixture")
        val match = matches.single()
        assertEquals("Descending Triangle", match.name)
        assertEquals(PatternIds.of("Descending Triangle"), match.patternId)
        ChartAssertions.nameIsCanonical(match.name)

        assertEquals(listOf(30, 42, 54, 66, 78, 90), match.pivots.map { it.barIndex })
        assertEquals(PivotKind.HIGH, match.pivots.first().kind)
        ChartAssertions.pivotsAreRealElements(match, series.pivots)
        ChartAssertions.pivotsAreOrderedOldestFirst(match)
        ChartAssertions.scoreIsTheBreakdownTotal(match)

        val measured = assertNotNull(match.measuredMove)
        assertEquals(120.000, measured.height, 1e-6, "DD-4 mirror: height = highestHigh − support")
        assertEquals(1200.000, measured.triggerLevel, 1e-6, "the flat side is the trigger")
        assertEquals(1066.000, measured.projectedLevel, 1e-6, "DD-4 mirror: 1,186 − 120 = 1,066")
        assertEquals(Direction.DOWN, measured.direction)
        ChartAssertions.measuredMoveIsOnTheCorrectSide(match)
    }

    @Test
    fun `theSpecsShapeRulesAllHoldOnTheFixture`() {
        val candidate = singleCandidate(triangleSeries())
        // §17.7 S1 — `max|L_i − 1,200| / 1,200 × 100 = 0.3333 %`, inside `FLAT_BAND_PCT`.
        assertEquals(1.0 - 0.333333 / ChartSpec.flatBandPct(com.mia.app.domain.model.Timeframe.D1), candidate.measure("G1 triSym"), 1e-4)
        // §17.7 S2 — `1304 < 1320`, `1288 < 1304`.
        assertEquals(1.0, candidate.measure("G6 monoScore"), 1e-12)
        // §17.7 S3 — `progressPct(H₀ → H₂) = (1288 − 1320)/48 / 1320 × 100 = −0.05051 %/bar`, and the
        // spec's own bar is `≤ −0.05`.
        assertEquals(2.424242, candidate.measure("G3 risePct"), 1e-5)
        // §17.7 P3 — `priorTrendPct(30) = (1320 − 1480) / 1480 × 100 = −10.811 %`.
        assertEquals(10.810811, candidate.measure("X1 priorTrendPct"), 1e-4)
    }

    @Test
    fun `aDescendingTriangleCarriesNoFailureModePenaltiesOnTheTextbookFixture`() {
        val candidate = singleCandidate(triangleSeries())
        assertEquals(emptyList(), candidate.penaltyNames(), "§17.7's fixture satisfies every §17 rule")
    }

    @Test
    fun `aDescendingTriangleIsNeverAlsoAnAscendingTriangle`() {
        val series = triangleSeries()
        val rising = AscendingTriangleDetector().detect(series.pivots, series.candles)
        assertTrue(
            rising.none { match -> match.pivots.map { it.barIndex } == series.pivots.map { it.barIndex } },
            "the §17.7 window falls into a flat floor, which is not an ascending triangle",
        )
    }

    @Test
    fun `theMirrorWindowFormThatEndsAtAHighIsAlsoAccepted`() {
        // §17's prose reads "Window ends at a `HIGH`" and calls the pattern the mirror of §16, i.e.
        // `2m + 1` pivots with `m + 1` highs and `m` lows. Both readings must be recognised so
        // neither can silently lose a formation, and the **longest** window wins when both fit — so
        // this fixture leaves the breakdown bar as an ordinary bar, which makes the seven-pivot
        // (ending HIGH) window the longest one available.
        val series = triangleSeries(
            lastLow = null,
            extraHighBar = 102,
            extraHigh = 1272.0,
            closeAtBreak = 1180.0,
            breakdownBar = 125,
            breakoutPivot = false,
        )
        val match = detector.detect(series.pivots, series.candles).single()
        assertEquals(PivotKind.HIGH, match.pivots.last().kind, "§17's prose form ends at a HIGH")
        assertEquals(listOf(30, 42, 54, 66, 78, 90, 102), match.pivots.map { it.barIndex })
        // `support = mean(1,200, 1,196, 1,204) = 1,200` and `highestHigh = 1,320`, so `ref = 120`.
        val measured = assertNotNull(match.measuredMove)
        assertEquals(120.0, measured.height, 1e-6)
        assertEquals(1200.0, measured.triggerLevel, 1e-6)
        assertEquals(Direction.DOWN, measured.direction)
        assertEquals(1060.0, measured.projectedLevel, 1e-6, "the first close below 1,194, less the 120 height")
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
        ChartAssertions.verdictIsConsistent(match, series.candles.size)
    }

    @Test
    fun `threeLowsTwoPercentApartAreARangeNotADescendingTriangle`() {
        // §16.7's mirrored near-miss: `L₂ = 1,215` widens `resTolPct` past the 1.50 % band.
        val series = triangleSeries(secondLow = 1240.0)
        assertTrue(
            detector.detect(series.pivots, series.candles).isEmpty(),
            "§17 S1: three lows 2 % apart are a range",
        )
    }

    @Test
    fun `highsThatDoNotAllFallAreNotADescendingTriangle`() {
        val series = triangleSeries(secondHigh = 1320.0)
        assertTrue(
            detector.detect(series.pivots, series.candles).isEmpty(),
            "§17 S2 requires every high to be strictly lower than the one before it",
        )
    }

    @Test
    fun `aFallingCeilingThatIsTooShallowIsNotADescendingTriangle`() {
        // §17 S3 `[derived]` −0.05 %/bar: `H₂ = 1,310` gives `(1310 − 1320)/48 / 1320 × 100 = −0.0158`.
        val series = triangleSeries(secondHigh = 1300.0, lastHigh = 1310.0)
        assertTrue(
            detector.detect(series.pivots, series.candles).isEmpty(),
            "§17 S3 requires a real, falling ceiling",
        )
    }

    @Test
    fun `aFormationWithNoPriorFallIsNotADescendingTriangle`() {
        val series = triangleSeries(priorBase = 1300.0)
        assertTrue(
            detector.detect(series.pivots, series.candles).isEmpty(),
            "§17 P3 requires a clear prior downtrend",
        )
    }

    @Test
    fun `aBearTrapReducesTheScoreAgainstTheSameFormationWithoutTheFailure`() {
        val clean = triangleSeries()
        val trapped = triangleSeries(closeAfterBreak = listOf(1204.0, 1210.0, 1215.0, 1220.0, 1225.0))

        val cleanScore = detector.detect(clean.pivots, clean.candles).single().score
        val trappedMatch = detector.detect(trapped.pivots, trapped.candles).single()
        val trappedCandidate = singleCandidate(trapped)
        assertTrue(
            trappedCandidate.penaltyNames().contains("BEAR_TRAP"),
            "§17's I3 must fire; got ${trappedCandidate.penaltyNames()}",
        )
        assertTrue(trappedMatch.score < cleanScore, "the bear trap must lower the score")
    }

    @Test
    fun `aStillFormingDescendingTriangleCarriesNoMeasuredMove`() {
        val series = triangleSeries(
            closeAtBreak = 1210.0,
            breakoutPivot = false,
            closeAfterBreak = List(9) { 1210.0 },
        )
        val match = detector.detect(series.pivots, series.candles).single()
        assertEquals(null, match.measuredMove, "§11.1: FORMING carries no measured move")
        assertEquals(0.0, singleCandidate(series).measure("C1 breakoutDepth"), 1e-12)
    }

    // ------------------------------------------------------------------------------- helpers

    @Suppress("LongParameterList")
    private fun triangleSeries(
        priorBase: Double = 1480.0,
        firstHigh: Double = 1320.0,
        secondHigh: Double = 1304.0,
        lastHigh: Double = 1288.0,
        firstLow: Double = 1200.0,
        secondLow: Double = 1196.0,
        thirdLow: Double = 1204.0,
        lastLow: Double? = 1204.0,
        extraHighBar: Int? = null,
        extraHigh: Double? = null,
        closeAtBreak: Double = 1186.0,
        breakdownBar: Int = 102,
        breakoutPivot: Boolean = true,
        closeAfterBreak: List<Double>? = null,
    ): BuiltSeries {
        val lastBar = 130
        val builder = ChartSeriesBuilder(bars = lastBar + 1)
        // Conditional pivots come **before** the chain: a statement in the middle of a fluent chain
        // silently ends it, and everything after would be applied to nothing.
        if (lastLow != null && lastLow != thirdLow) builder.pivot(102, lastLow, PivotKind.LOW)
        if (extraHighBar != null && extraHigh != null) builder.pivot(extraHighBar, extraHigh, PivotKind.HIGH)
        if (breakoutPivot) builder.pivot(breakdownBar, closeAtBreak, PivotKind.LOW)
        builder
            // §17.7 P3 — `priorTrendPct(30) = (1320 − 1480) / 1480 × 100 = −10.811 %`.
            .at(0, priorBase)
            .at(10, priorBase)
            .pivot(30, firstHigh, PivotKind.HIGH)
            .pivot(42, firstLow, PivotKind.LOW)
            .pivot(54, secondHigh, PivotKind.HIGH)
            .pivot(66, secondLow, PivotKind.LOW)
            .pivot(78, lastHigh, PivotKind.HIGH)
            .pivot(90, thirdLow, PivotKind.LOW)
            // Walk down to the flat floor without closing through it, then break.
            .at(breakdownBar - 1, 1210.0)
            .at(breakdownBar, closeAtBreak)
            .at(lastBar, closeAfterBreak?.lastOrNull() ?: 1160.0)
            .volumes(10, 29, 1_050_000L)
            .volumes(30, 90, 780_000L)
            .volumeAt(breakdownBar, 2_800_000L)
            .deliveryEvery(50.0)
            .deliveryAt(breakdownBar, 62.0)
        if (closeAfterBreak != null) {
            closeAfterBreak.forEachIndexed { index, price -> builder.closeAt(breakdownBar + 1 + index, price) }
        }
        return builder.build()
    }

    private fun singleCandidate(series: BuiltSeries): ChartCandidate {
        val context = ChartContext.of(series.candles)
        return ChartAssertions.single(
            TriangleEngine.candidates("Descending Triangle", ascending = false, context, context.confirmed(series.pivots)),
            "Descending Triangle",
        )
    }
}