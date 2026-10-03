package com.mia.app.data.fundamentals

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the `ratios` card parser does when the page is not the page it was written for.
 *
 * ### The rule under test
 * plan.md §12: a parser returns a health flag, not an empty list, and §13: substring matching over
 * exact strings. Every case below asserts the *same* invariant from a different angle — an
 * unreadable card produces a **report**, and no case may end in a zero, an empty list with no
 * note, or an exception.
 *
 * ### The synthetic pages here are small on purpose
 * The golden tests own "the real markup is parsed correctly". These own "a plausible wrong page is
 * described correctly". Writing 12-column synthetic tables would only reproduce the fixtures, so
 * each case is the smallest thing that breaks exactly one rule.
 */
class RatiosCardResilienceTest {

    // ------------------------------------------------------------------ card missing entirely

    @Test
    fun `an absent card is reported not silently empty`() {
        val card = RatiosCardParser.parse("<html><body><h1>No card here</h1></body></html>")
        assertTrue(card.isAbsent, "the section element was not on the page")
        assertTrue(card.isEmpty)
        assertFalse(card.isUsable)
        assertEquals(emptyList(), card.periods)
        assertEquals(emptyMap(), card.rows)
        assertNull(card.table)
        assertTrue(
            card.notes.any { it == "section 'ratios' is absent from the page" },
            card.notes.toString(),
        )
    }

    @Test
    fun `a page whose card was removed names it in sectionsMissing and marks the result not ok`() {
        val html = removeSection(ScreenerFixtures.html("RELIANCE"), RatiosCardParser.SECTION_ID)
        assertFalse(html.contains("id=\"ratios\""))
        val result = FundamentalsParserFacade.parse(html, "RELIANCE", ScreenerFixtures.AS_OF)

        assertFalse(result.health.ok, "a page with no card cannot be a complete fundamentals read")
        assertEquals(listOf(RatiosCardParser.SECTION_ID), result.health.sectionsMissing)
        assertFalse(RatiosCardParser.SECTION_ID in result.health.sectionsFound)
        assertTrue(result.health.notes.any { it.contains("section 'ratios' is absent") })

        // The series are absent, not zero.
        assertTrue(result.ratioCard.dsoByYear.isEmpty())
        assertTrue(result.ratioCard.inventoryDaysByYear.isEmpty())
        assertTrue(result.ratioCard.extras.debtorDays.isEmpty())
        assertTrue(result.ratioCard.isEmpty)
        assertNull(result.ratioCard.extras.debtorDaysFor(2026))
    }

    @Test
    fun `the ratios card is a first class section in the canonical section list`() {
        assertTrue(RatiosCardParser.SECTION_ID in FundamentalsParserFacade.SECTION_IDS)
        // Inserted in page order: after cash-flow, before shareholding, per the real markup.
        val order = FundamentalsParserFacade.SECTION_IDS
        assertEquals(
            listOf("top-ratios", "quarters", "profit-loss", "balance-sheet", "cash-flow",
                "ratios", "shareholding"),
            order,
        )
        // And a superset of the older six, so ScreenerInSource.isComplete stays satisfied.
        assertTrue(ScreenerHtml.SECTION_IDS.all { it in order })
        for (symbol in ScreenerFixtures.symbols) {
            assertTrue(
                "ratios" in ScreenerFixtures.parse(symbol).health.sectionsFound,
                "$symbol publishes the card, so it must be reported as found",
            )
        }
    }

    // ------------------------------------------------------------------------ renamed label

    @Test
    fun `a renamed debtor days label is reported as renamed not as zero`() {
        // "Days Sales Outstanding" is a plausible alternative name and is deliberately **not** in
        // the alias list: assuming an unknown label means DSO is how a parser invents a figure.
        val html = cardHtml(rows = listOf("Days Sales Outstanding" to listOf("31.0", "22.0")))
        val card = RatiosCardParser.parse(html)
        assertTrue(card.isUsable)

        // The row is captured under its new key, so nothing is lost from the card itself.
        assertEquals(listOf("dayssalesoutstanding"), card.rowKeys)
        assertEquals(listOf("Days Sales Outstanding"), card.rowLabels)
        assertEquals(22.0, card.row("dayssalesoutstanding")?.byPeriod?.getValue("Mar 2026"))

        // But the alias list does not cover it, so the *named series* stays empty rather than
        // guessing that an unknown label means DSO.
        assertTrue(card.debtorDays.isEmpty(), "an unrecognised label must not populate the series")
        assertNull(card.row(*RatiosCardParser.DEBTOR_DAYS_KEYS))
        // A note is required so the absence is visible rather than looking like a card with no DSO.
        val derived = DerivedMetrics.ratioCard(card)
        assertTrue(derived.dsoByYear.isEmpty())
        assertTrue(derived.extras.debtorDays.isEmpty())
    }

    @Test
    fun `a documented alias of debtor days does populate the series`() {
        // The aliases exist so a *plausible* rename is still read; each is one published synonym
        // of the same measure, not a guess.
        for (alias in listOf("Receivable Days", "Trade Receivable Days", "DSO", "Debtor Day")) {
            val card = RatiosCardParser.parse(cardHtml(rows = listOf(alias to listOf("31.0"))))
            assertEquals(31.0, card.debtorDays.getValue("Mar 2025"), alias)
            assertEquals(31.0, DerivedMetrics.ratioCard(card).dsoByYear.getValue(2025), alias)
        }
    }

    @Test
    fun `a renamed row on a real fixture keeps the rest of the card and reports the loss`() {
        val html = ScreenerFixtures.html("RELIANCE").replace("Debtor Days", "Days Sales Outstanding")
        assertFalse(html.contains("Debtor Days"), "the fixture must actually contain the old label")
        val card = RatiosCardParser.parse(html)

        assertTrue(card.isUsable, "six other rows survive, so the card is still found")
        assertEquals(88.0, card.inventoryDays.getValue("Mar 2026"), "the untouched row still parses")
        assertTrue(card.debtorDays.isEmpty(), "the renamed row does not silently become DSO")
        assertNull(card.row(*RatiosCardParser.DEBTOR_DAYS_KEYS))

        val result = FundamentalsParserFacade.parse(html, "RELIANCE", ScreenerFixtures.AS_OF)
        assertTrue(
            result.health.notes.any {
                it.startsWith("'Debtor Days' is not published on this page's 'ratios' card")
            },
            result.health.notes.toString(),
        )
        assertTrue(result.ratioCard.dsoByYear.isEmpty())
        assertEquals(88.0, result.ratioCard.inventoryDaysByYear[2026], "inventory days survives")
    }

    // --------------------------------------------------------------------- placeholder cells

    @Test
    fun `a hyphen cell is absent from the series not zero`() {
        val card = RatiosCardParser.parse(
            cardHtml(rows = listOf("Debtor Days" to listOf("20.0", "-", "18.0"))),
        )
        assertEquals(mapOf("Mar 2025" to 20.0, "Mar 2027" to 18.0), card.debtorDays)
        assertNull(card.debtorDays["Mar 2026"], "a hyphen is not zero days")
        assertEquals(listOf(20.0, null, 18.0), card.row("debtordays")?.values)

        val derived = DerivedMetrics.ratioCard(card)
        assertEquals(20.0, derived.dsoByYear[2025])
        assertNull(derived.dsoByYear[2026], "the hyphen year is dropped, not zero-filled")
        assertEquals(18.0, derived.dsoByYear[2027])
        assertTrue(derived.dsoByYear.values.none { it == 0.0 }, "never a fabricated zero")
    }

    @Test
    fun `an NA cell behaves exactly like a hyphen`() {
        val card = RatiosCardParser.parse(
            cardHtml(rows = listOf("Inventory Days" to listOf("NA", "88.0"))),
        )
        assertEquals(mapOf("Mar 2026" to 88.0), card.inventoryDays)
        assertNull(card.inventoryDays["Mar 2025"])
    }

    @Test
    fun `an all placeholder row is published but carries no figure`() {
        val card = RatiosCardParser.parse(
            cardHtml(rows = listOf("Inventory Days" to listOf("-", "", "NA"))),
        )
        val row = assertNotNull(card.row(*RatiosCardParser.INVENTORY_DAYS_KEYS))
        assertTrue(row.isEmpty)
        assertFalse(row.hasAnyValue)
        assertEquals(0, row.publishedCount)
        assertEquals(emptyMap(), card.inventoryDays)
        assertTrue(card.isUsable, "the section still published a row, so it is found")
        assertTrue(
            card.notes.any { it.contains("every cell is blank or a placeholder") },
            card.notes.toString(),
        )
    }

    // ------------------------------------------------------------------------- empty markup

    @Test
    fun `a card with an empty body is reported present but empty`() {
        val card = RatiosCardParser.parse(cardHtml(rows = emptyList()))
        assertFalse(card.isAbsent, "the section element exists")
        assertTrue(card.isEmpty, "but it publishes no data row")
        assertFalse(card.isUsable, "which for the working-capital flags is the same as absent")
        assertEquals(3, card.periods.size, "the headers still parsed")
        assertTrue(card.notes.any { it.contains("no data rows") }, card.notes.toString())
    }

    @Test
    fun `a card with no table at all is reported`() {
        val card = RatiosCardParser.parse("<html><body><section id=\"ratios\"><h2>Ratios</h2></section></body></html>")
        assertFalse(card.isAbsent)
        assertTrue(card.isEmpty)
        assertTrue(card.notes.any { it.contains("contains no <table>") }, card.notes.toString())
    }

    @Test
    fun `a card whose every row is blank is present but yields no figure`() {
        val card = RatiosCardParser.parse(cardHtml(rows = listOf("Debtor Days" to listOf("-", "-"))))
        assertTrue(card.isUsable, "rows exist, so the section is found")
        assertTrue(card.debtorDays.isEmpty())
        assertTrue(
            card.notes.any { it.contains("no readable figure in any of them") },
            card.notes.toString(),
        )
    }

    @Test
    fun `an empty card marks the whole parse not ok and names the section`() {
        val html = emptyCardBody(ScreenerFixtures.html("TCS"))
        val result = FundamentalsParserFacade.parse(html, "TCS", ScreenerFixtures.AS_OF)
        assertFalse(result.health.ok)
        assertEquals(listOf(RatiosCardParser.SECTION_ID), result.health.sectionsMissing)
        assertTrue(result.health.notes.any { it.contains("no data rows") })
        assertTrue(result.ratioCard.isEmpty)
    }

    // ------------------------------------------------------------------ a different shape

    @Test
    fun `a card with fewer cells than columns is padded with null and reported`() {
        val html = """
            <html><body><section id="ratios"><table>
            <thead><tr><th></th>
              <th data-date-key="2025-03-31">Mar 2025</th>
              <th data-date-key="2026-03-31">Mar 2026</th>
              <th data-date-key="2027-03-31">Mar 2027</th></tr></thead>
            <tbody><tr><td>Debtor Days</td><td>20</td><td>22</td></tr></tbody>
            </table></section></body></html>
        """.trimIndent()
        val card = RatiosCardParser.parse(html)
        assertTrue(card.isUsable)
        assertEquals(listOf("Mar 2025", "Mar 2026", "Mar 2027"), card.periods)
        assertEquals(listOf(20.0, 22.0, null), card.row("debtordays")?.values, "padded, not truncated")
        assertNull(card.debtorDays["Mar 2027"], "the missing cell is absent, not zero")
        assertTrue(
            card.notes.any { it.contains("publishes 2 value cell(s) for 3 column(s)") },
            card.notes.toString(),
        )
    }

    @Test
    fun `a card with more cells than columns is truncated and reported`() {
        val html = """
            <html><body><section id="ratios"><table>
            <thead><tr><th></th><th data-date-key="2026-03-31">Mar 2026</th></tr></thead>
            <tbody><tr><td>Debtor Days</td><td>20</td><td>22</td><td>24</td></tr></tbody>
            </table></section></body></html>
        """.trimIndent()
        val card = RatiosCardParser.parse(html)
        assertEquals(listOf(20.0), card.row("debtordays")?.values)
        assertTrue(
            card.notes.any { it.contains("publishes 3 value cell(s) for 1 column(s)") },
            card.notes.toString(),
        )
    }

    @Test
    fun `a column count that differs from twelve is noted but still parsed`() {
        // The helper card carries three columns; the point is that three ≠ twelve is noted, not fatal.
        val card = RatiosCardParser.parse(cardHtml(rows = listOf("Debtor Days" to listOf("20.0"))))
        assertEquals(3, card.periods.size)
        assertEquals(20.0, card.debtorDays.getValue("Mar 2025"))
        assertTrue(
            card.notes.any {
                it.contains("publishes 3 period column(s)") && it.contains("verified 12")
            },
            card.notes.toString(),
        )
    }

    @Test
    fun `a card with no period columns is reported instead of producing junk`() {
        val html = """
            <html><body><section id="ratios"><table>
            <thead><tr><th></th></tr></thead>
            <tbody><tr><td>Debtor Days</td><td>20</td></tr></tbody>
            </table></section></body></html>
        """.trimIndent()
        val card = RatiosCardParser.parse(html)
        // The row is captured — nothing is thrown away — but with no header there is no year to
        // attribute the cell to, so the card cannot be claimed as usable.
        assertFalse(card.isAbsent)
        assertFalse(card.isEmpty)
        assertFalse(card.isUsable, "no column header means no figure can be attributed to a year")
        assertTrue(card.periods.isEmpty())
        assertTrue(card.notes.any { it.contains("no period columns") }, card.notes.toString())
        assertEquals(20.0, card.row("debtordays")?.values?.single(), "the cell is still captured")
        assertTrue(card.debtorDays.isEmpty(), "no header means no year, so nothing is attributed")
        assertTrue(DerivedMetrics.ratioCard(card).dsoByYear.isEmpty())

        // And the facade reports it as missing rather than found-but-useless. (This snippet is
        // the whole page, so every other section is missing too.)
        val result = FundamentalsParserFacade.parse(html, "NOCOL", ScreenerFixtures.AS_OF)
        assertTrue(RatiosCardParser.SECTION_ID in result.health.sectionsMissing)
        assertFalse(RatiosCardParser.SECTION_ID in result.health.sectionsFound)
        assertTrue(result.ratioCard.isEmpty)
    }

    @Test
    fun `a duplicated row label is read once and the duplication is reported`() {
        val html = """
            <html><body><section id="ratios"><table>
            <thead><tr><th></th><th data-date-key="2026-03-31">Mar 2026</th></tr></thead>
            <tbody>
              <tr><td>Debtor Days</td><td>20</td></tr>
              <tr><td>Debtor Days</td><td>99</td></tr>
            </tbody></table></section></body></html>
        """.trimIndent()
        val card = RatiosCardParser.parse(html)
        assertEquals(20.0, card.debtorDays.getValue("Mar 2026"), "the first occurrence wins")
        assertEquals(1, card.rows.size)
        assertTrue(card.notes.any { it.contains("appears more than once") }, card.notes.toString())
    }

    @Test
    fun `a card holding only a Cash from Operating Activity row still parses it`() {
        // Not the live shape, but the alias path must work so a future revision is a no-op here.
        val card = RatiosCardParser.parse(
            cardHtml(rows = listOf("Cash from Operating Activity" to listOf("70,023.0", "48,013.0"))),
        )
        assertEquals(70_023.0, card.cashFromOperations.getValue("Mar 2025"))
        assertEquals(70_023.0, card.extras().cashFromOperations.getValue("Mar 2025"))
        assertFalse(card.extras().isEmpty)
    }

    @Test
    fun `a hostile card never throws and always produces health`() {
        for (body in listOf(
            "",
            "not html at all",
            "<section id=\"ratios\"><table><tr><td>unclosed",
            "<section id=\"ratios\"></section>",
            "<section id=\"ratios\"><table></table></section>",
            "<section id=\"ratios\"><table><thead></thead><tbody></tbody></table></section>",
            "<html><body><h1>403</h1></body></html>",
        )) {
            val result = FundamentalsParserFacade.parse(body, "HOSTILE", ScreenerFixtures.AS_OF)
            assertFalse(result.health.ok, body.take(40))
            assertTrue(result.health.sectionsMissing.isNotEmpty(), body.take(40))
            assertNotNull(result.health)
            // Nothing became a fabricated zero on the way through.
            assertTrue(result.ratioCard.dsoByYear.values.none { it == 0.0 }, body.take(40))
            assertTrue(result.ratioCard.extras.debtorDays.isEmpty(), body.take(40))
        }
    }

    // ---------------------------------------------------------------------------- helpers

    /** A minimal, well-formed card: [rows] of `label -> one cell per annual column`. */
    private fun cardHtml(rows: List<Pair<String, List<String>>>): String {
        val years = listOf("2025-03-31" to "Mar 2025", "2026-03-31" to "Mar 2026", "2027-03-31" to "Mar 2027")
        val headers = years.joinToString("") { (key, label) ->
            "<th data-date-key=\"$key\">$label</th>"
        }
        val body = rows.joinToString("") { (label, cells) ->
            val tds = cells.joinToString("") { "<td>$it</td>" }
            "<tr><td class=\"text\">$label</td>$tds</tr>"
        }
        return """
            <html><body><section id="ratios"><h2>Ratios</h2>
            <table class="data-table responsive-text-nowrap">
            <thead><tr><th class="text"></th>$headers</tr></thead>
            <tbody>$body</tbody></table></section></body></html>
        """.trimIndent()
    }

    private fun removeSection(html: String, id: String): String {
        val start = html.indexOf("<section id=\"$id\"")
        check(start >= 0) { "fixture has no section $id" }
        val end = html.indexOf("</section>", start)
        check(end >= 0) { "fixture section $id is not closed" }
        return html.substring(0, start) + html.substring(end + "</section>".length)
    }

    private fun emptyCardBody(html: String): String {
        val start = html.indexOf("<section id=\"ratios\"")
        val end = html.indexOf("</section>", start)
        val section = html.substring(start, end)
        val bodyStart = section.indexOf("<tbody>")
        val bodyEnd = section.indexOf("</tbody>")
        val emptied = section.substring(0, bodyStart) + "<tbody>\n" + section.substring(bodyEnd)
        return html.substring(0, start) + emptied + html.substring(end)
    }
}