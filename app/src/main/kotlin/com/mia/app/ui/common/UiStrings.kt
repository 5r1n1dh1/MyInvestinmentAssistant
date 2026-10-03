package com.mia.app.ui.common

/**
 * Every piece of UI copy that is **not** regulated copy.
 *
 * ### Why these are not in `:domain`
 * plan.md §1.4 puts the words that carry a compliance obligation — the disclaimer, what a match
 * score is, what a projected level is, what a hit rate is — in `Disclosure` and
 * `MatchScoreLabels` so that no screen can soften them. Those are the strings the app can be
 * held to.
 *
 * Everything else ("Indicators", "Volume", "Retry") is ordinary interface vocabulary. It lives
 * here, in one Kotlin object, for three reasons:
 *
 * 1. **One reviewable list.** `AdviceLanguage`'s compliance test walks user-facing literals; a
 *    single object is one file to check instead of seven screens' worth of `stringResource` calls.
 * 2. **No resource plumbing for internal chrome.** `R.string.*` is the right home for anything a
 *    translator will touch; these strings are not that.
 * 3. **Compile-checked.** A typo is a compile error, not a blank label at runtime.
 *
 * ### Rules for adding a string here
 * * Never state a conclusion about the future ("will", "expected", "likely").
 * * Never name a measured move a target. Use `MatchScoreLabels.PROJECTION_LABEL`.
 * * Never describe an indicator in a way that implies a verdict — "RSI 74, above the 70
 *   overbought line", not "overbought, watch for a fall".
 */
object UiStrings {

    // ------------------------------------------------------------------ timeframes (D1)
    const val TIMEFRAME_D1: String = "1D"
    const val TIMEFRAME_W1: String = "1W"

    // --------------------------------------------------------------------- stock header
    const val LAST_CLOSE: String = "Last close"
    const val CHANGE: String = "Change"
    const val OPEN: String = "Open"
    const val HIGH: String = "High"
    const val LOW: String = "Low"
    const val CLOSE: String = "Close"
    const val VOLUME: String = "Volume"
    const val DELIVERY: String = "Delivery"

    /**
     * Single-letter forms for the chart's crosshair readout.
     *
     * A full `O 1,234.50  H 1,240.00  L 1,229.00  C 1,238.75` line is already ~46 characters; at
     * `labelSmall` on a 360 dp phone a second line would push the plot below the fold. One letter
     * per field is the convention every broker terminal uses, so it needs no explanation.
     */
    const val SHORT_OPEN: String = "O"
    const val SHORT_HIGH: String = "H"
    const val SHORT_LOW: String = "L"
    const val SHORT_CLOSE: String = "C"
    const val SHORT_VOLUME: String = "V"
    const val SHORT_DELIVERY: String = "Del"

    // ------------------------------------------------------------------------ chart pane
    const val NOT_ENOUGH_HISTORY: String = "Not enough history"
    const val CHART_VOLUME_PANE: String = "Volume"
    const val CHART_EMPTY: String = "No bars to draw"

    /** Built from [NOT_ENOUGH_HISTORY]; a number, so the gate is always visible. */
    fun notEnoughHistoryDetail(have: Int, need: Int): String =
        "$NOT_ENOUGH_HISTORY to draw this timeframe: $have of $need bars stored."

    // ------------------------------------------------------------- empty-price-layer titles

    /**
     * Nothing has been downloaded on this device yet.
     *
     * Distinct from [NOT_ENOUGH_HISTORY] and from a red error banner on purpose: those say a series
     * is *short*, and an error says a read *failed*. This says neither — the device simply has no
     * data yet — which is the honest description of a fresh install and the state a fetch fixes.
     */
    const val NO_DATA_DOWNLOADED: String = "No market data downloaded yet"

    /**
     * The published files contain no rows for this ticker.
     *
     * Worth its own title because the remedy is completely different: re-downloading the same files
     * cannot invent a row, so this is usually a symbol outside the EQ/SM series the app keeps
     * (plan.md §3.1), or one that has been delisted or renamed (plan.md §9).
     */
    const val NO_DATA_FOR_SYMBOL: String = "No published data for this symbol"

    /** Reading the crosshair off a bar that has no OHLC (a gap in the series). */
    const val NO_BAR_SELECTED: String = "Touch a bar to read its OHLC"

    // --------------------------------------------------------------------- technicals tab
    const val INDICATORS: String = "Indicators"
    const val MOMENTUM: String = "Momentum"
    const val VOLATILITY: String = "Volatility"
    const val VOLUME_FLOW: String = "Volume and flow"
    const val TREND: String = "Trend"
    const val MOVING_AVERAGES: String = "Moving averages"
    const val PATTERN_MATCHES: String = "Pattern matches"
    const val MEASURED_MOVE: String = "Measured move"
    const val PROJECTION: String = "Projection"
    const val BREAKOUT_TRIGGER: String = "Breakout trigger"
    const val TRACK_RECORD: String = "Track record"
    const val TREND_REGIME: String = "Trend regime"

    /** One indicator's name plus its period, e.g. `RSI (14)`. */
    fun indicatorWithPeriod(name: String, period: Int): String = "$name ($period)"

    /** The two halves of the plan.md §8.2 death-cross condition, stated as measurements. */
    fun deathCrossState(sma50BelowSma200: Boolean, closeBelowSma200: Boolean): String = when {
        !sma50BelowSma200 && closeBelowSma200 ->
            "Close is below the 200-day moving average; the 50-day is still above it."
        sma50BelowSma200 && !closeBelowSma200 ->
            "The 50-day moving average is below the 200-day; price has not closed below the 200-day."
        sma50BelowSma200 && closeBelowSma200 ->
            "The 50-day moving average is below the 200-day and price closed below the 200-day."
        else -> "The 50-day moving average is above the 200-day and price closed above it."
    }

    /** Shown where a value does not exist yet, with the reason. Never a zero, never blank. */
    fun unavailable(reason: String): String = "Not available — $reason"

    // --------------------------------------------------------------------- red flag banner
    const val RED_FLAGS: String = "Red flags"
    const val RED_FLAG_EVIDENCE_TITLE: String = "Evidence"
    const val RED_FLAG_THRESHOLD: String = "Threshold"
    const val RED_FLAG_SOURCE: String = "Source"

    /** Tapping a red flag reveals its numbers; this says so before the user taps. */
    const val RED_FLAG_TAP_FOR_EVIDENCE: String = "Tap a flag to see the numbers behind it"

    // --------------------------------------------------------------------------- states
    const val LOADING: String = "Loading"
    const val RETRY: String = "Retry"
    const val COULD_NOT_LOAD: String = "Could not load"

    fun errorTitle(reason: String): String = "$COULD_NOT_LOAD — $reason"

    // ---------------------------------------------------------------------- data sources
    const val SOURCE: String = "Source"
    const val DIVERGENT_VALUE: String = "Other source"
    const val CONFLICT_CHIP: String = "Sources differ"

    /** Never averaged (plan.md §5.1): this is the whole explanation of the second number. */
    const val NOT_AVERAGED: String = "Shown separately, not averaged"

    /**
     * Why two numbers are on screen at once. Written as a statement about the merge, never as a
     * judgement about which source is right — plan.md §5.1 measured 7%+ divergence on TCS P/E
     * and never established which figure is correct.
     */
    fun conflictExplanation(deltaPercent: Double): String =
        "The two sources differ by ${formatFixed(deltaPercent, 1)}%. Both are shown; neither is averaged into the other."

    // ------------------------------------------------------------------------- staleness
    const val STALE: String = "Stale"
    const val FRESH: String = "Current"
    const val MISSING: String = "Missing"

    // ------------------------------------------------------------------------ hit rates
    const val HIT_RATE_WITHHELD: String = "Hit rate withheld"
    const val SAMPLES: String = "samples"

    fun withheldSamples(have: Int, need: Int): String =
        "$HIT_RATE_WITHHELD until $need tracked samples. This one has $have."

    // ---------------------------------------------------------------------- empty states

    /**
     * No matches is a *result*, not a failure, and the threshold is named so the reader knows
     * what was looked for.
     */
    fun noMatchesFound(renderThreshold: Int): String =
        "No pattern scored $renderThreshold or more on this timeframe"

    const val NO_RED_FLAGS: String = "No red flags in the data checked"
}
