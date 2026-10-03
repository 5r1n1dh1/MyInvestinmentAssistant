package com.mia.app.data.android.net

import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The per-host rate limiter's pacing arithmetic, with an injected clock and an injected sleep so
 * no test ever waits.
 *
 * ### Why this is tested at all when no test can run on this machine
 * Because `:android-data` has no Android SDK here, so *nothing* in this module compiles or runs in
 * CI on this repository until a machine with the SDK builds it. What these tests therefore protect
 * is the review: the arithmetic is written out, so a reviewer can check it against the KDoc, and
 * the moment an SDK exists the suite runs without anyone re-deriving the intent.
 */
class HostRateLimiterTest {

    private class FakeClock(start: Long = 0L) {
        private val nanos = AtomicLong(start)

        fun now(): Long = nanos.get()

        fun advanceMillis(millis: Long) {
            nanos.addAndGet(millis * 1_000_000L)
        }
    }

    private fun limiter(
        minIntervalMillis: Long,
        hosts: Set<String> = setOf("www.screener.in"),
        clock: FakeClock = FakeClock(),
        waits: MutableList<Long> = mutableListOf(),
    ) = HostRateLimiter(
        minIntervalMillis = minIntervalMillis,
        throttledHosts = hosts,
        sleeper = { millis -> waits += millis; clock.advanceMillis(millis) },
        nanoTime = { clock.now() },
    )

    @Test
    fun `the first request to a throttled host is not delayed`() {
        val clock = FakeClock()
        val waits = mutableListOf<Long>()
        val rateLimiter = limiter(1_500L, clock = clock, waits = waits)

        rateLimiter.acquire("www.screener.in")
        rateLimiter.acquire("www.screener.in")

        // The first acquire records a timestamp; the second waits the full interval minus the
        // (zero) elapsed time.
        assertEquals(listOf(1_500L), waits)
        assertEquals(3_000L * 1_000_000L, clock.now())
    }

    @Test
    fun `a request after the interval has elapsed is not delayed`() {
        val clock = FakeClock()
        val waits = mutableListOf<Long>()
        val rateLimiter = limiter(1_500L, clock = clock, waits = waits)

        rateLimiter.acquire("www.screener.in")
        clock.advanceMillis(1_500L)
        rateLimiter.acquire("www.screener.in")

        assertEquals(emptyList(), waits)
    }

    @Test
    fun `a partially elapsed interval waits only the remainder`() {
        val clock = FakeClock()
        val waits = mutableListOf<Long>()
        val rateLimiter = limiter(1_500L, clock = clock, waits = waits)

        rateLimiter.acquire("www.screener.in")
        clock.advanceMillis(1_000L)
        rateLimiter.acquire("www.screener.in")

        assertEquals(listOf(500L), waits)
    }

    @Test
    fun `hosts are paced independently`() {
        val clock = FakeClock()
        val waits = mutableListOf<Long>()
        val rateLimiter = limiter(
            minIntervalMillis = 1_500L,
            hosts = setOf("www.screener.in", "archives.nseindia.com"),
            clock = clock,
            waits = waits,
        )

        rateLimiter.acquire("www.screener.in")
        rateLimiter.acquire("archives.nseindia.com")

        // A different host has no previous timestamp, so it is not delayed.
        assertEquals(emptyList(), waits)
    }

    @Test
    fun `an unthrottled host is never delayed`() {
        val clock = FakeClock()
        val waits = mutableListOf<Long>()
        val rateLimiter = limiter(1_500L, clock = clock, waits = waits)

        rateLimiter.acquire("query1.finance.yahoo.com")
        rateLimiter.acquire("query1.finance.yahoo.com")

        assertEquals(emptyList(), waits)
    }

    @Test
    fun `host matching is case-insensitive`() {
        val clock = FakeClock()
        val waits = mutableListOf<Long>()
        val rateLimiter = limiter(1_500L, hosts = setOf("www.screener.in"), clock = clock, waits = waits)

        rateLimiter.acquire("WWW.SCREENER.IN")
        rateLimiter.acquire("www.screener.in")

        assertEquals(listOf(1_500L), waits)
    }

    @Test
    fun `reset drops the recorded timestamp`() {
        val clock = FakeClock()
        val waits = mutableListOf<Long>()
        val rateLimiter = limiter(1_500L, clock = clock, waits = waits)

        rateLimiter.acquire("www.screener.in")
        rateLimiter.reset()
        rateLimiter.acquire("www.screener.in")

        assertEquals(emptyList(), waits)
    }

    @Test
    fun `a zero interval disables pacing entirely`() {
        val clock = FakeClock()
        val waits = mutableListOf<Long>()
        val rateLimiter = limiter(0L, clock = clock, waits = waits)

        rateLimiter.acquire("www.screener.in")
        rateLimiter.acquire("www.screener.in")

        assertEquals(emptyList(), waits)
    }

    @Test
    fun `the production defaults are the ones the KDoc states`() {
        assertEquals(1_500L, HostRateLimiter.DEFAULT_MIN_INTERVAL_MILLIS)
        assertTrue(HostRateLimiter.DEFAULT_HOSTS.contains("www.screener.in"))
        assertTrue(HostRateLimiter.DEFAULT_HOSTS.contains("archives.nseindia.com"))
        assertTrue(HostRateLimiter.DEFAULT_HOSTS.contains("nsearchives.nseindia.com"))
        // Yahoo is deliberately absent: its pacing is `:data`'s handshake policy.
        assertTrue(HostRateLimiter.DEFAULT_HOSTS.none { it.contains("yahoo") })
    }
}