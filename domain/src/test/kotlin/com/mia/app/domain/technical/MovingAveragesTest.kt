package com.mia.app.domain.technical

import com.mia.app.domain.model.Candle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Golden values and invariants for SMA / EMA / rolling stdev and the moving-average crossover
 * helpers (plan.md §8.2 death cross).
 *
 * The series is [TestSeries.GOLDEN_CLOSES]:
 * ```
 * index :  0  1  2  3  4  5  6  7  8  9 10 11 12 13 14 15 16 17 18 19 20 21 22 23 24 25 26 27 28 29
 * close :  1  2  3  4  5  6  7  8  9 10  9  8  7  6  5  4  3  2  1  2  3  4  5  6  7  8  9 10 11 12
 * ```
 */
class MovingAveragesTest {

    private val closes = TestSeries.GOLDEN_CLOSES
    private val candles = TestSeries.golden

    // ------------------------------------------------------------------------------ SMA

    @Test
    fun `sma leaves every bar before period minus one null`() {
        val series = sma(closes, 3)
        assertEquals(30, series.size)
        assertWarmup(series, 2, "sma3")
        assertEquals(2, smaWarmupBars(3), "period - 1 bars cannot be filled")
        assertEquals(19, smaWarmupBars(20))
    }

    @Test
    fun `sma matches hand computed windows on the golden series`() {
        // SMA(3): index 2 -> (1+2+3)/3 = 2.0; index 3 -> (2+3+4)/3 = 3.0
        // index 17 -> closes[15,16,17] = (4+3+2)/3 = 3.0
        // index 19 -> closes[17,18,19] = (2+1+2)/3 = 5/3
        // index 29 -> closes[27,28,29] = (10+11+12)/3 = 11.0
        val series = sma(closes, 3)
        assertEquals(2.0, series[2])
        assertEquals(3.0, series[3])
        assertEquals(3.0, series[17])
        assertClose(5.0 / 3.0, series[19])
        assertEquals(11.0, series[29])

        // SMA(5): index 4 -> (1+2+3+4+5)/5 = 3.0
        // index 9 -> (6+7+8+9+10)/5 = 8.0
        // index 29 -> (8+9+10+11+12)/5 = 50/5 = 10.0
        val five = sma(closes, 5)
        assertEquals(3.0, five[4])
        assertEquals(8.0, five[9])
        assertEquals(10.0, five[29])
    }

    @Test
    fun `sma over candles equals sma over their closes`() {
        assertEquals(sma(closes, 7), smaClose(candles, 7))
        assertEquals(sma(closes, 7), sma(candles.map { it.close }, 7))
    }

    @Test
    fun `sma running-sum matches a naive sliding-window recomputation`() {
        val expected = MutableList<Double?>(closes.size) { null }
        for (i in closes.indices) {
            if (i >= 4) {
                var sum = 0.0
                for (j in (i - 4)..i) sum += closes[j]
                expected[i] = sum / 5.0
            }
        }
        assertEquals(expected, sma(closes, 5))
    }

    // ------------------------------------------------------------------------------ EMA

    @Test
    fun `ema is seeded with the sma of the first period values`() {
        // alpha = 2/(3+1) = 0.5, seed = (1+2+3)/3 = 2.0
        assertEquals(2.0, ema(closes, 3)[2])
        assertClose(0.5 * 4 + 0.5 * 2.0, ema(closes, 3)[3])
    }

    @Test
    fun `ema matches hand computed recursion on the golden series`() {
        // alpha = 0.5. On the rising ramp the EMA trails the close by exactly one bar:
        //   ema[i] = 0.5*close[i] + 0.5*ema[i-1]
        //   ema[2]=2.0 (seed)  ema[3]=0.5*4+0.5*2=3.0   ema[4]=4.0  ema[5]=5.0
        //   ema[6]=6.0  ema[7]=7.0  ema[8]=8.0  ema[9]=0.5*10+0.5*8=9.0
        // The reversal at index 10 (close 9 after 10) gives ema[10] = 0.5*9+0.5*9 = 9.0.
        // Every value below is an exact binary fraction (multiples of 1/2048):
        //   ema[11] = 0.5*8+0.5*9      = 8.5
        //   ema[12] = 0.5*7+0.5*8.5    = 7.75
        //   ema[13] = 0.5*6+0.5*7.75   = 6.875
        //   ema[14] = 0.5*5+0.5*6.875  = 5.9375
        //   ema[15] = 0.5*4+0.5*5.9375 = 4.96875
        //   ema[16] = 0.5*3+0.5*4.96875= 3.984375
        //   ema[17] = 0.5*2+0.5*3.984375=2.9921875
        //   ema[18] = 0.5*1+0.5*2.9921875=1.99609375
        //   ema[19] = 0.5*2+0.5*1.99609375=1.998046875
        //   ema[20] = 0.5*3+0.5*1.998046875=2.4990234375
        //   ema[29] = 11.0009746551513671875
        val e = ema(closes, 3)
        assertEquals(2.0, e[2])
        assertEquals(3.0, e[3])
        assertEquals(4.0, e[4])
        assertEquals(5.0, e[5])
        assertEquals(6.0, e[6])
        assertEquals(7.0, e[7])
        assertEquals(8.0, e[8])
        assertEquals(9.0, e[9])
        assertEquals(9.0, e[10])
        assertEquals(8.5, e[11])
        assertEquals(7.75, e[12])
        assertEquals(6.875, e[13])
        assertEquals(5.9375, e[14])
        assertEquals(4.96875, e[15])
        assertEquals(3.984375, e[16])
        assertEquals(2.9921875, e[17])
        assertEquals(1.99609375, e[18])
        assertEquals(1.998046875, e[19])
        assertEquals(2.4990234375, e[20])
        assertEquals(3.24951171875, e[21])
        assertEquals(4.124755859375, e[22])
        assertEquals(5.0623779296875, e[23])
        assertEquals(6.03118896484375, e[24])
        assertEquals(7.015594482421875, e[25])
        assertEquals(8.0077972412109375, e[26])
        assertEquals(9.00389862060546875, e[27])
        assertEquals(10.001949310302734375, e[28])
        assertEquals(11.0009746551513671875, e[29])
    }

    @Test
    fun `ema of a constant series equals that constant everywhere it is defined`() {
        val flat = List(10) { 42.0 }
        ema(flat, 5).forEachIndexed { i, v ->
            if (i >= 4) assertEquals(42.0, v, "ema[$i]")
        }
        assertEquals(0.0, rollingStdev(flat, 5).last(), "a constant series has no dispersion")
    }

    @Test
    fun `ema reacts faster than sma after a sustained move`() {
        // On a clean ramp the SMA lags by exactly (period-1)/2 bars and the EMA by far less, which
        // is the whole reason the technicals tab shows both. Using a ramp avoids the ambiguity of
        // a reversal, where the ordering depends on where the turn sits in each window.
        // closes 100, 102, …, 178 over 40 bars.
        //   SMA(10) at the last bar = mean(160..178) = 169.0 exactly
        //   EMA(10) on a straight ramp converges onto the ramp itself, giving 169.0 (to within
        //   floating point), because both averages of a linear series are the series shifted by a
        //   constant lag and an EMA has a smaller effective lag here than the SMA midpoint.
        val ramp = List(40) { 100.0 + it * 2.0 }
        val ema10 = assertNotNull(ema(ramp, 10).last())
        val sma10 = assertNotNull(sma(ramp, 10).last())
        assertEquals(169.0, sma10, "mean(160, 162, …, 178) = 169")
        assertClose(169.0, ema10, 1e-9)
        // The distinguishing property is *responsiveness*, so test it on a step rather than a ramp:
        // after the jump to 400 the EMA must sit closer to price than the SMA does.
        val stepped = List(30) { 100.0 } + List(5) { 400.0 }
        val ema5 = assertNotNull(ema(stepped, 10).last())
        val sma5 = assertNotNull(sma(stepped, 10).last())
        assertTrue(ema5 > sma5, "ema=$ema5 must lead sma=$sma5 after a step up")
        assertTrue(400.0 - ema5 < 400.0 - sma5, "the ema is closer to the new price")
    }

    // -------------------------------------------------------------------- rolling stdev

    @Test
    fun `rolling stdev seeds with the sample stdev of the first period`() {
        // values 1..5, mean 3, deviations -2,-1,0,1,2, squares 4+1+0+1+4 = 10,
        // sample variance = 10/(5-1) = 2.5, stdev = sqrt(2.5) = 1.5811388300841898
        val sd = rollingStdev(closes, 5)
        assertClose(Math.sqrt(2.5), sd[4], 1e-12)
    }

    @Test
    fun `rolling stdev matches a naive per window recomputation`() {
        val expected = MutableList<Double?>(closes.size) { null }
        for (i in closes.indices) {
            if (i >= 4) {
                var sum = 0.0
                var sq = 0.0
                for (j in (i - 4)..i) {
                    sum += closes[j]
                    sq += closes[j] * closes[j]
                }
                val n = 5.0
                expected[i] = Math.sqrt((sq - sum * sum / n) / (n - 1.0))
            }
        }
        assertEquals(expected, rollingStdev(closes, 5))
    }

    @Test
    fun `rolling stdev of a constant series decays to zero and never goes negative`() {
        val sd = rollingStdev(List(40) { 7.0 }, 5)
        sd.forEachIndexed { i, v ->
            if (v != null) assertTrue(v >= 0.0, "stdev[$i] = $v")
        }
        assertEquals(0.0, sd.last())
    }

    // --------------------------------------------------------------------- edge cases

    @Test
    fun `fewer bars than the period yields an all null series of the right length`() {
        assertEquals(List(2) { null }, sma(closes.take(2), 3))
        assertEquals(List(2) { null }, ema(closes.take(2), 3))
        assertEquals(List(2) { null }, rollingStdev(closes.take(2), 3))
    }

    @Test
    fun `zero and negative periods never throw and never emit a value`() {
        for (period in listOf(0, -1, -20)) {
            assertEquals(30, sma(closes, period).size)
            assertTrue(sma(closes, period).all { it == null })
            assertTrue(ema(closes, period).all { it == null })
            assertTrue(rollingStdev(closes, period).all { it == null })
            assertEquals(Int.MAX_VALUE, smaWarmupBars(period))
        }
    }

    @Test
    fun `empty input yields an empty series`() {
        assertEquals(emptyList<Double?>(), sma(emptyList<Double>(), 5))
        assertEquals(emptyList<Double?>(), ema(emptyList<Double>(), 5))
        assertEquals(emptyList<Double?>(), rollingStdev(emptyList<Double>(), 5))
        assertEquals(emptyList<Candle>(), TestSeries.golden.take(0))
    }

    @Test
    fun `single bar input is a fully warmed SMA and EMA`() {
        // A period of 1 is the mean of one window, i.e. the value itself.
        assertEquals(listOf(10.0), sma(listOf(10.0), 1))
        assertEquals(listOf(10.0), ema(listOf(10.0), 1))
        // The sample stdev needs two points, so it stays null.
        assertEquals(listOf(null), rollingStdev(listOf(10.0), 1))
    }

    @Test
    fun `rolling stdev needs at least two points because it uses the sample denominator`() {
        assertEquals(listOf(null), rollingStdev(listOf(1.0), 2))
        // Two points: mean 1.5, deviations ±0.5, sample variance = 0.5/1 = 0.5.
        assertEquals(listOf(null, Math.sqrt(0.5)), rollingStdev(listOf(1.0, 2.0), 2))
    }

    // ------------------------------------------------------------------- cross helpers

    @Test
    fun `smaCrossState reads the position of the two lines at each bar`() {
        val fast = listOf(null, null, 99.0, 101.0, 98.0)
        val slow = listOf(null, null, 100.0, 100.0, 100.0)
        assertNull(smaCrossState(fast, slow, 0), "warm-up bar must be unknown")
        assertEquals(SmaCrossState.BELOW, smaCrossState(fast, slow, 2))
        assertEquals(SmaCrossState.ABOVE, smaCrossState(fast, slow, 3))
        assertEquals(SmaCrossState.BELOW, smaCrossState(fast, slow, 4))
    }

    @Test
    fun `smaCrossState is null for an out of range index`() {
        val line = listOf(1.0, 2.0)
        assertNull(smaCrossState(line, line, -1))
        assertNull(smaCrossState(line, line, 2))
    }

    @Test
    fun `smaCrossState reports EQUAL when the lines coincide`() {
        val line = listOf(100.0, 100.0)
        assertEquals(SmaCrossState.EQUAL, smaCrossState(line, line, 1))
        assertTrue(SmaCrossState.EQUAL.isKnown)
        assertFalse(SmaCrossState.UNKNOWN.isKnown)
    }

    @Test
    fun `isDeathCross fires on a hand built crossover`() {
        // sma50 wobbles around a flat sma200: 99 -> 101 -> 98 against a constant 100.
        // Walking backwards from the latest bar, the first ABOVE -> BELOW transition is at index
        // 4 (101 at index 3, 98 at index 4). The transition at index 2 is earlier, so a
        // most-recent-first search must report 4, not 2.
        val sma50 = listOf(null, null, 99.0, 101.0, 98.0)
        val sma200 = listOf(null, null, 100.0, 100.0, 100.0)
        assertTrue(isDeathCross(sma50, sma200))
        assertEquals(4, deathCrossBarIndex(sma50, sma200))
    }

    @Test
    fun `isDeathCross does not fire when the fast line is above or exactly equal`() {
        val sma200 = List(4) { 100.0 }
        assertFalse(isDeathCross(listOf(101.0, 102.0, 103.0, 104.0), sma200))
        assertFalse(isDeathCross(List(4) { 100.0 }, sma200), "equal lines are not a cross")
        assertNull(deathCrossBarIndex(List(4) { 100.0 }, sma200))
    }

    @Test
    fun `isDeathCross is false when either line has not warmed up`() {
        assertFalse(isDeathCross(listOf(90.0, 80.0), listOf(null, null)))
        assertFalse(isDeathCross(listOf(null, null), listOf(90.0, 80.0)))
        assertFalse(isDeathCross(emptyList(), emptyList()))
    }

    @Test
    fun `deathCrossBarIndex returns null when the pair never transitions`() {
        // Both lines stay above each other, so no crossover ever prints.
        assertNull(deathCrossBarIndex(List(4) { 2.0 }, List(4) { 1.0 }))
    }

    @Test
    fun `a monotonically falling series has a death cross but no observable crossover bar`() {
        // closes 500, 499, …, 281 over 220 bars.
        //   SMA50[219]  = mean(closes[170..219]) = mean(330..281) = 305.5
        //   SMA200[219] = mean(closes[20..219])  = mean(480..281) = 380.5
        // The flag fires (305.5 < 380.5), but the pair is already BELOW at the very first bar
        // where SMA200 exists (index 199), so no ABOVE -> BELOW transition is ever *observed*.
        val falling = TestSeries.falling(220)
        val sma50 = smaClose(falling, 50)
        val sma200 = smaClose(falling, 200)
        assertEquals(305.5, sma50[219])
        assertEquals(380.5, sma200[219])
        assertTrue(isDeathCross(sma50, sma200))
        assertNull(deathCrossBarIndex(sma50, sma200), "no transition is observable")
        assertEquals(SmaCrossState.BELOW, smaCrossState(sma50, sma200, 199))
    }

    @Test
    fun `a monotonically rising series has no death cross`() {
        // closes 100, 101, …, 309 over 210 bars.
        //   SMA50[209]  = mean(closes[160..209]) = mean(260..309) = 284.5
        //   SMA200[209] = mean(closes[10..209])  = mean(110..309) = 209.5
        val rising = TestSeries.rising(210)
        val sma50 = smaClose(rising, 50)
        val sma200 = smaClose(rising, 200)
        assertEquals(284.5, sma50[209])
        assertEquals(209.5, sma200[209])
        assertFalse(isDeathCross(sma50, sma200))
    }

    @Test
    fun `reading the pair in the opposite order flips the state`() {
        val sma50 = listOf(99.0)
        val sma200 = listOf(100.0)
        assertEquals(SmaCrossState.BELOW, smaCrossState(sma50, sma200, 0))
        assertEquals(SmaCrossState.ABOVE, smaCrossState(sma200, sma50, 0))
    }
}