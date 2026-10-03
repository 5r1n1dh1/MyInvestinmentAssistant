package com.mia.app.domain.pattern.detectors.candlestick

import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.Category
import com.mia.app.domain.model.MatchScorePolicy
import com.mia.app.domain.model.Timeframe
import com.mia.app.domain.pattern.PatternIds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Detector 5 — the star family: `Match.name` is "Morning Star" or "Evening Star"
 * (`patterns-spec.md` section 10, `[Ch.17-3 §Morning Star]`, `[Ch.17-3 §Evening Star]`).
 *
 * Section 10 says one class emits both directions; the two directions own two different
 * `PatternIds` constants (5 and 6), because the accuracy table is keyed per pattern name
 * (decision D6), and the class registers itself under the morning-star name so
 * `PatternIds.of(detector.name)` stays canonical.
 *
 * The penetration rule is the one section 10.7 had to correct itself twice to state: "closes well
 * into the body of Candle 1" is measured from the **first candle's open**, not its close, because
 * recovering only to the first candle's close has recovered none of its body.
 */
class StarDetectorTest {

    private val detector = StarDetector()

    @Test
    fun `detector name is a canonical accuracy-table key`() {
        assertEquals("Morning Star", detector.name)
        assertTrue(PatternIds.isCanonical(detector.name))
        assertEquals(5L, PatternIds.of(detector.name))
        assertEquals(6L, PatternIds.of("Evening Star"))
        assertEquals(Category.CANDLESTICK, detector.category)
    }

    @Test
    fun `theTextbookMorningStarMatchesAndRenders`() {
        val candles = StarFixture.morningStar()
        assertEquals(62, candles.size)

        // Section 10.7: priorTrendPct(n-3) = (2,090.00 - 2,180.00)/2,180.00 = -4.128 %.
        val priorTrend = (candles[57].close - candles[37].close) / candles[37].close * 100.0
        assertEquals(-4.128, priorTrend, 0.001, "section 10.1 P3 priorTrendPct(n-3)")
        assertTrue(priorTrend <= -CandlestickPolicy.declineThresholdPct(Timeframe.D1))

        val match = detectOn(detector, candles).single()
        assertEquals("Morning Star", match.name)
        assertEquals(5L, match.patternId, "the morning star owns its own accuracy-table id")
        // G=77 (starRatio 0.0431 decays to 100, pen 0.5172 reads 42, bodyRatio 0.6444 reads 36),
        // S=44, C=85, X=36, no penalties.
        assertEquals(64, match.score, "§10.7 morning star, scored by FactorScorer")
        assertTrue(match.score >= MatchScorePolicy.RENDER_THRESHOLD)

        val breakdown = assertNotNull(match.breakdown)
        assertEquals(match.score, breakdown.total, "score IS ScoreBreakdown.total")
        assertNull(match.measuredMove, "section 10.4 / DD-1: no measured move for a candlestick")
        assertTrue(match.pivots.isEmpty(), "section 4 rule 7: no pivots")
        // Three geometry measures apply to the star family, so GEOMETRY does not saturate and the
        // confirmation evidence is the strongest factor - which is what the book argues for.
        assertTrue(
            breakdown.confirmation > breakdown.geometry,
            "confirmation must beat geometry for the triple-candle template, got $breakdown",
        )
    }

    @Test
    fun `theTextbookMorningStarReportsTheSpecShapeNumbers`() {
        val candles = StarFixture.morningStar()
        val first = candles[58]
        val middle = candles[59]
        val third = candles[60]

        assertEquals(58.00, kotlin.math.abs(first.close - first.open), 0.0001, "body[n-2]")
        assertEquals(64.00, first.high - first.low, 0.0001, "range[n-2]")
        assertEquals(0.9063, kotlin.math.abs(first.close - first.open) / (first.high - first.low), 0.0001)
        assertEquals(2.50, kotlin.math.abs(middle.close - middle.open), 0.0001, "body[n-1]")
        assertEquals(0.2500, kotlin.math.abs(middle.close - middle.open) / (middle.high - middle.low), 0.0001)
        assertEquals(90.00, kotlin.math.abs(third.close - third.open), 0.0001, "body[n]")
        assertEquals(0.9375, kotlin.math.abs(third.close - third.open) / (third.high - third.low), 0.0001)

        // Section 10.7: pen = (close[n] - open[n-2]) / body[n-2] = 30.00 / 58.00 = 0.5172.
        val pen = (third.close - first.open) / kotlin.math.abs(first.close - first.open)
        assertEquals(0.5172, pen, 0.0001, "section 10.2 S4 penetration")
        val starRatio = kotlin.math.abs(middle.close - middle.open) /
            minOf(kotlin.math.abs(first.close - first.open), kotlin.math.abs(third.close - third.open))
        assertEquals(0.0431, starRatio, 0.0001, "section 10.2 S6 starRatio")
        assertEquals(0.6444, 58.00 / 90.00, 0.0001, "section 10.2 S6 bodyRatio")

        assertEquals(2085.00, candles.subList(40, 58).minOf { it.low }, 0.0001, "min low over [n-20, n-3]")
        assertTrue(first.low <= 2085.00 * 1.02, "P4 location band")
        assertTrue(candles.lastIndex - 60 <= CandlestickPolicy.MAX_SIGNAL_AGE_BARS)
    }

    @Test
    fun `theTextbookEveningStarMatchesUnderItsOwnNameAndId`() {
        val candles = StarFixture.eveningStar()
        val priorTrend = (candles[57].close - candles[37].close) / candles[37].close * 100.0
        assertEquals(4.545, priorTrend, 0.001, "section 10.1 P3 priorTrendPct(n-3), mirrored")
        assertTrue(priorTrend >= CandlestickPolicy.riseThresholdPct(Timeframe.D1))

        val match = detectOn(detector, candles).single()
        assertEquals("Evening Star", match.name)
        assertEquals(6L, match.patternId, "the evening star owns its own accuracy-table id")
        // G=50 (pen 0.5517 and bodyRatio 0.6444 are the weakest of the family), S=46, C=85, X=38.
        assertEquals(54, match.score, "§10.7 evening star, scored by FactorScorer")
        assertTrue(match.score >= MatchScorePolicy.RENDER_THRESHOLD)
        assertEquals(match.score, assertNotNull(match.breakdown).total)
    }
}

/**
 * Detector 5's near-miss and negative cases — "one condition violated" and "the mirror pattern" —
 * in their own class.
 */
class StarDetectorFailureModeTest {

    private val detector = StarDetector()

    @Test
    fun `aStarThatOnlyRecoversToTheFirstCandlesCloseIsNotAStar`() {
        // Section 10.7's own first attempt: the third candle recovers to above the first candle's
        // CLOSE, which has recovered none of its body. pen = (2,175 - 2,150)/58 = 0.431 < 0.50.
        val candles = StarFixture.morningStar(thirdClose = 2175.00)
        val pen = (candles[60].close - candles[58].open) / 58.00
        assertEquals(0.431, pen, 0.001, "the penetration rule the spec had to restate twice")
        assertTrue(
            detectOn(detector, candles).isEmpty(),
            "section 10.2 S4: a third candle that does not close into candle 1's body is not a star",
        )
    }

    @Test
    fun `aStarWithAFatMiddleCandleIsNotAStar`() {
        // Section 10.2 S2: the middle candle's body must be <= 0.30 of its range. 4.50/10 = 0.45.
        val candles = StarFixture.morningStar(middleClose = 2095.00)
        val middleBodyFrac = kotlin.math.abs(candles[59].close - candles[59].open) / 10.00
        assertEquals(0.450, middleBodyFrac, 0.001)
        assertTrue(
            detectOn(detector, candles).isEmpty(),
            "section 10.2 S2: a middle candle with bodyFrac > 0.30 is not a star",
        )
    }

    @Test
    fun `theMirrorTripleIsNotThisDirection`() {
        val evening = StarFixture.eveningStar()
        val morning = StarFixture.morningStar()
        assertEquals("Evening Star", detectOn(detector, evening).single().name)
        assertEquals("Morning Star", detectOn(detector, morning).single().name)
        // One detector invocation returns at most one match: the two triples never both qualify.
        assertEquals(1, detectOn(detector, evening).size)
        assertEquals(1, detectOn(detector, morning).size)
    }

    @Test
    fun `aShortHistoryNeverEmitsAScore`() {
        assertTrue(
            detectOn(detector, StarFixture.morningStar().take(59)).isEmpty(),
            "below 60 daily bars the answer is 'not enough history', never a low score",
        )
    }
}

/** The section 10.7 formation, every bar the spec prints. */
internal object StarFixture {

    /**
     * | bars | content |
     * |------|---------|
     * | 0-36 | flat 2,180.00 (history; bar 37 is the prior-trend reference) |
     * | 37-38 | 2,180.00 then 2,170.00 |
     * | 39-57 | declining ramp 2,160.00 to 2,090.00, last bar's low pinned to 2,085.00 |
     * | 58 | `n-2`: O=2,150.00 H=2,152.00 L=2,088.00 C=2,092.00 (the large candle) |
     * | 59 | `n-1`: O=2,090.50 H=2,096.00 L=2,086.00 C=2,093.00 (the small "star") |
     * | 60 | `n`: O=2,090.00 H=2,185.00 L=2,089.00 C=2,180.00 (the large reversal) |
     * | 61 | `n+1`: C=2,190.00 — follow-through, and it clears the window's own high |
     */
    fun morningStar(
        middleClose: Double = 2093.00,
        thirdClose: Double = 2180.00,
    ): List<Candle> {
        val series = CandleSeries()
        series.filler(37, 2180.00)
        series.flat(2180.00) // bar 37 — the prior-trend reference
        series.flat(2170.00) // bar 38
        series.ramp(
            count = 19,
            fromClose = 2160.00,
            toClose = 2090.00,
            deliveryPercent = 43.0,
            lastBarLow = 2085.00,
        )
        series.bar(2150.00, 2152.00, 2088.00, 2092.00, deliveryPercent = 43.0)
        series.bar(2090.50, 2096.00, 2086.00, middleClose, deliveryPercent = 43.0)
        series.bar(
            open = 2090.00,
            high = 2185.00,
            low = 2089.00,
            close = thirdClose,
            // Section 10.5 C1: rvol exactly 3.00.
            volume = series.nextVolumeForRvol(3.0),
            // Section 10.5 C5: 68.4 % against a 43 % average.
            deliveryPercent = 68.4,
        )
        series.bar(2182.00, 2195.00, 2178.00, 2190.00, deliveryPercent = 44.0)
        return series.build()
    }

    /** The mirror of [morningStar], rising into the triple. */
    fun eveningStar(
        middleClose: Double = 2067.00,
        thirdClose: Double = 1980.00,
    ): List<Candle> {
        val series = CandleSeries()
        series.filler(37, 1980.00)
        series.flat(1980.00) // bar 37 — the prior-trend reference
        series.flat(1990.00) // bar 38
        series.ramp(
            count = 19,
            fromClose = 1990.00,
            toClose = 2070.00,
            deliveryPercent = 43.0,
            lastBarHigh = 2085.00,
        )
        series.bar(2012.00, 2070.00, 2010.00, 2070.00, deliveryPercent = 43.0)
        series.bar(2069.50, 2074.00, 2064.00, middleClose, deliveryPercent = 43.0)
        series.bar(
            open = 2070.00,
            high = 2071.00,
            low = 1975.00,
            close = thirdClose,
            volume = series.nextVolumeForRvol(3.0),
            deliveryPercent = 68.4,
        )
        series.bar(1978.00, 1982.00, 1970.00, 1972.00, deliveryPercent = 44.0)
        return series.build()
    }
}