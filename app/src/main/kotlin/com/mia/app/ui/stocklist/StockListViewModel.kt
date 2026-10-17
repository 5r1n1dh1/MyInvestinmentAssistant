package com.mia.app.ui.stocklist

import com.mia.app.domain.technical.Breadth
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * What the Stock List screen renders.
 *
 * ### The empty list is a first-run state, not a failure
 * [rows] being empty is the correct state of a brand-new install and is rendered as an intentional
 * empty state that points at Search — never as an error, and never as "no data available". The
 * screen distinguishes the two by asking [isEmpty] rather than inferring from `errorReason`.
 *
 * ### Why a mutation replaces the list instead of invalidating it
 * [StockListDataSource.add] and `remove` both return the resulting list. Re-reading would be a
 * second round trip for a change the data source has already made, and on a low-RAM device a second
 * read is also a second chance to fail — which would leave the reader's own action looking like an
 * error.
 */
data class StockListUiState(
    /** The tracked rows, sorted by ticker by the data source. */
    val rows: List<WatchlistRow> = emptyList(),
    val isLoading: Boolean = true,
    /** The sentence to show in `ErrorState`, or `null`. */
    val errorReason: String? = null,
    /**
     * One line per red-flag check that could not be evaluated (plan.md §12). Rendered **above** the
     * list, never folded into a row, because a coverage gap is a property of the check, not of one
     * symbol.
     */
    val coverageNotes: List<String> = emptyList(),
    /** A one-line confirmation of the last add/remove, so a tap is visibly acknowledged. */
    val notice: String? = null,

    /**
     * Whole-market breadth for the newest stored session, or null when none is stored.
     *
     * Null is an ordinary state — a fresh install, or a device whose refresh has never landed —
     * and the card renders it as *not measured yet*. Zeros here would render as a market that
     * closed flat everywhere, which is the most misleading thing this card could show.
     */
    val breadth: Breadth? = null,
    /** Ticker typed into the inline add field. Held here so the field survives recomposition. */
    val addField: String = "",
    /** `true` while an add/remove is in flight, so the row's button can be disabled. */
    val isMutating: Boolean = false,
) {
    /**
     * True for a genuinely empty watchlist.
     *
     * Distinct from "nothing loaded yet" ([isLoading]) and from "the read failed"
     * ([errorReason]): each of the three is a different screen, and collapsing them is how a user
     * ends up looking at an empty list and concluding the app lost their data.
     */
    val isEmpty: Boolean get() = !isLoading && errorReason == null && rows.isEmpty()

    /** True when at least one row is showing, whatever else is happening. */
    val hasRows: Boolean get() = rows.isNotEmpty()
}

/**
 * The Stock List screen's ViewModel.
 *
 * ### Offline-first by construction
 * [StockListDataSource.tracked] is documented to read the cache without touching the network, so
 * [load] produces a renderable list immediately on a cold, offline launch. There is no
 * "loading from network" phase to wait through, which is plan.md §10's requirement that staleness be
 * visible rather than a spinner in its place.
 *
 * ### Why a mutation failure is a notice and not [StockListUiState.errorReason]
 * A failed *remove* leaves the user's list intact; a failed *add* leaves it un-changed. Neither
 * invalidates the rows already on screen, so turning either into a full-screen `ErrorState` would
 * replace a working watchlist with an error card because one tap did not land.
 */
@HiltViewModel
class StockListViewModel @Inject constructor(
    private val dataSource: StockListDataSource,
    private val marketPulse: MarketPulseDataSource,
    @StockListBackgroundDispatcher private val backgroundDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val _uiState = MutableStateFlow(StockListUiState())
    val uiState: StateFlow<StockListUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    /** Re-reads the tracked list. Called on retry. */
    fun onRetry() {
        load()
    }

    /** The inline add field changed. */
    fun onAddFieldChanged(value: String) {
        _uiState.update { it.copy(addField = value, notice = null) }
    }

    /**
     * Adds the ticker in the add field.
     *
     * Idempotent in the data source and idempotent here: an empty or already-tracked ticker is
     * reported and nothing is written, so a double tap cannot produce two rows.
     */
    fun onAddConfirmed() {
        val ticker = _uiState.value.addField.trim().uppercase()
        if (ticker.isEmpty()) return
        if (_uiState.value.rows.any { it.symbol == ticker }) {
            _uiState.update { it.copy(notice = "$ticker is already tracked") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isMutating = true, notice = null) }
            val stored = try {
                withContext(backgroundDispatcher) { dataSource.add(ticker) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                _uiState.update {
                    it.copy(isMutating = false, notice = failure.message ?: UNEXPECTED_FAILURE)
                }
                return@launch
            }
            _uiState.update { current ->
                if (stored == null) {
                    current.copy(
                        isMutating = false,
                        notice = "$ticker is not in the bundled NSE symbol master",
                    )
                } else {
                    current.copy(
                        isMutating = false,
                        addField = "",
                        rows = (current.rows + stored).sortedBy { row -> row.symbol },
                        notice = "${stored.symbol} added to your tracked symbols",
                    )
                }
            }
        }
    }

    /**
     * Removes [symbol].
     *
     * The row disappears only after the data source confirms, because a remove that silently fails
     * leaves a symbol the reader believes they deleted still generating nightly work.
     */
    fun onRemove(symbol: String) {
        val ticker = symbol.trim().uppercase()
        if (ticker.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isMutating = true, notice = null) }
            val remaining = try {
                withContext(backgroundDispatcher) { dataSource.remove(ticker) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                _uiState.update {
                    it.copy(isMutating = false, notice = failure.message ?: UNEXPECTED_FAILURE)
                }
                return@launch
            }
            _uiState.update { current ->
                current.copy(
                    isMutating = false,
                    rows = remaining.sortedBy { row -> row.symbol },
                    notice = "$ticker removed from your tracked symbols",
                )
            }
        }
    }

    /** Dismisses the confirmation line. */
    fun onNoticeDismissed() {
        _uiState.update { it.copy(notice = null) }
    }

    // ------------------------------------------------------------------------------- internals

    private fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorReason = null) }
            try {
                // Breadth is read alongside the rows rather than in its own pass, so the card and
                // the list appear in the same frame — a breadth card that popped in a moment after
                // the rows would read as a late correction rather than as part of the screen.
                //
                // A breadth failure must not fail the list: breadth is context, the rows are the
                // product. So it is caught separately and left null, which the card already knows
                // how to render as "not measured yet".
                val breadth = withContext(backgroundDispatcher) {
                    try {
                        marketPulse.breadth()
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: MarketPulseUnavailableException) {
                        null
                    }
                }
                val loaded = withContext(backgroundDispatcher) {
                    StockListLoad(
                        rows = dataSource.tracked(),
                        coverageNotes = dataSource.flagCoverageNotes(),
                    )
                }
                _uiState.update { current ->
                    current.copy(
                        rows = loaded.rows,
                        coverageNotes = loaded.coverageNotes,
                        isLoading = false,
                        errorReason = null,
                        notice = loaded.note,
                        breadth = breadth,
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: StockListUnavailableException) {
                // A failed reload must not wipe the rows that are already on screen: the cache the
                // screen is rendering is still the truth it rendered last time.
                _uiState.update { current ->
                    current.copy(isLoading = false, errorReason = failure.reason)
                }
            } catch (failure: Throwable) {
                _uiState.update { current ->
                    current.copy(
                        isLoading = false,
                        errorReason = failure.message ?: UNEXPECTED_FAILURE,
                    )
                }
            }
        }
    }

    private companion object {
        const val UNEXPECTED_FAILURE = "the data layer failed without a reason"
    }
}