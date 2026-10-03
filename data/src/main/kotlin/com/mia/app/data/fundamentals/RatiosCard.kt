package com.mia.app.data.fundamentals

import com.mia.app.data.fundamentals.ScreenerHtml.ParsedTable
import com.mia.app.domain.ratios.RatioExtras
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * One row of the `ratios` card, with its cells aligned to the card's column headers.
 *
 * ### Two views of the same row, and why both exist
 * [values] is **index-aligned with** [RatiosCard.periods], so a value can never be attributed to
 * the wrong year by array position alone; [byPeriod] is the flattened `period label -> figure`
 * view that [RatioExtras] and the domain's `RatiosCardSeries` consume.
 *
 * A cell the page left blank, printed as `-` or printed as `NA` is `null` in [values] and
 * **absent** from [byPeriod]. That distinction is the whole point of this row type: TCS and INFY
 * publish a `Inventory Days` row in which every cell is empty, which must read as *"this company
 * carries no inventory"* and never as *twelve zeros* (plan.md §12, §13).
 */
data class RatiosCardRow(
    /** The label exactly as printed, e.g. `"Debtor Days"` or `"ROCE %"`. */
    val label: String,
    /** [ScreenerHtml.labelKey] of [label] — `"debtordays"`, `"roce"`. */
    val key: String,
    /** Index-aligned with [RatiosCard.periods]; `null` where the cell held no number. */
    val values: List<Double?>,
    /** `period label -> published figure`. Unreadable and unpublished cells are absent. */
    val byPeriod: Map<String, Double>,
) {
    /** How many columns actually carried a figure. */
    val publishedCount: Int get() = byPeriod.size

    /** True when at least one cell carried a figure. An all-blank row is **not** usable. */
    val hasAnyValue: Boolean get() = byPeriod.isNotEmpty()

    /** Every cell was blank / `-` / `NA`: the row exists but the company publishes no figure. */
    val isEmpty: Boolean get() = byPeriod.isEmpty()
}

/**
 * The `ratios` card — the days-based working-capital and returns series screener.in publishes in
 * its own right.
 *
 * ### Why this card is the only route to debtor days and inventory days
 * plan.md §8.1 asks for a receivables-vs-revenue red flag and an inventory-days flag. The
 * collapsed `balance-sheet` summary carries **no** Trade Receivables, **no** Inventory and **no**
 * cost-of-goods line (verified across all five fixtures; those live behind
 * `Company.showSchedule(…)`, a second URL shape plan.md §3.6 does not permit), so both figures
 * are unreachable from the statements. They are, however, **published** here — 12 fiscal years of
 * `Debtor Days` and `Inventory Days`. This card is therefore the difference between a red flag
 * that can be evaluated and one that must report "not evaluated".
 *
 * ### What is actually on the card (measured, not assumed)
 * Verified across the five fixtures captured 2026-10-02, the card publishes **only** working-capital
 * days and a returns percentage:
 *
 * ```
 * RELIANCE / TCS / INFY / TITAN   Debtor Days · Inventory Days · Days Payable
 *                                Cash Conversion Cycle · Working Capital Days · ROCE %
 * HDFCBANK                       ROE %   (one row — a bank has no inventory cycle)
 * ```
 *
 * It publishes **no** `Gross Profit`, `Operating Profit`, `Expenses`, `Cash from Operating
 * Activity`, EBITDA, cash or enterprise-value row. Two consequences are recorded rather than
 * papered over: gross margin stays unsourceable (it needs cost of goods, and nothing here or in
 * the statements provides it), and so does EV/EBITDA. See `DerivedMetrics`'s notes.
 *
 * ### Absence is represented as absence
 * plan.md §12. Three distinct absences are kept apart, and none of them is `0.0`:
 * * the **section** is absent (`[isAbsent]`),
 * * the section is present but publishes **no rows** (`[isEmpty]`),
 * * a **row** is absent from a card that has other rows (HDFCBANK has no `Inventory Days`), or
 *   present with every cell blank (TCS/INFY `Inventory Days`).
 *
 * ### One request, no extra fetch
 * plan.md §3.6 permits exactly one URL shape. The card lives on the same consolidated page as the
 * statements, so reading it costs no second request and no robots question.
 */
data class RatiosCard(
    /** Column headers exactly as published, oldest first: `"Mar 2015" … "Mar 2026"`. */
    val periods: List<String> = emptyList(),
    /** `data-date-key` per column when the card publishes one; `null` otherwise. */
    val dateKeys: List<String?> = emptyList(),
    /** Row label key -> row, in published order. A renamed label yields a different key. */
    val rows: Map<String, RatiosCardRow> = emptyMap(),
    /** The same parse as a [ParsedTable], for evidence display. `null` only when absent. */
    val table: ParsedTable? = null,
    val notes: List<String> = emptyList(),
) {
    /** The `<section id="ratios">` element was not on the page at all. */
    val isAbsent: Boolean get() = table == null

    /** Present, but it publishes no usable data row. Never confusable with a real zero. */
    val isEmpty: Boolean get() = rows.isEmpty()

    /**
     * Present and able to yield at least one attributed figure — the only state the facade calls
     * "found".
     *
     * Requires a **column header** as well as a row: a card whose table has headers stripped can
     * produce cells but no year to attribute them to, so reporting it as found would be the
     * silent-empty failure with extra steps. The cells are still captured in [rows] and named in
     * [notes], so nothing is discarded — it is simply not claimed to be usable.
     */
    val isUsable: Boolean get() = !isAbsent && !isEmpty && periods.isNotEmpty()

    /** Row keys in published order, for diagnostics and exact-shape assertions. */
    val rowKeys: List<String> get() = rows.keys.toList()

    /** Row labels exactly as printed, in published order. */
    val rowLabels: List<String> get() = rows.values.map { it.label }

    /** The first row whose label key is one of [keys], or `null`. Most specific key first. */
    fun row(vararg keys: String): RatiosCardRow? {
        for (key in keys) rows[key]?.let { return it }
        return null
    }

    /**
     * `period label -> figure` for the first row matching [keys].
     *
     * Empty when the row is absent, and empty-but-for-a-different-reason when the row is present
     * with every cell blank. [RatiosCardRow.isEmpty] tells the two apart; the map itself never
     * invents a `0.0`.
     */
    fun series(vararg keys: String): Map<String, Double> = row(*keys)?.byPeriod ?: emptyMap()

    /**
     * The same series keyed by **fiscal year** instead of period label.
     *
     * Columns whose header does not resolve to a fiscal year are dropped, never guessed: the card
     * is annual today, but plan.md §3.3's `TTM` landmine applies to any screener.in table and a
     * trailing-twelve-months figure must never sit beside twelve annual ones as if it were one.
     * Where two columns resolve to the same year the **later column wins**, because that is the
     * revision the page prints last.
     */
    fun byYear(vararg keys: String): Map<Int, Double> {
        val row = row(*keys) ?: return emptyMap()
        val result = LinkedHashMap<Int, Double>()
        periods.forEachIndexed { index, period ->
            val year = ScreenerHtml.yearOf(period) ?: return@forEachIndexed
            val value = row.values.getOrNull(index) ?: return@forEachIndexed
            if (value.isFinite()) result[year] = value
        }
        return result.toSortedMap()
    }

    // --------------------------------------------------------------- named card series
    //
    // Keys are `ScreenerHtml.labelKey` shapes: "Debtor Days" -> "debtordays", "ROCE %" -> "roce".
    // Each list is ordered most-specific first; a rename only costs the alias, never the parse.

    /** `Debtor Days` — trade-receivable days, the §8.1 receivables signal. Aliases: `Receivable Days`, `DSO`. */
    val debtorDays: Map<String, Double> get() = series(*RatiosCardParser.DEBTOR_DAYS_KEYS)

    /** `Inventory Days`. Aliases: `Days Inventory Outstanding`, `Inventory Turnover Days`. */
    val inventoryDays: Map<String, Double> get() = series(*RatiosCardParser.INVENTORY_DAYS_KEYS)

    /** `Days Payable`. Supporting evidence for the inventory flag; never a flag on its own. */
    val daysPayable: Map<String, Double> get() = series(*RatiosCardParser.DAYS_PAYABLE_KEYS)

    /** `Cash Conversion Cycle` = debtor days + inventory days − payable days, as published. */
    val cashConversionCycle: Map<String, Double> get() = series(*RatiosCardParser.CASH_CONVERSION_CYCLE_KEYS)

    /** `Working Capital Days`, as published. */
    val workingCapitalDays: Map<String, Double> get() = series(*RatiosCardParser.WORKING_CAPITAL_DAYS_KEYS)

    /** `ROCE %` from the card (the 12-year series), distinct from `top-ratios`' single figure. */
    val rocePercent: Map<String, Double> get() = series(*RatiosCardParser.ROCE_KEYS)

    /** `ROE %` from the card. The only profitability series HDFCBANK publishes. */
    val roePercent: Map<String, Double> get() = series(*RatiosCardParser.ROE_KEYS)

    /**
     * `Cash from Operating Activity`, **if** the card ever publishes it.
     *
     * It does not on any of the five fixtures (verified): the row lives in the `cash-flow`
     * statement and is already in `CashFlowDerived.cfoByYear` keyed by year. This accessor exists
     * so that if screener.in adds the row to the card it is picked up without a new code path —
     * and, being empty today, it is an honest `null` for `RatioExtras.cashFromOperations` rather
     * than a duplicate of a figure we already have.
     */
    val cashFromOperations: Map<String, Double> get() = series(*RatiosCardParser.CASH_FROM_OPERATIONS_KEYS)

    /**
     * The card as the domain's **producer-independent input**.
     *
     * `RatioExtras` (domain/ratios/RatioComputation) is the type the ratios phase declared for
     * exactly this card, keyed by the page's own period labels. Populating it here is the reason
     * this file exists rather than a parallel data-layer type: `RatioComputation.receivableDays`
     * and `.inventoryDays` take it and stop returning "this page publishes none".
     */
    fun extras(): RatioExtras = RatioExtras(
        debtorDays = debtorDays,
        inventoryDays = inventoryDays,
        cashFromOperations = cashFromOperations,
    )

    companion object {
        /** Nothing was parsed: absent section, no periods, no rows. */
        val EMPTY: RatiosCard = RatiosCard()
    }
}

/**
 * Reads the `ratios` card out of a screener.in consolidated page.
 *
 * ### Built on [ScreenerHtml.tableRowsById] rather than a second table reader
 * The card is a `<section id="ratios">` holding an ordinary
 * `<table class="data-table responsive-text-nowrap">` with `data-date-key` headers — the same
 * shape as `cash-flow`. Reusing the existing reader means the three documented landmines are
 * already handled: substring class matching, digits inside a child `<span class="number">`, and
 * **short rows padded with `null` plus a recorded note** rather than being silently truncated.
 * That last one is what makes a card with a different column count safe here.
 *
 * It never throws: an absent element yields `[RatiosCard.EMPTY]`-shaped output with
 * `isAbsent = true`, and the facade turns that into a reported missing section.
 */
object RatiosCardParser {

    /**
     * The element id of the card.
     *
     * Declared here rather than in `ScreenerHtml.SECTION_IDS` because plan.md §3.3's canonical
     * six-section list predates the card being parsed; `FundamentalsParserFacade.SECTION_IDS`
     * folds this in so the health accounting reports it like any other section.
     */
    const val SECTION_ID: String = "ratios"

    /** plan.md §3.3: 12 fiscal years on the live card, Mar 2015 through Mar 2026. */
    const val EXPECTED_COLUMNS: Int = 12

    // `ScreenerHtml.labelKey` shapes, most specific first. Public so `DerivedMetrics` reads the
    // same lists the accessors use — one source of truth for what a label can be called.
    val DEBTOR_DAYS_KEYS: Array<String> = arrayOf(
        "debtordays", "receivabledays", "receivableday", "debtorday", "dso",
        "debtordayssales", "tradereceivabledays",
    )
    val INVENTORY_DAYS_KEYS: Array<String> = arrayOf(
        "inventorydays", "inventoryday", "daysinventoryoutstanding", "inventoryturnoverdays",
        "inventorydaysheld",
    )
    val DAYS_PAYABLE_KEYS: Array<String> = arrayOf(
        "dayspayable", "payabledays", "creditordays", "payableday",
    )
    val CASH_CONVERSION_CYCLE_KEYS: Array<String> = arrayOf(
        "cashconversioncycle", "cashtocashcycle", "cashconversion", "ccc",
    )
    val WORKING_CAPITAL_DAYS_KEYS: Array<String> = arrayOf(
        "workingcapitaldays", "workingcapitalcycle", "workingcapital",
    )
    val ROCE_KEYS: Array<String> = arrayOf(
        "roce", "rocepercent", "returnoncapitalemployed", "returnoncapital",
    )
    val ROE_KEYS: Array<String> = arrayOf(
        "roe", "roepercent", "returnonequity", "returnonequitycapital",
    )

    /**
     * `Cash from Operating Activity`, which the card does **not** publish on any verified page.
     *
     * Listed because a future card revision may add it; empty today is the honest answer, and an
     * empty `RatioExtras.cashFromOperations` leaves the domain reading CFO from the `cash-flow`
     * statement instead of from two places at once.
     */
    val CASH_FROM_OPERATIONS_KEYS: Array<String> = arrayOf(
        "cashfromoperatingactivity", "cashfromoperations", "netcashfromoperatingactivities",
        "cfo", "cashfromoperationsandworkingcapital",
    )

    /** Parses [html] then reads the card; the convenience overload matching the brief. */
    fun parse(html: String): RatiosCard = parse(ScreenerHtml.document(html))

    fun parse(doc: Document): RatiosCard = parse(doc.getElementById(SECTION_ID))

    /**
     * Reads the card out of [root], or reports it absent when [root] is `null`.
     *
     * A section that exists but publishes no usable data row returns a card with
     * `isAbsent = false, isEmpty = true` and a note — "present but empty" is reported, never
     * collapsed into the silent empty list plan.md §12 forbids.
     */
    fun parse(root: Element?): RatiosCard {
        if (root == null) {
            return RatiosCard(notes = listOf("section '$SECTION_ID' is absent from the page"))
        }
        // A fresh one-section document so the existing reader can be reused verbatim. Going back
        // through `root.ownerDocument()` instead would silently report "absent" for a detached
        // element and would pick the *first* duplicate id rather than the element handed in.
        val holder = Document("")
        holder.appendChild(root.clone())
        val table = ScreenerHtml.tableRowsById(holder, SECTION_ID)
            ?: return RatiosCard(notes = listOf("section '$SECTION_ID' is absent from the page"))

        val periods = table.years
        val notes = table.notes.toMutableList()
        val rows = LinkedHashMap<String, RatiosCardRow>()

        for (annual in table.rows) {
            val key = ScreenerHtml.labelKey(annual.label)
            if (key.isEmpty()) continue
            // First row wins, matching StatementParsers.rowByKey, so a duplicated label cannot
            // make the card disagree with itself depending on iteration order.
            if (rows.containsKey(key)) {
                notes += "row '${annual.label}' appears more than once in section '$SECTION_ID'; " +
                    "the first occurrence is used"
                continue
            }
            val byPeriod = LinkedHashMap<String, Double>()
            periods.forEachIndexed { index, period ->
                val value = annual.values.getOrNull(index) ?: return@forEachIndexed
                if (value.isFinite()) byPeriod[period] = value
            }
            rows[key] = RatiosCardRow(
                label = annual.label,
                key = key,
                values = annual.values,
                byPeriod = byPeriod,
            )
        }

        if (periods.size != EXPECTED_COLUMNS) {
            notes += "section '$SECTION_ID' publishes ${periods.size} period column(s); " +
                "plan.md §3.3 verified $EXPECTED_COLUMNS on the live card"
        }
        if (rows.isEmpty()) {
            notes += "section '$SECTION_ID' has a table but no data rows"
        } else if (rows.values.none { it.hasAnyValue }) {
            // Distinct from "no rows": the card is structurally there and every cell is blank.
            notes += "section '$SECTION_ID' publishes ${rows.size} row(s) but no readable figure " +
                "in any of them"
        }
        for (row in rows.values.filter { it.isEmpty }) {
            notes += "row '${row.label}' is present in section '$SECTION_ID' but every cell is " +
                "blank or a placeholder, so the series is absent rather than zero-filled"
        }
        return RatiosCard(
            periods = periods,
            dateKeys = table.dateKeys,
            rows = rows,
            table = table,
            notes = notes,
        )
    }
}