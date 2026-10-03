package com.mia.app.domain.model

/**
 * The measurement primitives a detector emits, and the four normalisation bands that turn them
 * into a `0..100` reading.
 *
 * ### Why these live in `model`, not in `pattern`
 * They started in `pattern/Measures.kt`, beside the seam that consumes them. But `Match` — which
 * lives here in `model` — now carries the measures that produced its own score, so that a
 * near-miss can be shown its weakest factor's detail instead of a bare number.
 *
 * Putting [Measure] in `pattern` would have made `model` depend on `pattern`, inverting the one
 * direction `docs/CONTRACTS.md` draws: `pattern` → `model`. [ScoreBreakdown], the aggregate of
 * these measures, has always lived here, so this also groups a measure with its own summary.
 *
 * The split rule they encode, from `scoring-spec.md` §1.1: *a measure about the shape is GEOMETRY;
 * about the lines drawn through it is STRUCTURE; about what happened at or after the trigger is
 * CONFIRMATION; about the world outside the formation is CONTEXT.*
 */

import com.mia.app.domain.pattern.ScoreFactor

/**
 * The four normalisation primitives of `scoring-spec.md` §2. "Every raw measure is passed
 * through exactly one of them. No prose, no judgement."
 *
 * Each band maps a raw measure to `0..100`; [FactorScorer] then takes the weighted mean per
 * factor and rounds once (`scoring-spec.md` §2.5).
 */
sealed class MeasureBand {

    /** The band's contribution for [value], always within `[0, 100]`. */
    abstract fun normalise(value: Double): Double

    /**
     * `LIN_LINEAR(v, lo, hi) = 100 · (clamp(v, lo, hi) − lo) / (hi − lo)` (`scoring-spec.md` §2.1).
     *
     * Used when **both** ends of the range are meaningful, so the midpoint should score mid.
     * With [invert] the clamp is applied to `hi − v` instead, which is how a measure whose *low*
     * end is best (`RATIO_SATURATING(v, k) = 100·min(1, v/k)`) is expressed with the same band.
     */
    data class Linear(val invert: Boolean, val lo: Double, val hi: Double) : MeasureBand() {
        init {
            require(!lo.isNaN() && !hi.isNaN()) { "LIN_LINEAR anchors must be finite" }
            require(hi > lo) { "LIN_LINEAR needs hi > lo, got lo=$lo hi=$hi" }
        }

        override fun normalise(value: Double): Double {
            if (value.isNaN()) return 0.0
            val v = if (invert) hi - (value - lo) else value
            return (100.0 * (v - lo) / (hi - lo)).coerceIn(0.0, 100.0)
        }
    }

    /**
     * `LIN_DECAY(v, perfect, floor) = 100 · (clamp(v, floor, perfect) − floor) / (perfect − floor)`
     * (`scoring-spec.md` §2.2), expressed on the **offset from the perfect point** so the locked
     * single-argument shape can carry it: `LIN_DECAY(v, perfect, floor) == decay(v − perfect, floor − perfect)`.
     *
     * [halfLife] is therefore the width of the decay band: the score is **100** at the measure's
     * perfect value and **0** one [halfLife] away from it. Use [Measure.decay], which applies the
     * shift and the saturation for you; a detector should never subtract [perfect] by hand.
     */
    data class Decay(val halfLife: Double) : MeasureBand() {
        init {
            require(halfLife > 0.0 && !halfLife.isNaN()) { "decay band must be wider than zero" }
        }

        override fun normalise(value: Double): Double {
            if (value.isNaN()) return 0.0
            return (100.0 * (1.0 - value / halfLife)).coerceIn(0.0, 100.0)
        }
    }

    /**
     * A two-point gate with no interpolation: **100** inside the tolerance window around [target],
     * **0** outside it (`scoring-spec.md` §2.3 `STEP`, the "third touch validates it" /
     * "close, not high" family, and `DELIVERY % unavailable` which must read as a hard zero).
     *
     * Readings that are genuinely intermediate (the book's `0 / 0.5 / 1` follow-through,
     * retest-held and OBV rules) are **not** expressed here: emit the 0–1 value with
     * [Measure.identity], which maps it through unchanged.
     */
    data class Exact(val target: Double, val tolerance: Double) : MeasureBand() {
        init {
            require(!target.isNaN()) { "exact target must be finite" }
            require(tolerance >= 0.0 && !tolerance.isNaN()) { "tolerance must not be negative" }
        }

        override fun normalise(value: Double): Double =
            if (value.isNaN() || kotlin.math.abs(value - target) > tolerance) 0.0 else 100.0
    }

    /**
     * `100` at [target], decaying linearly to `0` at [target] ± [tolerance].
     *
     * This is the "ratio where 1.0 is perfect and 0.5 is unacceptable" shape of `scoring-spec.md`
     * §2.2, expressed symmetrically around its target.
     */
    data class RatioEqual(val target: Double, val tolerance: Double) : MeasureBand() {
        init {
            require(!target.isNaN()) { "ratio target must be finite" }
            require(tolerance > 0.0 && !tolerance.isNaN()) { "ratio tolerance must be positive" }
        }

        override fun normalise(value: Double): Double {
            if (value.isNaN()) return 0.0
            val deviation = kotlin.math.abs(value - target)
            return (100.0 * (1.0 - deviation / tolerance)).coerceIn(0.0, 100.0)
        }
    }
}

/**
 * One weighted measurement from a detector, plus the primitive that normalises it.
 *
 * [weight] is **relative within its own factor** (`scoring-spec.md` §2.5): a geometry factor may
 * use 20/15/20/10/10/15 as `scoring-spec.md` §3 writes them, and the scorer renormalises by the
 * sum, so a detector never has to rescale by hand. A [weight] of `0` marks a measure that does
 * not apply to this pattern; its share is redistributed across the factor's other measures.
 *
 * @param value the raw measure, or — for a [MeasureBand.Decay] band — the **offset from the
 *   perfect point**, which [Measure.decay] computes. Non-negative and finite by construction:
 *   a negative or NaN measure is a detector bug and is rejected here rather than silently scored.
 */
data class Measure(
    val name: String,
    val value: Double,
    val weight: Int,
    val band: MeasureBand,
    val factor: ScoreFactor,
) {
    init {
        require(name.isNotBlank()) { "a measure needs a name for the UI to explain itself" }
        require(!value.isNaN() && !value.isInfinite()) {
            "measure '$name' has a non-finite value ($value); a measure must be a real reading"
        }
        require(value >= 0.0) {
            "measure '$name' has a negative value ($value); use MeasureBand.Decay to express a " +
                "measure that is better the smaller it gets"
        }
        require(weight >= 0) { "measure '$name' has a negative weight ($weight)" }
    }

    /** This measure's contribution to its factor, `0..100`. */
    fun normalised(): Double = band.normalise(value)

    /** `true` when the detector marked this measure inapplicable to this pattern. */
    val isApplicable: Boolean get() = weight > 0

    companion object {
        /** General form; prefer one of the named factories. */
        fun of(
            name: String,
            value: Double,
            weight: Int,
            band: MeasureBand,
            factor: ScoreFactor,
        ): Measure = Measure(name, value, weight, band, factor)

        /**
         * `LIN_LINEAR(v, lo, hi)` (`scoring-spec.md` §2.1). `invert = true` scores a measure that
         * is best when *small*.
         */
        fun linear(
            name: String,
            value: Double,
            weight: Int,
            factor: ScoreFactor,
            lo: Double,
            hi: Double,
            invert: Boolean = false,
        ): Measure = Measure(name, value, weight, MeasureBand.Linear(invert, lo, hi), factor)

        /**
         * `RATIO_SATURATING(v, k) = 100 · min(1, v/k)` (`scoring-spec.md` §2.4) for quantities
         * that grow without bound and saturate at 1 — volume multiples (`k = 3.0`) and penetration
         * (`k = 1.0`). Identical maths to a non-inverted [linear] over `0..k`, named separately
         * because the spec names it separately.
         */
        fun saturating(
            name: String,
            value: Double,
            weight: Int,
            factor: ScoreFactor,
            k: Double,
        ): Measure = Measure(name, value, weight, MeasureBand.Linear(false, 0.0, k), factor)

        /**
         * `LIN_DECAY(v, perfect, floor)` (`scoring-spec.md` §2.2), for a measure that is a
         * *violation* — bigger is worse.
         *
         * The band stores `floor − perfect` and the measure stores `max(0, v − perfect)`, which
         * is algebraically identical to the spec's two-anchor formula while keeping the locked
         * single-argument [MeasureBand.Decay] shape.
         */
        fun decay(
            name: String,
            value: Double,
            weight: Int,
            factor: ScoreFactor,
            perfect: Double,
            floor: Double,
        ): Measure {
            require(floor != perfect) { "decay measure '$name' needs floor != perfect" }
            val offset = kotlin.math.max(0.0, value - perfect)
            return Measure(name, offset, weight, MeasureBand.Decay(kotlin.math.abs(floor - perfect)), factor)
        }

        /** `STEP(v, target, tolerance)`: 100 inside the window, 0 outside (`scoring-spec.md` §2.3). */
        fun exact(
            name: String,
            value: Double,
            weight: Int,
            factor: ScoreFactor,
            target: Double,
            tolerance: Double,
        ): Measure = Measure(name, value, weight, MeasureBand.Exact(target, tolerance), factor)

        /** `100` at [target], decaying to `0` at [target] ± [tolerance]. */
        fun ratioEqual(
            name: String,
            value: Double,
            weight: Int,
            factor: ScoreFactor,
            target: Double,
            tolerance: Double,
        ): Measure = Measure(name, value, weight, MeasureBand.RatioEqual(target, tolerance), factor)

        /**
         * Identity: the value *is* the `0..100` reading. The spec's `0 / 0.5 / 1` rules (C3
         * follow-through, C4 retest held, C5 OBV sign, K2 next-bar confirmation) are emitted
         * through here so no invented intermediate band is needed.
         */
        fun identity(
            name: String,
            value: Double,
            weight: Int,
            factor: ScoreFactor,
        ): Measure = Measure(name, value.coerceIn(0.0, 1.0), weight, MeasureBand.Linear(false, 0.0, 1.0), factor)

        /** Boolean rule ("volume on the signal bar expanded"): `1` → 100, `0` → 0. */
        fun binary(
            name: String,
            hit: Boolean,
            weight: Int,
            factor: ScoreFactor,
        ): Measure = identity(name, if (hit) 1.0 else 0.0, weight, factor)

        /**
         * A measure that does not apply to this pattern: weight 0, so `scoring-spec.md` §6.1
         * redistributes its share instead of scoring it.
         */
        fun notApplicable(name: String, factor: ScoreFactor): Measure =
            Measure(name, 0.0, 0, MeasureBand.Linear(false, 0.0, 1.0), factor)
    }
}
