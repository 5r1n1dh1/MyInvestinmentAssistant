package com.mia.app.data.android.repo

import com.mia.app.data.android.db.DerivedMetricEntity
import com.mia.app.data.android.db.FundamentalsDao
import com.mia.app.data.android.db.QuarterEntity
import com.mia.app.data.android.db.RatiosEntity
import com.mia.app.data.android.db.StatementRowEntity
import com.mia.app.data.android.db.SymbolDao
import com.mia.app.data.android.db.SymbolEntity
import com.mia.app.domain.model.Sector
import com.mia.app.domain.ratios.RatioId
import com.mia.app.domain.sectors.SectorPopulation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [DefaultSectorRepository] against fake DAOs.
 *
 * ### What is worth testing here, given the median's own rules are pinned elsewhere
 * `SectorBenchmarkTest` owns the sample floor and §7.3. This file owns the three things that could
 * silently corrupt the **population** before the floor ever sees it — and each is a way for a
 * median to mean something other than what its sentence says:
 *
 * 1. **A company fetched twice must appear once.** `ratios` is `PK(symbol, asOf)`, so a re-fetch
 *    leaves two dated rows. Counting both would double-count that peer and put two different dates
 *    into one median — the same class of error as mixing in a stale page, but invisible because
 *    both rows are inside the window.
 * 2. **The sector must come from the stored industry sentence.** The resolver's precedence puts
 *    that text above the company name, and a peer set classified any other way would put a bank and
 *    an IT company into one median — plan.md §7.3's failure arriving through a different door.
 * 3. **Freshness must be marked per row.** A median built on a mix of a January page and this
 *    week's is a comparison between dates.
 *
 * It also pins the empty case as a **typed failure with a sentence**, not a silent empty list —
 * plan.md §12's distinction, and the one that lets the screen say "open a Fundamentals tab first"
 * rather than showing a blank table.
 */
class DefaultSectorRepositoryTest {

    private val today = LocalDate.of(2026, 10, 16)
    private val fresh = today.minusDays(1)
    private val old = LocalDate.of(2026, 1, 5)

    @Test
    fun `an empty table is a stated absence, not an empty list`() = runTest {
        val result = repo().cachedCompanies(today)

        assertFalse(result.isOk, "nothing cached is not a successful read of nothing")
        val failure = requireNotNull(result.failure)
        assertEquals(DataFailureReason.NOT_ATTEMPTED, failure.reason)
        assertTrue(
            failure.message.contains("several peers"),
            "the sentence must name what would fix it: ${failure.message}",
        )
        assertNull(result.valueOrNull())
    }

    @Test
    fun `only the latest snapshot of a company becomes a peer`() = runTest {
        val result = repo(
            ratios = listOf(
                row("ONGC", asOf = old, roce = 5.0),
                row("ONGC", asOf = fresh, roce = 14.0),
                row("IOC", asOf = fresh, roce = 12.0),
            ),
        ).cachedCompanies(today)

        val companies = requireNotNull(result.valueOrNull())
        assertEquals(2, companies.size, "two companies, not three snapshots")
        val ongc = companies.single { it.symbol == "ONGC" }
        assertEquals(14.0, ongc.readingOf(RatioId.ROCE))
        assertEquals(fresh, ongc.asOf, "the latest snapshot is the one a reader would see")
    }

    @Test
    fun `the sector comes from the stored industry sentence`() = runTest {
        val result = repo(
            ratios = listOf(
                row(
                    "RELIANCE",
                    // Deliberately misleading as a name on its own: "Industries" would match
                    // capital goods. The industry text is the resolver's highest-precedence input,
                    // and it is what says Oil & gas.
                    industry = OIL_GAS_INDUSTRY,
                    roce = 18.4,
                ),
            ),
            symbols = listOf(symbolEntity("RELIANCE", "Reliance Industries Ltd")),
        ).cachedCompanies(today)

        val reliance = requireNotNull(result.valueOrNull()).single()
        assertTrue(reliance.resolution.resolved)
        assertEquals(Sector.OIL_GAS, reliance.sector)
        assertEquals("Reliance Industries Ltd", reliance.name)
        assertTrue(
            reliance.resolution.evidence.isNotBlank(),
            "a sector resolution must be explainable (plan.md §7.1)",
        )
    }

    @Test
    fun `a row with no industry text and an uninformative name stays unresolved`() = runTest {
        val result = repo(
            ratios = listOf(
                // The shape of a row written before `ratios.industry` existed: the resolver has only
                // a name that matches no keyword, so it declines rather than guessing.
                row("ZZZZ", industry = null, roce = 9.0),
            ),
            symbols = listOf(symbolEntity("ZZZZ", "Zzzz Holdings")),
        ).cachedCompanies(today)

        val company = requireNotNull(result.valueOrNull()).single()
        assertFalse(company.resolution.resolved)
        assertEquals(Sector.OTHER, company.sector, "plan.md §7.1: unresolved must stay OTHER")
        assertTrue(company.resolution.evidence.isNotBlank())
    }

    @Test
    fun `staleness is marked per row so it can be excluded from a sample`() = runTest {
        val result = repo(
            ratios = listOf(
                row("ONGC", asOf = fresh, roce = 14.0),
                row("IOC", asOf = old, roce = 12.0),
            ),
        ).cachedCompanies(today)

        val companies = requireNotNull(result.valueOrNull()).associateBy { it.symbol }
        assertFalse(companies.getValue("ONGC").isStale, "one day old is inside the 7-day window")
        assertTrue(companies.getValue("IOC").isStale, "a January page is outside it")
        // The stale figure is still *there*. Dropping it here would shrink the reader's peer group
        // without saying why; it is excluded from the sample and counted instead.
        assertEquals(12.0, companies.getValue("IOC").readingOf(RatioId.ROCE))
    }

    @Test
    fun `a company missing from the symbol master still classifies from its industry text`() = runTest {
        val result = repo(
            ratios = listOf(row("NEWCO", roce = 11.0)),
            symbols = emptyList(),
        ).cachedCompanies(today)

        val company = requireNotNull(result.valueOrNull()).single()
        assertEquals("NEWCO", company.name, "with no master entry the symbol stands in for the name")
        assertTrue(
            company.resolution.resolved,
            "the resolver still has screener.in's industry sentence, which outranks the name",
        )
    }

    @Test
    fun `readings are exactly the columns the ratios table carries`() = runTest {
        val result = repo(ratios = listOf(row("ONGC", roce = 14.0, pe = 6.5))).cachedCompanies(today)

        val company = requireNotNull(result.valueOrNull()).single()
        assertEquals(14.0, company.readingOf(RatioId.ROCE))
        assertEquals(6.5, company.readingOf(RatioId.PE_VS_SECTOR_MEDIAN))

        // The ratios that need the annual statements are deliberately absent, and the screen says so
        // in its coverage note. Reading them here would mean walking every peer's statements and
        // aligning fiscal years across companies — a median over different periods.
        assertNull(company.readings[RatioId.FCF_VS_PAT])
        assertNull(company.readings[RatioId.DIVIDEND_PAYOUT])
        assertNull(company.readings[RatioId.FII_HOLDING])
    }

    @Test
    fun `a non-finite figure is treated as no figure`() = runTest {
        val result = repo(
            ratios = listOf(row("ONGC", roce = Double.NaN), row("IOC", roce = Double.POSITIVE_INFINITY)),
        ).cachedCompanies(today)

        requireNotNull(result.valueOrNull()).forEach { company ->
            assertNull(
                company.readingOf(RatioId.ROCE),
                "${company.symbol}: NaN and Infinity must not enter a median",
            )
        }
    }

    @Test
    fun `the coverage note travels with the read`() = runTest {
        val result = repo(ratios = listOf(row("ONGC", roce = 14.0))).cachedCompanies(today)

        val notes = result.provenance.notes
        assertEquals(1, notes.size, "exactly one coverage note: $notes")
        assertTrue(
            notes.single().contains("annual statements"),
            "the note must name the cause: ${notes.single()}",
        )
    }

    @Test
    fun `a single stored page builds no comparable peer group`() = runTest {
        // The fresh-install case, across the storage boundary: the repository hands back one company
        // and the population refuses to treat it as a sector.
        val companies = requireNotNull(
            repo(ratios = listOf(row("RELIANCE", roce = 18.4))).cachedCompanies(today).valueOrNull(),
        )
        assertEquals(1, companies.size)

        val population = SectorPopulation.select(subject = companies.single(), cached = companies)
        assertFalse(population.isComparable)
        assertEquals(SectorPopulation.NO_PEERS_REASON, population.notComparableReason)
    }

    // --------------------------------------------------------------------------- helpers

    /**
     * `Dispatchers.Unconfined` rather than a `TestDispatcher`.
     *
     * The repository's only suspension points are the fake DAOs, which return immediately, so there
     * is no scheduling to control — and a `UnconfinedTestDispatcher()` built outside `runTest`'s
     * scope would run on an orphan scheduler and emit a warning the build does not fail on, which
     * is exactly the kind of noise that hides a real one.
     */
    private fun repo(
        ratios: List<RatiosEntity> = emptyList(),
        symbols: List<SymbolEntity> = emptyList(),
    ) = DefaultSectorRepository(
        fundamentalsDao = FakeFundamentalsDao(ratios),
        symbolDao = FakeSymbolDao(symbols),
        dispatcher = Dispatchers.Unconfined,
    )

    // -------------------------------------------------------------------------- fakes

    /**
     * An in-memory stand-in for the Room DAO. Room's abstract DAOs subclass cleanly off-device.
     *
     * Only [allRatios] carries behaviour; every other method throws. A repository that reached for
     * a quarter table or a statement table would be widening its read past what a peer group needs,
     * and returning an empty list would hide that widening. Making the untaken paths loud is the
     * cheapest way to notice if that ever changes.
     */
    private class FakeFundamentalsDao(private val rows: List<RatiosEntity>) : FundamentalsDao() {
        override suspend fun allRatios(): List<RatiosEntity> = rows

        override suspend fun putRatios(ratios: RatiosEntity): Unit = unused("putRatios")
        override suspend fun latestRatios(symbol: String): RatiosEntity? = unused("latestRatios")
        override suspend fun ratiosOn(symbol: String, asOf: String): RatiosEntity? = unused("ratiosOn")

        override suspend fun putQuarters(quarters: List<QuarterEntity>): Unit = unused("putQuarters")
        override suspend fun quarters(symbol: String): List<QuarterEntity> = unused("quarters")
        override suspend fun clearQuarters(symbol: String): Int = unused("clearQuarters")

        override suspend fun putStatementRows(rows: List<StatementRowEntity>): Unit =
            unused("putStatementRows")

        override suspend fun statementRows(
            symbol: String,
            statement: String,
        ): List<StatementRowEntity> = unused("statementRows")

        override suspend fun allStatementRows(symbol: String): List<StatementRowEntity> =
            unused("allStatementRows")

        override suspend fun clearStatementRows(symbol: String): Int = unused("clearStatementRows")

        override suspend fun putDerivedMetrics(rows: List<DerivedMetricEntity>): Unit =
            unused("putDerivedMetrics")

        override suspend fun derivedMetrics(
            symbol: String,
            asOf: String,
        ): List<DerivedMetricEntity> = unused("derivedMetrics")

        override suspend fun allDerivedMetrics(symbol: String): List<DerivedMetricEntity> =
            unused("allDerivedMetrics")

        override suspend fun clearDerivedMetrics(symbol: String, asOf: String): Int =
            unused("clearDerivedMetrics")

        private fun unused(daoMethod: String): Nothing =
            throw AssertionError("$daoMethod is outside what the sector view is allowed to read")
    }

    private class FakeSymbolDao(private val rows: List<SymbolEntity>) : SymbolDao() {
        override suspend fun all(): List<SymbolEntity> = rows

        override suspend fun insertIgnore(symbols: List<SymbolEntity>): List<Long> =
            unused("insertIgnore")

        override suspend fun updateDisplayFields(
            isin: String,
            name: String,
            series: String,
            listingDate: String?,
            paidUpValue: Double?,
            lotSize: Long?,
            faceValue: Double?,
        ): Int = unused("updateDisplayFields")

        override suspend fun byIsin(isin: String): SymbolEntity? = unused("byIsin")
        override suspend fun bySymbol(symbol: String): SymbolEntity? = unused("bySymbol")
        override suspend fun bySymbols(symbols: List<String>): List<SymbolEntity> = unused("bySymbols")
        override suspend fun count(): Int = unused("count")

        private fun unused(daoMethod: String): Nothing =
            throw AssertionError("$daoMethod is outside what the sector view is allowed to read")
    }

    // ------------------------------------------------------------------------ fixtures

    @Suppress("LongParameterList")
    private fun row(
        symbol: String,
        asOf: LocalDate = fresh,
        industry: String? = OIL_GAS_INDUSTRY,
        roce: Double? = null,
        pe: Double? = null,
    ) = RatiosEntity(
        symbol = symbol,
        asOf = asOf.toString(),
        sector = Sector.OIL_GAS.name,
        industry = industry,
        source = "SCREENER_IN",
        confidence = "AGREED",
        roce = roce,
        roe = null,
        debtToEquity = null,
        interestCoverage = null,
        operatingMargin = null,
        fcf = null,
        pat = null,
        cfoToOp = null,
        revenueCagr3y = null,
        revenueGrowth = null,
        grossMargin = null,
        pe = pe,
        pb = null,
        evToEbitda = null,
        dividendYield = null,
        promoterHolding = null,
        marketCap = null,
    )

    private fun symbolEntity(symbol: String, name: String) = SymbolEntity(
        symbol = symbol,
        name = name,
        isin = "INE${symbol.hashCode().toUInt().toString().padStart(9, '0')}",
        series = "EQ",
        listingDate = null,
        paidUpValue = null,
        lotSize = 1L,
        faceValue = 10.0,
        source = "NSE_BHAVCOPY",
    )

    private companion object {
        /** RELIANCE's own industry sentence on screener.in, which is what resolves its sector. */
        const val OIL_GAS_INDUSTRY = "Energy, Oil, Gas & Consumable Fuels, Refineries & Marketing"
    }
}
