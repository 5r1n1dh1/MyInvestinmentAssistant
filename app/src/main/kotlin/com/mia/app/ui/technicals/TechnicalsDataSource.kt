package com.mia.app.ui.technicals

import com.mia.app.domain.model.BreakoutTrigger
import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.HitRate
import com.mia.app.domain.model.Match
import com.mia.app.domain.model.ProjectedPath
import com.mia.app.domain.model.RedFlag
import com.mia.app.domain.model.Timeframe
import com.mia.app.domain.redflag.RuleSkip
import com.mia.app.domain.technical.IndicatorSnapshot

/**
 * The Technicals tab's entire view of the outside world.
 *
 * ### Why this interface exists in `:app` and not in `:android-data`
 * plan.md §4.3 makes `:app` depend on `:domain` and not on the storage layer, so the tab cannot name
 * a Room entity, a DAO or a repository class. But it also must not invent its own data model, or the
 * same `Match` would be built twice with two field sets. This interface is the seam: it speaks only
 * in `:domain` types, so `TechnicalsViewModel` is compilable and reviewable on its own, and the
 * adapter that satisfies it is a single, small, separately-reviewable class.
 *
 * The concrete binding is **not** written here because `:android-data`'s repositories are being
 * written in parallel and this module cannot be compiled against them; see `TechnicalsModule.kt`
 * for the placeholder that must be deleted when the real adapter lands.
 *
 * ### Contract
 * * Every function is `suspend` and must already be off the main thread — the caller adds
 *   [TechnicalsBackgroundDispatcher] around the whole load, and a repository that switches again
 *   internally is merely redundant, not wrong.
 * * Every function returns `:domain` types only.
 * * `null` / empty means **"there is nothing", not "we failed"** — the same distinction
 *   `DataResult` draws in `:android-data`. A function that *fails* throws; see [TechnicalsLoad]
 *   for what the ViewModel does with the exception.
 * * Implementations must return bars sorted **ascending by date**, matching the `Candle` contract in
 *   `CandleResampler`, because the chart indexes them and the detectors address pivots by index.
 */
interface TechnicalsDataSource {

    /**
     * Stored EOD bars for one symbol and timeframe, ascending by date.
     *
     * Empty when nothing has been fetched yet. The tab then shows "not enough history" with the
     * counts, which is the honest answer — it must not invent a short series.
     */
    suspend fun candles(symbol: String, timeframe: Timeframe): List<Candle>

    /**
     * Red flags for this symbol, already ordered by `RedFlagReport`'s severity → tab → id rule.
     *
     * Only flags whose [com.mia.app.domain.model.FlagTab] is `TECHNICALS` or `BOTH` belong here; a
     * fundamentals-only flag belongs to the other tab.
     *
     * @param skipped rules that could not be evaluated, with the reason. The tab surfaces these
     *   rather than implying a clean bill of health (plan.md §12).
     */
    suspend fun redFlags(symbol: String, timeframe: Timeframe): TechnicalsRedFlags

    /**
     * Every detected match, **unfiltered** except for the hard bar gate.
     *
     * The ViewModel applies `MatchScorePolicy`'s render threshold and the `suppress` verdict. That
     * split is deliberate: the *engine* knows a match's regime alignment and direction, and it is
     * the engine's job to decide what may be rendered at all (plan.md §6.4); the tab's job is only
     * to sort and to lay them out.
     */
    suspend fun matches(symbol: String, timeframe: Timeframe): List<Match>

    /**
     * The 1–5 bar dashed path for [match] (plan.md §6.6 item 2).
     *
     * `null` when [match] has no measured move and therefore no direction to project, or when the
     * match's score is below `MatchScorePolicy.PROJECT_THRESHOLD` — a projection is only ever drawn
     * for a match the engine would have projected.
     */
    suspend fun projectedPath(symbol: String, timeframe: Timeframe, match: Match): ProjectedPath?

    /**
     * The breakout condition for [match] (plan.md §6.6 item 3).
     *
     * `null` when there is no trigger to describe. Never folded into the projected path: decision D2
     * requires the two to stay separate outputs with separate schedules.
     */
    suspend fun breakoutTrigger(symbol: String, timeframe: Timeframe, match: Match): BreakoutTrigger?

    /**
     * The app's own track record (plan.md §6.1, decision D6).
     *
     * @param patternName the pattern to report on, or `null` for the stock's all-pattern figure.
     *   Per **stock and pattern** is the number that answers the reader's question, so callers
     *   should pass it whenever a match is in hand and request the per-stock figure separately.
     *
     * Returns a [HitRate] even when it is not reportable: `HitRate.isReportable` and
     * `HitRate.label()` encode the withholding rule, and the tab shows the sample count. Returning
     * `null` here would make "withheld" indistinguishable from "never tracked".
     */
    suspend fun trackRecord(symbol: String, patternName: String?): HitRate?
}

/**
 * The red flags plus the rules that could not run.
 *
 * A tiny owned type rather than `:android-data`'s `DataResult`, because a tab needs a *successful
 * read with a caveat*, and `RedFlagReport` already carries `skipped` and `failures` — this is the
 * slice of it the Technicals tab renders, named so the screen's contract is readable without
 * opening the storage layer.
 */
data class TechnicalsRedFlags(
    /** Flags for this tab, in `RedFlagReport`'s stable order. */
    val flags: List<RedFlag>,
    /** Rules that could not be evaluated, with the reason. */
    val skipped: List<RuleSkip> = emptyList(),
    /**
     * Rules that threw, mapped to the message. A rule crash must not hide every other flag
     * (`RedFlagEngine` isolates them); the tab shows this so coverage is not overstated.
     */
    val failures: Map<String, String> = emptyMap(),
) {
    /** True when the banner must not imply "all clear". */
    val coverageIncomplete: Boolean get() = skipped.isNotEmpty() || failures.isNotEmpty()

    companion object {
        /** Nothing to report, and nothing was skipped. */
        val EMPTY = TechnicalsRedFlags(flags = emptyList())
    }
}

/**
 * The exception every implementation of [TechnicalsDataSource] throws on a failed read.
 *
 * ### Why a declared exception type at all
 * `:android-data`'s `DataResult` already models "not published yet" versus "source unreachable"
 * with a machine-readable reason. This tab must not import `:data`, so it re-declares the *two*
 * distinctions it actually renders:
 *
 * * [isTransient] — worth offering a retry button for (offline, 429, 5xx).
 * * [isNotPublished] — **not** an error at all: plan.md §3.1's bhavcopy for today is simply not out
 *   yet at 16:00 IST, and the correct UI is the last successful session's data with an "as of"
 *   marker, not a red banner.
 *
 * An implementation that throws something else is treated as unexpected and still surfaced, with the
 * message shown verbatim.
 */
class TechnicalsUnavailableException(
    /** The sentence shown to the user. Must already be plain language. */
    val reason: String,
    val isTransient: Boolean = true,
    val isNotPublished: Boolean = false,
) : RuntimeException(reason)

/**
 * The whole tab's load, assembled off the main thread.
 *
 * Grouped into one object so `TechnicalsViewModel` has a single value to publish and there is no
 * intermediate "candles arrived but flags have not" state to flicker through.
 */
internal data class TechnicalsLoad(
    val candles: List<Candle>,
    /** Computed once, here, so the fifteen-indicator pass runs exactly once per load. */
    val snapshot: IndicatorSnapshot?,
    val redFlags: TechnicalsRedFlags,
    val matches: List<Match>,
    val projectedPath: ProjectedPath?,
    val breakoutTrigger: BreakoutTrigger?,
    /** Per stock **and** per pattern (decision D6). */
    val trackRecord: HitRate?,
    /** The stock's all-pattern figure. */
    val trackRecordForSymbol: HitRate?,
)
