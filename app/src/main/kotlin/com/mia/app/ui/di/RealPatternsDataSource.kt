package com.mia.app.ui.di

import com.mia.app.data.android.repo.AccuracyRepository
import com.mia.app.data.android.repo.CandleRepository
import com.mia.app.data.android.repo.CandleSeries
import com.mia.app.data.android.repo.DataFailure
import com.mia.app.data.android.repo.DataResult
import com.mia.app.data.android.repo.DetectorRegistry
import com.mia.app.data.android.repo.RedFlagRepository
import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.FlagTab
import com.mia.app.domain.model.HitRate
import com.mia.app.domain.model.Match
import com.mia.app.domain.model.MatchScorePolicy
import com.mia.app.domain.model.Timeframe
import com.mia.app.domain.pattern.BreakoutEvaluator
import com.mia.app.domain.pattern.PatternEngine
import com.mia.app.domain.pattern.PatternScanOptions
import com.mia.app.domain.pattern.PatternScanResult
import com.mia.app.domain.pattern.PivotSet
import com.mia.app.domain.pattern.ProjectionBuilder
import com.mia.app.domain.redflag.RuleSkip
import com.mia.app.domain.technical.IndicatorSnapshot
import com.mia.app.ui.patterns.PatternsDataSource
import com.mia.app.ui.patterns.PatternsProjection
import com.mia.app.ui.patterns.PatternsRedFlags
import com.mia.app.ui.patterns.PatternsUnavailableException
import com.mia.app.work.MiaBackgroundDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [PatternsDataSource] over `:android-data`, running the engine through [DetectorRegistry].
 *
 * ### Why the engine runs here rather than through `PatternRepository.scan`
 * `PatternRepository.scan` returns `PatternScan`, a *projection* of the engine's result: it keeps
 * `List<Pivot>` and drops the `PivotSet`'s parameters and per-pivot ATR, and it refuses to scan at all
 * below plan.md §6.4's gate (`NOT_ENOUGH_HISTORY`) instead of returning the engine's own
 * `insufficientHistory = true` result.
 *
 * [PatternsDataSource.scan] is required to return `PatternScanResult` **verbatim** — the interface says
 * so, and says it is because that type already carries the three things the screen must render
 * honestly. Re-shaping `PatternScan` into it would create a second place for the gate to drift and
 * would lose the engine's own "not enough history, here are the counts" answer. So the engine is
 * called directly with `:android-data`'s [DetectorRegistry], exactly as `DefaultPatternRepository.scan`
 * does, and the result is handed back untouched.
 *
 * ### The consequence, stated rather than hidden
 * A scan from this tab is **not persisted**, so a symbol the reader opens on demand does not enter
 * the app's track record from here. That is the correct division of labour: persistence plus
 * `AccuracyRepository.recordProjections` belongs to `PatternScanWorker`, which walks the **tracked
 * list** (decision OQ-4) and is the only thing allowed to grow the record. The Technicals tab, which
 * a reader reaches by tracking a stock, does go through `PatternRepository.scan` so its detections do
 * persist. See `RealTechnicalsDataSource` for that side of the trade.
 *
 * ### Threading
 * This is the most expensive computation in the app on a budget device — a zig-zag pass, a
 * fifteen-indicator snapshot and twelve detectors over one shared pivot list (plan.md §6.2) — and it
 * runs entirely off the main thread. `PatternsViewModel` wraps the whole load in
 * `withContext(MiaBackgroundDispatcher)`, so there is one hop rather than one per call, and
 * [projections] runs inside that same context.
 *
 * ### `benchmarkCandles = null`, and why that is not a zero
 * No index series is loaded on device, so the relative-strength CONTEXT measure has no input.
 * `null` means "no benchmark" to the engine, which unscores that factor and records it in
 * `PatternScanResult.notes`; `scoring-spec.md` §6 X4 forbids substituting a strength of zero, and this
 * is the substitution that is being avoided.
 */
@Singleton
class RealPatternsDataSource @Inject constructor(
    private val candleRepository: CandleRepository,
    private val detectorRegistry: DetectorRegistry,
    private val redFlagRepository: RedFlagRepository,
    private val accuracyRepository: AccuracyRepository,
    @MiaBackgroundDispatcher private val backgroundDispatcher: CoroutineDispatcher,
) : PatternsDataSource {

    /**
     * Stale-only freshness check. See [PatternsDataSource.ensureFresh] for why it exists.
     *
     * The result is discarded on purpose: a failed fetch returns normally so the following [candles]
     * call names the real reason, instead of collapsing "the network is down" and "no rows for this
     * ticker" into a single banner.
     */
    override suspend fun ensureFresh(symbol: String, timeframe: Timeframe) {
        withContext(backgroundDispatcher) {
            candleRepository.ensureData(symbol.trim().uppercase())
        }
    }

    override suspend fun candles(symbol: String, timeframe: Timeframe): List<Candle> =
        withContext(backgroundDispatcher) {
            candleRepository.storedSeries(symbol, timeframe).barsOrEmpty()
        }

    override suspend fun scan(symbol: String, timeframe: Timeframe): PatternScanResult =
        withContext(backgroundDispatcher) {
            val ticker = symbol.trim().uppercase()
            val bars = candleRepository.storedSeries(ticker, timeframe).barsOrEmpty()
            if (bars.isEmpty()) {
                // Nothing stored is not a failure, and it is answered explicitly rather than by handing
                // an empty series to the engine: the pipeline needs at least one bar before it can
                // produce a pivot list, and the honest answer is a stated refusal, not a crash and not
                // a silent empty result. Every field below is what `PatternEngine.run` produces for
                // zero bars, and `MatchScorePolicy.minBars` is read from the shared policy rather than
                // restated.
                return@withContext PatternScanResult(
                    matches = emptyList(),
                    suppressed = emptyList(),
                    verdict = MatchScorePolicy.Verdict.NOT_ENOUGH_HISTORY,
                    insufficientHistory = true,
                    minBarsRequired = MatchScorePolicy.minBars(timeframe),
                    barCount = 0,
                    pivots = PivotSet.EMPTY,
                    notes = listOf(
                        "No $timeframe bars for $ticker are stored on this device, so no pattern can " +
                            "be detected and no score is emitted. Refresh the stock, or wait for the " +
                            "nightly scan (plan.md §10).",
                    ),
                )
            }
            PatternEngine.run(
                candles = bars,
                timeframe = timeframe,
                // A fresh detector list per scan, which is what `DetectorRegistry.all()` documents:
                // no detector instance can be held across two scans.
                detectors = detectorRegistry.all(),
                options = PatternScanOptions(
                    symbol = ticker,
                    benchmarkCandles = null,
                    collectDiagnostics = false,
                ),
            )
        }

    override suspend fun projections(
        symbol: String,
        timeframe: Timeframe,
        match: Match,
    ): PatternsProjection = withContext(backgroundDispatcher) {
        val move = match.measuredMove
        if (move == null) {
            // A candlestick formation has no height to project and no boundary to break (spec DD-1),
            // so all three outputs are legitimately absent and the row renders the pattern alone.
            return@withContext PatternsProjection.NONE
        }
        val bars = candleRepository.storedSeries(symbol, timeframe).barsOrEmpty()
        if (bars.isEmpty()) return@withContext PatternsProjection.NONE

        val regime = IndicatorSnapshot.of(bars).trendRegime
        val aligned = PatternEngine.regimeAligned(regime, move.direction, match.category)
        val verdict = MatchScorePolicy.verdict(match.score, timeframe, bars.size, aligned)

        PatternsProjection(
            measuredMove = move,
            // decision D2: the path is drawn **only** for a match the engine would have projected —
            // `PROJECT` is `score >= 70` **and** an aligned regime, and both halves are read from the
            // shared policy rather than restated here.
            path = if (verdict == MatchScorePolicy.Verdict.PROJECT) {
                ProjectionBuilder.directionalPath(
                    candles = bars,
                    direction = move.direction,
                    timeframe = timeframe,
                )
            } else {
                null
            },
            // The trigger is an event, not a forecast, so it is **not** gated. `PatternScanWorker`
            // applies `MatchScorePolicy.PROJECT_THRESHOLD` itself before raising a notification, and
            // gating here would make the tab and the alert disagree about the same formation.
            trigger = BreakoutEvaluator.trigger(
                candles = bars,
                level = move.triggerLevel,
                direction = move.direction,
                subject = TRIGGER_SUBJECT,
                timeframe = timeframe,
            ),
        )
    }

    override suspend fun trackRecord(symbol: String, patternName: String?): HitRate? =
        withContext(backgroundDispatcher) {
            TrackRecordReader(accuracyRepository, ::unavailable).hitRate(symbol, patternName)
        }

    override suspend fun redFlags(symbol: String, timeframe: Timeframe): PatternsRedFlags =
        withContext(backgroundDispatcher) {
            val result = redFlagRepository.technicalFlags(symbol, timeframe)
            val failure = result.failureOrNull()
            if (failure != null) {
                if (failure.isNothingStored()) {
                    // Never an empty flag list: that renders as "no red flags". A synthetic skip puts
                    // the reason on screen as "Not evaluated", which is the honest state for a symbol
                    // whose bars have not been fetched.
                    return@withContext PatternsRedFlags(
                        skipped = listOf(
                            RuleSkip(
                                ruleId = TECHNICAL_RULE_GROUP,
                                reason = "No $symbol $timeframe price series is stored on this device, " +
                                    "so none of the technical checks could be evaluated. " +
                                    failure.userFacingReason(),
                            ),
                        ),
                    )
                }
                throw unavailable(failure)
            }
            val view = result.valueOrNull() ?: return@withContext PatternsRedFlags.EMPTY
            PatternsRedFlags(
                flags = view.forTab(FlagTab.TECHNICALS),
                skipped = view.skipped,
                failures = view.failureReasons,
            )
        }

    // --------------------------------------------------------------------------- internals

    /**
     * Stored bars, ascending by date — or nothing.
     *
     * `CandleRepository.DEFAULT_SERIES_LIMIT` (500) is the documented window plan.md §6.2 feeds the
     * pattern engine, so the scan and the chart see exactly the same bars and a `Match.pivots` bar
     * index addresses the same bar on both surfaces. Ascending order is `CandleSeries.ordered()`'s
     * contract and the chart's assumption.
     *
     * A `NO_DATA_CACHED` result is an honest absence and becomes an empty list. Any other failure is
     * translated into this tab's typed exception here rather than escaping as a storage-layer
     * throwable, so the screen's `isNotPublished` handling applies to every read on this surface.
     */
    private fun DataResult<CandleSeries>.barsOrEmpty(): List<Candle> = try {
        orNullWhenNothingStored()?.ordered().orEmpty()
    } catch (raw: DataFailureException) {
        throw unavailable(raw.failure)
    }

    private fun unavailable(failure: DataFailure) = PatternsUnavailableException(
        reason = failure.userFacingReason(),
        isTransient = failure.isWorthRetrying(),
        isNotPublished = failure.isNotPublishedYet(),
    )

    private companion object {
        /** See `RealTechnicalsDataSource`'s `TRIGGER_SUBJECT`; identical wording on purpose. */
        const val TRIGGER_SUBJECT: String = "pattern boundary"

        /** A group id, not a rule id — see the same reasoning in the fundamentals adapter. */
        const val TECHNICAL_RULE_GROUP: String = "technical-checks"
    }
}