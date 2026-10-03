package com.mia.app.domain.pattern

import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.MatchScorePolicy
import com.mia.app.domain.model.PatternDetector
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
     */
    fun scanWindow(
        candles: List<Candle>,
        timeframe: Timeframe,
        detectors: List<PatternDetector>,
        symbol: String = "",
        windowBars: Int = candles.size,
        benchmarkCandles: List<Candle>? = null,
    ): PatternEvaluation {
        val result = PatternEngine.run(
            candles = candles,
            timeframe = timeframe,
            detectors = detectors,
            options = PatternScanOptions(
                symbol = symbol,
                benchmarkCandles = benchmarkCandles,
                // Explicit and non-default: a window scan is exploratory by definition, and
                // PatternIds is name-based, so recording one would collide with the canonical
                // scan's ids in the accuracy table.
                isCanonicalScan = false,
            ),
        )
        return evaluate(result, symbol, timeframe, windowBars)
    }

    // ------------------------------------------------------------------ helpers

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

