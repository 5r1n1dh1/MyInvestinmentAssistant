package com.mia.app.domain.redflag.technical

import com.mia.app.domain.model.FlagGroup
import com.mia.app.domain.model.FlagSeverity
import com.mia.app.domain.model.FlagTab
import com.mia.app.domain.technical.IndicatorSnapshot
import com.mia.app.domain.technical.TechnicalPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `RF-T-4` — `AT_52WEEK_LOW_WITH_DECLINING_OBV` (plan.md §8.2; `redflags-spec.md` §7.4).
 *
 * The condition is a conjunction, so the interesting pair is *the same 52-session low* with and
 * without a declining OBV slope. That pair is what distinguishes this flag from "the price is
 * cheap", which is not a red flag and must never be one.
 */
class FiftyTwoWeekLowObvRuleTest {

    @Test
    fun `fires at the 52-session low when the 20-session OBV slope is falling`() {
        val candles = TechnicalSeries.atFiftyTwoSessionLowWithFallingObv()
        val snapshot = IndicatorSnapshot.of(candles)
        assertEquals(120.0, snapshot.fiftyTwoWeekLow)
        assertEquals(120.0, snapshot.lastClose)
        assertEquals(-0.8, snapshot.obvSlope!!, 1e-9)
        assertTrue(snapshot.isAtFiftyTwoWeekLowWithDecliningObv)

        val flag = FiftyTwoWeekLowObvRule.evaluate(technicalContext(candles)).single()

        assertEquals(TechnicalFlagIds.AT_52WEEK_LOW_WITH_DECLINING_OBV, flag.id)
        assertEquals(FlagGroup.TECHNICAL, flag.group)
        assertEquals(FlagTab.TECHNICALS, flag.tab)
        assertEquals(FiftyTwoWeekLowObvRule.THRESHOLD, flag.threshold)
        flag.assertEveryMeasurementIsDated()
    }

    @Test
    fun `evidence shows the close, the 52-session range and the OBV slope`() {
        val flag = FiftyTwoWeekLowObvRule
            .evaluate(technicalContext(TechnicalSeries.atFiftyTwoSessionLowWithFallingObv()))
            .single()

        assertEquals("₹120.00", flag.evidenceValue("Close (last bar)"))
        assertEquals("₹120.00", flag.evidenceValue("52-week low (last 52 sessions)"))
        assertEquals("₹200.00", flag.evidenceValue("52-week high (last 52 sessions)"))
        assertEquals("0.0 %", flag.evidenceValue("Position in the 52-session range"))
        assertEquals("₹125.00", flag.evidenceValue(FiftyTwoWeekLowObvRule.PRIOR_LOW_LABEL))
        assertEquals("4.00 %", flag.evidenceValue("Distance below that prior low"))
        assertEquals(
            "-0.800 (signed ÷ gross volume)",
            flag.evidenceValue("On-balance-volume slope (20 sessions)"),
        )
        assertEquals(
            "The last close of ₹120.00 is at the 52-session low of ₹120.00, and the 20-session " +
                "on-balance-volume slope is -0.800 (declining).",
            flag.title,
        )
    }

    @Test
    fun `the same 52-session low with a rising OBV does not fire`() {
        val candles = TechnicalSeries.atFiftyTwoSessionLowWithRisingObv()
        val snapshot = IndicatorSnapshot.of(candles)
        assertEquals(120.0, snapshot.fiftyTwoWeekLow, "the low is identical to the firing fixture")
        assertEquals(120.0, snapshot.lastClose, "only the OBV slope differs")
        assertTrue(snapshot.obvSlope!! > 0.0, "slope = ${snapshot.obvSlope}")
        assertEquals(false, snapshot.isAtFiftyTwoWeekLowWithDecliningObv)

        assertTrue(FiftyTwoWeekLowObvRule.evaluate(technicalContext(candles)).isEmpty())
    }

    @Test
    fun `a close above the 52-session low does not fire`() {
        val candles = TechnicalSeries.atFiftyTwoSessionLowWithFallingObv().toMutableList()
        val last = candles.last()
        candles[candles.lastIndex] = last.copy(open = 130.0, high = 130.5, low = 129.5, close = 130.0)

        val snapshot = IndicatorSnapshot.of(candles)
        assertEquals(125.0, snapshot.fiftyTwoWeekLow, "the low moved up with the last bar")
        assertTrue(130.0 > snapshot.fiftyTwoWeekLow!!)

        assertTrue(FiftyTwoWeekLowObvRule.evaluate(technicalContext(candles)).isEmpty())
    }

    @Test
    fun `a break of the prior low by more than two percent is MEDIUM`() {
        // The fixture's last close is 4.00 % below the prior 52-session low of ₹125.
        val flag = FiftyTwoWeekLowObvRule
            .evaluate(technicalContext(TechnicalSeries.atFiftyTwoSessionLowWithFallingObv()))
            .single()
        assertEquals(FlagSeverity.MEDIUM, flag.severity)
    }

    @Test
    fun `a break of the prior low by two percent or less is HIGH`() {
        // Same shape as the firing fixture, with the last bar closing just inside the prior low.
        val base = TechnicalSeries.atFiftyTwoSessionLowWithFallingObv()
        val priorLow = base.dropLast(1).takeLast(TechnicalPolicy.WEEKS_PER_YEAR).minOf { it.close }
        assertEquals(125.0, priorLow)

        val candles = base.toMutableList()
        val last = candles.last()
        candles[candles.lastIndex] = last.copy(
            open = last.close + 0.5,
            high = last.close + 1.0,
            low = priorLow * 0.985 - 1.0,
            close = priorLow * 0.985,
        )
        val snapshot = IndicatorSnapshot.of(candles)
        assertTrue(snapshot.obvSlope!! < 0.0)
        assertTrue(snapshot.lastClose!! <= snapshot.fiftyTwoWeekLow!!)

        val flag = FiftyTwoWeekLowObvRule.evaluate(technicalContext(candles)).single()
        assertEquals(FlagSeverity.HIGH, flag.severity)
        assertEquals(
            "1.50 %",
            flag.evidenceValue("Distance below that prior low"),
            "125 x 0.985 is 1.50 % below the prior low",
        )
    }

    @Test
    fun `reports not-evaluated rather than clean when the 52-session window does not exist`() {
        val candles = TechnicalSeries.tooShortForFiftyTwoSessionWindow(bars = 30)
        assertTrue(candles.size < TechnicalPolicy.WEEKS_PER_YEAR)

        assertTrue(FiftyTwoWeekLowObvRule.evaluate(technicalContext(candles)).isEmpty())
        val skip = FiftyTwoWeekLowObvRule.skipReason(technicalContext(candles))
        assertTrue(
            skip!!.startsWith(
                "The 52-session high/low window needs ${TechnicalPolicy.WEEKS_PER_YEAR} daily " +
                    "sessions; this series has 30.",
            ),
            "got '$skip'",
        )
    }

    @Test
    fun `a 52-session low is not claimed from a shorter window even though the seam would allow it`() {
        // fiftyTwoWeekRange happily takes the last 52 bars of a 30-bar series, so the seam *does*
        // produce a "low" here. The rule must still abstain: calling it a 52-session low would be a
        // claim the data cannot support.
        val candles = TechnicalSeries.tooShortForFiftyTwoSessionWindow(bars = 30)
        val snapshot = IndicatorSnapshot.of(candles)
        assertEquals(100.0, snapshot.fiftyTwoWeekLow, "the seam is permissive about window length")
        assertTrue(FiftyTwoWeekLowObvRule.evaluate(technicalContext(candles)).isEmpty())
    }

    @Test
    fun `the OBV slope row states its normalisation because a raw OBV level is not comparable`() {
        val flag = FiftyTwoWeekLowObvRule
            .evaluate(technicalContext(TechnicalSeries.atFiftyTwoSessionLowWithFallingObv()))
            .single()
        val value = flag.evidenceValue("On-balance-volume slope (20 sessions)")
        assertTrue(value.contains("signed ÷ gross volume"), "got '$value'")
        assertEquals(FiftyTwoWeekLowObvRule.CAVEAT, flag.evidenceValue("Caveat"))
    }
}