package com.mia.app.data.price

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
 * The index-chart parser against the committed synthetic fixture.
 *
 * ### Why a *synthetic* fixture, not a capture
 * The bhavcopy's golden tests use real bytes because the risk there is a format change in a file
 * this app depends on. The risk on the index path is different: it is **misunderstanding a
 * field**. A hand-authored document makes every field accounted for — the nulls, the duplicate
 * timestamp and the 03:45-UTC stamp are each there because a test defends them, not because
 * Yahoo happened to send them on the day of a capture.
 *
 * ### What the fixture contains
 * 64 weekday sessions from 2026-01-01 to 2026-03-31, each stamped 03:45 UTC (09:15 IST), with:
 * * row 2 — `open`/`high`/`low` null, so the bar must collapse to `O=H=L=C=close`;
 * * row 3 — `close` null, so the row must be **dropped** and counted;
 * * rows 57 and 58 — the same timestamp, so one must be collapsed and the later value kept.
 */
class YahooIndexChartParserTest {

    private fun fixture(): String =
        IndexTestFixtures.text("yahoo_index_nsei_sample.json")

    private fun parse(body: String = fixture()) =
        YahooIndexChartParser.parse(body = body, symbol = IndexSymbol.NIFTY_50)

    // ------------------------------------------------------------------- the happy path

    @Test
    fun `the fixture parses into 62 ascending sessions from a 64-row document`() {
        val result = parse()

        assertTrue(result.isUsable, result.unavailableReason)
        assertEquals(64, result.rowsRequested)
        // 64 rows − 1 duplicate − 1 null close.
        assertEquals(62, result.candles.size)
        assertEquals(1, result.rowsDropped)
        assertNull(result.failure)
        assertEquals(IndexSymbol.NIFTY_50, result.symbol)
    }

    @Test
    fun `every bar carries the index wire symbol, Yahoo provenance and no delivery figure`() {
        val first = parse().candles.first()

        assertEquals("^NSEI", first.symbol)
        assertEquals(Timeframe.D1, first.timeframe)
        assertEquals(Source.YAHOO, first.source)
        assertEquals(0L, first.volume, "an index has no traded share volume")
        // null, never 0.0 — see plan.md §8.2.
        assertNull(first.deliveryPercent)
        assertTrue(first.isValid, "every parsed bar must be structurally valid")
    }

    @Test
    fun `dates are read in the exchange timezone, not UTC`() {
        val candles = parse().candles

        // 03:45 UTC on 2026-01-01 is 09:15 IST the same day. Reading it as UTC would still give
        // the same date here, so the assertion that matters is the *session* boundary one below.
        assertEquals(LocalDate.parse("2026-01-01"), candles.first().date)
        assertEquals(LocalDate.parse("2026-03-31"), candles.last().date)
        assertEquals(
            candles.map { it.date }.sorted(),
            candles.map { it.date },
            "the series must be ascending: the engine's newest-bar read assumes it",
        )
        assertTrue(
            parse().health.notes.any { it.contains("Asia/Calcutta") },
            parse().health.notes.toString(),
        )
    }

    @Test
    fun `a session stamped late in the UTC day keeps its own IST date`() {
        // 1767294000 is 2026-01-01T19:00:00Z, which is 2026-01-02T00:30 IST. Read as UTC the bar
        // would land on 2026-01-01, and because `relativeStrength` aligns the two series *by
        // date*, one day's shift would silently empty the intersection and unscore the Context
        // factor with nothing to show for it.
        val body = """
            {"chart":{"result":[{"meta":{"exchangeTimezoneName":"Asia/Calcutta"},
            "timestamp":[1767294000],"indicators":{"quote":[{"close":[100.0]}]}}],"error":null}}
        """.trimIndent()

        val result = YahooIndexChartParser.parse(body, IndexSymbol.NIFTY_50)

        assertEquals(LocalDate.parse("2026-01-02"), result.candles.single().date)
    }

    @Test
    fun `an unusable timezone falls back to UTC and says which zone it used`() {
        val body = """
            {"chart":{"result":[{"meta":{"exchangeTimezoneName":"Not/AZone","timezone":"IST"},
            "timestamp":[1767294000],"indicators":{"quote":[{"close":[100.0]}]}}],"error":null}}
        """.trimIndent()

        val result = YahooIndexChartParser.parse(body, IndexSymbol.NIFTY_50)

        // "IST" is an abbreviation, not a ZoneId, so the fallback is UTC — and it is reported.
        assertEquals(LocalDate.parse("2026-01-01"), result.candles.single().date)
        assertTrue(
            result.health.notes.any { it.contains("timestamps read as UTC") },
            result.health.notes.toString(),
        )
    }

    @Test
    fun `a payload with no meta at all still parses and reports the zone it assumed`() {
        val body = """
            {"chart":{"result":[{"timestamp":[1767239100],
            "indicators":{"quote":[{"close":[100.0]}]}}],"error":null}}
        """.trimIndent()

        val result = YahooIndexChartParser.parse(body, IndexSymbol.NIFTY_50)

        assertTrue(result.isUsable)
        assertTrue(
            result.health.notes.any { it.contains("no meta block") },
            result.health.notes.toString(),
        )
    }

    // ------------------------------------------------------------------- the quirks

    @Test
    fun `a row with a null close is dropped and counted, never zero-filled`() {
        val result = parse()

        assertEquals(1, result.rowsDropped)
        assertTrue(result.candles.none { it.close == 0.0 }, "no bar may be invented at zero")
        assertTrue(
            result.health.notes.any { it.contains("1 of 64 row(s) dropped") },
            result.health.notes.toString(),
        )
        // The dropped row is session 4; the sessions either side of it survive untouched.
        assertEquals(LocalDate.parse("2026-01-02"), result.candles[1].date)
        assertEquals(LocalDate.parse("2026-01-05"), result.candles[2].date)
    }

    @Test
    fun `a bar with only a close collapses to a flat bar and says so`() {
        val result = parse()
        val collapsed = result.candles.first { it.date == LocalDate.parse("2026-01-05") }

        assertEquals(collapsed.close, collapsed.open)
        assertEquals(collapsed.close, collapsed.high)
        assertEquals(collapsed.close, collapsed.low)
        assertTrue(
            result.health.notes.any { it.contains("no open/high/low") },
            result.health.notes.toString(),
        )
    }

    @Test
    fun `a duplicate timestamp is collapsed and the later value is kept`() {
        val result = parse()
        val dates = result.candles.map { it.date }

        assertEquals(dates.distinct().size, dates.size, "no date may appear twice")
        assertTrue(
            result.health.notes.any { it.contains("1 duplicate timestamp") },
            result.health.notes.toString(),
        )
        // The later of the two duplicate rows carried the higher close, and that is the one kept.
        assertEquals(
            26011.3,
            result.candles.last { it.date == LocalDate.parse("2026-03-23") }.close,
        )
    }

    @Test
    fun `rows are sorted when the payload is not ascending`() {
        val body = """
            {"chart":{"result":[{"meta":{"exchangeTimezoneName":"Asia/Calcutta"},
            "timestamp":[1767325500,1767239100],
            "indicators":{"quote":[{"close":[101.0,100.0]}]}}],"error":null}}
        """.trimIndent()

        val result = YahooIndexChartParser.parse(body, IndexSymbol.NIFTY_50)

        assertTrue(result.health.notes.any { it.contains("ascending timestamp order") })
        assertEquals(LocalDate.parse("2026-01-01"), result.candles.first().date)
    }

    // ------------------------------------------------------------------ the failure modes

    @Test
    fun `a body that is not chart JSON fails as unparseable and never throws`() {
        val result = YahooIndexChartParser.parse(
            "<html>rate limited</html>",
            IndexSymbol.NIFTY_50,
        )

        assertFalse(result.isUsable)
        assertEquals(IndexFailureReason.UNPARSEABLE, result.failure)
        assertTrue(result.candles.isEmpty())
        assertNotNull(result.unavailableReason)
    }

    @Test
    fun `a body with no chart object names what it saw`() {
        val result = YahooIndexChartParser.parse("""{"nope":1}""", IndexSymbol.NIFTY_50)

        assertEquals(IndexFailureReason.UNPARSEABLE, result.failure)
        assertTrue(assertNotNull(result.unavailableReason).contains("no 'chart' object"))
    }

    @Test
    fun `a populated chart error is no-data and suggests the rename it probably is`() {
        val body = """
            {"chart":{"result":null,"error":{"code":"Not Found",
            "description":"No data found, symbol may be delisted"}}}
        """.trimIndent()

        val result = YahooIndexChartParser.parse(body, IndexSymbol.NIFTY_50)

        assertEquals(IndexFailureReason.NO_DATA, result.failure)
        assertTrue(assertNotNull(result.unavailableReason).contains("^NSEI"))
        assertTrue(result.candles.isEmpty())
    }

    @Test
    fun `an empty timestamp array is no-data rather than an empty benchmark`() {
        val body = """{"chart":{"result":[{"timestamp":[],"indicators":{"quote":[{"close":[]}]}}],"error":null}}"""

        val result = YahooIndexChartParser.parse(body, IndexSymbol.NIFTY_50)

        assertEquals(IndexFailureReason.NO_DATA, result.failure)
        assertTrue(result.candles.isEmpty())
    }

    @Test
    fun `a missing quote block is unparseable`() {
        val body = """{"chart":{"result":[{"timestamp":[1767239100]}],"error":null}}"""

        val result = YahooIndexChartParser.parse(body, IndexSymbol.NIFTY_50)

        assertEquals(IndexFailureReason.UNPARSEABLE, result.failure)
        assertTrue(assertNotNull(result.unavailableReason).contains("indicators.quote[0]"))
    }

    @Test
    fun `every close null is an empty series, not a flat index`() {
        val body = """
            {"chart":{"result":[{"meta":{"exchangeTimezoneName":"Asia/Calcutta"},
            "timestamp":[1767239100,1767325500],
            "indicators":{"quote":[{"close":[null,null]}]}}],"error":null}}
        """.trimIndent()

        val result = YahooIndexChartParser.parse(body, IndexSymbol.NIFTY_50)

        assertEquals(IndexFailureReason.EMPTY_SERIES, result.failure)
        assertEquals(2, result.rowsDropped)
        assertTrue(result.candles.isEmpty())
    }

    @Test
    fun `a non-positive close is dropped rather than used as a denominator`() {
        val body = """
            {"chart":{"result":[{"meta":{"exchangeTimezoneName":"Asia/Calcutta"},
            "timestamp":[1767239100,1767325500],
            "indicators":{"quote":[{"close":[0.0,101.0]}]}}],"error":null}}
        """.trimIndent()

        val result = YahooIndexChartParser.parse(body, IndexSymbol.NIFTY_50)

        assertEquals(1, result.rowsDropped)
        assertTrue(result.candles.none { it.close == 0.0 })
    }

    // ------------------------------------------------------------------- the seam itself

    @Test
    fun `the caret is percent-encoded in the URL and nothing else is`() {
        val url = IndexEndpoints.chartUrl(
            host = "query1.finance.yahoo.com",
            symbol = IndexSymbol.NIFTY_50,
            range = "1y",
            crumb = "AbCd123",
        )

        assertEquals(
            "https://query1.finance.yahoo.com/v8/finance/chart/%5ENSEI?range=1y&interval=1d" +
                "&crumb=AbCd123",
            url,
        )
    }

    @Test
    fun `each index symbol maps to the wire symbol the probe measured`() {
        assertEquals("^NSEI", IndexSymbol.NIFTY_50.wire)
        assertEquals("^NSEMDCP50", IndexSymbol.MIDCAP_50.wire)
        assertEquals("^NSEMDCP150", IndexSymbol.MIDCAP_150.wire)
        assertEquals(IndexSymbol.NIFTY_50, IndexSymbol.byWire("^NSEI"))
        assertNull(IndexSymbol.byWire("^NSEI "), "the caret symbol is matched exactly")
        assertEquals(IndexSymbol.NIFTY_50, IndexSymbol.DEFAULT)
    }

    @Test
    fun `the rejected NSE index-CSV shapes stay on the record with their 404s`() {
        // Kept so a future shape that answers is a one-line change rather than a rediscovery.
        // See docs/LIVE-PROBE.md.
        assertTrue(IndexEndpoints.NSE_CSV_CANDIDATES.contains(IndexEndpoints.IND_CLOSE_ALL))
        assertTrue(IndexEndpoints.NSE_CSV_HOSTS.contains("archives.nseindia.com"))
        assertTrue(IndexEndpoints.NSE_CSV_HOSTS.contains("nsearchives.nseindia.com"))
    }
}

/** Reads the committed fixtures for the index layer. Local to this file, so nothing else moves. */
internal object IndexTestFixtures {
    fun text(name: String): String {
        val stream = IndexTestFixtures::class.java.getResourceAsStream("/fixtures/$name")
            ?: error("missing fixture /fixtures/$name")
        return stream.use { it.readBytes() }.toString(Charsets.UTF_8)
    }
}