package com.mia.app.domain.technical

import com.mia.app.domain.model.Candle

/**
 * Simple and exponential moving averages (plan.md §4.2 `domain/technical/`).
 *
 * **Alignment contract, honoured by every function in this package:**
 * index 0 of the returned list corresponds to index 0 of the input list (the *oldest* bar),
 * and every bar inside an indicator's warm-up window yields `null`. Nothing is ever shifted,
 * compacted or back-filled — a caller can zip a series straight onto its candle list, and
 * `null` unambiguously means "not enough history yet", never "value missing".
 *
 * A period ≤ 0, or fewer input points than the period, yields an all-`null` list of the same
 * length. That is a value, not an exception: an indicator with too little history is an
 * expected state on this app, not an error (plan.md §6.4 "not enough history").
 *
 * Each function exists twice: a `DoubleArray` form that carries the maths, and thin `List`
 * / `List<Candle>` wrappers. There is exactly one implementation, so the standalone indicator
 * and the [IndicatorSnapshot] fusion can never drift apart.
 */

/**
 * Number of leading bars a simple moving average cannot fill: `period - 1`.
 *
 * Non-positive periods produce a fully null series, so they get [Int.MAX_VALUE] rather than a
 * negative number that would make `index < smaWarmupBars(period)` trivially true everywhere.
 */
fun smaWarmupBars(period: Int): Int = if (period <= 0) Int.MAX_VALUE else period - 1

/**
 * Simple moving average.
 *
 * `out[i] = (values[i-period+1] + … + values[i]) / period`, so the first non-null index is
 * `period - 1`.
 */
internal fun smaArray(values: DoubleArray, period: Int): List<Double?> {
    val size = values.size
    val out = MutableList<Double?>(size) { null }
    if (period <= 0 || size < period) return out

    // Running sum: O(n) instead of the textbook O(n * period). Matters on a 2 GB-RAM device
    // that re-runs the whole indicator panel for every watchlist symbol.
    var sum = 0.0
    for (i in 0 until size) {
        sum += values[i]
        if (i >= period) sum -= values[i - period]
        if (i >= period - 1) out[i] = sum / period
    }
    return out
}

/** Simple moving average over a list of points. See [smaArray]. */
fun sma(values: List<Double>, period: Int): List<Double?> =
    smaArray(values.toDoubleArray(), period)

/**
 * Simple moving average over candle closes — the form the app almost always wants.
 *
 * `@JvmName` is required because `List<Double>` and `List<Candle>` erase to the same JVM
 * signature; Kotlin would otherwise reject the two overloads even though it can tell them apart
 * from the argument types.
 */
@JvmName("smaOfCandles")
fun sma(candles: List<Candle>, period: Int): List<Double?> =
    smaArray(closesOf(candles), period)

/** Alias for [sma] over candle closes, for call sites that read better without an overload. */
fun smaClose(candles: List<Candle>, period: Int): List<Double?> = sma(candles, period)

/**
 * Exponential moving average with Wilder-style SMA seeding.
 *
 * * Seed: `out[period - 1] = SMA(values[0..period-1])`, the textbook initialisation. It means
 *   the EMA is defined from the very first computable bar, with no bias from an arbitrary
 *   starting point.
 * * Recursion: `out[i] = alpha * values[i] + (1 - alpha) * out[i-1]`, `alpha = 2/(period+1)`.
 *   Using `2/(period+1)` rather than `1/period` is what makes an EMA(20) and an SMA(20)
 *   genuinely comparable on the chart, which is the point of showing both.
 */
internal fun emaArray(values: DoubleArray, period: Int): List<Double?> {
    val size = values.size
    val out = MutableList<Double?>(size) { null }
    if (period <= 0 || size < period) return out

    val alpha = 2.0 / (period + 1.0)
    var seed = 0.0
    for (i in 0 until period) seed += values[i]
    var prev = seed / period
    out[period - 1] = prev

    for (i in period until size) {
        prev = alpha * values[i] + (1.0 - alpha) * prev
        out[i] = prev
    }
    return out
}

/** Exponential moving average over a list of points. See [emaArray]. */
fun ema(values: List<Double>, period: Int): List<Double?> =
    emaArray(values.toDoubleArray(), period)

/** Exponential moving average over candle closes. See `@JvmName` note on [sma]. */
@JvmName("emaOfCandles")
fun ema(candles: List<Candle>, period: Int): List<Double?> =
    emaArray(closesOf(candles), period)

/** Alias for [ema] over candle closes. */
fun emaClose(candles: List<Candle>, period: Int): List<Double?> = ema(candles, period)

/**
 * Rolling **sample** standard deviation over a `period`-bar window, smoothed with a simple
 * (not exponential) moving average.
 *
 * Two deliberate choices, both standard on charting platforms:
 * * SMA-style smoothing (`bollinger_stdev_mode = 0` on TradingView; `BollingerBands(20, 2)` in
 *   MetaStock), because that is what the bands a user already sees in their broker app are
 *   compared against.
 * * `ddof = 1` — divide by `period − 1`, not `period`. MetaStock, TA-Lib `STDDEV` and
 *   TradingView all use the sample convention, and `ddof = 0` would make our bands visibly
 *   narrower than everyone else's for the same prices.
 *
 * Computed from rolling sums of `x` and `x²` in O(n) rather than O(n·period):
 *
 * ```
 * mean = Σx / n
 * var  = (Σx² - (Σx)²/n) / (n - 1)
 * sd   = sqrt(var)
 * ```
 *
 * A constant series therefore gives **exactly** 0.0 rather than a value that decays toward it,
 * which matters because [BollingerResult.percentB] divides by the band width and a residue
 * there would put a spurious number on the chart.
 */
internal fun rollingStdevArray(values: DoubleArray, period: Int): List<Double?> {
    val size = values.size
    val out = MutableList<Double?>(size) { null }
    if (period < 2 || size < period) return out

    var sum = 0.0
    var sumSquares = 0.0
    for (i in 0 until period) {
        val v = values[i]
        sum += v
        sumSquares += v * v
    }
    out[period - 1] = sqrtSample(sum, sumSquares, period)

    for (i in period until size) {
        sum += values[i]
        sumSquares += values[i] * values[i]
        if (i >= period) {
            val dropped = values[i - period]
            sum -= dropped
            sumSquares -= dropped * dropped
        }
        out[i] = sqrtSample(sum, sumSquares, period)
    }
    return out
}

/**
 * Sample stdev from the running sums. Returns exactly `0.0` for a window whose values are all
 * equal, which is what keeps [BollingerResult.percentB] finite.
 */
private fun sqrtSample(sum: Double, sumSquares: Double, period: Int): Double {
    val n = period.toDouble()
    val meanSquare = sum * sum / n
    // Catastrophic cancellation can make this a tiny negative float on a near-constant series.
    val variance = ((sumSquares - meanSquare) / (n - 1.0)).coerceAtLeast(0.0)
    // `+ 0.0` normalises a -0.0 result, which would otherwise print as "-0.0" on the chart.
    return Math.sqrt(variance) + 0.0
}

/** Rolling sample stdev over a list of points. See [rollingStdevArray]. */
fun rollingStdev(values: List<Double>, period: Int): List<Double?> =
    rollingStdevArray(values.toDoubleArray(), period)

/** Rolling sample stdev of candle closes. See `@JvmName` note on [sma]. */
@JvmName("rollingStdevOfCandles")
fun rollingStdev(candles: List<Candle>, period: Int): List<Double?> =
    rollingStdevArray(closesOf(candles), period)

/** Alias for [rollingStdev] over candle closes. */
fun rollingStdevClose(candles: List<Candle>, period: Int): List<Double?> = rollingStdev(candles, period)

/** The close series of [candles] as a primitive array — the input every other indicator takes. */
internal fun closesOf(candles: List<Candle>): DoubleArray =
    DoubleArray(candles.size) { candles[it].close }

// ------------------------------------------------------------------- crossover helpers

/**
 * State of a two-line moving-average pair at one bar.
 *
 * Consumed by [smaCrossState] and [isDeathCross] for the plan.md §8.2 death-cross flag.
 */
enum class SmaCrossState {
    /** Not enough history on at least one line to say anything (warm-up, or short series). */
    UNKNOWN,

    /** Fast line is strictly above the slow line. */
    ABOVE,

    /** Fast line is strictly below the slow line. */
    BELOW,

    /** Lines are exactly equal. */
    EQUAL,
    ;

    val isKnown: Boolean get() = this != UNKNOWN
}

/**
 * Relative position of [fast] against [slow] at one bar.
 *
 * `null` for an out-of-range index or when either line is still warming up, so a caller
 * walking backwards from the last bar stops naturally at the first usable bar.
 */
fun smaCrossState(fast: List<Double?>, slow: List<Double?>, index: Int): SmaCrossState? {
    if (index < 0 || index >= fast.size || index >= slow.size) return null
    val f = fast[index] ?: return null
    val s = slow[index] ?: return null
    return when {
        f > s -> SmaCrossState.ABOVE
        f < s -> SmaCrossState.BELOW
        else -> SmaCrossState.EQUAL
    }
}

/**
 * Death cross test — plan.md §8.2 "Close below 200-DMA AND SMA50 < SMA200".
 *
 * This helper answers only the `SMA50 < SMA200` half; the red-flag engine owns the
 * conjunction so that the close-versus-200-DMA check and its evidence stay in one place.
 * [IndicatorSnapshot.isDeathCrossCondition] is the full test.
 *
 * Exact equality does **not** count as a cross: on a flat series both averages are equal and
 * a flag would be nonsense.
 */
fun isDeathCross(sma50: List<Double?>, sma200: List<Double?>): Boolean {
    val latest50 = sma50.lastOrNull() ?: return false
    val latest200 = sma200.lastOrNull() ?: return false
    return latest50 < latest200
}

/**
 * Index of the most recent bar at which the pair was `BELOW` while the immediately preceding
 * *known* bar was `ABOVE` — the bar the death cross printed on. `null` when no such bar exists,
 * so the flag's evidence can quote *when* it happened rather than only *that* it is true.
 *
 * The whole series is walked forwards and the last transition is kept, rather than stopping at
 * the first: the crossover that matters is the most recent one. A `BELOW` at the first usable
 * bar with no `ABOVE` before it is *not* a cross — there is no transition to observe.
 */
fun deathCrossBarIndex(fast: List<Double?>, slow: List<Double?>): Int? {
    var previousKnown: SmaCrossState? = null
    var lastCross: Int? = null
    for (i in 0 until minOf(fast.size, slow.size)) {
        val state = smaCrossState(fast, slow, i) ?: continue
        if (state == SmaCrossState.BELOW && previousKnown == SmaCrossState.ABOVE) lastCross = i
        previousKnown = state
    }
    return lastCross
}