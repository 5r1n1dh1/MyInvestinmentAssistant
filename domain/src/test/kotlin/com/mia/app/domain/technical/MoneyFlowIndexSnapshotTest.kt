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
 * MFI as it arrives through [IndicatorSnapshot], rather than through its own function.
 *
 * The point of these assertions is the **snapshot contract** in `Indicators.kt`: every field is
 * index-aligned with the input bars and every value is nullable, where `null` always means
 * "insufficient history" and never zero. A new indicator that computes correctly on its own can
 * still break that contract at the snapshot boundary — by being off by one bar, or by defaulting an
 * absent reading to a number — and the failure would show up on a chart rather than in a test.
 */
@DisplayName("MFI inside the shared indicator snapshot")
class MoneyFlowIndexSnapshotTest {

    private fun candles(count: Int, volume: (Int) -> Long = { 1_000L }): List<Candle> =
        (0 until count).map { i ->
            val close = 100.0 + if (i % 2 == 1) 3.0 else -1.0
            Candle(
                symbol = "TEST",
                timeframe = Timeframe.D1,
                date = LocalDate.of(2026, 1, 1).plusDays(i.toLong()),
                open = close,
                high = close + 1.0,
                low = close - 1.0,
                close = close,
                volume = volume(i),
                source = Source.NSE_BHAVCOPY,
            )
        }

    @Test
    fun `the snapshot's MFI is index aligned with the bars`() {
        val series = candles(50)
        val snapshot = IndicatorSnapshot.of(series)
        assertEquals(series.size, snapshot.mfi.size, "MFI must stay index-aligned with the input")
    }

    @Test
    fun `mfiValue agrees with the standalone function`() {
        val series = candles(50)
        assertEquals(
            MoneyFlowIndex.compute(series).last(),
            IndicatorSnapshot.of(series).mfiValue,
            "the snapshot must not re-derive MFI differently from its own function",
        )
    }

    @Test
    fun `a short series leaves mfiValue null rather than a number`() {
        val snapshot = IndicatorSnapshot.of(candles(5))
        assertNull(snapshot.mfiValue, "warmup unfinished is 'not enough history', not zero")
    }

    @Test
    fun `a zero-volume series leaves mfiValue null rather than the conventional 50`() {
        // The same contract as the standalone function, asserted at the boundary the UI reads.
        val snapshot = IndicatorSnapshot.of(candles(50, volume = { 0L }))
        assertNull(snapshot.mfiValue, "a stock that did not trade has no MFI to show")
    }

    @Test
    fun `MFI is populated on the same fixture the other indicators use`() {
        // Shares the snapshot with RSI/MACD/ATR; if the wiring silently dropped it, everything else
        // would still look correct and only the chart would be missing a reading.
        val snapshot = IndicatorSnapshot.of(candles(120))
        assertNotNull(snapshot.mfiValue)
        assertTrue(snapshot.mfiValue!! in 0.0..100.0)
        assertNotNull(snapshot.rsi.value, "the snapshot's other indicators are unaffected")
    }

    @Test
    fun `a risk line from the snapshot reports both units or withholds the multiple`() {
        val snapshot = IndicatorSnapshot.of(candles(60))
        val price = 1_000.0
        val line = riskLine(price = price, percent = 2.0, atrValue = snapshot.atrValue)
        assertEquals(20.0, line.rupees, 1e-9)
        // Same contract at the boundary: the rupee distance is always reportable, the ATR multiple
        // only when ATR exists.
        if (snapshot.atrValue != null) assertNotNull(line.atrMultiple) else assertNull(line.atrMultiple)
    }
}