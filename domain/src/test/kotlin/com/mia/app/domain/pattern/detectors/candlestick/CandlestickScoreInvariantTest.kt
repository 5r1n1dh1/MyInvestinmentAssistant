package com.mia.app.domain.pattern.detectors.candlestick

import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.MatchScorePolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * plan.md §12 property tests for the candlestick family, run over the **real** fixture.
 *
 * Three properties, each of which a broken detector/scorer seam would violate:
 *
 * 1. `0 <= score <= 100`, and every factor reading too — plan.md §12's own invariant.
 * 2. `Match.breakdown.total == Match.score` — the locked seam. A detector emits measures and never
 *    a score, so the breakdown and the score cannot legitimately disagree.
 * 3. `measuredMove == null` for detectors 1–5 (**DD-1**) — a candlestick match with a measured
 *    move would draw a projected level on the chart that `[Ch.17-2]`/`[Ch.17-3]` never define.
 */
class CandlestickScoreInvariantTest {

    @Test
    fun `everyScoreAndEveryFactorReadingLiesInRange`() {
        var seen = 0
        for (detector in allCandlestickDetectors()) {
            for (symbol in RealHistory.SYMBOLS) {
                for (hit in detectOn(detector, RealHistory.candles(symbol))) {
                    seen += 1
                    assertTrue(
                        hit.score in 0..100,
                        "${detector.name}/$symbol scored ${hit.score}",
                    )
                    val breakdown = hit.breakdown ?: error("no breakdown on ${detector.name}/$symbol")
                    assertTrue(breakdown.geometry in 0..100, "geometry out of range")
                    assertTrue(breakdown.structure in 0..100, "structure out of range")
                    assertTrue(breakdown.confirmation in 0..100, "confirmation out of range")
                    assertTrue(breakdown.context in 0..100, "context out of range")
                }
            }
        }
        assertTrue(seen > 0, "the real fixture must produce at least one candlestick match")
    }

    @Test
    fun `theScoreIsAlwaysTheBreakdownTotal`() {
        for (detector in allCandlestickDetectors()) {
            for (symbol in RealHistory.SYMBOLS) {
                for (hit in detectOn(detector, RealHistory.candles(symbol))) {
                    val breakdown = hit.breakdown ?: error("no breakdown on ${detector.name}/$symbol")
                    assertEquals(
                        hit.score,
                        breakdown.total,
                        "${detector.name}/$symbol: CONTRACTS.md - score IS ScoreBreakdown.total",
                    )
                }
            }
        }
    }

    @Test
    fun `noCandlestickMatchCarriesAMeasuredMoveOrPivots`() {
        for (detector in allCandlestickDetectors()) {
            for (symbol in RealHistory.SYMBOLS) {
                for (hit in detectOn(detector, RealHistory.candles(symbol))) {
                    assertNull(hit.measuredMove, "DD-1: detectors 1-5 never project a level")
                    assertTrue(hit.pivots.isEmpty(), "rule 7: a candlestick match carries no pivots")
                }
            }
        }
    }

    @Test
    fun `noMatchIsEverReturnedBelowTheSuppressThreshold`() {
        // patterns-spec.md rule 4: returning a suppressed match would let the UI render a pattern
        // the gate exists to hide.
        val fixtures: List<List<Candle>> = listOf(
            BullishEngulfingDetectorTest().bullishTextbook(),
            BearishEngulfingDetectorTest().textbook(),
            HammerFixture.textbook(),
            ShootingStarFixture.textbook(),
            StarFixture.morningStar(),
            StarFixture.eveningStar(),
        )
        for (fixture in fixtures) {
            for (detector in allCandlestickDetectors()) {
                for (hit in detectOn(detector, fixture)) {
                    assertTrue(
                        hit.score >= MatchScorePolicy.SUPPRESS_THRESHOLD,
                        "${detector.name} returned ${hit.name} at ${hit.score}",
                    )
                }
            }
        }
    }
}