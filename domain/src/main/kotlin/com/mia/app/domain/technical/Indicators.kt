package com.mia.app.domain.technical

import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.MatchScorePolicy
import com.mia.app.domain.model.Timeframe
import java.time.LocalDate

/**
 * Everything the technicals tab, the pattern pipeline (§6.3) and the red-flag engine (§8.2)
 * need, computed **once**.
 *
 * **Why this type exists.** plan.md §11 targets 2 GB-RAM hardware. One stock screen needs SMA
 * 20/50/100/200, EMA 20/50/200, RSI, MACD, ATR, Bollinger, OBV, ADX and a regression; the §8.2
 * flags re-read five of those; the pattern pipeline needs them again. Computing each
 * independently is fifteen passes over the candle list and fifteen sets of boxed intermediates.
 * [of] makes it one pass for the OHLC work ([rangeArrays] plus the closes/volumes arrays) and
 * then delegates to the *same* primitive implementations the standalone functions use, so
 * there is no fused fast path that could silently disagree with the plain API.
 *
 * **Every field is nullable, and `null` always means "insufficient history"**, never zero and
 * never a fabricated value. That is the contract plan.md §6.4's "not enough history" rests on.
 *
 * Build one per (symbol, timeframe) and share it; it is immutable.
 */
data class IndicatorSnapshot(
    val symbol: String,
    val timeframe: Timeframe,
    val barCount: Int,
    val asOf: LocalDate?,

    // Moving averages (plan.md §8.2 death cross, §6.3 structure).
    val sma20: List<Double?>,
    val sma50: List<Double?>,
    val sma100: List<Double?>,
    val sma200: List<Double?>,
    val ema20: List<Double?>,
    val ema50: List<Double?>,
    val ema200: List<Double?>,

    // Oscillators.
    val rsi: RsiResult,
    val macd: MacdResult,

    // Volatility.
    val trueRange: List<Double>,
    val atr: List<Double?>,
    val atrPercent: List<Double?>,
    val bollinger: BollingerResult,

    // Volume.
    val obv: List<Double>,
    val obvSlope: Double?,
    val volumeSma20: List<Double?>,
    val volumeRatio: List<Double?>,
    val deliveryAveragePercent: List<Double?>,
    val deliveryCollapsing: Boolean,

    // Trend.
    val adx: TechnicalAdx,

    /** Relative strength vs a benchmark. `null` when [of] was called without one. */
    val relativeStrength: RelativeStrengthResult?,

    // Derived views.
    val fiftyTwoWeekHigh: Double?,
    val fiftyTwoWeekLow: Double?,
    val fiftyTwoWeekRange: FiftyTwoWeekRange?,
    val trendline: TrendlineFit,
    val annualisedVolatilityPercent: Double?,
    val maxDrawdownPercent: Double?,

    /** Latest close, kept so the §8.2 tests need no access to the candle list. */
    val lastClose: Double?,
) {

    // ------------------------------------------------------------------ latest readings

    val sma20Value: Double? get() = sma20.lastOrNull()
    val sma50Value: Double? get() = sma50.lastOrNull()
    val sma100Value: Double? get() = sma100.lastOrNull()
    val sma200Value: Double? get() = sma200.lastOrNull()
    val ema20Value: Double? get() = ema20.lastOrNull()
    val ema50Value: Double? get() = ema50.lastOrNull()
    val ema200Value: Double? get() = ema200.lastOrNull()
    val atrValue: Double? get() = atr.lastOrNull()
    val volumeAverage: Double? get() = volumeSma20.lastOrNull()
    val deliveryAverage: Double? get() = deliveryAveragePercent.lastOrNull()
    val obvValue: Double get() = obv.lastOrNull() ?: 0.0
    val latestVolumeRatio: Double? get() = volumeRatio.lastOrNull()

    /** plan.md §6.4 gate input: the ADX regime at the latest bar. */
    val trendRegime: TrendRegime get() = adx.latestRegime

    /** plan.md §6.3 "Structure: trendline fit R²"; 0.0 when the fit is degenerate. */
    val trendlineFitRSquared: Double
        get() = if (trendline.isDegenerate) 0.0 else trendline.rSquared

    /** plan.md §6.4 hard gate: enough bars for this timeframe's detectors. */
    fun hasSufficientBars(): Boolean =
        MatchScorePolicy.isSufficientBars(timeframe, barCount)

    // -------------------------------------------------------------------- §8.2 flags

    /** plan.md §8.2 death cross, first half: `SMA50 < SMA200`. */
    val isDeathCross: Boolean get() = isDeathCross(sma50, sma200)

    /** Bar index the most recent death cross printed on, for the flag's evidence. */
    val deathCrossBarIndex: Int? get() = deathCrossBarIndex(sma50, sma200)

    /** plan.md §8.2 death cross, second half: close below the 200-DMA. */
    val isBelowLongTermAverage: Boolean
        get() {
            val close = lastClose ?: return false
            val ma = sma200Value ?: return false
            return close < ma
        }

    /** plan.md §8.2 "Close below 200-DMA AND SMA50 < SMA200" — the complete condition. */
    fun isDeathCrossCondition(): Boolean = isDeathCross && isBelowLongTermAverage

    /** plan.md §8.2 "RSI > 70 / < 30". */
    val isRsiOverbought: Boolean get() = rsi.isOverbought

    val isRsiOversold: Boolean get() = rsi.isOversold

    /**
     * plan.md §8.2 "Volume spike > 2.5x 20d average on a down day (distribution)".
     *
     * The down day is read from [Candle.isBullish] at the *same* bar, so the two halves of the
     * condition can never be measured at different dates.
     */
    fun isDistributionDay(
        candles: List<Candle>,
        multiple: Double = TechnicalPolicy.VOLUME_SPIKE_MULTIPLE,
    ): Boolean {
        val last = candles.lastOrNull() ?: return false
        if (last.isBullish) return false
        return (latestVolumeRatio ?: return false) >= multiple
    }

    /** plan.md §8.2 "Price at 52-week low with declining OBV". */
    val isAtFiftyTwoWeekLowWithDecliningObv: Boolean
        get() {
            val low = fiftyTwoWeekLow ?: return false
            val close = lastClose ?: return false
            if (close > low) return false
            return (obvSlope ?: return false) < 0.0
        }

    /** plan.md §8.2 "Delivery % collapsing vs 20-day average". */
    val isDeliveryCollapsingFlag: Boolean get() = deliveryCollapsing

    companion object {
        /**
         * Builds the snapshot in a single fused pass, then delegates to the shared primitive
         * implementations. See the class KDoc for why.
         *
         * @param benchmarkCandles index history (e.g. NIFTY), or `null` to skip the
         *   relative-strength alignment entirely — most screens have no benchmark loaded, and
         *   paying for an empty alignment pass would be waste.
         */
        fun of(
            candles: List<Candle>,
            benchmarkCandles: List<Candle>? = null,
            regressionWindow: Int = TechnicalPolicy.REGRESSION_WINDOW,
        ): IndicatorSnapshot {
            val n = candles.size
            val closes = DoubleArray(n)
            val volumes = DoubleArray(n)
            val delivery = DoubleArray(n)
            val hasDelivery = BooleanArray(n)

            // One pass: closes, volumes, delivery and OBV. True range and DM (which also need
            // the previous bar) come from rangeArrays, the single shared implementation.
            var obvRunning = 0.0
            val obv = DoubleArray(n)
            for (i in 0 until n) {
                val c = candles[i]
                closes[i] = c.close
                volumes[i] = c.volume.toDouble()
                val d = c.deliveryPercent
                if (d != null) {
                    delivery[i] = d
                    hasDelivery[i] = true
                }
                if (i > 0) {
                    val delta = c.close - candles[i - 1].close
                    if (delta > 0) obvRunning += c.volume
                    else if (delta < 0) obvRunning -= c.volume
                }
                obv[i] = obvRunning
            }

            val ranges = rangeArrays(candles)
            val sma20 = smaArray(closes, TechnicalPolicy.SMA_FAST)
            val sma50 = smaArray(closes, TechnicalPolicy.SMA_MID)
            val sma100 = smaArray(closes, TechnicalPolicy.SMA_SLOW)
            val sma200 = smaArray(closes, TechnicalPolicy.SMA_LONG)
            val atr = atrFromRaw(ranges.tr, TechnicalPolicy.ATR_PERIOD)
            val atrPct: List<Double?> = List(n) { i ->
                val a = atr[i] ?: return@List null
                if (closes[i] == 0.0) null else a / closes[i] * 100.0
            }
            val volumeSma = smaArray(volumes, TechnicalPolicy.VOLUME_SMA_PERIOD)
            val volRatio = volumeSma.mapIndexed { i, avg ->
                if (avg == null || avg == 0.0) null else volumes[i] / avg
            }

            // 52-week range from the trailing window of closes.
            val windowStart = maxOf(0, n - TechnicalPolicy.WEEKS_PER_YEAR)
            var highIdx = -1
            var lowIdx = -1
            for (i in windowStart until n) {
                if (highIdx < 0 || closes[i] > closes[highIdx]) highIdx = i
                if (lowIdx < 0 || closes[i] < closes[lowIdx]) lowIdx = i
            }
            val latestClose = if (n == 0) null else closes[n - 1]
            val range = if (highIdx >= 0 && lowIdx >= 0) {
                val span = closes[highIdx] - closes[lowIdx]
                FiftyTwoWeekRange(
                    high = closes[highIdx],
                    highDate = candles[highIdx].date,
                    low = closes[lowIdx],
                    lowDate = candles[lowIdx].date,
                    currentPositionPercent = if (span == 0.0) {
                        50.0
                    } else {
                        (latestClose!! - closes[lowIdx]) / span * 100.0
                    },
                )
            } else {
                null
            }

            // Peak-to-trough drawdown over the same window the technicals tab shows.
            var peak = 0.0
            var worst = 0.0
            for (i in 0 until n) {
                if (closes[i] <= 0.0) continue
                if (closes[i] > peak) peak = closes[i]
                val dd = (closes[i] - peak) / peak * 100.0
                if (dd < worst) worst = dd
            }

            val timeframe = candles.firstOrNull()?.timeframe ?: Timeframe.D1
            val signed = signedVolumes(candles)
            val slope = obvSlope(candles, TechnicalPolicy.SMA_FAST, signed)

            return IndicatorSnapshot(
                symbol = candles.firstOrNull()?.symbol ?: "",
                timeframe = timeframe,
                barCount = n,
                asOf = candles.lastOrNull()?.date,
                sma20 = sma20,
                sma50 = sma50,
                sma100 = sma100,
                sma200 = sma200,
                ema20 = emaArray(closes, TechnicalPolicy.EMA_FAST),
                ema50 = emaArray(closes, TechnicalPolicy.EMA_MID),
                ema200 = emaArray(closes, TechnicalPolicy.EMA_LONG),
                rsi = rsiFromCloses(closes, TechnicalPolicy.RSI_PERIOD),
                macd = macdOf(
                    closes,
                    TechnicalPolicy.MACD_FAST,
                    TechnicalPolicy.MACD_SLOW,
                    TechnicalPolicy.MACD_SIGNAL,
                ),
                trueRange = ranges.tr.toList(),
                atr = atr,
                atrPercent = atrPct,
                bollinger = bollingerFromCloses(
                    closes,
                    TechnicalPolicy.BOLLINGER_PERIOD,
                    TechnicalPolicy.BOLLINGER_MULTIPLIER,
                ),
                obv = obv.toList(),
                obvSlope = slope,
                volumeSma20 = volumeSma,
                volumeRatio = volRatio,
                deliveryAveragePercent = deliveryAveragePercentFromRaw(
                    delivery,
                    hasDelivery,
                    TechnicalPolicy.DELIVERY_SMA_PERIOD,
                ),
                deliveryCollapsing = isDeliveryCollapsing(candles),
                adx = adxFromRaw(ranges, TechnicalPolicy.ADX_PERIOD),
                relativeStrength = benchmarkCandles?.let { relativeStrength(candles, it) },
                fiftyTwoWeekHigh = if (highIdx >= 0) closes[highIdx] else null,
                fiftyTwoWeekLow = if (lowIdx >= 0) closes[lowIdx] else null,
                fiftyTwoWeekRange = range,
                trendline = trendlineFit(closes.toList(), regressionWindow),
                annualisedVolatilityPercent = annualisedVolatility(candles, timeframe = timeframe),
                maxDrawdownPercent = if (peak <= 0.0) null else -worst + 0.0,
                lastClose = latestClose,
            )
        }
    }
}