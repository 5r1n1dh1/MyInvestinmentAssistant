package com.mia.app.domain.pattern.detectors.candlestick

import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.MatchScorePolicy
import com.mia.app.domain.model.Timeframe
import com.mia.app.domain.pattern.PatternEngine
import com.mia.app.domain.pattern.PatternScanOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * plan.md §12 property tests for the candlestick family, run over the **real** fixture.
 *
 * Four properties, each of which a broken detector/scorer seam would violate:
 *
 * 1. `0 <= score <= 100`, and every factor reading too — plan.md §12's own invariant.
 * 2. `Match.breakdown.total == Match.score` — the locked seam. A detector emits measures and never
 *    a score, so the breakdown and the score cannot legitimately disagree.
 * 3. `measuredMove == null` for detectors 1–5 (**DD-1**) — a candlestick match with a measured
 *    move would draw a projected level on the chart that `[Ch.17-2]`/`[Ch.17-3]` never define.
 * 4. A sub-threshold candidate is reported as **suppressed with a reason**, and never rendered —
 *    the gate from `patterns-spec.md` §4 rule 4, asserted where it is now enforced.
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

    /**
     * patterns-spec.md §4 rule 4 — **no match below the suppress threshold may be rendered** — is
     * enforced in one place now: the engine.
     *
     * This test previously asserted it at the *detector* level, i.e. that `detect()` returns
     * nothing below 50. That was stronger than the spec requires and it cost more than it
     * protected: a candidate scoring 47 was dropped by the detector and so never reached
     * `PatternScanResult.suppressed` either. Measured over 20 real large caps x 372 sessions, three
     * candlestick patterns — Shooting Star, Morning Star, Evening Star — appeared at no window
     * depth at all, because their sub-threshold candidates were destroyed before anything could
     * record them. "The app found nothing" and "the app could not explain what it found" became
     * indistinguishable.
     *
     * So the guarantee is now asserted where it is enforced and where it is what matters: a
     * sub-threshold candidate must be *visible as suppressed*, and never present in `matches`.
     */
@Test
    fun `a below-threshold candidate is reported as suppressed, never as a match`() {
        val detectors = allCandlestickDetectors()
        var sawSubThreshold = false

        // Real history, not a textbook fixture. A textbook formation is a *good* candlestick and
        // scores above the gate by construction, so it cannot exercise the sub-threshold path at
        // all — an earlier draft of this test used them and its own guard assertion caught that.
        // Real sessions are where the near-misses actually live.
        for (symbol in RealHistory.SYMBOLS) {
            val candles = RealHistory.candles(symbol)
            if (candles.size < MatchScorePolicy.minBars(Timeframe.D1)) continue

            val result = PatternEngine.run(
                candles = candles,
                timeframe = Timeframe.D1,
                detectors = detectors,
                options = PatternScanOptions(symbol = symbol),
            )

            // 1. The engine-level guarantee: nothing below the threshold is ever rendered.
            for (match in result.matches) {
                assertTrue(
                    match.score >= MatchScorePolicy.SUPPRESS_THRESHOLD,
                    "$symbol: ${match.name} was returned as a match at ${match.score}",
                )
            }

            // 2. Nothing is silently lost: every candidate the detectors produced is either a kept
            //    match or a named suppressed entry. Silence is the failure mode that made three
            //    patterns unanswerable.
            val produced = detectors.flatMap { detector ->
                detectOn(detector, candles).map { it.name }
            }.toSet()
            val accountedFor = result.matches.map { it.name }.toSet() +
                result.suppressed.map { it.name }.toSet()
            for (name in produced) {
                assertTrue(
                    name in accountedFor,
                    "$symbol: $name was produced by a detector but appears in neither matches " +
                        "nor suppressed — it was dropped without explanation",
                )
            }

            // 3. A candidate below the threshold is explained, never dropped.
            for (suppressed in result.suppressed) {
                if (suppressed.score < MatchScorePolicy.SUPPRESS_THRESHOLD) sawSubThreshold = true
                assertTrue(
                    suppressed.reason.isNotBlank(),
                    "$symbol: ${suppressed.name} was suppressed with no reason to show a reader",
                )
            }
        }

        // If real data ever stops producing a sub-threshold candlestick candidate this test would
        // pass without exercising the path it exists to protect, so assert coverage explicitly.
        assertTrue(
            sawSubThreshold,
            "no sub-threshold candlestick candidate was produced, so the suppressed path is untested",
        )
    }
}