package com.mia.app.domain.technical

import com.mia.app.domain.model.Candle

/**
 * Ordinary least squares regression, used for the "Structure: trendline fit R²" input of
 * `ScoreBreakdown` (plan.md §6.3) and for the slope shown on the technicals tab.
 *
 * `x` is always a bar index `0, 1, 2, …`, so [TrendlineFit.intercept] is the *fitted* value
 * at x = 0 and not necessarily the first observed point.
 */

/**
 * A least-squares fit of `y = slope * x + intercept`, with the goodness-of-fit
 * `R² = 1 - Σ(y - ŷ)² / Σ(y - ȳ)²`, i.e. the share of the variance in `y` explained by a
 * straight line.
 *
 * **Flat-series decision** (documented because it changes a number the pattern scorer reads):
 * when every `y` is identical the variance `Σ(y - ȳ)²` is `0`, the formula is `0/0`, and two
 * common libraries report `1.0`. Neither is meaningful — a horizontal line has no trend to
 * fit, so this returns **0.0** ("the line explains none of the variation, because there is
 * none"). A flat series is therefore *not* a good trendline, which is the behaviour the §6.3
 * structure score needs.
 */
data class TrendlineFit(
    val slope: Double,
    val intercept: Double,
    val rSquared: Double,
    /** `true` when the input was too short or flat, i.e. the fit cannot be trusted. */
    val isDegenerate: Boolean,
) {
    /** Fitted value at bar index [x]. */
    fun at(x: Double): Double = slope * x + intercept
}

/** Slope, intercept and R² of the least-squares line through [values] against their index. */
fun linearRegression(values: List<Double>): TrendlineFit {
    val n = values.size
    if (n < 3) {
        // Zero/one point: no line. Two points always lie exactly on a line, so R² = 1 by
        // construction; reporting it would claim a "good trendline" from a single move, so
        // that case is flagged degenerate and the scorers are expected to skip it.
        return if (n == 2) {
            TrendlineFit(values[1] - values[0], values[0], 1.0, true)
        } else {
            TrendlineFit(0.0, 0.0, 0.0, true)
        }
    }

    val xMean = (n - 1) / 2.0
    var yMean = 0.0
    for (y in values) yMean += y
    yMean /= n

    var sxy = 0.0
    var sxx = 0.0
    var syy = 0.0
    for (i in 0 until n) {
        val dx = i - xMean
        val dy = values[i] - yMean
        sxy += dx * dy
        sxx += dx * dx
        syy += dy * dy
    }

    // sxx == 0 only when n == 1 (already handled); syy == 0 means a flat series.
    if (sxx == 0.0 || syy == 0.0) {
        return TrendlineFit(0.0, yMean, 0.0, true)
    }
    val slope = sxy / sxx
    val intercept = yMean - slope * xMean
    val rSquared = 1.0 - sse(values, slope, intercept) / syy
    return TrendlineFit(
        slope = slope,
        intercept = intercept,
        // Guard against a tiny negative from floating-point cancellation on a near-perfect
        // fit, which would otherwise break an `r² in [0, 1]` invariant check.
        rSquared = rSquared.coerceIn(0.0, 1.0),
        isDegenerate = false,
    )
}

private fun sse(values: List<Double>, slope: Double, intercept: Double): Double {
    var total = 0.0
    for (i in values.indices) {
        val residual = values[i] - (slope * i + intercept)
        total += residual * residual
    }
    return total
}

/** Alias matching the brief's naming; see [trendlineFit]. */
fun regression(values: List<Double>): TrendlineFit = linearRegression(values)

/**
 * Trendline fit over the last `window` points (default 30 bars ≈ six weeks of daily data),
 * with `x` re-based so index 0 is the first point of the window.
 *
 * Re-basing matters: the pattern scorer draws the fitted line over the visible chart, and an
 * intercept anchored at absolute bar 300 would put the line nowhere near the bars.
 */
fun trendlineFit(values: List<Double>, window: Int = TechnicalPolicy.REGRESSION_WINDOW): TrendlineFit {
    if (window <= 0 || values.isEmpty()) return TrendlineFit(0.0, 0.0, 0.0, true)
    if (values.size <= window) return linearRegression(values)
    return linearRegression(values.subList(values.size - window, values.size))
}

/** Trendline fit over the trailing window of candle closes. See [trendlineFit]. */
fun trendlineFitOf(
    candles: List<Candle>,
    window: Int = TechnicalPolicy.REGRESSION_WINDOW,
): TrendlineFit = trendlineFit(candles.map { it.close }, window)