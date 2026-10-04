package com.mia.app.ui.patterns

import com.mia.app.domain.model.BreakoutTrigger
import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.HitRate
import com.mia.app.domain.model.Match
import com.mia.app.domain.model.MeasuredMove
import com.mia.app.domain.model.ProjectedPath
import com.mia.app.domain.model.RedFlag
import com.mia.app.domain.model.Timeframe
import com.mia.app.domain.pattern.PatternEvaluation
import com.mia.app.domain.pattern.PatternScanResult
import com.mia.app.domain.redflag.RuleSkip

/**
 * The Patterns tab's entire view of the outside world (plan.md §6.5, §6.6, decision D2 / D6).
 *
 * ### Why this interface exists in `:app` and not in `:android-data`
 * plan.md §4.3 makes `:app` depend on `:domain` and not on the storage layer, so this tab cannot name
 * a Room entity, a DAO or a repository class. It speaks only in `:domain` types, so
 * [PatternsViewModel] is compilable and reviewable on its own and the adapter that satisfies this is a
 * single, small, separately-reviewable class.
 *
 * ### Contract
 * * Every function is `suspend` and must already be off the main thread.
 * * Every function returns `:domain` types only.
 * * `null` / empty means **"there is nothing"**, not "we failed". A function that fails throws
 *   [PatternsUnavailableException].
 * * Bars come back sorted **ascending by date**, matching the `Candle` contract, because the chart
 *   indexes them and `Match.pivots` address bars by absolute index.
 *
 * ### The one thing this interface must not let the screen do
 * It returns the three projection outputs as **three separate values** ([PatternsProjection]), never
 * one merged structure. decision D2 requires them to stay apart, and the cheapest way to guarantee
 * that is for the seam itself to have no combined type to pass.
 */
interface PatternsDataSource {

    /**
     * Checks the symbol's bars are present and current enough to scan, **fetching only when the
     * cache is stale**.
     *
     * Same contract as [com.mia.app.ui.technicals.TechnicalsDataSource.ensureFresh], and for the
     * same reason: the tab reads the `candles` table, and nothing wrote to it until the nightly
     * 19:00 IST worker, so a reader who opened this tab first saw a permanent "not enough history".
     *
     * Never throws for a failed fetch; the following [candles] call reports the real reason.
     */
    suspend fun ensureFresh(symbol: String, timeframe: Timeframe)

    /** Stored EOD bars for one symbol and timeframe, ascending by date. */
    suspend fun candles(symbol: String, timeframe: Timeframe): List<Candle>

    /**
     * The scan: every renderable match, every suppressed candidate with its reason, the gate verdict,
     * the shared pivot list and the transparency notes.
     *
     * Returned as the domain's own [PatternScanResult] rather than a re-shaped copy, because the
     * type already carries the three things the screen must render honestly — `suppressed` with its
     * reasons, `insufficientHistory` with its counts, and `notes` — and re-declaring them would
     * create a second place for the gate to drift.
     *
     * A scan below plan.md §6.4's bar gate is **not** an error: it returns a result with
     * `insufficientHistory = true` and an empty match list, which the screen renders as "not enough
     * history".
     */
    suspend fun scan(symbol: String, timeframe: Timeframe): PatternScanResult

    /**
     * The Pattern Explorer's per-pattern view: one outcome per catalogue pattern.
     *
     * Added because `scan` alone cannot answer "why did nothing match?". It returns matches and
     * suppressed candidates but the screen had no way to ask about the 28 patterns the app names
     * and does not evaluate — so a reader saw "no match" and could not tell whether the app looked
     * and found nothing, or never looked.
     *
     * [windowBars] selects how much of the stored series to analyse. `null` means the whole series,
     * which is the canonical scan and the only one whose results may be recorded — the Explorer
     * passes a bar count, and the implementation marks that scan non-canonical so a window tap can
     * never overwrite a projection in the accuracy table.
     */
    suspend fun evaluate(
        symbol: String,
        timeframe: Timeframe,
        windowBars: Int?,
    ): PatternEvaluation

    /**
     * The three projection outputs for one match (plan.md §6.6), kept as three fields.
     *
     * Each is independently `null`:
     * * [PatternsProjection.measuredMove] — `null` for a candlestick formation, which has no height
     *   to project (spec DD-1).
     * * [PatternsProjection.path] — `null` when the score is below
     *   `MatchScorePolicy.PROJECT_THRESHOLD` or the regime gate was unmet, because a projection is
     *   only ever drawn for a match the engine would have projected.
     * * [PatternsProjection.trigger] — `null` when there is no condition to describe.
     */
    suspend fun projections(symbol: String, timeframe: Timeframe, match: Match): PatternsProjection

    /**
     * The app's own track record (plan.md §6.1, decision D6).
     *
     * @param patternName the pattern to report on, or `null` for this stock's all-pattern figure.
     *   Per **stock and pattern** is the number that answers the reader's question, so a caller with
     *   a match in hand should always ask for that one as well.
     *
     * Returns a [HitRate] even when it is not reportable: `HitRate.isReportable` and
     * `HitRate.label()` encode the withholding rule, and the tab shows the sample count. `null` is
     * reserved for "this stock has never been tracked", which is a different fact from "tracked but
     * withheld".
     */
    suspend fun trackRecord(symbol: String, patternName: String?): HitRate?

    /**
     * The manual "analyse this stock now" action, behind a button the reader presses.
     *
     * ### Why this is not just [ensureFresh] again
     * [ensureFresh] asks for the **newest** session and only when the cache is stale. That is
     * correct for keeping a tab current and useless for a stock the app has never analysed: such a
     * symbol has zero rows, so "is the cache stale?" is answered yes every time while the fetch it
     * performs — one whole-market bhavcopy — still only ever adds the *latest* bar. The tab then
     * reports 1 bar against a 60-bar gate, and no amount of waiting on that path fixes it. Deep
     * history is a different request, and this is it.
     *
     * The order is deliberate: **deepen first, then record**. A projection is only trackable if the
     * bars after it exist, and an outcome opened before the history is in place would be judged
     * against an incomplete window. So history lands first, then the projections for what is now
     * visible.
     *
     * Never throws: a failure is reported in [ForceScanOutcome], because the caller's job is to
     * show the reason, not to unwind.
     */
    suspend fun forceScan(symbol: String, timeframe: Timeframe): ForceScanOutcome

    /**
     * Red flags for this symbol, already ordered by `RedFlagReport`'s severity → tab → id rule.
     *
     * Flags whose [com.mia.app.domain.model.FlagTab] is `TECHNICALS` or `BOTH` belong here.
     */
    suspend fun redFlags(symbol: String, timeframe: Timeframe): PatternsRedFlags
}

/**
 * What [PatternsDataSource.forceScan] did, in the reader's terms.
 *
 * ### Why this is a value and not a boolean
 * "Failed" is not actionable, and the three ways this can come up short are genuinely different
 * problems with different fixes:
 *
 * - no network → try again later, nothing is wrong
 * - Yahoo refused → the NSE archive walk may still work, which the reader can trigger
 * - history arrived but is still under the gate → the symbol genuinely does not have enough
 *   trading history, and widening the window cannot help
 *
 * Collapsing those to `false` is what made the original "not enough history" state permanent: the
 * screen said nothing, so nothing told the reader what to do.
 */
data class ForceScanOutcome(
    /** Bars stored for the symbol after the attempt, across both timeframes. */
    val barsStored: Int,
    /** Bars this attempt added. `0` when nothing new was available. */
    val barsAdded: Int,
    /** Projections opened for the track record by this attempt. */
    val projectionsOpened: Int,
    /** Gate for [com.mia.app.domain.model.Timeframe], for the "you have N, you need M" sentence. */
    val minBarsRequired: Int,
    /**
     * Why the attempt came up short, in a sentence the reader can act on. `null` when the symbol
     * now clears the gate.
     */
    val blocker: String? = null,
) {
    /** `true` when the symbol can now be scanned. */
    val isScannable: Boolean get() = blocker == null
}

/**
 * One match's three projection outputs, kept apart (plan.md §6.6, decision D2).
 *
 * | Output | Field | What it is |
 * |---|---|---|
 * | measured move | [measuredMove] | the formation's height projected from the breakout — geometry |
 * | directional path | [path] | a 1–5 bar dashed path, directional only, no level attached |
 * | breakout alert | [trigger] | a condition on a close — an event, not a forecast |
 *
 * There is deliberately no convenience property that combines them. The single most likely bug in
 * this feature area is a screen that renders one panel containing both the geometry level and the
 * projected path, and a type with three independent nullable fields does not offer that bug to be
 * written.
 */
data class PatternsProjection(
    val measuredMove: MeasuredMove? = null,
    val path: ProjectedPath? = null,
    val trigger: BreakoutTrigger? = null,
) {
    /** True when there is nothing at all to draw for this match. */
    val isEmpty: Boolean get() = measuredMove == null && path == null && trigger == null

    companion object {
        /** No outputs at all — e.g. a candlestick formation with no alert condition. */
        val NONE = PatternsProjection()
    }
}

/**
 * The red flags plus the rules that could not run.
 *
 * An owned type rather than `:android-data`'s `DataResult`, because a tab needs a *successful read
 * with a caveat*, and `RedFlagReport` already carries `skipped` and `failures` — this is the slice
 * of it this tab renders, named so the screen's contract is readable without opening the storage
 * layer. Deliberately mirrors `TechnicalsRedFlags` so the two tabs cannot diverge.
 */
data class PatternsRedFlags(
    /** Flags for this tab, in `RedFlagReport`'s stable order. */
    val flags: List<RedFlag> = emptyList(),
    /** Rules that could not be evaluated, with the reason. */
    val skipped: List<RuleSkip> = emptyList(),
    /**
     * Rules that threw, mapped to their message. A rule crash must not hide every other flag, so
     * the tab shows this rather than overstating coverage.
     */
    val failures: Map<String, String> = emptyMap(),
) {
    /** True when the banner must not imply "all clear". */
    val coverageIncomplete: Boolean get() = skipped.isNotEmpty() || failures.isNotEmpty()

    companion object {
        /** Nothing to report, and nothing was skipped. */
        val EMPTY = PatternsRedFlags()
    }
}

/**
 * The exception every implementation throws on a failed read.
 *
 * @param reason plain language, shown verbatim. Never an exception class name.
 * @param isNotPublished today's bhavcopy is simply not out yet at 16:00 IST (plan.md §3.1). **Not an
 *   error**: the tab keeps showing the last successful session with its as-of date and adds a note,
 *   because a red banner for a file that has not been published yet is the wrong answer.
 */
class PatternsUnavailableException(
    val reason: String,
    val isTransient: Boolean = true,
    val isNotPublished: Boolean = false,
) : RuntimeException(reason)

/**
 * One suppressed candidate, ready to render.
 *
 * @param score the score it would have carried. `0` when no score was ever computed, in which case
 *   the row says so rather than implying a measured zero.
 * @param reason the engine's own sentence, rendered verbatim. The domain already words these in UI
 *   language and never in advice or probability language, so this tab does not rewrite them.
 */
data class SuppressedRow(
    val name: String,
    val score: Int?,
    val reason: String,
)