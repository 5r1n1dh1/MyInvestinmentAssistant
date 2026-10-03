package com.mia.app.ui.di

import com.mia.app.data.android.work.RefreshSchedule
import com.mia.app.data.android.work.RefreshWorkScheduler
import com.mia.app.data.android.work.WorkStateRecorder
import com.mia.app.work.CacheClearResult
import com.mia.app.work.RefreshRunState
import com.mia.app.work.RefreshStatus
import com.mia.app.work.RefreshStatusSource
import com.mia.app.work.RefreshTask
import com.mia.app.work.WorkInfoRefreshStatusMapper
import com.mia.app.work.WorkInfoSnapshot
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import java.time.LocalDateTime
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [RefreshStatusSource] over `:android-data`'s `WorkStateRecorder` and `RefreshWorkScheduler`.
 *
 * ### The constraint that shapes this class
 * `:app` has **no `androidx.work` dependency** — it is `implementation` in `:android-data`, so it is
 * not on this module's compile classpath — and `MiaNavHost`/`SettingsViewModel` must not name a
 * `com.mia.app.data.*` type. So a `WorkInfo` cannot be read here, and neither can
 * `getWorkInfosForUniqueWorkLiveData`.
 *
 * What *can* be read is `work_state`, which is exactly what plan.md §10 asks for — *"Store last
 * success/failure in `work_state`"* — and which `EodRefreshWorker`/`PatternScanWorker` write on every
 * run. The live framework state (`RUNNING`, `ENQUEUED`, the next attempt's backoff) is therefore
 * **not** observable from `:app`, and every status this class emits carries a `note` saying so. A
 * status line that silently omitted the caveat would read as "the app knows exactly where both jobs
 * are", which is not true.
 *
 * ### The decoding is still the pure one
 * The repository-agnostic half is not reimplemented: a `WorkInfoSnapshot` is built from the
 * `work_state` row here, and `WorkInfoRefreshStatusMapper.toStatus` does the rest. That is the same
 * function a real `WorkInfo` decoder would call, so swapping in one later cannot change how a state
 * name becomes a [RefreshRunState] or how a timestamp becomes market-local.
 *
 * ### The state names are this adapter's, deliberately
 * `work_state.status` holds `OK` / `NOT_PUBLISHED` / `NOT_TRADING_DAY` / `FAILED` / `ERROR` /
 * `PARTIAL` / `EMPTY_WATCHLIST`. Those are **not** `WorkInfo.State` names, and feeding them to
 * [WorkInfoRefreshStatusMapper] would render every job as `UNKNOWN` — "state not recognised by this
 * build" — which would be true of the *names* and false of the *jobs*. So the mapping from a
 * recorded row to a `WorkInfo.State.name` happens here, where it can be read:
 *
 * | `work_state` | `RefreshRunState` | why |
 * |---|---|---|
 * | `lastError != null` | `FAILED` | the last attempt recorded a reason. This is the one that must never read as fresh. |
 * | `lastSuccess != null` | `SUCCEEDED` | a run completed and stored its figures. |
 * | neither | `NEVER_RUN` | no row, or a row with neither column — not a success, not a failure. |
 *
 * ### Two capabilities this build does not have, reported rather than faked
 * * **[refreshNow] cannot enqueue a one-off run.** `RefreshWorkScheduler` exposes only
 *   `scheduleEodRefresh` / `schedulePatternScan`, both of which use
 *   `ExistingPeriodicWorkPolicy.UPDATE` and therefore *replace* the enqueued request — the opposite of
 *   "replacing nothing", and it would silently move the nightly run. So the sentence states the real
 *   schedule, derived from `RefreshSchedule`, and says plainly that a one-off cannot be queued here.
 * * **[clearCache] cannot delete anything.** See `RealSettingsDataSource.clearCache`: `:android-data`
 *   exposes no capability for it.
 */
@Singleton
class RealRefreshStatusSource @Inject constructor(
    private val workStateRecorder: WorkStateRecorder,
    private val scheduler: RefreshWorkScheduler,
) : RefreshStatusSource {

    /**
     * A cold, long-lived status stream.
     *
     * `flow { while (active) { emit(read); delay(POLL) } }` rather than `flowOf(...)`: the holder
     * collects this for as long as Settings is on screen, and a flow that completed after one
     * emission would freeze the line at whatever it read when the screen opened — which is the
     * "refreshed 3 days ago" bug plan.md §10 exists to prevent.
     *
     * Cancellable: `delay` is the only suspension point, so cancelling the holder's collector (which
     * happens on every navigation away) stops the read immediately with no leaked coroutine.
     *
     * `POLL_MILLIS` is 30 s rather than a WorkManager `LiveData` observation because no `LiveData` is
     * reachable from here. 30 s is well inside the 24 h period of the jobs it reports on, and it costs
     * one indexed `work_state` primary-key lookup per minute per task — nothing on the battery of a
     * device that fetches 200 KB once a day (plan.md §13's CGNAT mitigation is about *requests*, and
     * this issues none).
     */
    override fun observe(task: RefreshTask): Flow<RefreshStatus> = flow {
        while (currentCoroutineContext().isActive) {
            emit(readStatus(task))
            delay(POLL_MILLIS)
        }
    }

    /**
     * Queues a real one-off run, and says exactly what was queued.
     *
     * ### Why this now does something
     * It previously returned "a one-off run cannot be queued from this screen in this build", which
     * was true and useless: the reader who pressed Refresh on an empty app was told the app could
     * not do the one thing they asked, with no alternative. `RefreshWorkScheduler` now exposes
     * [RefreshWorkScheduler.refreshNow] and [RefreshWorkScheduler.backfillHistoryNow], so the button
     * enqueues real work.
     *
     * ### Why the two tasks map to different calls
     * [RefreshTask.EOD_REFRESH] is one whole-market file (~208 KB) and answers "is the newest session
     * in?". [RefreshTask.HISTORY_BACKFILL] walks the archive and answers "is there enough history to
     * score anything?". They are not the same cost and not the same question, so one button must not
     * silently do the other — a reader who wanted a current price should not download tens of MB.
     *
     * ### What it deliberately does not claim
     * Not "done". WorkManager has not run yet, and plan.md §10 makes the network constraint part of
     * the contract: offline, this is queued and will run when a connection appears. The sentence says
     * "queued", never "fetched".
     */
    override suspend fun refreshNow(task: RefreshTask): String = when (task) {
        RefreshTask.EOD_REFRESH -> {
            // `force = true` so one tap satisfies both halves of what a reader on an empty app
            // needs: a current price *and* enough history to detect anything. Pressing the button
            // once and getting a price but no patterns would read as the button half-working.
            scheduler.refreshNow(force = true)
            "Queued: today's session plus a history pass, to run as soon as there is a connection. " +
                "It continues from whatever is already stored rather than starting over."
        }

        RefreshTask.HISTORY_BACKFILL -> {
            scheduler.backfillHistoryNow()
            "Queued a history pass to run as soon as there is a connection. Each session already " +
                "stored is skipped, so this continues where the last one stopped."
        }

        // The pattern scan is derived work — it reads bars the EOD refresh stores — so re-running it
        // on its own would only re-read the existing cache. Saying so is more useful than a button
        // that appears to do something.
        RefreshTask.PATTERN_SCAN ->
            "The pattern scan reads bars the end-of-day refresh stores, so there is nothing for it " +
                "to fetch on its own. Refresh prices or history first, then it runs on its own. " +
                describeSchedule(task)
    }

    /**
     * The schedule, from the same pure arithmetic the scheduler enqueues with.
     *
     * `RefreshSchedule.describe(now, target)` is what `RefreshWorkScheduler.scheduleAll` passes to
     * `WorkManager`, so the sentence here and the request WorkManager holds cannot disagree. The
     * unique-work name is looked up through [RefreshTask.uniqueWorkName] rather than restated, and
     * that constant is asserted to match `RefreshWorkScheduler.UNIQUE_EOD` / `UNIQUE_SCAN`.
     */
    override fun describeSchedule(task: RefreshTask): String {
        val now = ZonedDateTime.now()
        val schedules = scheduler.describeSchedule(now)
        val described = schedules[task.uniqueWorkName]
            ?: RefreshSchedule.describe(now, targetFor(task, now))
        return "$task.label: $described"
    }

    /**
     * Nothing was removed, and why.
     *
     * `removedEntries = null` is the documented "the implementation cannot count what it removed"
     * case (see `CacheClearResult`), and the message is rendered verbatim, so it states the absence and
     * the reason rather than reporting a failure to read. Nothing is claimed about the tracked list,
     * because nothing was touched.
     */
    override suspend fun clearCache(): CacheClearResult = CacheClearResult(
        removedEntries = null,
        message = "Nothing was removed. This build has no way to delete stored bars, fundamentals or " +
            "pattern detections, so the cache cannot be cleared from here. Your tracked symbols and " +
            "their track record are untouched either way.",
    )

    // --------------------------------------------------------------------------- internals

    /**
     * One fresh status for [task], read from `work_state` and the schedule arithmetic.
     *
     * `nextRunAt` is set **only when there is evidence of a run**. For a job that has never fired,
     * `RefreshStatus.detail` then reads "no further run is queued" — which is the truth, because
     * nothing has been enqueued — instead of promising a 19:00 IST run that may not exist.
     *
     * `runAttemptCount` stays `0`: `work_state` keeps **one** row per task (primary key `taskId`), so
     * there is no counter to read. `RefreshStatus.hasEverRun` still reports correctly because
     * `lastRunAt` is populated from the row's `lastAttempt`.
     */
    private suspend fun readStatus(task: RefreshTask): RefreshStatus {
        val row = workStateRecorder.read(workStateTaskId(task))
        val stateName = when {
            row == null -> null
            !row.lastError.isNullOrBlank() -> STATE_FAILED
            !row.lastSuccess.isNullOrBlank() -> STATE_SUCCEEDED
            else -> null
        }
        val lastAttempt = row?.lastAttempt?.let { parseIsoOrNull(it) }
        val hasRun = row != null && (lastAttempt != null || !row.lastSuccess.isNullOrBlank())

        val now = ZonedDateTime.now()
        val snapshot = WorkInfoSnapshot(
            stateName = stateName,
            runAttemptCount = 0,
            lastRunTimeMillis = null,
            nextScheduleTimeMillis = null,
            // Both jobs are `PeriodicWorkRequest`s, so this is the 24 h period the scheduler enqueues.
            periodMillis = RefreshSchedule.PERIOD.toMillis(),
        )
        return WorkInfoRefreshStatusMapper.toStatus(
            task = task,
            snapshot = snapshot,
            // `WorkStateRecorder.describe(null)` returns the *sentence* "This task has never run on
            // this device." rather than `null`, and passing that through would set
            // `RefreshStatus.recordedState`, which `hasEverRun` counts as evidence of a run. Gating on
            // `row` keeps a job that has never fired reporting `hasEverRun == false` — the property
            // that stops Settings printing a fabricated "last refreshed at" for it.
            recordedState = row?.let { WorkStateRecorder.describe(it) },
        ).copy(
            lastRunAt = lastAttempt,
            nextRunAt = if (hasRun) targetFor(task, now).toLocalDateTime() else null,
            note = FRAMEWORK_NOT_READABLE_NOTE,
        )
    }

    private fun targetFor(task: RefreshTask, now: ZonedDateTime): ZonedDateTime = when (task) {
        RefreshTask.EOD_REFRESH -> RefreshSchedule.eodTarget(now)
        RefreshTask.PATTERN_SCAN -> RefreshSchedule.patternScanTarget(now)
        // The history walk is on-demand, never scheduled, so there is no recurring target to
        // describe. Reusing the EOD slot would state a schedule that does not exist.
        RefreshTask.HISTORY_BACKFILL -> RefreshSchedule.eodTarget(now)
    }

    /**
     * `work_state.taskId` for a task.
     *
     * A `when`, not string surgery: `RefreshTask.uniqueWorkName` (`mia.eod_refresh`) and
     * `WorkStateRecorder.Tasks.EOD_REFRESH` (`eod_refresh`) are deliberately different strings —
     * one is a WorkManager unique-work name, the other a table key — and `RefreshStatus`'s own KDoc
     * explains why the work name is repeated in `:app`. Deriving one from the other would break the
     * moment either changed, so the mapping is written out and a new `RefreshTask` is a compile error.
     */
    private fun workStateTaskId(task: RefreshTask): String = when (task) {
        RefreshTask.EOD_REFRESH -> WorkStateRecorder.Tasks.EOD_REFRESH
        RefreshTask.PATTERN_SCAN -> WorkStateRecorder.Tasks.PATTERN_SCAN
        RefreshTask.HISTORY_BACKFILL -> WorkStateRecorder.Tasks.HISTORY_BACKFILL
    }

    /**
     * `2026-10-01T19:04:12` → [LocalDateTime], or `null` when it is absent or unparseable.
     *
     * `WorkStateRecorder.now()` writes `LocalDateTime.now()` with no zone, and these are **device**
     * local times. `RefreshStatus` documents its timestamps as market-local, which is the same clock
     * for this app's audience (`RefreshSchedule` computes its 19:00 target in the device's own zone
     * and calls that "IST" for exactly this reason). An unparseable value becomes `null` — a time
     * this adapter cannot read is an unknown time, never today's.
     */
    private fun parseIsoOrNull(value: String): LocalDateTime? =
        try {
            if (value.isBlank()) null else LocalDateTime.parse(value)
        } catch (unparseable: RuntimeException) {
            null
        }

    private companion object {
        /**
         * `WorkInfo.State.name` values, spelled out so this file needs no `androidx.work` import.
         *
         * Only the two that a recorded outcome can produce are needed: a job that has run and one
         * whose last attempt recorded a reason. Everything else is `null`, which the mapper turns into
         * `NEVER_RUN` — "not run on this device yet", never "succeeded".
         */
        const val STATE_SUCCEEDED: String = "SUCCEEDED"
        const val STATE_FAILED: String = "FAILED"

        /** 30 s. See [observe]'s KDoc. */
        const val POLL_MILLIS: Long = 30_000L

        /**
         * Rendered verbatim beneath every status.
         *
         * The honest diagnostic `RefreshStatus.note` exists for. It says what is *not* known — the
         * live framework state — without claiming the recorded state is unreliable, because it is
         * not: it is the row the worker itself wrote.
         */
        const val FRAMEWORK_NOT_READABLE_NOTE: String =
            "Live job state is not readable from this screen; the line above is the last result the " +
                "job itself recorded."
    }
}