package com.mia.app.domain.redflag.fundamentals

import com.mia.app.domain.model.Sector
import com.mia.app.domain.redflag.RedFlagContext
import com.mia.app.domain.redflag.RedFlagRule

/**
 * A fundamentals red-flag rule that can also say **why it produced nothing**.
 *
 * ### Why this exists on top of [RedFlagRule]
 * plan.md §12 and spec §11 rule 8:
 *
 * > "A parser must return a health flag, not an empty list. Silent-empty is indistinguishable
 * > from 'no red flags' — the most dangerous possible failure."
 *
 * `RedFlagEngine.evaluate` returns `List<RedFlag>` and isolates a crashing rule; it does not ask
 * a rule to explain itself, and it is owned by another agent. "Returned no flag" has two very
 * different meanings — *ran and did not trip* and *could not be evaluated at all* — and only one
 * of them may be rendered as silence (spec §11 rule 8: *"Suppressed ≠ clean… Absence is only for
 * a check that ran and did not trip"*).
 *
 * [explainSkip] is the difference. It returns `null` when the check ran and did not trip, and a
 * user-facing reason when the check could not be evaluated, so the caller can turn it into a
 * `RuleSkip` and the UI can print *"Not evaluated — <reason>"* rather than a green tick.
 */
interface FundamentalsRule : RedFlagRule {

    /**
     * Why this rule returned nothing for [context], or `null` when it ran and did not trip.
     *
     * The reason is user-facing prose: it names what was missing or why the comparison does not
     * apply, and never blames the user or the stock.
     */
    fun explainSkip(context: RedFlagContext): String?
}

/**
 * The `ratios` card's series, handed to the rules that need them.
 *
 * ### Why an optional constructor parameter and not a [RedFlagContext] field
 * [RedFlagContext] is the fixed seam owned by the orchestrator, and the `ratios` card — which
 * carries debtor days, inventory days, days payable, `ROCE %` and `ROE %` — is parsed by another
 * agent at a different time. Adding a field there would have coupled two independent phases, so
 * the series travel as a constructor argument that defaults to `null`.
 *
 * ### What `null` means, precisely
 * It means **"the source does not publish this, or no parser has supplied it yet"** — never
 * "the value is zero" and never "the company is clean". Rules that need one of these series
 * return no flag and a skip reason naming the missing series. Two of the ten fundamentals flags
 * are permanently in that state (goodwill, promoter pledge: spec §2.3) and are declared in
 * [FundamentalsPolicy.DECLARED_COVERAGE_GAPS] rather than hidden.
 *
 * ### Why years are keys
 * `RatioComputation` keys its per-year maps by fiscal year and drops the `TTM` column, so an
 * `Int` year keeps one representation across the whole `:domain` layer. [from] converts the
 * `RatioExtras` period labels (`"Mar 2026"`) into those keys.
 */
data class RatiosCardSeries(
    /** `Debtor Days`, in days — the proxy for receivables growth. */
    val debtorDays: Map<Int, Double> = emptyMap(),
    /** `Inventory Days`, in days. */
    val inventoryDays: Map<Int, Double> = emptyMap(),
    /** `Days Payable`, in days. Only used as supporting evidence for the inventory flag. */
    val daysPayable: Map<Int, Double> = emptyMap(),
    /** `ROCE %`, as published (a percentage such as `10.3`, not `0.103`). */
    val rocePercent: Map<Int, Double> = emptyMap(),
    /** `ROE %`, as published. The only profitability series a bank publishes. */
    val roePercent: Map<Int, Double> = emptyMap(),
) {
    val isEmpty: Boolean
        get() = debtorDays.isEmpty() && inventoryDays.isEmpty() && daysPayable.isEmpty() &&
            rocePercent.isEmpty() && roePercent.isEmpty()

    /**
     * The latest two **consecutive** fiscal years in [series], oldest first.
     *
     * Consecutive is deliberate: both working-capital flags are year-on-year changes, and
     * comparing a 2026 figure with the 2024 one because 2025 is missing would report a two-year
     * move as a one-year one.
     */
    fun latestConsecutivePair(series: Map<Int, Double>): Pair<Int, Pair<Int, Double>>? {
        if (series.isEmpty()) return null
        val latest = series.keys.maxOrNull() ?: return null
        val previous = latest - 1
        val currentValue = series[latest] ?: return null
        val previousValue = series[previous] ?: return null
        if (!currentValue.isFinite() || !previousValue.isFinite()) return null
        return previous to (latest to currentValue)
    }

    /** The latest published value of [series], with its year, or `null`. */
    fun latest(series: Map<Int, Double>): Pair<Int, Double>? {
        val year = series.keys.maxOrNull() ?: return null
        val value = series[year] ?: return null
        return if (value.isFinite()) year to value else null
    }

    companion object {
        /** The default: nothing has been supplied, so every dependent rule skips. */
        val EMPTY: RatiosCardSeries = RatiosCardSeries()

        /**
         * Reads whatever [extras] currently carries, keyed by fiscal year.
         *
         * The `RatioExtras` type was introduced by the ratios phase for exactly this purpose
         * ("the card is on every page and is currently unparsed, but…"), so this is the seam the
         * parser will hand its output to. Series that `RatioExtras` does not carry yet
         * (days payable, ROCE, ROE) stay empty and the dependent rules skip with a reason.
         */
        fun from(extras: com.mia.app.domain.ratios.RatioExtras): RatiosCardSeries = RatiosCardSeries(
            debtorDays = keyedByFiscalYear(extras.debtorDays),
            inventoryDays = keyedByFiscalYear(extras.inventoryDays),
        )

        /**
         * `{"Mar 2026": 20.0}` → `{2026: 20.0}`.
         *
         * A label that does not resolve to a fiscal year is **dropped**, never guessed: `"TTM"`
         * is twelve months that overlap the annual columns, so a value under that header would
         * otherwise appear to be a fiscal year and be compared against real ones.
         */
        fun keyedByFiscalYear(series: Map<String, Double>): Map<Int, Double> {
            val result = LinkedHashMap<Int, Double>()
            for ((label, value) in series) {
                val year = fiscalYearOf(label) ?: continue
                if (value.isFinite()) result[year] = value
            }
            return result.toSortedMap()
        }

        /**
         * The fiscal year a published column label denotes, or `null`.
         *
         * Kept small and local on purpose: `:domain` may not import `:data` (plan.md §4.3), so
         * `ScreenerHtml.yearOf` is not available here, and borrowing the ratios package's
         * internal parser would couple this phase to another agent's visibility choices.
         */
        fun fiscalYearOf(label: String?): Int? {
            val text = label.orEmpty().trim().uppercase().replace('\'', ' ')
            if (text.isEmpty() || text == "TTM") return null
            val four = Regex("(?<!\\d)(\\d{4})(?!\\d)").find(text)?.value?.toIntOrNull()
            if (four != null && four in 1900..2100) return four
            val two = Regex("(?<!\\d)(\\d{2})(?!\\d)").find(text)?.value?.toIntOrNull()
                ?: return null
            return (2000 + two).takeIf { it in 1900..2100 }
        }
    }
}

/**
 * Sector-level verdicts the rules share, so two rules cannot disagree about which sector a
 * check is meaningless for.
 *
 * Every function here returns a user-facing reason when the answer is "do not evaluate", which
 * is what [FundamentalsRule.explainSkip] forwards.
 */
internal object FundamentalsSectors {

    /** Why the working-capital day flags are not evaluated, or `null` when they are. */
    fun workingCapitalSkip(sector: Sector): String? {
        if (sector !in FundamentalsPolicy.WORKING_CAPITAL_NORM_SUPPRESSED_SECTORS) return null
        val own = if (sector == Sector.OTHER) {
            "the sector is unresolved, and industry norms are the whole basis of this measure"
        } else {
            "${com.mia.app.domain.ratios.SectorResolver.displayNameOf(sector)} is a sector with " +
                "structurally long credit terms or inventory cycles, and we publish no industry " +
                "norm to judge the change against"
        }
        return "Not evaluated for $own. The textbook requires the comparison: \"Seasonality and " +
            "industry norms should always be considered\" (Ch.13-R1 §10.12) and \"Some " +
            "industries naturally require higher inventory levels than others\" (Ch.13-R1 " +
            "§10.13)."
    }

    /** Why other income as a share of profit is not evaluated, or `null` when it is. */
    fun otherIncomeSkip(sector: Sector): String? {
        if (sector !in FundamentalsPolicy.OTHER_INCOME_SUPPRESSED_SECTORS) return null
        return "Not evaluated for ${com.mia.app.domain.ratios.SectorResolver.displayNameOf(sector)}: " +
            "non-operating income is a structural component of this income statement rather than " +
            "a top-up to operating profit, so the share says nothing about earnings quality " +
            "(plan.md §7.3, spec §6.1)."
    }

    /** Why interest coverage is not evaluated, or `null` when it is. */
    fun interestCoverageSkip(sector: Sector): String? {
        if (sector !in FundamentalsPolicy.INTEREST_COVERAGE_SUPPRESSED_SECTORS) return null
        val detail = if (sector == Sector.BANKING) {
            "its published Interest row is interest income, not interest expense, so EBIT divided " +
                "by it is meaningless (verified on the HDFCBANK fixture)"
        } else {
            "interest is a cost of goods sold for this business rather than a financing cost, so " +
            "an EBIT-to-interest multiple does not measure the same thing"
        }
        return "Not evaluated for ${com.mia.app.domain.ratios.SectorResolver.displayNameOf(sector)}: " +
            "$detail (plan.md §7.3, spec §4.3)."
    }

    /** The evidence row spec §6.2 requires when the sector is unresolved, else `null`. */
    fun unresolvedSectorEvidence(sector: Sector): String? =
        if (sector == Sector.OTHER) FundamentalsPolicy.UNRESOLVED_SECTOR_VALUE else null
}