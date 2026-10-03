package com.mia.app.data.android.net

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * Retry on *transient* answers only, and honour `Retry-After` when the server sends one.
 *
 * ### What is retried, and why exactly these
 *
 * | Response | Retried | Reason |
 * |---|---|---|
 * | `IOException` | yes | DNS blip, TLS renegotiation, a mobile handover mid-request. |
 * | `429` | yes | The rate limit plan.md §13 names for Yahoo; the server states when to come back. |
 * | `503` | yes | The site is up but overloaded — the ordinary "try later". |
 * | `5xx` other | yes | Server-side fault; a retry costs one request and the alternative is a dead tab. |
 * | `404` / `410` | **no** | A *fact*, not a fault. plan.md §3.1: a 404 before ~18:00 IST means "not published yet"; retrying inside the transport would hide the state plan.md §10's guard depends on. |
 * | `4xx` other | **no** | The request is wrong. Retrying it wastes the source's bandwidth. |
 *
 * ### `Retry-After`
 * Parsed as **seconds** per RFC 9110, converted to milliseconds and clamped to
 * [maxRetryAfterMillis]. An absent, unparseable, zero or negative value falls back to an
 * exponential backoff rather than to "do not retry": plan.md §13 lists "Yahoo 429" as the risk
 * that kills the history layer outright, so the retry is never skipped — only its length is a
 * guess. The HTTP-date form of `Retry-After` is deliberately not parsed: no source in plan.md
 * §3 sends it, and a hand-rolled date parser on a phone's clock is a worse failure than
 * ignoring the header.
 *
 * ### Why this is an interceptor and not a loop in `:data`
 * `:data` already owns its own retry policy for screener.in (`ScreenerInSource.Policy`) and for
 * Yahoo (one extra round after a 429). This interceptor is the floor underneath them — it covers
 * the requests those policies do not, such as the bhavcopy mirrors — so the number of attempts
 * is bounded at every layer instead of multiplying.
 */
class RetryInterceptor(
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val backoffBaseMillis: Long = DEFAULT_BACKOFF_BASE_MILLIS,
    private val backoffMaxMillis: Long = DEFAULT_BACKOFF_MAX_MILLIS,
    private val maxRetryAfterMillis: Long = DEFAULT_MAX_RETRY_AFTER_MILLIS,
    /** Blocking sleep; OkHttp calls interceptors on its own dispatcher thread, never the main one. */
    private val sleeper: (Long) -> Unit = { millis -> if (millis > 0L) Thread.sleep(millis) },
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var attempt = 0
        var backoff = backoffBaseMillis

        while (true) {
            attempt++
            val response = try {
                chain.proceed(request)
            } catch (e: IOException) {
                if (attempt >= maxAttempts) throw e
                sleeper(backoff)
                backoff = (backoff * 2).coerceAtMost(backoffMaxMillis)
                continue
            }

            if (attempt >= maxAttempts || !isRetryableStatus(response.code)) {
                return response
            }

            // The body must be released before the connection can be reused for the retry;
            // OkHttp throws otherwise, which would turn a 429 into a leaked connection.
            response.close()
            sleeper(retryAfterMillis(response) ?: backoff)
            backoff = (backoff * 2).coerceAtMost(backoffMaxMillis)
        }
    }

    private fun isRetryableStatus(code: Int): Boolean =
        code == 429 || code == 503 || code in 500..599

    /** `Retry-After` in seconds → milliseconds, clamped. Absent/garbage → `null`. */
    private fun retryAfterMillis(response: Response): Long? {
        val raw = response.header(HEADER_RETRY_AFTER)?.trim()?.toLongOrNull() ?: return null
        if (raw <= 0L) return null
        return (raw * 1_000L).coerceAtMost(maxRetryAfterMillis)
    }

    companion object {
        const val HEADER_RETRY_AFTER: String = "Retry-After"

        /**
         * Three attempts: one, plus two retries. Three keeps the worst case (two backoffs of
         * 1 s and 2 s plus three requests) inside a WorkManager run that also has to parse a
         * 3,712-row bhavcopy on a 2 GB phone (plan.md §13).
         */
        const val DEFAULT_MAX_ATTEMPTS: Int = 3

        const val DEFAULT_BACKOFF_BASE_MILLIS: Long = 1_000L
        const val DEFAULT_BACKOFF_MAX_MILLIS: Long = 8_000L

        /** Waiting an hour on a mobile network is not waiting. */
        const val DEFAULT_MAX_RETRY_AFTER_MILLIS: Long = 30_000L
    }
}