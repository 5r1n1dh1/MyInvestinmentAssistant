package com.mia.app.domain.pattern

import com.mia.app.domain.model.Category
import com.mia.app.domain.model.Direction
import com.mia.app.domain.model.Match
import com.mia.app.domain.model.MatchScorePolicy
import com.mia.app.domain.model.MeasuredMove
import com.mia.app.domain.model.Timeframe
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **The permanent guard against a gate nothing can satisfy.**
 *
 * ### The bug this class exists to make impossible again
 * `MatchScorePolicy.PROJECT_THRESHOLD = 70` gated the project-and-alert path — the app's headline
 * feature, the one that draws a projected level and fires a notification. Walk-forward over the
 * fixture, the highest score 875 candidates ever produced was **68**. The gate was unreachable, so
 * that path had never executed once.
 *
 * Nothing caught it, because **a gate nothing can cross is indistinguishable from a gate that
 * works**. Every unit test asked "given this score, what is the verdict?" and got a correct answer;
 * none asked "is there any input that gets a PROJECT?". A gate can therefore fail in the most
 * expensive way — silently, forever — unless something is required to *produce* a PROJECT verdict
 * from real data. That is what this file does.
 *
 * ### Why it goes through the engine rather than through the rule
 * `ProjectionGate.admits` on a hand-written [Match] would only prove the arithmetic works. The bug
 * lived in the wiring — the rule and the path that used it could disagree, and the wiring is where
 * it disagreed. So every count below comes from `PatternEngine.verdictFor`, the same function
 * `PatternEngine.run` applies to every candidate of every real scan, over the same walk-forward the
 * calibration table uses.
 */
class ProjectionGateSatisfiabilityTest {

    @Test
    fun `the distance gate is crossed by real candidates on real history`() {
        val candidates = ProjectionFixture.walk.candidates
        val projecting = candidates.filter { it.verdict == MatchScorePolicy.Verdict.PROJECT }
        val distances = candidates.mapNotNull { it.distancePercent }
        val lowest = distances.minOrNull()
        val highest = distances.maxOrNull()

        println(
            "projection gate: ${projecting.size}/${candidates.size} candidates projected " +
                "(${ProjectionFixture.detectors.size} detectors over " +
                "${ProjectionFixture.candlesBySymbol.size} symbols); distance range " +
                "${lowest?.let { "%.2f".format(it) }}%..${highest?.let { "%.2f".format(it) }}%",
        )
        println(
            "  by pattern: " + projecting.groupingBy { it.match.name }.eachCount()
                .entries.sortedByDescending { it.value }
                .joinToString { "${it.key}=${it.value}" },
        )

        assertTrue(
            candidates.isNotEmpty(),
            "the walk-forward produced no candidates at all, so this test would pass vacuously",
        )
        assertTrue(
            projecting.size >= MIN_PROJECTING_CANDIDATES,
            "only ${projecting.size} of ${candidates.size} real candidates produced a PROJECT verdict — " +
                "the gate is unreachable again, which is exactly the bug this test exists to catch. " +
                "Candidate distances ranged over the range printed above; DISTANCE_MAX is " +
                "ProjectionGate.DISTANCE_MAX.",
        )
    }

    @Test
    fun `the engine's own scan verdict fires at least once`() {
        // The stricter, end-to-end claim: not only do candidates cross the gate, a whole scan —
        // top-ranked match and all — reports PROJECT. One scan is the smallest number that can
        // honestly be called "the feature runs".
        val projectingWindows = ProjectionFixture.walk.candidates
            .filter { it.scanVerdict == MatchScorePolicy.Verdict.PROJECT }
            .map { it.symbol to it.projectedOn }
            .distinct()

        println("projection gate: ${projectingWindows.size} windows reported a PROJECT scan verdict")
        assertTrue(
            projectingWindows.isNotEmpty(),
            "no window of the fixture produced a PROJECT scan verdict — the projected-level path " +
                "still never executes end to end",
        )
    }

    @Test
    fun `the retired score threshold was unreachable and the score no longer decides`() {
        val candidates = ProjectionFixture.walk.candidates
        val highest = candidates.maxOf { it.match.score }

        // The finding that retired the score gate, pinned so it cannot be quietly forgotten. If this
        // ever fails the scorer has changed: re-derive the gate from a measurement rather than
        // restoring the score threshold.
        assertTrue(
            highest < LEGACY_PROJECT_THRESHOLD,
            "the highest match score in ${candidates.size} candidates is now $highest, at or above " +
                "the retired threshold of $LEGACY_PROJECT_THRESHOLD",
        )
        // And the score is no longer an input to the projection decision: some candidate below the
        // retired threshold projects, which under the old gate was arithmetically impossible.
        val projected = candidates.filter { it.verdict == MatchScorePolicy.Verdict.PROJECT }
        val lowestProjecting = projected.minOfOrNull { it.match.score } ?: Int.MAX_VALUE
        assertTrue(
            lowestProjecting < LEGACY_PROJECT_THRESHOLD,
            "the lowest-scoring candidate that projected scored $lowestProjecting; if none of them " +
                "sits below $LEGACY_PROJECT_THRESHOLD the score may still be deciding the outcome",
        )
    }

    // ---------------------------------------------------------------- the rule, on its own

    @Test
    fun `a tight level projects and a distant one does not, whatever the score`() {
        val tight = match(projectedLevel = 98.0, score = 61)
        val distant = match(projectedLevel = 70.0, score = 61)
        val perfectButDistant = match(projectedLevel = 70.0, score = 100)

        assertTrue(ProjectionGate.admits(tight, LAST_CLOSE, Timeframe.D1, BARS, regimeAligned = true))
        assertTrue(!ProjectionGate.admits(distant, LAST_CLOSE, Timeframe.D1, BARS, regimeAligned = true))
        assertEquals(
            ProjectionGate.Blocker.DISTANCE_TOO_FAR,
            ProjectionGate.blocker(perfectButDistant, LAST_CLOSE, Timeframe.D1, BARS, regimeAligned = true),
            "a textbook-perfect score is not a reason to project a level 30 % away",
        )
    }

    @Test
    fun `a formation with no measured move can never project`() {
        // Spec DD-14: a candlestick formation has no height, so there is no level to draw and none
        // to track. This has been true since `OutcomeTracker` refused to track one.
        val candlestick = match(projectedLevel = null, score = 99)
        assertEquals(
            ProjectionGate.Blocker.NO_MEASURED_MOVE,
            ProjectionGate.blocker(candlestick, LAST_CLOSE, Timeframe.D1, BARS, regimeAligned = true),
        )
        assertEquals(null, ProjectionGate.distance(candlestick, LAST_CLOSE))
        assertTrue(!ProjectionGate.admits(candlestick, LAST_CLOSE, Timeframe.D1, BARS, regimeAligned = true))
    }

    @Test
    fun `an undecided regime or too little history still blocks the projection`() {
        val tight = match(projectedLevel = 98.0, score = 61)
        assertEquals(
            ProjectionGate.Blocker.REGIME_NOT_ALIGNED,
            ProjectionGate.blocker(tight, LAST_CLOSE, Timeframe.D1, BARS, regimeAligned = null),
        )
        assertEquals(
            ProjectionGate.Blocker.REGIME_NOT_ALIGNED,
            ProjectionGate.blocker(tight, LAST_CLOSE, Timeframe.D1, BARS, regimeAligned = false),
        )
        assertEquals(
            ProjectionGate.Blocker.INSUFFICIENT_HISTORY,
            ProjectionGate.blocker(tight, LAST_CLOSE, Timeframe.D1, 59, regimeAligned = true),
        )
    }

    private fun match(projectedLevel: Double?, score: Int): Match = Match(
        patternId = 1L,
        name = "Probe",
        score = score,
        measuredMove = projectedLevel?.let {
            MeasuredMove(height = 100.0 - it, triggerLevel = 99.0, direction = Direction.DOWN, projectedLevel = it)
        },
        category = Category.REVERSAL,
        timeframe = Timeframe.D1,
        detectedOn = LocalDate.of(2025, 1, 1),
    )

    private companion object {
        /**
         * The retired score gate, written out rather than referenced.
         *
         * `MatchScorePolicy.PROJECT_THRESHOLD` is deprecated because it is both unreachable and
         * inverted; a test that still read it would be asserting against a policy the engine no
         * longer follows. The number is kept here as the historical record that it is.
         */
        const val LEGACY_PROJECT_THRESHOLD = 70

        /** The breakout close every probe level is measured from. */
        const val LAST_CLOSE = 100.0

        /** Comfortably past `MatchScorePolicy.minBars(D1)`. */
        const val BARS = 500

        /**
         * How many real candidates must project.
         *
         * Ten is not a tuned target: it is a floor any working gate clears by a wide margin (the
         * measurement put roughly a quarter of all candidates inside 5 %), chosen so this test
         * fails only when the gate has become *unsatisfiable* — the failure it was written for.
         */
        const val MIN_PROJECTING_CANDIDATES = 10
    }
}