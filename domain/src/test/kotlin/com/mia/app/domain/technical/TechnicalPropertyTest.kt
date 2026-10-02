package com.mia.app.domain.technical

import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.Timeframe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Property / invariant tests for the whole indicator library, as required by plan.md §12.
 *
 * These run over **seeded** pseudo-random walks so a failure is always reproducible, and they
 * assert the invariants that must hold for *any* input, not just the golden series:
 *
 * * every output is aligned to the input and never longer or shorter;
 * * nothing is ever emitted from inside a warm-up window;
 * * RSI, ADX, DI+ and DI- live in `[0, 100]`, ATR is `>= 0`;
 * * Bollinger satisfies `upper >= middle >= lower`;
 * * nothing anywhere is `NaN` or infinite;
 * * insufficient history yields `null`, never an exception.
 */
class TechnicalPropertyTest {

    /** Seeded walks of a length that exercises every warm-up window (200-DMA included). */
    private fun walks(count: Int, seedFrom: Long = 1L, withDelivery: Boolean = false): List<List<Candle>> =
        (0 until count).map { TestSeries.seededWalk(280, seedFrom + it, withDelivery = withDelivery) }

    @Test
    fun `every series is aligned to the input length`() {
        for (walk in walks(12)) {
            val n = walk.size
            assertEquals(n, sma(walk, 20).size)
            assertEquals(n, ema(walk, 50).size)
            assertEquals(n, rsi(walk).rsi.size)
            assertEquals(n, rsi(walk).averageGain.size)
            assertEquals(n, rsi(walk).averageLoss.size)
            assertEquals(n, macd(walk).macd.size)
            assertEquals(n, macd(walk).signalLine.size)
            assertEquals(n, macd(walk).histogram.size)
            assertEquals(n, trueRange(walk).size)
            assertEquals(n, atr(walk).size)
            assertEquals(n, atrPercent(walk).size)
            assertEquals(n, obv(walk).size)
            assertEquals(n, volumeSma(walk).size)
            assertEquals(n, volumeRatio(walk).size)
            assertEquals(n, deliveryAveragePercent(walk).size)

            val b = bollinger(walk)
            assertEquals(n, b.middle.size)
            assertEquals(n, b.upper.size)
            assertEquals(n, b.lower.size)
            assertEquals(n, b.bandwidthPercent.size)
            assertEquals(n, b.percentB.size)

            val a = adx(walk)
            assertEquals(n, a.adx.size)
            assertEquals(n, a.diPlus.size)
            assertEquals(n, a.diMinus.size)
            assertEquals(n, a.dx.size)
            assertEquals(n, a.regime.size)
        }
    }

    @Test
    fun `no indicator ever emits a value from inside its warm up window`() {
        for (walk in walks(12)) {
            val n = walk.size
            // Simple and exponential moving averages.
            for (period in listOf(20, 50, 100, 200)) {
                val s = sma(walk, period)
                val e = ema(walk, period)
                assertWarmup(s, period - 1, "sma$period")
                assertWarmup(e, period - 1, "ema$period")
                assertEquals(n, s.size)
                assertEquals(n, e.size)
            }
            assertWarmup(rsi(walk).rsi, 14, "rsi")
            assertWarmup(rsi(walk).averageGain, 14, "rsi avgGain")
            assertWarmup(rsi(walk).averageLoss, 14, "rsi avgLoss")
            // MACD starts where both EMAs exist; the signal starts `signal` bars later.
            assertWarmup(macd(walk).macd, 25, "macd")
            assertWarmup(macd(walk).signalLine, 33, "macd signal")
            assertWarmup(macd(walk).histogram, 33, "macd histogram")
            assertWarmup(atr(walk), 13, "atr")
            assertWarmup(atrPercent(walk), 13, "atrPercent")
            assertWarmup(bollinger(walk).middle, 19, "bollinger middle")
            assertWarmup(bollinger(walk).upper, 19, "bollinger upper")
            assertWarmup(bollinger(walk).lower, 19, "bollinger lower")
            assertWarmup(volumeSma(walk), 19, "volumeSma")
            // ADX has the longest warm-up of all.
            val a = adx(walk)
            assertWarmup(a.adx, 2 * 14, "adx")
            assertWarmup(a.diPlus, 15, "diPlus")
            assertWarmup(a.diMinus, 15, "diMinus")
            assertWarmup(a.dx, 15, "dx")
            // Delivery additionally requires enough bars *and* enough coverage.
            assertWarmup(deliveryAveragePercent(walk), 19, "deliveryAverage")
        }
    }

    @Test
    fun `rsi stays inside zero and one hundred`() {
        for (walk in walks(20)) {
            rsi(walk).rsi.forEachIndexed { i, v ->
                if (v == null) return@forEachIndexed
                assertTrue(v in 0.0..100.0, "rsi[$i] = $v escaped [0,100]")
            }
        }
    }

    @Test
    fun `adx diPlus and diMinus stay inside zero and one hundred`() {
        for (walk in walks(20)) {
            val a = adx(walk)
            a.adx.forEachIndexed { i, v -> if (v != null) assertTrue(v in 0.0..100.0, "adx[$i]=$v") }
            a.diPlus.forEachIndexed { i, v -> if (v != null) assertTrue(v in 0.0..100.0, "diPlus[$i]=$v") }
            a.diMinus.forEachIndexed { i, v -> if (v != null) assertTrue(v in 0.0..100.0, "diMinus[$i]=$v") }
            a.dx.forEachIndexed { i, v -> if (v != null) assertTrue(v in 0.0..100.0, "dx[$i]=$v") }
        }
    }

    @Test
    fun `atr is never negative and true range is never negative`() {
        for (walk in walks(20)) {
            trueRange(walk).forEachIndexed { i, v ->
                assertTrue(v >= 0.0, "trueRange[$i] = $v")
            }
            atr(walk).forEachIndexed { i, v -> if (v != null) assertTrue(v >= 0.0, "atr[$i]=$v") }
            atrPercent(walk).forEachIndexed { i, v ->
                if (v != null) assertTrue(v >= 0.0, "atrPercent[$i]=$v")
            }
        }
    }

    @Test
    fun `bollinger bands are ordered upper at least middle at least lower`() {
        for (walk in walks(20)) {
            val b = bollinger(walk)
            for (i in b.middle.indices) {
                val mid = b.middle[i] ?: continue
                val up = b.upper[i] ?: continue
                val lo = b.lower[i] ?: continue
                assertTrue(up >= mid, "upper[$i]=$up < middle=$mid")
                assertTrue(mid >= lo, "middle[$i]=$mid < lower=$lo")
                // Bandwidth is a percentage of a positive price, so it is never negative.
                assertTrue(b.bandwidthPercent[i]!! >= 0.0, "bandwidth[$i]")
            }
        }
    }

    @Test
    fun `percentB is finite even when the band width is zero`() {
        for (walk in walks(10)) {
            bollinger(walk).percentB.forEachIndexed { i, v ->
                if (v == null) return@forEachIndexed
                assertFalse(v.isNaN(), "percentB[$i] is NaN")
                assertFalse(v.isInfinite(), "percentB[$i] is infinite")
            }
        }
    }

    @Test
    fun `no indicator produces NaN or infinity over seeded walks`() {
        for (walk in walks(20, withDelivery = true)) {
            assertFinite("sma", sma(walk, 20))
            assertFinite("ema", ema(walk, 20))
            assertFinite("rollingStdev", rollingStdev(walk, 20))
            assertFinite("rsi", rsi(walk).rsi)
            assertFinite("macd", macd(walk).macd)
            assertFinite("macd signal", macd(walk).signalLine)
            assertFinite("macd histogram", macd(walk).histogram)
            assertFinite("atr", atr(walk))
            assertFinite("atr%", atrPercent(walk))
            assertFinite("bollinger upper", bollinger(walk).upper)
            assertFinite("bollinger lower", bollinger(walk).lower)
            assertFinite("bollinger %B", bollinger(walk).percentB)
            assertFinite("adx", adx(walk).adx)
            assertFinite("volumeRatio", volumeRatio(walk))
            assertFinite("deliveryAverage", deliveryAveragePercent(walk))
            trueRange(walk).forEachIndexed { i, v ->
                assertFalse(v.isNaN(), "trueRange[$i] is NaN")
            }
            obv(walk).forEachIndexed { i, v -> assertFalse(v.isNaN(), "obv[$i] is NaN") }
            assertFalse((annualisedVolatility(walk) ?: 0.0).isNaN(), "annualisedVolatility")
            assertFalse((maxDrawdown(walk) ?: 0.0).isNaN(), "maxDrawdown")
            assertFalse(linearRegression(walk.map { it.close }).rSquared.isNaN(), "rSquared")
        }
    }

    @Test
    fun `a flat price series yields adx of zero, atr of one and no NaN anywhere`() {
        val flat = TestSeries.directionless(280)
        val a = adx(flat)
        // The series is directionless with a 1.0 intraday range, so TR is a constant 1 and both
        // DIs are 0: DX = 0 and the ADX of a constant is 0.
        assertClose(0.0, a.value, 1e-9)
        assertClose(0.0, a.diPlusValue, 1e-9)
        assertClose(0.0, a.diMinusValue, 1e-9)
        assertClose(0.0, a.dxValue, 1e-9)
        assertEquals(TrendRegime.RANGING, a.latestRegime)

        // ATR is exactly the constant true range.
        assertClose(1.0, atr(flat)[13], 1e-12)
        assertClose(1.0, atr(flat).last(), 1e-12)

        // RSI has no losses and no gains; the documented saturation applies.
        assertEquals(100.0, rsi(flat).rsi.last())

        // Bollinger collapses to a zero-width band, so percentB is the continuous 0.5.
        assertEquals(0.5, bollinger(flat).percentB.last())

        // Every other series is finite and, where meaningful, non null.
        assertFinite("flat sma", sma(flat, 20))
        assertFinite("flat ema", ema(flat, 20))
        assertFinite("flat stdev", rollingStdev(flat, 20))
        assertFinite("flat macd", macd(flat).macd)
        assertFinite("flat bollinger", bollinger(flat).upper)
        assertFinite("flat delivery", deliveryAveragePercent(flat))
        assertTrue(obv(flat).all { it == 0.0 })
        assertEquals(0.0, maxDrawdown(flat))
        assertEquals(0.0, annualisedVolatility(flat))
        assertTrue(linearRegression(List(30) { 100.0 }).isDegenerate)
    }

    @Test
    fun `a zero range series produces null direction rather than NaN`() {
        val pin = TestSeries.zeroRange(280)
        assertTrue(adx(pin).adx.all { it == null }, "no denominator means no reading")
        assertTrue(adx(pin).diPlus.all { it == null })
        assertEquals(TrendRegime.UNKNOWN, adx(pin).latestRegime)
        // ATR is a mean of true ranges, and every true range is a legitimate zero here.
        assertEquals(0.0, atr(pin)[13])
        assertEquals(0.0, atr(pin).last())
        assertTrue(atr(pin).filterNotNull().all { it == 0.0 })
        assertEquals(0.0, atrPercent(pin).last())
        assertTrue(trueRange(pin).all { it == 0.0 })
        assertFinite("zero range", adx(pin).adx)
    }

    @Test
    fun `insufficient history yields null and never an exception`() {
        val one = TestSeries.golden.take(1)
        val three = TestSeries.golden.take(3)
        // Longest warm-up in the library is SMA/EMA(200) and ADX(14) at 3*14-3 = 39 bars.
        for (series in listOf(emptyList(), one, three, TestSeries.golden.take(10))) {
            val n = series.size
            assertEquals(n, sma(series, 200).size)
            assertTrue(sma(series, 200).all { it == null })
            assertTrue(ema(series, 200).all { it == null })
            assertTrue(bollinger(series, 20).upper.all { it == null })
            assertTrue(adx(series).adx.all { it == null })
            assertTrue(macd(series).macd.all { it == null })
            assertNull(rsi(series).value)
            // ATR(14) cannot fill any of these windows, so no bar carries a value. Using
            // lastOrNull rather than last keeps the empty-series case from throwing here: the
            // property under test is "nothing is emitted", which an empty list also satisfies.
            assertNull(atr(series).lastOrNull())
            // The 52-week high is null only for a genuinely empty series: one bar is enough.
            if (series.isEmpty()) assertNull(fiftyTwoWeekHigh(series)) else assertNotNull(fiftyTwoWeekHigh(series))
            // These are defined even for a single bar, and that is correct rather than a leak.
            assertEquals(n, trueRange(series).size)
            assertEquals(n, obv(series).size)
            IndicatorSnapshot.of(series)
        }
    }

    @Test
    fun `zero volume and null delivery never yield NaN`() {
        for (seed in 1L..10L) {
            val bars = TestSeries.seededWalk(120, seed).map { it.copy(volume = 0L, deliveryPercent = null) }
            assertFinite("zero volume sma", sma(bars, 20))
            // A 20-bar average of zeros is a real value of 0.0, not a missing one.
            assertTrue(volumeSma(bars).drop(19).all { it == 0.0 })
            assertTrue(volumeRatio(bars).drop(19).all { it == null }, "0/0 has no ratio")
            assertTrue(obv(bars).all { it == 0.0 }, "no volume means no accumulation")
            assertNull(obvSlope(bars), "no gross volume to normalise by")
            assertTrue(deliveryAveragePercent(bars).all { it == null })
            assertFalse(isDeliveryCollapsing(bars))
            val s = IndicatorSnapshot.of(bars)
            assertFalse(s.trendlineFitRSquared.isNaN())
            assertNull(s.deliveryAverage, "no delivery data was fetched")
        }
    }

    @Test
    fun `null delivery readings never become zero percent`() {
        // If a missing bar were treated as 0% the 20-bar average would be dragged down and the
        // §8.2 "delivery collapsing" flag would fire on missing data. It must not.
        val bars = TestSeries.fromCloses(
            closes = List(30) { 100.0 + it },
            deliveryPercent = { i -> if (i % 3 == 0) null else 50.0 },
        )
        val d = deliveryAveragePercent(bars)
        d.forEachIndexed { i, v ->
            if (v != null) assertTrue(v > 0.0, "delivery average[$i] = $v looks like missing data")
        }
        assertFalse(isDeliveryCollapsing(bars))
    }

    @Test
    fun `rsi over a zero range series does not divide by zero`() {
        val pin = TestSeries.zeroRange(120)
        val r = rsi(pin)
        assertFinite("pin rsi", r.rsi)
        assertEquals(100.0, r.rsi.last(), "documented saturation when there are no losses")
    }

    @Test
    fun `trendline fit r squared stays in zero to one and is zero when degenerate`() {
        for (seed in 1L..20L) {
            val closes = TestSeries.seededWalk(280, seed).map { it.close }
            val fit = trendlineFit(closes)
            assertTrue(fit.rSquared in 0.0..1.0, "seed=$seed R²=${fit.rSquared}")
            if (fit.isDegenerate) assertEquals(0.0, fit.rSquared, "seed=$seed")
        }
        val flat = trendlineFit(List(50) { 5.0 })
        assertTrue(flat.isDegenerate)
        assertEquals(0.0, flat.rSquared)
    }

    @Test
    fun `max drawdown is always reported as a non negative percentage`() {
        for (seed in 1L..20L) {
            val dd = assertNotNull(maxDrawdown(TestSeries.seededWalk(280, seed)))
            assertTrue(dd in 0.0..100.0, "seed=$seed drawdown=$dd")
        }
    }

    @Test
    fun `relative strength never throws on mismatched or duplicated dates`() {
        val stock = TestSeries.seededWalk(60, seed = 5L)
        val shuffledBench = TestSeries.seededWalk(60, seed = 6L).reversed()
        assertNotNull(relativeStrength(stock, shuffledBench).latestRsRatio)
        assertEquals(60, relativeStrength(stock, stock).alignedDates.size)

        // A benchmark that shares no dates at all.
        val foreign = stock.map { it.copy(date = it.date.plusYears(3)) }
        assertTrue(relativeStrength(stock, foreign).alignedDates.isEmpty())
        assertNull(relativeStrength(stock, foreign).latestRsRatio)

        // Duplicated benchmark dates collapse to one entry.
        val dupes = (1..4).map { stock[0] }
        assertEquals(1, relativeStrength(stock, dupes).alignedDates.size)
    }

    @Test
    fun `relative strength is exactly one when the stock tracks the benchmark`() {
        val both = TestSeries.fromCloses(List(30) { 100.0 + it })
        val rs = relativeStrength(both, both)
        rs.rsLine.forEachIndexed { i, v ->
            assertEquals(1.0, v, "rsLine[$i]")
        }
        assertEquals(1.0, rs.latestRsRatio)
        assertEquals(1.0, rs.latestNormalisedRs)
        assertEquals(0.0, relativeStrengthTrend(rs, lookback = 10))
    }

    @Test
    fun `regime is only unknown when adx or the direction is genuinely unavailable`() {
        for (seed in 1L..20L) {
            val a = adx(TestSeries.seededWalk(280, seed))
            a.regime.forEachIndexed { i, r ->
                if (r == TrendRegime.UNKNOWN) {
                    assertTrue(
                        a.adx[i] == null || a.adx[i]!! < TechnicalPolicy.ADX_TRENDING,
                        "seed=$seed bar=$i: UNKNOWN with adx=${a.adx[i]}",
                    )
                } else {
                    assertNotNull(a.adx[i], "seed=$seed bar=$i: regime $r without an ADX value")
                }
            }
        }
    }

    @Test
    fun `regime classification is stable across a seeded walk`() {
        // A bar classified TRENDING_UP or TRENDING_DOWN must have ADX at or above the band.
        for (seed in 1L..15L) {
            val a = adx(TestSeries.seededWalk(280, seed))
            a.regime.forEachIndexed { i, r ->
                val adxValue = a.adx[i] ?: return@forEachIndexed
                when (r) {
                    TrendRegime.TRENDING_UP -> {
                        assertTrue(adxValue >= TechnicalPolicy.ADX_TRENDING, "seed=$seed bar=$i")
                        assertTrue(a.diPlusValue == null || a.diPlus[i]!! > a.diMinus[i]!!, "seed=$seed bar=$i")
                    }

                    TrendRegime.TRENDING_DOWN -> {
                        assertTrue(adxValue >= TechnicalPolicy.ADX_TRENDING, "seed=$seed bar=$i")
                        assertTrue(a.diMinus[i]!! > a.diPlus[i]!!, "seed=$seed bar=$i")
                    }

                    TrendRegime.RANGING -> assertTrue(adxValue < TechnicalPolicy.ADX_RANGING, "seed=$seed bar=$i")
                    TrendRegime.UNKNOWN -> Unit // Covered by the separate UNKNOWN test above.
                }
            }
        }
    }

    @Test
    fun `death cross state is exactly mirrored by the snapshot on seeded walks`() {
        for (seed in 1L..15L) {
            val walk = TestSeries.seededWalk(280, seed)
            val s = IndicatorSnapshot.of(walk)
            assertEquals(isDeathCross(s.sma50, s.sma200), s.isDeathCross, "seed=$seed")
            assertEquals(
                isDeathCross(s.sma50, s.sma200) && s.isBelowLongTermAverage,
                s.isDeathCrossCondition(),
                "seed=$seed",
            )
        }
    }

    @Test
    fun `snapshot agrees with the standalone functions on seeded walks`() {
        for (seed in 1L..12L) {
            val walk = TestSeries.seededWalk(280, seed, withDelivery = true)
            val s = IndicatorSnapshot.of(walk)
            assertEquals(rsi(walk).rsi, s.rsi.rsi, "seed=$seed")
            assertEquals(atr(walk), s.atr, "seed=$seed")
            assertEquals(bollinger(walk), s.bollinger, "seed=$seed")
            assertEquals(adx(walk), s.adx, "seed=$seed")
            assertEquals(macd(walk), s.macd, "seed=$seed")
            assertEquals(obv(walk), s.obv, "seed=$seed")
            assertEquals(sma(walk, 200), s.sma200, "seed=$seed")
            assertEquals(ema(walk, 200), s.ema200, "seed=$seed")
        }
    }

    @Test
    fun `weekly timeframe volumes by its own bar count when annualising`() {
        val weekly = TestSeries.seededWalk(80, timeframe = Timeframe.W1)
        val dailyEquivalent = IndicatorSnapshot.of(weekly)
        assertEquals(Timeframe.W1, dailyEquivalent.timeframe)
        // Same bars, different barsPerYear, so the annualised figures must differ.
        val asDaily = annualisedVolatility(weekly, timeframe = Timeframe.D1)
        val asWeekly = annualisedVolatility(weekly, timeframe = Timeframe.W1)
        assertNotNull(asDaily)
        assertNotNull(asWeekly)
        assertTrue(asWeekly != asDaily)
    }
}