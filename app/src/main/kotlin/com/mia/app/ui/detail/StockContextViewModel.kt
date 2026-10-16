package com.mia.app.ui.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mia.app.ui.nav.ARG_SYMBOL
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
 * What the header's market-context row renders, and nothing else.
 *
 * ### Why this is a separate ViewModel from [StockDetailViewModel]
 * The header's own state is the four figures [StockDetailDataSource] returns, and that ViewModel
 * reads its symbol from the route **once** in its constructor. The context row has a different
 * lifetime: the cohort lists are process-cached and the liquidity measurement is a pure function
 * over stored bars, so reloading it on a timeframe change would be pure waste — and folding it
 * into [StockDetailViewModel] would put three facts of a different kind on one screen's critical
 * path.
 *
 * It is also the difference between one failure and two. A missing constituent asset must not stop
 * the price header from drawing, which it could not if both lived in one load.
 */
data class StockContextUiState(
    val symbol: String,
    val context: StockContext? = null,
    val isLoading: Boolean = true,
) {
    /** True when there is nothing worth drawing, so the row renders nothing at all. */
    val hasContent: Boolean get() = context?.isEmpty == false
}

/**
 * Loads [StockContext] for the route's symbol.
 *
 * ### Why this never surfaces an error
 * Both facts here are *absences*, not failures, and plan.md §12's rule is that an absence must be
 * explained rather than announced as a breakage:
 * * no index membership → a sentence saying the stock is in none of NSE's three published lists;
 * * no stored bars → no liquidity line, and no claim that the stock is liquid or illiquid.
 *
 * A thrown exception here would put an error card in the middle of a working price header, which
 * would be both alarming and untrue. [RealStockContextSource.context] is documented not to throw;
 * the `catch` below is belt and braces, and it leaves the row empty rather than showing a reason
 * the reader cannot act on.
 */
@HiltViewModel
class StockContextViewModel @Inject constructor(
    private val source: RealStockContextSource,
    savedStateHandle: SavedStateHandle,
    @DetailBackgroundDispatcher private val backgroundDispatcher: CoroutineDispatcher,
) : ViewModel() {

    /** The ticker, from the route — the same source `StockDetailViewModel` reads. */
    val symbol: String = savedStateHandle.get<String>(ARG_SYMBOL)
        ?.trim()
        ?.uppercase()
        .orEmpty()

    private val _uiState = MutableStateFlow(StockContextUiState(symbol = symbol))
    val uiState: StateFlow<StockContextUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    init {
        load()
    }

    /**
     * Re-reads the context.
     *
     * Exposed for the header's retry, and it is idempotent: nothing in the context is written back,
     * so re-reading produces the same answer unless a refresh has since stored more bars.
     */
    fun onRetry() {
        load()
    }

    private fun load() {
        loadJob?.cancel()
        if (symbol.isEmpty()) {
            _uiState.update { it.copy(isLoading = false, context = null) }
            return
        }
        _uiState.update { it.copy(isLoading = true) }
        loadJob = viewModelScope.launch {
            val loaded = try {
                withContext(backgroundDispatcher) { source.context(symbol) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (ignored: Throwable) {
                null
            }
            _uiState.update { current ->
                current.copy(context = loaded, isLoading = false)
            }
        }
    }
}