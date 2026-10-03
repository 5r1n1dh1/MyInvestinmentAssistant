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
 * `RF-T-1` — `DEATH_CROSS` (plan.md §8.2; `redflags-spec.md` §7.1).
 *
 * The golden numbers below are read off [TechnicalSeries.deathCross], a 260-session series that
 * rises and then falls. They are pinned as **formatted strings** rather than re-derived numbers,
 * because the product's promise is that the evidence list shows the number the comparison used.
 */
class DeathCrossRuleTest {

    @Test
    fun `fires when the 50-day average is below the 200-day average and the close is below it too`() {
        val candles = TechnicalSeries.deathCross()
        val snapshot = IndicatorSnapshot.of(candles)

        // The fixture is only meaningful if the seam agrees with what the rule asserts.
        assertEquals(110.00, snapshot.lastClose)
        assertEquals(127.32, TechnicalFlagFormat.decimal(snapshot.sma50Value!!, 2).toDouble(), 0.005)
        assertEquals(145.86, TechnicalFlagFormat.decimal(snapshot.sma200Value!!, 2).toDouble(), 0.005)
        assertTrue(snapshot.isDeathCrossCondition())

        val flag = DeathCrossRule.evaluate(technicalContext(candles)).single()

        assertEquals(TechnicalFlagIds.DEATH_CROSS, flag.id)
        assertEquals(FlagGroup.TECHNICAL, flag.group)
        assertEquals(FlagTab.TECHNICALS, flag.tab)
        assertEquals(
            "The 50-day average is below the 200-day average, and the last close of ₹110.00 is " +
                "below the 200-day average of ₹145.86.",
            flag.title,
        )
        assertEquals(DeathCrossRule.THRESHOLD, flag.threshold)
        flag.assertEveryMeasurementIsDated()
    }

    @Test
    fun `evidence names both moving averages and the close`() {
        val candles = TechnicalSeries.deathCross()
        val flag = DeathCrossRule.evaluate(technicalContext(candles)).single()

        assertEquals("₹110.00", flag.evidenceValue("Close (last bar)"))
        assertEquals("₹127.32", flag.evidenceValue("50-day average"))
        assertEquals("₹145.86", flag.evidenceValue("200-day average"))
        assertEquals("-24.6 %", flag.evidenceValue("Close against the 200-day average"))
        assertEquals(
            Source.NSE_BHAVCOPY,
            flag.evidenceRow("Close (last bar)").source,
            "the close is a candle, not a computed ratio",
        )
        assertEquals(Source.DERIVED, flag.evidenceRow("50-day average").source)
        assertEquals("2026-09-17", flag.evidenceRow("Close (last bar)").asOf.toString())
    }

    @Test
    fun `evidence reports when the cross printed and how many sessions ago`() {
        val candles = TechnicalSeries.deathCross()
        val snapshot = IndicatorSnapshot.of(candles)
        val flag = DeathCrossRule.evaluate(technicalContext(candles)).single()

        val crossIndex = snapshot.deathCrossBarIndex!!
        assertEquals(235, crossIndex)
        assertEquals("2026-08-24", flag.evidenceValue(DeathCrossRule.CROSS_DATE_LABEL))
        assertEquals(candles[crossIndex].date.toString(), flag.evidenceValue(DeathCrossRule.CROSS_DATE_LABEL))
        assertEquals("24 session(s)", flag.evidenceValue("Sessions since the cross"))
        assertEquals("260 daily bar(s)", flag.evidenceValue("Bars in the loaded window"))
    }

    @Test
    fun `a cross older than five sessions is MEDIUM`() {
        val flag = DeathCrossRule.evaluate(technicalContext(TechnicalSeries.deathCross())).single()
        assertEquals(FlagSeverity.MEDIUM, flag.severity)
        assertTrue(24 > TechnicalRedFlagPolicy.DEATH_CROSS_RECENT_BARS)
    }

    @Test
    fun `a cross printed within five sessions is HIGH`() {
        val candles = TechnicalSeries.recentDeathCross()
        val snapshot = IndicatorSnapshot.of(candles)
        val flag = DeathCrossRule.evaluate(technicalContext(candles)).single()

        assertEquals(FlagSeverity.HIGH, flag.severity)
        assertEquals(263, snapshot.deathCrossBarIndex)
        assertEquals("1 session(s)", flag.evidenceValue("Sessions since the cross"))
        assertEquals("₹35.00", flag.evidenceValue("Close (last bar)"))
        assertEquals("₹117.70", flag.evidenceValue("50-day average"))
        assertEquals("₹120.32", flag.evidenceValue("200-day average"))
    }

    @Test
    fun `stays silent while the 50-day average is still above the 200-day average`() {
        val candles = TechnicalSeries.noDeathCross()
        assertTrue(IndicatorSnapshot.of(candles).sma50Value!! > IndicatorSnapshot.of(candles).sma200Value!!)
        assertTrue(DeathCrossRule.evaluate(technicalContext(candles)).isEmpty())
    }

    @Test
    fun `stays silent when the averages are below but the close has recovered above the 200-day average`() {
        // The condition is a conjunction, so half of it is not a flag. Reusing the firing fixture's
        // shape but with a recovering close proves the second half is really tested.
        val candles = TechnicalSeries.deathCross().toMutableList()
        val last = candles.last()
        candles[candles.lastIndex] = last.copy(open = 150.0, high = 150.5, low = 149.5, close = 150.0)

        val snapshot = IndicatorSnapshot.of(candles)
        assertTrue(snapshot.isDeathCross, "the 50-day average is still below the 200-day average")
        assertEquals(false, snapshot.isBelowLongTermAverage)

        assertTrue(DeathCrossRule.evaluate(technicalContext(candles)).isEmpty())
    }

    @Test
    fun `reports not-evaluated rather than clean when there are fewer than 200 sessions`() {
        val candles = TechnicalSeries.tooShortForDeathCross(bars = 80)
        assertNull(IndicatorSnapshot.of(candles).sma200Value)

        assertTrue(DeathCrossRule.evaluate(technicalContext(candles)).isEmpty())
        val skip = DeathCrossRule.skipReason(technicalContext(candles))
        assertEquals(
            "The 200-day average needs ${TechnicalPolicy.SMA_LONG} daily sessions; this series has 80.",
            skip,
        )
    }

    @Test
    fun `reports not-evaluated when there is no price history at all`() {
        assertTrue(DeathCrossRule.evaluate(technicalContext(emptyList())).isEmpty())
        assertEquals(
            "No price history was supplied for TEST.",
            DeathCrossRule.skipReason(technicalContext(emptyList())),
        )
    }

    @Test
    fun `says so plainly when no crossover exists inside the loaded window`() {
        // A series that is already declining from its first computable bar has no ABOVE -> BELOW
        // transition inside the window, so quoting a cross date would be inventing one.
        val candles = TechnicalSeries.monotonicDecline()
        val snapshot = IndicatorSnapshot.of(candles)
        assertNull(snapshot.deathCrossBarIndex, "fixture must have no crossover inside the window")

        val flag = DeathCrossRule.evaluate(technicalContext(candles)).single()
        assertEquals(DeathCrossRule.CROSS_NOT_IN_WINDOW, flag.evidenceValue(DeathCrossRule.CROSS_DATE_LABEL))
        assertEquals(
            "Not observable inside the loaded window",
            flag.evidenceValue("Sessions since the cross"),
        )
    }

    @Test
    fun `carries the book's lagging-indicator caveat as an evidence row`() {
        val flag = DeathCrossRule.evaluate(technicalContext(TechnicalSeries.deathCross())).single()
        assertEquals(DeathCrossRule.CAVEAT, flag.evidenceValue("Caveat"))
        assertTrue(
            flag.evidenceValue("Caveat").contains("lagging"),
            "the caveat must say the averages lag, not merely exist",
        )
    }
}