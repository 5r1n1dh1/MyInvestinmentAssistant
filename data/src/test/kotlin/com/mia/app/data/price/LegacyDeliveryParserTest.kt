package com.mia.app.data.price

import com.mia.app.domain.model.Timeframe
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Legacy delivery-percentage parsing (plan.md §3.5).
 *
 * The file is *optional*: plan.md §3.5 marks its availability for recent dates UNVERIFIED, so
 * the tests that matter most are the ones proving it cannot break the price layer.
 */
class LegacyDeliveryParserTest {

    private val tradeDate = LocalDate.of(2026, 10, 1)

    @Test
    fun `the real file parses 3534 rows with 3262 delivery percentages`() {
        val result = LegacyDeliveryParser.parse(Fixtures.legacyDeliveryCsv)

        assertEquals(3534, result.rowsTotal)
        assertEquals(0, result.rowsSkipped)
        assertEquals(3262, result.deliveryPercentBySymbol.size)
        // 272 rows carry '-' for DELIV_PER (the BE series is delivery-ineligible).
        assertEquals(272, result.rowsWithoutDelivery)
        assertEquals(3534 - 272, result.deliveryPercentBySymbol.size)
    }

    @Test
    fun `RELIANCE delivery percentage is 61_24`() {
        val result = LegacyDeliveryParser.parse(Fixtures.legacyDeliveryCsv)

        assertEquals(61.24, result.deliveryPercentBySymbol["RELIANCE"])
        assertEquals(63.31, result.deliveryPercentBySymbol["HDFCBANK"])
        assertEquals(51.96, result.deliveryPercentBySymbol["INFY"])
        assertEquals(54.90, result.deliveryPercentBySymbol["TCS"])
        assertEquals(46.10, result.deliveryPercentBySymbol["20MICRONS"])
    }

    @Test
    fun `the leading space on every column name is handled`() {
        // Raw header, verbatim from the file: "SYMBOL, SERIES, DATE1, …".
        assertTrue(
            Fixtures.legacyDeliveryCsv.lineSequence().first().startsWith("SYMBOL, SERIES, DATE1, PREV_CLOSE"),
            "fixture no longer has the leading-space headers this parser defends against",
        )

        val result = LegacyDeliveryParser.parse(Fixtures.legacyDeliveryCsv)

        assertTrue(result.isUsable)
        assertTrue(result.health.ok, "a clean file must not be reported as degraded: ${result.health}")
        assertEquals("EQ", result.series)
        assertEquals(tradeDate, result.tradDt)
    }

    @Test
    fun `the dd-MMM-yyyy date is parsed not guessed`() {
        assertEquals(tradeDate, LegacyDeliveryParser.parseLegacyDate("01-Oct-2026"))
        assertEquals(tradeDate, LegacyDeliveryParser.parseLegacyDate(" 01-Oct-2026 "))
        assertEquals(LocalDate.of(2026, 12, 25), LegacyDeliveryParser.parseLegacyDate("25-Dec-2026"))
        assertNull(LegacyDeliveryParser.parseLegacyDate("2026-10-01"))
        assertNull(LegacyDeliveryParser.parseLegacyDate(""))
    }

    @Test
    fun `a dash delivery percentage leaves the symbol out instead of recording zero`() {
        // A fabricated 0.0% would be read by Volume.kt as the "delivery collapsed" red flag of
        // plan.md §8.2 — a red flag manufactured out of missing data.
        val text = "SYMBOL, SERIES, DATE1, DELIV_QTY, DELIV_PER\n" +
            "20MICRONS, EQ, 01-Oct-2026, 42666, 46.10\n" +
            "3IINFOLTD, BE, 01-Oct-2026, -, -\n"

        val result = LegacyDeliveryParser.parse(text)

        assertEquals(46.10, result.deliveryPercentBySymbol["20MICRONS"])
        assertNull(result.deliveryPercentBySymbol["3IINFOLTD"])
        assertEquals(2, result.rowsTotal)
        assertEquals(1, result.rowsWithoutDelivery)
        assertEquals(0, result.rowsSkipped)
        assertTrue(result.health.ok)
        assertTrue(result.health.notes.any { it.contains("'-' for DELIV_PER") })
    }

    @Test
    fun `garbage text fails with a reason instead of returning an empty map`() {
        val result = LegacyDeliveryParser.parse("<html>404</html>")

        assertTrue(result.deliveryPercentBySymbol.isEmpty())
        assertFalse(result.health.ok)
        assertTrue(result.health.notes.single().contains("SYMBOL"))
        assertFalse(result.isUsable)
    }

    @Test
    fun `empty text fails with a reason`() {
        val result = LegacyDeliveryParser.parse("")

        assertFalse(result.health.ok)
        assertTrue(result.health.notes.single().contains("no header line"))
    }

    @Test
    fun `a file with no delivery percentage at all fails rather than reporting zero`() {
        val text = "SYMBOL, SERIES, DATE1, DELIV_PER\nA, EQ, 01-Oct-2026, -\nB, BE, 01-Oct-2026, -\n"

        val result = LegacyDeliveryParser.parse(text)

        assertFalse(result.isUsable)
        assertFalse(result.health.ok)
        assertTrue(result.health.notes.single().contains("no delivery percentage parsed"))
    }

    @Test
    fun `applying delivery fills only the symbols the file published`() = runTest {
        val delivery = LegacyDeliveryParser.parse(Fixtures.legacyDeliveryCsv)
        val candles = NseBhavcopyParser.parseZip(Fixtures.bhavcopyZip).candles

        val enriched = delivery.applyTo(candles)

        assertEquals(candles.size, enriched.size)
        val reliance = assertNotNull(enriched.firstOrNull { it.symbol == "RELIANCE" })
        assertEquals(61.24, reliance.deliveryPercent)
        assertEquals(Timeframe.D1, reliance.timeframe)
        assertEquals(1167.70, reliance.close)
        // Every one of the 3,051 EQ+SM symbols is published in the legacy file.
        assertEquals(0, enriched.count { it.deliveryPercent == null })
    }

    @Test
    fun `a symbol missing from the delivery file keeps null rather than zero`() {
        val candles = NseBhavcopyParser.parseCsv(
            "TradDt,TckrSymb,SctySrs,OpnPric,HghPric,LwPric,ClsPric,TtlTradgVol\n" +
                "2026-10-01,PUBLISHED,EQ,10.00,12.00,9.00,11.00,5000\n" +
                "2026-10-01,MISSING,EQ,10.00,12.00,9.00,11.00,5000\n",
        ).candles
        val delivery = LegacyDeliveryResult(deliveryPercentBySymbol = mapOf("PUBLISHED" to 46.10))

        val enriched = delivery.applyTo(candles)

        assertEquals(46.10, assertNotNull(enriched.first { it.symbol == "PUBLISHED" }).deliveryPercent)
        assertNull(assertNotNull(enriched.first { it.symbol == "MISSING" }).deliveryPercent)
    }

    @Test
    fun `an unusable delivery result leaves every candle untouched`() = runTest {
        val candles = NseBhavcopyParser.parseZip(Fixtures.bhavcopyZip).candles
        val broken = LegacyDeliveryParser.parse("<html>nope</html>")

        assertEquals(candles, broken.applyTo(candles))
    }

    // ------------------------------------------------------------- graceful source failure

    @Test
    fun `a broken legacy fetch degrades gracefully and never throws`() = runTest {
        val transport = FakeHttpTransport { throw IOException("connection reset") }

        val result = LegacyDeliverySource(transport).fetch(tradeDate)

        assertTrue(result.deliveryPercentBySymbol.isEmpty())
        assertFalse(result.health.ok)
        assertTrue(result.health.notes.single().contains("connection reset"))
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun `a legacy 404 degrades gracefully`() = runTest {
        val transport = FakeHttpTransport { FakeHttpTransport.status(404) }

        val result = LegacyDeliverySource(transport).fetch(tradeDate)

        assertFalse(result.isUsable)
        assertFalse(result.health.ok)
        assertTrue(result.health.notes.single().contains("HTTP 404"))
    }

    @Test
    fun `an html error page served with 200 degrades gracefully`() = runTest {
        val transport = FakeHttpTransport { FakeHttpTransport.text("<html>Access Denied</html>") }

        val result = LegacyDeliverySource(transport).fetch(tradeDate)

        assertFalse(result.isUsable)
        assertFalse(result.health.ok)
    }

    @Test
    fun `a working legacy fetch requests the documented url and parses the fixture`() = runTest {
        val transport = FakeHttpTransport { request ->
            FakeHttpTransport.text(
                if (request.url == BhavcopyUrls.legacy(tradeDate)) {
                    Fixtures.legacyDeliveryCsv
                } else {
                    "not found"
                },
            )
        }

        val result = LegacyDeliverySource(transport).fetch(tradeDate)

        assertEquals(BhavcopyUrls.legacy(tradeDate), transport.urls.single())
        assertTrue(result.isUsable)
        assertEquals(61.24, result.deliveryPercentBySymbol["RELIANCE"])
        assertTrue(result.health.ok)
    }

    @Test
    fun `the legacy symbol space matches the filtered bhavcopy symbol space exactly`() = runTest {
        // Verified against the real 2026-10-01 files: the legacy file's EQ+SM symbols are the
        // same 3,051 as the bhavcopy's, and all 3,051 carry a DELIV_PER. This is what lets the
        // two sources be joined on TckrSymb at all.
        val candles = NseBhavcopyParser.parseZip(Fixtures.bhavcopyZip).candles
        val delivery = LegacyDeliveryParser.parse(Fixtures.legacyDeliveryCsv)

        val covered = candles.count { delivery.deliveryPercentBySymbol.containsKey(it.symbol) }
        assertEquals(3051, covered)
        assertEquals(0, candles.count { it.deliveryPercent != null })
    }
}
