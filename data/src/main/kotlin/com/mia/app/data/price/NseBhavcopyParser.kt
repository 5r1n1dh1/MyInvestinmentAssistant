package com.mia.app.data.price

import com.mia.app.domain.model.ParserHealth
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/**
 * Pure functions that turn a raw NSE EOD bhavcopy payload into candles.
 *
 * **No IO, no clock, no configuration.** Everything the parser knows about the file format
 * lives here, which is what makes plan.md §13's "bhavcopy format changes silently" risk
 * testable: the fixture in `data/src/test/resources/fixtures/bhavcopy_20261001.zip` is a
 * real 208,712-byte archive, and every field mapping is asserted against it exactly.
 *
 * The format is undocumented and unversioned by NSE, so the parser is written to *fail
 * loudly* instead of quietly returning nothing (plan.md §12):
 * - a missing **required** column is a hard failure naming the column;
 * - a missing **optional** column is a *partial* health with the column named;
 * - a malformed row is skipped **and counted**, with samples retained;
 * - an unusable payload is a failure, never an empty list.
 */
object NseBhavcopyParser {

    /**
     * Series kept by default: `EQ` (equities) and `SM` (suriots/structured SME).
     *
     * plan.md §3.1, verified on the 2026-10-01 file — 3,712 instruments split as
     * `EQ` 2662, `SM` 389, `BE` 246, `ST` 87, `GS` 55, `GB` 44 plus ~50 further codes.
     * Everything else is currency derivatives, commodity futures, corporate-action
     * snapshots (`BE` = book entry from a scheme, `ST` = demerged/suspended relic) or
     * sovereign gold bonds. None of them is a tradeable equity and none belongs in a price
     * chart; keeping them would corrupt every market-wide statistic downstream.
     */
    val DEFAULT_SERIES: Set<String> = setOf("EQ", "SM")

    /** Every series code observed in the 2026-10-01 fixture — test/discovery helper. */
    val KNOWN_SERIES: Set<String> = setOf(
        "EQ", "SM", "BE", "ST", "GS", "GB", "SG", "N0", "BZ", "IV", "N1", "N6", "N2", "TB",
        "RR", "NE", "NA", "N4", "ND", "NC", "NB", "N9", "N8", "N7", "N5", "NG", "NF", "N3",
        "BL", "NZ", "NS", "NK", "NJ", "NI", "NH", "E1", "ZH", "Z8", "YW", "YT", "YS", "YI",
        "YC", "Y8", "Y4", "Y3", "Y0", "NX", "NR", "NO",
    )

    /** Column spellings, exactly as NSE writes them (plan.md §3.1). */
    object Columns {
        const val TRAD_DT = "TRADDT"
        const val BIZ_DT = "BIZDT"
        const val SEGMENT = "SGMT"
        const val SRC = "SRC"
        const val INSTR_TP = "FININSTRMTP"
        const val INSTR_ID = "FININSTRMID"
        const val ISIN = "ISIN"
        const val SYMBOL = "TCKRSYMB"
        const val SERIES = "SCTYSRS"
        const val NAME = "FININSTRMNM"
        const val OPEN = "OPNPRIC"
        const val HIGH = "HGHPRIC"
        const val LOW = "LWPRIC"
        const val CLOSE = "CLSPRIC"
        const val LAST = "LASTPRIC"
        const val PREV_CLOSE = "PRVSCLSGPRIC"
        const val UNDERLYING = "UNDRLYGPRIC"
        const val SETTLEMENT = "STTLMPRIC"
        const val OPEN_INTEREST = "OPNINTRST"
        const val CHANGE_OI = "CHNGINOPNINTRST"
        const val VOLUME = "TTLTRADGVOL"
        const val TURNOVER = "TTLTRFVAL"
        const val TRADES = "TTLNBOFTXSEXCTD"
        const val SESSION = "SSNID"
        const val LOT_SIZE = "NEWBRDLOTQTY"

        /** Present in the file, deliberately not carried anywhere. */
        val IGNORED = setOf(
            "XPRYDT", "FININSTRMACTLXPRYDT", "STRKPRIC", "OPTNTP",
            "RMKS", "RSVD1", "RSVD2", "RSVD3", "RSVD4",
        )
    }

    /**
     * Columns without which no usable candle can be built. Missing any of these and the file
     * is not a bhavcopy — a different layout wearing the same URL.
     */
    val REQUIRED_COLUMNS: List<String> = listOf(
        Columns.TRAD_DT, Columns.SYMBOL, Columns.SERIES,
        Columns.OPEN, Columns.HIGH, Columns.LOW, Columns.CLOSE, Columns.VOLUME,
    )

    /**
     * Columns that enrich a candle but whose absence must not stop the parse. Each one that
     * is missing is reported through [ParserHealth.sectionsMissing] so a silent format change
     * is still visible (plan.md §13).
     */
    val OPTIONAL_COLUMNS: List<String> = listOf(
        Columns.BIZ_DT, Columns.SEGMENT, Columns.SRC, Columns.INSTR_TP, Columns.INSTR_ID,
        Columns.ISIN, Columns.NAME, Columns.LAST, Columns.PREV_CLOSE, Columns.UNDERLYING,
        Columns.SETTLEMENT, Columns.OPEN_INTEREST, Columns.CHANGE_OI, Columns.TURNOVER,
        Columns.TRADES, Columns.SESSION, Columns.LOT_SIZE,
    )

    /** How many offending lines are kept verbatim in [BhavcopyParseResult.skippedSamples]. */
    const val SKIPPED_SAMPLE_LIMIT: Int = 5

    /** How long a sample line may be before it is truncated for display. */
    private const val SAMPLE_MAX_CHARS: Int = 200

    /** Refuse to inflate more than this from one archive (a zip bomb is not a bhavcopy). */
    private const val MAX_ENTRY_BYTES: Int = 32 * 1024 * 1024

    /** Entry names listed in a failure message before it stops being useful. */
    private const val MAX_REPORTED_ENTRIES: Int = 10

    private val TRADE_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT)

    /**
     * Unzips [bytes] and parses the archive's CSV member.
     *
     * The real archive holds exactly one member, `BhavCopy_NSE_CM_0_0_0_<date>_F_0000.csv`,
     * stored with deflate (verified: 640,497 bytes in 208,480 compressed, 68% ratio).
     * A member may also arrive inside a folder, so the directory prefix is ignored.
     *
     * **When several CSVs are present, the lexicographically smallest entry name wins.**
     * `ZipInputStream` yields entries in archive order, which the writer controls and which is
     * therefore not a stable selection rule; picking by name makes the choice reproducible
     * across re-zips and across hosts. The rule is recorded in [health]'s notes so a future
     * two-member archive is never silently mis-parsed.
     *
     * Never throws: an unreadable archive becomes [FetchFailure.UNPARSEABLE] via
     * [BhavcopyParseResult.fetchFailure] plus a failed [health].
     */
    fun parseZip(bytes: ByteArray): BhavcopyParseResult {
        if (bytes.isEmpty()) return unparseable("bhavcopy archive is empty (0 bytes)")
        // Every zip member starts with the local file header signature `PK\x03\x04`. Checking it
        // up front turns the most common wrong payload — an Akamai/403 HTML page served with
        // 200 — into a message that says so, instead of "no .csv member (entries: none)".
        if (!hasZipSignature(bytes)) {
            return unparseable(
                "payload is not a zip: no 'PK' signature at offset 0, first bytes are " +
                    bytes.take(8).joinToString(" ") { String.format(Locale.ROOT, "%02X", it) },
            )
        }

        var bestName: String? = null
        var bestBytes: ByteArray? = null
        val csvNames = mutableListOf<String>()
        // Every entry, not just the csv ones, so "no csv here" can name what *was* here.
        val allNames = mutableListOf<String>()
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    if (allNames.size < MAX_REPORTED_ENTRIES) allNames += name
                    if (!entry.isDirectory && BhavcopyUrls.isCsvEntry(name)) {
                        if (csvNames.size < MAX_REPORTED_ENTRIES) csvNames += name
                        if (bestName == null || name < bestName) {
                            bestName = name
                            bestBytes = zip.readEntryBounded()
                        }
                    }
                    zip.closeEntry()
                }
            }
        } catch (e: ZipException) {
            return unparseable("payload is not a readable zip (${e.javaClass.simpleName}: ${e.message})")
        } catch (e: Exception) {
            return unparseable("zip could not be read: ${e.message}")
        }

        val chosen = bestName
        val payload = bestBytes
        if (chosen == null || payload == null) {
            val seen = allNames.takeIf { it.isNotEmpty() }?.joinToString() ?: "archive is empty"
            return unparseable("archive contains no .csv member (entries: $seen)")
        }

        val note = if (csvNames.size > 1) {
            "archive held more than one csv member; picked '$chosen' " +
                "(lexicographically smallest, see KDoc)"
        } else {
            null
        }
        return parseCsv(payload.toString(Charsets.UTF_8), DEFAULT_SERIES, note)
    }

    private fun hasZipSignature(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() &&
            bytes[2] == 3.toByte() && bytes[3] == 4.toByte()

    private fun ZipInputStream.readEntryBounded(): ByteArray {
        val out = ByteArrayOutputStream(1 shl 16)
        val buffer = ByteArray(1 shl 16)
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read <= 0) break
            total += read
            if (total > MAX_ENTRY_BYTES) {
                throw ZipException("entry exceeds $MAX_ENTRY_BYTES bytes")
            }
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    /**
     * Parses bhavcopy CSV [text] into candles.
     *
     * Tolerance, in the order it is applied:
     * 1. a UTF-8 BOM and a `CR` are stripped (Windows exports and Excel round-trips exist);
     * 2. the header line is normalised per cell — BOM, surrounding whitespace (the legacy
     *    file's leading-space bug, plan.md §3.5), casing, and internal whitespace/underscores
     *    are all irrelevant, so ` tradsymb `, `TckrSymb` and `TCKR_SYMB` are one column;
     * 3. unknown columns are ignored, so an NSE addition (`XpryDt`, `StrkPric`, `OptnTp`,
     *    `FininstrmActlXpryDt`, `Rmks`, `Rsvd1..4` — all present in the real file) cannot
     *    break parsing;
     * 4. missing optional columns degrade the health, missing required columns fail the parse;
     * 5. rows are located **by header position, never by column index**, because positions are
     *    exactly what changes when a column is inserted.
     *
     * [filterSeries] filters on `SctySrs`; filtered rows are *not* counted as skipped. Pass
     * `null` to keep every series — used by diagnostics and by the tests that assert the
     * whole-file series distribution.
     *
     * [note] is prepended to [ParserHealth.notes] when the result is fully healthy; it exists
     * so [parseZip] can record how it chose an archive member.
     */
    fun parseCsv(
        text: String,
        filterSeries: Set<String>? = DEFAULT_SERIES,
        note: String? = null,
    ): BhavcopyParseResult {
        val cleaned = text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        val lines = cleaned.split('\n')

        // Diagnostics accumulate in declaration order and are attached to the health whatever
        // it turns out to be.
        val notes = mutableListOf<String>()

        val headerIndex = lines.indexOfFirst { it.isNotBlank() }
        if (headerIndex < 0) {
            return unparseable("payload contains no header line (${text.length} chars)")
        }

        val header = splitCsvLine(lines[headerIndex])
        if (header.isEmpty()) return unparseable("header line is empty")
        val columns = buildColumnIndex(header)

        if (note != null) notes += note

        val recognised = REQUIRED_COLUMNS + OPTIONAL_COLUMNS
        val found = recognised.filter { columns.containsKey(it) }
        val missingOptional = recognised.filterNot { it in REQUIRED_COLUMNS || it in columns }
        val missingRequired = REQUIRED_COLUMNS.filterNot { it in columns }

        if (missingRequired.isNotEmpty()) {
            return unparseable(
                "required column(s) absent: ${missingRequired.joinToString()}; " +
                    "header was [${header.joinToString()}]",
            )
        }

        val rows = ArrayList<BhavcopyRow>(2048)
        val samples = ArrayList<String>(SKIPPED_SAMPLE_LIMIT)
        var rowsTotal = 0
        var rowsSkipped = 0

        for (lineNo in (headerIndex + 1) until lines.size) {
            val line = lines[lineNo]
            if (line.isBlank()) continue
            rowsTotal++
            val cells = splitCsvLine(line)
            if (cells.size != header.size) {
                rowsSkipped++
                addSample(samples, lineNo + 1, line, "expected ${header.size} cells, got ${cells.size}")
                continue
            }
            val row = cells.toRow(columns)
            when {
                row == null -> {
                    rowsSkipped++
                    addSample(samples, lineNo + 1, line, "unparseable value")
                }
                // Intended filter, not a defect: filtered rows are excluded but never skipped.
                filterSeries != null && row.series !in filterSeries -> Unit
                else -> rows += row
            }
        }

        if (missingOptional.isNotEmpty()) {
            notes += "optional column(s) absent: ${missingOptional.joinToString()}"
        }
        if (rowsSkipped > 0) notes += "$rowsSkipped row(s) skipped"

        if (rows.isEmpty()) {
            // Never a silent empty list: say why, and say how much was seen and rejected.
            return BhavcopyParseResult(
                candles = emptyList(),
                health = ParserHealth.failed(
                    buildString {
                        append("no rows kept: ").append(rowsTotal).append(" data row(s) seen, ")
                        append(rowsSkipped).append(" skipped, series filter ")
                        append(filterSeries?.sorted() ?: "none")
                        if (notes.isNotEmpty()) append("; ").append(notes.joinToString("; "))
                    },
                ),
                rowsTotal = rowsTotal,
                rowsSkipped = rowsSkipped,
                skippedSamples = samples,
                tradDt = null,
                rows = emptyList(),
                fetchFailure = FetchFailure.UNPARSEABLE,
            )
        }

        val tradDt = rows.first().tradDt
        if (rows.any { it.tradDt != tradDt }) {
            notes += "TradDt differs between rows; used the first row's date ($tradDt)"
        }

        // A note is never dropped: a degraded parse is exactly when the diagnostics matter most.
        val health = when {
            missingOptional.isNotEmpty() || rowsSkipped > 0 -> ParserHealth.partial(
                found = found,
                missing = missingOptional,
                note = "NSE bhavcopy $tradDt; ${notes.joinToString("; ")}",
            )
            else -> ParserHealth(true, found, emptyList(), notes)
        }

        return BhavcopyParseResult(
            candles = rows.map { it.toCandle() },
            health = health,
            rowsTotal = rowsTotal,
            rowsSkipped = rowsSkipped,
            skippedSamples = samples,
            tradDt = tradDt,
            rows = rows,
        )
    }

    /**
     * Header cell → normalised name → column index. Later duplicates win, matching how NSE's
     * own generator behaves when it emits a column twice; first wins would silently hide a
     * generator bug.
     */
    private fun buildColumnIndex(header: List<String>): Map<String, Int> {
        val index = LinkedHashMap<String, Int>(header.size * 2)
        header.forEachIndexed { i, raw -> index[normaliseHeader(raw)] = i }
        return index
    }

    /**
     * Header normalisation: strip BOM, collapse internal whitespace/underscores, uppercase
     * with [Locale.ROOT].
     *
     * [Locale.ROOT] matters even though these are ASCII letters: the Turkish locale maps
     * `i` to `İ`, and a device set to `tr-TR` would then fail to recognise `ISIN`.
     */
    fun normaliseHeader(name: String): String =
        name.removePrefix("\uFEFF")
            .trim()
            .replace(WHITESPACE, "")
            .replace("_", "")
            .uppercase(Locale.ROOT)

    private val WHITESPACE = Regex("\\s+")

    /**
     * Minimal RFC-4180 field splitter.
     *
     * A plain `split(',')` is enough for the current file (verified: every one of the 3,713
     * lines has exactly 34 cells and **zero** quote characters), but `FinInstrmNm` is free
     * text and NSE quoting it the moment a company name contains a comma would otherwise
     * shift every later field by one and produce plausible-looking nonsense prices. Quoted
     * fields are unwrapped and `""` becomes a literal quote.
     */
    fun splitCsvLine(line: String): List<String> {
        if (line.indexOf('"') < 0) return line.split(',')
        val out = ArrayList<String>(16)
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                quoted && ch == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                    cell.append('"'); i++
                }
                ch == '"' -> quoted = !quoted
                ch == ',' && !quoted -> { out += cell.toString(); cell.setLength(0) }
                else -> cell.append(ch)
            }
            i++
        }
        out += cell.toString()
        return out
    }

    /**
     * Builds a [BhavcopyRow], or returns null when a **required** cell cannot be read.
     *
     * Blank optional cells become null; a blank required cell is a skip.
     *
     * Numbers are read with Kotlin's `toDoubleOrNull`, which delegates to
     * `java.lang.Double.parseDouble` — locale-independent by JDK contract, unlike
     * `String.format`/`Scanner`. That is strictly stronger than passing `Locale.ROOT`
     * explicitly: a device set to `de-DE` cannot silently read `1167.70` as `1,167.70 ×
     * 10^4`. [Locale.ROOT] is still used where it is required — [TRADE_DATE] and
     * [normaliseHeader].
     */
    private fun List<String>.toRow(index: Map<String, Int>): BhavcopyRow? {
        fun cell(column: String): String = index[column]?.let { get(it).trim() }.orEmpty()

        val tradDt = parseDate(cell(Columns.TRAD_DT)) ?: return null
        val ticker = cell(Columns.SYMBOL)
        if (ticker.isEmpty()) return null
        val series = cell(Columns.SERIES)
        if (series.isEmpty()) return null
        val open = parseDouble(cell(Columns.OPEN)) ?: return null
        val high = parseDouble(cell(Columns.HIGH)) ?: return null
        val low = parseDouble(cell(Columns.LOW)) ?: return null
        val close = parseDouble(cell(Columns.CLOSE)) ?: return null
        val volume = parseLong(cell(Columns.VOLUME)) ?: return null

        return BhavcopyRow(
            tradDt = tradDt,
            bizDt = parseDate(cell(Columns.BIZ_DT)),
            segment = cell(Columns.SEGMENT).ifEmpty { null },
            sourceExchange = cell(Columns.SRC).ifEmpty { null },
            instrumentType = cell(Columns.INSTR_TP).ifEmpty { null },
            finInstrmId = cell(Columns.INSTR_ID).ifEmpty { null },
            isin = cell(Columns.ISIN).ifEmpty { null },
            ticker = ticker,
            series = series,
            name = cell(Columns.NAME).ifEmpty { null },
            open = open,
            high = high,
            low = low,
            close = close,
            lastPrice = parseDouble(cell(Columns.LAST)),
            prevClose = parseDouble(cell(Columns.PREV_CLOSE)),
            underlyingPrice = parseDouble(cell(Columns.UNDERLYING)),
            settlementPrice = parseDouble(cell(Columns.SETTLEMENT)),
            openInterest = parseDouble(cell(Columns.OPEN_INTEREST)),
            changeInOpenInterest = parseDouble(cell(Columns.CHANGE_OI)),
            volume = volume,
            turnoverValue = parseDouble(cell(Columns.TURNOVER)),
            trades = parseLong(cell(Columns.TRADES)),
            sessionId = cell(Columns.SESSION).ifEmpty { null },
            lotSize = parseLong(cell(Columns.LOT_SIZE)),
        )
    }

    /** `ISO` date only. NSE writes `2026-10-01`; anything else is a skip, not a guess. */
    fun parseDate(value: String): LocalDate? {
        if (value.isEmpty()) return null
        return runCatching { LocalDate.parse(value, TRADE_DATE) }.getOrNull()
    }

    fun parseDouble(value: String): Double? {
        if (value.isEmpty()) return null
        return value.toDoubleOrNull()
    }

    fun parseLong(value: String): Long? {
        if (value.isEmpty()) return null
        value.toLongOrNull()?.let { return it }
        // Tolerate a decimal rendering of a share count without silently truncating garbage.
        return value.toDoubleOrNull()?.takeIf { it.isFinite() }?.toLong()
    }

    private fun addSample(samples: MutableList<String>, lineNo: Int, line: String, reason: String) {
        if (samples.size >= SKIPPED_SAMPLE_LIMIT) return
        val body = if (line.length > SAMPLE_MAX_CHARS) line.take(SAMPLE_MAX_CHARS) + "…" else line
        samples += "line $lineNo: $reason — $body"
    }

    private fun unparseable(reason: String) = BhavcopyParseResult(
        candles = emptyList(),
        health = ParserHealth.failed(reason),
        rowsTotal = 0,
        rowsSkipped = 0,
        skippedSamples = emptyList(),
        tradDt = null,
        rows = emptyList(),
        fetchFailure = FetchFailure.UNPARSEABLE,
    )
}
