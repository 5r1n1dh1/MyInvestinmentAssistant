package com.mia.app.domain.accuracy

import com.mia.app.domain.model.AccuracyKey
import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.Match
import com.mia.app.domain.model.MatchScorePolicy
import com.mia.app.domain.model.OutcomeTrackerPolicy
import com.mia.app.domain.model.PatternOutcome
import java.time.LocalDate

/**
 * The lifecycle of a projection, from pattern detection to a settled outcome (plan.md §6.1,
 * §6.6, phase P9, decision D6).
 *
 * This module is the product's differentiator: the app publishes its own track record. That
 * only means anything if the record cannot be bent, so every rule below exists to stop the
 * number flattering itself:
 *
 * 1. **A hit is a chart event, never a profit.** A hit means the measured-move level was
 *    *touched* inside the horizon ([OutcomeTrackerPolicy.isHit]). There is no expected
 *    return, no P&L and no win/loss anywhere in this package (plan.md §1.4).
 * 2. **Nothing is trackable without a measured move.** A candlestick formation has no height
 *    to project (spec DD-14), so there is no level whose reach could be recorded; tracking it
 *    would mean inventing a verdict out of nothing.
 * 3. **Nothing is trackable that the app never showed.** The score gate keeps suppressed
 *    patterns out of the sample (see [MIN_SCORE_TO_TRACK]).
 * 4. **A verdict is written once.** A resolved [PatternOutcome] is immutable: re-evaluating it
 *    with more bars cannot move `hit` from true to false, and cannot un-resolve it. This is
 *    what stops a hit rate being rewritten after the fact.
 * 5. **A verdict waits for the whole window.** `resolved` is set only once the full horizon of
 *    bars has elapsed, because a hit rate that counts half-elapsed windows is inflated by
 *    construction.
 * 6. **The projection bar never counts**, and neither does any bar dated after [today] — a
 *    verdict may not be computed from data that did not exist when it was asked for.
 *
 * Pure functions, no IO, no Android (plan.md §4.3). `today` is a defaulted parameter rather
 * than a clock read so every call is reproducible in tests.
 */
object OutcomeTracker {

    /**
     * Lowest score that may enter the track record: [MatchScorePolicy.RENDER_THRESHOLD].
     *
     * The record is meant to describe the patterns the app actually showed, so a match that
     * `verdict()` would suppress (`score < 50`) must never contribute a sample. It is not a
     * quality filter and it is not a threshold on how good a pattern has to be to be counted:
     * every rendered match enters, and the *hit rate* is what measures how it went.
     */
    const val MIN_SCORE_TO_TRACK: Int = MatchScorePolicy.RENDER_THRESHOLD

    /**
     * Why [match] cannot be tracked, or `null` when it can.
     *
     * Returned rather than only thrown so a caller (scan pipeline, UI) can explain the refusal
     * without catching an exception.
     */
    fun rejectionReason(match: Match): String? = when {
        match.measuredMove == null ->
            "Pattern '${match.name}' has no measured move, so it has no level to track. " +
                "Candlestick formations have no height to project (spec DD-14), and an outcome " +
                "without a level cannot be scored as reached or not reached."

        match.score < MIN_SCORE_TO_TRACK ->
            "Pattern '${match.name}' scored ${match.score}, below the render threshold of " +
                "$MIN_SCORE_TO_TRACK, so the app never displayed it. Only patterns the user " +
                "actually saw may enter the track record."

        else -> null
    }

    /** True when [record] would accept [match]. See [rejectionReason] for the reason. */
    fun isTrackable(match: Match): Boolean = rejectionReason(match) == null

    /**
     * Opens a pending outcome for [match], projected on [asOf].
     *
     * The direction and the projected level both come from the measured move — the level is
     * chart geometry and must never be derived from anything else. [Match] carries no symbol,
     * so it is passed in: accuracy is keyed per stock *and* per pattern (decision D6) and a
     * record without a symbol could not be grouped.
     *
     * @throws IllegalArgumentException when [match] has no measured move (see
     * [rejectionReason]), when [symbol] is blank, when [asOf] precedes the detection date
     * (an outcome cannot precede its own pattern), or when [horizonBars] is not positive.
     */
    fun record(
        match: Match,
        symbol: String,
        asOf: LocalDate,
        horizonBars: Int = OutcomeTrackerPolicy.DEFAULT_HORIZON_BARS,
    ): PatternOutcome {
        val reason = rejectionReason(match)
        require(reason == null) { reason ?: "untrackable match" }
        require(symbol.isNotBlank()) { "an outcome must record the symbol it was projected on" }
        require(!asOf.isBefore(match.detectedOn)) {
            "a projection cannot be dated before its pattern was detected: " +
                "projectedOn=$asOf but detectedOn=${match.detectedOn}"
        }
        require(horizonBars >= 1) { "horizonBars must be at least 1 bar, was $horizonBars" }

        val measuredMove = match.measuredMove ?: error("unreachable: rejectionReason allowed this match")
        return PatternOutcome(
            patternId = match.patternId,
            symbol = symbol,
            patternName = match.name,
            timeframe = match.timeframe,
            category = match.category,
            projectedOn = asOf,
            score = match.score,
            direction = measuredMove.direction,
            projectedLevel = measuredMove.projectedLevel,
            horizonBars = horizonBars,
        )
    }

    /**
     * The bars a verdict for [outcome] may be computed from: at most [PatternOutcome.horizonBars]
     * bars, strictly **after** the projection bar, dated on or before [today], and belonging to
     * the same symbol and timeframe as the projection.
     *
     * The three date rules are the honest ones. The projection bar's own high and low are
     * excluded because that bar's range was already on the chart when the pattern was projected
     * — it could never have "triggered after being projected". Bars after [today] are excluded
     * because a verdict must not be computed from data that did not exist yet.
     */
    fun horizonWindow(
        outcome: PatternOutcome,
        candles: List<Candle>,
        today: LocalDate,
    ): List<Candle> = candles
        .asSequence()
        .filter { it.symbol == outcome.symbol && it.timeframe == outcome.timeframe }
        .filter { it.date.isAfter(outcome.projectedOn) }
        .filter { !it.date.isAfter(today) }
        .sortedBy { it.date }
        .take(outcome.horizonBars)
        .toList()

    /**
     * True when the full horizon of bars for [outcome] is available as of [today], i.e. when a
     * verdict may be settled now rather than left open.
     */
    fun isDue(outcome: PatternOutcome, candles: List<Candle>, today: LocalDate): Boolean {
        if (outcome.resolved) return true
        return horizonWindow(outcome, candles, today).size >= outcome.horizonBars
    }

    /**
     * Settles [pending] against the bars that followed its projection.
     *
     * Returns [pending] unchanged when it is already resolved: a verdict is written once and
     * never revised, in either direction, by more data. Otherwise it fills `actualHigh` /
     * `actualLow` with what was actually observed, and sets `hit` and `resolved` **only** when
     * the whole window is in hand. While the window is still open `hit` stays `null` — a
     * provisional hit would be counted by [HitRateCalculator] as a fact, which is exactly the
     * inflation rule 5 above forbids.
     *
     * Idempotent: the same outcome evaluated against the same bars yields an equal value.
     */
    fun evaluate(
        pending: PatternOutcome,
        candlesAfterProjection: List<Candle>,
        today: LocalDate = LocalDate.now(),
    ): PatternOutcome {
        require(pending.horizonBars >= 1) {
            "horizonBars must be at least 1 bar, was ${pending.horizonBars}"
        }
        if (pending.resolved) return pending

        val window = horizonWindow(pending, candlesAfterProjection, today)
        if (window.isEmpty()) return pending

        val actualHigh = window.maxOf { it.high }
        val actualLow = window.minOf { it.low }
        if (window.size < pending.horizonBars) {
            // Window still open: record what has been seen so far, publish no verdict.
            return pending.copy(actualHigh = actualHigh, actualLow = actualLow)
        }
        return pending.copy(
            actualHigh = actualHigh,
            actualLow = actualLow,
            hit = OutcomeTrackerPolicy.isHit(pending, window),
            resolved = true,
        )
    }

    /**
     * Evaluates every outcome against its own symbol's bars.
     *
     * Pure and deterministic: the result is sorted by
     * [projectedOn, symbol, patternName, patternId], so the same inputs always produce the same
     * list order, and running it twice changes nothing (resolved outcomes are returned as-is).
     *
     * An outcome whose symbol has no entry in [candlesBySymbol] is left untouched. Missing data
     * must leave a projection pending, never resolve it.
     */
    fun evaluateAll(
        outcomes: List<PatternOutcome>,
        candlesBySymbol: Map<String, List<Candle>>,
        today: LocalDate = LocalDate.now(),
    ): List<PatternOutcome> = outcomes
        .map { outcome ->
            val candles = candlesBySymbol[outcome.symbol]
            if (candles == null) outcome else evaluate(outcome, candles, today)
        }
        .sortedWith(DETERMINISTIC_ORDER)

    /**
     * Outcomes still open as of [today]: not resolved, and not projected in the future.
     *
     * A projection whose window has in fact elapsed but has not been evaluated yet still appears
     * here — telling an open window from a stale one needs the bars, which is what
     * [dueForEvaluation] takes.
     */
    fun pending(outcomes: List<PatternOutcome>, today: LocalDate): List<PatternOutcome> =
        outcomes
            .filter { !it.resolved && !it.projectedOn.isAfter(today) }
            .sortedWith(DETERMINISTIC_ORDER)

    /**
     * Unresolved outcomes whose full horizon is already available as of [today] — the work queue
     * for the nightly refresh.
     *
     * Takes the bars because "has the window elapsed" is a question about trading sessions, not
     * about calendar days; guessing a session calendar would be fabricating data.
     */
    fun dueForEvaluation(
        outcomes: List<PatternOutcome>,
        candlesBySymbol: Map<String, List<Candle>>,
        today: LocalDate,
    ): List<PatternOutcome> = outcomes
        .filter { outcome ->
            !outcome.resolved &&
                !outcome.projectedOn.isAfter(today) &&
                horizonWindow(outcome, candlesBySymbol[outcome.symbol].orEmpty(), today)
                    .size >= outcome.horizonBars
        }
        .sortedWith(DETERMINISTIC_ORDER)

    /** Grouping key for an outcome, per stock *and* per pattern (decision D6). */
    fun keyOf(outcome: PatternOutcome): AccuracyKey =
        AccuracyKey(symbol = outcome.symbol, patternName = outcome.patternName)

    private val DETERMINISTIC_ORDER: Comparator<PatternOutcome> =
        compareBy<PatternOutcome> { it.projectedOn }
            .thenBy { it.symbol }
            .thenBy { it.patternName }
            .thenBy { it.patternId }
}