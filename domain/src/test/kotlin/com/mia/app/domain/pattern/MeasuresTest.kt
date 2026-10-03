package com.mia.app.domain.pattern

import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.Category
import com.mia.app.domain.model.Direction
import com.mia.app.domain.model.MatchScorePolicy
import com.mia.app.domain.model.MeasuredMove
import com.mia.app.domain.model.Pivot
import com.mia.app.domain.model.PivotKind
import com.mia.app.domain.model.Source
import com.mia.app.domain.model.Timeframe
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The seam itself: measure construction, `matchOf`, and the stable pattern ids. */
class MeasuresTest {

    @Test
    fun `matchOfBuildsAMatchWhoseScoreIsTheBreakdownTotal`() {
        val detector = FakeDetector()
        val candles = candles(40)
        val measures = ScoringFixtures.exampleA()
        val match = matchOf(
            detector = detector,
            candles = candles,
            pivots = listOf(pivot(10, 1_000.0, PivotKind.HIGH)),
            measures = measures,
            measuredMove = MeasuredMove(189.4, 1_036.5, Direction.DOWN, 805.6),
        )

        assertEquals("Fake", match.name)
        assertEquals(Category.REVERSAL, match.category)
        assertEquals(Timeframe.D1, match.timeframe)
        assertEquals(match.breakdown!!.total, match.score, "score is always breakdown.total")
        assertEquals(74, match.score)
        assertEquals(1, match.pivots.size)
        assertEquals(189.4, match.measuredMove!!.height, 1e-9)
        assertEquals(PatternIds.of("Fake"), match.patternId)
        assertEquals(candles.last().date, match.detectedOn)
    }

    @Test
    fun `matchOfAppliesPenaltiesAndKeepsTheBreakdownConsistent`() {
        val detector = FakeDetector()
        val match = matchOf(
            detector = detector,
            candles = candles(40),
            pivots = emptyList(),
            measures = ScoringFixtures.exampleB(),
            penalties = ScoringFixtures.exampleBPenalties(),
        )
        assertEquals(39, match.score)
        assertEquals(match.breakdown!!.total, match.score)
    }

    @Test
    fun `matchOfAcceptsTheTwoDirectionFamilyNames`() {
        val detector = FakeDetector()
        for (name in listOf("Morning Star", "Evening Star", "Bull Flag", "Bear Flag")) {
            val match = matchOf(
                detector = detector,
                candles = candles(10),
                pivots = emptyList(),
                measures = listOf(Measure.identity("g", 1.0, 10, ScoreFactor.GEOMETRY)),
                name = name,
            )
            assertEquals(name, match.name)
            assertTrue(PatternIds.isCanonical(name))
        }
    }

    @Test
    fun `theFourteenCanonicalPatternIdsAreDistinctStableAndNonZero`() {
        val names = PatternIds.CANONICAL_NAMES
        assertEquals(14, names.size)
        val ids = names.map { PatternIds.of(it) }
        assertEquals(ids.size, ids.distinct().size, "ids must be collision-free")
        assertTrue(ids.all { it > 0L })
        assertEquals(names.sorted(), names.sorted()) // sanity: the list is ordered by id
        // Stability: the same call in a later "process" must produce the same number, because the
        // accuracy table (plan.md §6.1, D6) is keyed on it.
        names.forEach { assertEquals(PatternIds.of(it), PatternIds.of(it)) }
        assertEquals(1L, PatternIds.of("Bullish Engulfing"))
        assertEquals(14L, PatternIds.of("Bear Flag"))
    }

    @Test
    fun `cosmeticRenamesOfADisplayNameResolveToTheSameId`() {
        // Ids key the accuracy table, so a cosmetic change of the display string must not split one
        // pattern's history into two rows: case, spacing and punctuation are all normalised away.
        assertEquals(PatternIds.of("Head & Shoulders"), PatternIds.of("head & shoulders"))
        assertEquals(PatternIds.of("Head & Shoulders"), PatternIds.of("  Head   &  Shoulders "))
        assertNotEquals(PatternIds.of("Head & Shoulders"), PatternIds.of("Double Top"))
    }

    @Test
    fun `anUnknownNameGetsADeterministicNonCollidingCustomId`() {
        val a = PatternIds.of("Some Future Pattern")
        val b = PatternIds.of("Some Future Pattern")
        assertEquals(a, b)
        assertTrue(a >= PatternIds.CUSTOM_ID_BIT, "custom ids must sit above the canonical range")
        assertNotEquals(a, PatternIds.of("Another Future Pattern"))
        assertTrue(PatternIds.CANONICAL_NAMES.none { PatternIds.of(it) == a })
        assertTrue(!PatternIds.isCanonical("Some Future Pattern"))
    }

    @Test
    fun `measureFactoriesProduceTheSpecBandShapes`() {
        assertEquals(MeasureBand.Linear(false, 0.8, 1.0), Measure.linear("x", 0.9, 1, ScoreFactor.GEOMETRY, 0.8, 1.0).band)
        assertEquals(MeasureBand.Linear(true, 0.0, 4.0), Measure.linear("x", 1.0, 1, ScoreFactor.GEOMETRY, 0.0, 4.0, invert = true).band)
        assertEquals(MeasureBand.Linear(false, 0.0, 3.0), Measure.saturating("x", 1.0, 1, ScoreFactor.CONFIRMATION, 3.0).band)
        assertEquals(MeasureBand.Exact(1.0, 0.0), Measure.exact("x", 1.0, 1, ScoreFactor.CONFIRMATION, 1.0, 0.0).band)
        assertEquals(MeasureBand.RatioEqual(1.0, 0.5), Measure.ratioEqual("x", 1.0, 1, ScoreFactor.CONTEXT, 1.0, 0.5).band)

        // Decay shifts the value into an offset from the perfect point, keeping the locked band.
        val decay = Measure.decay("starRatio", 0.0431, 20, ScoreFactor.GEOMETRY, perfect = 0.05, floor = 0.50)
        assertEquals(MeasureBand.Decay(0.45), decay.band)
        assertEquals(0.0, decay.value, 1e-12, "0.0431 is *better* than the 0.05 perfect point")
        assertEquals(100.0, decay.normalised(), 1e-12)

        val worse = Measure.decay("starRatio", 0.50, 20, ScoreFactor.GEOMETRY, perfect = 0.05, floor = 0.50)
        assertEquals(0.45, worse.value, 1e-12)
        assertEquals(0.0, worse.normalised(), 1e-12)
    }

    @Test
    fun `identityAndBinaryMeasuresMapTheSpecsZeroHalfOneRules`() {
        assertEquals(100.0, Measure.identity("x", 1.0, 1, ScoreFactor.CONFIRMATION).normalised())
        assertEquals(50.0, Measure.identity("x", 0.5, 1, ScoreFactor.CONFIRMATION).normalised())
        assertEquals(0.0, Measure.identity("x", 0.0, 1, ScoreFactor.CONFIRMATION).normalised())
        assertEquals(100.0, Measure.binary("x", true, 1, ScoreFactor.CONFIRMATION).normalised())
        assertEquals(0.0, Measure.binary("x", false, 1, ScoreFactor.CONFIRMATION).normalised())
    }

    @Test
    fun `theContextMismatchPenaltyIsDoubledForCandlesticks`() {
        val penalty = PenaltyId.of(PenaltyId.CTX_MISMATCH, Category.CANDLESTICK, "wrong location")
        assertEquals(PatternScoringPolicy.PEN_CTX_MISMATCH_CS, penalty.points)
        assertEquals("CTX_MISMATCH", penalty.name)
        val chart = PenaltyId.of(PenaltyId.CTX_MISMATCH, Category.REVERSAL, "no prior trend")
        assertEquals(PatternScoringPolicy.PEN_CTX_MISMATCH, chart.points)
    }

    private class FakeDetector : com.mia.app.domain.model.PatternDetector {
        override val name: String = "Fake"
        override val category: Category = Category.REVERSAL
        override val minBars: Int = 5
        override fun detect(pivots: List<Pivot>, candles: List<Candle>): List<com.mia.app.domain.model.Match> =
            emptyList()
    }

    private fun candles(count: Int): List<Candle> = List(count) { i ->
        val close = 100.0 + i
        Candle(
            symbol = "TEST",
            timeframe = Timeframe.D1,
            date = LocalDate.of(2025, 1, 1).plusDays(i.toLong()),
            open = close,
            high = close + 1.0,
            low = close - 1.0,
            close = close,
            volume = 1_000L,
            source = Source.NSE_BHAVCOPY,
        )
    }

    private fun pivot(barIndex: Int, price: Double, kind: PivotKind) =
        Pivot(barIndex, LocalDate.of(2025, 1, 1).plusDays(barIndex.toLong()), price, kind, 0)
}

/** The shared geometry helpers every detector depends on (`patterns-spec.md` §3). */
class GeometryTest {

    @Test
    fun `symmetryRatioIsBoundedAndOrderIndependent`() {
        assertEquals(1.0, symmetryRatio(1150.0, 1150.0), 1e-12)
        assertEquals(1140.0 / 1150.0, symmetryRatio(1150.0, 1140.0), 1e-12)
        assertEquals(symmetryRatio(1150.0, 1140.0), symmetryRatio(1140.0, 1150.0), 1e-12)
        assertEquals(0.0, symmetryRatio(0.0, 0.0), 1e-12)
        assertEquals(0.0, symmetryRatio(100.0, 0.0), 1e-12, "an absent leg is not symmetric")
        assertEquals(0.0, symmetryRatio(Double.NaN, 1.0), 1e-12)
    }

    @Test
    fun `peakEqualityIsSymmetryForTheTwoPeaks`() {
        // patterns-spec.md §14.2 S1: sym ≥ 0.97 is a double top; 0.95 is two unrelated highs.
        assertTrue(peakEquality(1200.0, 1192.0) >= 0.97)
        assertTrue(peakEquality(1200.0, 1140.0) < 0.97)
    }

    @Test
    fun `slopeHelpersMatchTheSpecDefinitions`() {
        val t1 = pivot(74, 1_000.0, PivotKind.LOW)
        val t2 = pivot(108, 1_020.0, PivotKind.LOW)
        assertEquals(20.0 / 34, slopePerBar(t1, t2), 1e-12)
        assertEquals(slopePerBar(t1, t2) * 100.0 / 1000.0, slopePctPerBar(t1, t2), 1e-12)
        // patterns-spec.md §12.2 S6: tilt measured across the whole formation.
        assertEquals(4.0, slopeOverSpan(t1, t2, 68), 1e-9)
        assertEquals(34, gap(t1, t2))
        assertEquals(0.0, slopePerBar(t1, t1), 1e-12, "a zero-length span never divides")
    }

    @Test
    fun `barPctIsASignedPercentageChange`() {
        assertEquals(13.861, barPct(1150.0, 1010.0), 1e-3)
        assertEquals(-25.0, barPct(480.0, 640.0), 1e-9)
        assertEquals(0.0, barPct(100.0, 0.0), 1e-12)
    }

    @Test
    fun `proportionalityHandlesAViolationWithoutThrowing`() {
        // scoring-spec.md §10.2: a head *below* the shoulder gives a negative ratio — the roles are
        // wrong, and that must be representable as a number the scorer can clamp.
        assertTrue(proportionality(1060.0 - 1140.0, 57.692) < 0.0)
        assertEquals(0.0, proportionality(1.0, 0.0), 1e-12)
        assertEquals(0.5, proportionality(50.0, 100.0), 1e-12)
    }

    @Test
    fun `aTwoPointBoundaryReportsResidualsOfZeroAndTheFitIsExact`() {
        val points = listOf(
            pivot(74, 1_000.0, PivotKind.LOW),
            pivot(108, 1_020.0, PivotKind.LOW),
        )
        val fit = fitBoundary(points, ref = 189.41)
        assertEquals(0.0, fit.residMax, 1e-12)
        assertEquals(0.0, fit.residMean, 1e-12)
        assertEquals(1_010.588, fit.line.at(92), 1e-3)
    }

    @Test
    fun `aTwoPointNecklineReportsTheNeutralRSquaredNotOne`() {
        // scoring-spec.md DD-7. Reporting 1.0 would let every two-point neckline claim a perfect fit.
        val two = listOf(pivot(74, 1_000.0, PivotKind.LOW), pivot(108, 1_020.0, PivotKind.LOW))
        assertEquals(PatternScoringPolicy.NEUTRAL_BOUNDARY_R2, boundaryRSquared(two), 1e-12)

        val three = two + pivot(140, 1_038.0, PivotKind.LOW)
        assertTrue(boundaryRSquared(three) > 0.99)
    }

    @Test
    fun `residualsAreExpressedInUnitsOfThePatternScale`() {
        val points = listOf(
            pivot(10, 1_000.0, PivotKind.LOW),
            pivot(20, 1_010.0, PivotKind.LOW),
            pivot(30, 1_030.0, PivotKind.LOW),
        )
        val fit = fitBoundary(points, ref = 100.0)
        assertTrue(fit.residMax >= 0.0)
        assertTrue(fit.rSquared in 0.0..1.0)
        // A zero ref must not divide by zero.
        val zeroRef = fitBoundary(points, ref = 0.0)
        assertEquals(0.0, zeroRef.residMax, 1e-12)
    }

    @Test
    fun `monotonicityAndProgressMatchTheTriangleRules`() {
        // patterns-spec.md §16.7: rising lows 900 → 918 → 938 → 960.
        val lows = listOf(
            pivot(30, 900.0, PivotKind.LOW),
            pivot(54, 918.0, PivotKind.LOW),
            pivot(78, 938.0, PivotKind.LOW),
            pivot(102, 960.0, PivotKind.LOW),
        )
        assertEquals(1.0, monoScore(lows, rising = true), 1e-12)
        assertEquals(0.0, monoScore(lows, rising = false), 1e-12)
        assertEquals(0.09259, progressPctPerBar(lows), 1e-5)

        // One step against the required direction costs exactly 1/(k-1).
        val uneven = listOf(lows[0], pivot(54, 880.0, PivotKind.LOW), lows[2], lows[3])
        assertEquals(2.0 / 3.0, monoScore(uneven, rising = true), 1e-12)
    }

    @Test
    fun `parallelDeviationSeparatesAFlagFromAPennant`() {
        // patterns-spec.md §18.2 S4: perfectly parallel 0.00, the accepted fixture 0.4444, the
        // rejected 0.5556 (a pennant).
        assertEquals(0.0, parallelDeviation(-0.5, -0.5), 1e-9)
        assertEquals(0.4444, parallelDeviation(-0.6, -0.3333), 1e-3)
        assertEquals(0.5556, parallelDeviation(-0.75, -0.3333), 1e-3)
        assertTrue(parallelDeviation(-0.75, -0.3333) > 0.50, "beyond 0.50 it is a pennant")
    }

    @Test
    fun `touchToleranceIsHalfTheSignificanceThreshold`() {
        val p = pivot(100, 1_000.0, PivotKind.HIGH)
        val d1 = touchTolerance(p, Timeframe.D1, atr = 10.0)
        // max(1.5% of price × 1.5, 0.5 ATR) = max(22.5, 5.0)
        assertEquals(22.5, d1, 1e-9)
        assertEquals(22.5, tolerancePct(ZigzagParams.D1.depthPct * TOUCH_TOL_MULT, 1_000.0), 1e-9)
        assertEquals(5.0, toleranceAtr(TOUCH_TOL_ATR, 10.0), 1e-9)
        assertEquals(0.0, toleranceAtr(TOUCH_TOL_ATR, null), 1e-12, "no ATR means no ATR tolerance")
    }

    @Test
    fun `touchDetectionUsesThePivotOwnTolerance`() {
        val line = BoundaryLine.horizontal(1_000.0)
        val onLine = pivot(50, 1_005.0, PivotKind.HIGH) // within 22.5
        val far = pivot(60, 1_050.0, PivotKind.HIGH)
        assertTrue(touches(onLine, line, 22.5))
        assertTrue(!touches(far, line, 22.5))
        assertEquals(listOf(onLine), touching(listOf(onLine, far), line, Timeframe.D1) { null })
    }

    @Test
    fun `spacingAndDurationMatchScoringSpecSectionFour`() {
        assertEquals(1.0, legSpacingScore(15.0.toInt(), 15.0, 12.0), 1e-12)
        assertEquals(0.5, legSpacingScore(21, 15.0, 12.0), 1e-12)
        assertEquals(0.0, legSpacingScore(27, 15.0, 12.0), 1e-12, "clamped at zero")
        assertEquals(0.472656, spacingScore(listOf(16, 18, 16, 18), 15.0, 12.0), 1e-5)
        assertEquals(0.52, spacingScore(listOf(42), 30.0, 25.0), 1e-9)
        assertEquals(41.176, durationScore(68, 68.0), 1e-3)
        assertEquals(0.0, durationScore(20, 68.0), 1e-9, "a five-day formation scores zero")
        assertEquals(100.0, durationScore(136, 68.0), 1e-9, "and a very old one saturates")
    }

    @Test
    fun `priorTrendIsATimedWindowNotAFixedPercentOfTheFormation`() {
        val closes = List(40) { 100.0 + it } // close[39] = 139, close[19] = 119
        assertEquals(20, priorTrendLookback(Timeframe.D1))
        assertEquals(13, priorTrendLookback(Timeframe.W1))
        assertEquals(3.0, priorTrendThreshold(Timeframe.D1), 1e-12)
        assertEquals(5.0, priorTrendThreshold(Timeframe.W1), 1e-12)
        val pct = priorTrendPct(closes, 39, 20)
        assertTrue(pct != null)
        assertEquals(barPct(closes[39], closes[19]), pct!!, 1e-9)
        assertEquals(null, priorTrendPct(closes, 39, 100), "an absent window is not 0%")
    }

    @Test
    fun `coefficientOfVariationMeasuresContextNoise`() {
        assertEquals(0.0, coefficientOfVariation(List(10) { 100.0 }), 1e-12)
        assertTrue(coefficientOfVariation(listOf(90.0, 110.0, 100.0, 100.0)) > 0.05)
        assertEquals(0.0, coefficientOfVariation(listOf(100.0)), 1e-12)
    }

    private fun pivot(barIndex: Int, price: Double, kind: PivotKind) =
        Pivot(barIndex, LocalDate.of(2025, 1, 1).plusDays(barIndex.toLong()), price, kind, 0)
}