package com.mia.app.data.symbols

import com.mia.app.domain.model.ParserHealth
import java.time.LocalDate

/**
 * Which NSE index a stock belongs to, as published by NSE's own constituent lists.
 *
 * ### Why this exists at all
 * The bundled symbol master (`EQUITY_L.csv`) has **no market-cap column** — only
 * `SYMBOL, NAME OF COMPANY, SERIES, DATE OF LISTING, PAID UP VALUE, MARKET LOT, ISIN NUMBER,
 * FACE VALUE`. So "large cap", "mid cap" and "small cap" could not be stated at all from data on
 * the device, and any backtest split by size would have had to invent a proxy (listing date, paid-up
 * value) and then label the result as something it was not.
 *
 * NSE publishes the real membership. That is a *published, dated definition* rather than a
 * derivation, which is the difference between a cohort table a reader can trust and one they have
 * to take on trust.
 *
 * ### Why these are disjoint, and why that matters
 * NSE maintains the three lists as a partition of the index series: verified on the shipped files,
 * 50 + 150 + 250 = 451 rows with **zero** overlap. A symbol resolving to more than one cohort would
 * mean either the files were fetched on different dates or the partition assumption is wrong, and
 * both would quietly corrupt a per-cohort hit rate by double-counting the same formation.
 * [IndexCohortTable.cohortOf] therefore resolves to a single cohort or to `null`, never to a list.
 */
enum class IndexCohort(
    /** The index's published name, used in the cohort label the reader sees. */
    val label: String,
    /** The NSE constituent file this cohort is parsed from. */
    val assetName: String,
    /** Rows the list held when it was fetched, used as a completeness check. */
    val expectedSize: Int,
) {
    /** NIFTY 50 — the large-cap cohort. */
    NIFTY_50("NIFTY 50 (large cap)", "nifty50.csv", 50),

    /** NIFTY MIDCAP 150. */
    MIDCAP_150("NIFTY MIDCAP 150", "nifty_midcap150.csv", 150),

    /** NIFTY SMALLCAP 250. */
    SMALLCAP_250("NIFTY SMALLCAP 250", "nifty_smallcap250.csv", 251),
    ;

    companion object {
        /** Look a cohort up by its asset name, as the loader receives it. */
        fun byAsset(name: String): IndexCohort? = entries.firstOrNull { it.assetName == name }

        /**
         * The classpath directory every cohort file lives under, **without** a leading or trailing
         * slash beyond the one shown.
         *
         * [assetName] is relative to this, deliberately: storing `indices/` in both produced
         * `/assets/indices/indices/nifty50.csv`, which resolves to nothing and reads as "the asset
         * is not bundled".
         */
        const val DIRECTORY: String = "assets/indices/"

        /**
         * The date these files were fetched, which is the **only** thing that makes "large cap"
         * reproducible.
         *
         * NSE revises index membership quarterly. A cohort table without a vintage is not a claim
         * about the index; it is a claim about whichever revision happened to be on disk, and it
         * would silently change meaning after an app update while the backtest numbers stayed on
         * screen. Every surface that shows a cohort shows this date beside it.
         */
        val BUNDLED_AS_OF: LocalDate = LocalDate.parse("2026-10-04")
    }
}

/** One row of an NSE constituent list. */
data class IndexConstituent(
    /** NSE's ticker, e.g. `RELIANCE`. Uppercased and trimmed, so it joins to the symbol master. */
    val symbol: String,
    /** NSE's company name, verbatim — including its own spelling, which is not always correct. */
    val companyName: String,
    /**
     * NSE's industry classification for the company, e.g. `"Oil, Gas & Consumable Fuels"`.
     *
     * Kept because it is the *only* cap-independent classification the device receives from a
     * published source, and `SectorResolver` already treats this text as its highest-precedence
     * input. Not used for sector resolution here — the two vocabularies differ and joining them
     * would be a guess.
     */
    val industry: String,
    /** The NSE series, e.g. `EQ`. Retained so a non-equity row can be rejected rather than scored. */
    val series: String,
    /** The ISIN, which survives a rename and a demerger (`EQUITY_L.csv` is keyed on it). */
    val isin: String,
)

/**
 * One cohort's parsed membership.
 *
 * @param rejectedCount rows the parser dropped, so a short list is never silent.
 */
data class IndexCohortTable(
    val cohort: IndexCohort,
    val constituents: List<IndexConstituent>,
    val rejectedCount: Int,
    val health: ParserHealth,
) {
    /**
     * The `asOf` shown beside the cohort label.
     *
     * The bundled file's vintage, **not** the current date. Reading the current date here would
     * imply the list is current, which is exactly the claim the vintage exists to prevent.
     */
    val asOf: LocalDate get() = IndexCohort.BUNDLED_AS_OF

    private val bySymbol: Map<String, IndexConstituent> =
        constituents.associateBy { it.symbol }

    /** The row for [symbol], or `null`. Case- and space-insensitive, matching the master. */
    fun constituent(symbol: String): IndexConstituent? =
        bySymbol[symbol.trim().uppercase()]

    /** `true` when [symbol] is in this cohort. */
    fun contains(symbol: String): Boolean = constituent(symbol) != null

    /** Which cohort [symbol] is in, or `null` when it is in none of the parsed tables. */
    fun cohortOf(
        symbol: String,
        tables: List<IndexCohortTable>,
    ): IndexCohort? = tables.firstOrNull { it.contains(symbol) }?.cohort

    /**
     * `true` when the parsed row count matches what NSE publishes for this list.
     *
     * A short parse is the failure that matters here: a truncated download would otherwise look
     * like a genuinely smaller index, and every per-cohort number computed from it would be wrong
     * in a way nothing on screen could reveal.
     */
    val isComplete: Boolean
        get() = constituents.size == cohort.expectedSize && rejectedCount == 0
}
