package com.mia.app.domain.redflag.fundamentals

import com.mia.app.domain.model.FlagTab
import com.mia.app.domain.redflag.RedFlagContext
import com.mia.app.domain.redflag.RedFlagEngine
import com.mia.app.domain.redflag.RedFlagReport
import com.mia.app.domain.redflag.RedFlagRule
import com.mia.app.domain.redflag.RuleSkip

/**
 * Runs the ten fundamentals flags and reports both what tripped and what could not be checked.
 *
 * ### What this adds over [RedFlagEngine]
 * [RedFlagEngine] does three things, all of them right: run the rules, isolate a crash, and order
 * the result. What it cannot do — because it belongs to another phase — is distinguish *returned
 * nothing because the check was clean* from *returned nothing because the input was not there*.
 * plan.md §12 is blunt about which of those two is dangerous:
 *
 * > "A parser must return a health flag, not an empty list. Silent-empty is indistinguishable
 * > from 'no red flags' — the most dangerous possible failure."
 *
 * So this wrapper keeps [RedFlagEngine] as the thing that evaluates, and fills in the one field
 * it leaves empty. [FundamentalsRule.explainSkip] is consulted per rule after the run and each
 * non-null reason becomes a [RuleSkip] on the returned report. A rule that ran and did not trip
 * contributes nothing, which keeps spec §11 rule 8 intact: *"Suppressed ≠ clean… Absence is only
 * for a check that ran and did not trip."*
 *
 * ### Skip reasons and failures are the same kind of news
 * Both mean the user is looking at an incomplete picture, and the report keeps them as separate
 * lists because they need different fixes — a failure is a bug in a rule, a skip is a missing
 * source. Neither is ever rendered as a green tick.
 */
class FundamentalsRedFlagEngine(
    /** Extra rules from other packages, e.g. the technical flags, run in the same report. */
    extraRules: List<RedFlagRule> = emptyList(),
    /** The `ratios`-card series handed to the three rules that need them. */
    ratiosCard: RatiosCardSeries? = null,
) {
    /** The rules this engine owns. Exposed so a test can assert identity, not just behaviour. */
    val rules: List<FundamentalsRule> = FundamentalsRedFlagRules.all(ratiosCard)

    private val delegate = RedFlagEngine(rules + extraRules)

    /** Ids of every rule this engine runs, fundamentals first. */
    val ruleIds: List<String> get() = delegate.ruleIds

    /**
     * Evaluates every rule and returns the report with [RedFlagReport.skipped] filled in.
     *
     * A rule whose [FundamentalsRule.explainSkip] throws is recorded in `failures` rather than
     * allowed to lose the whole report: the reason text is the one piece of user-facing copy a
     * rule produces outside [FundamentalsRule.evaluate], and it deserves the same isolation.
     */
    fun evaluate(context: RedFlagContext): RedFlagReport {
        val report = delegate.evaluate(context)
        val skipped = mutableListOf<RuleSkip>()
        val failures = LinkedHashMap(report.failures)
        for (rule in rules) {
            try {
                val reason = rule.explainSkip(context)
                if (reason != null) skipped += RuleSkip(rule.id, reason)
            } catch (t: Throwable) {
                failures[rule.id] = t.message ?: t::class.simpleName ?: "unknown error"
            }
        }
        return report.copy(skipped = skipped, failures = failures)
    }

    /**
     * Why each rule would produce nothing, without running the flags.
     *
     * A caller building the "Not evaluated" list of a fundamentals section needs this without the
     * cost or the side effects of a full run.
     */
    fun skipsFor(context: RedFlagContext): List<RuleSkip> = rules.mapNotNull { rule ->
        runCatching { rule.explainSkip(context) }.getOrNull()?.let { RuleSkip(rule.id, it) }
    }

    /**
     * The tabs this engine can contribute to. Every fundamentals flag is
     * [FlagTab.FUNDAMENTALS]; the technical half of the engine returns the other value, which is
     * why the two can share one report.
     */
    val tabs: Set<FlagTab> get() = rules.map { it.tab }.toSet()

    companion object {
        /** An engine over exactly the ten fundamentals rules, with no `ratios` card supplied. */
        fun fundamentalsOnly(): FundamentalsRedFlagEngine = FundamentalsRedFlagEngine()

        /** An engine over the ten fundamentals rules plus [extraRules]. */
        fun with(extraRules: List<RedFlagRule>): FundamentalsRedFlagEngine =
            FundamentalsRedFlagEngine(extraRules = extraRules)
    }
}