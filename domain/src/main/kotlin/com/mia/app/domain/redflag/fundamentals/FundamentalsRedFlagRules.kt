package com.mia.app.domain.redflag.fundamentals

/**
 * The ten fundamentals red-flag rules, and the capability gaps they can never close.
 *
 * ### Why a registry rather than a constructor list at each call site
 * The rules are pure functions of [com.mia.app.domain.redflag.RedFlagContext], so composing them
 * is a list — but a list built by hand at three call sites is a list that will drift, and a rule
 * silently dropped from one of them is a red flag that never appears for some users. [all] is the
 * single composition point; [ruleIds] exists so a test can assert the count and the identity of
 * every id, which is how "15 flags fire, 2 are permanently suppressed" (spec §8) becomes a
 * machine-checked statement rather than a comment.
 *
 * ### The three rules that need the `ratios` card
 * [ReceivablesGrowingFasterThanRevenueRule], [InventoryDaysRisingRule] and
 * [LowPeWithFallingReturnsRule] read series the `ratios` card publishes — debtor days, inventory
 * days, days payable, `ROCE %`, `ROE %` — and that card is parsed by another phase. They take it
 * as an optional constructor argument rather than a [com.mia.app.domain.redflag.RedFlagContext]
 * field, so this phase neither depends on that parser nor blocks it. With no series supplied,
 * those three rules return nothing **and a skip reason**: they never guess a value, and in
 * particular never manufacture a receivables figure out of revenue.
 *
 * ### The declared gaps
 * [DECLARED_COVERAGE_GAPS] is spec §2.3's "optionally a static line in Settings → Data coverage"
 * turned into code. Two of the ten flags — goodwill and promoter pledge — are complete,
 * unit-tested and **permanently unable to fire**, because no permitted source publishes the input.
 * Shipping the rules and declaring the gap is the difference between a known limitation and a
 * silent omission; the alternative the spec rejects — substituting fixed assets for goodwill, or
 * promoter holding for pledge — would produce a confidently wrong number under an id the user
 * believes they understand.
 */
object FundamentalsRedFlagRules {

    /**
     * All ten rules, in the order the flag groups appear in plan.md §8.1.
     *
     * @param ratiosCard the `ratios`-card series, when a parser has produced them. `null` (the
     *   default) makes the three dependent rules skip with a reason instead of failing.
     */
    fun all(ratiosCard: RatiosCardSeries? = null): List<FundamentalsRule> = listOf(
        // Earnings quality.
        CfoNegativeWhilePatPositiveRule(),
        ReceivablesGrowingFasterThanRevenueRule(ratiosCard),
        InventoryDaysRisingRule(ratiosCard),
        OtherIncomeAsPctOfPatRule(),
        // Balance sheet.
        DebtToEquitySpikeRule(),
        InterestCoverageBelowTwoRule(),
        GoodwillAboveTwentyPercentAssetsRule(),
        PromoterPledgingRule(),
        // Valuation traps. plan.md §8.1's third item (negative FCF yield) is not in this phase's
        // brief and is therefore absent here rather than silently dropped: see the report.
        LowPeWithFallingReturnsRule(ratiosCard),
        PeRisingWhileEarningsFallRule(),
    )

    /** The ids [all] produces, for a test that asserts the registry's completeness. */
    val ruleIds: List<String> = all().map { it.id }

    /** Rules that can never fire with the sources we are permitted to read (spec §2.3). */
    val declaredCoverageGaps: List<FundamentalsPolicy.CoverageGap> =
        FundamentalsPolicy.DECLARED_COVERAGE_GAPS

    /**
     * The two flags plan.md §8.1 lists that this phase did not implement, by id, so a caller can
     * notice them rather than discover the absence by looking for a flag that never appears.
     *
     * `RF-VT-3 NEGATIVE_FCF_YIELD` (spec §5.3) and `DEBT_TO_EBITDA_ELEVATED` (spec §6.3) are
     * both specified in full and both need inputs this phase's brief does not cover. They are
     * named here so their absence is declared in code, not discovered in production.
     */
    val notImplementedInThisPhase: List<String> = listOf(
        "NEGATIVE_FCF_YIELD",
        "DEBT_TO_EBITDA_ELEVATED",
    )
}