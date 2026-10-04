package com.mia.app.data.history

import com.mia.app.domain.model.Timeframe
import java.util.Locale

/**
 * The two Yahoo query hosts, in the fallback order plan.md §3.2 mandates.
 *
 * plan.md §3.2 step 4 is a *product* requirement, not a nicety: the two hosts are separate
 * front doors onto the same v8 chart service, and one of them being rate-limited or down must
 * not cost the user their history. [FALLBACK_ORDER] is therefore the single ordered list every
 * caller walks — there is no way to construct an order that omits the fallback.
 *
 * **VERIFIED (2026-10-03, this machine):** both hosts return `200` for a symbol given a correct
 * cookie handshake and crumb. The earlier note here — that they "answer 429 even after a correct
 * cookie handshake" — was a misdiagnosis: the refused requests were presenting a mobile
 * `User-Agent`, which this edge treats as scraper. See [userAgents] for the measurement. The
 * ordering below is still plan.md §3.2's, and the fallback is still what keeps one unwell node
 * from costing the user their history.
 */
enum class YahooHost(val queryHost: String) {

    QUERY1("query1.finance.yahoo.com"),

    /** Second front door. Same path, same schema, same session cookie. */
    QUERY2("query2.finance.yahoo.com"),
    ;

    /** `https://query1.finance.yahoo.com` */
    val base: String get() = "https://$queryHost"

    companion object {
        /**
         * plan.md §3.2: `query1` first, `query2` second.
         *
         * A `List`, not a `Set`, and deliberately not `shuffled()`: the order is the contract,
         * and plan.md §13's request budget means we would rather always pay the fallback
         * request than randomly prefer the host that is currently limited.
         */
        val FALLBACK_ORDER: List<YahooHost> = listOf(QUERY1, QUERY2)
    }
}

/**
 * The only two Yahoo bar intervals this app ever asks for.
 *
 * plan.md D1 and §1.4 rule 9: timeframes are `1D` and `1W` only, no intraday. Modelling the
 * allowed intervals as an enum over [Timeframe] means an intraday request is not
 * representable — the strongest form of the rule available in Kotlin.
 */
enum class YahooInterval(val wire: String, val timeframe: Timeframe) {

    /** `1d` — plan.md §3.2: `range=2y` → ~500 rows. */
    DAY("1d", Timeframe.D1),

    /** `1wk` — plan.md §3.2: `range=5y` → 261 rows. */
    WEEK("1wk", Timeframe.W1),
    ;

    companion object {
        private val BY_WIRE: Map<String, YahooInterval> = entries.associateBy { it.wire }

        /** The wire value for [timeframe]. Every [Timeframe] has exactly one interval. */
        fun forTimeframe(timeframe: Timeframe): YahooInterval =
            entries.first { it.timeframe == timeframe }

        fun fromWire(wire: String): YahooInterval? =
            BY_WIRE[wire.trim().lowercase(Locale.ROOT)]
    }
}

/**
 * URL construction for Yahoo Finance (plan.md §3.2) — and the validation that makes those URLs
 * safe to build from a symbol that came out of a CSV.
 *
 * ### The three requests, verbatim from plan.md §3.2
 *
 * ```
 * 1. GET https://fc.yahoo.com                          → sets the session cookie
 * 2. GET https://query1.finance.yahoo.com/v1/test/getcrumb
 * 3. GET https://query1.finance.yahoo.com/v8/finance/chart/{SYMBOL}.NS?range=2y&interval=1d
 *       with BOTH the session cookie and ?crumb={crumb}
 * 4. fall back between query1 and query2
 * ```
 *
 * Skipping step 1 or 2 yields `429` on *every* request (plan.md §3.2, verified), so the
 * choreography lives in [YahooCrumbHandshake] and this file only ever produces step 2 and 3.
 *
 * ### Why validation is an allow-list and not a filter
 * A symbol reaches this layer from three untrusted places: the bundled `EQUITY_L.csv`, a
 * user-typed search string, and a bhavcopy `TckrSymb`. The allow-list is the same one
 * `ScreenerInUrls` uses and the same one that was derived from the real symbol master: all
 * 2,593 NSE symbols match `[A-Za-z0-9][A-Za-z0-9&-]*`, so `M&M`, `GVT&D` and `BAJAJ-AUTO` all
 * pass while `../`, `A?x=1`, `A#f` and `evil.example` cannot reach the URL at all. Filtering
 * (strip the bad characters) would be worse: it would silently turn `RELIANCE/x` into
 * `RELIANCE` and fetch the wrong company's history.
 */
object YahooEndpoints {

    // ------------------------------------------------------------------ the three endpoints

    /**
     * plan.md §3.2 step 1.
     *
     * **A non-2xx here is normal and must not fail the handshake.** Verified from this machine
     * on 2026-10-03: `https://fc.yahoo.com` answers `404` while *still* setting the `d=` and
     * `A1` cookies, and those cookies are the entire point of the request. Only a response that
     * carries no cookie is a failure — see `YahooCrumbHandshake`.
     */
    const val SESSION_URL: String = "https://fc.yahoo.com"

    /** plan.md §3.2 step 2. */
    const val CRUMB_PATH: String = "/v1/test/getcrumb"

    /** plan.md §3.2 step 3. `%s` is the percent-encoded `{SYMBOL}.NS`. */
    const val CHART_PATH: String = "/v8/finance/chart/"

    /** plan.md §3.2: NSE symbols are suffixed `.NS`. */
    const val NSE_SUFFIX: String = ".NS"

    /** plan.md §3.2: `1d, range=2y` → 500 rows. */
    const val DAILY_RANGE: String = "2y"

    /** plan.md §3.2: `1wk, range=5y` → 261 rows. */
    const val WEEKLY_RANGE: String = "5y"

    /** plan.md §3.2 documents this shape as the P3 gate: `≥ 490` rows per symbol for 20 caps. */
    const val P3_GATE_MIN_DAILY_ROWS: Int = 490

    /** The documented weekly shape (plan.md §14: `1wk/5y → 261`). */
    const val EXPECTED_WEEKLY_ROWS: Int = 261

    /** The documented daily shape (plan.md §14: `1d/2y → 500`). */
    const val EXPECTED_DAILY_ROWS: Int = 500

    // ------------------------------------------------------------------ validation

    /**
     * Same charset as the bundled NSE symbol master allows, same as [ScreenerInUrls].
     * Longest real symbol is 10 characters; 20 is generous headroom.
     */
    const val MAX_SYMBOL_LENGTH: Int = 20

    private val SAFE_SYMBOL = Regex("^[A-Za-z0-9][A-Za-z0-9&\\-]{0,19}$")

    /**
     * A crumb is an opaque token from Yahoo. Only unreserved-ish characters are accepted, and
     * anything else (notably `&`, `=`, `?`, `#`, space) is a refusal rather than an escape —
     * a crumb that needed escaping would be a crumb we do not understand, and interpolating an
     * untrusted token into a query string is exactly the injection this allow-list exists to
     * prevent.
     */
    private val SAFE_CRUMB = Regex("^[A-Za-z0-9._~+/=-]{1,64}$")

    /** `2y`, `5y`, … — lower-case alphanumerics only, and from the set below. */
    private val SAFE_RANGE = Regex("^[a-z0-9]{1,8}$")

    /** Ranges Yahoo's chart endpoint documents and that a 5-year-or-shorter app has any use for. */
    val SUPPORTED_RANGES: Set<String> = setOf("1mo", "3mo", "6mo", "1y", "2y", "3y", "5y", "10y", "ytd", "max")

    /** plan.md §3.2 symbol mapping: NSE symbols are `{SYMBOL}.NS`. */
    fun isValidSymbol(symbol: String): Boolean {
        val trimmed = symbol.trim()
        return trimmed.isNotEmpty() &&
            trimmed.length <= MAX_SYMBOL_LENGTH &&
            SAFE_SYMBOL.matches(trimmed)
    }

    /**
     * The upper-cased, validated bare NSE symbol.
     *
     * @throws IllegalArgumentException when [symbol] is outside the allow-list. This is a
     *   programming/validation error, not a network outcome; `YahooHistorySource.fetch` catches
     *   it and returns a typed failed result rather than letting it escape.
     */
    fun requireSymbol(symbol: String): String {
        val trimmed = symbol.trim()
        require(trimmed.isNotEmpty()) { "symbol is blank" }
        require(trimmed.length <= MAX_SYMBOL_LENGTH) {
            "symbol '$trimmed' is longer than $MAX_SYMBOL_LENGTH characters"
        }
        require(SAFE_SYMBOL.matches(trimmed)) {
            "symbol '$trimmed' contains characters outside [A-Za-z0-9&-]"
        }
        return trimmed.uppercase(Locale.ROOT)
    }

    /** `RELIANCE` → `RELIANCE.NS`, percent-encoded (`M&M` → `M%26M.NS`). */
    fun yahooSymbol(symbol: String): String = requireSymbol(symbol) + NSE_SUFFIX

    fun isValidRange(range: String): Boolean {
        val trimmed = range.trim().lowercase(Locale.ROOT)
        return trimmed in SUPPORTED_RANGES && SAFE_RANGE.matches(trimmed)
    }

    fun requireRange(range: String): String {
        val trimmed = range.trim().lowercase(Locale.ROOT)
        require(trimmed in SUPPORTED_RANGES) {
            "range '$trimmed' is not one of ${SUPPORTED_RANGES.sorted()}"
        }
        return trimmed
    }

    /**
     * True when [crumb] is a token this client is willing to put in a query string.
     *
     * Non-throwing, because the crumb arrives from the network and the honest reaction to an
     * unexpected one is "the handshake failed", not an exception three frames away.
     */
    fun isValidCrumb(crumb: String): Boolean = SAFE_CRUMB.matches(crumb.trim())

    // ------------------------------------------------------------------ URL building

    /** plan.md §3.2 step 2. */
    fun crumbUrl(host: YahooHost): String = host.base + CRUMB_PATH

    /**
     * plan.md §3.2 step 3.
     *
     * ```
     * https://query1.finance.yahoo.com/v8/finance/chart/RELIANCE.NS?range=2y&interval=1d&crumb=AbCdEf
     * ```
     *
     * Query-parameter order is `range`, `interval`, `crumb` — the plan's order — because a test
     * asserts the whole URL and a stable order makes that assertion about the contract rather
     * than about a `Map` iteration.
     *
     * ### Why this takes a [YahooSession] and not a crumb string
     * plan.md §3.2: *"Verified: skipping the handshake yields 429 Too Many Requests on every
     * request."* A `crumb: String?` parameter makes the broken request a one-character edit —
     * pass `null` and the client silently ships the request that is guaranteed to fail, and the
     * symptom (`429`) does not point at the cause. Taking a [YahooSession], whose `init`
     * rejects a blank crumb and an empty cookie jar, makes the unauthenticated chart request
     * **unrepresentable**: there is no overload and no default that skips the handshake.
     */
    fun chartUrl(
        host: YahooHost,
        symbol: String,
        range: String,
        interval: YahooInterval,
        session: YahooSession,
    ): String {
        val path = CHART_PATH + percentEncodePathSegment(yahooSymbol(symbol))
        val query = buildString {
            append("range=").append(requireRange(range))
            append("&interval=").append(interval.wire)
            append("&crumb=").append(percentEncodeQueryValue(session.crumb))
        }
        return host.base + path + "?" + query
    }

    /** The range plan.md §3.2 pairs with [interval]. */
    fun defaultRange(interval: YahooInterval): String = when (interval) {
        YahooInterval.DAY -> DAILY_RANGE
        YahooInterval.WEEK -> WEEKLY_RANGE
    }

    /**
     * Headers for steps 1–3.
     *
     * Yahoo is a public web front end, not an API: it refuses requests whose `User-Agent` does
     * not look like a browser, which is why this differs from `NseBhavcopySource`'s honest
     * app-identifying agent. `Cookie` is added by the caller because only
     * [YahooCrumbHandshake] knows the session.
     *
     * @param userAgent which agent to present — see [userAgents] for why this is a parameter
     *   rather than a constant baked into the map.
     */
    fun headers(
        cookieHeader: String? = null,
        userAgent: String = USER_AGENT,
    ): Map<String, String> = buildMap {
        put("User-Agent", userAgent)
        put("Accept", "*/*")
        put("Accept-Language", "en-US,en;q=0.9")
        if (!cookieHeader.isNullOrBlank()) put("Cookie", cookieHeader)
    }

    /**
     * The agents to try, in order, and why more than one is needed.
     *
     * ### The measurement that produced this list
     * Replaying plan.md §3.2's three steps from one machine, same session cookie, same URLs, and
     * changing **only** the `User-Agent`:
     *
     * | `User-Agent` | `getcrumb` | chart request |
     * |---|---|---|
     * | `…(Linux; Android 14) … Chrome/126 Mobile Safari/537.36` | `429 Too Many Requests` | never reached |
     * | `Mozilla/5.0 (X11; Linux x86_64) … Chrome/126 Safari/537.36` | `VSkrj05ZC8T` | `200`, 3161 bytes |
     *
     * So the long-standing "both hosts answer 429 from here" note was a **misdiagnosis**: the
     * app was presenting a mobile agent to an edge that treats a bare mobile agent with no client
     * hints as scraper, and the resulting `429` was read as a network-level block. The host
     * fallback was never the problem and could not have fixed it.
     *
     * ### Why both are kept rather than picking one
     * Which agent is refused is a property of the *edge node the request lands on*, and that
     * varies by IP reputation and network — a residential mobile IP may accept the mobile agent
     * that a datacenter IP is refused. Hard-coding whichever agent happened to work from one
     * machine would trade a known bug for an environment-specific one. [YahooCrumbHandshake] walks
     * this list, so an agent that is refused is discovered rather than assumed.
     */
    val userAgents: List<String> = listOf(DESKTOP_USER_AGENT, MOBILE_USER_AGENT)

    /**
     * Desktop Chrome. Tried first because it is the one measured working, and because it is the
     * agent `yfinance` — the reference implementation this API shape comes from — presents.
     */
    const val DESKTOP_USER_AGENT: String =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    /**
     * Mobile Chrome. Second, not because it is expected to work where the desktop one already
     * does, but because an edge that accepts it and refuses the desktop agent is a real
     * configuration and the client should not be blind to it.
     */
    const val MOBILE_USER_AGENT: String =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    /** The agent used unless a caller asks for another. */
    const val USER_AGENT: String = DESKTOP_USER_AGENT

    // ------------------------------------------------------------------ encoding

    /**
     * Percent-encodes a path segment.
     *
     * Only `A-Z a-z 0-9 - . _ ~` survive; everything else becomes `%XX` of its UTF-8 bytes.
     * `java.net.URLEncoder` is *wrong* here: it is a form encoder, so it turns a space into `+`
     * (which in a path means a literal plus) and leaves `/` intact, which would let a symbol
     * escape its segment. Neither can happen after the allow-list, but the encoder is written
     * so that it could not matter.
     */
    internal fun percentEncodePathSegment(value: String): String = percentEncode(value)

    /** Same encoder for query values; the allow-list on the crumb makes this total. */
    internal fun percentEncodeQueryValue(value: String): String = percentEncode(value)

    private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

    private fun percentEncode(value: String): String {
        val out = StringBuilder(value.length + 8)
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val ch = (byte.toInt() and 0xFF).toChar()
            if (UNRESERVED.indexOf(ch) >= 0) {
                out.append(ch)
            } else {
                out.append('%').append(HEX[(byte.toInt() and 0xFF) shr 4]).append(HEX[byte.toInt() and 0x0F])
            }
        }
        return out.toString()
    }

    private val HEX = "0123456789ABCDEF".toCharArray()
}