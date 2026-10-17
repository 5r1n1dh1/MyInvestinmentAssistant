package com.mia.app.domain.technical

import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.Source
import com.mia.app.domain.model.Timeframe
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * MFI arithmetic, its warmup, and — the part this file spends most of its assertions on — the
 * degenerate cases, where the choice between a number and `null` is the whole decision.
 */
@DisplayName("Money Flow Index")
class MoneyFlowIndexTest {

    private fun candles(
        count: Int,
        close: (Int) -> Double = { 100.0 },
        volume: (Int) -> Long = { 1_000L },
        typical: (Int) -> Double = { close(it) },
    ): List<Candle> = (0 until count).map { i ->
        val t = typical(i)
        Candle(
            symbol = "TEST",
            timeframe = Timeframe.D1,
            date = LocalDate.of(2026, 1, 1).plusDays(i.toLong()),
            open = t,
            high = t,
            low = t,
            close = close(i),
            volume = volume(i),
            source = Source.NSE_BHAVCOPY,
        )
    }

    // ------------------------------------------------------------------- warmup

    @Test
    fun `warmup matches RSI's, so the two indicators are comparable`() {
        assertEquals(TechnicalPolicy.RSI_PERIOD, MoneyFlowIndex.warmupBars(TechnicalPolicy.MFI_PERIOD))
        assertEquals(Int.MAX_VALUE, MoneyFlowIndex.warmupBars(0))
    }

    @Test
    fun `a series shorter than period plus one is all null, never an early value`() {
        val series = candles(TechnicalPolicy.MFI_PERIOD)
        val out = MoneyFlowIndex.compute(series)
        assertEquals(series.size, out.size, "output must stay index-aligned with input")
        assertTrue(out.all { it == null }, "no reading may be invented before the warmup completes")
    }

    @Test
    fun `the first usable reading lands at period, exactly as RSI does`() {
        val series = candles(40) { i -> 100.0 + i }
        val out = MoneyFlowIndex.compute(series)
        assertNull(out[TechnicalPolicy.MFI_PERIOD - 1])
        assertNotNull(out[TechnicalPolicy.MFI_PERIOD], "the seed window itself must produce a reading")
    }

    // ------------------------------------------------------------------ extremes

    @Test
    fun `a strictly rising typical price reads 100`() {
        val series = candles(40) { i -> 100.0 + i }
        // Every bar's money flow is in the rising direction, so the ratio is the 0/0-adjacent
        // limit: honest 100, not the null case below.
        assertEquals(100.0, MoneyFlowIndex.compute(series).last()!!, 1e-9)
    }

    @Test
    fun `a strictly falling typical price reads 0`() {
        val series = candles(40) { i -> 200.0 - i }
        assertEquals(0.0, MoneyFlowIndex.compute(series).last()!!, 1e-9)
    }

    @Test
    fun `a flat typical price is neither direction and carries no money flow`() {
        // Typical price identical every bar ⇒ no positive and no negative flow ⇒ we measured
        // nothing. 50 is the conventional filler and returning it would put a fabricated reading
        // on the chart.
        val series = candles(40)
        assertNull(MoneyFlowIndex.compute(series).last())
    }

    @Test
    fun `zero volume across every bar yields null rather than 50`() {
        val series = candles(40, volume = { 0L }) { i -> 100.0 + i }
        assertNull(MoneyFlowIndex.compute(series).last(), "a stock that did not trade has no MFI")
        assertEquals(100, MoneyFlowIndex.compute(series).count { it == null })
    }

    @Test
    fun `every reading is inside 0 to 100`() {
        val series = candles(60) { i -> 100.0 + 8.0 * kotlin.math.sin(i / 3.0) }
        val out = MoneyFlowIndex.compute(series).filterNotNull()
        assertTrue(out.isNotEmpty())
        out.forEach { assertTrue(it in 0.0..100.0, "MFI out of range: $it") }
    }

    // ------------------------------------------------------------------ weighting

    @Test
    fun `volume decides whether a move counts, which is the whole point`() {
        // Identical price path; the only difference is that the rising half carries all the volume.
        // If MFI were just RSI on typical price, these two would agree.
        val quiet = candles(40, volume = { i -> if (i < 20) 10_000L else 10L })
        val loud = candles(40, volume = { i -> if (i < 20) 10L else 10_000L })

        val quietMfi = MoneyFlowIndex.compute(quiet).last()!!
        val loudMfi = MoneyFlowIndex.compute(loud).last()!!

        assertTrue(
            quietMfi > loudMfi,
            "the same prices with volume on the way up should read higher ($quietMfi) than with " +
                "volume on the way down ($loudMfi)",
        )
    }

    @Test
    fun `the output is index aligned with the input including after the warmup`() {
        val series = candles(50) { i -> 100.0 + (i % 7) }
        assertEquals(series.size, MoneyFlowIndex.compute(series).size)
        assertEquals(50, MoneyFlowIndex.compute(series, period = 5).size)
    }

    @Test
    fun `a non-positive period returns all null instead of dividing by zero`() {
        val series = candles(40)
        assertTrue(MoneyFlowIndex.compute(series, period = 0).all { it == null })
        assertTrue(MoneyFlowIndex.compute(series, period = -1).all { it == null })
        assertNull(latestMfi(series, period = 0))
    }

    // ----------------------------------------------------------------- risk line

    @Test
    fun `a two percent stop reports rupees and the ATR multiple together`() {
        val line = riskLine(price = 1000.0, percent = 2.0, atrValue = 15.0)
        assertEquals(20.0, line.rupees, 1e-9)
        assertEquals(20.0 / 15.0, line.atrMultiple!!, 1e-9)
    }

    @Test
    fun `an unavailable ATR withholds the multiple and keeps the rupee distance`() {
        val line = riskLine(price = 1000.0, percent = 2.0, atrValue = null)
        assertEquals(20.0, line.rupees, 1e-9)
        assertNull(line.atrMultiple, "a ratio against an undefined ATR is a fabricated reading")
        assertNull(line.atr)
    }

    @Test
    fun `a zero or negative ATR is treated as unavailable, not as an infinite multiple`() {
        listOf(0.0, -5.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { bad ->
            val line = riskLine(price = 1000.0, percent = 2.0, atrValue = bad)
            assertNull(line.atrMultiple, "ATR=$bad must not produce a multiple")
        }
    }

    @Test
    fun `the same percentage stop spans very different ATR multiples`() {
        // The reason RiskLine exists: "a 2% stop" alone says nothing.
        val calm = riskLine(price = 1000.0, percent = 2.0, atrValue = 6.0)   // 3.3 ATR
        val wild = riskLine(price = 1000.0, percent = 2.0, atrValue = 30.0)  // 0.67 ATR
        assertEquals(calm.rupees, wild.rupees, 1e-9)
        assertTrue(calm.atrMultiple!! > wild.atrMultiple!!)
        assertTrue(calm.atrMultiple!! > 2.0, "a 2% stop on a 0.6%-ATR stock is multi-day noise")
    }
}