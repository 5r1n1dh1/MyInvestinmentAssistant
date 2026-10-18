package com.mia.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mia.app.ui.share.BriefCard
import com.mia.app.work.CacheClearResult
import com.mia.app.work.MiaBackgroundDispatcher
import com.mia.app.work.RefreshStatus
import com.mia.app.work.RefreshStatusHolder
import com.mia.app.work.RefreshTask
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * What the Settings screen renders.
 *
 * One immutable value on a [StateFlow]. Everything is either a measurement or an explicit absence —
 * a coverage count that could not be read is `null`, never `0`, because "0 symbols with daily bars"
 * on a screen that could not reach the database is a false statement about the user's data.
 */
data class SettingsUiState(
    val isLoading: Boolean = false,
    /** The sentence shown inside the coverage card, or `null`. Never takes the screen down. */
    val coverageErrorReason: String? = null,
    val coverage: SettingsCoverage = SettingsCoverage.EMPTY,
    val speech: SpeechAvailability = SpeechAvailability.UNKNOWN,

    /**
     * The on-device decision counter — gap F3.
     *
     * `null` until it has been read, and the failure path sets it back to `null` with a reason,
     * because a counter that renders as zero after a failed read is the exact silent-empty failure
     * plan.md §12 calls the most dangerous shape a data layer can have. `DecisionCounterReport.EMPTY`
     * is reserved for a table that was genuinely read and was genuinely empty.
     */
    val decisionCounter: DecisionCounterReport? = null,

    /** Why the counter could not be read, or `null`. Rendered inside the counter card only. */
    val decisionCounterErrorReason: String? = null,

    /** `true` while a reset is in flight, so the button cannot be pressed twice. */
    val isResettingCounter: Boolean = false,

    /**
     * The background-job status, keyed by task.
     *
     * Kept out of [coverage] on purpose: it changes every time a worker finishes, while coverage
     * changes only when data does. Merging them would make the coverage card recompose on every
     * refresh and re-read the database for nothing.
     */
    val refreshStatuses: Map<RefreshTask, RefreshStatus> = emptyMap(),

    /** The one-off or cache action's outcome, rendered verbatim. */
    val actionMessage: String? = null,
    val isActionRunning: Boolean = false,

    /** The symbol the share section is currently pointed at, upper-cased. */
    val shareSymbol: String = "",
    /** The brief built for [shareSymbol], or `null` when nothing is stored for it. */
    val shareCard: BriefCard? = null,
    /** Why no brief could be built, when [shareCard] is `null` and a build was attempted. */
    val shareUnavailableReason: String? = null,
    val isBuildingBrief: Boolean = false,
) {
    /** `true` when the disclaimer has not been read yet — there is no such state on this screen. */
    val hasAnythingToReport: Boolean get() = coverage.hasAnythingToReport

    companion object {
        /** The state before the first load completes. */
        fun initial(): SettingsUiState = SettingsUiState()
    }
}

/**
 * Settings' ViewModel.
 *
 * ### What it owns and what it does not
 * It owns the coverage read, the share-card build, the app version and the action messages. It does
 * **not** own the WorkManager observation: that lives in [RefreshStatusHolder], a `@Singleton`,
 * because the refresh status is app-level state that outlives this back-stack entry and is also read
 * by the Technicals and Patterns headers.
 *
 * ### Why the refresh statuses are combined rather than polled
 * `RefreshStatusHolder.statuses` is a `StateFlow`, so `combine` produces one emission per change with
 * no timer, no `WorkManager.getWorkInfoByIdLiveData` polling and no battery cost. The screen shows
 * "last run succeeded" the moment the worker finishes and nothing at all while nothing changes.
 *
 * ### Cost
 * The coverage read touches several tables, so it runs on [MiaBackgroundDispatcher]. The share-card
 * build touches one symbol's bars and one scan's detections, so it gets its own small hop and does
 * not block the screen.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val dataSource: SettingsDataSource,
    private val refreshStatusHolder: RefreshStatusHolder,
    @MiaBackgroundDispatcher private val backgroundDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState.initial())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    init {
        observeRefreshStatus()
        load()
    }

    /** Re-reads coverage and the speech availability. */
    fun onRetry() {
        load()
    }

    /** Points the share section at a symbol. Trimming and upper-casing happens here, once. */
    fun onShareSymbolChanged(symbol: String) {
        val cleaned = symbol.trim().uppercase()
        _uiState.update { it.copy(shareSymbol = cleaned, shareCard = null, shareUnavailableReason = null) }
    }

    /**
     * Builds the brief for the current [SettingsUiState.shareSymbol].
     *
     * Silently does nothing for a blank symbol rather than reporting an error, because a user typing
     * the first character of a ticker has not made a mistake yet.
     */
    fun onShareBriefRequested() {
        val symbol = _uiState.value.shareSymbol
        if (symbol.isBlank()) return
        _uiState.update { it.copy(isBuildingBrief = true, shareCard = null, shareUnavailableReason = null) }
        viewModelScope.launch {
            try {
                val card = withContext(backgroundDispatcher) { dataSource.briefFor(symbol) }
                _uiState.update {
                    it.copy(
                        isBuildingBrief = false,
                        shareCard = card,
                        shareUnavailableReason = if (card == null) {
                            "Nothing is stored for $symbol yet. Fetch it from the search screen first."
                        } else {
                            null
                        },
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                _uiState.update {
                    it.copy(
                        isBuildingBrief = false,
                        shareCard = null,
                        shareUnavailableReason = failure.message ?: UNEXPECTED_FAILURE,
                    )
                }
            }
        }
    }

    /**
     * Enqueues a one-off background run.
     *
     * Delegates to [RefreshStatusHolder] rather than touching WorkManager, because `:app` has no
     * `androidx.work` dependency. The outcome arrives on `actionMessage` through the same flow.
     */
    fun onRefreshNow(task: RefreshTask) {
        refreshStatusHolder.refreshNow(task)
    }

    /** Clears the cache. Delegates for the same reason. */
    fun onClearCache() {
        refreshStatusHolder.clearCache()
        // Coverage changes when the cache does, so the numbers on screen are now stale.
        load()
    }

    /**
     * Clears the decision counter (gap F3).
     *
     * Re-reads rather than zeroing the field locally, because the honest value after a reset is
     * whatever the table now holds — which on a device that scanned between the tap and the write
     * is not zero, and asserting zero would be a claim this screen cannot support.
     *
     * The outcome lands on [SettingsUiState.actionMessage] rather than inside the counter card, so
     * that a confirmation is visible even if the counter's own re-read then fails: telling a reader
     * their reset did nothing because the *next* read broke would be its own small lie.
     */
    fun onResetDecisionCounter() {
        if (_uiState.value.isResettingCounter) return
        _uiState.update { it.copy(isResettingCounter = true) }
        viewModelScope.launch {
            try {
                val result = withContext(backgroundDispatcher) { dataSource.resetDecisionCounter() }
                _uiState.update { it.copy(isResettingCounter = false, actionMessage = result.message) }
                load()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                _uiState.update {
                    it.copy(
                        isResettingCounter = false,
                        actionMessage = "The counter could not be cleared: " +
                            (failure.message ?: UNEXPECTED_FAILURE),
                    )
                }
            }
        }
    }

    /** Clears the one-off action message. Called when the user dismisses it. */
    fun onDismissActionMessage() {
        refreshStatusHolder.dismissActionMessage()
        _uiState.update { it.copy(actionMessage = null) }
    }

    private fun observeRefreshStatus() {
        viewModelScope.launch {
            combine(
                refreshStatusHolder.statuses,
                refreshStatusHolder.actionMessage,
                refreshStatusHolder.isActing,
            ) { statuses, message, isActing -> Triple(statuses, message, isActing) }
                .collect { (statuses, message, isActing) ->
                    _uiState.update {
                        it.copy(
                            refreshStatuses = statuses,
                            actionMessage = message ?: it.actionMessage,
                            isActionRunning = isActing,
                        )
                    }
                }
        }
    }

    private fun load() {
        loadJob?.cancel()
        _uiState.update { it.copy(isLoading = true) }
        loadJob = viewModelScope.launch {
            try {
                // The three reads are independent, so a failure in one must not lose the others: the
                // disclaimer has to render regardless, which is why coverage's error goes into the
                // state rather than into `errorReason`.
                val coverage = runCatching {
                    withContext(backgroundDispatcher) { dataSource.coverage() }
                }
                val speech = runCatching {
                    withContext(backgroundDispatcher) { dataSource.speechAvailability() }
                }
                // The counter is read on its own so that a database hiccup on the *market* tables
                // cannot blank the one figure that says whether the projection feature is alive —
                // they are different tables and only one of them carries the answer to A1.
                val counter = runCatching {
                    withContext(backgroundDispatcher) { dataSource.decisionCounter() }
                }
                _uiState.update { current ->
                    current.copy(
                        isLoading = false,
                        coverage = coverage.getOrNull() ?: current.coverage,
                        coverageErrorReason = coverage.exceptionOrNull()?.let {
                            it.message ?: UNEXPECTED_FAILURE
                        },
                        speech = speech.getOrNull() ?: SpeechAvailability.UNKNOWN,
                        decisionCounter = counter.getOrNull() ?: current.decisionCounter,
                        decisionCounterErrorReason = counter.exceptionOrNull()?.let {
                            it.message ?: UNEXPECTED_FAILURE
                        },
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            }
        }
    }

    private companion object {
        /** Shown only when an exception carried no message of its own. */
        const val UNEXPECTED_FAILURE = "the data layer failed without a reason"
    }
}

/**
 * The result of clearing the cache, as a one-line sentence.
 *
 * A thin wrapper so the settings screen never renders [CacheClearResult] fields directly: the count
 * and the sentence are two views of the same fact, and only the sentence is user-facing.
 */
fun CacheClearResult.toDisplayLine(): String = message