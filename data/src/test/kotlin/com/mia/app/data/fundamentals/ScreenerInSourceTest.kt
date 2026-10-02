package com.mia.app.data.fundamentals

import com.mia.app.data.http.HttpRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fetch choreography against a hand-written fake transport — no network, no mocking library
 * (docs/CONTRACTS.md).
 *
 * What is under test is what cannot be checked against a fixture: the exact request, the
 * `Referer` the page requires, and what each failure mode turns into.
 */
class ScreenerInSourceTest {

    private val url = "https://www.screener.in/company/RELIANCE/consolidated/"

    // ------------------------------------------------------------------- the request itself

    @Test
    fun `the request is the permitted url with the Referer the page requires`() = runTest {
        val sleeper = RecordingSleeper()
        val transport = FakeScreenerTransport { FakeScreenerTransport.html(ScreenerFixtures.html("RELIANCE")) }
        val source = ScreenerInSource(transport, ScreenerInSource.Policy(minIntervalMillis = 0), sleeper.sleeper)

        val result = source.fetch("RELIANCE", ScreenerFixtures.AS_OF)

        assertEquals(1, transport.count)
        val request: HttpRequest = transport.requests.single()
        assertEquals(url, request.url)
        assertEquals("GET", request.method)
        assertEquals("https://www.screener.in/", request.headers["Referer"], "plan.md §3.3")
        assertNotNull(request.headers["User-Agent"])
        assertNull(request.body)

        assertTrue(result.health.ok, result.health.notes.toString())
        assertEquals(200, result.httpCode)
        assertEquals(1, result.attempts)
        assertEquals(url, result.url)
        assertEquals("RELIANCE", result.snapshot.symbol)
        assertEquals("Reliance Industries Ltd", result.snapshot.name)
        assertEquals(21.2, result.snapshot.ratios.pe)
        assertEquals(ScreenerFixtures.AS_OF, result.snapshot.ratios.asOf)
    }

    // ---------------------------------------------------------------------------- rate limit

    @Test
    fun `a 429 is retried after a backoff and the retry succeeds`() = runTest {
        val sleeper = RecordingSleeper()
        var served = 0
        val transport = FakeScreenerTransport {
            served++
            if (served == 1) {
                FakeScreenerTransport.status(429)
            } else {
                FakeScreenerTransport.html(ScreenerFixtures.html("RELIANCE"))
            }
        }
        val policy = ScreenerInSource.Policy(minIntervalMillis = 0, backoffBaseMillis = 2_000, maxAttempts = 3)
        val source = ScreenerInSource(transport, policy, sleeper.sleeper)

        val result = source.fetch("RELIANCE", ScreenerFixtures.AS_OF)

        assertEquals(2, transport.count, "one 429 then one success")
        assertEquals(2, result.attempts)
        assertTrue(result.health.ok)
        assertEquals(listOf(2_000L), sleeper.waits, "waited exactly one backoff step")
        assertEquals(url, transport.urls.first())
    }

    @Test
    fun `the backoff doubles between retries`() = runTest {
        val sleeper = RecordingSleeper()
        val transport = FakeScreenerTransport { FakeScreenerTransport.status(429) }
        val policy = ScreenerInSource.Policy(
            minIntervalMillis = 0,
            maxAttempts = 4,
            backoffBaseMillis = 1_000,
            backoffMaxMillis = 10_000,
        )
        val source = ScreenerInSource(transport, policy, sleeper.sleeper)

        val result = source.fetch("RELIANCE", ScreenerFixtures.AS_OF)

        assertEquals(4, transport.count)
        assertEquals(listOf(1_000L, 2_000L, 4_000L), sleeper.waits)
        assertFalse(result.health.ok)
        assertEquals(4, result.attempts)
        assertEquals(429, result.httpCode)
        assertTrue(result.health.notes.any { it.contains("rate-limited") })
    }

    @Test
    fun `Retry-After wins over the computed backoff`() = runTest {
        val sleeper = RecordingSleeper()
        var served = 0
        val transport = FakeScreenerTransport {
            served++
            if (served == 1) {
                FakeScreenerTransport.status(429, headers = mapOf("Retry-After" to listOf("7")))
            } else {
                FakeScreenerTransport.html(ScreenerFixtures.html("TCS"))
            }
        }
        val policy = ScreenerInSource.Policy(
            minIntervalMillis = 0,
            maxAttempts = 2,
            backoffBaseMillis = 500,
            backoffMaxMillis = 30_000,
        )
        val source = ScreenerInSource(transport, policy, sleeper.sleeper)

        val result = source.fetch("TCS", ScreenerFixtures.AS_OF)

        assertTrue(result.health.ok)
        assertEquals(listOf(7_000L), sleeper.waits)
    }

    @Test
    fun `a 503 is treated as a rate limit not as a hard failure`() = runTest {
        val sleeper = RecordingSleeper()
        var served = 0
        val transport = FakeScreenerTransport {
            served++
            if (served < 3) {
                FakeScreenerTransport.status(503)
            } else {
                FakeScreenerTransport.html(ScreenerFixtures.html("TCS"))
            }
        }
        val source = ScreenerInSource(
            transport,
            ScreenerInSource.Policy(minIntervalMillis = 0, maxAttempts = 3),
            sleeper.sleeper,
        )

        val result = source.fetch("TCS", ScreenerFixtures.AS_OF)
        assertTrue(result.health.ok)
        assertEquals(3, transport.count)
    }

    // ------------------------------------------------------------------------------- 404

    @Test
    fun `a 404 yields a clear not-ok health result and is not retried`() = runTest {
        val sleeper = RecordingSleeper()
        val transport = FakeScreenerTransport { FakeScreenerTransport.status(404) }
        val source = ScreenerInSource(transport, ScreenerInSource.Policy(minIntervalMillis = 0), sleeper.sleeper)

        val result = source.fetch("NOSUCHSYMBOL", ScreenerFixtures.AS_OF)

        assertEquals(1, transport.count, "a 404 is an answer, not a transient failure")
        assertFalse(result.health.ok)
        assertEquals(404, result.httpCode)
        val note = result.health.notes.single()
        assertTrue(note.contains("no consolidated page for 'NOSUCHSYMBOL'"), note)
        assertTrue(note.contains("HTTP 404"), note)
        assertTrue(note.contains("EQUITY_L.csv"), "must point at the local symbol book, not ?q=")
        assertTrue(ScreenerHtml.SECTION_IDS.all { it in result.health.sectionsMissing })
        assertEquals(emptyList(), result.snapshot.quarters)
        assertNull(result.snapshot.ratios.pe)
    }

    @Test
    fun `other non-2xx codes are terminal and reported`() = runTest {
        for (code in listOf(400, 403, 410, 500)) {
            val sleeper = RecordingSleeper()
            val transport = FakeScreenerTransport { FakeScreenerTransport.status(code) }
            val source = ScreenerInSource(transport, ScreenerInSource.Policy(minIntervalMillis = 0), sleeper.sleeper)
            val result = source.fetch("RELIANCE", ScreenerFixtures.AS_OF)
            assertEquals(1, transport.count, "HTTP $code must not be retried")
            assertFalse(result.health.ok, "HTTP $code")
            assertEquals(code, result.httpCode)
            assertTrue(result.health.notes.any { it.contains("HTTP $code") }, result.health.notes.toString())
        }
    }

    @Test
    fun `a 200 with zero bytes is reported rather than parsed as an empty page`() = runTest {
        val sleeper = RecordingSleeper()
        val transport = FakeScreenerTransport { FakeScreenerTransport.status(200) }
        val source = ScreenerInSource(transport, ScreenerInSource.Policy(minIntervalMillis = 0), sleeper.sleeper)
        val result = source.fetch("RELIANCE", ScreenerFixtures.AS_OF)
        assertFalse(result.health.ok)
        assertTrue(result.health.notes.single().contains("0 bytes"))
    }

    // ------------------------------------------------------------------ transport failures

    @Test
    fun `a throwing transport is retried and then surfaced as health not as an exception`() = runTest {
        val sleeper = RecordingSleeper()
        var served = 0
        val transport = FakeScreenerTransport {
            served++
            throw IOException("socket closed")
        }
        val source = ScreenerInSource(
            transport,
            ScreenerInSource.Policy(minIntervalMillis = 0, maxAttempts = 2, backoffBaseMillis = 100),
            sleeper.sleeper,
        )

        val result = source.fetch("RELIANCE", ScreenerFixtures.AS_OF)

        assertEquals(2, transport.count)
        assertFalse(result.health.ok)
        assertEquals(2, result.attempts)
        val note = result.health.notes.single()
        assertTrue(note.contains("every attempt to reach screener.in failed"), note)
        assertTrue(note.contains("transport error IOException"), note)
    }

    @Test
    fun `a transport that throws once then succeeds recovers`() = runTest {
        val sleeper = RecordingSleeper()
        var served = 0
        val transport = FakeScreenerTransport {
            served++
            if (served == 1) throw IOException("handshake failed") else FakeScreenerTransport.html(
                ScreenerFixtures.html("INFY"),
            )
        }
        val source = ScreenerInSource(
            transport,
            ScreenerInSource.Policy(minIntervalMillis = 0, maxAttempts = 2, backoffBaseMillis = 100),
            sleeper.sleeper,
        )
        val result = source.fetch("INFY", ScreenerFixtures.AS_OF)
        assertTrue(result.health.ok, result.health.notes.toString())
        assertEquals(2, result.attempts)
        assertEquals(13.5, result.snapshot.ratios.pe)
    }

    // ------------------------------------------------------------------------ throttling

    @Test
    fun `consecutive fetches are spaced by the minimum interval`() = runTest {
        val sleeper = RecordingSleeper()
        val transport = FakeScreenerTransport { FakeScreenerTransport.html(ScreenerFixtures.html("TCS")) }
        val source = ScreenerInSource(
            transport,
            ScreenerInSource.Policy(minIntervalMillis = 3_000, maxAttempts = 1),
            sleeper.sleeper,
        )

        source.fetch("TCS", ScreenerFixtures.AS_OF)
        assertEquals(emptyList(), sleeper.waits, "the first request is not throttled")

        source.fetch("TCS", ScreenerFixtures.AS_OF)
        assertEquals(1, sleeper.waits.size, "the second request waits")
        val wait = sleeper.waits.single()
        assertTrue(wait in 1..3_000, "waited $wait ms, expected a positive gap no larger than the interval")
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `concurrent fetches never overlap on the wire`() = runTest {
        val sleeper = RecordingSleeper()
        val inFlight = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val release = CompletableDeferred<Unit>()
        val transport = FakeScreenerTransport {
            val now = inFlight.incrementAndGet()
            peak.accumulateAndGet(now) { a, b -> maxOf(a, b) }
            release.await()
            inFlight.decrementAndGet()
            FakeScreenerTransport.html(ScreenerFixtures.html("TCS"))
        }
        val source = ScreenerInSource(
            transport,
            ScreenerInSource.Policy(minIntervalMillis = 0, maxAttempts = 1),
            sleeper.sleeper,
        )

        val first = launch { source.fetch("TCS", ScreenerFixtures.AS_OF) }
        val second = launch { source.fetch("TCS", ScreenerFixtures.AS_OF) }
        runCurrent()

        // The first request is parked inside the transport. Without the mutex the second
        // coroutine would have entered the transport too, and screener.in would have seen two
        // simultaneous requests from one client (plan.md §13, CGNAT).
        assertEquals(1, inFlight.get(), "two requests were on the wire at once")
        assertEquals(1, transport.count)

        release.complete(Unit)
        first.join()
        second.join()
        assertEquals(2, transport.count)
        assertEquals(1, peak.get(), "the source must serialise its requests")
    }

    // ------------------------------------------------------------------------ completeness

    @Test
    fun `the fetch result is complete for all five fixtures`() = runTest {
        val sleeper = RecordingSleeper()
        for (symbol in ScreenerFixtures.symbols) {
            val transport = FakeScreenerTransport {
                FakeScreenerTransport.html(ScreenerFixtures.html(symbol))
            }
            val source = ScreenerInSource(
                transport,
                ScreenerInSource.Policy(minIntervalMillis = 0, maxAttempts = 1),
                sleeper.sleeper,
            )
            val result = source.fetch(symbol, ScreenerFixtures.AS_OF)
            assertTrue(ScreenerInSource.isComplete(result.health), "$symbol incomplete: ${result.health.sectionsMissing}")
        }
    }
}
