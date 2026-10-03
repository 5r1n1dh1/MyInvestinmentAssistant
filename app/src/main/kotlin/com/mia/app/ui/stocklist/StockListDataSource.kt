package com.mia.app.ui.stocklist

import dagger.Qualifier
import java.time.LocalDate

/**
 * The dispatcher the Stock List screen's reads run on.
 *
 * Declared here rather than borrowed for the same reason as `TechnicalsBackgroundDispatcher`: the
 * storage layer's IO dispatcher lives in a module being written in parallel, and a watchlist read is
 * a Room query — IO, but bounded by the row count, so a dedicated qualifier keeps the screen
 * independently compilable and independently testable with a hand-written fake.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class StockListBackgroundDispatcher

/**
 * One tracked symbol, with the two numbers that make a watchlist readable and nothing else.
 *
 * ### Why the list is so narrow
 * A watchlist row answers one question — "is anything I track worth opening?" — so it carries the
 * last close, the day's change and the red-flag count. Everything else (indicators, patterns, the
 * track record) belongs to the detail screen, and pulling it here would mean a full
 * `IndicatorSnapshot` pass for every row on every render, which plan.md §12's 2 GB-RAM target
 * cannot afford.
 *
 * ### Offline-first, and how the screen knows
 * [lastClose] and [changePercent] come from the on-device cache **first** and are only replaced when
 * a refresh succeeds, so the list renders instantly and correctly with no network. [isFromCache]
 * carries that fact to the row, which then says the figure is from the cache — plan.md §10's rule is
 * that a figure's age must be visible, and "rendered from cache" is part of its age.
 *
 * ### Nullable numbers stay nullable
 * A symbol added to the watchlist before its first bhavcopy row has a `null` close. The row renders
 * that as "no close stored yet", never as `0.00`: a zero price is a fabricated number, and plan.md
 * §12 calls a fabricated number the most dangerous failure in the product.
 */
data class WatchlistRow(
    val symbol: String,
    /** Company name, or the ticker itself when the master has no row for it. Never blank. */
    val name: String,
    /** NSE series code, so a row that cannot be priced says why (plan.md §3.1). */
    val series: String,
    /** Last EOD close, or `null` when nothing has been stored for this symbol. */
    val lastClose: Double?,
    /** Day change against the previous close, in percent. `null` when either close is absent. */
    val changePercent: Double?,
    /**
     * How many red flags the engine produced for this symbol.
     *
     * `0` means "no flags in the data checked", which is not the same as "no flags exist" — a rule
     * that could not run is reported by [StockListDataSource.flagCoverageNotes], never as a zero.
     */
    val redFlagCount: Int,
    /** The trading day [lastClose] is from. `null` when nothing has been stored. */
    val asOf: LocalDate? = null,
    /** True when the close came from the on-device cache rather than a fetch in this session. */
    val isFromCache: Boolean = true,
) {
    init {
        require(symbol.isNotBlank()) { "a watchlist row without a ticker has no identity" }
        require(redFlagCount >= 0) { "a red-flag count cannot be negative" }
    }

    /** `true` when the row can be opened at all: an unpriceable series has nothing to show. */
    val hasPrice: Boolean get() = lastClose != null
}

/**
 * The Stock List screen's entire view of the outside world.
 *
 * ### Why the watchlist is a screen-level concept and not a repository
 * plan.md decision OQ-4 leaves *"do patterns run on all watchlist symbols nightly, or only on
 * demand?"* open, and the answer changes where the list lives. Declaring the seam here keeps that
 * choice above this screen: the ViewModel asks for "the tracked symbols with their latest snapshots"
 * and does not know whether those snapshots came from the nightly worker or from a foreground
 * fetch.
 *
 * ### Contract
 * * Every function is `suspend` and must already be off the main thread — the caller wraps the load
 *   in [StockListBackgroundDispatcher].
 * * `null` / empty means **"there is nothing", not "we failed"**. A function that *fails* throws
 *   [StockListUnavailableException].
 * * [add] and [remove] must be **idempotent**. A double tap on "add" must not produce two rows, and
 *   removing a symbol that is already gone must succeed, because both are the natural result of a
 *   tap the reader is sure they made.
 */
interface StockListDataSource {

    /**
     * The tracked symbols, each with its latest stored snapshot.
     *
     * **Offline-first**: implementations return the cache without waiting on the network. Sorted by
     * ticker so the list does not reshuffle between refreshes — a watchlist that reorders itself is
     * impossible to scan.
     */
    suspend fun tracked(): List<WatchlistRow>

    /**
     * Adds [symbol] to the tracked list.
     *
     * @return the stored row, or `null` when the symbol is not in the bundled NSE master. "Not
     *   tracked" is a real answer and must not be rendered as an error.
     */
    suspend fun add(symbol: String): WatchlistRow?

    /**
     * Removes [symbol] from the tracked list.
     *
     * @return the remaining rows, so the caller never has to re-read to redraw.
     */
    suspend fun remove(symbol: String): List<WatchlistRow>

    /**
     * One line per red-flag check that could not be evaluated, across the tracked symbols.
     *
     * ### Why this is not derivable from the rows
     * A row with `redFlagCount = 0` and a row where half the rules abstained are different facts, and
     * rendering them identically is plan.md §12's "silent-empty is indistinguishable from no red
     * flags". So the screen shows this line whenever it is non-empty, above the list.
     */
    suspend fun flagCoverageNotes(): List<String>
}

/**
 * The exception every [StockListDataSource] implementation throws on a failed read.
 *
 * @param reason the sentence shown to the user. Must already be plain language — never an exception
 *   class name (see `ErrorState`'s contract).
 * @param isTransient whether a retry button is worth offering.
 */
class StockListUnavailableException(
    val reason: String,
    val isTransient: Boolean = true,
) : Exception(reason)

/**
 * The whole list's load, assembled off the main thread.
 *
 * Grouped into one object for the same reason as `TechnicalsLoad`: there must be no intermediate
 * "rows arrived but the coverage note has not" frame, or the coverage line would flicker.
 */
internal data class StockListLoad(
    val rows: List<WatchlistRow>,
    val coverageNotes: List<String>,
    /** Set when one row's identity could not be read; the rest of the list is still valid. */
    val note: String? = null,
)