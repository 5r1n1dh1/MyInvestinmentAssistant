package com.mia.app.ui.compare

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
 * What the Compare screen renders.
 *
 * @property symbols the three raw fields, upper-cased as they are typed so what is stored is what
 *   the reader sees.
 * @property comparison the aligned table, or [CompareComparison.EMPTY] until the reader has named
 *   two companies.
 */
data class CompareUiState(
    val symbols: List<String> = List(MAX_COMPANIES) { "" },
    val isLoading: Boolean = false,
    val errorReason: String? = null,
    val comparison: CompareComparison = CompareComparison.EMPTY,
) {
    /** The symbols a reader has actually named, normalised and de-duplicated. */
    val chosenSymbols: List<String>
        get() = symbols.map { it.trim().uppercase() }.filter { it.isNotBlank() }.distinct()

    /**
     * Why the screen cannot compare yet, or `null` when it can.
     *
     * Two is the floor: a one-column comparison is not a comparison. Three is the ceiling, and it is
     * a layout fact rather than a rule — three columns of long absence-reasons is the most that
     * fits on a 360 dp screen at a readable size.
     *
     * A repeated symbol is its own message rather than a silent de-duplication, because two boxes
     * holding `RELIANCE` and a screen quietly showing it once looks like a dropped company.
     */
    fun blockingReason(): String? {
        val named = symbols.map { it.trim().uppercase() }.filter { it.isNotBlank() }
        val chosen = named.distinct()
        return when {
            named.isEmpty() -> "Type at least two NSE symbols to compare."
            named.size > chosen.size ->
                "Two of the boxes hold the same symbol. Each company gets its own column."
            chosen.size < MIN_COMPANIES -> "Type one more symbol — a comparison needs at least two."
            else -> null
        }
    }

    /** True when the reader has named a set worth reading. */
    val canCompare: Boolean get() = blockingReason() == null

    companion object {
        fun initial(): CompareUiState = CompareUiState()
    }
}

/**
 * The Compare screen's ViewModel.
 *
 * ### Why it loads on every change rather than on a "compare" button
 * A comparison is read by *changing* it — the whole point of the screen is putting a third company
 * next to two others and re-reading. A confirm button would make each of those re-reads a
 * navigation-shaped decision, and the natural habit ("I typed it, it should have updated") would
 * teach the reader that the screen is broken. So a change to any non-blank field reloads, debounced
 * only by the fact that a blank field does not trigger a read at all.
 *
 * A company with nothing cached is not a failure here: the screen renders it as an explained column,
 * so the ViewModel never converts an absence into an error. Only an unreachable data layer does.
 */
@HiltViewModel
class CompareViewModel @Inject constructor(
    private val dataSource: CompareDataSource,
    @MiaBackgroundDispatcher private val backgroundDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CompareUiState.initial())
    val uiState: StateFlow<CompareUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    init {
        load()
    }

    /** Re-reads the same set, e.g. after a fetch that changed what this device holds. */
    fun onRetry() {
        load()
    }

    /** Called for any keystroke in any of the three fields. */
    fun onSymbolChanged(index: Int, value: String) {
        _uiState.update { state ->
            if (index !in state.symbols.indices) return@update state
            val next = state.symbols.toMutableList()
            next[index] = value
            state.copy(symbols = next)
        }
        load()
    }

    private fun load() {
        loadJob?.cancel()
        val state = _uiState.value
        val reason = state.blockingReason()
        if (reason != null) {
            // Not an error and not a load: the reader has not finished choosing, so the screen shows
            // the reason and the previous table is cleared rather than left stale beside it.
            _uiState.update {
                it.copy(
                    isLoading = false,
                    errorReason = null,
                    comparison = CompareComparison.EMPTY,
                )
            }
            return
        }
        _uiState.update { it.copy(isLoading = true, errorReason = null) }
        val symbols = state.chosenSymbols
        loadJob = viewModelScope.launch {
            try {
                val loaded = withContext(backgroundDispatcher) { dataSource.compare(symbols) }
                _uiState.update { it.copy(isLoading = false, comparison = loaded) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (unexpected: Throwable) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorReason = unexpected.message ?: UNEXPECTED_FAILURE,
                        comparison = CompareComparison.EMPTY,
                    )
                }
            }
        }
    }

    private companion object {
        const val UNEXPECTED_FAILURE = "the comparison could not be built"
    }
}

/** Two is the floor and three the ceiling; see [CompareUiState.blockingReason]. */
const val MIN_COMPANIES: Int = 2

/** Three columns of long absence-reasons do not fit on a 360 dp screen. */
const val MAX_COMPANIES: Int = 3