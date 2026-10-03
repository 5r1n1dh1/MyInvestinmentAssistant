package com.mia.app.domain.pattern.detectors.candlestick

import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.Timeframe
import com.mia.app.domain.pattern.priorTrendPct
import com.mia.app.domain.technical.TechnicalPolicy
import com.mia.app.domain.technical.adx
import com.mia.app.domain.technical.atr
import com.mia.app.domain.technical.atrPercent
import com.mia.app.domain.technical.deliveryAveragePercent
import com.mia.app.domain.technical.isRsiDivergence
import com.mia.app.domain.technical.obvSlope
import com.mia.app.domain.technical.rsi
import com.mia.app.domain.technical.trendlineFitOf
import com.mia.app.domain.technical.volumeRatio
import com.mia.app.domain.technical.volumeSma
import kotlin.math.abs

/**
 * The per-invocation technical readings detectors 1–5 need, read once per `detect` call.
 *
 * **Why this exists rather than an `IndicatorSnapshot`.** `scoring-spec.md` §5.2 and §6 read
 * `rvol`, RSI, divergence, delivery %, ADX and +DI/−DI off the shared indicator set, and
 * `IndicatorSnapshot.of` already computes every one of them. But the locked
 * `PatternDetector.detect(pivots, candles)` signature ([`com.mia.app.domain.model.PatternDetector`])
 * carries no snapshot, so a detector inside it cannot reach one. Rather than rebuild the whole
 * snapshot — SMA/EMA/MACD/Bollinger/52-week range, none of which a candle reads — this class
 * computes the six O(n) series that the spec's confirmation and context measures actually name,
 * each through the **shared** `domain/technical` primitive so a detector can never disagree with
 * the technicals tab about the same number.
 *
 * *Reported to the orchestrator*: handing `IndicatorSnapshot.of(candles)` to the detectors (one
 * line in `PatternEngine`, which is not mine to change) would replace these five passes with one
 * and would additionally unlock the relative-strength CONTEXT measure **X4**, which is
 * permanently neutral at `0.5` here because a benchmark index is not reachable from `detect`.
 *
 * Cost is O(n) with a small constant and nothing is cached between calls: no mutable global
 * state (plan.md §4.3 purity), identical inputs give identical output byte-for-byte.
 */
internal class CandlestickSignals(candles: List<Candle>) {

    val timeframe: Timeframe = candles.firstOrNull()?.timeframe ?: Timeframe.D1
    val barCount: Int = candles.size

    private val closes: List<Double> = candles.map { it.close }

    /** Wilder RSI(14), aligned to [candles]; `null` inside the warm-up. `[Ch.22 §22.16]` */
    val rsi: List<Double?> = rsi(candles).rsi

    /** Wilder ATR(14), aligned to [candles]. The book's own volatility yardstick `[Ch.22 §22.26]`. */
    val atr: List<Double?> = atr(candles)

    /** ATR as a % of close — the §6.6 `VOLATILE_MARKET` input. */
    val atrPercent: List<Double?> = atrPercent(candles)

    /** 20-bar average volume `[Ch.21 §21.5]`. */
    val volumeAverage: List<Double?> = volumeSma(candles, TechnicalPolicy.VOLUME_SMA_PERIOD)

    /** `volume[j] / volumeSma20[j]` — §3.8 `rvol`. `null` where the average does not exist. */
    val rvol: List<Double?> = candles.mapIndexed { i, candle ->
        volumeAverage[i]?.let { volumeRatio(candle.volume.toDouble(), it) }
    }

    /** Trailing mean delivery %, aligned to [candles] `[Ch.21 §21.18]`. */
    val deliveryAverage: List<Double?> = deliveryAveragePercent(candles, TechnicalPolicy.DELIVERY_SMA_PERIOD)

    private val adxFamily = adx(candles, TechnicalPolicy.ADX_PERIOD)

    /** §6 **X2** ADX at a bar; `null` inside the warm-up. */
    fun adxAt(bar: Int): Double? = adxFamily.adx.getOrNull(bar)?.takeIf { it.isFinite() }

    /** §6 **X3** `+DI > −DI` for a bullish reading, the mirror for a bearish one. */
    fun diAlignedAt(bar: Int, bullish: Boolean): Boolean {
        val plus = adxFamily.diPlus.getOrNull(bar) ?: return false
        val minus = adxFamily.diMinus.getOrNull(bar) ?: return false
        return if (bullish) plus > minus else minus > plus
    }

    /**
     * §4.3 **S2** "trendline fit R² (closes)" — `IndicatorSnapshot.trendlineFitRSquared`
     * computed over the same 30-bar window the technicals tab shows. A degenerate (flat) fit
     * reports `0.0`, never `1.0`, following the shared convention.
     */
    val trendlineRSquared: Double =
        trendlineFitOf(candles).let { if (it.isDegenerate) 0.0 else it.rSquared }

    /** RSI at [bar], `null` inside the warm-up. `[Ch.22 §22.16]` */
    fun rsiAt(bar: Int): Double? = rsi.getOrNull(bar)?.takeIf { it.isFinite() }

    /** ATR at [bar], `null` inside the warm-up. */
    fun atrAt(bar: Int): Double? = atr.getOrNull(bar)?.takeIf { it.isFinite() && it > 0.0 }

    /** `rvol` at [bar], `null` when the 20-bar average does not exist yet. */
    fun rvolAt(bar: Int): Double? = rvol.getOrNull(bar)?.takeIf { it.isFinite() }

    /**
     * `deliveryVsAvg(j) = deliveryPercent[j] / deliveryAverage[j]` — §3.8, `[Ch.21 §21.19]`.
     * `null` when either side is missing, which the spec says must read as **0**, never as
     * "average" ([`CandlestickScoring`] K5).
     */
    fun deliveryVsAverageAt(candles: List<Candle>, bar: Int): Double? {
        val latest = candles.getOrNull(bar)?.deliveryPercent ?: return null
        val average = deliveryAverage.getOrNull(bar) ?: return null
        if (average <= 0.0) return null
        return latest / average
    }

    /**
     * `obvSlope` over the [CandlestickPolicy.OBV_SLOPE_LOOKBACK] bars ending at [bar] —
     * §6.5 **C5**, `[Ch.22 §22.29]`. `null` when the window does not exist.
     *
     * The shared `obvSlope` is a *latest-value* function, so it is called on the prefix ending
     * at [bar]; only the one or two bars a detector actually reads ever pay for it.
     */
    fun obvSlopeAt(candles: List<Candle>, bar: Int): Double? {
        if (bar < 0 || bar >= barCount) return null
        return obvSlope(candles.subList(0, bar + 1), CandlestickPolicy.OBV_SLOPE_LOOKBACK)
    }

    /**
     * §3.8 RSI divergence over the trailing context window, using the book's own definition:
     * a bullish divergence is price making a **lower low** while RSI makes a **higher low**
     * (`[Ch.22 §22.17]`); a bearish divergence is the mirror.
     *
     * §8.5 **C4** and §10.5 **C3** ask the question as "a divergence over the last 20 bars"
     * rather than naming a pivot pair, so the earlier bar is taken to be the extreme of the
     * context window that precedes the signal — the swing low a bullish pattern must be reacting
     * to, the swing high a bearish one must be rejecting.
     *
     * @return `0.0` when no divergence is present, `0.5` when one is present but its RSI gap is
     *   under [CandlestickPolicy.DIVERGENCE_MIN_GAP_RSI], `1.0` for a full gap — the spec's
     *   `1 / 0.5 / 0` reading (`scoring-spec.md` §5.2 K4).
     */
    fun divergenceScore(candles: List<Candle>, from: Int, signalBar: Int, bullish: Boolean): Double {
        val searchFrom = maxOf(from, 0)
        val searchTo = signalBar - 1
        if (searchFrom > searchTo || searchTo >= barCount) return 0.0
        if (signalBar - searchFrom < 3) return 0.0 // §3.8: the two pivots must be ≥ 3 bars apart

        var earlier = searchFrom
        for (i in searchFrom..searchTo) {
            if (if (bullish) candles[i].low < candles[earlier].low else candles[i].high > candles[earlier].high) {
                earlier = i
            }
        }
        val earlierRsi = rsi.getOrNull(earlier) ?: return 0.0
        val laterRsi = rsi.getOrNull(signalBar) ?: return 0.0
        val (earlierPrice, laterPrice) = if (bullish) {
            candles[earlier].low to candles[signalBar].low
        } else {
            candles[earlier].high to candles[signalBar].high
        }
        if (!isRsiDivergence(rsi, earlier, signalBar, earlierPrice, laterPrice)) return 0.0
        val gap = abs(laterRsi - earlierRsi)
        return if (gap >= CandlestickPolicy.DIVERGENCE_MIN_GAP_RSI) 1.0 else 0.5
    }

    /**
     * §6.6 `VOLATILE_MARKET`: `atrPct[bar] > 2.0 × median(atrPct over the trailing 120 bars)`.
     *
     * "Candlestick patterns become less reliable. Large price swings can distort short-term
     * signals" `[Ch.17-4 §Highly Volatile Markets]`. `false` whenever the reading is unavailable —
     * absence of evidence is not evidence of volatility.
     */
    fun isVolatileMarket(bar: Int): Boolean {
        val latest = atrPercent.getOrNull(bar)?.takeIf { it.isFinite() } ?: return false
        val sample = finiteSamples(atrPercent, bar)
        if (sample.size < MIN_VOLATILITY_SAMPLES) return false
        val median = sample.sorted()[sample.size / 2]
        return median > 0.0 && latest > CandlestickPolicy.VOLATILE_ATR_MULTIPLE * median
    }

    /**
     * §8.5 **C7** selling climax / §9 buying climax `[Ch.21 §21.22]`: the 20-bar volume average is
     * in the top decile of its own trailing 120 bars **while price printed a 20-bar low**
     * (the mirror for the buying climax).
     *
     * `[derived]` decile — the book names the setup and gives no percentile.
     */
    fun isClimax(candles: List<Candle>, bar: Int, bullish: Boolean): Boolean {
        val average = volumeAverage.getOrNull(bar) ?: return false
        val sorted = finiteSamples(volumeAverage, bar).sorted()
        if (sorted.size < MIN_VOLATILITY_SAMPLES) return false
        val index = (CandlestickPolicy.CLIMAX_DECILE * (sorted.size - 1)).toInt()
            .coerceIn(0, sorted.lastIndex)
        if (average <= sorted[index]) return false
        val windowLow = lowestLow(candles, bar - contextLookback() + 1, bar) ?: return false
        val windowHigh = highestHigh(candles, bar - contextLookback() + 1, bar) ?: return false
        return if (bullish) candles[bar].low <= windowLow else candles[bar].high >= windowHigh
    }

    /**
     * The finite readings of [series] over the trailing
     * [CandlestickPolicy.VOLATILE_MEDIAN_BARS] bars ending at [bar].
     *
     * A median over a handful of points would compare the signal bar against itself, so the two
     * percentile tests below require at least [MIN_VOLATILITY_SAMPLES] readings and otherwise
     * report "no evidence".
     */
    private fun finiteSamples(series: List<Double?>, bar: Int): List<Double> {
        val start = maxOf(0, bar - CandlestickPolicy.VOLATILE_MEDIAN_BARS + 1)
        if (start > bar) return emptyList()
        val out = ArrayList<Double>(bar - start + 1)
        for (i in start..minOf(bar, series.lastIndex)) {
            val value = series[i] ?: continue
            if (value.isFinite()) out += value
        }
        return out
    }

    private companion object {
        /** Enough readings for a percentile to mean anything. */
        const val MIN_VOLATILITY_SAMPLES: Int = 20
    }

    /** §3.6 prior trend at [bar] in signed form; `null` when the look-back does not exist. */
    fun priorTrendPctAt(bar: Int): Double? =
        priorTrendPct(closes, bar, CandlestickPolicy.contextLookback(timeframe))

    /** The context look-back in bars for this series' timeframe. */
    fun contextLookback(): Int = CandlestickPolicy.contextLookback(timeframe)
}