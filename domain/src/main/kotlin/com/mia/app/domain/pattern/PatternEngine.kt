package com.mia.app.domain.pattern

import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.Category
import com.mia.app.domain.model.Direction
import com.mia.app.domain.model.Match
import com.mia.app.domain.model.MatchScorePolicy
import com.mia.app.domain.model.PatternDetector
import com.mia.app.domain.model.PivotKind
import com.mia.app.domain.model.ScoreBreakdown
import com.mia.app.domain.model.Timeframe
import com.mia.app.domain.technical.IndicatorSnapshot
import com.mia.app.domain.technical.TrendRegime

/**
 * The detection pipeline of plan.md §6.2 / §6.4.
 *
 * ```
 * candles ──▶ ZigzagPivots (ONCE) ──┬─▶ detector 1 ─┐
 *           └─▶ IndicatorSnapshot   ├─▶ detector 2 ─┼─▶ measures ─▶ FactorScorer ─▶ gate
 *                                     ├─▶ …           │
 *                                     └─▶ detector 12 ┘
 * ```
 *
 * **Two things are computed once and shared**: the pivot list and the indicator snapshot. Both
 * exist for the same reason — plan.md §11 targets 2 GB-RAM budget phones, and twelve detectors
 * each re-walking 500 bars (plus an independent ATR/RSI/ADX pass) is the difference between a
 * nightly scan that finishes and one that does not.
 */

/** A candidate that was *not* shown, with the reason. Transparency is a product requirement. */
data class SuppressedMatch(
    /** Pattern display name, so the UI can say what it chose not to draw. */
    val name: String,
    /** The score it would have carried; `0` when no score was ever computed. */
    val score: Int,
    /** Why it was hidden, in UI language. Never advice, never a probability. */
    val reason: String,
)

/** Per-detector cost, so a slow detector can be found on a 2 GB phone (plan.md §12). */
data class ScanDiagnostic(
    val detector: String,
    /** Matches the detector returned. */
    val matchCount: Int,
    /**
     * Of those, how many carry a `ScoreBreakdown`. A match without one never went through
     * [FactorScorer] and is a contract violation by the detector (`patterns-spec.md` §4).
     */
    val scoredMatchCount: Int,
    /** Wall-clock nanoseconds for that detector only. Never part of a score. */
    val nanos: Long,
) {
    val millis: Double get() = nanos / 1_000_000.0
}

/** Inputs the caller supplies to one scan. All optional; the defaults make a pure D1 scan. */
data class PatternScanOptions(
    /** Display symbol for notes. */
    val symbol: String = "",
    /**
     * Benchmark history (e.g. NIFTY) for the relative-strength CONTEXT measure. `null` means no
     * index was loaded, which must read as "no benchmark", never as a strength of zero
     * (`scoring-spec.md` §6, X4).
     */
    val benchmarkCandles: List<Candle>? = null,
    /**
     * Regime to gate against, overriding the symbol's own latest ADX regime. The app passes the
     * *other* timeframe's regime here so a daily pattern can be gated on the weekly picture
     * (`scoring-spec.md` §12.5 "multiple timeframe confirmation").
     */
    val regimeOverride: TrendRegime? = null,
    /**
     * How a match's direction is resolved for the regime gate. `null` entries mean "unknown",
     * which may RENDER but never PROJECT (`MatchScorePolicy.verdict`).
     *
     * The default reads the measured move's direction and otherwise infers it from the newest
     * pivot's kind — a formation ending on a swing high resolves DOWN. A candlestick detector
     * carries no measured move (`patterns-spec.md` DD-1), so it overrides this if it wants its
     * signal's own direction used.
     */
    val directionResolver: ((Match) -> Direction?)? = null,
    /** Collect [ScanDiagnostic]s. Off by default so timings never leak into a score comparison. */
    val collectDiagnostics: Boolean = false,
    /**
     * Injection seam: an already-built snapshot (the caller has one for the technicals tab), or a
     * factory. Exists so a test can *count* how many times the expensive pass runs.
     */
    val snapshotFactory: ((List<Candle>, List<Candle>?) -> IndicatorSnapshot)? = null,
) {
    companion object {
        val DEFAULT = PatternScanOptions()
    }
}

/**
 * Everything one scan produced, including what it chose not to show.
 *
 * [suppressed] is not debug output: showing the user *why* a detected pattern was hidden is how
 * they learn the app is not quietly manufacturing signals (plan.md §1.4).
 */
data class PatternScanResult(
    /** Kept matches, best score first, ties broken by the most recent pivot. */
    val matches: List<Match>,
    /** Candidates that scored or gated below the render threshold, each with a reason. */
    val suppressed: List<SuppressedMatch>,
    /** The gate outcome for the top-ranked match, or [MatchScorePolicy.Verdict.NOT_ENOUGH_HISTORY]. */
    val verdict: MatchScorePolicy.Verdict,
    /** `true` when [barCount] is below [minBarsRequired]; then no score is emitted at all. */
    val insufficientHistory: Boolean,
    val minBarsRequired: Int,
    val barCount: Int,
    /** The shared pivot list every detector consumed. */
    val pivots: PivotSet,
    /** Transparency notes: warm-ups, missing delivery data, unscored factors. */
    val notes: List<String>,
    /**
     * Populated only when `PatternScanOptions.collectDiagnostics` is set, because it carries wall
     * clock timings that must not participate in any equality or determinism comparison. Use
     * [contentEquals] for that.
     */
    val diagnostics: List<ScanDiagnostic> = emptyList(),
) {
    /** True when the scan produced nothing to draw. */
    val isEmpty: Boolean get() = matches.isEmpty()

    /** The best-scored match, or `null`. */
    val topMatch: Match? get() = matches.firstOrNull()

    /** Every penalty explanation, deduplicated for a single "why this is lower" line. */
    fun penaltyNotes(): List<String> = suppressed.map { it.reason }.distinct()

    /**
     * Compares everything except [diagnostics] — the honest way to assert that two runs of the
     * same input produce the same result (plan.md §4.3 purity, `scoring-spec.md` §9.1 determinism).
     */
    fun contentEquals(other: PatternScanResult): Boolean =
        matches == other.matches &&
            suppressed == other.suppressed &&
            verdict == other.verdict &&
            insufficientHistory == other.insufficientHistory &&
            minBarsRequired == other.minBarsRequired &&
            barCount == other.barCount &&
            pivots == other.pivots &&
            notes == other.notes
}

object PatternEngine {

    /**
     * Runs the whole pipeline once for one `(symbol, timeframe)`.
     *
     * @param detectors the detectors to run; the orchestrator wires the list, so the per-pattern
     *   detector packages never leak into the engine.
     * @param options see [PatternScanOptions]; every field has a default.
     */
    fun run(
        candles: List<Candle>,
        timeframe: Timeframe,
        detectors: List<PatternDetector>,
        options: PatternScanOptions = PatternScanOptions.DEFAULT,
    ): PatternScanResult {
        val barCount = candles.size
        val minBarsRequired = MatchScorePolicy.minBars(timeframe)
        val insufficientHistory = !MatchScorePolicy.isSufficientBars(timeframe, barCount)

        // --- shared, computed once -------------------------------------------------------
        val snapshot = (options.snapshotFactory ?: { s, b -> IndicatorSnapshot.of(s, b) })
            .invoke(candles, options.benchmarkCandles)
        val pivots = ZigzagPivots.compute(candles, timeframe, atr = snapshot.atr, barCount = barCount)
        val regime = options.regimeOverride ?: snapshot.trendRegime

        // --- detectors -------------------------------------------------------------------
        val notes = ArrayList<String>()
        val all = ArrayList<Match>()
        val diagnostics = if (options.collectDiagnostics) ArrayList<ScanDiagnostic>() else null

        for (detector in detectors) {
            // §8.1 row 2: a detector whose own window is unmet returns no match. Skipping it here
            // is the same decision, one pass cheaper.
            if (detector.minBars > barCount) {
                notes += "${detector.name} skipped — needs ${detector.minBars} bars, have $barCount"
                continue
            }
            val startedAt = System.nanoTime()
            val matches = detector.detect(pivots.pivots, candles)
            diagnostics?.add(
                ScanDiagnostic(
                    detector = detector.name,
                    matchCount = matches.size,
                    scoredMatchCount = matches.count { it.breakdown != null },
                    nanos = System.nanoTime() - startedAt,
                ),
            )
            all += matches
        }

        notes += dataQualityNotes(snapshot, candles, regime, pivots)

        // --- gate ------------------------------------------------------------------------
        val kept = ArrayList<Match>()
        val suppressed = ArrayList<SuppressedMatch>()

        for (match in all) {
            val direction = resolveDirection(match, options.directionResolver)
            val aligned = regimeAligned(regime, direction, match.category)
            val verdict = MatchScorePolicy.verdict(match.score, timeframe, barCount, aligned)

            if (insufficientHistory) {
                suppressed += SuppressedMatch(match.name, match.score, notEnoughHistoryReason(timeframe, barCount))
                continue
            }
            if (verdict == MatchScorePolicy.Verdict.NOT_ENOUGH_HISTORY) {
                suppressed += SuppressedMatch(match.name, match.score, notEnoughHistoryReason(timeframe, barCount))
                continue
            }
            if (verdict == MatchScorePolicy.Verdict.SUPPRESS) {
                suppressed += SuppressedMatch(
                    match.name,
                    match.score,
                    "${match.name} not shown — match score ${match.score} is below the " +
                        "${MatchScorePolicy.SUPPRESS_THRESHOLD} render threshold. Geometric fit only.",
                )
                continue
            }
            kept += match
        }

        val ranked = kept.sortedWith(rankOrder)
        if (insufficientHistory) {
            notes += notEnoughHistoryReason(timeframe, barCount)
        }
        notes += rankingNotes(ranked, timeframe, barCount, regime)
        notes += unscoredFactorNotes(ranked)

        return PatternScanResult(
            matches = ranked,
            suppressed = suppressed.sortedWith(
                compareByDescending<SuppressedMatch> { it.score }.thenBy { it.name },
            ),
            verdict = when {
                insufficientHistory -> MatchScorePolicy.Verdict.NOT_ENOUGH_HISTORY
                ranked.isEmpty() -> MatchScorePolicy.Verdict.SUPPRESS
                else -> MatchScorePolicy.verdict(ranked.first().score, timeframe, barCount, regimeAlignedFor(ranked.first(), options, regime))
            },
            insufficientHistory = insufficientHistory,
            minBarsRequired = minBarsRequired,
            barCount = barCount,
            pivots = pivots,
            notes = notes.distinct(),
            diagnostics = diagnostics ?: emptyList(),
        )
    }

    /**
     * The plan.md §6.4 regime gate, **direction aware** (`docs/CONTRACTS.md`; spec DD-4,
     * `patterns-spec.md` §23 C4).
     *
     * * `TRENDING_UP` / `TRENDING_DOWN` aligned with the pattern's own direction.
     * * `RANGING` **also counts as aligned for a reversal pattern**: "detecting reversals near
     *   range boundaries" `[Ch.17-4 §Ranging Markets]` and "breakouts fail frequently" in a
     *   range `[Ch.20 §Regime 3]` are the same statement, so a reversal at a range boundary is
     *   the *good* case, not the blocked one. It is never aligned for a continuation pattern.
     * * `UNKNOWN` is **never** aligned — an undecided market may still render, never project.
     *
     * The candlestick family is treated as reversal-like: every one of detectors 1–5 is a
     * reversal of the immediately preceding bar's pressure `[Ch.17-2]`, and their
     * `CTX_MISMATCH` is already weighted double (`scoring-spec.md` §7.1).
     */
    fun regimeAligned(
        regime: TrendRegime,
        direction: Direction?,
        category: Category,
    ): Boolean? {
        if (direction == null || regime == TrendRegime.UNKNOWN) return null
        val trendingInDirection = when (direction) {
            Direction.UP -> TrendRegime.TRENDING_UP
            Direction.DOWN -> TrendRegime.TRENDING_DOWN
        }
        if (regime == trendingInDirection) return true
        val reversalLike = category == Category.REVERSAL || category == Category.CANDLESTICK
        return if (reversalLike && regime == TrendRegime.RANGING) true else false
    }

    /**
     * Direction of a match: an explicit resolver, then the measured move, then the newest
     * pivot's kind, then the pattern's textbook bias.
     *
     * The last step is what lets the candlestick family pass the regime gate at all — those
     * patterns have neither a pivot nor a measured move (spec DD-14), so without it their
     * direction was `null`, `regimeAligned` was `null`, and every candlestick match was capped
     * at `RENDER` regardless of score.
     */
    fun resolveDirection(match: Match, resolver: ((Match) -> Direction?)? = null): Direction? {
        resolver?.let { return it(match) }
        match.measuredMove?.let { return it.direction }
        match.pivots.lastOrNull()?.let { last ->
            return when (last.kind) {
                PivotKind.HIGH -> Direction.DOWN
                PivotKind.LOW -> Direction.UP
            }
        }
        return PatternIds.directionalBias(match.name)
    }

    /** Score descending, then most recent pivot date descending (`CONTRACTS.md` sort rule). */
    val rankOrder: Comparator<Match> = compareByDescending<Match> { it.score }
        .thenByDescending { latestPivotDate(it) }

    private fun latestPivotDate(match: Match) = match.pivots.lastOrNull()?.date ?: match.detectedOn

    private fun regimeAlignedFor(match: Match, options: PatternScanOptions, regime: TrendRegime): Boolean? =
        regimeAligned(regime, resolveDirection(match, options.directionResolver), match.category)

    /**
     * Why the top match projects (or does not). The regime gate is invisible in a bare number,
     * and plan.md §6.4 makes it a hard condition, so the user is told.
     */
    private fun rankingNotes(
        matches: List<Match>,
        timeframe: Timeframe,
        barCount: Int,
        regime: TrendRegime,
    ): List<String> {
        val top = matches.firstOrNull() ?: return emptyList()
        val aligned = regimeAlignedFor(top, PatternScanOptions.DEFAULT, regime)
        return when (MatchScorePolicy.verdict(top.score, timeframe, barCount, aligned)) {
            MatchScorePolicy.Verdict.PROJECT -> listOf(
                "${top.name} scored ${top.score} with the trend regime aligned — its geometry is " +
                    "projected and its breakout alert is scheduled.",
            )

            MatchScorePolicy.Verdict.RENDER ->
                if (top.score >= MatchScorePolicy.PROJECT_THRESHOLD) {
                    listOf(
                        "${top.name} scored ${top.score} but the trend regime is not aligned — shown " +
                            "without a projection.",
                    )
                } else {
                    listOf("${top.name} scored ${top.score} — shown without a projection.")
                }

            else -> emptyList()
        }
    }

    private fun notEnoughHistoryReason(timeframe: Timeframe, barCount: Int): String {
        val label = if (timeframe == Timeframe.D1) "daily" else "weekly"
        return "Not enough history — need ${MatchScorePolicy.minBars(timeframe)} $label bars, have $barCount."
    }

    /**
     * Notes about what could not be scored, from the snapshot's own nulls
     * (`scoring-spec.md` §8.1). Every one of these is a case where the UI must say so rather
     * than present a factor as a real reading.
     */
    private fun dataQualityNotes(
        snapshot: IndicatorSnapshot,
        candles: List<Candle>,
        regime: TrendRegime,
        pivots: PivotSet,
    ): List<String> {
        val notes = ArrayList<String>()
        val label = if (snapshot.timeframe == Timeframe.D1) "daily" else "weekly"

        if (candles.none { it.deliveryPercent != null }) {
            notes += "Delivery % unavailable — confirmation partially unscored."
        }
        if (snapshot.relativeStrength == null) {
            notes += "No index benchmark — relative strength unscored."
        }
        if (snapshot.adx.adxWarmupBars() >= candles.size) {
            notes += "ADX warm-up incomplete ($label) — context partially unscored."
        }
        if (regime == TrendRegime.UNKNOWN) {
            notes += "Trend regime undecided — this match can be shown but not projected."
        }
        if (pivots.isEmpty) {
            notes += "No confirmed swing points in this window — no structural pattern can match."
        }
        return notes
    }

    /** Per-match reasons for any factor that scored 0 because no measure existed. */
    private fun unscoredFactorNotes(matches: List<Match>): List<String> =
        matches.flatMap { match ->
            val breakdown = match.breakdown ?: return@flatMap emptyList()
            ScoreFactor.ALL
                .filter { readingOf(breakdown, it) == PatternScoringPolicy.UNSCORED_FACTOR_SCORE }
                .map { factor -> "${match.name}: ${factor.label} unscored — no measures emitted." }
        }

    private fun readingOf(breakdown: ScoreBreakdown, factor: ScoreFactor): Int =
        when (factor) {
            ScoreFactor.GEOMETRY -> breakdown.geometry
            ScoreFactor.STRUCTURE -> breakdown.structure
            ScoreFactor.CONFIRMATION -> breakdown.confirmation
            ScoreFactor.CONTEXT -> breakdown.context
        }
}