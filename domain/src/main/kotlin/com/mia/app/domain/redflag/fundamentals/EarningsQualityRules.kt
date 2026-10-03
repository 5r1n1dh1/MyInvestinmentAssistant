package com.mia.app.domain.redflag.fundamentals

import com.mia.app.domain.model.Evidence
import com.mia.app.domain.model.FlagGroup
import com.mia.app.domain.model.FlagSeverity
import com.mia.app.domain.model.FlagTab
import com.mia.app.domain.model.RedFlag
import com.mia.app.domain.model.Source
import com.mia.app.domain.redflag.RedFlagContext
import com.mia.app.domain.ratios.RatioComputation

/**
 * `CFO < 0` while `PAT > 0`, for two or more consecutive fiscal years.
 *
 * **Book**, `[Ch.11-4 §Red Flag 4]`: *"One year may not indicate a problem. Several consecutive
 * years require explanation."* and `[Ch.11-4 §The Golden Rule]`: *"Profits must eventually become
 * cash."*
 *
 * ### Why the count is two, not one
 * The book sets the rule itself, so the threshold is not ours to soften. The other half of the
 * reason is in the same chapter: a single negative year follows a capex programme or a
 * working-capital build routinely, which is why the book says *require explanation* and not
 * *sell*. Two consecutive years is the smallest count for which "several years" is literally
 * true.
 *
 * ### Consecutive means adjacent fiscal years, both published
 * The run is walked backwards from the latest year that publishes **both** lines and stops at
 * the first year that either does not qualify or is not adjacent to the one before it. A gap in
 * the cash-flow statement therefore breaks the run rather than being skipped over: a 2026 and a
 * 2024 pair are two years with an absence between them, not "consecutive", and reporting them as
 * consecutive would overstate the finding.
 */
class CfoNegativeWhilePatPositiveRule : FundamentalsRule {

    override val id: String = FundamentalsFlagIds.CFO_NEGATIVE_WHILE_PAT_POSITIVE
    override val tab: FlagTab = FlagTab.FUNDAMENTALS

    override fun evaluate(context: RedFlagContext): List<RedFlag> {
        val run = qualifyingRun(context)
        if (run == null || run.size < FundamentalsPolicy.CFO_NEGATIVE_MIN_CONSECUTIVE_YEARS) {
            return emptyList()
        }
        val latest = run.last()
        val cfo = context.derived.cfoByYear.getValue(latest)
        val pat = context.derived.patByYear.getValue(latest)
        val label = FundamentalsEvidence.fyLabel(latest)

        val title = "${context.symbol} reported profit after tax in ${run.size} consecutive " +
            "fiscal years while operating cash flow was negative in all of them " +
            "(latest, $label: profit after tax ${FundamentalsEvidence.crore(pat)} against " +
            "operating cash flow ${FundamentalsEvidence.crore(cfo)})."

        val rows = mutableListOf<Evidence>()
        for (year in run) {
            rows += FundamentalsEvidence.statement(
                "Operating cash flow (${FundamentalsEvidence.fyLabel(year)})",
                FundamentalsEvidence.crore(context.derived.cfoByYear.getValue(year)),
                year,
            )
            rows += FundamentalsEvidence.statement(
                "Profit after tax (${FundamentalsEvidence.fyLabel(year)})",
                FundamentalsEvidence.crore(context.derived.patByYear.getValue(year)),
                year,
            )
        }
        rows += Evidence(
            "Consecutive fiscal years meeting the condition",
            FundamentalsEvidence.number(run.size.toDouble()),
            Source.DERIVED,
            FundamentalsEvidence.fiscalYearEnd(latest),
        )
        rows += FundamentalsEvidence.threshold(
            "Threshold",
            THRESHOLD_TEXT,
            FundamentalsEvidence.fiscalYearEnd(latest),
        )
        rows += context.sectorEvidenceRows()

        return listOf(
            RedFlag(
                id = id,
                group = FlagGroup.EARNINGS_QUALITY,
                severity = if (run.size >= FundamentalsPolicy.CFO_NEGATIVE_HIGH_CONSECUTIVE_YEARS) {
                    FlagSeverity.HIGH
                } else {
                    FlagSeverity.MEDIUM
                },
                tab = tab,
                title = title,
                evidence = rows,
                threshold = THRESHOLD_TEXT,
            ),
        )
    }

    override fun explainSkip(context: RedFlagContext): String? {
        val run = qualifyingRun(context) ?: return MISSING_INPUT
        // An empty run, or a run of one year, is a real answer rather than a gap: the check ran on
        // published figures and the book's "several consecutive years" was not met.
        return null
    }

    /**
     * The maximal adjacent run of qualifying years ending at the latest published year, or `null`
     * when no year publishes both lines at all.
     *
     * The empty list and the `null` are different answers and must stay different: an empty run
     * means "ran, nothing qualified", and `null` means "cannot be evaluated". Collapsing them is
     * exactly the silent-empty failure plan.md §12 warns about.
     */
    private fun qualifyingRun(context: RedFlagContext): List<Int>? {
        val cfo = context.derived.cfoByYear
        val pat = context.derived.patByYear
        val common = cfo.keys.filter { pat.containsKey(it) }.sortedDescending()
        if (common.isEmpty()) return null
        val run = mutableListOf<Int>()
        for (year in common) {
            val qualifies = cfo.getValue(year) < 0.0 && pat.getValue(year) > 0.0
            val adjacent = run.isEmpty() || run.last() - year == 1
            if (!qualifies || !adjacent) break
            run += year
        }
        return run.reversed()
    }

    private companion object {
        const val THRESHOLD_TEXT =
            "Operating cash flow negative while profit positive, 2+ consecutive fiscal years"

        const val MISSING_INPUT =
            "Not evaluated: no fiscal year publishes both 'Cash from Operating Activity' and " +
                "'Net Profit', which are the two figures this check compares."
    }
}

/**
 * Receivables growing materially faster than revenue — evaluated in **days sales outstanding**.
 *
 * ### The substitution, stated rather than hidden
 * plan.md §8.1 asks for `gReceivables − gRevenue > 10pp`. Our one fundamentals source publishes
 * **no receivables line at all** (`grep -i receivab` returns zero hits across all five captured
 * fixtures), so the ₹-based comparison cannot be computed and a rupee figure must never be
 * invented from revenue. What the source *does* publish, as a twelve-year series, is
 * `Debtor Days` — `average trade receivables ÷ revenue × 365` `[Ch.13-R1 §10.12]`, the same
 * measure the book uses. Ten days of DSO is roughly 2.7 % of a year's revenue turned into
 * receivables, so the day bands carry plan.md's intent into the only unit available.
 *
 * ### Sector suppression, and it is suppression rather than a lower threshold
 * The book's own warning for this ratio is *"Seasonality and industry norms should always be
 * considered"* `[Ch.13-R1 §10.12]`. For a sector where that is checkable we do not have the
 * norm, so the flag returns nothing and says so (see [FundamentalsSectors.workingCapitalSkip]).
 *
 * ### The revenue clause is omitted, never dangling
 * The wording template ends with *"while revenue {grew|fell} x%"* only when revenue growth is
 * computable. A clause reading *"while revenue"* with no figure is worse than no clause, so the
 * title simply stops at the day count.
 */
class ReceivablesGrowingFasterThanRevenueRule(
    private val ratiosCard: RatiosCardSeries? = null,
) : FundamentalsRule {

    override val id: String = FundamentalsFlagIds.RECEIVABLES_GROWING_FASTER_THAN_REVENUE
    override val tab: FlagTab = FlagTab.FUNDAMENTALS

    override fun evaluate(context: RedFlagContext): List<RedFlag> {
        if (FundamentalsSectors.workingCapitalSkip(context.sector) != null) return emptyList()
        val series = ratiosCard?.debtorDays.orEmpty()
        val pair = ratiosCard?.latestConsecutivePair(series) ?: return emptyList()
        val (previousYear, current) = pair
        val currentYear = current.first
        val currentValue = current.second
        val previousValue = series.getValue(previousYear)
        val change = currentValue - previousValue
        if (change < FundamentalsPolicy.WorkingCapitalDayBands.LOW) return emptyList()

        val fiscal = FundamentalsEvidence.fyLabel(currentYear)
        val growth = RatioComputation.revenueGrowth(context.derived, currentYear)
        val growthPercent = growth.orNull()
        val clause = when {
            growthPercent == null -> ""
            growthPercent >= 0.0 -> " while revenue grew " +
                "${FundamentalsEvidence.number(growthPercent, 1)} %"

            else -> " while revenue fell " +
                "${FundamentalsEvidence.number(-growthPercent, 1)} %"
        }
        val title = "Days sales outstanding rose from " +
            "${FundamentalsEvidence.number(previousValue)} to ${FundamentalsEvidence.number(currentValue)}" +
            " days in $fiscal (${FundamentalsEvidence.signed(change)} days)$clause."

        val asOf = FundamentalsEvidence.fiscalYearEnd(currentYear)
        val rows = mutableListOf(
            FundamentalsEvidence.statement(
                "Days sales outstanding ($fiscal)",
                FundamentalsEvidence.days(currentValue),
                currentYear,
            ),
            FundamentalsEvidence.statement(
                "Days sales outstanding (${FundamentalsEvidence.fyLabel(previousYear)})",
                FundamentalsEvidence.days(previousValue),
                previousYear,
            ),
            Evidence("Change", FundamentalsEvidence.signed(change) + " days", Source.DERIVED, asOf),
        )
        if (growthPercent != null) {
            rows += Evidence(
                "Revenue growth ($fiscal)",
                FundamentalsEvidence.signed(growthPercent, 1) + " %",
                Source.DERIVED,
                asOf,
            )
        }
        rows += Evidence(
            "Measure used",
            "Days sales outstanding (the source publishes no trade-receivables line, so the " +
                "rupee form of this check cannot be computed)",
            Source.DERIVED,
            asOf,
        )
        FundamentalsEvidence.sectorRow(context.sector, asOf)?.let { rows += it }
        rows += FundamentalsEvidence.threshold("Threshold", THRESHOLD_TEXT, asOf)

        return listOf(
            RedFlag(
                id = id,
                group = FlagGroup.EARNINGS_QUALITY,
                severity = daySeverity(change),
                tab = tab,
                title = title,
                evidence = rows,
                threshold = THRESHOLD_TEXT,
            ),
        )
    }

    override fun explainSkip(context: RedFlagContext): String? {
        FundamentalsSectors.workingCapitalSkip(context.sector)?.let { return it }
        if (ratiosCard?.debtorDays.isNullOrEmpty()) return MISSING_INPUT
        if (ratiosCard.latestConsecutivePair(ratiosCard.debtorDays) == null) {
            return "Not evaluated: 'Debtor Days' is published but the latest two consecutive " +
                "fiscal years are not both present, and this check is a year-on-year change."
        }
        return null
    }

    internal companion object {
        const val THRESHOLD_TEXT =
            "Days sales outstanding rising at least 10 days year on year"
        const val MISSING_INPUT =
            "Not evaluated: this check needs the source's own 'Debtor Days' series, which our " +
                "fundamentals page does not publish as a rupee receivables line. No receivables " +
                "figure is derived from revenue, because that would be a different measure."
    }
}

/**
 * Inventory days rising sharply year on year.
 *
 * **Book**, `[Ch.13-R1 §10.13]`: `inventory ÷ cost of goods sold × 365`, and *"Increasing
 * inventory days may suggest: slowing demand, overstocking, obsolete inventory, poor inventory
 * management."* The growth form is `[Ch.11-4 §Red Flag 3]`: *"Sales increase 8%. Inventory
 * increases 40%."*
 *
 * ### Days payable is supporting evidence, never a second flag
 * The spec asks for it because `Cash Conversion Cycle` is the better normalised measure and the
 * direction of days payable changes what the inventory move means: inventory days rising while
 * days payable is flat or falling is materially worse, while both rising together is supplier
 * financing. That is one evidence row inside this flag, not a separate finding — a user who saw
 * two flags for one working-capital shift would read them as two problems.
 *
 * ### The ten-day floor is calibrated against a real fixture
 * TITAN's published inventory days run `169 → 222` over twelve years, i.e. a fifty-day range in
 * a healthy watch business, so a ±5-day annual wobble is normal here and a five-day rule would
 * fire on nothing but noise.
 */
class InventoryDaysRisingRule(
    private val ratiosCard: RatiosCardSeries? = null,
) : FundamentalsRule {

    override val id: String = FundamentalsFlagIds.INVENTORY_DAYS_RISING
    override val tab: FlagTab = FlagTab.FUNDAMENTALS

    override fun evaluate(context: RedFlagContext): List<RedFlag> {
        if (FundamentalsSectors.workingCapitalSkip(context.sector) != null) return emptyList()
        val series = ratiosCard?.inventoryDays.orEmpty()
        val pair = ratiosCard?.latestConsecutivePair(series) ?: return emptyList()
        val (previousYear, current) = pair
        val currentYear = current.first
        val currentValue = current.second
        val previousValue = series.getValue(previousYear)
        val change = currentValue - previousValue
        if (change < FundamentalsPolicy.WorkingCapitalDayBands.LOW) return emptyList()

        val fiscal = FundamentalsEvidence.fyLabel(currentYear)
        val payableCurrent = ratiosCard.daysPayable[currentYear]
        val payablePrevious = ratiosCard.daysPayable[previousYear]
        val payableClause = if (payableCurrent != null && payablePrevious != null) {
            " while days payable moved from ${FundamentalsEvidence.number(payablePrevious)} to " +
                "${FundamentalsEvidence.number(payableCurrent)}"
        } else {
            ""
        }
        val title = "Inventory days rose from ${FundamentalsEvidence.number(previousValue)} to " +
            "${FundamentalsEvidence.number(currentValue)} days in $fiscal " +
            "(${FundamentalsEvidence.signed(change)} days)$payableClause."

        val asOf = FundamentalsEvidence.fiscalYearEnd(currentYear)
        val rows = mutableListOf(
            FundamentalsEvidence.statement(
                "Inventory days ($fiscal)",
                FundamentalsEvidence.days(currentValue),
                currentYear,
            ),
            FundamentalsEvidence.statement(
                "Inventory days (${FundamentalsEvidence.fyLabel(previousYear)})",
                FundamentalsEvidence.days(previousValue),
                previousYear,
            ),
            Evidence("Change", FundamentalsEvidence.signed(change) + " days", Source.DERIVED, asOf),
        )
        if (payableCurrent != null) {
            rows += FundamentalsEvidence.statement(
                "Days payable ($fiscal)",
                FundamentalsEvidence.days(payableCurrent),
                currentYear,
            )
        }
        if (payablePrevious != null) {
            rows += FundamentalsEvidence.statement(
                "Days payable (${FundamentalsEvidence.fyLabel(previousYear)})",
                FundamentalsEvidence.days(payablePrevious),
                previousYear,
            )
        }
        FundamentalsEvidence.sectorRow(context.sector, asOf)?.let { rows += it }
        rows += FundamentalsEvidence.threshold("Threshold", THRESHOLD_TEXT, asOf)

        return listOf(
            RedFlag(
                id = id,
                group = FlagGroup.EARNINGS_QUALITY,
                severity = daySeverity(change),
                tab = tab,
                title = title,
                evidence = rows,
                threshold = THRESHOLD_TEXT,
            ),
        )
    }

    override fun explainSkip(context: RedFlagContext): String? {
        FundamentalsSectors.workingCapitalSkip(context.sector)?.let { return it }
        if (ratiosCard?.inventoryDays.isNullOrEmpty()) return MISSING_INPUT
        if (ratiosCard.latestConsecutivePair(ratiosCard.inventoryDays) == null) {
            return "Not evaluated: 'Inventory Days' is published but the latest two consecutive " +
                "fiscal years are not both present, and this check is a year-on-year change."
        }
        return null
    }

    internal companion object {
        const val THRESHOLD_TEXT = "Inventory days rising at least 10 days year on year"
        const val MISSING_INPUT =
            "Not evaluated: this check needs the source's own 'Inventory Days' series. Inventory " +
                "days are never derived here, because the published page carries neither an " +
                "inventory line nor a cost-of-goods line, and dividing by revenue instead of cost " +
                "of goods would print a confident number that is not inventory days."
    }
}

/**
 * Other income as a percent of profit after tax.
 *
 * **Book**, `[Ch.11-3 §9.68]`: *"Recurring operating profit is generally more valuable than profit
 * driven by volatile or one-time other income."* Its worked example is PAT ₹1,000 Cr with ₹600 Cr
 * of other income: *"Investors should investigate whether the underlying operating business is
 * actually strong."* `[Ch.11-4 §Red Flag 5]` adds that recurring *"non-recurring"* income is a
 * classic warning sign.
 *
 * ### The flag measures the level, not the quality — and the wording says so
 * A company with a quarter of profit in recurring bank interest is fine; one with a quarter in
 * fair-value gains is not, and this ratio cannot tell them apart. So the title says *"items
 * outside operations"* rather than implying anything about those items, and the evidence carries
 * an explicit definition row.
 */
class OtherIncomeAsPctOfPatRule : FundamentalsRule {

    override val id: String = FundamentalsFlagIds.OTHER_INCOME_AS_PCT_OF_PAT
    override val tab: FlagTab = FlagTab.FUNDAMENTALS

    override fun evaluate(context: RedFlagContext): List<RedFlag> {
        if (FundamentalsSectors.otherIncomeSkip(context.sector) != null) return emptyList()
        val share = otherIncomeShare(context) ?: return emptyList()
        val (year, percent, otherIncome, pat) = share
        if (percent <= FundamentalsPolicy.OTHER_INCOME_MEDIUM_PERCENT) return emptyList()

        val fiscal = FundamentalsEvidence.fyLabel(year)
        val title = "Other income was ${FundamentalsEvidence.crore(otherIncome)} against profit " +
            "after tax of ${FundamentalsEvidence.crore(pat)} in $fiscal — " +
            "${FundamentalsEvidence.number(percent, 1)} % of profit came from items outside " +
            "operations."

        val asOf = FundamentalsEvidence.fiscalYearEnd(year)
        val rows = mutableListOf(
            FundamentalsEvidence.statement(
                "Other income ($fiscal)",
                FundamentalsEvidence.crore(otherIncome),
                year,
            ),
            FundamentalsEvidence.statement(
                "Profit after tax ($fiscal)",
                FundamentalsEvidence.crore(pat),
                year,
            ),
            Evidence(
                "Other income as % of PAT",
                FundamentalsEvidence.percent(percent, 1),
                Source.DERIVED,
                asOf,
            ),
        )
        context.derived.ebitByYear[year]?.let { ebit ->
            rows += FundamentalsEvidence.statement(
                "EBIT, operating profit plus depreciation ($fiscal)",
                FundamentalsEvidence.crore(ebit),
                year,
            )
        }
        rows += Evidence(
            "Definition",
            "Share of profit after tax that came from outside operating profit. It measures the " +
                "level of non-operating income, not its quality: the same share is routine for " +
                "some businesses and a one-off for others.",
            Source.DERIVED,
            asOf,
        )
        FundamentalsEvidence.sectorRow(context.sector, asOf)?.let { rows += it }
        rows += FundamentalsEvidence.threshold("Threshold", THRESHOLD_TEXT, asOf)

        return listOf(
            RedFlag(
                id = id,
                group = FlagGroup.EARNINGS_QUALITY,
                severity = if (percent > FundamentalsPolicy.OTHER_INCOME_HIGH_PERCENT) {
                    FlagSeverity.HIGH
                } else {
                    FlagSeverity.MEDIUM
                },
                tab = tab,
                title = title,
                evidence = rows,
                threshold = THRESHOLD_TEXT,
            ),
        )
    }

    override fun explainSkip(context: RedFlagContext): String? {
        FundamentalsSectors.otherIncomeSkip(context.sector)?.let { return it }
        if (otherIncomeShare(context) == null) return MISSING_INPUT
        return null
    }

    /** Latest fiscal year publishing both lines, as `(year, percent, otherIncome, pat)`. */
    private fun otherIncomeShare(
        context: RedFlagContext,
    ): FourValues? {
        val other = context.derived.otherIncomeByYear
        val pat = context.derived.patByYear
        val common = other.keys.filter { pat.containsKey(it) }.sortedDescending()
        for (year in common) {
            val profit = pat.getValue(year)
            if (profit == 0.0) continue
            val income = other.getValue(year)
            if (!income.isFinite() || !profit.isFinite()) continue
            return FourValues(year, income / profit * 100.0, income, profit)
        }
        return null
    }

    /** Year, share in percent, other income in ₹ crore, PAT in ₹ crore. */
    private data class FourValues(
        val year: Int,
        val percent: Double,
        val otherIncome: Double,
        val pat: Double,
    )

    private companion object {
        const val THRESHOLD_TEXT = "Other income exceeds 30% of profit after tax"
        const val MISSING_INPUT =
            "Not evaluated: no fiscal year publishes both 'Other Income' and 'Net Profit', " +
                "which are the two figures this check divides."
    }
}

/** Day-band severity shared by the two working-capital flags. */
internal fun daySeverity(change: Double): FlagSeverity = when {
    change >= FundamentalsPolicy.WorkingCapitalDayBands.HIGH -> FlagSeverity.HIGH
    change >= FundamentalsPolicy.WorkingCapitalDayBands.MEDIUM -> FlagSeverity.MEDIUM
    else -> FlagSeverity.LOW
}

/** spec §6.2's sector evidence row, appended by a rule that does not build its own rows. */
internal fun RedFlagContext.sectorEvidenceRows(): List<Evidence> =
    FundamentalsEvidence.sectorRow(sector, ratios.asOf)?.let(::listOf).orEmpty()