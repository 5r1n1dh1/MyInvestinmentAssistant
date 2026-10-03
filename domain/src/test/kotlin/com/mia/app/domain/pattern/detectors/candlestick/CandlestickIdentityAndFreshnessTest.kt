package com.mia.app.domain.pattern.detectors.candlestick

import com.mia.app.domain.model.Category
import com.mia.app.domain.pattern.PatternIds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The naming, categorisation and freshness invariants of the candlestick family.
 *
 * * **Canonical names** are the keys of the accuracy table (decision D6: hit rates are stored per
 *   stock *and* per pattern name). A `Match` whose name is not in `PatternIds.CANONICAL_NAMES`
 *   starts a new, empty hit-rate table, and because the app withholds hit rates below
 *   `MatchScorePolicy.MIN_HIT_RATE_SAMPLES` samples the user would permanently see
 *   "not enough data" for a pattern they had been tracked on.
 * * **At most one match per invocation**, and only on one of the last few bars: a candlestick is
 *   defined over its last one to three bars, so a formation ten sessions old is history.
 */
class CandlestickIdentityAndFreshnessTest {

    @Test
    fun `everyMatchUsesACanonicalNameAndTheCandlestickCategory`() {
        val allowed = setOf(
            CandlestickNames.BULLISH_ENGULFING,
            CandlestickNames.BEARISH_ENGULFING,
            CandlestickNames.HAMMER,
            CandlestickNames.SHOOTING_STAR,
            CandlestickNames.MORNING_STAR,
            CandlestickNames.EVENING_STAR,
        )
        for (detector in allCandlestickDetectors()) {
            for (symbol in RealHistory.SYMBOLS) {
                for (hit in detectOn(detector, RealHistory.candles(symbol))) {
                    assertTrue(hit.name in allowed, "unexpected display name: ${hit.name}")
                    assertTrue(PatternIds.isCanonical(hit.name), "${hit.name} has no stable id")
                    assertEquals(Category.CANDLESTICK, hit.category)
                }
            }
        }
    }

    @Test
    fun `eachDetectorNameIsItsOwnCanonicalAccuracyTableKey`() {
        assertEquals(1L, PatternIds.of(BullishEngulfingDetector().name))
        assertEquals(2L, PatternIds.of(BearishEngulfingDetector().name))
        assertEquals(3L, PatternIds.of(HammerDetector().name))
        assertEquals(4L, PatternIds.of(ShootingStarDetector().name))
        assertEquals(5L, PatternIds.of(StarDetector().name))
    }

    @Test
    fun `eachDetectorReturnsAtMostOneMatchPerInvocation`() {
        for (detector in allCandlestickDetectors()) {
            for (symbol in RealHistory.SYMBOLS) {
                val hits = detectOn(detector, RealHistory.candles(symbol))
                assertTrue(hits.size <= 1, "${detector.name}/$symbol returned ${hits.size} matches")
            }
        }
    }

    @Test
    fun `aFormationOnTheOldestAllowedBarStillMatches`() {
        // The hammer fixture's signal bar sits exactly MAX_SIGNAL_AGE_BARS back from the end of the
        // series: the boundary of the window, and it must still match.
        val atLimit = HammerFixture.textbook()
        assertEquals(CandlestickPolicy.MAX_SIGNAL_AGE_BARS, atLimit.lastIndex - 60)
        assertEquals(1, detectOn(HammerDetector(), atLimit).size)
    }

    @Test
    fun `aFormationOlderThanTheSignalWindowIsNotReported`() {
        // The same formation, ten sessions later. Reporting it would be reporting history.
        val textbook = BearishEngulfingDetectorTest().textbook()
        val stale = textbook + CandleSeries(startDate = textbook.last().date.plusDays(1))
            .filler(10, 505.00)
            .build()
        assertEquals(textbook.size + 10, stale.size)
        assertTrue(
            detectOn(BearishEngulfingDetector(), stale).isEmpty(),
            "a ten-session-old formation must not be reported as a current pattern",
        )
    }

    @Test
    fun `theSameInputAlwaysProducesTheSameMatches`() {
        for (detector in allCandlestickDetectors()) {
            val candles = RealHistory.candles(RealHistory.SYMBOLS.first())
            assertEquals(
                detectOn(detector, candles),
                detectOn(detector, candles),
                "${detector.name} must be a pure function of its input (plan.md §4.3)",
            )
        }
    }
}