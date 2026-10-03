package com.mia.app.work

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The composable-friendly status holder the Settings screen reads (plan.md §10).
 *
 * ### Why a singleton holder rather than a ViewModel
 * The refresh status is **app-level**, not screen-level: the Technicals tab, the Patterns tab and
 * Settings all need to know whether this morning's bhavcopy arrived, and none of them should have
 * to keep its own WorkManager subscription alive. A `@Singleton` with a [StateFlow] gives every
 * screen the same answer from one subscription, and it survives navigation because it outlives
 * any single back-stack entry.
 *
 * `SettingsViewModel` folds [statuses] into its own screen state, so the screen still has exactly
 * one observable and the holder stays a dumb collector.
 *
 * ### Lifetime
 * Owns a [SupervisorJob]-backed scope on [Dispatchers.Default]. A `SupervisorJob` because one
 * task's stream failing must not cancel the other task's collector — a job that has been cancelled
 * emits nothing at all, and silently losing half the status list is worse than showing one
 * `NEVER_RUN`.
 *
 * ### Error handling
 * A stream that throws is caught and folded into a [RefreshStatus] carrying the message in
 * [RefreshStatus.note]. Throwing here would take the Settings screen down for a condition the user
 * can do nothing about; showing the message lets them see that the status is unknown.
 */
@Singleton
class RefreshStatusHolder @Inject constructor(
    private val source: RefreshStatusSource,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _statuses = MutableStateFlow<Map<RefreshTask, RefreshStatus>>(
        // Seeded, not empty: every task has a real state from the first frame, so the screen never
        // has to render "unknown because nothing arrived yet" as if it were a fact about the job.
        RefreshTask.entries.associateWith { RefreshStatus.neverRun(it) },
    )
    val statuses: StateFlow<Map<RefreshTask, RefreshStatus>> = _statuses.asStateFlow()

    private val _actionMessage = MutableStateFlow<String?>(null)

    /** The outcome of the last "refresh now" / "clear cache", rendered verbatim. */
    val actionMessage: StateFlow<String?> = _actionMessage.asStateFlow()

    /** `true` while an action is in flight, so the buttons can be disabled. */
    private val _isActing = MutableStateFlow(false)
    val isActing: StateFlow<Boolean> = _isActing.asStateFlow()

    init {
        RefreshTask.entries.forEach { task -> observe(task) }
    }

    private fun observe(task: RefreshTask) {
        scope.launch {
            source.observe(task)
                .catch { throwable ->
                    publish(
                        RefreshStatus.neverRun(task).copy(
                            note = throwable.message ?: "The refresh status could not be read.",
                        ),
                    )
                }
                .collect { status -> publish(status) }
        }
    }

    /** Enqueues a one-off run of [task]; the outcome lands in [actionMessage]. */
    fun refreshNow(task: RefreshTask) {
        act {
            source.refreshNow(task)
        }
    }

    /** Clears the cache; the outcome lands in [actionMessage]. */
    fun clearCache() {
        act {
            source.clearCache().message
        }
    }

    /** Clears [actionMessage]. Called when the user dismisses the confirmation. */
    fun dismissActionMessage() {
        _actionMessage.value = null
    }

    /** One sentence describing when [task] next runs, read on demand. */
    fun describeSchedule(task: RefreshTask): String = source.describeSchedule(task)

    private fun act(message: suspend () -> String) {
        if (_isActing.value) return
        _isActing.value = true
        scope.launch {
            _actionMessage.value = try {
                message()
            } catch (throwable: Throwable) {
                throwable.message ?: "The action could not be completed."
            } finally {
                _isActing.value = false
            }
        }
    }

    private fun publish(status: RefreshStatus) {
        _statuses.update { current -> current + (status.task to status) }
    }
}