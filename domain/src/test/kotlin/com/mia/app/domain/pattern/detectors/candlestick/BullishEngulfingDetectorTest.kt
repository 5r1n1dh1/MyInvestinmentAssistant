package com.mia.app.domain.pattern.detectors.candlestick

import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.Category
import com.mia.app.domain.model.Match
import com.mia.app.domain.model.MatchScorePolicy
import com.mia.app.domain.model.Timeframe
import com.mia.app.domain.pattern.PatternIds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Detector 1 — Bullish Engulfing (`patterns-spec.md` §6, §6.7 worked fixture).
 *
 * ### Two corrections to the spec's own fixture, and why neither is made silently
 *
 * 1. **`S1` contradicts the bar the spec prints.** §6.7 lists
 *    `n−1 : O=497.00 … C=501.00 (bullish, body = 4.00)` and then asserts `S1 isBear(n−1) ✔`. That
 *    bar is bullish (`497 < 501`), so `S1` fails and §6.7's own "⇒ MATCH" does not follow. The
 *    formation the book describes ("Day 1: Bearish candle. Day 2: Bullish candle.") needs that
 *    day's open and close the other way round, so this fixture uses `O=501.00 … C=497.00`. Every
 *    *shape* number §6.7 prints is untouched and `engulf` still saturates its anchor
 *    (`(509.50 − 501.00) / 4.00 = 2.125 ≥ 1.0`, versus the spec's 3.125 with the bar as printed —
 *    both above the `RATIO_SATURATING(v, 1.0)` ceiling, so the GEOMETRY reading is identical).
 * 2. **§6.7's rejected near-miss cannot reject anything.** It claims
 *    `close[n−20] = 512.00, close[n−2] = 498.20 ⇒ priorTrendPct = −2.70 %`; the true value is
 *    `−4.19 %`, which *passes* the `−3.0 %` precondition. The near-miss below therefore uses
 *    `close[n−2] = 505.50` (`−2.79 %`), which genuinely fails §6.1 **P3**.
 *
 * ### Why the fixture's `close[n−20]` sits on bar 37
 *
 * `priorTrendPct` is `[repo]` (`Geometry.kt`): `(close[bar] − close[bar − 20]) / close[bar − 20]`.
 * Evaluated at the §6.1 **P3** bar `n−2` it reads `close[n−22]`, so the `520.00` reference the
 * spec prints is placed on bar 37 and §6.7's own `−3.846 %` comes out exactly.
 */
class BullishEngulfingDetectorTest {

    private val detector = BullishEngulfingDetector()

    // ---------------------------------------------------------------------- registration

    @Test
    fun `detector name is the canonical accuracy-table key`() {
        assertEquals("Bullish Engulfing", detector.name)
        assertTrue(PatternIds.isCanonical(detector.name), "the name must resolve to a stable id")
        assertEquals(1L, PatternIds.of(detector.name))
        assertEquals(Category.CANDLESTICK, detector.category)
    }

    // -------------------------------------------------------------------- textbook §6.7

    @Test
    fun `textbookBullishEngulfing_matches_andRenders`() {
        val candles = textbook()

        assertEquals(62, candles.size, "the fixture clears the 60-bar history gate")
        assertEquals(59, candles.lastIndex - 2, "the signal bar sits two bars back from the end")

        // §6.7's own arithmetic, reproduced before anything is scored.
        val priorTrend = (candles[57].close - candles[37].close) / candles[37].close * 100.0
        assertEquals(-3.846, priorTrend, 0.001, "§6.7 priorTrendPct(n−2)")

        val match = detectOn(detector, candles).single()

        assertEquals("Bullish Engulfing", match.name)
        assertEquals(Category.CANDLESTICK, match.category)
        // Measured from this fixture and asserted exactly, so a change to any anchor, weight or
        // penalty table shows up as a failing number rather than as a drifting score:
        // G=100 (engulf 2.125 saturates RATIO_SATURATING(v, 1.0)), S=57, C=84, X=25, no penalties.
        assertEquals(74, match.score, "§6.7 textbook fixture, scored by FactorScorer")
        assertTrue(match.score >= MatchScorePolicy.RENDER_THRESHOLD)

        val breakdown = assertNotNull(match.breakdown, "every match carries its breakdown")
        assertEquals(match.score, breakdown.total, "score IS ScoreBreakdown.total")
        // §3.1 G3: engulf = (close[n] − open[n−1]) / body[n−1] = 2.125 ≥ 1.0, so it saturates.
        assertEquals(100, breakdown.geometry, "a complete engulfment is a perfect geometric fit")
        // The evidence the book tells the reader to look for is the evidence that is present:
        // rvol 3.00, follow-through close 511.20 > 509.50, a close above the prior swing high, and
        // delivery 1.40x its own average.
        assertTrue(
            breakdown.confirmation > breakdown.structure && breakdown.confirmation > breakdown.context,
            "confirmation must be the strongest evidence factor, got $breakdown",
        )
        // §6.4 / DD-1 — no measured move, ever, for a candlestick pattern.
        assertNull(match.measuredMove, "§6.4: Match.measuredMove must be null for detectors 1–5")
        // §4 rule 7 — candlestick detectors consume no pivots.
        assertTrue(match.pivots.isEmpty(), "a candlestick match carries no pivots")
        // CONTRACTS.md: a detector must emit measures for **all four** factors, or the engine
        // scores the missing one as 0. All four are non-zero here, so none was left out.
        assertTrue(breakdown.geometry > 0, "GEOMETRY must be scored")
        assertTrue(breakdown.structure > 0, "STRUCTURE must be scored")
        assertTrue(breakdown.confirmation > 0, "CONFIRMATION must be scored")
        assertTrue(breakdown.context > 0, "CONTEXT must be scored")
    }

    @Test
    fun `textbookBullishEngulfing_reportsTheSpecGeometryInputs`() {
        val candles = textbook()
        val previous = candles[58]
        val signal = candles[59]

        // §6.7's closing line, restated as assertions so the fixture cannot drift silently.
        assertEquals(13.00, kotlin.math.abs(signal.close - signal.open), 0.0001, "body[n]")
        assertEquals(4.00, kotlin.math.abs(previous.close - previous.open), 0.0001, "body[n−1]")
        val engulf = (signal.close - previous.open) / kotlin.math.abs(previous.close - previous.open)
        assertEquals(2.125, engulf, 0.0001, "§3.1 G3 engulf")
        // A candlestick match is only ever reported on one of the last few bars.
        assertTrue(
            candles.lastIndex - 59 <= CandlestickPolicy.MAX_SIGNAL_AGE_BARS,
            "a candlestick match must be reported on one of the last few bars",
        )
        assertEquals(candles.last().date, detectOn(detector, candles).single().detectedOn)
    }

    // ------------------------------------------------------------------------- near miss

    @Test
    fun `nearMissWithoutASustainedDeclineIsNotAMatchAtAll`() {
        // Exactly one condition broken: §6.1 P3, priorTrendPct(n−2) <= −3.0 %.
        val candles = textbook(closeNMinus2 = 505.50)
        val priorTrend = (candles[57].close - candles[37].close) / candles[37].close * 100.0
        assertEquals(-2.788, priorTrend, 0.001, "the precondition is what this fixture breaks")
        assertTrue(priorTrend > -CandlestickPolicy.declineThresholdPct(Timeframe.D1))

        // DD-2 makes it a gate, so there is no score to compare: the detector reports nothing
        // rather than a weak bullish engulfing in a sideways market.
        assertTrue(
            detectOn(detector, candles).isEmpty(),
            "a bullish engulfing with no preceding decline must not match [Ch.17-2 §Poor Locations]",
        )
    }

    @Test
    fun `nearMissOnThinVolumeScoresStrictlyLowerThanTheTextbook`() {
        val textbookScore = detectOn(detector, textbook()).single().score

        // One condition broken: §6.3 I4 / K1 — thin volume on a close that cleared the prior high.
        // Same formation, same shape, same context; only participation is missing.
        val thin = detectOn(detector, textbook(signalVolume = CandleSeries.DEFAULT_VOLUME))
        // Measured: 74 with rvol 3.00, 64 with the average volume (K1 collapses from 100 to 33 and
        // the I4 weak-volume penalty fires for -6). The property under test is the direction.
        assertEquals(64, thin.single().score, "same shape, no participation")
        assertTrue(thin.single().score < textbookScore)
    }

    // -------------------------------------------------------------------------- negative

    @Test
    fun `theBearishMirrorIsNotABullishEngulfing`() {
        val candles = bearishMirror()
        val signal = candles[candles.lastIndex - 1]
        val previous = candles[candles.lastIndex - 2]
        assertTrue(previous.close > previous.open, "mirror day 1 must be bullish")
        assertTrue(signal.close < signal.open, "mirror day 2 must be bearish")
        assertTrue(signal.open > previous.close && signal.close < previous.open, "it engulfs")

        assertTrue(
            detectOn(detector, candles).isEmpty(),
            "the mirror is a bearish engulfing, not a bullish one",
        )
        // And the mirror detector does claim it — the negative is about *this* detector, not about
        // the fixture being unmatchable.
        assertEquals(1, detectOn(BearishEngulfingDetector(), candles).size, "§7 claims the mirror")
    }

    @Test
    fun `aZeroRangeBarHasNoAnatomyAndCannotQualify`() {
        // §5.1: range == 0 gives every fraction 0.0, "and the bar can never satisfy any
        // candlestick shape rule". Two flat bars can engulf nothing, because there is no body.
        val series = CandleSeries()
        series.filler(40, 500.00)
        series.ramp(count = 20, fromClose = 520.00, toClose = 500.00, lastBarLow = 498.20)
        series.flat(497.00)
        series.flat(508.00)
        assertTrue(detectOn(detector, series.build()).isEmpty())
    }

    @Test
    fun `aShortHistoryNeverEmitsAScore`() {
        val short = textbook().take(59)
        assertTrue(
            detectOn(detector, short).isEmpty(),
            "§6.1 P1 / §24 check 1: below 60 daily bars the answer is 'not enough history'",
        )
    }

    @Test
    fun `theDetectorIgnoresThePivotListItIsGiven`() {
        val candles = textbook()
        val fromEmpty = detector.detect(emptyList(), candles)
        // A pivoted list cannot reach a candle-shape rule (§4 rule 7), so the result must be
        // identical: a detector that consulted pivots would disagree here.
        val pivots = listOf(
            com.mia.app.domain.model.Pivot(
                10,
                java.time.LocalDate.of(2026, 1, 11),
                520.0,
                com.mia.app.domain.model.PivotKind.HIGH,
                0,
            ),
        )
        assertEquals(fromEmpty, detector.detect(pivots, candles), "candles only")
    }

    // ---------------------------------------------------------------------------- fixture

    /**
     * The §6.7 formation with room for the book's own follow-through and structure-confirmation
     * bars after the signal bar — the spec's fixture stops at `close[n+1]`, which leaves §6.5
     * **C3**'s market-structure confirmation untestable.
     *
     * Layout — 62 daily bars:
     *
     * | bars | content |
     * |------|---------|
     * | 0–36 | flat 520.00 — history; bar 37 is the prior-trend reference |
     * | 37 | 520.00 — §6.7's `close[n−LOOKBACK]` reference |
     * | 38 | 519.00 — outside both the context window and the pattern |
     * | 39–56 | declining ramp 518.00 → 505.00 |
     * | 57 | **§6.7 `n−2`** O=505.00 H=506.10 L=498.20 C=500.00 |
     * | 58 | **`n−1`** O=501.00 H=506.00 L=496.00 C=497.00 — bearish, see the class KDoc |
     * | 59 | **§6.7 `n`** O=496.50 H=512.00 L=496.00 C=509.50 |
     * | 60 | `n+1` C=511.20 — §6.7 C2 follow-through |
     * | 61 | C=522.60 — §6.5 C3 "break above the prior swing high" |
     */
    private fun textbook(
        closeNMinus2: Double = 500.00,
        signalVolume: Long? = null,
    ): List<Candle> {
        val series = CandleSeries()
        series.filler(37, 520.00)
        series.flat(520.00) // bar 37 — the prior-trend reference
        series.flat(519.00) // bar 38
        series.ramp(count = 18, fromClose = 518.00, toClose = 505.00, deliveryPercent = 42.0)
        series.bar(
            open = 505.00,
            high = 506.10,
            low = 498.20,
            close = closeNMinus2,
            volume = CandleSeries.DEFAULT_VOLUME,
            deliveryPercent = 42.0,
        )
        series.bar(
            open = 501.00,
            high = 506.00,
            low = 496.00,
            close = 497.00,
            volume = CandleSeries.DEFAULT_VOLUME,
            deliveryPercent = 42.0,
        )
        series.bar(
            open = 496.50,
            high = 512.00,
            low = 496.00,
            close = 509.50,
            // §6.5 C1: rvol exactly 3.00 — "Volume: 3x average" [Ch.17-4 §Layer 3].
            volume = signalVolume ?: series.nextVolumeForRvol(3.0),
            // §6.5 C6: delivery 60 % against a 42 % average ⇒ deliveryVsAvg = 1.399.
            deliveryPercent = 60.0,
        )
        series.bar(510.00, 512.50, 509.80, 511.20, deliveryPercent = 44.0)
        series.bar(512.00, 523.00, 511.50, 522.60, deliveryPercent = 45.0)
        return series.build()
    }

    /** The exact mirror of [textbook] — same geometry, both signs flipped, and a rally instead of a
     * decline as the context — so the *only* difference from the positive case is direction.
     *
     * §7's own worked line has the same inconsistency as §6.7: it prints
     * `n−1 : O=511.00 … C=509.00 (bullish, body = 2.00)` and then asserts `S1 isBull(n−1) ✔`, but
     * `511 > 509` makes that bar bearish. This fixture does not need §7's absolute prices, only
     * its shape, so it mirrors the §6.7 fixture with the same magnitudes (bodies 4.00 and 13.00,
     * `engulf` 2.125) and a context that rises `+4.17 %` instead of falling `−3.85 %`.
     */
    private fun bearishMirror(): List<Candle> {
        val series = CandleSeries()
        series.filler(37, 480.00)
        series.flat(480.00) // bar 37 — the mirror's prior-trend reference
        series.flat(481.00) // bar 38
        series.ramp(count = 18, fromClose = 482.00, toClose = 499.00, lastBarHigh = 501.00)
        series.bar(498.00, 501.00, 497.50, 500.00) // n−2
        series.bar(499.00, 504.00, 494.00, 503.00) // n−1 — bullish, body 4.00
        series.bar(503.50, 504.00, 488.00, 490.50) // n — bearish engulfing, body 13.00
        series.bar(489.00, 490.00, 488.20, 488.60) // n+1 — follow-through down
        return series.build()
    }

    /** The §6.7 formation, exposed for detector 2's negative (mirror) case. */
    internal fun bullishTextbook(): List<Candle> = textbook()

    /** The same formation with the signal bar's volume overridden (near-miss cases). */
    internal fun bullishTextbook(signalVolume: Long?): List<Candle> = textbook(signalVolume = signalVolume)
}