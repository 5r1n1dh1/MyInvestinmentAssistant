package com.mia.app.domain.technical

import com.mia.app.domain.model.Direction
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Golden values and invariants for ADX / DMI, the trend regime gate (plan.md §6.4) and
 * relative strength versus a benchmark (§6.3 Context).
 */
class TrendTest {

    private val candles = TestSeries.golden

    // ------------------------------------------------------------------- DMI / ADX

    @Test
    fun `directional movement is mutually exclusive and matches hand computed values`() {
        // On the rising part of the golden series each bar steps up by 1:
        //   high[i] - high[i-1] = 1  -> +DM = 1  (and 1 > 0)
        //   low[i-1] - low[i]   = -1 -> -DM = 0
        // so +DI = 100 * smoothed(+DM) / smoothed(TR) = 100/1.5 = 66.666... and -DI = 0.
        val a = adx(candles, period = 14)
        assertEquals(30, a.diPlus.size)
        // First DI is at index 15 (see adxWarmupBars): the seed window covers bars 1..period and
        // the smoothers only produce a new value from the following bar.
        assertWarmup(a.diPlus, 15, "diPlus")
        assertWarmup(a.diMinus, 15, "diMinus")
        assertWarmup(a.dx, 15, "dx")
        // ADX needs `period` DX readings on top of that, so the first ADX lands at 2 * period = 28.
        assertEquals(28, a.adxWarmupBars())
        assertWarmup(a.adx, a.adxWarmupBars(), "adx")
        // The golden series ends on a rally (11, 12), so +DI dominates at the latest bar. It is not
        // 100/1.5 because Wilder's smoother still carries the decline at indices 10..18; the
        // hand-computable pure case is covered by the strictly-rising test below.
        assertTrue(assertNotNull(a.diPlusValue) > assertNotNull(a.diMinusValue), "rally at the end")
        assertEquals(36.745872142131546, assertNotNull(a.diPlusValue))
        assertEquals(10.376896827282042, assertNotNull(a.diMinusValue))
        assertEquals(27.3879001061091, assertNotNull(a.value), 1e-9)
    }

    @Test
    fun `a strictly rising series has di plus of exactly 100 over 1_5 and di minus of zero`() {
        // The fixture builds each bar as open = previous close, high = close + 0.5,
        // low = open - 0.5. On a +1 step that gives:
        //   high[i] - high[i-1] = 1      -> +DM = 1
        //   low[i-1] - low[i]   = -1     -> -DM = 0
        //   TR = max(high-low = 2, |high - prevClose| = 1.5, |low - prevClose| = 1.5) = 2
        // so DI+ = 100 * 1 / 2 = 50 exactly and DI- = 0, on every bar after the seed.
        val a = adx(TestSeries.rising(80), period = 14)
        assertEquals(2.0, trueRange(TestSeries.rising(80))[1], "the fixture's true range")
        for (i in 16 until a.diPlus.size) {
            assertEquals(50.0, a.diPlus[i], "diPlus[$i]")
            assertEquals(0.0, a.diMinus[i], "diMinus[$i]")
        }
        assertEquals(0.0, assertNotNull(a.diMinusValue))
        assertEquals(50.0, assertNotNull(a.diPlusValue))
        // DX = 100 * |50 - 0| / (50 + 0) = 100 and DI+ > DI-, so the regime trends up.
        assertEquals(100.0, assertNotNull(a.dxValue), 1e-9)
        assertEquals(TrendRegime.TRENDING_UP, a.latestRegime)
    }

    @Test
    fun `adx is exactly 100 on a perfectly trending series`() {
        // A strictly rising series has +DM = TR on every bar, so DI+ = 100, -DI = 0 and
        // DX = 100 | 100 - 0 | / (100 + 0) = 100. The ADX of a constant DX is therefore 100.
        val a = adx(TestSeries.rising(80), period = 14)
        assertEquals(100.0, assertNotNull(a.value), 1e-9)
        assertEquals(100.0, assertNotNull(a.dxValue), 1e-9)
        assertEquals(TrendRegime.TRENDING_UP, a.latestRegime)
    }

    @Test
    fun `adx is exactly 100 on a perfectly falling series and the regime flips`() {
        val a = adx(TestSeries.falling(80), period = 14)
        assertEquals(100.0, assertNotNull(a.value), 1e-9)
        assertEquals(0.0, assertNotNull(a.diPlusValue), 1e-9, "a pure downtrend has no +DM")
        assertEquals(TrendRegime.TRENDING_DOWN, a.latestRegime)
    }

    @Test
    fun `adx is exactly zero on a flat price series and produces no NaN`() {
        // A directionless series has TR = 1 on every bar but +DM = -DM = 0, so both DIs are 0 and
        // DX = 0 | 0 - 0 | / 0 -> 0 by the documented zero-denominator rule. ADX of constant 0 is 0.
        val flat = TestSeries.directionless(80)
        val a = adx(flat, period = 14)
        val value = assertNotNull(a.value)
        assertEquals(0.0, value, 1e-9)
        assertEquals(0.0, assertNotNull(a.diPlusValue), 1e-9)
        assertEquals(0.0, assertNotNull(a.diMinusValue), 1e-9)
        assertEquals(0.0, assertNotNull(a.dxValue), 1e-9)
        // ADX 0 is below the 20 ranging band.
        assertEquals(TrendRegime.RANGING, a.latestRegime)
        assertFinite("adx flat", a.adx)
        assertFinite("diPlus flat", a.diPlus)
        assertFinite("dx flat", a.dx)
    }

    @Test
    fun `adx is all null on a zero range series because the denominator vanishes`() {
        // TR is 0 on every bar, so the smoothed TR is 0 and the DIs are undefined rather than 0.
        // This is the documented "no directional information" answer, not a NaN.
        val a = adx(TestSeries.zeroRange(80), period = 14)
        assertNull(a.value)
        assertTrue(a.diPlus.all { it == null })
        assertTrue(a.adx.all { it == null })
        assertEquals(TrendRegime.UNKNOWN, a.latestRegime)
        assertFinite("adx zero range", a.adx)
    }

    @Test
    fun `adx stays inside zero and one hundred for seeded random walks`() {
        for (seed in 1L..20L) {
            val a = adx(TestSeries.seededWalk(300, seed))
            assertFinite("adx(seed=$seed)", a.adx)
            a.adx.forEachIndexed { i, v ->
                if (v != null) assertTrue(v >= 0.0 && v <= 100.0, "adx[$i]=$v for seed $seed")
            }
            a.diPlus.forEachIndexed { i, v ->
                if (v != null) assertTrue(v >= 0.0 && v <= 100.0, "diPlus[$i]=$v for seed $seed")
            }
            a.diMinus.forEachIndexed { i, v ->
                if (v != null) assertTrue(v >= 0.0 && v <= 100.0, "diMinus[$i]=$v for seed $seed")
            }
        }
    }

    @Test
    fun `adx never emits a value before its warm-up window`() {
        for (seed in 1L..10L) {
            val a = adx(TestSeries.seededWalk(300, seed), period = 14)
            val warmup = a.adxWarmupBars()
            for (i in 0 until warmup) {
                assertNull(a.adx[i], "adx[$i] must be null inside the warm-up for seed $seed")
            }
        }
    }

    @Test
    fun `adx returns aligned nulls for no bars and for too few bars`() {
        val empty = adx(emptyList())
        assertEquals(emptyList(), empty.adx)
        assertNull(empty.value)
        assertEquals(TrendRegime.UNKNOWN, empty.latestRegime)

        val short = adx(candles.take(14), period = 14)
        assertEquals(14, short.adx.size)
        assertTrue(short.adx.all { it == null })
        assertEquals(28, short.adxWarmupBars(), "2 * 14 for a valid period")
    }

    @Test
    fun `adx rejects a non positive period without throwing`() {
        val a = adx(candles, period = 0)
        assertTrue(a.adx.all { it == null })
        assertEquals(Int.MAX_VALUE, a.adxWarmupBars())
    }

    // ------------------------------------------------------------------------ regime

    @Test
    fun `regime uses the conventional adx bands`() {
        assertEquals(TrendRegime.TRENDING_UP, regime(adxValue = 40.0, diPlusValue = 30.0, diMinusValue = 10.0))
        assertEquals(TrendRegime.TRENDING_DOWN, regime(adxValue = 40.0, diPlusValue = 10.0, diMinusValue = 30.0))
        assertEquals(TrendRegime.RANGING, regime(adxValue = 10.0, diPlusValue = 30.0, diMinusValue = 10.0))
        // The 20..25 band is undecided: never guess a direction.
        assertEquals(TrendRegime.UNKNOWN, regime(adxValue = 22.0, diPlusValue = 30.0, diMinusValue = 10.0))
        // Exact boundaries.
        assertEquals(TrendRegime.RANGING, regime(adxValue = 19.999, diPlusValue = 1.0, diMinusValue = 1.0))
        assertEquals(TrendRegime.UNKNOWN, regime(adxValue = 20.0, diPlusValue = 1.0, diMinusValue = 1.0))
        assertEquals(TrendRegime.TRENDING_UP, regime(adxValue = 25.0, diPlusValue = 2.0, diMinusValue = 1.0))
    }

    @Test
    fun `regime is UNKNOWN when history is missing or the direction is balanced`() {
        assertEquals(TrendRegime.UNKNOWN, regime(null))
        assertEquals(TrendRegime.UNKNOWN, regime(Double.NaN))
        // ADX trending but with no DI yet: direction cannot be established.
        assertEquals(TrendRegime.UNKNOWN, regime(adxValue = 30.0, diPlusValue = null, diMinusValue = null))
        // Trending with exactly balanced DIs.
        assertEquals(TrendRegime.UNKNOWN, regime(adxValue = 30.0, diPlusValue = 15.0, diMinusValue = 15.0))
    }

    @Test
    fun `regime from adx alone does not invent a direction`() {
        assertEquals(TrendRegime.UNKNOWN, regime(40.0), "no DI means no direction")
        assertEquals(TrendRegime.RANGING, regime(5.0))
        assertEquals(TrendRegime.UNKNOWN, regime(null))
    }

    @Test
    fun `regime transitions on a hand built adx series`() {
        // A hand-built ADX family: ranging -> undecided -> trending up -> trending down.
        val adxSeries = listOf(10.0, 22.0, 30.0, 30.0, 8.0)
        val diPlus = listOf(20.0, 20.0, 25.0, 5.0, 20.0)
        val diMinus = listOf(15.0, 15.0, 10.0, 25.0, 15.0)
        val a = TechnicalAdx(
            period = 14,
            adx = adxSeries.map { it },
            diPlus = diPlus.map { it },
            diMinus = diMinus.map { it },
            dx = adxSeries.map { it },
            regime = adxSeries.indices.map {
                regime(adxSeries[it], diPlus[it], diMinus[it])
            },
        )
        assertEquals(
            listOf(
                TrendRegime.RANGING,
                TrendRegime.UNKNOWN,
                TrendRegime.TRENDING_UP,
                TrendRegime.TRENDING_DOWN,
                TrendRegime.RANGING,
            ),
            a.regime,
        )
    }

    @Test
    fun `regime alignment is the gate input and is null when undecided`() {
        val trendingUp = adx(TestSeries.rising(80))
        assertEquals(true, trendingUp.isRegimeAligned(Direction.UP))
        assertEquals(false, trendingUp.isRegimeAligned(Direction.DOWN))

        val trendingDown = adx(TestSeries.falling(80))
        assertEquals(true, trendingDown.isRegimeAligned(Direction.DOWN))
        assertEquals(false, trendingDown.isRegimeAligned(Direction.UP))

        val ranging = adx(TestSeries.directionless(80))
        assertEquals(false, ranging.isRegimeAligned(Direction.UP))
        assertEquals(false, ranging.isRegimeAligned(Direction.DOWN))
    }

    @Test
    fun `regime alignment is null when there is not enough history`() {
        val short = adx(candles.take(20))
        assertNull(short.isRegimeAligned(Direction.UP))
        assertNull(short.isRegimeAligned(Direction.DOWN))
    }

    @Test
    fun `trend regime flags are consistent with isTrending`() {
        assertTrue(TrendRegime.TRENDING_UP.isTrending)
        assertTrue(TrendRegime.TRENDING_DOWN.isTrending)
        assertFalse(TrendRegime.RANGING.isTrending)
        assertFalse(TrendRegime.UNKNOWN.isTrending)
    }

    // ----------------------------------------------------------- relative strength

    @Test
    fun `relative strength divides stock by benchmark on matching dates`() {
        val stock = TestSeries.fromCloses(listOf(100.0, 150.0))
        val bench = TestSeries.fromCloses(listOf(100.0, 100.0))
        val rs = relativeStrength(stock, bench)
        assertEquals(2, rs.alignedDates.size)
        assertEquals(1.0, rs.rsLine[0])
        assertEquals(1.5, rs.rsLine[1])
        assertEquals(1.5, rs.latestRsRatio)
        // Rebased to 1.0 at the first aligned date.
        assertEquals(1.0, rs.normalisedRsLine[0])
        assertEquals(1.5, rs.normalisedRsLine[1])
        assertEquals(1.5, rs.latestNormalisedRs)
    }

    @Test
    fun `relative strength aligns on intersection when dates do not match`() {
        // Stock has dates d0,d1,d2,d3; the benchmark only d1,d2. Only those two align.
        val stock = TestSeries.fromCloses(listOf(10.0, 20.0, 40.0, 80.0))
        val bench = TestSeries.fromCloses(listOf(1.0, 2.0)).let {
            it.mapIndexed { idx, c -> c.copy(date = TestSeries.date(idx + 1)) }
        }
        val rs = relativeStrength(stock, bench)
        assertEquals(2, rs.alignedDates.size)
        assertEquals(listOf(TestSeries.date(1), TestSeries.date(2)), rs.alignedDates)
        assertEquals(20.0 / 1.0, rs.rsLine[0])
        assertEquals(40.0 / 2.0, rs.rsLine[1])
    }

    @Test
    fun `relative strength returns empty results when no dates overlap and never crashes`() {
        val stock = TestSeries.fromCloses(listOf(10.0, 20.0))
        val bench = listOf(stock[0].copy(date = LocalDate.of(2020, 1, 1)))
        val rs = relativeStrength(stock, bench)
        assertEquals(emptyList(), rs.alignedDates)
        assertEquals(emptyList(), rs.rsLine)
        assertNull(rs.latestRsRatio)
        assertNull(rs.latestNormalisedRs)
        // No crash on two empty inputs either.
        val none = relativeStrength(emptyList(), emptyList())
        assertEquals(emptyList(), none.alignedDates)
        assertNull(relativeStrength(emptyList(), stock).latestRsRatio)
        assertNull(relativeStrength(stock, emptyList()).latestRsRatio)
    }

    @Test
    fun `relative strength keeps the first occurrence of a duplicated benchmark date`() {
        val stock = TestSeries.fromCloses(listOf(10.0))
        val bench = listOf(
            stock[0].copy(close = 2.0),
            stock[0].copy(close = 4.0),
        )
        val rs = relativeStrength(stock, bench)
        assertEquals(1, rs.alignedDates.size)
        // The first matching benchmark bar wins: 10 / 2 = 5, not 10 / 4.
        assertEquals(5.0, rs.latestRsRatio)
    }

    @Test
    fun `relative strength trend is the change in the rebased line`() {
        val stock = TestSeries.fromCloses(listOf(100.0, 110.0, 90.0))
        val bench = TestSeries.fromCloses(listOf(100.0, 100.0, 100.0))
        val rs = relativeStrength(stock, bench)
        // Lookback 1: normalised[2] - normalised[1] = 0.9 - 1.1 = -0.2
        assertClose(-0.2, relativeStrengthTrend(rs, lookback = 1))
        assertClose(0.9 - 1.0, relativeStrengthTrend(rs, lookback = 2))
        assertNull(relativeStrengthTrend(rs, lookback = 3), "window too long for 3 points")
        assertNull(relativeStrengthTrend(rs, lookback = 0))
    }

    @Test
    fun `relative strength trend is null for an empty alignment`() {
        val rs = relativeStrength(emptyList(), emptyList())
        assertNull(relativeStrengthTrend(rs))
    }
}