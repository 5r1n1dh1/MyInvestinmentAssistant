package com.mia.app.domain.technical

import com.mia.app.domain.model.Candle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Golden values and invariants for Wilder's RSI and MACD.
 *
 * Two different hand-built series are used, each chosen so the arithmetic is exact:
 *
 * * [TestSeries.GOLDEN_CLOSES] — changes of exactly ±1, which makes the MACD line collapse to
 *   a constant on the rising ramp.
 * * `alternating` — closes 100/101/100/101…, so Wilder's smoothed averages of ±1 steps stay
 *   exact binary fractions and RSI lands on values like 50.0, 25.0 and 62.5.
 */
class OscillatorsTest {

    private val closes = TestSeries.GOLDEN_CLOSES
    private val candles = TestSeries.golden

    // ------------------------------------------------------------------------------ RSI

    @Test
    fun `rsi leaves the first period bars null because it needs period changes`() {
        val r = rsi(candles, period = 14)
        assertEquals(30, r.rsi.size)
        // The first RSI is at index 14: changes exist for indices 1..14, which is 14 of them.
        assertWarmup(r.rsi, 14, "rsi14")
        assertEquals(14, rsiWarmupBars(14))
        assertEquals(Int.MAX_VALUE, rsiWarmupBars(0))
    }

    @Test
    fun `rsi seed matches the hand computed Wilder averages on the golden series`() {
        // closes 1..10 then down to 1, so over changes i = 1..14:
        //   gains  = +1 for i = 1..9, 0 for i = 10..14  -> sum 9  -> avgGain = 9/14
        //   losses =  0 for i = 1..9, 1 for i = 10..14  -> sum 5  -> avgLoss = 5/14
        //   RS = (9/14) / (5/14) = 9/5 = 1.8
        //   RSI = 100 - 100/(1 + 1.8) = 100 - 100/2.8 = 100 - 35.714285714... = 64.2857142857
        val r = rsi(candles, period = 14)
        assertEquals(9.0 / 14.0, r.averageGain[14], "nine gains of +1 over a window of 14")
        assertEquals(5.0 / 14.0, r.averageLoss[14], "five losses of -1 over a window of 14")
        assertClose(64.28571428571429, r.rsi[14])
        assertClose(100.0 - 100.0 / (1.0 + 9.0 / 5.0), r.rsi[14])
    }

    @Test
    fun `rsi matches exact hand computed values on an alternating plus minus one series`() {
        // Closes alternate 100, 101, 100, 101, … so every change is exactly ±1.
        // With period = 2 Wilder smooths with alpha = 1/2, i.e. a plain average of the last two:
        //   i=2  (seed, changes +1 then -1)  avgGain = 0.5    avgLoss = 0.5     RS = 1     RSI = 100-100/2 = 50.0
        //   i=3  (change +1)  avgGain = (0.5+1)/2   = 0.75   avgLoss = 0.25    RS = 3     RSI = 100-100/4 = 75.0
        //   i=4  (change -1)  avgGain = 0.375       avgLoss = (0.25+1)/2 = 0.625
        //                          RS = 0.6                                    RSI = 100-100/1.6 = 37.5
        //   i=5  (change +1)  avgGain = (0.375+1)/2 = 0.6875 avgLoss = 0.3125  RS = 2.2
        //                          1 + RS = 3.2                               RSI = 100-100/3.2 = 68.75
        //   i=6  (change -1)  avgGain = 0.34375     avgLoss = (0.3125+1)/2 = 0.65625
        //                          RS = 11/21, 1 + RS = 32/21, 100*21/32 = 65.625  RSI = 34.375
        // Every value is an exact binary fraction, so these are asserted with no tolerance.
        val r = rsi(TestSeries.alternating(12), period = 2)
        assertEquals(50.0, r.rsi[2])
        assertEquals(75.0, r.rsi[3])
        assertEquals(37.5, r.rsi[4])
        assertEquals(68.75, r.rsi[5])
        assertEquals(34.375, r.rsi[6])
        assertEquals(0.5, r.averageGain[2])
        assertEquals(0.5, r.averageLoss[2])
        assertEquals(0.75, r.averageGain[3])
        assertEquals(0.25, r.averageLoss[3])
        assertEquals(0.6875, r.averageGain[5])
        assertEquals(0.3125, r.averageLoss[5])
    }

    @Test
    fun `rsi is exactly 100 when there are no losses at all`() {
        // Documented convention: avgLoss == 0 has no finite RS, so RSI saturates at 100.
        val rising = TestSeries.rising(30)
        val r = rsi(rising, period = 14)
        assertEquals(100.0, r.rsi[14])
        assertEquals(100.0, r.rsi.last())
        assertEquals(0.0, r.averageLoss[14])
        assertTrue(r.isOverbought)
        assertFalse(r.isOversold)
    }

    @Test
    fun `rsi is exactly 0 when there are no gains at all`() {
        val r = rsi(TestSeries.falling(30), period = 14)
        assertEquals(0.0, r.rsi[14])
        assertEquals(0.0, r.rsi.last())
        assertEquals(0.0, r.averageGain[14])
        assertTrue(r.isOversold)
        assertFalse(r.isOverbought)
    }

    @Test
    fun `a perfectly flat series produces the documented saturation rather than NaN`() {
        val r = rsi(TestSeries.directionless(30), period = 14)
        assertFinite("rsi", r.rsi)
        assertFinite("avgGain", r.averageGain)
        assertFinite("avgLoss", r.averageLoss)
        // Both averages are 0, so the avgLoss == 0 branch fires and RSI saturates at 100. That
        // is the textbook  x/0 -> +Inf  limit, and it is an artefact rather than momentum: there
        // is no trend here at all. A hole in the line would look broken on the chart, so the
        // value is emitted and the flatness is caught by the ADX regime instead.
        assertEquals(0.0, r.averageGain[14])
        assertEquals(0.0, r.averageLoss[14])
        assertEquals(100.0, r.rsi[14])
        assertEquals(100.0, r.rsi.last())
    }

    @Test
    fun `rsi stays inside zero and one hundred for every seeded random walk`() {
        for (seed in 1L..25L) {
            val walk = TestSeries.seededWalk(260, seed)
            val r = rsi(walk, period = 14)
            assertFinite("rsi(seed=$seed)", r.rsi)
            r.rsi.forEachIndexed { i, v ->
                if (v != null) {
                    assertTrue(v >= 0.0 && v <= 100.0, "rsi[$i] = $v out of [0,100] for seed $seed")
                }
            }
        }
    }

    @Test
    fun `rsi exposes only the latest value when the series is short`() {
        val r = rsi(candles.take(5))
        assertNull(r.value)
        assertFalse(r.isOverbought)
        assertFalse(r.isOversold)
    }

    @Test
    fun `rsi returns an empty aligned result for no bars and a null-only one for too few`() {
        val empty = rsi(emptyList<Candle>())
        assertEquals(emptyList(), empty.rsi)
        assertNull(empty.value)

        val short = rsi(candles.take(14), period = 14)
        assertEquals(14, short.rsi.size)
        assertTrue(short.rsi.all { it == null })
    }

    @Test
    fun `rsi tolerates a non positive period without throwing`() {
        val r = rsi(candles, period = 0)
        assertTrue(r.rsi.all { it == null })
    }

    @Test
    fun `rsi over candles agrees with rsi over the raw close series`() {
        assertEquals(rsi(candles, 14), rsi(closes, 14))
        assertEquals(rsi(candles, 14), rsiOf(closes, 14))
    }

    // --------------------------------------------------------------------- divergence

    @Test
    fun `a lower price low with a higher rsi low is a bullish divergence`() {
        // Price makes a lower low (10 -> 9) while RSI makes a higher low (20 -> 40).
        val series = listOf(30.0, 20.0, 30.0, 40.0)
        assertTrue(
            isRsiDivergence(series, earlierIndex = 1, laterIndex = 3, earlierPrice = 10.0, laterPrice = 9.0),
        )
    }

    @Test
    fun `a higher price high with a lower rsi high is a bearish divergence`() {
        // Price makes a higher high (9 -> 10) while RSI makes a lower high (40 -> 20).
        val series = listOf(30.0, 40.0, 30.0, 20.0)
        assertTrue(
            isRsiDivergence(series, earlierIndex = 1, laterIndex = 3, earlierPrice = 9.0, laterPrice = 10.0),
        )
    }

    @Test
    fun `a move that agrees on both legs is not a divergence`() {
        val series = listOf(50.0, 40.0, 30.0)
        // Price lower and RSI lower: momentum simply followed price.
        assertFalse(isRsiDivergence(series, 0, 2, earlierPrice = 10.0, laterPrice = 9.0))
        // Price higher and RSI higher.
        assertFalse(isRsiDivergence(series, 2, 0, earlierPrice = 9.0, laterPrice = 10.0))
        // Flat price is never a divergence.
        assertFalse(isRsiDivergence(series, 0, 2, earlierPrice = 10.0, laterPrice = 10.0))
        // Flat RSI is never a divergence.
        assertFalse(isRsiDivergence(listOf(30.0, 30.0, 30.0), 0, 2, 10.0, 9.0))
        // Equal price and RSI is not a divergence either.
        assertFalse(isRsiDivergence(listOf(30.0, 40.0, 30.0), 0, 2, 10.0, 10.0))
    }

    @Test
    fun `divergence is false when an rsi read is inside the warm-up window`() {
        val real = rsi(candles, 14).rsi
        assertFalse(isRsiDivergence(real, 0, 20, earlierPrice = 5.0, laterPrice = 4.0))
        assertFalse(isRsiDivergence(real, 20, 0, earlierPrice = 4.0, laterPrice = 5.0))
        assertFalse(isRsiDivergence(real, -1, 20, 5.0, 4.0))
        assertFalse(isRsiDivergence(real, 0, 999, 5.0, 4.0))
    }

    // ------------------------------------------------------------------------------ MACD

    @Test
    fun `macd warm-up is bounded by the slower ema and the signal ema`() {
        val m = macd(candles, fast = 3, slow = 6, signal = 3)
        // The MACD line starts where both EMAs exist: slow - 1 = 5.
        assertWarmup(m.macd, 5, "macd")
        // The signal line needs 3 MACD values, so it starts at 5 + 3 - 1 = 7.
        assertWarmup(m.signalLine, 7, "signal")
        assertWarmup(m.histogram, 7, "histogram")
        assertEquals(m.macd.size, m.signalLine.size)
        assertEquals(m.macd.size, m.histogram.size)
    }

    @Test
    fun `macd matches hand computed values on the golden series`() {
        // fast = 3: alpha 0.5, seed ema3[2] = (1+2+3)/3 = 2.0, then ema3[i] = 0.5*close[i] + 0.5*ema3[i-1]
        //   ema3[3..9] = 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0   (all exact)
        // slow = 6: alpha = 2/7, seed ema6[5] = (1+2+3+4+5+6)/6 = 3.5   (exact)
        //   ema6[6] = (2/7)*7 + (5/7)*3.5 = 4.5
        //   ema6[7] = (2/7)*8 + (5/7)*4.5 = 5.5
        //   ema6[8] = (2/7)*9 + (5/7)*5.5 = 6.5
        //   ema6[9] = (2/7)*10 + (5/7)*6.5 = 7.5
        // MACD[i] = ema3[i] - ema6[i]. Both EMAs have slope 1 and the same one-bar lag offset, so
        // the MACD line is a constant 1.5 across the whole ramp:
        //   macd[5] = 5 - 3.5 = 1.5   macd[6] = 6 - 4.5 = 1.5   ...   macd[9] = 9 - 7.5 = 1.5
        // macd[5] is exact (alpha 0.5 and the seed 21/6 are both exact); the rest carry the 2/7
        // rounding, so they are asserted to 1e-9 against the exact rational.
        val m = macd(candles, fast = 3, slow = 6, signal = 3)
        assertEquals(1.5, m.macd[5], "macd[5] = ema3[5] - ema6[5] = 5.0 - 3.5, both exact")
        assertClose(1.5, m.macd[6])
        assertClose(1.5, m.macd[7])
        assertClose(1.5, m.macd[8])
        assertClose(1.5, m.macd[9])
        // The signal EMA is seeded with the mean of the first three MACD values, all 1.5.
        assertClose(1.5, m.signalLine[7])
        assertClose(0.0, m.histogram[7])
        assertClose(1.5, m.signalLine[8])
        assertClose(0.0, m.histogram[8])
        // Index 10 breaks the ramp: the close drops 10 -> 9.
        //   ema3[10] = 0.5*9 + 0.5*9                     = 9.0
        //   ema6[10] = (2/7)*9 + (5/7)*7.5 = 55.5/7      = 7.928571428571429
        //   macd[10] = 9 - 7.928571428571429              = 1.0714285714285714 = 15/14
        //   signal[10] = 0.5*1.0714285714285714 + 0.5*1.5 = 1.2857142857142858 = 9/7
        //   hist[10]  = 15/14 - 9/7 = 15/14 - 18/14 = -3/14
        assertClose(15.0 / 14.0, m.macd[10])
        assertClose(9.0 / 7.0, m.signalLine[10])
        assertClose(-3.0 / 14.0, m.histogram[10])
        // index 11: close 8. ema3[11] = 0.5*8 + 0.5*9 = 8.5; ema6[11] = (2/7)*8 + (5/7)*7.9285714...
        //   = 2.2857142857 + 5.6632653061 = 7.9489795918 = 390/49
        //   macd[11] = 8.5 - 7.9489795918 = 0.5510204081632653
        assertClose(0.5510204081632653, m.macd[11])
        // The histogram identity macd - signal = histogram is exact at every bar.
        for (i in m.macd.indices) {
            val line = m.macd[i] ?: continue
            val sig = m.signalLine[i] ?: continue
            assertClose(line - sig, assertNotNull(m.histogram[i]), 1e-12, "bar $i")
        }
    }

    @Test
    fun `macd histogram at the latest bar is macd minus signal`() {
        val m = macd(candles, fast = 3, slow = 6, signal = 3)
        assertClose(assertNotNull(m.value) - assertNotNull(m.signal), assertNotNull(m.histogramValue))
        assertClose(1.3834805907414953, assertNotNull(m.value))
        assertClose(1.3121276599841984, assertNotNull(m.signal))
        assertClose(0.07135293075729694, assertNotNull(m.histogramValue))
        assertTrue(m.isBullishCrossover, "the golden series ends on a rally")
    }

    @Test
    fun `macd is bullish after a recovery and bearish after a roll over`() {
        // A sustained linear trend drives both EMAs onto the same line, so MACD is flat at
        // `a - b` for the whole ramp and sits exactly on its signal — the crossover test is
        // meaningless there. A *turn* is what separates them:
        //   Recovery: 60 down bars then 20 up bars  -> macd 19.181291975715624 > signal 13.956993084893151
        //   Roll over: 60 up bars then 20 down bars -> macd -19.18129197571568 < signal -13.956993084893176
        val recovery = macd(TestSeries.fromCloses(List(60) { 200.0 - it } + List(20) { i -> 141.0 + i * 5.0 }))
        assertClose(19.181291975715624, assertNotNull(recovery.value))
        assertClose(13.956993084893151, assertNotNull(recovery.signal))
        assertTrue(recovery.isBullishCrossover)
        assertTrue(assertNotNull(recovery.histogramValue) > 0.0)

        val rollover = macd(TestSeries.fromCloses(List(60) { 100.0 + it } + List(20) { i -> 159.0 - i * 5.0 }))
        assertClose(-19.18129197571568, assertNotNull(rollover.value))
        assertClose(-13.956993084893176, assertNotNull(rollover.signal))
        assertFalse(rollover.isBullishCrossover)
        assertTrue(assertNotNull(rollover.histogramValue) < 0.0)
    }

    @Test
    fun `macd is flat on a sustained linear trend because both emas converge`() {
        // On a perfect ramp the fast and slow EMAs are the same line offset by a constant, so
        // MACD is a constant 7.0 and the signal EMA has converged onto it. That is the correct
        // behaviour, and it is why no crossover fires.
        val m = macd(TestSeries.rising(120))
        assertClose(7.0, assertNotNull(m.value), 1e-9)
        assertClose(7.0, assertNotNull(m.signal), 1e-9)
    }

    @Test
    fun `macd with the default 12 26 9 parameters is null on a 30 bar series`() {
        // The MACD line needs both EMAs, so it starts at slow - 1 = 25. The signal line then needs
        // `signal` = 9 of those, landing at 33. With 30 bars the MACD line has 5 usable values,
        // so the line exists but the signal and histogram do not.
        val m = macd(candles)
        assertEquals(30, m.macd.size)
        assertWarmup(m.macd, 25, "macd(12,26,9)")
        assertNotNull(m.macd[25])
        assertTrue(m.signalLine.all { it == null }, "9 MACD values are needed, only 5 exist")
        assertTrue(m.histogram.all { it == null })
        assertNull(m.signal)
        assertNull(m.histogramValue)
        assertFalse(m.isBullishCrossover, "no signal means no crossover to report")
    }

    @Test
    fun `macd with the default parameters becomes available at bar 33`() {
        val long = TestSeries.seededWalk(60, seed = 7L)
        val m = macd(long)
        assertWarmup(m.macd, 25, "macd(12,26,9)")
        assertWarmup(m.signalLine, 33, "signal(9)")
        assertWarmup(m.histogram, 33, "histogram(9)")
        assertNull(m.macd[24])
        assertNull(m.signalLine[32])
        assertNotNull(m.macd[25])
        assertNotNull(m.signalLine[33])
        assertFinite("macd", m.macd)
        assertFinite("signal", m.signalLine)
        assertFinite("histogram", m.histogram)
    }

    @Test
    fun `macd rejects nonsensical parameters without throwing`() {
        // fast >= slow is not a MACD.
        assertTrue(macd(candles, fast = 26, slow = 12).macd.all { it == null })
        assertTrue(macd(candles, fast = 0).macd.all { it == null })
        assertTrue(macd(candles, signal = -1).macd.all { it == null })
        assertEquals(30, macd(candles, fast = 0).macd.size)
    }

    @Test
    fun `macd returns aligned empty results for no bars`() {
        val m = macd(emptyList())
        assertEquals(emptyList(), m.macd)
        assertEquals(emptyList(), m.signalLine)
        assertEquals(emptyList(), m.histogram)
    }

    @Test
    fun `macd is never NaN and histogram always equals macd minus signal`() {
        for (seed in 1L..15L) {
            val walk = TestSeries.seededWalk(200, seed)
            val m = macd(walk)
            for (i in m.macd.indices) {
                val line = m.macd[i]
                val sig = m.signalLine[i]
                val hist = m.histogram[i]
                if (line == null || sig == null || hist == null) continue
                assertClose(line - sig, hist, 1e-12, "seed=$seed index=$i")
            }
        }
    }
}