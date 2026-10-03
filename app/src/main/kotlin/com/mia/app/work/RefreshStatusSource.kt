package com.mia.app.work

import kotlinx.coroutines.flow.Flow

/**
 * The Settings screen's entire view of background work (plan.md §10).
 *
 * ### Why an interface rather than a direct `WorkManager` call
 * `:app` has no `androidx.work` dependency (it is `implementation` in `:android-data`), and the
 * workers themselves live there too. This seam is the same one `TechnicalsDataSource` uses, for
 * the same reason: the ViewModel and the composables are reviewable on their own, and the adapter
 * that satisfies them is a single small class.
 *
 * ### Contract
 * * Every function is `suspend` and must already be off the main thread.
 * * [observe] is **cold but long-lived**: the Settings screen collects it for as long as it is on
 *   screen, and every emission is a fresh [RefreshStatus] for [RefreshTask]. It must not complete
 *   after one emission, or the status line would freeze at the first observation.
 * * [refreshNow] and [clearCache] return a **user-facing sentence** rather than a boolean or an
 *   exception, because Settings renders the result verbatim and a thrown exception here would have
 *   nothing to show the user.
 */
interface RefreshStatusSource {

    /**
     * The live status of one periodic job.
     *
     * Emits [RefreshRunState.NEVER_RUN] immediately for a job that has not been enqueued, so the
     * caller can render a real state from the first frame rather than a spinner.
     */
    fun observe(task: RefreshTask): Flow<RefreshStatus>

    /**
     * Enqueues a one-off run of [task], replacing nothing.
     *
     * The returned sentence is rendered as a confirmation, e.g. "Queued a one-off daily price
     * refresh. It runs when the device is on a network and the battery is not low." It must state
     * the constraint rather than promise an immediate result — plan.md §10 makes the constraints
     * part of the contract, and a promise the constraints can break is a small lie.
     */
    suspend fun refreshNow(task: RefreshTask): String

    /**
     * One sentence describing when [task] next runs.
     *
     * When nothing is queued, it says so. It must never claim a date it cannot derive.
     */
    fun describeSchedule(task: RefreshTask): String

    /** Clears cached market data and history. Never clears the user's tracked-symbol list. */
    suspend fun clearCache(): CacheClearResult
}

/**
 * What [RefreshStatusSource.clearCache] did.
 *
 * A count rather than a bare `true`, because "cache cleared" and "there was nothing cached" are
 * different facts and a settings screen that conflates them teaches the user the button works when
 * it did nothing.
 */
data class CacheClearResult(
    /** `null` when the implementation cannot count what it removed (a whole-directory delete). */
    val removedEntries: Int?,
    /** Rendered verbatim. States plainly whether anything was removed. */
    val message: String,
) {
    companion object {
        /** Nothing to remove. */
        fun nothingToClear(): CacheClearResult = CacheClearResult(
            removedEntries = 0,
            message = "There was nothing cached to remove.",
        )
    }
}