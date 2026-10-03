package com.mia.app.data.history

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
 * Golden and resilience parsing of Yahoo's v8 chart document.
 *
 * **Payload provenance, stated once so no test name overstates it:** the document in
 * `fixtures/yahoo_chart_reliance_2y_1d_sample.json` is hand-written to the real v8 schema
 * (see `YahooChartFixtures` for why: Yahoo answers `429` from this machine, verified 2026-10-03).
 * The 500-row and 261-row documents are generated from a documented recipe. What is asserted
 * below is therefore *this implementation against the documented contract*, not a live capture —
 * except where a number comes from the real bhavcopy fixture (RELIANCE's 2026-10-01 close of
 * 1167.70), which is genuine.
 */
class YahooChartParserGoldenTest {

    private val symbol = "RELIANCE"
    private val timeframe = Timeframe.D1

    private fun parse(body: String = YahooChartFixtures.sample(), tf: Timeframe = timeframe) =
        YahooChartParser.parse(body, symbol, tf, range = "2y", interval = tf.name)

    // ------------------------------------------------------------------ row counts and drops

    @Test
    fun `the hand-written payload yields the documented row counts`() {
        val result = parse()

        // 9 timestamps in the fixture; three rows carry a null OHLC member
        // (index 2 null open, index 3 null high, index 8 null close).
        assertEquals(9, result.rowsRequested)
        assertEquals(6, result.candles.size)
        assertEquals(3, result.rowsDropped)
        assertTrue(result.isUsable)
        assertFalse(result.health.ok, "a parse that dropped rows is partial, not ok (plan.md §12)")
        assertEquals("2026-09-22", result.candles.first().date.toString())
        assertEquals("2026-10-01", result.candles.last().date.toString())
    }

    @Test
    fun `a null OHLC member drops the row instead of zero-filling it`() {
        val result = parse()
        val dates = result.candles.map { it.date }

        // The fixture's nulls are: open on 2026-09-24, high on 2026-09-25, close on 2026-10-02.
        assertFalse(dates.contains(LocalDate.of(2026, 9, 24)), "the null-open row must be gone")
        assertFalse(dates.contains(LocalDate.of(2026, 9, 25)), "the null-high row must be gone")
        assertFalse(dates.contains(LocalDate.of(2026, 10, 2)), "the null-close row must be gone")
        // Nothing anywhere in the series may be a zero price.
        assertTrue(result.candles.all { it.open > 0 && it.high > 0 && it.low > 0 && it.close > 0 })
        assertTrue(
            result.health.notes.any { it.contains("3 of 9 row(s) dropped") },
            "the drop count must be visible: ${result.health.notes}",
        )
    }

    @Test
    fun `the surviving bars carry the exact values from the payload`() {
        val result = parse()
        val bar = result.candles.single { it.date == LocalDate.of(2026, 10, 1) }

        // These are the real 2026-10-01 NSE bhavcopy values for RELIANCE, also asserted by
        // NseBhavcopyParserFixtureTest, so the two sources can be compared honestly.
        assertEquals(1180.10, bar.open)
        assertEquals(1183.90, bar.high)
        assertEquals(1160.80, bar.low)
        assertEquals(1167.70, bar.close)
        assertEquals(16_771_221L, bar.volume)
        assertEquals(Source.YAHOO, bar.source)
        assertEquals(Timeframe.D1, bar.timeframe)
        assertEquals("RELIANCE", bar.symbol)
        // Yahoo publishes no delivery figure; null, never 0.0 (see Volume.kt).
        assertNull(bar.deliveryPercent)
        assertTrue(bar.isValid)
    }

    @Test
    fun `dates are read in the exchange timezone so an IST session keeps its own date`() {
        val result = parse()

        // 1790826300 = 2026-10-01T03:45Z = 2026-10-01 09:15 IST.
        assertTrue(
            result.candles.any { it.date == LocalDate.of(2026, 10, 1) },
            result.candles.joinToString { it.date.toString() },
        )
        assertTrue(
            result.health.notes.any { it.contains("Asia/Calcutta") },
            "the zone used must be reported: ${result.health.notes}",
        )
    }

    @Test
    fun `an unusable exchange timezone falls back to UTC and says so`() {
        val body = """
            {"chart":{"result":[{"meta":{"symbol":"RELIANCE.NS","exchangeTimezoneName":"Mars/Olympus",
            "timezone":"IST"},
            "timestamp":[1790826300],
            "indicators":{"quote":[{"open":[1180.10],"high":[1183.90],"low":[1160.80],
            "close":[1167.70],"volume":[16771221]}]}}],"error":null}}
        """.trimIndent()

        val result = YahooChartParser.parse(body, symbol, timeframe)

        assertEquals(LocalDate.of(2026, 10, 1), result.candles.single().date)
        assertTrue(
            result.health.notes.any { it.contains("are unusable") || it.contains("fell back") },
            "the fallback must be reported: ${result.health.notes}",
        )
    }

    // ------------------------------------------------------------------ adjclose

    @Test
    fun `adjclose is kept beside the unadjusted series and never mixed into it`() {
        val result = parse()

        // The fixture's adjclose sits below close on every surviving row (1167.70 → 1162.31).
        assertEquals(result.candles.size, result.adjustedCloses.size)
        val bar = result.candles.single { it.date == LocalDate.of(2026, 10, 1) }
        val adj = result.adjustedCloses.single { it.date == LocalDate.of(2026, 10, 1) }
        assertEquals(1167.70, bar.close, "the candle keeps the raw quote close")
        assertEquals(1162.31, adj.close, "the adjusted close lives in its own series")
        assertTrue(
            result.health.notes.any { it.contains("unadjusted quote block") },
            "the basis of the stored series must be stated: ${result.health.notes}",
        )
    }

    // ------------------------------------------------------------------ splits

    @Test
    fun `a split event is parsed with its date and ratio`() {
        val result = parse()

        val split = result.candles.let { result.splits.single() }
        assertEquals("RELIANCE", split.symbol)
        assertEquals(LocalDate.of(2026, 6, 10), split.date)
        assertEquals(1, split.numerator)
        assertEquals(2, split.denominator)
        assertEquals("1:2", split.ratio)
        assertEquals("1:2", split.description)
        assertEquals(0.5, split.shareMultiplier)
        assertTrue(
            result.health.sectionsFound.contains("events.splits"),
            result.health.sectionsFound.toString(),
        )
        assertTrue(
            result.health.notes.any { it.contains("split event") },
            "a corporate action must be announced: ${result.health.notes}",
        )
    }

    @Test
    fun `a split with a zero denominator is dropped and counted`() {
        val body = """
            {"chart":{"result":[{"meta":{"symbol":"X.NS","exchangeTimezoneName":"Asia/Calcutta"},
            "timestamp":[1790826300],
            "events":{"splits":{
              "1781049600":{"date":1781049600,"numerator":1,"denominator":2,"splitRatio":"1:2"},
              "1782000000":{"date":1782000000,"numerator":1,"denominator":0,"splitRatio":"1:0"},
              "1783000000":{"numerator":2,"denominator":1,"splitRatio":"2:1"}}},
            "indicators":{"quote":[{"open":[1180.10],"high":[1183.90],"low":[1160.80],
            "close":[1167.70],"volume":[16771221]}]}}],"error":null}}
        """.trimIndent()

        val result = YahooChartParser.parse(body, "X", timeframe)

        assertEquals(1, result.splits.size)
        assertEquals("1:2", result.splits.single().description)
        assertTrue(
            result.health.notes.any { it.contains("2 of 3 split event(s) dropped") },
            "dropped splits must be counted: ${result.health.notes}",
        )
    }

    // ------------------------------------------------------------------ the documented shapes

    @Test
    fun `the daily document has the 500-row shape plan 3_2 documents`() {
        val result = YahooChartParser.parse(
            YahooChartFixtures.dailyDocument(500),
            symbol,
            Timeframe.D1,
            range = "2y",
            interval = "1d",
        )

        assertEquals(500, result.rowsRequested)
        assertEquals(500, result.candles.size)
        assertEquals(0, result.rowsDropped)
        assertTrue(result.health.ok)
        assertTrue(result.isComplete)
        assertEquals(
            YahooEndpoints.EXPECTED_DAILY_ROWS,
            result.candles.size,
            "plan.md §14 records 1d/2y → 500 rows for RELIANCE/TCS/INFY/HDFCBANK",
        )
        assertTrue(result.candles.zipWithNext().all { (a, b) -> a.date.isBefore(b.date) })
        assertEquals(LocalDate.of(2024, 10, 1), result.candles.first().date)
        assertTrue(result.candles.all { it.symbol == "RELIANCE" && it.source == Source.YAHOO })
    }

    @Test
    fun `the weekly document has the 261-row shape plan 3_2 documents`() {
        val result = YahooChartParser.parse(
            YahooChartFixtures.weeklyDocument(261),
            symbol,
            Timeframe.W1,
            range = "5y",
            interval = "1wk",
        )

        assertEquals(261, result.rowsRequested)
        assertEquals(261, result.candles.size)
        assertEquals(0, result.rowsDropped)
        assertTrue(result.candles.all { it.timeframe == Timeframe.W1 })
        assertTrue(result.candles.zipWithNext().all { (a, b) -> a.date.isBefore(b.date) })
        assertTrue(
            result.candles.size >= YahooEndpoints.EXPECTED_WEEKLY_ROWS,
            "plan.md §14 records 1wk/5y → 261 rows",
        )
    }

    @Test
    fun `both documented shapes together clear the P3 gate of 490 daily rows`() {
        val daily = YahooChartParser.parse(YahooChartFixtures.dailyDocument(500), symbol, Timeframe.D1)
        val weekly = YahooChartParser.parse(YahooChartFixtures.weeklyDocument(261), symbol, Timeframe.W1)

        // plan.md §11's P3 gate: "fetch 2y for 20 large caps; assert ≥490 rows".
        assertTrue(
            daily.candles.size >= YahooEndpoints.P3_GATE_MIN_DAILY_ROWS,
            "P3 gate needs ≥${YahooEndpoints.P3_GATE_MIN_DAILY_ROWS} daily rows",
        )
        assertTrue(daily.isUsable && weekly.isUsable)
    }

    // ------------------------------------------------------------------ resilience

    @Test
    fun `a body that is not chart JSON fails with a parse reason`() {
        val result = YahooChartParser.parse(YahooChartFixtures.notChartJson(), symbol, timeframe)

        assertEquals(YahooFailureReason.UNPARSEABLE, result.failure)
        assertFalse(result.health.ok)
        assertTrue(result.candles.isEmpty())
        assertNotNull(result.health.notes.firstOrNull())
    }

    @Test
    fun `a populated chart error is reported as no data not as a parse failure`() {
        val result = YahooChartParser.parse(YahooChartFixtures.errored(), symbol, timeframe)

        assertEquals(YahooFailureReason.NO_DATA, result.failure)
        assertTrue(result.health.notes.first().contains("No data found"), result.health.notes.toString())
        assertTrue(
            result.health.notes.first().contains("demerger"),
            "plan.md §9's TATAMOTORS case should be named: ${result.health.notes}",
        )
    }

    @Test
    fun `an empty timestamp array is no data`() {
        val result = YahooChartParser.parse(YahooChartFixtures.emptyResult(), symbol, timeframe)

        assertEquals(YahooFailureReason.NO_DATA, result.failure)
        assertTrue(result.candles.isEmpty())
    }

    @Test
    fun `a missing quote block is unparseable`() {
        val body = """{"chart":{"result":[{"meta":{},"timestamp":[1,2]}],"error":null}}"""

        val result = YahooChartParser.parse(body, symbol, timeframe)

        assertEquals(YahooFailureReason.UNPARSEABLE, result.failure)
    }

    @Test
    fun `an all-null series is an empty series failure and never an empty success`() {
        val body = """
            {"chart":{"result":[{"meta":{"symbol":"X.NS","exchangeTimezoneName":"Asia/Calcutta"},
            "timestamp":[1790826300,1790912700],
            "indicators":{"quote":[{"open":[null,null],"high":[null,null],"low":[null,null],
            "close":[null,null],"volume":[1,2]}]}}],"error":null}}
        """.trimIndent()

        val result = YahooChartParser.parse(body, "X", timeframe)

        assertEquals(YahooFailureReason.EMPTY_SERIES, result.failure)
        assertFalse(result.isUsable)
        assertFalse(result.health.ok)
        assertEquals(2, result.rowsRequested)
    }

    @Test
    fun `a null volume is recorded as zero shares and counted, not dropped`() {
        val body = """
            {"chart":{"result":[{"meta":{"symbol":"X.NS","exchangeTimezoneName":"Asia/Calcutta"},
            "timestamp":[1790826300],
            "indicators":{"quote":[{"open":[10.0],"high":[12.0],"low":[9.0],"close":[11.0],
            "volume":[null]}]}}],"error":null}}
        """.trimIndent()

        val result = YahooChartParser.parse(body, "X", timeframe)

        assertEquals(1, result.candles.size)
        assertEquals(0L, result.candles.single().volume)
        assertEquals(0, result.rowsDropped)
        assertTrue(result.health.notes.any { it.contains("null volume") }, result.health.notes.toString())
    }

    @Test
    fun `a structurally invalid bar high below low is dropped`() {
        val body = """
            {"chart":{"result":[{"meta":{"symbol":"X.NS","exchangeTimezoneName":"Asia/Calcutta"},
            "timestamp":[1790826300],
            "indicators":{"quote":[{"open":[10.0],"high":[9.0],"low":[11.0],"close":[10.5],
            "volume":[100]}]}}],"error":null}}
        """.trimIndent()

        val result = YahooChartParser.parse(body, "X", timeframe)

        assertEquals(0, result.candles.size)
        assertEquals(1, result.rowsDropped)
        assertEquals(YahooFailureReason.EMPTY_SERIES, result.failure)
    }

    @Test
    fun `out-of-order timestamps are sorted and duplicates collapsed`() {
        val body = """
            {"chart":{"result":[{"meta":{"symbol":"X.NS","exchangeTimezoneName":"Asia/Calcutta"},
            "timestamp":[1790912700,1790826300,1790826300],
            "indicators":{"quote":[{"open":[12.0,10.0,11.0],"high":[13.0,11.0,12.0],
            "low":[11.0,9.0,10.0],"close":[12.5,10.5,99.0],"volume":[10,20,30]}]}}],
            "error":null}}
        """.trimIndent()

        val result = YahooChartParser.parse(body, "X", timeframe)

        assertEquals(2, result.candles.size)
        assertTrue(result.candles[0].date.isBefore(result.candles[1].date))
        assertEquals(10.5, result.candles.first().close, "the duplicate timestamp is collapsed")
        assertTrue(result.health.notes.any { it.contains("ascending timestamp order") }, result.health.notes.toString())
        assertTrue(result.health.notes.any { it.contains("duplicate timestamp") }, result.health.notes.toString())
    }

    @Test
    fun `unknown fields anywhere in the document are ignored`() {
        // Yahoo adds fields without notice; a forward-compatible parser is plan.md §13's ask.
        val body = """
            {"chart":{"result":[{"somethingNew":true,
            "meta":{"symbol":"X.NS","exchangeTimezoneName":"Asia/Calcutta","brandNewMeta":[1,2]},
            "timestamp":[1790826300],
            "events":{"dividends":{"1790826300":{"amount":1.0,"date":1790826300}},
                      "capitalGains":{"1790826300":{"amount":1.0}}},
            "indicators":{"quote":[{"open":[10.0],"high":[11.0],"low":[9.0],"close":[10.5],
            "volume":[100],"futureQuoteField":"x"}],"adjclose":[{"adjclose":[10.5]}],
            "futureIndicatorBlock":{"a":1}}}],"error":null},"futureRootField":7}
        """.trimIndent()

        val result = YahooChartParser.parse(body, "X", timeframe)

        assertTrue(result.isUsable)
        assertEquals(1, result.candles.size)
    }

    @Test
    fun `the meta block is reported so two parses can be compared`() {
        val result = parse()

        // A partial parse collapses its notes into one string (`ParserHealth.partial`), so the
        // assertions are substring-based rather than element-based.
        val notes = result.health.notes.joinToString(" | ")
        assertTrue(notes.contains("meta: symbol=RELIANCE.NS"), notes)
        assertTrue(notes.contains("exchange=NS"), notes)
        assertTrue(notes.contains("currency=INR"), notes)
        assertTrue(notes.contains("granularity=1d"), notes)
        assertEquals(1167.7, result.regularMarketPrice)
        assertEquals(1187.0, result.previousClose)
        assertEquals("2y", result.range)
        assertEquals("1d", result.interval)
    }

    @Test
    fun `the minimal document parses to exactly one candle`() {
        val result = YahooChartParser.parse(YahooChartFixtures.minimal(), "X", Timeframe.D1)

        assertEquals(1, result.candles.size)
        assertEquals(100.0, result.candles.single().close)
        assertTrue(
            result.health.notes.any { it.contains("no meta block") },
            "a missing meta block must be stated: ${result.health.notes}",
        )
    }
}