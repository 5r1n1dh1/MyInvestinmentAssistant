package com.mia.app.data.android.repo

import com.mia.app.domain.accuracy.TrackRecordContext
import com.mia.app.domain.model.AccuracyReport
import com.mia.app.domain.model.Match
import com.mia.app.domain.model.PatternOutcome
import com.mia.app.domain.model.Timeframe
import java.time.LocalDate

/**
 * What one nightly accuracy pass did.
 *
 * Reported into `work_state` so the accuracy screen can say how fresh the record is rather than
 * implying it is maintained continuously. It is not: outcomes settle in **trading sessions**
 * (`OutcomeTracker.horizonWindow` counts bars, not days), so the only correct statement about
 * freshness is how many outcomes were settled by the last run.
 */
data class AccuracyPass(
    /** Projections opened by this pass. */
    val recorded: Int,
    /** Projections whose evaluation window is still open. */
    val stillPending: Int,
    /** Verdicts written by this pass. Always non-negative and never revised later. */
    val settled: Int,
    val asOf: LocalDate,
    val notes: List<String> = emptyList(),
) {
    fun summary(): String =
        "$recorded projection(s) recorded, $settled settled, $stillPending still pending, as of $asOf"
}

/**
 * The only way `:app` reaches the app's own track record (plan.md §6.1, decision D6).
 *
 * ### The rules this type exists to protect
 * 1. Hit rates are reported **per stock AND per pattern** (decision D6).
 * 2. Below `MatchScorePolicy.MIN_HIT_RATE_SAMPLES` (30) the number is **withheld** and only the
 *    count is shown. `HitRate.isReportable` enforces this inside the domain; nothing here
 *    recomputes it.
 * 3. A **resolved** outcome is immutable. [settlePending] re-reads and writes, and never moves
 *    `hit` from true to false — `OutcomeTracker.evaluate` returns a resolved outcome unchanged,
 *    so the guarantee is structural rather than a promise in this KDoc.
 */
interface AccuracyRepository {

    /**
     * The full report, grouped three ways (per stock+pattern, per pattern, per stock).
     *
     * Returns a typed failure when the device holds no outcomes at all, so an empty accuracy
     * screen says "nothing has been tracked yet" instead of rendering an empty table that reads
     * as "0 % hit rate".
     */
    suspend fun report(today: LocalDate = todayInMarket()): DataResult<AccuracyReport>

    /**
     * The track record shown next to one pattern: this stock's past cases with that formation,
     * plus the cross-stock figure when this one is too thin (plan.md §6.1).
     */
    suspend fun trackRecord(
        symbol: String,
        patternName: String,
        today: LocalDate = todayInMarket(),
    ): DataResult<TrackRecordContext>

    /**
     * Opens pending outcomes for every stored, trackable detection on `symbol` / `timeframe`.
     *
     * Only matches that pass `OutcomeTracker.rejectionReason` are recorded: a candlestick has no
     * measured move and therefore no level whose reach could be observed, and a match the app
     * never displayed cannot enter a record of what the app displayed.
     */
    suspend fun recordProjections(
        symbol: String,
        timeframe: Timeframe,
        asOf: LocalDate = todayInMarket(),
    ): DataResult<AccuracyPass>

    /**
     * Settles every open outcome whose full evaluation window is in hand.
     *
     * "In hand" is measured in trading sessions, not calendar days, because guessing a session
     * calendar would be fabricating data (see `OutcomeTracker.dueForEvaluation`).
     */
    suspend fun settlePending(today: LocalDate = todayInMarket()): DataResult<AccuracyPass>

    /** Outcomes still open as of [today], for the accuracy screen's "not yet settled" count. */
    suspend fun pending(today: LocalDate = todayInMarket()): DataResult<List<PatternOutcome>>

    /** The stored outcome for one detection, or a typed failure when it has never been opened. */
    suspend fun outcomeFor(symbol: String, patternId: Long): DataResult<PatternOutcome>

    /**
     * Whether a match may enter the track record, with the reason when it may not.
     *
     * Exposed so the pattern screen can say *"this one is not tracked — a candlestick formation
     * has no level to track"* instead of silently omitting it.
     */
    fun rejectionReason(match: Match): String? = com.mia.app.domain.accuracy.OutcomeTracker.rejectionReason(match)
}