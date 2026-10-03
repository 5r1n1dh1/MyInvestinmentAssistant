package com.mia.app.data.fundamentals

import com.mia.app.data.fundamentals.ScreenerHtml.ParsedTable
import com.mia.app.domain.model.CashFlowDerived
import com.mia.app.domain.ratios.RatioExtras
import com.mia.app.domain.redflag.fundamentals.RatiosCardSeries

/**
 * Turns the four statement tables into the per-year aggregates the red-flag engine consumes
 * (plan.md §8).
 *
 * ### Unit convention — stated once, applied everywhere
 * A [CashFlowDerived] field whose name ends in `Percent` holds a percentage; every other field
 * holds ₹ crore, except [CashFlowDerived.cfoToOpByYear] and [CashFlowDerived.sharesOutstanding]
 * which hold plain ratios / share counts. The page prints `CFO/OP` as `"109%"`, so it is
 * divided by 100 on the way in — the map key is the same shape as every other per-year map, so
 * a caller cannot accidentally compare a percentage with a rupee figure.
 *
 * ### Nothing is invented
 * plan.md §12 and plan.md §13 ("Misleading blank ratios → user distrusts app"). Two rules:
 *
 * * A value the page does not publish is **absent from the map**, never `0.0`.
 * * A value the page publishes as `-` / `NA` is `null` in the row and therefore absent from the
 *   map. It is never coerced to zero.
 *
 * ### Verified absences on the live page (2026-10-02 fixtures)
 * The published balance-sheet card is a **collapsed summary**. It carries Equity Capital,
 * Reserves, Borrowings, Other Liabilities, Total Liabilities, Fixed Assets, CWIP, Investments,
 * Other Assets and Total Assets — and **no Trade Receivables, no Inventory and no Goodwill**
 * line. Those three live behind `Company.showSchedule(…)`, an AJAX sub-schedule behind a
 * different URL. This parser fetches exactly one page (plan.md §3.6, one permitted URL shape),
 * so [CashFlowDerived.receivablesByYear], [CashFlowDerived.inventoryByYear] and
 * [CashFlowDerived.goodwillByYear] come back **empty** for every fixture and a note says why.
 * That is reported to the red-flag phase in the hand-off, not hidden.
 *
 * ### Where the working-capital days come from instead
 * The same collapsed-summary gap is why `Debtor Days` and `Inventory Days` cannot be derived:
 * their textbook denominators (credit sales, cost of goods sold) are exactly the lines the page
 * does not publish. screener.in **does** publish both series in its own `ratios` card, so
 * [RatioCardDerived] carries them through as the source's own figures rather than recomputing
 * anything. See [RatiosCard].
 *
 * ### Gross margin and EBITDA are still unsourceable — measured, not assumed
 * The `ratios` card was the last unparsed section on the page, so it was the one remaining place
 * a cost-of-goods or EBITDA figure could have been hiding. It is not there: across all five
 * fixtures the card publishes only working-capital days (`Debtor Days`, `Inventory Days`,
 * `Days Payable`, `Cash Conversion Cycle`, `Working Capital Days`) and one returns percentage
 * (`ROCE %`, or `ROE %` for HDFCBANK). [derive] therefore records, once per parse, that gross
 * margin needs cost of goods and EV/EBITDA needs both an enterprise value and an EBITDA line,
 * neither of which any section of this page carries. `RatioPriority` classifies all three as
 * `NEVER_SOURCED`; this note is the data-layer evidence behind that verdict, so
 * `RatioState.NOT_SOURCED` has a measured reason rather than an assumption.
 *
 * ### Derived figures, and what they are derived from
 * * `ebitByYear` = `Operating Profit + Depreciation` for the same year (brief-specified).
 * * `equityByYear` = `Equity Capital + Reserves` — the page has no single net-worth row.
 * * `debtByYear` = the `Borrowings`/`Borrowing` row (both spellings are matched; HDFCBANK
 *   prints the singular).
 * * `sharesOutstanding` = latest annual `Equity Capital` (₹ crore) ÷ `Face Value` (₹). Both are
 *   published; the quotient is the share count by definition. `null` when either is missing.
 * * `promoterPledgePercent` — the page exposes no pledge figure on the fixtures
 *   (searched `pledge` / `Pledge`: zero hits), so it stays `null`.
 */
object DerivedMetrics {

    /** [CashFlowDerived] plus the notes explaining what the page did and did not publish. */
    data class DerivedResult(
        val derived: CashFlowDerived,
        val notes: List<String>,
        /** The `ratios` card as parsed. `isAbsent`/`isEmpty` distinguish the two failures. */
        val ratiosCard: RatiosCard = RatiosCard.EMPTY,
        /** The card's per-fiscal-year series, ready for the domain. */
        val ratioCard: RatioCardDerived = RatioCardDerived(),
    )

    // Row keys. `labelKey` lower-cases and drops punctuation, so these are the shapes a
    // published row label reduces to. Matched longest/most-specific first where they overlap.
    private const val KEY_REVENUE = "revenue"
    private const val KEY_SALES = "sales"
    private const val KEY_TOTAL_REVENUE = "totalrevenue"
    private const val KEY_OTHER_INCOME = "otherincome"
    private const val KEY_OPERATING_PROFIT = "operatingprofit"
    private const val KEY_DEPRECIATION = "depreciation"
    private const val KEY_INTEREST = "interest"
    private const val KEY_NET_PROFIT = "netprofit"
    private const val KEY_CFO = "cashfromoperatingactivity"
    private const val KEY_FCF = "freecashflow"
    private const val KEY_CFO_OP = "cfoop"
    private const val KEY_RECEIVABLES = "tradereceivables"
    private const val KEY_DEBTORS = "debtors"
    private const val KEY_SUNDRYDEBTORS = "sundrydebtors"
    private const val KEY_INVENTORY = "inventory"
    private const val KEY_TOTAL_ASSETS = "totalassets"
    private const val KEY_GOODWILL = "goodwill"
    private const val KEY_BORROWINGS = "borrowings"
    private const val KEY_BORROWING = "borrowing"
    private const val KEY_EQUITY_CAPITAL = "equitycapital"
    private const val KEY_RESERVES = "reserves"
    private const val KEY_PROMOTERS = "promoters"
    private const val KEY_PLEDGE = "sharepledge"
    private const val KEY_PLEDGED_SHARES = "pledgedshares"
    private const val KEY_PLEDGE_BARE = "pledge"

    private const val CRORE = 10_000_000.0

    fun derive(
        profitLoss: ParsedTable?,
        balanceSheet: ParsedTable?,
        cashFlow: ParsedTable?,
        shareholding: ParsedTable?,
        topRatios: TopRatiosSnapshot?,
        ratiosCard: RatiosCard = RatiosCard.EMPTY,
    ): DerivedResult {
        val notes = mutableListOf<String>()

        val revenue = series(profitLoss, KEY_SALES, KEY_REVENUE, KEY_TOTAL_REVENUE)
        val otherIncome = series(profitLoss, KEY_OTHER_INCOME)
        val interest = series(profitLoss, KEY_INTEREST)
        val netProfit = series(profitLoss, KEY_NET_PROFIT)
        val depreciation = series(profitLoss, KEY_DEPRECIATION)
        val operatingProfit = series(profitLoss, KEY_OPERATING_PROFIT)

        val totalAssets = series(balanceSheet, KEY_TOTAL_ASSETS)
        val equityCapital = series(balanceSheet, KEY_EQUITY_CAPITAL)
        val equity = sumSeries(balanceSheet, listOf(KEY_EQUITY_CAPITAL, KEY_RESERVES))
        val debt = series(balanceSheet, KEY_BORROWINGS, KEY_BORROWING)

        val cfo = series(cashFlow, KEY_CFO)
        val fcf = series(cashFlow, KEY_FCF)
        val cfoToOpPercent = series(cashFlow, KEY_CFO_OP)

        val receivables = series(balanceSheet, KEY_RECEIVABLES, KEY_DEBTORS, KEY_SUNDRYDEBTORS)
        val inventory = series(balanceSheet, KEY_INVENTORY)
        val goodwill = series(balanceSheet, KEY_GOODWILL)

        val ebit = combine(operatingProfit, depreciation) { op, dep -> op + dep }

        for ((label, map) in listOf(
            "Trade Receivables" to receivables,
            "Inventory" to inventory,
            "Goodwill" to goodwill,
        )) {
            if (map.isEmpty()) {
                notes += "'$label' is not published on the collapsed balance-sheet card (it lives " +
                    "behind an AJAX sub-schedule this parser does not request, plan.md §3.6); " +
                    "the map stays empty rather than zero-filled"
            }
        }
        notes += ratioCardNotes(ratiosCard)
        if (cfoToOpPercent.isNotEmpty()) {
            notes += "'CFO/OP' is published as a percentage; CashFlowDerived.cfoToOpByYear stores " +
                "the ratio (percent / 100)"
        }

        val pledge = latestValue(shareholding, KEY_PLEDGE, KEY_PLEDGED_SHARES, KEY_PLEDGE_BARE)
        if (pledge == null) {
            notes += "promoter pledge is not published on this page; " +
                "CashFlowDerived.promoterPledgePercent stays null, never 0"
        }

        val derived = CashFlowDerived(
            cfoByYear = cfo,
            patByYear = netProfit,
            fcfByYear = fcf,
            cfoToOpByYear = cfoToOpPercent.mapValues { (_, v) -> v / 100.0 },
            receivablesByYear = receivables,
            inventoryByYear = inventory,
            revenueByYear = revenue,
            otherIncomeByYear = otherIncome,
            totalAssetsByYear = totalAssets,
            goodwillByYear = goodwill,
            debtByYear = debt,
            equityByYear = equity,
            interestByYear = interest,
            ebitByYear = ebit,
            sharesOutstanding = sharesOutstanding(equityCapital, topRatios, notes),
            promoterPledgePercent = pledge,
        )
        return DerivedResult(
            derived = derived,
            notes = notes,
            ratiosCard = ratiosCard,
            ratioCard = ratioCard(ratiosCard),
        )
    }

    /**
     * The `ratios` card as per-fiscal-year series, plus the notes that explain what it did and
     * did not publish.
     *
     * Both halves of plan.md §8.1's working-capital flags travel here: **DSO** ([dsoByYear]) is
     * the receivables signal and [inventoryDaysByYear] the inventory one, each exactly as the
     * source published it — nothing is recomputed, because `debtor days = receivables ÷ credit
     * sales × 365` is not computable from this page (no receivables line, no credit-sales line)
     * and `inventory ÷ revenue × 365` would print a confident number that is not inventory days.
     *
     * [extras] is the domain's own `RatioExtras`, keyed by the page's period labels, so
     * `RatioComputation.receivableDays` / `.inventoryDays` can prefer the published figure and
     * explain the absence when there is none. [toRatiosCardSeries] adapts it to the red-flag
     * phase's `RatiosCardSeries`.
     */
    fun ratioCard(card: RatiosCard): RatioCardDerived = RatioCardDerived(
        dsoByYear = card.byYear(*RatiosCardParser.DEBTOR_DAYS_KEYS),
        inventoryDaysByYear = card.byYear(*RatiosCardParser.INVENTORY_DAYS_KEYS),
        daysPayableByYear = card.byYear(*RatiosCardParser.DAYS_PAYABLE_KEYS),
        cashConversionCycleByYear = card.byYear(*RatiosCardParser.CASH_CONVERSION_CYCLE_KEYS),
        workingCapitalDaysByYear = card.byYear(*RatiosCardParser.WORKING_CAPITAL_DAYS_KEYS),
        rocePercentByYear = card.byYear(*RatiosCardParser.ROCE_KEYS),
        roePercentByYear = card.byYear(*RatiosCardParser.ROE_KEYS),
        extras = card.extras(),
    )

    /**
     * Why the card's series are what they are — reported on every parse, never only when empty.
     *
     * Three things the UI and the domain's `NOT_SOURCED` reporting need, in one place:
     * 1. the card is absent or empty (the facade also puts it in `sectionsMissing`),
     * 2. which named series this page does and does not publish, and
     * 3. that gross margin and EV/EBITDA remain unsourceable **after** this card was checked, so
     *    the ratio tab can print a measured reason instead of a blank.
     */
    private fun ratioCardNotes(card: RatiosCard): List<String> {
        val notes = mutableListOf<String>()
        when {
            card.isAbsent -> notes += "section '${RatiosCardParser.SECTION_ID}' is absent from this " +
                "page, so debtor days and inventory days are unavailable rather than 0"
            card.isEmpty -> notes += "section '${RatiosCardParser.SECTION_ID}' is present but " +
                "publishes no data row, so debtor days and inventory days are unavailable rather " +
                "than 0"
            else -> {
                notes += "section '${RatiosCardParser.SECTION_ID}' published ${card.rowKeys.size} " +
                    "row(s) — ${card.rowLabels.joinToString(", ")}"
                for ((label, keys) in listOf(
                    "Debtor Days" to RatiosCardParser.DEBTOR_DAYS_KEYS,
                    "Inventory Days" to RatiosCardParser.INVENTORY_DAYS_KEYS,
                    "Days Payable" to RatiosCardParser.DAYS_PAYABLE_KEYS,
                    "ROCE %" to RatiosCardParser.ROCE_KEYS,
                    "ROE %" to RatiosCardParser.ROE_KEYS,
                )) {
                    val series = card.series(*keys)
                    if (series.isEmpty()) {
                        notes += "'$label' is not published on this page's " +
                            "'${RatiosCardParser.SECTION_ID}' card (rows present: " +
                            "${card.rowLabels.joinToString(", ")}); the series is absent, never 0"
                    }
                }
            }
        }
        if (card.cashFromOperations.isEmpty()) {
            notes += "the '${RatiosCardParser.SECTION_ID}' card publishes no 'Cash from Operating " +
                "Activity' row on any verified page; cash from operations is read from the " +
                "'cash-flow' statement into CashFlowDerived.cfoByYear instead, so " +
                "RatioExtras.cashFromOperations is empty rather than duplicated"
        }
        notes += "gross margin is not derivable: it needs cost of goods sold, and no section of " +
            "this page publishes one — the '${RatiosCardParser.SECTION_ID}' card was checked and " +
            "carries only working-capital days and a returns percentage (plan.md §3.3), so " +
            "gross margin is reported NOT_SOURCED with this reason and never 0"
        notes += "EV/EBITDA is not derivable: the page publishes neither an enterprise value nor " +
            "an EBITDA line, and the '${RatiosCardParser.SECTION_ID}' card has neither either. " +
            "The 'EV / EBITDA' string that does appear on the page is a chart-metric selector " +
            "button (value=\"EV Multiple-Median EV Multiple-EBITDA\") that drives an AJAX chart, " +
            "not a published figure, so it is reported NOT_SOURCED with this reason and never 0"
        notes += "no EBITDA proxy is available from this page: there is no depreciation-and-" +
            "amortisation line on the '${RatiosCardParser.SECTION_ID}' card and no EBITDA row on " +
            "any statement, so no proxy is constructed"
        return notes
    }

    /**
     * `Equity Capital (₹ crore) × 1e7 ÷ Face Value (₹)` for the most recent year published.
     *
     * Both inputs are on the page, so this is a division of published figures rather than a
     * guess — the share count by definition, since equity capital is face value × shares
     * outstanding. It is `null` — never `0.0` — when either input is missing. Deliberately
     * uses **Equity Capital**, not net worth: reserves are not share capital.
     */
    private fun sharesOutstanding(
        equityCapital: Map<Int, Double>,
        topRatios: TopRatiosSnapshot?,
        notes: MutableList<String>,
    ): Double? {
        val faceValue = topRatios?.faceValue
        val latestCapital = equityCapital.maxByOrNull { it.key }?.value
        if (faceValue == null || faceValue <= 0.0 || latestCapital == null) {
            notes += "shares outstanding cannot be derived (face value or equity capital absent); " +
                "CashFlowDerived.sharesOutstanding stays null"
            return null
        }
        notes += "shares outstanding is derived as equity capital (₹ crore) / face value (₹), " +
            "both published on the page"
        return latestCapital * CRORE / faceValue
    }

    /**
     * Reads one row into a `year → value` map.
     *
     * Only columns whose header resolves to a fiscal year are kept, so the `TTM` column of
     * `profit-loss` is dropped: a trailing-twelve-months figure is not a fiscal year and must
     * not sit next to twelve annual ones as if it were.
     */
    private fun series(table: ParsedTable?, vararg keys: String): Map<Int, Double> {
        val row = StatementParsers.rowByKey(table, *keys) ?: return emptyMap()
        val years = table?.years.orEmpty()
        val result = LinkedHashMap<Int, Double>()
        for (i in years.indices) {
            val year = ScreenerHtml.yearOf(years[i]) ?: continue
            val value = row.values.getOrNull(i) ?: continue
            result[year] = value
        }
        return result
    }

    /** Element-wise sum of two series; a year present in only one contributes only that one. */
    private fun sumSeries(table: ParsedTable?, keys: List<String>): Map<Int, Double> {
        val maps = keys.map { series(table, it) }.filter { it.isNotEmpty() }
        if (maps.isEmpty()) return emptyMap()
        val result = LinkedHashMap<Int, Double>()
        for (map in maps) for ((year, value) in map) {
            result[year] = (result[year] ?: 0.0) + value
        }
        return result.toSortedMap()
    }

    private fun combine(
        a: Map<Int, Double>,
        b: Map<Int, Double>,
        op: (Double, Double) -> Double,
    ): Map<Int, Double> {
        val result = LinkedHashMap<Int, Double>()
        for (year in a.keys) {
            val left = a[year]
            val right = b[year]
            if (left != null && right != null) result[year] = op(left, right)
        }
        return result.toSortedMap()
    }

    /**
     * The most recently published value of [row] in [table].
     *
     * Used for the two figures the red-flag engine needs as a single number. The most recent
     * column is the one with the highest resolvable period, which for `shareholding` is
     * `Jun 2026` — the table is quarterly even though the label reads like a date series.
     */
    fun latestValue(table: ParsedTable?, vararg keys: String): Double? {
        if (table == null) return null
        val row = StatementParsers.rowByKey(table, *keys) ?: return null
        var bestKey = Long.MIN_VALUE
        var best: Double? = null
        for (i in table.years.indices) {
            val period = ScreenerHtml.periodOf(table.dateKeys.getOrNull(i), table.years.getOrNull(i))
            // Chronological key, never the raw column position: `shareholding` is quarterly, so
            // its last column is the most recent, but a page that reorders columns must not
            // silently change which figure counts as "current".
            val sortKey = period?.let { ScreenerHtml.periodSortKey(it) }
                ?: (ScreenerHtml.yearOf(table.years.getOrNull(i)) ?: 0) * 4L
            val value = row.values.getOrNull(i) ?: continue
            if (best == null || sortKey > bestKey) {
                bestKey = sortKey
                best = value
            }
        }
        return best
    }

    /** Most recent promoter holding percentage, or `null` when the page publishes no Promoters row. */
    fun latestPromoterPercent(shareholding: ParsedTable?): Double? = latestValue(shareholding, KEY_PROMOTERS)
}

/**
 * The `ratios` card's series, keyed by **fiscal year**, plus the domain's hand-off types.
 *
 * ### Why this is separate from [CashFlowDerived]
 * `CashFlowDerived` is a domain type this parser does not own, and its fields are rupee figures
 * and per-year aggregates of the statements — a `Debtor Days` of `20` sitting in a map whose
 * other values are ₹ crore would be a unit trap (plan.md §13, "misleading blank ratios"). Days and
 * percentages therefore travel in their own holder, and the map key shape matches every other
 * per-year map in the domain (`Int` fiscal year) so the two cannot be silently compared.
 *
 * ### Nothing here is recomputed
 * Every value is the figure the source published, keyed by the fiscal year of the column that
 * published it. `debtor days = receivables ÷ credit sales × 365` is *not* applied: this page
 * publishes neither receivables nor credit sales, and `inventory ÷ revenue × 365` is not
 * inventory days. A missing series is an empty map, never a map of zeros (plan.md §12).
 *
 * ### The two hand-offs, and why both exist
 * [extras] is `RatioComputation`'s `RatioExtras` — the producer-independent input the ratios
 * phase declared for this card, keyed by the page's own period labels (`"Mar 2026"`), because the
 * card can be quarterly on some pages and annual on others. [toRatiosCardSeries] produces the
 * red-flag phase's `RatiosCardSeries`, which is keyed by fiscal year.
 */
data class RatioCardDerived(
    /** `Debtor Days` — DSO, the receivables signal behind plan.md §8.1's receivables flag. */
    val dsoByYear: Map<Int, Double> = emptyMap(),
    /** `Inventory Days`, the input to plan.md §8.1's inventory-days flag. */
    val inventoryDaysByYear: Map<Int, Double> = emptyMap(),
    /** `Days Payable`, supporting evidence only. */
    val daysPayableByYear: Map<Int, Double> = emptyMap(),
    /** `Cash Conversion Cycle` as published; never recomputed from the three series above. */
    val cashConversionCycleByYear: Map<Int, Double> = emptyMap(),
    /** `Working Capital Days` as published. */
    val workingCapitalDaysByYear: Map<Int, Double> = emptyMap(),
    /** `ROCE %` as published — a percentage such as `10.0`, not `0.10`. */
    val rocePercentByYear: Map<Int, Double> = emptyMap(),
    /** `ROE %` as published. HDFCBANK's only profitability series. */
    val roePercentByYear: Map<Int, Double> = emptyMap(),
    /** The domain's producer-independent input, keyed by the page's period labels. */
    val extras: RatioExtras = RatioExtras(),
) {
    /** True when the card published none of the series above. */
    val isEmpty: Boolean
        get() = dsoByYear.isEmpty() && inventoryDaysByYear.isEmpty() && daysPayableByYear.isEmpty() &&
            cashConversionCycleByYear.isEmpty() && workingCapitalDaysByYear.isEmpty() &&
            rocePercentByYear.isEmpty() && roePercentByYear.isEmpty() && extras.isEmpty

    /** The latest fiscal year any series in this holder reaches, or `null` when all are empty. */
    val latestYear: Int?
        get() = listOf(
            dsoByYear, inventoryDaysByYear, daysPayableByYear, cashConversionCycleByYear,
            workingCapitalDaysByYear, rocePercentByYear, roePercentByYear,
        ).flatMap { it.keys }.maxOrNull()

    /**
     * The red-flag phase's `RatiosCardSeries`, fiscal-year keyed.
     *
     * `debtorDays` and `inventoryDays` are taken from `RatiosCardSeries.from(extras)` rather than
     * from this class, so the domain's own label-to-year conversion stays the single place that
     * logic exists; the three series `RatioExtras` does not carry today are filled from the maps
     * above, which were built with the same `ScreenerHtml.yearOf` rule the rest of `:data` uses.
     */
    fun toRatiosCardSeries(): RatiosCardSeries = RatiosCardSeries.from(extras).copy(
        daysPayable = daysPayableByYear,
        rocePercent = rocePercentByYear,
        roePercent = roePercentByYear,
    )
}
