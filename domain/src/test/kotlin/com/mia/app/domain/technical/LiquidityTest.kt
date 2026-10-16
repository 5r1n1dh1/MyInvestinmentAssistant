package com.mia.app.domain.technical

import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.Source
import com.mia.app.domain.model.Timeframe
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The liquidity gate's arithmetic and, just as importantly, its **wording** (planv2 W3's SME check).
 *
 * The fixtures are constructed, not captured, so the expected median is arithmetic a reader can
 * redo on paper. The wording assertions exist because a "thin trading" line is the single easiest
 * place in this app to slide from a measurement into a verdict, and the only thing that stops that
 * is a test that reads the sentence.
 */
class LiquidityTest {

    private val start = LocalDate.parse("2026-01-01")

    /** One valid daily bar. Turnover on it is `volume × close`. */
    private fun bar(
        i: Int,
        close: Double,
        volume: Long,
        symbol: String = "SMCO",
    ): Candle = Candle(
        symbol = symbol,
        timeframe = Timeframe.D1,
        date = start.plusDays(i.toLong()),
        open = close,
        high = close,
        low = close,
        close = close,
        volume = volume,
        deliveryPercent = null,
        source = Source.NSE_BHAVCOPY,
    )

    /** [count] bars each turning over exactly [rupees], starting [from] sessions after [start]. */
    private fun flatTurnover(count: Int, rupees: Double, from: Int = 0): List<Candle> =
        List(count) { i -> bar(i + from, close = 1.0, volume = rupees.toLong()) }

    // --------------------------------------------------------------------- the median

    @Test
    fun `the median of an even window is the upper middle of the sorted sessions`() {
        // 20 sessions at ₹10 lakh and 20 at ₹90 lakh. The median of 40 values sits at index 20 of
        // the sorted list, which is the first of the ₹90 lakh half — the documented rule for an
        // even-sized sample, stated here so a change to it has to break a test.
        val candles = flatTurnover(20, 10_00_000.0) + flatTurnover(20, 90_00_000.0)

        val profile = Liquidity.of(candles)

        assertEquals(40, profile.sessionsUsed)
        assertEquals(90_00_000.0, profile.medianDailyTurnover)
    }

    @Test
    fun `one outsized session does not move the median`() {
        // The whole reason the gate uses a median: a single 100x session is invisible to it, while
        // a mean would report this stock as liquid.
        val candles = flatTurnover(Liquidity.MIN_SESSIONS, 20_00_000.0) +
            bar(Liquidity.MIN_SESSIONS, close = 1.0, volume = 2_000_00_000L)

        val profile = Liquidity.of(candles)

        assertEquals(20_00_000.0, profile.medianDailyTurnover)
        assertEquals(2_000_00_000.0, profile.maximumDailyTurnover)
    }

    @Test
    fun `the window is the newest sessions, not the first ones`() {
        val old = flatTurnover(30, 1_00_00_000.0, from = 0)
        val recent = flatTurnover(30, 5_00_000.0, from = 100)

        val profile = Liquidity.of(old + recent, window = 30)

        assertEquals(30, profile.windowSessions)
        assertEquals(5_00_000.0, profile.medianDailyTurnover)
    }

    @Test
    fun `bars are sorted by date before the window is taken`() {
        // Handed over newest-first on purpose: the stored series is ascending by contract, but the
        // measurement must not silently return the wrong end of it if a caller disagrees.
        val ascending = flatTurnover(30, 5_00_000.0)
        val shuffled = ascending.reversed()

        assertEquals(
            Liquidity.of(ascending).medianDailyTurnover,
            Liquidity.of(shuffled).medianDailyTurnover,
        )
    }

    // ------------------------------------------------------------------- the verdict

    @Test
    fun `a median under fifty lakh is thin trading`() {
        val candles = flatTurnover(Liquidity.MIN_SESSIONS, 38_00_000.0)

        val profile = Liquidity.of(candles)

        assertTrue(profile.isThin)
        assertEquals(true, profile.isThinTrading.valueOrNull())
    }

    @Test
    fun `a median over fifty lakh is not thin trading and says nothing`() {
        val candles = flatTurnover(Liquidity.MIN_SESSIONS, 51_00_000.0)

        val profile = Liquidity.of(candles)

        assertFalse(profile.isThin)
        assertEquals(false, profile.isThinTrading.valueOrNull())
        // No verdict means no notice: a stock that is not thin gets a blank, not a reassurance.
        assertNull(profile.notice())
    }

    @Test
    fun `a median exactly at the threshold is not thin`() {
        // `<` not `<=`: the threshold is the line the app flags *below*, and a median that lands
        // exactly on it has met the stated bar rather than fallen under it.
        val candles = flatTurnover(Liquidity.MIN_SESSIONS, Liquidity.THIN_TRADING_THRESHOLD_RUPEES)

        assertFalse(Liquidity.of(candles).isThin)
    }

    @Test
    fun `too few sessions is an unmeasured verdict, never a false reassurance`() {
        val candles = flatTurnover(Liquidity.MIN_SESSIONS - 1, 10_00_000.0)

        val profile = Liquidity.of(candles)

        assertNull(profile.medianDailyTurnover)
        assertFalse(profile.isThin)
        assertNull(profile.isThinTrading.valueOrNull())
        val reason = assertNotNull(profile.isThinTrading.reasonOrNull())
        assertTrue(reason.contains("${Liquidity.MIN_SESSIONS - 1} usable session"), reason)
    }

    @Test
    fun `an empty series is unmeasured rather than zero`() {
        val profile = Liquidity.of(emptyList())

        assertNull(profile.medianDailyTurnover)
        assertFalse(profile.isThin)
        assertNotNull(profile.notice())
    }

    @Test
    fun `a bar with no usable close is skipped rather than counted as a zero turnover day`() {
        // Counting it as zero would drag the median down and manufacture the very verdict the
        // notice is trying to state honestly.
        val candles = flatTurnover(Liquidity.MIN_SESSIONS, 30_00_000.0) +
            bar(Liquidity.MIN_SESSIONS, close = 0.0, volume = 1_000_000L)

        val profile = Liquidity.of(candles)

        assertEquals(Liquidity.MIN_SESSIONS, profile.sessionsUsed)
        assertEquals(30_00_000.0, profile.medianDailyTurnover)
    }

    // --------------------------------------------------------------------- the wording

    @Test
    fun `the thin trading notice carries the measured median and the window length`() {
        val profile = Liquidity.of(flatTurnover(Liquidity.MIN_SESSIONS, 38_00_000.0))

        val notice = assertNotNull(profile.notice())

        assertTrue(notice.contains("Thin trading"), notice)
        assertTrue(notice.contains("₹38 lakh"), notice)
        assertTrue(notice.contains("${Liquidity.MIN_SESSIONS} stored session"), notice)
        assertTrue(notice.contains("₹50 lakh"), notice)
    }

    @Test
    fun `the notice states a measurement and carries no verdict words`() {
        val notice = assertNotNull(
            Liquidity.of(flatTurnover(Liquidity.MIN_SESSIONS, 5_00_000.0)).notice(),
        )

        // Compliance is asserted, not remembered: the sentence names a number and never says the
        // stock is bad, risky, safe or worth avoiding.
        assertTrue(com.mia.app.domain.compliance.AdviceLanguage.isClean(notice), notice)
        val banned = listOf("risky", "dangerous", "avoid", "unsafe", "bad", "illiquid")
        banned.forEach { word ->
            assertFalse(notice.lowercase().contains(word), "'$word' in: $notice")
        }
    }

    @Test
    fun `the unmeasured notice explains the absence instead of leaving a blank`() {
        val notice = assertNotNull(Liquidity.of(flatTurnover(5, 10_00_000.0)).notice())

        assertTrue(notice.contains("Turnover could not be measured"), notice)
        assertTrue(notice.contains("${Liquidity.MIN_SESSIONS}"), notice)
    }

    // --------------------------------------------------------------------- formatting

    @Test
    fun `rupees are written in the words an Indian quote terminal uses`() {
        assertEquals("₹450", formatRupees(450.0))
        assertEquals("₹1 lakh", formatRupees(1_00_000.0))
        assertEquals("₹38 lakh", formatRupees(38_41_299.3))
        assertEquals("₹2 crore", formatRupees(2_00_00_000.0))
        assertEquals("₹12.5 crore", formatRupees(12_50_00_000.0))
        assertEquals("unknown", formatRupees(null))
        assertEquals("unknown", formatRupees(Double.NaN))
    }

    // --------------------------------------------------------------------- the SME series

    @Test
    fun `the SME series is identified by its published code`() {
        assertTrue(Liquidity.isSmeSeries("SM"))
        assertTrue(Liquidity.isSmeSeries(" sm "))
        assertFalse(Liquidity.isSmeSeries("EQ"))
        // An unknown series must not be assumed thin — that would flag ordinary large caps.
        assertFalse(Liquidity.isSmeSeries(null))
    }
}