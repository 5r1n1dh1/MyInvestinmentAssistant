package com.mia.app.data.price

import com.mia.app.data.history.YahooCrumbHandshake
import com.mia.app.data.history.YahooFailureReason
import com.mia.app.data.history.YahooHistoryException
import com.mia.app.data.history.YahooHost
import com.mia.app.data.http.HttpRequest
import com.mia.app.data.http.HttpTransport
import com.mia.app.domain.model.Timeframe
import kotlinx.coroutines.delay

/**
 * The index series, from Yahoo Finance.
 *
 * ### Why Yahoo, and why that is a measured decision rather than a preference
 * planv2 W3 left this open and docs/LIVE-PROBE.md closed it. GitHub Actions run 38949742442
 * (2026-10-16) replayed both routes from GitHub's network:
 *
 * | Route | Result |
 * |---|---|
 * | NSE `ind_close_all` index CSV, three hosts/shapes | **404 on every one** |
 * | `fc.yahoo.com` cookie seed | 404 **with** `Set-Cookie` — the documented good case |
 * | `query1…/v1/test/getcrumb` | **200**, crumb returned |
 * | `query1…/v8/finance/chart/RELIANCE.NS` with cookie + crumb | **200** |
 *
 * Yahoo serves `^NSEI`, `^NSEMDCP50` and `^NSEMDCP150` through that same endpoint, so the
 * benchmark series is obtainable. The NSE shapes stay on the record in
 * [IndexEndpoints.NSE_CSV_CANDIDATES] rather than being deleted, because a 404 from one network is
 * not proof a file does not exist and a future shape that answers should be a one-line change.
 *
 * ### What this class owns
 * * **The handshake, always.** There is no API here that issues a chart request without a session.
 *   `plan.md §3.2` measured that skipping it yields `429` on every request, and the failure that
 *   produced — a benchmark that could never load — is exactly what this workstream exists to
 *   remove, so it is made unrepresentable rather than merely discouraged.
 * * **The query1 → query2 fallback**, and the one-and-only-one retry after a `429`.
 * * **Typed failure.** Nothing throws out of here; every failure is an [IndexSeries] with an
 *   [IndexFailureReason], and the caller passes `null` to the engine, which unscores the Context
 *   factor and states why (`PatternEngine.dataQualityNotes`).
 *
 * ### What it deliberately does not own
 * The write-once cache. The EOD refresh writes the index once a day and every later read comes
 * from storage, because `plan.md §13`'s CGNAT mitigation is about looking like one client and an
 * index fetch per scan would not.
 *
 * ### The session is cached, and that is the whole point
 * [YahooCrumbHandshake] holds one session per process behind a mutex and re-handshakes only when
 * it is invalidated by a `429`. Re-performing the handshake per request would be the difference
 * between one client that looks like a reader and one that looks like a scraper.
 */
class YahooIndexSource(
    private val transport: HttpTransport,
    /** Injected explicitly so the one object that owns the session is visible in the graph. */
    private val handshake: YahooCrumbHandshake = YahooCrumbHandshake(transport),
    /** Injected so the tests can assert the 429 retry without waiting on it. */
    private val sleeper: suspend (Long) -> Unit = { millis -> delay(millis) },
) : IndexSource {

    /**
     * Fetches one index's daily series, or the typed reason there is none.
     *
     * | Outcome | [IndexFailureReason] | What the caller should do |
     * |---|---|---|
     * | the index is not one of [IndexSymbol] | `UNKNOWN_SYMBOL` | fix the configuration |
     * | no cookie or no crumb | `HANDSHAKE_FAILED` | pass `null`; try again after the next refresh |
     * | `429` everywhere, even after a new session | `RATE_LIMITED` | pass `null`; retry tomorrow |
     * | `404` | `NOT_FOUND` | the wire symbol is stale — the index was probably renamed |
     * | no 2xx anywhere | `HOST_UNREACHABLE` | pass `null`; the app works without a benchmark |
     * | `chart.error` or zero timestamps | `NO_DATA` | pass `null`; the symbol is wrong |
     * | body is not chart JSON | `UNPARSEABLE` | Yahoo changed the schema |
     * | every close null | `EMPTY_SERIES` | a source defect, not "the index did not trade" |
     * | fewer than [IndexEndpoints.MIN_USABLE_BARS] bars | `NO_BENCHMARK_ROWS` | retry with a deeper range |
     */
    override suspend fun daily(
        symbol: IndexSymbol,
        range: String,
    ): IndexSeries = fetch(symbol, range)

    /** The same call, named for what it does rather than for the range it defaults to. */
    suspend fun fetch(symbol: IndexSymbol, range: String = IndexEndpoints.DEFAULT_RANGE): IndexSeries {
        val safeRange = range.trim().lowercase()
        if (!isPlausibleRange(safeRange)) {
            return IndexSeries.failed(
                symbol = symbol,
                failure = IndexFailureReason.UNSUPPORTED_REQUEST,
                reason = "range '$range' is not one of " +
                    "${IndexEndpoints.SUPPORTED_RANGES.joinToString(", ")}",
                range = safeRange,
                interval = IndexEndpoints.INTERVAL,
            )
        }

        var attempts = 0
        var lastFailure = IndexFailureReason.HOST_UNREACHABLE
        val problems = mutableListOf<String>()

        // Two rounds: the first, and — only if it saw a 429 — exactly one retry with a fresh
        // session. A stale crumb is the likeliest cause of a 429, so retrying with the *same*
        // session would be the same request again.
        for (round in 0 until MAX_ROUNDS) {
            val session = try {
                handshake.session()
            } catch (e: YahooHistoryException) {
                return IndexSeries.failed(
                    symbol = symbol,
                    failure = e.reason.toIndexFailure(),
                    reason = "Yahoo handshake failed, so no index could be requested: ${e.message}",
                    range = safeRange,
                    interval = IndexEndpoints.INTERVAL,
                    attempts = attempts + 1,
                )
            } catch (e: Exception) {
                // The handshake is written not to throw; this is belt and braces so an unexpected
                // runtime failure still cannot reach a worker or a screen as a crash.
                return IndexSeries.failed(
                    symbol = symbol,
                    failure = IndexFailureReason.HOST_UNREACHABLE,
                    reason = "Yahoo handshake failed unexpectedly: " +
                        "${e.javaClass.simpleName}: ${e.message}",
                    range = safeRange,
                    interval = IndexEndpoints.INTERVAL,
                    attempts = attempts + 1,
                )
            }

            var rateLimited = false

            for (host in YahooHost.FALLBACK_ORDER) {
                val url = IndexEndpoints.chartUrl(host.queryHost, symbol, safeRange, session.crumb)
                attempts++
                val response = try {
                    transport.execute(HttpRequest(url = url, headers = session.chartHeaders()))
                } catch (e: Exception) {
                    // A thrown transport is "the phone is offline", which the caller says
                    // differently from "the server said no" — hence a distinct reason.
                    problems += "$url → transport error ${e.javaClass.simpleName}: ${e.message}"
                    lastFailure = IndexFailureReason.HOST_UNREACHABLE
                    continue
                }

                when {
                    response.code == 429 -> {
                        problems += "$url → HTTP 429 (Retry-After: " +
                            "${response.header("Retry-After") ?: "absent"})"
                        lastFailure = IndexFailureReason.RATE_LIMITED
                        rateLimited = true
                        // A stale session is the likeliest cause; drop it before the retry.
                        handshake.invalidate()
                        // Honour `Retry-After` when the server sends one; otherwise use the fixed
                        // backoff. Waiting is never skipped, only its length is a guess — and it is
                        // clamped, because waiting an hour on a mobile network is not waiting.
                        sleeper(retryAfterMillis(response))
                    }

                    response.code == 404 || response.code == 410 -> {
                        problems += "$url → HTTP ${response.code} (Yahoo does not know ${symbol.wire})"
                        lastFailure = IndexFailureReason.NOT_FOUND
                    }

                    !response.isSuccess -> {
                        problems += "$url → HTTP ${response.code}"
                        lastFailure = IndexFailureReason.HOST_UNREACHABLE
                    }

                    response.body.isEmpty() -> {
                        problems += "$url → HTTP ${response.code} with 0 bytes"
                        lastFailure = IndexFailureReason.UNPARSEABLE
                    }

                    else -> {
                        val parsed = YahooIndexChartParser.parse(
                            body = response.text,
                            symbol = symbol,
                            timeframe = Timeframe.D1,
                            range = safeRange,
                            interval = IndexEndpoints.INTERVAL,
                            url = url,
                            httpCode = response.code,
                            attempts = attempts,
                        )
                        when {
                            // A parsed-but-short series is refused here rather than stored. Passing
                            // it onwards would align with a handful of dates and produce an RS line
                            // too short for any lookback, which unscores the Context factor with no
                            // stated reason — the exact silent failure the `null` path exists to
                            // replace. The candles are dropped, never padded.
                            parsed.isUsable && parsed.candles.size < IndexEndpoints.MIN_USABLE_BARS ->
                                return IndexSeries.failed(
                                    symbol = symbol,
                                    failure = IndexFailureReason.NO_BENCHMARK_ROWS,
                                    reason = "Yahoo returned ${parsed.candles.size} usable " +
                                        "session(s) for ${symbol.wire} on a $safeRange request, and " +
                                        "a benchmark needs at least " +
                                        "${IndexEndpoints.MIN_USABLE_BARS} before relative " +
                                        "strength can be measured over any of the app's lookback " +
                                        "windows. Nothing is substituted for it.",
                                    range = safeRange,
                                    interval = IndexEndpoints.INTERVAL,
                                    url = url,
                                    httpCode = response.code,
                                    attempts = attempts,
                                    rowsRequested = parsed.rowsRequested,
                                    rowsDropped = parsed.rowsDropped,
                                )

                            // `chart.error` and an empty timestamp array are *answers*, not
                            // transport faults: the other host would produce the same one.
                            parsed.isUsable || parsed.failure == IndexFailureReason.NO_DATA -> {
                                return parsed
                            }

                            else -> {
                                problems += "$url → HTTP ${response.code} but " +
                                    "${parsed.unavailableReason ?: "unusable"}"
                                lastFailure = parsed.failure ?: IndexFailureReason.UNPARSEABLE
                            }
                        }
                    }
                }
            }

            // A 429 is the only outcome worth a second round: the session was dropped above, so
            // the next round performs a fresh handshake. Every other failure is the same answer
            // the other host would give.
            if (!rateLimited) break
        }

        return IndexSeries.failed(
            symbol = symbol,
            failure = lastFailure,
            reason = "Yahoo gave no usable series for ${symbol.wire} ($safeRange/" +
                "${IndexEndpoints.INTERVAL}): " +
                (problems.joinToString("; ").ifEmpty { "no host was contacted" }),
            range = safeRange,
            interval = IndexEndpoints.INTERVAL,
            attempts = attempts,
        )
    }

    /** The cached session's crumb, or `null`. Diagnostics and tests; performs no IO. */
    fun cachedCrumb(): String? = handshake.peek()?.crumb

    private fun isPlausibleRange(range: String): Boolean =
        range.isNotEmpty() &&
            range.length <= MAX_RANGE_LENGTH &&
            range.all { it.isLetterOrDigit() } &&
            IndexEndpoints.SUPPORTED_RANGES.contains(range)

    /**
     * `Retry-After` in seconds (RFC 9110), clamped to [MAX_RETRY_AFTER_MILLIS].
     *
     * Absent, unparseable, zero or negative falls back to [RETRY_BACKOFF_MILLIS] rather than to
     * "do not retry": plan.md §13 names the Yahoo 429 as the risk that kills the benchmark, so the
     * retry is never skipped, only its length is a guess.
     */
    private fun retryAfterMillis(response: com.mia.app.data.http.HttpResponse): Long =
        response.header("Retry-After")?.trim()?.toLongOrNull()
            ?.takeIf { it > 0 }
            ?.times(1_000L)
            ?.coerceAtMost(MAX_RETRY_AFTER_MILLIS)
            ?: RETRY_BACKOFF_MILLIS

    private companion object {
        /** One, plus exactly one retry after a `429`. */
        const val MAX_ROUNDS: Int = 2

        /** Wait before the retry when the server sends no `Retry-After`. */
        const val RETRY_BACKOFF_MILLIS: Long = 1_500L

        /** Refusing to wait an hour on a mobile network is part of being an on-device app. */
        const val MAX_RETRY_AFTER_MILLIS: Long = 30_000L

        /** Yahoo documents short alphanumeric range names; anything longer is a mistake. */
        const val MAX_RANGE_LENGTH: Int = 8
    }
}

/**
 * Maps the history layer's failure vocabulary onto the index layer's.
 *
 * The two share a cause in most cases, so re-declaring them would be duplication; the ones that
 * differ are the ones where the *caller's* next action differs, which is why [IndexFailureReason]
 * has its own enum rather than reusing [YahooFailureReason] verbatim.
 */
internal fun YahooFailureReason.toIndexFailure(): IndexFailureReason = when (this) {
    YahooFailureReason.HANDSHAKE_FAILED -> IndexFailureReason.HANDSHAKE_FAILED
    YahooFailureReason.RATE_LIMITED -> IndexFailureReason.RATE_LIMITED
    YahooFailureReason.NOT_FOUND -> IndexFailureReason.NOT_FOUND
    YahooFailureReason.HOST_UNREACHABLE -> IndexFailureReason.HOST_UNREACHABLE
    YahooFailureReason.TRANSPORT_ERROR -> IndexFailureReason.HOST_UNREACHABLE
    YahooFailureReason.NO_DATA -> IndexFailureReason.NO_DATA
    YahooFailureReason.UNPARSEABLE -> IndexFailureReason.UNPARSEABLE
    YahooFailureReason.EMPTY_SERIES -> IndexFailureReason.EMPTY_SERIES
    // Neither of these is reachable from this source — it never builds a URL for an unvalidated
    // symbol and never asks for an undocumented range — but the mapping must be total so a future
    // caller cannot get an `else` that silently succeeds.
    YahooFailureReason.REJECTED_SYMBOL -> IndexFailureReason.UNKNOWN_SYMBOL
    YahooFailureReason.UNSUPPORTED_REQUEST -> IndexFailureReason.UNSUPPORTED_REQUEST
}