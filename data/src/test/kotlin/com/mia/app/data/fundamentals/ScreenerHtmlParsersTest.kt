package com.mia.app.data.fundamentals

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for the pieces in [ScreenerHtmlParsers] — the landmines from plan.md §3.3 tested
 * in isolation so a failure names the actual defect instead of "the golden test broke".
 */
class ScreenerHtmlParsersTest {

    // ------------------------------------------------------------------ class landmine 1

    @Test
    fun `class matching is by substring so a class rename does not break the parse`() {
        for (markup in listOf("nowrap value", "value nowrap", "value", "VALUE")) {
            val li = parseLi(markup, "10.3")
            val value = ScreenerHtml.elementsWithClassToken(li, "value").first()
            assertEquals("10.3", ScreenerHtml.valueTextOf(value), "class='$markup'")
        }
    }

    @Test
    fun `an unrelated class is never mistaken for the value class`() {
        val li = parseLi("blue-icon", "10.3")
        assertTrue(ScreenerHtml.elementsWithClassToken(li, "value").isEmpty())
    }

    @Test
    fun `the digits are read from the inner number span not the surrounding text`() {
        val li = parseLi("nowrap value", "668", prefix = "₹", suffix = " Cr.")
        val value = ScreenerHtml.elementsWithClassToken(li, "value").first()
        assertEquals("668", ScreenerHtml.valueTextOf(value))
        assertEquals(listOf("668"), ScreenerHtml.numberTextsOf(value))
        assertEquals(668.0, ScreenerHtml.parseNumber(ScreenerHtml.valueTextOf(value)))
    }

    @Test
    fun `a cell with two number spans keeps both`() {
        val li = parseLi("nowrap value", "1,612", prefix = "₹ ", second = "1,161", between = " / ")
        val value = ScreenerHtml.elementsWithClassToken(li, "value").first()
        assertEquals(listOf("1,612", "1,161"), ScreenerHtml.numberTextsOf(value))
        assertEquals("₹ 1,612 / 1,161", ScreenerHtml.valueTextOf(value), "both figures stay visible")
        assertNull(ScreenerHtml.parseNumber(ScreenerHtml.valueTextOf(value)), "two figures are not one number")
    }

    @Test
    fun `a value span that is itself the number span still yields its text`() {
        val el = ScreenerHtml.document("<span class=\"number\">15,80,194</span>").selectFirst("span")!!
        assertEquals(listOf("15,80,194"), ScreenerHtml.numberTextsOf(el))
        assertEquals(1_580_194.0, ScreenerHtml.parseNumber(ScreenerHtml.valueTextOf(el)))
    }

    // ------------------------------------------------------------------------ numbers

    @Test
    fun `parseNumber reads Indian and western formatting`() {
        assertEquals(1_580_194.0, ScreenerHtml.parseNumber("15,80,194"))
        assertEquals(1_612.0, ScreenerHtml.parseNumber("1,612"))
        assertEquals(1168.0, ScreenerHtml.parseNumber("₹ 1,168"))
        assertEquals(10.3, ScreenerHtml.parseNumber("10.3"))
        assertEquals(-11_560.0, ScreenerHtml.parseNumber("-11,560"))
        assertEquals(33_698_648.0, ScreenerHtml.parseNumber("3,36,98,648"))
        assertEquals(0.0, ScreenerHtml.parseNumber("0"))
        assertEquals(1.2, ScreenerHtml.parseNumber("1.2 Cr"))
        assertEquals(1.2, ScreenerHtml.parseNumber("1.2L"))
        assertEquals(1_580_194.0, ScreenerHtml.parseNumber("₹ 15,80,194 Cr."))
        assertEquals(26.0, ScreenerHtml.parseNumber("26%"))
        assertEquals(26.0, ScreenerHtml.parseNumber(" 26 % "))
        assertEquals(-2.0, ScreenerHtml.parseNumber("-2 Cr"))
        assertEquals(1168.0, ScreenerHtml.parseNumber("Rs 1,168"))
        assertEquals(1168.0, ScreenerHtml.parseNumber("in Rs. 1,168"))
    }

    @Test
    fun `parseNumber returns null for every absence marker never zero`() {
        for (absent in listOf(null, "", "  ", "-", "--", "—", "NA", "na", "N/A", "NM", "%", "nil")) {
            assertNull(ScreenerHtml.parseNumber(absent), "input was '$absent'")
        }
    }

    @Test
    fun `parseNumber refuses text that is not provably one number`() {
        assertNull(ScreenerHtml.parseNumber("₹ 1,612 / 1,161"), "two figures")
        assertNull(ScreenerHtml.parseNumber("Mar 2025"), "a period label")
        assertNull(ScreenerHtml.parseNumber("50-100"), "a range")
        assertNull(ScreenerHtml.parseNumber("Consolidated"))
        assertNull(ScreenerHtml.parseNumber("."))
    }

    @Test
    fun `non breaking spaces and ragged whitespace collapse`() {
        assertEquals("Sales +", ScreenerHtml.normalise(" Sales + "))
        assertEquals("Sales +", ScreenerHtml.normalise("  Sales \n\n +  "))
        assertEquals("₹ 1,612", ScreenerHtml.normalise("₹ 1,612"))
        assertEquals("", ScreenerHtml.normalise(null))
        assertEquals(1168.0, ScreenerHtml.parseNumber("₹ 1,168"))
    }

    @Test
    fun `row labels reduce to punctuation free keys`() {
        assertEquals("sales", ScreenerHtml.labelKey("Sales +"))
        assertEquals("highlow", ScreenerHtml.labelKey("High / Low"))
        assertEquals("epsinrs", ScreenerHtml.labelKey("EPS in Rs"))
        assertEquals("noofshareholders", ScreenerHtml.labelKey("No. of Shareholders"))
        assertEquals("cfoop", ScreenerHtml.labelKey("CFO/OP"))
        assertEquals("fiis", ScreenerHtml.labelKey("FIIs +"))
        assertEquals("rawpdf", ScreenerHtml.labelKey("Raw PDF"))
        assertEquals("financingmargin", ScreenerHtml.labelKey("Financing Margin %"))
        assertEquals("", ScreenerHtml.labelKey(null))
    }

    // ---------------------------------------------------------------------- period labels

    @Test
    fun `yearOf handles every published label shape and rejects TTM`() {
        assertEquals(2025, ScreenerHtml.yearOf("Mar 2025"))
        assertEquals(2025, ScreenerHtml.yearOf("Mar'25"))
        assertEquals(2025, ScreenerHtml.yearOf("2025"))
        assertEquals(2026, ScreenerHtml.yearOf("2026-03-31"))
        assertEquals(2023, ScreenerHtml.yearOf("Sep 2023"))
        assertNull(ScreenerHtml.yearOf("TTM"))
        assertNull(ScreenerHtml.yearOf(""))
        assertNull(ScreenerHtml.yearOf(null))
        assertNull(ScreenerHtml.yearOf("Company Name"))
    }

    @Test
    fun `periodOf prefers the date key and falls back to the printed label`() {
        assertEquals(ScreenerHtml.Period(2025, 1), ScreenerHtml.periodOf("2025-03-31", "Mar 2025"))
        assertEquals(ScreenerHtml.Period(2023, 3), ScreenerHtml.periodOf("2023-09-30", "Sep 2023"))
        // shareholding has no data-date-key at all.
        assertEquals(ScreenerHtml.Period(2026, 2), ScreenerHtml.periodOf(null, "Jun 2026"))
        assertEquals(ScreenerHtml.Period(2023, 3), ScreenerHtml.periodOf(null, "Sep 2023"))
        assertEquals(ScreenerHtml.Period(2024, 4), ScreenerHtml.periodOf(null, "Dec'24"))
        assertNull(ScreenerHtml.periodOf("TTM", "TTM"))
    }

    @Test
    fun `period keys are strictly increasing with the date`() {
        val ordered = listOf(
            ScreenerHtml.Period(2023, 3), ScreenerHtml.Period(2023, 4),
            ScreenerHtml.Period(2024, 1), ScreenerHtml.Period(2024, 2),
            ScreenerHtml.Period(2026, 1), ScreenerHtml.Period(2026, 2),
        ).map(ScreenerHtml::periodSortKey)
        for (i in 1 until ordered.size) {
            assertTrue(ordered[i] > ordered[i - 1], "period $i did not advance")
        }
    }

    // ------------------------------------------------------------------------- tables

    @Test
    fun `an absent section yields null rather than an empty table`() {
        assertNull(ScreenerHtml.tableRowsById("<html><body></body></html>", ScreenerHtml.QUARTERS))
    }

    @Test
    fun `a section with a table but no rows is distinguishable from an absent section`() {
        val doc = ScreenerHtml.document(
            "<section id=\"quarters\"><table><thead><tr><th></th><th>Mar 2025</th></tr></thead>" +
                "<tbody></tbody></table></section>",
        )
        val table = ScreenerHtml.tableRowsById(doc, ScreenerHtml.QUARTERS)
        assertNotNull(table)
        assertTrue(table.isEmpty)
        assertEquals(listOf("Mar 2025"), table.years)
    }

    @Test
    fun `a short row is padded with null and the discrepancy is noted`() {
        val doc = ScreenerHtml.document(
            """
            <section id="cash-flow"><table><thead><tr><th></th>
              <th data-date-key="2024-03-31">Mar 2024</th>
              <th data-date-key="2025-03-31">Mar 2025</th></tr></thead>
            <tbody><tr><td>Free Cash Flow</td><td>1</td><td>2</td></tr>
            <tr><td>Net Cash Flow</td><td>3</td></tr></tbody></table></section>
            """.trimIndent(),
        )
        val table = assertNotNull(ScreenerHtml.tableRowsById(doc, ScreenerHtml.CASH_FLOW))
        assertEquals(listOf(1.0, 2.0), table.rows[0].values)
        assertEquals(listOf(3.0, null), table.rows[1].values, "missing cell is null, never 0")
        assertTrue(table.notes.any { it.contains("Net Cash Flow") && it.contains("1 value cell(s)") })
    }

    @Test
    fun `the populated table is picked over the empty segment placeholder`() {
        val doc = ScreenerHtml.document(
            """
            <section id="quarters">
              <div class="responsive-holder hidden" data-segment-table>
                <table class="data-table"><thead><tr><th>Segment</th></tr></thead>
                  <tbody><tr><td>Oil</td></tr></tbody></table>
              </div>
              <table class="data-table"><thead><tr><th></th>
                <th data-date-key="2025-03-31">Mar 2025</th></tr></thead>
                <tbody><tr><td>Sales +</td><td>1,000</td></tr></tbody></table>
            </section>
            """.trimIndent(),
        )
        val table = assertNotNull(ScreenerHtml.tableRowsById(doc, ScreenerHtml.QUARTERS))
        assertEquals(1, table.rows.size)
        assertEquals("Sales +", table.rows[0].label)
        assertEquals(listOf(1_000.0), table.rows[0].values)
    }

    @Test
    fun `a hyphen cell parses to null so it never becomes a zero`() {
        val doc = ScreenerHtml.document(
            """
            <section id="balance-sheet"><table><thead><tr><th></th>
              <th data-date-key="2024-03-31">Mar 2024</th>
              <th data-date-key="2025-03-31">Mar 2025</th></tr></thead>
            <tbody><tr><td>Goodwill</td><td>-</td><td>NA</td></tr></tbody></table></section>
            """.trimIndent(),
        )
        val table = assertNotNull(ScreenerHtml.tableRowsById(doc, ScreenerHtml.BALANCE_SHEET))
        assertEquals(listOf< Double?>(null, null), table.rows.single().values)
        assertFalse(0.0 in table.rows.single().values.filterNotNull())
    }

    private fun parseLi(
        valueClass: String,
        number: String,
        prefix: String = "",
        suffix: String = "",
        second: String? = null,
        between: String = "",
    ) = ScreenerHtml.document(
        buildString {
            append("<li><span class=\"name\">Test</span><span class=\"")
            append(valueClass).append("\">").append(prefix)
            append("<span class=\"number\">").append(number).append("</span>")
            if (second != null) append(between).append("<span class=\"number\">").append(second).append("</span>")
            append(suffix).append("</span></li>")
        },
    ).selectFirst("li")!!
}
