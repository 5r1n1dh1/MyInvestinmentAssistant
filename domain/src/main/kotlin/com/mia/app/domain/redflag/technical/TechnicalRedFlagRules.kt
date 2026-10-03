package com.mia.app.domain.redflag.technical

import com.mia.app.domain.redflag.RedFlagContext
import com.mia.app.domain.redflag.RedFlagEngine
import com.mia.app.domain.redflag.RedFlagRule
import com.mia.app.domain.redflag.RuleSkip

/**
 * The technical red-flag family (plan.md §8.2; `redflags-spec.md` §7).
 *
 * **What is here and why there are six rules for five spec flags.** `redflags-spec.md` §7 specifies
 * exactly five technical flags (`RF-T-1 … RF-T-5`). `RF-T-2` covers two bands, and the brief allows
 * either one rule or two; this package ships **two** (`RSI_OVERBOUGHT`, `RSI_OVERSOLD`) because a
 * rule whose id changed with the reading would break the stable-key contract on
 * `RedFlagRule.id`. There is **no seventh flag**: nothing else in the spec's technical section
 * names one, and inventing a sixth *flag* to fill a slot would be exactly the kind of unspecified
 * threshold this product exists to avoid. The spec's remaining §7 material — severity bands, the
 * liquidity-floor suppression, the D1-only constraint, the "not enough history" abstention — is
 * implemented *inside* these rules and declared in [TechnicalRedFlagPolicy].
 *
 * **Everything is deterministic.** No AI, no inference (plan.md §8). Given the same context the same
 * flags come out, in the same order, which is what lets stored outcomes key off [TechnicalFlagIds].
 */
object TechnicalRedFlagRules {

    /**
     * The family, in id order. The list is immutable and freshly wrapped, so a caller cannot
     * reorder the engine's rule set by accident.
     */
    fun all(): List<TechnicalRedFlagRule> = listOf(
        DeathCrossRule,
        RsiBandRule(RsiBand.OVERBOUGHT),
        RsiBandRule(RsiBand.OVERSOLD),
        DistributionDayRule,
        FiftyTwoWeekLowObvRule,
        DeliveryCollapseRule,
    )

    /**
     * A [RedFlagEngine] over the technical rules, optionally with more appended.
     *
     * The parameter exists so the app can wire one engine over the technical *and* fundamentals
     * families: `TechnicalRedFlagRules.engine(FundamentalRedFlagRules.all())`. The default —
     * technicals only — is what the technicals tab needs and what the tests in this package assert.
     */
    fun engine(additional: List<RedFlagRule> = emptyList()): RedFlagEngine =
        RedFlagEngine(all() + additional)

    /**
     * Which rules could not answer, and why.
     *
     * **This is the half of plan.md §12 the empty flag list cannot express.** A rule that abstained
     * is not clean, and `RedFlagRule.evaluate` — a locked seam returning `List<RedFlag>` — has no
     * way to say so. The screen calls this alongside `RedFlagEngine.evaluate` and renders each
     * entry as *"Not evaluated — <reason>"* (`redflags-spec.md` §11 rule 8).
     *
     * A rule that ran and found nothing contributes no entry: absence is only for a check that ran.
     */
    fun skips(context: RedFlagContext): List<RuleSkip> {
        val resolved = context.withComputedIndicators()
        return all().mapNotNull { rule ->
            rule.skipReason(resolved)?.let { RuleSkip(rule.id, it) }
        }
    }

    /** Every threshold string the family can render, for the compliance walk. */
    fun allThresholdStrings(): List<String> = listOf(
        DeathCrossRule.THRESHOLD,
        RsiBandRule.THRESHOLD,
        DistributionDayRule.THRESHOLD,
        FiftyTwoWeekLowObvRule.THRESHOLD,
        DeliveryCollapseRule.THRESHOLD,
    )
}