package com.mia.app.data.android.net

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Serialises requests per host and enforces a minimum gap between them.
 *
 * ### Why this exists at all, given `:data` already throttles
 * `ScreenerInSource` has its own 3 s gap and its own mutex, because that policy is part of the
 * parser's tested contract. This interceptor is the **transport-level** floor underneath it, and
 * it exists for two reasons `:data` cannot cover:
 *
 * 1. **The bhavcopy hosts do not have a policy in `:data`.** Two mirror requests a second apart
 *    is exactly the profile plan.md §13 describes as "one abusive scraper behind a shared
 *    CGNAT address", and the mirror fallback is what turns one request into two.
 * 2. **A retry multiplies.** `RetryInterceptor` can turn one logical request into three. The
 *    rate limiter runs *before* it in the chain, so those three are spaced too.
 *
 * ### Only the named hosts are limited
 * [minIntervalMillis] applies to [throttledHosts] only. Yahoo's own pacing is deliberate (the
 * cookie + crumb handshake must not be delayed by an unrelated symbol's request) and NSE's
 * files are whole-market downloads, not per-symbol calls, so adding a second, hidden pacing
 * layer to either would be a behaviour nobody could reason about.
 *
 * ### Thread safety and determinism
 * Blocking, because it is called from an OkHttp interceptor. State is a per-host
 * `ReentrantLock` plus a last-request timestamp; the clock and the sleep are both injected, so
 * the pacing behaviour is assertable in a plain JVM test with no real waiting.
 */
class HostRateLimiter(
    private val minIntervalMillis: Long,
    private val throttledHosts: Set<String>,
    private val sleeper: (Long) -> Unit = { millis -> if (millis > 0L) Thread.sleep(millis) },
    private val nanoTime: () -> Long = { System.nanoTime() },
) : Interceptor {

    private val locks = HashMap<String, Any>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val host = chain.request().url.host.lowercase()
        acquire(host)
        return chain.proceed(chain.request())
    }

    /**
     * Blocks until [host] may be contacted, honouring the per-host interval.
     *
     * Split out of [intercept] so the pacing arithmetic is callable — and therefore assertable —
     * without an OkHttp chain. Returns immediately for an unthrottled host or a zero interval.
     */
    fun acquire(host: String): Long {
        val key = host.lowercase()
        if (key !in throttledHosts || minIntervalMillis <= 0L) return 0L

        val lock = synchronized(locks) { locks.getOrPut(key) { Any() } }
        var waited = 0L
        synchronized(lock) {
            val now = nanoTime()
            val last = lastRequestNanos[key]
            if (last != null) {
                val elapsedMillis = (now - last) / NANOS_PER_MILLI
                val wait = minIntervalMillis - elapsedMillis
                if (wait > 0L) {
                    waited = wait
                    sleeper(wait)
                }
            }
            lastRequestNanos[key] = nanoTime()
        }
        return waited
    }

    /** Clears the pacing state. Called when a new work cycle starts so a stale gap is not paid. */
    fun reset() {
        synchronized(locks) {
            lastRequestNanos.clear()
        }
    }

    private val lastRequestNanos = HashMap<String, Long>()

    companion object {
        private const val NANOS_PER_MILLI = 1_000_000L

        /**
         * 1.5 s between requests to a throttled host.
         *
         * `[data]` already spaces screener.in at 3 s (`ScreenerInSource.Policy`), so this value
         * is deliberately *below* it: the two must never be mistaken for each other, and the
         * stricter of the two is the one `:data` documents and tests.
         */
        const val DEFAULT_MIN_INTERVAL_MILLIS: Long = 1_500L

        /**
         * The hosts this limiter paces.
         *
         * screener.in is listed because it is the one source a CGNAT address must not hammer
         * (plan.md §3.6, §13); the two NSE hosts because the mirror fallback turns a single
         * request into two (plan.md §3.1).
         */
        val DEFAULT_HOSTS: Set<String> = setOf(
            "www.screener.in",
            "screener.in",
            "nsearchives.nseindia.com",
            "archives.nseindia.com",
        )

        /** Host of a URL, lower-cased. Exposed so the set above is testable. */
        fun hostOf(url: HttpUrl): String = url.host.lowercase()
    }
}