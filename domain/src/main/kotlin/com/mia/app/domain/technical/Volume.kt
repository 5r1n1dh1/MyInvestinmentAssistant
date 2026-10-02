package com.mia.app.domain.technical

import com.mia.app.domain.model.Candle
import java.time.LocalDate

/**
 * Volume-based indicators: On-Balance Volume, volume moving averages and NSE **delivery
 * percentage** (plan.md §3.5, §6.3 Confirmation, §8.2 technical flags).
 *
 * Delivery % is the share of a stock's traded volume that actually changed hands on the
 * exchange. It is the only participation measure in the app that is not pure turnover, which
 * is why the "delivery % collapsing" red flag (plan.md §8.2) is built on it rather than on raw
 * volume.
 *
 * **All functions here tolerate `deliveryPercent == null`.** The legacy NSE file that carries
 * delivery data is published for only some sessions, and a missing bar must produce a `null`
 * in the output — never a zero and never a `NaN`. Treating a missing bar as 0% would silently
 * manufacture the very "delivery collapsed" red flag the product depends on.
 */

/**
 * On-Balance Volume (AI-Invest Part 3, Ch.23 — volume confirmation).
 *
 * ```
 * obv[0] = 0
 * obv[i] = obv[i-1] + volume[i]  if close[i] > close[i-1]
 *        = obv[i-1] - volume[i]  if close[i] < close[i-1]
 *        = obv[i-1]              otherwise
 * ```
 *
 * OBV is a cumulative signed total, so its absolute level carries no meaning — only its slope
 * does. plan.md §8.2 uses it exactly that way ("price at 52-week low with declining OBV"),
 * via [obvSlope]. Index 0 is `0.0` rather than `null` because OBV is defined there; it just
 * cannot express direction without a previous close.
 */
fun obv(candles: List<Candle>): List<Double> {
    val out = DoubleArray(candles.size)
    for (i in 1 until candles.size) {
        val delta = candles[i].close - candles[i - 1].close
        out[i] = out[i - 1] + when {
            delta > 0 -> candles[i].volume
            delta < 0 -> -candles[i].volume
            else -> 0L
        }
    }
    return out.toList()
}

/** Signed volume per bar: `+volume` on an up close, `-volume` on a down close, else 0. */
internal fun signedVolumes(candles: List<Candle>): DoubleArray {
    val out = DoubleArray(candles.size)
    for (i in 1 until candles.size) {
        val delta = candles[i].close - candles[i - 1].close
        val sign = when {
            delta > 0 -> 1.0
            delta < 0 -> -1.0
            else -> 0.0
        }
        out[i] = sign * candles[i].volume
    }
    return out
}

/**
 * Change in OBV over the last `lookback` bars, normalised by the total volume traded over
 * that same window.
 *
 * Normalising matters: raw OBV differences scale with the stock's own turnover, so a ₹50
 * small cap and a ₹40,000 large cap would need different thresholds. This ratio lives in
 * `[-1, 1]` and is therefore comparable across symbols. A negative result is the "declining
 * OBV" plan.md §8.2 pairs with a 52-week low.
 *
 * `null` when the window does not exist or when every bar in it had zero volume (no gross
 * volume to normalise by).
 */
fun obvSlope(
    candles: List<Candle>,
    lookback: Int = TechnicalPolicy.SMA_FAST,
    signedVolume: DoubleArray = signedVolumes(candles),
): Double? {
    if (lookback <= 0 || candles.size < lookback + 1) return null
    var signed = 0.0
    var gross = 0.0
    for (i in (candles.size - lookback) until candles.size) {
        signed += signedVolume[i]
        gross += candles[i].volume
    }
    return if (gross == 0.0) null else signed / gross
}

/** Simple moving average of traded volume, aligned to `candles`. */
fun volumeSma(candles: List<Candle>, period: Int = TechnicalPolicy.VOLUME_SMA_PERIOD): List<Double?> =
    smaArray(DoubleArray(candles.size) { candles[it].volume.toDouble() }, period)

/**
 * Volume expansion ratio: `volume / averageVolume`.
 *
 * `null` when the average is zero (a halted or never-traded bar) — a division by zero, and a
 * `null` says "no baseline" where `Infinity` would say "infinite expansion".
 *
 * plan.md §8.2 flags "volume spike > 2.5x 20d average on a down day (distribution)"; the
 * expansion half is this function and the red-flag engine owns the conjunction with a down
 * close, so the flag keeps its evidence in one place.
 */
fun volumeRatio(volume: Double, averageVolume: Double): Double? =
    if (averageVolume == 0.0 || averageVolume.isNaN()) null else volume / averageVolume

/** Volume expansion for each candle against its own trailing average. */
fun volumeRatio(candles: List<Candle>, period: Int = TechnicalPolicy.VOLUME_SMA_PERIOD): List<Double?> {
    val averages = volumeSma(candles, period)
    return averages.mapIndexed { i, avg ->
        if (avg == null) null else volumeRatio(candles[i].volume.toDouble(), avg)
    }
}

/**
 * Rising volume at the latest bar — plan.md §6.3 "volume expansion on breakout".
 *
 * True when the latest bar's volume is at least `multiple` times its own 20-bar average.
 */
fun isVolumeExpanding(
    candles: List<Candle>,
    multiple: Double = TechnicalPolicy.VOLUME_SPIKE_MULTIPLE,
    period: Int = TechnicalPolicy.VOLUME_SMA_PERIOD,
): Boolean {
    val ratio = volumeRatio(candles, period).lastOrNull() ?: return false
    return ratio >= multiple
}

/**
 * Mean delivery percentage over a trailing window, aligned to `candles`.
 *
 * Only bars that actually carry a delivery figure are averaged in, and a bar is emitted only
 * once [TechnicalPolicy.DELIVERY_COVERAGE_REQUIREMENT] of the window has data — a symbol with
 * delivery published on 3 of the last 20 sessions does not get a confident reading, it gets
 * `null`.
 */
fun deliveryAveragePercent(
    candles: List<Candle>,
    period: Int = TechnicalPolicy.DELIVERY_SMA_PERIOD,
): List<Double?> {
    val size = candles.size
    val values = DoubleArray(size)
    val present = BooleanArray(size)
    for (i in 0 until size) {
        val d = candles[i].deliveryPercent
        if (d != null) {
            values[i] = d
            present[i] = true
        }
    }
    return deliveryAveragePercentFromRaw(values, present, period)
}

/** The single implementation of the delivery average, over raw arrays. See [deliveryAveragePercent]. */
internal fun deliveryAveragePercentFromRaw(
    delivery: DoubleArray,
    present: BooleanArray,
    period: Int,
): List<Double?> {
    val size = delivery.size
    val out = MutableList<Double?>(size) { null }
    if (period <= 0 || size < period) return out

    val ring = DoubleArray(period)
    var count = 0
    var sum = 0.0
    for (i in 0 until size) {
        if (present[i]) {
            val slot = i % period
            if (count == period) sum -= ring[slot]
            ring[slot] = delivery[i]
            sum += delivery[i]
            if (count < period) count++
        }
        if (i >= period - 1 && count >= TechnicalPolicy.DELIVERY_COVERAGE_REQUIREMENT * period) {
            out[i] = sum / count
        }
    }
    return out
}

/**
 * plan.md §8.2 — "Delivery % collapsing vs 20-day average".
 *
 * Fires when each of the last [consecutiveBars] bars' delivery % was at most
 * [TechnicalPolicy.DELIVERY_COLLAPSE_RATIO] of its own 20-bar average (see `TechnicalPolicy`
 * for the full justification). Two bars are required so that one soft session cannot raise a
 * red flag.
 *
 * Returns `false` when the delivery series is unavailable — absence of evidence is not evidence
 * of collapse.
 */
fun isDeliveryCollapsing(
    candles: List<Candle>,
    period: Int = TechnicalPolicy.DELIVERY_SMA_PERIOD,
    ratioThreshold: Double = TechnicalPolicy.DELIVERY_COLLAPSE_RATIO,
    consecutiveBars: Int = TechnicalPolicy.DELIVERY_COLLAPSE_CONSECUTIVE_BARS,
): Boolean {
    if (consecutiveBars <= 0) return false
    val averages = deliveryAveragePercent(candles, period)
    for (i in (candles.size - consecutiveBars) until candles.size) {
        if (i < 0) return false
        val latest = candles[i].deliveryPercent ?: return false
        val average = averages[i] ?: return false
        if (average <= 0.0 || latest > average * ratioThreshold) return false
    }
    return true
}

/**
 * Latest delivery reading versus its own 20-bar average, as the numbers the red flag shows on
 * tap (plan.md §8.3). `null` when either side is unavailable.
 */
fun deliveryCollapseEvidence(
    candles: List<Candle>,
    period: Int = TechnicalPolicy.DELIVERY_SMA_PERIOD,
): DeliveryCollapseEvidence? {
    val i = candles.lastIndex
    if (i < 0) return null
    val latest = candles[i].deliveryPercent ?: return null
    val average = deliveryAveragePercent(candles, period)[i] ?: return null
    if (average <= 0.0) return null
    return DeliveryCollapseEvidence(
        latestPercent = latest,
        averagePercent = average,
        ratio = latest / average,
        date = candles[i].date,
    )
}

/** The two delivery numbers behind the §8.2 flag, plus when they were observed. */
data class DeliveryCollapseEvidence(
    val latestPercent: Double,
    val averagePercent: Double,

    /** `latestPercent / averagePercent`; `<= 0.40` trips the flag. */
    val ratio: Double,
    val date: LocalDate,
)