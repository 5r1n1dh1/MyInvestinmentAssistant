package com.mia.app.data.android.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.mia.app.data.android.repo.CandleRepository
import com.mia.app.data.android.repo.DataFailureReason
import com.mia.app.data.android.repo.DataResult
import com.mia.app.data.android.repo.HistoryBackfill
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Walks the NSE archive backwards to deepen the price cache (plan.md §3.1 retention, §6.4 gates).
 *
 * ### Why this is separate from [EodRefreshWorker]
 * They answer different questions at very different costs, and conflating them is what left the app
 * unusable on a fresh install:
 *
 * | | [EodRefreshWorker] | this worker |
 * |---|---|---|
 * | Question | "is the newest session in yet?" | "do I have enough history to score anything?" |
 * | Requests | 1 (~208 KB, whole market) | up to [CandleRepository.HISTORY_BATCH_LIMIT] |
 * | Frequency | nightly, 19:00 IST | first run, then on demand |
 *
 * A nightly refresh alone adds **one bar a day**. That can never satisfy plan.md §6.4's 60-bar
 * daily gate — three months of waiting — so without this pass the pattern engine stays permanently
 * below its own threshold and the Patterns tab says "not enough history" indefinitely.
 *
 * ### Why market-wide rather than per symbol
 * Every bhavcopy file contains every instrument, so one pass gives every symbol the same depth.
 * Fetching per symbol would spend the identical requests on one stock while 3,000 others gained
 * nothing — the opposite of plan.md §13's CGNAT mitigation.
 *
 * ### Resumable
 * Each session already present in `candles` is skipped **without a request**, so an interrupted
 * pass (background limit, process death, backoff) continues where it stopped, and tapping the button
 * again continues rather than restarts.
 *
 * ### Cost honesty
 * At ~208 KB per session, a full [CandleRepository.DEFAULT_HISTORY_SESSIONS] pass is roughly 58 MB.
 * That is a deliberate one-time cost, taken in the background and bounded per run so it cannot
 * monopolise a metered connection. Progress is written to `work_state` so Settings can show it.
 */
@HiltWorker
class HistoryBackfillWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val candles: CandleRepository,
    private val workState: WorkStateRecorder,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        run()
    } catch (t: Throwable) {
        workState.recordFailure(
            taskId = WorkStateRecorder.Tasks.HISTORY_BACKFILL,
            status = STATUS_ERROR,
            error = "${t.javaClass.simpleName}: ${t.message}",
        )
        if (t is kotlinx.coroutines.CancellationException) throw t
        Result.retry()
    }

    private suspend fun run(): Result {
        val sessions = inputData.getInt(KEY_SESSIONS, CandleRepository.DEFAULT_HISTORY_SESSIONS)
            .coerceIn(1, CandleRepository.DEFAULT_HISTORY_SESSIONS)

        val result: DataResult<HistoryBackfill> = candles.backfillHistory(sessions = sessions)

        return when {
            result.isOk -> {
                val backfill = result.orThrow()
                workState.recordSuccess(
                    taskId = WorkStateRecorder.Tasks.HISTORY_BACKFILL,
                    status = STATUS_OK,
                )
                Result.success(
                    workDataOf(
                        STATUS_OK to backfill.summary(),
                        KEY_SESSIONS_FETCHED to backfill.sessionsFetched,
                        KEY_BARS_WRITTEN to backfill.barsWritten,
                        KEY_EARLIEST to (backfill.earliestStored?.toString() ?: ""),
                        KEY_LATEST to (backfill.latestStored?.toString() ?: ""),
                    ),
                )
            }

            // `HISTORY_UNAVAILABLE` is not a failure to retry blindly: `backfillHistory` returns it
            // precisely when it fetched nothing new, which usually means the archive is already
            // covered at this depth. The walk is resumable, so a later tap continues — but this run
            // has nothing to do, and reporting that as success stops an endless backoff loop against
            // an archive that has no more days to give.
            result.failure?.reason == DataFailureReason.HISTORY_UNAVAILABLE -> Result.success(
                workDataOf(
                    STATUS_UP_TO_DATE to
                        (result.failure?.message ?: "the stored history already covers the archive"),
                ),
            )

            else -> {
                workState.recordFailure(
                    taskId = WorkStateRecorder.Tasks.HISTORY_BACKFILL,
                    status = STATUS_FAILED,
                    error = result.failure?.message ?: "unknown history failure",
                )
                Result.retry()
            }
        }
    }

    companion object {
        /** Sessions to attempt in this run. Absent ⇒ [CandleRepository.DEFAULT_HISTORY_SESSIONS]. */
        const val KEY_SESSIONS: String = "sessions"

        const val STATUS_OK: String = "history_deepened"
        const val STATUS_UP_TO_DATE: String = "history_already_deep"
        const val STATUS_FAILED: String = "history_failed"
        const val STATUS_ERROR: String = "history_error"

        const val KEY_SESSIONS_FETCHED: String = "sessionsFetched"
        const val KEY_BARS_WRITTEN: String = "barsWritten"
        const val KEY_EARLIEST: String = "earliest"
        const val KEY_LATEST: String = "latest"
    }
}