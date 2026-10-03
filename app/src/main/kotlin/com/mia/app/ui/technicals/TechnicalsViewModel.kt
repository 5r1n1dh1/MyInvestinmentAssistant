package com.mia.app.ui.technicals

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mia.app.domain.model.BreakoutTrigger
import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.Direction
import com.mia.app.domain.model.HitRate
import com.mia.app.domain.model.Match
import com.mia.app.domain.model.MatchScorePolicy
import com.mia.app.domain.model.ProjectedPath
import com.mia.app.domain.model.RedFlag
import com.mia.app.domain.model.Timeframe
import com.mia.app.domain.redflag.RuleSkip
import com.mia.app.domain.technical.IndicatorSnapshot
import com.mia.app.domain.technical.TrendRegime
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
 * What the Technicals tab renders.
 *
 * One immutable value, published as a [StateFlow]. Every field is either a measurement or an
 * explicit absence — there is no "null means zero" anywhere, because a zero shown for a missing
 * 200-day moving average is a fabricated number (plan.md §6.4).
 */
data class TechnicalsUiState(
    val symbol: String,
    val timeframe: Timeframe,
    val isLoading: Boolean = false,
    /** The sentence to show in `ErrorState`, or `null` when there is nothing wrong. */
    val errorReason: String? = null,

    /**
     * Why this tab is empty, when emptiness is a *fact about the device or the symbol* rather than
     * a failure.
     *
     * ### Why this is separate from [errorReason]
     * "No bars stored" and "the network is down" both leave the tab with nothing to draw, but they
     * are different claims and only one of them is fixable by pressing a button. Collapsing them
     * into one red banner is what made a merely-empty tab look broken — the reader was told the read
     * failed when in truth nothing had ever been downloaded. This field carries the honest absence
     * and [TechnicalsUiState.absenceOffersFetch] says whether a retry can help, so the screen can
     * choose between an explanation with an action and an explanation without one.
     *
     * `null` when bars exist, or when [errorReason] is set — the two are never both set.
     */
    val absenceReason: String? = null,

    /**
     * Whether a fetch button can actually fix the current absence.
     *
     * `true` for "nothing has ever been downloaded" and for a retryable source failure. `false`
     * for a symbol the published files contain no rows for — re-downloading the same files cannot
     * invent a row, so a button there would be a lie.
     */
    val absenceOffersFetch: Boolean = false,
    /**
     * Set when the data is fine but today's file is not out yet (plan.md §3.1). Distinct from
     * [errorReason]: the tab still shows the last successful session, with its as-of date, and no
     * red banner — because "today's bhavcopy is not published at 16:00" is not an error.
     */
    val notPublishedNote: String? = null,
    val candles: List<Candle> = emptyList(),
    /** `null` only when there were no bars at all. */
    val snapshot: IndicatorSnapshot? = null,
    /** Renderable matches, sorted by score descending. Never contains a suppressed match. */
    val matches: List<Match> = emptyList(),
    /** The match the chart's overlays and the projection panel describe. */
    val primaryMatch: Match? = null,
    val redFlags: List<RedFlag> = emptyList(),
    val skippedRules: List<RuleSkip> = emptyList(),
    /** Rules that threw, so coverage is not overstated. */
    val ruleFailures: Map<String, String> = emptyMap(),
    val projectedPath: ProjectedPath? = null,
    val breakoutTrigger: BreakoutTrigger? = null,
    val trackRecord: HitRate?,
    val trackRecordForSymbol: HitRate? = null,
    /** The last bar's date: the chart's and the header's "as of". */
    val asOf: LocalDate? = null,
) {
    /** Bars currently stored. */
    val barCount: Int get() = candles.size

    /** The plan.md §6.4 hard gate for this timeframe. */
    val minimumBars: Int get() = MatchScorePolicy.minBars(timeframe)

    /** False → the tab must say "not enough history" and must not draw a series. */
    val hasEnoughHistory: Boolean
        get() = MatchScorePolicy.isSufficientBars(timeframe, barCount)

    /** True when the red-flag banner must not imply a clean bill of health. */
    val flagCoverageIncomplete: Boolean
        get() = skippedRules.isNotEmpty() || ruleFailures.isNotEmpty()

    /** The last close, or null when there is no bar. */
    val lastClose: Double? get() = candles.lastOrNull()?.close

    /**
     * Change versus the previous close, in percent.
     *
     * `null` for a single bar — a percentage against nothing is the kind of number this app exists
     * not to invent.
     */
    val changePercent: Double?
        get() {
            val last = candles.lastOrNull() ?: return null
            val previous = candles.getOrNull(candles.lastIndex - 1) ?: return null
            if (previous.close <= 0.0) return null
            return (last.close - previous.close) / previous.close * 100.0
        }

    /** The indicator pass's own regime, or [TrendRegime.UNKNOWN] when it could not be computed. */
    val trendRegime: TrendRegime get() = snapshot?.trendRegime ?: TrendRegime.UNKNOWN

    /** Direction of the primary match's geometry, or null when there is no match. */
    val primaryDirection: Direction? get() = primaryMatch?.measuredMove?.direction

    companion object {
        /** The state before the first load completes. */
        fun initial(symbol: String, timeframe: Timeframe) = TechnicalsUiState(
            symbol = symbol,
            timeframe = timeframe,
            trackRecord = null,
            trackRecordForSymbol = null,
        )
    }
}

/**
 * The Technicals tab's ViewModel (plan.md P4).
 *
 * ### Why the indicator pass is on an injected dispatcher
 * [IndicatorSnapshot.of] is the single most expensive computation in the app on a budget device:
 * fifteen passes over up to 500 bars (SMA 20/50/100/200, EMA 20/50/200, Wilder RSI, MACD ×3, ATR,
 * Bollinger ×5, OBV, ADX ×5, a 30-bar regression and a 52-week scan), each allocating its own
 * aligned list. plan.md §12 targets 2 GB-RAM devices with a strict per-frame budget, so it runs
 * inside `withContext(backgroundDispatcher)` — never on the main thread — and the whole load
 * (bars, flags, matches, projections, track record) happens in that one context so there is a
 * single thread hop rather than six.
 *
 * ### Why the tab switches timeframe rather than loading both
 * A `1W` view is derived from the `1D` bars by `CandleResampler`, and the detector run is a full
 * fifteen-indicator pass. Loading both eagerly would double the work and the peak memory for a view
 * the reader may never open, which is the opposite of plan.md §12's "lazy-load briefs".
 *
 * ### Error handling
 * A [TechnicalsUnavailableException] with `isNotPublished` is **not** an error: the tab keeps
 * showing the last successful session with its as-of date and adds [TechnicalsUiState.notPublishedNote].
 * Anything else surfaces as [TechnicalsUiState.errorReason] with a retry. `CancellationException` is
 * always rethrown, because swallowing it would break `viewModelScope`'s structured concurrency.
 */
@HiltViewModel
class TechnicalsViewModel @Inject constructor(
    private val dataSource: TechnicalsDataSource,
    savedStateHandle: SavedStateHandle,
    @TechnicalsBackgroundDispatcher private val backgroundDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val symbol: String = savedStateHandle.get<String>(ARG_SYMBOL)
        ?.trim()
        ?.uppercase()
        .orEmpty()

    private val requestedTimeframe: Timeframe = Timeframe.fromStorageKey(
        savedStateHandle.get<String>(ARG_TIMEFRAME)?.trim()?.uppercase().orEmpty(),
    ) ?: Timeframe.D1

    private val _uiState = MutableStateFlow(TechnicalsUiState.initial(symbol, requestedTimeframe))
    val uiState: StateFlow<TechnicalsUiState> = _uiState.asStateFlow()

    /** The in-flight load, so a timeframe switch cancels the previous one instead of racing it. */
    private var loadJob: Job? = null

    init {
        load(requestedTimeframe)
    }

    /**
     * Switches the tab's timeframe, or does nothing when it is already selected.
     *
     * Idempotent on purpose: the two chips are `FilterChip`s, so a double tap during a load must
     * not restart the fifteen-indicator pass.
     */
    fun onTimeframeSelected(timeframe: Timeframe) {
        if (timeframe == _uiState.value.timeframe) return
        load(timeframe)
    }

    /** Re-runs the load for the current timeframe. */
    fun onRetry() {
        load(_uiState.value.timeframe)
    }

    private fun load(timeframe: Timeframe) {
        loadJob?.cancel()
        _uiState.update { current ->
            // Keep the previous candles on screen while the new timeframe loads: the tab then shows
            // a spinner over stale data rather than flashing an empty chart, which reads as "this
            // stock has no history".
            current.copy(
                timeframe = timeframe,
                isLoading = true,
                errorReason = null,
                absenceReason = null,
                absenceOffersFetch = false,
            )
        }
        loadJob = viewModelScope.launch {
            try {
                val loaded = withContext(backgroundDispatcher) { fetch(timeframe) }
                _uiState.update { current ->
                    current.copy(
                        timeframe = timeframe,
                        isLoading = false,
                        errorReason = null,
                        absenceReason = null,
                        absenceOffersFetch = false,
                        notPublishedNote = null,
                        candles = loaded.candles,
                        snapshot = loaded.snapshot,
                        redFlags = loaded.redFlags.flags,
                        skippedRules = loaded.redFlags.skipped,
                        ruleFailures = loaded.redFlags.failures,
                        matches = loaded.matches,
                        primaryMatch = loaded.matches.firstOrNull(),
                        projectedPath = loaded.projectedPath,
                        breakoutTrigger = loaded.breakoutTrigger,
                        trackRecord = loaded.trackRecord,
                        trackRecordForSymbol = loaded.trackRecordForSymbol,
                        asOf = loaded.candles.lastOrNull()?.date,
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (unavailable: TechnicalsUnavailableException) {
                if (unavailable.isNotPublished) {
                    // plan.md §3.1: today's file simply is not out yet. Keep the data, add a note.
                    _uiState.update { current ->
                        current.copy(
                            timeframe = timeframe,
                            isLoading = false,
                            errorReason = null,
                            absenceReason = null,
                            absenceOffersFetch = false,
                            notPublishedNote = unavailable.reason,
                        )
                    }
                } else if (unavailable.isAbsence) {
                    // Nothing to draw, but nothing has gone wrong either: an explained empty state
                    // with a fetch button when a fetch can help. `isTransient` is the right test —
                    // "never downloaded" and an unreachable source are both worth retrying, whereas
                    // a ticker the published files contain no rows for is not, and offering a button
                    // that cannot work teaches the reader to distrust it.
                    _uiState.update { current ->
                        current.copy(
                            timeframe = timeframe,
                            isLoading = false,
                            errorReason = null,
                            absenceReason = unavailable.reason,
                            absenceOffersFetch = unavailable.isTransient,
                        )
                    }
                } else {
                    _uiState.update { current ->
                        current.copy(
                            timeframe = timeframe,
                            isLoading = false,
                            errorReason = unavailable.reason,
                            absenceReason = null,
                            absenceOffersFetch = false,
                        )
                    }
                }
            } catch (unexpected: Throwable) {
                _uiState.update { current ->
                    current.copy(
                        timeframe = timeframe,
                        isLoading = false,
                        errorReason = unexpected.message ?: UNEXPECTED_FAILURE,
                        absenceReason = null,
                        absenceOffersFetch = false,
                    )
                }
            }
        }
    }

    /**
     * The whole load, on the background dispatcher.
     *
     * Ordered cheapest-first so a failure in an early step does not pay for the later ones: bars
     * must exist before indicators can be computed, and indicators must exist before a match's
     * regime gate can be applied.
     *
     * ### Why [ensureFresh] runs first, and why it is stale-only
     * This tab is the app's main reason the price table exists, so it is the natural place to notice
     * the table is behind. [TechnicalsDataSource.ensureFresh] fetches **only when the newest stored
     * session predates the newest expected session**, which is why opening ten stocks from a cache
     * fetched an hour ago costs one request rather than ten.
     *
     * It is a `try`-and-ignore on purpose. Its failure modes are already represented downstream: if
     * it cannot fetch, the subsequent `candles` call reports `NOT_ATTEMPTED` and the tab renders its
     * empty state with a fetch button; if it fetched and there is still nothing, the adapter's
     * typed exception carries the real reason. Letting the ensure's own exception escape instead
     * would collapse "the network is down" and "there are no bars for this ticker" into one
     * red banner, which is exactly the conflation the typed failures exist to prevent.
     */
    private suspend fun fetch(timeframe: Timeframe): TechnicalsLoad {
        runCatching { dataSource.ensureFresh(symbol, timeframe) }

        val candles = dataSource.candles(symbol, timeframe)

        // One indicator pass per load, shared by the render gate and by the screen's read-outs.
        val snapshot = if (candles.isEmpty()) null else IndicatorSnapshot.of(candles)

        val matches = renderable(dataSource.matches(symbol, timeframe), candles.size, timeframe)

        val redFlags = dataSource.redFlags(symbol, timeframe)

        val primary = matches.firstOrNull()
        val path = if (primary != null) dataSource.projectedPath(symbol, timeframe, primary) else null
        val trigger = if (primary != null) {
            dataSource.breakoutTrigger(symbol, timeframe, primary)
        } else {
            null
        }

        // decision D6: per stock **and** per pattern, plus the stock's own all-pattern figure.
        val record = dataSource.trackRecord(symbol, primary?.name)
        val recordForSymbol = dataSource.trackRecord(symbol, null)

        return TechnicalsLoad(
            candles = candles,
            snapshot = snapshot,
            redFlags = redFlags,
            matches = matches,
            projectedPath = path,
            breakoutTrigger = trigger,
            trackRecord = record,
            trackRecordForSymbol = recordForSymbol,
        )
    }

    /**
     * plan.md §6.4's render rule, applied at the last possible moment.
     *
     * * Below the timeframe's bar gate → nothing at all. The screen says "not enough history"
     *   instead of listing patterns it refuses to score.
     * * Below `SUPPRESS_THRESHOLD` → dropped entirely. plan.md §6.4: "suppress entirely".
     *
     * The regime gate is **not** applied here: it only separates `PROJECT` from `RENDER`, and a
     * match that cannot be projected is still rendered with its measured move. Suppressing it here
     * would hide geometry the reader is entitled to see.
     *
     * Sorted by score descending so the chart's overlays follow the *best* match, which is the one
     * [TechnicalsUiState.primaryMatch] describes.
     */
    private fun renderable(
        matches: List<Match>,
        barCount: Int,
        timeframe: Timeframe,
    ): List<Match> {
        if (!MatchScorePolicy.isSufficientBars(timeframe, barCount)) return emptyList()
        return matches
            .filter { it.score >= MatchScorePolicy.SUPPRESS_THRESHOLD }
            .sortedByDescending { it.score }
    }

    private companion object {
        /** Shown only when an exception carried no message of its own. */
        const val UNEXPECTED_FAILURE = "the data layer failed without a reason"
    }
}
