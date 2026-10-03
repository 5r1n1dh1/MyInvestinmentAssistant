package com.mia.app.ui.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mia.app.domain.model.Timeframe
import com.mia.app.domain.ratios.Freshness
import com.mia.app.ui.nav.ARG_SYMBOL
import com.mia.app.ui.nav.ARG_TIMEFRAME
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
 * What the Stock Detail tab host renders outside the tabs.
 *
 * ### State that survives process death, and how
 * Everything on this screen comes from a **nav argument**, so the `SavedStateHandle` is the storage.
 * There is no `var symbol by mutableStateOf(...)` field anywhere in this file, which is the point: a
 * field is lost silently when the process is killed in the background, whereas a nav argument is
 * re-delivered by the navigation component and a screen restored from it is identical to the one the
 * user left.
 */
data class StockDetailUiState(
    val symbol: String,
    val timeframe: Timeframe,
    /** The header's four figures. `null` until the first load completes. */
    val header: StockHeader? = null,
    val isLoading: Boolean = true,
    /** The sentence to show in `ErrorState`, or `null`. */
    val errorReason: String? = null,
    /**
     * Set when the data is fine but today's file is not out yet (plan.md §3.1). Distinct from
     * [errorReason]: the screen keeps the last successful session with its as-of date and adds a note,
     * because "the 19:00 IST bhavcopy has not landed" is not a failure.
     */
    val notPublishedNote: String? = null,
    /**
     * The newest as-of date held for the selected timeframe, used for the header's freshness verdict
     * even when the header itself came from the other timeframe's cache.
     */
    val asOf: LocalDate? = null,
) {
    /** The freshness verdict for the header's close. `null` until a date is known. */
    val freshness: Freshness? get() = asOf?.let { priceFreshnessForDetail(it) }

    /** The company name, or the ticker when the master has no row for this symbol. */
    val displayName: String get() = header?.name?.takeIf { it.isNotBlank() } ?: symbol

    companion object {
        fun initial(symbol: String, timeframe: Timeframe) =
            StockDetailUiState(symbol = symbol, timeframe = timeframe)
    }
}

/**
 * The Stock Detail tab host's ViewModel.
 *
 * ### Why it holds so little
 * The two tabs own their own ViewModels and their own data sources. This one owns the header's four
 * numbers, the selected tab and the selected timeframe — nothing else. That is what keeps a
 * fundamentals reload from rebuilding a chart and a chart reload from re-running the sector resolver.
 *
 * ### `SavedStateHandle` is the single source of truth for both navigable arguments
 * [symbol] and [timeframe] are read **once**, in the constructor, exactly as `TechnicalsViewModel`
 * does for its own route. The reason to read them once rather than observing is that both are
 * navigation arguments: a change to either means a **different destination**, and navigation-compose
 * has already created a new `NavBackStackEntry` (and therefore a new ViewModel) by the time the
 * change lands. Observing would be reacting to a state that can no longer change.
 *
 * The selected **tab** is different — it is not an argument — so it is written back into the
 * [SavedStateHandle] and restored from it. See [onTabSelected].
 */
@HiltViewModel
class StockDetailViewModel @Inject constructor(
    private val dataSource: StockDetailDataSource,
    savedStateHandle: SavedStateHandle,
    @DetailBackgroundDispatcher private val backgroundDispatcher: CoroutineDispatcher,
) : ViewModel() {

    /**
     * The ticker, from the route.
     *
     * `SavedStateHandle`, not a constructor parameter of the composable: the back stack is what knows
     * which symbol this entry is, and a hand-passed argument could disagree with it.
     */
    val symbol: String = savedStateHandle.get<String>(ARG_SYMBOL)
        ?.trim()
        ?.uppercase()
        .orEmpty()

    /**
     * The timeframe the route was opened with, defaulting to `D1`.
     *
     * [Timeframe.fromStorageKey] returns `null` for anything unrecognised, which a hand-typed route or
     * a restored entry from an older build can produce. Decision D1 leaves exactly two members, so the
     * worst case is the other granularity — visible in the toggle and one tap from correct.
     */
    val timeframe: Timeframe =
        Timeframe.fromStorageKey(savedStateHandle.get<String>(ARG_TIMEFRAME)?.trim()?.uppercase().orEmpty())
            ?: Timeframe.D1

    /**
     * The selected tab, persisted so a rotation or a process death returns to the same tab.
     *
     * ### Why `SavedStateHandle` and not a `remember`
     * `remember` survives a rotation and loses process death, so it is the wrong half-solution: the tab
     * a reader was on when Android killed the app in the background is exactly the tab they want back.
     * A plain `MutableStateFlow` held as a field would have the same problem. `SavedStateHandle` is the
     * one store both survive.
     *
     * Persisted as the tab's **index** rather than its name so reordering the tab list cannot orphan a
     * restored index, and the restored index is **validated** against [TAB_COUNT] on read so a saved
     * index from a build with a different tab count falls back to [FUNDAMENTALS_TAB] instead of
     * selecting whatever now sits at that position.
     */
    private val _selectedTab = MutableStateFlow(
        savedStateHandle.get<Int>(KEY_SELECTED_TAB)
            ?.takeIf { it in 0 until TAB_COUNT }
            ?: FUNDAMENTALS_TAB,
    )

    /** The selected tab index. The screen maps it onto its own tab order. */
    val selectedTab: StateFlow<Int> = _selectedTab.asStateFlow()

    private val _uiState = MutableStateFlow(StockDetailUiState.initial(symbol, timeframe))
    val uiState: StateFlow<StockDetailUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    init {
        load()
    }

    /**
     * Switches the visible tab.
     *
     * Idempotent, because the two tabs are `Tab`s and a double tap during a recomposition must not
     * write twice. Out-of-range indices are ignored rather than clamped, so a restored index from a
     * build with more tabs cannot silently select a different one.
     */
    fun onTabSelected(index: Int) {
        if (index !in 0 until TAB_COUNT) return
        if (index == _selectedTab.value) return
        _selectedTab.value = index
        savedStateHandle[KEY_SELECTED_TAB] = index
    }

    /**
     * Changes the header's timeframe.
     *
     * The route argument is written back as well as the in-memory value, so a **new** `NavBackStackEntry`
     * opened from this one inherits the timeframe the reader chose. This is what lets a reader go to the
     * patterns route from here and land on the granularity they were reading.
     *
     * It does **not** change a tab that owns its own timeframe: `TechnicalsViewModel` reads
     * `ARG_TIMEFRAME` once in its constructor and keeps it, so the Technicals tab's own toggle remains
     * the authority for the price pane. The brief for this screen forbids wrapping `TechnicalsScreen()`,
     * and that constructor read is the concrete consequence; see the report.
     */
    fun onTimeframeSelected(timeframe: Timeframe) {
        if (timeframe == _uiState.value.timeframe) return
        savedStateHandle[ARG_TIMEFRAME] = timeframe.name
        load(timeframe)
    }

    /** Re-runs the header load for the current timeframe. */
    fun onRetry() {
        load(_uiState.value.timeframe)
    }

    private fun load(timeframe: Timeframe) {
        loadJob?.cancel()
        if (symbol.isEmpty()) {
            _uiState.update {
                it.copy(isLoading = false, errorReason = MISSING_SYMBOL_REASON)
            }
            return
        }
        _uiState.update { current ->
            // The previous header stays up while the new timeframe loads: a spinner over the old close
            // beats an empty header that reads as "this stock has no price".
            current.copy(timeframe = timeframe, isLoading = true, errorReason = null)
        }
        loadJob = viewModelScope.launch {
            try {
                val header = withContext(backgroundDispatcher) { dataSource.header(symbol) }
                val asOf = withContext(backgroundDispatcher) {
                    dataSource.asOf(symbol, timeframe) ?: header.asOf
                }
                _uiState.update { current ->
                    current.copy(
                        timeframe = timeframe,
                        header = header,
                        asOf = asOf,
                        isLoading = false,
                        errorReason = null,
                        notPublishedNote = null,
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (unavailable: StockDetailUnavailableException) {
                if (unavailable.isNotPublished) {
                    _uiState.update { current ->
                        current.copy(
                            timeframe = timeframe,
                            isLoading = false,
                            errorReason = null,
                            notPublishedNote = unavailable.reason,
                        )
                    }
                } else {
                    _uiState.update { current ->
                        current.copy(
                            timeframe = timeframe,
                            isLoading = false,
                            errorReason = unavailable.reason,
                        )
                    }
                }
            } catch (failure: Throwable) {
                _uiState.update { current ->
                    current.copy(
                        timeframe = timeframe,
                        isLoading = false,
                        errorReason = failure.message ?: UNEXPECTED_FAILURE,
                    )
                }
            }
        }
    }

    companion object {
        /** `SavedStateHandle` key for the selected tab. Prefix it so it cannot collide with a nav arg. */
        const val KEY_SELECTED_TAB: String = "detail_selected_tab"

        /** plan.md §8.3: fundamentals first, because a red flag can be on either tab. */
        const val FUNDAMENTALS_TAB: Int = 0

        /** Number of tabs the host renders. Read by [onTabSelected] to validate a restored index. */
        const val TAB_COUNT: Int = 2

        /** Shown only when an exception carried no message of its own. */
        const val UNEXPECTED_FAILURE: String = "the data layer failed without a reason"

        /** A route that arrived without a symbol. */
        const val MISSING_SYMBOL_REASON: String = "this screen was opened without a symbol"
    }
}