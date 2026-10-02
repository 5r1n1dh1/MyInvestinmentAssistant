package com.mia.app.data.fundamentals

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Parser resilience (plan.md §12: "A parser must return a health flag, not an empty list.
 * Silent-empty is indistinguishable from 'no red flags' — the most dangerous possible failure").
 *
 * Every case below must produce a [com.mia.app.domain.model.ParserHealth] with the right missing
 * sections. None may throw, and none may hand back an "ok" snapshot with nothing in it.
 */
class ScreenerResilienceTest {

    // ------------------------------------------------------------------- truncated HTML

    @Test
    fun `truncated html reports every section it lost and never throws`() {
        val html = ScreenerFixtures.html("RELIANCE")
        for (fraction in listOf(0.01, 0.05, 0.1, 0.25, 0.33, 0.5)) {
            val cut = html.substring(0, (html.length * fraction).toInt())
            val result = FundamentalsParserFacade.parse(cut, "RELIANCE", ScreenerFixtures.AS_OF)
            assertFalse(result.health.ok, "a ${(fraction * 100).toInt()}% page cannot be ok")
            assertTrue(
                result.health.sectionsMissing.isNotEmpty(),
                "a ${(fraction * 100).toInt()}% page must name what it lost",
            )
            assertTrue(
                result.health.sectionsMissing.all { it in ScreenerHtml.SECTION_IDS },
                "unknown section names: ${result.health.sectionsMissing}",
            )
        }
    }

    @Test
    fun `cutting the page before each section names that section and everything after it`() {
        val html = ScreenerFixtures.html("RELIANCE")
        // Sections in page order. `top-ratios` is a <ul>, not a <section>.
        val order = listOf(
            ScreenerHtml.TOP_RATIOS,
            ScreenerHtml.QUARTERS,
            ScreenerHtml.PROFIT_LOSS,
            ScreenerHtml.BALANCE_SHEET,
            ScreenerHtml.CASH_FLOW,
            ScreenerHtml.SHAREHOLDING,
        )
        for ((cutAt, id) in order.withIndex()) {
            val start = html.indexOf(elementOpenFor(id))
            check(start >= 0) { "fixture has no $id" }
            val result = FundamentalsParserFacade.parse(
                html.substring(0, start),
                "RELIANCE",
                ScreenerFixtures.AS_OF,
            )
            assertFalse(result.health.ok, id)
            assertEquals(order.drop(cutAt), result.health.sectionsMissing, "cut before $id")
            assertEquals(order.take(cutAt), result.health.sectionsFound, "cut before $id")
        }
    }

    @Test
    fun `a page truncated before top-ratios reports no ratios rather than zeros`() {
        val html = ScreenerFixtures.html("RELIANCE")
        val cut = html.substring(0, html.indexOf("<ul id=\"top-ratios\""))
        val result = FundamentalsParserFacade.parse(cut, "RELIANCE", ScreenerFixtures.AS_OF)
        val ratios = result.snapshot.ratios
        assertNull(ratios.roce)
        assertNull(ratios.roe)
        assertNull(ratios.pe)
        assertNull(ratios.marketCap)
        assertNull(ratios.dividendYield)
        assertNull(ratios.promoterHolding)
        assertTrue(ScreenerHtml.TOP_RATIOS in result.health.sectionsMissing)
        assertTrue(result.health.notes.any { it.contains("top-ratios is empty") })
    }

    @Test
    fun `empty input is a clean not-ok result`() {
        val result = FundamentalsParserFacade.parse("", "RELIANCE", ScreenerFixtures.AS_OF)
        assertFalse(result.health.ok)
        assertEquals(ScreenerHtml.SECTION_IDS, result.health.sectionsMissing)
        assertEquals(emptyList(), result.snapshot.quarters)
        assertEquals("RELIANCE", result.snapshot.name, "falls back to the requested symbol")
    }

    @Test
    fun `an html error page is a clean not-ok result`() {
        for (body in listOf(
            "<html><head><title>403 Forbidden</title></head><body><h1>403 Forbidden</h1></body></html>",
            "<html><body>Rate limit exceeded. Please try again later.</body></html>",
            "<!DOCTYPE html><html><head><title>503 Service Unavailable</title></head></html>",
            "<html><body><h1>Reliance Industries Ltd</h1></body></html>",
        )) {
            val result = FundamentalsParserFacade.parse(body, "RELIANCE", ScreenerFixtures.AS_OF)
            assertFalse(result.health.ok, body.take(40))
            assertEquals(ScreenerHtml.SECTION_IDS, result.health.sectionsMissing, body.take(40))
            assertEquals(0, result.sections.size, body.take(40))
        }
    }

    // ------------------------------------------------------------------ renamed classes

    @Test
    fun `renaming nowrap value to value nowrap keeps every ratio identical`() {
        val original = ScreenerFixtures.html("RELIANCE")
        val renamed = original.replace("class=\"nowrap value\"", "class=\"value nowrap\"")
        assertTrue(renamed != original, "the fixture must actually contain the old class")
        assertFalse(renamed.contains("nowrap value"))

        val before = ScreenerFixtures.parse("RELIANCE").snapshot.ratios
        val after = FundamentalsParserFacade.parse(renamed, "RELIANCE", ScreenerFixtures.AS_OF).snapshot.ratios
        assertEquals(before, after, "a class reorder must be invisible to the parser")
    }

    @Test
    fun `renaming the number span class still parses via the value cell text`() {
        val original = ScreenerFixtures.html("RELIANCE")
        val renamed = original.replace("class=\"number\"", "class=\"digits\"")
        assertFalse(renamed.contains("class=\"number\""))
        val result = FundamentalsParserFacade.parse(renamed, "RELIANCE", ScreenerFixtures.AS_OF)
        val top = TopRatiosParser.parse(ScreenerHtml.document(renamed))
        // No inner number span, so the whole cell text is used and ₹ / Cr. must not break it.
        assertEquals(10.3, top.rocePercent)
        assertEquals(21.2, top.stockPe)
        assertEquals(1_580_194.0, top.marketCapCr)
        assertEquals(10.3, result.snapshot.ratios.roce)
    }

    // ------------------------------------------------------------------- missing sections

    @Test
    fun `a missing quarters section is named in sectionsMissing and marks the result not ok`() {
        val html = removeSection(ScreenerFixtures.html("RELIANCE"), ScreenerHtml.QUARTERS)
        assertFalse(html.contains("id=\"quarters\""))
        val result = FundamentalsParserFacade.parse(html, "RELIANCE", ScreenerFixtures.AS_OF)

        assertFalse(result.health.ok)
        assertEquals(listOf(ScreenerHtml.QUARTERS), result.health.sectionsMissing)
        assertFalse(ScreenerHtml.QUARTERS in result.health.sectionsFound)
        assertTrue(result.health.notes.any { it.contains("section 'quarters' is absent") })
        assertEquals(emptyList(), result.snapshot.quarters)
        // Everything else still parses — a partial page is still useful, just not ok.
        assertEquals(12, result.snapshot.profitLoss.size)
        assertNotNull(result.snapshot.ratios.pe)
        assertEquals(21.2, result.snapshot.ratios.pe)
    }

    @Test
    fun `a missing top-ratios section is named and blanks every ratio that depended on it`() {
        val html = removeSection(ScreenerFixtures.html("TCS"), ScreenerHtml.TOP_RATIOS)
        val result = FundamentalsParserFacade.parse(html, "TCS", ScreenerFixtures.AS_OF)
        assertFalse(result.health.ok)
        assertEquals(listOf(ScreenerHtml.TOP_RATIOS), result.health.sectionsMissing)
        assertNull(result.snapshot.ratios.roce)
        assertNull(result.snapshot.ratios.roe)
        assertNull(result.snapshot.ratios.pe)
        assertNull(result.snapshot.ratios.marketCap)
        // Derived from the statements, so still present.
        assertEquals(49_454.0, result.snapshot.ratios.pat)
        assertEquals(13, result.snapshot.quarters.size)
    }

    @Test
    fun `each section removed in turn is reported by name`() {
        for (id in ScreenerHtml.SECTION_IDS) {
            val html = removeSection(ScreenerFixtures.html("INFY"), id)
            val result = FundamentalsParserFacade.parse(html, "INFY", ScreenerFixtures.AS_OF)
            assertFalse(result.health.ok, id)
            assertEquals(listOf(id), result.health.sectionsMissing, id)
        }
    }

    @Test
    fun `a section present but with no data rows is reported missing not silently empty`() {
        val html = emptyBody(ScreenerFixtures.html("TCS"), ScreenerHtml.PROFIT_LOSS)
        val result = FundamentalsParserFacade.parse(html, "TCS", ScreenerFixtures.AS_OF)
        assertFalse(result.health.ok)
        assertEquals(listOf(ScreenerHtml.PROFIT_LOSS), result.health.sectionsMissing)
        assertTrue(
            result.health.notes.any { it.contains("no data rows") },
            result.health.notes.toString(),
        )
        assertEquals(emptyList(), result.snapshot.profitLoss)
        // The ratios that came from the P&L are null, not zero.
        assertNull(result.snapshot.ratios.pat)
        assertNull(result.snapshot.ratios.fcf)
    }

    @Test
    fun `a table with no period columns is reported instead of producing junk`() {
        val doc = ScreenerHtml.document(
            "<section id=\"quarters\"><table><thead><tr><th></th></tr></thead>" +
                "<tbody><tr><td>Sales +</td></tr></tbody></table></section>",
        )
        val section = StatementParsers.quarters(doc)
        assertEquals(emptyList(), section.records)
        assertFalse(section.isUsable)
        assertTrue(section.notes.any { it.contains("no data rows") || it.contains("no quarter-shaped") })
    }

    // ------------------------------------------------------------------- degenerate cells

    @Test
    fun `hyphen and NA cells stay null through the whole pipeline`() {
        val html = """
            <html><body><h1>Degenerate Ltd</h1>
            <section id="quarters"><table><thead><tr><th></th>
              <th data-date-key="2025-03-31">Mar 2025</th>
              <th data-date-key="2025-06-30">Jun 2025</th></tr></thead>
            <tbody>
              <tr><td>Sales +</td><td>1,000</td><td>-</td></tr>
              <tr><td>Expenses +</td><td>600</td><td>NA</td></tr>
              <tr><td>Operating Profit</td><td>400</td><td>-</td></tr>
              <tr><td>EPS in Rs</td><td>1.25</td><td></td></tr>
            </tbody></table></section>
            </body></html>
        """.trimIndent()
        val result = FundamentalsParserFacade.parse(html, "DEGEN", ScreenerFixtures.AS_OF)
        val q1 = result.snapshot.quarters.single { it.year == 2025 && it.quarter == 1 }
        val q2 = result.snapshot.quarters.single { it.year == 2025 && it.quarter == 2 }
        assertEquals(1_000.0, q1.sales)
        assertEquals(600.0, q1.expenses)
        assertEquals(400.0, q1.operatingProfit)
        assertEquals(1.25, q1.eps)

        assertNull(q2.sales)
        assertNull(q2.expenses)
        assertNull(q2.operatingProfit)
        assertNull(q2.eps)

        // The derived per-year map must not contain a fabricated 0.0 for the bad quarter.
        assertFalse(result.health.ok, "only top-ratios and statements 2..6 exist here")
        assertEquals(emptyMap(), result.snapshot.cashFlowDerived.revenueByYear)
    }

    @Test
    fun `a zero divisor never becomes an infinite ratio`() {
        val ratios = RatioMapper.map(
            symbol = "ZERODEP",
            asOf = ScreenerFixtures.AS_OF,
            topRatios = TopRatiosSnapshot(
                mapOf(
                    "bookvalue" to TopRatiosSnapshot.TopRatioField("Book Value", "0", 0.0, listOf(0.0)),
                    "currentprice" to TopRatiosSnapshot.TopRatioField("Current Price", "100", 100.0, listOf(100.0)),
                ),
            ),
            derived = com.mia.app.domain.model.CashFlowDerived(),
            profitLoss = null,
            shareholding = null,
        )
        assertNull(ratios.pb, "price / 0 is undefined, not infinity")
        assertNull(ratios.interestCoverage)
        assertNull(ratios.revenueCagr3y)
        assertNull(ratios.revenueGrowth)
        assertNull(ratios.fcf)
        assertNull(ratios.pat)
    }

    @Test
    fun `a nonsense document with duplicated ids still yields health rather than an exception`() {
        val html = ScreenerFixtures.html("TCS") +
            "<section id=\"quarters\"><table><tbody><tr><td>Duplicate</td></tr></tbody></table></section>"
        val result = FundamentalsParserFacade.parse(html, "TCS", ScreenerFixtures.AS_OF)
        // The first element with the id wins; whatever it parses to, the result must be described.
        assertTrue(result.health.sectionsFound.contains(ScreenerHtml.QUARTERS) ||
            result.health.sectionsMissing.contains(ScreenerHtml.QUARTERS))
        assertNotNull(result.health)
    }

    // ---------------------------------------------------------------------- helpers

    /** `top-ratios` is a `<ul>` on the page; the other five cards are `<section>`s. */
    private fun elementOpenFor(id: String): String =
        if (id == ScreenerHtml.TOP_RATIOS) "<ul id=\"$id\"" else "<section id=\"$id\""

    private fun removeSection(html: String, id: String): String {
        val start = html.indexOf(elementOpenFor(id))
        check(start >= 0) { "fixture has no section $id" }
        val closer = if (id == ScreenerHtml.TOP_RATIOS) "</ul>" else "</section>"
        val end = html.indexOf(closer, start)
        check(end >= 0) { "fixture section $id is not closed" }
        return html.substring(0, start) + html.substring(end + closer.length)
    }

    private fun emptyBody(html: String, id: String): String {
        val start = html.indexOf(elementOpenFor(id))
        val end = html.indexOf("</section>", start)
        val section = html.substring(start, end)
        val bodyStart = section.indexOf("<tbody>")
        val bodyEnd = section.indexOf("</tbody>")
        val emptied = section.substring(0, bodyStart) + "<tbody>\n" + section.substring(bodyEnd)
        return html.substring(0, start) + emptied + html.substring(end)
    }
}
