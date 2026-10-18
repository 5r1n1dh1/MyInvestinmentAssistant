package com.mia.app.data.android.repo

import com.mia.app.data.android.db.CandleDao
import com.mia.app.data.android.db.Mappers
import com.mia.app.data.android.db.PatternDao
import com.mia.app.data.android.db.SymbolDao
import com.mia.app.data.android.di.IoDispatcher
import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.Match
import com.mia.app.domain.model.Source
import com.mia.app.domain.model.Timeframe
import com.mia.app.domain.pattern.BreakoutEvaluator
import com.mia.app.domain.pattern.PatternEngine
import com.mia.app.domain.pattern.PatternScanOptions
import com.mia.app.domain.pattern.ProjectionBuilder
import com.mia.app.domain.pattern.ProjectionGate
import com.mia.app.domain.technical.IndicatorSnapshot
import com.mia.app.domain.technical.TrendRegime
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
    private val indexRepository: IndexRepository,
    private val decisionCounterRepository: DecisionCounterRepository,
    @IoDispatcher private val dispatcher: CoroutineDispatcher,
) : PatternRepository {

    override suspend fun scan(
        symbol: String,
        timeframe: Timeframe,
    ): DataResult<PatternScan> = withContext(dispatcher) {
        val ticker = symbol.trim().uppercase()
        val rows = candleDao.all(ticker, Mappers.timeframeKey(timeframe))
        if (rows.isEmpty()) {
            // Same three-way distinction as `DefaultCandleRepository.storedSeries`, because the two
            // are read together on the same screen and disagreeing reasons would be worse than one
            // vague reason: this tab must not say "refresh" for a ticker that has no published rows.
            return@withContext when {
                candleDao.symbols().isEmpty() -> DataResult.unavailable(
                    failure = DataFailure(
                        reason = DataFailureReason.NOT_ATTEMPTED,
                        message = "No market data has been downloaded on this device yet, so no " +
                            "pattern can be detected for $ticker. Detection needs at least " +
                            "${minBarsFor(timeframe)} ${timeframe.name} bars — use Refresh, or " +
                            "Settings → Refresh history to fetch a history deep enough to score.",
                        detail = "symbol=$ticker timeframe=${timeframe.name} candlesTable=empty",
                    ),
                    source = Source.NSE_BHAVCOPY,
                    fetchedOn = todayInMarket(),
                )

                candleDao.count(ticker, Mappers.timeframeKey(Timeframe.D1)) == 0 &&
                    candleDao.count(ticker, Mappers.timeframeKey(Timeframe.W1)) == 0 -> DataResult.unavailable(
                    failure = DataFailure(
                        reason = DataFailureReason.NO_ROWS_FOR_SYMBOL,
                        message = "Market data is stored on this device, but the published files " +
                            "contain no rows for $ticker, so no pattern can be detected. That usually " +
                            "means it is not in the EQ/SM series the app keeps (plan.md §3.1), or it " +
                            "was delisted or renamed — refreshing again will not change it.",
                        detail = "symbol=$ticker timeframe=${timeframe.name}",
                    ),
                    source = Source.NSE_BHAVCOPY,
                    fetchedOn = todayInMarket(),
                )

                else -> DataResult.unavailable(
                    failure = DataFailure(
                        reason = DataFailureReason.NO_DATA_CACHED,
                        message = "No ${timeframe.name} bars are stored for $ticker yet, though daily " +
                            "data exists. The weekly series is derived from daily bars, so it appears " +
                            "once the daily history has been deepened.",
                        detail = "symbol=$ticker timeframe=${timeframe.name}",
                    ),
                    source = Source.NSE_BHAVCOPY,
                    fetchedOn = todayInMarket(),
                )
            }
        }
        val candles = rows.map { Mappers.toCandle(it) }.sortedBy { it.date }
        val minBars = minBarsFor(timeframe)

        if (candles.size < minBars) {
            return@withContext DataResult.unavailable(
                failure = DataFailure(
                    reason = DataFailureReason.NOT_ENOUGH_HISTORY,
                    message = "$ticker has ${candles.size} ${timeframe.name} bar(s); plan.md §6.4's hard " +
                        "gate is $minBars. The app says \"not enough history\" and emits no pattern " +
                        "score from this series — a low score from too few bars would be noise. " +
                        "The 19:00 IST refresh adds only one session a day, so use Settings → " +
                        "Refresh history to walk the NSE archive; that pass is resumable.",
                    detail = "symbol=$ticker bars=${candles.size} required=$minBars",
                ),
                source = Source.NSE_BHAVCOPY,
                fetchedOn = todayInMarket(),
                asOf = candles.last().date,
            )
        }

        // The benchmark, read once per scan from the on-device `index_candles` cache.
        //
        // `null` here is a **real, expected** state — it is what the device reports before the
        // first index fetch, and forever on a device whose Yahoo requests all fail. The engine
        // treats it as "no benchmark": it unscores the Context factor's relative-strength
        // component and writes "No index benchmark — relative strength unscored" into its notes,
        // which this repository passes straight through to `PatternScan.notes`. That honesty is
        // the reason the read is wrapped in `runCatching` and why an empty list is never
        // substituted: an empty `benchmarkCandles` would reach the same code path while meaning
        // something different entirely (a benchmark exists but shares no dates), and would
        // unscore the factor with no stated reason at all.
        val benchmark = runCatching { indexRepository.benchmark() }
            .getOrNull()
            ?.valueOrNull()
            ?.asBenchmarkCandles()

        // The indicator snapshot is built **here** and handed to the engine through its injection
        // seam, rather than being built twice. Two reasons, and the second is the one that matters:
        // the snapshot carries `trendRegime`, which is half of the projection gate, and a stored
        // row's `verdict` column has to be written from the same regime the engine judged on — not
        // from a `null` that could only ever have produced `RENDER`.
        //
        // Built with the same benchmark the engine would have used, so handing it over changes
        // nothing about the scan and only saves the second fused pass.
        val snapshot = IndicatorSnapshot.of(candles, benchmark)

        val result = PatternEngine.run(
            candles = candles,
            timeframe = timeframe,
            detectors = detectors.all(),
            options = PatternScanOptions(
                symbol = symbol,
                benchmarkCandles = benchmark,
                collectDiagnostics = false,
                snapshotFactory = { _, _ -> snapshot },
            ),
        )

        val persisted = persist(
            symbol = symbol,
            timeframe = timeframe,
            matches = result.matches,
            barCount = result.barCount,
            engineNotes = result.notes,
            lastClose = candles.last().close,
            regime = snapshot.trendRegime,
        )

        val counterNotes = recordGateDecisions(
            matches = result.matches,
            timeframe = timeframe,
            lastClose = candles.last().close,
            barCount = result.barCount,
            regime = snapshot.trendRegime,
        )

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
                notes = result.notes + persisted.notes + counterNotes,
            ),
            source = candles.last().source,
            fetchedOn = todayInMarket(),
            asOf = candles.last().date,
            notes = result.notes + persisted.notes + counterNotes,
        )
    }

    /**
     * Tells the on-device decision counter what the gate decided for this scan — gap F3.
     *
     * ### Why this is the recording point and nowhere else
     * [projectionOf]'s own KDoc already states that a scan is the only place the
     * four things `ProjectionGate` needs still exist at once — the breakout close, the bar count,
     * the match's own measured move and the regime the scan resolved. A counter hooked anywhere else
     * would either re-derive the rule (a second copy of it, which is exactly how the score gate and
     * the distance gate drifted apart in the first place) or record a decision that was never made.
     *
     * ### What is counted, and why it is not everything
     * One evaluation per **kept** match, which is one per candidate the engine had a verdict about.
     * Candidates the engine suppressed below the render threshold are deliberately not counted:
     * they never became a projection decision, and counting them would put geometry the reader was
     * never shown into the denominator of a rate the reader is being asked to trust. The screen
     * states this population in words rather than leaving the sample quietly undefined.
     *
     * The scan itself is recorded once as well, and that is what makes "0 fired" legible: with a
     * scan counter, zero admissions can mean *the gate refused everything* or *the gate was never
     * asked*, which are different bugs and look identical as one number.
     *
     * ### Why a failure here does not fail the scan
     * `runCatching` with a note rather than a throw. The counter is observability, not the product:
     * a scan is a reader's request for an analysis of a stock, and failing it because a diagnostic
     * table could not be written would trade a working feature for a working dashboard. The failure
     * is reported *in the scan's own notes*, which is exactly where a reader debugging a missing
     * projection will look — and it is also where a silent failure would otherwise have been
     * invisible, which is the bug this whole workstream exists to remove.
     */
    private suspend fun recordGateDecisions(
        matches: List<Match>,
        timeframe: Timeframe,
        lastClose: Double,
        barCount: Int,
        regime: TrendRegime,
    ): List<String> = runCatching {
        val today = todayInMarket()
        decisionCounterRepository.recordScan(today)
        matches.forEach { match ->
            // The same alignment resolution the engine applied inside `verdictFor`, so the tally
            // records the decision that was actually taken rather than a re-derivation of it.
            val aligned = PatternEngine.regimeAligned(
                regime = regime,
                direction = PatternEngine.resolveDirection(match),
                category = match.category,
            )
            decisionCounterRepository.record(
                blocker = ProjectionGate.blocker(
                    match = match,
                    lastClose = lastClose,
                    timeframe = timeframe,
                    barCount = barCount,
                    regimeAligned = aligned,
                ),
                today = today,
            )
        }
    }.fold(
        onSuccess = { emptyList() },
        onFailure = { failure ->
            listOf(
                "The on-device decision counter could not be updated for this scan, so the counts " +
                    "in Settings are behind what has actually happened: ${failure.message ?: "the " +
                        "write failed"}. Nothing about the analysis itself is affected.",
            )
        },
    )

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
        // One indicator pass for the whole symbol, not one per stored detection: the regime gate
        // asks the same question of the same series for every row, and twelve stored rows would
        // otherwise mean twelve identical passes over 500 bars. It is the same snapshot the engine
        // builds for the scan, at the same cost as the scan's own.
        val regime = candles.takeIf { it.isNotEmpty() }?.let { IndicatorSnapshot.of(it).trendRegime }
        val lastClose = candles.lastOrNull()?.close ?: 0.0
        DataResult.ok(
            value = rows.flatMap { row ->
                val match = Mappers.toMatch(row, patternDao.pivots(row.id))
                buildList {
                    add(projectionOf(symbol, match, candles, timeframe, lastClose, regime))
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
     *
     * ### Why the gate inputs are passed in
     * The `verdict` column used to be written with `regimeAligned = null`, which could only ever
     * produce `RENDER` — the stored row therefore recorded, permanently, that no detection was
     * ever projectable, whatever the geometry said. It is the same class of bug as the retired
     * `PROJECT_THRESHOLD`, one layer down and invisible because nothing reads the column. The
     * verdict is now the one `PatternEngine.verdictFor` would give, with the breakout close and the
     * regime the scan itself used.
     */
    private suspend fun persist(
        symbol: String,
        timeframe: Timeframe,
        matches: List<Match>,
        barCount: Int,
        engineNotes: List<String>,
        lastClose: Double,
        regime: TrendRegime,
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
                verdict = PatternEngine.verdictFor(
                    match = match,
                    timeframe = timeframe,
                    barCount = barCount,
                    lastClose = lastClose,
                    regime = regime,
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
     *
     * ### Why the gate inputs are captured here and not downstream
     * This is the **only** place the four things `ProjectionGate` needs still exist: [candles] is
     * in hand, so the breakout close and the bar count are free, and the regime is one indicator
     * pass over the same bars. Everything past this function — `PatternScanWorker`, the alert
     * dispatcher, the accuracy pass — sees a `PatternProjection` and never the series again.
     *
     * The regime passed in is the one the **newest stored bars** produce, which for the nightly
     * scan is the same bar the detection was made on (`scan` and `projections` read the same
     * series in the same run). For a row read back later it is the current regime rather than the
     * one at detection, which is the reading a reader would use to judge the gate anyway.
     *
     * `regimeAligned` is resolved through `PatternEngine`'s own direction resolution, so a stored
     * row and a freshly detected one cannot disagree about what direction the formation runs in.
     * It is not invented: with no bars stored there is no regime, `null` is passed, and the gate
     * declines to project.
     */
    private fun projectionOf(
        symbol: String,
        match: Match,
        candles: List<Candle>,
        timeframe: Timeframe,
        lastClose: Double,
        regime: TrendRegime?,
    ): PatternProjection {
        val move = match.measuredMove
        val direction = move?.direction
        val regimeAligned = regime?.let { resolved ->
            PatternEngine.regimeAligned(
                regime = resolved,
                direction = PatternEngine.resolveDirection(match),
                category = match.category,
            )
        }
        val blocker = ProjectionGate.blocker(
            match = match,
            lastClose = lastClose,
            // The projection's own timeframe, not the caller's: the gate below and the one
            // `PatternProjection` re-asks downstream must be the same question.
            timeframe = match.timeframe,
            barCount = candles.size,
            regimeAligned = regimeAligned,
        )
        // The path is withheld with everything else the gate withholds, so nothing downstream can
        // draw a projection the app decided against. The **trigger** is not: it is an event, not a
        // forecast, and the nightly worker asks the gate itself before it raises a notification —
        // hiding the condition here would make the tab and the alert disagree about one formation.
        val path = if (move == null || candles.isEmpty() || blocker != null) {
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
            lastClose = lastClose,
            barCount = candles.size,
            regimeAligned = regimeAligned,
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