package com.mia.app.data.price

import com.mia.app.data.http.HttpRequest
import com.mia.app.data.http.HttpTransport
import com.mia.app.domain.model.ParserHealth
import java.time.LocalDate

/**
 * Fetches one day's whole-market bhavcopy, primary host first, mirror second.
 *
 * ### Why the fallback exists
 * plan.md §3.1 names `nsearchives.nseindia.com`. Re-verified 2026-10-02 from the development
 * machine, that host fails the TLS handshake (`curl` exit 16, `HTTP/2 protocol error`) and
 * then times out, while `archives.nseindia.com` returns `200` and a **byte-identical**
 * 208,712-byte archive (md5 `aa906c0012ac4c7b5cb8b7fe08044500`, matching
 * `data/src/test/resources/fixtures/bhavcopy_20261001.zip` exactly). Since both are front
 * doors onto the same archive namespace, falling back is free in fidelity and costs one extra
 * request — and plan.md §13's CGNAT mitigation keeps the daily cost to a handful of requests
 * for the *entire market*, so one extra is irrelevant next to a price layer that does not work.
 *
 * ### Decision table (documented so the behaviour is auditable, not accidental)
 *
 * | Response | Action |
 * |---|---|
 * | 2xx, payload parses with candles | return it — stop |
 * | 2xx, payload unparseable | remember the reason, try the next host |
 * | 404 / 410 | remember it, try the next host |
 * | 403 / 429 / 5xx, or a thrown transport error | remember the reason, try the next host |
 *
 * The result is `[FetchFailure.NOT_PUBLISHED]` **only when every candidate host answered
 * 404/410**. A single host 404ing is not proof: `nsearchives.nseindia.com` sits behind the same
 * Akamai edge as the rest of nseindia.com and could answer 404 for a client-side reason while
 * `archives.nseindia.com` still serves the file. Reporting "not published" from one host would
 * silently drop a trading day, which is the worst failure this class can have, while the cost
 * of asking twice is one extra request once a day (plan.md §13's request budget covers the whole
 * market). plan.md §3.1's verified behaviour — today's file 404s until ~18:00 IST — still
 * surfaces exactly as [FetchFailure.NOT_PUBLISHED], just after both hosts agree.
 *
 * ### Idempotency
 * Every call parses its own response into a fresh list, so re-running the worker (plan.md §10's
 * WorkManager exponential backoff) can never duplicate a candle.
 *
 * The class holds no mutable state, so concurrent calls for different dates are safe.
 */
class NseBhavcopySource(private val transport: HttpTransport) : PriceSource {

    override suspend fun daily(date: LocalDate): BhavcopyParseResult = fetch(date)

    /**
     * Fetches and parses [date]'s bhavcopy, trying every host in [BhavcopyUrls.candidates]
     * order until one yields candles.
     */
    suspend fun fetch(date: LocalDate): BhavcopyParseResult {
        val candidates = BhavcopyUrls.candidates(date)
        val problems = mutableListOf<String>()
        var notFound = 0

        for (url in candidates) {
            val response = try {
                transport.execute(HttpRequest(url = url, headers = HEADERS))
            } catch (e: Exception) {
                problems += "$url → transport error ${e.javaClass.simpleName}: ${e.message}"
                continue
            }

            when {
                response.code == 404 || response.code == 410 -> {
                    notFound++
                    problems += "$url → HTTP ${response.code}"
                }

                !response.isSuccess -> problems += "$url → HTTP ${response.code}"

                response.body.isEmpty() -> problems += "$url → HTTP ${response.code} with 0 bytes"

                else -> {
                    val parsed = NseBhavcopyParser.parseZip(response.body)
                    if (parsed.isUsable) return parsed
                    problems += "$url → HTTP ${response.code} but unparseable " +
                        "(${parsed.health.notes.firstOrNull()})"
                }
            }
        }

        return unreachable(date, problems, everyHostSaidNotPublished = notFound == candidates.size)
    }

    /**
     * Newest session published on or before [date], walking backwards over non-trading days.
     *
     * plan.md §3.1 puts publication at ~17:30–19:00 IST, so the 19:00 worker (plan.md §10)
     * can legitimately run before the file exists, and weekends/holidays never have a file at
     * all. The walk is capped at [BhavcopyUrls.LOOKBACK_DAYS] days: four days covers a normal
     * weekend plus one holiday, and a longer silence means the archive is missing data rather
     * than being closed — which the caller must see as a failure instead of waiting forever.
     *
     * **The returned [BhavcopyParseResult.tradDt] is the date that actually succeeded**, which
     * need not be [date]. A caller must stamp candles with that date; presenting yesterday's
     * bars under today's date would fabricate data (plan.md §1.4 rule 3).
     */
    suspend fun fetchLatestOnOrBefore(date: LocalDate): BhavcopyParseResult {
        var notPublished = 0
        val problems = mutableListOf<String>()

        for (offset in 0 until BhavcopyUrls.LOOKBACK_DAYS) {
            val candidate = date.minusDays(offset.toLong())
            val result = fetch(candidate)
            when {
                result.isUsable -> return result
                result.fetchFailure == FetchFailure.NOT_PUBLISHED -> notPublished++
                else -> problems += "$candidate → ${result.health.notes.firstOrNull()}"
            }
        }

        return BhavcopyParseResult(
            candles = emptyList(),
            health = ParserHealth.failed(
                buildString {
                    append("no bhavcopy published on or before $date within ")
                    append("${BhavcopyUrls.LOOKBACK_DAYS} day(s): ")
                    append("$notPublished date(s) not published")
                    if (problems.isNotEmpty()) append("; ").append(problems.joinToString("; "))
                },
            ),
            fetchFailure = if (notPublished > 0) {
                FetchFailure.NOT_PUBLISHED
            } else {
                FetchFailure.HOST_UNREACHABLE
            },
        )
    }

    private fun unreachable(
        date: LocalDate,
        problems: List<String>,
        everyHostSaidNotPublished: Boolean,
    ): BhavcopyParseResult {
        val detail = if (problems.isEmpty()) {
            "no candidate host was contacted"
        } else {
            problems.joinToString("; ")
        }
        return if (everyHostSaidNotPublished) {
            BhavcopyParseResult(
                candles = emptyList(),
                health = ParserHealth.failed(
                    "NSE has not published the $date bhavcopy yet (every host answered 404); " +
                        "expected around 18:00 IST (plan.md §3.1) — $detail",
                ),
                fetchFailure = FetchFailure.NOT_PUBLISHED,
            )
        } else {
            BhavcopyParseResult(
                candles = emptyList(),
                health = ParserHealth.failed("no host served the $date bhavcopy — $detail"),
                fetchFailure = FetchFailure.HOST_UNREACHABLE,
            )
        }
    }

    private companion object {
        /**
         * The bhavcopy needs no auth (plan.md §3.1), but it is a public web host rather than an
         * API: `Accept-Encoding: identity` keeps the archive bytes untouched so the zip can be
         * parsed directly, and the `User-Agent` identifies the app rather than impersonating a
         * browser.
         */
        val HEADERS: Map<String, String> = mapOf(
            "User-Agent" to "MyInvestinmentAssistant/1.0 (on-device NSE bhavcopy reader)",
            "Accept" to "*/*",
            "Accept-Encoding" to "identity",
        )
    }
}
