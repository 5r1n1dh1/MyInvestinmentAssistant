package com.mia.app.data.android.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.mia.app.data.android.alert.BreakoutAlert
import com.mia.app.data.android.alert.BreakoutAlertDispatcher
import com.mia.app.data.android.repo.AccuracyRepository
import com.mia.app.data.android.repo.DataFailureReason
import com.mia.app.data.android.repo.PatternProjection
import com.mia.app.data.android.repo.PatternRepository
import com.mia.app.data.android.repo.PatternScan
import com.mia.app.data.android.repo.SymbolRepository
import com.mia.app.data.android.repo.todayInMarket
import com.mia.app.domain.model.MatchScorePolicy
import com.mia.app.domain.model.Timeframe
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * plan.md §10, 19:05 IST, and decision OQ-4: **rescan the user's tracked symbols only**, generate
 * projections, and raise breakout alerts.
 *
 * ### Only the watchlist. Never the market.
 * plan.md §13's CGNAT mitigation is "1 fetch/day for the whole market, not per symbol". A scan is
 * not a fetch, but it is CPU: twelve detectors over 500 bars is milliseconds per symbol, and
 * 3,051 symbols a night is a battery complaint on the 2 GB hardware plan.md §13 flags. OQ-4
 * settled it: **watchlist nightly, everything else on demand.** The whole-market bhavcopy has
 * already been stored by [EodRefreshWorker], so a watchlist scan costs no network at all.
 *
 * ### One symbol failing must not abort the run
 * Each symbol is scanned inside its own `try`/`catch`, and the results are folded into a
 * [ScanSummary]. plan.md §12's silent-empty rule makes "some symbols failed" a fact the user must
 * be able to see, so the failure count and one note per failure go into `work_state`.
 *
 * ### The alert is the last step, and it is optional
 * Breakout alerts require a close beyond the measured move's **trigger** level, and only for a
 * match at or above `MatchScorePolicy.PROJECT_THRESHOLD` (plan.md §6.4). A dispatcher that
 * declines — the default no-op, or a user who denied `POST_NOTIFICATIONS` — costs the app
 * nothing but the notification, so the detections, projections and settled outcomes are still
 * stored.
 */
@HiltWorker
class PatternScanWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val symbols: SymbolRepository,
    private val patterns: PatternRepository,
    private val accuracy: AccuracyRepository,
    private val alerts: BreakoutAlertDispatcher,
    private val workState: WorkStateRecorder,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        run()
    } catch (t: Throwable) {
        workState.recordFailure(
            taskId = WorkStateRecorder.Tasks.PATTERN_SCAN,
            status = STATUS_ERROR,
            error = "${t.javaClass.simpleName}: ${t.message}",
        )
        if (t is kotlinx.coroutines.CancellationException) throw t
        Result.retry()
    }

    private suspend fun run(): Result {
        val today = todayInMarket()

        val watchlist = symbols.watchlist().valueOrNull().orEmpty()
        if (watchlist.isEmpty()) {
            // Nothing to do is a *success*, and it is recorded as one: "0 symbols tracked" must
            // not look like a failed run in the freshness strip.
            workState.recordSuccess(
                taskId = WorkStateRecorder.Tasks.PATTERN_SCAN,
                status = STATUS_EMPTY_WATCHLIST,
            )
            return Result.success(dataOf(STATUS_EMPTY_WATCHLIST to "0 symbol(s) on the watchlist"))
        }

        val outcomes = ArrayList<SymbolScanOutcome>(watchlist.size)
        var alertsDelivered = 0

        for (entry in watchlist) {
            val ticker = entry.summary.symbol
            try {
                val scanned = patterns.scan(ticker, Timeframe.D1)
                if (!scanned.isOk) {
                    val reason = scanned.failure?.reason
                    outcomes += SymbolScanOutcome(
                        symbol = ticker,
                        status = if (reason == DataFailureReason.NOT_ENOUGH_HISTORY) {
                            SymbolScanStatus.SKIPPED_NOT_ENOUGH_HISTORY
                        } else {
                            SymbolScanStatus.FAILED
                        },
                        matchCount = 0,
                        topScore = NO_SCORE,
                        note = scanned.failure?.message ?: "scan failed",
                    )
                    continue
                }

                val scan: PatternScan = scanned.orThrow()
                val top = scan.topMatch()

                // Projections and outcomes first, so a notification failure cannot cost them.
                accuracy.recordProjections(ticker, Timeframe.D1, scan.pivots.lastOrNull()?.date ?: today)
                accuracy.settlePending(today)

                val projections = patterns.projections(ticker, Timeframe.D1).valueOrNull().orEmpty()
                for (projection in projections) {
                    if (alerts.dispatch(projection)) alertsDelivered++
                }

                outcomes += SymbolScanOutcome(
                    symbol = ticker,
                    status = SymbolScanStatus.SCANNED,
                    matchCount = scan.matches.size,
                    topScore = top?.score ?: NO_SCORE,
                    note = scan.summary(),
                )
            } catch (t: Throwable) {
                // One symbol must never abort the run: every other watchlist entry still gets its
                // detections, and this one is reported as failed rather than silently skipped.
                if (t is kotlinx.coroutines.CancellationException) throw t
                outcomes += SymbolScanOutcome(
                    symbol = ticker,
                    status = SymbolScanStatus.FAILED,
                    matchCount = 0,
                    topScore = NO_SCORE,
                    note = "${t.javaClass.simpleName}: ${t.message}",
                )
            }
        }

        val summary = ScanCoordinator.summarise(outcomes, today, alertsDelivered)
        val failureNote = ScanCoordinator.failureNote(summary)
        if (failureNote == null) {
            workState.recordSuccess(
                taskId = WorkStateRecorder.Tasks.PATTERN_SCAN,
                status = STATUS_OK,
            )
        } else {
            workState.recordFailure(
                taskId = WorkStateRecorder.Tasks.PATTERN_SCAN,
                status = STATUS_PARTIAL,
                error = failureNote,
            )
        }

        // A partial run still returns `success`: every symbol that could be scanned was scanned,
        // and the ones that could not are recorded. Returning `retry()` would re-scan the
        // successful ones and duplicate work without fixing the failing ones.
        return Result.success(
            dataOf(
                STATUS_OK to summary.headline(),
                KEY_SCANNED to summary.scanned.toString(),
                KEY_FAILED to summary.failed.toString(),
                KEY_ALERTS to summary.alertsDelivered.toString(),
            ),
        )
    }

    /**
     * Sends one projection's breakout alert, if it is allowed to.
     *
     * @return `true` when a notification was actually posted.
     */
    private suspend fun BreakoutAlertDispatcher.dispatch(projection: PatternProjection): Boolean {
        val trigger = projection.trigger ?: return false
        if (!trigger.fired) return false
        // Read from the shared policy rather than restated: a projection and an alert both need
        // `score >= 70`, and the pattern screen must not disagree with the alert about that.
        if (projection.score < MatchScorePolicy.PROJECT_THRESHOLD) return false

        val alert = BreakoutAlert(
            symbol = projection.symbol,
            patternName = projection.patternName,
            timeframe = projection.timeframe,
            score = projection.score,
            direction = trigger.direction,
            level = trigger.level,
            condition = trigger.description,
            asOf = projection.detectedOn,
        )
        return alerts.notifyBreakout(alert)
    }

    private fun dataOf(vararg pairs: Pair<String, String>): androidx.work.Data =
        androidx.work.Data.Builder().apply { pairs.forEach { (k, v) -> putString(k, v) } }.build()

    companion object {
        const val STATUS_OK: String = "OK"
        const val STATUS_PARTIAL: String = "PARTIAL"
        const val STATUS_EMPTY_WATCHLIST: String = "EMPTY_WATCHLIST"
        const val STATUS_ERROR: String = "ERROR"

        const val KEY_SCANNED: String = "scanned"
        const val KEY_FAILED: String = "failed"
        const val KEY_ALERTS: String = "alerts"

        /** `-1` distinguishes "scored nothing" from "scored zero", which is suppressed anyway. */
        const val NO_SCORE: Int = -1
    }
}