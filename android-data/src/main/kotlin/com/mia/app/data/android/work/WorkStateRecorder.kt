package com.mia.app.data.android.work

import com.mia.app.data.android.db.WorkStateDao
import com.mia.app.data.android.db.WorkStateEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import com.mia.app.data.android.di.IoDispatcher

/**
 * plan.md §10: *"Store last success/failure in `work_state`."*
 *
 * ### Why both columns rather than one
 * The UI's freshness strip needs two independent facts: "last refreshed" and "last failed", and
 * they must not be able to contradict each other. [recordSuccess] clears the error and
 * [recordFailure] clears the success, so exactly one is ever populated by a given run.
 *
 * ### Why the timestamp is text
 * ISO-8601 `LocalDateTime` through `MiaConverters`, exactly like every other date in this
 * schema, so `work_state` sorts and reads the same way the rest of the database does.
 *
 * ### Why the recorder never throws
 * A bookkeeping failure must not fail the work it is bookkeeping. Every method swallows its own
 * exceptions; the worker's `Result` is decided by the refresh, not by whether this table was
 * updated.
 */
@Singleton
class WorkStateRecorder @Inject constructor(
    private val workStateDao: WorkStateDao,
    @IoDispatcher private val dispatcher: CoroutineDispatcher,
) {

    /** Stable ids. Also the unique-work names in `RefreshWork`, so one string means one task. */
    object Tasks {
        const val EOD_REFRESH: String = "eod_refresh"
        const val PATTERN_SCAN: String = "pattern_scan"

        /**
         * The NSE-archive history walk (`HistoryBackfillWorker`).
         *
         * Its own row because it answers a different question from the nightly refresh — "is there
         * enough history to score?" rather than "is today in yet?" — and a reader who sees one
         * succeed while the other never runs needs to be able to tell which one is stuck.
         */
        const val HISTORY_BACKFILL: String = "history_backfill"

        /**
         * The benchmark index fetch (`planv2` W3, run inside `EodRefreshWorker`).
         *
         * Its own row because it answers a different question from everything else here — "is
         * there an index to measure relative strength against?" rather than "is today's market
         * data in?" — and because a reader who sees the prices refresh nightly while relative
         * strength stays unscored needs to be able to tell which of the two is stuck. Folding it
         * into `EOD_REFRESH` would make an index problem look like a broken price layer.
         */
        const val INDEX_REFRESH: String = "index_refresh"
    }

    suspend fun recordSuccess(taskId: String, status: String) {
        put(taskId, success = now(), error = null, attempt = now(), status = status)
    }

    suspend fun recordFailure(taskId: String, status: String, error: String, attempt: String = now()) {
        put(taskId, success = null, error = error, attempt = attempt, status = status)
    }

    /** Latest known state for [taskId], or `null` when it has never run. */
    suspend fun read(taskId: String): WorkStateEntity? = try {
        withContext(dispatcher) { workStateDao.byTask(taskId) }
    } catch (e: Exception) {
        null
    }

    private suspend fun put(
        taskId: String,
        success: String?,
        error: String?,
        attempt: String,
        status: String,
    ) {
        try {
            withContext(dispatcher) {
                val existing = workStateDao.byTask(taskId)
                workStateDao.put(
                    WorkStateEntity(
                        taskId = taskId,
                        lastSuccess = success ?: existing?.lastSuccess,
                        lastError = error,
                        lastAttempt = attempt,
                        status = status,
                    ),
                )
            }
        } catch (e: Exception) {
            // Deliberate: see the class KDoc.
        }
    }

    companion object {
        /** Local time in ISO-8601, e.g. `2026-10-01T19:04:12`. */
        private val FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT)

        /** The clock read, in one place, so a test can reason about the format. */
        fun now(): String = FORMAT.format(LocalDateTime.now())

        /**
         * A device-readable rendering of a stored row, or `null` when the task has never run.
         * `:app` shows this on the settings/freshness screen.
         */
        fun describe(row: WorkStateEntity?): String? {
            if (row == null) return "This task has never run on this device."
            val status = row.status ?: "UNKNOWN"
            return buildString {
                append(status)
                row.lastAttempt?.let { append(" · last attempted ").append(it) }
                row.lastSuccess?.let { append(" · last succeeded ").append(it) }
                row.lastError?.let { append(" · last failed: ").append(it) }
            }
        }
    }
}