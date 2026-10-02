package com.mia.app.data.price

import com.mia.app.domain.model.Source
import com.mia.app.domain.model.Timeframe
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The P2 phase gate (plan.md §11): parse a saved bhavcopy fixture and assert exact field values.
 *
 * Every number here comes from the real 2026-10-01 archive captured from
 * `archives.nseindia.com` (plan.md §3.1, §14 evidence log) — nothing is derived or rounded by
 * this test. If NSE changes the file, these assertions are what should fail.
 */
class NseBhavcopyParserFixtureTest {

    private val tradeDate = LocalDate.of(2026, 10, 1)

    // ---------------------------------------------------------------- RELIANCE, exactly

    @Test
    fun `RELIANCE row parses to the exact values NSE published`() {
        val row = relianceRow()

        assertEquals("RELIANCE", row.ticker)
        assertEquals("INE002A01018", row.isin)
        assertEquals("EQ", row.series)
        assertEquals("2885", row.finInstrmId)
        assertEquals("RELIANCE INDUSTRIES LTD", row.name)
        assertEquals("F1", row.sessionId)
        assertEquals(tradeDate, row.tradDt)
        assertEquals(tradeDate, row.bizDt)
        assertEquals("CM", row.segment)
        assertEquals("NSE", row.sourceExchange)
        assertEquals("STK", row.instrumentType)

        assertEquals(1180.10, row.open)
        assertEquals(1183.90, row.high)
        assertEquals(1160.80, row.low)
        assertEquals(1167.70, row.close)
        assertEquals(1187.00, row.prevClose)
        assertEquals(1167.21, row.settlementPrice)

        assertEquals(16_771_221L, row.volume)
        assertEquals(19_674_608_846.20, row.turnoverValue)
        assertEquals(279_428L, row.trades)
        assertEquals(1L, row.lotSize)
    }

    @Test
    fun `RELIANCE candle carries close 1167_70 as D1 from NSE with null delivery`() {
        val candle = relianceCandle()

        assertEquals("RELIANCE", candle.symbol)
        assertEquals(Timeframe.D1, candle.timeframe)
        assertEquals(Source.NSE_BHAVCOPY, candle.source)
        assertEquals(tradeDate, candle.date)
        assertEquals(1180.10, candle.open)
        assertEquals(1183.90, candle.high)
        assertEquals(1160.80, candle.low)
        assertEquals(1167.70, candle.close)
        assertEquals(16_771_221L, candle.volume)
        // Delivery percentage lives in the legacy file (plan.md §3.5); fabricating 0.0 here
        // would manufacture the "delivery collapsed" red flag of plan.md §8.2.
        assertNull(candle.deliveryPercent)
    }

    @Test
    fun `every column constant matches the real header cell it claims to name`() {
        // Regression guard for a real bug caught during this phase: hand-typed normalised column
        // names are easy to get wrong (`FinInstrmNm` → FININSTRM**NM**, not FININSTRNM) and the
        // only symptom is a silently null field or a "missing optional column" warning.
        val header = Fixtures.bhavcopyCsv.lineSequence().first()
            .split(',')
            .map(NseBhavcopyParser::normaliseHeader)

        val declared = (
            NseBhavcopyParser.REQUIRED_COLUMNS +
                NseBhavcopyParser.OPTIONAL_COLUMNS +
                NseBhavcopyParser.Columns.IGNORED
            ).toSet()

        assertTrue(
            (declared - header.toSet()).isEmpty(),
            "declared column absent from the real header: ${declared - header.toSet()}",
        )
    }

    @Test
    fun `the real header contains no column the parser does not account for`() {
        val header = Fixtures.bhavcopyCsv.lineSequence().first()
            .split(',')
            .map(NseBhavcopyParser::normaliseHeader)
            .toSet()

        val declared = (
            NseBhavcopyParser.REQUIRED_COLUMNS +
                NseBhavcopyParser.OPTIONAL_COLUMNS +
                NseBhavcopyParser.Columns.IGNORED
            ).toSet()

        assertTrue(
            (header - declared).isEmpty(),
            "NSE added a column; classify it: ${header - declared}",
        )
        assertEquals(34, header.size)
    }

    // ------------------------------------------------------- whole-file invariants

    @Test
    fun `the 2026-10-01 archive holds exactly 3712 instruments and 3051 EQ plus SM candles`() {
        val all = NseBhavcopyParser.parseCsv(Fixtures.bhavcopyCsv, filterSeries = null)

        assertEquals(3712, all.rowsTotal)
        assertEquals(3712, all.rows.size)
        assertEquals(3712, all.candles.size)
        assertEquals(0, all.rowsSkipped)

        val filtered = NseBhavcopyParser.parseZip(Fixtures.bhavcopyZip)
        assertEquals(3712, filtered.rowsTotal)
        assertEquals(3051, filtered.candles.size)
        assertEquals(3051, filtered.rowsKept)
        assertEquals(0, filtered.rowsSkipped)
    }

    @Test
    fun `series distribution is EQ 2662 and SM 389 with everything else filtered out`() {
        val all = NseBhavcopyParser.parseCsv(Fixtures.bhavcopyCsv, filterSeries = null)

        val distribution = all.rows.groupingBy { it.series }.eachCount()
        assertEquals(2662, distribution["EQ"])
        assertEquals(389, distribution["SM"])
        // Everything else (BE 246, ST 87, GB 44, GS 55, …) is a derivative, a scheme book entry
        // or a sovereign gold bond — never a tradeable equity.
        assertEquals(246, distribution["BE"])
        assertEquals(87, distribution["ST"])
        assertEquals(55, distribution["GS"])
        assertEquals(44, distribution["GB"])

        val filtered = NseBhavcopyParser.parseCsv(Fixtures.bhavcopyCsv)
        assertEquals(setOf("EQ", "SM"), filtered.rows.map { it.series }.toSet())
        assertEquals(2662, filtered.rows.count { it.series == "EQ" })
        assertEquals(389, filtered.rows.count { it.series == "SM" })
        assertEquals(3712 - 3051, all.rows.size - filtered.rows.size)
        // Filtered rows are excluded, never counted as defects.
        assertEquals(0, filtered.rowsSkipped)
    }

    @Test
    fun `KNOWN_SERIES covers every code present in the real file`() {
        val all = NseBhavcopyParser.parseCsv(Fixtures.bhavcopyCsv, filterSeries = NseBhavcopyParser.KNOWN_SERIES)
        assertEquals(3712, all.rows.size, "KNOWN_SERIES is stale: ${all.rows.map { it.series }.toSet() - NseBhavcopyParser.KNOWN_SERIES}")
    }

    @Test
    fun `every one of the 3712 candles is structurally valid`() {
        // Proves the OHLC field mapping: a transposed column (high↔low, close↔prevClose) would
        // break Candle.isValid for at least some instrument in 3,712 rows.
        val all = NseBhavcopyParser.parseCsv(Fixtures.bhavcopyCsv, filterSeries = null)
        assertEquals(3712, all.candles.size)

        val invalid = all.candles.filterNot { it.isValid }
        assertTrue(invalid.isEmpty(), "invalid candles: ${invalid.take(5)}")
    }

    @Test
    fun `parsing the zip equals parsing the extracted csv`() {
        val fromZip = NseBhavcopyParser.parseZip(Fixtures.bhavcopyZip)
        val fromCsv = NseBhavcopyParser.parseCsv(Fixtures.bhavcopyCsv)

        assertEquals(fromCsv.candles, fromZip.candles)
        assertEquals(fromCsv.rows, fromZip.rows)
        assertEquals(fromCsv.health, fromZip.health)
        assertEquals(fromCsv.rowsTotal, fromZip.rowsTotal)
        assertEquals(fromCsv.rowsSkipped, fromZip.rowsSkipped)
        assertEquals(fromCsv.tradDt, fromZip.tradDt)
    }

    @Test
    fun `a real file with nothing skipped reports healthy with no missing columns`() {
        val result = NseBhavcopyParser.parseZip(Fixtures.bhavcopyZip)

        assertTrue(result.health.ok, "unexpected health: ${result.health}")
        assertEquals(emptyList(), result.health.sectionsMissing)
        assertFalse(result.health.hasMissingSections)
        assertEquals(tradeDate, result.tradDt)
        assertEquals(emptyList(), result.skippedSamples)
        // XpryDt / StrkPric / OptnTp / FininstrmActlXpryDt / Rmks / Rsvd1..4 are all present in
        // the real file and all correctly ignored.
        assertEquals(emptyList(), result.health.notes)
        assertEquals(
            NseBhavcopyParser.REQUIRED_COLUMNS.size + NseBhavcopyParser.OPTIONAL_COLUMNS.size,
            result.health.sectionsFound.size,
        )
    }

    @Test
    fun `every candle shares the single trade date of the file`() {
        val result = NseBhavcopyParser.parseZip(Fixtures.bhavcopyZip)

        assertEquals(setOf(tradeDate), result.candles.map { it.date }.toSet())
        assertEquals(setOf("D1"), result.candles.map { it.timeframe.name }.toSet())
        assertEquals(setOf(Source.NSE_BHAVCOPY), result.candles.map { it.source }.toSet())
    }

    @Test
    fun `TATAMOTORS is absent from the bhavcopy because of the demerger`() {
        // plan.md §4.2 / §14: the reason the resolver must be ISIN-keyed. Recorded here so the
        // omission is a known, asserted fact rather than a bug filed later.
        val result = NseBhavcopyParser.parseZip(Fixtures.bhavcopyZip)

        assertNull(result.candles.firstOrNull { it.symbol == "TATAMOTORS" })
        assertTrue(result.candles.any { it.symbol == "RELIANCE" })
        assertEquals(3051, result.candles.map { it.symbol }.toSet().size, "ticker space is unique")
    }

    @Test
    fun `spot-checked large caps match their published rows`() {
        val result = NseBhavcopyParser.parseZip(Fixtures.bhavcopyZip)
        val bySymbol = result.rows.associateBy { it.ticker }

        val tcs = assertNotNull(bySymbol["TCS"])
        assertEquals("INE467B01029", tcs.isin)
        assertEquals("EQ", tcs.series)
        assertEquals("TATA CONSULTANCY SERV LT", tcs.name)
        assertEquals(2052.60, tcs.open)
        assertEquals(2093.90, tcs.high)
        assertEquals(2045.10, tcs.low)
        assertEquals(2075.00, tcs.close)
        assertEquals(2050.60, tcs.prevClose)
        assertEquals(3_428_501L, tcs.volume)
        assertEquals(133_083L, tcs.trades)

        val infy = assertNotNull(bySymbol["INFY"])
        assertEquals("INE009A01021", infy.isin)
        assertEquals("INFOSYS LIMITED", infy.name)
        assertEquals(1010.00, infy.open)
        assertEquals(1035.00, infy.high)
        assertEquals(999.15, infy.low)
        assertEquals(1035.00, infy.close)
        assertEquals(15_166_446L, infy.volume)
        assertEquals(15_409_540_599.40, infy.turnoverValue)

        val hdfc = assertNotNull(bySymbol["HDFCBANK"])
        assertEquals("INE040A01034", hdfc.isin)
        assertEquals("HDFC BANK LTD", hdfc.name)
        assertEquals(708.40, hdfc.open)
        assertEquals(721.30, hdfc.high)
        assertEquals(707.10, hdfc.low)
        assertEquals(721.20, hdfc.close)
    }

    @Test
    fun `SM suriot rows are kept and carry their own lot size`() {
        val result = NseBhavcopyParser.parseZip(Fixtures.bhavcopyZip)

        val aakaar = assertNotNull(result.rows.firstOrNull { it.ticker == "AAKAAR" })
        assertEquals("SM", aakaar.series)
        assertEquals("INE1GYP01013", aakaar.isin)
        assertEquals("AAKAAR MEDICAL TECHNO LTD", aakaar.name)
        assertEquals(65.90, aakaar.open)
        assertEquals(67.00, aakaar.high)
        assertEquals(65.00, aakaar.low)
        assertEquals(65.00, aakaar.close)
        assertEquals(4_800L, aakaar.volume)
        assertEquals(1_600L, aakaar.lotSize)

        assertTrue(assertNotNull(result.candles.firstOrNull { it.symbol == "AAKAAR" }).isValid)
    }

    @Test
    fun `turnover is rupees not lakhs`() {
        // plan.md §3.1: TtlTrfVal is rupees. The legacy file's TURNOVER_LACS carries the same
        // day's turnover in lakhs (196746.09), so a factor-of-1e5 mix-up is the classic bug.
        val row = relianceRow()
        assertEquals(19_674_608_846.20, row.turnoverValue)
        assertEquals(19_674_608_846.20 / 100_000.0, 196_746.088462, 1e-6)
    }

    private fun relianceRow(): BhavcopyRow =
        assertNotNull(NseBhavcopyParser.parseZip(Fixtures.bhavcopyZip).rows.firstOrNull { it.ticker == "RELIANCE" })

    private fun relianceCandle() = assertNotNull(
        NseBhavcopyParser.parseZip(Fixtures.bhavcopyZip).candles.firstOrNull { it.symbol == "RELIANCE" },
    )
}
