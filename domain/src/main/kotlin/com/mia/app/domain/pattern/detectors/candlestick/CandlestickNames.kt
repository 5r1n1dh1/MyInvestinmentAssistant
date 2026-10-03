package com.mia.app.domain.pattern.detectors.candlestick

/**
 * The six canonical candlestick display names, with the `PatternIds` constant each one owns.
 *
 * **Why the names are pinned here.** `PatternIds` assigns fixed ids `1..6` to
 * "Bullish Engulfing", "Bearish Engulfing", "Hammer", "Shooting Star", "Morning Star" and
 * "Evening Star" because those strings are the **keys of the accuracy table** (decision D6, plan.md
 * §6.1): every tracked outcome is grouped per stock *and* per pattern name. A renamed `Match` does
 * not just look different, it starts a new, empty hit-rate table — and the app is required to
 * withhold hit rates below `MatchScorePolicy.MIN_HIT_RATE_SAMPLES` samples, so the user would
 * permanently see "not enough data" for a pattern they had been tracked on.
 *
 * `CandlestickNamesTest` asserts that every name here is still in
 * `PatternIds.CANONICAL_NAMES`, so a rename cannot slip in.
 */
internal object CandlestickNames {

    /** `PatternIds` id 1. Detector 1 and `Match.name`. */
    const val BULLISH_ENGULFING: String = "Bullish Engulfing"

    /** `PatternIds` id 2. Detector 2 and `Match.name`. */
    const val BEARISH_ENGULFING: String = "Bearish Engulfing"

    /** `PatternIds` id 3. Detector 3 and `Match.name`. */
    const val HAMMER: String = "Hammer"

    /** `PatternIds` id 4. Detector 4 and `Match.name`. */
    const val SHOOTING_STAR: String = "Shooting Star"

    /** `PatternIds` id 5. The star family's registered name and its bullish `Match.name`. */
    const val MORNING_STAR: String = "Morning Star"

    /** `PatternIds` id 6. The star family's bearish `Match.name`. */
    const val EVENING_STAR: String = "Evening Star"
}