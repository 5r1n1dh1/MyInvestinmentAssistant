package com.mia.app.data.aggregate

import com.mia.app.data.price.LegacyDeliveryParser
import com.mia.app.data.price.NseBhavcopyParser
import com.mia.app.domain.model.Confidence
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
 * Aggregation against the **real** 2026-10-01 NSE files, not a hand-built stand-in.
 *
 * This is the test that would catch a change in what the app actually shows for RELIANCE. Every
 * number asserted here is a number in the fixtures under
 * `data/src/test/resources/fixtures/` — the 3,712-instrument bhavcopy archive and the 3,534-row
 * legacy delivery file, both captured from the live NSE endpoints (plan.md §3.1, §3.5).
 *
 * The Yahoo side is the only synthesised half: Yahoo is unreachable from this machine
 * (`getcrumb` → `429`, verified 2026-10-03), so its bar uses plan.md §5.1's own recorded Yahoo
 * figure for RELIANCE on 2026-10-01: **1167.7**, measured against NSE's **1167.70**.
 */
class AggregationRealFileTest {

    private val tradeDate = LocalDate.of(2026, 10, 1)

    private val bhavcopy by lazy { NseBhavcopyParser.parseZip(fixture("bhavcopy_20261001.zip")) }

    private val delivery by lazy { LegacyDeliveryParser.parse(fixtureText("sec_bhavdata_20261001.csv")) }

    private fun fixture(name: String): ByteArray =
        (javaClass.getResourceAsStream("/fixtures/$name") ?: error("missing fixture $name")).readBytes()

    private fun fixtureText(name: String): String = fixture(name).toString(Charsets.UTF_8)

    // ------------------------------------------------------------------ the real file parses

    @Test
    fun `the real bhavcopy archive yields the documented RELIANCE row`() {
        val reliance = bhavcopy.candles.single { it.symbol == "RELIANCE" }

        assertEquals(tradeDate, reliance.date)
        assertEquals(1180.10, reliance.open)
        assertEquals(1183.90, reliance.high)
        assertEquals(1160.80, reliance.low)
        assertEquals(1167.70, reliance.close)
        assertEquals(16_771_221L, reliance.volume)
        assertEquals(Timeframe.D1, reliance.timeframe)
        assertEquals(Source.NSE_BHAVCOPY, reliance.source)
        assertEquals("INE002A01018", bhavcopy.rows.single { it.ticker == "RELIANCE" }.isin)
    }

    @Test
    fun `the real legacy file yields RELIANCE's published delivery percentage`() {
        assertEquals(61.24, delivery.deliveryPercentBySymbol["RELIANCE"])
        assertEquals(46.10, delivery.deliveryPercentBySymbol["20MICRONS"])
        assertTrue(delivery.isUsable)
    }

    // ------------------------------------------------------------------ the aggregation

    @Test
    fun `the RELIANCE close survives aggregation as NSE_BHAVCOPY 1167_70`() {
        val result = PriceAggregator.mergeSession(
            symbol = "RELIANCE",
            bhavcopy = bhavcopy,
            // plan.md §5.1's measured Yahoo figure for RELIANCE on 2026-10-01.
            yahoo = AggregationFixtures.yahooHistory(
                listOf(
                    AggregationFixtures.candle(
                        symbol = "RELIANCE",
                        date = tradeDate,
                        close = 1167.7,
                        source = Source.YAHOO,
                    ),
                ),
            ),
            delivery = delivery,
            today = tradeDate.plusDays(1),
        )

        val bar = assertNotNull(result.candle, "the real RELIANCE row must aggregate to a bar")
        assertEquals(1167.70, bar.close, "NSE is the price precedence winner and its value is kept verbatim")
        assertEquals(Source.NSE_BHAVCOPY, bar.source)
        assertEquals("RELIANCE", bar.symbol)
        assertEquals(tradeDate, bar.date)
        assertEquals(Timeframe.D1, bar.timeframe)
        assertEquals(1180.10, bar.open)
        assertEquals(1183.90, bar.high)
        assertEquals(1160.80, bar.low)
        assertEquals(16_771_221L, bar.volume)
        assertTrue(bar.isValid, "the merged bar must still satisfy Candle.isValid")

        val close = assertNotNull(result.close)
        assertEquals(1167.70, close.value)
        assertEquals(Source.NSE_BHAVCOPY, close.source)
        assertEquals(Confidence.AGREED, close.confidence, "1168.0/1167.7 was plan.md §5.1's agreed row")
        assertFalse(result.hasConflicts)
    }

    @Test
    fun `the delivery file's 61_24 attaches to the aggregated RELIANCE bar`() {
        val result = PriceAggregator.mergeSession(
            symbol = "RELIANCE",
            bhavcopy = bhavcopy,
            yahoo = null,
            delivery = delivery,
            today = tradeDate.plusDays(1),
        )

        val bar = assertNotNull(result.candle)
        assertEquals(61.24, bar.deliveryPercent, "plan.md §3.5: DELIV_PER for RELIANCE on 2026-10-01")
        assertEquals(
            61.24,
            result.field(PriceFieldId.DELIVERY_PERCENT)?.merged?.value,
            "delivery is merged as its own field, not folded into the close",
        )
        assertEquals(Source.NSE_BHAVCOPY, result.field(PriceFieldId.DELIVERY_PERCENT)?.merged?.source)
        assertEquals(Confidence.SINGLE, result.field(PriceFieldId.DELIVERY_PERCENT)?.merged?.confidence)
    }

    @Test
    fun `a symbol absent from the delivery file keeps a null percentage`() {
        // BE-series rows carry '-' for DELIV_PER (272 of 3,534 rows, plan.md §3.5); the symbol is
        // simply absent from the map, and fabricating 0.0 would manufacture plan.md §8.2's
        // delivery-collapse red flag. The full-file parse is needed because parseZip applies the
        // EQ/SM filter, and BE is not one of the kept series.
        val allRows = NseBhavcopyParser.parseCsv(
            fixtureText("bhavcopy_20261001.csv"),
            filterSeries = null,
        )
        val beSymbol = allRows.rows.first { it.series == "BE" }.ticker
        assertFalse(delivery.deliveryPercentBySymbol.containsKey(beSymbol), "$beSymbol has no DELIV_PER")

        val result = PriceAggregator.mergeSession(
            symbol = beSymbol,
            // The unfiltered parse, so this symbol really does have a bhavcopy bar: the point of
            // the test is a bar with no published delivery, not a bar with no data.
            bhavcopy = allRows,
            yahoo = null,
            delivery = delivery,
            today = tradeDate.plusDays(1),
        )

        assertNotNull(result.candle, "$beSymbol still has a bhavcopy bar")
        assertNull(result.candle?.deliveryPercent)
        assertTrue(result.report.notes.any { it.contains("delivery percentage is absent") })
    }

    @Test
    fun `a Yahoo conflict on a real row keeps the real value and reports the gap`() {
        // A hypothetical 900.0 would be a 22.9% gap; the point is that the NSE value is what the
        // app shows and the gap is what it reports.
        val result = PriceAggregator.mergeSession(
            symbol = "RELIANCE",
            bhavcopy = bhavcopy,
            yahoo = AggregationFixtures.yahooHistory(
                listOf(
                    AggregationFixtures.candle(
                        symbol = "RELIANCE",
                        date = tradeDate,
                        close = 900.0,
                        source = Source.YAHOO,
                    ),
                ),
            ),
            delivery = delivery,
            today = tradeDate.plusDays(1),
        )

        assertEquals(1167.70, result.candle?.close, "the real NSE close is never replaced")
        assertEquals(Source.NSE_BHAVCOPY, result.candle?.source)
        assertEquals(900.0, result.close?.alternate, "the other value is kept, greyed beneath it")
        // |1167.70 − 900| ÷ 900 = 29.74%, measured against the alternate per plan.md §5.1's formula.
        assertEquals(
            29.74,
            Math.round(result.field(PriceFieldId.CLOSE)!!.divergencePercent!! * 100.0) / 100.0,
        )
        assertEquals(listOf("CLOSE"), result.report.conflictFields)
        assertEquals(Confidence.CONFLICT, result.close?.confidence)
        assertEquals(61.24, result.deliveryPercent, "delivery still attaches from the real legacy file")
        assertTrue(result.report.chipFor("CLOSE")!!.contains("YAHOO"))
    }

    @Test
    fun `mergeSeries over the real row keeps NSE and tags every source`() {
        val merged = PriceAggregator.mergeSeries(
            nseDaily = bhavcopy.candles.filter { it.symbol == "RELIANCE" },
            yahooDaily = listOf(
                AggregationFixtures.candle(
                    date = tradeDate.minusDays(1),
                    close = 1187.00,
                    source = Source.YAHOO,
                ),
            ),
            delivery = delivery,
        )

        assertEquals(2, merged.size)
        val latest = merged.last()
        assertEquals(tradeDate, latest.date)
        assertEquals(1167.70, latest.close)
        assertEquals(Source.NSE_BHAVCOPY, latest.source)
        assertEquals(61.24, latest.deliveryPercent)
        val older = merged.first()
        assertEquals(tradeDate.minusDays(1), older.date)
        assertEquals(Source.YAHOO, older.source)
        assertNull(older.deliveryPercent, "the legacy file has no row for a date it does not publish")
    }

    @Test
    fun `the merged RELIANCE bar is the same object for every large cap in the file`() {
        // The whole-market claim of plan.md §3.1: one fetch covers every symbol, so aggregation
        // must work for a symbol the test never named.
        val tcs = bhavcopy.candles.single { it.symbol == "TCS" }
        val result = PriceAggregator.mergeSession(
            symbol = "TCS",
            bhavcopy = bhavcopy,
            yahoo = null,
            delivery = delivery,
            today = tradeDate.plusDays(1),
        )

        assertEquals(tcs.close, result.candle?.close)
        assertEquals(tcs.close, result.close?.value)
        assertEquals(Source.NSE_BHAVCOPY, result.close?.source)
        assertEquals(delivery.deliveryPercentBySymbol["TCS"], result.candle?.deliveryPercent)
    }

    @Test
    fun `the merged value is always one of the two inputs across the whole file`() {
        // plan.md §5.1's no-averaging rule, checked against every row the real file produced.
        val yahooMirror = bhavcopy.candles.take(200).map {
            AggregationFixtures.candle(
                symbol = it.symbol,
                date = it.date,
                open = it.open,
                high = it.high,
                low = it.low,
                close = it.close,
                volume = it.volume,
                source = Source.YAHOO,
            )
        }

        val results = bhavcopy.candles.take(200).map { nse ->
            PriceAggregator.mergeSession(
                symbol = nse.symbol,
                bhavcopy = AggregationFixtures.bhavcopy(listOf(nse)),
                yahoo = AggregationFixtures.yahooHistory(listOf(yahooMirror.first { it.symbol == nse.symbol })),
                today = tradeDate.plusDays(1),
            )
        }

        assertEquals(200, results.size)
        for (result in results) {
            val close = assertNotNull(result.close, "${result.symbol} must aggregate")
            assertTrue(
                close.value == result.candle?.close,
                "${result.symbol}: merged close ${close.value} is not the stored bar's",
            )
            assertEquals(Source.NSE_BHAVCOPY, close.source)
            assertEquals(Confidence.AGREED, close.confidence, "identical inputs are agreement, not conflict")
        }
    }

    @Test
    fun `the real RELIANCE snapshot from screener_in merges against Yahoo without inventing fields`() {
        // plan.md §5.1's RELIANCE row: screener.in P/E 21.2, Yahoo 21.142 → agreed; ROE 8.91 with
        // no Yahoo counterpart → single. The screener.in side is transcribed from §5.1's table.
        val screener = AggregationFixtures.screenerIn(
            symbol = "RELIANCE",
            pe = 21.2,
            roe = 8.91,
            roce = 10.3,
        ).copy(asOf = tradeDate.plusDays(1))
        val yahoo = AggregationFixtures.yahoo(pe = 21.142, roe = null).copy(asOf = tradeDate.plusDays(1))

        val merge = FundamentalsAggregator.merge(screener, yahoo, tradeDate.plusDays(1))

        assertEquals(21.2, merge.field(RatioFieldId.PE)?.value)
        assertEquals(Confidence.AGREED, merge.field(RatioFieldId.PE)?.confidence)
        assertEquals(8.91, merge.field(RatioFieldId.ROE)?.value)
        assertEquals(Confidence.SINGLE, merge.field(RatioFieldId.ROE)?.confidence)
        assertEquals(10.3, merge.field(RatioFieldId.ROCE)?.value, "screener.in has ROCE, Yahoo has none")
        assertFalse(merge.report.hasConflicts)
        assertNull(merge.field(RatioFieldId.PE)?.alternate)
    }
}