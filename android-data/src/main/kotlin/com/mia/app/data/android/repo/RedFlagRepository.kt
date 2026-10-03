package com.mia.app.data.android.repo

import com.mia.app.domain.model.FlagTab
import com.mia.app.domain.model.RedFlag
import com.mia.app.domain.model.Timeframe
import com.mia.app.domain.redflag.RedFlagReport
import com.mia.app.domain.redflag.RuleSkip

/**
 * One symbol's red flags, with the coverage statement plan.md §8.3 requires.
 *
 * ### The distinction this type is built around
 * "No red flags" and "no red flags could be checked" are different facts, and the second is the
 * dangerous one. [skipped] and [failureReasons] carry it explicitly: a rule that abstained — a
 * bank with no `Debt/Equity`, a series with fewer than 200 bars — is listed as **Not evaluated**,
 * never rendered as a green tick.
 *
 * [complete] is false whenever a rule could not answer or a rule threw, so the UI cannot
 * accidentally print the reassuring copy.
 */
data class RedFlagView(
    val symbol: String,
    val timeframe: Timeframe,
    val flags: List<RedFlag>,
    /** Rules that abstained, each with a reason. Rendered as "Not evaluated — …". */
    val skipped: List<RuleSkip>,
    /** Rules that threw, keyed by rule id. A crash must not read as "clean". */
    val failureReasons: Map<String, String>,
    /** `true` only when every rule ran and none abstained. */
    val complete: Boolean,
    /** True when at least one flag has evidence missing — impossible by construction, asserted. */
    val everyFlagCarriesEvidence: Boolean,
) {
    fun forTab(tab: FlagTab): List<RedFlag> = flags.filter { flag ->
        flag.tab == FlagTab.BOTH || flag.tab == tab
    }

    /**
     * The banner header.
     *
     * Three states, never two: a clean pass, a pass that ran and tripped something, and a pass
     * that could not cover the data. The copy is composed here rather than delegated to
     * `RedFlagReport.headerText`, because that helper cannot see [skipped] — and "3 flag(s)" over
     * a check that silently skipped half the rules is the misleading version.
     */
    fun headerText(tab: FlagTab): String {
        val base = when {
            flags.isEmpty() && !complete -> "Could not check red flags — the data for some checks is missing"
            flags.isEmpty() -> "No red flags in the data checked"
            else -> "${forTab(tab).size} flag(s)"
        }
        if (complete) return base
        val reason = when {
            failureReasons.isNotEmpty() -> "${failureReasons.size} check(s) failed to run"
            skipped.isNotEmpty() -> "${skipped.size} check(s) could not be evaluated"
            else -> return base
        }
        return "$base · $reason"
    }

    init {
        require(flags.all { it.evidence.isNotEmpty() }) {
            "plan.md §8.3: every rendered red flag must carry the numbers behind it"
        }
    }
}

/**
 * The only way `:app` reaches red flags (plan.md §8).
 *
 * ### Determinism, restated
 * "All are deterministic threshold checks. No AI, no inference" (plan.md §8). This repository
 * therefore only *gathers* the inputs and calls the domain engine; it computes no threshold
 * itself. Two runs over the same cache produce the same flags in the same order, which is what
 * lets a stored detection key off a stable rule id.
 */
interface RedFlagRepository {

    /**
     * Evaluates the fundamentals and technical families for `symbol`.
     *
     * Fundamentals need the cached screener.in page; technicals need the stored daily series.
     * Either being absent does not fail the call — it produces [RedFlagView.skipped] entries, so
     * a user who has not opened the fundamentals tab yet sees the technical flags they *can*
     * have plus an honest line about the rest.
     */
    suspend fun flags(symbol: String, timeframe: Timeframe = Timeframe.D1): DataResult<RedFlagView>

    /** Only the fundamentals family, for the fundamentals tab's banner. */
    suspend fun fundamentalsFlags(symbol: String): DataResult<RedFlagView>

    /** Only the technical family, for the technicals tab's banner. */
    suspend fun technicalFlags(symbol: String, timeframe: Timeframe = Timeframe.D1): DataResult<RedFlagView>

    /** Rule ids this build evaluates. The accuracy/evidence screens list them for transparency. */
    suspend fun ruleIds(): List<String>
}

/** Local alias so `:app` never imports `com.mia.app.domain.redflag` for a plain container. */
typealias EngineReport = RedFlagReport