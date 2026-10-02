package com.mia.app.domain.technical

import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.Timeframe
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Golden values and invariants for the share-image / risk utilities.
 */
class UtilitiesTest {

    private val candles = TestSeries.golden

    // ---------------------------------------------------------------- percent change

    @Test
    fun `percent change is the ratio of the change to the base`() {
        assertEquals(10.0, percentChange(100.0, 110.0))
        assertEquals(50.0, percentChange(200.0, 300.0))
        assertEquals(-50.0, percentChange(200.0, 100.0))
        assertEquals(0.0, percentChange(100.0, 100.0))
    }

    @Test
    fun `percent change is null rather than infinite for a zero or unusable base`() {
        assertNull(percentChange(0.0, 100.0))
        assertNull(percentChange(0.0, 0.0))
        assertNull(percentChange(Double.NaN, 100.0))
        assertNull(percentChange(100.0, Double.NaN))
        assertNull(percentChange(Double.POSITIVE_INFINITY, 100.0))
        assertNull(percentChange(100.0, Double.POSITIVE_INFINITY))
    }

    @Test
    fun `percent change over a bar window reads the closes`() {
        // The last leg of the golden series runs 2, 3, …, 12, so each step is a +1 move:
        //   1 bar back: closes[28] = 11 -> closes[29] = 12, (12 - 11) / 11 * 100 = 9.0909090909
        //   2 bars back: closes[27] = 10 -> (12 - 10) / 10 * 100 = 20.0
        assertClose(100.0 / 11.0, percentChangeOver(candles, 1))
        assertClose(9.090909090909092, percentChangeOver(candles, 1))
        assertEquals(20.0, percentChangeOver(candles, 2))
        assertNull(percentChangeOver(candles, 0))
        assertNull(percentChangeOver(candles, -1))
        assertNull(percentChangeOver(candles, 30), "needs bars + 1")
    }

    @Test
    fun `percent change between two candles uses their closes`() {
        assertEquals(100.0, percentChange(candles[0], candles[1]), "1 -> 2 is +100%")
        assertEquals(-50.0, percentChange(candles[1], candles[0]), "2 -> 1 is -50%")
    }

    // ------------------------------------------------------- annualised volatility

    @Test
    fun `annualised volatility of a flat series is exactly zero`() {
        // Every log return is ln(100/100) = 0, so the sample stdev is 0 and the annualised
        // figure is 0.0 exactly.
        val v = annualisedVolatility(TestSeries.directionless(120))
        assertEquals(0.0, v)
    }

    @Test
    fun `annualised volatility matches a hand computed value on an alternating series`() {
        // Closes alternate 100 and 101 daily, so every daily log return is ±ln(1.01).
        // With period = 50 there are 50 returns: 25 up and 25 down, all of the same magnitude
        // g = ln(1.01). Their mean is 0 (they cancel), so the sample stdev is
        // sqrt(sum(g^2) / (50 - 1)) = g * sqrt(50 / 49).
        // Annualising with 252 daily bars multiplies by sqrt(252).
        val period = 50
        val bars = TestSeries.alternating(period + 1)
        val v = assertNotNull(annualisedVolatility(bars, period = period, timeframe = Timeframe.D1))
        val g = ln(101.0 / 100.0)
        val expected = g * sqrt(50.0 / 49.0) * sqrt(252.0) * 100.0
        assertClose(expected, v, 1e-9)
    }

    @Test
    fun `annualised volatility uses the timeframe's bars per year`() {
        val bars = TestSeries.alternating(60)
        val daily = assertNotNull(annualisedVolatility(bars, period = 50, timeframe = Timeframe.D1))
        val weekly = assertNotNull(annualisedVolatility(bars, period = 50, timeframe = Timeframe.W1))
        // Same per-bar volatility, but weekly annualises with sqrt(52) instead of sqrt(252).
        assertClose(daily * sqrt(52.0 / 252.0), weekly, 1e-9)
    }

    @Test
    fun `annualised volatility is null without enough bars or a bad period`() {
        assertNull(annualisedVolatility(candles.take(20), period = 50), "needs period + 1 bars")
        assertNull(annualisedVolatility(candles, period = 0))
        assertNull(annualisedVolatility(candles, period = 1))
        assertNull(annualisedVolatility(emptyList()))
    }

    @Test
    fun `annualised volatility is null when a close is not positive`() {
        // ln(0) would be -Inf and ln of a negative is NaN; neither belongs on the screen.
        val bad = candles.toMutableList()
        bad[bad.lastIndex] = bad.last().copy(close = 0.0, open = 0.0, high = 0.0, low = 0.0)
        assertNull(annualisedVolatility(bad, period = 10))
    }

    @Test
    fun `annualised volatility is finite and positive for seeded random walks`() {
        for (seed in 1L..20L) {
            val v = annualisedVolatility(TestSeries.seededWalk(300, seed))
            val value = assertNotNull(v, "seed=$seed")
            assertTrue(value >= 0.0, "volatility must not be negative for seed $seed")
            assertFalse(value.isNaN())
            assertFalse(value.isInfinite())
        }
    }

    // ---------------------------------------------------------------- max drawdown

    @Test
    fun `max drawdown is a positive percentage from the running peak`() {
        // Golden series peaks at 10 (index 9) and falls to 1 (index 18): (1 - 10)/10 = -90%.
        assertEquals(90.0, assertNotNull(maxDrawdown(candles)), 1e-12)
    }

    @Test
    fun `max drawdown is zero on a monotonically rising series`() {
        assertEquals(0.0, maxDrawdown(TestSeries.rising(60)))
    }

    @Test
    fun `max drawdown matches a hand computed deep drawdown`() {
        // 100 -> 80 -> 120: the trough is 20% below the 100 peak, and 120 is a new high.
        assertEquals(20.0, maxDrawdown(TestSeries.fromCloses(listOf(100.0, 80.0, 120.0))))
    }

    @Test
    fun `max drawdown never goes negative`() {
        for (seed in 1L..25L) {
            val dd = assertNotNull(maxDrawdown(TestSeries.seededWalk(300, seed)))
            assertTrue(dd >= 0.0, "drawdown must be reported as a positive percentage, got $dd")
            assertTrue(dd <= 100.0, "drawdown cannot exceed 100%, got $dd")
        }
    }

    @Test
    fun `max drawdown is null when no bar has a usable close`() {
        assertNull(maxDrawdown(emptyList()))
        val zeroed = List(10) { candles[0].copy(close = 0.0, open = 0.0, high = 0.0, low = 0.0) }
        assertNull(maxDrawdown(zeroed))
    }

    @Test
    fun `sample stdev uses the n minus one denominator`() {
        assertEquals(0.0, sampleStdev(List(5) { 3.0 }))
        // 1,2,3,4 -> mean 2.5, squared deviations 2.25+0.25+0.25+2.25 = 5, sample var = 5/3.
        assertClose(sqrt(5.0 / 3.0), sampleStdev(listOf(1.0, 2.0, 3.0, 4.0)))
        assertNull(sampleStdev(listOf(1.0)))
        assertNull(sampleStdev(emptyList()))
    }

    // ------------------------------------------------------------- 52 week range

    @Test
    fun `fifty two week high and low read the whole series when it is short`() {
        assertEquals(12.0, fiftyTwoWeekHigh(candles))
        assertEquals(1.0, fiftyTwoWeekLow(candles))
        // The series ends on its high, so the "at 52-week low" flag must not fire.
        assertFalse(isAtFiftyTwoWeekLow(candles))
    }

    @Test
    fun `fifty two week range reports the extreme bars and the current position`() {
        val range = assertNotNull(fiftyTwoWeekRange(candles))
        assertEquals(12.0, range.high)
        assertEquals(candles[29].date, range.highDate)
        assertEquals(1.0, range.low)
        assertEquals(candles[0].date, range.lowDate)
        // The latest close (12) is the high of a range spanning 1..12 -> 100%.
        assertEquals(100.0, range.currentPositionPercent)
    }

    @Test
    fun `fifty two week range reports the midpoint when the window has no spread`() {
        val range = assertNotNull(fiftyTwoWeekRange(TestSeries.directionless(80)))
        assertEquals(100.0, range.high)
        assertEquals(100.0, range.low)
        assertEquals(50.0, range.currentPositionPercent)
    }

    @Test
    fun `fifty two week range only looks at the trailing fifty two bars`() {
        // 60 bars: the first 8 are at 1000 and the last 52 ramp from 100 to 151.
        val series = TestSeries.fromCloses(List(8) { 1000.0 } + List(52) { 100.0 + it })
        assertEquals(151.0, fiftyTwoWeekHigh(series), "the 1000 bars are outside the window")
        assertEquals(100.0, fiftyTwoWeekLow(series))
    }

    @Test
    fun `fifty two week helpers are null or false for no bars`() {
        assertNull(fiftyTwoWeekHigh(emptyList()))
        assertNull(fiftyTwoWeekLow(emptyList()))
        assertNull(fiftyTwoWeekRange(emptyList()))
        assertFalse(isAtFiftyTwoWeekLow(emptyList()))
    }

    @Test
    fun `isAtFiftyTwoWeekLow fires when the latest close is the window low`() {
        val risingThenFalling = TestSeries.fromCloses(List(20) { 100.0 + it } + listOf(5.0))
        assertTrue(isAtFiftyTwoWeekLow(risingThenFalling))
    }
}