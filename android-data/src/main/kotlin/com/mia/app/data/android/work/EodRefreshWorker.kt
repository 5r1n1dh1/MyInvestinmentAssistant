package com.mia.app.data.android.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.mia.app.data.android.repo.CandleRepository
import com.mia.app.data.android.repo.DataFailureReason
import com.mia.app.data.android.repo.DataResult
import com.mia.app.data.android.repo.MarketRefresh
import com.mia.app.data.android.repo.todayInMarket
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * plan.md §10, 19:00 IST: **fetch bhavcopy → parse → replace the market cache**, attaching the
 * delivery percentage from the legacy file when it is available.
 *
 * ### The four outcomes, and why each one returns what it returns
 *
 * | Outcome | `Result` | Why |
 * |---|---|---|
 * | Not a trading day (weekend / known holiday) | `success` | plan.md §10 depends on the bhavcopy existing, and it never will on a Saturday. Retrying would burn four requests a night to be told `404` and then hit the exponential backoff floor. The reason is recorded in `work_state`, so "nothing happened" is an explained nothing. |
 * | `NOT_PUBLISHED` (before ~18:00 IST, or a holiday) | `retry` | This is plan.md §3.1's expected pre-publication state, and it resolves on its own. `WorkManager`'s exponential backoff (minimum 30 min, plan.md §10) is the right answer: try again in half an hour. |
 * | Source unreachable / unparseable | `retry` | plan.md §13's "total price-layer failure" risk. Backing off exponentially beats a tight loop, and the stale cache keeps the app usable meanwhile. |
 * | Stored | `success` | The work is done. The next run re-reads the same file, and the write-once cache turns that into a no-op rather than a revision. |
 *
 * ### A `Result` is always returned
 * `doWork` ends in a `try` whose `catch` also returns a `Result`. There is no path out of this
 * function that does not.
 *
 * ### Cancellation
 * The whole body is one suspend call, so cancelling the work cancels the coroutine, which
 * cancels the OkHttp call. Nothing is left half-written: Room's `@Insert` runs in a transaction.
 */
@HiltWorker
class EodRefreshWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val candles: CandleRepository,
    private val workState: WorkStateRecorder,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        run()
    } catch (t: Throwable) {
        // A `CancellationException` here means the work was cancelled, which is not a failure to
        // record — but rethrowing keeps the cancellation semantics intact for WorkManager.
        workState.recordFailure(
            taskId = WorkStateRecorder.Tasks.EOD_REFRESH,
            status = STATUS_ERROR,
            error = "${t.javaClass.simpleName}: ${t.message}",
        )
        if (t is kotlinx.coroutines.CancellationException) throw t
        Result.retry()
    }

    private suspend fun run(): Result {
        val today = todayInMarket()

        if (!TradingCalendar.isPossiblyTradingDay(today)) {
            val reason = TradingCalendar.skipReason(today)
            workState.recordSuccess(
                taskId = WorkStateRecorder.Tasks.EOD_REFRESH,
                status = STATUS_NOT_TRADING_DAY,
            )
            return Result.success(workDataOf(STATUS_NOT_TRADING_DAY to reason))
        }

        val result: DataResult<MarketRefresh> = candles.refreshMarket(today)
        return when {
            result.isOk -> {
                val refresh = result.orThrow()

                // "Fetch history" mode: the newest session alone is one bar, and plan.md §6.4 needs
                // 60, so a reader who asked for history and got only today's bar has been ignored.
                // Deepening here keeps one tap and one `work_state` row describing the whole outcome.
                val historySummary = if (inputData.getBoolean(KEY_FORCE_BACKFILL, false)) {
                    candles.backfillHistory(
                        sessions = CandleRepository.DEFAULT_HISTORY_SESSIONS,
                        today = today,
                    ).let { backfill ->
                        when {
                            backfill.isOk -> " · history: ${backfill.orThrow().summary()}"
                            else -> " · history could not be deepened: " +
                                (backfill.failure?.message ?: "unknown reason")
                        }
                    }
                } else {
                    ""
                }

                workState.recordSuccess(
                    taskId = WorkStateRecorder.Tasks.EOD_REFRESH,
                    status = STATUS_OK,
                )
                Result.success(
                    workDataOf(
                        STATUS_OK to refresh.summary(today) + historySummary,
                        KEY_AS_OF to refresh.asOf.toString(),
                        KEY_BARS_WRITTEN to refresh.barsWritten.toString(),
                        KEY_SYMBOLS to refresh.symbolsInFile.toString(),
                    ),
                )
            }

            result.failure?.reason == DataFailureReason.NOT_TRADING_DAY -> {
                workState.recordSuccess(
                    taskId = WorkStateRecorder.Tasks.EOD_REFRESH,
                    status = STATUS_NOT_TRADING_DAY,
                )
                Result.success(workDataOf(STATUS_NOT_TRADING_DAY to (result.failure?.message ?: "")))
            }

            result.failure?.reason == DataFailureReason.NOT_PUBLISHED -> {
                workState.recordFailure(
                    taskId = WorkStateRecorder.Tasks.EOD_REFRESH,
                    status = STATUS_NOT_PUBLISHED,
                    error = result.failure?.message ?: "bhavcopy not published yet",
                )
                // plan.md §3.1: today's file answers 404 until roughly 18:00 IST. Retry.
                Result.retry()
            }

            else -> {
                workState.recordFailure(
                    taskId = WorkStateRecorder.Tasks.EOD_REFRESH,
                    status = STATUS_FAILED,
                    error = result.failure?.message ?: "unknown refresh failure",
                )
                Result.retry()
            }
        }
    }

    /** The one-line summary the freshness screen and the logs share. */
    private fun MarketRefresh.summary(requestedOn: java.time.LocalDate): String = buildString {
        append("Refreshed $symbolsInFile symbol(s) as of $asOf")
        append("; $barsWritten bar(s) written, $barsAlreadyKnown already cached")
        if (deliveryAttached > 0) append("; $deliveryAttached delivery percentage(s) attached")
        if (identityEvents > 0) append("; $identityEvents identity event(s)")
        if (asOf != requestedOn) append("; file dated $asOf, requested for $requestedOn")
        notes.take(MAX_NOTES).forEach { append("; ").append(it) }
    }

    private fun workDataOf(vararg pairs: Pair<String, String>): androidx.work.Data =
        androidx.work.Data.Builder().apply { pairs.forEach { (k, v) -> putString(k, v) } }.build()

    companion object {
        /** Machine statuses written to `work_state.status` and to the output `Data`. */
        const val STATUS_OK: String = "OK"
        const val STATUS_NOT_PUBLISHED: String = "NOT_PUBLISHED"
        const val STATUS_NOT_TRADING_DAY: String = "NOT_TRADING_DAY"
        const val STATUS_FAILED: String = "FAILED"
        const val STATUS_ERROR: String = "ERROR"

        const val KEY_AS_OF: String = "asOf"
        const val KEY_BARS_WRITTEN: String = "barsWritten"
        const val KEY_SYMBOLS: String = "symbols"

        /**
         * Input flag: after storing the newest session, also deepen history in this same run.
         *
         * Set by `RefreshWorkScheduler.refreshNow(force = true)`, which is what Settings' "fetch
         * history" action uses. Folding it into the existing worker rather than chaining a second
         * one means the reader's single tap produces one `work_state` row describing the whole
         * outcome, instead of two runs that must be correlated afterwards.
         */
        const val KEY_FORCE_BACKFILL: String = "forceBackfill"

        /** Bounds the summary so `Data` stays under WorkManager's 10 KB payload limit. */
        const val MAX_NOTES: Int = 3
    }
}