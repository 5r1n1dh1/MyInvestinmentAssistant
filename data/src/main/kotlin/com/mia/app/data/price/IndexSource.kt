package com.mia.app.data.price

import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.ParserHealth
import com.mia.app.domain.model.Source
import com.mia.app.domain.model.Timeframe
import java.time.LocalDate

/**
 * Why an index fetch could not produce candles.
 *
 * Every reason here is something the UI must be able to say out loud. plan.md §12's rule is that
 * a source failure must never look like "the index has no data", and the failure this class most
 * has to prevent is a **fabricated series**: a benchmark invented from a stale cache, a straight
 * line, or a carried-forward close would silently score the Context factor. So nothing in this file
 * ever substitutes a number for a measurement — a failure is a typed value with a sentence, and
 * the caller passes `null` onwards, which is the engine's honest "no benchmark — relative strength
 * unscored" path (`PatternEngine.dataQualityNotes`).
 *
 * The reasons mirror `YahooFailureReason` where the cause is the same (a rate limit is a rate
 * limit) and are separate where the *meaning to a caller* differs: [NO_BENCHMARK_ROWS] exists
 * because "the fetch worked and the answer was not enough bars" is a different situation from
 * "the fetch failed", and only the first one is worth retrying tomorrow.
 */
enum class IndexFailureReason {
    /** The symbol is not one of the [IndexSymbol]s this app knows. No URL was ever built. */
    UNKNOWN_SYMBOL,

    /**
     * A `range` outside [IndexEndpoints.SUPPORTED_RANGES] was requested. No URL was ever built —
     * a programming error on the caller's side, not a network outcome.
     */
    UNSUPPORTED_REQUEST,

    /** No session cookie or no crumb: the mandatory Yahoo handshake of plan.md §3.2 failed. */
    HANDSHAKE_FAILED,

    /** `429` on every host, even after a fresh session. plan.md §13's named risk. */
    RATE_LIMITED,

    /** HTTP 404/410: the wire symbol does not exist. Almost always a renamed index. */
    NOT_FOUND,

    /** Nothing answered 2xx (DNS, TLS, 403/5xx) or the transport threw. */
    HOST_UNREACHABLE,

    /** `chart.error` was populated, or the payload held zero timestamps. */
    NO_DATA,

    /** A 2xx body that is not v8 chart JSON. */
    UNPARSEABLE,

    /** Every row was dropped — an all-null close series is a defect, not "the index did not trade". */
    EMPTY_SERIES,

    /**
     * The fetch succeeded but the series is shorter than
     * [IndexEndpoints.MIN_USABLE_BARS], so relative strength would be measured over too few
     * sessions to mean anything.
     *
     * Distinct from [EMPTY_SERIES] on purpose: this is the *healthy* answer for a freshly
     * installed app whose index fetch has not run yet, and the right reaction is "try again
     * after tonight's refresh", not "report a broken source".
     */
    NO_BENCHMARK_ROWS,
}

/**
 * The index series one fetch produced, with the honesty flags attached.
 *
 * [isUsable] is the single question a caller should ask and [failure] says why when the answer is
 * no. This mirrors [com.mia.app.data.price.BhavcopyParseResult]'s contract deliberately: a source
 * returns a result object, never a bare `List<Candle>`, so "the list was empty" cannot be
 * mistaken for "there is nothing there".
 */
data class IndexSeries(
    /** Which index this was for. Never a free-form string, so a caller cannot typo a benchmark. */
    val symbol: IndexSymbol,
    val timeframe: Timeframe = Timeframe.D1,
    val candles: List<Candle> = emptyList(),
    val health: ParserHealth = ParserHealth.failed("not fetched"),
    /** Rows the document contained, before null-dropping. */
    val rowsRequested: Int = 0,
    /** Rows dropped because a close member was null or the bar was structurally invalid. */
    val rowsDropped: Int = 0,
    val range: String? = null,
    val interval: String? = null,
    val url: String? = null,
    val httpCode: Int? = null,
    /** Requests issued, handshake included. */
    val attempts: Int = 1,
    val failure: IndexFailureReason? = null,
) {
    val isUsable: Boolean get() = candles.isNotEmpty() && failure == null

    /** Newest bar's date — the "as of" the UI must show (plan.md §1.4 rule 3). */
    val asOf: LocalDate? get() = candles.lastOrNull()?.date

    /**
     * The sentence to show when there is no benchmark.
     *
     * `null` when the fetch worked, so a caller can write `failure?.let { … }` and print nothing
     * at all in the healthy case — "relative strength unscored" is only worth a line when the
     * reason is known.
     */
    val unavailableReason: String? get() = health.notes.firstOrNull()

    companion object {
        /** The only way this object reports a failure; there is no `throw` path out of a fetch. */
        fun failed(
            symbol: IndexSymbol,
            failure: IndexFailureReason,
            reason: String,
            timeframe: Timeframe = Timeframe.D1,
            range: String? = null,
            interval: String? = null,
            url: String? = null,
            httpCode: Int? = null,
            attempts: Int = 1,
            rowsRequested: Int = 0,
            rowsDropped: Int = 0,
        ): IndexSeries = IndexSeries(
            symbol = symbol,
            timeframe = timeframe,
            health = ParserHealth.failed(reason),
            range = range,
            interval = interval,
            url = url,
            httpCode = httpCode,
            attempts = attempts,
            rowsRequested = rowsRequested,
            rowsDropped = rowsDropped,
            failure = failure,
        )
    }
}

/**
 * The seam the Context factor's relative-strength measure is fed through.
 *
 * ### Why this exists at all
 * `PatternScanOptions.benchmarkCandles` has been `null` at both call sites since the app was
 * written, which means the Context factor's relative-strength component is permanently unscored.
 * The seam was already there and correct; what was missing was a series to put in it. This
 * interface is that series' one method, kept deliberately narrow so a future NSE-hosted index file
 * drops in without touching anything above this line (plan.md §3.7's documented blast-radius
 * decision, applied to the index rather than to equities).
 *
 * ### The contract
 * * **Never throws.** Every failure is an [IndexSeries] with [IndexSeries.failure] set and a
 *   sentence on [IndexSeries.health]. A thrown exception three frames from a scan is a blank
 *   screen; a typed result is a line the header can print.
 * * **Never fabricates.** A failed fetch yields an empty [IndexSeries.candles], and the caller
 *   passes `null` to the engine, which unscores the factor and says so. There is deliberately no
 *   overload that returns a default or a zero-filled series, so the one thing this file must
 *   never do is unrepresentable.
 */
interface IndexSource {

    /**
     * The daily series for [symbol], ready to be handed to the engine as
     * `benchmarkCandles`, or a typed failure naming why it could not be.
     *
     * @param range Yahoo's `range` parameter. [IndexEndpoints.DEFAULT_RANGE] unless a caller
     *   wants a deeper window.
     */
    suspend fun daily(symbol: IndexSymbol, range: String = IndexEndpoints.DEFAULT_RANGE): IndexSeries
}

/**
 * The index series this app measures relative strength against.
 *
 * ### Why an enum and not a string
 * The benchmark symbol reaches this layer from UI configuration and from stored preferences, and
 * an index's wire symbol is a **caret-prefixed** string (`^NSEI`) that cannot survive the
 * `YahooEndpoints` allow-list built for NSE equity tickers. Modelling the benchmark as an enum
 * makes both facts structural: there is no way to pass an unvalidated string into the URL, and
 * the wire symbol lives in exactly one place.
 *
 * ### Why these three and not the indices NSE also publishes
 * NSE publishes ~100 indices. The app needs a benchmark that (a) covers most of the market it
 * tracks, (b) has a stable Yahoo symbol, and (c) is not itself a listed instrument the app might
 * confuse for a stock. `^NSEI` (NIFTY 50), `^NSEMDCP50` (MIDCAP 50) and `^NSEMDCP150` (MIDCAP 150)
 * are the three docs/LIVE-PROBE.md measured as answering `200` through the chart endpoint. The
 * default is [NIFTY_50] because it is the index an Indian retail reader means by "the market".
 */
enum class IndexSymbol(
    /** Yahoo's wire symbol. Caret included, percent-encoded only at URL-build time. */
    val wire: String,
    /** The published index name, for the note that says *what* was compared against. */
    val label: String,
) {
    /** NIFTY 50 — the large-cap benchmark. */
    NIFTY_50("^NSEI", "NIFTY 50"),

    /** NIFTY MIDCAP 50. */
    MIDCAP_50("^NSEMDCP50", "NIFTY MIDCAP 50"),

    /** NIFTY MIDCAP 150 — the benchmark for the MIDCAP 150 cohort. */
    MIDCAP_150("^NSEMDCP150", "NIFTY MIDCAP 150"),
    ;

    companion object {
        /**
         * Looks an index up by its wire symbol, case-sensitively.
         *
         * The `^` is part of Yahoo's naming and cannot be dropped, so the match is exact; a
         * case-insensitive match would accept `^nsei` and then fail at the server with a 404 that
         * reads like a missing index rather than a typo.
         */
        fun byWire(wire: String): IndexSymbol? = entries.firstOrNull { it.wire == wire }

        /** The benchmark used when nothing else is configured. */
        val DEFAULT: IndexSymbol = NIFTY_50
    }
}

/**
 * URL construction for the index chart request, plus the candidate list of NSE-hosted index files
 * that planv2 W3 measured and rejected.
 */
object IndexEndpoints {

    /** plan.md §3.2 step 3, the chart path. */
    const val CHART_PATH: String = "/v8/finance/chart/"

    /**
     * One year of daily closes.
     *
     * plan.md §6.2's stored series is 500 daily bars, and one calendar year of NSE sessions is
     * ~250 of them — comfortably longer than `TechnicalPolicy`'s longest lookback, so relative
     * strength over the app's whole stored window is computable from one fetch. `max` would be a
     * larger response for no additional reading.
     */
    const val DEFAULT_RANGE: String = "1y"

    /** plan.md §1.4 rule 9: `D1` only. No intraday index bars are ever asked for. */
    const val INTERVAL: String = "1d"

    /**
     * Bars below which the series is not usable as a benchmark.
     *
     * `relativeStrengthTrend` needs `lookback + 1` aligned sessions, and the longest lookback the
     * Context factor uses is 50. Fetching fewer than this and passing it onwards would produce an
     * empty RS line and an unscored factor *with no stated reason*, which is exactly the silent
     * failure the engine's null path exists to avoid.
     */
    const val MIN_USABLE_BARS: Int = 60

    /**
     * NSE's own index-close CSV shapes, **all measured 404**.
     *
     * ### Why these are kept at all
     * docs/LIVE-PROBE.md (GitHub Actions run 38949742442, 2026-10-16) replayed all three shapes
     * from GitHub's network:
     *
     * | Candidate | Result |
     * |---|---|
     * | [IND_CLOSE_ALL] on `archives.nseindia.com` | **404** |
     * | [IND_CLOSE_ALL] on `nsearchives.nseindia.com` | **404** |
     * | [IND_CLOSE_ALL] on the `products` path | **404** |
     *
     * A 404 from one host is not proof that the file does not exist — `nsearchives` sits behind
     * the same edge that fails HTTP/2 from that network for everything else (LIVE-PROBE §1). So
     * the shape stays on the record as a **documented candidate**: if a future probe finds one
     * that answers, adding it to the walk is a one-line change, and the reason it was not chosen
     * is written down rather than rediscovered.
     *
     * Yahoo is the implementation ([YahooIndexSource]) because it was measured answering `200`
     * for the same cookie+crumb handshake the app already performs.
     */
    val NSE_CSV_CANDIDATES: List<String> = listOf(IND_CLOSE_ALL)

    /** The only NSE index-CSV shape that was tried; measured 404 on all three hosts. */
    const val IND_CLOSE_ALL: String =
        "https://%s/content/indices/ind_close_all_%s.csv"

    /**
     * Ranges the chart endpoint documents and that a 5-year-or-shorter app has any use for.
     *
     * The same set [com.mia.app.data.history.YahooEndpoints] validates equities against. A
     * range outside it never reaches a URL: an unvalidated `range` would be a query-injection
     * seam, and the string cannot be a plausible Yahoo range anyway.
     */
    val SUPPORTED_RANGES: Set<String> =
        setOf("1mo", "3mo", "6mo", "1y", "2y", "3y", "5y", "10y", "ytd", "max")

    /** The two hosts [IND_CLOSE_ALL] was tried on, and the `products` path variant. */
    val NSE_CSV_HOSTS: List<String> = listOf(
        "archives.nseindia.com",
        "nsearchives.nseindia.com",
        "www1.nseindia.com",
    )

    /**
     * The chart URL for [symbol].
     *
     * `^` is percent-encoded to `%5E`, which is what `yfinance` — the reference implementation
     * of this API shape — sends. The crumb is percent-encoded the same way [YahooEndpoints] does
     * for equities; it arrives from the network and is validated by the handshake before it can
     * reach here.
     *
     * Parameter order is `range`, `interval`, `crumb`, so a test can assert the whole URL as a
     * contract rather than as a `Map` iteration order.
     */
    fun chartUrl(host: String, symbol: IndexSymbol, range: String, crumb: String): String =
        "https://$host" + CHART_PATH + percentEncode(symbol.wire) +
            "?range=" + percentEncode(range) +
            "&interval=" + INTERVAL +
            "&crumb=" + percentEncode(crumb)

    /**
     * Percent-encodes everything outside `A-Z a-z 0-9 - . _ ~`.
     *
     * `java.net.URLEncoder` is wrong for a path segment: it is a form encoder, so a space becomes
     * `+`, which in a path is a literal plus. The caret is the only character that actually needs
     * encoding here, but the encoder is written so that a future symbol containing something else
     * cannot escape its segment.
     */
    internal fun percentEncode(value: String): String {
        val out = StringBuilder(value.length + 8)
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val ch = (byte.toInt() and 0xFF).toChar()
            if (UNRESERVED.indexOf(ch) >= 0) {
                out.append(ch)
            } else {
                out.append('%').append(HEX[(byte.toInt() and 0xFF) shr 4])
                    .append(HEX[byte.toInt() and 0x0F])
            }
        }
        return out.toString()
    }

    private const val UNRESERVED =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

    private val HEX = "0123456789ABCDEF".toCharArray()

    /** `Source.YAHOO`, named so the benchmark's provenance is stated in one place. */
    val INDEX_SOURCE: Source = Source.YAHOO
}