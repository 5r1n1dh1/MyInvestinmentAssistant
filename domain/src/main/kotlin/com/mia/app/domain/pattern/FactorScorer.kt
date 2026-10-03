package com.mia.app.domain.pattern

import com.mia.app.domain.model.ScoreBreakdown
import com.mia.app.domain.model.Measure
import kotlin.math.roundToInt

/**
 * Every `LIN_*` anchor, weight and penalty magnitude used by the pattern scorer
 * (`scoring-spec.md` §11 — "Every `LIN_*` anchor, weight and penalty value in this file is a
 * named constant. Do not inline them.").
 *
 * The anchors were chosen so textbook values land in 70–100 and hard values in 0–40
 * (`scoring-spec.md` §9.3); tightening them is the sanctioned way to improve discrimination and
 * widening them is never the way (`scoring-spec.md` §9.3.4).
 */
object PatternScoringPolicy {

    // ------------------------------------------------------------------------ geometry
    const val GEOM_SYM_LO: Double = 0.80
    const val GEOM_SYM_HI: Double = 1.00
    const val GEOM_DOM_LO: Double = 0.10
    const val GEOM_DOM_HI: Double = 0.60
    const val GEOM_DOM3_LO: Double = 0.10
    const val GEOM_DOM3_HI: Double = 0.50
    const val GEOM_DOM_DT_LO: Double = 2.0
    const val GEOM_DOM_DT_HI: Double = 6.0
    const val GEOM_TIMESYM_LO: Double = 0.50
    const val GEOM_TIMESYM_HI: Double = 1.00
    const val GEOM_TILT_PERFECT: Double = 0.0
    const val GEOM_TILT_FLOOR: Double = 10.0
    const val GEOM_MONO_LO: Double = 0.60
    const val GEOM_MONO_HI: Double = 1.00
    const val GEOM_PARALLEL_FLOOR: Double = 0.0
    const val GEOM_PARALLEL_MAX: Double = 0.75
    const val GEOM_FLAGDEPTH_LO: Double = 0.05
    const val GEOM_FLAGDEPTH_HI: Double = 0.45
    const val GEOM_POLE_SAT: Double = 0.25
    const val GEOM_TRI_REF_LO: Double = 3.0
    const val GEOM_TRI_REF_HI: Double = 10.0
    const val GEOM_WICK_SAT: Double = 4.0
    const val GEOM_STARRATIO_PERFECT: Double = 0.05
    const val GEOM_STARRATIO_FLOOR: Double = 0.50
    const val GEOM_PEN_LO: Double = 0.30
    const val GEOM_PEN_HI: Double = 0.90
    const val GEOM_TRI_RISE_LO: Double = 2.0
    const val GEOM_TRI_RISE_HI: Double = 15.0

    /** `scoring-spec.md` DD-7: what a two-point neckline reports instead of a fabricated R² = 1. */
    const val NEUTRAL_BOUNDARY_R2: Double = 0.60

    // ----------------------------------------------------------------------- structure
    const val STRUCT_RESID_FLOOR: Double = 0.15
    const val STRUCT_R2_LO: Double = 0.30
    const val STRUCT_R2_HI: Double = 0.95
    const val STRUCT_BOUNDARY_R2_LO: Double = 0.40
    const val STRUCT_BOUNDARY_R2_HI: Double = 0.98
    const val STRUCT_DUR_LO: Double = 0.30
    const val STRUCT_DUR_HI: Double = 2.00
    const val STRUCT_CV_LO: Double = 0.02
    const val STRUCT_CV_HI: Double = 0.12
    const val STRUCT_RANGEAT_PERFECT: Double = 4.0
    const val STRUCT_RANGEAT_FLOOR: Double = 0.6

    // -------------------------------------------------------------------- confirmation
    const val CONF_BREAKDEPTH_SAT: Double = 2.0
    const val CONF_RVOL_SAT: Double = 3.0
    const val CONF_DELIVERY_SAT: Double = 1.50
    const val CONF_RSI_GAP_HI: Double = 8.0

    // -------------------------------------------------------------------------- context
    const val CTX_PRIOR_LO: Double = 0.0
    const val CTX_PRIOR_HI: Double = 12.0
    const val CTX_ADX_LO: Double = 20.0
    const val CTX_ADX_HI: Double = 40.0
    const val CTX_RS_SAT: Double = 0.15

    // ------------------------------------------------------------------------- penalties
    const val PEN_WEAK_VOLUME: Int = 6
    const val PEN_TRAP: Int = 12
    const val PEN_FAILED_RETEST: Int = 8
    const val PEN_FAILED_PATTERN: Int = 18
    const val PEN_FAILED_CONTINUATION: Int = 18
    const val PEN_STRUCTURE_CONTRADICTS: Int = 14
    const val PEN_CTX_MISMATCH: Int = 10
    const val PEN_CTX_MISMATCH_CS: Int = 20 // candlesticks, ×2
    const val PEN_HTF_CONFLICT: Int = 5
    const val PEN_VOLATILE: Int = 5
    const val PEN_LOW_LIQUIDITY: Int = 8
    const val PEN_VOLUME_CONTRACTION_ABSENT: Int = 6
    const val PEN_NO_DIVERGENCE: Int = 6
    const val PEN_NO_PRIOR_TREND: Int = 10
    const val PEN_RANGING: Int = 8
    const val PEN_EXHAUSTION: Int = 6
    const val PEN_INTRADAY_SPIKE: Int = 10
    const val PEN_STALE: Int = 6
    const val PEN_NO_CONFIRMATION: Int = 8
    const val PEN_NO_FOLLOW_THROUGH: Int = 5
    const val PEN_WRONG_LOCATION: Int = 12

    /** `scoring-spec.md` §7.2 hard cap: no penalty set can deduct more than 30 points. */
    const val PENALTY_CAP: Int = 30

    /** A factor with no applicable measures at all. Locked at 0 — see [FactorScorer]. */
    const val UNSCORED_FACTOR_SCORE: Int = 0

    // ------------------------------------------------------- discrimination gate (§9.1)
    const val DISCRIM_MIN_DISTINCT_SCORES: Int = 45
    const val DISCRIM_MIN_STDDEV: Double = 18.0
    const val DISCRIM_MIN_IQR: Double = 20.0
    const val DISCRIM_MAX_MODE_SHARE: Double = 0.06
    const val DISCRIM_MIN_BAND_SHARE: Double = 0.10
    const val DISCRIM_MIN_PER_PATTERN_STDDEV: Double = 12.0
}

/** The four factor readings plus the *reason* whenever one of them could not be scored. */
data class FactorAssessment(
    /** The un-penalised breakdown, i.e. `score = Σ wᵢ · factors[i]`. */
    val breakdown: ScoreBreakdown,
    /** Per-factor `0..100` readings, keyed by [ScoreFactor]. */
    val factors: Map<ScoreFactor, Int>,
    /** Weight actually used per factor, after dropping inapplicable measures. */
    val weightsUsed: Map<ScoreFactor, Int>,
    /**
     * Why a factor is missing, in UI language (`scoring-spec.md` §8.1: "ScoreBreakdown must
     * expose which measures were unavailable so the UI can say so. Never silently drop the
     * factor to 0 without a label.").
     */
    val missingReasons: Map<ScoreFactor, String>,
    /** Measure names the detector emitted with weight 0, i.e. marked inapplicable. */
    val inapplicableMeasures: List<String>,
) {
    val isFullyScored: Boolean get() = missingReasons.isEmpty()
}

/** A penalised breakdown, with the pre-penalty arithmetic kept for the UI's explanation. */
data class ScoreApplication(
    /** What `Match.breakdown` carries, so `Match.score == breakdown.total` always holds. */
    val breakdown: ScoreBreakdown,
    /** The four factors *before* penalties — what the geometry actually measured. */
    val rawFactors: Map<ScoreFactor, Int>,
    /** Sum of [Penalty.points], before the §7.2 cap. */
    val rawPenaltyPoints: Int,
    /** `min(rawPenaltyPoints, 30)` — what was actually deducted. */
    val appliedPenaltyPoints: Int,
    /** The penalties that were passed in, deduplicated by id for display. */
    val penalties: List<Penalty>,
) {
    /** `true` when the §7.2 cap bound and points were dropped. */
    val penaltyCapBound: Boolean get() = rawPenaltyPoints > appliedPenaltyPoints
}

/**
 * The measure → `ScoreBreakdown` compiler (`scoring-spec.md` §1–§7).
 *
 * ```
 * score = round(0.40·G + 0.25·S + 0.20·C + 0.15·X) − min(Σ penalties, 30), clamped to [0, 100]
 * ```
 *
 * Three rules make the number mean something:
 *
 * 1. **A factor is the weighted mean of its measures**, weights renormalised over the measures
 *    that apply (§6.1), clamped to `[0, 100]` and rounded **once**, half-up (§2.5).
 * 2. **A factor with no applicable measures scores `0`, not 100 and not a fabricated 50**, and
 *    records a reason. The locked `docs/CONTRACTS.md` rule — "the engine treats the missing
 *    factor as 0 with a recorded reason (never silently 100)" — governs over
 *    `scoring-spec.md` §6.1's neutral-50 wording, which the spec itself notes never arises in v1.
 * 3. **Penalties are subtracted from the total, never from a factor**, so no match can be lifted
 *    back above the suppress threshold by a later factor (§7.2).
 */
object FactorScorer {

    /** `scoring-spec.md` §3, GEOMETRY. */
    fun geometry(measures: List<Measure>): Int = factor(measures, ScoreFactor.GEOMETRY)

    /** `scoring-spec.md` §4, STRUCTURE. */
    fun structure(measures: List<Measure>): Int = factor(measures, ScoreFactor.STRUCTURE)

    /** `scoring-spec.md` §5, CONFIRMATION. */
    fun confirmation(measures: List<Measure>): Int = factor(measures, ScoreFactor.CONFIRMATION)

    /** `scoring-spec.md` §6, CONTEXT. */
    fun context(measures: List<Measure>): Int = factor(measures, ScoreFactor.CONTEXT)

    /**
     * One factor's `0..100` reading.
     *
     * @return the weighted mean of the applicable measures, or
     *   [PatternScoringPolicy.UNSCORED_FACTOR_SCORE] when there are none.
     */
    fun factor(measures: List<Measure>, factor: ScoreFactor): Int {
        val applicable = applicableMeasures(measures, factor)
        if (applicable.isEmpty()) return PatternScoringPolicy.UNSCORED_FACTOR_SCORE
        val totalWeight = applicable.sumOf { it.weight }
        if (totalWeight <= 0) return PatternScoringPolicy.UNSCORED_FACTOR_SCORE
        var weighted = 0.0
        for (m in applicable) weighted += m.normalised() * m.weight
        // Round once, at the end, half-up (scoring-spec.md §2.5).
        return (weighted / totalWeight).coerceIn(0.0, 100.0).roundToInt()
    }

    /** The breakdown before penalties. Equivalent to calling the four factor functions. */
    fun score(measures: List<Measure>): ScoreBreakdown = assess(measures).breakdown

    /** [score] plus the per-factor weights and every "this could not be scored" reason. */
    fun assess(measures: List<Measure>): FactorAssessment {
        val factors = LinkedHashMap<ScoreFactor, Int>()
        val weights = LinkedHashMap<ScoreFactor, Int>()
        val reasons = LinkedHashMap<ScoreFactor, String>()

        for (factor in ScoreFactor.ALL) {
            val mine = measures.filter { it.factor == factor }
            val applicable = applicableMeasures(measures, factor)
            factors[factor] = factor(measures, factor)
            weights[factor] = applicable.sumOf { it.weight }
            if (applicable.isEmpty()) {
                reasons[factor] = missingReason(mine, factor)
            }
        }

        return FactorAssessment(
            breakdown = ScoreBreakdown(
                geometry = factors.getValue(ScoreFactor.GEOMETRY),
                structure = factors.getValue(ScoreFactor.STRUCTURE),
                confirmation = factors.getValue(ScoreFactor.CONFIRMATION),
                context = factors.getValue(ScoreFactor.CONTEXT),
            ),
            factors = factors,
            weightsUsed = weights,
            missingReasons = reasons,
            inapplicableMeasures = measures.filter { !it.isApplicable }.map { it.name },
        )
    }

    /**
     * Subtracts the penalties from a breakdown.
     *
     * **Why the penalty comes off *every* factor.** The factor weights sum to 100, so lowering
     * each factor by `p` lowers the weighted total by exactly `p` — which is what
     * `scoring-spec.md` §7.2 prescribes — while leaving `ScoreBreakdown.total` (the only score
     * `Match.score` is allowed to use, per `docs/CONTRACTS.md`) equal to the gated number.
     * Each factor is floored at 0, so a large penalty can never raise the total and the score can
     * never fall below 0.
     *
     * Use [apply] instead when the UI needs the pre-penalty factors and the deducted points; this
     * overload exists because it is the locked seam signature.
     */
    fun combine(breakdown: ScoreBreakdown, penalties: List<Penalty>): ScoreBreakdown =
        apply(breakdown, penalties).breakdown

    /** [combine] with the arithmetic exposed, so the UI can show *why* a match lost points. */
    fun apply(breakdown: ScoreBreakdown, penalties: List<Penalty>): ScoreApplication {
        val rawPenalty = penalties.sumOf { it.points }
        val applied = minOf(rawPenalty, PatternScoringPolicy.PENALTY_CAP)
        val rawFactors = ScoreFactor.ALL.associateWith { factor -> readingOf(breakdown, factor) }
        val adjusted = ScoreFactor.ALL.associateWith { factor ->
            (rawFactors.getValue(factor) - applied).coerceAtLeast(0)
        }
        return ScoreApplication(
            breakdown = ScoreBreakdown(
                geometry = adjusted.getValue(ScoreFactor.GEOMETRY),
                structure = adjusted.getValue(ScoreFactor.STRUCTURE),
                confirmation = adjusted.getValue(ScoreFactor.CONFIRMATION),
                context = adjusted.getValue(ScoreFactor.CONTEXT),
            ),
            rawFactors = rawFactors,
            rawPenaltyPoints = rawPenalty,
            appliedPenaltyPoints = applied,
            penalties = penalties.distinctBy { it.name to it.reason },
        )
    }

    /** Total deduction a detector's penalty list will cost, after the §7.2 cap. */
    fun totalPenalty(penalties: List<Penalty>): Int =
        minOf(penalties.sumOf { it.points }, PatternScoringPolicy.PENALTY_CAP)

    /** The measures of [factor] that actually carry weight (non-zero, finite). */
    fun applicableMeasures(measures: List<Measure>, factor: ScoreFactor): List<Measure> =
        measures.filter { it.factor == factor && it.isApplicable && it.value.isFinite() }

    private fun readingOf(breakdown: ScoreBreakdown, factor: ScoreFactor): Int = when (factor) {
        ScoreFactor.GEOMETRY -> breakdown.geometry
        ScoreFactor.STRUCTURE -> breakdown.structure
        ScoreFactor.CONFIRMATION -> breakdown.confirmation
        ScoreFactor.CONTEXT -> breakdown.context
    }

    private fun missingReason(emitted: List<Measure>, factor: ScoreFactor): String {
        val label = factor.label
        return if (emitted.isEmpty()) {
            "$label unscored — no measures emitted"
        } else {
            val names = emitted.joinToString(", ") { it.name }
            "$label unscored — no applicable measure (all marked inapplicable: $names)"
        }
    }
}