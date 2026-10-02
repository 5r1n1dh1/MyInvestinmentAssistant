package com.mia.app.domain.technical

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Golden values and invariants for True Range, ATR and Bollinger Bands.
 */
class VolatilityTest {

    private val candles = TestSeries.golden
    private val closes = TestSeries.GOLDEN_CLOSES

    // -------------------------------------------------------------------- true range

    @Test
    fun `true range on the golden series is exactly two on every bar after the first`() {
        // open[i] = close[i-1], so a rising bar has high = close+0.5, low = open-0.5 = close-1.5,
        // giving high-low = 2.0; a falling bar has high = open+0.5 = close+1.5 and low = close-0.5,
        // also 2.0. The previous-close terms are only 1.5 and 0.5, so they never win.
        // Bar 0 has no previous close, so TR = high - low = 1.5 - 0.5 = 1.0.
        val tr = trueRange(candles)
        assertEquals(30, tr.size)
        assertEquals(1.0, tr[0])
        for (i in 1..29) {
            assertEquals(2.0, tr[i], "trueRange[$i]")
        }
        assertTrue(tr.all { it >= 0.0 })
    }

    @Test
    fun `true range is zero on a bar with no intraday range`() {
        val flat = TestSeries.zeroRange(5)
        assertEquals(List(5) { 0.0 }, trueRange(flat))
    }

    @Test
    fun `true range is empty for no bars`() {
        assertEquals(emptyList(), trueRange(emptyList()))
    }

    // -------------------------------------------------------------------------- ATR

    @Test
    fun `atr warm-up matches the sma of the first period true ranges`() {
        val a = atr(candles, period = 14)
        assertEquals(30, a.size)
        assertWarmup(a, 13, "atr14")
        // Seed = (TR[0] + TR[1..13]) / 14 = (1.0 + 13 * 2.0) / 14 = 27/14.
        assertClose(27.0 / 14.0, a[13])
        // Next bar: (27/14 * 13 + 2) / 14 = (351/14 + 28/14) / 14 = 379/196.
        assertClose(379.0 / 196.0, a[14])
    }

    @Test
    fun `atr is exactly zero on a directionless series and never negative`() {
        val a = atr(TestSeries.directionless(40), period = 14)
        assertWarmup(a, 13, "atr on a flat series")
        // True range is 1.0 on every bar, so ATR settles on exactly 1.0.
        assertClose(1.0, a[13])
        assertClose(1.0, a.last())
        a.forEach { if (it != null) assertTrue(it >= 0.0) }
    }

    @Test
    fun `atr is exactly zero when the true range is zero everywhere`() {
        val a = atr(TestSeries.zeroRange(40), period = 14)
        assertEquals(0.0, a[13])
        assertEquals(0.0, a.last())
        assertFinite("atr", a)
    }

    @Test
    fun `atr returns all nulls when there are fewer bars than the period`() {
        assertTrue(atr(candles.take(13), period = 14).all { it == null })
        assertTrue(atr(candles, period = 0).all { it == null })
        assertEquals(emptyList(), atr(emptyList()))
    }

    @Test
    fun `atr stays finite and non negative for seeded random walks`() {
        for (seed in 1L..20L) {
            val a = atr(TestSeries.seededWalk(300, seed))
            assertFinite("atr(seed=$seed)", a)
            a.forEachIndexed { i, v ->
                if (v != null) assertTrue(v >= 0.0, "atr[$i] = $v for seed $seed")
            }
        }
    }

    @Test
    fun `atrPercent is a percentage of the close and null where the close is zero`() {
        val pct = atrPercent(candles, period = 14)
        assertWarmup(pct, 13, "atrPercent")
        // ATR[13] = 27/14 = 1.9285714285714286 and close[13] = 6 (the golden series' falling leg runs
        // 9, 8, 7, 6 at indices 10..13), so 1.9285714285714286 / 6 * 100 = 32.142857142857146.
        assertClose(1.9285714285714286 / 6.0 * 100.0, pct[13])
        assertClose(32.142857142857146, pct[13])
        assertFinite("atrPercent", pct)
    }

    // --------------------------------------------------------------------- Bollinger

    @Test
    fun `bollinger is null before period minus one`() {
        val b = bollinger(candles, period = 20, multiplier = 2.0)
        assertWarmup(b.middle, 19, "bollinger middle")
        assertWarmup(b.upper, 19, "bollinger upper")
        assertWarmup(b.lower, 19, "bollinger lower")
        assertWarmup(b.bandwidthPercent, 19, "bollinger bandwidth")
        assertWarmup(b.percentB, 19, "bollinger percentB")
    }

    @Test
    fun `bollinger middle matches the 20-period sma on the golden series`() {
        val b = bollinger(candles, period = 20)
        // SMA(20) at index 19: closes[0..19] = 1..10 then 9,8,7,6,5,4,3,2,1,2
        //   sum(1..10) = 55, sum(9..1) = 45, plus the final 2 -> 102, so 102/20 = 5.1
        assertEquals(5.1, b.middle[19])
        // SMA(20) at index 29: closes[10..29] = 9..1 (45) then 2..12 (77) -> 122, so 122/20 = 6.1
        assertEquals(6.1, b.middle[29])
        assertEquals(sma(closes, 20), b.middle)
    }

    @Test
    fun `bollinger bands match the hand computed sample stdev on a five point window`() {
        // closes[0..4] = 1,2,3,4,5 -> mean 3.0, deviations -2,-1,0,1,2, squares 4+1+0+1+4 = 10,
        // sample variance = 10/(5-1) = 2.5, stdev = sqrt(2.5) = 1.5811388300841898.
        //   middle = 3.0
        //   upper  = 3 + 2*sqrt(2.5) = 6.162277660168379
        //   lower  = 3 - 2*sqrt(2.5) = -0.16227766016837952
        //   width  = 4*sqrt(2.5)     = 6.324555320336758
        //   bandwidth% = 6.324555320336758 / 3 * 100 = 210.81851067789193
        //   percentB at index 4 (close 5) = (5 - lower) / width
        //                                    = (5 - (-0.16227766016837952)) / 6.324555320336758
        //                                    = 5.1622776601683795 / 6.324555320336758
        //                                    = 0.8162277660168379
        val b = bollinger(candles, period = 5, multiplier = 2.0)
        val sd = Math.sqrt(2.5)
        assertEquals(3.0, b.middle[4])
        assertClose(3.0 + 2 * sd, b.upper[4], 1e-12)
        assertClose(3.0 - 2 * sd, b.lower[4], 1e-12)
        assertClose(4 * sd / 3.0 * 100.0, b.bandwidthPercent[4], 1e-9)
        assertClose((5.0 - (3.0 - 2 * sd)) / (4 * sd), b.percentB[4], 1e-12)
        assertClose(0.8162277660168379, b.percentB[4], 1e-12)
        // The latest reading is index 29, whose 5-bar window is (8+9+10+11+12)/5 = 10.0.
        val latest = assertNotNull(b.latest())
        assertEquals(10.0, latest.middle)
    }

    @Test
    fun `bollinger upper is never below middle and middle is never below lower`() {
        for (seed in 1L..20L) {
            val b = bollinger(TestSeries.seededWalk(300, seed))
            for (i in b.middle.indices) {
                val mid = b.middle[i] ?: continue
                val up = b.upper[i] ?: continue
                val lo = b.lower[i] ?: continue
                assertTrue(up >= mid, "upper[$i]=$up < middle=$mid for seed $seed")
                assertTrue(mid >= lo, "middle[$i]=$mid < lower=$lo for seed $seed")
                assertTrue(b.bandwidthPercent[i]!! >= 0.0)
            }
            assertFinite("bollinger upper(seed=$seed)", b.upper)
            assertFinite("bollinger lower(seed=$seed)", b.lower)
            assertFinite("bollinger %B(seed=$seed)", b.percentB)
        }
    }

    @Test
    fun `percentB is exactly one half on a zero width band instead of NaN`() {
        // A constant series drives the stdev to exactly 0, so upper == lower == middle.
        val b = bollinger(TestSeries.directionless(40), period = 20)
        assertEquals(100.0, b.middle[39])
        // The stdev of a constant series is exactly 0, so the bands coincide with the middle.
        assertEquals(100.0, b.upper[39])
        assertEquals(100.0, b.lower[39])
        // The close sits exactly on the middle band, which is the continuous answer.
        assertEquals(0.5, b.percentB[39])
        assertEquals(0.0, b.bandwidthPercent[39])
        assertFinite("zero width percentB", b.percentB)
    }

    @Test
    fun `percentB is not clamped so a breakout close above the band stays above one`() {
        // A single 30% spike on an otherwise flat series. Inside a 20-bar window ending on the
        // spike the mean is 101.5 and the sample stdev of one 130 against nineteen 100s is
        // sqrt(855/19) = 6.7082, so upper = 101.5 + 13.4164 = 114.9 and the close sits well above
        // it. %B is therefore > 1 — the breakout case the app must surface, not a clamped 1.0.
        val closes = List(20) { 100.0 } + listOf(130.0)
        val b = bollinger(TestSeries.fromCloses(closes), period = 20, multiplier = 2.0)
        val mid = assertNotNull(b.middle[20])
        val up = assertNotNull(b.upper[20])
        val lo = assertNotNull(b.lower[20])
        assertClose(101.5, mid, 1e-9)
        assertClose(101.5 + 2.0 * Math.sqrt(855.0 / 19.0), up, 1e-9)
        assertClose(101.5 - 2.0 * Math.sqrt(855.0 / 19.0), lo, 1e-9)
        val pct = assertNotNull(b.percentB[20])
        assertClose((130.0 - lo) / (up - lo), pct, 1e-12)
        assertTrue(pct > 1.0, "percentB=$pct should exceed 1 above the upper band")
        assertFalse(pct.isInfinite())
    }

    @Test
    fun `bollinger squeeze is reported when the bands are tight`() {
        // A constant series has the narrowest possible bands.
        val b = bollinger(TestSeries.directionless(60), period = 20)
        val latest = assertNotNull(b.latest())
        assertTrue(latest.isSqueeze(10.0), "bandwidth ${latest.bandwidthPercent} should be a squeeze")
        assertFalse(latest.isSqueeze(-1.0))
    }

    @Test
    fun `bollinger rejects bad parameters without throwing`() {
        assertTrue(bollinger(candles, period = 0).middle.all { it == null })
        assertTrue(bollinger(candles, multiplier = -1.0).upper.all { it == null })
        assertEquals(30, bollinger(candles, period = 0).middle.size)
        assertNull(bollinger(emptyList()).latest())
    }
}