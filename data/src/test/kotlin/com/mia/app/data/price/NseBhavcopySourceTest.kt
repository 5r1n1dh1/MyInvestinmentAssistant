package com.mia.app.data.price

import com.mia.app.data.http.HttpRequest
import com.mia.app.data.http.HttpResponse
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
 * Host choreography against a hand-written fake transport — no network, no mocking library
 * (docs/CONTRACTS.md).
 *
 * The behaviour under test is the one that cannot be checked against the live service: which
 * host is asked first, what happens when it fails, and what a 404 means.
 */
class NseBhavcopySourceTest {

    private val tradeDate = LocalDate.of(2026, 10, 1)
    private val primaryUrl = BhavcopyUrls.primary(tradeDate)
    private val mirrorUrl = BhavcopyUrls.mirror(tradeDate)

    // --------------------------------------------------------------------- url building

    @Test
    fun `url construction follows the verified NSE naming`() {
        assertEquals(
            "https://nsearchives.nseindia.com/content/cm/" +
                "BhavCopy_NSE_CM_0_0_0_20261001_F_0000.csv.zip",
            primaryUrl,
        )
        assertEquals(
            "https://archives.nseindia.com/content/cm/" +
                "BhavCopy_NSE_CM_0_0_0_20261001_F_0000.csv.zip",
            mirrorUrl,
        )
        assertEquals(listOf(primaryUrl, mirrorUrl), BhavcopyUrls.candidates(tradeDate))
        assertEquals(
            "https://archives.nseindia.com/products/content/sec_bhavdata_full_01102026.csv",
            BhavcopyUrls.legacy(tradeDate),
        )
    }

    @Test
    fun `url construction pads single digit months and days`() {
        val date = LocalDate.of(2026, 3, 7)
        assertTrue(BhavcopyUrls.primary(date).endsWith("BhavCopy_NSE_CM_0_0_0_20260307_F_0000.csv.zip"))
        assertTrue(BhavcopyUrls.legacy(date).endsWith("sec_bhavdata_full_07032026.csv"))
    }

    // ------------------------------------------------------------------- host fallback

    @Test
    fun `the primary host is tried first and a healthy response stops the search`() = runTest {
        val transport = FakeHttpTransport { FakeHttpTransport.ok(Fixtures.bhavcopyZip) }

        val result = NseBhavcopySource(transport).fetch(tradeDate)

        assertEquals(listOf(primaryUrl), transport.urls, "the mirror must not be contacted")
        assertEquals(3051, result.candles.size)
        assertTrue(result.health.ok)
        assertNull(result.fetchFailure)
    }

    @Test
    fun `a dead primary host falls through to the mirror`() = runTest {
        // This is the real 2026-10-02 observation: nsearchives fails the TLS handshake, the
        // archives mirror serves the identical archive.
        val transport = FakeHttpTransport { request ->
            if (request.url.startsWith(BhavcopyUrls.PRIMARY)) throw IOException("HTTP/2 protocol error")
            FakeHttpTransport.ok(Fixtures.bhavcopyZip)
        }

        val result = NseBhavcopySource(transport).fetch(tradeDate)

        assertEquals(listOf(primaryUrl, mirrorUrl), transport.urls)
        assertEquals(3051, result.candles.size)
        assertEquals(LocalDate.of(2026, 10, 1), result.tradDt)
        assertTrue(result.health.ok)
    }

    @Test
    fun `both hosts serving the identical archive produce identical candles`() = runTest {
        // Served from the primary host…
        val viaPrimary = NseBhavcopySource(
            FakeHttpTransport { request ->
                FakeHttpTransport.ok(Fixtures.bhavcopyZip)
                    .also { assertTrue(request.url.startsWith(BhavcopyUrls.PRIMARY)) }
            },
        ).fetch(tradeDate)

        // …and from the mirror, after the primary is made to fail.
        val viaMirror = NseBhavcopySource(
            FakeHttpTransport { request ->
                if (request.url.startsWith(BhavcopyUrls.PRIMARY)) {
                    throw IOException("handshake failed")
                }
                FakeHttpTransport.ok(Fixtures.bhavcopyZip)
                    .also { assertTrue(request.url.startsWith(BhavcopyUrls.MIRROR)) }
            },
        ).fetch(tradeDate)

        assertEquals(3051, viaPrimary.candles.size)
        assertEquals(3051, viaMirror.candles.size)
        assertEquals(viaPrimary.candles, viaMirror.candles)
        assertEquals(viaPrimary.rows, viaMirror.rows)
    }

    @Test
    fun `a 403 on the primary falls through to the mirror`() = runTest {
        val transport = FakeHttpTransport { request ->
            if (request.url.startsWith(BhavcopyUrls.PRIMARY)) {
                FakeHttpTransport.status(403)
            } else {
                FakeHttpTransport.ok(Fixtures.bhavcopyZip)
            }
        }

        val result = NseBhavcopySource(transport).fetch(tradeDate)

        assertEquals(listOf(primaryUrl, mirrorUrl), transport.urls)
        assertEquals(3051, result.candles.size)
    }

    @Test
    fun `a 2xx body that is not a zip falls through to the mirror`() = runTest {
        val transport = FakeHttpTransport { request ->
            if (request.url.startsWith(BhavcopyUrls.PRIMARY)) {
                FakeHttpTransport.text("<html>Akamai</html>")
            } else {
                FakeHttpTransport.ok(Fixtures.bhavcopyZip)
            }
        }

        val result = NseBhavcopySource(transport).fetch(tradeDate)

        assertEquals(listOf(primaryUrl, mirrorUrl), transport.urls)
        assertEquals(3051, result.candles.size)
    }

    @Test
    fun `when every host fails the failure is typed and names both attempts`() = runTest {
        val transport = FakeHttpTransport { request ->
            if (request.url.startsWith(BhavcopyUrls.PRIMARY)) throw IOException("handshake failed")
            FakeHttpTransport.status(500)
        }

        val result = NseBhavcopySource(transport).fetch(tradeDate)

        assertEquals(listOf(primaryUrl, mirrorUrl), transport.urls)
        assertTrue(result.candles.isEmpty())
        assertFalse(result.health.ok)
        assertEquals(FetchFailure.HOST_UNREACHABLE, result.fetchFailure)
        assertTrue(result.health.notes.single().contains("handshake failed"))
        assertTrue(result.health.notes.single().contains("HTTP 500"))
    }

    // ---------------------------------------------------------------------- not published

    @Test
    fun `a 404 from every host is surfaced as not published rather than an exception`() = runTest {
        val transport = FakeHttpTransport { FakeHttpTransport.status(404) }

        val result = NseBhavcopySource(transport).fetch(tradeDate)

        assertEquals(listOf(primaryUrl, mirrorUrl), transport.urls)
        assertEquals(FetchFailure.NOT_PUBLISHED, result.fetchFailure)
        assertTrue(result.candles.isEmpty())
        assertFalse(result.health.ok)
        assertTrue(result.health.notes.single().contains("has not published"))
        assertTrue(result.health.notes.single().contains("2026-10-01"))
    }

    @Test
    fun `a 404 from one host only is not enough to declare the session unpublished`() = runTest {
        // Losing a trading day silently is the worst outcome, so a single host's 404 is not
        // accepted as proof; the other host is asked.
        val transport = FakeHttpTransport { request ->
            if (request.url.startsWith(BhavcopyUrls.PRIMARY)) {
                FakeHttpTransport.status(404)
            } else {
                FakeHttpTransport.ok(Fixtures.bhavcopyZip)
            }
        }

        val result = NseBhavcopySource(transport).fetch(tradeDate)

        assertEquals(listOf(primaryUrl, mirrorUrl), transport.urls)
        assertEquals(3051, result.candles.size)
        assertNull(result.fetchFailure)
    }

    @Test
    fun `an empty 200 body is a failure not an empty market`() = runTest {
        val transport = FakeHttpTransport { FakeHttpTransport.status(200) }

        val result = NseBhavcopySource(transport).fetch(tradeDate)

        assertTrue(result.candles.isEmpty())
        assertEquals(FetchFailure.HOST_UNREACHABLE, result.fetchFailure)
        assertTrue(result.health.notes.single().contains("0 bytes"))
    }

    // -------------------------------------------------------------------------- idempotency

    @Test
    fun `refetching the same session never duplicates candles`() = runTest {
        val transport = FakeHttpTransport { FakeHttpTransport.ok(Fixtures.bhavcopyZip) }
        val source = NseBhavcopySource(transport)

        // WorkManager retries the EOD worker (plan.md §10 backoff), so the same session is
        // fetched repeatedly. Each result must be the same set, and the accumulated candle list
        // must deduplicate to exactly one bar per symbol.
        val repeated = (1..3).map { source.fetch(tradeDate) }
        assertEquals(3051, repeated.map { it.candles.size }.distinct().single())
        // Structurally identical results: nothing accumulates across retries.
        assertEquals(1, repeated.distinct().size)

        val accumulated = repeated.flatMap { it.candles }
        val deduped = accumulated.distinctBy { it.symbol to it.timeframe to it.date }
        assertEquals(3051, deduped.size)
        assertEquals(3051, accumulated.map { it.symbol }.toSet().size)
        assertEquals(3 * 3051, accumulated.size)
    }

    @Test
    fun `a retry after a host failure still yields exactly one set of candles`() = runTest {
        var primaryBroken = true
        val transport = FakeHttpTransport { request ->
            if (request.url.startsWith(BhavcopyUrls.PRIMARY) && primaryBroken) {
                throw IOException("flaky")
            }
            FakeHttpTransport.ok(Fixtures.bhavcopyZip)
        }
        val source = NseBhavcopySource(transport)

        val first = source.fetch(tradeDate)
        primaryBroken = false
        val second = source.fetch(tradeDate)

        assertEquals(listOf(primaryUrl, mirrorUrl), transport.urls.take(2))
        assertEquals(first.candles, second.candles)
        assertEquals(3051, second.candles.size)
        assertEquals(3, transport.requests.size)
    }

    // ----------------------------------------------------------------- latest on or before

    @Test
    fun `latest on or before walks back to the last published session`() = runTest {
        val published = LocalDate.of(2026, 9, 28) // a Monday before the 2026-10-01 session
        val transport = FakeHttpTransport { request ->
            if (request.url.contains(published.toString().replace("-", ""))) {
                FakeHttpTransport.ok(Fixtures.bhavcopyZip)
            } else {
                FakeHttpTransport.status(404)
            }
        }

        val result = NseBhavcopySource(transport).fetchLatestOnOrBefore(tradeDate)

        assertTrue(result.isUsable)
        // The returned date is the file's own date, not the requested one.
        assertEquals(LocalDate.of(2026, 10, 1), result.tradDt)
        assertEquals(3051, result.candles.size)
        // 10-01, 30-Sep and 29-Sep cost two requests each (both hosts) before 28-Sep answers
        // from the primary host.
        assertEquals(7, transport.requests.size)
        assertEquals(BhavcopyUrls.primary(published), transport.urls.last())
    }

    @Test
    fun `latest on or before fails after the lookback window with counts`() = runTest {
        val transport = FakeHttpTransport { FakeHttpTransport.status(404) }

        val result = NseBhavcopySource(transport).fetchLatestOnOrBefore(tradeDate)

        assertTrue(result.candles.isEmpty())
        assertEquals(FetchFailure.NOT_PUBLISHED, result.fetchFailure)
        assertTrue(result.health.notes.single().contains("${BhavcopyUrls.LOOKBACK_DAYS} day(s)"))
        assertEquals(BhavcopyUrls.LOOKBACK_DAYS * 2, transport.requests.size)
    }

    @Test
    fun `latest on or before asks for strictly earlier dates in order`() = runTest {
        val transport = FakeHttpTransport { FakeHttpTransport.status(404) }

        NseBhavcopySource(transport).fetchLatestOnOrBefore(tradeDate)

        assertEquals(
            (0 until BhavcopyUrls.LOOKBACK_DAYS).map { BhavcopyUrls.primary(tradeDate.minusDays(it.toLong())) },
            transport.urls.filter { it.startsWith(BhavcopyUrls.PRIMARY) },
        )
    }

    // ------------------------------------------------------------------------- interface

    @Test
    fun `the source is usable through the PriceSource seam`() = runTest {
        val transport = FakeHttpTransport { FakeHttpTransport.ok(Fixtures.bhavcopyZip) }
        val source: PriceSource = NseBhavcopySource(transport)

        val result = source.daily(tradeDate)

        assertEquals(3051, result.candles.size)
        assertEquals(1167.70, assertNotNull(result.candles.firstOrNull { it.symbol == "RELIANCE" }).close)
    }

    @Test
    fun `requests carry a user agent and ask for an unencoded body`() = runTest {
        val transport = FakeHttpTransport { FakeHttpTransport.ok(Fixtures.bhavcopyZip) }

        NseBhavcopySource(transport).fetch(tradeDate)

        val request: HttpRequest = transport.requests.single()
        assertEquals("GET", request.method)
        assertEquals("identity", request.headers["Accept-Encoding"])
        assertTrue(assertNotNull(request.headers["User-Agent"]).contains("MyInvestinmentAssistant"))
        // No auth of any kind: plan.md §3.1 verified the bhavcopy is free and open.
        assertNull(request.body)
    }

    @Test
    fun `an http error with a body is still treated as an error not as a payload`() = runTest {
        val transport = FakeHttpTransport {
            HttpResponse(code = 429, body = "<html>Too Many Requests</html>".toByteArray())
        }

        val result = NseBhavcopySource(transport).fetch(tradeDate)

        assertTrue(result.candles.isEmpty())
        assertEquals(FetchFailure.HOST_UNREACHABLE, result.fetchFailure)
        assertTrue(result.health.notes.single().contains("HTTP 429"))
    }
}
