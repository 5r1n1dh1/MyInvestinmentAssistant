package com.mia.app.domain.technical

import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.Timeframe
import java.time.LocalDate
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Small numeric utilities shared by the technicals tab, the share-image renderer and the risk
 * section (plan.md §4.2, §7).
 *
 * They live next to the indicators rather than in a `Util` grab-bag because each one has an
 * indicator-shaped contract: `null` in, `null` out, never an exception and never a `NaN`.
 */

/** Trailing-window high/low close plus where the latest close sits inside that range. */
data class FiftyTwoWeekRange(
    val high: Double,
    val highDate: LocalDate,
    val low: Double,
    val lowDate: LocalDate,

    /** 0% = at the 52-week low, 100% = at the high. 50% when high == low. */
    val currentPositionPercent: Double,
)

/**
 * 52-week range over the trailing 52 bars.
 *
 * 52 *bars*, not 52 calendar days: the app only stores exchange sessions, and plan.md §4.2
 * works in [Timeframe].52 trading weeks rather than 365 calendar days. On a D1 series the two
 * differ by ~15%, which is the difference between "at its low since March" and "at its low
 * since February" on a screen the user will check.
 *
 * `null` for an empty series. `currentPositionPercent` is exactly 50% when the window's high
 * equals its low (nothing moved), instead of a division by zero.
 */
fun fiftyTwoWeekRange(candles: List<Candle>): FiftyTwoWeekRange? {
    val window = candles.takeLast(TechnicalPolicy.WEEKS_PER_YEAR)
    val high = window.maxByOrNull { it.close } ?: return null
    val low = window.minByOrNull { it.close } ?: return null
    val latest = candles.last().close
    val span = high.close - low.close
    return FiftyTwoWeekRange(
        high = high.close,
        highDate = high.date,
        low = low.close,
        lowDate = low.date,
        currentPositionPercent = if (span == 0.0) 50.0 else (latest - low.close) / span * 100.0,
    )
}

/** Highest close over the trailing 52 bars. `null` for an empty series. */
fun fiftyTwoWeekHigh(candles: List<Candle>): Double? =
    candles.takeLast(TechnicalPolicy.WEEKS_PER_YEAR).maxOfOrNull { it.close }

/** Lowest close over the trailing 52 bars. `null` for an empty series. */
fun fiftyTwoWeekLow(candles: List<Candle>): Double? =
    candles.takeLast(TechnicalPolicy.WEEKS_PER_YEAR).minOfOrNull { it.close }

/** True when the latest close *is* the 52-week low — plan.md §8.2's first half. */
fun isAtFiftyTwoWeekLow(candles: List<Candle>): Boolean {
    val low = fiftyTwoWeekLow(candles) ?: return false
    val latest = candles.lastOrNull()?.close ?: return false
    return latest <= low
}

/**
 * Annualised volatility of log returns over a trailing window, as a **percentage**.
 *
 * ```
 * r[i] = ln(close[i] / close[i-1])
 * sigma = stdev(r, sample) * sqrt(barsPerYear) * 100
 * ```
 *
 * Log returns rather than simple returns because daily equity moves are small enough that the
 * two agree to several decimals, while log returns are *additive* across days, which is what
 * makes the `sqrt(barsPerYear)` annualisation exact rather than approximate.
 *
 * Sample (`n - 1`) stdev and [Timeframe.barsPerYear] (252 daily / 52 weekly sessions) are the
 * standard convention; annualising by 365 instead would understate daily volatility by ~9%.
 *
 * `null` when there is no window, when fewer than `period + 1` bars exist (a stdev of a single
 * return is not a volatility estimate), or when any close in the window is non-positive — `ln`
 * would produce `NaN`/`-Infinity`, and a zero price is not real data.
 */
fun annualisedVolatility(
    candles: List<Candle>,
    period: Int = TechnicalPolicy.SMA_MID,
    timeframe: Timeframe = candles.firstOrNull()?.timeframe ?: Timeframe.D1,
): Double? {
    if (period <= 1 || candles.size < period + 1) return null
    val window = candles.takeLast(period + 1)
    val returns = DoubleArray(period)
    for (i in 1..period) {
        val previous = window[i - 1].close
        val current = window[i].close
        if (previous <= 0.0 || current <= 0.0) return null
        returns[i - 1] = ln(current / previous)
    }
    val stdev = sampleStdev(returns.toList()) ?: return null
    return stdev * sqrt(timeframe.barsPerYear.toDouble()) * 100.0
}

/**
 * Sample (n − 1) standard deviation. `null` for fewer than two points, since a single point has
 * no dispersion to measure.
 */
fun sampleStdev(values: List<Double>): Double? {
    val n = values.size
    if (n < 2) return null
    var mean = 0.0
    for (v in values) mean += v
    mean /= n
    var accum = 0.0
    for (v in values) {
        val d = v - mean
        accum += d * d
    }
    return sqrt(accum / (n - 1))
}

/**
 * Percentage change from [from] to [to]: `(to - from) / from * 100`.
 *
 * `null` when [from] is zero, negative or `NaN` — a percentage change *from* zero is undefined,
 * and returning `Infinity` would put an ∞ on the share image.
 */
fun percentChange(from: Double, to: Double): Double? {
    if (from == 0.0 || from.isNaN() || from.isInfinite()) return null
    if (to.isNaN() || to.isInfinite()) return null
    return (to - from) / from * 100.0
}

/** Percentage change between two bars' closes. See [percentChange]. */
fun percentChange(from: Candle, to: Candle): Double? = percentChange(from.close, to.close)

/**
 * Percentage change of the latest close against the close [bars] bars earlier — the "1D / 1W /
 * 1M / 1Y" row on the technicals tab. `null` when the window does not exist.
 */
fun percentChangeOver(candles: List<Candle>, bars: Int): Double? {
    if (bars <= 0 || candles.size < bars + 1) return null
    return percentChange(candles[candles.size - 1 - bars].close, candles.last().close)
}

/**
 * Maximum drawdown of the close series as a **positive** percentage, so 25.0 means "fell 25%
 * from its peak".
 *
 * The sign convention is chosen so the renderer never has to remember to negate the number, and
 * so the plan.md §12 invariant "drawdown is never negative" is actually meaningful.
 *
 * ```
 * peak[i]  = running maximum of close
 * dd[i]    = (close[i] - peak[i]) / peak[i] * 100      (<= 0)
 * result   = -min(dd)                                   (>= 0)
 * ```
 *
 * Bars with a non-positive close are skipped: they cannot be a peak and a zero price would
 * divide by zero. `null` when no bar in the series has a usable close.
 */
fun maxDrawdown(candles: List<Candle>): Double? {
    var peak = 0.0
    var worst = 0.0
    for (c in candles) {
        if (c.close <= 0.0 || c.close.isNaN()) continue
        if (c.close > peak) peak = c.close
        val dd = (c.close - peak) / peak * 100.0
        if (dd < worst) worst = dd
    }
    // `+ 0.0` normalises the -0.0 that a monotonically rising series would otherwise produce, which
    // would render as "-0.00%" on the share image.
    return if (peak <= 0.0) null else -worst + 0.0
}