package com.mia.app.domain.pattern

import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.MatchScorePolicy
import com.mia.app.domain.model.PatternDetector
import com.mia.app.domain.model.Pivot
import com.mia.app.domain.model.Timeframe


/**
 * Turns one scan into one row per catalogue pattern — the Explorer's data source, in `:domain`.
 *
 * ### Why this sits beside the engine rather than in `:app`
 * The Explorer's entire value is that it answers *"why did nothing match?"*, and that answer is a
 * function of a scan result plus the catalogue. Putting the mapping here means it is a plain JVM
 * test over the real fixture, rather than untestable `:app` code — and `:app` cannot be compiled on
 * a machine with no Android SDK, so a mapping placed there would go unverified.
 *
 * ### The one thing this must never do
 * **It must not change a score.** It re-labels what the engine already decided; it never re-scores,
 * re-gates or re-sorts. So every number it reports is the engine's, and the Explorer's per-pattern
 * view can be compared against the Patterns tab's list without any chance of disagreement.
 */
object PatternExplorer {

    /**
     * One pattern's outcome for one scan.
     *
     * @param gateReason why the scan emitted nothing at all, or `null` when it ran normally.
     */
    private fun outcomesFor(
        result: PatternScanResult,
        timeframe: Timeframe,
        windowBars: Int,
        blockers: Map<String, EvaluationOutcome.Blocker>,
    ): List<EvaluationOutcome> {
        val out = ArrayList<EvaluationOutcome>(PatternCatalog.all.size)

        for (match in result.matches) {
            out += EvaluationOutcome.Matched(match)
        }

        for (suppressed in result.suppressed) {
            // A candidate with no breakdown never reached the scorer — it was rejected by a
            // geometry pre-check or by the bar gate. Reporting it as a near-miss would be a
            // fabricated score.
            if (suppressed.breakdown == null) {
                val blocker = blockers[suppressed.name] ?: blockerFor(reason = suppressed.reason)
                out += EvaluationOutcome.CannotEvaluate(
                    name = suppressed.name,
                    reason = suppressed.reason,
                    blocker = blocker,
                )
                continue
            }
            val breakdown = suppressed.breakdown
            val weakest = weakestOf(breakdown)
            out += EvaluationOutcome.NearMiss(
                name = suppressed.name,
                score = suppressed.score,
                breakdown = breakdown,
                weakestFactor = weakest,
                // Prefer the suppressed row's own measures; fall back to all of them so the
                // weakest-factor query never returns nothing for a scored candidate.
                measures = suppressed.measures.filter { it.factor == weakest }
                    .ifEmpty { suppressed.measures },
                reason = suppressed.reason,
            )
        }

        // Catalogue rows the scan said nothing about. Filled in `complete()` so a pattern that
        // genuinely never fired is not silently absent from the list.
        val seen = out.map { it.name }.toSet()
        for (spec in PatternCatalog.all) {
            if (spec.name in seen) continue
            out += if (spec.isEvaluated) {
                EvaluationOutcome.CannotEvaluate(
                    name = spec.name,
                    reason = blockerSentence(spec.name, timeframe, windowBars),
                    blocker = blockerFor(spec, timeframe, windowBars, result),
                )
            } else {
                EvaluationOutcome.NotEvaluated(spec.name)
            }
        }
        return out
    }

    /**
     * Builds the full evaluation for one scan.
     *
     * @param pivotsUsed the pivot list the scan actually used, so `usedFallbackPivots` can be
     *   reported. Taken from the result rather than recomputed.
     */
    fun evaluate(
        result: PatternScanResult,
        symbol: String,
        timeframe: Timeframe,
        windowBars: Int,
    ): PatternEvaluation = PatternEvaluation(
        symbol = symbol,
        timeframe = timeframe,
        windowBars = windowBars,
        usedFallbackPivots = result.pivots.usedFallbackDepth,
        outcomes = outcomesFor(result, timeframe, windowBars, blockers = emptyMap()),
    )

    /**
     * Runs one window scan and evaluates it.
     *
     * Convenience for the Explorer path, and the only place a window scan is issued, so the
     * non-canonical flag cannot be forgotten at a call site. Every caller here is exploring; the
     * nightly canonical scan goes through `PatternEngine.run` directly with the default.
     *
     * ### Why [candles] is the whole series and [windowBars] selects a slice of it
     * The engine needs bars *before* the window to compute indicators honestly, so this takes the
     * full stored series and slices internally. A caller that pre-sliced with `takeLast` would
     * leave every indicator at the window's left edge computed on a partial series, which is the
     * warm-up bug this signature exists to prevent — see [WARM_UP_BARS].
     *
     * @param candles the **whole** stored series, ascending by date. Not a pre-sliced window.
     * @param windowBars how many of the most recent bars to report on. Clamped to what is stored.
     */
    fun scanWindow(
        candles: List<Candle>,
        timeframe: Timeframe,
        detectors: List<PatternDetector>,
        symbol: String = "",
        windowBars: Int = candles.size,
        benchmarkCandles: List<Candle>? = null,
    ): PatternEvaluation {
        val reported = windowBars.coerceIn(0, candles.size)
        // Fed to the engine: the reported window plus enough earlier bars for the longest
        // indicator to have a real reading at the window's first bar.
        val fed = candles.takeLast(reported + WARM_UP_BARS)

        val result = PatternEngine.run(
            candles = fed,
            timeframe = timeframe,
            detectors = detectors,
            options = PatternScanOptions(
                symbol = symbol,
                benchmarkCandles = benchmarkCandles?.let { bench ->
                    // Trimmed to the same end so a benchmark cannot read further forward than the
                    // prices it is being compared against.
                    bench.takeLast(fed.size)
                },
                // Explicit and non-default: a window scan is exploratory by definition, and
                // PatternIds is name-based, so recording one would collide with the canonical
                // scan's ids in the accuracy table.
                isCanonicalScan = false,
            ),
        )

        // `fed` is the window plus warm-up, so a candidate belongs to the reported window only if
        // its pivots sit at or after the warm-up boundary. Reported in the engine's own
        // bar-index space, which is the space of `fed`.
        val windowStart = fed.size - reported
        return evaluate(
            result = result.filterToWindow(windowStart),
            symbol = symbol,
            timeframe = timeframe,
            windowBars = reported,
        )
    }

    /**
     * Drops candidates whose pivots fall entirely before [windowStart].
     *
     * ### Why filtering is not the same as recomputing
     * The engine was given the window *and* its warm-up, so a formation straddling the boundary is
     * detected with full context on both sides — that is the point of the warm-up. What this
     * removes are candidates that belong to the warm-up itself: without it, widening a window
     * could surface a match whose entire shape sits outside the bars the reader selected, and the
     * chart would then draw a shape anchored to pivots it is not showing.
     *
     * A candidate is kept when **any** of its pivots is inside the window, not only when all are.
     * A double top whose left top precedes the window is genuinely a formation of this window —
     * the reader can see both tops in the visible bars — so requiring all pivots would discard the
     * most useful matches at narrow widths.
     *
     * Candidates with no pivots are kept: they are the ones rejected before a shape existed, and
     * they carry no geometry that could disagree with the window. Suppressing them would invent a
     * blocker the engine never reported.
     */
    private fun PatternScanResult.filterToWindow(windowStart: Int): PatternScanResult {
        if (windowStart <= 0) return this
        fun inWindow(pivots: List<Pivot>): Boolean =
            pivots.isEmpty() || pivots.any { it.barIndex >= windowStart }

        return copy(
            matches = matches.filter { inWindow(it.pivots) },
            suppressed = suppressed.filter { inWindow(it.pivots) },
        )
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Bars fed to the engine *before* the reported window, so the window's first bar has an
     * indicator reading that exists.
     *
     * ### Why this number and not a taste
     * Every detector reads indicators, and the longest look-back any of them touches is the
     * candlestick context window (`CandlestickPolicy.contextLookback` = 20 daily / 13 weekly)
     * measured from the *current* bar, with ADX14 needing roughly twice its period for `+DI`/`−DI`
     * to settle. Twenty bars is below all three, so at the left edge of a window computed from
     * scratch those readings would be `null` — and a `null` is a silently unscored factor, which
     * is how a window ends up scoring lower than the same stock in a wider window for reasons that
     * have nothing to do with the pattern.
     *
     * Sixty is chosen as a round number that clears the longest look-back with room to spare, and
     * because it is small enough that a 60-bar window still analyses 120 bars rather than
     * pretending the reader's selection was ignored. It is deliberately *not* the full history:
     * the pivot pass is meant to see the window's own swings, and feeding all 372 bars would put
     * the zigzag's shallow-and-fallback threshold back on the footing this change exists to fix.
     *
     * What this does **not** fix: a pivot just inside the window's left edge is still confirmed
     * using fewer bars to its left than the full series would have. Warm-up repairs the
     * *indicator* readings; it cannot repair pivot confirmation without recomputing pivots over
     * the whole series, which is the other design and a different trade.
     */
    const val WARM_UP_BARS: Int = 60

    /** The lowest factor reading, ties broken by weight so the costliest factor wins. */
    private fun weakestOf(breakdown: com.mia.app.domain.model.ScoreBreakdown): ScoreFactor {
        val readings = ScoreFactor.ALL.map { factor ->
            factor to when (factor) {
                ScoreFactor.GEOMETRY -> breakdown.geometry
                ScoreFactor.STRUCTURE -> breakdown.structure
                ScoreFactor.CONFIRMATION -> breakdown.confirmation
                ScoreFactor.CONTEXT -> breakdown.context
            }
        }
        return readings.minWithOrNull(
            compareBy<Pair<ScoreFactor, Int>> { it.second }.thenByDescending { it.first.weight },
        )?.first ?: ScoreFactor.GEOMETRY
    }

    /** Infers the blocker from the engine's own sentence when no explicit one was supplied. */
    private fun blockerFor(reason: String): EvaluationOutcome.Blocker {
        val lower = reason.lowercase()
        return when {
            lower.contains("bar") -> EvaluationOutcome.Blocker.NOT_ENOUGH_BARS
            lower.contains("pivot") -> EvaluationOutcome.Blocker.NOT_ENOUGH_PIVOTS
            lower.contains("indicator") -> EvaluationOutcome.Blocker.INDICATORS_UNAVAILABLE
            else -> EvaluationOutcome.Blocker.NO_DATA
        }
    }

    /** The blocker for a pattern the scan never mentioned, worked out from the window itself. */
    private fun blockerFor(
        spec: PatternSpec,
        timeframe: Timeframe,
        windowBars: Int,
        result: PatternScanResult,
    ): EvaluationOutcome.Blocker = when {
        windowBars < MatchScorePolicy.minBars(timeframe) -> {
            EvaluationOutcome.Blocker.NOT_ENOUGH_BARS
        }

        result.pivots.size < ZigzagParams.MIN_PIVOTS -> EvaluationOutcome.Blocker.NOT_ENOUGH_PIVOTS

        windowBars < spec.minBars -> EvaluationOutcome.Blocker.NOT_ENOUGH_BARS

        else -> EvaluationOutcome.Blocker.NO_DATA
    }

    /**
     * A sentence that tells the reader what to do, not merely that something is missing.
     *
     * Deliberately carries the two numbers — required and available — because "not enough data" is
     * the one phrasing that cannot be acted on, and the whole point of [EvaluationOutcome] is to
     * be actionable.
     */
    private fun blockerSentence(
        name: String,
        timeframe: Timeframe,
        windowBars: Int,
    ): String {
        val gate = MatchScorePolicy.minBars(timeframe)
        return if (windowBars < gate) {
            "$name not evaluated — this window has $windowBars bars and plan.md §6.4 requires " +
                "$gate for ${timeframe.name} bars"
        } else {
            "$name not evaluated — no candidate formed within this $windowBars-bar window"
        }
    }
}

/**
 * Runs the canonical scan for a `(symbol, timeframe)` and evaluates it.
 *
 * Kept beside [PatternExplorer] so the two paths cannot drift: the Explorer's window view and the
 * Patterns tab's list are the same computation over different slices, and a reader who sees
 * different numbers on two screens of the same app should be looking at a bug.
 */
fun evaluateCanonicalScan(
    candles: List<Candle>,
    timeframe: Timeframe,
    detectors: List<PatternDetector>,
    symbol: String,
    benchmarkCandles: List<Candle>? = null,
): PatternEvaluation {
    val result = PatternEngine.run(
        candles = candles,
        timeframe = timeframe,
        detectors = detectors,
        options = PatternScanOptions(
            symbol = symbol,
            benchmarkCandles = benchmarkCandles,
            isCanonicalScan = true,
        ),
    )
    return PatternExplorer.evaluate(result, symbol, timeframe, candles.size)
}

