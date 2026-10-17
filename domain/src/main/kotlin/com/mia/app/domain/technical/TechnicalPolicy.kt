package com.mia.app.domain.technical

import com.mia.app.domain.model.Timeframe

/**
 * Shared vocabulary and thresholds for the technical indicator library (plan.md §4.2
 * `domain/technical/`).
 *
 * Every number the indicators depend on lives here as a named constant with the reason it
 * was chosen. Nothing in this package hardcodes a magic period inline. That keeps the
 * technicals tab, the pattern scorers (§6.3) and the red-flag engine (§8.2) provably in
 * agreement — a silent drift between, say, RSI(14) in the chart and RSI(14) in a red flag
 * would be a correctness bug, not a cosmetic one.
 *
 * The package is pure Kotlin: no IO, no Android, no clock. Given the same `List<Candle>`
 * it always returns the same numbers, which is what makes it unit-testable on the JVM
 * (plan.md §12).
 */
object TechnicalPolicy {

    // ---------------------------------------------------------------- moving averages

    const val SMA_FAST: Int = 20
    const val SMA_MID: Int = 50
    const val SMA_SLOW: Int = 100
    const val SMA_LONG: Int = 200

    const val EMA_FAST: Int = 20
    const val EMA_MID: Int = 50
    const val EMA_LONG: Int = 200

    /**
     * Indian "52-week" window. We use the trading calendar (252 D1 bars / 52 W1 bars)
     * rather than 365 calendar days because the app only ever stores exchange sessions
     * ([Timeframe.barsPerYear]).
     */
    const val WEEKS_PER_YEAR: Int = 52

    // ------------------------------------------------------------------------ RSI

    const val RSI_PERIOD: Int = 14

    /**
     * plan.md §8.2. Conventional overbought / oversold band used by Wilder and by every
     * charting package; the red-flag engine reuses these two constants verbatim so the flag
     * and the chart can never disagree.
     */
    const val RSI_OVERBOUGHT: Double = 70.0
    const val RSI_OVERSOLD: Double = 30.0

    // ------------------------------------------------------------------- MFI

    /**
     * Money Flow Index period. Wilder's own default, and the same 14 RSI uses, so the two
     * indicators can be read side by side without a smoothing-convention difference doing the
     * explaining.
     */
    const val MFI_PERIOD: Int = 14

    /**
     * There are deliberately **no** `MFI_OVERBOUGHT` / `MFI_OVERSOLD` constants, and the absence is
     * the decision.
     *
     * RSI has them ([RSI_OVERBOUGHT], [RSI_OVERSOLD]) and this repository has already paid for
     * what that buys: the RSI oversold flag fires on 3.34% of rolling observations
     * (`docs/VERIFICATION.md`), which is a number consistent with the flag being nearly noise.
     * MFI's 80/20 convention is borrowed from the same literature as Wilder's 70/30, with no
     * measurement behind it in this codebase — so publishing one would put a threshold on a chart
     * that no test here can defend. The number and its warmup state are published; the level is not.
     */

    // ----------------------------------------------------------------------- MACD

    const val MACD_FAST: Int = 12
    const val MACD_SLOW: Int = 26
    const val MACD_SIGNAL: Int = 9

    // ------------------------------------------------------------ ATR / Bollinger

    const val ATR_PERIOD: Int = 14

    const val BOLLINGER_PERIOD: Int = 20

    /**
     * Two standard deviations. Bollinger's original definition uses 2; 1.5 is the squeeze
     * variant and 3 the wide variant. 2.0 is the default every platform ships with, so it
     * is what a user comparing the app against their broker's terminal will see.
     */
    const val BOLLINGER_MULTIPLIER: Double = 2.0

    // ------------------------------------------------------------------ ADX / trend

    const val ADX_PERIOD: Int = 14

    /**
     * ADX ≥ 25 → a trend is present. This is Wilder's own guidance and the value
     * MarketWatch/StockCharts document; below it the market is "not trending" and a
     * breakout pattern is far more likely to fail.
     */
    const val ADX_TRENDING: Double = 25.0

    /**
     * ADX < 20 → ranging. Between 20 and 25 the market is undecided, which [TrendRegime]
     * reports as [TrendRegime.UNKNOWN] rather than guessing a direction.
     */
    const val ADX_RANGING: Double = 20.0

    /** Conventional bands for overbought / oversold volume expansion (plan.md §8.2). */
    const val VOLUME_SPIKE_MULTIPLE: Double = 2.5

    // ---------------------------------------------------------------------- volume

    const val VOLUME_SMA_PERIOD: Int = 20
    const val DELIVERY_SMA_PERIOD: Int = 20

    /**
     * plan.md §8.2 "Delivery % collapsing vs 20-day average".
     *
     * Threshold = the latest delivery % is at most 40% of its own 20-bar average.
     *
     * Justification: delivery % is the share of traded volume that actually changed hands,
     * so it measures real participation rather than intraday churn. On NSE cash segment a
     * stock normally delivers 25–55% of its volume on a quiet day; a day that delivers 40%
     * of its own recent average is not "a bit quieter", it is roughly a halving of genuine
     * participation. Because day-to-day delivery is lumpy, comparing the latest single bar to
     * the 20-bar average (a ~1-month baseline) rather than to the previous day is what keeps
     * the flag from firing on ordinary noise. A relative (ratio) test is also used in place
     * of a fixed percentage-point drop because delivery averages differ by an order of
     * magnitude between a large cap (~50%) and a thin small cap (~8%), so a point threshold
     * cannot be honest for both.
     */
    const val DELIVERY_COLLAPSE_RATIO: Double = 0.40

    /**
     * Minimum fraction of the trailing delivery window that must actually carry a reading.
     *
     * 75%: NSE publishes the legacy delivery file for most, not all, sessions, and a flag
     * computed from one reading in a twenty-bar window would be an assertion with no evidence
     * behind it (plan.md §8.3 forbids bare assertions). Requiring three quarters of the
     * window keeps the evidence real while tolerating the few missing sessions that are
     * normal.
     */
    const val DELIVERY_COVERAGE_REQUIREMENT: Double = 0.75

    /** Consecutive collapsing bars required before the §8.2 delivery flag fires. */
    const val DELIVERY_COLLAPSE_CONSECUTIVE_BARS: Int = 2

    // ------------------------------------------------------------------- regression

    /**
     * Window used for the trendline-fit R² fed into `ScoreBreakdown.structure`
     * (plan.md §6.3). 30 bars ≈ six weeks of daily data: long enough that the fit is
     * meaningful, short enough that a 40-year-old formation does not dominate the score.
     */
    const val REGRESSION_WINDOW: Int = 30

    // ----------------------------------------------------------------- derived views

    /**
     * Bars needed for a 20/50/100/200 MA panel. A user with fewer than this gets an
     * honest "not enough history" rather than a partially-warm panel.
     */
    fun minBarsForMaPanel(): Int = SMA_LONG

    /** plan.md §6.4 hard gate, expressed in bars rather than as a score. */
    fun minBars(timeframe: Timeframe): Int =
        when (timeframe) {
            Timeframe.D1 -> 60
            Timeframe.W1 -> 40
        }
}