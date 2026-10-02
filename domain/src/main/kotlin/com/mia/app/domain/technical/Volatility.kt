package com.mia.app.domain.technical

import com.mia.app.domain.model.Candle

/**
 * Volatility indicators: True Range / ATR and Bollinger Bands (plan.md §4.2).
 *
 * Both are consumed by the pattern scorer — ATR is the yardstick for whether a formation's
 * height is noise or signal (§6.3 Geometry), and the band width is what makes the chart's
 * volatility panel readable on a phone.
 */

/**
 * The three arrays Wilder's directional movement is defined on, produced in one pass over the
 * candles. [IndicatorSnapshot] reuses them instead of re-deriving true range a second time.
 */
internal class RangeArrays(
    val size: Int,
    val tr: DoubleArray,
    val plusDm: DoubleArray,
    val minusDm: DoubleArray,
)

/**
 * Builds true range and directional movement in a single pass.
 *
 * ```
 * TR[0]   = high - low
 * TR[i]    = max(high - low, |high - close[i-1]|, |low - close[i-1]|)
 * +DM[i]   = (high[i] - high[i-1]) if that is > 0 and > -DM[i], else 0
 * -DM[i]   = (low[i-1] - low[i])  if that is > 0 and > +DM[i], else 0
 * ```
 *
 * `+DM` and `-DM` are mutually exclusive by construction, which is the whole point of the
 * `-DM > +DM` clause in Wilder's definition: a bar that gapped up is not also a bar that
 * gapped down.
 */
internal fun rangeArrays(candles: List<Candle>): RangeArrays {
    val size = candles.size
    val tr = DoubleArray(size)
    val plusDm = DoubleArray(size)
    val minusDm = DoubleArray(size)
    if (size == 0) return RangeArrays(0, tr, plusDm, minusDm)

    tr[0] = candles[0].high - candles[0].low
    for (i in 1 until size) {
        val c = candles[i]
        val prev = candles[i - 1]
        tr[i] = maxOf(
            c.high - c.low,
            Math.abs(c.high - prev.close),
            Math.abs(c.low - prev.close),
        )
        val up = c.high - prev.high
        val down = prev.low - c.low
        plusDm[i] = if (up > down && up > 0) up else 0.0
        minusDm[i] = if (down > up && down > 0) down else 0.0
    }
    return RangeArrays(size, tr, plusDm, minusDm)
}

/**
 * True Range per bar, aligned to `candles`.
 *
 * `TR[0] = high - low` because there is no previous close. Always `>= 0` for a structurally
 * valid bar ([Candle.isValid] guarantees `high >= low`), and `0.0` for a perfectly flat bar.
 * Taking the `max` against the previous close is what makes a gap — the normal state of an
 * Indian stock opening after a weekend — count as volatility instead of silently shrinking
 * the ATR.
 */
fun trueRange(candles: List<Candle>): List<Double> = rangeArrays(candles).tr.toList()

/**
 * Average True Range, Wilder smoothing (`alpha = 1/period`).
 *
 * `atr[i] = null` for `i < period - 1`, mirroring [sma]; Wilder's own presentation also
 * starts the series one bar after the simple average, but alignment with [sma] is worth more
 * on a chart than matching that off-by-one.
 *
 * A **flat** series gives ATR `= 0.0`, not `null` and not `NaN`: ATR is a mean of true ranges,
 * and true ranges are legitimately zero when nothing moved. The helpers that divide by price
 * ([atrPercent], [annualisedVolatility]) are the ones that must return `null` rather than
 * `NaN` when a price is zero.
 */
fun atr(candles: List<Candle>, period: Int = TechnicalPolicy.ATR_PERIOD): List<Double?> =
    atrFromRaw(rangeArrays(candles).tr, period)

/** ATR from a pre-computed true-range series. See [atr]. */
fun atrFromRaw(tr: DoubleArray, period: Int): List<Double?> {
    val out = MutableList<Double?>(tr.size) { null }
    if (period <= 0 || tr.size < period) return out
    var prev = 0.0
    for (i in 0 until period) prev += tr[i]
    prev /= period
    out[period - 1] = prev
    for (i in period until tr.size) {
        prev = (prev * (period - 1) + tr[i]) / period
        out[i] = prev
    }
    return out
}

/**
 * Bollinger Bands: a `period`-SMA with an exponential rolling sample stdev scaled by
 * `multiplier`, plus the two derived readings the UI actually shows.
 *
 * Invariant for every non-null index: `upper >= middle >= lower`.
 *
 * * [BollingerResult.bandwidthPercent] = `(upper - lower) / middle * 100` — squeeze detection,
 *   and the conventional way to compare "how tight are the bands" across price scales. `null`
 *   when `middle == 0`, never `NaN`/`Infinity`.
 * * [BollingerResult.percentB] = `(close - lower) / (upper - lower)` — where price sits inside
 *   the bands: 0% at the lower band, 100% at the upper. On a **zero-width** band (a perfectly
 *   flat series, where the stdev decays to exactly 0) `percentB` is defined as `0.5`: the
 *   price is exactly on the middle band, which is the mathematically continuous answer and
 *   beats a `NaN` or an arbitrary 0/1. A close *outside* the bands is not clamped — a close
 *   above the upper band is precisely the breakout the app wants to surface.
 */
data class BollingerResult(
    val middle: List<Double?>,
    val upper: List<Double?>,
    val lower: List<Double?>,
    val bandwidthPercent: List<Double?>,
    val percentB: List<Double?>,
) {
    /** Latest reading of every series in one object for the chart legend; `null` if unavailable. */
    fun latest(): BollingerLatest? {
        val i = middle.lastIndex
        if (i < 0) return null
        val m = middle[i] ?: return null
        return BollingerLatest(
            middle = m,
            upper = upper[i],
            lower = lower[i],
            bandwidthPercent = bandwidthPercent[i],
            percentB = percentB[i],
        )
    }
}

/** Latest Bollinger reading. `upper`/`lower` are `null` while still warming up. */
data class BollingerLatest(
    val middle: Double,
    val upper: Double?,
    val lower: Double?,
    val bandwidthPercent: Double?,
    val percentB: Double?,
) {
    /** True when the bands are narrower than [maxBandwidthPercent] — a Bollinger squeeze. */
    fun isSqueeze(maxBandwidthPercent: Double): Boolean {
        val w = bandwidthPercent ?: return false
        return w < maxBandwidthPercent
    }
}

/** The single implementation of the Bollinger maths, from a raw close array. */
internal fun bollingerFromCloses(
    closes: DoubleArray,
    period: Int,
    multiplier: Double,
): BollingerResult {
    val size = closes.size
    val middle = smaArray(closes, period)
    val stdev = rollingStdevArray(closes, period)
    val upper = MutableList<Double?>(size) { null }
    val lower = MutableList<Double?>(size) { null }
    val bandwidth = MutableList<Double?>(size) { null }
    val percentB = MutableList<Double?>(size) { null }
    if (period <= 0 || multiplier < 0.0 || size < period) {
        return BollingerResult(middle, upper, lower, bandwidth, percentB)
    }
    for (i in 0 until size) {
        val m = middle[i] ?: continue
        val sd = stdev[i] ?: continue
        val u = m + multiplier * sd
        val l = m - multiplier * sd
        upper[i] = u
        lower[i] = l
        val width = u - l
        bandwidth[i] = if (m == 0.0) null else width / m * 100.0
        percentB[i] = if (width == 0.0) 0.5 else (closes[i] - l) / width
    }
    return BollingerResult(middle, upper, lower, bandwidth, percentB)
}

/** Bollinger Bands over candles. See [BollingerResult]. */
fun bollinger(
    candles: List<Candle>,
    period: Int = TechnicalPolicy.BOLLINGER_PERIOD,
    multiplier: Double = TechnicalPolicy.BOLLINGER_MULTIPLIER,
): BollingerResult = bollingerFromCloses(closesOf(candles), period, multiplier)

/**
 * ATR as a percentage of the close at the same bar.
 *
 * `null` where ATR is unavailable or the close is zero, so a scaled-in bar can never put
 * `NaN` on the chart's axis.
 */
fun atrPercent(candles: List<Candle>, period: Int = TechnicalPolicy.ATR_PERIOD): List<Double?> {
    val closes = closesOf(candles)
    return atr(candles, period).mapIndexed { i, a ->
        if (a == null || closes[i] == 0.0) null else a / closes[i] * 100.0
    }
}