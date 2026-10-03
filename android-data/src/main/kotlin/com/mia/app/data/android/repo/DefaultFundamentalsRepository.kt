package com.mia.app.data.android.repo

import com.mia.app.data.android.db.FundamentalsDao
import com.mia.app.data.android.db.Mappers
import com.mia.app.data.android.db.SymbolDao
import com.mia.app.data.android.di.IoDispatcher
import com.mia.app.data.fundamentals.FundamentalsParseResult
import com.mia.app.data.fundamentals.ScreenerInSource
import com.mia.app.domain.model.FundamentalsSnapshot
import com.mia.app.domain.model.ParserHealth
import com.mia.app.domain.model.Ratios
import com.mia.app.domain.model.Source
import com.mia.app.domain.ratios.FreshnessKind
import com.mia.app.domain.ratios.FreshnessPolicy
import com.mia.app.domain.ratios.withFreshness
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The screener.in page, cached whole and dated.
 *
 * ### Why a failed parse is never written
 * plan.md §10: *"Fundamentals can lag a quarter. A stale P/E shown as live is the fastest way to
 * lose trust."* The cure for staleness is a **label**, not a deletion. So when a fetch or parse
 * fails, the previous page is left exactly as it was and the failure is returned for the UI to
 * render next to the stale figures. Overwriting good data with nothing would make the tab empty
 * rather than old, and an empty tab cannot be aged.
 *
 * ### What is persisted
 * Ratios, quarters, the four annual statement tables (normalised, one row per label/year) and the
 * derived cash-flow metrics. That is the whole page. It is persisted whole rather than "the
 * ratios" because plan.md §8.3 requires a flag to show the numbers behind it, and those numbers
 * live in the statements.
 */
@Singleton
class DefaultFundamentalsRepository @Inject constructor(
    private val screenerInSource: ScreenerInSource,
    private val fundamentalsDao: FundamentalsDao,
    private val symbolDao: SymbolDao,
    @IoDispatcher private val dispatcher: CoroutineDispatcher,
) : FundamentalsRepository {

    override suspend fun snapshot(
        symbol: String,
        maxAgeDays: Long,
        allowFetch: Boolean,
    ): DataResult<FundamentalsView> = withContext(dispatcher) {
        val cached = read(symbol)
        if (cached != null) {
            val age = cached.asOf?.let { ChronoUnit.DAYS.between(it, todayInMarket()) } ?: Long.MAX_VALUE
            if (age <= maxAgeDays) return@withContext cached
        }
        if (!allowFetch) {
            return@withContext cached ?: noCache(symbol)
        }
        // Stale cache in hand and a fetch was allowed: try to refresh, and fall back to the
        // labelled-stale page rather than to nothing if the refresh fails.
        val refreshed = refresh(symbol)
        if (refreshed.isOk) return@withContext refreshed
        cached ?: refreshed
    }

    override suspend fun refresh(symbol: String): DataResult<FundamentalsView> =
        withContext(dispatcher) {
            val asOf = todayInMarket()
            val parsed = screenerInSource.fetch(symbol, asOf)

            if (!parsed.isOk) {
                // No write. See the class KDoc.
                return@withContext DataResult.unavailable(
                    failure = DataFailure(
                        reason = when (parsed.httpCode) {
                            404, 410 -> DataFailureReason.NO_DATA_CACHED
                            else -> DataFailureReason.SOURCE_UNAVAILABLE
                        },
                        message = parsed.health.notes.firstOrNull()
                            ?: "screener.in returned no usable page for $symbol.",
                        detail = "httpCode=${parsed.httpCode} attempts=${parsed.attempts}",
                    ),
                    source = Source.SCREENER_IN,
                    fetchedOn = asOf,
                    asOf = parsed.snapshot.ratios.asOf,
                    health = parsed.health,
                )
            }

            write(symbol, parsed)
            read(symbol) ?: DataResult.unavailable(
                failure = DataFailure(
                    reason = DataFailureReason.UNEXPECTED,
                    message = "The screener.in page for $symbol parsed cleanly but could not be read " +
                        "back from the cache, which is a defect in this module rather than a source " +
                        "problem. ${parsed.health.notes.firstOrNull() ?: ""}",
                ),
                source = Source.SCREENER_IN,
                fetchedOn = asOf,
                asOf = parsed.snapshot.ratios.asOf,
                health = parsed.health,
            )
        }

    override suspend fun ratios(symbol: String): DataResult<Ratios> = withContext(dispatcher) {
        val view = read(symbol)?.valueOrNull() ?: return@withContext noCache(symbol)
        DataResult.ok(
            value = view.ratios,
            source = view.ratios.source,
            fetchedOn = view.ratios.asOf ?: todayInMarket(),
            asOf = view.ratios.asOf,
            health = view.snapshot.health,
            notes = listOf(view.stalenessLine),
        )
    }

    override suspend fun asOf(symbol: String): LocalDate? = withContext(dispatcher) {
        fundamentalsDao.latestRatios(symbol)?.let { LocalDate.parse(it.asOf) }
    }

    override suspend fun hasCache(symbol: String): Boolean = withContext(dispatcher) {
        fundamentalsDao.latestRatios(symbol) != null
    }

    // --------------------------------------------------------------------------- internals

    /** Rebuilds the view from storage, or `null` when nothing is cached for [symbol]. */
    private suspend fun read(symbol: String): DataResult<FundamentalsView>? {
        val ratiosRow = fundamentalsDao.latestRatios(symbol) ?: return null
        val ratios = Mappers.toRatios(ratiosRow)
        val asOf = ratios.asOf

        val quarters = fundamentalsDao.quarters(symbol).map { Mappers.toQuarter(it) }
        val statementRows = fundamentalsDao.allStatementRows(symbol)
        val sections = Mappers.Statements.ALL.associateWith { section ->
            Mappers.fromStatementRows(statementRows.filter { it.statement == section })
        }
        val derived = Mappers.toDerived(
            fundamentalsDao.derivedMetrics(symbol, asOf.toString()),
        )

        val found = mutableListOf<String>()
        val missing = mutableListOf<String>()
        val notes = mutableListOf<String>()
        for ((section, rows) in sections) {
            if (rows.isEmpty()) {
                missing += section
                notes += "section '$section' is absent from the cached page for $symbol"
            } else {
                found += section
            }
        }
        if (quarters.isEmpty()) {
            missing += "quarters"
            notes += "no quarterly results are cached for $symbol"
        } else {
            found += "quarters"
        }

        val health = ParserHealth(
            ok = missing.isEmpty(),
            sectionsFound = found,
            sectionsMissing = missing,
            notes = notes,
        )
        val today = todayInMarket()
        val ratiosWithFreshness = ratios.withFreshness(today)
        val snapshot = FundamentalsSnapshot(
            symbol = symbol,
            name = symbolDao.bySymbol(symbol)?.name ?: symbol,
            sector = ratios.sector,
            // plan.md §1.4 rule 8: an unresolved sector is OTHER, and OTHER *is* unresolved.
            sectorResolved = ratios.sector != com.mia.app.domain.model.Sector.OTHER,
            ratios = ratiosWithFreshness,
            health = health,
            quarters = quarters,
            profitLoss = sections.getValue(Mappers.Statements.PROFIT_LOSS),
            balanceSheet = sections.getValue(Mappers.Statements.BALANCE_SHEET),
            cashFlow = sections.getValue(Mappers.Statements.CASH_FLOW),
            cashFlowDerived = derived,
            shareholding = sections.getValue(Mappers.Statements.SHAREHOLDING),
        )

        return DataResult.ok(
            value = FundamentalsView(
                snapshot = snapshot,
                ratios = ratiosWithFreshness,
                stalenessLine = FreshnessPolicy.describe(
                    asOf = asOf,
                    today = today,
                    kind = FreshnessKind.FUNDAMENTALS_SNAPSHOT,
                ),
                complete = health.ok,
                incompletenessNotes = notes,
            ),
            source = ratios.source,
            fetchedOn = asOf,
            asOf = asOf,
            health = health,
        )
    }

    /** Persists a clean parse. Only ever called with a not-ok-health result filtered out. */
    private suspend fun write(symbol: String, parsed: FundamentalsParseResult) {
        val snapshot = parsed.snapshot
        val asOf = snapshot.ratios.asOf

        fundamentalsDao.putRatios(Mappers.fromRatios(snapshot.ratios))

        fundamentalsDao.clearQuarters(symbol)
        if (snapshot.quarters.isNotEmpty()) {
            fundamentalsDao.putQuarters(
                snapshot.quarters.map { Mappers.fromQuarter(symbol, it) },
            )
        }

        fundamentalsDao.clearStatementRows(symbol)
        val sections = mapOf(
            Mappers.Statements.PROFIT_LOSS to snapshot.profitLoss,
            Mappers.Statements.BALANCE_SHEET to snapshot.balanceSheet,
            Mappers.Statements.CASH_FLOW to snapshot.cashFlow,
            Mappers.Statements.SHAREHOLDING to snapshot.shareholding,
        )
        for ((section, rows) in sections) {
            if (rows.isEmpty()) continue
            val table = parsed.sections[section]
            val years = (table?.years ?: emptyList()).mapNotNull { Mappers.Statements.fiscalYearOf(it) }
            if (years.isEmpty()) continue
            fundamentalsDao.putStatementRows(Mappers.toStatementRows(section, symbol, rows, years))
        }

        fundamentalsDao.clearDerivedMetrics(symbol, asOf.toString())
        fundamentalsDao.putDerivedMetrics(
            Mappers.fromDerived(symbol, asOf, snapshot.cashFlowDerived),
        )
    }

    private fun <T : Any> noCache(symbol: String): DataResult<T> = DataResult.unavailable(
        failure = DataFailure(
            reason = DataFailureReason.NO_DATA_CACHED,
            message = "No fundamentals page for $symbol has been fetched on this device. Every figure " +
                "must show a source and an as-of date (plan.md §1.4 rule 3), so nothing is shown " +
                "until there is one — this is not a statement that the company publishes nothing.",
            detail = "symbol=$symbol",
        ),
        source = Source.SCREENER_IN,
        fetchedOn = todayInMarket(),
    )
}