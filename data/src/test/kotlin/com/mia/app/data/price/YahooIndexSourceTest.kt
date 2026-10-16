package com.mia.app.data.price

import com.mia.app.data.history.YahooCrumbHandshake
import com.mia.app.data.history.YahooEndpoints
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [YahooIndexSource] against the existing [FakeHttpTransport].
 *
 * ### What these tests are for
 * Not "does it fetch" — that is the fixture's job — but the two properties the workstream exists
 * for:
 *
 * 1. **The handshake is performed, and performed once.** A chart request without a session is the
 *    request that answers `429` on every attempt, and a per-request handshake is what makes this
 *    app look like a scraper to plan.md §13's edge.
 * 2. **A missing index is a typed result with a stated reason and an empty series.** Never an
 *    exception, and never a fabricated series — because the caller passes `null` to the engine,
 *    and a source that quietly invented a benchmark would make the Context factor score on a
 *    number nobody can trace.
 */
class YahooIndexSourceTest {

    private val cookie = "A3=abc123; Domain=.yahoo.com; Path=/; Secure; HttpOnly"
    private val crumb = "Q7dR8kZ2vN1mX9pL"

    /**
     * A transport that answers the handshake and lets the caller script the chart response.
     *
     * The handshake's first step is `https://fc.yahoo.com`, which answers **404 and still sets a
     * cookie** — that is the documented good case, and [FakeHttpTransport] returns it verbatim so
     * the source has to cope with a non-2xx that carries what it needs.
     */
    private fun transport(chart: (String) -> com.mia.app.data.http.HttpResponse) =
        FakeHttpTransport { request ->
            when {
                request.url.contains(YahooEndpoints.SESSION_URL) ->
                    FakeHttpTransport.status(404).copy(setCookies = listOf(cookie))

                request.url.contains("/v1/test/getcrumb") ->
                    FakeHttpTransport.text(crumb)

                else -> chart(request.url)
            }
        }

    private fun ok(json: String) = { _: String ->
        FakeHttpTransport.text(json)
    }

    private fun source(t: FakeHttpTransport) = YahooIndexSource(
        transport = t,
        handshake = YahooCrumbHandshake(t, sleeper = { }),
        sleeper = { },
    )

    private val goodChart = IndexTestFixtures.text("yahoo_index_nsei_sample.json")

    // ------------------------------------------------------------------- the happy path

    @Test
    fun `the request sequence is cookie then crumb then chart`() = runTest {
        val t = transport(ok(goodChart))

        val result = source(t).daily(IndexSymbol.NIFTY_50)

        assertTrue(result.isUsable, result.unavailableReason)
        assertEquals(3, t.requests.size)
        assertTrue(t.requests[0].url.contains("fc.yahoo.com"))
        assertTrue(t.requests[1].url.contains("/v1/test/getcrumb"))
        assertTrue(t.requests[2].url.contains("/v8/finance/chart/%5ENSEI"))
        assertEquals("^NSEI", result.candles.first().symbol)
    }

    @Test
    fun `the chart request carries both the session cookie and the crumb`() = runTest {
        val t = transport(ok(goodChart))

        val result = source(t).daily(IndexSymbol.NIFTY_50)

        val chart = t.requests.last()
        assertEquals("A3=abc123", chart.headers["Cookie"], "plan.md §3.2: the cookie is mandatory")
        assertTrue(chart.url.contains("crumb=$crumb"), chart.url)
        assertEquals(chart.url, result.url)
        assertEquals(200, result.httpCode)
    }

    @Test
    fun `the session is cached, so a second fetch spends no handshake request`() = runTest {
        // The CGNAT mitigation in plan.md §13 is about looking like one reader. Re-handshaking per
        // request is exactly the behaviour that makes a client look like twenty.
        val t = transport(ok(goodChart))
        val subject = source(t)

        subject.daily(IndexSymbol.NIFTY_50)
        val afterFirst = t.requests.size
        subject.daily(IndexSymbol.NIFTY_50)

        assertEquals(1, t.requests.count { it.url.contains("fc.yahoo.com") })
        assertEquals(1, t.requests.count { it.url.contains("/v1/test/getcrumb") })
        assertEquals(afterFirst + 1, t.requests.size, "the second fetch is the chart alone")
    }

    @Test
    fun `each index symbol asks for its own wire symbol`() = runTest {
        val t = transport(ok(goodChart))

        source(t).daily(IndexSymbol.MIDCAP_150)

        assertTrue(t.requests.last().url.contains("%5ENSEMDCP150"), t.requests.last().url)
    }

    // ------------------------------------------------------------------ the failure modes

    @Test
    fun `a crumb that never arrives is a handshake failure and never reaches a chart request`() = runTest {
        // The exact failure this workstream's predecessor produced: no benchmark, no reason, and a
        // 429 on every request that looks like a network problem rather than a missing step.
        val t = FakeHttpTransport { request ->
            when {
                request.url.contains(YahooEndpoints.SESSION_URL) ->
                    FakeHttpTransport.status(404).copy(setCookies = listOf(cookie))
                request.url.contains("/v1/test/getcrumb") ->
                    FakeHttpTransport.ok(
                        ByteArray(0),
                        code = 429,
                    ).copy(headers = mapOf("Retry-After" to listOf("1")))
                else -> error("the chart must not be requested without a crumb")
            }
        }

        val result = source(t).daily(IndexSymbol.NIFTY_50)

        assertFalse(result.isUsable)
        assertEquals(IndexFailureReason.HANDSHAKE_FAILED, result.failure)
        assertTrue(result.candles.isEmpty())
        assertTrue(
            assertNotNull(result.unavailableReason).contains("handshake failed"),
            result.unavailableReason,
        )
        assertFalse(
            t.requests.any { it.url.contains("/v8/finance/chart/") },
            "no chart request may be issued without a session",
        )
    }

    @Test
    fun `a cookie that never arrives is a handshake failure too`() = runTest {
        val t = FakeHttpTransport { request ->
            when {
                request.url.contains(YahooEndpoints.SESSION_URL) -> FakeHttpTransport.status(200)
                else -> error("unreachable")
            }
        }

        val result = source(t).daily(IndexSymbol.NIFTY_50)

        assertEquals(IndexFailureReason.HANDSHAKE_FAILED, result.failure)
        assertTrue(result.candles.isEmpty())
    }

    @Test
    fun `a non-200 on every host is a host failure, and both hosts were tried`() = runTest {
        val t = transport { FakeHttpTransport.status(503) }

        val result = source(t).daily(IndexSymbol.NIFTY_50)

        assertFalse(result.isUsable)
        assertEquals(IndexFailureReason.HOST_UNREACHABLE, result.failure)
        assertTrue(result.candles.isEmpty())
        val chartRequests = t.requests.filter { it.url.contains("/v8/finance/chart/") }
        assertEquals(2, chartRequests.size, "query1 then query2 — the fallback must actually run")
        assertTrue(chartRequests[0].url.contains("query1"))
        assertTrue(chartRequests[1].url.contains("query2"))
    }

    @Test
    fun `a malformed body on every host is unparseable, not a crash`() = runTest {
        val t = transport { _: String -> FakeHttpTransport.text("<html>maintenance</html>") }

        val result = source(t).daily(IndexSymbol.NIFTY_50)

        assertFalse(result.isUsable)
        assertEquals(IndexFailureReason.UNPARSEABLE, result.failure)
        assertTrue(result.candles.isEmpty())
        assertNotNull(result.unavailableReason)
    }

    @Test
    fun `a 404 is reported as not found, which usually means the index was renamed`() = runTest {
        val t = transport { FakeHttpTransport.status(404) }

        val result = source(t).daily(IndexSymbol.NIFTY_50)

        assertEquals(IndexFailureReason.NOT_FOUND, result.failure)
        assertTrue(result.candles.isEmpty())
    }

    @Test
    fun `a thrown transport becomes a typed failure rather than an exception`() = runTest {
        val t = transport { _ -> throw java.net.UnknownHostException("query1") }

        val result = source(t).daily(IndexSymbol.NIFTY_50)

        assertFalse(result.isUsable)
        assertEquals(IndexFailureReason.HOST_UNREACHABLE, result.failure)
        assertTrue(assertNotNull(result.unavailableReason).contains("transport error"))
    }

    @Test
    fun `an empty body is unparseable rather than an empty benchmark`() = runTest {
        val t = transport { _: String -> FakeHttpTransport.text("") }

        val result = source(t).daily(IndexSymbol.NIFTY_50)

        assertEquals(IndexFailureReason.UNPARSEABLE, result.failure)
        assertTrue(result.candles.isEmpty())
    }

    @Test
    fun `a chart error is returned immediately without trying the second host`() = runTest {
        // `chart.error` is an answer, not a transport fault: the mirror would repeat it.
        val body = """
            {"chart":{"result":null,"error":{"code":"Not Found","description":"delisted"}}}
        """.trimIndent()
        val t = transport { _: String -> FakeHttpTransport.text(body) }

        val result = source(t).daily(IndexSymbol.NIFTY_50)

        assertEquals(IndexFailureReason.NO_DATA, result.failure)
        assertEquals(1, t.requests.count { it.url.contains("/v8/finance/chart/") })
    }

    // -------------------------------------------------------------- the usable-bars gate

    @Test
    fun `a series shorter than the usable floor is refused, and nothing is padded`() = runTest {
        // The failure this prevents: a 10-bar benchmark aligns with a handful of dates and
        // produces an RS line too short for any lookback, unscoring the Context factor with no
        // stated reason — exactly what the engine's honest `null` path exists to replace.
        val timestamps = (0 until 30).joinToString(",") { (1767239100 + it * 86_400L).toString() }
        val closes = (0 until 30).joinToString(",") { (25_000 + it).toString() + ".0" }
        val short = """
            {"chart":{"result":[{"meta":{"exchangeTimezoneName":"Asia/Calcutta"},
            "timestamp":[$timestamps],"indicators":{"quote":[{"close":[$closes]}]}}],"error":null}}
        """.trimIndent()
        val t = transport { _: String -> FakeHttpTransport.text(short) }

        val result = source(t).daily(IndexSymbol.NIFTY_50)

        assertFalse(result.isUsable)
        assertEquals(IndexFailureReason.NO_BENCHMARK_ROWS, result.failure)
        assertTrue(result.candles.isEmpty(), "a short series must be dropped, never padded")
        assertTrue(assertNotNull(result.unavailableReason).contains("30 usable session"))
        // One host is enough: the answer would not change on the other.
        assertEquals(1, t.requests.count { it.url.contains("/v8/finance/chart/") })
    }

    @Test
    fun `the committed 64-session fixture clears the usable floor`() = runTest {
        val result = source(transport(ok(goodChart))).daily(IndexSymbol.NIFTY_50)

        assertTrue(result.isUsable, result.unavailableReason)
        assertTrue(result.candles.size >= IndexEndpoints.MIN_USABLE_BARS)
    }

    // ------------------------------------------------------------------------ the 429 path

    @Test
    fun `a 429 refreshes the session and retries exactly once`() = runTest {
        // A stale crumb is the likeliest cause of a 429, so retrying with the *same* session would
        // be the same request again. One retry, then the failure is reported as a rate limit.
        var charts = 0
        val t = FakeHttpTransport { request ->
            when {
                request.url.contains(YahooEndpoints.SESSION_URL) ->
                    FakeHttpTransport.status(404).copy(setCookies = listOf(cookie))
                request.url.contains("/v1/test/getcrumb") -> FakeHttpTransport.text(crumb)
                else -> {
                    charts++
                    if (charts == 1) FakeHttpTransport.status(429) else FakeHttpTransport.text(goodChart)
                }
            }
        }

        val result = source(t).daily(IndexSymbol.NIFTY_50)

        assertTrue(result.isUsable, result.unavailableReason)
        assertEquals(2, charts, "exactly one retry after a 429")
        assertEquals(
            2,
            t.requests.count { it.url.contains("fc.yahoo.com") },
            "the session is re-handshaked before the retry",
        )
    }

    @Test
    fun `a 429 on both rounds is a rate limit, not an unreachable host`() = runTest {
        val t = FakeHttpTransport { request ->
            when {
                request.url.contains(YahooEndpoints.SESSION_URL) ->
                    FakeHttpTransport.status(404).copy(setCookies = listOf(cookie))
                request.url.contains("/v1/test/getcrumb") -> FakeHttpTransport.text(crumb)
                else -> FakeHttpTransport.status(429)
            }
        }

        val result = source(t).daily(IndexSymbol.NIFTY_50)

        assertEquals(IndexFailureReason.RATE_LIMITED, result.failure)
        assertTrue(result.candles.isEmpty())
    }

    // ------------------------------------------------------------------- the seam itself

    @Test
    fun `an undocumented range never reaches a URL`() = runTest {
        val t = transport { error("unreachable") }

        val result = source(t).daily(IndexSymbol.NIFTY_50, range = "1y&crumb=x")

        assertEquals(IndexFailureReason.UNSUPPORTED_REQUEST, result.failure)
        assertFalse(t.requests.any { it.url.contains("/v8/finance/chart/") })
        assertNull(result.candles.firstOrNull())
    }

    @Test
    fun `the default range is one year of daily bars`() {
        assertEquals("1y", IndexEndpoints.DEFAULT_RANGE)
        assertEquals("1d", IndexEndpoints.INTERVAL)
        assertTrue(IndexEndpoints.MIN_USABLE_BARS > 0)
    }
}