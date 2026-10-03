package com.mia.app.domain.pattern.detectors.candlestick

import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.Timeframe
import com.mia.app.domain.pattern.priorTrendPct
import com.mia.app.domain.model.Measure
import kotlin.math.abs
import kotlin.math.min

/**
 * Every candlestick constant and the candle anatomy of `patterns-spec.md` §5, in one place.
 *
 * **Why a shared file.** Detectors 1–5 all read the same bar anatomy (`body` / `upperWick` /
 * `lowerWick` and their fractions), the same context thresholds ("after a sustained decline"
 * `3.0 %` daily / `5.0 %` weekly) and the same location band. If each detector carried its own
 * copy of those numbers, "lower wick is long" would mean three different things inside one
 * screen and a user could never be told *why* two similar-looking candles were graded
 * differently. `patterns-spec.md` §21 already collects every derived constant in one table;
 * this object is that table in code.
 *
 * Nothing here computes a score: the detectors emit `Measure`s and `FactorScorer` owns the
 * arithmetic (`docs/CONTRACTS.md` → "the detector ↔ scorer seam").
 */
object CandlestickPolicy {

    // ------------------------------------------------------------------------ §5.2 context

    /**
     * "after a sustained decline" `[Ch.17-2 §Best Location]`, `[Ch.17-3 §Best Location]`,
     * `patterns-spec.md` §5.2 `CONTEXT_DECLINE_PCT`, `[derived]`.
     *
     * Identical to the chart-pattern prior-trend threshold (§3.6) on purpose: the book uses the
     * same words for both families, so a bull flag and a bullish engulfing must not be graded
     * against different bars.
     */
    fun declineThresholdPct(timeframe: Timeframe): Double = when (timeframe) {
        Timeframe.D1 -> 3.0
        Timeframe.W1 -> 5.0
    }

    /** "after prolonged rallies" — the mirror of [declineThresholdPct]. `patterns-spec.md` §5.2. */
    fun riseThresholdPct(timeframe: Timeframe): Double = declineThresholdPct(timeframe)

    /**
     * The context look-back in bars: `20` daily (`≈ 1 month of sessions`) / `13` weekly
     * (`≈ 1 quarter`). `patterns-spec.md` §5.2 `CONTEXT_LOOKBACK`, `[derived]`.
     */
    fun contextLookback(timeframe: Timeframe): Int = when (timeframe) {
        Timeframe.D1 -> 20
        Timeframe.W1 -> 13
    }

    /**
     * How far from the context window's own extreme a pattern may form and still be "at the
     * level". `patterns-spec.md` §5.3 `LOCATION_BAND_PCT = 2.0`, `[derived]`.
     *
     * *"forms near strong support / at demand zones"* `[Ch.17-2 §Best Location]` gives no
     * distance. 2 % of price is the narrowest band that reliably separates "at the level" from
     * "somewhere in the lower half of the range".
     */
    const val LOCATION_BAND_PCT: Double = 2.0

    /**
     * How old a candlestick formation may be and still be reported.
     *
     * **Not in the spec, and that is the point.** A candlestick pattern is defined over the last
     * one to three bars `[Ch.17-2, Ch.17-3]`, so a 200-bar-old engulfing is not a *current*
     * pattern — it is history. `patterns-spec.md` §4 rule 3 ("ordered oldest → newest") and
     * §6.1 P2 ("the scan considers every `n`") describe an offline back-scan; §22 **DD-12**
     * separately says a match is re-emitted every day *while the formation persists*, which only
     * means anything if a stale formation stops being reported. `[derived]` 3 daily bars is the
     * window over which "the next session" still exists for the book's own follow-through rule
     * and the alert it schedules.
     */
    const val MAX_SIGNAL_AGE_BARS: Int = 3

    // ------------------------------------------------------------------- §6/§7 engulfing

    /**
     * "The second body is **significantly larger** than the first" `[Ch.17-2 §Structure]`.
     * `patterns-spec.md` §6.2 **S3** = `1.20`, `[derived]`.
     *
     * The book gives no number; its own worked example is a ₹15 body after a ₹15 body
     * `[Ch.17-2 §Example]`, so a doubling would reject the book's own fixture. 1.20 is the
     * loosest multiple that still reads as "significantly larger".
     */
    const val ENGULF_BODY_RATIO_MIN: Double = 1.20

    // --------------------------------------------------------- §8/§9 hammer & shooting star

    /** "long lower wick" — §8.2 **S1**, `[derived]`: half the bar's range, the loosest real rejection. */
    const val LONG_WICK_FRAC_MIN: Double = 0.50

    /** "little upper wick" — §8.2 **S2**, `[derived]`: the mirror, leaving 0.25 to the body. */
    const val OPPOSITE_WICK_FRAC_MAX: Double = 0.25

    /** "small body" — §8.2 **S3**, `[derived]`: relaxed from the 0.25 implied by S1+S2. */
    const val SMALL_BODY_FRAC_MAX: Double = 0.35

    /** §8.2 **S4** `lowerFrac >= 2.0 × bodyFrac`, `[derived]`: the wick is long *relative to the body*. */
    const val WICK_VS_BODY_MIN: Double = 2.0

    // --------------------------------------------------------------------- §10 star family

    /** "Large … candle" — §10.2 **S1/S3**, `[derived]`: a body filling at least half the bar. */
    const val STAR_LARGE_BODY_FRAC_MIN: Double = 0.50

    /** "Small body. Can be: small bullish, small bearish, doji" — §10.2 **S2**, `[derived]`. */
    const val STAR_SMALL_BODY_FRAC_MAX: Double = 0.30

    /**
     * "Closes **well into** the body of Candle 1" `[Ch.17-3 §Morning Star]` — §10.2 **S4**
     * `pen >= 0.50`, `[derived]`: the classical definition of "into the body", i.e. more than
     * half of the first candle's move has been recovered.
     */
    const val STAR_PENETRATION_MIN: Double = 0.50

    /** §10.3 **I5** `EXHAUSTION` look-back — "Suppose a stock has already risen 40 %". */
    fun exhaustionLookback(timeframe: Timeframe): Int = when (timeframe) {
        Timeframe.D1 -> 60
        Timeframe.W1 -> 40
    }

    /** §10.3 **I5**: `close[n] >= 1.40 × close[n − lookback]`. `[Ch.17-3 §When to Be Careful]`. */
    const val EXHAUSTION_MULTIPLE: Double = 1.40

    // ------------------------------------------------------------------ §3.7/§3.8 shared

    /** §3.7 `TRAP_WINDOW` — "the next one or two sessions" `[Ch.18-5 §Confirmation]`. */
    fun trapWindow(timeframe: Timeframe): Int = when (timeframe) {
        Timeframe.D1 -> 3
        Timeframe.W1 -> 2
    }

    /**
     * §6.3 **I3** / §8.3 **I3**: "no contradictory structure in the next 5 bars"
     * (`patterns-spec.md` §5.3, market-structure layer). `[derived]` to match
     * `[Ch.17-2 §Combining with Market Structure]`'s own five-bar window.
     */
    const val STRUCTURE_LOOKAHEAD_BARS: Int = 5

    /** §3.8: `rvol >= 2.0` confirms ("twice the average") `[Ch.17-3 §Role of Volume]`. */
    const val RVOL_CONFIRM: Double = 2.0

    /** §3.8: `rvol >= 3.0` is strong ("Volume: 3× average") `[Ch.17-4 §Layer 3]`. */
    const val RVOL_STRONG: Double = 3.0

    /** §5.3 Layer 3: `rvol < 1.5` on the signal bar is "the book wants participation". */
    const val RVOL_WEAK: Double = 1.5

    /** §3.8: `deliveryVsAvg >= 1.00` confirms `[Ch.21 §21.19]`. */
    const val DELIVERY_CONFIRM_RATIO: Double = 1.00

    /**
     * §6.5 **C6** penalty: big volume with `deliveryVsAvg < 0.70` is intraday churn rather than
     * ownership transfer `[Ch.21-6 §Delivery Volume]`, `[derived]` — 30 % below a stock's own
     * 20-session average.
     */
    const val DELIVERY_CHURN_RATIO: Double = 0.70

    /** §3.8: `gapRsi >= 3.0` RSI points is full divergence confirmation, `[derived]`. */
    const val DIVERGENCE_MIN_GAP_RSI: Double = 3.0

    /**
     * §6.6 / §19 `VOLATILE_MARKET`: "Candlestick patterns become less reliable. Large price swings
     * can distort short-term signals" `[Ch.17-4 §Highly Volatile Markets]`.
     * `atrPct[j] > 2.0 × median(atrPct over the trailing 120 bars)` — `[derived]` multiple, the
     * book gives prose only.
     */
    const val VOLATILE_ATR_MULTIPLE: Double = 2.0

    /** §6.6: the median window for the volatility trap. */
    const val VOLATILE_MEDIAN_BARS: Int = 120

    /**
     * §8.5 **C7** selling climax `[Ch.21 §21.22]`: the 20-bar volume average is in the top decile
     * of its own trailing 120 bars while price printed a 20-bar low. `[derived]` decile — the
     * book names the setup and no percentile.
     */
    const val CLIMAX_DECILE: Double = 0.90

    /** §6.5 **C5**: OBV slope over the 5 bars ending at the signal bar `[Ch.22 §22.29]`. */
    const val OBV_SLOPE_LOOKBACK: Int = 5

    /**
     * The shortest window a candlestick detector can read at all: the context look-back's first bar
     * must exist and the pattern's own bars must follow it (20 + 2, the longest formation in the
     * family). Reported as the D1 figure because it is the larger of the two.
     *
     * This sits far below `MatchScorePolicy.minBars(timeframe)` = 60/40, which every detector also
     * enforces (`patterns-spec.md` §6.1 **P1**). The value exists only so `PatternEngine` can skip
     * a detector that cannot possibly have a window — `patterns-spec.md` §20's note on what
     * `PatternDetector.minBars` is for.
     */
    val MIN_REQUIRED_BARS: Int = contextLookback(Timeframe.D1) + 2
}

/**
 * The candle anatomy of `patterns-spec.md` §5.1, computed once per bar.
 *
 * ```
 * body       = |close − open|
 * upperWick  = high − max(open, close)
 * lowerWick  = min(open, close) − low
 * range      = high − low
 * bodyFrac   = range > 0 ? body / range : 0      (and the same for the two wicks)
 * isBull     = close > open                      (strict; equality is a doji)
 * isBear     = close < open
 * ```
 *
 * `range == 0` gives every fraction `0.0`, which makes such a bar fail every shape rule in the
 * spec automatically — the spec says so explicitly, so it is encoded rather than special-cased.
 */
internal data class CandleShape(
    val body: Double,
    val upperWick: Double,
    val lowerWick: Double,
    val range: Double,
    val bodyFrac: Double,
    val upperFrac: Double,
    val lowerFrac: Double,
    val isBull: Boolean,
    val isBear: Boolean,
    /** `true` when the bar has no range at all and can satisfy no shape rule. */
    val isFlat: Boolean,
) {
    /** §8.2 **S6** / §9 **S6**: `lowerWick / max(body, 0.001 × low)`. */
    fun lowerWickRatio(low: Double): Double = lowerWick / maxOf(body, FLOOR_FRACTION * low)

    /** §9 **S6**: the mirror, `upperWick / max(body, 0.001 × high)`. */
    fun upperWickRatio(high: Double): Double = upperWick / maxOf(body, FLOOR_FRACTION * high)

    private companion object {
        /** §8.2 S6's own `0.001 × low` guard, so a doji's ratio cannot divide by zero. */
        const val FLOOR_FRACTION: Double = 0.001
    }
}

/** The §5.1 measures of [candle]. See [CandleShape]. */
internal fun shapeOf(candle: Candle): CandleShape {
    val body = abs(candle.close - candle.open)
    val upperWick = candle.high - maxOf(candle.open, candle.close)
    val lowerWick = min(candle.open, candle.close) - candle.low
    val range = candle.high - candle.low
    return if (range > 0.0) {
        CandleShape(
            body = body,
            upperWick = upperWick,
            lowerWick = lowerWick,
            range = range,
            bodyFrac = body / range,
            upperFrac = upperWick / range,
            lowerFrac = lowerWick / range,
            isBull = candle.close > candle.open,
            isBear = candle.close < candle.open,
            isFlat = false,
        )
    } else {
        CandleShape(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, false, false, isFlat = true)
    }
}

// ----------------------------------------------------------------------- context windows

/**
 * Lowest low over the inclusive bar window `[from, to]`, or `null` when nothing is in range.
 *
 * The window is the detector's **context window**, never its own bars: §22 **DD-11** ("Never
 * bars inside the pattern") exists to stop a pattern from satisfying its own context test.
 * Out-of-range ends are clamped, so a forward-looking window simply stops at the last bar rather
 * than inventing bars that have not printed yet.
 */
internal fun lowestLow(candles: List<Candle>, from: Int, to: Int): Double? {
    val start = maxOf(from, 0)
    val end = minOf(to, candles.lastIndex)
    if (start > end) return null
    var low = Double.MAX_VALUE
    for (i in start..end) low = min(low, candles[i].low)
    return low
}

/** Highest high over the inclusive bar window `[from, to]`, or `null`. See [lowestLow]. */
internal fun highestHigh(candles: List<Candle>, from: Int, to: Int): Double? {
    val start = maxOf(from, 0)
    val end = minOf(to, candles.lastIndex)
    if (start > end) return null
    var high = -Double.MAX_VALUE
    for (i in start..end) high = maxOf(high, candles[i].high)
    return high
}

/** Closes over the inclusive bar window — the §4.3 S4 "context window cleanliness" input. */
internal fun closesIn(candles: List<Candle>, from: Int, to: Int): List<Double> {
    val start = maxOf(from, 0)
    val end = minOf(to, candles.lastIndex)
    if (start > end) return emptyList() else return candles.subList(start, end + 1).map { it.close }
}

/**
 * `priorTrendPct` (§3.6) as a **magnitude** — the decline a bullish pattern needs and the rally
 * a bearish one needs, both expressed positive so the CONTEXT measure is a single reading.
 *
 * The sign still decides the precondition; it is applied by the detector before this is called.
 *
 * @return `null` when the look-back window does not exist — "no prior trend to judge", which the
 *   spec never treats as `0 %`.
 */
internal fun priorTrendMagnitude(candles: List<Candle>, bar: Int, timeframe: Timeframe): Double? {
    val closes = candles.map { it.close }
    val pct = priorTrendPct(closes, bar, CandlestickPolicy.contextLookback(timeframe)) ?: return null
    return abs(pct)
}