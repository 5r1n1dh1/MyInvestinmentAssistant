package com.mia.app.work

import com.mia.app.domain.ratios.FreshnessPolicy
import java.time.ZoneId

/**
 * Everything `androidx.work.WorkInfo` exposes that the Settings screen needs, copied into a
 * framework-free value.
 *
 * ### Why the copy exists
 * `:app` does not depend on `androidx.work` (it is an `implementation` dependency of
 * `:android-data` and therefore not on `:app`'s compile classpath), and it must not name a
 * `com.mia.app.data.*` type. So the storage layer decodes a `WorkInfo` into this and the UI layer
 * never sees a framework type — and, more usefully, the *decoding* becomes a pure function
 * ([WorkInfoRefreshStatusMapper]) that can be asserted on without an Android SDK, which is exactly
 * the kind of logic most likely to be wrong.
 *
 * ### Field mapping from `WorkInfo` (WorkManager 2.10)
 * | `WorkInfo` | this |
 * |---|---|
 * | `state.name` | [stateName] |
 * | `runAttemptCount` | [runAttemptCount] |
 * | `lastRunTime` | [lastRunTimeMillis] |
 * | `nextScheduleTimeMillis` | [nextScheduleTimeMillis] |
 * | `periodicityInfo?.repeatIntervalMillis` | [periodMillis] |
 *
 * Every time is epoch **millis**, exactly as WorkManager reports it, so nothing is lost in the
 * copy. The conversion to a readable instant happens once, in the mapper.
 */
data class WorkInfoSnapshot(
    /** `WorkInfo.State.name`, e.g. `SUCCEEDED`. `null` when the job is not enqueued at all. */
    val stateName: String?,
    val runAttemptCount: Int = 0,
    val lastRunTimeMillis: Long? = null,
    val nextScheduleTimeMillis: Long? = null,
    /** `null` for a one-off run. */
    val periodMillis: Long? = null,
) {
    /** A one-off run: periodic, attempt 1, no next run. */
    fun isOneOff(): Boolean = periodMillis == null

    companion object {
        /**
         * The state of a unique work name that has never been enqueued.
         *
         * This is a real, common case — a first install, or a user who cancelled automatic
         * refresh — and it must not be rendered as a success.
         */
        fun notEnqueued(): WorkInfoSnapshot = WorkInfoSnapshot(stateName = null)
    }
}

/**
 * Turns a [WorkInfoSnapshot] into the [RefreshStatus] Settings renders.
 *
 * Pure: no clock read (the zone is a parameter), no framework type, no IO. That is the whole
 * point — this is the one piece of the refresh pipeline that has branching in it, and it has to
 * be checkable by reading rather than by running the app.
 */
object WorkInfoRefreshStatusMapper {

    /**
     * `WorkInfo.State.name` → [RefreshRunState].
     *
     * `null` maps to [RefreshRunState.NEVER_RUN] (the work was never enqueued) and an unrecognised
     * name maps to [RefreshRunState.UNKNOWN]. Both are honest; neither is ever
     * [RefreshRunState.SUCCEEDED], because defaulting an unknown state to success is the one
     * mapping that would let a failure read as fresh data.
     */
    fun stateOf(stateName: String?): RefreshRunState = when (stateName?.uppercase()) {
        null -> RefreshRunState.NEVER_RUN
        STATE_ENQUEUED -> RefreshRunState.ENQUEUED
        STATE_RUNNING -> RefreshRunState.RUNNING
        STATE_SUCCEEDED -> RefreshRunState.SUCCEEDED
        STATE_FAILED -> RefreshRunState.FAILED
        STATE_BLOCKED -> RefreshRunState.BLOCKED
        STATE_CANCELLED -> RefreshRunState.CANCELLED
        else -> RefreshRunState.UNKNOWN
    }

    /**
     * The full mapping.
     *
     * @param snapshot `null` means the unique work name is not enqueued at all; equivalent to
     *   [WorkInfoSnapshot.notEnqueued].
     * @param recordedState the `work_state` row, verbatim. Passed through untouched — the mapper
     *   never paraphrases another layer's failure message.
     */
    fun toStatus(
        task: RefreshTask,
        snapshot: WorkInfoSnapshot?,
        recordedState: String? = null,
        zone: ZoneId = FreshnessPolicy.MARKET_ZONE,
    ): RefreshStatus {
        val resolved = snapshot ?: WorkInfoSnapshot.notEnqueued()
        return RefreshStatus(
            task = task,
            state = stateOf(resolved.stateName),
            isPeriodic = resolved.periodMillis != null,
            runAttemptCount = resolved.runAttemptCount.coerceAtLeast(0),
            lastRunAt = RefreshStatus.toMarketTime(resolved.lastRunTimeMillis, zone),
            nextRunAt = RefreshStatus.toMarketTime(resolved.nextScheduleTimeMillis, zone),
            recordedState = recordedState,
        )
    }

    // `WorkInfo.State` names. Spelled out so this file needs no import and cannot drift into
    // depending on a framework constant that moves between WorkManager versions.
    private const val STATE_ENQUEUED = "ENQUEUED"
    private const val STATE_RUNNING = "RUNNING"
    private const val STATE_SUCCEEDED = "SUCCEEDED"
    private const val STATE_FAILED = "FAILED"
    private const val STATE_BLOCKED = "BLOCKED"
    private const val STATE_CANCELLED = "CANCELLED"
}