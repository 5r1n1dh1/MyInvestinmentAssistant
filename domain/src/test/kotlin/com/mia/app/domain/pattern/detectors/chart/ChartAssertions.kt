package com.mia.app.domain.pattern.detectors.chart

import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.Direction
import com.mia.app.domain.model.Match
import com.mia.app.domain.model.MatchScorePolicy
import com.mia.app.domain.model.Pivot
import com.mia.app.domain.model.ScoreBreakdown
import com.mia.app.domain.pattern.FactorScorer
import com.mia.app.domain.pattern.PatternIds
import com.mia.app.domain.pattern.ProjectionGate
import com.mia.app.domain.pattern.ScoreFactor
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Assertions every detector in this package has to satisfy, in one place.
 *
 * These are `patterns-spec.md` §24's verification checklist made executable, so each detector test
 * states only what is specific to its own pattern and inherits the invariants from here.
 */
internal object ChartAssertions {

    /** §4 rule 8 + the plan.md §12 property test: the reported pivots are the input's own elements. */
    fun pivotsAreRealElements(match: Match, input: List<Pivot>) {
        for (pivot in match.pivots) {
            assertTrue(input.contains(pivot), "${match.name} reported a pivot that was not an input: $pivot")
        }
        assertEquals(match.pivots.map { it.barIndex }.sorted(), match.pivots.map { it.barIndex })
    }

    /** §4 rule 8: `Match.pivots` is exactly the roles the detector used, oldest first. */
    fun pivotsAreOrderedOldestFirst(match: Match) {
        val bars = match.pivots.map { it.barIndex }
        assertEquals(bars.sorted(), bars, "${match.name} must report its pivots oldest first")
    }

    /** plan.md §12 + §24 check 4: a measured move is never negative and sits on the right side. */
    fun measuredMoveIsOnTheCorrectSide(match: Match) {
        val measured = match.measuredMove ?: return
        assertTrue(measured.height >= 0.0, "${match.name}: height must be >= 0, was ${measured.height}")
        assertTrue(measured.height.isFinite(), "${match.name}: height must be finite")
        when (measured.direction) {
            Direction.UP ->
                assertTrue(
                    measured.projectedLevel > measured.triggerLevel,
                    "${match.name}: an UP projection must sit above its trigger",
                )

            Direction.DOWN ->
                assertTrue(
                    measured.projectedLevel < measured.triggerLevel,
                    "${match.name}: a DOWN projection must sit below its trigger",
                )
        }
    }

    /** `CONTRACTS.md`: `Match.score` is always `ScoreBreakdown.total`, never an ad-hoc number. */
    fun scoreIsTheBreakdownTotal(match: Match) {
        val breakdown = assertNotNull(match.breakdown, "${match.name}: a detector must emit a breakdown")
        assertEquals(breakdown.total, match.score, "${match.name}: score must be the breakdown total")
        assertTrue(match.score in 0..100, "${match.name}: score ${match.score} out of range")
    }

    /** `CONTRACTS.md`: all four factors must carry at least one applicable measure. */
    fun everyFactorIsScored(candidate: ChartCandidate) {
        for (factor in ScoreFactor.ALL) {
            assertTrue(
                candidate.measures.any { it.factor == factor && it.isApplicable },
                "${candidate.name}: ${factor.label} has no applicable measure, so the engine would " +
                    "read it as 0 with a recorded reason",
            )
        }
        assertTrue(
            FactorScorer.assess(candidate.measures).isFullyScored,
            "${candidate.name}: some factor scored nothing at all",
        )
    }

    /** §4 / decision D6: the display name is a canonical key of the accuracy table. */
    fun nameIsCanonical(name: String) {
        assertTrue(PatternIds.isCanonical(name), "'$name' is not a PatternIds canonical name")
    }

    /**
     * `scoring-spec.md` §8, as it stands after the projection-gate re-derivation: the **score**
     * decides show-or-hide, and the **distance gate** decides project-or-render.
     *
     * [candles] rather than a bare bar count, because the gate measures the projected level against
     * the series' own last close — the same numbers `PatternEngine` hands it in production. Passing
     * a synthetic `true` for alignment keeps the assertion about one variable at a time.
     */
    fun verdictIsConsistent(match: Match, candles: List<Candle>) {
        val barCount = candles.size
        val projectable = ProjectionGate.admits(
            match,
            candles.last().close,
            match.timeframe,
            barCount,
            regimeAligned = true,
        )
        val verdict = MatchScorePolicy.verdict(
            match.score,
            match.timeframe,
            barCount,
            regimeAligned = true,
            projectable = projectable,
        )
        when {
            match.score < MatchScorePolicy.RENDER_THRESHOLD ->
                assertEquals(MatchScorePolicy.Verdict.SUPPRESS, verdict)

            projectable -> assertEquals(MatchScorePolicy.Verdict.PROJECT, verdict)
            else -> assertEquals(MatchScorePolicy.Verdict.RENDER, verdict)
        }
    }

    /** A breakdown pinned to four exact factor readings. */
    fun breakdownIs(match: Match, geometry: Int, structure: Int, confirmation: Int, context: Int) {
        assertEquals(ScoreBreakdown(geometry, structure, confirmation, context), match.breakdown)
    }

    /** One candidate out of many, with a message that names the pattern and the series. */
    fun single(candidates: List<ChartCandidate>, name: String): ChartCandidate {
        assertEquals(1, candidates.size, "$name: expected exactly one formation, got ${candidates.size}")
        return candidates.single()
    }
}

/** The 0–100 reading of one measure, i.e. what the scorer actually uses. */
internal fun ChartCandidate.reading(name: String): Double =
    measures.first { it.name == name }.normalised()

/** The raw measure value. */
internal fun ChartCandidate.measure(name: String): Double =
    measures.first { it.name == name }.value

/** The penalty ids a candidate carries, in order. */
internal fun ChartCandidate.penaltyNames(): List<String> = penalties.map { it.name }