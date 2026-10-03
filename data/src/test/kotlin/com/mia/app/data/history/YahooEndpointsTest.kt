package com.mia.app.data.history

import com.mia.app.data.http.HttpRequest
import com.mia.app.data.http.HttpResponse
import com.mia.app.domain.model.Timeframe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * URL construction and symbol validation for Yahoo (plan.md §3.2).
 *
 * The validation tests matter more than they look: a symbol arrives from a CSV, from a search
 * box, or from a bhavcopy row, and it is interpolated into a URL on both this side and the
 * crumb side. An allow-list that is too loose would let a caller request a different host.
 */
class YahooEndpointsTest {

    @Test
    fun `chart URL is exactly the shape plan 3_2 documents`() {
        val session = YahooSession(
            cookies = mapOf("A3" to "abc123", "GUC" to "AQAB"),
            crumb = "Q7dR8kZ2vN1mX9pL",
            host = YahooHost.QUERY1,
        )

        val url = YahooEndpoints.chartUrl(
            host = YahooHost.QUERY1,
            symbol = "RELIANCE",
            range = "2y",
            interval = YahooInterval.DAY,
            session = session,
        )

        assertEquals(
            "https://query1.finance.yahoo.com/v8/finance/chart/RELIANCE.NS?range=2y&interval=1d" +
                "&crumb=Q7dR8kZ2vN1mX9pL",
            url,
        )
    }

    @Test
    fun `weekly request uses 5y and 1wk`() {
        val url = YahooEndpoints.chartUrl(
            host = YahooHost.QUERY2,
            symbol = "TCS",
            range = YahooEndpoints.WEEKLY_RANGE,
            interval = YahooInterval.WEEK,
            session = YahooSession(mapOf("A3" to "a"), "crumb42", YahooHost.QUERY2),
        )

        assertEquals(
            "https://query2.finance.yahoo.com/v8/finance/chart/TCS.NS?range=5y&interval=1wk&crumb=crumb42",
            url,
        )
    }

    @Test
    fun `crumb URL is the v1 test endpoint on both hosts`() {
        assertEquals("https://query1.finance.yahoo.com/v1/test/getcrumb", YahooEndpoints.crumbUrl(YahooHost.QUERY1))
        assertEquals("https://query2.finance.yahoo.com/v1/test/getcrumb", YahooEndpoints.crumbUrl(YahooHost.QUERY2))
    }

    @Test
    fun `session URL is fc yahoo dot com`() {
        assertEquals("https://fc.yahoo.com", YahooEndpoints.SESSION_URL)
    }

    @Test
    fun `fallback order is query1 then query2`() {
        assertEquals(listOf(YahooHost.QUERY1, YahooHost.QUERY2), YahooHost.FALLBACK_ORDER)
    }

    @Test
    fun `NSE symbols get the NS suffix`() {
        assertEquals("RELIANCE.NS", YahooEndpoints.yahooSymbol("reliance"))
        assertEquals("M%26M.NS", YahooEndpoints.chartUrl(
            YahooHost.QUERY1, "M&M", "2y", YahooInterval.DAY,
            YahooSession(mapOf("a" to "b"), "c", YahooHost.QUERY1),
        ).substringAfter("chart/").substringBefore('?'))
    }

    @Test
    fun `symbols outside the allow-list are rejected instead of escaped`() {
        val hostile = listOf(
            "../../v8/finance/chart/EVIL",           // path traversal
            "RELIANCE?range=1d",                     // query injection
            "RELIANCE#frag",                         // fragment injection
            "evil.example",                          // plausible-looking but not an NSE symbol
            "RELIANCE.NS",                           // already suffixed: would become RELIANCE.NS.NS
            "REL IANCE",                             // space
            "RELIANCE%2FNSE",                        // percent-encoded separator
            "A".repeat(21),                          // too long
            "",
            "   ",
            "-LEADING-HYPHEN",                       // must start alphanumeric
        )

        for (symbol in hostile) {
            assertFalse(YahooEndpoints.isValidSymbol(symbol), "expected '$symbol' to be rejected")
            assertFailsWith<IllegalArgumentException>("expected '$symbol' to throw") {
                YahooEndpoints.requireSymbol(symbol)
            }
        }
    }

    @Test
    fun `real NSE symbols with an ampersand or hyphen are accepted`() {
        for (symbol in listOf("RELIANCE", "TCS", "M&M", "GVT&D", "BAJAJ-AUTO", "20MICRONS", "L&T")) {
            assertTrue(YahooEndpoints.isValidSymbol(symbol), "expected '$symbol' to be accepted")
        }
        assertEquals("L%26T.NS", YahooEndpoints.percentEncodePathSegment(YahooEndpoints.yahooSymbol("L&T")))
    }

    @Test
    fun `a crumb containing an ampersand is refused rather than escaped`() {
        assertTrue(YahooEndpoints.isValidCrumb("Q7dR8kZ2vN1mX9pL"))
        assertTrue(YahooEndpoints.isValidCrumb("a.b-c_d=e+f/g"))
        assertFalse(YahooEndpoints.isValidCrumb("crumb&extra=1"))
        assertFalse(YahooEndpoints.isValidCrumb("crumb#frag"))
        assertFalse(YahooEndpoints.isValidCrumb(""))
        assertFalse(YahooEndpoints.isValidCrumb("a".repeat(65)))
    }

    @Test
    fun `only documented ranges are accepted`() {
        for (range in listOf("2y", "5y", "1y", "max", "ytd", "6mo")) {
            assertTrue(YahooEndpoints.isValidRange(range), "expected '$range'")
        }
        for (range in listOf("2y&interval=1m", "../../", "", "2Y x", "10x")) {
            assertFalse(YahooEndpoints.isValidRange(range), "expected '$range' to be rejected")
        }
    }

    @Test
    fun `default ranges are the two the plan pairs with the two timeframes`() {
        assertEquals("2y", YahooEndpoints.defaultRange(YahooInterval.DAY))
        assertEquals("5y", YahooEndpoints.defaultRange(YahooInterval.WEEK))
    }

    @Test
    fun `only D1 and W1 intervals exist so no intraday request is expressible`() {
        assertEquals(listOf("1d", "1wk"), YahooInterval.entries.map { it.wire })
        assertEquals(YahooInterval.DAY, YahooInterval.forTimeframe(Timeframe.D1))
        assertEquals(YahooInterval.WEEK, YahooInterval.forTimeframe(Timeframe.W1))
        assertNull(YahooInterval.fromWire("15m"))
    }

    @Test
    fun `chart URL cannot be built without a completed session`() {
        // A blank crumb or an empty cookie jar is a programming error, not a runtime surprise,
        // because both produce the exact request plan.md §3.2 says always answers 429.
        assertFailsWith<IllegalArgumentException> {
            YahooSession(emptyMap(), "crumb", YahooHost.QUERY1)
        }
        assertFailsWith<IllegalArgumentException> {
            YahooSession(mapOf("A3" to "a"), "", YahooHost.QUERY1)
        }
    }
}

/**
 * The mandatory cookie + crumb handshake (plan.md §3.2), proven against a hand-written transport.
 *
 * These are the tests that matter most in this package: the handshake is the difference between
 * a working history layer and one that gets `429` on every request, and it is invisible when it
 * breaks unless the request *sequence* is asserted.
 */
class YahooCrumbHandshakeTest {

    @Test
    fun `the request order is cookie then crumb and the crumb request carries the cookie`() = runTest {
        val transport = FakeYahooTransport()
            .alwaysCookies(YahooEndpoints.SESSION_URL, FakeYahooTransport.SESSION_COOKIES)
            .alwaysText("/v1/test/getcrumb", FakeYahooTransport.CRUMB)

        val session = YahooCrumbHandshake(transport).session()

        assertEquals(
            listOf(
                "https://fc.yahoo.com",
                "https://query1.finance.yahoo.com/v1/test/getcrumb",
            ),
            transport.urls,
            "plan.md §3.2: cookie handshake, then crumb — no other order is valid",
        )
        assertEquals(
            FakeYahooTransport.SESSION_COOKIE_HEADER,
            transport.requests[1].headers["Cookie"],
            "the crumb request must present the cookie the session step obtained",
        )
        assertEquals(FakeYahooTransport.CRUMB, session.crumb)
        assertEquals(YahooHost.QUERY1, session.host)
        assertEquals("A3=abc123; GUC=AQAB", session.cookieHeader)
    }

    @Test
    fun `a 404 from fc yahoo dot com is accepted because the cookie is what matters`() = runTest {
        // Verified 2026-10-03: fc.yahoo.com answers 404 and still sets `d=`. Treating the status
        // as the acceptance test would break the one request that does work.
        val transport = FakeYahooTransport()
            .alwaysCookies(YahooEndpoints.SESSION_URL, listOf("d=SESSIONCOOKIE; Path=/"), code = 404)
            .alwaysText("/v1/test/getcrumb", FakeYahooTransport.CRUMB)

        val session = YahooCrumbHandshake(transport).session()

        assertEquals("d=SESSIONCOOKIE", session.cookieHeader)
    }

    @Test
    fun `a session response with no cookie fails the handshake`() = runTest {
        val transport = FakeYahooTransport()
            .alwaysStatus(YahooEndpoints.SESSION_URL, 200)
            .alwaysText("/v1/test/getcrumb", FakeYahooTransport.CRUMB)

        val error = assertFailsWith<YahooHistoryException> { YahooCrumbHandshake(transport).session() }

        assertEquals(YahooFailureReason.HANDSHAKE_FAILED, error.reason)
        assertTrue(error.message!!.contains("set no cookie"), "message should name the cause: ${error.message}")
    }

    @Test
    fun `query1 crumb failure falls through to query2`() = runTest {
        val transport = FakeYahooTransport()
            .alwaysCookies(YahooEndpoints.SESSION_URL, FakeYahooTransport.SESSION_COOKIES)
            .alwaysStatus("query1.finance.yahoo.com", 503)
            .alwaysText("query2.finance.yahoo.com/v1/test/getcrumb", "SECONDHOSTCRUMB")

        val session = YahooCrumbHandshake(transport).session()

        assertEquals("SECONDHOSTCRUMB", session.crumb)
        assertEquals(YahooHost.QUERY2, session.host)
        assertEquals(
            listOf(
                "https://fc.yahoo.com",
                "https://query1.finance.yahoo.com/v1/test/getcrumb",
                "https://query2.finance.yahoo.com/v1/test/getcrumb",
            ),
            transport.urls,
        )
    }

    @Test
    fun `an HTML body served with 200 is not mistaken for a crumb`() = runTest {
        val transport = FakeYahooTransport()
            .alwaysCookies(YahooEndpoints.SESSION_URL, FakeYahooTransport.SESSION_COOKIES)
            .alwaysText("/v1/test/getcrumb", "<html><body>Something went wrong</body></html>")

        val error = assertFailsWith<YahooHistoryException> { YahooCrumbHandshake(transport).session() }

        assertEquals(YahooFailureReason.HANDSHAKE_FAILED, error.reason)
        assertTrue(
            error.message!!.contains("not a crumb token"),
            "an HTML body must be named as such: ${error.message}",
        )
    }

    @Test
    fun `both hosts failing surfaces one typed failure naming both`() = runTest {
        val transport = FakeYahooTransport()
            .alwaysCookies(YahooEndpoints.SESSION_URL, FakeYahooTransport.SESSION_COOKIES)
            .alwaysStatus("/v1/test/getcrumb", 429)

        val error = assertFailsWith<YahooHistoryException> { YahooCrumbHandshake(transport).session() }

        assertEquals(YahooFailureReason.HANDSHAKE_FAILED, error.reason)
        assertTrue(error.message!!.contains("query1.finance.yahoo.com"), error.message!!)
        assertTrue(error.message!!.contains("query2.finance.yahoo.com"), error.message!!)
    }

    @Test
    fun `a second call reuses the cached session and performs no further requests`() = runTest {
        val transport = FakeYahooTransport()
            .alwaysCookies(YahooEndpoints.SESSION_URL, FakeYahooTransport.SESSION_COOKIES)
            .alwaysText("/v1/test/getcrumb", FakeYahooTransport.CRUMB)
        val handshake = YahooCrumbHandshake(transport)

        val first = handshake.session()
        val second = handshake.session()

        assertEquals(first, second)
        assertEquals(2, transport.urls.size, "the handshake must happen once, not once per caller")
        assertEquals(1, handshake.handshakeCount)
    }

    @Test
    fun `concurrent callers do not stampede the handshake`() = runTest {
        val transport = FakeYahooTransport()
            .alwaysCookies(YahooEndpoints.SESSION_URL, FakeYahooTransport.SESSION_COOKIES)
            .alwaysText("/v1/test/getcrumb", FakeYahooTransport.CRUMB)
        val handshake = YahooCrumbHandshake(transport)

        val sessions = listOf(
            handshake.session(),
            handshake.session(),
            handshake.session(),
        )

        assertEquals(1, sessions.distinct().size, "all three callers must share one session")
        assertEquals(1, handshake.handshakeCount)
        assertEquals(2, transport.urls.size, "only the cookie and crumb requests were issued")
    }

    @Test
    fun `invalidate forces exactly one fresh handshake`() = runTest {
        val transport = FakeYahooTransport()
            .alwaysCookies(YahooEndpoints.SESSION_URL, FakeYahooTransport.SESSION_COOKIES)
            .alwaysText("/v1/test/getcrumb", FakeYahooTransport.CRUMB)
        val handshake = YahooCrumbHandshake(transport)

        handshake.session()
        handshake.invalidate()
        assertNull(handshake.peek())
        handshake.session()

        assertEquals(2, handshake.handshakeCount)
        assertEquals(4, transport.urls.size)
    }

    @Test
    fun `cookie parsing keeps name and value and drops attributes`() {
        val cookies = YahooCookies.parse(
            listOf(
                "A3=abc123; Domain=.yahoo.com; Path=/; Secure; HttpOnly",
                "  GUC = AQAB ; Expires=Wed, 09 Jun 2027 10:18:14 GMT; Secure",
                "empty=; Path=/",
                "novalue; Path=/",
                "quoted=\"QsAlZx123\"; Path=/",
            ),
        )

        assertEquals(mapOf("A3" to "abc123", "GUC" to "AQAB", "quoted" to "QsAlZx123"), cookies)
        assertEquals("A3=abc123; GUC=AQAB; quoted=QsAlZx123", YahooCookies.headerOf(cookies))
    }

    @Test
    fun `a Set-Cookie header carrying a comma is not split`() {
        // `Expires=Wed, 09 Jun 2027` contains a comma; splitting on it would invent a cookie.
        val cookies = YahooCookies.parse(listOf("A1=abc; Expires=Wed, 09 Jun 2027 10:18:14 GMT; Path=/"))

        assertEquals(mapOf("A1" to "abc"), cookies)
    }

    @Test
    fun `cookies are read from the dedicated channel and the header channel alike`() {
        val response = HttpResponse(
            code = 200,
            body = ByteArray(0),
            headers = mapOf("set-cookie" to listOf("H1=fromheader; Path=/")),
            setCookies = listOf("H2=fromfield; Path=/"),
        )

        assertEquals(mapOf("H2" to "fromfield", "H1" to "fromheader"), YahooCookies.of(response))
    }

    @Test
    fun `a transport that throws during the session step becomes a typed failure`() = runTest {
        val transport = FakeYahooTransport()
            .failWith(YahooEndpoints.SESSION_URL) { java.io.IOException("no route to host") }

        val error = assertFailsWith<YahooHistoryException> { YahooCrumbHandshake(transport).session() }

        assertEquals(YahooFailureReason.HANDSHAKE_FAILED, error.reason)
        assertTrue(error.message!!.contains("no route to host"), error.message!!)
    }

    @Test
    fun `chart headers carry both the browser agent and the session cookie`() {
        val session = YahooSession(mapOf("A3" to "abc123"), "crumb", YahooHost.QUERY1)

        val headers = session.chartHeaders()

        assertEquals("A3=abc123", headers["Cookie"])
        assertTrue(headers["User-Agent"]!!.startsWith("Mozilla/5.0"), headers.toString())
    }

    @Test
    fun `the chart request a session can produce is the one the plan documents`() {
        val session = YahooSession(
            cookies = mapOf("A3" to "abc123"),
            crumb = FakeYahooTransport.CRUMB,
            host = YahooHost.QUERY1,
        )

        val request = HttpRequest(
            url = YahooEndpoints.chartUrl(YahooHost.QUERY1, "RELIANCE", "2y", YahooInterval.DAY, session),
            headers = session.chartHeaders(),
        )

        assertEquals(
            "https://query1.finance.yahoo.com/v8/finance/chart/RELIANCE.NS?range=2y&interval=1d" +
                "&crumb=${FakeYahooTransport.CRUMB}",
            request.url,
        )
        assertEquals("A3=abc123", request.headers["Cookie"])
    }

    @Test
    fun `an unusable session value never reaches a request`() {
        val bogus = assertFailsWith<IllegalArgumentException> {
            YahooEndpoints.chartUrl(
                YahooHost.QUERY1, "RELIANCE", "2y", YahooInterval.DAY,
                YahooSession(emptyMap(), "c", YahooHost.QUERY1),
            )
        }
        assertNotNull(bogus.message)
    }
}