package com.mia.app.domain.pattern.detectors.candlestick

import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.Category
import com.mia.app.domain.model.MatchScorePolicy
import com.mia.app.domain.model.Timeframe
import com.mia.app.domain.pattern.PatternIds
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Detector 2 — **Bearish Engulfing** (`patterns-spec.md` §7,
 * `[Ch.17-2 §Bearish Engulfing Pattern]`).
 *
 * §7 is "the exact mirror of §6 with every sign flipped and the context precondition inverted",
 * and the fixture below is therefore the exact mirror of
 * [BullishEngulfingDetectorTest]'s: identical magnitudes, opposite colours, and a rally instead of
 * a decline as the context. Both mirrors carry the same two spec defects — §6.7 and §7 each print
 * an `n−1` bar that contradicts the `S1` check they then assert — and the reasoning for the
 * corrections is documented there and in [bearishMirror].
 *
 * The one asymmetry the spec calls out, and which K5 handles without a special case: for this
 * pattern `deliveryVsAvg >= 1.00` **confirms**, because "high delivery + falling price = selling
 * pressure" `[Ch.21 §21.19]` — ownership changed hands on the way down.
 */
class BearishEngulfingDetectorTest {

    private val detector = BearishEngulfingDetector()

    @Test
    fun `detector name is the canonical accuracy-table key`() {
        assertEquals("Bearish Engulfing", detector.name)
        assertTrue(PatternIds.isCanonical(detector.name))
        assertEquals(2L, PatternIds.of(detector.name))
        assertEquals(Category.CANDLESTICK, detector.category)
    }

    @Test
    fun `textbookBearishEngulfing_matches_andRenders`() {
        val candles = textbook()

        // §7's own arithmetic: priorTrendPct(n−2) = (500 − 480)/480 = +4.167 %.
        val priorTrend = (candles[57].close - candles[37].close) / candles[37].close * 100.0
        assertEquals(4.167, priorTrend, 0.001, "§7 P3 priorTrendPct(n−2)")

        val match = detectOn(detector, candles).single()
        assertEquals("Bearish Engulfing", match.name)
        // G=100 (engulf 2.125 saturates), S=66, C=69, X=36, no penalties.
        assertEquals(75, match.score, "§7 textbook fixture, scored by FactorScorer")
        assertTrue(match.score >= MatchScorePolicy.RENDER_THRESHOLD)

        val breakdown = assertNotNull(match.breakdown)
        assertEquals(match.score, breakdown.total, "score IS ScoreBreakdown.total")
        assertEquals(100, breakdown.geometry, "§3.1 G3: engulf = 2.125 saturates RATIO_SATURATING(v, 1.0)")
        assertTrue(
            breakdown.confirmation > breakdown.structure && breakdown.confirmation > breakdown.context,
            "confirmation must be the strongest evidence factor, got $breakdown",
        )
        assertNull(match.measuredMove, "§7 / DD-1: no measured move for a candlestick pattern")
        assertTrue(match.pivots.isEmpty(), "§4 rule 7: no pivots")
    }

    @Test
    fun `textbookBearishEngulfing_reportsTheSpecGeometryInputs`() {
        val candles = textbook()
        val previous = candles[58]
        val signal = candles[59]
        assertEquals(4.00, abs(previous.close - previous.open), 0.0001, "body[n−1]")
        assertEquals(13.00, abs(signal.close - signal.open), 0.0001, "body[n]")
        val engulf = (previous.open - signal.close) / abs(previous.close - previous.open)
        assertEquals(2.125, engulf, 0.0001, "§3.1 G3 engulf, the mirror of detector 1")
        assertTrue(candles.lastIndex - 59 <= CandlestickPolicy.MAX_SIGNAL_AGE_BARS)
    }

    @Test
    fun `nearMissWithoutAProlongedRallyIsNotAMatchAtAll`() {
        // Exactly one rule broken: §7 P3, priorTrendPct(n−2) < +3.0 %. The context ramp's end close
        // is pulled back so the pair sits after a merely flat fortnight.
        val candles = textbook(closeNMinus2 = 492.00, contextHigh = 503.00, contextLow = 487.50)
        val priorTrend = (candles[57].close - candles[37].close) / candles[37].close * 100.0
        assertEquals(2.500, priorTrend, 0.001)
        assertTrue(priorTrend < CandlestickPolicy.riseThresholdPct(Timeframe.D1))
        assertTrue(
            detectOn(detector, candles).isEmpty(),
            "§7 P3 is a gate: a bearish engulfing with no prior rally must not match",
        )
    }

    @Test
    fun `nearMissOnThinVolumeScoresStrictlyLowerThanTheTextbook`() {
        val textbookScore = detectOn(detector, textbook()).single().score
        // §7 I4 / K1: the reversal closes below the prior low on volume under its own average.
        val thin = detectOn(detector, textbook(signalVolume = CandleSeries.DEFAULT_VOLUME))
        // Measured: 75 with rvol 3.00, 65 without it.
        assertEquals(65, thin.single().score, "same shape, no participation")
        assertTrue(thin.single().score < textbookScore)
    }

    @Test
    fun `theBullishMirrorIsNotABearishEngulfing`() {
        val mirror = bullishMirror()
        val signal = mirror[59]
        val previous = mirror[58]
        assertTrue(previous.close < previous.open, "mirror day 1 must be bearish")
        assertTrue(signal.close > signal.open, "mirror day 2 must be bullish")
        assertTrue(detectOn(detector, mirror).isEmpty(), "that is a bullish engulfing, not this one")
        assertEquals(1, detectOn(BullishEngulfingDetector(), mirror).size, "detector 1 claims it")
    }

    @Test
    fun `aShortHistoryNeverEmitsAScore`() {
        assertTrue(
            detectOn(detector, textbook().take(59)).isEmpty(),
            "§6.1 P1 / §24 check 1: below 60 daily bars the answer is 'not enough history'",
        )
    }

    // ---------------------------------------------------------------------------- fixture

    /**
     * The mirror of §6.7's formation — same magnitudes, both signs flipped, context a rally.
     *
     * | bars | content |
     * |------|---------|
     * | 0–36 | flat 480.00; bar 37 is the prior-trend reference |
     * | 38 | 481.00 |
     * | 39–56 | rising ramp 482.00 → 499.00 |
     * | 57 | `n−2` O=498.00 H=501.00 L=497.50 C=500.00 |
     * | 58 | `n−1` O=499.00 H=504.00 L=494.00 C=503.00 — bullish, body 4.00 |
     * | 59 | `n` O=503.50 H=504.00 L=488.00 C=490.50 — bearish engulfing, body 13.00 |
     * | 60 | `n+1` C=488.60 — §7 follow-through down |
     */
    internal fun textbook(
        closeNMinus2: Double = 500.00,
        signalVolume: Long? = null,
        contextHigh: Double = 501.00,
        contextLow: Double = 497.50,
    ): List<Candle> {
        val series = CandleSeries()
        series.filler(37, 480.00)
        series.flat(480.00) // bar 37 — the prior-trend reference
        series.flat(481.00) // bar 38
        series.ramp(count = 18, fromClose = 482.00, toClose = 499.00, deliveryPercent = 42.0)
        series.bar(
            open = 498.00,
            high = contextHigh,
            low = contextLow,
            close = closeNMinus2,
            volume = CandleSeries.DEFAULT_VOLUME,
            deliveryPercent = 42.0,
        )
        series.bar(
            open = 499.00,
            high = 504.00,
            low = 494.00,
            close = 503.00,
            volume = CandleSeries.DEFAULT_VOLUME,
            deliveryPercent = 42.0,
        )
        series.bar(
            open = 503.50,
            high = 504.00,
            low = 488.00,
            close = 490.50,
            // §3.8: rvol exactly 3.00 — "three times the average" [Ch.17-4 §Layer 3].
            volume = signalVolume ?: series.nextVolumeForRvol(3.0),
            // §7: high delivery + falling price = selling pressure, which CONFIRMS this pattern.
            deliveryPercent = 60.0,
        )
        series.bar(489.00, 490.00, 488.20, 488.60, deliveryPercent = 44.0)
        return series.build()
    }

    /** The bullish textbook fixture, shared with detector 1's negative case. */
    internal fun bullishMirror(): List<Candle> = BullishEngulfingDetectorTest().bullishTextbook()
}