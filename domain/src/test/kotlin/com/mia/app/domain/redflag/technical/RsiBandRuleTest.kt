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
 * `RF-T-2` — `RSI_OVERBOUGHT` and `RSI_OVERSOLD` (plan.md §8.2; `redflags-spec.md` §7.2).
 *
 * Two rules, two stable ids. The boundary cases are tested against a **stated** RSI series rather
 * than an engineered price series, because "70.0 does not fire and 70.1 does" is a claim about a
 * comparison, and no price series lands RSI on exactly 70.0 by construction.
 */
class RsiBandRuleTest {

    private val overbought = RsiBandRule(RsiBand.OVERBOUGHT)
    private val oversold = RsiBandRule(RsiBand.OVERSOLD)

    // ------------------------------------------------------------------------------ firing

    @Test
    fun `overbought fires on a sustained RSI above 70`() {
        val candles = TechnicalSeries.rsiOverbought()
        assertEquals("88.0", TechnicalFlagFormat.decimal(IndicatorSnapshot.of(candles).rsi.value!!, 1))

        val flag = overbought.evaluate(technicalContext(candles)).single()

        assertEquals(TechnicalFlagIds.RSI_OVERBOUGHT, flag.id)
        assertEquals(FlagGroup.TECHNICAL, flag.group)
        assertEquals(FlagTab.TECHNICALS, flag.tab)
        assertEquals(FlagSeverity.MEDIUM, flag.severity)
        assertEquals("RSI_OVERBOUGHT", flag.id)
        assertTrue(
            flag.title.startsWith("RSI(14) is 88.0, in the overbought band above 70."),
            "unexpected title: ${flag.title}",
        )
        assertEquals("88.0", flag.evidenceValue("RSI (14)"))
        assertEquals("70.0", flag.evidenceValue("Band boundary"))
        assertEquals("15", flag.evidenceValue("Consecutive sessions above 70"))
        assertEquals("₹121.40", flag.evidenceValue("52-week high (last 52 sessions)"))
        assertEquals("₹121.40", flag.evidenceValue("Close (last bar)"))
        assertEquals(RsiBandRule.THRESHOLD, flag.threshold)
        flag.assertEveryMeasurementIsDated()
    }

    @Test
    fun `oversold fires on the mirror series`() {
        val candles = TechnicalSeries.rsiOversold()
        val flag = oversold.evaluate(technicalContext(candles)).single()

        assertEquals(TechnicalFlagIds.RSI_OVERSOLD, flag.id)
        assertEquals(FlagSeverity.MEDIUM, flag.severity)
        assertEquals("12.6", flag.evidenceValue("RSI (14)"))
        assertEquals("30.0", flag.evidenceValue("Band boundary"))
        assertEquals("15", flag.evidenceValue("Consecutive sessions below 30"))
        assertEquals(
            "RSI(14) is 12.6, in the oversold band below 30. Oversold describes momentum, not a " +
                "turn in the price, and a low reading is not by itself a reason to act.",
            flag.title,
        )
    }

    @Test
    fun `a reading that has held for fewer than five sessions is LOW`() {
        val flag = overbought.evaluate(technicalContext(TechnicalSeries.rsiFreshCrossingOfSeventy())).single()
        assertEquals(FlagSeverity.LOW, flag.severity)
        assertEquals("1", flag.evidenceValue("Consecutive sessions above 70"))
        assertEquals("80.7", flag.evidenceValue("RSI (14)"))
    }

    // ---------------------------------------------------------------------------- boundaries

    @Test
    fun `exactly 70 does not fire overbought and 70_1 does`() {
        val atBound = TechnicalSeries.rsiBetweenBands()
        assertTrue(overbought.evaluate(contextWithRsi(atBound, listOf(65.0, 70.0))).isEmpty())
        assertTrue(oversold.evaluate(contextWithRsi(atBound, listOf(65.0, 70.0))).isEmpty())

        val justOver = overbought.evaluate(contextWithRsi(atBound, listOf(65.0, 70.1)))
        assertEquals(1, justOver.size)
        assertEquals("70.1", justOver.single().evidenceValue("RSI (14)"))
        assertEquals("1", justOver.single().evidenceValue("Consecutive sessions above 70"))
    }

    @Test
    fun `exactly 30 does not fire oversold and 29_9 does`() {
        val atBound = TechnicalSeries.rsiBetweenBands()
        assertTrue(oversold.evaluate(contextWithRsi(atBound, listOf(35.0, 30.0))).isEmpty())
        assertTrue(overbought.evaluate(contextWithRsi(atBound, listOf(35.0, 30.0))).isEmpty())

        val justUnder = oversold.evaluate(contextWithRsi(atBound, listOf(35.0, 29.9)))
        assertEquals(1, justUnder.size)
        assertEquals("29.9", justUnder.single().evidenceValue("RSI (14)"))
        assertEquals("30.0", justUnder.single().evidenceValue("Band boundary"))
    }

    @Test
    fun `the band constants come from TechnicalPolicy so the chart and the flag cannot drift`() {
        assertEquals(70.0, TechnicalPolicy.RSI_OVERBOUGHT)
        assertEquals(30.0, TechnicalPolicy.RSI_OVERSOLD)
        assertEquals(RsiBand.OVERBOUGHT, RsiBand.of(TechnicalPolicy.RSI_OVERBOUGHT + 0.001))
        assertNull(RsiBand.of(TechnicalPolicy.RSI_OVERBOUGHT))
        assertEquals(RsiBand.OVERSOLD, RsiBand.of(TechnicalPolicy.RSI_OVERSOLD - 0.001))
        assertNull(RsiBand.of(TechnicalPolicy.RSI_OVERSOLD))
        assertNull(RsiBand.of(null))
        assertNull(RsiBand.of(50.0))
    }

    // ------------------------------------------------------------------------ quiet / skipped

    @Test
    fun `stays silent while the reading is between the bands`() {
        val candles = TechnicalSeries.rsiBetweenBands()
        val snapshot = IndicatorSnapshot.of(candles)
        assertEquals("47.5", TechnicalFlagFormat.decimal(snapshot.rsi.value!!, 1))
        assertTrue(overbought.evaluate(technicalContext(candles)).isEmpty())
        assertTrue(oversold.evaluate(technicalContext(candles)).isEmpty())
    }

    @Test
    fun `the overbought rule does not fire on an oversold reading and vice versa`() {
        val candles = TechnicalSeries.rsiOverbought()
        assertTrue(oversold.evaluate(technicalContext(candles)).isEmpty())
        val down = TechnicalSeries.rsiOversold()
        assertTrue(overbought.evaluate(technicalContext(down)).isEmpty())
    }

    @Test
    fun `reports not-evaluated rather than clean below 15 sessions`() {
        val candles = TechnicalSeries.tooShortForVolumeAverage(bars = 12)
        assertNull(IndicatorSnapshot.of(candles).rsi.value)

        assertTrue(overbought.evaluate(technicalContext(candles)).isEmpty())
        assertTrue(oversold.evaluate(technicalContext(candles)).isEmpty())
        val expected =
            "RSI(14) needs ${TechnicalPolicy.RSI_PERIOD + 1} daily sessions; this series has 12."
        assertEquals(expected, overbought.skipReason(technicalContext(candles)))
        assertEquals(expected, oversold.skipReason(technicalContext(candles)))
    }

    // --------------------------------------------------------------------------- the wording

    @Test
    fun `both rules share the momentum-not-reversal caveat`() {
        val up = overbought.evaluate(technicalContext(TechnicalSeries.rsiOverbought())).single()
        val down = oversold.evaluate(technicalContext(TechnicalSeries.rsiOversold())).single()
        assertEquals(RsiBandRule.CAVEAT, up.evidenceValue("Caveat"))
        assertEquals(RsiBandRule.CAVEAT, down.evidenceValue("Caveat"))
        assertTrue(RsiBandRule.CAVEAT.contains("momentum reading"))
        assertTrue(
            up.title.contains("a strong stock can stay in this band for months"),
            "the book's own correction to the naive reading must be in the title area",
        )
    }

    @Test
    fun `the band row names the band in words and in its number`() {
        val up = overbought.evaluate(technicalContext(TechnicalSeries.rsiOverbought())).single()
        val down = oversold.evaluate(technicalContext(TechnicalSeries.rsiOversold())).single()
        assertTrue(up.evidence.map { it.value }.contains(RsiBand.OVERBOUGHT.label))
        assertTrue(down.evidence.map { it.value }.contains(RsiBand.OVERSOLD.label))
        assertEquals(RsiBand.OVERBOUGHT.label, up.evidenceValue("Band"))
        assertEquals(RsiBand.OVERSOLD.label, down.evidenceValue("Band"))
    }
}