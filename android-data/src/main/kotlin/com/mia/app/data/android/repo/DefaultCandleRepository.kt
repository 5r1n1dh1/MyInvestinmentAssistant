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
import com.mia.app.domain.technical.CandleResampler
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
        val ticker = symbol.trim().uppercase()
        val rows = candleDao.recent(ticker, Mappers.timeframeKey(timeframe), limit)
        if (rows.isEmpty()) {
            // Three empty states that call for three different sentences, and collapsing them into
            // one "no data" is what made the first version of this tab look broken when it was
            // merely empty. The distinction matters because only two of the three are fixable by
            // the reader pressing a button.
            //
            // `candleDao.symbols()` is checked first and short-circuits: a full table means an
            // older symbol's bars are present, so an empty result for *this* symbol is a fact
            // about the symbol rather than about the device.
            return@withContext when {
                // Nothing on this device at all → offer the fetch.
                candleDao.symbols().isEmpty() -> DataResult.unavailable(
                    failure = DataFailure(
                        reason = DataFailureReason.NOT_ATTEMPTED,
                        message = "No market data has been downloaded on this device yet, so there " +
                            "is nothing to show for $ticker. This is not a missing price — the " +
                            "bhavcopy has never been fetched. Use Refresh, or open the stock and let " +
                            "it check for a newer session.",
                        detail = "symbol=$ticker timeframe=${timeframe.name} candlesTable=empty",
                    ),
                    source = Source.NSE_BHAVCOPY,
                    fetchedOn = todayInMarket(),
                )

                // The table is populated but this symbol is absent from it → retrying cannot help.
                candleDao.count(ticker, Mappers.timeframeKey(Timeframe.D1)) == 0 &&
                    candleDao.count(ticker, Mappers.timeframeKey(Timeframe.W1)) == 0 -> DataResult.unavailable(
                    failure = DataFailure(
                        reason = DataFailureReason.NO_ROWS_FOR_SYMBOL,
                        message = "Market data is stored on this device, but the published files " +
                            "contain no rows for $ticker. That usually means the symbol is not in " +
                            "the EQ/SM series the app keeps (plan.md §3.1), or it was delisted or " +
                            "renamed. Refreshing again will not change the answer — try resolving " +
                            "the ticker again, which reports corporate actions (plan.md §9).",
                        detail = "symbol=$ticker timeframe=${timeframe.name}",
                    ),
                    source = Source.NSE_BHAVCOPY,
                    fetchedOn = todayInMarket(),
                )

                else -> DataResult.unavailable(
                    failure = DataFailure(
                        reason = DataFailureReason.NOT_ENOUGH_HISTORY,
                        message = "No ${timeframe.name} bars are stored for $ticker yet, though " +
                            "daily data exists. The weekly series is derived from daily bars " +
                            "(plan.md §4.1 supports 1D and 1W only), so it appears once the daily " +
                            "history has been deepened.",
                        detail = "symbol=$ticker timeframe=${timeframe.name}",
                    ),
                    source = Source.NSE_BHAVCOPY,
                    fetchedOn = todayInMarket(),
                )
            }
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

    override suspend fun ensureData(
        symbol: String,
        today: LocalDate,
    ): EnsureResult = withContext(dispatcher) {
        val ticker = symbol.trim().uppercase()
        val stored = candleDao.recent(ticker, Mappers.timeframeKey(Timeframe.D1), 1)
        val newest = stored.firstOrNull()?.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val storedCount = candleDao.count(ticker, Mappers.timeframeKey(Timeframe.D1))

        // The one date the cache must cover to count as current. Before publication, today is not
        // an expected session yet, so the bar for *today* must not be demanded.
        val expectedThrough = expectedLatestSession(today)

        if (newest != null && expectedThrough != null && !newest.isBefore(expectedThrough)) {
            return@withContext EnsureResult.fresh(
                symbol = ticker,
                asOf = newest,
                barCount = storedCount,
                expectedThrough = expectedThrough,
            )
        }

        val before = storedCount
        val parsed = bhavcopySource.fetchLatestOnOrBefore(today)
        val failure = parsed.toDataFailureOrNull(requestedOn = today)
        if (failure != null) {
            // Deliberately not a throw and not a wipe: a network problem must never turn a
            // populated tab into an empty one.
            return@withContext EnsureResult.failed(
                symbol = ticker,
                asOf = newest,
                barCount = storedCount,
                failure = failure,
            )
        }

        val result = storeMarket(parsed, today)
        val after = candleDao.count(ticker, Mappers.timeframeKey(Timeframe.D1))
        val asOf = candleDao.recent(ticker, Mappers.timeframeKey(Timeframe.D1), 1)
            .firstOrNull()?.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

        // The weekly tab has no writer of its own — the bhavcopy publishes daily bars only — so W1
        // is aggregated here from the D1 rows we just ensured. Without this the 1W tab is
        // permanently empty no matter how much daily data arrives, and it would report "no bars"
        // for a symbol that plainly has them.
        val weeklyAdded = deriveWeeklyBars(ticker)

        val sessionNote = result.valueOrNull()?.let {
            "whole-market bhavcopy for ${it.asOf} stored: ${it.barsWritten} bar(s) written, " +
                "${it.barsAlreadyKnown} already known"
        } ?: "the bhavcopy parsed to ${parsed.candles.size} candle(s) but stored nothing new"

        val coverage = when {
            after >= CandleRepository.MIN_DAILY_BARS ->
                "the daily series now clears plan.md §6.4's 60-bar gate ($after bar(s))"
            else -> "the daily series is still short of plan.md §6.4's 60-bar gate " +
                "($after bar(s)); the 19:00 IST worker adds only one session a day, so use " +
                "Settings → Refresh history to walk the NSE archive"
        }

        EnsureResult.fetched(
            symbol = ticker,
            asOf = asOf,
            barsAdded = (after - before).coerceAtLeast(0) + weeklyAdded,
            note = "$sessionNote; this symbol gained " +
                "${(after - before).coerceAtLeast(0)} daily and $weeklyAdded derived weekly bar(s) " +
                "($before → $after daily); $coverage",
        )
    }

    /**
     * Aggregates stored D1 bars into stored W1 bars for one symbol.
     *
     * ### Why the weekly rows are written rather than derived per read
     * `CandleResampler.toWeekly` is pure and cheap, so deriving on read would be possible — but the
     * weekly series is then *invisible* to `storedSeries(W1)`, to the accuracy table and to the
     * settings coverage count, all of which read the table. Persisting the aggregate keeps one
     * source of truth, and the write-once guarantee means it can never disagree with the daily bars
     * it came from except by gaining another week.
     *
     * @return how many weekly bars were newly written.
     */
    private suspend fun deriveWeeklyBars(symbol: String): Int {
        val daily = candleDao.all(symbol, Mappers.timeframeKey(Timeframe.D1))
            .map { Mappers.toCandle(it) }
        if (daily.size < MIN_WEEKLY_SOURCE_BARS) return 0

        val weekly = CandleResampler.toWeekly(daily)
        if (weekly.isEmpty()) return 0

        // A weekly bar's key is its **last session's** date, which is the date the resampler stamps
        // (see `CandleResampler.toWeekly`'s KDoc). Re-deriving from the same daily rows therefore
        // produces byte-identical keys, so `INSERT OR IGNORE` makes this a no-op once a week has been
        // stored — which is why the method can be called on every fetch without accumulating rows.
        val ids = candleDao.insertIgnore(weekly.map { Mappers.fromCandle(it) })
        return ids.count { it != -1L }
    }

    /**
     * The newest session the cache must contain for the app to call itself current.
     *
     * Before the bhavcopy is published (~18:00 IST, plan.md §3.1) today's file simply does not
     * exist, so demanding it would report a healthy cache as stale and trigger a pointless fetch
     * on every stock open all afternoon. Returns `null` only in the narrow window where publication
     * status is genuinely unknowable from the calendar alone.
     */
    private fun expectedLatestSession(today: LocalDate): LocalDate? {
        if (!TradingCalendar.isPossiblyTradingDay(today)) {
            return TradingCalendar.previousPossiblyTradingDay(today)
        }
        return today
    }

    override suspend fun backfillHistory(
        sessions: Int,
        today: LocalDate,
    ): DataResult<HistoryBackfill> = withContext(dispatcher) {
        // No trading-day guard here, deliberately. `refreshMarket` needs one because it wants *today's*
        // file; this walk wants *older* files, and the archive publishes every weekday regardless of
        // what today is. Guarding on the trading calendar would make a Saturday the one day a reader
        // cannot fetch history — exactly backwards.
        val wanted = sessions.coerceAtMost(CandleRepository.HISTORY_BATCH_LIMIT.coerceAtLeast(1))

        var fetched = 0
        var skipped = 0
        var written = 0
        var known = 0
        val notes = mutableListOf<String>()
        var cursor = today

        while (fetched < wanted) {
            if (!TradingCalendar.isPossiblyTradingDay(cursor)) {
                cursor = cursor.minusDays(1)
                skipped++
                if (skipped > MAX_SKIPPED_SESSIONS) {
                    notes += "stopped after skipping $skipped consecutive non-trading days; the " +
                        "archive is not answering for anything older"
                    break
                }
                continue
            }

            val sessionKey = cursor.toString()
            // Skip without a request when this whole market's session is already stored. `forDate`
            // is the honest test: `bhavcopy_cache` is a *single* rolling row, so it only ever knows
            // the newest session and cannot answer this for older ones.
            if (candleDao.forDate(sessionKey).isNotEmpty()) {
                skipped++
                cursor = cursor.minusDays(1)
                continue
            }

            val parsed = bhavcopySource.fetch(cursor)
            if (parsed.candles.isEmpty()) {
                // Weekend, holiday, or purged by the ~18-month retention. Either way there is
                // nothing to store and no reason to treat it as an error.
                skipped++
                cursor = cursor.minusDays(1)
                if (skipped > MAX_SKIPPED_SESSIONS) {
                    notes += "stopped after skipping $skipped consecutive days with no published " +
                        "file; plan.md §3.1 measures roughly 18 months of retention, so this is " +
                        "the archive's edge rather than a fault"
                    break
                }
                continue
            }

            val added = storeHistorySession(parsed)
            if (added == null) {
                notes += "the bhavcopy for $sessionKey parsed but stored nothing: " +
                    "no session date could be read from it"
                skipped++
            } else {
                fetched++
                written += added
            }
            cursor = cursor.minusDays(1)
        }

        val earliest = candleDao.earliestDate(Mappers.timeframeKey(Timeframe.D1))
        val latest = candleDao.latestDate(Mappers.timeframeKey(Timeframe.D1))

        if (fetched == 0 && written == 0) {
            return@withContext DataResult.unavailable(
                failure = DataFailure(
                    reason = DataFailureReason.HISTORY_UNAVAILABLE,
                    message = "No further history could be downloaded. " +
                        (notes.firstOrNull() ?: "Every requested session was already stored.") +
                        " Any bars already on this device are unaffected and are still being served.",
                    detail = "requested=$wanted fetched=$fetched skipped=$skipped",
                ),
                source = Source.NSE_BHAVCOPY,
                fetchedOn = today,
            )
        }

        DataResult.ok(
            value = HistoryBackfill(
                requestedSessions = wanted,
                sessionsFetched = fetched,
                sessionsSkipped = skipped,
                barsWritten = written,
                barsAlreadyKnown = known,
                earliestStored = earliest?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
                latestStored = latest?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
                notes = notes,
            ),
            source = Source.NSE_BHAVCOPY,
            fetchedOn = today,
            asOf = latest?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
        )
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

    /**
     * Stores one historical session and **nothing else**.
     *
     * ### Why this is not [storeMarket]
     * A history walk calls it once per session, and `storeMarket` does three things that are
     * correct for the newest session and wrong sixty times over:
     *
     * 1. **[attachDelivery]** issues a second network request to plan.md §3.5's legacy file —
     *    doubling a 60-session pass to 120 requests for a figure the plan marks optional.
     * 2. **[marketDao.replace]** rewrites the *rolling* cache row. Walking backwards would leave
     *    that row holding the **oldest** session fetched, so "the market cache date" would start
     *    reporting a date months stale.
     * 3. **[recordIdentityEvents]** diffs consecutive sessions. Walking backwards makes every pair
     *    look like a mass disappearance and reappearance, manufacturing hundreds of fake corporate
     *    actions (plan.md §9).
     *
     * So this writes bars and only bars. Delivery %, the rolling cache and identity events belong
     * to the single-session path; plan.md §9's corporate-action detection is a same-day concern
     * anyway.
     *
     * @return how many bars were newly written, or `null` when the file carried no session date.
     */
    private suspend fun storeHistorySession(parsed: BhavcopyParseResult): Int? {
        val sessionDate = parsed.tradDt ?: parsed.candles.firstOrNull()?.date ?: return null
        if (sessionDate.toString() !in parsed.candles.map { it.date.toString() }) return null
        val ids = candleDao.insertIgnore(parsed.candles.map { Mappers.fromCandle(it) })
        return ids.count { it != -1L }
    }

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

    private companion object {
        /**
         * How many consecutive unfetchable days end a history walk.
         *
         * Generous on purpose. A real NSE holiday run (Diwali, Holi) is 3–4 sessions, so a bound of
         * 5 survives one without mistaking it for the archive's edge. Past that, the walk is
         * burning requests against purged or unavailable data, and plan.md §3.1's measured ~18
         * months of retention means the tail is mostly 404s by design.
         */
        const val MAX_SKIPPED_SESSIONS: Int = 12

        /**
         * Daily bars required before a weekly aggregate is worth writing.
         *
         * Below this the derived series is too short to be anything but noise — and
         * plan.md §6.4's own 1W gate is 40, which needs ~280 daily bars, so a symbol with fewer
         * than ~60 daily bars cannot reach a publishable weekly series no matter how many times we
         * derive it.
         */
        const val MIN_WEEKLY_SOURCE_BARS: Int = 20
    }
}