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
import com.mia.app.domain.pattern.EvaluationOutcome
import com.mia.app.domain.pattern.PatternEvaluation
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

    // -------------------------------------------------------------- Pattern Explorer (windowed)

    /**
     * Bars the Explorer is analysing, or `null` for the whole stored series.
     *
     * The score is only true of the slice that produced it, so this is carried in state and shown
     * on screen: a reader comparing two windows is comparing two different series.
     *
     * `null` means the canonical scan, which is also the only one whose results may be recorded.
     */
    val windowBars: Int? = null,

    /**
     * One outcome per catalogue pattern for the current window.
     *
     * `null` until the first evaluation completes, and deliberately kept separate from [matches]:
     * [matches] is what the engine kept, this is what it said about *everything* — which is the only
     * way to tell "scored below the gate" from "the app never looked for this".
     */
    val evaluation: PatternEvaluation? = null,

    /**
     * `true` when the chosen window was too short for a full pivot pass and the zigzag fell back to
     * a shallower depth.
     *
     * Surfaced because it is a quality signal invisible on the chart: the formations being found
     * are structurally shallower, so a match at 60 bars is not the same claim as one at 250.
     * Measured over 20 real large caps with window warm-up included: 13 of 20 use it at 60 bars,
     * 5 of 20 at 120, and 0 of 20 on a whole series.
     */
    val usedFallbackPivots: Boolean = false,

    /** The pattern whose glossary is open, or `null`. */
    val glossaryFor: String? = null,

    /**
     * `true` while a reader-triggered history fetch is in flight.
     *
     * Separate from [isLoading] because the two are not interchangeable: [isLoading] means "the
     * screen is drawing a placeholder", and a placeholder over an existing chart would make a
     * network fetch look like the tab lost its data. This one only drives a button's spinner.
     */
    val isFetchingHistory: Boolean = false,

    /**
     * What the last reader-triggered fetch achieved, or `null` if none has been pressed.
     *
     * `blocker != null` is the case that matters: it is the actionable sentence explaining why the
     * stock still cannot be analysed, which is exactly what the tab could not say before.
     */
    val forceScan: ForceScanOutcome? = null,
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

    // There is deliberately no `projectThreshold` here any more. It used to expose
    // `MatchScorePolicy.PROJECT_THRESHOLD` as "the threshold a match must clear to be projected at
    // all"; nothing read it, and nothing could clear it — 875 settled candidates produced a maximum
    // score of 68. A dead number on the state object invites the next reader to use it. What
    // replaced it is `com.mia.app.domain.pattern.ProjectionGate`, carried per match on
    // `PatternsProjection.blocker`, because "which condition said no" is a fact about one
    // formation and not a property of the tab.

    // --------------------------------------------------------- Pattern Explorer, derived

    /** Every catalogue pattern's outcome for the current window, or empty before the first one. */
    val outcomes: List<EvaluationOutcome>
        get() = evaluation?.outcomes.orEmpty()

    /** Patterns that cleared the render gate, best first. */
    val matchedOutcomes: List<EvaluationOutcome.Matched>
        get() = evaluation?.matched().orEmpty()

    /** Patterns that were scored and held back, best first. */
    val nearMisses: List<EvaluationOutcome.NearMiss>
        get() = evaluation?.nearMisses().orEmpty()

    /**
     * Window chips offered for this timeframe, from the measured sweep in
     * `WindowSizingMeasurementTest`.
     *
     * Every value is at or above [MatchScorePolicy.minBars] for the timeframe, because a shorter
     * window yields nothing at all: measured, a 30- or 40-bar daily window produced **zero** kept
     * matches and zero suppressed candidates on all 20 large caps. The last entry means "the whole
     * stored series", which is the canonical scan.
     */
    fun windowOptions(timeframe: Timeframe, totalBars: Int): List<Int?> {
        val gate = MatchScorePolicy.minBars(timeframe)
        val chips = listOf(60, 120, 250).filter { it >= gate && it < totalBars }
        return chips + null
    }

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

    /** The Explorer's window re-scan. Cancelled on every chip tap so taps cannot queue up. */
    private var windowJob: Job? = null

    /**
     * The reader-triggered history fetch. A separate job from [windowJob] because it must not be
     * cancelled by a window-chip tap: a fetch in progress is network work that a re-analysis cannot
     * usefully abandon, and cancelling it half way would leave the stored depth unknown.
     */
    private var forceJob: Job? = null

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
                _uiState.update { current ->
                    // `fetch` builds a fresh state, which is what keeps a stale bar count or scan
                    // note from surviving a reload — but it would also drop the outcome of a
                    // reader-triggered fetch that has just finished, leaving the button's own
                    // explanation of what it did on screen for a moment and then gone. Carried
                    // across explicitly rather than by making `fetch` aware of them.
                    loaded.copy(
                        timeframe = timeframe,
                        isLoading = false,
                        forceScan = current.forceScan,
                        isFetchingHistory = current.isFetchingHistory,
                    )
                }
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
        // Stale-only, and non-throwing: this tab is the app's clearest consumer of price history, so
        // it is the natural place to notice the cache is behind. A failure returns normally and the
        // `candles` call below reports the real reason.
        runCatching { dataSource.ensureFresh(symbol, timeframe) }

        val candles = dataSource.candles(symbol, timeframe)
        val scan = dataSource.scan(symbol, timeframe)
        val redFlags = dataSource.redFlags(symbol, timeframe)

        // The Explorer's per-pattern view, in the **same** background hop as the scan. It is a second
        // pass over the same bars, so folding it in here costs one thread hop rather than two — and
        // `evaluate` is what makes "no match" explainable.
        //
        // The canonical scan (windowBars = null) is deliberate for the initial load: it is the whole
        // stored series, which is what the other tabs quote, so the reader comparing tabs sees one
        // set of numbers rather than two.
        val evaluation = runCatching { dataSource.evaluate(symbol, timeframe, null) }.getOrNull()

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
            windowBars = null,
            evaluation = evaluation,
            usedFallbackPivots = evaluation?.usedFallbackPivots ?: false,
        )
    }

    /**
     * Re-analyses a chosen window and publishes the per-pattern outcomes.
     *
     * **Non-canonical**: the data source marks this scan so it can never reach the accuracy table.
     * `PatternIds` keys on the pattern name, so the same pattern has the same id at every window —
     * recording these would make the app's hit rate depend on which chip was tapped last.
     *
     * The in-flight job is cancelled first, so tapping three chips quickly runs one scan, not three.
     * Bars, matches and the chart are left untouched: a window change re-evaluates, it does not
     * reload, because the series did not change.
     */
    fun onWindowSelected(windowBars: Int?) {
        if (_uiState.value.windowBars == windowBars) return
        windowJob?.cancel()
        _uiState.update { it.copy(windowBars = windowBars, isLoading = true) }
        val timeframe = _uiState.value.timeframe
        windowJob = viewModelScope.launch {
            try {
                val evaluated = withContext(backgroundDispatcher) {
                    dataSource.evaluate(symbol, timeframe, windowBars)
                }
                _uiState.update { current ->
                    // Ignore a result that arrived after the reader moved on.
                    if (current.windowBars != windowBars) {
                        current
                    } else {
                        current.copy(
                            isLoading = false,
                            evaluation = evaluated,
                            usedFallbackPivots = evaluated.usedFallbackPivots,
                        )
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                // A failed window re-analysis leaves the previous outcomes on screen with a reason,
                // rather than blanking the list the reader was reading.
                _uiState.update { current ->
                    if (current.windowBars != windowBars) {
                        current
                    } else {
                        current.copy(
                            isLoading = false,
                            errorReason = failure.message ?: UNEXPECTED_FAILURE,
                        )
                    }
                }
            }
        }
    }

    /**
     * The reader's "fetch this stock's history and analyse it now" action.
     *
     * ### Why the reload afterwards is unconditional
     * [forceScan] writes bars and opens projections; it does not produce a screen. Re-running
     * [fetch] is what turns those bars into a scan, an evaluation and a chart, and doing it only
     * on success would leave the reader staring at "not enough history" next to a button that says
     * it added 280 bars — which reads as a bug even when the fetch worked.
     *
     * The reload is also what picks up the projections the scan just opened, so the track record
     * on screen is never one load behind the bars it is describing.
     *
     * Re-entrancy: a second press while one is in flight is ignored rather than queued, because
     * two concurrent fetches of the same symbol would race on the write-once `candles` table and
     * the loser would report zero bars added for work that did happen.
     */
    fun onForceScan() {
        if (_uiState.value.isFetchingHistory) return
        val timeframe = _uiState.value.timeframe
        _uiState.update { it.copy(isFetchingHistory = true, forceScan = null) }
        forceJob = viewModelScope.launch {
            val outcome = try {
                withContext(backgroundDispatcher) { dataSource.forceScan(symbol, timeframe) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                ForceScanOutcome(
                    barsStored = 0,
                    barsAdded = 0,
                    projectionsOpened = 0,
                    minBarsRequired = MatchScorePolicy.minBars(timeframe),
                    blocker = failure.message ?: UNEXPECTED_FAILURE,
                )
            }
            _uiState.update { it.copy(isFetchingHistory = false, forceScan = outcome) }
            // Whether it worked or not, re-read: a partial fetch may still have changed the
            // stored depth, and the screen must show the depth it actually has.
            load(timeframe)
        }
    }

    /** Opens the glossary for [patternName], or closes it when `null`. */
    fun onGlossaryToggled(patternName: String?) {
        _uiState.update { it.copy(glossaryFor = patternName) }
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