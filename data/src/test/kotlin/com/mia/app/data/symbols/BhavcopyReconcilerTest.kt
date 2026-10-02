package com.mia.app.data.symbols

import com.mia.app.data.price.BhavcopyRow
import com.mia.app.data.price.NseBhavcopyParser
import com.mia.app.domain.model.ParserHealth
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Master-versus-bhavcopy reconciliation, on the real captured pair and on synthetic mutations.
 *
 * The real numbers are the interesting part: they are large, they are *not* errors, and a UI that
 * renders them as a flat list is unusable. So both the counts and the grouping that makes them
 * legible are asserted.
 */
class BhavcopyReconcilerTest {

    private val book = SymbolFixtures.book()
    private val bhavcopy = SymbolFixtures.bhavcopy()
    private val report = BhavcopyReconciler.reconcile(book, bhavcopy)

    // -------------------------------------------------------------------------------------
    // The real pair
    // -------------------------------------------------------------------------------------

    @Test
    fun `the real master and the real bhavcopy give the real counts`() {
        assertEquals(2593, report.masterCount)
        assertEquals(3051, report.bhavcopyCount, "EQ 2662 + SM 389 kept, 661 filtered out of 3,712")
        assertEquals(3712, bhavcopy.rowsTotal)
        assertEquals(2313, report.matchedCount)
        assertEquals(280, report.masterOnly.size)
        assertEquals(738, report.bhavcopyOnly.size)
        // matched + masterOnly == masterCount, and matched + bhavcopyOnly == bhavcopyCount.
        assertEquals(2593, report.matchedCount + report.masterOnly.size)
        assertEquals(3051, report.matchedCount + report.bhavcopyOnly.size)
    }

    @Test
    fun `all 280 master-only symbols are named and grouped by the series that explains them`() {
        val bySeries = report.masterOnlyBySeries.mapValues { it.value.size }
        assertEquals(mapOf("BE" to 248, "BZ" to 26, "EQ" to 6), bySeries)

        // 274 of the 280 are book-entry (`BE`) or odd-lot (`BZ`) series, which NSE keeps in the
        // master and publishes outside the EQ+SM filter. They are not missing companies.
        assertEquals(274, bySeries.getValue("BE") + bySeries.getValue("BZ"))

        val equityOnly = report.masterOnly.filter { it.series == "EQ" }.map { it.symbol }
        assertEquals(SymbolFixtures.EXPECTED_MASTER_ONLY_EQUITY, equityOnly)
        assertEquals(280, report.masterOnly.map { it.symbol }.distinct().size)
    }

    @Test
    fun `only 8 master symbols are absent from the file entirely once every series counts`() {
        // 280 master-only symbols is the number against the EQ+SM filter. 272 of those are `BE`
        // (248) and `BZ` (26) rows, which NSE *does* publish under those series — so against the
        // whole 3,712-row file only 8 master symbols have no row at all. Those 8 are the ones a
        // corporate-action signal may legitimately be raised against; the other 272 are not.
        val unfiltered = NseBhavcopyParser.parseCsv(
            SymbolFixtures.text(SymbolFixtures.BHAVCOPY_RESOURCE),
            filterSeries = null,
        )
        assertEquals(3712, unfiltered.rows.size)

        val absentEntirely = BhavcopyReconciler.reconcileAllSeries(book, unfiltered, unfiltered.rows)
        assertEquals(8, absentEntirely.masterOnly.size, absentEntirely.masterOnly.map { it.symbol }.toString())
        assertEquals(
            listOf("ALFREDHE", "BNALTD", "KALYANI", "SAB", "SAYAJIHOTL", "SHERVANI", "SONAL", "TERAI"),
            absentEntirely.masterOnly.map { it.symbol },
        )
        // 6 of the 8 are `EQ` and 2 are `BE` — a mix, so this is not a series artefact.
        assertEquals(mapOf("BE" to 2, "EQ" to 6), absentEntirely.masterOnlyBySeries.mapValues { it.value.size })
        // The other 272 are visible in the file under a series the equity chart ignores.
        assertEquals(272, report.masterOnly.size - absentEntirely.masterOnly.size)
    }

    @Test
    fun `all 738 bhavcopy-only symbols are grouped EQ 349 and SM 389`() {
        assertEquals(mapOf("EQ" to 349, "SM" to 389), report.bhavcopyOnlyBySeries.mapValues { it.value.size })
        assertEquals(738, report.bhavcopyOnly.map { it.symbol }.distinct().size)
        // The master has no SM rows at all, so every SME instrument is necessarily "bhavcopy-only".
        assertEquals(389, report.bhavcopyOnly.count { it.series == "SM" })
        assertTrue(report.bhavcopyOnly.none { it.isin == null }, "every bhavcopy row carries an ISIN")
    }

    @Test
    fun `the two series breakdowns are reported separately not merged`() {
        assertEquals(mapOf("EQ" to 2319, "BE" to 248, "BZ" to 26), report.masterSeriesCounts)
        assertEquals(mapOf("EQ" to 2662, "SM" to 389), report.bhavcopySeriesCounts)
    }

    @Test
    fun `no ISIN mismatch or rename exists in the real pair on this date`() {
        assertEquals(0, report.isinMismatches.size)
        assertEquals(0, report.isinRenames.size)
        assertEquals(0, report.seriesChanges.size)
        assertEquals(0, report.duplicateBhavcopyTickers.size)
        assertEquals(0, report.changedIdentityCount)
        assertTrue(!report.isClean, "clean means both sides match exactly, which they do not")
    }

    @Test
    fun `the headline summarises instead of dumping rows`() {
        val headline = report.headline()
        assertTrue(headline.startsWith("As of 2026-10-01:"), headline)
        assertTrue(headline.contains("2313 of 3051"), headline)
        assertTrue(headline.contains("280 in the master but not trading today"), headline)
        assertTrue(headline.contains("274 of them book-entry/odd-lot series"), headline)
        assertTrue(headline.contains("738 trading but not in the master"), headline)
        assertTrue(headline.length < 400, "a headline must fit on one line: $headline")
        assertTrue(!headline.contains("changed identity"), "there is no identity change to report: $headline")
    }

    @Test
    fun `the headline leads with the identity-change count when there is one`() {
        val mutated = BhavcopyReconciler.reconcile(
            book,
            bhavcopy.rows.map { row ->
                if (row.ticker == "RELIANCE") row.copy(isin = "INE999Z99999") else row
            },
        )
        assertEquals(1, mutated.isinMismatches.size)
        val mismatch = mutated.isinMismatchFor("RELIANCE")!!
        assertEquals("INE002A01018", mismatch.masterIsin)
        assertEquals("INE999Z99999", mismatch.bhavcopyIsin)
        assertEquals(1, mutated.changedIdentityCount)
        assertTrue(mutated.headline().contains("1 symbol changed identity today"), mutated.headline())
        assertTrue(mutated.headline().contains("1 ISIN change"), mutated.headline())
    }

    // -------------------------------------------------------------------------------------
    // Synthetic mutations — the signal has to fire on a real disagreement
    // -------------------------------------------------------------------------------------

    @Test
    fun `an ISIN change under the same ticker is detected as a corporate-action signal`() {
        val mutated = BhavcopyReconciler.reconcile(
            book,
            bhavcopy.rows.map { row ->
                // Both demerger legs are the interesting case; TMCV is the one whose ISIN is new.
                if (row.ticker == "TMCV") row.copy(isin = "INE155A01022") else row
            },
        )
        val mismatch = assertNotNull(mutated.isinMismatchFor("TMCV"))
        assertEquals("INE1TAE01010", mismatch.masterIsin)
        assertEquals("INE155A01022", mismatch.bhavcopyIsin)
        assertEquals("EQ", mismatch.series)
    }

    @Test
    fun `a rename is found through the ISIN and not through the ticker`() {
        // Master knows INE002A01018 as RELIANCE; the bhavcopy publishes it as something else. Any
        // ticker-only comparison calls this "unknown symbol"; only the ISIN sees it as a rename.
        val mutated = BhavcopyReconciler.reconcile(
            book,
            bhavcopy.rows.map { row ->
                if (row.ticker == "RELIANCE") row.copy(ticker = "RILNEWCO") else row
            },
        )
        assertEquals(1, mutated.isinRenames.size)
        val rename = mutated.isinRenames.single()
        assertEquals("RILNEWCO", rename.symbol)
        assertEquals("INE002A01018", rename.bhavcopyIsin)
        assertEquals(1, mutated.changedIdentityCount)
        assertTrue("RELIANCE" in mutated.masterOnly.map { it.symbol })
        assertTrue("RILNEWCO" in mutated.bhavcopyOnly.map { it.symbol })
        assertTrue(mutated.headline().contains("1 renamed via ISIN"), mutated.headline())
    }

    @Test
    fun `a series change is reported separately from an ISIN change`() {
        val mutated = BhavcopyReconciler.reconcile(
            book,
            bhavcopy.rows.map { row -> if (row.ticker == "TCS") row.copy(series = "BE") else row },
        )
        val change = mutated.seriesChanges.single { it.symbol == "TCS" }
        assertEquals("EQ", change.masterSeries)
        assertEquals("BE", change.bhavcopySeries)
        assertEquals(0, mutated.isinMismatches.size)
    }

    @Test
    fun `a bhavcopy row with no ISIN is not reported as a mismatch`() {
        // A missing column is a gap in the source. Calling it a corporate action would manufacture
        // one out of a null.
        val mutated = BhavcopyReconciler.reconcile(
            book,
            bhavcopy.rows.map { row -> if (row.ticker == "RELIANCE") row.copy(isin = null) else row },
        )
        assertTrue(mutated.isinMismatches.isEmpty())
        assertTrue(mutated.bhavcopyOnly.none { it.symbol == "RELIANCE" })
        assertTrue("RELIANCE" !in mutated.masterOnly.map { it.symbol }, "it was seen, just without an ISIN")
    }

    @Test
    fun `a duplicated ticker in the bhavcopy is flagged`() {
        val doubled = bhavcopy.rows + listOf(bhavcopy.rows.first { it.ticker == "TCS" })
        val mutated = BhavcopyReconciler.reconcile(book, doubled)
        assertEquals(listOf("TCS"), mutated.duplicateBhavcopyTickers)
        assertEquals(3052, mutated.bhavcopyCount)
        assertTrue(mutated.headline().contains("1 duplicate ticker"), mutated.headline())
    }

    @Test
    fun `a fully failed bhavcopy is reported as empty rather than as the whole market vanishing`() {
        // The distinction that matters operationally: market holiday vs broken download.
        val failed = BhavcopyReconciler.reconcile(book, com.mia.app.data.price.BhavcopyParseResult())
        assertEquals(0, failed.matchedCount)
        assertEquals(2593, failed.masterOnly.size)
        assertNull(failed.asOf)
        assertTrue(!failed.health.ok, "the parser's failure must survive into the report")
        assertTrue(failed.health.notes.isNotEmpty())
    }

    @Test
    fun `a completely empty book reports zero master rows instead of dividing by nothing`() {
        val empty = SymbolBook.of(emptyList())
        val against = BhavcopyReconciler.reconcile(empty, bhavcopy)
        assertEquals(0, against.masterCount)
        assertEquals(0, against.matchedCount)
        assertEquals(0, against.masterOnly.size, "an empty master has nothing to be missing")
        assertEquals(3051, against.bhavcopyOnly.size)
        assertEquals(0, against.changedIdentityCount)
    }

    @Test
    fun `the snapshot overload reconciles identities without prices`() {
        val snapshot = PresenceSnapshot.fromBhavcopy(
            LocalDate.parse("2026-10-01"),
            bhavcopy.rows,
            bhavcopy.health,
        )
        val fromSnapshot = BhavcopyReconciler.reconcile(book, snapshot)
        assertEquals(report.masterOnly.size, fromSnapshot.masterOnly.size)
        assertEquals(report.bhavcopyOnly.size, fromSnapshot.bhavcopyOnly.size)
        assertEquals(report.matchedCount, fromSnapshot.matchedCount)
        assertEquals(LocalDate.parse("2026-10-01"), fromSnapshot.asOf)
    }

    @Test
    fun `a hand-built minimal pair reconciles to an exactly clean report`() {
        val tiny = SymbolBook.of(
            listOf(
                SymbolFixtures.record("AAA", isin = "INE111A01011"),
                SymbolFixtures.record("BBB", isin = "INE222B01022"),
            ),
        )
        val rows = listOf(
            row("AAA", "INE111A01011", "EQ"),
            row("BBB", "INE222B01022", "EQ"),
        )
        val clean = BhavcopyReconciler.reconcile(tiny, rows, ParserHealth.healthy("synthetic"))
        assertTrue(clean.isClean, clean.headline())
        assertEquals(2, clean.matchedCount)
        assertEquals(0, clean.changedIdentityCount)
        assertEquals(0, clean.health.notes.size)
    }

    private fun row(ticker: String, isin: String, series: String) = BhavcopyRow(
        tradDt = LocalDate.parse("2026-10-01"),
        isin = isin,
        ticker = ticker,
        series = series,
        name = "$ticker Limited",
        open = 1.0,
        high = 1.0,
        low = 1.0,
        close = 1.0,
        volume = 1,
    )
}