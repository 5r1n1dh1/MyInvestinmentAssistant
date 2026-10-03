package com.mia.app.domain.redflag.technical

import com.mia.app.domain.model.FlagGroup
import com.mia.app.domain.model.FlagSeverity
import com.mia.app.domain.model.FlagTab
import com.mia.app.domain.model.Source
import com.mia.app.domain.model.Timeframe
import com.mia.app.domain.technical.IndicatorSnapshot
import com.mia.app.domain.technical.TechnicalPolicy
import com.mia.app.domain.technical.isDeliveryCollapsing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `RF-T-5` — `DELIVERY_COLLAPSE` (plan.md §8.2; `redflags-spec.md` §7.5).
 *
 * `Candle.deliveryPercent` exists only in the legacy NSE `sec_bhavdata` file (plan.md §3.5,
 * availability `UNVERIFIED`) and is `null` on every bar of the shared OHLCV fixture. So this test
 * class carries more weight than the other four: the rule has to work on candles that carry delivery
 * data **and** abstain, loudly, on candles that do not. The absence cases are asserted as *skips*,
 * never as "clean".
 */
class DeliveryCollapseRuleTest {

    @Test
    fun `fires when delivery falls to ten percent against a fifty percent average`() {
        val candles = TechnicalSeries.deliveryCollapse()
        assertTrue(IndicatorSnapshot.of(candles).isDeliveryCollapsingFlag)
        assertTrue(isDeliveryCollapsing(candles))

        val flag = DeliveryCollapseRule.evaluate(technicalContext(candles)).single()

        assertEquals(TechnicalFlagIds.DELIVERY_COLLAPSE, flag.id)
        assertEquals(FlagGroup.TECHNICAL, flag.group)
        assertEquals(FlagTab.TECHNICALS, flag.tab)
        assertEquals(FlagSeverity.MEDIUM, flag.severity)
        assertEquals(DeliveryCollapseRule.THRESHOLD, flag.threshold)
        flag.assertEveryMeasurementIsDated()
    }

    @Test
    fun `evidence shows the reading, its own average, the ratio and the window coverage`() {
        val flag = DeliveryCollapseRule.evaluate(technicalContext(TechnicalSeries.deliveryCollapse())).single()

        assertEquals("10.0 %", flag.evidenceValue("Delivery % (last bar)"))
        assertEquals("46.0 %", flag.evidenceValue("20-session average delivery %"))
        assertEquals("0.22", flag.evidenceValue("Delivery % against its own average"))
        assertEquals("0.40", flag.evidenceValue("Collapse threshold ratio"))
        assertEquals("2", flag.evidenceValue("Consecutive collapsing sessions"))
        assertEquals("1,000,000 shares", flag.evidenceValue("Traded quantity (last bar)"))
        assertEquals("100,000 shares", flag.evidenceValue("Delivery quantity (last bar)"))
        assertEquals("20 of 20 sessions (100 %)", flag.evidenceValue("Window coverage"))
        assertEquals(
            "Delivery was 10.0 % on the last 2 sessions, against a 20-session average of 46.0 % — " +
                "22% of its own average.",
            flag.title,
        )
    }

    @Test
    fun `escalates to HIGH when the collapse followed high participation and a falling price`() {
        val flag = DeliveryCollapseRule
            .evaluate(technicalContext(TechnicalSeries.deliveryCollapseAfterHighParticipation()))
            .single()

        assertEquals(FlagSeverity.HIGH, flag.severity)
        assertEquals("10.0 %", flag.evidenceValue("Delivery % (last bar)"))
        assertEquals("37.5 %", flag.evidenceValue("20-session average delivery %"))
        assertEquals("0.27", flag.evidenceValue("Delivery % against its own average"))
        assertEquals("5", flag.evidenceValue("Consecutive collapsing sessions"))
    }

    @Test
    fun `stays silent when delivery holds at its own average`() {
        // The firing fixture's first 28 sessions: 50 % on every bar, so nothing collapses.
        val quiet = TechnicalSeries.deliveryCollapse().map { it.copy(deliveryPercent = 50.0) }
        assertEquals(false, IndicatorSnapshot.of(quiet).isDeliveryCollapsingFlag)
        assertTrue(DeliveryCollapseRule.evaluate(technicalContext(quiet)).isEmpty())
        assertEquals(null, DeliveryCollapseRule.skipReason(technicalContext(quiet)))
    }

    // -------------------------------------------------------------------------- the skip path

    @Test
    fun `abstains, and says why, when no session carries a delivery reading`() {
        val candles = TechnicalSeries.deliveryAbsent()
        assertTrue(candles.all { it.deliveryPercent == null })

        assertTrue(DeliveryCollapseRule.evaluate(technicalContext(candles)).isEmpty())
        assertEquals(
            "Delivery percentage is absent from all of the last " +
                "${TechnicalPolicy.DELIVERY_SMA_PERIOD} sessions; our sources did not publish it.",
            DeliveryCollapseRule.skipReason(technicalContext(candles)),
        )
    }

    @Test
    fun `abstains, and says why, when coverage is below the three-quarters floor`() {
        // 4 of the last 20 sessions carry a reading: below the 15-session (75 %) floor.
        val candles = TechnicalSeries.deliveryCollapse().mapIndexed { i, bar ->
            if (i in 10..25) bar.copy(deliveryPercent = null) else bar
        }
        val covered = candles.takeLast(TechnicalPolicy.DELIVERY_SMA_PERIOD).count { it.deliveryPercent != null }
        assertEquals(4, covered)
        assertTrue(covered < Math.ceil(TechnicalPolicy.DELIVERY_COVERAGE_REQUIREMENT * 20).toInt())

        assertTrue(DeliveryCollapseRule.evaluate(technicalContext(candles)).isEmpty())
        assertEquals(
            "Delivery percentage is available for only 4 of the last " +
                "${TechnicalPolicy.DELIVERY_SMA_PERIOD} sessions; the check needs at least 15 of " +
                "the window.",
            DeliveryCollapseRule.skipReason(technicalContext(candles)),
        )
    }

    @Test
    fun `abstains on a weekly view because the legacy feed is daily only`() {
        val candles = TechnicalSeries.deliveryCollapse().map { it.copy(timeframe = Timeframe.W1) }
        val context = technicalContext(candles, timeframe = Timeframe.W1)

        assertTrue(DeliveryCollapseRule.evaluate(context).isEmpty())
        assertEquals(DeliveryCollapseRule.WEEKLY_SKIP, DeliveryCollapseRule.skipReason(context))
        assertEquals(1, TechnicalRedFlagRules.skips(context).count { it.ruleId == TechnicalFlagIds.DELIVERY_COLLAPSE })
    }

    @Test
    fun `withholds below the liquidity floor and names the floor`() {
        val candles = TechnicalSeries.deliveryCollapseThinLiquidity()
        val turnover = candles.takeLast(20).map { it.volume.toDouble() }.average()
        assertEquals(1_000.0, turnover)
        assertTrue(turnover < TechnicalRedFlagPolicy.MIN_LIQUIDITY_SHARES_PER_SESSION)

        assertTrue(DeliveryCollapseRule.evaluate(technicalContext(candles)).isEmpty())
        val skip = DeliveryCollapseRule.skipReason(technicalContext(candles))
        assertTrue(skip!!.startsWith("Delivery percentage is not evaluated below 100,000 shares of average daily turnover"))
        assertTrue(skip.contains("small caps can be manipulated"), "the book's reason must be stated")
    }

    @Test
    fun `abstains when the 20-session window does not exist`() {
        val candles = TechnicalSeries.deliveryCollapse().take(12)
        assertTrue(DeliveryCollapseRule.evaluate(technicalContext(candles)).isEmpty())
        assertEquals(
            "The 20-session delivery window needs ${TechnicalPolicy.DELIVERY_SMA_PERIOD} daily " +
                "sessions; this series has 12.",
            DeliveryCollapseRule.skipReason(technicalContext(candles)),
        )
    }

    @Test
    fun `provenance and caveat are first-class rows`() {
        val flag = DeliveryCollapseRule.evaluate(technicalContext(TechnicalSeries.deliveryCollapse())).single()
        assertEquals(Source.NSE_BHAVCOPY, flag.evidenceRow("Delivery % (last bar)").source)
        assertEquals(Source.DERIVED, flag.evidenceRow("Delivery quantity (last bar)").source)
        assertEquals(DeliveryCollapseRule.CAVEAT, flag.evidenceValue("Caveat"))
        assertTrue(DeliveryCollapseRule.CAVEAT.contains("not necessarily bearish"))
        assertEquals(
            TechnicalPolicy.DELIVERY_COLLAPSE_RATIO,
            TechnicalFlagFormat.ratio(0.40).toDouble(),
            0.005,
            "the printed threshold is the policy constant, not a second number",
        )
    }
}