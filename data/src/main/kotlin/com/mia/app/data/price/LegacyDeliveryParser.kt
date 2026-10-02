package com.mia.app.data.price

import com.mia.app.data.http.HttpRequest
import com.mia.app.data.http.HttpTransport
import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.ParserHealth
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Delivery percentage from the legacy NSE `sec_bhavdata` file (plan.md §3.5).
 *
 * ```
 * https://archives.nseindia.com/products/content/sec_bhavdata_full_DDMMYYYY.csv
 * → 200, 401,205 bytes for 2026-10-01, 3,534 instruments
 * ```
 *
 * Three landmines, all handled here:
 * 1. **Every column name carries a leading space** — `SYMBOL, SERIES, DATE1, …` — and so
 *    does every value, because the file is `a, b, c` throughout. A header lookup of
 *    `"CLOSE_PRICE"` throws; so does parsing `" 01-Oct-2026"`. Headers and cells are trimmed.
 * 2. Dates are `01-Oct-2026`, not ISO. [LEGACY_DATE] parses exactly that, case-insensitively
 *    (an English-locale app must not fail in a Turkish locale where `oct` uppercases badly).
 * 3. **`DELIV_PER` is `-` for 272 of 3,534 rows** — every `BE`-series row, i.e. NSE publishes
 *    delivery for no delivery-eligible series. A `-` is *not* a parse error and *must not*
 *    become `0.0`; `Volume.kt` treats a `0%` delivery bar as the "delivery collapsed" red flag
 *    of plan.md §8.2, so a fabricated zero would manufacture a red flag out of missing data.
 *    Those symbols are simply absent from the map and the count is reported.
 *
 * plan.md §3.5 marks availability for recent dates UNVERIFIED and calls this source optional,
 * so nothing here may throw and a failure degrades to an empty map plus a failed [ParserHealth].
 */
object LegacyDeliveryParser {

    /** `01-Oct-2026`. */
    val LEGACY_DATE: DateTimeFormatter =
        DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH)

    private const val COLUMN_SYMBOL = "SYMBOL"
    private const val COLUMN_SERIES = "SERIES"
    private const val COLUMN_DATE = "DATE1"
    private const val COLUMN_DELIV_PER = "DELIV_PER"

    private val REQUIRED = listOf(COLUMN_SYMBOL, COLUMN_DELIV_PER)

    private const val SAMPLE_MAX_CHARS = 160
    private const val SAMPLE_LIMIT = 5

    /**
     * Parses the file and returns `symbol -> DELIV_PER`.
     *
     * Only symbols with a numeric `DELIV_PER` appear in the map; [LegacyDeliveryResult]
     * reports how many rows carried the `-` placeholder.
     */
    fun parse(text: String): LegacyDeliveryResult {
        val cleaned = text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        val lines = cleaned.split('\n')
        val headerIndex = lines.indexOfFirst { it.isNotBlank() }
        if (headerIndex < 0) {
            return failed("payload contains no header line (${text.length} chars)")
        }

        val header = NseBhavcopyParser.splitCsvLine(lines[headerIndex]).map { it.trim() }
        val index = header.withIndex().associate { (i, name) -> name.uppercase(Locale.ROOT) to i }
        val missing = REQUIRED.filterNot { it in index }
        if (missing.isNotEmpty()) {
            return failed("required column(s) absent: ${missing.joinToString()}; header was [$header]")
        }

        val delivery = LinkedHashMap<String, Double>(4096)
        val samples = ArrayList<String>(SAMPLE_LIMIT)
        var rowsTotal = 0
        var rowsSkipped = 0
        var rowsWithoutDelivery = 0
        var tradDt: LocalDate? = null
        var series: String? = null

        for (lineNo in (headerIndex + 1) until lines.size) {
            val line = lines[lineNo]
            if (line.isBlank()) continue
            rowsTotal++

            val cells = NseBhavcopyParser.splitCsvLine(line)
            fun cell(column: String): String = index[column]?.let { cells.getOrNull(it)?.trim() }.orEmpty()

            val symbol = cell(COLUMN_SYMBOL)
            if (symbol.isEmpty()) {
                rowsSkipped++
                addSample(samples, lineNo + 1, line, "blank SYMBOL")
                continue
            }
            if (tradDt == null) tradDt = parseLegacyDate(cell(COLUMN_DATE))
            if (series == null) series = cell(COLUMN_SERIES).ifEmpty { null }

            val raw = cell(COLUMN_DELIV_PER)
            val value = raw.toDoubleOrNull()
            when {
                value == null -> {
                    // `-` for delivery-ineligible series, or an empty cell. Not a defect.
                    rowsWithoutDelivery++
                }
                value < 0.0 -> {
                    rowsSkipped++
                    addSample(samples, lineNo + 1, line, "negative DELIV_PER '$raw'")
                }
                else -> delivery[symbol] = value
            }
        }

        if (delivery.isEmpty()) {
            return LegacyDeliveryResult(
                deliveryPercentBySymbol = emptyMap(),
                tradDt = tradDt,
                series = series,
                rowsTotal = rowsTotal,
                rowsSkipped = rowsSkipped,
                rowsWithoutDelivery = rowsWithoutDelivery,
                health = ParserHealth.failed(
                    "no delivery percentage parsed: $rowsTotal row(s) seen, " +
                        "$rowsWithoutDelivery without DELIV_PER, $rowsSkipped skipped",
                ),
            )
        }

        val notes = buildList {
            if (rowsWithoutDelivery > 0) {
                add("$rowsWithoutDelivery of $rowsTotal row(s) carried '-' for DELIV_PER " +
                    "(delivery-ineligible series); those symbols are absent from the map")
            }
            if (rowsSkipped > 0) add("$rowsSkipped row(s) skipped; first: ${samples.firstOrNull()}")
        }
        val health = if (rowsSkipped > 0) {
            ParserHealth.partial(
                found = listOf(COLUMN_SYMBOL, COLUMN_SERIES, COLUMN_DATE, COLUMN_DELIV_PER),
                missing = emptyList(),
                note = notes.joinToString("; "),
            )
        } else {
            ParserHealth(
                ok = true,
                sectionsFound = listOf(COLUMN_SYMBOL, COLUMN_SERIES, COLUMN_DATE, COLUMN_DELIV_PER),
                notes = notes,
            )
        }

        return LegacyDeliveryResult(
            deliveryPercentBySymbol = delivery,
            tradDt = tradDt,
            series = series,
            rowsTotal = rowsTotal,
            rowsSkipped = rowsSkipped,
            rowsWithoutDelivery = rowsWithoutDelivery,
            health = health,
        )
    }

    /**
     * `01-Oct-2026`, tolerating a missing leading zero (`1-Oct-2026`) and the surrounding
     * whitespace that this file puts on every value.
     */
    fun parseLegacyDate(value: String): LocalDate? {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return null
        return runCatching { LocalDate.parse(trimmed, LEGACY_DATE) }.getOrNull()
    }

    private fun addSample(samples: MutableList<String>, lineNo: Int, line: String, reason: String) {
        if (samples.size >= SAMPLE_LIMIT) return
        val body = if (line.length > SAMPLE_MAX_CHARS) line.take(SAMPLE_MAX_CHARS) + "…" else line
        samples += "line $lineNo: $reason — $body"
    }

    private fun failed(reason: String) = LegacyDeliveryResult(
        deliveryPercentBySymbol = emptyMap(),
        tradDt = null,
        series = null,
        rowsTotal = 0,
        rowsSkipped = 0,
        rowsWithoutDelivery = 0,
        health = ParserHealth.failed(reason),
    )
}

/**
 * Result of reading the legacy delivery file.
 *
 * `deliveryPercentBySymbol` is empty **only** when [health] is not ok — the same
 * never-silently-empty rule as [BhavcopyParseResult] (plan.md §12).
 */
data class LegacyDeliveryResult(
    val deliveryPercentBySymbol: Map<String, Double>,
    val tradDt: LocalDate? = null,
    val series: String? = null,
    val rowsTotal: Int = 0,
    val rowsSkipped: Int = 0,
    /** Rows whose `DELIV_PER` was `-`; see [LegacyDeliveryParser] for why this is normal. */
    val rowsWithoutDelivery: Int = 0,
    val health: ParserHealth = ParserHealth.failed("not parsed"),
) {
    val isUsable: Boolean get() = deliveryPercentBySymbol.isNotEmpty()

    /** Applies delivery to candles, leaving every bar without a published value untouched. */
    fun applyTo(candles: List<Candle>): List<Candle> =
        if (deliveryPercentBySymbol.isEmpty()) candles
        else candles.map { c ->
            val pct = deliveryPercentBySymbol[c.symbol]
            if (pct == null) c else c.copy(deliveryPercent = pct)
        }
}

/**
 * Fetches the optional legacy delivery file. Every failure mode — 404, transport exception,
 * HTML error page instead of CSV — degrades to an empty result with a failed health.
 *
 * There is no reason for this to ever throw: plan.md §3.5 marks the endpoint's availability
 * UNVERIFIED and the price layer must not depend on it.
 */
class LegacyDeliverySource(private val transport: HttpTransport) {

    suspend fun fetch(date: LocalDate): LegacyDeliveryResult {
        val url = BhavcopyUrls.legacy(date)
        return try {
            val response = transport.execute(
                HttpRequest(url = url, headers = LEGACY_HEADERS),
            )
            when {
                !response.isSuccess -> degraded("HTTP ${response.code} from $url")
                response.body.isEmpty() -> degraded("empty body from $url")
                else -> LegacyDeliveryParser.parse(response.text)
            }
        } catch (e: Exception) {
            degraded("$url failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun degraded(reason: String) = LegacyDeliveryResult(
        deliveryPercentBySymbol = emptyMap(),
        health = ParserHealth.failed("legacy delivery file unavailable — $reason"),
    )

    private companion object {
        /**
         * `sec_bhavdata` is served as a plain CSV download, so gzip negotiation only risks
         * a double-encoded body. NSE does not require a referer here (verified `200`).
         */
        val LEGACY_HEADERS: Map<String, String> = mapOf(
            "User-Agent" to "MyInvestinmentAssistant/1.0 (on-device NSE bhavcopy reader)",
            "Accept" to "text/csv,text/plain,*/*",
            "Accept-Encoding" to "identity",
        )
    }
}
