package com.mia.app.ui.patterns

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.HitRate
import com.mia.app.domain.model.Match
import com.mia.app.domain.model.MatchScorePolicy
import com.mia.app.domain.model.RedFlag
import com.mia.app.domain.model.Timeframe
import com.mia.app.domain.pattern.SuppressedMatch
import com.mia.app.domain.redflag.RuleSkip
import com.mia.app.ui.nav.ARG_SYMBOL
import com.mia.app.ui.nav.ARG_TIMEFRAME
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
 * What the Patterns tab renders.
 *
 * One immutable value on a [StateFlow]. Every field is a measurement or an explicit absence — there
 * is no "null means zero" anywhere, because a zero drawn for a missing 60-bar history is a
 * fabricated reading.
 */
data class PatternsUiState(
    val symbol: String,
    val timeframe: Timeframe,
    val isLoading: Boolean = false,
    /** The sentence to show in `ErrorState`, or `null`. */
    val errorReason: String? = null,
    /** Today's file is not out yet (plan.md §3.1). Not an error; a note above the content. */
    val notPublishedNote: String? = null,
    val candles: List<Candle> = emptyList(),
    /** Ranked, renderable matches. Sorted by `PatternScanResult`'s own ranking. */
    val matches: List<Match> = emptyList(),
    /** Candidates the engine chose not to draw, each with the reason it gives. */
    val suppressed: List<SuppressedMatch> = emptyList(),
    /** Transparency notes from the scan: warm-ups, missing benchmarks, unscored factors. */
    val scanNotes: List<String> = emptyList(),
    /**
     * The match the chart overlays and the geometry / projection panels describe.
     *
     * Defaults to the top-scoring match and follows the user's selection. `null` only when nothing
     * cleared the render threshold.
     */
    val selectedMatch: Match? = null,
    /** The selected match's three outputs. Never merged (decision D2). */
    val projection: PatternsProjection = PatternsProjection.NONE,
    val redFlags: List<RedFlag> = emptyList(),
    val skippedRules: List<RuleSkip> = emptyList(),
    /** Rules that threw, so coverage is not overstated. */
    val ruleFailures: Map<String, String> = emptyMap(),
    /**
     * Per **stock and pattern** for each rendered match, keyed by `Match.name`.
     *
     * A map rather than one figure because decision D6 asks for per stock **and** per pattern, and
     * the tab shows the row beside each pattern rather than only for the selected one.
     */
    val trackRecords: Map<String, HitRate> = emptyMap(),
    /** This stock's all-pattern figure, shown once under the per-pattern rows. */
    val trackRecordForSymbol: HitRate? = null,
    /** The last bar's date: the chart's and the header's "as of". */
    val asOf: LocalDate? = null,
) {
    /** Bars currently stored. */
    val barCount: Int get() = candles.size

    /** The plan.md §6.4 hard gate for this timeframe. */
    val minimumBars: Int get() = MatchScorePolicy.minBars(timeframe)

    /** `false` → the tab must say "not enough history" and must not draw a series. */
    val hasEnoughHistory: Boolean
        get() = MatchScorePolicy.isSufficientBars(timeframe, barCount)

    /** The best-scoring match, or `null`. */
    val topMatch: Match? get() = matches.firstOrNull()

    /** The last close, or `null` when there is no bar. */
    val lastClose: Double? get() = candles.lastOrNull()?.close

    /** Change versus the previous close in percent, or `null` for a single stored bar. */
    val changePercent: Double?
        get() {
            val last = candles.lastOrNull() ?: return null
            val previous = candles.getOrNull(candles.lastIndex - 1) ?: return null
            if (previous.close <= 0.0) return null
            return (last.close - previous.close) / previous.close * 100.0
        }

    /** True when the red-flag banner must not imply a clean bill of health. */
    val flagCoverageIncomplete: Boolean
        get() = skippedRules.isNotEmpty() || ruleFailures.isNotEmpty()

    /**
     * True when the scan ran, cleared the bar gate, and produced nothing renderable.
     *
     * This is a **result**, not a failure: it means nothing scored
     * [MatchScorePolicy.RENDER_THRESHOLD] or more. The screen says exactly that, naming the
     * threshold, because "no patterns found" with no threshold reads as a bug.
     */
    val isRenderEmpty: Boolean
        get() = hasEnoughHistory && barCount > 0 && matches.isEmpty()

    /** The threshold the render gate uses, for the copy that names it. */
    val renderThreshold: Int get() = MatchScorePolicy.RENDER_THRESHOLD

    /** The threshold below which a match is suppressed, for the same reason. */
    val suppressThreshold: Int get() = MatchScorePolicy.SUPPRESS_THRESHOLD

    /** The threshold a match must clear to be projected at all. */
    val projectThreshold: Int get() = MatchScorePolicy.PROJECT_THRESHOLD

    companion object {
        /** The state before the first load completes. */
        fun initial(symbol: String, timeframe: Timeframe) = PatternsUiState(
            symbol = symbol,
            timeframe = timeframe,
        )
    }
}

/**
 * The Patterns tab's ViewModel (plan.md §6.5 / P8).
 *
 * ### Why the whole load is one `withContext`
 * The scan is a shared zig-zag pass plus a fifteen-indicator snapshot plus twelve detectors over the
 * same pivot list, and it is the single most expensive computation in the app on a budget device.
 * Bars, scan, projections, flags and track records are therefore fetched in **one** background
 * context, so there is a single thread hop and no intermediate state in which the chart has bars but
 * the scan has not run.
 *
 * ### Why the render gate is applied here and not in the screen
 * plan.md §6.4 makes `barsSufficient` a hard gate and says "suppress entirely" below
 * [MatchScorePolicy.SUPPRESS_THRESHOLD]. The engine has already made that decision — it is what
 * `PatternScanResult.matches` and `.suppressed` are — so this ViewModel does **not** re-filter. The
 * only thing it adds is the selection, which is a view concern.
 *
 * ### Why the regime gate is *not* applied here
 * The regime gate only separates `PROJECT` from `RENDER`. A match that cannot be projected is still
 * rendered, with its geometry and without a directional path. Suppressing it would hide geometry
 * the reader is entitled to see, so the ViewModel keeps it and lets
 * `PatternsDataSource.projections` return a `null` path.
 *
 * ### Selection
 * [onMatchSelected] keeps the chart, the geometry panel and the projection panel pointing at one
 * match. A selection that does not exist in [PatternsUiState.matches] — because a refresh changed the
 * ranking — falls back to the top match rather than rendering panels for a match the list no longer
 * contains.
 */
@HiltViewModel
class PatternsViewModel @Inject constructor(
    private val dataSource: PatternsDataSource,
    savedStateHandle: SavedStateHandle,
    @MiaBackgroundDispatcher private val backgroundDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val symbol: String = savedStateHandle.get<String>(ARG_SYMBOL)
        ?.trim()
        ?.uppercase()
        .orEmpty()

    private val requestedTimeframe: Timeframe =
        Timeframe.fromStorageKey(savedStateHandle.get<String>(ARG_TIMEFRAME)?.trim()?.uppercase().orEmpty())
            ?: Timeframe.D1

    private val _uiState = MutableStateFlow(PatternsUiState.initial(symbol, requestedTimeframe))
    val uiState: StateFlow<PatternsUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    init {
        load(requestedTimeframe)
    }

    /**
     * Switches the timeframe, or does nothing when it is already selected.
     *
     * Idempotent on purpose: the two chips are `FilterChip`s, so a double tap during a load must not
     * restart the detector pass.
     */
    fun onTimeframeSelected(timeframe: Timeframe) {
        if (timeframe == _uiState.value.timeframe) return
        load(timeframe)
    }

    /** Re-runs the load for the current timeframe. */
    fun onRetry() {
        load(_uiState.value.timeframe)
    }

    /**
     * Points the chart and the geometry / projection panels at [match].
     *
     * `null` restores the default, which is the top-scoring match.
     */
    fun onMatchSelected(match: Match?) {
        val current = _uiState.value
        val target = match?.takeIf { candidate -> current.matches.any { it.patternId == candidate.patternId } }
            ?: current.topMatch
        if (target == null) {
            _uiState.update { it.copy(selectedMatch = null, projection = PatternsProjection.NONE) }
            return
        }
        if (current.selectedMatch?.patternId == target.patternId) return
        _uiState.update { it.copy(selectedMatch = target, projection = PatternsProjection.NONE) }
        loadProjection(target)
    }

    private fun load(timeframe: Timeframe) {
        loadJob?.cancel()
        _uiState.update { current ->
            // Keep the previous bars on screen while the new timeframe loads: a spinner over stale
            // bars beats an empty chart, which reads as "this stock has no history".
            current.copy(timeframe = timeframe, isLoading = true, errorReason = null)
        }
        loadJob = viewModelScope.launch {
            try {
                val loaded = withContext(backgroundDispatcher) { fetch(timeframe) }
                _uiState.update { current -> loaded.copy(timeframe = timeframe, isLoading = false) }
                val selected = loaded.selectedMatch ?: loaded.topMatch
                if (selected != null) loadProjection(selected)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (unavailable: PatternsUnavailableException) {
                _uiState.update { current ->
                    if (unavailable.isNotPublished) {
                        // plan.md §3.1: the file is simply not out yet. Keep the data, add a note.
                        current.copy(
                            timeframe = timeframe,
                            isLoading = false,
                            errorReason = null,
                            notPublishedNote = unavailable.reason,
                        )
                    } else {
                        current.copy(
                            timeframe = timeframe,
                            isLoading = false,
                            errorReason = unavailable.reason,
                        )
                    }
                }
            } catch (unexpected: Throwable) {
                _uiState.update { current ->
                    current.copy(
                        timeframe = timeframe,
                        isLoading = false,
                        errorReason = unexpected.message ?: UNEXPECTED_FAILURE,
                    )
                }
            }
        }
    }

    /**
     * The whole load, on the background dispatcher.
     *
     * Ordered cheapest-first where there is a dependency: the flags read does not depend on the
     * scan, but the track record does depend on knowing which patterns were actually shown, so the
     * scan runs first and only the *rendered* match names are asked about. That matters: asking the
     * record about a suppressed pattern would produce a table row for something the user never saw.
     */
    private suspend fun fetch(timeframe: Timeframe): PatternsUiState {
        val candles = dataSource.candles(symbol, timeframe)
        val scan = dataSource.scan(symbol, timeframe)
        val redFlags = dataSource.redFlags(symbol, timeframe)

        val trackRecords = LinkedHashMap<String, HitRate>(scan.matches.size)
        scan.matches.forEach { match ->
            dataSource.trackRecord(symbol, match.name)?.let { rate -> trackRecords[match.name] = rate }
        }
        val forSymbol = dataSource.trackRecord(symbol, null)

        return PatternsUiState(
            symbol = symbol,
            timeframe = timeframe,
            isLoading = false,
            errorReason = null,
            notPublishedNote = null,
            candles = candles,
            matches = scan.matches,
            suppressed = scan.suppressed,
            scanNotes = scan.notes,
            selectedMatch = scan.matches.firstOrNull(),
            projection = PatternsProjection.NONE,
            redFlags = redFlags.flags,
            skippedRules = redFlags.skipped,
            ruleFailures = redFlags.failures,
            trackRecords = trackRecords,
            trackRecordForSymbol = forSymbol,
            asOf = candles.lastOrNull()?.date,
        )
    }

    /**
     * Loads the selected match's three outputs.
     *
     * A second, small background hop: only one match is described, and the chart must not wait for a
     * path it will only draw for the selected formation.
     */
    private fun loadProjection(match: Match) {
        val timeframe = _uiState.value.timeframe
        viewModelScope.launch {
            val projection = try {
                withContext(backgroundDispatcher) {
                    dataSource.projections(symbol, timeframe, match)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                // A projection that cannot be computed must not take the match list down with it. The
                // screen then renders the match with no geometry panel, which is the honest state.
                PatternsProjection.NONE
            }
            _uiState.update { current ->
                // Only publish if the selection has not moved on while this hop was in flight.
                if (current.selectedMatch?.patternId != match.patternId) {
                    current
                } else {
                    current.copy(projection = projection)
                }
            }
        }
    }

    private companion object {
        /** Shown only when an exception carried no message of its own. */
        const val UNEXPECTED_FAILURE = "the pattern scan failed without a reason"
    }
}