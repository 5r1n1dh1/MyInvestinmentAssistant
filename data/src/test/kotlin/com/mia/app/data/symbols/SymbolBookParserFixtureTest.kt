package com.mia.app.data.symbols

import com.mia.app.data.price.NseBhavcopyParser
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Golden-file tests against the real 2,593-row NSE symbol master.
 *
 * Every value asserted here was read out of
 * `data/src/main/resources/assets/EQUITY_L.csv` (captured 2026-10-02). If the bundled asset is
 * refreshed the counts will legitimately move, and these tests will fail loudly rather than
 * letting identity resolution rot in silence — which is the whole reason plan.md §9 says to
 * build this first.
 */
class SymbolBookParserFixtureTest {

    private val parsed = SymbolFixtures.masterParse()
    private val book = SymbolFixtures.book()

    @Test
    fun `the real master parses 2593 data rows with none rejected`() {
        assertEquals(2593, parsed.rowsTotal, "master data rows (header excluded)")
        assertEquals(2593, parsed.records.size)
        assertEquals(0, parsed.rejected, "no row may be dropped for lacking a symbol or ISIN")
        assertEquals(SymbolFixtures.EXPECTED_MASTER_ROWS, parsed.records.size)
    }

    @Test
    fun `a healthy master reports ok with every column found`() {
        assertTrue(parsed.health.ok, "health notes: ${parsed.health.notes}")
        assertTrue(parsed.health.sectionsMissing.isEmpty(), "missing: ${parsed.health.sectionsMissing}")
        assertTrue(
            SymbolBookParser.Columns.ISIN in parsed.health.sectionsFound,
            "ISIN column must be reported found",
        )
    }

    @Test
    fun `every record has a non-blank ISIN because the ISIN is the identity key`() {
        val blank = parsed.records.filter { it.isin.isBlank() }
        assertTrue(blank.isEmpty(), "records without an ISIN: ${blank.map { it.symbol }}")
        assertEquals(2593, parsed.records.map { it.isin }.distinct().size, "ISINs must be unique")
        assertEquals(2593, parsed.records.map { it.symbol }.distinct().size, "tickers must be unique")
    }

    @Test
    fun `leading-space headers are handled so all eight columns land`() {
        // The real first line is:
        //   SYMBOL,NAME OF COMPANY, SERIES, DATE OF LISTING, PAID UP VALUE, MARKET LOT,
        //   ISIN NUMBER, FACE VALUE
        // Only SYMBOL is unspaced. A parser reading raw header cells would miss seven columns and
        // then reject every row for having no ISIN.
        val rawHeader = SymbolFixtures.text(SymbolFixtures.MASTER_RESOURCE).lineSequence().first()
        assertEquals(
            "SYMBOL,NAME OF COMPANY, SERIES, DATE OF LISTING, PAID UP VALUE, MARKET LOT, ISIN NUMBER, FACE VALUE",
            rawHeader,
        )
        assertEquals("NAME OF COMPANY", rawHeader.split(",")[1].trim(), "only the first cell is unspaced")
        assertTrue(parsed.health.sectionsFound.containsAll(SymbolBookParser.OPTIONAL_COLUMNS))
    }

    @Test
    fun `RELIANCE parses to its exact real record`() {
        val record = assertNotNull(book.find("RELIANCE"))
        assertEquals("RELIANCE", record.symbol)
        assertEquals("Reliance Industries Limited", record.name)
        assertEquals("EQ", record.series)
        assertEquals("INE002A01018", record.isin)
        assertEquals(LocalDate.parse("1995-11-29"), record.listingDate)
        assertEquals(10.0, record.paidUpValue)
        assertEquals(1L, record.lotSize)
        assertEquals(10.0, record.faceValue)
        assertTrue(record.isEquity)
    }

    @Test
    fun `HDFCBANK parses to its exact real record`() {
        val record = assertNotNull(book.find("HDFCBANK"))
        assertEquals("HDFC Bank Limited", record.name)
        assertEquals("EQ", record.series)
        assertEquals("INE040A01034", record.isin)
        assertEquals(LocalDate.parse("1995-11-08"), record.listingDate)
        assertEquals(1.0, record.paidUpValue)
        assertEquals(1L, record.lotSize)
        assertEquals(1.0, record.faceValue)
    }

    @Test
    fun `TCS parses to its exact real record`() {
        val record = assertNotNull(book.find("TCS"))
        assertEquals("Tata Consultancy Services Limited", record.name)
        assertEquals("EQ", record.series)
        assertEquals("INE467B01029", record.isin)
        assertEquals(LocalDate.parse("2004-08-25"), record.listingDate)
        assertEquals(1.0, record.paidUpValue)
        assertEquals(1L, record.lotSize)
        assertEquals(1.0, record.faceValue)
    }

    @Test
    fun `INFY parses to its exact real record`() {
        val record = assertNotNull(book.find("INFY"))
        assertEquals("Infosys Limited", record.name)
        assertEquals("INE009A01021", record.isin)
        assertEquals(LocalDate.parse("1995-02-08"), record.listingDate)
    }

    @Test
    fun `the master series distribution is EQ 2319 BE 248 BZ 26 and holds no SME series`() {
        assertEquals(SymbolFixtures.EXPECTED_MASTER_SERIES, book.seriesCounts())
        assertEquals(2593, book.seriesCounts().values.sum())
        // plan.md §3.1 filters the *bhavcopy* to EQ+SM; the master carries no SM rows at all, which
        // is why SME instruments can only ever be "known from the bhavcopy". Verified, not assumed.
        assertNull(book.find("SM"), "no ticker is literally named SM")
        assertTrue(book.records.none { it.series == "SM" }, "master has no SME series rows")
    }

    @Test
    fun `a book-entry record round-trips its series and ISIN`() {
        val bookEntry = assertNotNull(book.find("TIRUPATIFL"))
        assertEquals("BE", bookEntry.series)
        assertEquals("Tirupati Forge Limited", bookEntry.name)
        assertEquals("INE319Y01024", bookEntry.isin)
        assertEquals(LocalDate.parse("2020-08-04"), bookEntry.listingDate)
        assertTrue(!bookEntry.isEquity, "a BE record is not a normal listed equity")
    }

    @Test
    fun `the odd-lot BZ series is present and tiny`() {
        val bz = book.records.filter { it.series == "BZ" }
        assertEquals(26, bz.size)
        assertTrue(bz.all { it.isin.isNotBlank() })
    }

    @Test
    fun `tickers containing ampersand and dash survive parsing verbatim`() {
        // 10 real tickers contain '&' and 8 contain '-' (measured). Stripping them at parse time
        // would silently rename listed companies.
        val ampersand = book.records.filter { it.symbol.contains('&') }
        assertEquals(10, ampersand.size)
        assertEquals(
            listOf("ARE&M", "GMRP&UI", "GVT&D", "IL&FSENGG", "IL&FSTRANS", "J&KBANK", "M&M", "M&MFIN", "S&SPOWER", "SURANAT&P"),
            ampersand.map { it.symbol },
        )
        val dashed = book.records.filter { it.symbol.contains('-') }
        assertEquals(
            listOf("BAJAJ-AUTO", "BOSCH-HCIL", "CENTEXT-RE", "HCL-INSYS", "KLBRENG-B", "MCCHRLS-B", "NAM-INDIA", "UMIYA-MRO"),
            dashed.map { it.symbol },
        )
        assertEquals("INE101A01026", book.find("M&M")?.isin)
        assertEquals("INE917I01010", book.find("BAJAJ-AUTO")?.isin)
    }

    @Test
    fun `every listing date in the real master parses as dd-MMM-yyyy`() {
        val bad = parsed.records.filter { it.listingDate == null }
        assertTrue(bad.isEmpty(), "unparseable listing dates: ${bad.map { it.symbol }}")
        assertEquals(LocalDate.parse("1996-01-03"), book.find("M&M")?.listingDate)
    }

    @Test
    fun `every numeric column in the real master parses`() {
        assertEquals(0, parsed.records.count { it.paidUpValue == null })
        assertEquals(0, parsed.records.count { it.faceValue == null })
        assertEquals(0, parsed.records.count { it.lotSize == null })
    }

    @Test
    fun `the master carries no TATAMOTORS row at all which is the documented failure`() {
        // plan.md §9: TATAMOTORS returns zero rows on Yahoo and is absent from the bhavcopy because
        // it demerged. The bundled master has already been updated, so it too has no TATAMOTORS —
        // and it has no TATAMOTORSPV either, so the old name survives nowhere in the identity data.
        assertNull(book.find("TATAMOTORS"), "the master must not resurrect a retired ticker")
        assertNull(book.find("TATAMOTORSPV"))
    }

    @Test
    fun `both demerger legs are in the master with distinct ISINs`() {
        val newLeg = assertNotNull(book.find("TMCV"))
        assertEquals("Tata Motors Limited", newLeg.name)
        assertEquals("EQ", newLeg.series)
        assertEquals("INE1TAE01010", newLeg.isin)
        assertEquals(LocalDate.parse("2025-11-12"), newLeg.listingDate)

        val pvLeg = assertNotNull(book.find("TMPV"))
        assertEquals("Tata Motors Passenger Vehicles Limited", pvLeg.name)
        assertEquals("EQ", pvLeg.series)
        assertEquals("INE155A01022", pvLeg.isin)
        assertEquals(LocalDate.parse("1998-07-22"), pvLeg.listingDate)

        assertTrue(newLeg.isin != pvLeg.isin, "the two legs are separate securities")
        assertEquals(newLeg, book.findByIsin("INE1TAE01010"))
        assertEquals(pvLeg, book.findByIsin("INE155A01022"))
    }

    @Test
    fun `the csv splitter shared with the bhavcopy parser reads the master without quoting`() {
        // The master has zero quote characters today (measured across all 2,593 rows). Reusing the
        // bhavcopy's RFC-4180 splitter keeps quoting correct the day NSE quotes a name with a comma.
        assertEquals(0, SymbolFixtures.text(SymbolFixtures.MASTER_RESOURCE).count { it == '"' })
        val cells = NseBhavcopyParser.splitCsvLine("\"A, B Ltd\",EQ,INE1")
        assertEquals(listOf("A, B Ltd", "EQ", "INE1"), cells)
    }

    @Test
    fun `asset loading produces the same book the parser does`() {
        val loaded = SymbolBookSource.load(
            SymbolFixtures.assetReader(SymbolBookSource.ASSET_NAME, SymbolFixtures.text(SymbolFixtures.MASTER_RESOURCE)),
        )
        assertTrue(loaded.isUsable)
        assertEquals(2593, loaded.book?.size)
        assertEquals(2593, loaded.rowsTotal)
        assertEquals(0, loaded.rejected)
        val loadedBook = assertNotNull(loaded.book, "the bundled asset must load")
        assertEquals(book.find("RELIANCE"), loadedBook.find("RELIANCE"))
        assertTrue(loadedBook.health.ok)
    }

    @Test
    fun `all records are exposed sorted by ticker so iteration is deterministic`() {
        val symbols = book.all().map { it.symbol }
        assertEquals(symbols.sorted(), symbols)
        assertEquals("20MICRONS", symbols.first())
        assertEquals("ZYDUSWELL", symbols.last())
        assertEquals(2593, book.size)
    }
}