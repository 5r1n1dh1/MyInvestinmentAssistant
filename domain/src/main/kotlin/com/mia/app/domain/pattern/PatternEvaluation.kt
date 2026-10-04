package com.mia.app.domain.pattern

import com.mia.app.domain.model.Match
import com.mia.app.domain.model.MatchScorePolicy
import com.mia.app.domain.model.Measure
import com.mia.app.domain.model.ScoreBreakdown
import com.mia.app.domain.model.Timeframe

/**
 * What happened to one pattern on one `(symbol, timeframe, window)`.
 *
 * ### Why four states and not a nullable match
 * The four states are the four *different reasons* a pattern may not appear on screen, and they
 * are the whole answer to "why did nothing match?":
 *
 * | State | Meaning | What the reader learns |
 * |---|---|---|
 * | [Matched] | scored at or above the render gate | the shape resembles the textbook |
 * | [NearMiss] | scored, but below the gate | how close it came, and which factor fell short |
 * | [CannotEvaluate] | blocked before scoring | *why* — too few bars, too few pivots |
 * | [NotEvaluated] | no detector exists | the app never looked, and says so |
 *
 * Collapsing any two of these is a lie of a different kind. A nullable `Match` would make
 * "scored 47" indistinguishable from "never looked", and a reader who cannot tell those apart
 * will conclude the app is either broken or hiding something.
 */
sealed interface EvaluationOutcome {

    /** The pattern name, so a caller can join an outcome back to its [PatternSpec] row. */
    val name: String

    /**
     * Scored at or above [MatchScorePolicy.RENDER_THRESHOLD] and kept by the engine.
     *
     * The score is shape fit only — never a probability (plan.md §6.1).
     */
    data class Matched(val match: Match) : EvaluationOutcome {
        override val name: String get() = match.name
        val score: Int get() = match.score
    }

    /**
     * Scored, but held back by the render gate.
     *
     * Carries the whole score, not just the number, because a bare 47 cannot answer the question:
     * geometry failing, structure failing and a penalty applied all produce the same total. The
     * measures and the weakest factor are what make it explainable.
     */
    data class NearMiss(
        override val name: String,
        val score: Int,
        val breakdown: ScoreBreakdown,
        /** The factor that dragged the total down, ties broken by weight. */
        val weakestFactor: ScoreFactor,
        /** The measures behind [weakestFactor], weakest first. Never empty for a scored candidate. */
        val measures: List<Measure>,
        /** The engine's own sentence, kept verbatim so both surfaces word it identically. */
        val reason: String,
    ) : EvaluationOutcome {
        init {
            require(measures.isNotEmpty()) {
                "a near-miss must carry its measures, or it is the unexplained number this " +
                    "type exists to replace"
            }
            require(score < MatchScorePolicy.RENDER_THRESHOLD) {
                "$name scored $score, which is not a near-miss — it is above the render gate"
            }
        }
    }

    /**
     * Blocked before a score could be computed.
     *
     * The [reason] must be specific enough to act on: "needs 8 pivots, this window has 4" tells the
     * reader to widen the window, whereas "not enough data" tells them nothing.
     */
    data class CannotEvaluate(
        override val name: String,
        val reason: String,
        /** Which constraint failed, so a UI can phrase the remedy rather than print the raw text. */
        val blocker: Blocker,
    ) : EvaluationOutcome {
        init {
            require(reason.isNotBlank()) { "a blocked pattern must explain itself" }
        }
    }

    /**
     * No detector exists for this pattern.
     *
     * Distinct from [CannotEvaluate] on purpose: this is a gap in the software, not a property of
     * the data, and the reader is told which.
     */
    data class NotEvaluated(override val name: String) : EvaluationOutcome

    /** Why a pattern could not be scored on this data. */
    enum class Blocker {
        /** Fewer bars than [com.mia.app.domain.model.MatchScorePolicy.minBars] requires. */
        NOT_ENOUGH_BARS,

        /** Fewer swing points than a structural detector needs to place a formation. */
        NOT_ENOUGH_PIVOTS,

        /**
         * The series cannot be read — no bars stored, or a parse that produced nothing usable.
         */
        NO_DATA,

        /**
         * Indicators were unavailable, so a factor could not be computed. Distinct from
         * `NOT_ENOUGH_BARS` because widening the window may not help.
         */
        INDICATORS_UNAVAILABLE,
    }
}

/**
 * One pattern's outcome, plus the window and timeframe it was measured over.
 *
 * The window is carried because the score is **only** true of the window that produced it. A
 * reader comparing two chips is comparing two different series, and a UI that shows the numbers
 * without showing the window would be quoting figures out of context.
 */
data class PatternEvaluation(
    val symbol: String,
    val timeframe: Timeframe,
    /** Bars in the analysed window. */
    val windowBars: Int,
    /**
     * `true` when the window was too short for the pivot detector and it fell back to a shallower
     * zigzag pass.
     *
     * Surfaced because it is a quality signal the reader cannot see on the chart: the shapes being
     * found are structurally shallower, so a match at 60 bars is not the same claim as one at 250.
     * Measured over 20 real large caps with window warm-up included: 13 of 20 use it at 60 bars,
     * 5 of 20 at 120, and 0 of 20 on a whole series.
     */
    val usedFallbackPivots: Boolean,
    val outcomes: List<EvaluationOutcome>,
) {
    private val byName: Map<String, EvaluationOutcome> = outcomes.associateBy { it.name }

    /** The outcome for [name], or `null` when the pattern is not in this evaluation at all. */
    fun outcomeFor(name: String): EvaluationOutcome? = byName[name]

    /** Patterns that cleared the render gate, best score first. */
    fun matched(): List<EvaluationOutcome.Matched> =
        outcomes.filterIsInstance<EvaluationOutcome.Matched>().sortedByDescending { it.score }

    /** Patterns that were scored and held back, best score first. */
    fun nearMisses(): List<EvaluationOutcome.NearMiss> =
        outcomes.filterIsInstance<EvaluationOutcome.NearMiss>().sortedByDescending { it.score }

    /**
     * Rows for every catalogue pattern, whether or not this evaluation says anything about it.
     *
     * A pattern with no outcome becomes [EvaluationOutcome.NotEvaluated] or
     * [EvaluationOutcome.CannotEvaluate], so the catalogue can always render a complete list rather
     * than showing only the patterns that happened to be scanned.
     */
    fun outcomesFor(patterns: List<PatternSpec> = PatternCatalog.all): List<EvaluationOutcome> =
        patterns.map { spec ->
            byName[spec.name] ?: if (spec.isEvaluated) {
                EvaluationOutcome.CannotEvaluate(
                    name = spec.name,
                    reason = "no candidate formed in this ${windowBars}-bar window",
                    blocker = EvaluationOutcome.Blocker.NOT_ENOUGH_BARS,
                )
            } else {
                EvaluationOutcome.NotEvaluated(spec.name)
            }
        }

    /** The single sentence a screen can show when nothing cleared the gate. */
    fun emptyStateSentence(): String? {
        if (matched().isNotEmpty()) return null
        val gate = MatchScorePolicy.RENDER_THRESHOLD
        val scored = nearMisses().size
        val blocked = outcomes.count { it is EvaluationOutcome.CannotEvaluate }
        val notEvaluated = outcomes.count { it is EvaluationOutcome.NotEvaluated }
        val parts = mutableListOf<String>()
        // The window is named in both branches, always. It is the one piece of context a reader
        // cannot infer from the sentence itself, and these scores are only true of the slice they
        // were measured over — so quoting the gate without the window quotes a number out of
        // context.
        val window = "in this $windowBars-bar window"
        if (scored > 0) {
            parts += "$scored pattern${plural(scored)} scored below $gate $window" +
                (if (blocked > 0) ", $blocked could not be scored" else "")
        } else {
            parts += "no pattern scored $gate or more $window"
        }
        if (notEvaluated > 0) {
            parts += "$notEvaluated more are not looked for by the app yet"
        }
        return parts.joinToString("; ") + "."
    }

    private fun plural(n: Int) = if (n == 1) "" else "s"
}