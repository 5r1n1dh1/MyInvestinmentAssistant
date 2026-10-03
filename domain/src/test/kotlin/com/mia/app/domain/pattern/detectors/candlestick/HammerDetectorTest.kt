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
 * Detector 3 — Hammer. Rules and worked example: `patterns-spec.md` section 8,
 * `[Ch.17-1 §Hammer]`.
 *
 * ### One correction to the spec's own fixture
 *
 * Section 8.7 states `close[n−20] = 950.00 ; close[n] = 918.00 => priorTrendPct = −3.368 %`, but the
 * hammer bar it prints a few lines later is `O=922.00 … C=924.00`, i.e. `close[n] = 924.00`. With
 * 950.00 that is −2.74 %, which **fails** the section 8.1 P2 precondition — the fixture as printed
 * is a rejection, not a hammer. The bar is kept exactly as the spec prints it (every shape number
 * untouched: `bodyFrac 0.1481`, `upperFrac 0.1481`, `lowerFrac 0.7037`, `wickRatio 4.75`) and
 * `close[n−20]` moves to **956.00**, which reproduces the gate at −3.347 %.
 *
 * Section 8.7's *near-miss* note is also arithmetically impossible: it claims
 * `919.00 > 912.00 × 1.02`, but `919.00 < 930.24`, so the bar it describes would pass P3. The
 * location near-miss in [HammerDetectorFailureModeTest] is therefore rebuilt so that it genuinely
 * violates P3.
 */
class HammerDetectorTest {

    private val detector = HammerDetector()

    @Test
    fun `detector name is the canonical accuracy-table key`() {
        assertEquals("Hammer", detector.name)
        assertTrue(PatternIds.isCanonical(detector.name))
        assertEquals(3L, PatternIds.of(detector.name))
        assertEquals(Category.CANDLESTICK, detector.category)
    }

    @Test
    fun `theTextbookHammerMatchesAndRenders`() {
        val candles = HammerFixture.textbook()
        assertEquals(64, candles.size)
        assertEquals(60, candles.lastIndex - 3, "the oldest signal bar the window allows")

        val priorTrend = (candles[60].close - candles[40].close) / candles[40].close * 100.0
        assertEquals(-3.347, priorTrend, 0.001, "section 8.1 P2 priorTrendPct(n)")
        assertTrue(priorTrend <= -CandlestickPolicy.declineThresholdPct(Timeframe.D1))

        val match = detectOn(detector, candles).single()
        assertEquals("Hammer", match.name)
        // G=100 (wickRatio 4.75 saturates RATIO_SATURATING(v, 4.0)), S=36, C=85, X=23.
        assertEquals(69, match.score, "§8.7 textbook fixture, scored by FactorScorer")
        assertTrue(match.score >= MatchScorePolicy.RENDER_THRESHOLD)

        val breakdown = assertNotNull(match.breakdown)
        assertEquals(match.score, breakdown.total, "score IS ScoreBreakdown.total")
        assertEquals(100, breakdown.geometry, "wickRatio 4.75 saturates RATIO_SATURATING(v, 4.0)")
        assertTrue(
            breakdown.confirmation > breakdown.structure && breakdown.confirmation > breakdown.context,
            "confirmation must be the strongest evidence factor, got $breakdown",
        )
        assertNull(match.measuredMove, "section 8.4 / DD-1: no measured move for a candlestick")
        assertTrue(match.pivots.isEmpty(), "section 4 rule 7: no pivots")
    }

    @Test
    fun `theTextbookHammerReportsTheSpecShapeNumbers`() {
        val candles = HammerFixture.textbook()
        val hammer = candles[60]
        val range = hammer.high - hammer.low
        val body = kotlin.math.abs(hammer.close - hammer.open)
        val upperWick = hammer.high - maxOf(hammer.open, hammer.close)
        val lowerWick = kotlin.math.min(hammer.open, hammer.close) - hammer.low

        assertEquals(13.50, range, 0.0001, "range")
        assertEquals(2.00, body, 0.0001, "body")
        assertEquals(2.00, upperWick, 0.0001, "upperWick")
        assertEquals(9.50, lowerWick, 0.0001, "lowerWick")
        assertEquals(0.1481, body / range, 0.0001, "bodyFrac <= 0.35")
        assertEquals(0.1481, upperWick / range, 0.0001, "upperFrac <= 0.25")
        assertEquals(0.7037, lowerWick / range, 0.0001, "lowerFrac >= 0.50")
        assertTrue(lowerWick / range >= 2.0 * body / range, "S4 wick >= 2x body")
        assertEquals(4.75, lowerWick / body, 0.0001, "section 8.2 S6 wickRatio")
        assertEquals(912.00, candles.subList(40, 60).minOf { it.low }, 0.0001, "section 8.7 min low")
        assertTrue(hammer.low <= 912.00 * 1.02, "P3 location band")
        assertTrue(candles.lastIndex - 60 <= CandlestickPolicy.MAX_SIGNAL_AGE_BARS)
    }
}

/**
 * Detector 3's near-miss and negative cases — the other two halves of the detector contract
 * ("one condition violated" and "the mirror pattern"), in their own class.
 */
class HammerDetectorFailureModeTest {

    private val detector = HammerDetector()

    @Test
    fun `aHammerOnThinVolumeScoresStrictlyLowerThanTheTextbook`() {
        val withVolume = detectOn(detector, HammerFixture.textbook())
        val thinMatch = detectOn(detector, HammerFixture.textbook(signalVolume = 1_000_000L))

        assertTrue(withVolume.size == 1, "the textbook fixture always matches")
        val textbookScore = withVolume.single().score
        val thinScore = thinMatch.firstOrNull()?.score
        // Measured: 69 with rvol 2.50, 60 without it (K1 collapses and the I4 penalty fires).
        assertEquals(69, textbookScore, "the textbook fixture's exact score")
        assertEquals(60, thinScore, "same shape, no participation")
        assertTrue(thinScore != null && thinScore < textbookScore)
    }

    @Test
    fun `aHammerAwayFromSupportIsSuppressedNotMerelyPenalised`() {
        // Exactly one rule broken: section 8.1 P3, the location precondition. The context is lifted
        // with the hammer so P2 still passes at -3.84 %: this is a *location* rejection, not a
        // trend one.
        val candles = HammerFixture.textbook(
            contextFrom = 990.00,
            contextTo = 960.00,
            contextLow = 920.00,
            hammerOpen = 950.00,
            hammerHigh = 954.00,
            hammerLow = 945.00,
            hammerClose = 952.00,
        )
        val priorTrend = (candles[60].close - candles[40].close) / candles[40].close * 100.0
        assertEquals(-3.838, priorTrend, 0.001, "P2 still passes")
        assertTrue(
            candles[60].low > 920.00 * 1.02,
            "the hammer is above the support band, which is what P3 rejects",
        )
        assertTrue(
            detectOn(detector, candles).isEmpty(),
            "section 8.7: a hammer away from support is suppressed, not shown with a penalty",
        )
    }

    @Test
    fun `aHammerInsideAnUptrendIsAHangingManAndIsNotAMatch`() {
        // DD-3: the identical shape with the opposite context is a Hanging Man, "a potential
        // warning, not a hammer" [Ch.17-1 §Hanging Man] — so it must not be shown as one.
        val candles = HammerFixture.textbook(
            contextFrom = 856.00,
            contextTo = 924.00,
            contextLow = 912.00,
        )
        val priorTrend = (candles[60].close - candles[40].close) / candles[40].close * 100.0
        assertTrue(
            priorTrend >= CandlestickPolicy.riseThresholdPct(Timeframe.D1),
            "the mirror context must be an uptrend, got $priorTrend",
        )
        assertTrue(
            detectOn(detector, candles).isEmpty(),
            "section 8.3 I2 / DD-3: the same shape in an uptrend is suppressed entirely",
        )
        assertTrue(
            detectOn(ShootingStarDetector(), candles).isEmpty(),
            "and it is not a shooting star either: its rejection is on the wrong side of the bar",
        )
    }

    @Test
    fun `aShortHistoryNeverEmitsAScore`() {
        assertTrue(
            detectOn(detector, HammerFixture.textbook().take(59)).isEmpty(),
            "below 60 daily bars the answer is 'not enough history', never a low score",
        )
    }
}

/** The section 8.7 formation (see the class KDoc for the `close[n−20]` correction). */
internal object HammerFixture {

    /**
     * | bars | content |
     * |------|---------|
     * | 0-39 | flat 956.00 (history) |
     * | 40-59 | declining ramp 956.00 to 924.00, last bar's low pinned to 912.00 |
     * | 60 | the signal bar: O=922.00 H=926.00 L=912.50 C=924.00 |
     * | 61 | C=927.40 — section 8.5 C1, "the next session closes above the hammer high" |
     * | 62 | C=960.00 — market-structure break above the prior swing high |
     * | 63 | C=963.00 |
     */
    fun textbook(
        contextFrom: Double = 956.00,
        contextTo: Double = 924.00,
        contextLow: Double = 912.00,
        hammerOpen: Double = 922.00,
        hammerHigh: Double = 926.00,
        hammerLow: Double = 912.50,
        hammerClose: Double = 924.00,
        signalVolume: Long? = null,
    ): List<Candle> {
        val series = CandleSeries()
        series.filler(40, contextFrom)
        series.ramp(
            count = 20,
            fromClose = contextFrom,
            toClose = contextTo,
            deliveryPercent = 44.8,
            lastBarLow = contextLow,
        )
        series.bar(
            open = hammerOpen,
            high = hammerHigh,
            low = hammerLow,
            close = hammerClose,
            volume = signalVolume ?: series.nextVolumeForRvol(2.5),
            deliveryPercent = 61.2,
        )
        series.bar(925.00, 928.00, 924.50, 927.40, deliveryPercent = 45.0)
        series.bar(928.00, 962.00, 927.00, 960.00, deliveryPercent = 46.0)
        series.bar(961.00, 964.00, 960.00, 963.00, deliveryPercent = 46.0)
        return series.build()
    }
}