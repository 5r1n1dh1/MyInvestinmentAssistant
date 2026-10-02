package com.mia.app.data.fundamentals

import com.mia.app.data.http.HttpRequest
import com.mia.app.data.http.HttpTransport
import com.mia.app.domain.model.ParserHealth
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate

/**
 * Fetches one screener.in consolidated company page and parses it.
 *
 * ### Robots and request discipline (plan.md §3.6, §13)
 * * **Exactly one URL shape is ever built** — see [ScreenerInUrls]. No search, no pagination,
 *   no sub-schedules. Nothing here accepts a URL; it accepts a *symbol*.
 * * **Requests are serialised** behind a [Mutex] and spaced by at least
 *   [Policy.minIntervalMillis]. screener.in is a small site served to individuals from behind
 *   CGNAT (plan.md §13), where one abusive-looking scraper can look like thousands. On-device
 *   fundamentals are cached and refreshed once a day (plan.md §10), so a slow source costs
 *   nothing and a fast one costs the site its availability.
 * * **429 and 503 are retried with exponential backoff**, honouring `Retry-After` when the
 *   server sends it. Every other non-2xx is terminal: a 404 means screener.in does not know
 *   that symbol, and retrying it just wastes a request the site has to answer.
 *
 * ### Failures are health, not exceptions
 * A rejected symbol, a 404, a rate-limit that never clears and an unparseable page all return a
 * [FundamentalsParseResult] whose [ParserHealth.ok] is false with the reason in its notes.
 * plan.md §12: silent-empty is indistinguishable from "no red flags". Throwing would push every
 * one of these into whatever `try` the caller forgot to write.
 *
 * The only non-`fetch` API is [ScreenerInUrls.companyPage], which throws on a bad symbol on
 * purpose — that is a programming error, not a network outcome.
 */
class ScreenerInSource(
    private val transport: HttpTransport,
    private val policy: Policy = Policy(),
    /** Injected so tests can assert the backoff schedule without actually waiting. */
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
) {

    /**
     * Fetch-and-parse policy. The defaults are deliberately unhurried: fundamentals are
     * daily-cached (plan.md §10), so a slower first byte costs the user nothing.
     */
    data class Policy(
        /** Minimum wall-clock gap between two requests, enforced inside the mutex. */
        val minIntervalMillis: Long = 3_000,
        /** Total attempts including the first. `3` means one request plus two retries. */
        val maxAttempts: Int = 3,
        /** First backoff; doubles per retry up to [backoffMaxMillis]. */
        val backoffBaseMillis: Long = 2_000,
        val backoffMaxMillis: Long = 30_000,
    )

    /** Serialises every request and owns the last-request timestamp. */
    private val gate = Mutex()
    private var lastRequestAtMillis: Long = 0

    /**
     * Reads `https://www.screener.in/company/{SYMBOL}/consolidated/`.
     *
     * @param asOf the "as of" date stamped onto the ratios. Defaults to today in India,
     *   because screener.in publishes no timestamp for its headline ratios and plan.md §10
     *   requires the date to be visible anyway.
     */
    suspend fun fetch(
        symbol: String,
        asOf: LocalDate = FundamentalsParserFacade.todayInIndia(),
    ): FundamentalsParseResult = gate.withLock {
        val url = try {
            ScreenerInUrls.companyPage(symbol)
        } catch (e: IllegalArgumentException) {
            return@withLock FundamentalsParserFacade.failure(
                symbol = symbol,
                reason = "rejected symbol '${symbol.trim()}': ${e.message} — screener.in is only " +
                    "ever asked for one permitted URL shape (plan.md §3.6)",
                asOf = asOf,
            )
        }

        var attempt = 0
        var backoff = policy.backoffBaseMillis
        val problems = mutableListOf<String>()

        while (true) {
            attempt++
            throttle()
            val response = try {
                transport.execute(request(url))
            } catch (e: Exception) {
                problems += "attempt $attempt: transport error ${e.javaClass.simpleName}: ${e.message}"
                if (attempt >= policy.maxAttempts) break
                sleeper(backoff)
                backoff = (backoff * 2).coerceAtMost(policy.backoffMaxMillis)
                continue
            }

            when {
                response.code == 429 || response.code == 503 -> {
                    problems += "attempt $attempt: HTTP ${response.code}" +
                        (response.header("Retry-After")?.let { " (Retry-After: $it)" } ?: "")
                    if (attempt >= policy.maxAttempts) {
                        return@withLock FundamentalsParserFacade.failure(
                            symbol = symbol,
                            reason = "screener.in rate-limited this request (HTTP ${response.code}) " +
                                "after $attempt attempt(s); ${problems.joinToString("; ")}",
                            asOf = asOf,
                        ).copy(httpCode = response.code, attempts = attempt, url = url)
                    }
                    val wait = retryAfterMillis(response) ?: backoff
                    sleeper(wait)
                    backoff = (backoff * 2).coerceAtMost(policy.backoffMaxMillis)
                }

                !response.isSuccess -> {
                    val reason = if (response.code == 404 || response.code == 410) {
                        "screener.in has no consolidated page for '$symbol' (HTTP ${response.code}); " +
                            "the symbol may be wrong, delisted, or BSE-only — search locally via " +
                            "EQUITY_L.csv rather than hitting the disallowed ?q= endpoint (plan.md §9)"
                    } else {
                        "screener.in answered HTTP ${response.code}"
                    }
                    return@withLock FundamentalsParserFacade.failure(
                        symbol = symbol,
                        reason = reason,
                        asOf = asOf,
                    ).copy(httpCode = response.code, attempts = attempt, url = url)
                }

                response.body.isEmpty() -> {
                    return@withLock FundamentalsParserFacade.failure(
                        symbol = symbol,
                        reason = "screener.in answered HTTP ${response.code} with 0 bytes for '$symbol'",
                        asOf = asOf,
                    ).copy(httpCode = response.code, attempts = attempt, url = url)
                }

                else -> {
                    val parsed = try {
                        FundamentalsParserFacade.parse(response.text, symbol, asOf)
                    } catch (e: Exception) {
                        // The facade is written not to throw; this is the belt to its braces,
                        // because "an exception became a crash" is the worst version of §12.
                        return@withLock FundamentalsParserFacade.failure(
                            symbol = symbol,
                            reason = "screener.in HTML for '$symbol' could not be parsed: " +
                                "${e.javaClass.simpleName}: ${e.message}",
                            asOf = asOf,
                        ).copy(httpCode = response.code, attempts = attempt, url = url)
                    }
                    return@withLock parsed.copy(httpCode = response.code, attempts = attempt, url = url)
                }
            }
        }

        FundamentalsParserFacade.failure(
            symbol = symbol,
            reason = "every attempt to reach screener.in failed — ${problems.joinToString("; ")}",
            asOf = asOf,
        ).copy(attempts = attempt, url = url)
    }

    private fun request(url: String): HttpRequest = HttpRequest(
        url = url,
        method = "GET",
        headers = HEADERS,
    )

    /**
     * Holds the minimum gap between requests. Runs inside the mutex, so it is safe without any
     * further locking.
     */
    private suspend fun throttle() {
        val now = System.currentTimeMillis()
        val since = now - lastRequestAtMillis
        if (lastRequestAtMillis != 0L && since < policy.minIntervalMillis) {
            sleeper(policy.minIntervalMillis - since)
        }
        lastRequestAtMillis = System.currentTimeMillis()
    }

    /** `Retry-After` in seconds, clamped to [Policy.backoffMaxMillis]. Absent/invalid → `null`. */
    private fun retryAfterMillis(response: com.mia.app.data.http.HttpResponse): Long? {
        val raw = response.header("Retry-After")?.trim()?.toLongOrNull() ?: return null
        if (raw <= 0) return null
        return (raw * 1_000).coerceAtMost(policy.backoffMaxMillis)
    }

    companion object {
        /**
         * plan.md §3.3: the page requires `Referer: https://www.screener.in/`. The golden
         * fixtures were captured with exactly these headers and they are what make the page
         * return the server-rendered tables instead of refusing the client.
         */
        val HEADERS: Map<String, String> = mapOf(
            "Referer" to ScreenerInUrls.REFERER,
            "User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9",
        )

        /** Exposed so a caller can render plan.md §1.4 rule 3 ("source") without hardcoding it. */
        val HEALTHY_SECTIONS: List<String> = ScreenerHtml.SECTION_IDS

        /** True when [health] describes a complete parse. */
        fun isComplete(health: ParserHealth): Boolean =
            health.ok && health.sectionsMissing.isEmpty() &&
                HEALTHY_SECTIONS.all { it in health.sectionsFound }
    }
}
