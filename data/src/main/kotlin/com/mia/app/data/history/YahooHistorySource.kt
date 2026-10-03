package com.mia.app.data.history

import com.mia.app.data.http.HttpRequest
import com.mia.app.data.http.HttpTransport
import com.mia.app.domain.model.Timeframe

/**
 * The two series plan.md §3.2 asks for, in one value.
 *
 * Pairing them matters because the pair is the *unit* of the P3 gate ("500 daily + 261 weekly
 * bars", plan.md §11) and because they fail independently: a `429` on the weekly request must
 * not throw away a perfectly good 500-row daily backfill, which is why each side carries its own
 * [HistoryResult.failure].
 */
data class HistorySeries(
    val symbol: String,
    val daily: HistoryResult,
    val weekly: HistoryResult,
) {
    val dailyUsable: Boolean get() = daily.isUsable
    val weeklyUsable: Boolean get() = weekly.isUsable
    val isUsable: Boolean get() = dailyUsable || weeklyUsable

    /** True when both series cleared plan.md §14's documented shapes (500 / 261 rows). */
    val meetsDocumentedShapes: Boolean
        get() = daily.candles.size >= YahooEndpoints.EXPECTED_DAILY_ROWS &&
            weekly.candles.size >= YahooEndpoints.EXPECTED_WEEKLY_ROWS
}

/**
 * Fetches price history from Yahoo Finance (plan.md §3.2, phase P3).
 *
 * ### What this class owns
 * * **The handshake, always.** [YahooHistorySource] has no API that issues a chart request
 *   without a session: [YahooEndpoints.chartUrl] demands a [YahooSession]. plan.md §3.2 measured
 *   that skipping it yields `429` on *every* request, so the mistake is unrepresentable rather
 *   than merely discouraged.
 * * **The query1 → query2 fallback**, and the one-and-only-one retry after a `429`.
 * * **Typed failure.** Nothing throws out of here; every failure is a [HistoryResult] with a
 *   [YahooFailureReason] (plan.md §12: no silent empty).
 *
 * ### What it deliberately does not own
 * The write-once cache. The caller stores a successful result once (`candles` is keyed
 * `PK(symbol,timeframe,date)`, plan.md §5.2) and asks [needsBackfill] before spending another
 * request — plan.md §13's CGNAT mitigation is that history is fetched **once per symbol**, not
 * once per launch.
 *
 * **UNVERIFIED (2026-10-03, this machine):** `getcrumb` answers `429` from this network even
 * after a correct cookie handshake, so the happy path could not be exercised against live
 * Yahoo. Every behaviour below is asserted against a hand-written transport and against a
 * hand-written v8 chart payload.
 */
class YahooHistorySource(
    private val transport: HttpTransport,
    private val handshake: YahooCrumbHandshake = YahooCrumbHandshake(transport),
    /** Injected so the tests can assert the 429 retry without waiting on it. */
    private val sleeper: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
) {

    /**
     * Fetch and parse one chart range.
     *
     * Never throws. The failure modes and what they mean:
     *
     * | Outcome | [YahooFailureReason] | What the caller should do |
     * |---|---|---|
     * | symbol outside the allow-list | [YahooFailureReason.REJECTED_SYMBOL] | fix the symbol (plan.md §9) |
     * | range/interval not documented | [YahooFailureReason.UNSUPPORTED_REQUEST] | fix the call |
     * | 429 on every host, twice | [YahooFailureReason.RATE_LIMITED] | serve from bhavcopy; retry after a day |
     * | no 2xx anywhere | [YahooFailureReason.HOST_UNREACHABLE] | offline; serve from cache |
     * | 404 | [YahooFailureReason.NOT_FOUND] | the symbol does not exist (demerger) |
     * | `chart.error` set | [YahooFailureReason.NO_DATA] | the symbol does not exist (demerger) |
     * | body is not chart JSON | [YahooFailureReason.UNPARSEABLE] | Yahoo changed the schema |
     */
    suspend fun fetch(symbol: String, range: String, interval: YahooInterval): HistoryResult {
        val bare = try {
            YahooEndpoints.requireSymbol(symbol)
        } catch (e: IllegalArgumentException) {
            return HistoryResult.failed(
                symbol = symbol.trim().uppercase(),
                timeframe = interval.timeframe,
                failure = YahooFailureReason.REJECTED_SYMBOL,
                reason = "rejected symbol '${symbol.trim()}': ${e.message}",
                range = range,
                interval = interval.wire,
            )
        }
        val safeRange = try {
            YahooEndpoints.requireRange(range)
        } catch (e: IllegalArgumentException) {
            return HistoryResult.failed(
                symbol = bare,
                timeframe = interval.timeframe,
                failure = YahooFailureReason.UNSUPPORTED_REQUEST,
                reason = e.message ?: "unsupported range",
                interval = interval.wire,
            )
        }

        var attempts = 0
        var lastFailure: YahooFailureReason = YahooFailureReason.HOST_UNREACHABLE
        var problems = mutableListOf<String>()

        // Two rounds: the first, and — only if it saw a 429 — exactly one retry with a fresh
        // session. plan.md §3.2: a 429 means the crumb is stale, so retrying with the *same*
        // session is pointless; retrying with a new one sometimes works.
        for (round in 0 until MAX_ROUNDS) {
            // `round` is the retry counter. Round 0 is the first attempt; round 1 exists only
            // because round 0 saw a 429, and it re-reads the (now invalidated) session below.
            val session = try {
                handshake.session()
            } catch (e: YahooHistoryException) {
                lastFailure = e.reason
                problems += "handshake: ${e.message}"
                return HistoryResult.failed(
                    symbol = bare, timeframe = interval.timeframe, failure = e.reason,
                    reason = e.message ?: "handshake failed", range = safeRange, interval = interval.wire,
                    attempts = attempts + 1,
                )
            } catch (e: Exception) {
                // The handshake is written not to throw; this is belt and braces so that an
                // unexpected runtime failure still cannot reach the UI as a crash.
                return HistoryResult.failed(
                    symbol = bare, timeframe = interval.timeframe,
                    failure = YahooFailureReason.TRANSPORT_ERROR,
                    reason = "handshake failed unexpectedly: ${e.javaClass.simpleName}: ${e.message}",
                    range = safeRange, interval = interval.wire, attempts = attempts + 1,
                )
            }

            var rateLimited = false

            for (host in YahooHost.FALLBACK_ORDER) {
                val url = YahooEndpoints.chartUrl(host, bare, safeRange, interval, session)
                attempts++
                val response = try {
                    transport.execute(HttpRequest(url = url, headers = session.chartHeaders()))
                } catch (e: Exception) {
                    // A thrown transport is "the phone is offline / DNS failed", which is a
                    // different thing from "the server said no" and the UI says so differently.
                    problems += "$url → transport error ${e.javaClass.simpleName}: ${e.message}"
                    lastFailure = YahooFailureReason.TRANSPORT_ERROR
                    continue
                }

                when {
                    response.code == 429 -> {
                        problems += "$url → HTTP 429 (Retry-After: ${response.header("Retry-After") ?: "absent"})"
                        lastFailure = YahooFailureReason.RATE_LIMITED
                        rateLimited = true
                        // A stale session is the likeliest cause; drop it before the retry.
                        handshake.invalidate()
                        // Honour `Retry-After` when the server sends one, so a well-behaved rate
                        // limit is respected rather than guessed at. The fallback is the fixed
                        // backoff for a server that omits it, and an absurd value is clamped:
                        // waiting a day on a phone is not waiting.
                        sleeper(retryAfterMillis(response))
                    }

                    response.code == 404 || response.code == 410 -> {
                        problems += "$url → HTTP ${response.code} (Yahoo does not know $bare)"
                        lastFailure = YahooFailureReason.NOT_FOUND
                    }

                    !response.isSuccess -> {
                        problems += "$url → HTTP ${response.code}"
                        lastFailure = YahooFailureReason.HOST_UNREACHABLE
                    }

                    response.body.isEmpty() -> {
                        problems += "$url → HTTP ${response.code} with 0 bytes"
                        lastFailure = YahooFailureReason.UNPARSEABLE
                    }

                    else -> {
                        val parsed = YahooChartParser.parse(
                            body = response.text,
                            symbol = bare,
                            timeframe = interval.timeframe,
                            range = safeRange,
                            interval = interval.wire,
                            url = url,
                            httpCode = response.code,
                            attempts = attempts,
                        )
                        // `chart.error` and an empty timestamp array are answers, not transport
                        // faults: asking the other host would produce the same answer.
                        if (parsed.failure == YahooFailureReason.NO_DATA || parsed.isUsable) {
                            return parsed
                        }
                        problems += "$url → HTTP ${response.code} but ${parsed.health.notes.firstOrNull()}"
                        lastFailure = parsed.failure ?: YahooFailureReason.UNPARSEABLE
                    }
                }
            }

            // A 429 is the only outcome worth a second round: the session was dropped above, so
            // the next round performs a fresh handshake. Every other failure is the same answer
            // the other host would give.
            if (!rateLimited) break
        }

        val reason = "Yahoo gave no usable chart for $bare ($safeRange/${interval.wire}): " +
            problems.joinToString("; ").ifEmpty { "no host was contacted" }
        return HistoryResult.failed(
            symbol = bare,
            timeframe = interval.timeframe,
            failure = lastFailure,
            reason = reason,
            range = safeRange,
            interval = interval.wire,
            attempts = attempts,
        )
    }

    /**
     * plan.md §3.2's two shapes in one call: `2y/1d` (≈500 rows) and `5y/1wk` (261 rows).
     *
     * [skip] lets a caller that already holds a full series avoid the request entirely — the
     * write-once cache (plan.md §5.2, §13) means the second launch of the app must not re-ask.
     * A skipped side comes back as a [HistoryResult] whose health is not-ok with the reason, so
     * "I did not ask" is never mistaken for "there is nothing there".
     */
    suspend fun dailyAndWeekly(symbol: String, skip: Set<Timeframe> = emptySet()): HistorySeries {
        val daily = if (Timeframe.D1 in skip) {
            skippedResult(symbol, Timeframe.D1, "daily history was not requested: the write-once " +
                "cache is already past the backfill threshold (see needsBackfill)")
        } else {
            fetch(symbol, YahooEndpoints.DAILY_RANGE, YahooInterval.DAY)
        }
        val weekly = if (Timeframe.W1 in skip) {
            skippedResult(symbol, Timeframe.W1, "weekly history was not requested: the write-once " +
                "cache is already past the backfill threshold (see needsBackfill)")
        } else {
            fetch(symbol, YahooEndpoints.WEEKLY_RANGE, YahooInterval.WEEK)
        }
        return HistorySeries(symbol = symbol.trim().uppercase(), daily = daily, weekly = weekly)
    }

    /**
     * The P3 cache rule (plan.md §3.2 "once per symbol, then cached forever"; §11 P3).
     *
     * **More than 500 daily bars, or more than 261 weekly bars, already stored ⇒ no refetch.**
     * Below the threshold the symbol still needs its backfill, because plan.md §6.4 makes
     * `barsSufficient` a hard gate (1D ≥ 60, 1W ≥ 40) and the pattern engine is fed 500 bars
     * (plan.md §6.2).
     *
     * The comparison is `>`, exactly as specified. The boundary is worth naming because it is
     * the awkward one: Yahoo's documented daily shape is *exactly* 500 rows (plan.md §14), so a
     * symbol that was stored at the full shape still answers `true` here. The practical
     * consequence is that the caller should gate on this **and** on the presence of a stored
     * write-once marker rather than on the count alone; this method deliberately does not
     * second-guess the rule it was given. A symbol never reaches 501 daily bars from this
     * source, so in practice `needsBackfill` is asked once per symbol and then answered by the
     * caller's own "already stored" check.
     */
    fun needsBackfill(symbol: String, timeframe: Timeframe, existingBars: Int): Boolean =
        existingBars <= backfillThreshold(timeframe)

    /** 500 for [Timeframe.D1], 261 for [Timeframe.W1]. */
    fun backfillThreshold(timeframe: Timeframe): Int = when (timeframe) {
        Timeframe.D1 -> YahooEndpoints.EXPECTED_DAILY_ROWS
        Timeframe.W1 -> YahooEndpoints.EXPECTED_WEEKLY_ROWS
    }

    private fun skippedResult(symbol: String, timeframe: Timeframe, note: String): HistoryResult =
        HistoryResult.skipped(symbol.trim().uppercase(), timeframe, note)

    /**
     * How long to wait after a `429`.
     *
     * `Retry-After` is in **seconds** per RFC 9110, converted here and clamped to
     * [MAX_RETRY_AFTER_MILLIS]. An absent, unparseable, zero or negative value falls back to
     * [RETRY_BACKOFF_MILLIS] rather than to "do not retry": plan.md §13 names Yahoo 429 as the
     * risk that kills history, so the retry is never skipped, only its length is a guess.
     */
    private fun retryAfterMillis(response: com.mia.app.data.http.HttpResponse): Long =
        response.header("Retry-After")?.trim()?.toLongOrNull()
            ?.takeIf { it > 0 }
            ?.times(1_000L)
            ?.coerceAtMost(MAX_RETRY_AFTER_MILLIS)
            ?: RETRY_BACKOFF_MILLIS

    private companion object {
        /** One, plus exactly one retry after a `429`. */
        const val MAX_ROUNDS = 2

        /** Wait before the retry when the server sends no `Retry-After`. */
        const val RETRY_BACKOFF_MILLIS = 1_500L

        /** Refusing to wait an hour on a mobile network is part of being an on-device app. */
        const val MAX_RETRY_AFTER_MILLIS = 30_000L
    }
}
