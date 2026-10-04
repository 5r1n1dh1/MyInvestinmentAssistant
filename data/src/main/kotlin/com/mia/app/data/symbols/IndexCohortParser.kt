package com.mia.app.data.symbols

import com.mia.app.data.price.NseBhavcopyParser.splitCsvLine
import com.mia.app.domain.model.ParserHealth
import java.io.InputStream

/**
 * Parses NSE's published index constituent lists.
 *
 * **Why a dedicated parser rather than [SymbolBookParser].** The two files look alike and are not:
 * the symbol master is `SYMBOL, NAME OF COMPANY, SERIES, DATE OF LISTING, …` with a `dd-MMM-yyyy`
 * date, while a constituent list is `Company Name,Industry,Symbol,Series,ISIN Code` — **different
 * column order**, and the first column is a company name that NSE sometimes quotes. Reusing the
 * master's column indices would read `ADANIENT`'s industry as its name and its ISIN as its ticker,
 * which is the kind of silent shift CONTRACTS.md rule 5 exists to prevent.
 *
 * The line splitter is shared for the reason it is shared in [SymbolBookParser]: `split(',')` would
 * shift every column after any quoted company name.
 *
 * **Header cells are matched by name, not position.** NSE has reordered these columns before and the
 * only safe contract is the header's own spelling, so a reordered file parses rather than silently
 * producing 451 wrong rows.
 *
 * **Never returns a silent empty table.** A file it cannot understand comes back with a failed
 * [ParserHealth] naming the missing column and the cohort it was reading, and rows it drops are
 * counted in [IndexCohortTable.rejectedCount].
 *
 * ### It does **not** filter by series, and that is a decision rather than an omission
 * An earlier version kept only `Series == EQ`. Measured against the shipped files, that dropped
 * **six real constituents** — three REITs (`RR`) and three listed companies (`BE`). The rule was
 * invented: NSE's own equity *index* files only contain instruments with a traded price series, so
 * a warrant cannot appear here in the first place.
 *
 * Deciding what the app can *score* is not this parser's job. It reports index membership and
 * records the series on each row; whether a given instrument has enough history and volume for a
 * detector is a question for the consumer, and a consumer can answer it and report the gap.
 */
object IndexCohortParser {

    /** Header cells, **after** whitespace is stripped. */
    object Columns {
        const val NAME = "Company Name"
        const val INDUSTRY = "Industry"
        const val SYMBOL = "Symbol"
        const val SERIES = "Series"
        const val ISIN = "ISIN Code"
    }

    /** Without these a row has no usable identity and no durable key. */
    val REQUIRED_COLUMNS: List<String> = listOf(Columns.SYMBOL, Columns.ISIN)

    /**
     * Series seen in the shipped lists, with what each one is.
     *
     * Measured across all three files: `EQ` 445 rows, `RR` 3 rows, `BE` 3 rows.
     *
     * - `EQ` — ordinary equity. The overwhelming majority.
     * - `RR` — **REIT**: Embassy Office Parks, Bagmane Prime Office, Brookfield India Real Estate
     *   Trust. Price-traded, ordinary OHLCV, genuinely part of NIFTY MIDCAP 150.
     * - `BE` — HFCL, MTAR Technologies, Sterlite Technologies. Ordinary listed companies that NSE
     *   currently carries under this code.
     *
     * Kept as data rather than as a filter, because the earlier `EQ`-only rule silently dropped
     * those six real constituents — which is exactly what the completeness check exists to catch.
     */
    val KNOWN_SERIES: List<String> = listOf("EQ", "RR", "BE")

    /** How many rejected rows are kept as samples in the health notes. */
    const val REJECTED_SAMPLE_LIMIT: Int = 5

    /** Parses the CSV text of one constituent list. */
    fun parse(cohort: IndexCohort, text: String): IndexCohortTable = parseLines(
        cohort = cohort,
        lines = text.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n').lines(),
    )

    /** Parses one constituent CSV as UTF-8. Never throws; a null stream is a failure. */
    fun parse(cohort: IndexCohort, stream: InputStream?): IndexCohortTable {
        if (stream == null) {
            return failure(cohort, "constituent list stream is null (asset not bundled?)")
        }
        val text = runCatching { stream.reader(Charsets.UTF_8).readText() }
            .getOrElse { return failure(cohort, "constituent list could not be read: ${it.message}") }
        return parse(cohort, text)
    }

    private fun parseLines(
        cohort: IndexCohort,
        lines: List<String>,
    ): IndexCohortTable {
        val rows = lines.filter { it.isNotBlank() }
        if (rows.isEmpty()) return failure(cohort, "constituent list is empty")

        val header = splitCsvLine(rows.first()).map { it.trim() }
        val index = header.withIndex().associate { (i, name) -> name to i }

        val missing = REQUIRED_COLUMNS.filterNot { it in index }
        if (missing.isNotEmpty()) {
            return failure(
                cohort,
                "${cohort.label}: header has no ${missing.joinToString(" / ")}; " +
                    "saw ${header.joinToString(", ")}",
            )
        }

        val out = ArrayList<IndexConstituent>(rows.size)
        var rejected = 0
        val samples = ArrayList<String>()

        for (row in rows.drop(1)) {
            val cells = splitCsvLine(row)
            fun cell(name: String): String = index[name]?.let { cells.getOrNull(it) }?.trim().orEmpty()

            val symbol = cell(Columns.SYMBOL).uppercase()
            val isin = cell(Columns.ISIN)
            val series = cell(Columns.SERIES)

            // A blank ticker or a missing ISIN leaves nothing to key the row on. Rejecting is the
            // honest outcome; storing an empty symbol would create a cohort member that no scan
            // could ever match.
            if (symbol.isEmpty() || isin.isEmpty()) {
                rejected++
                if (samples.size < REJECTED_SAMPLE_LIMIT) samples += "no symbol or ISIN: ${row.take(80)}"
                continue
            }
            out += IndexConstituent(
                symbol = symbol,
                // NSE's own spelling, kept verbatim. Correcting it here would make the reader's
                // search disagree with the master, which is the registry this joins to.
                companyName = cell(Columns.NAME),
                industry = cell(Columns.INDUSTRY),
                series = series,
                isin = isin,
            )
        }

        val notes = buildList {
            if (rejected > 0) {
                add("$rejected row(s) rejected" + if (samples.isEmpty()) "" else ": ${samples.joinToString("; ")}")
            }
            val expected = cohort.expectedSize
            if (out.size != expected) {
                // The check that matters. A truncated download would otherwise read as a
                // genuinely smaller index, and every per-cohort number built on it would be wrong
                // in a way nothing on screen could reveal.
                add("parsed ${out.size} row(s), NSE publishes $expected for ${cohort.label}")
            }
        }

        return IndexCohortTable(
            cohort = cohort,
            constituents = out,
            rejectedCount = rejected,
            health = ParserHealth(
                ok = out.isNotEmpty(),
                sectionsFound = listOf("${out.size} constituents"),
                sectionsMissing = if (out.isEmpty()) listOf(cohort.label) else emptyList(),
                notes = notes,
            ),
        )
    }

    private fun failure(cohort: IndexCohort, reason: String) = IndexCohortTable(
        cohort = cohort,
        constituents = emptyList(),
        rejectedCount = 0,
        health = ParserHealth(
            ok = false,
            sectionsFound = emptyList(),
            sectionsMissing = listOf(cohort.label),
            notes = listOf(reason),
        ),
    )
}
