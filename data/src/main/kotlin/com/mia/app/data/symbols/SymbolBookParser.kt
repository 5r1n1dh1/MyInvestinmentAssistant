package com.mia.app.data.symbols

import com.mia.app.domain.model.ParserHealth
import java.io.InputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale

/**
 * Parses the bundled NSE symbol master (`assets/EQUITY_L.csv`) into [SymbolRecord]s.
 *
 * **Why a dedicated parser instead of reusing the bhavcopy parser**: the two files share a
 * header convention (leading spaces after the first comma — plan.md §3.5 documents the bug in
 * the legacy file and it is present in this one too) but not a shape. The master is
 * `SYMBOL, NAME OF COMPANY, SERIES, DATE OF LISTING, PAID UP VALUE, MARKET LOT, ISIN NUMBER,
 * FACE VALUE`: no prices, a `dd-MMM-yyyy` date, and an ISIN that is the whole point.
 *
 * The one thing deliberately shared is the line splitter: [com.mia.app.data.price.NseBhavcopyParser.splitCsvLine]
 * already implements RFC-4180 quoting, and a plain `split(',')` here would silently shift every
 * column after any company name NSE ever decides to quote (which is exactly the failure mode
 * plan.md §13 lists as "HTML/bhavcopy parser brittleness → silent empty screens").
 *
 * **Never returns a silent empty list** (CONTRACTS.md rule 5): a payload it cannot understand
 * comes back with a failed [ParserHealth] naming what was missing, and rows it drops come back
 * counted in [SymbolBookParseResult.rejected] with samples retained.
 */
object SymbolBookParser {

    /**
     * The bundled asset name, matching `data/src/main/resources/assets/EQUITY_L.csv`.
     * Kept here so the loader and the tests cannot drift apart from the shipped file.
     */
    const val ASSET_NAME: String = "EQUITY_L.csv"

    /**
     * Header cells, **after** leading/trailing whitespace is stripped.
     *
     * The real header is `SYMBOL,NAME OF COMPANY, SERIES, ...` — only the first cell is
     * unspaced, so indexing by the raw string would silently miss seven of the eight columns.
     */
    object Columns {
        const val SYMBOL = "SYMBOL"
        const val NAME = "NAME OF COMPANY"
        const val SERIES = "SERIES"
        const val LISTING_DATE = "DATE OF LISTING"
        const val PAID_UP_VALUE = "PAID UP VALUE"
        const val LOT_SIZE = "MARKET LOT"
        const val ISIN = "ISIN NUMBER"
        const val FACE_VALUE = "FACE VALUE"
    }

    /**
     * Columns without which a row has no usable identity: no ticker means no name to show, and
     * no ISIN means the one key that survives a demerger (plan.md §9). Either absence rejects
     * the row rather than storing an empty key.
     */
    val REQUIRED_COLUMNS: List<String> = listOf(Columns.SYMBOL, Columns.ISIN)

    /** Columns that enrich a record; their absence degrades the health, it does not stop the parse. */
    val OPTIONAL_COLUMNS: List<String> = listOf(
        Columns.NAME, Columns.SERIES, Columns.LISTING_DATE,
        Columns.PAID_UP_VALUE, Columns.LOT_SIZE, Columns.FACE_VALUE,
    )

    /** How many rejected rows are kept verbatim in [SymbolBookParseResult.rejectedSamples]. */
    const val REJECTED_SAMPLE_LIMIT: Int = 5

    private const val SAMPLE_MAX_CHARS: Int = 160

    /**
     * `06-OCT-2008` — the master writes an English 3-letter month.
     *
     * `parseCaseInsensitive()` is not paranoia: it is what makes `06-oct-2008` work, and the
     * real file has been seen with both cases after Excel round-trips. [Locale.ENGLISH] is
     * required because the Turkish locale maps `OCT` differently.
     */
    private val LISTING_DATE: DateTimeFormatter = DateTimeFormatterBuilder()
        .parseCaseInsensitive()
        .appendPattern("dd-MMM-yyyy")
        .toFormatter(Locale.ENGLISH)

    /** Parses master CSV [text]. Never throws. */
    fun parse(text: String): SymbolBookParseResult = parseLines(
        text.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n'),
    )

    /** Parses master CSV [stream] as UTF-8. Never throws; a null stream is a failure. */
    fun parse(stream: InputStream?): SymbolBookParseResult {
        if (stream == null) return failure("symbol master stream is null (asset not bundled?)")
        val text = runCatching { stream.reader(Charsets.UTF_8).readText() }
            .getOrElse { return failure("symbol master stream could not be read: ${it.message}") }
        return parse(text)
    }

    private fun failure(reason: String) = SymbolBookParseResult(
        records = emptyList(),
        health = ParserHealth.failed(reason),
    )

    private fun parseLines(cleaned: String): SymbolBookParseResult {
        val lines = cleaned.split('\n')
        val headerIndex = lines.indexOfFirst { it.isNotBlank() }
        if (headerIndex < 0) return failure("symbol master contains no header line (${cleaned.length} chars)")

        val header = com.mia.app.data.price.NseBhavcopyParser.splitCsvLine(lines[headerIndex])
        // Leading spaces are the documented landmine (plan.md §3.5): only the first cell of the
        // real header is unspaced, so trimming every cell before indexing is mandatory, not tidy.
        val columns = LinkedHashMap<String, Int>(header.size * 2)
        header.forEachIndexed { index, cell -> columns[cell.trim().uppercase(Locale.ROOT)] = index }

        val missingRequired = REQUIRED_COLUMNS.filterNot { it in columns }
        if (missingRequired.isNotEmpty()) {
            return failure(
                "required column(s) absent: ${missingRequired.joinToString()}; " +
                    "header was [${header.joinToString()}]",
            )
        }
        val missingOptional = OPTIONAL_COLUMNS.filterNot { it in columns }

        val notes = mutableListOf<String>()
        if (missingOptional.isNotEmpty()) notes += "optional column(s) absent: ${missingOptional.joinToString()}"

        val records = ArrayList<SymbolRecord>(4096)
        val samples = ArrayList<String>(REJECTED_SAMPLE_LIMIT)
        var rowsTotal = 0
        var rejected = 0
        var unreadableValues = 0

        for (lineNo in (headerIndex + 1) until lines.size) {
            val line = lines[lineNo]
            if (line.isBlank()) continue
            rowsTotal++

            val cells = com.mia.app.data.price.NseBhavcopyParser.splitCsvLine(line)
            fun cell(column: String): String = columns[column]?.let { cells.getOrNull(it) }?.trim().orEmpty()

            val symbol = cell(Columns.SYMBOL).uppercase(Locale.ROOT)
            val isin = cell(Columns.ISIN).uppercase(Locale.ROOT)
            // Rejecting is the only safe response: an empty ISIN would become a shared index key
            // and silently merge unrelated companies, which is the exact bug plan.md §9 exists
            // to prevent.
            if (symbol.isEmpty() || isin.isEmpty()) {
                rejected++
                addSample(
                    samples, lineNo + 1, line,
                    if (symbol.isEmpty()) "no symbol" else "no ISIN",
                )
                continue
            }

            val listingDate = parseListingDate(cell(Columns.LISTING_DATE))
            val paidUp = parseDouble(cell(Columns.PAID_UP_VALUE))
            val face = parseDouble(cell(Columns.FACE_VALUE))
            val lot = parseLong(cell(Columns.LOT_SIZE))
            if (cell(Columns.LISTING_DATE).isNotEmpty() && listingDate == null) unreadableValues++
            if (cell(Columns.PAID_UP_VALUE).isNotEmpty() && paidUp == null) unreadableValues++
            if (cell(Columns.FACE_VALUE).isNotEmpty() && face == null) unreadableValues++
            if (cell(Columns.LOT_SIZE).isNotEmpty() && lot == null) unreadableValues++

            records += SymbolRecord(
                symbol = symbol,
                name = cell(Columns.NAME),
                series = cell(Columns.SERIES).uppercase(Locale.ROOT),
                isin = isin,
                listingDate = listingDate,
                paidUpValue = paidUp,
                lotSize = lot,
                faceValue = face,
            )
        }

        // Duplicate resolution is last-wins and is documented rather than silent: a future
        // master that lists one ticker twice is a data problem the user must be able to see.
        val byTicker = LinkedHashMap<String, SymbolRecord>(records.size * 2)
        val duplicateTickers = mutableListOf<String>()
        for (record in records) {
            if (byTicker.put(record.symbol, record) != null) duplicateTickers += record.symbol
        }
        val deduplicated = byTicker.values.toList()

        val byIsin = HashMap<String, SymbolRecord>(deduplicated.size * 2)
        val duplicateIsins = mutableListOf<String>()
        for (record in deduplicated) {
            if (byIsin.put(record.isin, record) != null) duplicateIsins += record.symbol
        }

        if (rejected > 0) notes += "$rejected row(s) rejected (no symbol or no ISIN)"
        if (unreadableValues > 0) notes += "$unreadableValues value(s) unreadable, stored as null"
        if (duplicateTickers.isNotEmpty()) notes += "duplicate ticker(s), last row wins: ${duplicateTickers.joinToString()}"
        if (duplicateIsins.isNotEmpty()) notes += "duplicate ISIN(s), first row wins: ${duplicateIsins.joinToString()}"

        if (deduplicated.isEmpty()) {
            return SymbolBookParseResult(
                records = emptyList(),
                health = ParserHealth.failed(
                    "no usable rows: $rowsTotal data row(s) seen, $rejected rejected" +
                        if (notes.isEmpty()) "" else "; ${notes.joinToString("; ")}",
                ),
                rowsTotal = rowsTotal,
                rejected = rejected,
                rejectedSamples = samples,
            )
        }

        val health = if (missingOptional.isNotEmpty() || rejected > 0 || unreadableValues > 0 ||
            duplicateTickers.isNotEmpty() || duplicateIsins.isNotEmpty()
        ) {
            ParserHealth.partial(
                found = REQUIRED_COLUMNS + OPTIONAL_COLUMNS.filter { it in columns },
                missing = missingOptional,
                note = "NSE symbol master; ${notes.joinToString("; ")}",
            )
        } else {
            ParserHealth(
                ok = true,
                sectionsFound = REQUIRED_COLUMNS + OPTIONAL_COLUMNS.filter { it in columns },
                notes = notes,
            )
        }

        return SymbolBookParseResult(
            records = deduplicated,
            health = health,
            rowsTotal = rowsTotal,
            rejected = rejected,
            rejectedSamples = samples,
            duplicateTickers = duplicateTickers,
            duplicateIsins = duplicateIsins,
        )
    }

    /** `06-OCT-2008`. Anything else is null, never a guess — a wrong listing date is a lie. */
    fun parseListingDate(value: String): LocalDate? {
        if (value.isBlank()) return null
        return runCatching { LocalDate.parse(value, LISTING_DATE) }.getOrNull()
    }

    fun parseDouble(value: String): Double? =
        value.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()

    fun parseLong(value: String): Long? {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return null
        trimmed.toLongOrNull()?.let { return it }
        // Tolerate a decimal rendering of a share count without silently truncating garbage.
        return trimmed.toDoubleOrNull()?.takeIf { it.isFinite() }?.toLong()
    }

    private fun addSample(samples: MutableList<String>, lineNo: Int, line: String, reason: String) {
        if (samples.size >= REJECTED_SAMPLE_LIMIT) return
        val body = if (line.length > SAMPLE_MAX_CHARS) line.take(SAMPLE_MAX_CHARS) + "…" else line
        samples += "line $lineNo: $reason — $body"
    }
}

/**
 * Outcome of parsing the symbol master.
 *
 * [rowsTotal] counts data rows in the file; [rejected] counts only rows that had no usable
 * identity. Those are different failures and the UI needs to tell them apart: 2,593 rows and
 * 0 rejected means the asset is intact, whereas 2,593 rows and 2,593 rejected means the header
 * moved and every key in the app is empty.
 */
data class SymbolBookParseResult(
    val records: List<SymbolRecord> = emptyList(),
    val health: ParserHealth = ParserHealth.failed("not parsed"),
    /** Data rows present in the file, before rejection or de-duplication. */
    val rowsTotal: Int = 0,
    /** Rows dropped for having no symbol or no ISIN. */
    val rejected: Int = 0,
    /** Up to [SymbolBookParser.REJECTED_SAMPLE_LIMIT] offending lines, verbatim. */
    val rejectedSamples: List<String> = emptyList(),
    /** Tickers seen more than once; the **last** row wins (documented, not silent). */
    val duplicateTickers: List<String> = emptyList(),
    /** ISINs seen more than once across distinct tickers; the **first** row wins. */
    val duplicateIsins: List<String> = emptyList(),
) {
    val isUsable: Boolean get() = records.isNotEmpty()
}

/**
 * Result of reading the bundled asset.
 *
 * [book] is null exactly when [health] is not ok *and* nothing was parsed — the caller can then
 * decide whether to refuse to start the symbol-dependent screens rather than showing an
 * unresolvable blank (plan.md §9: a dead symbol must be explained, never silent).
 */
data class SymbolBookLoadResult(
    val book: SymbolBook?,
    val health: ParserHealth,
    val rowsTotal: Int = 0,
    val rejected: Int = 0,
    val error: String? = null,
) {
    val isUsable: Boolean get() = book != null
}