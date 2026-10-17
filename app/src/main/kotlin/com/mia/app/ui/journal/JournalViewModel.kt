package com.mia.app.ui.journal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mia.app.domain.journal.ExpectationDirection
import com.mia.app.domain.journal.JournalDraft
import com.mia.app.domain.journal.JournalEntry
import com.mia.app.domain.journal.PersonalRecord
import com.mia.app.domain.model.HitRate
import com.mia.app.domain.model.MatchScorePolicy
import com.mia.app.domain.model.Timeframe
import com.mia.app.domain.ratios.FreshnessPolicy
import com.mia.app.work.MiaBackgroundDispatcher
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject

/**
 * What the Journal screen renders.
 *
 * ### Every number here is either a measurement or an explicit absence
 * There is no "null means zero" anywhere in this state, because the one figure this screen is most
 * likely to misuse is the reader's own tally, and a tally that rendered as 0 % over an empty
 * denominator would be a fabricated judgement about a person who has written nothing.
 *
 * @property record the reader's tally. Its label is built in `:domain` and carries no percentage at
 *   all below [MatchScorePolicy.MIN_HIT_RATE_SAMPLES]; this class never formats a rate.
 * @property appHitRate the app's own engine record, handed over as a [HitRate] so this screen and the
 *   Accuracy tab render it through the same `HitRate.label()` — same wording, same `n=`, same gate.
 *   The two are never combined into a third figure, and neither is described as evidence about the
 *   other.
 */
data class JournalUiState(
    val isLoading: Boolean = false,
    /** The sentence shown in `ErrorState`, or `null` when there is nothing wrong. */
    val errorReason: String? = null,
    val entries: List<JournalEntry> = emptyList(),
    val record: PersonalRecord = PersonalRecord.EMPTY,
    val appHitRate: HitRate = HitRate(symbol = null, patternName = null, hits = 0, samples = 0),
    /** Why each past-its-review-date entry is still open, keyed by entry id. */
    val openReasons: Map<Long, String> = emptyMap(),
    /** The entry currently being written, or `null` when the form is closed. */
    val draft: JournalDraft? = null,
    /** Set after a successful save or delete. Cleared by the next edit. */
    val actionMessage: String? = null,
    val today: LocalDate = FreshnessPolicy.todayInMarket(),
) {
    /** The bar below which the reader's own tally is withheld. Read from the policy. */
    val minimumSamples: Int get() = MatchScorePolicy.MIN_HIT_RATE_SAMPLES

    /** True when this device holds no entries at all. The honest empty state. */
    val hasNoEntries: Boolean get() = entries.isEmpty()

    /** True while the form is open. */
    val isComposing: Boolean get() = draft != null

    /** Why the form cannot be saved, or `null` when it can. Drives the button *and* the message. */
    val blockingReason: String? get() = draft?.blockingReason()

    /** Entries with a recorded outcome, newest first — the resolved half of the list. */
    val resolvedEntries: List<JournalEntry> get() = entries.filter { it.isResolved }

    /** Entries still open: past their review date, or not yet due. */
    val openEntries: List<JournalEntry> get() = entries.filterNot { it.isResolved }

    companion object {
        fun initial(): JournalUiState = JournalUiState()
    }
}

/**
 * The Journal screen's ViewModel.
 *
 * ### Why the draft lives here rather than in the composable
 * Two reasons, and the second is the important one. First, a rotation must not discard a paragraph
 * the reader just wrote — `rememberSaveable` would be enough for that. Second, and load-bearing:
 * [JournalDraft] is the only thing in this app that can refuse to become an entry, and it has to be
 * consulted on **every** save rather than only on the button's enabled state. Holding it in the
 * ViewModel means the object that enforces the falsification requirement is the same object the
 * save path calls, so there is no path to storage that skips it.
 *
 * ### What is *not* durable here
 * The draft is in memory; an entry is not. The acceptance criterion is that entries survive process
 * death, and they do — they are rows in `journal_entry` and nothing in this class owns them. A
 * half-written draft lost to process death is an inconvenience, and persisting it would mean a
 * serialisable copy of a person's unsent reasoning in the saved-state bundle, which is a worse trade
 * than losing the text.
 *
 * ### Error handling
 * `CancellationException` is always rethrown so `viewModelScope`'s structured concurrency survives a
 * retry mid-load; anything else surfaces as [JournalUiState.errorReason] with a retry.
 */
@HiltViewModel
class JournalViewModel @Inject constructor(
    private val dataSource: JournalDataSource,
    @MiaBackgroundDispatcher private val backgroundDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val _uiState = MutableStateFlow(JournalUiState.initial())
    val uiState: StateFlow<JournalUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    init {
        load()
    }

    /** Re-reads the journal and re-runs the resolution pass. */
    fun onRetry() {
        load()
    }

    /** Opens an empty form. An existing draft is never silently discarded — see [onDraftSymbol]. */
    fun onCompose() {
        _uiState.update {
            it.copy(draft = blankDraft(), actionMessage = null)
        }
    }

    /** Closes the form, keeping what is stored and dropping only what was typed. */
    fun onCancelCompose() {
        _uiState.update { it.copy(draft = null, actionMessage = null) }
    }

    fun onDraftSymbol(value: String) = editDraft { it.copy(symbol = value) }

    fun onDraftTimeframe(value: Timeframe) = editDraft { it.copy(timeframe = value) }

    fun onDraftThesis(value: String) = editDraft { it.copy(thesis = value) }

    fun onDraftExpectation(value: String) = editDraft { it.copy(expectation = value) }

    fun onDraftDirection(value: ExpectationDirection?) = editDraft { it.copy(direction = value) }

    fun onDraftFalsification(value: String) = editDraft { it.copy(falsification = value) }

    fun onDraftReviewDate(value: LocalDate?) = editDraft { it.copy(reviewDate = value) }

    /**
     * Writes the draft.
     *
     * The save button is disabled while [JournalUiState.blockingReason] is non-null, so the guard
     * below is a backstop rather than the primary mechanism — but it is the mechanism that matters,
     * because it is [JournalDraft.toEntry]'s own completeness check that runs, and that is the
     * check which requires a falsification.
     */
    fun onSave() {
        val draft = _uiState.value.draft ?: return
        if (!draft.isComplete) return
        _uiState.update { it.copy(isLoading = true, errorReason = null) }
        viewModelScope.launch {
            try {
                val today = FreshnessPolicy.todayInMarket()
                withContext(backgroundDispatcher) { dataSource.save(draft, today) }
                _uiState.update { it.copy(isLoading = false, draft = null, actionMessage = ENTRY_SAVED) }
                // Re-read rather than appending locally: the stored entry carries the row id the
                // database assigned, and the tally has to be recomputed over the full set anyway.
                load(silent = true)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (unavailable: JournalUnavailableException) {
                _uiState.update {
                    it.copy(isLoading = false, errorReason = unavailable.reason)
                }
            } catch (unexpected: Throwable) {
                // `toEntry` can only throw on an incomplete draft, which the button already rules
                // out — so anything arriving here is a defect, and a defect that blanks the screen
                // is worse than one that is shown.
                _uiState.update {
                    it.copy(isLoading = false, errorReason = unexpected.message ?: UNEXPECTED_FAILURE)
                }
            }
        }
    }

    /** Removes one entry. Never a bulk action — see `JournalDao.delete`. */
    fun onDelete(id: Long) {
        _uiState.update { it.copy(isLoading = true, errorReason = null) }
        viewModelScope.launch {
            try {
                withContext(backgroundDispatcher) { dataSource.delete(id) }
                _uiState.update {
                    it.copy(isLoading = false, actionMessage = ENTRY_DELETED)
                }
                load(silent = true)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (unavailable: JournalUnavailableException) {
                _uiState.update {
                    it.copy(isLoading = false, errorReason = unavailable.reason)
                }
            }
        }
    }

    private fun load(silent: Boolean = false) {
        loadJob?.cancel()
        if (!silent) {
            _uiState.update { it.copy(isLoading = true, errorReason = null) }
        }
        loadJob = viewModelScope.launch {
            try {
                val loaded = withContext(backgroundDispatcher) { dataSource.load() }
                _uiState.update { previous ->
                    previous.copy(
                        isLoading = false,
                        errorReason = null,
                        entries = loaded.entries,
                        record = loaded.record,
                        appHitRate = loaded.appHitRate,
                        openReasons = loaded.openReasons,
                        today = loaded.today,
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (unavailable: JournalUnavailableException) {
                _uiState.update {
                    it.copy(isLoading = false, errorReason = unavailable.reason)
                }
            } catch (unexpected: Throwable) {
                _uiState.update {
                    it.copy(isLoading = false, errorReason = unexpected.message ?: UNEXPECTED_FAILURE)
                }
            }
        }
    }

    private fun editDraft(transform: (JournalDraft) -> JournalDraft) {
        _uiState.update { state ->
            val current = state.draft ?: return@update state
            state.copy(draft = transform(current), actionMessage = null)
        }
    }

    /**
     * A fresh form.
     *
     * The review date is pre-set to one month out rather than left blank: the date is the one field
     * where a sensible default is obvious, and a reader who has to invent one before the form will
     * save at all is a reader with no entry. The falsification field is left **empty on purpose** —
     * it is the one field that must be typed, and pre-filling it with any example would invite
     * exactly the copy-paste entry this screen exists to prevent.
     */
    private fun blankDraft(): JournalDraft = JournalDraft(
        symbol = "",
        timeframe = Timeframe.D1,
        thesis = "",
        expectation = "",
        direction = null,
        falsification = "",
        reviewDate = FreshnessPolicy.todayInMarket().plusMonths(DEFAULT_REVIEW_MONTHS),
    )

    private companion object {
        const val UNEXPECTED_FAILURE = "the journal could not be read"
        const val DEFAULT_REVIEW_MONTHS: Long = 1L

        /** Both passed through [com.mia.app.domain.compliance.AdviceLanguage] at build time. */
        const val ENTRY_SAVED = "Entry stored as written. It cannot be edited afterwards."
        const val ENTRY_DELETED = "Entry deleted from this device. It cannot be recovered."
    }
}