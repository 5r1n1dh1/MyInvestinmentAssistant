package com.mia.app.domain.accuracy

import com.mia.app.domain.compliance.AdviceLanguage
import com.mia.app.domain.compliance.HonestyContract
import com.mia.app.domain.model.Category
import com.mia.app.domain.model.Direction
import com.mia.app.domain.model.Match
import com.mia.app.domain.model.MeasuredMove
import com.mia.app.domain.model.Timeframe
import com.mia.app.domain.technical.CandleResampler
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The track record measured on real NSE bars (plan.md §6.1, phase P9).
 *
 * Everything else in this package can be proven with hand-written candles. This file cannot: the
 * claim the product makes is a claim about the world, so it is measured on the world — 15 large
 * caps, 372 real sessions each, 2025-04-01 to 2026-10-01.
 *
 * The measured numbers are asserted rather than merely printed, and they are *not* flattering:
 * a level reached within five bars is a geometric event whose likelihood is dominated by how far
 * the level sits from price, not by any edge. That is the point of tracking it honestly.
 */
class RealHistorySimulationTest {

    private val simulation: RealHistorySimulation.SimulationResult by lazy { RealHistorySimulation.run() }

    // -----------------------------------------------------------------------------------
    // The fixture itself
    // -----------------------------------------------------------------------------------

    @Test
    fun `the real history is 41 symbols and 372 sessions for the full-length series`() {
        assertEquals(41, EodHistoryFixture.symbols().size)
        assertEquals(372, EodHistoryFixture.EXPECTED_FULL_BARS)
        // 41 keys, minus TATAMOTORSPV (no rows) and TATAMOTORS (stops at the demerger).
        assertEquals(39, EodHistoryFixture.symbolsWithFullHistory().size)
        // The 15 symbols the simulation walks must all be full-length, and a subset of the 39.
        assertTrue(RealHistorySimulation.SYMBOLS.size < EodHistoryFixture.symbolsWithFullHistory().size)
        assertTrue(EodHistoryFixture.symbolsWithFullHistory().containsAll(RealHistorySimulation.SYMBOLS))
        RealHistorySimulation.SYMBOLS.forEach { symbol ->
            assertEquals(372, EodHistoryFixture.candles(symbol).size, symbol)
        }

        val reliance = EodHistoryFixture.candles("RELIANCE")
        assertEquals(372, reliance.size)
        assertEquals(EodHistoryFixture.FIRST_DATE, reliance.first().date)
        assertEquals(EodHistoryFixture.LAST_DATE, reliance.last().date)

        // First and last bar of RELIANCE, exactly as published in the fixture.
        val first = reliance.first()
        assertEquals("2025-04-01", first.date.toString())
        assertEquals(1264.6, first.open)
        assertEquals(1277.9, first.high)
        assertEquals(1249.3, first.low)
        assertEquals(1252.6, first.close)
        assertEquals(12_099_648L, first.volume)

        val last = reliance.last()
        assertEquals("2026-10-01", last.date.toString())
        assertEquals(1167.7, last.close)
        // The NSE close of 2026-10-01 is also the value the P2 bhavcopy parser golden-checks.
        assertEquals(1167.7, last.close)

        assertTrue(reliance.all { it.isValid })
        assertTrue(reliance.all { it.deliveryPercent == null }, "the bhavcopy fixture carries no delivery data")
        assertTrue(reliance.map { it.date } == reliance.map { it.date }.sorted())
    }

    @Test
    fun `a symbol with no rows is an empty history, not a failure`() {
        // TATAMOTORS demerged on 2025-10-23 (plan.md §9); TATAMOTORSPV has no rows at all.
        assertEquals(140, EodHistoryFixture.candles("TATAMOTORS").size)
        assertEquals("2025-10-23", EodHistoryFixture.candles("TATAMOTORS").last().date.toString())
        assertEquals(0, EodHistoryFixture.candles("TATAMOTORSPV").size)

        // An empty history can never resolve a projection.
        val outcome = AccuracyTestData.outcome(symbol = "TATAMOTORSPV", projectedOn = EodHistoryFixture.FIRST_DATE)
        val evaluated = OutcomeTracker.evaluateAll(
            listOf(outcome),
            EodHistoryFixture.candlesBySymbol(),
            today = EodHistoryFixture.LAST_DATE,
        )
        assertEquals(listOf(outcome), evaluated)
    }

    // -----------------------------------------------------------------------------------
    // Invariants over the whole walk
    // -----------------------------------------------------------------------------------

    @Test
    fun `every projection is either settled with a verdict or still open, and nothing else`() {
        val result = simulation
        // Bars 60..370 of 372 on a 5-bar stride: 63 projections per symbol.
        assertEquals(15 * 63, result.outcomes.size)
        assertEquals(15 * 63, result.samples.size)

        assertTrue(result.outcomes.all { it.hit != null || !it.resolved })
        assertTrue(result.resolved.all { it.hit != null && it.actualHigh != null && it.actualLow != null })
        assertTrue(result.pending.all { it.hit == null })
        assertEquals(result.outcomes.size, result.resolved.size + result.pending.size)
    }

    @Test
    fun `hits plus misses equals the number of settled outcomes`() {
        val result = simulation
        val hits = result.resolved.count { it.hit == true }
        val misses = result.resolved.count { it.hit == false }

        assertEquals(result.resolved.size, hits + misses)
        assertEquals(15 * 62, hits + misses)
        assertEquals(
            15,
            result.pending.size,
            "the final projection of each series has no full horizon inside the fixture",
        )
        assertTrue(result.pending.all { it.symbol.isNotEmpty() })
    }

    @Test
    fun `no outcome was settled before its horizon had elapsed`() {
        val result = simulation
        result.resolved.forEach { outcome ->
            val candles = result.candlesBySymbol.getValue(outcome.symbol)
            val available = candles.count {
                it.date.isAfter(outcome.projectedOn) && !it.date.isAfter(result.today)
            }
            assertTrue(
                available >= outcome.horizonBars,
                "${outcome.symbol} ${outcome.patternName} ${outcome.projectedOn} settled with only $available bars",
            )
        }
    }

    @Test
    fun `open windows never reach the report`() {
        val result = simulation
        val report = HitRateCalculator.report(result.outcomes, today = result.today)

        assertEquals(result.outcomes.size, report.totalOutcomes)
        assertEquals(result.pending.size, report.unresolved)
        report.perSymbolAndPattern.forEach { cell ->
            val pendingHere = result.pending.count {
                it.symbol == cell.symbol && it.patternName == cell.patternName
            }
            val resolvedHere = result.resolved.count {
                it.symbol == cell.symbol && it.patternName == cell.patternName
            }
            assertEquals(resolvedHere, cell.samples, "unresolved cases leaked into ${cell.symbol}/${cell.patternName}")
            assertTrue(resolvedHere + pendingHere > 0)
        }
        assertEquals(15 * 62, report.perPattern.sumOf { it.samples })
        assertEquals(15 * 62, report.perSymbol.sumOf { it.samples })
    }

    @Test
    fun `the measured hit rate is the one the real bars produce`() {
        val result = simulation
        val hits = result.resolved.count { it.hit == true }

        // Measured on the fixture: 153 of 930 settled projections, i.e. 16.45%.
        assertEquals(153, hits)
        assertEquals(930, result.resolved.size)
        val rate = hits.toDouble() / result.resolved.size * 100.0
        assertEquals(16.451612903225806, rate, 1e-9)
        assertTrue(rate in 0.0..100.0)

        // Up and down projections behave identically: the number is not tracking an edge.
        val upLeg = result.resolved.filter { it.direction == Direction.UP }
        val downLeg = result.resolved.filter { it.direction == Direction.DOWN }
        val upRate = upLeg.count { it.hit == true } / upLeg.size.toDouble()
        val downRate = downLeg.count { it.hit == true } / downLeg.size.toDouble()
        assertEquals(464, upLeg.size)
        assertEquals(466, downLeg.size)
        assertEquals(76 / 464.0, upRate)
        assertEquals(77 / 466.0, downRate)
        assertTrue(abs(upRate - downRate) < 0.05, "up $upRate vs down $downRate")
    }

    @Test
    fun `the hit rate falls as the level moves further from price, so it is geometry and not skill`() {
        val result = simulation
        // Bucket the projections by how far the level sits from the close, in whole percent.
        // Only settled windows are counted, so a bucket is never mixed with a half-open one.
        val buckets = result.samples
            .filter { it.hasFullHorizon }
            .groupBy { it.distancePercent.toInt() }
            .filterKeys { it in 1..7 }
            .mapValues { (_, samples) -> samples.count { it.hit } / samples.size.toDouble() }

        assertEquals((1..7).toList(), buckets.keys.sorted())

        // Measured on the fixture, as fractions: 33.3%, 26.5%, 17.9%, 13.9%, 10.0%, 7.2%, 4.9%.
        val expected = listOf(0.3333, 0.2646, 0.1786, 0.1394, 0.1000, 0.0725, 0.0488)
        val byDistance = (1..7).map { distance -> buckets.getValue(distance) }
        for (i in byDistance.indices) {
            assertTrue(
                abs(byDistance[i] - expected[i]) < 0.0001,
                "bucket ${i + 1}% was ${byDistance[i]}, expected ${expected[i]}",
            )
        }
        for (i in 0 until byDistance.size - 1) {
            assertTrue(
                byDistance[i] > byDistance[i + 1],
                "hit rate must fall with distance, got $byDistance",
            )
        }
    }

    @Test
    fun `the direction base rate on this fixture is the ~50 percent of plan 6_1`() {
        // plan.md §6.1 measured 50.10% "always up" over 20,780 next-day returns on 20 caps.
        // The same control on this fixture's 15 symbols must land in the same place, which is the
        // reason the app refuses to call a match score a probability.
        var up = 0
        var total = 0
        RealHistorySimulation.SYMBOLS.forEach { symbol ->
            val candles = EodHistoryFixture.candles(symbol)
            for (i in 0 until candles.size - 1) {
                total++
                if (candles[i + 1].close > candles[i].close) up++
            }
        }

        assertEquals(5565, total)
        assertEquals(2723, up)
        assertTrue(up / total.toDouble() in 0.45..0.55, "base rate was ${up / total.toDouble()}")
    }

    @Test
    fun `evaluating the whole real history again changes nothing`() {
        val result = simulation
        val again = OutcomeTracker.evaluateAll(result.outcomes, result.candlesBySymbol, result.today)

        assertEquals(result.outcomes, again)
        assertEquals(result.outcomes.sortedBy { it.patternId }, again.sortedBy { it.patternId })
    }

    @Test
    fun `real bars without delivery percentages settle exactly like the same bars with them`() {
        val symbol = "RELIANCE"
        val plain = EodHistoryFixture.candles(symbol)
        val withDelivery = EodHistoryFixture.candlesWithDelivery(symbol)
        assertTrue(withDelivery.any { it.deliveryPercent != null })

        // Same projection, same verdict, whatever the legacy delivery file happened to carry.
        val bar = plain.first()
        val match = Match(
            patternId = 1L,
            name = "Bull flag",
            score = 80,
            measuredMove = MeasuredMove(
                height = 20.0,
                triggerLevel = bar.close,
                direction = Direction.UP,
                projectedLevel = bar.close + 20.0,
            ),
            category = Category.CONTINUATION,
            timeframe = Timeframe.D1,
            detectedOn = bar.date,
        )
        val outcome = OutcomeTracker.record(match, symbol, bar.date)

        val today = plain[10].date
        assertEquals(
            OutcomeTracker.evaluate(outcome, plain, today),
            OutcomeTracker.evaluate(outcome, withDelivery, today),
        )
        assertTrue(OutcomeTracker.evaluate(outcome, plain, today).resolved)
    }

    @Test
    fun `weekly bars can be tracked on their own horizon`() {
        // plan.md decision D1: 1W is a supported timeframe, so an outcome may be tracked on it.
        val weekly = CandleResampler.toWeekly(EodHistoryFixture.candles("TCS"))
        assertTrue(weekly.isNotEmpty())
        assertTrue(weekly.all { it.timeframe == Timeframe.W1 })

        val bar = weekly.first()
        val match = Match(
            patternId = 1L,
            name = "Head & shoulders",
            score = 75,
            measuredMove = MeasuredMove(
                height = 50.0,
                triggerLevel = bar.close,
                direction = Direction.DOWN,
                projectedLevel = bar.close - 50.0,
            ),
            category = Category.REVERSAL,
            timeframe = Timeframe.W1,
            detectedOn = bar.date,
        )
        val outcome = OutcomeTracker.record(match, "TCS", bar.date)

        // Daily bars must not settle a weekly outcome.
        val dailyOnly = OutcomeTracker.evaluate(outcome, EodHistoryFixture.candles("TCS"), weekly[6].date)
        assertFalse(dailyOnly.resolved)

        val settled = OutcomeTracker.evaluate(outcome, weekly, weekly[6].date)
        assertTrue(settled.resolved)
        assertEquals(5, OutcomeTracker.horizonWindow(outcome, weekly, weekly[6].date).size)
        assertNotNull(settled.hit)
    }

    @Test
    fun `the whole simulated history aggregates into a reportable per-pattern table`() {
        val result = simulation
        val report = HitRateCalculator.report(result.outcomes, today = result.today)

        // 15 stocks x 3 formations = 45 cells, each with 20 or 21 settled cases: all below the
        // 30-case gate, so every per-stock cell is withheld. That is the honest outcome of D6's
        // strict grouping on 18 months of data for one stock.
        assertEquals(45, report.perSymbolAndPattern.size)
        assertEquals(15, report.perSymbol.size)
        assertEquals(3, report.perPattern.size)
        report.perSymbolAndPattern.forEach { cell ->
            assertTrue(cell.samples in 20..21, "${cell.symbol}/${cell.patternName} has ${cell.samples}")
            assertFalse(cell.isReportable, "${cell.symbol}/${cell.patternName}")
            val text = HitRateCalculator.summaryText(cell)
            assertTrue(text.contains("${cell.samples}/30 samples"), text)
            assertFalse(text.contains("%"), "a withheld cell must not print a percentage: $text")
        }

        // Per symbol (62 cases) and per pattern (310) both clear the gate and print with n.
        report.perSymbol.forEach { cell ->
            assertEquals(62, cell.samples, cell.symbol!!)
            assertTrue(cell.isReportable)
            assertNotNull(cell.hitRate)
            assertTrue(cell.hitRate!! in 0.0..100.0)
            assertTrue(HitRateCalculator.summaryText(cell).contains("n=62"))
        }
        // Measured on the fixture: Bull flag 58/310, Ascending triangle 47/310, H&S 48/310.
        report.perPattern.forEach { cell ->
            assertEquals(310, cell.samples, cell.patternName!!)
            assertTrue(cell.isReportable)
            val text = HitRateCalculator.summaryText(cell)
            assertTrue(text.contains("n=310"))
            HonestyContract.requireNotProbabilityClaim(text)
        }
        assertEquals(
            listOf("Ascending triangle" to 47, "Bull flag" to 58, "Head & shoulders" to 48),
            report.perPattern.map { it.patternName to it.hits },
        )
    }

    @Test
    fun `the projector reads the real history back without a single probability claim`() {
        val result = simulation
        val asOf = result.today
        val patternName = RealHistorySimulation.PATTERNS.first()
        val match = Match(
            patternId = 0L,
            name = patternName,
            score = 72,
            measuredMove = MeasuredMove(
                height = 10.0,
                triggerLevel = 100.0,
                direction = Direction.UP,
                projectedLevel = 110.0,
            ),
            category = Category.CONTINUATION,
            timeframe = Timeframe.D1,
            detectedOn = asOf,
        )

        val context = AccuracyProjector.context(match, "RELIANCE", result.outcomes, asOf = asOf)

        // 21 settled cases of this formation on RELIANCE, projected strictly before asOf: below
        // the 30-case gate, so no per-stock percentage. The cross-stock pool (310 cases) is
        // offered instead, and it is the one that clears the gate.
        val expected = result.resolved.count { it.symbol == "RELIANCE" && it.patternName == patternName }
        assertEquals(21, expected)
        assertEquals(expected, context.priorSamples)
        assertFalse(context.isReportable)
        assertNull(context.hitRate)
        assertEquals(310, context.comparableOutcomes)
        assertEquals(18.709677419354838, context.comparableHitRate!!, 1e-9)
        assertTrue(context.evidence.contains("Not enough tracked cases yet on RELIANCE (21 of 30)."))
        assertTrue(context.evidence.contains("reached in 58 of 310 cases (18.7%)"))
        HonestyContract.requireNotProbabilityClaim(context.evidence)
        AdviceLanguage.requireClean(context.evidence)

        // Exactly one projection per stock is still open, and it is excluded from the sample.
        val open = result.pending.single { it.symbol == "RELIANCE" }
        assertTrue(RealHistorySimulation.PATTERNS.contains(open.patternName))
        val outcomesForCell = result.outcomes.filter {
            it.symbol == "RELIANCE" && it.patternName == open.patternName
        }
        val cell = HitRateCalculator.perStockAndPattern(outcomesForCell).single()
        assertEquals(21, outcomesForCell.size)
        assertEquals(20, cell.samples, "the open projection must not reach the sample")
        assertEquals(outcomesForCell.count { it.resolved }, cell.samples)
    }

    @Test
    fun `a real pending outcome stays pending however long the report is run`() {
        val result = simulation
        val pending = result.pending

        // The last bar of each series is projected with only one session left in the fixture, so
        // its horizon can never close on this data. Waiting must not manufacture a verdict.
        assertEquals(15, pending.size)
        assertTrue(pending.all { it.hit == null && !it.resolved })
        // What *has* been seen is still reported — as observation, not as a verdict.
        assertTrue(pending.all { it.actualHigh != null && it.actualLow != null })
        assertEquals(
            0,
            OutcomeTracker.dueForEvaluation(result.outcomes, result.candlesBySymbol, result.today).size,
            "a window with one bar left is not due",
        )
        assertEquals(
            0,
            OutcomeTracker.dueForEvaluation(
                result.outcomes,
                result.candlesBySymbol,
                result.today.plusDays(30),
            ).size,
            "and no amount of calendar time makes it due",
        )
        assertEquals(15, OutcomeTracker.pending(result.outcomes, result.today).size)
    }
}