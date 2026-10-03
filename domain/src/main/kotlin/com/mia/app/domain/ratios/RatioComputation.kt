package com.mia.app.domain.ratios

import com.mia.app.domain.model.AnnualRow
import com.mia.app.domain.model.CashFlowDerived
import com.mia.app.domain.model.Direction
import com.mia.app.domain.model.Sector
import com.mia.app.domain.ratios.Computation.Companion.missing
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow

/**
 * A computed figure, or the reason there is not one.
 *
 * plan.md §12: "A parser must return a health flag, not an empty list. Silent-empty is
 * indistinguishable from 'no red flags'." The same rule applies to every ratio here: a bare
 * `null` cannot tell the UI whether the page lacked the line, the sector made it meaningless,
 * or the maths ran out of inputs. The `init` block makes the reason **mandatory** whenever the
 * value is absent, so the compiler, not a reviewer, enforces it.
 */
data class Computation<out T>(val value: T?, val note: String?) {
    init {
        require(value != null || !note.isNullOrBlank()) {
            "a computation without a value must say why (plan.md §12)"
        }
    }

    val isComputed: Boolean get() = value != null

    /** The value, or `null`. */
    fun orNull(): T? = value

    companion object {
        fun <T> of(value: T, note: String? = null): Computation<T> = Computation(value, note)

        fun <T> missing(reason: String): Computation<T> = Computation(null, reason)
    }
}

/** One `(fiscal year, value)` point of an annual series. */
data class YearValue(val year: Int, val value: Double)

/**
 * The shape of a trend the fundamentals tab draws beside a ratio (plan.md §7.2 ranks 2 and 7).
 *
 * [changePerYear] is the linear change in points per year, because "operating margin went from
 * 11% to 17%" and "it moved 1.2pp a year" answer different questions and only the first is
 * robust when a single year is missing. [direction] is `null` for a flat window rather than
 * an invented [Direction]: "no change" is a finding, not an unknown, but it is neither up nor
 * down, and the UI has to be able to say so.
 */
data class Trend(
    val latest: YearValue?,
    val oldest: YearValue?,
    /** Latest minus oldest, in the ratio's own unit (percentage points for a percent ratio). */
    val changeAbsolute: Double?,
    /** `(latest / oldest - 1) × 100`, in percent. `null` when [oldest] is not positive. */
    val changePercent: Double?,
    /** [changeAbsolute] spread over [yearsSpanned], in points per year. */
    val changePerYear: Double?,
    val direction: Direction?,
    /** The points actually used, oldest first, with gaps already dropped. */
    val points: List<YearValue>,
    val yearsSpanned: Int,
    val note: String? = null,
) {
    val isEmpty: Boolean get() = points.isEmpty()
    val isRising: Boolean get() = direction == Direction.UP
    val isFalling: Boolean get() = direction == Direction.DOWN
}

/** What [promoterHoldingTrend] found, with the comparison it actually made. */
data class PromoterTrend(
    val latestPercent: Double?,
    val latestPeriod: String?,
    val previousPercent: Double?,
    val previousPeriod: String?,
    /** Latest minus previous, in **percentage points**. */
    val changePercentagePoints: Double?,
    /** Which "previous" was used — the phrase "down 1.5pp" is meaningless without it. */
    val comparison: Comparison,
) {
    /** A promoter sale and a promoter purchase read the same without this. */
    enum class Comparison {
        /** Same quarter, previous year: a true year-on-year change. */
        SAME_PERIOD_PRIOR_YEAR,

        /** The previous published column, which was not a year earlier. */
        PREVIOUS_COLUMN,

        /** Only one column published. */
        NONE,
    }

    val isDeclining: Boolean
        get() = comparison != Comparison.NONE && (changePercentagePoints ?: 0.0) < 0.0

    val isRising: Boolean
        get() = comparison != Comparison.NONE && (changePercentagePoints ?: 0.0) > 0.0
}

/**
 * The `ratios` card on a screener.in page — **Debtor Days, Inventory Days, Cash from
 * operations** — as an input, for the day a parser owns it.
 *
 * ### Why this type already exists
 * The card is on every page and is currently unparsed, but `inventoryDays` and
 * `receivableDays` need a **days-based** figure that the collapsed balance sheet cannot
 * provide: inventory days is `inventory ÷ cost of goods sold × 365`, and screener.in publishes
 * neither an Inventory line nor a cost-of-goods line (verified across all five fixtures).
 * Inventing `inventory ÷ revenue × 365` would produce a number that looks right and is not,
 * so today the honest answer is a null with a reason.
 *
 * ### Why it is a defaulted parameter and not a new overload
 * Every function that will eventually consume it takes `extras: RatioExtras = RatioExtras()`.
 * Adding the parameter later is source-compatible for every existing caller, and the
 * "prefer the published figure, else explain the absence" branch is written and tested now
 * rather than being rewritten later. That is the whole point of introducing the type with an
 * empty default today.
 *
 * Keys are the page's own period labels (`"Mar 2026"`, `"Jun 2026"`), because the card is
 * quarterly in some places and annual in others and the label is what the user will see.
 */
data class RatioExtras(
    val debtorDays: Map<String, Double> = emptyMap(),
    val inventoryDays: Map<String, Double> = emptyMap(),
    val cashFromOperations: Map<String, Double> = emptyMap(),
) {
    val isEmpty: Boolean
        get() = debtorDays.isEmpty() && inventoryDays.isEmpty() && cashFromOperations.isEmpty()

    fun debtorDaysFor(year: Int): Double? = valueForYear(debtorDays, year)

    fun inventoryDaysFor(year: Int): Double? = valueForYear(inventoryDays, year)

    fun cashFromOperationsFor(year: Int): Double? = valueForYear(cashFromOperations, year)

    private fun valueForYear(series: Map<String, Double>, year: Int): Double? =
        series.entries.firstOrNull { (label, _) -> PeriodColumn.of(label).year == year }?.value
}

/**
 * One `(fiscal year, value)` point of an annual series.
 *
 * Derived from the screener.in statements by `data`, and the half of ratio computation the
 * `data` layer is not allowed to do (plan.md §4.3: `:domain` must not import `:data`).
 *
 * ### What is computed here, and what is not
 *
 * **Computed:** debt/equity (the page publishes no D/E), interest coverage, CFO/OP, revenue
 * CAGR and growth, FCF vs PAT, goodwill as a share of assets, promoter-holding trend, and the
 * operating-margin and gross-margin trends.
 *
 * **Never computed, with a reason:** gross margin (no cost-of-goods line), EV/EBITDA (no
 * enterprise value, no EBITDA), inventory/receivable days (no Inventory or Trade Receivables
 * line and no cost of goods), current ratio (its components are absent), promoter pledge (the
 * page publishes no pledge figure). Each of those returns a null **and a note** — a zero
 * would be a fabrication and a bare blank would be indistinguishable from a bug.
 *
 * ### Sector first
 * [debtToEquity] and [interestCoverage] consult [RatioPriority] before touching a number. A
 * bank has no meaningful D/E (plan.md §7.3) and its published `Interest` row is interest
 * *income*; handing such a company a D/E and a coverage ratio would produce two confident
 * wrong numbers, which is the failure mode §7.3 exists to prevent.
 *
 * ### Year alignment
 * Every per-year function resolves its year from the **intersection of the maps it needs**, so
 * it can never divide a 2026 numerator by a 2025 denominator. Where a year is absent from
 * one side, the caller gets a null with the year named — never a zero, and never a figure
 * assembled from two different years.
 */
object RatioComputation {

    /** Default window for the margin trends: plan.md §7.2 ranks 2 and 7 both ask for 5 years. */
    const val TREND_YEARS: Int = 5

    /** Fiscal years plan.md §7.2 rank 5 asks for. */
    const val CAGR_SHORT_YEARS: Int = 3
    const val CAGR_LONG_YEARS: Int = 5

    /** Indian fiscal years end in March, so that is the annual column label of a P&L. */
    const val FISCAL_YEAR_END_MONTH: Int = 3

    // ------------------------------------------------------------------------ leverage

    /**
     * `borrowings ÷ (equity capital + reserves)`, for the latest year that publishes both.
     *
     * screener.in publishes neither D/E nor its components as a ratio, but the balance-sheet
     * summary card does publish `Borrowings`, `Equity Capital` and `Reserves`
     * ([CashFlowDerived.debtByYear] / [CashFlowDerived.equityByYear]), and
     * `equity capital + reserves` is net worth by definition. Verified: RELIANCE
     * `402,962 ÷ 904,030 = 0.4457`; TITAN `30,621 ÷ 15,703 = 1.95`.
     *
     * **Suppressed for [Sector.BANKING]**: a bank's borrowings are the business, not leverage
     * (plan.md §7.3). Suppressed — not computed and hidden — so the UI can say *not meaningful*
     * instead of showing a blank it cannot explain.
     */
    fun debtToEquity(
        derived: CashFlowDerived,
        sector: Sector,
        year: Int? = null,
    ): Computation<Double> {
        suppressedBy(sector, RatioId.DEBT_TO_EQUITY)?.let { return it }
        val at = sharedYear(year, derived.debtByYear, derived.equityByYear)
            ?: return missing(
                "no fiscal year publishes both 'Borrowings' and 'Equity Capital + Reserves'" +
                    yearHint(year) + ", which are the two inputs of debt/equity",
            )
        val debt = derived.debtByYear.getValue(at)
        val equity = derived.equityByYear.getValue(at)
        if (equity == 0.0) return missing("net worth is zero in $at, so debt/equity is undefined")
        val ratio = divide(debt, equity) ?: return missing("debt/equity is undefined in $at")
        return Computation.of(
            ratio,
            "₹${trim(debt)} Cr borrowings ÷ ₹${trim(equity)} Cr net worth (equity capital + " +
                "reserves), $at",
        )
    }

    /**
     * `EBIT ÷ interest expense`, for the latest year that publishes both.
     *
     * EBIT is `operating profit + depreciation`, both already aggregated into
     * [CashFlowDerived.ebitByYear] by the data layer. Verified: RELIANCE
     * `236,753 ÷ 27,061 = 8.7489`; TCS `77,958 ÷ 1,227 = 63.5354`. The result is a
     * **multiple**, not a percent.
     *
     * **Suppressed for [Sector.BANKING]**: a bank's published `Interest` row is interest income
     * (verified on the HDFCBANK fixture), so the ratio would be inverted nonsense.
     */
    fun interestCoverage(
        derived: CashFlowDerived,
        sector: Sector,
        year: Int? = null,
    ): Computation<Double> {
        suppressedBy(sector, RatioId.INTEREST_COVERAGE)?.let { return it }
        val at = sharedYear(year, derived.ebitByYear, derived.interestByYear)
            ?: return missing(
                "no fiscal year publishes both an EBIT (operating profit + depreciation) and an " +
                    "interest expense line" + yearHint(year),
            )
        val ebit = derived.ebitByYear.getValue(at)
        val interest = derived.interestByYear.getValue(at)
        if (interest == 0.0) return missing("interest expense is zero in $at, so coverage is undefined")
        val ratio = divide(ebit, interest) ?: return missing("interest coverage is undefined in $at")
        return Computation.of(
            ratio,
            "EBIT ₹${trim(ebit)} Cr ÷ interest ₹${trim(interest)} Cr, $at",
        )
    }

    /**
     * `cash from operations ÷ operating profit`, as the **ratio** the page means.
     *
     * The page prints `"113%"` for RELIANCE; [CashFlowDerived.cfoToOpByYear] stores `1.13`.
     * This function passes that ratio through unchanged rather than multiplying by 100,
     * because `Ratios.cfoToOp` is a ratio field (`RatioMapper`'s documented unit) and a silent
     * ×100 here would show a 113× earnings-quality ratio.
     */
    fun cfoToOp(derived: CashFlowDerived, year: Int? = null): Computation<Double> {
        val at = year
            ?: derived.cfoToOpByYear.keys.maxOrNull()
            ?: return missing("this page publishes no CFO/OP row")
        val value = derived.cfoToOpByYear[at]
            ?: return missing("this page publishes no CFO/OP row for $at")
        return Computation.of(
            value,
            "CFO ÷ operating profit for $at, published as a percentage and stored as a ratio",
        )
    }

    // ------------------------------------------------------------------------ growth

    /**
     * Compound annual growth of revenue over [years] fiscal years, in percent.
     *
     * ### Missing years are skipped, not assumed away
     * The window is `[latest - years, latest]` — a three-year CAGR uses endpoints three years
     * apart, which is what makes the exponent `1/3`. The endpoints are the **latest year with
     * data inside the window** and the **oldest year with data inside it**, and the exponent is
     * the number of fiscal years those endpoints actually span. So for
     * `{2018: 100, 2020: 121, 2021: 133.1}` the answer is `(133.1/100)^(1/3) - 1 = 10.00%`:
     * 2019 is simply absent and the three elapsed years are still three. Dividing by `years`
     * while using endpoints two years apart would overstate growth; treating the gap as
     * continuity would fabricate a 2019.
     *
     * Requires at least two endpoints and both strictly positive — a revenue series that
     * starts at or below zero has no growth rate.
     */
    fun revenueCagr(
        derived: CashFlowDerived,
        years: Int = CAGR_SHORT_YEARS,
        latestYear: Int? = null,
    ): Computation<Double> {
        require(years >= 1) { "a CAGR window of $years years is meaningless" }
        val revenue = derived.revenueByYear
        if (revenue.isEmpty()) return missing("this page publishes no revenue row")
        val latest = latestYear ?: revenue.keys.maxOrNull()
            ?: return missing("this page publishes no revenue row")
        val window = revenue.filterKeys { it in (latest - years)..latest }
        if (window.isEmpty()) {
            return missing("no revenue published in the $years years ending $latest")
        }
        val start = window.keys.minOrNull() ?: return missing("no revenue published")
        val end = window.keys.maxOrNull() ?: return missing("no revenue published")
        if (end <= start) {
            return missing(
                "only ${end - start + 1} year(s) of revenue in the $years-year window, so a " +
                    "$years-year growth rate cannot be computed",
            )
        }
        val from = window.getValue(start)
        val to = window.getValue(end)
        if (from <= 0.0 || to <= 0.0) {
            return missing("revenue CAGR is undefined when either endpoint is not positive ($start/$end)")
        }
        val span = (end - start).toDouble()
        val rate = ((to / from).pow(1.0 / span) - 1.0) * 100.0
        if (!rate.isFinite()) return missing("revenue CAGR is not a finite number for $start..$end")
        val gaps = (start + 1 until end).filterNot { it in window }
        return Computation.of(
            rate,
            "revenue $start ₹${trim(from)} Cr → $end ₹${trim(to)} Cr over $span year(s)" +
                if (gaps.isEmpty()) "" else "; year(s) ${gaps.joinToString(", ")} not published, skipped",
        )
    }

    /**
     * Revenue growth year-on-year, in percent.
     *
     * **Strictly one year.** Unlike [revenueCagr] this does *not* skip a gap: comparing 2026
     * with 2024 and calling the result "growth this year" is a false statement, so a missing
     * immediate prior year yields a null with that year named.
     */
    fun revenueGrowth(derived: CashFlowDerived, year: Int? = null): Computation<Double> {
        val revenue = derived.revenueByYear
        if (revenue.isEmpty()) return missing("this page publishes no revenue row")
        val end = year ?: revenue.keys.maxOrNull() ?: return missing("this page publishes no revenue row")
        val from = revenue[end] ?: return missing("revenue is not published for $end")
        val prior = revenue[end - 1] ?: return missing("revenue is not published for ${end - 1}, so " +
            "year-on-year growth for $end cannot be computed")
        if (prior == 0.0) return missing("revenue was zero in ${end - 1}, so growth is undefined")
        val growth = divide(from, prior)?.let { (it - 1.0) * 100.0 }
            ?: return missing("revenue growth is undefined for $end")
        return Computation.of(
            growth,
            "revenue ${end - 1} ₹${trim(prior)} Cr → $end ₹${trim(from)} Cr",
        )
    }

    // ----------------------------------------------------------------- earnings quality

    /**
     * `free cash flow ÷ PAT`, as a ratio — plan.md §7.2 rank 4, "the #1 earnings-quality tell".
     *
     * Below 1.0 means the reported profit was not cash; persistently below 1.0 with a positive
     * PAT is the §8.1 earnings-quality red flag's arithmetic. A ratio, not a percent:
     * RELIANCE `70,023 ÷ 95,754 = 0.73`.
     */
    fun fcfToPat(derived: CashFlowDerived, year: Int? = null): Computation<Double> {
        val at = sharedYear(year, derived.fcfByYear, derived.patByYear)
            ?: return missing("no fiscal year publishes both 'Free Cash Flow' and 'Net Profit'")
        val fcf = derived.fcfByYear.getValue(at)
        val pat = derived.patByYear.getValue(at)
        if (pat == 0.0) return missing("PAT is zero in $at, so FCF/PAT is undefined")
        val ratio = divide(fcf, pat) ?: return missing("FCF/PAT is undefined in $at")
        return Computation.of(ratio, "free cash flow ₹${trim(fcf)} Cr ÷ PAT ₹${trim(pat)} Cr, $at")
    }

    /**
     * Free cash flow in ₹ crore for the latest published year (plan.md §7.2 rank 4's second half).
     */
    fun freeCashFlow(derived: CashFlowDerived, year: Int? = null): Computation<Double> {
        val at = year ?: derived.fcfByYear.keys.maxOrNull()
            ?: return missing("this page publishes no 'Free Cash Flow' row")
        val value = derived.fcfByYear[at] ?: return missing("'Free Cash Flow' is not published for $at")
        return Computation.of(value, "free cash flow for $at, ₹ crore")
    }

    /** PAT in ₹ crore for the latest published year. */
    fun pat(derived: CashFlowDerived, year: Int? = null): Computation<Double> {
        val at = year ?: derived.patByYear.keys.maxOrNull()
            ?: return missing("this page publishes no 'Net Profit' row")
        val value = derived.patByYear[at] ?: return missing("'Net Profit' is not published for $at")
        return Computation.of(value, "profit after tax for $at, ₹ crore")
    }

    // ------------------------------------------------------------- working capital / BS

    /**
     * Inventory days, from the source's own `ratios` card when one has been parsed.
     *
     * Never derived from revenue: the textbook formula divides by **cost of goods sold**, and
     * screener.in publishes neither COGS nor an Inventory line on the collapsed balance-sheet
     * summary (verified across all five fixtures — the line lives behind an AJAX sub-schedule
     * that plan.md §3.6 does not permit us to fetch). `inventory ÷ revenue × 365` would print a
     * confident two-digit number that is not inventory days, so the answer is a null with the
     * reason attached until a parser supplies [RatioExtras].
     */
    fun inventoryDays(
        derived: CashFlowDerived,
        year: Int? = null,
        extras: RatioExtras = RatioExtras(),
    ): Computation<Double> {
        if (!extras.isEmpty) {
            val at = year ?: latestYearOf(extras.inventoryDays.keys)
            if (at != null) {
                val value = extras.inventoryDaysFor(at)
                if (value != null) {
                    return Computation.of(value, "inventory days for $at, as published by the source")
                }
            }
        }
        return missing(
            if (derived.inventoryByYear.isEmpty()) {
                "inventory days needs cost of goods sold; this page publishes no Inventory line " +
                    "on the balance-sheet summary and no cost of goods, so it is never derived"
            } else {
                "inventory is published for ${derived.inventoryByYear.keys.sorted()} but " +
                    "cost of goods sold is not, so inventory days cannot be computed"
            },
        )
    }

    /**
     * Debtor (trade receivable) days, from the source's own `ratios` card when parsed.
     *
     * Same reasoning as [inventoryDays]: receivables are not on the summary card, and the
     * correct denominator (credit sales) is not either.
     */
    fun receivableDays(
        derived: CashFlowDerived,
        year: Int? = null,
        extras: RatioExtras = RatioExtras(),
    ): Computation<Double> {
        if (!extras.isEmpty) {
            val at = year ?: latestYearOf(extras.debtorDays.keys)
            if (at != null) {
                val value = extras.debtorDaysFor(at)
                if (value != null) {
                    return Computation.of(value, "debtor days for $at, as published by the source")
                }
            }
        }
        return missing(
            if (derived.receivablesByYear.isEmpty()) {
                "debtor days needs credit sales; this page publishes no Trade Receivables line " +
                    "on the balance-sheet summary and no cost of goods, so it is never derived"
            } else {
                "receivables are published for ${derived.receivablesByYear.keys.sorted()} but " +
                    "cost of goods sold is not, so debtor days cannot be computed"
            },
        )
    }

    /**
     * Goodwill as a percent of total assets — the input to plan.md §8.1's "goodwill > 20% of
     * total assets" balance-sheet flag.
     *
     * A percent because the threshold is stated in percent and a 0.2 that has to be multiplied
     * by 100 at the call site is a bug waiting to happen.
     */
    fun goodwillToAssets(derived: CashFlowDerived, year: Int? = null): Computation<Double> {
        val at = sharedYear(year, derived.goodwillByYear, derived.totalAssetsByYear)
            ?: return missing(
                "no fiscal year publishes both 'Goodwill' and 'Total Assets'; the collapsed " +
                    "balance-sheet summary card carries no Goodwill line at all (verified on all " +
                    "five fixtures), so this stays null rather than zero",
            )
        val goodwill = derived.goodwillByYear.getValue(at)
        val assets = derived.totalAssetsByYear.getValue(at)
        if (assets == 0.0) return missing("total assets are zero in $at")
        val percent = divide(goodwill, assets)?.times(100.0)
            ?: return missing("goodwill/assets is undefined in $at")
        return Computation.of(
            percent,
            "goodwill ₹${trim(goodwill)} Cr ÷ total assets ₹${trim(assets)} Cr, $at",
        )
    }

    /** Promoter pledge percentage, or the reason the page publishes none. */
    fun promoterPledge(derived: CashFlowDerived): Computation<Double> =
        derived.promoterPledgePercent?.let {
            Computation.of(it, "promoter pledge, as published")
        } ?: missing(
            "no pledge figure is published on this page (searched: zero hits), so it stays null " +
                "and never 0",
        )

    // ------------------------------------------------------------------------ shareholding

    /**
     * Promoter holding and its change — plan.md §7.2 rank 11, "capital-allocation confidence".
     *
     * ### Why [periods] is a separate argument
     * [AnnualRow.values] is index-aligned with the table's **column headers**, and
     * [FundamentalsSnapshot.shareholding] carries the rows without the headers. "The most
     * recent column" therefore cannot be found from the rows alone, and picking one by array
     * order would make the answer depend on a page's column order. The caller passes the
     * published labels (`["Sep 2023", "Dec 2023", …, "Jun 2026"]`) and this function sorts by
     * the period each label denotes.
     *
     * ### Year-on-year, and what "previous" means
     * The shareholding card is **quarterly**, so there are four columns per calendar year. The
     * primary comparison is the same month one year earlier (a true year-on-year change); when
     * that column is absent it falls back to the previous published column and says so in
     * [PromoterTrend.comparison], because "promoters down 1.5pp" is a different statement when
     * the two figures are three months apart.
     *
     * A page with no `Promoters` row returns a null with a reason (verified: HDFCBANK).
     */
    fun promoterHoldingTrend(
        periods: List<String>,
        shareholding: List<AnnualRow>,
    ): Computation<PromoterTrend> {
        val row = shareholding.firstOrNull { labelKey(it.label) == "promoters" }
            ?: return missing(
                "this page publishes no 'Promoters' row in the shareholding card, so promoter " +
                    "holding is null rather than 0",
            )
        val points = shareholdingPoints(row, periods)
        if (points.isEmpty()) {
            return missing("the Promoters row is present but carries no readable quarter")
        }
        val latest = points.last()
        val sameMonthPriorYear = points.firstOrNull { it.year == latest.year - 1 && it.month == latest.month }
        val previousColumn = points.getOrNull(points.size - 2)
        val comparison = when {
            sameMonthPriorYear != null -> PromoterTrend.Comparison.SAME_PERIOD_PRIOR_YEAR
            previousColumn != null -> PromoterTrend.Comparison.PREVIOUS_COLUMN
            else -> PromoterTrend.Comparison.NONE
        }
        val previous = sameMonthPriorYear ?: previousColumn
        val change = if (previous == null) null else latest.value - previous.value
        val priorLabel = previous?.label.orEmpty()
        val note = when {
            comparison == PromoterTrend.Comparison.SAME_PERIOD_PRIOR_YEAR && previous != null ->
                "promoter holding ${trim(latest.value)}% in ${latest.label}, " +
                    "${signed(change)}pp versus $priorLabel"
            comparison == PromoterTrend.Comparison.PREVIOUS_COLUMN && previous != null ->
                "promoter holding ${trim(latest.value)}% in ${latest.label}; the same quarter a " +
                    "year earlier is not published, so this compares against the previous " +
                    "column $priorLabel, not a year on year"
            else ->
                "promoter holding ${trim(latest.value)}% in ${latest.label}; only one quarter is " +
                    "published, so there is no trend"
        }
        return Computation.of(
            PromoterTrend(
                latestPercent = latest.value,
                latestPeriod = latest.label,
                previousPercent = previous?.value,
                previousPeriod = previous?.label,
                changePercentagePoints = change,
                comparison = comparison,
            ),
            note,
        )
    }

    /** The latest promoter holding percentage alone, in percent. */
    fun promoterHolding(
        periods: List<String>,
        shareholding: List<AnnualRow>,
    ): Computation<Double> = promoterHoldingTrend(periods, shareholding).let { computed ->
        val trend = computed.value
            ?: return Computation<Double>(null, computed.note)
        trend.latestPercent?.let { Computation.of(it, computed.note) }
            ?: Computation.missing(computed.note ?: "promoter holding is not published")
    }

    // ------------------------------------------------------------------------- margins

    /**
     * Operating margin and its [years]-year trend — plan.md §7.2 rank 2.
     *
     * Reads the published `OPM %` row, which is a percent already (the page prints `"17"` for
     * RELIANCE's Mar 2026, not `0.17`), so nothing is scaled. A bank publishes
     * `Financing Margin %` instead (verified on HDFCBANK) and gets a null with that said.
     */
    fun operatingMarginTrend(
        periods: List<String>,
        profitLoss: List<AnnualRow>,
        years: Int = TREND_YEARS,
    ): Computation<Trend> {
        val points = annualPoints(profitLoss, periods, setOf("opm", "opmargin", "operatingmargin"))
        if (points.isEmpty()) {
            return missing(
                "this page publishes no 'OPM %' row; a bank publishes 'Financing Margin %' " +
                    "instead, which is not an operating margin (plan.md §7.3)",
            )
        }
        return fiveYearTrend(points, years, "operating margin (OPM %)")
    }

    /**
     * Gross margin and its trend — plan.md §7.2 rank 7.
     *
     * The series is empty in practice: gross margin is `revenue - cost of goods`, and
     * screener.in publishes no cost-of-goods line (verified across all five fixtures). The
     * function exists and is wired so that the day a source provides the line this is a
     * five-point trend and not a new feature; today it says why it is empty.
     */
    fun grossMarginTrend(
        grossMarginByYear: Map<Int, Double>,
        years: Int = TREND_YEARS,
    ): Computation<Trend> {
        if (grossMarginByYear.isEmpty()) {
            return missing(
                "gross margin needs cost of goods sold; screener.in's consolidated page " +
                    "publishes no such line, so the trend is absent rather than zero-filled " +
                    "(plan.md §3.3)",
            )
        }
        val points = grossMarginByYear.entries
            .filter { it.value.isFinite() }
            .sortedBy { it.key }
            .map { YearValue(it.key, it.value) }
        return fiveYearTrend(points, years, "gross margin")
    }

    /**
     * The [years]-year trend of any annual series, with gaps dropped.
     *
     * The window is the [years] fiscal years ending at the latest point (`latest - years`
     * through `latest`), so a five-year trend is six observations and [Trend.yearsSpanned] is
     * five — the same span convention as [revenueCagr], so a trend and a CAGR describe the same
     * period. Years absent from the series are skipped rather than interpolated:
     * [Trend.changePerYear] then describes the window that actually exists, so a series missing
     * its middle year reports a slower annual change instead of a wrong total.
     *
     * Needs two points to say anything. A single point is a value, not a trend, so the result
     * is a null with the count named.
     */
    fun fiveYearTrend(
        points: List<YearValue>,
        years: Int = TREND_YEARS,
        label: String = "series",
    ): Computation<Trend> {
        require(years >= 1) { "a $years-year trend window is meaningless" }
        val usable = points.filter { it.value.isFinite() }.sortedBy { it.year }
        if (usable.isEmpty()) return missing("no $label data to plot")
        val latest = usable.last().year
        val window = usable.filter { it.year in (latest - years)..latest }
        if (window.size < 2) {
            return Computation(
                null,
                "$label has ${window.size} usable year(s) in the $years-year window; a trend " +
                    "needs two",
            )
        }
        val first = window.first()
        val last = window.last()
        val span = (last.year - first.year).coerceAtLeast(1)
        val change = last.value - first.value
        val perYear = change / span
        val relative = if (first.value > 0.0) divide(last.value, first.value)?.times(100.0)?.minus(100.0) else null
        val dropped = (first.year + 1 until last.year).filterNot { y -> window.any { it.year == y } }
        val trend = Trend(
            latest = last,
            oldest = first,
            changeAbsolute = change,
            changePercent = relative,
            changePerYear = perYear,
            direction = when {
                abs(perYear) < TREND_FLAT_BAND -> null
                perYear > 0.0 -> Direction.UP
                else -> Direction.DOWN
            },
            points = window,
            yearsSpanned = span,
        )
        return Computation.of(
            trend,
            "$label ${trim(first.value)} in ${first.year} → ${trim(last.value)} in ${last.year}" +
                " (${signed(change)} over $span year(s)" +
                if (dropped.isEmpty()) "" else "; year(s) ${dropped.joinToString(", ")} not published, skipped",
        )
    }

    /**
     * Dividend payout ratio in percent, from the published `Dividend Payout %` row.
     *
     * Pairs with dividend yield in plan.md §7.2 rank 12: yield says what you receive, payout
     * says how much of the earnings are actually being paid out.
     */
    fun dividendPayout(
        periods: List<String>,
        profitLoss: List<AnnualRow>,
        year: Int? = null,
    ): Computation<Double> {
        val points = annualPoints(profitLoss, periods, setOf("dividendpayout", "dividendpayoutratio"))
        if (points.isEmpty()) return missing("this page publishes no 'Dividend Payout %' row")
        val target = year ?: points.last().year
        val point = points.firstOrNull { it.year == target }
            ?: return missing("'Dividend Payout %' is not published for $target")
        return Computation.of(point.value, "dividend payout ${trim(point.value)}% of PAT, $target")
    }

    /** `0` below this per-year change is reported as flat ([Trend.direction] `null`). */
    const val TREND_FLAT_BAND: Double = 1e-9

    // ----------------------------------------------------------------------- internals

    /** The sector-level suppression for [id], or `null` when the sector does not suppress it. */
    private fun suppressedBy(sector: Sector, id: RatioId): Computation<Double>? =
        if (RatioPriority.sectorStateFor(sector, id) == RatioState.SUPPRESSED_FOR_SECTOR) {
            Computation(null, RatioPriority.sectorNoteFor(sector, id))
        } else {
            null
        }

    /** Latest fiscal year any of [labels] denotes; `null` when none of them parses. */
    private fun latestYearOf(labels: Collection<String>): Int? =
        labels.mapNotNull { PeriodColumn.of(it).year }.maxOrNull()

    /** `", including $year"` when a specific year was asked for, so the message names it. */
    private fun yearHint(year: Int?): String = if (year == null) "" else ", including $year"

    /** Latest (or requested) year present in **every** map, so a ratio never mixes two years. */
    private fun sharedYear(year: Int?, vararg maps: Map<Int, Double>): Int? {
        if (maps.isEmpty()) return null
        val common = maps.reduce { acc, m -> acc.filterKeys(m::containsKey) }.keys
        val candidate = year ?: common.maxOrNull() ?: return null
        return candidate.takeIf { common.contains(it) }
    }

    /** `a / b`, or `null` rather than infinity or NaN. */
    private fun divide(a: Double, b: Double): Double? {
        if (b == 0.0 || !b.isFinite()) return null
        val result = a / b
        return if (result.isFinite()) result else null
    }

    private fun trim(value: Double): String =
        if (value == value.toLong().toDouble()) {
            value.toLong().toString()
        } else {
            // Locale.ROOT so a note reads "8.75" on every device rather than "8,75".
            String.format(Locale.ROOT, "%.2f", value)
        }

    private fun signed(value: Double?): String {
        if (value == null) return "n/a"
        return (if (value >= 0) "+" else "") + trim(value)
    }

    private fun labelKey(label: String?): String {
        val source = label.orEmpty()
        return buildString(source.length) {
            for (ch in source) if (ch.isLetterOrDigit()) append(ch.lowercaseChar())
        }
    }

    /** One promoter-holding observation: the value plus the period that published it. */
    private data class HoldingPoint(val label: String, val year: Int, val month: Int, val value: Double)

    private fun shareholdingPoints(row: AnnualRow, periods: List<String>): List<HoldingPoint> {
        val points = mutableListOf<HoldingPoint>()
        for (index in row.values.indices) {
            val value = row.values[index] ?: continue
            if (!value.isFinite()) continue
            val label = periods.getOrNull(index) ?: continue
            val column = PeriodColumn.of(label)
            points += HoldingPoint(label.trim(), column.year ?: continue, column.month ?: 1, value)
        }
        return points.sortedBy { it.year * 12 + it.month }
    }

    /** Every `(year, value)` in the first row whose label key is in [keys], gaps dropped. */
    private fun annualPoints(rows: List<AnnualRow>, periods: List<String>, keys: Set<String>): List<YearValue> {
        val row = rows.firstOrNull { labelKey(it.label) in keys } ?: return emptyList()
        val points = mutableListOf<YearValue>()
        for (index in row.values.indices) {
            val value = row.values[index] ?: continue
            if (!value.isFinite()) continue
            val label = periods.getOrNull(index) ?: continue
            val year = PeriodColumn.of(label).year ?: continue
            points += YearValue(year, value)
        }
        return points.sortedBy { it.year }
    }
}

/**
 * A published column label, reduced to the two facts a trend needs.
 *
 * ### Why :domain parses labels again
 * `ScreenerHtml.yearOf` / `periodOf` in `:data` already do this, but plan.md §4.3 forbids
 * `:domain` from importing `:data`, and plan.md §12 says to unit-test everything in `:domain`
 * on a plain JVM. So the parsing lives here as well, deliberately kept small and defensive:
 * `"Mar 2026"`, `"Mar'26"`, `"Jun 2026"`, `"2026-03-31"` and a bare `"2026"` all resolve,
 * while `"TTM"`, `""` and anything else resolve to `null` and are skipped. A `TTM` column must
 * never be treated as a fiscal year — it is twelve months that overlap the annual columns.
 */
internal data class PeriodColumn(val label: String, val year: Int?, val month: Int?) {
    companion object {
        private val MONTHS = listOf(
            "JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC",
        )

        fun of(label: String?): PeriodColumn {
            val text = label.orEmpty().trim().uppercase().replace('\'', ' ')
            if (text.isEmpty() || text == "TTM") return PeriodColumn(label.orEmpty(), null, null)
            val fourDigit = Regex("(?<!\\d)(\\d{4})(?!\\d)").find(text)
            val twoDigit = Regex("(?<!\\d)(\\d{2})(?!\\d)").find(text)
            val year = when {
                fourDigit != null -> fourDigit.value.toIntOrNull()?.takeIf { it in 1900..2100 }
                twoDigit != null -> (2000 + (twoDigit.value.toIntOrNull() ?: -1)).takeIf { it in 1900..2100 }
                else -> null
            }
            if (year == null) return PeriodColumn(label.orEmpty(), null, null)
            val monthToken = Regex("\\b([A-Z]{3})\\b").find(text)?.groupValues?.get(1)
            val month = monthToken?.let { MONTHS.indexOf(it) }?.takeIf { it >= 0 }?.plus(1)
            return PeriodColumn(label.orEmpty(), year, month)
        }
    }
}