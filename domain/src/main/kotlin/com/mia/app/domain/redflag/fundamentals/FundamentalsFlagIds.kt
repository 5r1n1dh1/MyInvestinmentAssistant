package com.mia.app.domain.redflag.fundamentals

/**
 * The ten flag ids of the fundamentals tab, as constants.
 *
 * `docs/spec/redflags-spec.md` §8 is the source of every id. They are constants rather than
 * inline strings so that [FundamentalsRedFlagRules.ruleIds] can be asserted against one list,
 * so the `Settings → Data coverage` screen can name a suppressed check by id, and so no rule
 * can quietly ship an id that nothing else recognises.
 *
 * Id names are the *condition*, not the verdict: `CFO_NEGATIVE_WHILE_PAT_POSITIVE` says what
 * was observed. Nothing here says a company is unsound, because plan.md §8.3 requires the flag
 * to name what was measured and never what it implies.
 */
object FundamentalsFlagIds {

    /** `CFO < 0` while `PAT > 0`, for two or more consecutive fiscal years. */
    const val CFO_NEGATIVE_WHILE_PAT_POSITIVE = "CFO_NEGATIVE_WHILE_PAT_POSITIVE"

    /**
     * Receivables growing faster than revenue.
     *
     * The ₹-based form needs a receivables line our source does not publish, so the rule
     * evaluates the **same idea** in days sales outstanding, which the `ratios` card does
     * publish. The id keeps plan.md §8.1's wording because that is what the check is about; the
     * evidence and the threshold string both say which form was used, and the substitution is
     * documented at the rule rather than hidden in the id.
     */
    const val RECEIVABLES_GROWING_FASTER_THAN_REVENUE = "RECEIVABLES_GROWING_FASTER_THAN_REVENUE"

    /** Inventory days rising by at least ten days year on year. */
    const val INVENTORY_DAYS_RISING = "INVENTORY_DAYS_RISING"

    /** Other income above thirty percent of profit after tax. */
    const val OTHER_INCOME_AS_PCT_OF_PAT = "OTHER_INCOME_AS_PCT_OF_PAT"

    /** Debt/equity spiking year on year, or above the sector's band. */
    const val DEBT_TO_EQUITY_SPIKE = "DEBT_TO_EQUITY_SPIKE"

    /** `EBIT ÷ interest` below two. */
    const val INTEREST_COVERAGE_BELOW_2 = "INTEREST_COVERAGE_BELOW_2"

    /** Goodwill above twenty percent of total assets. **Unsourceable today** — see the KDoc. */
    const val GOODWILL_ABOVE_20PCT_ASSETS = "GOODWILL_ABOVE_20PCT_ASSETS"

    /** Promoter shares pledged. **Unsourceable today** — see the KDoc. */
    const val PROMOTER_PLEDGING = "PROMOTER_PLEDGING"

    /** P/E below the sector band while ROCE or ROE declines. */
    const val LOW_PE_WITH_FALLING_RETURNS = "LOW_PE_WITH_FALLING_RETURNS"

    /** Trailing EPS falling while the multiple on it rises. */
    const val PE_RISING_WHILE_EARNINGS_FALL = "PE_RISING_WHILE_EARNINGS_FALL"
}