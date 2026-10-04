package com.mia.app.domain.pattern

import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.Category
import com.mia.app.domain.model.Direction
import com.mia.app.domain.model.Match
import com.mia.app.domain.model.Measure
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
 * `1..14`, and those constants are **permanent**: see [RETIRED_CANONICAL_NAMES] for the six the
 * catalogue stopped claiming to evaluate while keeping their ids. Lookup is exact first, then on a *normalised* name — lowercase, alphanumerics only —
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

    /**
     * The six canonical names the catalogue no longer claims to evaluate — ids `1..6`, the
     * candlestick family.
     *
     * ### Why the ids stay and the rows move
     * `PatternOutcome.patternId` is a stored column, and an accuracy history has to survive this
     * decision: a candle detected under id `3` last month must still join to its own row today. So
     * the ids are **not** renumbered and the names are **not** removed from [CANONICAL_NAMES] —
     * renumbering would silently re-point every stored outcome at a different pattern, which is the
     * one failure a stability contract exists to prevent.
     *
     * What changed is the *claim*, not the *identity*: these rows moved from the catalogue's
     * evaluated list to its not-evaluated list. [CANONICAL_NAMES] therefore no longer equals the
     * evaluated set, and the difference is this list — so the two can still be reconciled instead
     * of drifting apart unnoticed.
     *
     * A detector for any of these would keep its existing id. Reinstating one is a catalogue-row
     * change and nothing more.
     */
    val RETIRED_CANONICAL_NAMES: List<String> = listOf(
        "Bullish Engulfing",
        "Bearish Engulfing",
        "Hammer",
        "Shooting Star",
        "Morning Star",
        "Evening Star",
    )

    /**
     * The canonical names the catalogue currently claims to evaluate: ids `7..14`.
     *
     * Distinct from [CANONICAL_NAMES] on purpose. The full list is an *identity* contract and this
     * is a *product* one, and conflating them is what would make the next retirement look like a
     * breaking change rather than a labelling decision.
     */
    val EVALUATED_CANONICAL_NAMES: List<String> =
        CANONICAL_NAMES.filterNot { it in RETIRED_CANONICAL_NAMES }

    /** `true` when [name] is one of the fourteen v1 pattern names, evaluated or retired. */
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
        // The measures that produced the breakdown. This is the last point at which they exist —
        // a detector emits them and never a score (docs/CONTRACTS.md, locked seam) — so dropping
        // them here is what used to make a near-miss unexplainable.
        measures = measures,
    )
}

/**
 * The date a match is stamped with: the **latest bar**, per `patterns-spec.md` DD-12 ("a `Match`
 * is re-emitted every day while the formation persists … with `detectedOn` = the latest bar").
 * Falls back to the newest pivot only when no candle was supplied.
 */
internal fun detectedDate(candles: List<Candle>, pivots: List<Pivot>): LocalDate =
    candles.lastOrNull()?.date ?: pivots.lastOrNull()?.date ?: LocalDate.MIN