package com.mia.app.domain.technical

import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.Timeframe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The aggregate [IndicatorSnapshot] must be observationally identical to calling the individual
 * functions: same values, same alignment, same nulls. If the fused pass ever drifts from the
 * plain API, the chart and a red flag can disagree — these tests are the guard.
 */
class IndicatorSnapshotTest {

    private val candles = TestSeries.golden
    private val long = TestSeries.seededWalk(280, seed = 4_242L)

    @Test
    fun `snapshot moving averages equal the individual functions exactly`() {
        val s = IndicatorSnapshot.of(candles)
        assertEquals(sma(candles, 20), s.sma20)
        assertEquals(sma(candles, 50), s.sma50)
        assertEquals(sma(candles, 100), s.sma100)
        assertEquals(sma(candles, 200), s.sma200)
        assertEquals(ema(candles, 20), s.ema20)
        assertEquals(ema(candles, 50), s.ema50)
        assertEquals(ema(candles, 200), s.ema200)
    }

    @Test
    fun `snapshot oscillators equal the individual functions exactly`() {
        val s = IndicatorSnapshot.of(candles)
        assertEquals(rsi(candles, 14), s.rsi)
        assertEquals(macd(candles), s.macd)
    }

    @Test
    fun `snapshot volatility equals the individual functions exactly`() {
        val s = IndicatorSnapshot.of(candles)
        assertEquals(trueRange(candles), s.trueRange)
        assertEquals(atr(candles, 14), s.atr)
        assertEquals(atrPercent(candles, 14), s.atrPercent)
        assertEquals(bollinger(candles), s.bollinger)
    }

    @Test
    fun `snapshot volume and trend equal the individual functions exactly`() {
        val s = IndicatorSnapshot.of(candles)
        assertEquals(obv(candles), s.obv)
        assertEquals(obvSlope(candles), s.obvSlope)
        assertEquals(volumeSma(candles, 20), s.volumeSma20)
        assertEquals(volumeRatio(candles, 20), s.volumeRatio)
        assertEquals(deliveryAveragePercent(candles), s.deliveryAveragePercent)
        assertEquals(adx(candles, 14), s.adx)
    }

    @Test
    fun `snapshot derived views equal the individual functions exactly`() {
        val s = IndicatorSnapshot.of(candles)
        assertEquals(fiftyTwoWeekHigh(candles), s.fiftyTwoWeekHigh)
        assertEquals(fiftyTwoWeekLow(candles), s.fiftyTwoWeekLow)
        assertEquals(fiftyTwoWeekRange(candles), s.fiftyTwoWeekRange)
        assertEquals(annualisedVolatility(candles), s.annualisedVolatilityPercent)
        assertEquals(maxDrawdown(candles), s.maxDrawdownPercent)
        assertEquals(trendlineFit(candles.map { it.close }), s.trendline)
        assertEquals(candles.last().close, s.lastClose)
        assertEquals(candles.last().date, s.asOf)
        assertEquals(30, s.barCount)
        assertEquals(TestSeries.SYMBOL, s.symbol)
        assertEquals(Timeframe.D1, s.timeframe)
    }

    @Test
    fun `snapshot never leaks a value into a warm-up window on a long series`() {
        val s = IndicatorSnapshot.of(long)
        // SMA(200) cannot be defined before bar 199 of a 280-bar series.
        assertWarmup(s.sma200, 199, "snapshot sma200")
        assertWarmup(s.ema200, 199, "snapshot ema200")
        assertWarmup(s.sma100, 99, "snapshot sma100")
        assertWarmup(s.bollinger.middle, 19, "snapshot bollinger middle")
        assertWarmup(s.rsi.rsi, 14, "snapshot rsi")
        assertWarmup(s.atr, 13, "snapshot atr")
        assertWarmup(s.adx.adx, s.adx.adxWarmupBars(), "snapshot adx")
        assertNotNull(s.sma200Value, "280 bars is enough for SMA(200)")
        assertNotNull(s.adx.value)
    }

    @Test
    fun `snapshot is NaN free over seeded random walks`() {
        for (seed in 1L..20L) {
            val walk = TestSeries.seededWalk(280, seed, withDelivery = true)
            val s = IndicatorSnapshot.of(walk)
            val tag = "seed=$seed"
            assertFinite("sma20 $tag", s.sma20)
            assertFinite("sma50 $tag", s.sma50)
            assertFinite("ema200 $tag", s.ema200)
            assertFinite("rsi $tag", s.rsi.rsi)
            assertFinite("atr $tag", s.atr)
            assertFinite("atr% $tag", s.atrPercent)
            assertFinite("bollinger %B $tag", s.bollinger.percentB)
            assertFinite("bollinger width $tag", s.bollinger.bandwidthPercent)
            assertFinite("adx $tag", s.adx.adx)
            assertFinite("volumeRatio $tag", s.volumeRatio)
            assertFinite("delivery $tag", s.deliveryAveragePercent)
            assertFalse(s.trendlineFitRSquared.isNaN(), "trendline rSquared $tag")
            assertFalse((s.annualisedVolatilityPercent ?: 0.0).isNaN(), "volatility $tag")
            assertFalse((s.maxDrawdownPercent ?: 0.0).isNaN(), "drawdown $tag")
        }
    }

    @Test
    fun `snapshot handles zero bars without throwing`() {
        val s = IndicatorSnapshot.of(emptyList())
        assertEquals(0, s.barCount)
        assertNull(s.lastClose)
        assertNull(s.asOf)
        assertNull(s.sma20Value)
        assertNull(s.atrValue)
        assertNull(s.fiftyTwoWeekHigh)
        assertNull(s.fiftyTwoWeekLow)
        assertNull(s.fiftyTwoWeekRange)
        assertNull(s.adx.value)
        assertNull(s.rsi.value)
        assertEquals(TrendRegime.UNKNOWN, s.trendRegime)
        assertEquals(0.0, s.obvValue)
        assertFalse(s.isDeathCross)
        assertFalse(s.isDeathCrossCondition())
        assertFalse(s.isRsiOverbought)
        assertFalse(s.isRsiOversold)
        assertFalse(s.isBelowLongTermAverage)
        assertFalse(s.isAtFiftyTwoWeekLowWithDecliningObv)
        assertFalse(s.hasSufficientBars())
    }

    @Test
    fun `snapshot handles a single bar without throwing`() {
        val one = listOf(candles.first())
        val s = IndicatorSnapshot.of(one)
        assertEquals(1, s.barCount)
        assertEquals(1.0, s.lastClose)
        // A single bar is enough for the high/low but not for any windowed average.
        assertEquals(1.0, s.fiftyTwoWeekHigh)
        assertEquals(1.0, s.fiftyTwoWeekLow)
        assertNull(s.sma20Value, "SMA(20) needs 20 bars")
        assertNull(s.atrValue, "ATR(14) needs 14 bars")
        assertNull(s.rsi.value, "RSI(14) needs 15 bars")
        assertNull(s.sma200Value)
        assertNull(s.adx.value)
        // A one-bar snapshot still reports a degenerate fit rather than throwing.
        assertTrue(s.trendline.isDegenerate)
        assertEquals(0.0, s.trendlineFitRSquared)
        assertEquals(0.0, s.obvValue, "OBV is defined from bar 0 at 0.0")
        assertEquals(0.0, s.maxDrawdownPercent)
    }

    @Test
    fun `snapshot handles a flat series without NaN and reports a ranging regime`() {
        val s = IndicatorSnapshot.of(TestSeries.directionless(280))
        assertEquals(0.0, s.maxDrawdownPercent)
        assertEquals(0.0, s.annualisedVolatilityPercent)
        assertEquals(TrendRegime.RANGING, s.trendRegime)
        // The bars still have a 1.0 range, so ATR is 1.0 rather than 0.0.
        assertEquals(1.0, s.atrValue)
        // A flat series has no trendline worth trusting, so the structure score reads 0.
        assertTrue(s.trendline.isDegenerate)
        assertEquals(0.0, s.trendlineFitRSquared)
        assertEquals(0.0, s.obvValue)
        // Price never moves, so OBV neither accumulates nor declines: the slope is a real 0.0
        // (there is gross volume to normalise by) rather than a null.
        assertEquals(0.0, s.obvSlope)
        assertEquals(100.0, s.fiftyTwoWeekHigh)
        assertEquals(100.0, s.fiftyTwoWeekLow)
        assertEquals(50.0, s.fiftyTwoWeekRange?.currentPositionPercent)
        assertFinite("flat sma20", s.sma20)
        assertFinite("flat bollinger %B", s.bollinger.percentB)
        assertEquals(0.5, assertNotNull(s.bollinger.percentB.last()), 1e-12)
    }

    @Test
    fun `snapshot tolerates zero volume on every bar`() {
        val noVolume = List(60) { i ->
            candles[i % candles.size].copy(
                date = TestSeries.date(i),
                volume = 0L,
                deliveryPercent = 50.0,
            )
        }
        val s = IndicatorSnapshot.of(noVolume)
        assertEquals(0.0, s.volumeAverage)
        assertTrue(s.volumeRatio.all { it == null }, "no volume means no ratio, not Infinity")
        assertFalse(isVolumeExpanding(noVolume))
        assertNull(s.obvSlope, "no gross volume to normalise by")
        assertFinite("zero volume delivery", s.deliveryAveragePercent)
        assertEquals(50.0, s.deliveryAverage)
    }

    @Test
    fun `snapshot tolerates every delivery reading being null`() {
        val s = IndicatorSnapshot.of(long)
        assertTrue(s.deliveryAveragePercent.all { it == null })
        assertNull(s.deliveryAverage)
        assertFalse(s.isDeliveryCollapsingFlag, "no data is not a collapse")
    }

    @Test
    fun `snapshot death cross flag reproduces the manual computation`() {
        // Falling series: SMA50[219] = 305.5, SMA200[219] = 381.0, latest close 281.0.
        val falling = TestSeries.falling(220)
        val s = IndicatorSnapshot.of(falling)
        assertEquals(305.5, s.sma50Value)
        assertEquals(380.5, s.sma200Value)
        assertTrue(s.isDeathCross)
        assertTrue(s.isBelowLongTermAverage)
        assertTrue(s.isDeathCrossCondition())
        // The pair is already below at the first bar where SMA200 exists, so no crossover is seen.
        assertNull(s.deathCrossBarIndex)

        // Rising series: no death cross.
        val rising = IndicatorSnapshot.of(TestSeries.rising(210))
        assertEquals(284.5, rising.sma50Value)
        assertEquals(209.5, rising.sma200Value)
        assertFalse(rising.isDeathCross)
        assertFalse(rising.isDeathCrossCondition())
    }

    @Test
    fun `snapshot distribution day requires a down close at the same bar`() {
        val volumes = List(21) { 10_000L }.toMutableList().also { it[20] = 30_000L }
        // Index 20 of the golden series closes at 12 while index 19 closed at 11 -> an up day, so
        // the expansion is not distribution.
        val upDay = TestSeries.withVolumes(21, volumes).let { series ->
            series.mapIndexed { i, c -> c.copy(date = TestSeries.date(i)) }
        }
        val upSnapshot = IndicatorSnapshot.of(upDay)
        // The 20-bar volume average includes the spike itself, so the ratio is 30000/11000 and
        // not 3.0. That is correct: an average that excluded the bar could never flag a spike.
        assertClose(30_000.0 / 11_000.0, upSnapshot.latestVolumeRatio)
        assertClose(2.727272727272727, upSnapshot.latestVolumeRatio)
        assertFalse(upSnapshot.isDistributionDay(upDay), "an up close is not distribution")

        // Reverse the last close to force a down day and the same expansion now is distribution.
        val downDay = upDay.toMutableList().also { list ->
            val last = list.last()
            list[list.lastIndex] = last.copy(close = 10.0, high = last.high, low = 9.5, open = last.close)
        }
        val downSnapshot = IndicatorSnapshot.of(downDay)
        assertTrue(downSnapshot.isDistributionDay(downDay))
    }

    @Test
    fun `snapshot at fifty two week low with declining obv`() {
        // 60 rising bars (100..159) then 20 declining bars stepping down 8 at a time to 6, a fresh
        // 52-week low. The trailing 20-bar OBV window is entirely down closes, so the normalised
        // slope is exactly -1.0 and both halves of the §8.2 condition are satisfied.
        val closes = List(60) { 100.0 + it } + List(20) { i -> 158.0 - i * 8.0 }
        val bars = closes.mapIndexed { i, c ->
            val previous = if (i == 0) c else closes[i - 1]
            TestSeries.bar(i, c, previous, volume = 100_000L)
        }
        assertEquals(6.0, closes.last(), "the constructed series ends at 6")
        val s = IndicatorSnapshot.of(bars)
        assertEquals(6.0, s.lastClose, "the last close is the new low")
        assertEquals(6.0, s.fiftyTwoWeekLow)
        assertEquals(-1.0, s.obvSlope, "every bar in the OBV window is a down close")
        assertTrue(s.isAtFiftyTwoWeekLowWithDecliningObv)
    }

    @Test
    fun `snapshot at fifty two week low does not fire while obv is still rising`() {
        // Same 52-week low, but the 20-bar window before it is a rally, so OBV is climbing. The
        // half of the condition that matters is the divergence, so the flag must stay silent.
        val closes = List(60) { 100.0 + it } + listOf(5.0, 6.0, 7.0, 8.0, 9.0)
        val bars = closes.mapIndexed { i, c ->
            val previous = if (i == 0) c else closes[i - 1]
            TestSeries.bar(i, c, previous, volume = 100_000L)
        }
        val s = IndicatorSnapshot.of(bars)
        assertEquals(5.0, s.fiftyTwoWeekLow)
        assertTrue(s.lastClose!! > s.fiftyTwoWeekLow!!, "price has bounced off the low")
        assertFalse(s.isAtFiftyTwoWeekLowWithDecliningObv)
    }

    @Test
    fun `snapshot delivery collapse flag mirrors the standalone function`() {
        val series = TestSeries.fromCloses(
            closes = List(24) { 100.0 + it },
            deliveryPercent = { i -> if (i >= 22) 10.0 else 50.0 },
        )
        val s = IndicatorSnapshot.of(series)
        assertEquals(isDeliveryCollapsing(series), s.isDeliveryCollapsingFlag)
        assertTrue(s.isDeliveryCollapsingFlag)
        assertEquals(46.0, s.deliveryAverage)
    }

    @Test
    fun `snapshot rsi flags match the standalone thresholds`() {
        val rising = IndicatorSnapshot.of(TestSeries.rising(60))
        assertEquals(100.0, rising.rsi.value)
        assertTrue(rising.isRsiOverbought)
        assertFalse(rising.isRsiOversold)

        val falling = IndicatorSnapshot.of(TestSeries.falling(60))
        assertEquals(0.0, falling.rsi.value)
        assertTrue(falling.isRsiOversold)
        assertFalse(falling.isRsiOverbought)
    }

    @Test
    fun `snapshot bar gate mirrors the plan's hard gate`() {
        assertFalse(IndicatorSnapshot.of(candles).hasSufficientBars(), "30 bars < 60 for D1")
        assertTrue(IndicatorSnapshot.of(long).hasSufficientBars(), "280 bars >= 60 for D1")

        val weekly = TestSeries.seededWalk(40, timeframe = Timeframe.W1)
        assertTrue(IndicatorSnapshot.of(weekly).hasSufficientBars(), "40 bars >= 40 for W1")
        assertFalse(IndicatorSnapshot.of(weekly.take(39)).hasSufficientBars())
    }

    @Test
    fun `snapshot regime mirrors the standalone adx regime`() {
        for (seed in 1L..15L) {
            val walk = TestSeries.seededWalk(280, seed)
            val s = IndicatorSnapshot.of(walk)
            assertEquals(adx(walk, 14).latestRegime, s.trendRegime, "seed=$seed")
        }
    }

    @Test
    fun `snapshot computes relative strength only when a benchmark is supplied`() {
        val stock = TestSeries.fromCloses(listOf(100.0, 150.0, 90.0))
        val bench = TestSeries.fromCloses(listOf(100.0, 100.0, 100.0))
        assertNull(IndicatorSnapshot.of(stock).relativeStrength, "no benchmark, no RS")

        val withBench = IndicatorSnapshot.of(stock, bench)
        assertNotNull(withBench.relativeStrength)
        assertEquals(relativeStrength(stock, bench), withBench.relativeStrength)
        assertEquals(0.9, withBench.relativeStrength?.latestRsRatio)
    }

    @Test
    fun `snapshot regression window is configurable`() {
        val values = List(10) { 1000.0 - it * 7.0 } + List(30) { it.toDouble() }
        val bars = TestSeries.fromCloses(values)
        // Default window of 30 sees only the perfect ramp.
        assertEquals(1.0, IndicatorSnapshot.of(bars).trendlineFitRSquared, 1e-9)
        // A window covering all 40 bars sees the wild first section and fits far worse.
        val wide = IndicatorSnapshot.of(bars, regressionWindow = 40)
        assertEquals(0.5492851340133647, wide.trendlineFitRSquared, 1e-9)
        assertTrue(wide.trendlineFitRSquared < 1.0, "the wild section must cost the fit some R²")
    }

    @Test
    fun `snapshot exposes the latest readings used by the technicals tab`() {
        val s = IndicatorSnapshot.of(candles)
        assertEquals(s.sma20.lastOrNull(), s.sma20Value)
        assertEquals(s.sma50.lastOrNull(), s.sma50Value)
        assertEquals(s.ema200.lastOrNull(), s.ema200Value)
        assertEquals(s.atr.lastOrNull(), s.atrValue)
        assertEquals(s.volumeSma20.lastOrNull(), s.volumeAverage)
        assertEquals(s.volumeRatio.lastOrNull(), s.latestVolumeRatio)
        assertEquals(11000.0, s.obvValue)
        assertNotNull(s.macd.value)
    }

    @Test
    fun `snapshot is immutable so it can be shared across screens`() {
        val s = IndicatorSnapshot.of(candles)
        val first = s.sma20
        // Building a second snapshot must not disturb the first one.
        IndicatorSnapshot.of(long)
        assertSame(first, s.sma20)
        assertEquals(IndicatorSnapshot.of(candles), s, "the build is deterministic")
    }

    @Test
    fun `snapshot handles invalid candles without NaN`() {
        // Zeroed and inverted bars must not poison any indicator.
        val broken = List(40) { i ->
            val c = candles[i % candles.size]
            if (i % 3 == 0) c.copy(close = 0.0, open = 0.0, high = 0.0, low = 0.0, date = TestSeries.date(i))
            else c.copy(date = TestSeries.date(i))
        }
        val s = IndicatorSnapshot.of(broken)
        assertFinite("broken sma20", s.sma20)
        assertFinite("broken rsi", s.rsi.rsi)
        assertFinite("broken atr", s.atr)
        assertFinite("broken adx", s.adx.adx)
        assertFinite("broken bollinger", s.bollinger.percentB)
        assertFalse((s.annualisedVolatilityPercent ?: 0.0).isNaN())
    }

    @Test
    fun `snapshot on a duplicate-date series does not crash relative strength`() {
        val dupes = listOf(
            Candle("T", Timeframe.D1, TestSeries.date(0), 10.0, 10.5, 9.5, 10.0, 100L),
            Candle("T", Timeframe.D1, TestSeries.date(0), 10.0, 10.5, 9.5, 20.0, 100L),
            Candle("T", Timeframe.D1, TestSeries.date(1), 10.0, 11.5, 9.5, 30.0, 100L),
        )
        val s = IndicatorSnapshot.of(dupes, dupes)
        assertNotNull(s.relativeStrength)
        assertEquals(2, s.relativeStrength!!.alignedDates.size, "one entry per unique date")
    }
}