package com.mia.app.work

import com.mia.app.domain.ratios.FreshnessPolicy
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * UI-facing state of the two periodic jobs of plan.md §10.
 *
 * ### Why this model exists in `:app` and not in `:android-data`
 * plan.md §10 requires *"Store last success/failure in `work_state`"* and §1.4 requires the UI
 * to be honest about what it knows. `WorkManager`'s `WorkInfo` is a framework type, and
 * `WorkStateRecorder` lives in the storage module; neither may appear in a `:app` composable's
 * signature. This file is the small, reviewable value both are decoded into, and it is the only
 * thing the Settings screen has to understand about background work.
 *
 * ### The honesty rules this type enforces by construction
 * * [hasEverRun] is `false` until there is evidence of a run, so Settings never renders a
 *   fabricated "last refreshed at" for a task that has never fired.
 * * [headline] never says "up to date". A refresh that has not happened is *unknown*, not
 *   current — the same rule `FreshnessState.MISSING` exists for on a price.
 * * [recordedState] is carried through **verbatim** from `work_state`; this file never
 *   paraphrases a failure message, because a paraphrased failure is a claim the storage layer did
 *   not make.
 *
 * ### All timestamps are market-local
 * Rendered in [FreshnessPolicy.MARKET_ZONE] (IST), because the 19:00 IST bhavcopy lands on the
 * following calendar day in several other zones and the Settings screen must not show two
 * different dates for one run.
 */
data class RefreshStatus(
    val task: RefreshTask,
    val state: RefreshRunState,
    /** `false` only for a one-off run enqueued by "refresh now". */
    val isPeriodic: Boolean = true,
    /** How many times the job has been attempted, including the current run. */
    val runAttemptCount: Int = 0,
    /** When the current or last run started. `null` when the job has never run. */
    val lastRunAt: LocalDateTime? = null,
    /** When the next scheduled run is due. `null` when there is no future run queued. */
    val nextRunAt: LocalDateTime? = null,
    /**
     * The `work_state` row, verbatim (`WorkStateRecorder.describe`). `null` when the task has
     * never recorded anything.
     */
    val recordedState: String? = null,
    /**
     * An honest diagnostic from the data layer, e.g. "the refresh data source is not wired yet in
     * this build". Rendered verbatim; never used in place of a real status.
     */
    val note: String? = null,
) {

    /** `true` only when there is evidence of at least one run. */
    val hasEverRun: Boolean
        get() = lastRunAt != null || runAttemptCount > 0 || recordedState != null

    /** A run is in flight right now. */
    val isRunning: Boolean get() = state == RefreshRunState.RUNNING

    /** A run is in flight or queued, so "refresh now" would be a no-op. */
    val isBusy: Boolean get() = state == RefreshRunState.RUNNING || state == RefreshRunState.ENQUEUED

    /**
     * One short line, always a statement about what is *known*.
     *
     * Deliberately never "up to date" or "current": a job that has never run is not fresh, it is
     * unknown, and plan.md §10 treats an unknown as something the UI must show.
     */
    val headline: String
        get() = when (state) {
            RefreshRunState.NEVER_RUN -> "Not run on this device yet"
            RefreshRunState.ENQUEUED -> "Waiting to start"
            RefreshRunState.RUNNING -> "Running now"
            RefreshRunState.SUCCEEDED -> "Last run succeeded"
            RefreshRunState.FAILED -> "Last run did not succeed"
            RefreshRunState.BLOCKED -> "Waiting for its constraints"
            RefreshRunState.CANCELLED -> "Stopped"
            RefreshRunState.UNKNOWN -> "State not recognised by this build"
        }

    /**
     * The second line: when it ran, when it is next due, and the recorded row.
     *
     * Every part is conditional, so a run with no recorded row still reads as a sentence rather
     * than as three "—".
     */
    val detail: String
        get() = buildString {
            append("Attempts: ")
            append(runAttemptCount.toString())
            append(" · ")
            if (isPeriodic) {
                append(if (nextRunAt != null) {
                    "next run expected ${formatTimestamp(nextRunAt)}"
                } else {
                    "no further run is queued"
                })
            } else {
                append("one-off run")
            }
            recordedState?.let {
                append(" · ")
                append(it)
            }
            note?.let {
                append(" · ")
                append(it)
            }
        }

    /** One line for a screen-reader node: the headline plus the detail. */
    val spoken: String get() = "$taskLabel. $headline. $detail"

    /** The task's user-facing name. */
    val taskLabel: String get() = task.label

    companion object {
        /**
         * The state of a task with no evidence at all.
         *
         * Used as the initial value of every flow so the Settings screen never flashes a
         * "running" or "succeeded" state it has not earned.
         */
        fun neverRun(task: RefreshTask): RefreshStatus = RefreshStatus(
            task = task,
            state = RefreshRunState.NEVER_RUN,
        )

        /** `19:04`, `02 Oct 19:04` — locale-independent, market-local. */
        fun formatTimestamp(at: LocalDateTime): String = TIMESTAMP.format(at)

        private val TIMESTAMP: DateTimeFormatter =
            DateTimeFormatter.ofPattern("dd MMM HH:mm", Locale.ROOT)

        /** Millis → market-local. `null` in, `null` out: an unknown time stays unknown. */
        fun toMarketTime(millis: Long?, zone: ZoneId = FreshnessPolicy.MARKET_ZONE): LocalDateTime? =
            millis?.takeIf { it > 0L }?.let { LocalDateTime.ofInstant(Instant.ofEpochMilli(it), zone) }
    }
}

/**
 * The two jobs, with the unique-work name each one is enqueued under.
 *
 * ### Why the name is repeated here
 * `RefreshWorkScheduler.UNIQUE_EOD` lives in `:android-data`, and `:app` must not name a
 * `com.mia.app.data.*` type (see `app/build.gradle.kts`). The string is therefore repeated here
 * with a matching literal, and it is the one place in the UI layer where that duplication is
 * acceptable: a mismatch produces an *empty* observation flow, which the Settings screen renders
 * as [RefreshRunState.NEVER_RUN] rather than as a crash — a loud-enough failure for a constant.
 */
enum class RefreshTask(
    /** Must match `RefreshWorkScheduler.UNIQUE_EOD`. */
    val uniqueWorkName: String,
    /** What Settings calls it. */
    val label: String,
    /** What the job does, in one sentence, with no claim about the future. */
    val description: String,
) {
    EOD_REFRESH(
        uniqueWorkName = "mia.eod_refresh",
        label = "Daily price refresh",
        description = "Fetches the NSE end-of-day bhavcopy, parses it and replaces the stored " +
            "market cache for every tracked symbol.",
    ),

    PATTERN_SCAN(
        uniqueWorkName = "mia.pattern_scan",
        label = "Nightly pattern scan",
        description = "Re-runs the detectors over the tracked symbols, evaluates the breakout " +
            "conditions and settles any outcome whose evaluation window has closed.",
    ),

    /**
     * The NSE-archive history walk (`HistoryBackfillWorker`).
     *
     * A third task, not a flag on [EOD_REFRESH], because it answers a different question at a very
     * different cost: one whole-market file versus dozens. The nightly refresh adds **one bar a
     * day**, which can never satisfy plan.md §6.4's 60-bar gate — so without this the pattern engine
     * stays permanently below its own threshold and every pattern tab says "not enough history"
     * forever. It is also what a reader needs after a fresh install, when the database has exactly
     * one session in it.
     */
    HISTORY_BACKFILL(
        uniqueWorkName = "mia.history_backfill",
        label = "History refresh",
        description = "Walks backwards through the NSE archive to store enough daily bars for " +
            "pattern detection. Sessions already stored are skipped, so it resumes rather than " +
            "starting over.",
    ),
}

/**
 * The run states, flattened from `WorkInfo.State`.
 *
 * Declared here rather than mirrored by name so that a Settings screen never has to import a
 * framework enum, and so an unknown future `WorkInfo.State` degrades to [UNKNOWN] instead of
 * crashing a `when`.
 */
enum class RefreshRunState {
    /** No run has ever been recorded on this device. Not the same as "up to date". */
    NEVER_RUN,

    /** Queued, waiting for a slot or for its constraints. */
    ENQUEUED,

    /** In flight. */
    RUNNING,

    /** The last attempt finished successfully. */
    SUCCEEDED,

    /** The last attempt failed, or was retried and is now backed off. */
    FAILED,

    /** Waiting on `NetworkType.CONNECTED` / `requiresBatteryNotLow` (plan.md §10). */
    BLOCKED,

    /** Cancelled, or stopped before it ever ran. */
    CANCELLED,

    /** A `WorkInfo.State` this build does not know about. Rendered as unknown, never as success. */
    UNKNOWN,
}