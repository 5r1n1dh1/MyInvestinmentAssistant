package com.mia.app.domain.pattern

import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.Category
import com.mia.app.domain.model.Direction
import com.mia.app.domain.model.Match
import com.mia.app.domain.model.MeasuredMove
import com.mia.app.domain.model.MatchScorePolicy
import com.mia.app.domain.model.PatternDetector
import com.mia.app.domain.model.Pivot
import com.mia.app.domain.model.Timeframe
import java.time.LocalDate

/**
 * The detector ↔ scorer seam (`docs/CONTRACTS.md` → "The detector ↔ scorer seam (locked)").
 *
 * **The rule the whole pattern engine rests on: a detector does not compute a score.** It emits
 * *weighted measures*; [FactorScorer] turns measures into a `ScoreBreakdown`. That is what keeps
 * all 12 patterns comparable and what makes the plan.md §11 P7 discrimination gate meaningful —
 * a detector that could assert its own score would make the number unfalsifiable.
 *
 * The split rule from `scoring-spec.md` §1.1: *a measure about the shape is GEOMETRY; about the
 * lines drawn through it is STRUCTURE; about what happened at or after the trigger is
 * CONFIRMATION; about the world outside the formation is CONTEXT.*
 */

/** The four scoring factors and their plan.md §6.3 weights. */
enum class ScoreFactor(val weight: Int, val label: String) {
    GEOMETRY(MatchScorePolicy.GEOMETRY_WEIGHT, "Geometry"),
    STRUCTURE(MatchScorePolicy.STRUCTURE_WEIGHT, "Structure"),
    CONFIRMATION(MatchScorePolicy.CONFIRMATION_WEIGHT, "Confirmation"),
    CONTEXT(MatchScorePolicy.CONTEXT_WEIGHT, "Context"),
    ;

    companion object {
        val ALL: List<ScoreFactor> = entries.toList()

        /** 100 — asserted by a test so a weight edit can never break the composition. */
        val TOTAL_WEIGHT: Int = entries.sumOf { it.weight }
    }
}

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

/**
 * A deduction applied **after** the four factors are summed (`scoring-spec.md` §7).
 *
 * [points] is the deduction as a **positive magnitude**, matching `PatternScoringPolicy.PEN_*`;
 * the score is the factor sum minus the deductions, clamped at 0.
 *
 * A penalty is a *failure mode the book names*, never a thirteenth pattern (`patterns-spec.md`
 * §7.0). Multiplicity is carried by the list: emitting the same id twice is how `scoring-spec.md`
 * §7.2's "×2 for detectors 1–5" and "max 2 trap events" are expressed.
 */
data class Penalty(val name: String, val points: Int, val reason: String) {
    init {
        require(name.isNotBlank()) { "a penalty needs an id" }
        require(points > 0) { "penalty '$name' must carry a positive point value, got $points" }
    }

    companion object {
        /** Build from the catalogue so the point value can never be inlined by mistake. */
        fun of(id: PenaltyId, reason: String): Penalty = Penalty(id.name, id.points, reason)

        /** Same id, a different piece of evidence — how a repeated id is expressed. */
        fun repeat(of: Penalty, reason: String): Penalty = of.copy(reason = reason)
    }
}

/**
 * The shared penalty catalogue (`patterns-spec.md` §19 ids, `scoring-spec.md` §7.1 magnitudes).
 *
 * Every magnitude is `[derived]` — the book supplies the *categories*, never the numbers — and
 * they are chosen so any single book-named failure mode can move a match down one tier and two
 * independent failures are enough to suppress it (`scoring-spec.md` §7.2).
 */
enum class PenaltyId(val points: Int) {
    WEAK_VOLUME(PatternScoringPolicy.PEN_WEAK_VOLUME),
    BULL_TRAP(PatternScoringPolicy.PEN_TRAP),
    BEAR_TRAP(PatternScoringPolicy.PEN_TRAP),
    FAILED_RETEST(PatternScoringPolicy.PEN_FAILED_RETEST),
    FAILED_PATTERN(PatternScoringPolicy.PEN_FAILED_PATTERN),
    FAILED_CONTINUATION(PatternScoringPolicy.PEN_FAILED_CONTINUATION),
    STRUCTURE_CONTRADICTS(PatternScoringPolicy.PEN_STRUCTURE_CONTRADICTS),
    CTX_MISMATCH(PatternScoringPolicy.PEN_CTX_MISMATCH),
    HTF_CONFLICT(PatternScoringPolicy.PEN_HTF_CONFLICT),
    VOLATILE_MARKET(PatternScoringPolicy.PEN_VOLATILE),
    LOW_LIQUIDITY(PatternScoringPolicy.PEN_LOW_LIQUIDITY),
    VOLUME_CONTRACTION_ABSENT(PatternScoringPolicy.PEN_VOLUME_CONTRACTION_ABSENT),
    NO_DIVERGENCE(PatternScoringPolicy.PEN_NO_DIVERGENCE),
    NO_PRIOR_TREND(PatternScoringPolicy.PEN_NO_PRIOR_TREND),
    RANGING_REGIME(PatternScoringPolicy.PEN_RANGING),
    EXHAUSTION(PatternScoringPolicy.PEN_EXHAUSTION),
    INTRADAY_SPIKE_ONLY(PatternScoringPolicy.PEN_INTRADAY_SPIKE),
    STALE_PATTERN(PatternScoringPolicy.PEN_STALE),
    NO_CONFIRMATION(PatternScoringPolicy.PEN_NO_CONFIRMATION),
    NO_FOLLOW_THROUGH(PatternScoringPolicy.PEN_NO_FOLLOW_THROUGH),
    WRONG_LOCATION(PatternScoringPolicy.PEN_WRONG_LOCATION),
    ;

    /** `CTX_MISMATCH` counts double for the candlestick family (`scoring-spec.md` §7.1). */
    val isContextMismatch: Boolean get() = this == CTX_MISMATCH

    /** `scoring-spec.md` §19.1: the candlestick family weights `CTX_MISMATCH` at ×2. */
    fun pointsFor(category: Category): Int =
        if (isContextMismatch && category == Category.CANDLESTICK) {
            PatternScoringPolicy.PEN_CTX_MISMATCH_CS
        } else {
            points
        }

    companion object {
        /** The candlestick weighted double, kept explicit for detector authors. */
        fun of(id: PenaltyId, category: Category, reason: String): Penalty =
            Penalty(id.name, id.pointsFor(category), reason)
    }
}

/**
 * Stable, collision-free pattern ids — the keys of the accuracy table (`plan.md` §6.1, D6).
 *
 * **Why ids cannot be derived from anything volatile.** `PatternOutcome.patternId` groups every
 * tracked outcome per stock **and** per pattern, and those rows must survive app updates,
 * process restarts and reinstalls. A `hashCode()`, an enum ordinal appended to later, or an
 * autoincrement row id would all break a stored accuracy history.
 *
 * **How stability is achieved.** The fourteen canonical v1 pattern names
 * (`patterns-spec.md` §20, including the two two-direction families) own fixed constants
 * `1..14`. Lookup is exact first, then on a *normalised* name — lowercase, alphanumerics only —
 * so a cosmetic rename of the display string ("Head and Shoulders" vs "Head & Shoulders") still
 * resolves to the same id. Anything else gets a 64-bit FNV-1a digest of the same normalised
 * name, forced into `>= 2^62` so it can never collide with a canonical id.
 *
 * Adding a v2 pattern therefore costs one new constant and never moves an existing id.
 */
object PatternIds {

    /** Canonical id range: `1..` [CANONICAL_NAMES].size. */
    const val FIRST_CANONICAL_ID: Long = 1L

    /** Custom-name ids are pushed above this so they can never collide with a canonical id. */
    const val CUSTOM_ID_BIT: Long = 1L shl 62

    private val CANONICAL: Map<String, Long> = linkedMapOf(
        "Bullish Engulfing" to 1L,
        "Bearish Engulfing" to 2L,
        "Hammer" to 3L,
        "Shooting Star" to 4L,
        "Morning Star" to 5L,
        "Evening Star" to 6L,
        "Head & Shoulders" to 7L,
        "Inverse Head & Shoulders" to 8L,
        "Double Top" to 9L,
        "Double Bottom" to 10L,
        "Ascending Triangle" to 11L,
        "Descending Triangle" to 12L,
        "Bull Flag" to 13L,
        "Bear Flag" to 14L,
    )

    private val BY_NORMALISED: Map<String, Long> = CANONICAL.mapKeys { normalise(it.key) }

    /** Canonical display names, in id order. */
    val CANONICAL_NAMES: List<String> = CANONICAL.keys.toList()

    /** `true` when [name] is one of the fourteen v1 pattern names. */
    fun isCanonical(name: String): Boolean = normalise(name) in BY_NORMALISED

    /**
     * The direction a pattern *asserts*, independent of its geometry.
     *
     * ### Why this exists
     * The regime gate (plan.md §6.4) needs a direction, but the candlestick family has **no
     * pivots** (a 1–3 bar formation) and **no measured move** (spec DD-14 — the book defines
     * measured moves only for chart patterns). Without this table `resolveDirection` returned
     * `null` for every candlestick match, so `regimeAligned` returned `null`, and the engine
     * downgraded every candlestick match from `PROJECT` to `RENDER` for a reason that had
     * nothing to do with the pattern's quality.
     *
     * These are the textbook directions — a bullish engulfing is a reversal of prior downward
     * pressure, a shooting star a reversal of prior upward pressure. A chart pattern is absent
     * here on purpose: its direction comes from its measured move, which is measured rather
     * than assumed.
     */
    private val DIRECTIONAL_BIAS: Map<String, Direction> = mapOf(
        "Bullish Engulfing" to Direction.UP,
        "Hammer" to Direction.UP,
        "Morning Star" to Direction.UP,
        "Bearish Engulfing" to Direction.DOWN,
        "Shooting Star" to Direction.DOWN,
        "Evening Star" to Direction.DOWN,
    )

    /** Textbook direction for [name], or `null` when it must be measured, not assumed. */
    fun directionalBias(name: String): Direction? = DIRECTIONAL_BIAS[normalise(name)]

    /**
     * The id for [name]. Stable for the lifetime of the repository: identical input ⇒ identical
     * id, in this process and in every later one.
     */
    fun of(name: String): Long {
        val key = normalise(name)
        BY_NORMALISED[key]?.let { return it }
        val digest = fnv1a64(key)
        return CUSTOM_ID_BIT or (digest and (CUSTOM_ID_BIT - 1L))
    }

    /** Lowercase, alphanumerics only — the form the canonical table is keyed by. */
    internal fun normalise(name: String): String =
        name.lowercase().filter { it.isLetterOrDigit() }

    private fun fnv1a64(text: String): Long {
        var hash = -0x340d631b7bdddcdbL // 14695981039346656037 as a signed Long
        for (ch in text) {
            hash = hash xor ch.code.toLong()
            hash *= 0x100000001b3L
        }
        return hash
    }
}

/**
 * Uniform `Match` construction (`docs/CONTRACTS.md`; `patterns-spec.md` §4 rule 8).
 *
 * Every detector emits its geometry as [measures] and calls this, so `Match.score` is always
 * `ScoreBreakdown.total` and never an ad-hoc number computed inside a detector
 * (`scoring-spec.md` §2.5, `CONTRACTS.md`).
 *
 * @param pivots **exactly** the pivots the detector used, in order — the UI draws the shape and
 *   `pattern_pivots` re-scores the match later without re-detecting (`patterns-spec.md` §4).
 * @param name defaults to [detector].name; the two-direction families (star, flag) pass their own.
 */
fun matchOf(
    detector: PatternDetector,
    candles: List<Candle>,
    pivots: List<Pivot>,
    measures: List<Measure>,
    measuredMove: MeasuredMove? = null,
    penalties: List<Penalty> = emptyList(),
    name: String = detector.name,
    category: Category = detector.category,
    timeframe: Timeframe = candles.firstOrNull()?.timeframe ?: Timeframe.D1,
): Match {
    val assessed = FactorScorer.assess(measures)
    val application = FactorScorer.apply(assessed.breakdown, penalties)
    return Match(
        patternId = PatternIds.of(name),
        name = name,
        score = application.breakdown.total,
        pivots = pivots,
        measuredMove = measuredMove,
        category = category,
        timeframe = timeframe,
        detectedOn = detectedDate(candles, pivots),
        breakdown = application.breakdown,
    )
}

/**
 * The date a match is stamped with: the **latest bar**, per `patterns-spec.md` DD-12 ("a `Match`
 * is re-emitted every day while the formation persists … with `detectedOn` = the latest bar").
 * Falls back to the newest pivot only when no candle was supplied.
 */
internal fun detectedDate(candles: List<Candle>, pivots: List<Pivot>): LocalDate =
    candles.lastOrNull()?.date ?: pivots.lastOrNull()?.date ?: LocalDate.MIN