package com.mia.app.data.price

import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.ParserHealth
import com.mia.app.domain.model.Timeframe
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

// ---------------------------------------------------------------------- payload shape

/**
 * The subset of Yahoo's v8 `finance/chart` document an index fetch needs.
 *
 * Declared here rather than reused from `com.mia.app.data.history` for one substantive reason: the
 * equity parser's shapes carry per-split `events` and an `adjclose` block that an **index** can
 * never have, and coupling the two would mean an added field on the equity side silently changed
 * what an index parse accepts. A benchmark series is a different shape of problem and has its own
 * small, auditable schema.
 */
@Serializable
internal data class IndexChartRoot(val chart: IndexChartNode? = null)

@Serializable
internal data class IndexChartNode(
    val result: List<IndexChartResult>? = null,
    val error: IndexChartError? = null,
)

@Serializable
internal data class IndexChartError(val code: String? = null, val description: String? = null)

@Serializable
internal data class IndexChartResult(
    val meta: IndexChartMeta? = null,
    val timestamp: List<Long>? = null,
    val indicators: IndexChartIndicators? = null,
)

@Serializable
internal data class IndexChartMeta(
    val symbol: String? = null,
    val currency: String? = null,
    val exchangeName: String? = null,
    val instrumentType: String? = null,
    val regularMarketPrice: Double? = null,
    val dataGranularity: String? = null,
    val range: String? = null,
    val exchangeTimezoneName: String? = null,
    val timezone: String? = null,
)

@Serializable
internal data class IndexChartIndicators(val quote: List<IndexQuoteBlock>? = null)

@Serializable
internal data class IndexQuoteBlock(
    val open: List<Double?>? = null,
    val high: List<Double?>? = null,
    val low: List<Double?>? = null,
    val close: List<Double?>? = null,
)

// ---------------------------------------------------------------------- the parser

/**
 * Turns Yahoo's v8 chart document into a benchmark series of [Candle]s.
 *
 * ### Separate from the source on purpose
 * A parser that only ever runs against a hand-written fixture cannot be caught drifting from the
 * source that feeds it, and it cannot be asserted at all when the source needs a live handshake.
 * `data/src/test/resources/fixtures/yahoo_index_nsei_sample.json` is therefore a **synthetic**
 * document — hand-written from the schema, not captured — and the golden test asserts exact
 * dates, exact closes and the exact row count. A captured payload is deliberately *not* used here:
 * unlike the bhavcopy (whose failure mode is a format change in a file this app depends on), the
 * risk on this path is a misunderstood field, and a hand-written fixture is one whose every field
 * is accounted for.
 *
 * ### Four decisions that are not free choices
 *
 * 1. **A row with a null close is dropped, never zero-filled.** Yahoo emits `null` for a session
 *    with no data; a `0.0` close would be a catastrophic-looking index level that the
 *    relative-strength ratio would then happily amplify. [IndexSeries.rowsDropped] counts them and
 *    the health note names them.
 * 2. **The close is the only member that must be present.** An index chart occasionally carries a
 *    close with a null `open`/`high`/`low`. Collapsing the bar to `O=H=L=C=close` keeps it
 *    structurally valid without inventing a price that differs from the one published, and the
 *    number of such rows is reported so the shape is visible rather than silent.
 * 3. **Dates come from the exchange timezone.** Yahoo stamps Indian daily bars at ~03:45 UTC
 *    (09:15 IST). Reading them as UTC would put every session on the previous day, and since
 *    `relativeStrength` aligns the two series **by date**, that would silently empty the
 *    intersection and unscore the Context factor. Whichever zone was used is stated in the health
 *    notes, because a wrong-zone parse is invisible otherwise.
 * 4. **Rows are de-duplicated and sorted.** `relativeStrength` sorts internally, but the engine's
 *    `Candle.lastOrNull()?.date` "as of" read assumes ascending order, so a descending payload
 *    must not be passed on as-is.
 *
 * ### Never throws
 * Malformed JSON, an absent `chart` object, a populated `chart.error`, an empty timestamp array
 * and a wholly null quote block all come back as a failed [IndexSeries] naming the reason
 * (plan.md §12).
 *
 * ### What the parser deliberately does *not* decide
 * Whether the parsed series is **long enough to be a benchmark**. That gate
 * ([IndexEndpoints.MIN_USABLE_BARS]) belongs to [YahooIndexSource], which knows what range was
 * requested and can therefore say *"30 sessions came back for a 1y request"* — a sentence this
 * parser cannot form. Keeping it out of here also keeps this object answerable to one question:
 * what did the document say.
 */
object YahooIndexChartParser {

    /** `ignoreUnknownKeys` is mandatory: Yahoo adds fields without notice (plan.md §13). */
    private val JSON: Json = Json {
        ignoreUnknownKeys = true
        isLenient = false
    }

    /** Sections named in the health of a successful parse, so two parses can be diffed. */
    val SECTIONS: List<String> = listOf("meta", "timestamp", "indicators.quote[0]")

    /**
     * Parses a chart document into a benchmark series.
     *
     * @param body the raw response text. Never trimmed or pre-processed: what Yahoo sent is what
     *   is parsed, and a body that is not chart JSON fails rather than being coaxed into shape.
     */
    fun parse(
        body: String,
        symbol: IndexSymbol,
        timeframe: Timeframe = Timeframe.D1,
        range: String? = null,
        interval: String? = IndexEndpoints.INTERVAL,
        url: String? = null,
        httpCode: Int? = 200,
        attempts: Int = 1,
    ): IndexSeries {
        val root = try {
            JSON.decodeFromString(IndexChartRoot.serializer(), body)
        } catch (e: Exception) {
            return IndexSeries.failed(
                symbol = symbol,
                failure = IndexFailureReason.UNPARSEABLE,
                reason = "Yahoo index body is not v8 chart JSON " +
                    "(${e.javaClass.simpleName}: ${e.message})",
                timeframe = timeframe,
                range = range,
                interval = interval,
                url = url,
                httpCode = httpCode,
                attempts = attempts,
            )
        }

        val chart = root.chart
            ?: return IndexSeries.failed(
                symbol, IndexFailureReason.UNPARSEABLE,
                "Yahoo index body has no 'chart' object (${body.take(120).replace('\n', ' ')})",
                timeframe, range, interval, url, httpCode, attempts,
            )

        chart.error?.let { error ->
            return IndexSeries.failed(
                symbol, IndexFailureReason.NO_DATA,
                "Yahoo reported an error for ${symbol.wire}: code=${error.code ?: "?"}, " +
                    "description=${error.description ?: "?"}. A caret symbol that stops answering " +
                    "usually means the index was renamed, not that the market is closed.",
                timeframe, range, interval, url, httpCode, attempts,
            )
        }

        val result = chart.result?.firstOrNull()
            ?: return IndexSeries.failed(
                symbol, IndexFailureReason.NO_DATA,
                "Yahoo returned no chart result for ${symbol.wire}",
                timeframe, range, interval, url, httpCode, attempts,
            )

        val timestamps = result.timestamp
        if (timestamps.isNullOrEmpty()) {
            return IndexSeries.failed(
                symbol, IndexFailureReason.NO_DATA,
                "Yahoo returned ${symbol.wire} with an empty timestamp array",
                timeframe, range, interval, url, httpCode, attempts, rowsRequested = 0,
            )
        }

        val quote = result.indicators?.quote?.firstOrNull()
            ?: return IndexSeries.failed(
                symbol, IndexFailureReason.UNPARSEABLE,
                "Yahoo index chart for ${symbol.wire} has no indicators.quote[0]",
                timeframe, range, interval, url, httpCode, attempts, rowsRequested = timestamps.size,
            )

        val (zone, zoneNote) = zoneOf(result.meta)
        val notes = mutableListOf<String>()
        zoneNote?.let { notes += it }
        result.meta?.let { meta ->
            notes += "meta: symbol=${meta.symbol ?: symbol.wire}, " +
                "exchange=${meta.exchangeName ?: "?"}, currency=${meta.currency ?: "?"}, " +
                "granularity=${meta.dataGranularity ?: "?"}, range=${meta.range ?: range ?: "?"}"
        }

        // Ascending, de-duplicated: the engine's "newest bar" reads assume it.
        val order = ascending(timestamps)
        if (order.sorted) {
            notes += "payload was not in ascending timestamp order; sorted ${timestamps.size} row(s)"
        }
        if (order.duplicates > 0) {
            notes += "${order.duplicates} duplicate timestamp(s) collapsed, last value kept"
        }

        val candles = ArrayList<Candle>(timestamps.size)
        var rowsDropped = 0
        var collapsed = 0

        for (row in order.rows) {
            val close = quote.close?.getOrNull(row)
            if (close == null || !close.isFinite() || close <= 0.0) {
                rowsDropped++
                continue
            }
            val open = quote.open?.getOrNull(row)?.takeIf { it.isFinite() && it > 0.0 }
            val high = quote.high?.getOrNull(row)?.takeIf { it.isFinite() && it > 0.0 }
            val low = quote.low?.getOrNull(row)?.takeIf { it.isFinite() && it > 0.0 }
            if (open == null || high == null || low == null) collapsed++
            val candle = Candle(
                symbol = symbol.wire,
                timeframe = timeframe,
                date = dateOf(timestamps[row], zone),
                open = open ?: close,
                high = high ?: close,
                low = low ?: close,
                close = close,
                // An index has no traded share volume; Yahoo sends nulls. Recorded as 0 rather
                // than dropped, because the bar itself is real — see the equity parser's identical
                // decision for halted sessions.
                volume = 0L,
                // No delivery figure exists for an index. null, never 0.0 (plan.md §8.2).
                deliveryPercent = null,
                source = IndexEndpoints.INDEX_SOURCE,
            )
            if (!candle.isValid) {
                rowsDropped++
                continue
            }
            candles += candle
        }

        if (rowsDropped > 0) {
            notes += "$rowsDropped of ${timestamps.size} row(s) dropped: a null or non-positive " +
                "close. Zero-filling them would have invented an index level (plan.md §12)"
        }
        if (collapsed > 0) {
            notes += "$collapsed row(s) carried no open/high/low and were collapsed to " +
                "O=H=L=C=close; the close is the published price and the flat bar is the honest " +
                "representation of a session where only it was sent"
        }

        if (candles.isEmpty()) {
            return IndexSeries.failed(
                symbol, IndexFailureReason.EMPTY_SERIES,
                "Yahoo returned ${timestamps.size} row(s) for ${symbol.wire} and every one was " +
                    "dropped ($rowsDropped null/invalid close); ${notes.joinToString("; ")}",
                timeframe, range, interval, url, httpCode, attempts,
                rowsRequested = timestamps.size, rowsDropped = rowsDropped,
            )
        }

        val health = if (rowsDropped > 0) {
            ParserHealth.partial(found = SECTIONS, missing = emptyList(), note = notes.joinToString("; "))
        } else {
            ParserHealth(ok = true, sectionsFound = SECTIONS, notes = notes)
        }

        return IndexSeries(
            symbol = symbol,
            timeframe = timeframe,
            candles = candles,
            health = health,
            rowsRequested = timestamps.size,
            rowsDropped = rowsDropped,
            range = result.meta?.range ?: range,
            interval = result.meta?.dataGranularity ?: interval,
            url = url,
            httpCode = httpCode,
            attempts = attempts,
        )
    }

    /**
     * The zone a timestamp should be read in.
     *
     * `exchangeTimezoneName` first (Yahoo publishes `"Asia/Calcutta"` for NSE indices), then
     * `timezone` (the abbreviation `"IST"`, which is **not** a `ZoneId` and is expected to fail),
     * then UTC. Whichever was used is reported, so a wrong-zone parse cannot hide.
     */
    private fun zoneOf(meta: IndexChartMeta?): Pair<ZoneId, String?> {
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

    private data class OrderedRows(val rows: List<Int>, val sorted: Boolean, val duplicates: Int)

    /** Ascending order plus duplicate collapsing, in one pass over the index list. */
    private fun ascending(timestamps: List<Long>): OrderedRows {
        val rows = ArrayList<Int>(timestamps.size)
        val seen = HashSet<Long>(timestamps.size * 2)
        var duplicates = 0
        for (i in timestamps.indices) {
            if (!seen.add(timestamps[i])) {
                duplicates++
                continue
            }
            rows += i
        }
        val comparator = compareBy<Int> { timestamps[it] }
        val wasSorted = rows.zipWithNext().all { (a, b) -> comparator.compare(a, b) <= 0 }
        if (!wasSorted) rows.sortWith(comparator)
        return OrderedRows(rows, sorted = !wasSorted, duplicates = duplicates)
    }
}