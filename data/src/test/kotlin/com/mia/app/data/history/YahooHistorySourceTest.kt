package com.mia.app.data.history

import com.mia.app.domain.model.Timeframe
import kotlinx.coroutines.test.runTest
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [YahooHistorySource] end to end against a hand-written transport.
 *
 * The assertions are about the *sequence* of requests and about what the caller is told when
 * things go wrong, because those are the two things a "history fetch just returns nothing"
 * incident would have broken silently.
 */
class YahooHistorySourceTest {

    private fun source(transport: FakeYahooTransport) = YahooHistorySource(
        transport = transport,
        handshake = YahooCrumbHandshake(transport, sleeper = { }),
        sleeper = { },
    )

    // ------------------------------------------------------------------ the happy path

    @Test
    fun `the request sequence is cookie then crumb then chart, and the chart carries both`() = runTest {
        val transport = FakeYahooTransport.happyPath(YahooChartFixtures.minimal())
        val result = source(transport).fetch("RELIANCE", "2y", YahooInterval.DAY)

        assertTrue(result.isUsable)
        assertEquals(
            listOf(
                "https://fc.yahoo.com",
                "https://query1.finance.yahoo.com/v1/test/getcrumb",
                "https://query1.finance.yahoo.com/v8/finance/chart/RELIANCE.NS?range=2y&interval=1d" +
                    "&crumb=${FakeYahooTransport.CRUMB}",
            ),
            transport.urls,
        )
        val chartRequest = transport.requests[2]
        assertEquals(
            FakeYahooTransport.SESSION_COOKIE_HEADER,
            chartRequest.headers["Cookie"],
            "the chart request must carry the session cookie (plan.md §3.2)",
        )
        assertTrue(chartRequest.url.contains("crumb=${FakeYahooTransport.CRUMB}"))
        assertEquals(chartRequest.url, result.url)
        assertEquals(200, result.httpCode)
    }

    @Test
    fun `one handshake serves both the daily and the weekly request`() = runTest {
        val transport = FakeYahooTransport.happyPath(YahooChartFixtures.minimal())
            .respondText("/v8/finance/chart/", YahooChartFixtures.minimal(200.0))
        val series = source(transport).dailyAndWeekly("RELIANCE")

        assertTrue(series.daily.isUsable)
        assertTrue(series.weekly.isUsable)
        assertEquals(
            1,
            transport.requestsTo("/v1/test/getcrumb").size,
            "the crumb is fetched once per process, not once per range (plan.md §3.2)",
        )
        assertEquals(1, transport.countOf(YahooEndpoints.SESSION_URL))
        assertTrue(series.meetsDocumentedShapes.not(), "1-bar fixtures are not the documented shapes")
    }

    @Test
    fun `daily requests 2y_1d and weekly requests 5y_1wk`() = runTest {
        val transport = FakeYahooTransport.happyPath(YahooChartFixtures.minimal())
            .respondText("/v8/finance/chart/", YahooChartFixtures.minimal())
        source(transport).dailyAndWeekly("TCS")

        assertTrue(
            transport.urls.any { it.contains("TCS.NS?range=2y&interval=1d") },
            transport.urls.toString(),
        )
        assertTrue(
            transport.urls.any { it.contains("TCS.NS?range=5y&interval=1wk") },
            transport.urls.toString(),
        )
    }

    @Test
    fun `a skipped timeframe issues no request and says why`() = runTest {
        val transport = FakeYahooTransport.happyPath(YahooChartFixtures.minimal())
        val series = source(transport).dailyAndWeekly("RELIANCE", skip = setOf(Timeframe.W1))

        assertTrue(series.daily.isUsable)
        assertFalse(series.weekly.isUsable)
        assertTrue(transport.urls.none { it.contains("interval=1wk") }, transport.urls.toString())
        assertTrue(
            series.weekly.health.notes.single().contains("write-once"),
            "a skipped fetch must be distinguishable from no data: ${series.weekly.health.notes}",
        )
    }

    // ------------------------------------------------------------------ host fallback

    @Test
    fun `a failing query1 chart falls through to query2`() = runTest {
        val transport = FakeYahooTransport()
            .alwaysCookies(YahooEndpoints.SESSION_URL, FakeYahooTransport.SESSION_COOKIES)
            .alwaysText("/v1/test/getcrumb", FakeYahooTransport.CRUMB)
            .respondStatus("query1.finance.yahoo.com/v8/finance/chart/", 503)
            .respondText("query2.finance.yahoo.com/v8/finance/chart/", YahooChartFixtures.minimal())

        val result = source(transport).fetch("RELIANCE", "2y", YahooInterval.DAY)

        assertTrue(result.isUsable)
        assertTrue(result.url!!.startsWith("https://query2.finance.yahoo.com/"), result.url!!)
        assertEquals(2, transport.requestsTo("/v8/finance/chart/").size)
    }

    @Test
    fun `the cookie survives the host switch so the fallback is not a fresh handshake`() = runTest {
        val transport = FakeYahooTransport()
            .alwaysCookies(YahooEndpoints.SESSION_URL, FakeYahooTransport.SESSION_COOKIES)
            .alwaysText("/v1/test/getcrumb", FakeYahooTransport.CRUMB)
            .respondStatus("query1.finance.yahoo.com/v8/finance/chart/", 503)
            .respondText("query2.finance.yahoo.com/v8/finance/chart/", YahooChartFixtures.minimal())

        source(transport).fetch("RELIANCE", "2y", YahooInterval.DAY)

        for (request in transport.requestsTo("/v8/finance/chart/")) {
            assertEquals(
                FakeYahooTransport.SESSION_COOKIE_HEADER,
                request.headers["Cookie"],
                "the query2 request must reuse the same session",
            )
        }
    }

    // ------------------------------------------------------------------ 429 handling

    @Test
    fun `a 429 on query1 drops the session and the next host is still tried with it`() = runTest {
        // The session is invalidated the moment a 429 is seen, but the host walk continues: the
        // fallback host is served by the same cookie jar, so trying it costs one request and can
        // still succeed inside the same round.
        val transport = FakeYahooTransport()
            .alwaysCookies(YahooEndpoints.SESSION_URL, FakeYahooTransport.SESSION_COOKIES)
            .alwaysText("/v1/test/getcrumb", FakeYahooTransport.CRUMB)
            .respondStatus("query1.finance.yahoo.com/v8/finance/chart/", 429)
            .respondText("query2.finance.yahoo.com/v8/finance/chart/", YahooChartFixtures.minimal())

        val handshake = YahooCrumbHandshake(transport, sleeper = { })
        val result = YahooHistorySource(transport, handshake, sleeper = { }).fetch(
            "RELIANCE", "2y", YahooInterval.DAY,
        )

        assertTrue(result.isUsable, result.health.notes.toString())
        assertTrue(result.url!!.startsWith("https://query2.finance.yahoo.com/"), result.url!!)
        assertEquals(2, transport.requestsTo("/v8/finance/chart/").size)
    }

    @Test
    fun `a 429 on every host re-handshakes exactly once more`() = runTest {
        val transport = FakeYahooTransport()
            .alwaysCookies(YahooEndpoints.SESSION_URL, FakeYahooTransport.SESSION_COOKIES)
            .alwaysText("/v1/test/getcrumb", FakeYahooTransport.CRUMB)
            .alwaysStatus("/v8/finance/chart/", 429)

        val handshake = YahooCrumbHandshake(transport, sleeper = { })
        val result = YahooHistorySource(transport, handshake, sleeper = { }).fetch(
            "RELIANCE", "2y", YahooInterval.DAY,
        )

        assertEquals(YahooFailureReason.RATE_LIMITED, result.failure)
        assertEquals(2, handshake.handshakeCount, "the retry must re-handshake, not reuse the stale crumb")
        assertEquals(
            2,
            transport.countOf(YahooEndpoints.SESSION_URL),
            "one cookie request per handshake, no more",
        )
        assertEquals(2, transport.requestsTo("/v1/test/getcrumb").size)
    }

    @Test
    fun `a 429 on every host surfaces RATE_LIMITED after exactly one retry`() = runTest {
        val transport = FakeYahooTransport()
            .alwaysCookies(YahooEndpoints.SESSION_URL, FakeYahooTransport.SESSION_COOKIES)
            .alwaysText("/v1/test/getcrumb", FakeYahooTransport.CRUMB)
            .alwaysStatus("/v8/finance/chart/", 429)

        val result = source(transport).fetch("RELIANCE", "2y", YahooInterval.DAY)

        assertEquals(YahooFailureReason.RATE_LIMITED, result.failure)
        assertFalse(result.isUsable)
        assertFalse(result.health.ok)
        assertTrue(result.health.notes.single().contains("429"), result.health.notes.toString())
        // Two rounds × two hosts = four chart requests, and no more.
        assertEquals(4, transport.requestsTo("/v8/finance/chart/").size)
        assertEquals(2, transport.requestsTo("/v1/test/getcrumb").size)
    }

    @Test
    fun `a Retry-After header on the 429 is read and honoured`() = runTest {
        val waited = mutableListOf<Long>()
        val transport = FakeYahooTransport()
            .alwaysCookies(YahooEndpoints.SESSION_URL, FakeYahooTransport.SESSION_COOKIES)
            .alwaysText("/v1/test/getcrumb", FakeYahooTransport.CRUMB)
            .alwaysStatus("/v8/finance/chart/", 429, headers = mapOf("Retry-After" to listOf("2")))
        val handshake = YahooCrumbHandshake(transport, sleeper = { waited += it })
        val source = YahooHistorySource(transport, handshake, sleeper = { waited += it })

        source.fetch("RELIANCE", "2y", YahooInterval.DAY)

        // 429 on both hosts × two rounds = four waits, each the server's 2 seconds.
        assertEquals(4, waited.size, "waited=$waited")
        assertTrue(waited.all { it == 2_000L }, "Retry-After 2s → 2000ms, not the 1500ms default: $waited")
    }

    @Test
    fun `a 429 with no Retry-After falls back to the fixed backoff`() = runTest {
        val waited = mutableListOf<Long>()
        val transport = FakeYahooTransport()
            .alwaysCookies(YahooEndpoints.SESSION_URL, FakeYahooTransport.SESSION_COOKIES)
            .alwaysText("/v1/test/getcrumb", FakeYahooTransport.CRUMB)
            .alwaysStatus("/v8/finance/chart/", 429)
        val source = YahooHistorySource(
            transport,
            YahooCrumbHandshake(transport, sleeper = { waited += it }),
            sleeper = { waited += it },
        )

        source.fetch("RELIANCE", "2y", YahooInterval.DAY)

        assertEquals(4, waited.size, "waited=$waited")
        assertTrue(waited.all { it == 1_500L }, waited.toString())
    }

    @Test
    fun `an absurd Retry-After is clamped rather than obeyed`() = runTest {
        val waited = mutableListOf<Long>()
        val transport = FakeYahooTransport()
            .alwaysCookies(YahooEndpoints.SESSION_URL, FakeYahooTransport.SESSION_COOKIES)
            .alwaysText("/v1/test/getcrumb", FakeYahooTransport.CRUMB)
            .alwaysStatus("/v8/finance/chart/", 429, headers = mapOf("Retry-After" to listOf("86400")))
        val source = YahooHistorySource(
            transport,
            YahooCrumbHandshake(transport, sleeper = { waited += it }),
            sleeper = { waited += it },
        )

        source.fetch("RELIANCE", "2y", YahooInterval.DAY)

        assertTrue(waited.isNotEmpty())
        assertTrue(waited.all { it == 30_000L }, "a day-long wait on a phone is not a wait: $waited")
    }

    @Test
    fun `a non-429 failure is not retried`() = runTest {
        val transport = FakeYahooTransport()
            .alwaysCookies(YahooEndpoints.SESSION_URL, FakeYahooTransport.SESSION_COOKIES)
            .alwaysText("/v1/test/getcrumb", FakeYahooTransport.CRUMB)
            .alwaysStatus("/v8/finance/chart/", 500)

        val result = source(transport).fetch("RELIANCE", "2y", YahooInterval.DAY)

        assertEquals(YahooFailureReason.HOST_UNREACHABLE, result.failure)
        assertEquals(
            2,
            transport.requestsTo("/v8/finance/chart/").size,
            "one attempt per host, no second round",
        )
        assertEquals(1, transport.requestsTo("/v1/test/getcrumb").size)
    }

    // ------------------------------------------------------------------ typed failures

    @Test
    fun `both hosts dead surfaces one typed failure and no exception`() = runTest {
        val transport = FakeYahooTransport()
            .alwaysCookies(YahooEndpoints.SESSION_URL, FakeYahooTransport.SESSION_COOKIES)
            .alwaysText("/v1/test/getcrumb", FakeYahooTransport.CRUMB)
            .alwaysStatus("/v8/finance/chart/", 500)

        val result = source(transport).fetch("RELIANCE", "2y", YahooInterval.DAY)

        assertEquals(YahooFailureReason.HOST_UNREACHABLE, result.failure)
        assertFalse(result.health.ok)
        assertTrue(result.candles.isEmpty())
        assertTrue(result.health.notes.single().contains("query1"), result.health.notes.toString())
        assertTrue(result.health.notes.single().contains("query2"), result.health.notes.toString())
    }

    @Test
    fun `a transport that throws becomes a typed failure rather than a crash`() = runTest {
        val transport = FakeYahooTransport()
            .alwaysCookies(YahooEndpoints.SESSION_URL, FakeYahooTransport.SESSION_COOKIES)
            .alwaysText("/v1/test/getcrumb", FakeYahooTransport.CRUMB)
            .failWith("/v8/finance/chart/") { java.net.UnknownHostException("query2.finance.yahoo.com") }

        val result = source(transport).fetch("RELIANCE", "2y", YahooInterval.DAY)

        assertFalse(result.isUsable)
        assertFalse(result.health.ok)
        // TRANSPORT_ERROR, not HOST_UNREACHABLE: the server never said no, the phone could not
        // reach it. The UI says "offline" for one and "the source is down" for the other.
        assertEquals(YahooFailureReason.TRANSPORT_ERROR, result.failure)
        assertTrue(
            result.health.notes.single().contains("UnknownHostException"),
            result.health.notes.toString(),
        )
    }

    @Test
    fun `a failed handshake becomes a typed failure without a chart request`() = runTest {
        val transport = FakeYahooTransport()
            .alwaysCookies(YahooEndpoints.SESSION_URL, FakeYahooTransport.SESSION_COOKIES)
            .alwaysStatus("/v1/test/getcrumb", 429)

        val result = source(transport).fetch("RELIANCE", "2y", YahooInterval.DAY)

        assertEquals(YahooFailureReason.HANDSHAKE_FAILED, result.failure)
        assertTrue(transport.urls.none { it.contains("/v8/finance/chart/") }, transport.urls.toString())
    }

    @Test
    fun `a 404 is NOT_FOUND and does not try the other host twice`() = runTest {
        val transport = FakeYahooTransport()
            .alwaysCookies(YahooEndpoints.SESSION_URL, FakeYahooTransport.SESSION_COOKIES)
            .alwaysText("/v1/test/getcrumb", FakeYahooTransport.CRUMB)
            .alwaysStatus("/v8/finance/chart/", 404)

        val result = source(transport).fetch("TATAMOTORS", "2y", YahooInterval.DAY)

        assertEquals(YahooFailureReason.NOT_FOUND, result.failure)
        assertTrue(result.health.notes.single().contains("HTTP 404"), result.health.notes.toString())
    }

    @Test
    fun `a chart error body is reported without asking the other host`() = runTest {
        // plan.md §9: TATAMOTORS.NS returns zero rows because of the demerger. Asking query2 the
        // same question would only spend a request on the same answer.
        val transport = FakeYahooTransport()
            .alwaysCookies(YahooEndpoints.SESSION_URL, FakeYahooTransport.SESSION_COOKIES)
            .alwaysText("/v1/test/getcrumb", FakeYahooTransport.CRUMB)
            .respondText("/v8/finance/chart/", YahooChartFixtures.errored())

        val result = source(transport).fetch("TATAMOTORS", "2y", YahooInterval.DAY)

        assertEquals(YahooFailureReason.NO_DATA, result.failure)
        assertEquals(1, transport.requestsTo("/v8/finance/chart/").size)
    }

    @Test
    fun `an unparseable body tries the other host and then reports it`() = runTest {
        val transport = FakeYahooTransport()
            .alwaysCookies(YahooEndpoints.SESSION_URL, FakeYahooTransport.SESSION_COOKIES)
            .alwaysText("/v1/test/getcrumb", FakeYahooTransport.CRUMB)
            .alwaysText("/v8/finance/chart/", YahooChartFixtures.notChartJson())

        val result = source(transport).fetch("RELIANCE", "2y", YahooInterval.DAY)

        assertEquals(YahooFailureReason.UNPARSEABLE, result.failure)
        assertEquals(2, transport.requestsTo("/v8/finance/chart/").size)
    }

    @Test
    fun `an empty 200 body is not mistaken for an empty series`() = runTest {
        val transport = FakeYahooTransport()
            .alwaysCookies(YahooEndpoints.SESSION_URL, FakeYahooTransport.SESSION_COOKIES)
            .alwaysText("/v1/test/getcrumb", FakeYahooTransport.CRUMB)
            .always("/v8/finance/chart/", com.mia.app.data.http.HttpResponse(200, ByteArray(0)))

        val result = source(transport).fetch("RELIANCE", "2y", YahooInterval.DAY)

        assertEquals(YahooFailureReason.UNPARSEABLE, result.failure)
        assertTrue(result.health.notes.single().contains("0 bytes"), result.health.notes.toString())
    }

    // ------------------------------------------------------------------ request validation

    @Test
    fun `a rejected symbol never reaches the network`() = runTest {
        val transport = FakeYahooTransport.handshakeOnly()

        for (symbol in listOf("../evil", "RELIANCE?x=1", "", "A B", "A".repeat(21))) {
            val result = source(transport).fetch(symbol, "2y", YahooInterval.DAY)
            assertEquals(YahooFailureReason.REJECTED_SYMBOL, result.failure, "symbol '$symbol'")
            assertFalse(result.isUsable)
        }
        assertTrue(transport.requests.isEmpty(), "no request may be made for a rejected symbol")
    }

    @Test
    fun `an unsupported range is refused before any request`() = runTest {
        val transport = FakeYahooTransport.handshakeOnly()

        val result = source(transport).fetch("RELIANCE", "7y", YahooInterval.DAY)

        assertEquals(YahooFailureReason.UNSUPPORTED_REQUEST, result.failure)
        assertTrue(transport.requests.isEmpty())
    }

    // ------------------------------------------------------------------ the P3 cache rule

    @Test
    fun `needsBackfill is false only beyond the documented bar counts`() = runTest {
        val source = source(FakeYahooTransport.handshakeOnly())

        // plan.md §3.2: more than 500 daily, or more than 261 weekly, means no refetch.
        assertTrue(source.needsBackfill("RELIANCE", Timeframe.D1, 0))
        assertTrue(source.needsBackfill("RELIANCE", Timeframe.D1, 60))
        assertTrue(source.needsBackfill("RELIANCE", Timeframe.D1, 500))
        assertFalse(source.needsBackfill("RELIANCE", Timeframe.D1, 501))
        assertFalse(source.needsBackfill("RELIANCE", Timeframe.D1, 900))

        assertTrue(source.needsBackfill("RELIANCE", Timeframe.W1, 40))
        assertTrue(source.needsBackfill("RELIANCE", Timeframe.W1, 261))
        assertFalse(source.needsBackfill("RELIANCE", Timeframe.W1, 262))

        assertEquals(500, source.backfillThreshold(Timeframe.D1))
        assertEquals(261, source.backfillThreshold(Timeframe.W1))
    }

    @Test
    fun `the documented shapes sit exactly on the backfill threshold`() = runTest {
        val source = source(FakeYahooTransport.handshakeOnly())

        // Stated rather than hidden: plan.md §14 records 500 daily and 261 weekly rows, and the
        // brief's rule is `> threshold ⇒ no refetch`, so a symbol stored at the full documented
        // shape still answers "needs backfill" on a count alone. The caller is expected to also
        // consult its own stored-once marker; see needsBackfill's KDoc.
        assertEquals(
            YahooEndpoints.EXPECTED_DAILY_ROWS,
            source.backfillThreshold(Timeframe.D1),
        )
        assertEquals(
            YahooEndpoints.EXPECTED_WEEKLY_ROWS,
            source.backfillThreshold(Timeframe.W1),
        )
        assertTrue(source.needsBackfill("RELIANCE", Timeframe.D1, YahooEndpoints.EXPECTED_DAILY_ROWS))
        assertFalse(source.needsBackfill("RELIANCE", Timeframe.D1, YahooEndpoints.EXPECTED_DAILY_ROWS + 1))
    }

    // ------------------------------------------------------------------ the P3 gate, offline

    @Test
    fun `both documented shapes pass the P3 gate through the real request path`() = runTest {
        val transport = FakeYahooTransport()
            .alwaysCookies(YahooEndpoints.SESSION_URL, FakeYahooTransport.SESSION_COOKIES)
            .alwaysText("/v1/test/getcrumb", FakeYahooTransport.CRUMB)
            .respondText("interval=1d", YahooChartFixtures.dailyDocument(500))
            .respondText("interval=1wk", YahooChartFixtures.weeklyDocument(261))

        val series = source(transport).dailyAndWeekly("RELIANCE")

        assertEquals(500, series.daily.candles.size)
        assertEquals(261, series.weekly.candles.size)
        assertTrue(series.meetsDocumentedShapes)
        // The "as of" the UI shows for the daily tab is the newest backfilled bar.
        assertEquals(
            series.daily.candles.last().date,
            series.daily.asOf,
            "the as-of date is the newest bar (plan.md §1.4 rule 3)",
        )
        assertEquals(LocalDate.of(2024, 10, 1), series.daily.candles.first().date)
        assertEquals(LocalDate.of(2021, 10, 4), series.weekly.candles.first().date)
        assertEquals(Timeframe.D1, series.daily.candles.first().timeframe)
        assertEquals(Timeframe.W1, series.weekly.candles.first().timeframe)
    }
}