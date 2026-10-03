package com.mia.app.data.android.repo

import com.mia.app.data.android.db.CandleDao
import com.mia.app.data.android.db.Mappers
import com.mia.app.data.android.db.PatternDao
import com.mia.app.data.android.db.SymbolDao
import com.mia.app.data.android.di.IoDispatcher
import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.Match
import com.mia.app.domain.model.MatchScorePolicy
import com.mia.app.domain.model.Source
import com.mia.app.domain.model.Timeframe
import com.mia.app.domain.pattern.BreakoutEvaluator
import com.mia.app.domain.pattern.PatternEngine
import com.mia.app.domain.pattern.PatternScanOptions
import com.mia.app.domain.pattern.ProjectionBuilder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Detects patterns on the stored series and persists the result with its pivots.
 *
 * ### The one heavy pass, shared
 * plan.md §6.2 computes `ZigzagPivots` and the `IndicatorSnapshot` **once** per
 * `(symbol, timeframe)` and hands them to all twelve detectors. [PatternEngine.run] does that, and
 * this class's only job is to feed it a sorted, single-symbol series and to store what comes back.
 *
 * ### Nothing here computes a score
 * `Match.score` is always `ScoreBreakdown.total` produced by `FactorScorer`, because the
 * detectors emit weighted *measures* and never a score themselves (docs/CONTRACTS.md). This class
 * cannot change a score; it can only refuse to persist one.
 */
@Singleton
class DefaultPatternRepository @Inject constructor(
    private val candleDao: CandleDao,
    private val patternDao: PatternDao,
    private val symbolDao: SymbolDao,
    private val detectors: DetectorRegistry,
    @IoDispatcher private val dispatcher: CoroutineDispatcher,
) : PatternRepository {

    override suspend fun scan(
        symbol: String,
        timeframe: Timeframe,
    ): DataResult<PatternScan> = withContext(dispatcher) {
        val rows = candleDao.all(symbol, Mappers.timeframeKey(timeframe))
        if (rows.isEmpty()) {
            return@withContext DataResult.unavailable(
                failure = DataFailure(
                    reason = DataFailureReason.NO_DATA_CACHED,
                    message = "No ${timeframe.name} bars for $symbol are stored, so no pattern can be " +
                        "detected. Refresh the stock or wait for the nightly refresh (plan.md §10).",
                    detail = "symbol=$symbol timeframe=${timeframe.name}",
                ),
                source = Source.NSE_BHAVCOPY,
                fetchedOn = todayInMarket(),
            )
        }
        val candles = rows.map { Mappers.toCandle(it) }.sortedBy { it.date }
        val minBars = minBarsFor(timeframe)

        if (candles.size < minBars) {
            return@withContext DataResult.unavailable(
                failure = DataFailure(
                    reason = DataFailureReason.NOT_ENOUGH_HISTORY,
                    message = "$symbol has ${candles.size} ${timeframe.name} bar(s); plan.md §6.4's hard " +
                        "gate is $minBars. The app says \"not enough history\" and emits no pattern " +
                        "score from this series — a low score from too few bars would be noise.",
                    detail = "symbol=$symbol bars=${candles.size} required=$minBars",
                ),
                source = Source.NSE_BHAVCOPY,
                fetchedOn = todayInMarket(),
                asOf = candles.last().date,
            )
        }

        val result = PatternEngine.run(
            candles = candles,
            timeframe = timeframe,
            detectors = detectors.all(),
            options = PatternScanOptions(
                symbol = symbol,
                // No index benchmark is loaded on device. `null` must read as "no benchmark",
                // never as a relative strength of zero (scoring-spec.md §6, X4), which is what
                // the engine's CONTEXT notes state.
                benchmarkCandles = null,
                collectDiagnostics = false,
            ),
        )

        val persisted = persist(symbol, timeframe, result.matches, result.barCount, result.notes)

        DataResult.ok(
            value = PatternScan(
                symbol = symbol,
                timeframe = timeframe,
                matches = result.matches,
                suppressed = result.suppressed,
                verdict = result.verdict,
                barCount = result.barCount,
                minBarsRequired = result.minBarsRequired,
                insufficientHistory = result.insufficientHistory,
                pivots = result.pivots.pivots,
                notes = result.notes + persisted.notes,
            ),
            source = candles.last().source,
            fetchedOn = todayInMarket(),
            asOf = candles.last().date,
            notes = result.notes + persisted.notes,
        )
    }

    override suspend fun storedMatches(
        symbol: String,
        timeframe: Timeframe,
    ): DataResult<List<PatternProjection>> = withContext(dispatcher) {
        val rows = patternDao.matches(symbol, Mappers.timeframeKey(timeframe))
        if (rows.isEmpty()) {
            return@withContext DataResult.unavailable(
                failure = DataFailure(
                    reason = DataFailureReason.NO_DATA_CACHED,
                    message = "No pattern has been detected for $symbol on ${timeframe.name} yet. " +
                        "Run a scan, or wait for the nightly watchlist scan (plan.md §10).",
                    detail = "symbol=$symbol timeframe=${timeframe.name}",
                ),
                source = Source.DERIVED,
                fetchedOn = todayInMarket(),
            )
        }
        val candles = candlesOrEmpty(symbol, timeframe)
        DataResult.ok(
            value = rows.flatMap { row ->
                val match = Mappers.toMatch(row, patternDao.pivots(row.id))
                buildList {
                    add(projectionOf(symbol, match, candles, timeframe))
                    // A re-run can legitimately produce several rows for one day — the star and
                    // flag families emit both directions — so every stored row is projected.
                }
            },
            source = Source.DERIVED,
            fetchedOn = todayInMarket(),
            asOf = rows.firstOrNull()?.detectedOn?.let { LocalDate.parse(it) },
            notes = listOf("${rows.size} stored detection(s) for $symbol on ${timeframe.name}"),
        )
    }

    override suspend fun projections(
        symbol: String,
        timeframe: Timeframe,
    ): DataResult<List<PatternProjection>> = withContext(dispatcher) {
        storedMatches(symbol, timeframe)
    }

    override suspend fun storedCount(): Int = withContext(dispatcher) {
        patternDao.patternCount()
    }

    // --------------------------------------------------------------------------- internals

    /**
     * Writes each kept match plus its pivots.
     *
     * The pivots go in as rows, not as a blob — plan.md §5.2 states this is what lets a stored
     * detection be **re-scored** if the scorer changes, without re-detecting the formation.
     *
     * A missing identity row is a note, not an error: `patterns.symbol` carries a real foreign key
     * (plan.md §5.2's "FK(symbol)"), and failing a user-visible scan because a ticker is not in
     * the bundled master would be worse than showing the detection without storing it.
     */
    private suspend fun persist(
        symbol: String,
        timeframe: Timeframe,
        matches: List<Match>,
        barCount: Int,
        engineNotes: List<String>,
    ): PersistOutcome {
        val identity = symbolDao.bySymbol(symbol)
        if (identity == null) {
            return PersistOutcome(
                stored = 0,
                notes = listOf(
                    "detections were not stored: no identity row for $symbol, and patterns.symbol " +
                        "is a foreign key to symbols(symbol) (plan.md §5.2)",
                ),
            )
        }
        var stored = 0
        val notes = mutableListOf<String>()
        val today = todayInMarket()
        for (match in matches) {
            val entity = Mappers.toPattern(
                symbol = symbol,
                match = match,
                verdict = MatchScorePolicy.verdict(
                    score = match.score,
                    timeframe = timeframe,
                    barCount = barCount,
                    regimeAligned = null,
                ),
                barCount = barCount,
                notes = engineNotes.joinToString("\n"),
            )
            val pivots = match.pivots.mapIndexed { index, pivot ->
                Mappers.fromPivot(patternRowId = 0L, index = index, pivot = pivot)
            }
            patternDao.storeMatch(identity, entity, pivots)
            stored++
        }
        if (matches.isNotEmpty()) {
            notes += "$stored detection(s) stored for $symbol on ${timeframe.name} as of $today, " +
                "with their pivots normalised so a re-score needs no re-detection (plan.md §5.2)"
        }
        return PersistOutcome(stored = stored, notes = notes)
    }

    /**
     * The three projection outputs of plan.md §6.6, kept apart (decision D2).
     *
     * * The **measured move** is read from storage — it was computed once, by the book, at
     *   detection time, and must not be recomputed from a possibly-different series.
     * * The **directional path** is 1–5 bars of `ATR14` and carries no level.
     * * The **breakout trigger** is a close-based condition evaluated against the newest bar.
     *
     * A candlestick formation has none of them (spec DD-1): no height to project and no boundary
     * to break, so all three are null and the UI renders the pattern alone.
     */
    private fun projectionOf(
        symbol: String,
        match: Match,
        candles: List<Candle>,
        timeframe: Timeframe,
    ): PatternProjection {
        val move = match.measuredMove
        val direction = move?.direction
        val path = if (move == null || candles.isEmpty()) {
            null
        } else {
            ProjectionBuilder.directionalPath(
                candles = candles,
                direction = direction!!,
                timeframe = timeframe,
            )
        }
        val trigger = if (move == null || candles.isEmpty()) {
            null
        } else {
            BreakoutEvaluator.trigger(
                candles = candles,
                level = move.triggerLevel,
                direction = direction!!,
                subject = TRIGGER_SUBJECT,
                timeframe = timeframe,
            )
        }
        return PatternProjection(
            symbol = symbol,
            patternId = match.patternId,
            patternName = match.name,
            timeframe = match.timeframe,
            category = match.category,
            score = match.score,
            detectedOn = match.detectedOn,
            measuredMove = move,
            path = path,
            trigger = trigger,
        )
    }

    private suspend fun candlesOrEmpty(symbol: String, timeframe: Timeframe): List<Candle> =
        candleDao.all(symbol, Mappers.timeframeKey(timeframe))
            .map { Mappers.toCandle(it) }
            .sortedBy { it.date }

    private data class PersistOutcome(val stored: Int, val notes: List<String>)

    private companion object {
        /**
         * What the trigger's level is called in plain words.
         *
         * `BreakoutEvaluator` takes a subject so the description reads as English — "Close above
         * the neckline" rather than "Close above 810". One neutral subject is used for every
         * family here because the detector-specific boundary name (neckline, confirmation level,
         * channel boundary) lives in the detector, not in the stored row; the level itself, which
         * is the part that must be exact, is stored.
         */
        const val TRIGGER_SUBJECT = "pattern boundary"
    }
}