package com.mia.app.domain.model

/**
 * Red-flag contracts (plan.md §8).
 *
 * Every flag is a deterministic threshold check with its evidence attached. A flag must
 * never be a bare assertion: the UI renders [RedFlag.evidence] when the user taps it.
 *
 * Wording rules (plan.md §8.3): factual statements only —
 * "Receivables grew 34% while revenue grew 9%", never "This company is risky".
 */

enum class FlagSeverity {
    HIGH,
    MEDIUM,
    LOW,
}

/** Which tab surfaces the flag. Flags appear at the top of both tabs (plan.md §8.3). */
enum class FlagTab {
    FUNDAMENTALS,
    TECHNICALS,
    BOTH,
}

enum class FlagGroup {
    EARNINGS_QUALITY,
    BALANCE_SHEET,
    VALUATION_TRAP,
    TECHNICAL,
}

/** One number behind a flag. Tapping a flag shows exactly these. */
data class Evidence(
    val label: String,
    val value: String,
    /** Where the number came from, so the user can judge it (plan.md §1.4 rule 3). */
    val source: Source = Source.DERIVED,
    val asOf: java.time.LocalDate? = null,
)

data class RedFlag(
    val id: String,
    val group: FlagGroup,
    val severity: FlagSeverity,
    val tab: FlagTab,
    /** Factual one-liner, e.g. "Debt/Equity rose from 0.4 to 1.9 over 3 years". */
    val title: String,
    /** The numbers behind [title]. Never empty for a rendered flag. */
    val evidence: List<Evidence>,
    /** Threshold that tripped, rendered for transparency. */
    val threshold: String,
) {
    init {
        require(evidence.isNotEmpty()) { "Red flag $id must carry evidence (plan.md §8.3)" }
    }
}

/**
 * Result of parsing a fundamentals page.
 *
 * plan.md §12: "A parser must return a health flag, not an empty list. Silent-empty is
 * indistinguishable from 'no red flags' — the most dangerous possible failure."
 */
data class ParserHealth(
    val ok: Boolean,
    val sectionsFound: List<String> = emptyList(),
    val sectionsMissing: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
) {
    val hasMissingSections: Boolean get() = sectionsMissing.isNotEmpty()

    companion object {
        fun healthy(vararg sections: String) = ParserHealth(true, sections.toList())

        fun partial(found: List<String>, missing: List<String>, note: String? = null) =
            ParserHealth(false, found, missing, listOfNotNull(note))

        fun failed(reason: String) = ParserHealth(false, emptyList(), emptyList(), listOf(reason))
    }
}

/** Everything a fundamentals tab needs, plus the parse health that produced it. */
data class FundamentalsSnapshot(
    val symbol: String,
    val name: String,
    val sector: Sector,
    val sectorResolved: Boolean,
    val ratios: Ratios,
    val health: ParserHealth,
    val quarters: List<QuarterRecord> = emptyList(),
    val profitLoss: List<AnnualRow> = emptyList(),
    val balanceSheet: List<AnnualRow> = emptyList(),
    val cashFlow: List<AnnualRow> = emptyList(),
    val cashFlowDerived: CashFlowDerived = CashFlowDerived(),
    val shareholding: List<AnnualRow> = emptyList(),
)

/** One quarter from the screener.in `quarters` table (13 quarters available). */
data class QuarterRecord(
    val year: Int,
    val quarter: Int,
    val sales: Double? = null,
    val expenses: Double? = null,
    val operatingProfit: Double? = null,
    val opmPercent: Double? = null,
    val otherIncome: Double? = null,
    val interest: Double? = null,
    val depreciation: Double? = null,
    val eps: Double? = null,
)

/** A row of an annual statement, values aligned to the table's year columns. */
data class AnnualRow(
    val label: String,
    val values: List<Double?>,
)

/**
 * Aggregates the red-flag engine needs from the cash-flow / P&L tables.
 * Nullable throughout: a missing statement must not become a fabricated zero.
 */
data class CashFlowDerived(
    val cfoByYear: Map<Int, Double> = emptyMap(),
    val patByYear: Map<Int, Double> = emptyMap(),
    val fcfByYear: Map<Int, Double> = emptyMap(),
    val cfoToOpByYear: Map<Int, Double> = emptyMap(),
    val receivablesByYear: Map<Int, Double> = emptyMap(),
    val inventoryByYear: Map<Int, Double> = emptyMap(),
    val revenueByYear: Map<Int, Double> = emptyMap(),
    val otherIncomeByYear: Map<Int, Double> = emptyMap(),
    val totalAssetsByYear: Map<Int, Double> = emptyMap(),
    val goodwillByYear: Map<Int, Double> = emptyMap(),
    val debtByYear: Map<Int, Double> = emptyMap(),
    val equityByYear: Map<Int, Double> = emptyMap(),
    val interestByYear: Map<Int, Double> = emptyMap(),
    val ebitByYear: Map<Int, Double> = emptyMap(),
    val sharesOutstanding: Double? = null,
    /** Promoter pledge percentage if the source exposes it; null otherwise. */
    val promoterPledgePercent: Double? = null,
)