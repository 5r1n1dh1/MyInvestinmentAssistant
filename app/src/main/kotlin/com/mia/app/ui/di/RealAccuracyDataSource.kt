package com.mia.app.ui.di

import com.mia.app.data.android.repo.AccuracyRepository
import com.mia.app.data.android.repo.DataFailure
import com.mia.app.data.android.repo.DataResult
import com.mia.app.domain.accuracy.TrackRecordContext
import com.mia.app.domain.model.AccuracyReport
import com.mia.app.domain.model.PatternOutcome
import com.mia.app.domain.model.Timeframe
import com.mia.app.ui.accuracy.AccuracyDataSource
import com.mia.app.ui.accuracy.AccuracyUnavailableException
import com.mia.app.work.MiaBackgroundDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [AccuracyDataSource] over `:android-data`'s `AccuracyRepository` and `HitRateCalculator`.
 *
 * ### Nothing here computes a rate
 * Every figure comes from `HitRateCalculator.report` or `AccuracyProjector.context`, both in
 * `:domain`, and `HitRate.label()` / `HitRate.isReportable` are what withhold anything below
 * `MatchScorePolicy.MIN_HIT_RATE_SAMPLES`. This adapter's whole job is to decide which of three
 * states each read is in — *nothing tracked*, *tracked but unresolved*, *tracked and settled* — and
 * to hand the domain's own values through. Recomputing a percentage here would be the one change
 * that makes this screen bendable, which is exactly what decision D6 forbids.
 *
 * ### "Nothing tracked" is a value, not a failure
 * `AccuracyRepository.report` answers `NO_DATA_CACHED` when the `outcomes` table is empty. That is
 * the state of every fresh install, and the screen renders it as its intentional empty state
 * (`hasNoOutcomes` → "No tracked outcomes yet"). Turning it into a typed failure would replace an
 * honest empty state with a red banner on first launch. A **real** failure — an unreadable table, an
 * unexpected error — does throw.
 *
 * ### Two approximations, stated rather than hidden
 * Both come from the same cause: the storage projection does not expose the two facts the interface
 * asks for, and no repository method carries them.
 *
 * * **[lastSettledOn] returns the newest tracked projection date**, not the date a verdict settled.
 *   A `PatternOutcome` records `projectedOn` and, once resolved, `hit` — there is no settle-date
 *   column. `AccuracyRepository.report` publishes `Provenance.asOf = max(projectedOn)`, so that is
 *   the freshest true statement available: the most recent thing the record knows about. It is never
 *   later than the true settle date, so it cannot overstate freshness.
 * * **[trackedTimeframes] is derived from the unresolved outcomes only.** `AccuracyReport` groups by
 *   symbol and pattern and carries no timeframe, and the resolved outcomes are reachable only through
 *   the aggregated `HitRate`s. So a record with no open outcomes reports no timeframes rather than
 *   claiming coverage it cannot name. The screen renders the list as a coverage line, so an empty one
 *   reads as "not stated" rather than as "none".
 */
@Singleton
class RealAccuracyDataSource @Inject constructor(
    private val accuracyRepository: AccuracyRepository,
    @MiaBackgroundDispatcher private val backgroundDispatcher: CoroutineDispatcher,
) : AccuracyDataSource {

    override suspend fun report(): AccuracyReport = onBackground {
        val result = accuracyRepository.report()
        val failure = result.failureOrNull()
        if (failure != null) {
            if (failure.isNothingStored()) return@onBackground AccuracyReport.EMPTY
            throw unavailable(failure)
        }
        result.valueOrNull() ?: AccuracyReport.EMPTY
    }

    /**
     * Outcomes whose evaluation window has not closed.
     *
     * `AccuracyRepository.pending` delegates to `OutcomeTracker.pending`, which keeps an outcome only
     * while it is unresolved **and** not projected in the future — exactly the definition of "still
     * open". These are reported separately and never averaged into a rate, because a projection
     * whose window is open is neither a hit nor a miss, and folding it in would let the app shrink
     * its own denominator.
     */
    override suspend fun unresolved(): List<PatternOutcome> = onBackground {
        accuracyRepository.pending().listOrThrow()
    }

    override suspend fun trackedSymbols(): List<String> = onBackground {
        // `AccuracyReport.perSymbol` registers a group for **every** tracked outcome, resolved or not,
        // so this is "symbols with at least one outcome" and not "symbols with a settled outcome".
        // Sorted so the coverage summary does not reshuffle between renders.
        report().perSymbol.mapNotNull { it.symbol }.distinct().sorted()
    }

    override suspend fun lastSettledOn(): LocalDate? = onBackground {
        // See the class KDoc: this is `max(projectedOn)` over the tracked outcomes, because no
        // settle-date is stored. It is the newest thing the record knows, and never later than the
        // truth.
        accuracyRepository.report().asOf
    }

    override suspend fun trackRecord(symbol: String, patternName: String): TrackRecordContext =
        onBackground {
            val result = accuracyRepository.trackRecord(symbol.trim().uppercase(), patternName)
            val failure = result.failureOrNull()
            if (failure != null) throw unavailable(failure)
            // A context is returned even when the rate is withheld, because
            // `TrackRecordContext.evidence` already distinguishes "too few cases here, here is the
            // cross-stock figure" from "too few cases anywhere" — which is the whole reason the
            // interface asks for the context and not a `HitRate`.
            result.valueOrNull() ?: TrackRecordContext(
                priorHits = 0,
                priorSamples = 0,
                hitRate = null,
                comparableOutcomes = 0,
                comparableHitRate = null,
                evidence = "No tracked cases for ${patternName.ifBlank { symbol }} on $symbol are " +
                    "available on this device, and the record layer gave no reason. Nothing is " +
                    "claimed about this formation.",
            )
        }

    override suspend fun trackedTimeframes(): List<Timeframe> = onBackground {
        // See the class KDoc: derived from the open outcomes only, because the aggregated `HitRate`s
        // carry no timeframe. `distinct` keeps first-seen order, which is `OutcomeTracker`'s own
        // deterministic order.
        accuracyRepository.pending()
            .listOrThrow()
            .map { it.timeframe }
            .distinct()
    }

    // --------------------------------------------------------------------------- internals

    private suspend fun <T> onBackground(block: suspend () -> T): T =
        withContext(backgroundDispatcher) { block() }

    /**
     * The list, or this screen's typed exception.
     *
     * `AccuracyRepository.pending` answers `Ok` for an empty list — "no projection is awaiting a
     * verdict" is a true fact — so the list is never an error here. Any failure it *did* report would
     * be a genuine one, so it throws rather than collapsing to an empty list that would read as
     * "nothing pending".
     */
    private fun DataResult<List<PatternOutcome>>.listOrThrow(): List<PatternOutcome> = try {
        valueOrEmptyList()
    } catch (raw: DataFailureException) {
        throw unavailable(raw.failure)
    }

    private fun unavailable(failure: DataFailure) = AccuracyUnavailableException(
        reason = failure.userFacingReason(),
        isTransient = failure.isWorthRetrying(),
    )
}