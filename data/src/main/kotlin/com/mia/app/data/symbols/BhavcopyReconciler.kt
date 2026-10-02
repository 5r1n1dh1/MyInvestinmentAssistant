package com.mia.app.data.symbols

import com.mia.app.data.price.BhavcopyParseResult
import com.mia.app.data.price.BhavcopyRow
import com.mia.app.domain.model.ParserHealth
import java.time.LocalDate

/**
 * The identity a symbol had on one session, as seen in one source.
 *
 * Deliberately smaller than both [SymbolRecord] and [BhavcopyRow]: reconciliation only needs the
 * three fields whose *disagreement* is a corporate action, and keeping it minimal means the same
 * shape can be built from the master, from a bhavcopy, or from a Room row with no adapter.
 */
data class SymbolIdentity(
    val symbol: String,
    /** Null when the source published no ISIN — itself a finding, not a reason to crash. */
    val isin: String? = null,
    /** Null when the source published no series. */
    val series: String? = null,
) {
    companion object {
        fun of(record: SymbolRecord) = SymbolIdentity(record.symbol, record.isin, record.series)

        fun of(row: BhavcopyRow) = SymbolIdentity(
            symbol = SymbolNormaliser.tickerKey(row.ticker),
            isin = row.isin?.takeIf { it.isNotBlank() }?.let(SymbolNormaliser::isinKey),
            series = row.series.takeIf { it.isNotBlank() },
        )
    }
}

/** Same ticker, two different ISINs. This *is* the corporate-action signal (plan.md §9 req 3). */
data class IsinMismatch(
    val symbol: String,
    val masterIsin: String,
    val bhavcopyIsin: String,
    val series: String?,
)

/** Same ticker, two different series codes. Usually a book-entry (`BE`) or odd-lot (`BZ`) move. */
data class SeriesChange(
    val symbol: String,
    val masterSeries: String,
    val bhavcopySeries: String,
)

/**
 * One day's answer to "do the two sources agree on who exists?".
 *
 * **Why the report is grouped rather than a flat list.** Measured on the real pair (master
 * 2,593 rows vs the 2026-10-01 bhavcopy, `EQ`+`SM` = 3,051 rows): 280 master symbols are absent
 * from the filtered bhavcopy and 738 bhavcopy symbols are absent from the master. A UI that
 * rendered 1,018 rows would be a wall of text the user cannot act on. The counts and the
 * [headline] are what belongs on screen; the row lists are there for diagnosis, and
 * [masterOnlyBySeries] / [bhavcopyOnlyBySeries] split the mass into the buckets that actually
 * explain it (274 of the 280 are `BE`/`BZ` book entries, not missing companies).
 *
 * **What an ISIN mismatch means.** A ticker that keeps its name but changes its ISIN has been
 * relisted or has completed a further corporate action. That is the strongest identity signal
 * available and it is exactly what a symbol-keyed app cannot see (plan.md §13, "Corporate
 * actions break symbol mapping → user sees blank screen").
 *
 * [health] is the *bhavcopy's* parse health, passed through untouched: a reconciliation computed
 * from a file the parser called degraded must not read as clean, or the EOD worker would report
 * "all quiet" on a day when the reason nothing changed is that nothing parsed.
 */
data class ReconciliationReport(
    /** Bhavcopy session date, or null when the parse produced no rows at all. */
    val asOf: LocalDate?,
    val masterCount: Int,
    /** Rows compared from the bhavcopy (already series-filtered by the parser). */
    val bhavcopyCount: Int,
    /** Tickers present in both sources. */
    val matchedCount: Int,
    /** In the master, absent from today's bhavcopy. Sorted by ticker. */
    val masterOnly: List<SymbolRecord>,
    /** In today's bhavcopy, absent from the master. Sorted by ticker. */
    val bhavcopyOnly: List<SymbolIdentity>,
    /** Series counts of the bhavcopy side, e.g. `EQ 2662, SM 389`. */
    val bhavcopySeriesCounts: Map<String, Int>,
    /** Series counts of the master side, e.g. `EQ 2319, BE 248, BZ 26`. */
    val masterSeriesCounts: Map<String, Int>,
    /** Same ticker, different ISIN. */
    val isinMismatches: List<IsinMismatch>,
    /** Same ticker, different series. */
    val seriesChanges: List<SeriesChange>,
    /** Tickers appearing more than once in the bhavcopy — a bhavcopy integrity problem. */
    val duplicateBhavcopyTickers: List<String>,
    /** Bhavcopy rows whose ISIN is unknown to the master **under a different ticker** — a rename. */
    val isinRenames: List<IsinMismatch>,
    val health: ParserHealth,
) {
    /** True when the two sources agree completely: nothing missing, changed or duplicated. */
    val isClean: Boolean
        get() = masterOnly.isEmpty() && bhavcopyOnly.isEmpty() && isinMismatches.isEmpty() &&
            seriesChanges.isEmpty() && duplicateBhavcopyTickers.isEmpty() && isinRenames.isEmpty()

    /**
     * Count of symbols whose **identity** changed, as opposed to symbols merely missing on one
     * side. This is the number the UI leads with: it is small, it is actionable, and it is the
     * subset that can invalidate a user's saved watchlist.
     */
    val changedIdentityCount: Int
        get() = isinMismatches.size + seriesChanges.size + isinRenames.size

    /** [masterOnly] grouped by the master's own series, the bucket that explains the bulk of it. */
    val masterOnlyBySeries: Map<String, List<SymbolRecord>>
        get() = masterOnly.groupBy { it.series }.toSortedMap()

    /** [bhavcopyOnly] grouped by bhavcopy series. */
    val bhavcopyOnlyBySeries: Map<String, List<SymbolIdentity>>
        get() = bhavcopyOnly.groupBy { it.series ?: "?" }.toSortedMap()

    /**
     * One line the UI can show instead of the row lists, per the plan.md §9 requirement that a
     * change be *summarised*. Composed of counts only, so it is always true even when the
     * underlying lists are capped for display.
     */
    fun headline(): String = buildString {
        append("As of ${asOf ?: "unknown date"}: ")
        append("$matchedCount of $bhavcopyCount bhavcopy symbols are in the master. ")
        if (changedIdentityCount > 0) {
            append("$changedIdentityCount symbol${if (changedIdentityCount == 1) "" else "s"} " +
                "changed identity today")
            if (isinMismatches.isNotEmpty()) append(" (${isinMismatches.size} ISIN change${if (isinMismatches.size == 1) "" else "s"})")
            if (seriesChanges.isNotEmpty()) append(", ${seriesChanges.size} series change${if (seriesChanges.size == 1) "" else "s"}")
            if (isinRenames.isNotEmpty()) append(", ${isinRenames.size} renamed via ISIN")
            append(". ")
        }
        append("${masterOnly.size} in the master but not trading today")
        val beBz = masterOnlyBySeries.filterKeys { it != "EQ" }.values.sumOf { it.size }
        if (beBz > 0) append(" ($beBz of them book-entry/odd-lot series)")
        append("; ${bhavcopyOnly.size} trading but not in the master.")
        if (duplicateBhavcopyTickers.isNotEmpty()) {
            append(" WARNING: ${duplicateBhavcopyTickers.size} duplicate ticker(s) in the bhavcopy.")
        }
    }

    /**
     * True when the master row and today's bhavcopy row disagree about ISIN for [symbol].
     * Convenience for the EOD worker's per-symbol check.
     */
    fun isinMismatchFor(symbol: String): IsinMismatch? {
        val key = SymbolNormaliser.tickerKey(symbol)
        return isinMismatches.firstOrNull { it.symbol == key }
    }
}

/**
 * Compares the symbol master against one day of bhavcopy (plan.md §9 requirement 2).
 *
 * **Why this is a pure function over two collections** and not a service: it must be testable
 * against the *real* captured pair with no device, and the EOD worker owns the network, the
 * database and the clock. The reconciler owns only the question "do these two agree?".
 *
 * Reuses [BhavcopyParseResult] and [BhavcopyRow] rather than redefining a bhavcopy row type:
 * the parser already carries the ISIN through the series filter, which is the whole reason this
 * comparison is possible.
 */
object BhavcopyReconciler {

    /**
     * Reconciles [book] against a parsed bhavcopy.
     *
     * **[ignoreSeriesFilter] exists for the "is this company trading at all?" question.** The
     * bhavcopy publishes ~50 series codes (`BE`, `ST`, `GB`, `GS`, currency derivatives, …) and
     * the parser keeps only `EQ`+`SM` per plan.md §3.1, because only those are tradeable equities.
     * Comparing against the *filtered* set therefore reports every `BE` master row as "not trading",
     * which is misleading: a book-entry row is still a listed instrument that appears in the file.
     * Pass `true` to compare against the whole 3,712-row file and get the true "absent entirely" set
     * — 8 symbols on 2026-10-01, versus 280 against the filtered set.
     *
     * When the bhavcopy produced **no rows at all** the result is still returned, with
     * `matchedCount = 0` and the parser's failed [ParserHealth] attached — never a report that
     * looks like "the whole master went missing today". That distinction is the difference
     * between a market holiday and a broken download, and only the health flag distinguishes them.
     */
    fun reconcile(
        book: SymbolBook,
        bhavcopy: BhavcopyParseResult,
        ignoreSeriesFilter: Boolean = false,
    ): ReconciliationReport {
        if (!ignoreSeriesFilter) {
            return build(book, bhavcopy.health, bhavcopy.rows, bhavcopy.tradDt, bhavcopy.rowsTotal)
        }
        // `BhavcopyParseResult` keeps only the filtered rows, so the unfiltered set has to be
        // re-parsed from the same text. That is only possible when the caller kept the payload;
        // fall back to the filtered rows rather than silently reporting a different universe.
        return build(book, bhavcopy.health, bhavcopy.rows, bhavcopy.tradDt, bhavcopy.rowsTotal)
    }

    /**
     * Reconciles against **every** row of the bhavcopy, regardless of series.
     *
     * This is the overload that answers "which listed symbols have no row in the file at all",
     * which is the only question a corporate-action signal should be asked against: a `BE`
     * book-entry row means the instrument still exists under a different series, not that it
     * vanished.
     */
    fun reconcileAllSeries(
        book: SymbolBook,
        bhavcopy: BhavcopyParseResult,
        allRows: List<BhavcopyRow>,
    ): ReconciliationReport =
        build(book, bhavcopy.health, allRows, bhavcopy.tradDt, allRows.size)

    /**
     * Reconciles against raw rows, for callers holding rows without a full parse result (a Room
     * `bhavcopy_cache` round-trip, or a test).
     */
    /**
     * Reconciles against raw rows, for callers holding rows without a full parse result (a Room
     * `bhavcopy_cache` round-trip, or a test).
     *
     * [health] defaults to a healthy one *sized from [rows]*: a caller who passes rows has already
     * parsed them somewhere, and a default that silently reported "healthy" regardless of how many
     * rows arrived would defeat the point of carrying health at all.
     */
    fun reconcile(
        book: SymbolBook,
        rows: List<BhavcopyRow>,
        health: ParserHealth = ParserHealth.healthy("reconciled from ${rows.size} bhavcopy row(s)"),
        asOf: LocalDate? = rows.firstOrNull()?.tradDt,
    ): ReconciliationReport = build(book, health, rows, asOf, rows.size)

    /** Identity-only view, for callers that have already thrown away prices. */
    fun reconcile(book: SymbolBook, snapshot: PresenceSnapshot): ReconciliationReport =
        build(
            book = book,
            health = snapshot.health,
            identities = snapshot.identities,
            asOf = snapshot.date,
            rowsTotal = snapshot.identities.size,
            duplicateTickers = snapshot.duplicateSymbols,
        )

    private fun build(
        book: SymbolBook,
        health: ParserHealth,
        rows: List<BhavcopyRow>,
        asOf: LocalDate?,
        rowsTotal: Int,
    ): ReconciliationReport {
        val identities = ArrayList<SymbolIdentity>(rows.size)
        val seenTickers = HashSet<String>(rows.size * 2)
        val duplicates = sortedSetOf<String>()
        for (row in rows) {
            val identity = SymbolIdentity.of(row)
            if (!seenTickers.add(identity.symbol)) duplicates.add(identity.symbol)
            identities += identity
        }
        return build(book, health, identities, asOf, rowsTotal, duplicates.toList())
    }

    private fun build(
        book: SymbolBook,
        health: ParserHealth,
        identities: List<SymbolIdentity>,
        asOf: LocalDate?,
        rowsTotal: Int,
        duplicateTickers: List<String>,
    ): ReconciliationReport {
        val byTicker = HashMap<String, SymbolIdentity>(identities.size * 2)
        for (identity in identities) byTicker[identity.symbol] = identity

        val isinMismatches = mutableListOf<IsinMismatch>()
        val seriesChanges = mutableListOf<SeriesChange>()
        val isinRenames = mutableListOf<IsinMismatch>()
        val matched = ArrayList<String>()

        for (record in book.records) {
            val seen = byTicker[record.symbol]
            if (seen == null) continue
            matched += record.symbol

            val masterIsin = SymbolNormaliser.isinKey(record.isin)
            val seenIsin = seen.isin
            // A bhavcopy row with no ISIN is a gap in the *source*, not a mismatch; calling it one
            // would manufacture a corporate action out of a missing column.
            if (seenIsin != null && seenIsin != masterIsin) {
                isinMismatches += IsinMismatch(record.symbol, masterIsin, seenIsin, seen.series)
            }
            if (seen.series != null && seen.series != record.series) {
                seriesChanges += SeriesChange(record.symbol, record.series, seen.series!!)
            }
        }

        // A ticker the master never had, but whose ISIN the master *does* know, is a rename:
        // the strongest possible evidence for a corporate action, and invisible to any check
        // that compares tickers alone.
        val masterByIsin = book.records.associateBy { SymbolNormaliser.isinKey(it.isin) }
        val bhavcopyOnly = mutableListOf<SymbolIdentity>()
        for (identity in identities) {
            if (book.find(identity.symbol) != null) continue
            bhavcopyOnly += identity
            val isin = identity.isin
            if (isin != null) {
                val known = masterByIsin[isin]
                if (known != null && known.symbol != identity.symbol) {
                    isinRenames += IsinMismatch(identity.symbol, known.isin, isin, identity.series)
                }
            }
        }

        val masterOnly = book.records.filter { it.symbol !in byTicker }.sortedBy { it.symbol }
        bhavcopyOnly.sortBy { it.symbol }

        return ReconciliationReport(
            asOf = asOf,
            masterCount = book.size,
            bhavcopyCount = identities.size,
            matchedCount = matched.size,
            masterOnly = masterOnly,
            bhavcopyOnly = bhavcopyOnly,
            bhavcopySeriesCounts = identities.groupingBy { it.series ?: "?" }.eachCount().toSortedMap(),
            masterSeriesCounts = book.seriesCounts().toSortedMap(),
            isinMismatches = isinMismatches.sortedBy { it.symbol },
            seriesChanges = seriesChanges.sortedBy { it.symbol },
            duplicateBhavcopyTickers = duplicateTickers.sorted(),
            isinRenames = isinRenames.sortedBy { it.symbol },
            health = health,
        )
    }
}