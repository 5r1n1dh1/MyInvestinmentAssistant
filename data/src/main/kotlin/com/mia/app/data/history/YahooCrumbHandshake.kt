package com.mia.app.data.history

import com.mia.app.data.http.HttpRequest
import com.mia.app.data.http.HttpResponse
import com.mia.app.data.http.HttpTransport
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicReference

/**
 * Why a Yahoo fetch could not produce candles.
 *
 * Every reason here is something the UI must be able to say out loud, because plan.md §12's
 * rule is that a source failure must never look like "this stock has no data". They are carried
 * on [YahooHistoryException.reason] and on [HistoryResult.failure] rather than being flattened
 * into a string, so a caller can branch on [RATE_LIMITED] (fall back to bhavcopy, retry
 * tomorrow) differently from [NOT_FOUND] (the symbol is wrong — plan.md §9's TATAMOTORS case).
 */
enum class YahooFailureReason {

    /** The symbol failed [YahooEndpoints.isValidSymbol]; no URL was ever built. */
    REJECTED_SYMBOL,

    /** A range/interval outside the documented set was requested. */
    UNSUPPORTED_REQUEST,

    /** No session cookie, no crumb, or a crumb body that is not a token. */
    HANDSHAKE_FAILED,

    /** `429` from every host, even after one session refresh. plan.md §13's named risk. */
    RATE_LIMITED,

    /** Nothing answered 2xx on any host (DNS, TLS, 403/5xx, thrown transport error). */
    HOST_UNREACHABLE,

    /** HTTP 404/410: Yahoo does not know this symbol. plan.md §9's demerger case. */
    NOT_FOUND,

    /** `chart.error` was populated, or the payload had zero timestamps. */
    NO_DATA,

    /** 2xx with a body that is not v8 chart JSON. */
    UNPARSEABLE,

    /** Every row was dropped — an all-null OHLC series is a defect, not "no trading". */
    EMPTY_SERIES,

    /**
     * The [HttpTransport] itself threw (DNS, TLS, socket). Distinct from
     * [HOST_UNREACHABLE] so a caller can tell "the server said no" from "the phone was offline".
     */
    TRANSPORT_ERROR,
}

/**
 * The typed failure every layer of this package surfaces.
 *
 * It never escapes [YahooHistorySource.fetch]: that method converts it into a failed
 * [HistoryResult] carrying the same [reason], because plan.md §12 requires a health flag rather
 * than an exception, and a thrown exception three frames from the UI is a blank screen.
 */
class YahooHistoryException(
    val reason: YahooFailureReason,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * A usable Yahoo session: the cookies from step 1 plus the crumb from step 2.
 *
 * ### The invariant that makes the handshake hard to skip
 * `init` rejects a blank crumb and an empty cookie jar, and [chartUrl] demands one of these.
 * There is therefore no way to issue a chart request without a completed cookie+crumb
 * handshake, which is the single thing plan.md §3.2 says must never be skipped.
 */
data class YahooSession(
    /** Cookie name → value, e.g. `A1` and `GUC`. */
    val cookies: Map<String, String>,
    /** The opaque crumb token, e.g. `AbCdEf123`. */
    val crumb: String,
    /** Which query host answered the crumb request. */
    val host: YahooHost,
    /**
     * The agent that got the crumb.
     *
     * Carried rather than assumed, because the crumb was issued to one agent and step 3 must
     * present the **same** one: a crumb minted for a mobile agent and spent by a desktop agent is
     * a different request, and the failure would look like an expired crumb rather than a mismatch.
     * Defaults to the preferred agent so a hand-built session in a test stays valid.
     */
    val userAgent: String = YahooEndpoints.USER_AGENT,
) {
    init {
        require(cookies.isNotEmpty()) {
            "a Yahoo session without cookies is the request that gets 429 (plan.md §3.2)"
        }
        require(crumb.isNotBlank()) {
            "a Yahoo session without a crumb is the request that gets 429 (plan.md §3.2)"
        }
    }

    /** `a=1; b=2` — the `Cookie` header value. */
    val cookieHeader: String
        get() = cookies.entries.joinToString("; ") { (name, value) -> "$name=$value" }

    /**
     * Header map for step 3: the agent the crumb was minted for **and** the session cookie.
     *
     * Both must match the handshake, which is why this reads [userAgent] off the session instead
     * of taking the default.
     */
    fun chartHeaders(): Map<String, String> =
        YahooEndpoints.headers(cookieHeader, userAgent = userAgent)
}

/**
 * Minimal `Set-Cookie` handling.
 *
 * ### Deliberately not a cookie jar
 * A jar needs expiry, domains, paths and a replacement policy. Yahoo's step-1 response sets
 * `A1`, `A1S`, `GUC` and friends on `fc.yahoo.com`, and all of them are consumed by the very
 * next request on `query{1,2}`, so storing `name=value` is sufficient *and* has the useful
 * property that nothing is ever sent to a host it was not issued for — there is no scope
 * information to get wrong.
 *
 * Comma-joined `Set-Cookie` headers are **not** split, because `Expires=Wed, 09 Jun 2027 …`
 * contains commas and a naive split would manufacture a cookie called `09 Jun 2027`. The
 * `HttpTransport` contract already hands raw `Set-Cookie` values over separately
 * (`HttpResponse.setCookies`), which is the correct representation.
 */
object YahooCookies {

    /** Parses raw `Set-Cookie` values into `name -> value`, later occurrences winning. */
    fun parse(raw: List<String>): Map<String, String> {
        if (raw.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (line in raw) {
            val pair = line.substringBefore(';').trim()
            if (pair.isEmpty()) continue
            val name = pair.substringBefore('=').trim()
            if (name.isEmpty()) continue
            val value = pair.substringAfter('=', missingDelimiterValue = "")
                .trim()
                .trim('"')
            if (value.isEmpty()) continue
            out[name] = value
        }
        return out
    }

    /** Reads cookies off a response, from both channels [HttpResponse] offers them on. */
    fun of(response: HttpResponse): Map<String, String> {
        val fromHeaders = response.headers.entries
            .filter { it.key.equals("Set-Cookie", ignoreCase = true) }
            .flatMap { it.value }
        return parse(response.setCookies + fromHeaders)
    }

    /** `a=1; b=2`. */
    fun headerOf(cookies: Map<String, String>): String =
        cookies.entries.joinToString("; ") { (name, value) -> "$name=$value" }
}

/**
 * The mandatory cookie + crumb handshake of plan.md §3.2, made unavoidable and shared.
 *
 * ```
 * 1. GET https://fc.yahoo.com                     → sets the session cookie
 * 2. GET https://query1.finance.yahoo.com/v1/test/getcrumb
 * 3. GET .../v8/finance/chart/{SYMBOL}.NS?range=2y&interval=1d
 *    with BOTH the session cookie and ?crumb={crumb}
 * 4. Fall back between query1 and query2 hosts
 * ```
 *
 * ### Why step 1 tolerates a 404
 * Verified from this machine on 2026-10-03: `fc.yahoo.com` answers `404` and *still* sets the
 * cookies. Treating a non-2xx here as a failure would kill the only thing that works, so the
 * acceptance test is **"did the response set a cookie"**, not "was the status 2xx".
 *
 * ### One handshake per process, no stampede
 * The session is cached in an [AtomicReference] and handshakes are serialised by a [Mutex].
 * Two coroutines asking at once cannot both perform the handshake: the loser re-reads the cache
 * inside the lock and gets the winner's session. On-device the fan-out is small (the 19:00
 * worker), but plan.md §13's CGNAT mitigation is about looking like *one* client, and a
 * stampeded handshake looks like twenty.
 *
 * ### Refresh on 429
 * [invalidate] drops the cached session; the caller ([YahooHistorySource]) then re-requests one
 * and retries the chart request exactly once. A stale session is the usual cause of a `429`
 * after a few hours, and the crumb expires.
 *
 * ### The agent is part of the handshake, not a constant
 * The walk is over `(user-agent, host)` pairs rather than hosts alone. `429` from `getcrumb` used
 * to be read as "Yahoo is blocking us", and the note here recorded that as unverifiable from this
 * machine — but the cause was the agent the app presented. Same session cookie, same URL, and
 * `getcrumb` answers `429` for the mobile Chrome agent while returning a crumb for the desktop one.
 * The client now tries each agent in turn, and the winning agent travels on [YahooSession] so step
 * 3 presents the same identity that step 2 was served.
 */
class YahooCrumbHandshake(
    private val transport: HttpTransport,
    /** Injected so the tests can assert backoff without waiting on it. */
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
    /** Wait before retrying the crumb request after a 429. */
    private val backoffMillis: Long = 1_500,
) {

    private val cache = AtomicReference<YahooSession?>(null)
    private val gate = Mutex()

    /** How many handshakes this process has performed. Diagnostics and tests. */
    @Volatile
    var handshakeCount: Int = 0
        private set

    /** The cached session, or `null`. Read-only peek; never performs IO. */
    fun peek(): YahooSession? = cache.get()

    /**
     * Drops the cached session. Called when a chart request is answered `429`, because the crumb
     * has expired or the cookie was rotated server-side (plan.md §13: *"Yahoo 429 / crumb changes"*).
     *
     * Deliberately non-blocking: a caller that has just been rate-limited must not queue behind
     * an in-flight handshake, and the worst outcome of losing this race is one redundant
     * handshake rather than a stuck coroutine.
     */
    fun invalidate() {
        cache.set(null)
    }

    /**
     * The session for this process, performing the handshake only if there is not one already.
     *
     * @throws YahooHistoryException with [YahooFailureReason.HANDSHAKE_FAILED] when no host
     *   produced a crumb.
     */
    suspend fun session(): YahooSession {
        cache.get()?.let { return it }
        return gate.withLock {
            // Re-read inside the lock: another coroutine may have completed the handshake while
            // this one waited. This second read is the whole anti-stampede mechanism.
            cache.get()?.let { return@withLock it }
            handshake().also { cache.set(it) }
        }
    }

    /** Forces a new handshake and replaces the cached session. Used by the 429 retry path. */
    suspend fun refresh(): YahooSession = gate.withLock {
        handshake().also { cache.set(it) }
    }

    private suspend fun handshake(): YahooSession {
        val problems = mutableListOf<String>()

        // ---- step 1: the session cookie, fetched once with the preferred agent.
        //
        // Not per agent, and the exception from this request is deliberately **not** caught: a
        // transport that cannot reach `fc.yahoo.com` has no network, so trying the second agent
        // would repeat the identical request and report the same failure twice. The cookie is a
        // session identifier, not part of the identity under test — the agent is, and that is
        // tested at step 2, which is where a refusal actually appears.
        val cookies = try {
            val response = transport.execute(
                HttpRequest(
                    url = YahooEndpoints.SESSION_URL,
                    headers = YahooEndpoints.headers(userAgent = YahooEndpoints.USER_AGENT),
                ),
            )
            val set = YahooCookies.of(response)
            if (set.isEmpty()) {
                // A 404 here is survivable; a 404 *with no cookie* is not — see the class KDoc.
                throw YahooHistoryException(
                    YahooFailureReason.HANDSHAKE_FAILED,
                    "Yahoo session request to ${YahooEndpoints.SESSION_URL} answered HTTP " +
                        "${response.code} and set no cookie, so no crumb can be obtained " +
                        "(plan.md §3.2 requires the cookie before the crumb)",
                )
            }
            set
        } catch (e: YahooHistoryException) {
            throw e
        } catch (e: Exception) {
            throw YahooHistoryException(
                YahooFailureReason.HANDSHAKE_FAILED,
                "Yahoo session request to ${YahooEndpoints.SESSION_URL} failed: " +
                    "${e.javaClass.simpleName}: ${e.message}",
                e,
            )
        }

        // ---- step 2: the crumb, over every agent and both hosts.
        //
        // Agent first, then host: the agent is the thing being varied deliberately, so it is the
        // outer loop and the failure message names the agent that was refused. Swapping the loops
        // would still find a working combination but would report it as a host problem, which is
        // the misdiagnosis this whole change exists to correct.
        for (agent in YahooEndpoints.userAgents) {
            val shortAgent = agent.take(24) + "…"

            // ---- step 2: the crumb, query1 then query2, under this agent.
            for (host in YahooHost.FALLBACK_ORDER) {
                val url = YahooEndpoints.crumbUrl(host)
                val response = try {
                    transport.execute(
                        HttpRequest(
                            url = url,
                            headers = YahooEndpoints.headers(
                                cookieHeader = YahooCookies.headerOf(cookies),
                                userAgent = agent,
                            ),
                        ),
                    )
                } catch (e: Exception) {
                    problems += "$url (${shortAgent}) → transport error " +
                        "${e.javaClass.simpleName}: ${e.message}"
                    continue
                }

                when {
                    response.code == 429 -> {
                        problems += "$url (${shortAgent}) → HTTP 429 (rate limited; Retry-After: " +
                            "${response.header("Retry-After") ?: "absent"})"
                        // No backoff here. The next thing tried is a different *agent*, not the
                        // same request again, so waiting would delay the fix rather than enable
                        // it. Backoff belongs to the chart-level retry, which does repeat an
                        // identical request.
                    }

                    !response.isSuccess ->
                        problems += "$url (${shortAgent}) → HTTP ${response.code}"

                    else -> {
                        val body = response.text.trim()
                        if (YahooEndpoints.isValidCrumb(body)) {
                            handshakeCount++
                            return YahooSession(
                                cookies = cookies,
                                crumb = body,
                                host = host,
                                // Step 3 must present the agent the crumb was minted for.
                                userAgent = agent,
                            )
                        }
                        // An HTML error page or a JSON error body served with 200. Not a crumb.
                        problems += "$url (${shortAgent}) → HTTP ${response.code} but the body is " +
                            "not a crumb token (${body.length} chars, " +
                            "starts '${body.take(40).replace('\n', ' ')}')"
                    }
                }
            }
        }

        throw YahooHistoryException(
            YahooFailureReason.HANDSHAKE_FAILED,
            "no Yahoo crumb from ${YahooHost.FALLBACK_ORDER.joinToString(" then ") { it.queryHost }} " +
                "under any of ${YahooEndpoints.userAgents.size} user-agents: " +
                problems.joinToString("; "),
        )
    }

    /** `Retry-After` in seconds, clamped to a sane ceiling. Absent/invalid → [backoffMillis]. */
    private fun retryAfterMillis(response: HttpResponse): Long {
        val raw = response.header("Retry-After")?.trim()?.toLongOrNull()
        if (raw == null || raw <= 0) return backoffMillis
        return (raw * 1_000).coerceAtMost(MAX_RETRY_AFTER_MILLIS)
    }

    private companion object {
        /** Refusing to wait an hour on a mobile network is part of being an on-device app. */
        const val MAX_RETRY_AFTER_MILLIS: Long = 30_000
    }
}