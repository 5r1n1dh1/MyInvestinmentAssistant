package com.mia.app.data.fundamentals

import com.mia.app.domain.model.QuarterRecord
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `quarters` table: count, ordering, and the arithmetic identity that proves the columns
 * were addressed by header rather than by position.
 */
class ScreenerQuartersGoldenTest {

    @Test
    fun `every fixture publishes exactly thirteen quarters`() {
        for (symbol in ScreenerFixtures.symbols) {
            assertEquals(13, ScreenerFixtures.parse(symbol).snapshot.quarters.size, symbol)
            assertEquals(
                13,
                StatementParsers.EXPECTED_QUARTERS,
                "plan.md §3.3 verified 13; the constant must track the live page",
            )
        }
    }

    @Test
    fun `quarters are returned newest first and years strictly descend`() {
        for (symbol in ScreenerFixtures.symbols) {
            val quarters = ScreenerFixtures.parse(symbol).snapshot.quarters
            assertEquals(2026, quarters.first().year, symbol)
            assertEquals(2, quarters.first().quarter, "$symbol Jun 2026 is calendar Q2")
            assertEquals(2023, quarters.last().year, symbol)
            assertEquals(2, quarters.last().quarter, "$symbol Jun 2023 is calendar Q2")

            for (i in 1 until quarters.size) {
                val newer = quarters[i - 1]
                val older = quarters[i]
                assertTrue(
                    newer.year > older.year ||
                        (newer.year == older.year && newer.quarter > older.quarter),
                    "$symbol is not strictly descending at index $i: ${newer.year}Q${newer.quarter} " +
                        "then ${older.year}Q${older.quarter}",
                )
            }
        }
    }

    @Test
    fun `the oldest-first view reproduces the page's own column order`() {
        for (symbol in ScreenerFixtures.symbols) {
            val oldestFirst = StatementParsers.quartersOldestFirst(
                ScreenerHtml.document(ScreenerFixtures.html(symbol)),
            )
            assertEquals(13, oldestFirst.size, symbol)
            assertEquals(2023, oldestFirst.first().year, symbol)
            assertEquals(2, oldestFirst.first().quarter, symbol)
            assertEquals(2026, oldestFirst.last().year, symbol)
        }
    }

    @Test
    fun `operating profit equals sales minus expenses wherever the page publishes both`() {
        var checked = 0
        for (symbol in ScreenerFixtures.symbols) {
            for (q in ScreenerFixtures.parse(symbol).snapshot.quarters) {
                val sales = q.sales
                val expenses = q.expenses
                val op = q.operatingProfit
                if (sales == null || expenses == null || op == null) continue
                checked++
                // Screener.in rounds each of the three to the nearest crore, so the identity
                // holds to within half a crore of each figure.
                assertTrue(
                    abs(sales - expenses - op) <= 1.5,
                    "$symbol ${q.year}Q${q.quarter}: $sales - $expenses != $op",
                )
            }
        }
        // Four non-bank fixtures x 13 quarters; HDFCBANK publishes no operating profit at all.
        assertEquals(52, checked, "expected 4 x 13 quarters with all three figures present")
    }

    @Test
    fun `reliance quarters in full`() {
        val expected = listOf(
            Triple(2026, 2, listOf(309_468.0, 261_951.0, 47_517.0, 15.0, 6_550.0, 8_337.0, 15_100.0, 15.48)),
            Triple(2026, 1, listOf(294_059.0, 249_918.0, 44_141.0, 15.0, 4_447.0, 6_585.0, 14_808.0, 12.54)),
            Triple(2025, 4, listOf(264_905.0, 218_887.0, 46_018.0, 17.0, 4_914.0, 6_613.0, 14_622.0, 13.78)),
            Triple(2025, 3, listOf(254_623.0, 208_738.0, 45_885.0, 18.0, 4_482.0, 6_827.0, 14_416.0, 13.42)),
            Triple(2025, 2, listOf(243_632.0, 200_727.0, 42_905.0, 18.0, 15_119.0, 7_036.0, 13_842.0, 19.95)),
            Triple(2025, 1, listOf(261_388.0, 217_556.0, 43_832.0, 17.0, 4_905.0, 6_155.0, 13_479.0, 14.34)),
            Triple(2024, 4, listOf(239_986.0, 196_197.0, 43_789.0, 18.0, 4_214.0, 6_179.0, 13_181.0, 13.70)),
            Triple(2024, 3, listOf(231_535.0, 192_477.0, 39_058.0, 17.0, 4_876.0, 6_017.0, 12_880.0, 12.24)),
            Triple(2024, 2, listOf(231_784.0, 193_019.0, 38_765.0, 17.0, 3_983.0, 5_918.0, 13_596.0, 11.19)),
            Triple(2024, 1, listOf(236_533.0, 194_017.0, 42_516.0, 18.0, 4_534.0, 5_761.0, 13_569.0, 14.01)),
            Triple(2023, 4, listOf(225_086.0, 184_430.0, 40_656.0, 18.0, 3_869.0, 5_789.0, 12_903.0, 12.76)),
            Triple(2023, 3, listOf(231_886.0, 190_918.0, 40_968.0, 18.0, 3_841.0, 5_731.0, 12_585.0, 12.85)),
            Triple(2023, 2, listOf(207_559.0, 169_466.0, 38_093.0, 18.0, 3_813.0, 5_837.0, 11_775.0, 11.83)),
        )
        assertEquals(expected, render(ScreenerFixtures.parse("RELIANCE").snapshot.quarters))
    }

    @Test
    fun `tcs infy and titan quarters`() {
        val tcs = ScreenerFixtures.parse("TCS").snapshot.quarters
        assertQ(tcs[0], 2026, 2, sales = 72_275.0, op = 18_556.0, opm = 26.0, eps = 36.90)
        assertQ(tcs[12], 2023, 2, sales = 59_381.0, op = 14_998.0, opm = 25.0, eps = 30.26)

        val infy = ScreenerFixtures.parse("INFY").snapshot.quarters
        assertQ(infy[0], 2026, 2, sales = 48_211.0, op = 11_409.0, opm = 24.0, eps = 19.15)
        assertQ(infy[12], 2023, 2, sales = 37_933.0, op = 9_064.0, opm = 24.0, eps = 14.32)

        val titan = ScreenerFixtures.parse("TITAN").snapshot.quarters
        assertQ(titan[0], 2026, 2, sales = 21_356.0, op = 2_890.0, opm = 14.0, eps = 20.02)
        assertQ(titan[12], 2023, 2, sales = 11_897.0, op = 1_125.0, opm = 9.0, eps = 8.48)
    }

    @Test
    fun `hdfcbank leaves operating profit and margin null instead of borrowing financing profit`() {
        val quarters = ScreenerFixtures.parse("HDFCBANK").snapshot.quarters
        assertEquals(13, quarters.size)
        for (q in quarters) {
            assertNull(q.operatingProfit, "no Operating Profit row on a bank P&L")
            assertNull(q.opmPercent, "no OPM % row on a bank P&L")
        }
        // The rows it *does* publish are still read.
        assertQ(quarters[0], 2026, 2, sales = 90_575.0, eps = 12.50)
        assertEquals(58_292.0, quarters[0].expenses)
        assertEquals(42_535.0, quarters[0].otherIncome)
        assertEquals(47_626.0, quarters[0].interest)
        assertEquals(0.0, quarters[0].depreciation, "the page publishes a genuine zero")
    }

    @Test
    fun `a quarter count other than thirteen is reported rather than assumed`() {
        val html = keepFirstQuarterColumns(ScreenerFixtures.html("TCS"), keep = 5)
        val section = StatementParsers.quarters(ScreenerHtml.document(html))
        assertEquals(5, section.records.size)
        // The retained columns are the page's first five, so the most recent is Jun 2024.
        assertEquals(2024, section.records.first().year)
        assertEquals(2, section.records.first().quarter)
        assertEquals(2023, section.records.last().year)
        val note = section.notes.firstOrNull { it.contains("quarter column(s)") }
        assertNotNull(note, "a changed quarter depth must be surfaced")
        assertTrue(note.contains("plan.md §3.3 verified 13"), note)
    }

    private fun render(quarters: List<QuarterRecord>) = quarters.map { q ->
        Triple(q.year, q.quarter, listOfNotNull(
            q.sales, q.expenses, q.operatingProfit, q.opmPercent,
            q.otherIncome, q.interest, q.depreciation, q.eps,
        ))
    }

    private fun assertQ(
        q: QuarterRecord,
        year: Int,
        quarter: Int,
        sales: Double? = null,
        op: Double? = null,
        opm: Double? = null,
        eps: Double? = null,
    ) {
        assertEquals(year, q.year)
        assertEquals(quarter, q.quarter)
        if (sales != null) assertEquals(sales, q.sales)
        if (op != null) assertEquals(op, q.operatingProfit)
        if (opm != null) assertEquals(opm, q.opmPercent)
        if (eps != null) assertEquals(eps, q.eps)
    }

    /**
     * Keeps only the first [keep] data columns of the real `quarters` card: the header cells are
     * removed outright and the surplus `<td>`s of every body row are dropped, so the table stays
     * structurally honest while being shallower than the published 13.
     */
    private fun keepFirstQuarterColumns(html: String, keep: Int): String {
        val start = html.indexOf("<section id=\"quarters\"")
        val end = html.indexOf("</section>", start)
        val section = html.substring(start, end)
        var trimmed = section

        val headers = HEADER.findAll(section).toList()
        headers.forEachIndexed { index, header -> if (index >= keep) trimmed = trimmed.replace(header.value, "") }

        val bodyRows = ROW.findAll(section).drop(1)
        for (row in bodyRows) {
            val cells = CELL.findAll(row.value).toList()
            cells.drop(keep + 1).forEach { trimmed = trimmed.replace(it.value, "") }
        }
        return html.substring(0, start) + trimmed + html.substring(end)
    }

    private companion object {
        val HEADER = Regex("<th[^>]*data-date-key[^>]*>.*?</th>", RegexOption.DOT_MATCHES_ALL)
        val ROW = Regex("<tr[^>]*>.*?</tr>", RegexOption.DOT_MATCHES_ALL)
        val CELL = Regex("<td[^>]*>.*?</td>", RegexOption.DOT_MATCHES_ALL)
    }
}
