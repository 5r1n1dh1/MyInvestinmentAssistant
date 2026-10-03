package com.mia.app.domain.redflag.technical

import com.mia.app.domain.model.FlagGroup
import com.mia.app.domain.model.FlagSeverity
import com.mia.app.domain.model.FlagTab
import com.mia.app.domain.model.Source
import com.mia.app.domain.technical.IndicatorSnapshot
import com.mia.app.domain.technical.TechnicalPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `RF-T-3` — `DISTRIBUTION_DAY` (plan.md §8.2; `redflags-spec.md` §7.3).
 *
 * The two halves of the condition are read from **one** bar, so the tests pin the mirror pair that
 * makes that matter: the identical 3.0× volume spike fires on a down bar and stays silent on an up
 * bar. `redflags-spec.md` §7.3 is explicit that there is deliberately no accumulation mirror.
 */
class DistributionDayRuleTest {

    @Test
    fun `fires on a down bar at three times the 20-bar average volume`() {
        val candles = TechnicalSeries.distributionDownDay()
        val snapshot = IndicatorSnapshot.of(candles)
        assertEquals(3.0, snapshot.latestVolumeRatio!!, 1e-4)
        assertTrue(snapshot.isDistributionDay(candles))

        val flag = DistributionDayRule.evaluate(technicalContext(candles)).single()

        assertEquals(TechnicalFlagIds.DISTRIBUTION_DAY, flag.id)
        assertEquals(FlagGroup.TECHNICAL, flag.group)
        assertEquals(FlagTab.TECHNICALS, flag.tab)
        assertEquals(FlagSeverity.MEDIUM, flag.severity)
        assertEquals(DistributionDayRule.THRESHOLD, flag.threshold)
        flag.assertEveryMeasurementIsDated()
    }

    @Test
    fun `evidence shows the volume, the average it was measured against and the bar's own direction`() {
        val flag = DistributionDayRule.evaluate(technicalContext(TechnicalSeries.distributionDownDay())).single()

        assertEquals("3,352,941 shares", flag.evidenceValue("Volume (last bar)"))
        assertEquals("1,117,647 shares", flag.evidenceValue("20-bar average volume"))
        assertEquals("3.00×", flag.evidenceValue("Volume ratio"))
        assertEquals("₹100.50", flag.evidenceValue("Open"))
        assertEquals("₹99.60", flag.evidenceValue("Close"))
        assertEquals("-0.90 %", flag.evidenceValue("Change across the bar"))
        assertEquals("1.08× (₹1.21 ATR)", flag.evidenceValue("Bar range against ATR(14)"))
        assertEquals(
            Source.NSE_BHAVCOPY,
            flag.evidenceRow("Volume (last bar)").source,
        )
        assertEquals(Source.DERIVED, flag.evidenceRow("Volume ratio").source)
        assertEquals(
            "Volume was 3.0× the 20-bar average of 1,117,647 shares on a bar that closed lower " +
                "(₹100.50 open, ₹99.60 close). High volume means high participation; direction " +
                "requires context.",
            flag.title,
        )
    }

    @Test
    fun `the identical volume spike on an up bar does not fire`() {
        val candles = TechnicalSeries.distributionUpDay()
        val snapshot = IndicatorSnapshot.of(candles)
        assertEquals(3.0, snapshot.latestVolumeRatio!!, 1e-4, "the volume half is still satisfied")
        assertTrue(candles.last().isBullish, "only the direction differs from the firing fixture")
        assertEquals(false, snapshot.isDistributionDay(candles))

        assertTrue(DistributionDayRule.evaluate(technicalContext(candles)).isEmpty())
    }

    @Test
    fun `stays silent on a down bar with ordinary volume`() {
        val candles = TechnicalSeries.downDayOnAverageVolume()
        assertTrue(!candles.last().isBullish)
        assertEquals(1.0, IndicatorSnapshot.of(candles).latestVolumeRatio!!, 1e-9)
        assertTrue(DistributionDayRule.evaluate(technicalContext(candles)).isEmpty())
    }

    @Test
    fun `a spike at or above four times the average escalates to HIGH`() {
        val lab = CandleLab()
        repeat(29) { lab.bar(open = 100.0, high = 100.6, low = 99.4, close = 100.2) }
        val spike = lab.volumeForRatio(
            TechnicalRedFlagPolicy.DISTRIBUTION_HIGH_MULTIPLE,
            lab.build().takeLast(19).map { it.volume },
        )
        lab.bar(open = 100.50, high = 100.80, low = 99.50, close = 99.60, volume = spike)
        val candles = lab.build()

        assertEquals(4.0, IndicatorSnapshot.of(candles).latestVolumeRatio!!, 1e-4)
        assertEquals(FlagSeverity.HIGH, DistributionDayRule.evaluate(technicalContext(candles)).single().severity)
    }

    @Test
    fun `reports not-evaluated rather than clean below 20 sessions`() {
        val candles = TechnicalSeries.tooShortForVolumeAverage(bars = 12)
        assertNull(IndicatorSnapshot.of(candles).volumeAverage)

        assertTrue(DistributionDayRule.evaluate(technicalContext(candles)).isEmpty())
        assertEquals(
            "The 20-bar average volume needs ${TechnicalPolicy.VOLUME_SMA_PERIOD} daily sessions; " +
                "this series has 12.",
            DistributionDayRule.skipReason(technicalContext(candles)),
        )
    }

    @Test
    fun `the direction-requires-context caveat is an evidence row, not an assertion in the title`() {
        val flag = DistributionDayRule.evaluate(technicalContext(TechnicalSeries.distributionDownDay())).single()
        assertEquals(DistributionDayRule.CAVEAT, flag.evidenceValue("Caveat"))
        assertTrue(DistributionDayRule.CAVEAT.contains("high participation"))
        assertTrue(
            flag.title.contains("direction requires context"),
            "the title must not assert distribution, only the participation",
        )
        assertTrue(!flag.title.contains("distribution"), "no word that reads as intent")
    }
}