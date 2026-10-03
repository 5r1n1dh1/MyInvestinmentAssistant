package com.mia.app.domain.pattern

import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.Direction
import com.mia.app.domain.model.Pivot
import com.mia.app.domain.model.PivotKind

import com.mia.app.domain.model.ProjectedPath
import com.mia.app.domain.model.Source
import com.mia.app.domain.model.Timeframe
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * plan.md §12's measured-move property tests plus the projection/breakout semantics of §6.6.
 */
class ProjectionTest {

    @Test
    fun `measuredMoveHeightIsNeverNegative`() {
        // Property, over every argument order: a detector that passes its arguments the wrong way
        // round must still produce a height it can draw.
        val levels = listOf(0.0, 100.0, 1036.470, 1_200.0, -5.0)
        val extremes = listOf(0.0, 99.5, 1_200.0, 805.588, 1e6)
        for (level in levels) {
            for (extreme in extremes) {
                assertTrue(MeasuredMoveCalculator.heightAbove(level, extreme) >= 0.0)
                assertTrue(MeasuredMoveCalculator.heightBelow(level, extreme) >= 0.0)
            }
        }
        assertEquals(0.0, MeasuredMoveCalculator.heightAbove(100.0, 100.0), 1e-12)
    }

    @Test
    fun `headAndShouldersReproducesTheBooksOwnMeasuredMove`() {
        // patterns-spec.md §12.4 / §12.7: neckline through T1(74, 1000) and T2(108, 1020),
        // head at (92, 1200), breakout close 995 at bar 136.
        val neckline = lineThrough(pivot(74, 1_000.0, PivotKind.LOW), pivot(108, 1_020.0, PivotKind.LOW))
        assertEquals(0.5882352941, neckline.slopePerBar, 1e-9)
        assertEquals(1_010.588, neckline.at(92), 1e-3)

        val move = MeasuredMoveCalculator.fromBoundary(
            direction = Direction.DOWN,
            boundary = neckline,
            extremeBar = 92,
            extremePrice = 1_200.0,
            breakoutBar = 136,
            breakoutClose = 995.0,
        )
        assertEquals(189.412, move.height, 0.001)
        assertEquals(1_036.470, move.triggerLevel, 0.001)
        assertEquals(805.588, move.projectedLevel, 0.001)
        // The book's own arithmetic: 1200 − 1000 = 200; 1000 − 200 = 800.
        assertEquals(Direction.DOWN, move.direction)
        assertTrue(move.projectedLevel < move.triggerLevel, "a DOWN geometry lands below its trigger")
    }

    @Test
    fun `inverseHeadAndShouldersProjectsUpward`() {
        // patterns-spec.md §13: neckline(60) = 605, head 470 ⇒ height 135; breakout 640 at bar 104.
        val neckline = lineThrough(pivot(44, 600.0, PivotKind.HIGH), pivot(76, 610.0, PivotKind.HIGH))
        val move = MeasuredMoveCalculator.fromBoundary(
            direction = Direction.UP,
            boundary = neckline,
            extremeBar = 60,
            extremePrice = 470.0,
            breakoutBar = 104,
            breakoutClose = 640.0,
        )
        assertEquals(135.0, move.height, 0.001)
        assertEquals(618.75, move.triggerLevel, 0.001)
        assertEquals(775.0, move.projectedLevel, 0.001)
        assertTrue(move.projectedLevel > move.triggerLevel, "an UP geometry lands above its trigger")
    }

    @Test
    fun `doubleTopReproducesTheBooksOwnNumbers`() {
        // §14.4: "Peak ₹1,200, Support ₹1,050, Difference ₹150 … Estimated target ₹900."
        val move = MeasuredMoveCalculator.fromHorizontal(
            direction = Direction.DOWN,
            level = 1_050.0,
            extremePrice = 1_200.0,
            breakoutBar = 80,
            breakoutClose = 1_035.0,
        )
        assertEquals(150.0, move.height, 1e-9)
        assertEquals(1_050.0, move.triggerLevel, 1e-9)
        assertEquals(885.0, move.projectedLevel, 1e-9, "measured from the breakdown close, per the book")
    }

    @Test
    fun `triangleUsesTheUniversalConstructionBecauseTheBookGivesNone`() {
        // §16.4 / DD-4.
        val move = MeasuredMoveCalculator.triangle(
            direction = Direction.UP,
            flatSide = 1_000.6667,
            oppositeExtreme = 900.0,
            breakoutClose = 1_015.0,
        )
        assertEquals(100.6667, move.height, 1e-3)
        assertEquals(1_000.6667, move.triggerLevel, 1e-3)
        assertEquals(1_115.667, move.projectedLevel, 1e-3)
    }

    @Test
    fun `flagpoleProjectsThePoleLengthFromTheBreakout`() {
        // §18.4: "Flagpole ₹100, Breakout ₹500, Estimated target ₹600."
        val move = MeasuredMoveCalculator.flagpole(
            direction = Direction.UP,
            baseBar = 20,
            basePrice = 500.0,
            poleTopPrice = 620.0,
            triggerLevel = 602.0,
            breakoutBar = 70,
            breakoutClose = 632.0,
        )
        assertEquals(120.0, move.height, 1e-9)
        assertEquals(602.0, move.triggerLevel, 1e-9)
        assertEquals(752.0, move.projectedLevel, 1e-9)
        assertEquals(600.0, 500.0 + 100.0, "the book's own check")
    }

    @Test
    fun `aBearFlagProjectsDownward`() {
        val move = MeasuredMoveCalculator.flagpole(
            direction = Direction.DOWN,
            baseBar = 20,
            basePrice = 620.0,
            poleTopPrice = 500.0,
            triggerLevel = 598.0,
            breakoutBar = 70,
            breakoutClose = 588.0,
        )
        assertEquals(120.0, move.height, 1e-9)
        assertEquals(468.0, move.projectedLevel, 1e-9)
        assertTrue(move.projectedLevel < move.triggerLevel)
    }

    // ------------------------------------------------------------------------ projected path

    @Test
    fun `theDirectionalPathIsBetweenOneAndFiveBarsWhateverIsAsked`() {
        val candles = walk(40)
        for (requested in -5..12) {
            val path = ProjectionBuilder.directionalPath(candles, Direction.UP, horizonBars = requested)
            assertTrue(path.horizonBars in 1..5, "horizon $requested gave ${path.horizonBars}")
            assertEquals(path.horizonBars, path.points.size)
            assertEquals(ProjectionLabels.PATH_LABEL, path.label)
        }
    }

    @Test
    fun `theDirectionalPathMovesInTheRequestedDirectionAndCarriesNoLevel`() {
        val candles = walk(40)
        val up = ProjectionBuilder.directionalPath(candles, Direction.UP, stepPerBar = 2.0)
        val down = ProjectionBuilder.directionalPath(candles, Direction.DOWN, stepPerBar = 2.0)
        val lastClose = candles.last().close

        assertEquals(listOf(1.0, 2.0, 3.0, 4.0, 5.0).map { lastClose + 2.0 * it }, up.points.map { it.second })
        assertEquals(listOf(1.0, 2.0, 3.0, 4.0, 5.0).map { lastClose - 2.0 * it }, down.points.map { it.second })
        // A path is directional: it attaches no price level, which is what keeps it distinct from
        // the measured move.
        assertEquals(ProjectionBuilder.MAX_HORIZON_BARS, up.horizonBars)
    }

    @Test
    fun `theDirectionalPathDatesAdvanceOneBarPerPoint`() {
        val candles = walk(30)
        val path = ProjectionBuilder.directionalPath(candles, Direction.UP, horizonBars = 3)
        val dates = path.points.map { it.first }
        assertEquals(3, dates.distinct().size)
        for (i in 1 until dates.size) assertTrue(dates[i].isAfter(dates[i - 1]))
    }

    @Test
    fun `thePathLabelMatchesTheModelDefaultSoTheUiCannotDrift`() {
        assertEquals(ProjectedPath(emptyList(), 0).label, ProjectionLabels.PATH_LABEL)
    }

    @Test
    fun `anEmptyCandleListYieldsAnEmptyPathRatherThanACrash`() {
        val path = ProjectionBuilder.directionalPath(emptyList(), Direction.UP)
        assertTrue(path.points.isEmpty())
        assertEquals(ProjectionBuilder.MAX_HORIZON_BARS, path.horizonBars)
    }

    // ---------------------------------------------------------------------- breakout alerts

    @Test
    fun `theBreakoutFiresOnTheRightBarAndNotOneBarEarly`() {
        val level = 1_000.0
        val buffer = BreakoutEvaluator.bufferPct(Timeframe.D1)
        // Closes: … 1010 (already above), then a decisive close at bar 5.
        val closes = listOf(990.0, 995.0, 1_001.0, 1_002.0, 999.0, 1_020.0, 1_030.0)
        val candles = closes.mapIndexed { i, close -> bar(i, close) }

        val atFour = candles.take(5) // bars 0..4: the 1_001/1_002 pokes are not decisive
        val early = BreakoutEvaluator.evaluate(atFour, level, Direction.UP, "neckline")
        assertTrue(!early.fired, "an intraday poke above the level is not a breakout")
        assertEquals(-1, early.barsSinceFired)

        val atFive = candles.take(6)
        val fired = BreakoutEvaluator.evaluate(atFive, level, Direction.UP, "neckline")
        assertTrue(fired.fired)
        assertEquals(0, fired.barsSinceFired, "it fired on the latest bar")
        assertEquals("Close above the neckline", fired.description)
        assertEquals(level, fired.level)

        val later = BreakoutEvaluator.evaluate(candles, level, Direction.UP, "neckline")
        assertTrue(later.fired)
        assertEquals(1, later.barsSinceFired)
    }

    @Test
    fun `aCloseInsideTheBufferIsNotABreakout`() {
        val buffer = BreakoutEvaluator.bufferPct(Timeframe.D1)
        assertEquals(0.5, buffer, 1e-12)
        assertEquals(0.75, BreakoutEvaluator.bufferPct(Timeframe.W1), 1e-12)

        // Needs a close above 1005.00 on D1.
        assertTrue(!BreakoutEvaluator.isBreakoutClose(1_004.0, 1_000.0, Direction.UP, buffer))
        assertTrue(BreakoutEvaluator.isBreakoutClose(1_005.1, 1_000.0, Direction.UP, buffer))
        // Needs a close below 995.00 on D1.
        assertTrue(!BreakoutEvaluator.isBreakoutClose(995.5, 1_000.0, Direction.DOWN, buffer))
        assertTrue(BreakoutEvaluator.isBreakoutClose(994.9, 1_000.0, Direction.DOWN, buffer))
    }

    @Test
    fun `onlyClosingPricesCountAnIntradaySpikeIsNeverABreakout`() {
        val level = 1_000.0
        val buffer = BreakoutEvaluator.bufferPct(Timeframe.D1)
        val spike = bar(0, 1_000.0).copy(high = 1_050.0)
        assertTrue(
            !BreakoutEvaluator.isBreakoutClose(spike.close, level, Direction.UP, buffer),
            "\"price trades at ₹810 is not a breakout\" [Ch.18-5]",
        )
        assertEquals(null, BreakoutEvaluator.firstBreakoutBar(listOf(spike), 0, level, Direction.UP, buffer))
    }

    @Test
    fun `theTriggerFormCarriesTheSameAnswerAsTheState`() {
        val candles = listOf(bar(0, 990.0), bar(1, 1_020.0))
        val state = BreakoutEvaluator.evaluate(candles, 1_000.0, Direction.UP, "neckline")
        val trigger = BreakoutEvaluator.trigger(candles, 1_000.0, Direction.UP, "neckline")
        assertEquals(state.fired, trigger.fired)
        assertEquals(state.level, trigger.level)
        assertEquals(state.direction, trigger.direction)
        assertEquals(state.description, trigger.description)
        assertTrue(trigger.fired)
    }

    @Test
    fun `anEmptySeriesNeverFires`() {
        val state = BreakoutEvaluator.evaluate(emptyList(), 1_000.0, Direction.DOWN, "confirmation level")
        assertTrue(!state.fired)
        assertEquals(-1, state.barsSinceFired)
        assertEquals("Close below the confirmation level", state.description)
    }

    // ----------------------------------------------------------------------------- helpers

    private fun pivot(barIndex: Int, price: Double, kind: PivotKind) =
        Pivot(barIndex, LocalDate.of(2025, 1, 1).plusDays(barIndex.toLong()), price, kind, 0)

    private fun bar(i: Int, close: Double): Candle = Candle(
        symbol = "TEST",
        timeframe = Timeframe.D1,
        date = LocalDate.of(2025, 1, 1).plusDays(i.toLong()),
        open = close,
        high = close + 0.5,
        low = close - 0.5,
        close = close,
        volume = 1_000L,
        source = Source.NSE_BHAVCOPY,
    )

    private fun walk(count: Int): List<Candle> = List(count) { i ->
        val close = 100.0 + i * 0.5
        Candle(
            symbol = "TEST",
            timeframe = Timeframe.D1,
            date = LocalDate.of(2025, 1, 1).plusDays(i.toLong()),
            open = close - 0.2,
            high = close + 0.8,
            low = close - 0.8,
            close = close,
            volume = 1_000L,
            source = Source.NSE_BHAVCOPY,
        )
    }
}