package com.mia.app.domain.redflag

import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.FlagTab
import com.mia.app.domain.model.QuarterRecord
import com.mia.app.domain.model.RedFlag
import com.mia.app.domain.model.Ratios
import com.mia.app.domain.model.Sector
import com.mia.app.domain.model.Timeframe
import com.mia.app.domain.technical.IndicatorSnapshot
import java.time.LocalDate

/**
 * Everything a red flag may look at (plan.md §8).
 *
 * Every field is nullable-tolerant on purpose: fundamentals lag a quarter and technicals
 * need 200 bars, so a rule that cannot be evaluated must say so rather than assume.
 */
data class RedFlagContext(
    val symbol: String,
    val sector: Sector,
    val ratios: Ratios,
    val derived: com.mia.app.domain.model.CashFlowDerived,
    val quarters: List<QuarterRecord> = emptyList(),
    val candles: List<Candle> = emptyList(),
    val timeframe: Timeframe = Timeframe.D1,
    val indicators: IndicatorSnapshot? = null,
    val today: LocalDate = LocalDate.now(),
) {
    /**
     * Fundamentals are usable only when there is something behind them.
     *
     * [Ratios.asOf] is non-null by construction, so it cannot answer this: a `Ratios` built
     * from a failed parse still carries a date. What distinguishes "we have fundamentals" is
     * whether any actual figure came back.
     */
    val hasFundamentals: Boolean
        get() = ratios.roce != null || ratios.roe != null || ratios.pe != null ||
            ratios.marketCap != null || derived.revenueByYear.isNotEmpty() ||
            quarters.isNotEmpty()

    /**
     * Technicals are usable only when there are bars to compute from. A 150-bar series cannot
     * support a 200-day average, so a rule needing one must still abstain — this only answers
     * whether the tab has *any* technicals at all.
     */
    val hasTechnicals: Boolean get() = candles.isNotEmpty()
}

/**
 * One deterministic threshold check (plan.md §8: "All are deterministic threshold checks.
 * No AI, no inference").
 *
 * A rule returns **nothing** when it cannot be evaluated — insufficient data is not the
 * same as "no red flag", and the two must never be confused (plan.md §12).
 */
interface RedFlagRule {
    /** Stable id, used as the flag id and as the stable key in stored outcomes. */
    val id: String

    /** Which tab the flag surfaces on (plan.md §8.3: top of both tabs). */
    val tab: FlagTab

    fun evaluate(context: RedFlagContext): List<RedFlag>
}

/** Why a rule produced nothing — kept so the UI can be honest about coverage. */
data class RuleSkip(
    val ruleId: String,
    val reason: String,
)

/**
 * Runs the flag rules and groups the result for display.
 *
 * Ordering is deliberate and stable: severity, then tab order, then flag id — so the
 * banner at the top of a tab does not reshuffle between refreshes.
 */
data class RedFlagReport(
    val symbol: String,
    val flags: List<RedFlag>,
    val skipped: List<RuleSkip>,
    /** Rules that threw. A rule crash must not hide every other flag. */
    val failures: Map<String, String>,
) {
    val forFundamentals: List<RedFlag> get() = flags.filter { it.tab != FlagTab.TECHNICALS }
    val forTechnicals: List<RedFlag> get() = flags.filter { it.tab != FlagTab.FUNDAMENTALS }

    val isEmpty: Boolean get() = flags.isEmpty()

    /** Flags that belong on [tab]: a `BOTH` flag shows on each tab, its own tab once. */
    fun flagsFor(tab: FlagTab): List<RedFlag> = flags.filter { it.tab == tab || it.tab == FlagTab.BOTH }

    /**
     * Copy for a tab header.
     *
     * Never says "all clear" while checks could not run: [skipped] and [failures] mean the
     * absence of flags is partly unknown, and plan.md §12 treats a silent empty as the most
     * dangerous possible failure.
     */
    fun headerText(tab: FlagTab): String {
        val visible = flagsFor(tab)
        val incomplete = skipped.isNotEmpty() || failures.isNotEmpty()
        return when {
            failures.isNotEmpty() && visible.isEmpty() -> "Could not check red flags"
            visible.isEmpty() && incomplete -> "No red flags in the data checked (some checks skipped)"
            visible.isEmpty() -> "No red flags in the data checked"
            visible.size == 1 -> "1 flag"
            else -> "${visible.size} flags"
        }
    }

    companion object {
        val EMPTY = RedFlagReport("", emptyList(), emptyList(), emptyMap())
    }
}

/**
 * A rule that can explain why it abstained.
 *
 * Both flag packages implemented an ad-hoc skip channel (because [RedFlagReport.skipped] used
 * to be constructed empty). This interface is the one seam: implement it and the engine
 * records the reason itself, so the UI has a single place to read coverage from.
 */
interface SkippableRedFlagRule {
    /** Why this rule produced nothing, or `null` when it did evaluate cleanly. */
    fun skipReason(context: RedFlagContext): String?
}

/**
 * The red-flag engine (plan.md P6).
 *
 * Deliberately small: it owns ordering, isolation, skip reporting and nothing else. Rules
 * are pure functions of [RedFlagContext].
 */
class RedFlagEngine(
    private val rules: List<RedFlagRule>,
) {
    fun evaluate(context: RedFlagContext): RedFlagReport {
        val flags = mutableListOf<RedFlag>()
        val skipped = mutableListOf<RuleSkip>()
        val failures = linkedMapOf<String, String>()

        rules.forEach { rule ->
            try {
                val produced = rule.evaluate(context)
                flags += produced
                if (produced.isEmpty() && rule is SkippableRedFlagRule) {
                    rule.skipReason(context)?.let { skipped += RuleSkip(rule.id, it) }
                }
            } catch (t: Throwable) {
                // A crashing rule must not take the banner down with it.
                failures[rule.id] = t.message ?: t::class.simpleName ?: "unknown error"
            }
        }

        return RedFlagReport(
            symbol = context.symbol,
            flags = flags.sortedWith(
                compareBy({ it.severity.ordinal }, { it.tab.ordinal }, { it.id }),
            ),
            skipped = skipped,
            failures = failures,
        )
    }

    val ruleIds: List<String> get() = rules.map { it.id }
}

/** Severity ordering helper for sorting at the call site. */
val SEVERITY_ORDER: Map<com.mia.app.domain.model.FlagSeverity, Int> =
    mapOf(
        com.mia.app.domain.model.FlagSeverity.HIGH to 0,
        com.mia.app.domain.model.FlagSeverity.MEDIUM to 1,
        com.mia.app.domain.model.FlagSeverity.LOW to 2,
    )