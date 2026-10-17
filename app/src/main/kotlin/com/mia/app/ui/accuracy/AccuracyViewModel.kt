package com.mia.app.ui.accuracy

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mia.app.domain.accuracy.PublishedInsight
import com.mia.app.domain.accuracy.TrackRecordInsight
import com.mia.app.domain.accuracy.TrackRecordInsightReport
import com.mia.app.domain.model.AccuracyReport
import com.mia.app.domain.model.HitRate
import com.mia.app.domain.model.MatchScorePolicy
import com.mia.app.domain.model.PatternOutcome
import com.mia.app.domain.model.Timeframe
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
 * What the Accuracy tab renders.
 *
 * One immutable value on a [StateFlow]. Every field is a measurement or an explicit absence: there
 * is no "null means zero" anywhere, because a 0 % hit rate for a record that has never been built
 * is a fabricated measurement of the app's own competence.
 *
 * @property insight the distance-bucket calibration table and the measured reason the rate is what
 *   it is. It carries its own
 *   [com.mia.app.domain.accuracy.InsightProvenance], so the screen cannot render a published
 *   research figure as this device's record: the label travels inside the value.
 */
data class AccuracyUiState(
    val isLoading: Boolean = false,
    /** The sentence shown in `ErrorState`, or `null` when there is nothing wrong. */
    val errorReason: String? = null,
    /** `AccuracyReport.EMPTY` until the first load succeeds. */
    val report: AccuracyReport = AccuracyReport.EMPTY,
    /** Outcomes whose evaluation window is still open. Never averaged into a rate. */
    val unresolved: List<PatternOutcome> = emptyList(),
    /** Symbols with at least one tracked outcome, sorted. */
    val trackedSymbols: List<String> = emptyList(),
    /** Timeframes the tracked outcomes span. */
    val trackedTimeframes: List<Timeframe> = emptyList(),
    /** Most recent settled verdict, or `null` when none has ever settled. */
    val lastSettledOn: LocalDate? = null,
    /**
     * The calibration table and the "why the rate is what it is" explanation.
     *
     * Defaults to the published measurement rather than to nothing: a fresh install has no outcomes
     * at all, and the honest thing to show it is the engine's own published measurement, clearly
     * labelled — not an empty panel on the one tab that exists to publish a record.
     */
    val insight: TrackRecordInsightReport = PublishedInsight.REPORT,
) {
    /** The bar below which every rate is withheld. Read from the policy so it cannot drift. */
    val minimumSamples: Int get() = MatchScorePolicy.MIN_HIT_RATE_SAMPLES

    /**
     * True when this device has tracked nothing.
     *
     * The honest empty state: a track record builds as patterns are projected, so an empty record is
     * the state of a fresh install, not a failure.
     */
    val hasNoOutcomes: Boolean get() = report.totalOutcomes == 0 && unresolved.isEmpty()

    /** True when outcomes exist but none has settled, so every rate is legitimately empty. */
    val hasNoSettledOutcomes: Boolean get() = report.totalOutcomes == unresolved.size

    /** Resolved outcomes: the only ones a rate may be computed over. */
    val settledOutcomes: Int get() = report.totalOutcomes - unresolved.size

    /** `true` when at least one rate on screen clears [minimumSamples]. */
    val hasReportableRate: Boolean
        get() = report.perSymbolAndPattern.any { it.isReportable } ||
            report.perPattern.any { it.isReportable } ||
            report.perSymbol.any { it.isReportable }

    /**
     * The cell nearest to becoming reportable, or `null` when every cell already clears the gate.
     *
     * plan.md §6.1's gate is a floor on evidence, so how far away that floor is is part of the
     * measurement. A table that is empty *because of a threshold* has to name the threshold and say
     * how close it is — otherwise the emptiness reads as a broken app rather than as a small sample,
     * which is the entire point of withholding below the gate in the first place.
     */
    val nearestReportable: HitRate? get() = TrackRecordInsight.nearestToReportable(report)

    /** Settled outcomes still needed before the first rate on this screen may be quoted at all. */
    val samplesUntilNextReportable: Int?
        get() = nearestReportable?.let { TrackRecordInsight.samplesUntilReportable(it) }

    companion object {
        /** The state before the first load completes. */
        fun initial(): AccuracyUiState = AccuracyUiState()
    }
}

/**
 * The Accuracy tab's ViewModel.
 *
 * ### Why there is no argument and no route parameter
 * Unlike every other tab, this one has no symbol: it reports on the whole record. That is also why
 * it has no timeframe toggle — the tracked outcomes span whatever timeframes the nightly scan
 * covered, and [AccuracyUiState.trackedTimeframes] says which.
 *
 * ### Cost
 * Nothing here runs a detector or an indicator pass. `HitRateCalculator` folds stored outcomes into
 * three lists, which is arithmetic over a few hundred rows, so the whole load is cheap — but it still
 * happens on the injected dispatcher, because it is disk-bound work under the interface and a
 * synchronous read on the main thread would be a StrictMode finding on every entry.
 *
 * ### Error handling
 * `CancellationException` is always rethrown, so `viewModelScope`'s structured concurrency survives
 * a retry mid-load. Anything else surfaces as [AccuracyUiState.errorReason] with a retry.
 */
@HiltViewModel
class AccuracyViewModel @Inject constructor(
    private val dataSource: AccuracyDataSource,
    @MiaBackgroundDispatcher private val backgroundDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AccuracyUiState.initial())
    val uiState: StateFlow<AccuracyUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    init {
        load()
    }

    /** Re-reads the record. */
    fun onRetry() {
        load()
    }

    private fun load() {
        loadJob?.cancel()
        _uiState.update { it.copy(isLoading = true, errorReason = null) }
        loadJob = viewModelScope.launch {
            try {
                val loaded = withContext(backgroundDispatcher) { fetch() }
                _uiState.value = loaded
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (unavailable: AccuracyUnavailableException) {
                _uiState.update {
                    it.copy(isLoading = false, errorReason = unavailable.reason)
                }
            } catch (unexpected: Throwable) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorReason = unexpected.message ?: UNEXPECTED_FAILURE,
                    )
                }
            }
        }
    }

    private suspend fun fetch(): AccuracyUiState {
        // Ordered so the most useful half arrives first: the report drives the three tables, and the
        // unresolved list is what stops the screen implying every outcome has been judged.
        val report = dataSource.report()
        val unresolved = dataSource.unresolved()
        val symbols = dataSource.trackedSymbols()
        val timeframes = dataSource.trackedTimeframes()
        val settledOn = dataSource.lastSettledOn()
        // The calibration table is the one read that may legitimately be unavailable, because it
        // depends on the stored bars as well as the stored outcomes. A failure falls back to the
        // published measurement, which is labelled as published — so the worst case is a reader
        // seeing research figures clearly marked as research, never seeing an empty panel and never
        // seeing them labelled as their own.
        val insight = runCatching { dataSource.trackRecordInsight() }
            .getOrDefault(PublishedInsight.REPORT)
        return AccuracyUiState(
            isLoading = false,
            errorReason = null,
            report = report,
            unresolved = unresolved,
            trackedSymbols = symbols,
            trackedTimeframes = timeframes,
            lastSettledOn = settledOn,
            insight = insight,
        )
    }

    private companion object {
        /** Shown only when an exception carried no message of its own. */
        const val UNEXPECTED_FAILURE = "the accuracy record could not be read"
    }
}