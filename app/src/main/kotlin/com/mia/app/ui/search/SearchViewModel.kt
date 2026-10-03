package com.mia.app.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Where the voice affordance is, and why the screen needs to know.
 *
 * ### Why the UI owns the state and not the recogniser
 * The Hinglish recogniser is a separate concern with its own failure modes (no permission, no
 * speech, silence). Owning them here — [Failed] carries the sentence the user reads — keeps the
 * screen's error rendering testable with no recogniser in the graph, and means the future
 * recogniser only has to call [SearchViewModel.onVoiceResult] or
 * [SearchViewModel.onVoiceFailure]. It never touches a composable.
 *
 * [Failed] deliberately has no `retryable` flag: the screen shows a retry affordance for all of
 * them, because the only recovery from a failed permission request is to grant it and try again.
 */
sealed interface VoiceSearchUiState {

    /** Not started. The button shows the mic glyph. */
    data object Idle : VoiceSearchUiState

    /** The recogniser is capturing audio. The button shows a stop glyph plus an activity cue. */
    data object Listening : VoiceSearchUiState

    /** Audio captured, being turned into text. Nothing to do yet. */
    data object Transcribing : VoiceSearchUiState

    /**
     * It did not work.
     *
     * @param reason the sentence shown verbatim. Must already be plain language — plan.md §12's rule
     *   is that an error names what failed, and this string is the only thing the reader learns.
     */
    data class Failed(val reason: String) : VoiceSearchUiState
}

/**
 * One-shot things the Search screen asks the outside world to do.
 *
 * A [SharedFlow] rather than a field on the state because these are **events, not state**: emitting
 * "start listening" must happen exactly once per tap, and a screen that recomposes must not ask for
 * a second recogniser session. `extraBufferCapacity = 1` with no replay means an event emitted while
 * no collector is attached is dropped rather than delivered late, which is the correct behaviour for
 * "the user just tapped the mic".
 */
sealed interface SearchEvent {

    /**
     * The user asked for voice search.
     *
     * [SearchViewModel] has already moved [SearchUiState.voice] to [VoiceSearchUiState.Listening];
     * the recogniser owns the rest of the lifecycle and reports back through
     * [SearchViewModel.onVoiceResult] / [SearchViewModel.onVoiceFailure].
     */
    data object RequestVoiceSearch : SearchEvent
}

/**
 * What the Search screen renders.
 *
 * ### The rule the state is built around
 * plan.md §9 requirement 4: **never return zero results silently**. So there is no
 * `results: List<…>` + `isEmpty` + "nothing found" path: whenever [SearchUiState.results] is empty
 * and [SearchUiState.query] is non-blank, [SearchUiState.resolution] is non-null and carries both
 * the answer (a retired ticker mapped to its successor) and the sentence explaining it.
 * [SearchUiState.hasAnswer] makes that invariant checkable in one place.
 */
data class SearchUiState(
    /** Exactly what is in the text field, untrimmed — the field must echo the user's own casing. */
    val query: String = "",
    /** True while the bundled master is being read for the first time. */
    val isLoadingBook: Boolean = true,
    /** True while a debounced query is in flight. The previous results stay on screen meanwhile. */
    val isSearching: Boolean = false,
    /** Ranked hits. Empty is legal; it is never the whole story — see [resolution]. */
    val results: List<SymbolMatch> = emptyList(),
    /** The resolver's answer for the current query, or `null` when the results list is non-empty. */
    val resolution: SymbolResolution? = null,
    /** The sentence to show in `ErrorState`, or `null`. */
    val errorReason: String? = null,
    /** How many instruments the bundled master holds, for the header line. `0` until it is read. */
    val bookSize: Int = 0,
    /** Symbols already on the tracked list, so each row can say so instead of offering "Add" twice. */
    val trackedSymbols: Set<String> = emptySet(),
    /** The voice affordance's state. */
    val voice: VoiceSearchUiState = VoiceSearchUiState.Idle,
    /** A one-line confirmation of the last add-to-tracked action, cleared on the next query. */
    val notice: String? = null,
) {
    /**
     * True when the screen has something to show.
     *
     * `results` non-empty **or** a resolution that either names a symbol or offers near misses —
     * so the screen can never fall through to a bare "no results" card.
     */
    val hasAnswer: Boolean
        get() = results.isNotEmpty() ||
            (resolution?.let { it.summary != null || it.alternatives.isNotEmpty() } == true)

    /** The query after trimming — the form actually searched. Empty means "no query". */
    val effectiveQuery: String get() = query.trim()

    /** True when a query has been typed and the search has settled. Drives the "how many hits" line. */
    val hasSearched: Boolean get() = effectiveQuery.isNotEmpty() && !isSearching

    /** True only when nothing matched *and* the resolver also found nothing — the honest zero. */
    val isTrulyEmpty: Boolean
        get() = hasSearched && results.isEmpty() &&
            resolution?.summary == null &&
            resolution?.alternatives.isNullOrEmpty()
}

/**
 * The Search screen's ViewModel (plan.md §9, phase P1).
 *
 * ### Debounce, and why it is not a Flow operator
 * The search is [SearchDataSource.search] over an in-memory book, so the work per keystroke is a
 * 2,593-row scan — sub-millisecond, and cheap enough that a debounce is about *screen churn*, not
 * CPU. A `debounce().flatMapLatest()` pipeline would be the idiomatic form, but `flatMapLatest` is
 * `@ExperimentalCoroutinesApi` and this module cannot be compiled on the build machine; an explicit
 * [Job] plus `delay` is the same behaviour with no opt-in, and it makes the cancellation visible:
 * a new keystroke cancels the pending search, so results can never arrive out of order.
 */
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val dataSource: SearchDataSource,
    @SearchBackgroundDispatcher private val backgroundDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<SearchEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<SearchEvent> = _events.asSharedFlow()

    /** The in-flight or pending search, so a new keystroke supersedes the old one. */
    private var searchJob: Job? = null

    init {
        loadBook()
    }

    /**
     * A keystroke in the search field.
     *
     * @param raw written verbatim into [SearchUiState.query] so the text field never fights the
     *   ViewModel, then trimmed for the actual query.
     */
    fun onQueryChanged(raw: String) {
        searchJob?.cancel()
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            // A cleared field is not a search that found nothing; it is no search at all. Leaving the
            // previous hits up would be showing results for a query the user can no longer see.
            _uiState.update { current ->
                current.copy(
                    query = raw,
                    isSearching = false,
                    results = emptyList(),
                    resolution = null,
                    errorReason = null,
                    notice = null,
                )
            }
            return
        }
        _uiState.update { current ->
            current.copy(query = raw, isSearching = true, notice = null)
        }
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MILLIS)
            runSearch(trimmed)
        }
    }

    /**
     * The user asked for voice search.
     *
     * Moves the state to [VoiceSearchUiState.Listening] and emits [SearchEvent.RequestVoiceSearch].
     * A second tap while listening is a **stop**, not a second request, so it moves to
     * [VoiceSearchUiState.Idle] and emits nothing — otherwise a double tap would start two
     * recogniser sessions on a device that cannot afford one.
     */
    fun onVoiceRequested() {
        val voice = _uiState.value.voice
        if (voice == VoiceSearchUiState.Listening) {
            _uiState.update { it.copy(voice = VoiceSearchUiState.Idle) }
            return
        }
        _uiState.update { it.copy(voice = VoiceSearchUiState.Listening) }
        _events.tryEmit(SearchEvent.RequestVoiceSearch)
    }

    /**
     * The recogniser produced text.
     *
     * Called by the future recogniser, never by this class. Fills the search field exactly as a
     * keystroke would — same debounce, same ranking — so a spoken query and a typed one take the
     * identical path and cannot disagree.
     */
    fun onVoiceResult(transcript: String) {
        _uiState.update { it.copy(voice = VoiceSearchUiState.Idle) }
        onQueryChanged(transcript)
    }

    /**
     * The recogniser failed, or the user said nothing intelligible.
     *
     * @param reason plain language, shown verbatim as the voice row's message.
     */
    fun onVoiceFailure(reason: String) {
        _uiState.update { it.copy(voice = VoiceSearchUiState.Failed(reason)) }
    }

    /** Clears the voice error, returning the button to its idle glyph. */
    fun onVoiceDismissed() {
        _uiState.update { it.copy(voice = VoiceSearchUiState.Idle) }
    }

    /**
     * Adds [symbol] to the tracked list.
     *
     * @return nothing; the outcome lands in [SearchUiState.trackedSymbols] and [SearchUiState.notice].
     */
    fun onAddToTracked(symbol: String) {
        val ticker = symbol.trim().uppercase()
        if (ticker.isEmpty()) return
        viewModelScope.launch {
            try {
                val stored = withContext(backgroundDispatcher) { dataSource.addToWatchlist(ticker) }
                _uiState.update { current ->
                    if (stored == null) {
                        current.copy(
                            notice = "$ticker is not in the bundled NSE symbol master, so it was " +
                                "not added",
                        )
                    } else {
                        current.copy(
                            trackedSymbols = current.trackedSymbols + stored.symbol,
                            notice = "${stored.symbol} added to your tracked symbols",
                        )
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                // An add that failed must not throw away the results already on screen; it adds one
                // line and leaves everything else intact.
                _uiState.update { current ->
                    current.copy(notice = failure.message ?: "could not add $ticker")
                }
            }
        }
    }

    /** Re-reads the bundled master and re-runs the current query. */
    fun onRetry() {
        loadBook()
        _uiState.update { it.copy(errorReason = null) }
        val trimmed = _uiState.value.effectiveQuery
        if (trimmed.isNotEmpty()) {
            onQueryChanged(_uiState.value.query)
        }
    }

    // ------------------------------------------------------------------------------- internals

    /**
     * Reads the bundled master once, for the header line and for a genuinely empty book.
     *
     * `symbolBook()` is not called per keystroke: the ranking itself is the data source's in-memory
     * scan, so this is a one-off parse of a 2,593-row asset (plan.md §9 requirement 1).
     */
    private fun loadBook() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingBook = true, errorReason = null) }
            try {
                val book = withContext(backgroundDispatcher) { dataSource.symbolBook() }
                _uiState.update { it.copy(isLoadingBook = false, bookSize = book.size) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: SearchUnavailableException) {
                _uiState.update { it.copy(isLoadingBook = false, errorReason = failure.reason) }
            } catch (failure: Throwable) {
                _uiState.update {
                    it.copy(isLoadingBook = false, errorReason = failure.message ?: UNEXPECTED_FAILURE)
                }
            }
        }
    }

    /**
     * One search, on the background dispatcher.
     *
     * ### Why the ranking is re-applied here
     * `SymbolMatchKind.rank` states the rule plan.md §9 wants — exact ticker, then ticker prefix, then
     * ticker substring, then name prefix, then name substring — and re-sorting means the screen's
     * order is provably that rule even if the adapter's comparator changes. Ties break on the
     * shorter ticker and then alphabetically, so `RELIANCE` precedes `RELIANCEIND` for the same prefix.
     */
    private suspend fun runSearch(trimmed: String) {
        try {
            val ranked = withContext(backgroundDispatcher) {
                dataSource.search(trimmed, SearchDataSource.DEFAULT_RESULT_LIMIT)
            }
            val ordered = ranked.sortedWith(RANKING)
            if (ordered.isNotEmpty()) {
                _uiState.update { current ->
                    current.copy(
                        isSearching = false,
                        results = ordered,
                        // A hit list is its own answer; a stale resolution beside it would be a
                        // second, contradictory answer to the same query.
                        resolution = null,
                        errorReason = null,
                    )
                }
                return
            }
            // plan.md §9 requirement 4. Zero hits for a well-formed ticker is never reported as an
            // empty list — the resolver answers, with a sentence.
            val resolution = withContext(backgroundDispatcher) { dataSource.resolve(trimmed) }
            _uiState.update { current ->
                current.copy(
                    isSearching = false,
                    results = emptyList(),
                    resolution = resolution,
                    errorReason = null,
                )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: SearchUnavailableException) {
            _uiState.update { it.copy(isSearching = false, errorReason = failure.reason) }
        } catch (failure: Throwable) {
            _uiState.update {
                it.copy(
                    isSearching = false,
                    errorReason = failure.message ?: UNEXPECTED_FAILURE,
                )
            }
        }
    }

    private companion object {
        /**
         * 200 ms — long enough that a fast typist produces one search instead of eight, short enough
         * that the results feel attached to the keystroke. plan.md §9 requirement 5 asks for
         * "instant", which is a perceived-latency budget, not a zero.
         */
        const val SEARCH_DEBOUNCE_MILLIS = 200L

        const val UNEXPECTED_FAILURE = "the symbol book failed to load without a reason"

        /** The ranking rule, as one comparator. See [runSearch] for why it lives here. */
        val RANKING: Comparator<SymbolMatch> =
            compareBy<SymbolMatch> { it.kind.rank }
                .thenBy { it.summary.symbol.length }
                .thenBy { it.summary.symbol }
    }
}