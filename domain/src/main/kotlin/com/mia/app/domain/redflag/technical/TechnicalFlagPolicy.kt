package com.mia.app.domain.redflag.technical

/**
 * Stable flag ids for the technical group (plan.md §8.2, `redflags-spec.md` §7).
 *
 * **Why ids are constants and not inline strings.** `RedFlagRule.id` is documented as *the*
 * stable key for stored outcomes: an id that changes would silently orphan every historical
 * record of that flag. Putting them in one object means a rename is one edit and one grep, and
 * the test suite can assert the exact set without repeating literals in a dozen places.
 *
 * The ids themselves come from `redflags-spec.md` §8 ("Flag summary"). One documented divergence:
 * the spec's summary table lists a single `RSI_OVERBOUGHT_OR_OVERSOLD` id for `RF-T-2`, while §7.2
 * describes the flag as "`RSI_OVERBOUGHT` / `RSI_OVERSOLD`". The brief allows either; this package
 * ships **two** ids, because a single `RedFlagRule` cannot have two ids and one flag id that
 * changes with the reading is exactly the kind of unstable key the seam forbids.
 */
object TechnicalFlagIds {
    const val DEATH_CROSS: String = "DEATH_CROSS"
    const val RSI_OVERBOUGHT: String = "RSI_OVERBOUGHT"
    const val RSI_OVERSOLD: String = "RSI_OVERSOLD"
    const val DISTRIBUTION_DAY: String = "DISTRIBUTION_DAY"
    const val AT_52WEEK_LOW_WITH_DECLINING_OBV: String = "AT_52WEEK_LOW_WITH_DECLINING_OBV"
    const val DELIVERY_COLLAPSE: String = "DELIVERY_COLLAPSE"
}

/**
 * The severity bands and other thresholds that `redflags-spec.md` §7 states but that
 * `com.mia.app.domain.technical.TechnicalPolicy` does not carry.
 *
 * `TechnicalPolicy` owns everything the *indicators* need, and every one of its constants is
 * reused here verbatim (`SMA_LONG`, `RSI_OVERBOUGHT`, `RSI_OVERSOLD`, `VOLUME_SPIKE_MULTIPLE`,
 * `DELIVERY_COLLAPSE_RATIO`, `DELIVERY_COLLAPSE_CONSECUTIVE_BARS`, `DELIVERY_COVERAGE_REQUIREMENT`,
 * `DELIVERY_SMA_PERIOD`, `WEEKS_PER_YEAR`, `ATR_PERIOD`, `REGRESSION_WINDOW`, …). This object adds
 * **only** what the spec adds on top, so a reader can see at a glance which numbers come from the
 * reviewed policy and which are derived here.
 *
 * **These should be promoted into `TechnicalPolicy`** so the technicals tab and the red-flag engine
 * share one table. That file belongs to another agent, so it is reported rather than edited.
 */
object TechnicalRedFlagPolicy {

    /**
     * `RF-T-1` severity: a death cross printed within this many trading days of the latest bar is
     * `HIGH`; anything older is `MEDIUM` (`redflags-spec.md` §7.1).
     *
     * One week, so "how fresh is this?" answers in the unit the user reads prices in.
     */
    const val DEATH_CROSS_RECENT_BARS: Int = 5

    /**
     * `RF-T-3` severity: a distribution bar at or above this volume multiple is `HIGH` rather than
     * `MEDIUM` (`redflags-spec.md` §7.3).
     *
     * `[derived]` — the spec sets the 2.5× *trigger* from
     * `TechnicalPolicy.VOLUME_SPIKE_MULTIPLE` but names the escalation band without a number. 4.0
     * is the next conventional round multiple above 2.5 and is comfortably clear of it, so an
     * ordinary expansion cannot reach it by rounding.
     */
    const val DISTRIBUTION_HIGH_MULTIPLE: Double = 4.0

    /**
     * `RF-T-2` severity: the reading must have held for this many consecutive bars before the flag
     * is `MEDIUM` instead of `LOW` (`redflags-spec.md` §7.2).
     *
     * One week of sessions, matching [DEATH_CROSS_RECENT_BARS]: a one-bar excursion in an oscillator
     * is noise, and the book treats the *band* as a region a strong stock can sit in for months.
     */
    const val RSI_PERSISTENCE_BARS: Int = 5

    /**
     * `RF-T-4` severity: the close is `HIGH` rather than `MEDIUM` when it is within this fraction
     * below the 52-session low of the window *before* the latest bar (`redflags-spec.md` §7.4).
     *
     * **Why the prior window.** The condition itself is `close <= lowest close of the last 52
     * sessions`, and the latest bar is inside that window, so the two are equal by construction.
     * Measuring the 2 % band against a window that contains the close would therefore be dead code:
     * every firing would be `HIGH`. Measuring it against the 52 sessions *before* the latest bar
     * gives the band a job — a marginal break of the prior low versus a decisive new low — without
     * changing the condition, which stays on the fixed `IndicatorSnapshot` seam.
     */
    const val NEAR_LOW_TOLERANCE: Double = 0.02

    /**
     * `RF-T-5` severity: `HIGH` requires delivery to have been **above** its own 20-bar average on
     * each of this many bars immediately before the collapse *and* price to have fallen over that
     * same stretch (`redflags-spec.md` §7.5). Participation leaving after a run of high delivery is
     * materially different from participation that was never there.
     */
    const val DELIVERY_HIGH_PRIOR_BARS: Int = 5

    /**
     * `RF-T-5` suppression: the flag is withheld below this average daily turnover.
     *
     * `redflags-spec.md` §7.5 false-positive (c) cites the book's own *"In small caps volume can be
     * manipulated"* and requires suppressing "for stocks below a liquidity floor **and stating it**".
     * No liquidity floor exists anywhere in `TechnicalPolicy`, so this is a `[derived]` number and
     * is called out in the hand-off report as needing owner review against a real sample.
     *
     * 100,000 shares a session is roughly the point at which a ₹100 stock is a ₹1 crore turnover:
     * far below any large cap in the fixture, far above the sub-lakh-share names where the book's
     * warning applies. The rule never fires *silently* below it — the reason is reported through the
     * skip path so the UI can render "Not evaluated — …" (`redflags-spec.md` §11 rule 8).
     */
    const val MIN_LIQUIDITY_SHARES_PER_SESSION: Double = 100_000.0
}