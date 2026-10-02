package com.mia.app.data.symbols

import com.mia.app.domain.model.ParserHealth
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Resilience of the symbol master parser.
 *
 * plan.md §12 is blunt about it: "a parser must return a health flag, not an empty list. Silent-empty
 * is indistinguishable from 'no red flags' — the most dangerous possible failure." Each test here
 * feeds a broken payload and asserts that the failure is *described*, never silently empty.
 */
class SymbolBookParserResilienceTest {

    /** The real header, whose first cell is unspaced and whose other seven are (plan.md §3.5). */
    private val header = "SYMBOL,NAME OF COMPANY, SERIES, DATE OF LISTING, PAID UP VALUE, MARKET LOT, ISIN NUMBER, FACE VALUE"
    private val goodRow = "TCS,Tata Consultancy Services Limited,EQ,25-AUG-2004,1,1,INE467B01029,1"

    /** The real master header, leading spaces included — used to prove the parser is not lucky. */
    private val realHeader = SymbolFixtures.text(SymbolFixtures.MASTER_RESOURCE).lineSequence().first()

    @Test
    fun `the synthetic header used by these tests is byte-identical to the real one`() {
        // Otherwise a passing resilience suite could be passing against a friendlier header than
        // the file actually has, which is the exact bug plan.md §3.5 documents.
        assertEquals(realHeader, header)
    }

    @Test
    fun `an empty payload fails loudly and says so`() {
        val parsed = SymbolFixtures.parseMaster("")
        assertEquals(0, parsed.records.size)
        assertTrue(!parsed.health.ok)
        assertTrue(parsed.health.notes.single().contains("no header line"), parsed.health.notes.toString())
    }

    @Test
    fun `a header-only file is a failure not an empty symbol book`() {
        val parsed = SymbolFixtures.parseMaster(header)
        assertEquals(0, parsed.records.size)
        assertEquals(0, parsed.rowsTotal)
        assertTrue(!parsed.health.ok)
        assertTrue(parsed.health.notes.single().contains("no usable rows"), parsed.health.notes.toString())
    }

    @Test
    fun `a missing ISIN column fails and names the column`() {
        val withoutIsin = " SYMBOL, NAME OF COMPANY, SERIES, DATE OF LISTING, PAID UP VALUE, MARKET LOT, FACE VALUE"
        val parsed = SymbolFixtures.parseMaster("$withoutIsin\n$goodRow")
        assertEquals(0, parsed.records.size)
        val note = parsed.health.notes.single()
        assertTrue(note.contains("ISIN NUMBER"), note)
        assertTrue(note.contains("required column(s) absent"), note)
        assertTrue(note.contains("SYMBOL"), "the note must quote the header it saw: $note")
        assertTrue(note.contains("NAME OF COMPANY"), "the note must quote the header it saw: $note")
    }

    @Test
    fun `a missing SYMBOL column fails and names the column`() {
        val withoutSymbol = " NAME OF COMPANY, SERIES, DATE OF LISTING, PAID UP VALUE, MARKET LOT, ISIN NUMBER, FACE VALUE"
        val parsed = SymbolFixtures.parseMaster("$withoutSymbol\nTata Consultancy Services Limited,EQ,25-AUG-2004,1,1,INE467B01029,1")
        assertEquals(0, parsed.records.size)
        assertTrue(parsed.health.notes.single().contains("SYMBOL"), parsed.health.notes.toString())
    }

    @Test
    fun `missing optional columns degrade the health but still parse`() {
        val minimal = "SYMBOL,ISIN NUMBER\nTCS,INE467B01029"
        val parsed = SymbolFixtures.parseMaster(minimal)
        assertEquals(1, parsed.records.size)
        val record = parsed.records.single()
        assertEquals("TCS", record.symbol)
        assertEquals("INE467B01029", record.isin)
        assertEquals("", record.name)
        assertEquals("", record.series)
        assertNull(record.listingDate)
        assertNull(record.paidUpValue)
        assertTrue(!parsed.health.ok, "a degraded parse must not claim to be healthy")
        assertEquals(6, parsed.health.sectionsMissing.size, parsed.health.sectionsMissing.toString())
        assertTrue(parsed.health.notes.single().contains("optional column(s) absent"))
    }

    @Test
    fun `a row with no ISIN is rejected and counted with the line kept for diagnosis`() {
        val csv = buildString {
            appendLine(header)
            appendLine(goodRow)
            appendLine("NOISIN,No Isin Limited,EQ,01-JAN-2020,1,1,,1")
            appendLine(",No Symbol Limited,EQ,01-JAN-2020,1,1,INE111A01011,1")
            appendLine("BLANKBOTH,,EQ,01-JAN-2020,1,1,,1")
        }
        val parsed = SymbolFixtures.parseMaster(csv)
        assertEquals(1, parsed.records.size)
        assertEquals(4, parsed.rowsTotal)
        assertEquals(3, parsed.rejected)
        assertEquals(3, parsed.rejectedSamples.size)
        assertTrue(parsed.rejectedSamples.any { it.contains("no ISIN") })
        assertTrue(parsed.rejectedSamples.any { it.contains("no symbol") })
        assertTrue(!parsed.health.ok)
        assertTrue(parsed.health.notes.any { it.contains("3 row(s) rejected") })
    }

    @Test
    fun `the rejected sample list is capped so a broken file cannot exhaust memory`() {
        val csv = buildString {
            appendLine(header)
            repeat(500) { appendLine("BAD$it,,EQ,01-JAN-2020,1,1,,1") }
        }
        val parsed = SymbolFixtures.parseMaster(csv)
        assertEquals(0, parsed.records.size)
        assertEquals(500, parsed.rejected)
        assertEquals(SymbolBookParser.REJECTED_SAMPLE_LIMIT, parsed.rejectedSamples.size)
    }

    @Test
    fun `a duplicate ticker is resolved last-wins and reported`() {
        val csv = buildString {
            appendLine(header)
            appendLine("TCS,First Name Limited,EQ,25-AUG-2004,1,1,INE000A01011,1")
            appendLine("TCS,Second Name Limited,EQ,25-AUG-2004,1,1,INE000B01022,1")
        }
        val parsed = SymbolFixtures.parseMaster(csv)
        assertEquals(1, parsed.records.size)
        assertEquals("Second Name Limited", parsed.records.single().name, "documented rule: last row wins")
        assertEquals("INE000B01022", parsed.records.single().isin)
        assertEquals(listOf("TCS"), parsed.duplicateTickers)
        assertEquals(2, parsed.rowsTotal)
        assertTrue(parsed.health.notes.any { it.contains("duplicate ticker(s), last row wins: TCS") })
        assertTrue(!parsed.health.ok, "a duplicate is a data problem the UI must be able to see")
    }

    @Test
    fun `a duplicate ISIN across two tickers keeps the first and reports it`() {
        // Two tickers sharing one ISIN would merge into one identity key — the exact bug the ISIN
        // index must not have.
        val csv = buildString {
            appendLine(header)
            appendLine("AAAA,First Limited,EQ,01-JAN-2020,1,1,INESAME01001,1")
            appendLine("BBBB,Second Limited,EQ,01-JAN-2020,1,1,INESAME01001,1")
        }
        val parsed = SymbolFixtures.parseMaster(csv)
        val book = SymbolBook.of(parsed)
        assertEquals("AAAA", book.findByIsin("INESAME01001")?.symbol, "documented rule: first row wins")
        assertEquals(listOf("BBBB"), parsed.duplicateIsins)
        assertTrue(parsed.health.notes.any { it.contains("duplicate ISIN(s)") })
    }

    @Test
    fun `a BOM and CRLF line endings are handled`() {
        // What an Excel round-trip on a Windows machine produces.
        val csv = "﻿$header\r\n$goodRow\r\nRELIANCE,Reliance Industries Limited,EQ,29-NOV-1995,10,1,INE002A01018,10\r\n"
        val parsed = SymbolFixtures.parseMaster(csv)
        assertEquals(2, parsed.records.size)
        assertEquals("TCS", parsed.records[0].symbol, "the BOM must not become part of SYMBOL")
        assertEquals("INE002A01018", parsed.records[1].isin)
        assertTrue(parsed.health.ok, parsed.health.notes.toString())
    }

    @Test
    fun `lowercase headers and lower-case month names are recognised`() {
        val csv = "symbol,name of company,series,date of listing,paid up value,market lot,isin number,face value\n" +
            "TCS,Tata Consultancy Services Limited,eq,25-aug-2004,1,1,ine467b01029,1"
        val parsed = SymbolFixtures.parseMaster(csv)
        assertEquals(1, parsed.records.size)
        assertEquals("EQ", parsed.records.single().series)
        assertEquals("INE467B01029", parsed.records.single().isin)
        assertEquals(LocalDate.parse("2004-08-25"), parsed.records.single().listingDate)
        assertTrue(parsed.health.ok, parsed.health.notes.toString())
    }

    @Test
    fun `an unreadable date or number becomes null and is counted never guessed`() {
        val csv = buildString {
            appendLine(header)
            appendLine("TCS,Tata Consultancy Services Limited,EQ,not-a-date,free,lot,INE467B01029,face")
        }
        val parsed = SymbolFixtures.parseMaster(csv)
        val record = parsed.records.single()
        assertNull(record.listingDate)
        assertNull(record.paidUpValue)
        assertNull(record.faceValue)
        assertNull(record.lotSize)
        assertTrue(parsed.health.notes.any { it.contains("4 value(s) unreadable") }, parsed.health.notes.toString())
        assertTrue(!parsed.health.ok)
    }

    @Test
    fun `a quoted company name containing a comma does not shift the columns`() {
        // The real master has no commas in names today (measured across all 2,593 rows). The day it
        // does, a naive `split(',')` would shift every later column by one and reject the row — the
        // "parser brittleness → silent empty screens" failure plan.md §13 lists.
        val csv = buildString {
            appendLine(header)
            appendLine("ACME,\"Acme, Incorporated Limited\",EQ,01-JAN-2020,1,1,INE999A01001,10")
        }
        val parsed = SymbolFixtures.parseMaster(csv)
        val record = parsed.records.single()
        assertEquals("Acme, Incorporated Limited", record.name)
        assertEquals("EQ", record.series)
        assertEquals("INE999A01001", record.isin, "the ISIN must not be shifted by the comma")
        assertEquals(10.0, record.faceValue)
    }

    @Test
    fun `blank lines and whitespace-only lines are ignored not counted as rejected`() {
        val csv = buildString {
            appendLine(header)
            appendLine("")
            appendLine("   ")
            appendLine(goodRow)
            appendLine("")
        }
        val parsed = SymbolFixtures.parseMaster(csv)
        assertEquals(1, parsed.rowsTotal)
        assertEquals(0, parsed.rejected)
        assertTrue(parsed.health.ok)
    }

    @Test
    fun `a missing asset is a failure with a message and no book`() {
        val result = SymbolBookSource.load(SymbolFixtures.assetReader("OTHER.csv", "x"))
        assertNull(result.book)
        assertTrue(!result.isUsable)
        assertTrue(!result.health.ok)
        assertNotNull(result.error)
        assertTrue(result.error!!.contains("is not bundled"), result.error!!)
    }

    @Test
    fun `an asset reader that throws is contained not propagated`() {
        val result = SymbolBookSource.load(AssetReader { _ -> throw java.io.IOException("disk gone") })
        assertNull(result.book)
        assertTrue(result.error!!.contains("disk gone"), result.error!!)
        assertTrue(!result.health.ok)
    }

    @Test
    fun `a null stream is a failure with a message`() {
        val parsed = SymbolBookParser.parse(null)
        assertEquals(0, parsed.records.size)
        assertTrue(parsed.health.notes.single().contains("null"), parsed.health.notes.toString())
    }

    @Test
    fun `a symbol record refuses to be built without an identity`() {
        // The invariant is enforced at construction, so an empty key can never reach the index.
        var threwOnSymbol = false
        try {
            SymbolFixtures.record(" ").copy(symbol = " ")
        } catch (e: IllegalArgumentException) {
            threwOnSymbol = true
        }
        assertTrue(threwOnSymbol, "a blank symbol must be rejected")

        var threwOnIsin = false
        try {
            SymbolFixtures.record("OK").copy(isin = "")
        } catch (e: IllegalArgumentException) {
            threwOnIsin = true
        }
        assertTrue(threwOnIsin, "a blank ISIN must be rejected")
    }

    @Test
    fun `a resolution cannot be built without an explanation`() {
        var threw = false
        try {
            SymbolResolution("X", ResolutionStatus.EXACT, SymbolFixtures.record("X"), ResolutionStep.EXACT_TICKER, "  ")
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw, "CONTRACTS.md rule 5: never return a result without saying what happened")
    }

    @Test
    fun `an unresolved resolution cannot carry a record`() {
        var threw = false
        try {
            SymbolResolution(
                "X",
                ResolutionStatus.UNRESOLVED,
                SymbolFixtures.record("X"),
                ResolutionStep.UNRESOLVED,
                "nothing",
            )
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun `a snapshot with no symbols still parses and still carries its health`() {
        val snapshot = PresenceSnapshot.fromIdentities(
            LocalDate.parse("2026-10-01"),
            emptyList(),
            ParserHealth.failed("nothing downloaded"),
        )
        assertEquals(0, snapshot.identities.size)
        assertTrue(!snapshot.health.ok, "a broken day must stay broken in the snapshot")
    }

    @Test
    fun `duplicate rows in a snapshot are named`() {
        val snapshot = SymbolFixtures.snapshot(
            LocalDate.parse("2026-10-01"),
            listOf(SymbolFixtures.id("TCS", "INE467B01029"), SymbolFixtures.id("tcs", "INE467B01029")),
        )
        assertEquals(listOf("TCS"), snapshot.duplicateSymbols)
    }

    @Test
    fun `the tracker ignores a re-fed or out-of-order session`() {
        // An at-least-once EOD worker re-feeds; a stale fetch must not fabricate a gap.
        val tracker = CorporateActionTracker()
        val first = SymbolFixtures.snapshot(LocalDate.parse("2026-10-01"), listOf(SymbolFixtures.id("TCS", "INE467B01029")))
        assertEquals(0, tracker.record(first).size, "the first snapshot is the baseline, not news")
        assertEquals(0, tracker.record(first).size, "same-day re-feed is a no-op")
        assertEquals(0, tracker.record(SymbolFixtures.snapshot(LocalDate.parse("2026-09-30"), emptyList())).size)
        assertEquals(1, tracker.sessionCount, "rejected sessions must not advance the counter")
        assertEquals(LocalDate.parse("2026-10-01"), tracker.lastSessionDate)
        assertEquals(0, tracker.eventsFor("TCS").size)

        // A genuine new listing on a later day does produce exactly one event.
        val next = tracker.record(
            SymbolFixtures.snapshot(
                LocalDate.parse("2026-10-02"),
                listOf(SymbolFixtures.id("TCS", "INE467B01029"), SymbolFixtures.id("TMCV", "INE1TAE01010")),
            ),
        )
        assertEquals(listOf("TMCV"), next.map { it.symbol })
        assertEquals(CorporateActionType.SYMBOL_APPEARED, next.single().type)
        assertEquals(2, tracker.sessionCount)
    }
}