package com.mia.app.data.fundamentals

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Golden-file assertions on the statement tables and the derived per-year maps.
 *
 * The annual periods are the same on all five fixtures (`Mar 2015` … `Mar 2026`), so the
 * assertions below are indexed by year, not by position — which is the whole point of parsing
 * by header rather than by cell offset (plan.md §3.3).
 */
class ScreenerStatementsGoldenTest {

    private val years = listOf(
        2015, 2016, 2017, 2018, 2019, 2020, 2021, 2022, 2023, 2024, 2025, 2026,
    )

    // -------------------------------------------------------------- table shape / landmine 2

    @Test
    fun `profit-loss carries a trailing TTM column that the other tables do not`() {
        for (symbol in ScreenerFixtures.symbols) {
            val result = ScreenerFixtures.parse(symbol)
            val pl = result.sections.getValue(ScreenerHtml.PROFIT_LOSS)
            assertEquals(13, pl.years.size, "$symbol profit-loss column count")
            assertEquals("TTM", pl.years.last(), "$symbol")
            assertNull(ScreenerHtml.yearOf(pl.years.last()), "TTM is not a fiscal year")

            for (id in listOf(ScreenerHtml.BALANCE_SHEET, ScreenerHtml.CASH_FLOW, ScreenerHtml.SHAREHOLDING)) {
                assertEquals(12, result.sections.getValue(id).years.size, "$symbol $id column count")
            }

            // Values are index-aligned with headers, including the TTM cell.
            for (row in pl.rows) {
                assertEquals(pl.years.size, row.values.size, "$symbol row '${row.label}'")
            }
        }
    }

    @Test
    fun `shareholding headers carry no data-date-key so the printed label must be parsed`() {
        for (symbol in ScreenerFixtures.symbols) {
            val sh = ScreenerFixtures.parse(symbol).sections.getValue(ScreenerHtml.SHAREHOLDING)
            assertTrue(sh.dateKeys.all { it == null }, "$symbol shareholding should have no date keys")
            assertEquals("Sep 2023", sh.years.first(), "$symbol")
            assertEquals("Jun 2026", sh.years.last(), "$symbol")
            // The table's class string differs from the other cards — proof that one parser
            // cannot assume a single table shape (plan.md §3.3 landmine 2).
            assertTrue(
                ScreenerFixtures.html(symbol).contains("<table class=\"data-table\">"),
                "$symbol should carry the distinct shareholding table markup",
            )
        }
    }

    @Test
    fun `the Raw PDF footer row is dropped because robots disallows its links`() {
        for (symbol in ScreenerFixtures.symbols) {
            val rows = ScreenerFixtures.parse(symbol).snapshot.quarters
            assertTrue(rows.isNotEmpty(), symbol)
            // It carries no QuarterRecord field, and the note explains why it vanished.
            val note = ScreenerFixtures.parse(symbol).health.notes
                .firstOrNull { it.contains("Raw PDF") }
            assertNotNull(note, "$symbol should note the dropped Raw PDF row")
            assertTrue(note.contains("§3.6"), note)
        }
    }

    // --------------------------------------------------------------------- annual figures

    @Test
    fun `reliance annual statements`() {
        val result = ScreenerFixtures.parse("RELIANCE")
        assertEquals(
            listOf(
                "Sales +", "Expenses +", "Operating Profit", "OPM %", "Other Income +",
                "Interest", "Depreciation", "Profit before tax", "Tax %", "Net Profit +",
                "EPS in Rs", "Dividend Payout %",
            ),
            result.snapshot.profitLoss.map { it.label },
        )
        assertRow(result, ScreenerHtml.PROFIT_LOSS, "Sales +", "Sales", 2026, 1_055_780.0)
        assertRow(result, ScreenerHtml.PROFIT_LOSS, "Sales +", "Sales", 2015, 374_372.0)
        assertRow(result, ScreenerHtml.PROFIT_LOSS, "Expenses +", "Expenses", 2026, 876_715.0)
        assertRow(result, ScreenerHtml.PROFIT_LOSS, "Expenses +", "Expenses TTM", 2026, 939_494.0, "TTM")
        assertRow(result, ScreenerHtml.PROFIT_LOSS, "Operating Profit", "Operating Profit", 2026, 179_065.0)
        assertRow(result, ScreenerHtml.PROFIT_LOSS, "OPM %", "OPM %", 2026, 17.0)
        assertRow(result, ScreenerHtml.PROFIT_LOSS, "Other Income +", "Other Income", 2026, 28_846.0)
        assertRow(result, ScreenerHtml.PROFIT_LOSS, "Interest", "Interest", 2026, 27_061.0)
        assertRow(result, ScreenerHtml.PROFIT_LOSS, "Depreciation", "Depreciation", 2026, 57_688.0)
        assertRow(result, ScreenerHtml.PROFIT_LOSS, "Net Profit +", "Net Profit", 2026, 95_754.0)
        assertRow(result, ScreenerHtml.PROFIT_LOSS, "EPS in Rs", "EPS", 2026, 59.69)

        assertRow(result, ScreenerHtml.BALANCE_SHEET, "Equity Capital", "Equity Capital", 2026, 13_532.0)
        assertRow(result, ScreenerHtml.BALANCE_SHEET, "Reserves", "Reserves", 2026, 890_498.0)
        assertRow(result, ScreenerHtml.BALANCE_SHEET, "Borrowings +", "Borrowings", 2026, 402_962.0)
        assertRow(result, ScreenerHtml.BALANCE_SHEET, "Total Assets", "Total Assets", 2026, 2_177_546.0)

        assertRow(result, ScreenerHtml.CASH_FLOW, "Cash from Operating Activity +", "CFO", 2026, 192_113.0)
        assertRow(result, ScreenerHtml.CASH_FLOW, "Free Cash Flow", "FCF", 2026, 70_023.0)
        assertRow(result, ScreenerHtml.CASH_FLOW, "CFO/OP", "CFO/OP", 2026, 113.0)

        assertRow(result, ScreenerHtml.SHAREHOLDING, "Promoters +", "Promoters", 2026, 50.0)
        assertRow(result, ScreenerHtml.SHAREHOLDING, "Promoters +", "Promoters", 2026, 50.48, "Jun 2026")
        assertRow(result, ScreenerHtml.SHAREHOLDING, "FIIs +", "FIIs", 2026, 18.67)
        assertRow(result, ScreenerHtml.SHAREHOLDING, "DIIs +", "DIIs", 2026, 20.46)
        assertRow(result, ScreenerHtml.SHAREHOLDING, "No. of Shareholders", "Shareholders", 2026, 4_421_289.0)
    }

    @Test
    fun `tcs annual statements`() {
        val result = ScreenerFixtures.parse("TCS")
        assertRow(result, ScreenerHtml.PROFIT_LOSS, "Sales +", "Sales", 2026, 267_021.0)
        assertRow(result, ScreenerHtml.PROFIT_LOSS, "Operating Profit", "Operating Profit", 2026, 72_398.0)
        assertRow(result, ScreenerHtml.PROFIT_LOSS, "Net Profit +", "Net Profit", 2026, 49_454.0)
        assertRow(result, ScreenerHtml.BALANCE_SHEET, "Borrowings +", "Borrowings", 2026, 11_283.0)
        assertRow(result, ScreenerHtml.CASH_FLOW, "Free Cash Flow", "FCF", 2026, 48_013.0)
        assertRow(result, ScreenerHtml.SHAREHOLDING, "Promoters +", "Promoters", 2026, 71.77, "Jun 2026")
    }

    @Test
    fun `hdfcbank annual statements use bank shaped rows`() {
        val result = ScreenerFixtures.parse("HDFCBANK")
        assertEquals(
            listOf(
                "Revenue +", "Interest", "Expenses +", "Financing Profit", "Financing Margin %",
                "Other Income +", "Depreciation", "Profit before tax", "Tax %", "Net Profit +",
                "EPS in Rs", "Dividend Payout %",
            ),
            result.snapshot.profitLoss.map { it.label },
        )
        assertRow(result, ScreenerHtml.PROFIT_LOSS, "Revenue +", "Revenue", 2026, 348_615.0)
        assertRow(result, ScreenerHtml.PROFIT_LOSS, "Financing Profit", "Financing Profit", 2026, -40_511.0)
        assertRow(result, ScreenerHtml.PROFIT_LOSS, "Net Profit +", "Net Profit", 2026, 79_219.0)
        assertRow(result, ScreenerHtml.BALANCE_SHEET, "Deposits", "Deposits", 2026, 3_099_638.0)
        // The singular spelling must be matched just like the plural.
        assertRow(result, ScreenerHtml.BALANCE_SHEET, "Borrowing", "Borrowing", 2026, 588_485.0)
        assertRow(result, ScreenerHtml.CASH_FLOW, "Free Cash Flow", "FCF", 2026, 110_144.0)
        assertNull(StatementParsers.rowByKey(result.sections[ScreenerHtml.SHAREHOLDING], "promoters"))
    }

    @Test
    fun `infy and titan annual statements`() {
        val infy = ScreenerFixtures.parse("INFY")
        assertRow(infy, ScreenerHtml.PROFIT_LOSS, "Sales +", "Sales", 2026, 178_650.0)
        assertRow(infy, ScreenerHtml.PROFIT_LOSS, "Net Profit +", "Net Profit", 2026, 29_474.0)
        assertRow(infy, ScreenerHtml.PROFIT_LOSS, "Interest", "Interest", 2019, 0.0)
        assertRow(infy, ScreenerHtml.CASH_FLOW, "Cash from Operating Activity +", "CFO", 2026, 33_986.0)
        assertRow(infy, ScreenerHtml.SHAREHOLDING, "Promoters +", "Promoters", 2026, 13.82, "Jun 2026")
        assertRow(infy, ScreenerHtml.SHAREHOLDING, "Promoters +", "Promoters", 2026, 14.38, "Mar 2026")

        val titan = ScreenerFixtures.parse("TITAN")
        assertRow(titan, ScreenerHtml.PROFIT_LOSS, "Sales +", "Sales", 2026, 87_584.0)
        assertRow(titan, ScreenerHtml.PROFIT_LOSS, "Net Profit +", "Net Profit", 2026, 5_073.0)
        assertRow(titan, ScreenerHtml.BALANCE_SHEET, "Borrowings +", "Borrowings", 2026, 30_621.0)
        assertRow(titan, ScreenerHtml.CASH_FLOW, "Cash from Operating Activity +", "CFO", 2025, -541.0)
        assertRow(titan, ScreenerHtml.CASH_FLOW, "Free Cash Flow", "FCF", 2025, -1_011.0)
        assertRow(titan, ScreenerHtml.SHAREHOLDING, "Promoters +", "Promoters", 2026, 52.9, "Jun 2026")
    }

    // --------------------------------------------------------------- CashFlowDerived

    @Test
    fun `reliance derived maps cover twelve years and hold rupees crore`() {
        val derived = ScreenerFixtures.parse("RELIANCE").snapshot.cashFlowDerived
        assertEquals(years, derived.cfoByYear.keys.toList())
        assertEquals(years, derived.patByYear.keys.toList())
        assertEquals(years, derived.fcfByYear.keys.toList())
        assertEquals(years, derived.revenueByYear.keys.toList())
        assertEquals(years, derived.otherIncomeByYear.keys.toList())
        assertEquals(years, derived.totalAssetsByYear.keys.toList())
        assertEquals(years, derived.debtByYear.keys.toList())
        assertEquals(years, derived.equityByYear.keys.toList())
        assertEquals(years, derived.interestByYear.keys.toList())
        assertEquals(years, derived.ebitByYear.keys.toList())
        assertEquals(years, derived.cfoToOpByYear.keys.toList())

        assertEquals(192_113.0, derived.cfoByYear[2026])
        assertEquals(95_754.0, derived.patByYear[2026])
        assertEquals(70_023.0, derived.fcfByYear[2026])
        assertEquals(1_055_780.0, derived.revenueByYear[2026])
        assertEquals(28_846.0, derived.otherIncomeByYear[2026])
        assertEquals(2_177_546.0, derived.totalAssetsByYear[2026])
        assertEquals(402_962.0, derived.debtByYear[2026])
        assertEquals(13_532.0 + 890_498.0, derived.equityByYear[2026], "equity = capital + reserves")
        assertEquals(27_061.0, derived.interestByYear[2026])
        assertEquals(179_065.0 + 57_688.0, derived.ebitByYear[2026], "EBIT = operating profit + depreciation")
        assertEquals(1.13, derived.cfoToOpByYear[2026], "page prints 113%; stored as a ratio")
        assertEquals(0.65, derived.cfoToOpByYear[2019], "negative-ish: 65% published, 0.65 stored")
        assertEquals(13_532.0 * 1e7 / 10.0, derived.sharesOutstanding)
    }

    @Test
    fun `tcs derived maps`() {
        val derived = ScreenerFixtures.parse("TCS").snapshot.cashFlowDerived
        assertEquals(52_094.0, derived.cfoByYear[2026])
        assertEquals(49_454.0, derived.patByYear[2026])
        assertEquals(48_013.0, derived.fcfByYear[2026])
        assertEquals(267_021.0, derived.revenueByYear[2026])
        assertEquals(72_398.0 + 5_560.0, derived.ebitByYear[2026])
        assertEquals(0.92, derived.cfoToOpByYear[2026])
        assertEquals(362.0 * 1e7 / 1.0, derived.sharesOutstanding)
    }

    @Test
    fun `hdfcbank derived maps leave ebit empty because the page has no operating profit row`() {
        val result = ScreenerFixtures.parse("HDFCBANK")
        val derived = result.snapshot.cashFlowDerived
        assertEquals(emptyMap(), derived.ebitByYear, "no Operating Profit row, so no EBIT at all")
        assertEquals(348_615.0, derived.revenueByYear[2026], "revenue comes from the 'Revenue +' row")
        assertEquals(79_219.0, derived.patByYear[2026])
        assertEquals(110_144.0, derived.fcfByYear[2026])
        assertEquals(113_506.0, derived.cfoByYear[2026])
        assertEquals(588_485.0, derived.debtByYear[2026], "'Borrowing' singular matched")
        assertEquals(1_539.0 + 584_520.0, derived.equityByYear[2026])
        assertEquals(1_539.0 * 1e7 / 1.0, derived.sharesOutstanding)
        assertNull(derived.promoterPledgePercent)

        val note = result.health.notes.firstOrNull { it.contains("Operating Profit") }
        assertNotNull(note, "the bank-shaped P&L must be called out in the health notes")
        assertTrue(note.contains("§7.3"), note)
    }

    @Test
    fun `infy and titan derived maps`() {
        val infy = ScreenerFixtures.parse("INFY").snapshot.cashFlowDerived
        assertEquals(33_986.0, infy.cfoByYear[2026])
        assertEquals(29_474.0, infy.patByYear[2026])
        assertEquals(31_259.0, infy.fcfByYear[2026])
        assertEquals(42_280.0 + 4_902.0, infy.ebitByYear[2026])
        assertEquals(0.0, infy.interestByYear[2019], "the page genuinely publishes 0, which is data")
        assertEquals(2_024.0 * 1e7 / 5.0, infy.sharesOutstanding)

        val titan = ScreenerFixtures.parse("TITAN").snapshot.cashFlowDerived
        assertEquals(5_590.0, titan.cfoByYear[2026])
        assertEquals(5_073.0, titan.patByYear[2026])
        assertEquals(4_713.0, titan.fcfByYear[2026])
        assertEquals(-541.0, titan.cfoByYear[2025])
        assertEquals(8_357.0 + 826.0, titan.ebitByYear[2026])
        assertEquals(89.0 * 1e7 / 1.0, titan.sharesOutstanding)
    }

    @Test
    fun `lines the collapsed balance sheet does not publish stay empty and are reported`() {
        for (symbol in ScreenerFixtures.symbols) {
            val result = ScreenerFixtures.parse(symbol)
            val derived = result.snapshot.cashFlowDerived
            assertEquals(emptyMap(), derived.receivablesByYear, "$symbol receivables")
            assertEquals(emptyMap(), derived.inventoryByYear, "$symbol inventory")
            assertEquals(emptyMap(), derived.goodwillByYear, "$symbol goodwill")
            assertNull(derived.promoterPledgePercent, "$symbol pledge")

            val notes = result.health.notes
            for (line in listOf("Trade Receivables", "Inventory", "Goodwill", "promoter pledge")) {
                assertTrue(
                    notes.any { it.contains(line) },
                    "$symbol should report that $line is absent from the page",
                )
            }
        }
    }

    @Test
    fun `TTM never leaks into a per-year map`() {
        for (symbol in ScreenerFixtures.symbols) {
            val derived = ScreenerFixtures.parse(symbol).snapshot.cashFlowDerived
            // TTM revenue for RELIANCE is 1,123,055; if it had leaked in there would be a year
            // key that is not a real fiscal year, or a 13th entry.
            assertEquals(12, derived.revenueByYear.size, symbol)
            assertTrue(derived.revenueByYear.keys.none { it < 2015 || it > 2026 }, symbol)
        }
    }

    /**
     * Asserts one value of one row.
     *
     * Addressed either by **fiscal year** (`Mar 2026` → 2026) or by **column label** (`"Jun
     * 2026"`, `"TTM"`). The label form exists because `shareholding` is quarterly and publishes
     * two columns per calendar year, so a year alone does not identify a column there.
     */
    @Test
    fun `a pledge row would be read if the page published one`() {
        // None of the five fixtures carry a pledge figure, so the "stays null" path is the one
        // the golden files exercise. This pins the other direction, so the mapping cannot rot into
        // dead code that would silently keep reporting null after the page adds the row.
        val table = ScreenerHtml.document(
            """
            <section id="shareholding"><table><thead><tr><th></th>
              <th>Mar 2025</th><th>Jun 2026</th></tr></thead>
            <tbody>
              <tr><td>Promoters +</td><td>51.00%</td><td>50.48%</td></tr>
              <tr><td>Share Pledge</td><td>-</td><td>1.25%</td></tr>
            </tbody></table></section>
            """.trimIndent(),
        ).let { ScreenerHtml.tableRowsById(it, ScreenerHtml.SHAREHOLDING) }

        assertEquals(1.25, DerivedMetrics.latestValue(table, "sharepledge"))
        assertEquals(50.48, DerivedMetrics.latestPromoterPercent(table))
    }

    private fun assertRow(
        result: FundamentalsParseResult,
        section: String,
        label: String,
        what: String,
        year: Int,
        expected: Double,
        columnLabel: String? = null,
    ) {
        val table = result.sections.getValue(section)
        val index = if (columnLabel != null) {
            val at = table.years.indexOf(columnLabel)
            assertTrue(at >= 0, "$section has no column '$columnLabel'")
            at
        } else {
            assertNotNull(ScreenerHtml.yearIndexOf(table, year), "$section $year not published")
            ScreenerHtml.yearIndexOf(table, year)!!
        }
        val row = table.rows.firstOrNull { it.label == label }
        assertNotNull(row, "$section has no row '$label'")
        assertEquals(expected, row.values[index], "$section / $what / ${columnLabel ?: year}")
    }
}
