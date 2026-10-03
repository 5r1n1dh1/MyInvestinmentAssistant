package com.mia.app.data.android.repo

import com.mia.app.data.android.db.BhavcopyCacheEntity
import com.mia.app.data.android.db.CandleDao
import com.mia.app.data.android.db.Mappers
import com.mia.app.data.android.db.MarketDao
import com.mia.app.data.android.di.IoDispatcher
import com.mia.app.data.android.work.TradingCalendar
import com.mia.app.data.history.YahooHistorySource
import com.mia.app.data.price.BhavcopyParseResult
import com.mia.app.data.price.FetchFailure
import com.mia.app.data.price.LegacyDeliverySource
import com.mia.app.data.price.NseBhavcopySource
import com.mia.app.data.symbols.CorporateActionTracker
import com.mia.app.data.symbols.PresenceSnapshot
import com.mia.app.data.symbols.SymbolIdentity
import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.ParserHealth
import com.mia.app.domain.model.Source
import com.mia.app.domain.model.Timeframe
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The price layer on top of the write-once candle cache.
 *
 * ### Precedence, restated from plan.md §5.1
 * `price/OHLCV → NSE` (then Yahoo). The bhavcopy is the official end-of-day publication, needs
 * no auth, and one request returns the whole market; Yahoo is the fallback for the deep history
 * it alone has. So [refreshMarket] is the source of the newest bar and [backfill] only ever fills
 * in what NSE will never have.
 *
 * ### The write-once guarantee, in practice
 * Nothing in this class ever updates a stored price. [refreshMarket] and [backfill] both insert
 * with `OnConflictStrategy.IGNORE`, and the single `UPDATE` anywhere in the schema is the
 * delivery percentage — see `CandleDao.attachDelivery`.
 */
@Singleton
class DefaultCandleRepository @Inject constructor(
    private val candleDao: CandleDao,
    private val marketDao: MarketDao,
    private val bhavcopySource: NseBhavcopySource,
    private val legacyDeliverySource: LegacyDeliverySource,
    private val yahooHistorySource: YahooHistorySource,
    @IoDispatcher private val dispatcher: CoroutineDispatcher,
) : CandleRepository {

    // ------------------------------------------------------------------------------- reads

    override suspend fun storedSeries(
        symbol: String,
        timeframe: Timeframe,
        limit: Int,
    ): DataResult<CandleSeries> = withContext(dispatcher) {
        val rows = candleDao.recent(symbol, Mappers.timeframeKey(timeframe), limit)
        if (rows.isEmpty()) {
            return@withContext DataResult.unavailable(
                failure = DataFailure(
                    reason = DataFailureReason.NO_DATA_CACHED,
                    message = "No ${timeframe.name} bars for $symbol are stored on this device yet. " +
                        "Open the stock and refresh, or wait for the 19:00 IST nightly refresh " +
                        "(plan.md §10). This is not the same as 'no price data exists'.",
                    detail = "symbol=$symbol timeframe=${timeframe.name}",
                ),
                source = Source.NSE_BHAVCOPY,
                fetchedOn = todayInMarket(),
            )
        }
        val candles = rows.map { Mappers.toCandle(it) }
        val minBars = minBarsFor(timeframe)
        DataResult.ok(
            value = CandleSeries(
                symbol = symbol,
                timeframe = timeframe,
                candles = candles,
                asOf = candles.maxOf { it.date },
                barCount = candles.size,
                minBars = minBars,
                barsSufficient = candles.size >= minBars,
                sources = candles.map { it.source }.toSet(),
                notes = buildList {
                    if (candles.size < minBars) {
                        add(
                            "${candles.size} of $minBars required bars: plan.md §6.4's hard gate is " +
                                "unmet, so the app says \"not enough history\" and emits no score",
                        )
                    }
                    val withoutDelivery = candles.count { it.deliveryPercent == null }
                    if (withoutDelivery > 0) {
                        add(
                            "$withoutDelivery of ${candles.size} bar(s) carry no delivery percentage; " +
                                "plan.md §3.5's legacy file is optional, so plan.md §8.2's " +
                                "delivery-collapse flag cannot be evaluated for those bars",
                        )
                    }
                },
            ),
            source = candles.first().source,
            fetchedOn = todayInMarket(),
            asOf = candles.maxOf { it.date },
        )
    }

    override suspend fun seriesViews(symbol: String): DataResult<List<SeriesView>> =
        withContext(dispatcher) {
            val views = listOf(Timeframe.D1, Timeframe.W1).mapNotNull { timeframe ->
                val series = storedSeries(symbol, timeframe).valueOrNull() ?: return@mapNotNull null
                SeriesView(
                    symbol = symbol,
                    timeframe = timeframe,
                    series = series,
                    origin = originOf(series),
                    notes = series.notes,
                )
            }

            if (views.isEmpty()) {
                return@withContext DataResult.unavailable(
                    failure = DataFailure(
                        reason = DataFailureReason.NO_DATA_CACHED,
                        message = "Neither the daily nor the weekly series for $symbol is stored on " +
                            "this device. Both tabs must say the data is missing rather than draw an " +
                            "empty chart (plan.md §12).",
                        detail = "symbol=$symbol",
                    ),
                    source = Source.NSE_BHAVCOPY,
                    fetchedOn = todayInMarket(),
                )
            }
            DataResult.ok(
                value = views,
                source = views.first().series.sources.firstOrNull() ?: Source.NSE_BHAVCOPY,
                fetchedOn = todayInMarket(),
                asOf = views.mapNotNull { it.series.asOf }.maxOrNull(),
            )
        }

    override suspend fun latestCandle(symbol: String): DataResult<Candle> = withContext(dispatcher) {
        val rows = candleDao.recent(symbol, Mappers.timeframeKey(Timeframe.D1), 1)
        val entity = rows.firstOrNull() ?: return@withContext DataResult.unavailable(
            failure = DataFailure(
                reason = DataFailureReason.NO_DATA_CACHED,
                message = "No daily bar for $symbol is stored, so there is no latest price to show. " +
                    "This is a missing cache, not a price of zero.",
                detail = "symbol=$symbol",
            ),
            source = Source.NSE_BHAVCOPY,
            fetchedOn = todayInMarket(),
        )
        val candle = Mappers.toCandle(entity)
        DataResult.ok(
            value = candle,
            source = candle.source,
            fetchedOn = todayInMarket(),
            asOf = candle.date,
        )
    }

    override suspend fun cachedSymbols(): DataResult<List<String>> = withContext(dispatcher) {
        val symbols = candleDao.symbols()
        DataResult.ok(
            value = symbols,
            source = Source.NSE_BHAVCOPY,
            fetchedOn = todayInMarket(),
            notes = listOf("${symbols.size} instrument(s) hold at least one cached bar"),
        )
    }

    // ------------------------------------------------------------------------------ writes

    /**
     * plan.md §10: fetch → parse → replace the market cache, attaching delivery % when the
     * legacy file is available.
     *
     * Step order is load-bearing:
     * 1. **Trading-day guard.** plan.md §10 depends on the bhavcopy existing; a weekend run must
     *    not spend four requests learning that.
     * 2. **Fetch the newest session on or before [today].** `NseBhavcopySource` walks backwards
     *    over non-trading days itself and returns the date that actually succeeded — the caller
     *    must stamp *that* date, never [today].
     * 3. **Insert the bars, ignoring conflicts.** Write-once.
     * 4. **Attach delivery percentages.** The legacy file is optional (plan.md §3.5), so its
     *    failure is a note, not an error, and a symbol with no published figure keeps `null` —
     *    never `0.0`, which would manufacture plan.md §8.2's delivery-collapse flag.
     * 5. **Replace the rolling cache row.**
     * 6. **Feed the corporate-action tracker**, primed from the previous cache row.
     */
    override suspend fun refreshMarket(today: LocalDate): DataResult<MarketRefresh> =
        withContext(dispatcher) {
            if (!TradingCalendar.isPossiblyTradingDay(today)) {
                return@withContext DataResult.unavailable(
                    failure = DataFailure(
                        reason = DataFailureReason.NOT_TRADING_DAY,
                        message = TradingCalendar.skipReason(today),
                        detail = "date=$today",
                    ),
                    source = Source.NSE_BHAVCOPY,
                    fetchedOn = today,
                )
            }

            val parsed = bhavcopySource.fetchLatestOnOrBefore(today)
            val failure = parsed.toDataFailureOrNull(requestedOn = today)
            if (failure != null) {
                return@withContext DataResult.unavailable(
                    failure = failure,
                    source = Source.NSE_BHAVCOPY,
                    fetchedOn = today,
                    health = parsed.health,
                )
            }
            storeMarket(parsed, today)
        }

    override suspend fun backfill(
        symbol: String,
        timeframes: Set<Timeframe>,
    ): DataResult<Int> = withContext(dispatcher) {
        // `:data` owns the backfill rule (plan.md §3.2: more than 500 daily / 261 weekly bars
        // stored ⇒ never ask again). This asks it rather than restating it.
        val skip = timeframes.filter { timeframe ->
            val stored = candleDao.count(symbol, Mappers.timeframeKey(timeframe))
            !yahooHistorySource.needsBackfill(symbol, timeframe, stored)
        }.toSet()

        val series = yahooHistorySource.dailyAndWeekly(symbol, skip = skip)
        val usable = listOf(series.daily, series.weekly).filter { it.isUsable }
        if (usable.isEmpty()) {
            val reasons = listOf(series.daily, series.weekly).mapNotNull { it.failure }.distinct()
            return@withContext DataResult.unavailable(
                failure = DataFailure(
                    reason = DataFailureReason.SOURCE_UNAVAILABLE,
                    message = "Yahoo Finance supplied no usable history for $symbol (" +
                        "${reasons.joinToString(", ") { it.name.lowercase().replace('_', ' ') }}). " +
                        "plan.md §3.2: serve the D1 tab from the NSE bhavcopy cache instead and " +
                        "treat W1 as derived from it. " +
                        (series.daily.health.notes.firstOrNull() ?: ""),
                    detail = reasons.joinToString(", ") { it.name },
                ),
                source = Source.YAHOO,
                fetchedOn = todayInMarket(),
            )
        }

        val bars = usable.flatMap { it.candles }
        val ids = candleDao.insertIgnore(bars.map { Mappers.fromCandle(it) })
        val written = ids.count { it != -1L }

        DataResult.ok(
            value = written,
            source = Source.YAHOO,
            fetchedOn = todayInMarket(),
            asOf = usable.mapNotNull { it.asOf }.maxOrNull(),
            health = usable.first().health,
            notes = buildList {
                add("$written of ${bars.size} bar(s) written; the rest already existed (write-once)")
                add(
                    "documented shapes: plan.md §3.2 expects ${series.daily.candles.size} daily and " +
                        "${series.weekly.candles.size} weekly bar(s)",
                )
                if (Timeframe.D1 in skip) {
                    add("daily history skipped: the cache is already past the backfill threshold")
                }
                if (Timeframe.W1 in skip) {
                    add("weekly history skipped: the cache is already past the backfill threshold")
                }
                series.daily.splits.forEach { split ->
                    add(
                        "split on ${split.date}: ${split.description} — the series is UNADJUSTED, so " +
                            "levels before and after it are not comparable without the ratio " +
                            "(plan.md §3.2)",
                    )
                }
            },
        )
    }

    // --------------------------------------------------------------------------- internals

    private suspend fun storeMarket(
        parsed: BhavcopyParseResult,
        requestedOn: LocalDate,
    ): DataResult<MarketRefresh> {
        val sessionDate = parsed.tradDt ?: parsed.candles.firstOrNull()?.date
        if (sessionDate == null) {
            return DataResult.unavailable(
                failure = DataFailure(
                    reason = DataFailureReason.UNPARSEABLE,
                    message = "The bhavcopy parsed to ${parsed.candles.size} candle(s) but carried no " +
                        "session date, so nothing can be stored without inventing one " +
                        "(plan.md §1.4 rule 3). ${parsed.health.notes.firstOrNull() ?: ""}",
                    detail = "rowsTotal=${parsed.rowsTotal} rowsSkipped=${parsed.rowsSkipped}",
                ),
                source = Source.NSE_BHAVCOPY,
                fetchedOn = requestedOn,
                health = parsed.health,
            )
        }

        val previous = marketDao.cache()
        val bars = parsed.candles.map { Mappers.fromCandle(it) }
        val ids = candleDao.insertIgnore(bars)
        val written = ids.count { it != -1L }

        val deliveryAttached = attachDelivery(sessionDate, parsed)
        val identityEvents = recordIdentityEvents(previous, sessionDate, parsed)

        marketDao.replace(
            BhavcopyCacheEntity(
                date = sessionDate.toString(),
                fetchedOn = requestedOn.toString(),
                rowsTotal = parsed.rowsTotal,
                rowsKept = parsed.rowsKept,
                deliveryAttached = deliveryAttached,
                healthOk = parsed.health.ok,
                notes = parsed.health.notes.joinToString("\n"),
                presence = Mappers.encodePresence(parsed.rows),
            ),
        )

        val notes = buildList {
            addAll(parsed.health.notes)
            add(
                "$written of ${bars.size} bar(s) written; ${ids.size - written} already existed and " +
                    "were left untouched (write-once)",
            )
            if (sessionDate != requestedOn) {
                add(
                    "the file NSE served is for $sessionDate, not $requestedOn; the series is " +
                        "stamped $sessionDate so no bar is ever filed under the wrong day",
                )
            }
            if (deliveryAttached == 0) {
                add(
                    "no delivery percentage was attached: plan.md §3.5's legacy file is optional and " +
                        "its availability for recent dates is unverified, so plan.md §8.2's " +
                        "delivery-collapse flag stays unevaluated rather than false",
                )
            }
            if (identityEvents > 0) {
                add("$identityEvents identity event(s) recorded (plan.md §9 corporate actions)")
            }
        }

        return DataResult.ok(
            value = MarketRefresh(
                asOf = sessionDate,
                barsWritten = written,
                barsAlreadyKnown = ids.size - written,
                symbolsInFile = parsed.rowsKept,
                deliveryAttached = deliveryAttached,
                identityEvents = identityEvents,
                health = parsed.health,
                notes = notes,
            ),
            source = Source.NSE_BHAVCOPY,
            fetchedOn = requestedOn,
            asOf = sessionDate,
            health = parsed.health,
        )
    }

    /**
     * Attaches the legacy file's `DELIV_PER` to bars already stored for [sessionDate].
     *
     * Only the symbols this session actually kept are touched. Iterating all 3,534 legacy rows
     * would issue 3,534 statements, and the other 483 belong to series the parser filtered out
     * and therefore have no bar at all.
     */
    private suspend fun attachDelivery(
        sessionDate: LocalDate,
        parsed: BhavcopyParseResult,
    ): Int {
        val legacy = legacyDeliverySource.fetch(sessionDate)
        if (!legacy.isUsable) return 0
        var attached = 0
        for (row in parsed.rows) {
            val percent = legacy.deliveryPercentBySymbol[row.ticker] ?: continue
            attached += candleDao.attachDelivery(row.ticker, sessionDate.toString(), percent)
        }
        return attached
    }

    /**
     * Feeds today's presence to the tracker, primed from the previous cache row.
     *
     * **Known limitation, stated rather than hidden:** plan.md §5.2's cache is a *single rolling
     * row*, so the tracker's state cannot be replayed over more than two sessions. A symbol that
     * vanishes and returns after a long gap is therefore noticed on the day it returns rather
     * than after `CorporateActionTracker.DEFAULT_GAP_SESSIONS`. Making that accurate needs a
     * history of snapshots, which §5.2 does not provide; the trade is deliberate and is the
     * cheapest way to keep "a symbol vanished" visible at all.
     */
    private suspend fun recordIdentityEvents(
        previous: BhavcopyCacheEntity?,
        sessionDate: LocalDate,
        parsed: BhavcopyParseResult,
    ): Int {
        val tracker = CorporateActionTracker()
        if (previous != null) {
            tracker.record(
                PresenceSnapshot.fromIdentities(
                    date = LocalDate.parse(previous.date),
                    identities = Mappers.decodePresence(previous.presence).toIdentities(),
                    health = ParserHealth(
                        ok = previous.healthOk,
                        sectionsFound = listOf("presence"),
                        notes = previous.notes.split('\n').filter { it.isNotBlank() },
                    ),
                ),
            )
        }
        return tracker.record(
            PresenceSnapshot.fromBhavcopy(sessionDate, parsed.rows, parsed.health),
        ).size
    }

    private fun List<Triple<String, String, String>>.toIdentities(): List<SymbolIdentity> = map {
        (symbol, isin, series) ->
        SymbolIdentity(symbol = symbol, isin = isin.takeIf { it.isNotBlank() }, series = series)
    }

    /**
     * Maps a fetch failure onto the typed failure the UI renders.
     *
     * The distinction plan.md §3.1 draws matters operationally: `NOT_PUBLISHED` is the expected
     * state before ~18:00 IST and must be retried later, whereas `HOST_UNREACHABLE` is a broken
     * source and must be surfaced to the user. Neither is ever silently empty.
     */
    private fun BhavcopyParseResult.toDataFailureOrNull(requestedOn: LocalDate): DataFailure? {
        if (isUsable) return null
        val firstNote = health.notes.firstOrNull()
        return when (fetchFailure) {
            FetchFailure.NOT_PUBLISHED -> DataFailure(
                reason = DataFailureReason.NOT_PUBLISHED,
                message = "NSE has not published the bhavcopy for $requestedOn yet — it appears around " +
                    "18:00 IST (plan.md §3.1). That is an expected state before then, not a broken " +
                    "source, so the refresh will be retried.",
                detail = firstNote,
            )

            FetchFailure.HOST_UNREACHABLE -> DataFailure(
                reason = DataFailureReason.SOURCE_UNAVAILABLE,
                message = "Neither NSE bhavcopy host answered: ${firstNote ?: "no detail"}. The price " +
                    "layer is unavailable until this clears (plan.md §13).",
                detail = firstNote,
            )

            FetchFailure.UNPARSEABLE, null -> DataFailure(
                reason = DataFailureReason.UNPARSEABLE,
                message = "The bhavcopy payload could not be parsed: ${firstNote ?: "no detail"}. " +
                    "plan.md §13 names this as the total price-layer failure mode, so the raw cause is " +
                    "recorded rather than swallowed.",
                detail = "rowsTotal=$rowsTotal rowsSkipped=$rowsSkipped",
            )
        }
    }

    /** Which source a stored series came from, as the chart subtitle must state it (plan.md §1.4). */
    private fun originOf(series: CandleSeries): SeriesOrigin = when {
        series.timeframe == Timeframe.W1 && Source.YAHOO !in series.sources -> SeriesOrigin.DERIVED_WEEKLY
        Source.YAHOO in series.sources && Source.NSE_BHAVCOPY in series.sources -> SeriesOrigin.MERGED
        Source.YAHOO in series.sources -> SeriesOrigin.YAHOO
        else -> SeriesOrigin.NSE_BHAVCOPY
    }
}