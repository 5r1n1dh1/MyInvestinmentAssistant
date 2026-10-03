package com.mia.app.domain.model

import java.time.LocalDate

/**
 * Pattern-detection contracts (plan.md §6.5).
 *
 * The 12 v1 detectors all implement [PatternDetector] so that adding a pattern is
 * mechanical, and so that `ZigzagPivots` can be computed once and shared.
 */

/** Which of the three v1 families a pattern belongs to. */
enum class Category {
    CANDLESTICK,
    REVERSAL,
    CONTINUATION,
}

enum class PivotKind {
    HIGH,
    LOW,
}

enum class Direction {
    UP,
    DOWN,
}

/**
 * A swing point produced by the shared zigzag pass.
 *
 * [barIndex] is the index into the candle list; [date] and [price] come from that bar.
 * [legIndex] is the position of the pivot in the alternating high/low sequence, which
 * detectors use to reason about spacing without re-walking the candle list.
 */
data class Pivot(
    val barIndex: Int,
    val date: LocalDate,
    val price: Double,
    val kind: PivotKind,
    val legIndex: Int,
)

/**
 * Measured move (plan.md §6.6 item 1).
 *
 * This is *pattern geometry*: the height of the formation projected from the breakout
 * point. It is never a price target and must never be rendered as one (plan.md §1.4 rule 2).
 */
data class MeasuredMove(
    /** Formation height. Always >= 0 (plan.md §12 property test). */
    val height: Double,
    /** Level at which the pattern is considered broken/complete. */
    val triggerLevel: Double,
    /** Direction the geometry projects once the trigger breaks. */
    val direction: Direction,
    /** The derived level. Labelled "pattern geometry" in the UI, never "target price". */
    val projectedLevel: Double,
) {
    val isUpward: Boolean get() = direction == Direction.UP
}

/**
 * Directional forecast (plan.md §6.6 item 2): a dashed path for the next 1–5 bars,
 * visually distinct from actual price. Kept separate from [MeasuredMove] on purpose
 * (decision D2: both outputs, never conflated).
 */
data class ProjectedPath(
    val points: List<Pair<LocalDate, Double>>,
    val horizonBars: Int,
    val label: String = "Projected path (directional, not a forecast of price)",
)

/** Breakout alert condition (plan.md §6.6 item 3). Distinct from the projection. */
data class BreakoutTrigger(
    val description: String,
    val level: Double,
    val direction: Direction,
    val fired: Boolean,
)

/** A scored detection. [score] is shape fit only — never a probability (plan.md §6.1). */
data class Match(
    val patternId: Long,
    val name: String,
    val score: Int,
    val pivots: List<Pivot> = emptyList(),
    val measuredMove: MeasuredMove? = null,
    val category: Category,
    val timeframe: Timeframe,
    val detectedOn: LocalDate,
    val breakdown: ScoreBreakdown? = null,
    /**
     * The weighted measures [breakdown] was computed from, or empty when never scored.
     *
     * A detector emits measures and never a score (`docs/CONTRACTS.md`, the locked seam), so
     * [matchOf] is the last place these exist — and it used to drop them there. Keeping them is
     * what lets a **near-miss** show *why* it fell short: a score of 47 is equally consistent with
     * geometry failing, structure failing, or a penalty being applied, and the factor totals alone
     * cannot distinguish those.
     *
     * Empty rather than absent-and-null: a candidate that was never scored carries no measures,
     * and "no measures" is the honest reading. A caller must not infer "scored 0" from it.
     */
    val measures: List<Measure> = emptyList(),
)

/** Per-factor scores, kept so the UI can explain *why* a match scored what it scored. */
data class ScoreBreakdown(
    val geometry: Int,
    val structure: Int,
    val confirmation: Int,
    val context: Int,
) {
    /** Weighted 0–100 total using the plan's §6.3 weights. */
    val total: Int
        get() = (geometry * MatchScorePolicy.GEOMETRY_WEIGHT +
            structure * MatchScorePolicy.STRUCTURE_WEIGHT +
            confirmation * MatchScorePolicy.CONFIRMATION_WEIGHT +
            context * MatchScorePolicy.CONTEXT_WEIGHT)
            .toInt() / MatchScorePolicy.TOTAL_WEIGHT
            .coerceIn(0, 100)

    companion object {
        val ZERO = ScoreBreakdown(0, 0, 0, 0)
    }
}

/** Every v1 detector. Pure function of (pivots, candles) — no IO, no framework. */
interface PatternDetector {
    val name: String
    val category: Category
    val minBars: Int

    fun detect(pivots: List<Pivot>, candles: List<Candle>): List<Match>
}

/**
 * The honesty contract and thresholds (plan.md §6.1, §6.3, §6.4).
 *
 * A measured control test over 20,780 next-day returns showed every signal at ~50%
 * accuracy. Match score therefore measures *geometric fidelity to the textbook template*
 * and is never a probability of profit.
 */
object MatchScorePolicy {
    const val GEOMETRY_WEIGHT: Int = 40
    const val STRUCTURE_WEIGHT: Int = 25
    const val CONFIRMATION_WEIGHT: Int = 20
    const val CONTEXT_WEIGHT: Int = 15

    /**
     * The four weights sum to 100, so a weighted mean divides by this.
     *
     * `scoring-spec.md` §1: "`ScoreBreakdown.total` computes
     * `(geometry·40 + structure·25 + confirmation·20 + context·15) / 100`, truncated then clamped."
     */
    const val TOTAL_WEIGHT: Int = GEOMETRY_WEIGHT + STRUCTURE_WEIGHT + CONFIRMATION_WEIGHT + CONTEXT_WEIGHT

    /** >= 70 plus gates: project and schedule an alert. */
    const val PROJECT_THRESHOLD: Int = 70

    /** 50..69: render, but no projection. */
    const val RENDER_THRESHOLD: Int = 50

    /** < 50: suppress entirely. */
    const val SUPPRESS_THRESHOLD: Int = 50

    /** Hit rates are meaningless below this sample size (plan.md §6.1). */
    const val MIN_HIT_RATE_SAMPLES: Int = 30

    /** Hard gate on history length. Below this we say "not enough history". */
    fun minBars(timeframe: Timeframe): Int = when (timeframe) {
        Timeframe.D1 -> 60
        Timeframe.W1 -> 40
    }

    fun isSufficientBars(timeframe: Timeframe, barCount: Int): Boolean =
        barCount >= minBars(timeframe)

    enum class Verdict {
        /** Score >= 70 and every gate passed: project + alert. */
        PROJECT,

        /** 50 <= score < 70: render only. */
        RENDER,

        /** < 50: hide. */
        SUPPRESS,

        /** Not enough history to score at all (hard gate, plan.md §6.4). */
        NOT_ENOUGH_HISTORY,
    }

    /**
     * Applies §6.4. [regimeAligned] is the ADX/relative-strength gate; when it is null the
     * gate is treated as unmet for PROJECT but the match may still RENDER.
     */
    fun verdict(score: Int, timeframe: Timeframe, barCount: Int, regimeAligned: Boolean?): Verdict {
        if (!isSufficientBars(timeframe, barCount)) return Verdict.NOT_ENOUGH_HISTORY
        return when {
            score >= PROJECT_THRESHOLD && regimeAligned == true -> Verdict.PROJECT
            score >= RENDER_THRESHOLD -> Verdict.RENDER
            else -> Verdict.SUPPRESS
        }
    }
}

/** UI copy for what a score is and is not. Single source so no screen can drift. */
object MatchScoreLabels {
    const val TITLE = "Match score"
    const val SUBTITLE = "Geometric fit to the textbook pattern"
    const val DISCLAIMER =
        "Shape fit only. This is not a probability of profit and not a buy/sell signal."
    const val PROJECTION_LABEL = "Pattern geometry"
    const val NOT_A_TARGET_WARNING = "Projected from measured-move geometry. Not a price target."
}