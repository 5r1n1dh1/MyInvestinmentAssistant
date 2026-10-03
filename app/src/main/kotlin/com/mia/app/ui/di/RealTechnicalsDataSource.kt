package com.mia.app.ui.di

import com.mia.app.data.android.repo.AccuracyRepository
import com.mia.app.data.android.repo.CandleRepository
import com.mia.app.data.android.repo.CandleSeries
import com.mia.app.data.android.repo.DataFailure
import com.mia.app.data.android.repo.DataResult
import com.mia.app.data.android.repo.PatternRepository
import com.mia.app.data.android.repo.RedFlagRepository
import com.mia.app.domain.model.BreakoutTrigger
import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.FlagTab
import com.mia.app.domain.model.HitRate
import com.mia.app.domain.model.Match
import com.mia.app.domain.model.MatchScorePolicy
import com.mia.app.domain.model.MeasuredMove
import com.mia.app.domain.model.ProjectedPath
import com.mia.app.domain.model.Timeframe
import com.mia.app.domain.pattern.BreakoutEvaluator
import com.mia.app.domain.pattern.PatternEngine
import com.mia.app.domain.pattern.ProjectionBuilder
import com.mia.app.domain.technical.IndicatorSnapshot
import com.mia.app.ui.technicals.TechnicalsDataSource
import com.mia.app.ui.technicals.TechnicalsRedFlags
import com.mia.app.ui.technicals.TechnicalsUnavailableException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [TechnicalsDataSource] over `:android-data`.
 *
 * ### What it reads
 * | call | repository / engine |
 * |---|---|
 * | [candles] | `CandleRepository.storedSeries` |
 * | [matches] | `PatternRepository.scan` — which runs `PatternEngine` **and persists** the detections |
 * | [redFlags] | `RedFlagRepository.technicalFlags` |
 * | [projectedPath] | `ProjectionBuilder.directionalPath`, behind `MatchScorePolicy`'s `PROJECT` gate |
 * | [breakoutTrigger] | `BreakoutEvaluator.trigger` |
 * | [trackRecord] | `AccuracyRepository.trackRecord` / `.report` |
 *
 * ### Why the match list comes from the repository rather than a local engine call
 * `PatternRepository.scan` is the only call that **persists** a detection, and a stored detection is
 * what `AccuracyRepository.recordProjections` later turns into a track-record row — decision D6's
 * *"only patterns the user actually saw may enter the record"*. Running `PatternEngine` here would
 * produce the same list and record nothing, so the app's own hit rate would stay empty forever on
 * a device whose user only ever opened this tab.
 *
 * The Patterns tab runs the engine locally instead (`RealPatternsDataSource`), for the opposite
 * reason: its interface needs `PatternScanResult` and it is about symbols the nightly scan has not
 * visited.
 *
 * ### Threading
 * Every method is `suspend` and assumes the caller already dispatched —
 * `TechnicalsViewModel` wraps the entire load in `withContext(TechnicalsBackgroundDispatcher)`,
 * which is one thread hop rather than six. [projectedPath] does real CPU work (an
 * `IndicatorSnapshot` pass for the regime gate, then an ATR pass inside [ProjectionBuilder]), so it
 * must never be called from the main thread.
 *
 * ### The failure policy, restated for this tab
 * * no stored bars, or below plan.md §6.4's gate → **empty**, never a throw. The tab's own
 *   `hasEnoughHistory = false` renders "not enough history" with the counts, which is the honest
 *   answer and the state the screen was written for.
 * * [DataFailure.isNotPublishedYet] → thrown with `isNotPublished = true`, so the tab keeps the
 *   last good session on screen and adds a note instead of a red banner.
 * * anything else → thrown, and the tab renders `ErrorState` with a retry.
 */
@Singleton
class RealTechnicalsDataSource @Inject constructor(
    private val candleRepository: CandleRepository,
    private val patternRepository: PatternRepository,
    private val redFlagRepository: RedFlagRepository,
    private val accuracyRepository: AccuracyRepository,
) : TechnicalsDataSource {

    /**
     * Stale-only freshness check. See [TechnicalsDataSource.ensureFresh] for why it exists.
     *
     * The result is deliberately discarded: this method exists for its side effect (populating the
     * cache), and its typed failure is already represented by the [candles] call that follows. A
     * failure here therefore returns normally and the tab's empty state names the real reason,
     * rather than collapsing "the network is down" and "no rows for this ticker" into one banner.
     */
    override suspend fun ensureFresh(symbol: String, timeframe: Timeframe) {
        candleRepository.ensureData(symbol)
    }

    override suspend fun candles(symbol: String, timeframe: Timeframe): List<Candle> =
        candleRepository.storedSeries(symbol, timeframe).barsOrEmpty()

    /**
     * Series-level notes, passed through verbatim.
     *
     * Read with the same tolerant accessor as [candles]: a series that could not be read at all has
     * no notes either, and the tab already renders its absence for that case — so an honest absence
     * yields an empty list rather than a second, competing error.
     */
    override suspend fun seriesNotes(symbol: String, timeframe: Timeframe): List<String> =
        candleRepository.storedSeries(symbol, timeframe).valueOrNull()?.notes.orEmpty()

    override suspend fun redFlags(symbol: String, timeframe: Timeframe): TechnicalsRedFlags {
        val view = redFlagRepository.technicalFlags(symbol, timeframe).orThrowTyped()
        return TechnicalsRedFlags(
            // `RedFlagView.forTab` is the tab filter: a fundamentals-only flag belongs to the other
            // tab, and printing it here would double-count it against this tab's banner.
            flags = view.forTab(FlagTab.TECHNICALS),
            skipped = view.skipped,
            failures = view.failureReasons,
        )
    }

    override suspend fun matches(symbol: String, timeframe: Timeframe): List<Match> {
        val result = patternRepository.scan(symbol, timeframe)
        val failure = result.failureOrNull()
        if (failure != null) {
            // `NO_DATA_CACHED` (nothing stored) and `NOT_ENOUGH_HISTORY` (below plan.md §6.4's gate)
            // are both *stated refusals* rather than failed reads, and the tab renders them as "not
            // enough history" with the counts. Every other reason is a real failure: collapsing one of
            // those into an empty list would render a broken read as "this stock has no formations",
            // which is plan.md §12's most dangerous failure.
            if (failure.isNothingStored()) return emptyList()
            throw unavailable(failure)
        }
        return result.valueOrNull()?.matches.orEmpty()
    }

    override suspend fun projectedPath(
        symbol: String,
        timeframe: Timeframe,
        match: Match,
    ): ProjectedPath? {
        val gates = projectionGates(symbol, timeframe, match) ?: return null
        return ProjectionBuilder.directionalPath(
            candles = gates.bars,
            direction = gates.move.direction,
            timeframe = timeframe,
        )
    }

    override suspend fun breakoutTrigger(
        symbol: String,
        timeframe: Timeframe,
        match: Match,
    ): BreakoutTrigger? {
        // A trigger is an *event*, not a forecast, so it is deliberately **not** gated by the regime
        // or the score. `PatternScanWorker` reads the trigger for any stored detection and applies
        // `MatchScorePolicy.PROJECT_THRESHOLD` itself before raising an alert; gating it here would
        // hide a condition the engine is willing to describe and would make the tab and the
        // notification disagree about the same formation.
        val move = match.measuredMove ?: return null
        val bars = candleRepository.storedSeries(symbol, timeframe).barsOrEmpty()
        if (bars.isEmpty()) return null
        return BreakoutEvaluator.trigger(
            candles = bars,
            level = move.triggerLevel,
            direction = move.direction,
            subject = TRIGGER_SUBJECT,
            timeframe = timeframe,
        )
    }

    override suspend fun trackRecord(symbol: String, patternName: String?): HitRate? =
        TrackRecordReader(accuracyRepository, ::unavailable).hitRate(symbol, patternName)

    // --------------------------------------------------------------------------- internals

    /**
     * The bars plus the verdict that decides whether a projection may be drawn at all.
     *
     * plan.md §6.4 makes `PROJECT` a hard condition: `score >= 70` **and** an aligned regime. Both
     * halves are read from the shared policy rather than restated, so this tab, the patterns screen
     * and the alert dispatcher cannot disagree about which match may project.
     *
     * `null` means "nothing to draw": no measured move (a candlestick has no height to project,
     * spec DD-1), no stored bars, or a verdict below `PROJECT`.
     */
    private suspend fun projectionGates(
        symbol: String,
        timeframe: Timeframe,
        match: Match,
    ): ProjectionGates? {
        val move = match.measuredMove ?: return null
        val bars = candleRepository.storedSeries(symbol, timeframe).barsOrEmpty()
        if (bars.isEmpty()) return null
        val regime = IndicatorSnapshot.of(bars).trendRegime
        val aligned = PatternEngine.regimeAligned(regime, move.direction, match.category)
        val verdict = MatchScorePolicy.verdict(match.score, timeframe, bars.size, aligned)
        if (verdict != MatchScorePolicy.Verdict.PROJECT) return null
        return ProjectionGates(bars = bars, move = move)
    }

    private data class ProjectionGates(val bars: List<Candle>, val move: MeasuredMove)

    /**
     * Stored bars, ascending by date — or nothing.
     *
     * `CandleSeries.ordered()` is the documented accessor and it is what every indicator expects,
     * so the tab's list is always ascending: the chart indexes it and `Match.pivots` address bars
     * by absolute index. An honest absence — `NOT_ATTEMPTED`, `NO_DATA_CACHED`,
     * `NOT_ENOUGH_HISTORY`, `NO_ROWS_FOR_SYMBOL` — becomes an empty list; every other failure throws
     * this tab's typed exception, so an unreachable source can never be mistaken for an empty chart.
     *
     * The four honest absences are deliberately kept apart by
     * [DefaultCandleRepository.storedSeries] and by `DataFailure.isNothingStored`, because they
     * call for different sentences on screen and only two of them are fixable by pressing a button.
     */
    private fun DataResult<CandleSeries>.barsOrEmpty(): List<Candle> = try {
        orNullWhenNothingStored()?.ordered().orEmpty()
    } catch (raw: DataFailureException) {
        throw unavailable(raw.failure)
    }

    /** The value, or this tab's typed exception carrying the failure's own words. */
    private fun <T : Any> DataResult<T>.orThrowTyped(): T =
        valueOrNull() ?: throw unavailable(failureOrNull()!!)

    /**
     * This tab's typed exception, built from a typed failure.
     *
     * [TechnicalsUnavailableException.isAbsence] is what separates "nothing to draw, and here is
     * why" from "the read failed". An honest absence renders as an explained empty state — with a
     * fetch button only when one can help — while a real failure renders as `ErrorState`. Collapsing
     * the two is what made a fresh install look broken: an empty table was reported as an error.
     */
    private fun unavailable(failure: DataFailure) = TechnicalsUnavailableException(
        reason = failure.userFacingReason(),
        isTransient = failure.isWorthRetrying(),
        isNotPublished = failure.isNotPublishedYet(),
        isAbsence = failure.isNothingStored(),
    )

    private companion object {
        /**
         * What the trigger's level is called in plain words.
         *
         * `BreakoutEvaluator` takes a subject so the description reads as English — "Close above the
         * pattern boundary" rather than "Close above 810". One neutral subject serves every family
         * because the detector-specific boundary name (neckline, confirmation level, channel
         * boundary) lives in the detector, not in the stored row; the level itself, which is the
         * part that must be exact, is what is stored. The same string is used by
         * `DefaultPatternRepository.projectionOf`, so this tab and the nightly worker word it
         * identically.
         */
        const val TRIGGER_SUBJECT: String = "pattern boundary"
    }
}