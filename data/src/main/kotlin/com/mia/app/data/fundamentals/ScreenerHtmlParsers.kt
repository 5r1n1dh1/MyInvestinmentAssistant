package com.mia.app.data.fundamentals

import com.mia.app.domain.model.AnnualRow
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * The small, independently testable pieces every screener.in parser is built from.
 *
 * ### Why this file exists
 * plan.md §3.3 and §14 record two parser landmines that were hit during live testing, plus a
 * third that only shows up once you have five real pages side by side:
 *
 * 1. **The value span's class is `"nowrap value"`, not `"value"`.** An exact class match finds
 *    nothing. Every class lookup here is therefore a **substring** match on individual class
 *    tokens, so a rename to `"value nowrap"` keeps working (plan.md §13: "HTML parser
 *    brittleness → substring class matching").
 * 2. **The digits sit in a child `<span class="number">`,** not in the value span's own text.
 *    `valueTextOf` prefers that inner span.
 * 3. **Every table on the page is not the same shape.** `profit-loss` carries a trailing
 *    `TTM` column (`data-date-key="TTM"`) and `shareholding` has *no* `data-date-key`
 *    attributes at all and its `<table>` carries a different class string
 *    (`class="data-table"`, not `class="data-table responsive-text-nowrap"`). Verified across
 *    all five golden fixtures. Nothing below may assume a single table shape.
 *
 * ### Parsing is by element id, never by position
 * plan.md §3.3: sections are located with `getElementById`. Column positions are only ever
 * used *after* the header row has been read, and every value is then addressed by the year or
 * period its header declares — never by "the third cell".
 *
 * ### Missing means missing
 * plan.md §12: a parser must never turn absent data into a zero. `parseNumber` returns `null`
 * for `-`, `NA`, `%`, an empty cell, or anything it cannot prove is a single number, and
 * [tableRowsById] pads short rows with `null` rather than `0.0`.
 */
object ScreenerHtml {

    // --------------------------------------------------------------------- section ids

    /** plan.md §3.3: Market Cap, Current Price, High/Low, P/E, Book Value, Div Yield, ROCE, ROE, Face Value. */
    const val TOP_RATIOS: String = "top-ratios"

    /** plan.md §3.3: 13 quarters of Sales / Expenses / Operating Profit / OPM% / … */
    const val QUARTERS: String = "quarters"

    /** plan.md §3.3: annual P&L. 12 years **plus a trailing `TTM` column**. */
    const val PROFIT_LOSS: String = "profit-loss"

    /** plan.md §3.3: annual balance sheet, 12 years. */
    const val BALANCE_SHEET: String = "balance-sheet"

    /** plan.md §3.3: CFO / CFI / CFF / Net Cash Flow / Free Cash Flow / CFO-OP. */
    const val CASH_FLOW: String = "cash-flow"

    /** plan.md §3.3: Promoter / FII / DII patterns. Quarterly, and **quarterly-labelled**. */
    const val SHAREHOLDING: String = "shareholding"

    /**
     * Canonical section order. Health reporting walks this list so a note reads the same way
     * for every symbol, and so a test can assert the exact set.
     */
    val SECTION_IDS: List<String> =
        listOf(TOP_RATIOS, QUARTERS, PROFIT_LOSS, BALANCE_SHEET, CASH_FLOW, SHAREHOLDING)

    // ------------------------------------------------------------------------- strings

    /** Non-breaking space. screener.in pads button labels with `&nbsp;`. */
    const val NBSP: Char = '\u00A0'

    /**
     * The footer row of the `quarters` table. It links to `/company/source/quarter/…`, which
     * **robots.txt disallows** (plan.md §3.6: `Disallow: /company/source/quarter/` + `*`), so the
     * row is dropped rather than parsed. It carries no data.
     */
    const val RAW_PDF_ROW: String = "Raw PDF"

    /** Tokens that mean "no value", never zero. */
    private val PLACEHOLDERS: Set<String> = setOf(
        "", "-", "--", "---", "–", "—",
        "na", "n/a", "n.a.", "na.", "nm", "nil", "none", "%", "not available",
    )

    private val UNIT_PREFIX = Regex("(?i)^\\s*(?:in\\s+)?(?:rs\\.?|inr)\\s*")
    private val UNIT_SUFFIX = Regex("(?i)\\s*(?:cr\\.?|lakhs?|lmn|bn|rs\\.?|inr|[lk])\\s*$")
    private val NUMBER_SHAPE = Regex("^-?\\d*\\.?\\d+$")

    private val MONTH_NAMES: Map<String, Int> = mapOf(
        "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "may" to 5, "jun" to 6,
        "jul" to 7, "aug" to 8, "sep" to 9, "oct" to 10, "nov" to 11, "dec" to 12,
    )

    /** `data-date-key` shape, e.g. `2025-03-31`. `TTM` deliberately does not match. */
    private val DATE_KEY = Regex("^(\\d{4})-(\\d{2})-(\\d{2})$")

    // ------------------------------------------------------------------------ parsing

    /** Parses [html] without ever throwing: malformed markup simply produces fewer nodes. */
    fun document(html: String): Document = Jsoup.parse(html)

    /**
     * Substring class matching (plan.md §3.3 landmine 1).
     *
     * `"nowrap value"` and `"value nowrap"` both match `"value"`. Matching is per token and
     * case-insensitive, so `class="blue-icon"` can never be mistaken for `class="number"`.
     */
    fun hasClassToken(element: Element, token: String): Boolean =
        element.classNames().any { it.contains(token, ignoreCase = true) }

    /** All descendants of [element] whose class list contains [token] as a substring, in document order. */
    fun elementsWithClassToken(element: Element, token: String): List<Element> =
        element.select("*").filter { hasClassToken(it, token) }

    /** The inner `<span class="number">` texts of [element], in document order, normalised. */
    fun numberTextsOf(element: Element): List<String> {
        // LinkedHashSet because jsoup's `select("*")` may include the receiver itself, and a
        // `<span class="number">` asked about directly must not report its own digits twice.
        val candidates = LinkedHashSet<Element>()
        if (hasClassToken(element, "number")) candidates.add(element)
        candidates.addAll(element.select("*"))
        return candidates
            .filter { hasClassToken(it, "number") }
            .map { normalise(it.text()) }
            .filter { it.isNotEmpty() }
    }

    /**
     * The display text of a value cell.
     *
     * When the cell holds exactly one `number` span (the normal case — `"<span class="nowrap
     * value">₹ <span class="number">1,168</span></span>"`) that span's text wins, so the
     * currency symbol and unit suffixes never leak into a number. When it holds several (the
     * `High / Low` cell holds two) the whole cell text is kept so the caller can see both.
     */
    fun valueTextOf(element: Element): String {
        val numbers = numberTextsOf(element)
        return if (numbers.size == 1) numbers.first() else normalise(element.text())
    }

    /** Collapses whitespace, turns NBSP into a space, and trims. */
    fun normalise(text: String?): String {
        if (text == null) return ""
        val builder = StringBuilder(text.length)
        var pendingSpace = false
        for (ch in text) {
            val c = if (ch == NBSP || ch.isWhitespace()) ' ' else ch
            if (c == ' ') {
                if (builder.isNotEmpty()) pendingSpace = true
            } else {
                if (pendingSpace) {
                    builder.append(' ')
                    pendingSpace = false
                }
                builder.append(c)
            }
        }
        return builder.toString()
    }

    /**
     * Tolerant Indian-format number reader.
     *
     * Accepts `"15,80,194"`, `"₹ 1,168"`, `"10.3"`, `"-11,560"`, `"26%"`, `"1.2 Cr"`,
     * `"1.2L"`, `"₹ 1,80,194 Cr."`, `"3,36,98,648"`.
     *
     * Returns `null` for `"-"`, `""`, `"NA"`, `"%"` — and for any string that is not provably a
     * single number, e.g. `"₹ 1,612 / 1,161"` (two numbers) or `"Mar 2025"` (a period).
     * Callers therefore cannot accidentally read a mixed cell as one figure.
     */
    fun parseNumber(text: String?): Double? {
        val cleaned = normalise(text)
        if (cleaned.lowercase() in PLACEHOLDERS) return null
        var body = cleaned.replace("₹", " ").replace("%", " ").replace(",", "")
        body = UNIT_PREFIX.replace(body, "")
        body = UNIT_SUFFIX.replace(body, "")
        body = body.trim()
        if (!NUMBER_SHAPE.matches(body)) return null
        return body.toDoubleOrNull()
    }

    /**
     * Row-label key: `"High / Low"` → `highlow`, `"EPS in Rs"` → `epsinrs`, `"No. of
     * Shareholders"` → `noofshareholders`. Matching on this key instead of the raw label keeps
     * the row lookups immune to the `+` expand buttons and `&nbsp;` padding.
     */
    fun labelKey(label: String?): String {
        val source = normalise(label)
        if (source.equals(RAW_PDF_ROW, ignoreCase = true)) return "rawpdf"
        val builder = StringBuilder(source.length)
        for (ch in source) if (ch.isLetterOrDigit()) builder.append(ch.lowercaseChar())
        return builder.toString()
    }

    // ---------------------------------------------------------------------- tables

    /**
     * One screener.in table, flattened to labels plus numbers.
     *
     * [years] holds the column headers **exactly as published** — `"Mar 2025"`, `"Jun 2026"`,
     * `"TTM"` — because that string is what the UI must show (plan.md §1.4 rule 3: show the
     * source's own wording). [dateKeys] holds the `data-date-key` attribute when the table
     * provides one; `shareholding` does not, so it is `null` there and period parsing falls
     * back to the text label.
     *
     * [rows][AnnualRow.values] is index-aligned with [years]: `values[i]` belongs to
     * `years[i]`. Rows that publish fewer or more cells than there are columns are padded with
     * `null` or truncated, and the discrepancy is recorded in [notes] — never papered over.
     */
    data class ParsedTable(
        val sectionId: String,
        /** Text of the stub header cell, e.g. `"Period"`. Usually empty. */
        val label: String,
        val years: List<String>,
        val dateKeys: List<String?>,
        val rows: List<AnnualRow>,
        val notes: List<String> = emptyList(),
    ) {
        val isEmpty: Boolean get() = rows.isEmpty()
        val columnCount: Int get() = years.size
    }

    /** Convenience overload matching the brief: parse [html] then read section [id]. */
    fun tableRowsById(html: String, id: String): ParsedTable? = tableRowsById(document(html), id)

    /**
     * Reads the table inside the element with id [id], or `null` when that element is absent.
     *
     * A section that exists but yields no data rows returns a [ParsedTable] with empty [rows]
     * and an explanatory note, so the caller can report it as missing instead of silently
     * returning an empty list (plan.md §12).
     */
    fun tableRowsById(doc: Document, id: String): ParsedTable? {
        val section = doc.getElementById(id) ?: return null
        val table = pickTable(section)
            ?: return ParsedTable(id, "", emptyList(), emptyList(), emptyList(), listOf(
                "section '$id' is present but contains no <table>",
            ))

        val headers = table.select("thead th")
        val label = normalise(headers.firstOrNull()?.text())
        val dataHeaders = headers.drop(1)
        val years = dataHeaders.map { normalise(it.text()) }
        val dateKeys = dataHeaders.map { it.attr("data-date-key").ifBlank { null } }

        val notes = mutableListOf<String>()
        val rows = mutableListOf<AnnualRow>()
        for (tr in table.select("tbody tr")) {
            val cells = tr.select("td")
            val labelCell = cells.firstOrNull() ?: continue
            val rowLabel = normalise(labelCell.text())
            if (labelKey(rowLabel) == "rawpdf") {
                notes += "row '$RAW_PDF_ROW' dropped: it links to /company/source/quarter/*, " +
                    "which robots.txt disallows (plan.md §3.6)"
                continue
            }
            val values = cells.drop(1).map { parseNumber(normalise(it.text())) }
            if (values.size != years.size) {
                notes += "row '$rowLabel' publishes ${values.size} value cell(s) for " +
                    "${years.size} column(s); aligned with null padding"
            }
            rows += AnnualRow(rowLabel, align(values, years.size))
        }

        if (years.isEmpty()) notes += "section '$id' has a table with no period columns"
        if (rows.isEmpty()) notes += "section '$id' has a table but no data rows"
        return ParsedTable(id, label, years, dateKeys, rows, notes)
    }

    /**
     * Picks the real results table out of a section.
     *
     * A screener.in section can hold more than one `<table>`: the Product/Quarterly Segments
     * button is followed by an empty `data-segment-table` placeholder. The populated table is
     * the one with `data-date-key` headers, failing that the one with body rows.
     */
    private fun pickTable(section: Element): Element? {
        val tables = section.getElementsByTag("table")
        if (tables.isEmpty()) return null
        return tables.firstOrNull { it.select("th[data-date-key]").isNotEmpty() }
            ?: tables.firstOrNull { it.select("tbody tr").isNotEmpty() }
            ?: tables.first()
    }

    private fun align(values: List<Double?>, columns: Int): List<Double?> = when {
        columns <= 0 -> values
        values.size == columns -> values
        values.size < columns -> values + List(columns - values.size) { null }
        else -> values.subList(0, columns).toList()
    }

    // ----------------------------------------------------------------- period labels

    /** A calendar period. `quarter` is 1..4. */
    data class Period(val year: Int, val quarter: Int)

    /**
     * Calendar quarter (1 = Jan–Mar) for a period-end month.
     *
     * Screener.in prints Indian financial years (`Mar 2025` is FY2025 Q4), but
     * [QuarterRecord.year]/[QuarterRecord.quarter] is a **chronological identity**, and a
     * financial-quarter pair is not monotone in the period-end date: `Mar 2026` (FY2026 Q4)
     * comes *after* `Jun 2026` (FY2027 Q1) yet would sort earlier as `2026 Q4` vs `2026 Q1`.
     * Calendar quarters make `(year, quarter)` strictly increasing with the date, so "most
     * recent quarter", descending order and year-over-year comparison are all one comparison
     * away and cannot silently disagree with each other. The UI can re-render the Indian FY
     * label from these two numbers whenever it wants to.
     */
    fun quarterOfMonth(month: Int): Int = ((month + 2) / 3).coerceIn(1, 4)

    /** Sort key that orders periods chronologically: strictly increasing in `(year, quarter)`. */
    fun periodSortKey(period: Period): Long = period.year * 4L + period.quarter

    /**
     * The year behind a column label.
     *
     * Handles `"Mar 2025"`, `"Mar'25"`, `"Sep 2023"`, `"2025"` and `"2026-03-31"`. Returns
     * `null` for `"TTM"` and for anything unrecognised — a trailing-twelve-months column is not
     * a fiscal year and must never pollute the per-year maps.
     */
    fun yearOf(label: String?): Int? {
        val text = normalise(label)
        if (text.isEmpty() || text.equals("TTM", ignoreCase = true)) return null
        FOUR_DIGIT_YEAR.find(text)?.let { m ->
            return m.groupValues[1].toIntOrNull()?.takeIf { it in 1900..2100 }
        }
        TWO_DIGIT_YEAR.find(text)?.let { m ->
            val short = m.groupValues[1].toIntOrNull() ?: return null
            if (short in 0..99) return 2000 + short
        }
        return null
    }

    /** Index of [year] in [table]'s columns, or `null` when the table does not publish it. */
    fun yearIndexOf(table: ParsedTable?, year: Int): Int? =
        table?.years?.indexOfFirst { yearOf(it) == year }?.takeIf { it >= 0 }

    /**
     * The period of a column, preferring the machine-readable `data-date-key` and falling back
     * to the printed label. Returns `null` when neither yields a year.
     */
    fun periodOf(dateKey: String?, label: String?): Period? {
        if (dateKey != null) {
            DATE_KEY.find(dateKey)?.let { m ->
                val year = m.groupValues[1].toIntOrNull()
                val month = m.groupValues[2].toIntOrNull()
                if (year != null && month != null) return Period(year, quarterOfMonth(month))
            }
        }
        val text = normalise(label)
        val year = yearOf(text) ?: return null
        val monthToken = THREE_LETTERS.find(text)?.groupValues?.get(1)?.lowercase()
        val month = MONTH_NAMES[monthToken]
        return Period(year, month?.let { quarterOfMonth(it) } ?: 0)
    }

    private val FOUR_DIGIT_YEAR = Regex("(?<!\\d)(\\d{4})(?!\\d)")
    private val TWO_DIGIT_YEAR = Regex("(?<!\\d)(\\d{2})(?!\\d)")
    private val THREE_LETTERS = Regex("(?i)\\b([a-z]{3})\\b")
}
