package com.mia.app.data.history

import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.ParserHealth
import com.mia.app.domain.model.Source
import com.mia.app.domain.model.Timeframe
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * A corporate action inside the price history (plan.md §3.2 `events.splits`).
 *
 * ### Why splits must be parsed at all
 * The price series this app stores is **unadjusted** — deliberately, because the primary source
 * (the NSE bhavcopy) is unadjusted too, and plan.md §5.1 makes NSE the precedence winner for
 * OHLCV. Mixing an adjusted Yahoo close with unadjusted NSE prices would manufacture a gap that
 * never traded. The cost of keeping the series unadjusted is that a 1:2 bonus issue looks like a
 * 50% overnight crash, which is exactly the signal a pattern detector would read as a real move.
 *
 * The split event is therefore not decoration: it is the only thing that lets the app say "the
 * price halved because the share count doubled" instead of drawing a chart that lies. plan.md
 * §9 lists corporate actions as a first-class concern and §13 lists "corporate actions break
 * symbol mapping" as a named risk.
 */
data class StockSplit(
    val symbol: String,
    val date: LocalDate,
    val numerator: Int,
    val denominator: Int,
    /** Yahoo's own `splitRatio` text, e.g. `"1:2"`. Kept verbatim for display. */
    val ratio: String? = null,
) {
    /** New shares per old share: `1:2` → `0.5`, `2:1` → `2.0`. */
    val shareMultiplier: Double
        get() = if (denominator == 0) Double.NaN else numerator.toDouble() / denominator.toDouble()

    /** User-facing form, falling back to the numeric ratio when Yahoo sent no text. */
    val description: String get() = ratio?.takeIf { it.isNotBlank() } ?: "$numerator:$denominator"
}

/**
 * Yahoo's split-adjusted close for one session.
 *
 * Stored beside the unadjusted [Candle]s rather than replacing them, and never mixed into
 * them — see [StockSplit] for why. Callers that want a back-adjusted chart read this series;
 * the default chart reads the candles, because that is the same basis as the bhavcopy bars the
 * app has been showing all along.
 */
data class AdjustedClose(val date: LocalDate, val close: Double)

/**
 * Everything one Yahoo chart request produced, with the health attached.
 *
 * plan.md §12: a source must never return a silent empty list. [isUsable] is the single
 * question a caller should ask, [failure] says *why* when the answer is no, and [rowsRequested]
 * / [rowsDropped] make the P3 gate ("≥ 490 rows", plan.md §11) assertable arithmetic rather
 * than a vibe.
 */
data class HistoryResult(
    val symbol: String,
    val timeframe: Timeframe,
    val candles: List<Candle> = emptyList(),
    val splits: List<StockSplit> = emptyList(),
    /** Present only when the payload carried `indicators.adjclose`. */
    val adjustedCloses: List<AdjustedClose> = emptyList(),
    val health: ParserHealth = ParserHealth.failed("not fetched"),
    /** Rows the document contained, before null-dropping. */
    val rowsRequested: Int = 0,
    /** Rows dropped because an OHLC member was null or the bar was structurally invalid. */
    val rowsDropped: Int = 0,
    val range: String? = null,
    val interval: String? = null,
    /** `meta.regularMarketPrice`, for diagnostics against the bhavcopy close. */
    val regularMarketPrice: Double? = null,
    /** `meta.chartPreviousClose` (or `previousClose`). */
    val previousClose: Double? = null,
    val url: String? = null,
    val httpCode: Int? = null,
    /** Requests issued for this result, handshake included. */
    val attempts: Int = 1,
    val failure: YahooFailureReason? = null,
) {
    val isUsable: Boolean get() = candles.isNotEmpty() && failure == null

    /** Newest bar's date — the "as of" the UI must show (plan.md §1.4 rule 3). */
    val asOf: LocalDate? get() = candles.lastOrNull()?.date

    /** True when every requested row survived. Anything else is a partial parse. */
    val isComplete: Boolean get() = isUsable && rowsDropped == 0

    companion object {
        /**
         * The only way this package reports a failure. There is no `throw` path out of a fetch.
         */
        fun failed(
            symbol: String,
            timeframe: Timeframe,
            failure: YahooFailureReason,
            reason: String,
            range: String? = null,
            interval: String? = null,
            url: String? = null,
            httpCode: Int? = null,
            attempts: Int = 1,
            rowsRequested: Int = 0,
            rowsDropped: Int = 0,
        ): HistoryResult = HistoryResult(
            symbol = symbol,
            timeframe = timeframe,
            health = ParserHealth.failed(reason),
            range = range,
            interval = interval,
            url = url,
            httpCode = httpCode,
            attempts = attempts,
            rowsRequested = rowsRequested,
            // Kept on a failed result too: "every row was dropped" is the reason it failed, and
            // losing the count would make the most informative failure the least diagnosable one.
            rowsDropped = rowsDropped,
            failure = failure,
        )

        /** A series the caller deliberately did not fetch (write-once cache already full). */
        fun skipped(symbol: String, timeframe: Timeframe, note: String): HistoryResult = HistoryResult(
            symbol = symbol,
            timeframe = timeframe,
            health = ParserHealth(
                ok = false,
                sectionsFound = emptyList(),
                sectionsMissing = emptyList(),
                notes = listOf(note),
            ),
            failure = null,
        )
    }
}

// ---------------------------------------------------------------------- payload shape

@Serializable
internal data class ChartRoot(val chart: ChartNode? = null)

@Serializable
internal data class ChartNode(val result: List<ChartResult>? = null, val error: ChartError? = null)

@Serializable
internal data class ChartError(val code: String? = null, val description: String? = null)

@Serializable
internal data class ChartResult(
    val meta: ChartMeta? = null,
    val timestamp: List<Long>? = null,
    val events: ChartEvents? = null,
    val indicators: ChartIndicators? = null,
)

@Serializable
internal data class ChartMeta(
    val currency: String? = null,
    val symbol: String? = null,
    val exchangeName: String? = null,
    val instrumentType: String? = null,
    val regularMarketPrice: Double? = null,
    val chartPreviousClose: Double? = null,
    val previousClose: Double? = null,
    val dataGranularity: String? = null,
    val range: String? = null,
    val timezone: String? = null,
    val exchangeTimezoneName: String? = null,
)

@Serializable
internal data class ChartEvents(
    val dividends: Map<String, DividendEvent>? = null,
    val splits: Map<String, SplitEvent>? = null,
)

@Serializable
internal data class DividendEvent(val amount: Double? = null, val date: Long? = null)

@Serializable
internal data class SplitEvent(
    val date: Long? = null,
    val numerator: Int? = null,
    val denominator: Int? = null,
    val splitRatio: String? = null,
)

@Serializable
internal data class ChartIndicators(
    val quote: List<QuoteBlock>? = null,
    val adjclose: List<AdjCloseBlock>? = null,
)

@Serializable
internal data class QuoteBlock(
    val open: List<Double?>? = null,
    val high: List<Double?>? = null,
    val low: List<Double?>? = null,
    val close: List<Double?>? = null,
    val volume: List<Long?>? = null,
)

@Serializable
internal data class AdjCloseBlock(val adjclose: List<Double?>? = null)

// ---------------------------------------------------------------------- the parser

/**
 * Parses Yahoo's v8 `finance/chart` document into [Candle]s (plan.md §3.2).
 *
 * ### The schema, as used here
 *
 * ```json
 * { "chart": { "result": [ {
 *     "meta":   { "symbol":"RELIANCE.NS", "exchangeTimezoneName":"Asia/Calcutta", … },
 *     "timestamp": [1759276800, 1759363200, …],
 *     "events": { "splits": { "1609459200": {"date":1609459200,"numerator":1,
 *                                            "denominator":2,"splitRatio":"1:2"} } },
 *     "indicators": { "quote": [ {"open":[…],"high":[…],"low":[…],"close":[…],"volume":[…]} ],
 *                     "adjclose": [ {"adjclose":[…]} ] } } ], "error": null } }
 * ```
 *
 * ### Four decisions that are not free choices
 *
 * 1. **A row with any null OHLC is dropped, never zero-filled.** Yahoo emits `null` for a
 *    session it has no trade data for, and `0.0` would be a catastrophic-looking bar that a
 *    volatility indicator would happily amplify. plan.md §12's "never a silent empty" applies
 *    per row too: [HistoryResult.rowsDropped] counts them and the health says so.
 * 2. **Prices are the unadjusted `quote` block.** `adjclose` is kept alongside in
 *    [HistoryResult.adjustedCloses]. See [StockSplit] for why the two must not be mixed.
 * 3. **`events.splits` is parsed and returned.** This is how the app learns about a corporate
 *    action inside the backfilled window; see [StockSplit].
 * 4. **Dates come from the exchange timezone**, falling back to UTC. Yahoo stamps Indian daily
 *    bars at ~03:45 UTC (09:15 IST); using the exchange zone means a bar that lands just after
 *    midnight IST still carries the session's own date. The zone actually used is recorded in
 *    the health notes, because a wrong-zone parse shifts every date and is invisible otherwise.
 *
 * ### Never throws
 * Malformed JSON, an empty result array, a populated `chart.error` and a wholly null quote
 * block all come back as a failed [HistoryResult] naming the reason (plan.md §12).
 */
object YahooChartParser {

    /** `ignoreUnknownKeys` is mandatory: Yahoo adds fields without notice (plan.md §13). */
    private val JSON: Json = Json {
        ignoreUnknownKeys = true
        isLenient = false
    }

    /** Section names used in [ParserHealth.sectionsFound], so a caller can diff two parses. */
    val SECTIONS: List<String> = listOf("meta", "timestamp", "indicators.quote[0]", "indicators.adjclose[0]", "events.splits")

    fun parse(
        body: String,
        symbol: String,
        timeframe: Timeframe,
        range: String? = null,
        interval: String? = null,
        url: String? = null,
        httpCode: Int? = 200,
        attempts: Int = 1,
    ): HistoryResult {
        val notes = mutableListOf<String>()
        val root = try {
            JSON.decodeFromString(ChartRoot.serializer(), body)
        } catch (e: Exception) {
            return HistoryResult.failed(
                symbol = symbol,
                timeframe = timeframe,
                failure = YahooFailureReason.UNPARSEABLE,
                reason = "Yahoo chart body is not v8 chart JSON (${e.javaClass.simpleName}: ${e.message})",
                range = range,
                interval = interval,
                url = url,
                httpCode = httpCode,
                attempts = attempts,
            )
        }

        val chart = root.chart
            ?: return HistoryResult.failed(
                symbol, timeframe, YahooFailureReason.UNPARSEABLE,
                "Yahoo chart body has no 'chart' object (${body.take(120).replace('\n', ' ')})",
                range = range, interval = interval, url = url, httpCode = httpCode, attempts = attempts,
            )

        chart.error?.let { error ->
            return HistoryResult.failed(
                symbol, timeframe, YahooFailureReason.NO_DATA,
                "Yahoo reported an error for $symbol: " +
                    "code=${error.code ?: "?"}, description=${error.description ?: "?"} " +
                    "(plan.md §9: a symbol with no history is usually a demerger, e.g. TATAMOTORS)",
                range = range, interval = interval, url = url, httpCode = httpCode, attempts = attempts,
            )
        }

        val result = chart.result?.firstOrNull()
            ?: return HistoryResult.failed(
                symbol, timeframe, YahooFailureReason.NO_DATA,
                "Yahoo returned no chart result for $symbol",
                range = range, interval = interval, url = url, httpCode = httpCode, attempts = attempts,
            )

        val meta = result.meta
        val timestamps = result.timestamp
        if (timestamps == null || timestamps.isEmpty()) {
            return HistoryResult.failed(
                symbol, timeframe, YahooFailureReason.NO_DATA,
                "Yahoo returned $symbol with an empty timestamp array (plan.md §9: the symbol " +
                    "does not exist on Yahoo — for Indian equities that is usually a demerger)",
                range = range, interval = interval, url = url, httpCode = httpCode, attempts = attempts,
            )
        }

        val quote = result.indicators?.quote?.firstOrNull()
        if (quote == null) {
            return HistoryResult.failed(
                symbol, timeframe, YahooFailureReason.UNPARSEABLE,
                "Yahoo chart for $symbol has no indicators.quote[0]",
                range = range, interval = interval, url = url, httpCode = httpCode, attempts = attempts,
            )
        }

        val (zone, zoneNote) = zoneOf(meta)
        if (zoneNote != null) notes += zoneNote
        if (meta != null) {
            notes += "meta: symbol=${meta.symbol ?: symbol}, exchange=${meta.exchangeName ?: "?"}, " +
                "currency=${meta.currency ?: "?"}, granularity=${meta.dataGranularity ?: "?"}, " +
                "range=${meta.range ?: range ?: "?"}"
        }

        // De-duplicate and order by timestamp. Yahoo is normally already ascending; a chart that
        // is not would silently break every downstream "latest bar" assumption.
        val order = rememberSorted(timestamps)
        if (order.sorted) {
            notes += "payload was not in ascending timestamp order; sorted ${timestamps.size} row(s)"
        }
        if (order.duplicateCount > 0) {
            notes += "${order.duplicateCount} duplicate timestamp(s) collapsed, last value kept"
        }

        val adjClose = result.indicators?.adjclose?.firstOrNull()?.adjclose
        val candles = ArrayList<Candle>(timestamps.size)
        val adjusted = ArrayList<AdjustedClose>(timestamps.size)
        var rowsDropped = 0
        var volumeNulls = 0
        var adjustedDiffers = 0

        for (row in order.rows) {
            val o = quote.open?.getOrNull(row)
            val h = quote.high?.getOrNull(row)
            val l = quote.low?.getOrNull(row)
            val c = quote.close?.getOrNull(row)
            if (o == null || h == null || l == null || c == null) {
                rowsDropped++
                continue
            }
            val rawVolume = quote.volume?.getOrNull(row)
            if (rawVolume == null) volumeNulls++
            val candle = Candle(
                symbol = meta?.symbol?.substringBefore('.') ?: symbol,
                timeframe = timeframe,
                date = dateOf(timestamps[row], zone),
                open = o,
                high = h,
                low = l,
                close = c,
                volume = rawVolume ?: 0L,
                // Yahoo publishes no delivery figure. null, never 0.0 — see BhavcopyRow.toCandle.
                deliveryPercent = null,
                source = Source.YAHOO,
            )
            if (!candle.isValid) {
                rowsDropped++
                continue
            }
            candles += candle
            adjClose?.getOrNull(row)?.let { adj ->
                adjusted += AdjustedClose(candle.date, adj)
                if (adj != c) adjustedDiffers++
            }
        }

        val splits = splits(result.events, symbol, zone, notes)
        val sectionsFound = buildList {
            if (meta != null) add("meta")
            add("timestamp")
            add("indicators.quote[0]")
            if (adjClose != null) add("indicators.adjclose[0]")
            if (splits.isNotEmpty()) add("events.splits")
        }

        if (rowsDropped > 0) {
            notes += "$rowsDropped of ${timestamps.size} row(s) dropped: a null OHLC member or a " +
                "structurally invalid bar (high < low, non-positive price). Zero-filling them would " +
                "have invented a move (plan.md §12)"
        }
        if (volumeNulls > 0) {
            notes += "$volumeNulls row(s) carried a null volume; recorded as 0 shares, not dropped, " +
                "because a halted session is a real bar with no volume"
        }
        if (adjClose != null) {
            notes += "prices are the unadjusted quote block; adjclose was present for ${adjusted.size} " +
                "row(s) and differs on $adjustedDiffers of them. The NSE bhavcopy is unadjusted too " +
                "(plan.md §5.1 makes it the OHLCV precedence winner), so the stored series stays " +
                "unadjusted and the adjusted series is kept beside it in adjustedCloses"
        }
        if (splits.isNotEmpty()) {
            notes += "${splits.size} split event(s) inside the window: " +
                splits.joinToString(", ") { "${it.date} ${it.description}" } +
                " — the price series is unadjusted, so these must be applied before comparing " +
                "levels across the event (plan.md §9)"
        }

        if (candles.isEmpty()) {
            return HistoryResult.failed(
                symbol, timeframe, YahooFailureReason.EMPTY_SERIES,
                "Yahoo returned ${timestamps.size} row(s) for $symbol and every one was dropped " +
                    "(${rowsDropped} null/invalid OHLC); ${notes.joinToString("; ")}",
                range = range, interval = interval, url = url, httpCode = httpCode,
                attempts = attempts, rowsRequested = timestamps.size, rowsDropped = rowsDropped,
            )
        }

        val health = if (rowsDropped > 0) {
            ParserHealth.partial(found = sectionsFound, missing = emptyList(), note = notes.joinToString("; "))
        } else {
            ParserHealth(ok = true, sectionsFound = sectionsFound, notes = notes)
        }

        return HistoryResult(
            symbol = meta?.symbol?.substringBefore('.') ?: symbol,
            timeframe = timeframe,
            candles = candles,
            splits = splits,
            adjustedCloses = adjusted,
            health = health,
            rowsRequested = timestamps.size,
            rowsDropped = rowsDropped,
            range = meta?.range ?: range,
            interval = meta?.dataGranularity ?: interval,
            regularMarketPrice = meta?.regularMarketPrice,
            previousClose = meta?.chartPreviousClose ?: meta?.previousClose,
            url = url,
            httpCode = httpCode,
            attempts = attempts,
        )
    }

    /**
     * The zone a timestamp should be read in.
     *
     * `exchangeTimezoneName` first (Yahoo publishes `"Asia/Calcutta"` for NSE), then `timezone`
     * (which is the abbreviation `"IST"` and is **not** a `ZoneId`, so it is expected to fail),
     * then UTC. Whichever was used is reported so a wrong-zone parse cannot hide.
     */
    private fun zoneOf(meta: ChartMeta?): Pair<ZoneId, String?> {
        meta ?: return ZoneOffset.UTC to "no meta block; timestamps read as UTC"
        for (candidate in listOfNotNull(meta.exchangeTimezoneName, meta.timezone)) {
            val zone = runCatching { ZoneId.of(candidate) }.getOrNull() ?: continue
            val note = if (candidate == meta.exchangeTimezoneName) {
                "dates read in the exchange timezone $candidate"
            } else {
                "exchangeTimezoneName was absent; fell back to timezone '$candidate'"
            }
            return zone to note
        }
        return ZoneOffset.UTC to "exchangeTimezoneName '${meta.exchangeTimezoneName}' and timezone " +
            "'${meta.timezone}' are unusable; timestamps read as UTC"
    }

    private fun dateOf(epochSeconds: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochSecond(epochSeconds).atZone(zone).toLocalDate()

    private fun splits(
        events: ChartEvents?,
        symbol: String,
        zone: ZoneId,
        notes: MutableList<String>,
    ): List<StockSplit> {
        val raw = events?.splits.orEmpty()
        if (raw.isEmpty()) return emptyList()
        val parsed = raw.values.mapNotNull { event ->
            val at = event.date ?: return@mapNotNull null
            val numerator = event.numerator ?: return@mapNotNull null
            val denominator = event.denominator ?: return@mapNotNull null
            if (denominator == 0) return@mapNotNull null
            StockSplit(
                symbol = symbol,
                date = dateOf(at, zone),
                numerator = numerator,
                denominator = denominator,
                ratio = event.splitRatio,
            )
        }.sortedBy { it.date }
        val dropped = raw.size - parsed.size
        if (dropped > 0) {
            notes += "$dropped of ${raw.size} split event(s) dropped for a missing date or a zero " +
                "denominator; a split with neither is not a corporate action"
        }
        return parsed
    }

    /** Ascending order plus duplicate collapsing, in one pass over the index list. */
    private data class OrderedRows(val rows: List<Int>, val sorted: Boolean, val duplicateCount: Int)

    private fun rememberSorted(timestamps: List<Long>): OrderedRows {
        val rows = ArrayList<Int>(timestamps.size)
        val seen = HashSet<Long>(timestamps.size * 2)
        var duplicateCount = 0
        for (i in timestamps.indices) {
            if (!seen.add(timestamps[i])) {
                duplicateCount++
                continue
            }
            rows += i
        }
        // Stable sort keeps the payload order for equal keys; there are none left.
        val comparator = compareBy<Int> { timestamps[it] }
        val wasSorted = rows.zipWithNext().all { (a, b) -> comparator.compare(a, b) <= 0 }
        if (!wasSorted) rows.sortWith(comparator)
        return OrderedRows(rows, sorted = !wasSorted, duplicateCount = duplicateCount)
    }
}