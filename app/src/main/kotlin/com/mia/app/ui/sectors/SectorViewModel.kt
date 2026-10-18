package com.mia.app.ui.sectors

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mia.app.data.android.repo.DataFailureReason
import com.mia.app.domain.compliance.AdviceLanguage
import com.mia.app.domain.ratios.FreshnessPolicy
import com.mia.app.domain.sectors.SectorComparison
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
import javax.inject.Inject

/**
 * What the Sector screen renders.
 *
 * @property comparison the subject beside its sector medians, or `null` until a symbol this device
 *   holds has been named. `null` rather than [SectorComparison.EMPTY], because an empty comparison
 *   built from a placeholder subject would render as a real table with no company in it.
 * @property coverageNotes storage-layer notes, rendered once above the rows. Carried on the state
 *   rather than recomputed here so the sentence that explains missing coverage has exactly one
 *   author.
 */
data class SectorUiState(
    /** Upper-cased as it is typed, so what is stored is what the reader sees. */
    val symbol: String = "",
    val isLoading: Boolean = false,
    val errorReason: String? = null,
    val comparison: SectorComparison? = null,
    val coverageNotes: List<String> = emptyList(),
) {
    /** The normalised symbol, or `null` when the field is blank. */
    val subjectSymbol: String? get() = symbol.trim().uppercase().ifBlank { null }

    /**
     * Why the screen has nothing to show, or `null` when it has something.
     *
     * A blank field is **not** an error and not a failure — the reader has not finished typing. The
     * screen shows the prompt instead of a red banner, and clears the previous comparison rather
     * than leaving it stale beneath a symbol that no longer matches it.
     */
    fun blockingReason(): String? {
        val subject = subjectSymbol ?: return "Type an NSE symbol to compare it with its sector."
        return if (AdviceLanguage.findForbiddenPhrases(subject).isEmpty()) {
            null
        } else {
            // Unreachable for a ticker in practice, and kept because the input is free text. A
            // symbol containing "guaranteed" would otherwise be rendered inside a card that
            // recommends it.
            "That is not an NSE symbol. Type the company's ticker, such as RELIANCE."
        }
    }

    val canShow: Boolean get() = blockingReason() == null

    companion object {
        fun initial(): SectorUiState = SectorUiState()
    }
}

/**
 * The Sector screen's ViewModel.
 *
 * ### Why it loads on every change, like the Compare screen
 * A comparison is read by changing it. This one is a little stricter than Compare's: a non-blank
 * field that matches no stored company is a **stated absence** with a reason, not a failure, because
 * "this device has never looked up that company" is the normal state of a symbol the reader has not
 * opened — and the screen's job on that input is to say so, not to report a failure.
 *
 * A blank field, by contrast, clears the table. Leaving a stale comparison under a symbol the
 * reader has just deleted would be the most dangerous state this screen can be in, because the
 * table would still be showing a real company's numbers.
 */
@HiltViewModel
class SectorViewModel @Inject constructor(
    private val dataSource: SectorDataSource,
    @MiaBackgroundDispatcher private val backgroundDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SectorUiState.initial())
    val uiState: StateFlow<SectorUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    /** Called for every keystroke. */
    fun onSymbolChanged(value: String) {
        _uiState.update { it.copy(symbol = value.uppercase()) }
        load()
    }

    /** Re-reads the same symbol, e.g. after the reader opened that company's Fundamentals tab. */
    fun onRetry() {
        load()
    }

    private fun load() {
        loadJob?.cancel()
        val state = _uiState.value
        val subject = state.subjectSymbol
        if (subject == null || !state.canShow) {
            _uiState.update {
                it.copy(
                    isLoading = false,
                    errorReason = null,
                    comparison = null,
                    coverageNotes = emptyList(),
                )
            }
            return
        }

        _uiState.update { it.copy(isLoading = true, errorReason = null) }
        loadJob = viewModelScope.launch {
            try {
                val result = withContext(backgroundDispatcher) {
                    dataSource.sectorView(subject, FreshnessPolicy.todayInMarket())
                }
                val failure = result.failure
                _uiState.update {
                    if (failure != null) {
                        it.copy(
                            isLoading = false,
                            errorReason = failure.message,
                            comparison = null,
                            coverageNotes = emptyList(),
                        )
                    } else {
                        it.copy(
                            isLoading = false,
                            errorReason = null,
                            comparison = result.value,
                            coverageNotes = result.provenance.notes,
                        )
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (unexpected: Throwable) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorReason = unexpected.message ?: UNEXPECTED_FAILURE,
                        comparison = null,
                        coverageNotes = emptyList(),
                    )
                }
            }
        }
    }

    private companion object {
        const val UNEXPECTED_FAILURE = "the sector comparison could not be built"
    }
}

/**
 * Whether [reason] is one this screen renders as an explanation rather than an error.
 *
 * Exposed for the screen's own styling decision, and separated from the ViewModel so the
 * distinction is a property of the *reason* and not of one caller's handling of it.
 */
fun isExplainedAbsence(reason: DataFailureReason): Boolean =
    reason == DataFailureReason.NOT_ATTEMPTED ||
        reason == DataFailureReason.NO_ROWS_FOR_SYMBOL ||
        reason == DataFailureReason.NOTHING_TO_DO
