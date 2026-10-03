package com.mia.app.data.history

import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.ParserHealth
import com.mia.app.domain.model.Source
import com.mia.app.domain.model.Timeframe
import com.mia.app.domain.technical.CandleResampler
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The 1D/1W tabs must survive Yahoo being unavailable (plan.md §3.2, §13).
 *
 * The rule under test is not "fall back gracefully" — it is *label what is being shown*.
 * A derived weekly series that renders like a vendor one would be a fabrication, which is the
 * failure plan.md §12 and §13 both point at.
 */
class HistoryFallbackPlannerTest {

    /** The last session of the 70-session default cache, so "as of" is never hard-coded twice. */
    private val asOf: LocalDate = YahooChartFixtures.tradingDays(LocalDate.of(2026, 6, 1), 70).last()

    private fun daily(
        symbol: String = "RELIANCE",
        days: Int = 70,
        start: LocalDate = LocalDate.of(2026, 6, 1),
        source: Source = Source.NSE_BHAVCOPY,
    ): List<Candle> = YahooChartFixtures.tradingDays(start, days).mapIndexed { i, date ->
        val close = 1100.0 + i
        Candle(
            symbol = symbol,
            timeframe = Timeframe.D1,
            date = date,
            open = close - 1.0,
            high = close + 2.0,
            low = close - 2.5,
            close = close,
            volume = 1_000_000L + i * 1_000L,
            source = source,
        )
    }

    private fun yahooResult(symbol: String = "RELIANCE", bars: Int = 500): HistoryResult {
        val candles = YahooChartFixtures.tradingDays(LocalDate.of(2024, 10, 1), bars).mapIndexed { i, date ->
            val close = 900.0 + i * 0.5
            Candle(
                symbol = symbol,
                timeframe = Timeframe.D1,
                date = date,
                open = close - 1.0,
                high = close + 2.0,
                low = close - 2.5,
                close = close,
                volume = 900_000L,
                source = Source.YAHOO,
            )
        }
        return HistoryResult(
            symbol = symbol,
            timeframe = Timeframe.D1,
            candles = candles,
            health = ParserHealth(ok = true, sectionsFound = listOf("timestamp")),
            rowsRequested = bars,
            range = "2y",
            interval = "1d",
            url = "https://query1.finance.yahoo.com/v8/finance/chart/$symbol.NS?range=2y&interval=1d",
            httpCode = 200,
        )
    }

    private fun rateLimited(symbol: String = "RELIANCE"): HistoryResult =
        HistoryResult.failed(
            symbol = symbol,
            timeframe = Timeframe.D1,
            failure = YahooFailureReason.RATE_LIMITED,
            reason = "Yahoo gave no usable chart for $symbol (2y/1d): HTTP 429 on both hosts",
            range = "2y",
            interval = "1d",
            attempts = 4,
        )

    // ------------------------------------------------------------------ provenance

    @Test
    fun `a usable Yahoo series wins the series and is labelled YAHOO`() {
        val plans = HistoryFallbackPlanner.plans("RELIANCE", daily(), yahooDaily = yahooResult())

        val dailyPlan = plans.first { it.timeframe == Timeframe.D1 }
        assertEquals(HistoryProvenance.YAHOO, dailyPlan.provenance)
        assertEquals(Source.YAHOO, dailyPlan.provenance.source)
        assertEquals(500, dailyPlan.bars)
        assertEquals(dailyPlan.candles.last().date, dailyPlan.asOf)
        assertTrue(dailyPlan.barsSufficient)
    }

    @Test
    fun `a split inside the Yahoo window is surfaced as a note`() {
        val base = yahooResult()
        val withSplit = base.copy(
            splits = listOf(StockSplit("RELIANCE", LocalDate.of(2025, 3, 10), 1, 2, "1:2")),
        )

        val plan = HistoryFallbackPlanner.plans("RELIANCE", daily(), yahooDaily = withSplit)
            .first { it.timeframe == Timeframe.D1 }

        assertTrue(
            plan.notes.any { it.contains("UNADJUSTED") && it.contains("1:2") },
            plan.notes.toString(),
        )
    }

    @Test
    fun `Yahoo unavailable serves D1 from the bhavcopy and says why`() {
        val plans = HistoryFallbackPlanner.plans("RELIANCE", daily(days = 70), yahooDaily = rateLimited())

        val dailyPlan = plans.first { it.timeframe == Timeframe.D1 }
        assertEquals(HistoryProvenance.NSE_BHAVCOPY, dailyPlan.provenance)
        assertEquals(Source.NSE_BHAVCOPY, dailyPlan.provenance.source)
        assertEquals(70, dailyPlan.bars)
        assertEquals(asOf, dailyPlan.asOf)
        assertEquals(YahooFailureReason.RATE_LIMITED, dailyPlan.yahooFailure)
        assertTrue(
            dailyPlan.notes.any { it.contains("RATE_LIMITED") },
            "the reason Yahoo was dropped must be visible: ${dailyPlan.notes}",
        )
        assertTrue(dailyPlan.health.ok)
    }

    @Test
    fun `W1 is derived from the daily bars and labelled DERIVED_WEEKLY`() {
        val plans = HistoryFallbackPlanner.plans("RELIANCE", daily(days = 70), yahooWeekly = rateLimited())

        val weekly = plans.first { it.timeframe == Timeframe.W1 }
        assertEquals(HistoryProvenance.DERIVED_WEEKLY, weekly.provenance)
        assertEquals(Source.DERIVED, weekly.provenance.source)
        assertTrue(weekly.provenance.isDerived)
        assertTrue(weekly.bars > 0)
        assertTrue(weekly.bars < 70, "70 daily sessions cannot be 70 weekly bars")
        assertEquals(
            weekly.bars,
            CandleResampler.toWeekly(daily(days = 70)).size,
            "the derivation must be the domain resampler's, not a second implementation",
        )
        assertTrue(
            weekly.notes.any { it.contains("DERIVED, not published") },
            weekly.notes.toString(),
        )
        assertTrue(
            weekly.notes.any { it.contains("CandleResampler.toWeekly") },
            weekly.notes.toString(),
        )
    }

    @Test
    fun `the derived weekly series matches the resampler bar for bar`() {
        val bars = daily(days = 65)
        val plan = HistoryFallbackPlanner.plan("RELIANCE", Timeframe.W1, bars, null)
        val expected = CandleResampler.toWeekly(bars)

        assertEquals(expected, plan.candles)
        assertEquals(expected.last().date, plan.asOf)
        // Each weekly bar is dated the last session in its week (the domain convention).
        assertTrue(plan.candles.all { it.timeframe == Timeframe.W1 })
    }

    @Test
    fun `a vendor weekly series is preferred over a derived one when it exists`() {
        val weeklyResult = yahooResult(bars = 261).copy(timeframe = Timeframe.W1, range = "5y", interval = "1wk")

        val weekly = HistoryFallbackPlanner
            .plan("RELIANCE", Timeframe.W1, daily(days = 70), weeklyResult)

        assertEquals(HistoryProvenance.YAHOO, weekly.provenance)
        assertEquals(261, weekly.bars)
    }

    // ------------------------------------------------------------------ honesty about thin history

    @Test
    fun `a single bhavcopy session yields one weekly bar and says history is insufficient`() {
        val plan = HistoryFallbackPlanner.plan("RELIANCE", Timeframe.W1, daily(days = 1), null)

        assertEquals(1, plan.bars)
        assertFalse(plan.barsSufficient, "1 weekly bar is not a 40-bar series")
        assertFalse(plan.health.ok, "plan.md §12: a series below the gate is partial, not ok")
        assertTrue(
            plan.notes.any { it.contains("not enough history") },
            plan.notes.toString(),
        )
        assertTrue(
            plan.summary().contains("not enough history"),
            "the UI line must say it: ${plan.summary()}",
        )
    }

    @Test
    fun `the minimum bar counts are plan 6_4s hard gate`() {
        assertEquals(60, HistoryPlan.MIN_DAILY_BARS)
        assertEquals(40, HistoryPlan.MIN_WEEKLY_BARS)
        assertEquals(60, HistoryPlan.minBarsFor(Timeframe.D1))
        assertEquals(40, HistoryPlan.minBarsFor(Timeframe.W1))

        val daily60 = HistoryFallbackPlanner.plan("RELIANCE", Timeframe.D1, daily(days = 60), null)
        assertEquals(60, daily60.bars)
        assertTrue(daily60.barsSufficient, "exactly 60 daily bars clears the gate")
        val daily59 = HistoryFallbackPlanner.plan("RELIANCE", Timeframe.D1, daily(days = 59), null)
        assertFalse(daily59.barsSufficient, "59 daily bars does not")
        assertFalse(daily59.health.ok, "a series below the gate is partial, not ok (plan.md §12)")
    }

    @Test
    fun `an empty cache with no Yahoo produces a failed plan, not an empty chart`() {
        val plans = HistoryFallbackPlanner.plans("GHOST", emptyList(), yahooDaily = rateLimited("GHOST"))

        for (plan in plans) {
            assertTrue(plan.isEmpty)
            assertFalse(plan.health.ok)
            assertNull(plan.asOf)
            assertTrue(
                plan.notes.any { it.contains("cannot be served") },
                "${plan.timeframe}: ${plan.notes}",
            )
        }
    }

    @Test
    fun `candles for another symbol are not used`() {
        val bars = daily("RELIANCE", days = 70) + daily("TCS", days = 70)

        val plan = HistoryFallbackPlanner.plan("TCS", Timeframe.D1, bars, null)

        assertEquals(70, plan.bars)
        assertTrue(plan.candles.all { it.symbol == "TCS" })
    }

    @Test
    fun `an offline cache with no Yahoo attempt at all still produces a labelled plan`() {
        val plan = HistoryFallbackPlanner.plan("RELIANCE", Timeframe.D1, daily(days = 30), yahoo = null)

        assertEquals(HistoryProvenance.NSE_BHAVCOPY, plan.provenance)
        assertNull(plan.yahooFailure, "nothing failed; Yahoo was simply not consulted")
        assertTrue(plan.barsSufficient.not())
        assertTrue(plan.summary().startsWith("NSE end-of-day bhavcopy"), plan.summary())
    }

    @Test
    fun `plans come back in D1 then W1 order`() {
        val plans = HistoryFallbackPlanner.plans("RELIANCE", daily(days = 70))

        assertEquals(listOf(Timeframe.D1, Timeframe.W1), plans.map { it.timeframe })
    }

    @Test
    fun `delivery percentage presence is reported but never invented`() {
        val plain = HistoryFallbackPlanner.plan("RELIANCE", Timeframe.D1, daily(days = 5), null)
        assertTrue(plain.notes.none { it.contains("delivery percentage") })

        val withDelivery = daily(days = 3).let { bars ->
            bars.map { if (it.date == bars.last().date) it.copy(deliveryPercent = 61.24) else it }
        }
        val plan = HistoryFallbackPlanner.plan("RELIANCE", Timeframe.D1, withDelivery, null)
        assertEquals(61.24, plan.candles.last().deliveryPercent)
        assertTrue(
            plan.notes.any { it.contains("legacy sec_bhavdata") },
            plan.notes.toString(),
        )
    }

    @Test
    fun `plansFrom bridges the bhavcopy parse result`() {
        val bhavcopy = com.mia.app.data.price.BhavcopyParseResult(
            candles = daily(days = 10),
            health = ParserHealth(ok = true, sectionsFound = listOf("TCKRSYMB")),
            rowsTotal = 10,
            tradDt = asOf,
        )

        val plans = HistoryFallbackPlanner.plansFrom(bhavcopy, "RELIANCE", null)

        assertEquals(2, plans.size)
        assertEquals(HistoryProvenance.NSE_BHAVCOPY, plans.first().provenance)
        assertNotNull(plans.first().asOf)
    }

    @Test
    fun `the summary line states source as-of and bar count`() {
        val plan = HistoryFallbackPlanner.plan("RELIANCE", Timeframe.D1, daily(days = 70), null)

        val summary = plan.summary()
        assertTrue(summary.contains("NSE end-of-day bhavcopy"), summary)
        assertTrue(summary.contains("as of $asOf"), summary)
        assertTrue(summary.contains("70 D1 bar(s)"), summary)
    }
}