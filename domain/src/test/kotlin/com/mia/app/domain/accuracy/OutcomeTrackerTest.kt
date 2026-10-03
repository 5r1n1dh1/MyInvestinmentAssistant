package com.mia.app.domain.accuracy

import com.mia.app.domain.model.Category
import com.mia.app.domain.model.Direction
import com.mia.app.domain.model.MeasuredMove
import com.mia.app.domain.model.Match
import com.mia.app.domain.model.OutcomeTrackerPolicy
import com.mia.app.domain.model.Timeframe
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Unit behaviour of the projection → outcome lifecycle (plan.md §6.1, §6.6, P9). */
class OutcomeTrackerTest {

    private val d = AccuracyTestData::date

    // -----------------------------------------------------------------------------------
    // record()
    // -----------------------------------------------------------------------------------

    @Test
    fun `a recorded match becomes a pending outcome carrying its own geometry`() {
        val match = AccuracyTestData.match(
            name = "Double bottom",
            score = 78,
            projectedLevel = 123.5,
            direction = Direction.UP,
            detectedOn = d(0),
            patternId = 42L,
        )

        val outcome = OutcomeTracker.record(match, "RELIANCE", d(0))

        assertEquals(42L, outcome.patternId)
        assertEquals("RELIANCE", outcome.symbol)
        assertEquals("Double bottom", outcome.patternName)
        assertEquals(Timeframe.D1, outcome.timeframe)
        assertEquals(d(0), outcome.projectedOn)
        assertEquals(78, outcome.score)
        assertEquals(Direction.UP, outcome.direction)
        assertEquals(123.5, outcome.projectedLevel)
        // Pending by construction: nothing has been observed yet.
        assertNull(outcome.hit)
        assertFalse(outcome.resolved)
        assertNull(outcome.actualHigh)
        assertNull(outcome.actualLow)
        assertEquals(OutcomeTrackerPolicy.DEFAULT_HORIZON_BARS, outcome.horizonBars)
    }

    @Test
    fun `direction and level come from the measured move, never from anything else`() {
        val down = OutcomeTracker.record(
            AccuracyTestData.match(projectedLevel = 88.0, direction = Direction.DOWN, height = 12.0),
            "TCS",
            d(3),
        )

        assertEquals(Direction.DOWN, down.direction)
        assertEquals(88.0, down.projectedLevel)
    }

    @Test
    fun `a candlestick match is rejected because it has no measured move to track`() {
        val match = AccuracyTestData.candlestickMatch(name = "Hammer", score = 80)

        val reason = OutcomeTracker.rejectionReason(match)
        assertNotNull(reason)
        assertTrue(reason.contains("no measured move"), "reason was: $reason")
        assertTrue(reason.contains("Hammer"), "the refusal must name the pattern: $reason")
        assertFalse(OutcomeTracker.isTrackable(match))

        val failure = assertFailsWith<IllegalArgumentException> {
            OutcomeTracker.record(match, "RELIANCE", d(0))
        }
        assertTrue(failure.message!!.contains("Hammer"))
    }

    @Test
    fun `a match the app never displayed is rejected so the sample cannot be padded`() {
        val suppressed = AccuracyTestData.match(score = 49)
        val rendered = AccuracyTestData.match(score = 50)

        assertTrue(OutcomeTracker.rejectionReason(suppressed)!!.contains("render threshold"))
        assertFalse(OutcomeTracker.isTrackable(suppressed))
        // The gate is exactly MatchScorePolicy.RENDER_THRESHOLD: 50 is tracked, 49 is not.
        assertTrue(OutcomeTracker.isTrackable(rendered))
        assertEquals(50, OutcomeTracker.MIN_SCORE_TO_TRACK)
    }

    @Test
    fun `a projection cannot be dated before the pattern it came from`() {
        val match = AccuracyTestData.match(detectedOn = d(5))

        assertFailsWith<IllegalArgumentException> { OutcomeTracker.record(match, "TCS", d(4)) }
        assertEquals(d(5), OutcomeTracker.record(match, "TCS", d(5)).projectedOn)
    }

    @Test
    fun `a blank symbol is refused because accuracy is keyed per stock`() {
        assertFailsWith<IllegalArgumentException> { OutcomeTracker.record(AccuracyTestData.match(), "  ", d(0)) }
    }

    @Test
    fun `a zero-bar horizon is refused`() {
        assertFailsWith<IllegalArgumentException> {
            OutcomeTracker.record(AccuracyTestData.match(), "TCS", d(0), horizonBars = 0)
        }
    }

    // -----------------------------------------------------------------------------------
    // evaluate()
    // -----------------------------------------------------------------------------------

    @Test
    fun `an upward projection hits when the level is reached inside the horizon`() {
        val outcome = AccuracyTestData.outcome(projectedOn = d(0), projectedLevel = 110.0, direction = Direction.UP)
        // Bars 1..5: the level is touched on the third one.
        val bars = listOf(105.0, 106.0, 109.5, 100.0, 101.0).let { closes ->
            closes.mapIndexed { i, c -> AccuracyTestData.bar(i + 1, c, pad = 0.5) }
        }

        val settled = OutcomeTracker.evaluate(outcome, bars, today = d(5))

        assertTrue(settled.resolved)
        assertEquals(true, settled.hit)
        assertEquals(110.0, settled.actualHigh)
        assertEquals(99.5, settled.actualLow)
    }

    @Test
    fun `a downward projection hits when the low falls to the level`() {
        val outcome = AccuracyTestData.outcome(projectedOn = d(0), projectedLevel = 90.0, direction = Direction.DOWN)
        val bars = listOf(95.0, 94.0, 93.0, 89.0, 92.0).mapIndexed { i, c ->
            AccuracyTestData.bar(i + 1, c, pad = 0.5)
        }

        val settled = OutcomeTracker.evaluate(outcome, bars, today = d(5))

        assertTrue(settled.resolved)
        assertEquals(true, settled.hit)
        assertEquals(88.5, settled.actualLow)
    }

    @Test
    fun `a level never reached resolves to a miss, not to nothing`() {
        val outcome = AccuracyTestData.outcome(projectedOn = d(0), projectedLevel = 110.0)
        val bars = AccuracyTestData.flatBars(count = 5, close = 100.0, startIndex = 1)

        val settled = OutcomeTracker.evaluate(outcome, bars, today = d(5))

        assertTrue(settled.resolved)
        assertEquals(false, settled.hit)
    }

    @Test
    fun `the projection bar's own high never counts as a hit`() {
        // The formation's high (130) is already on the chart when the level is projected, so it
        // cannot be evidence that the projection worked. Only bars after the projection bar count.
        val projectionBar = AccuracyTestData.bar(0, 128.0, pad = 2.0)
        val followingBars = AccuracyTestData.flatBars(count = 5, close = 100.0, startIndex = 1)
        val outcome = AccuracyTestData.outcome(projectedOn = d(0), projectedLevel = 110.0)

        val wholeSeries = listOf(projectionBar) + followingBars
        val settled = OutcomeTracker.evaluate(outcome, wholeSeries, today = d(5))

        assertTrue(settled.resolved)
        assertEquals(false, settled.hit, "the projection bar must be excluded from the window")
        assertEquals(101.0, settled.actualHigh, "actualHigh must ignore the projection bar")
        assertEquals(5, OutcomeTracker.horizonWindow(outcome, wholeSeries, d(5)).size)
        assertTrue(OutcomeTracker.horizonWindow(outcome, wholeSeries, d(5)).none { it.date == d(0) })
    }

    @Test
    fun `an open window publishes no verdict however good the first bar looks`() {
        val outcome = AccuracyTestData.outcome(projectedOn = d(0), projectedLevel = 110.0, horizonBars = 5)
        // Bar 1 alone would be a hit; the window has 2 of 5 bars, so nothing may be settled.
        val partial = listOf(AccuracyTestData.bar(1, 112.0, pad = 1.0), AccuracyTestData.bar(2, 105.0, pad = 1.0))

        val stillOpen = OutcomeTracker.evaluate(outcome, partial, today = d(5))

        assertFalse(stillOpen.resolved, "an open window must not resolve")
        assertNull(stillOpen.hit, "an open window must not publish a hit")
        assertNull(stillOpen.hit)
        assertFalse(OutcomeTrackerPolicy.isResolved(stillOpen, d(5)))
        // What was observed is still reported — as data, not as a verdict.
        assertEquals(113.0, stillOpen.actualHigh)
        assertEquals(104.0, stillOpen.actualLow)
        assertFalse(OutcomeTracker.isDue(outcome, partial, d(5)))
    }

    @Test
    fun `an outcome with no bars after the projection is left exactly as it was`() {
        val outcome = AccuracyTestData.outcome(projectedOn = d(0))

        assertEquals(outcome, OutcomeTracker.evaluate(outcome, emptyList(), today = d(9)))
        // A series that ends on the projection bar itself contributes nothing either.
        val onlyProjectionBar = listOf(AccuracyTestData.bar(0, 120.0, pad = 5.0))
        assertEquals(outcome, OutcomeTracker.evaluate(outcome, onlyProjectionBar, today = d(9)))
    }

    @Test
    fun `bars dated after today are never used, so a verdict cannot look ahead`() {
        val outcome = AccuracyTestData.outcome(projectedOn = d(0), projectedLevel = 110.0, horizonBars = 5)
        // Every bar is in the future relative to `today`, including one that would be a hit.
        val future = listOf(130.0, 131.0, 132.0, 133.0, 134.0).mapIndexed { i, c ->
            AccuracyTestData.bar(i + 1, c, pad = 0.0)
        }

        val asOfToday = OutcomeTracker.evaluate(outcome, future, today = d(1))

        assertFalse(asOfToday.resolved)
        assertNull(asOfToday.hit)
        // Only the one bar that existed on `today` may be looked at.
        assertEquals(130.0, asOfToday.actualHigh)

        // The same bars, evaluated on the day they exist, settle the outcome.
        val asOfLastBar = OutcomeTracker.evaluate(outcome, future, today = d(5))
        assertTrue(asOfLastBar.resolved)
        assertEquals(true, asOfLastBar.hit)
    }

    @Test
    fun `only the horizon is measured, not the rest of the history`() {
        val outcome = AccuracyTestData.outcome(projectedOn = d(0), projectedLevel = 110.0, horizonBars = 3)
        // The level is reached on bar 4 — one bar too late to count.
        val bars = listOf(100.0, 101.0, 102.0, 115.0, 116.0).mapIndexed { i, c ->
            AccuracyTestData.bar(i + 1, c, pad = 0.5)
        }

        val settled = OutcomeTracker.evaluate(outcome, bars, today = d(5))

        assertEquals(3, OutcomeTracker.horizonWindow(outcome, bars, d(5)).size)
        assertEquals(false, settled.hit)
        assertEquals(102.5, settled.actualHigh, "bar 4 is outside a 3-bar horizon")
    }

    @Test
    fun `evaluating the same outcome twice changes nothing`() {
        val outcome = AccuracyTestData.outcome(projectedOn = d(0), projectedLevel = 110.0)
        val bars = listOf(100.0, 111.0, 102.0, 103.0, 104.0).mapIndexed { i, c ->
            AccuracyTestData.bar(i + 1, c, pad = 0.5)
        }

        val once = OutcomeTracker.evaluate(outcome, bars, today = d(5))
        val twice = OutcomeTracker.evaluate(once, bars, today = d(5))
        val thrice = OutcomeTracker.evaluate(twice, bars, today = d(5))

        assertEquals(once, twice)
        assertEquals(twice, thrice)
    }

    @Test
    fun `more bars can never move a resolved verdict from hit to miss`() {
        val outcome = AccuracyTestData.outcome(projectedOn = d(0), projectedLevel = 110.0, horizonBars = 5)
        val horizon = listOf(100.0, 111.0, 102.0, 103.0, 104.0).mapIndexed { i, c ->
            AccuracyTestData.bar(i + 1, c, pad = 0.5)
        }
        val settled = OutcomeTracker.evaluate(outcome, horizon, today = d(5))
        assertEquals(true, settled.hit)

        // 40 more bars of pure downside, and the projection bar itself, which is far above.
        val muchLater = (1..40).map { i -> AccuracyTestData.bar(i, 20.0, pad = 0.5) } +
            listOf(AccuracyTestData.bar(0, 500.0, pad = 1.0))
        val reEvaluated = OutcomeTracker.evaluate(settled, muchLater, today = d(40))

        assertEquals(settled, reEvaluated)
        assertEquals(true, reEvaluated.hit)
        assertEquals(settled.actualHigh, reEvaluated.actualHigh)
    }

    @Test
    fun `candles from another symbol or timeframe never resolve an outcome`() {
        val outcome = AccuracyTestData.outcome(projectedOn = d(0), projectedLevel = 110.0)
        val ownBars = AccuracyTestData.flatBars(count = 5, close = 100.0, startIndex = 1)

        val otherSymbol = ownBars.map { it.copy(symbol = "OTHER") }
        val weekly = ownBars.map { it.copy(timeframe = Timeframe.W1) }

        assertEquals(outcome, OutcomeTracker.evaluate(outcome, otherSymbol, today = d(5)))
        assertEquals(outcome, OutcomeTracker.evaluate(outcome, weekly, today = d(5)))
        assertEquals(0, OutcomeTracker.horizonWindow(outcome, otherSymbol, d(5)).size)
        // The same bars under the right identity do settle it.
        assertTrue(OutcomeTracker.evaluate(outcome, ownBars, today = d(5)).resolved)
    }

    @Test
    fun `a one-bar horizon settles on the first bar`() {
        val outcome = AccuracyTestData.outcome(projectedOn = d(0), projectedLevel = 105.0, horizonBars = 1)
        val bars = listOf(AccuracyTestData.bar(1, 106.0, pad = 0.0))

        val settled = OutcomeTracker.evaluate(outcome, bars, today = d(1))

        assertTrue(settled.resolved)
        assertEquals(true, settled.hit)
        assertEquals(106.0, settled.actualHigh)
    }

    @Test
    fun `a series with no delivery percentages behaves exactly like any other`() {
        val outcome = AccuracyTestData.outcome(projectedOn = d(0), projectedLevel = 110.0)
        val bars = AccuracyTestData.flatBars(count = 5, close = 100.0, startIndex = 1)
        val withDelivery = AccuracyTestData.flatBars(count = 5, close = 100.0, startIndex = 1) {
            12.5
        }
        assertTrue(bars.all { it.deliveryPercent == null })

        assertEquals(
            OutcomeTracker.evaluate(outcome, bars, today = d(5)),
            OutcomeTracker.evaluate(outcome, withDelivery, today = d(5)),
        )
    }

    // -----------------------------------------------------------------------------------
    // evaluateAll() / pending() / dueForEvaluation()
    // -----------------------------------------------------------------------------------

    @Test
    fun `evaluateAll sorts deterministically and is idempotent`() {
        val outcomes = listOf(
            AccuracyTestData.outcome(symbol = "TCS", patternName = "Bull flag", projectedOn = d(4), patternId = 2L),
            AccuracyTestData.outcome(symbol = "RELIANCE", patternName = "Head & shoulders", projectedOn = d(1), patternId = 7L),
            AccuracyTestData.outcome(symbol = "RELIANCE", patternName = "Bull flag", projectedOn = d(1), patternId = 3L),
        )
        val bySymbol = mapOf(
            "TCS" to AccuracyTestData.flatBars(5, 100.0, startIndex = 5, symbol = "TCS"),
            "RELIANCE" to AccuracyTestData.flatBars(5, 100.0, startIndex = 2, symbol = "RELIANCE"),
        )

        val first = OutcomeTracker.evaluateAll(outcomes, bySymbol, today = d(9))
        val second = OutcomeTracker.evaluateAll(first, bySymbol, today = d(9))

        assertEquals(
            listOf("RELIANCE" to "Bull flag", "RELIANCE" to "Head & shoulders", "TCS" to "Bull flag"),
            first.map { it.symbol to it.patternName },
        )
        assertEquals(first, second)
        assertTrue(first.all { it.resolved })
    }

    @Test
    fun `an outcome whose symbol has no bars stays pending rather than resolving`() {
        val outcomes = listOf(AccuracyTestData.outcome(symbol = "GHOST", projectedOn = d(0)))
        val bySymbol = mapOf("RELIANCE" to AccuracyTestData.flatBars(10, 100.0))

        val evaluated = OutcomeTracker.evaluateAll(outcomes, bySymbol, today = d(9))

        assertEquals(outcomes, evaluated)
        assertFalse(evaluated.single().resolved)
        assertNull(evaluated.single().hit)
    }

    @Test
    fun `pending lists open windows and nothing projected in the future`() {
        val resolved = AccuracyTestData.outcome(projectedOn = d(1), resolved = true, hit = true)
        val open = AccuracyTestData.outcome(projectedOn = d(2), patternId = 2L)
        val future = AccuracyTestData.outcome(projectedOn = d(8), patternId = 3L)

        val pending = OutcomeTracker.pending(listOf(resolved, open, future), today = d(5))

        assertEquals(listOf(open), pending)
    }

    @Test
    fun `due for evaluation means the whole horizon exists, not merely that days passed`() {
        val outcome = AccuracyTestData.outcome(projectedOn = d(0), projectedLevel = 110.0)
        val bySymbol = mapOf("TEST" to AccuracyTestData.flatBars(10, 100.0))

        // 10 bars exist but `today` is the second of them: only 2 of 5 are in hand.
        assertTrue(OutcomeTracker.dueForEvaluation(listOf(outcome), bySymbol, today = d(2)).isEmpty())
        assertEquals(listOf(outcome), OutcomeTracker.pending(listOf(outcome), today = d(2)))

        assertEquals(listOf(outcome), OutcomeTracker.dueForEvaluation(listOf(outcome), bySymbol, today = d(9)))
        assertTrue(OutcomeTracker.isDue(outcome, bySymbol.getValue("TEST"), d(9)))
    }

    @Test
    fun `empty input is not an error`() {
        assertEquals(emptyList(), OutcomeTracker.evaluateAll(emptyList(), emptyMap()))
        assertEquals(emptyList(), OutcomeTracker.pending(emptyList(), LocalDate.now()))
        assertEquals(emptyList(), OutcomeTracker.dueForEvaluation(emptyList(), emptyMap(), LocalDate.now()))
    }

    @Test
    fun `the measured move that produced the outcome is the one that is judged`() {
        // Regression guard: the tracker must not recompute the level from the bars it sees.
        val match = Match(
            patternId = 1L,
            name = "Ascending triangle",
            score = 71,
            measuredMove = MeasuredMove(
                height = 9.0,
                triggerLevel = 100.0,
                direction = Direction.UP,
                projectedLevel = 111.11,
            ),
            category = Category.CONTINUATION,
            timeframe = Timeframe.D1,
            detectedOn = d(0),
        )
        val outcome = OutcomeTracker.record(match, "INFY", d(0))
        val bars = AccuracyTestData.flatBars(5, 109.0, startIndex = 1, symbol = "INFY")

        assertEquals(111.11, outcome.projectedLevel)
        val settled = OutcomeTracker.evaluate(outcome, bars, today = d(5))
        assertTrue(settled.resolved)
        assertEquals(false, settled.hit)
    }
}