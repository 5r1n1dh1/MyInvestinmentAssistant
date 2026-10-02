package com.mia.app.data.fundamentals

import com.mia.app.data.fundamentals.ScreenerHtml.ParsedTable
import com.mia.app.data.fundamentals.ScreenerHtml.Period
import com.mia.app.domain.model.AnnualRow
import com.mia.app.domain.model.QuarterRecord
import org.jsoup.nodes.Document

/**
 * The four annual statements plus the quarterly results table.
 *
 * ### Ordering — a deliberate, documented choice
 * * **Quarters are returned newest first** (index 0 = the most recent column). Screener.in
 *   prints them oldest-left, so this is the reverse of the page's column order; a caller that
 *   wants the page's own left-to-right order calls `quartersOldestFirst`. Newest-first is what
 *   the UI and the trend calculations want, and "most recent quarter" must not require the
 *   caller to guess an index. The test asserts the first record is `Jun 2026` and the last is
 *   `Jun 2023` for every fixture.
 * * **(year, quarter) is a calendar identity, not an Indian financial-year one.** See
 *   [ScreenerHtml.quarterOfMonth]: `Jun 2026` is `(2026, Q2)` and `Mar 2026` is `(2026, Q1)`,
 *   which keeps the pair strictly increasing with the date.
 * * **Annual rows keep the published order** (oldest year first) because
 *   [AnnualRow.values] is index-aligned with [ParsedTable.years]. Re-sorting a row without
 *   re-sorting the header would silently mis-assign every value, so the order is left alone.
 *
 * ### Bank rows are not manufacturing rows
 * plan.md §7.3: for banks, operating margin and D/E are meaningless. Verified: HDFCBANK's
 * `quarters` table publishes `Revenue / Interest / Expenses / Financing Profit / Financing
 * Margin %` and **no** `Operating Profit` and **no** `OPM %`. Those two fields are therefore
 * left `null` and a note records the substitution — never coerced into `Financing Profit`.
 */
object StatementParsers {

    /**
     * The outcome of reading the `quarters` table.
     *
     * [table] is `null` when the section is absent from the page; `records` is then empty and
     * [notes] says so, so the caller can report a missing section rather than a silent empty
     * list (plan.md §12).
     */
    data class QuarterSection(
        val table: ParsedTable?,
        val records: List<QuarterRecord>,
        val notes: List<String>,
    ) {
        /** True when the section was present and produced at least one quarter. */
        val isUsable: Boolean get() = table != null && records.isNotEmpty()
    }

    /** The quarterly results, newest quarter first. */
    fun quarters(doc: Document): QuarterSection = quarters(ScreenerHtml.tableRowsById(doc, ScreenerHtml.QUARTERS))

    /** The quarterly results in the page's own left-to-right order (oldest first). */
    fun quartersOldestFirst(doc: Document): List<QuarterRecord> = quarters(doc).records.reversed()

    /**
     * Reads the `quarters` table into [QuarterRecord]s.
     *
     * Requires 13 columns on a live page (plan.md §3.3, §14: "screener.in has 13 quarters").
     * A different count is **not** an error — the column list is what it is — but it is
     * recorded in [QuarterSection.notes] so a change in the page's depth is visible rather than
     * assumed.
     */
    fun quarters(table: ParsedTable?): QuarterSection {
        if (table == null) {
            return QuarterSection(
                null,
                emptyList(),
                listOf("section '${ScreenerHtml.QUARTERS}' is absent from the page"),
            )
        }
        val baseNotes = table.notes.toMutableList()
        if (table.years.size != EXPECTED_QUARTERS) {
            baseNotes += "section '${ScreenerHtml.QUARTERS}' publishes ${table.years.size} " +
                "quarter column(s); plan.md §3.3 verified 13"
        }
        if (table.rows.isEmpty()) {
            return QuarterSection(table, emptyList(), baseNotes + "section '${ScreenerHtml.QUARTERS}' has no data rows")
        }

        val indexed = table.rows.associateBy { ScreenerHtml.labelKey(it.label) }
        val columns = table.years.indices.mapNotNull { i ->
            val period = ScreenerHtml.periodOf(
                table.dateKeys.getOrNull(i),
                table.years.getOrNull(i),
            ) ?: return@mapNotNull null
            if (period.quarter <= 0) return@mapNotNull null
            i to period
        }
        if (columns.isEmpty()) {
            return QuarterSection(table, emptyList(), baseNotes + "section '${ScreenerHtml.QUARTERS}' has no quarter-shaped column headers")
        }

        if (indexed["operatingprofit"] == null) {
            baseNotes += "no 'Operating Profit' row published; operatingProfit and opmPercent stay null " +
                "(plan.md §7.3 — banks publish 'Financing Profit' instead)"
        }
        if (indexed["operatingprofit"] == null && indexed["interest"] != null) {
            baseNotes += "this page's P&L shape is a bank's: its 'Interest' row is interest " +
                "income, not interest expense, so any coverage ratio read from it is meaningless"
        }

        val newestFirst = columns.sortedWith(
            compareByDescending<Pair<Int, Period>> { ScreenerHtml.periodSortKey(it.second) },
        )
        val records = newestFirst.map { (column, period) ->
            QuarterRecord(
                year = period.year,
                quarter = period.quarter,
                sales = valueAt(indexed, column, "sales", "revenue", "totalrevenue"),
                expenses = valueAt(indexed, column, "expenses", "totalexpenses"),
                operatingProfit = valueAt(indexed, column, "operatingprofit"),
                opmPercent = valueAt(indexed, column, "opm"),
                otherIncome = valueAt(indexed, column, "otherincome"),
                interest = valueAt(indexed, column, "interest"),
                depreciation = valueAt(indexed, column, "depreciation"),
                eps = valueAt(indexed, column, "epsinrs", "eps"),
            )
        }
        return QuarterSection(table, records, baseNotes)
    }

    /**
     * Reads any annual statement table (`profit-loss`, `balance-sheet`, `cash-flow`,
     * `shareholding`) into [AnnualRow]s, in published order, values index-aligned with
     * `years`. Returns `null` only when the section element itself is absent.
     */
    fun annualRows(doc: Document, sectionId: String): List<AnnualRow> =
        ScreenerHtml.tableRowsById(doc, sectionId)?.rows.orEmpty()

    /**
     * Row lookup by normalised label, first match wins.
     *
     * `"Borrowings +"` and `"Borrowing"` (HDFCBANK prints the singular) both reduce to a key
     * containing `borrow`, so callers pass a prefix-friendly key list.
     */
    fun rowByKey(table: ParsedTable?, vararg keys: String): AnnualRow? {
        if (table == null) return null
        val byKey = table.rows.associateBy { ScreenerHtml.labelKey(it.label) }
        for (key in keys) byKey[key]?.let { return it }
        return null
    }

    /** Reads one row's value at a column index, `null` when either is absent. */
    fun valueAt(row: AnnualRow?, column: Int): Double? = row?.values?.getOrNull(column)

    /** Reads the first row matching any of [keys] at column [column]. */
    fun valueAt(table: ParsedTable?, column: Int, vararg keys: String): Double? =
        valueAt(rowByKey(table, *keys), column)

    private fun valueAt(indexed: Map<String, AnnualRow>, column: Int, vararg keys: String): Double? {
        for (key in keys) indexed[key]?.let { return it.values.getOrNull(column) }
        return null
    }

    /** plan.md §3.3 and §14: a live `quarters` table carries 13 quarters. */
    const val EXPECTED_QUARTERS: Int = 13
}
