package com.mia.app.domain.pattern.detectors.candlestick

import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.Category
import com.mia.app.domain.model.MatchScorePolicy
import com.mia.app.domain.model.Timeframe
import com.mia.app.domain.pattern.PatternIds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Detector 4 — Shooting Star. Rules and worked example: `patterns-spec.md` section 9,
 * `[Ch.17-1 §Shooting Star]`.
 *
 * Section 9 is the mirror of section 8 and, unlike sections 6.7 and 7, its worked example is
 * internally consistent: it prints a `close[n]` of 1,217.00 while stating 1,222.00, and the
 * reading the implementation actually computes — (1,217 - 1,180) / 1,180 = +3.136 % — still clears
 * the +3.0 % gate of section 9 P2. Every bar in the fixture below is therefore the spec's own.
 *
 * The three-bar-fixture conventions (which bars are the context window, which is the signal bar)
 * are documented on [ShootingStarFixture].
 */
class ShootingStarDetectorTest {

    private val detector = ShootingStarDetector()

    @Test
    fun `detector name is the canonical accuracy-table key`() {
        assertEquals("Shooting Star", detector.name)
        assertTrue(PatternIds.isCanonical(detector.name))
        assertEquals(4L, PatternIds.of(detector.name))
        assertEquals(Category.CANDLESTICK, detector.category)
    }

    @Test
    fun `theTextbookShootingStarMatchesAndRenders`() {
        val candles = ShootingStarFixture.textbook()
        assertEquals(64, candles.size)
        assertEquals(60, candles.lastIndex - 3, "the oldest signal bar the window allows")

        val priorTrend = (candles[60].close - candles[40].close) / candles[40].close * 100.0
        assertEquals(3.136, priorTrend, 0.001, "section 9 P2 priorTrendPct(n)")
        assertTrue(priorTrend >= CandlestickPolicy.riseThresholdPct(Timeframe.D1))

        val match = detectOn(detector, candles).single()
        assertEquals("Shooting Star", match.name)
        // G=100 (wickRatio 7.00 saturates), S=46, C=99 (rvol 3.1, next-bar confirmation and a
        // market-structure break all fire), X=23.
        assertEquals(74, match.score, "§9 textbook fixture, scored by FactorScorer")
        assertTrue(match.score >= MatchScorePolicy.RENDER_THRESHOLD)

        val breakdown = assertNotNull(match.breakdown)
        assertEquals(match.score, breakdown.total, "score IS ScoreBreakdown.total")
        assertEquals(100, breakdown.geometry, "wickRatio 7.00 saturates RATIO_SATURATING(v, 4.0)")
        assertTrue(
            breakdown.confirmation > breakdown.structure && breakdown.confirmation > breakdown.context,
            "confirmation must be the strongest evidence factor, got $breakdown",
        )
        assertNull(match.measuredMove, "section 9 / DD-1: no measured move for a candlestick")
        assertTrue(match.pivots.isEmpty(), "section 4 rule 7: no pivots")
    }

    @Test
    fun `theTextbookShootingStarReportsTheSpecShapeNumbers`() {
        val candles = ShootingStarFixture.textbook()
        val star = candles[60]
        val range = star.high - star.low
        val body = kotlin.math.abs(star.close - star.open)
        val upperWick = star.high - maxOf(star.open, star.close)
        val lowerWick = kotlin.math.min(star.open, star.close) - star.low

        assertEquals(18.00, range, 0.0001, "range")
        assertEquals(2.00, body, 0.0001, "body")
        assertEquals(14.00, upperWick, 0.0001, "upperWick")
        assertEquals(2.00, lowerWick, 0.0001, "lowerWick")
        assertEquals(0.7778, upperWick / range, 0.0001, "upperFrac >= 0.50")
        assertEquals(0.1111, lowerWick / range, 0.0001, "lowerFrac <= 0.25")
        assertEquals(0.1111, body / range, 0.0001, "bodyFrac <= 0.35")
        assertTrue(upperWick / range >= 2.0 * body / range, "S4 wick >= 2x body")
        assertEquals(7.00, upperWick / body, 0.0001, "section 9 S6 wickRatio")
        assertEquals(1224.00, candles.subList(40, 60).maxOf { it.high }, 0.0001, "section 9 max high")
        assertTrue(star.high >= 1224.00 * 0.98, "P3 resistance band")
        assertTrue(candles.lastIndex - 60 <= CandlestickPolicy.MAX_SIGNAL_AGE_BARS)
    }
}

/**
 * Detector 4's near-miss and negative cases, kept in their own class.
 *
 * They exist for the same reason they would live beside the positives — they are the other two
 * halves of "one condition violated" and "the mirror pattern" — but they also keep each class in
 * this package small enough to read in one screen.
 */
class ShootingStarDetectorFailureModeTest {

    private val detector = ShootingStarDetector()

    @Test
    fun `aStarAwayFromResistanceIsSuppressed`() {
        val candles = ShootingStarFixture.textbook(
            contextFrom = 1140.00,
            contextTo = 1180.00,
            // The window's own high sits above the star, so 0.98 x it (1,225.00) clears the star's
            // 1,201.00 high: P3 rejects because the rejection never reached resistance.
            contextHigh = 1250.00,
            starOpen = 1195.00,
            starHigh = 1201.00,
            starLow = 1193.00,
            starClose = 1197.00,
        )
        val priorTrend = (candles[60].close - candles[40].close) / candles[40].close * 100.0
        assertTrue(
            priorTrend >= CandlestickPolicy.riseThresholdPct(Timeframe.D1),
            "P2 still passes, got $priorTrend",
        )
        assertTrue(
            candles[60].high < 1250.00 * 0.98,
            "the star sits below the resistance band, which is what P3 rejects",
        )
        assertTrue(
            detectOn(detector, candles).isEmpty(),
            "a shooting star away from resistance is suppressed [Ch.17-4 §Layer 1]",
        )
    }

    @Test
    fun `aStarOnThinVolumeScoresStrictlyLower`() {
        val textbookScore = detectOn(detector, ShootingStarFixture.textbook()).single().score
        val thin = detectOn(detector, ShootingStarFixture.textbook(signalVolume = 1_000_000L))
        // Measured: 74 with rvol 3.10, 62 without it.
        assertEquals(62, thin.single().score, "same shape, no participation")
        assertTrue(thin.single().score < textbookScore)
    }

    @Test
    fun `aStarInsideADeclineIsAnInvertedHammerNotAShootingStar`() {
        val candles = HammerFixture.textbook()
        val priorTrend = (candles[60].close - candles[40].close) / candles[40].close * 100.0
        assertTrue(
            priorTrend <= -CandlestickPolicy.declineThresholdPct(Timeframe.D1),
            "the mirror context must be a downtrend, got $priorTrend",
        )
        assertTrue(
            detectOn(detector, candles).isEmpty(),
            "section 9 I2 / DD-3: the same shape in a decline is suppressed entirely",
        )
        assertEquals(1, detectOn(HammerDetector(), candles).size, "detector 3 claims the mirror")
    }

    @Test
    fun `aShortHistoryNeverEmitsAScore`() {
        assertTrue(
            detectOn(detector, ShootingStarFixture.textbook().take(59)).isEmpty(),
            "below 60 daily bars the answer is 'not enough history', never a low score",
        )
    }
}

/** The section 9 formation, with every bar the spec prints. */
internal object ShootingStarFixture {

    /**
     * | bars | content |
     * |------|---------|
     * | 0-39 | flat 1,180.00 (history; bar 40 is the prior-trend reference) |
     * | 40-59 | rising ramp 1,180.00 to 1,213.00, last bar's high pinned to 1,224.00 |
     * | 60 | the signal bar: O=1,215.00 H=1,231.00 L=1,213.00 C=1,217.00 |
     * | 61 | C=1,209.50 — section 9 "Next day: Bearish confirmation" |
     * | 62 | C=1,175.00 — market-structure break below the prior swing low |
     * | 63 | C=1,179.00 |
     */
    fun textbook(
        contextFrom: Double = 1180.00,
        contextTo: Double = 1213.00,
        contextHigh: Double = 1224.00,
        starOpen: Double = 1215.00,
        starHigh: Double = 1231.00,
        starLow: Double = 1213.00,
        starClose: Double = 1217.00,
        signalVolume: Long? = null,
    ): List<Candle> {
        val series = CandleSeries()
        series.filler(40, contextFrom)
        series.ramp(
            count = 20,
            fromClose = contextFrom,
            toClose = contextTo,
            deliveryPercent = 42.0,
            lastBarHigh = contextHigh,
        )
        series.bar(
            open = starOpen,
            high = starHigh,
            low = starLow,
            close = starClose,
            volume = signalVolume ?: series.nextVolumeForRvol(3.1),
            deliveryPercent = 58.0,
        )
        series.bar(1216.00, 1216.50, 1208.00, 1209.50, deliveryPercent = 44.0)
        series.bar(1200.00, 1205.00, 1170.00, 1175.00, deliveryPercent = 45.0)
        series.bar(1176.00, 1180.00, 1174.00, 1179.00, deliveryPercent = 45.0)
        return series.build()
    }
}