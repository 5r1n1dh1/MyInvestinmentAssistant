package com.mia.app.data.android.repo

import com.mia.app.data.android.db.Mappers
import com.mia.app.data.android.db.MarketDao
import com.mia.app.data.android.db.SymbolDao
import com.mia.app.data.android.db.SymbolEntity
import com.mia.app.data.android.db.WatchlistDao
import com.mia.app.data.android.db.WatchlistEntity
import com.mia.app.data.symbols.BhavcopyReconciler
import com.mia.app.data.symbols.PresenceSnapshot
import com.mia.app.data.symbols.SymbolIdentity
import com.mia.app.data.symbols.SymbolNormaliser
import com.mia.app.data.symbols.SymbolRecord
import com.mia.app.domain.model.Source
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import com.mia.app.data.android.di.IoDispatcher

/**
 * Reads the bundled master, keeps the durable `symbols` / `watchlist` rows, and compares the two
 * identity sources.
 *
 * ### Never a blank screen
 * Every path here either produces a value or a typed [DataFailure]. The two states most likely
 * to produce a blank UI — an unbundled asset and an empty watchlist — are both values with an
 * explanation, not failures, because "nothing here" is a true answer for a watchlist while
 * "the master is missing" is a defect the user must see.
 */
@Singleton
class DefaultSymbolRepository @Inject constructor(
    private val bookProvider: SymbolBookProvider,
    private val symbolDao: SymbolDao,
    private val watchlistDao: WatchlistDao,
    private val marketDao: MarketDao,
    @IoDispatcher private val dispatcher: CoroutineDispatcher,
) : SymbolRepository {

    override suspend fun symbolBook(): DataResult<List<SymbolSummary>> = withContext(dispatcher) {
        val loaded = bookProvider.load()
        val book = loaded.book ?: return@withContext DataResult.unavailable(
            failure = DataFailure(
                reason = DataFailureReason.SYMBOL_BOOK_UNAVAILABLE,
                message = "The NSE symbol master is not available in this build, so search and " +
                    "symbol resolution cannot run. ${loaded.error ?: loaded.health.notes.firstOrNull() ?: ""}".trim(),
                detail = "asset=${com.mia.app.data.symbols.SymbolBookSource.ASSET_NAME}",
            ),
            source = SYMBOL_SOURCE,
            fetchedOn = todayInMarket(),
            health = loaded.health,
        )
        DataResult.ok(
            value = book.records.map { Mappers.toSummary(it) },
            source = SYMBOL_SOURCE,
            fetchedOn = todayInMarket(),
            health = book.health,
            notes = listOf("${book.size} instrument(s) bundled from EQUITY_L.csv"),
        )
    }

    override suspend fun search(
        query: String,
        limit: Int,
    ): DataResult<List<SymbolHit>> = withContext(dispatcher) {
        val book = bookProvider.load().book ?: return@withContext unavailableBook("search")
        if (query.isBlank() || limit <= 0) {
            // A blank box returns nothing on purpose (`:data`'s documented behaviour): returning
            // all 2,593 instruments is what makes a search box feel broken.
            return@withContext DataResult.ok(
                value = emptyList(),
                source = SYMBOL_SOURCE,
                fetchedOn = todayInMarket(),
                health = book.health,
                notes = listOf("blank query returns no hits; the full list is available via symbolBook()"),
            )
        }
        DataResult.ok(
            value = book.search(query, limit).map { hit ->
                SymbolHit(
                    summary = Mappers.toSummary(hit.record),
                    kind = SearchMatchKind.entries.firstOrNull { it.name == hit.kind.name }
                        ?: SearchMatchKind.NAME_SUBSTRING,
                    position = hit.position,
                )
            },
            source = SYMBOL_SOURCE,
            fetchedOn = todayInMarket(),
            health = book.health,
            notes = listOf("offline search over ${book.size} bundled instruments (plan.md §9)"),
        )
    }

    override suspend fun resolve(query: String): DataResult<ResolutionOutcome> = withContext(dispatcher) {
        val book = bookProvider.load().book ?: return@withContext unavailableBook("resolve")
        val resolution = book.resolve(query)
        DataResult.ok(
            value = ResolutionOutcome(
                query = resolution.query,
                status = resolution.status,
                summary = resolution.record?.let { Mappers.toSummary(it) },
                explanation = resolution.explanation,
                alternatives = resolution.alternatives.map { Mappers.toSummary(it) },
                adjusted = resolution.isAdjusted,
                corporateActionDetail = resolution.corporateAction?.detail,
            ),
            source = SYMBOL_SOURCE,
            fetchedOn = todayInMarket(),
            health = book.health,
        )
    }

    override suspend fun record(symbol: String): DataResult<SymbolSummary> = withContext(dispatcher) {
        val key = SymbolNormaliser.strictTickerKey(symbol)
        // The bundled master is authoritative; the database is the durable copy for symbols the
        // master has since dropped (a delisting must remain resolvable, plan.md §9).
        val fromBook = bookProvider.load().book?.find(key)
        val summary = fromBook?.let { Mappers.toSummary(it) }
            ?: symbolDao.bySymbol(key)?.let { Mappers.toSummary(Mappers.toRecord(it)) }
        if (summary == null) {
            return@withContext DataResult.unavailable(
                failure = DataFailure(
                    reason = DataFailureReason.NOTHING_TO_DO,
                    message = "'$key' is not a listed instrument in the bundled NSE symbol master. " +
                        "Search by company name instead — no network search is ever issued (plan.md §9).",
                    detail = "ticker=$key",
                ),
                source = SYMBOL_SOURCE,
                fetchedOn = todayInMarket(),
            )
        }
        DataResult.ok(
            value = summary,
            source = SYMBOL_SOURCE,
            fetchedOn = todayInMarket(),
        )
    }

    override suspend fun reconciliation(): DataResult<MarketReconciliation> = withContext(dispatcher) {
        val book = bookProvider.load().book ?: return@withContext unavailableBook("reconcile")
        val cache = marketDao.cache() ?: return@withContext DataResult.unavailable(
            failure = DataFailure(
                reason = DataFailureReason.NO_DATA_CACHED,
                message = "No bhavcopy has been cached yet, so the symbol master cannot be compared " +
                    "against a session. The nightly refresh at 19:00 IST populates it (plan.md §10).",
            ),
            source = Source.NSE_BHAVCOPY,
            fetchedOn = todayInMarket(),
        )

        val asOf = LocalDate.parse(cache.date)
        val identities = Mappers.decodePresence(cache.presence).map { (symbol, isin, series) ->
            SymbolIdentity(symbol = symbol, isin = isin.takeIf { it.isNotBlank() }, series = series)
        }
        val health = com.mia.app.domain.model.ParserHealth(
            ok = cache.healthOk,
            sectionsFound = listOf("presence"),
            sectionsMissing = emptyList(),
            notes = cache.notes.split('\n').filter { it.isNotBlank() },
        )
        val report = BhavcopyReconciler.reconcile(
            book = book,
            snapshot = PresenceSnapshot.fromIdentities(asOf, identities, health),
        )

        DataResult.ok(
            value = MarketReconciliation(
                asOf = report.asOf,
                masterCount = report.masterCount,
                bhavcopyCount = report.bhavcopyCount,
                matchedCount = report.matchedCount,
                changedIdentityCount = report.changedIdentityCount,
                headline = report.headline(),
                isClean = report.isClean,
                bhavcopyHealth = report.health,
            ),
            source = Source.NSE_BHAVCOPY,
            fetchedOn = LocalDate.parse(cache.date),
            asOf = report.asOf,
            health = report.health,
        )
    }

    // ------------------------------------------------------------------------------ watchlist

    override suspend fun watchlist(): DataResult<List<WatchlistEntry>> = withContext(dispatcher) {
        val rows = watchlistDao.all()
        val entries = rows.map { row -> row.toEntry(symbolDao.byIsin(row.isin)) }
        DataResult.ok(
            value = entries,
            source = SYMBOL_SOURCE,
            fetchedOn = todayInMarket(),
            notes = if (entries.isEmpty()) {
                listOf("the watchlist is empty, so the nightly scan has nothing to do (decision OQ-4)")
            } else {
                listOf("${entries.size} tracked instrument(s)")
            },
        )
    }

    override suspend fun addToWatchlist(symbols: List<String>): DataResult<List<WatchlistEntry>> =
        withContext(dispatcher) {
            val book = bookProvider.load().book ?: return@withContext unavailableBook("watchlist")
            val added = todayInMarket()
            val resolved = symbols.mapNotNull { ticker ->
                book.find(SymbolNormaliser.strictTickerKey(ticker))
            }
            if (resolved.isEmpty()) {
                return@withContext DataResult.unavailable(
                    failure = DataFailure(
                        reason = DataFailureReason.NOTHING_TO_DO,
                        message = "None of ${symbols.size} requested ticker(s) is in the bundled NSE " +
                            "symbol master, so nothing was added to the watchlist.",
                    ),
                    source = SYMBOL_SOURCE,
                    fetchedOn = todayInMarket(),
                )
            }
            // Identity first, then the watchlist row: `symbols` is the FK target of nothing
            // here, but writing the durable identity before the pointer is what makes a later
            // rename resolvable (plan.md §9).
            symbolDao.insertIgnore(resolved.map { Mappers.fromRecord(it) })
            watchlistDao.insertIgnore(
                resolved.map { record ->
                    WatchlistEntity(
                        isin = SymbolNormaliser.isinKey(record.isin),
                        symbol = record.symbol,
                        addedOn = added.toString(),
                        alertsEnabled = true,
                    )
                },
            )
            val entries = watchlistDao.all().map { row -> row.toEntry(symbolDao.byIsin(row.isin)) }
            DataResult.ok(
                value = entries,
                source = SYMBOL_SOURCE,
                fetchedOn = added,
                notes = listOf(
                    "added ${resolved.size} of ${symbols.size} requested ticker(s); " +
                        "${symbols.size - resolved.size} were not in the master",
                ),
            )
        }

    override suspend fun removeFromWatchlist(symbols: List<String>): DataResult<Int> =
        withContext(dispatcher) {
            var removed = 0
            for (ticker in symbols) {
                val key = SymbolNormaliser.strictTickerKey(ticker)
                val isin = symbolDao.bySymbol(key)?.isin ?: bookProvider.load().book?.find(key)?.isin
                if (isin != null) removed += watchlistDao.delete(SymbolNormaliser.isinKey(isin))
            }
            DataResult.ok(
                value = removed,
                source = SYMBOL_SOURCE,
                fetchedOn = todayInMarket(),
                notes = listOf("removed $removed of ${symbols.size} requested ticker(s)"),
            )
        }

    override suspend fun setAlertsEnabled(
        symbol: String,
        enabled: Boolean,
    ): DataResult<WatchlistEntry?> = withContext(dispatcher) {
        val key = SymbolNormaliser.strictTickerKey(symbol)
        val isin = symbolDao.bySymbol(key)?.isin
            ?: bookProvider.load().book?.find(key)?.isin
            ?: return@withContext DataResult.unavailable(
                failure = DataFailure(
                    reason = DataFailureReason.NOTHING_TO_DO,
                    message = "'$key' is not on the watchlist, so its alert setting could not be changed.",
                ),
                source = SYMBOL_SOURCE,
                fetchedOn = todayInMarket(),
            )
        watchlistDao.setAlertsEnabled(SymbolNormaliser.isinKey(isin), enabled)
        val row = watchlistDao.all().firstOrNull { it.isin == SymbolNormaliser.isinKey(isin) }
        DataResult.ok(
            value = row?.toEntry(symbolDao.byIsin(row.isin)),
            source = SYMBOL_SOURCE,
            fetchedOn = todayInMarket(),
        )
    }

    // --------------------------------------------------------------------------- internals

    private fun WatchlistEntity.toEntry(entity: SymbolEntity?): WatchlistEntry {
        val summary = entity?.let { Mappers.toSummary(Mappers.toRecord(it)) } ?: SymbolSummary(
            symbol = symbol,
            name = symbol,
            isin = isin,
            series = "EQ",
            listingDate = null,
        )
        return WatchlistEntry(
            summary = summary,
            addedOn = LocalDate.parse(addedOn),
            alertsEnabled = alertsEnabled,
        )
    }

    private fun <T : Any> unavailableBook(operation: String): DataResult<T> = DataResult.unavailable(
        failure = DataFailure(
            reason = DataFailureReason.SYMBOL_BOOK_UNAVAILABLE,
            message = "The NSE symbol master is not bundled in this build, so $operation cannot run. " +
                "Search and symbol resolution are entirely offline (plan.md §9), so there is no " +
                "fallback source to degrade to.",
            detail = "asset=${com.mia.app.data.symbols.SymbolBookSource.ASSET_NAME}",
        ),
        source = SYMBOL_SOURCE,
        fetchedOn = todayInMarket(),
    )
}

/** Local projection of `:data`'s `SymbolRecord`; kept here so `:app` never imports `:data`. */
internal fun Mappers.toSummary(record: SymbolRecord): SymbolSummary = SymbolSummary(
    symbol = record.symbol,
    name = record.name,
    isin = record.isin,
    series = record.series,
    listingDate = record.listingDate,
)