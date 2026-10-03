package com.mia.app.data.android.repo

import com.mia.app.data.symbols.ResolutionStatus
import com.mia.app.domain.model.ParserHealth
import com.mia.app.domain.model.Source
import java.time.LocalDate

/**
 * One listed instrument, as the search UI shows it.
 *
 * A local projection of `:data`'s `SymbolRecord` so `:app` never imports `:data`
 * (docs/CONTRACTS.md). Every field the UI renders is here; nothing else leaks.
 */
data class SymbolSummary(
    val symbol: String,
    val name: String,
    /** **The stable identity key** (plan.md §9). A rename changes [symbol], never [isin]. */
    val isin: String,
    val series: String,
    val listingDate: LocalDate?,
) {
    /** Only `EQ` is charted (plan.md §3.1). */
    val isEquity: Boolean get() = series == "EQ"

    fun listingLabel(): String = listingDate?.toString() ?: "unknown"
}

/** How a search query matched. Ordered best-first; the UI renders a chip for the non-exact ones. */
enum class SearchMatchKind {
    EXACT_TICKER,
    TICKER_PREFIX,
    TICKER_SUBSTRING,
    NAME_PREFIX,
    NAME_SUBSTRING,
}

/**
 * One search hit: the instrument, how it matched, and its 0-based rank.
 *
 * plan.md §9 requirement 5: search is **local, offline and instant**. Nothing in this call path
 * touches the network, which is also why it is robots.txt-irrelevant (plan.md §3.6 forbids
 * `?q=` searches and this app never issues one).
 */
data class SymbolHit(
    val summary: SymbolSummary,
    val kind: SearchMatchKind,
    val position: Int,
)

/**
 * The answer to "what did you mean, and how do you know?".
 *
 * plan.md §9 requirement 4 — *never return zero results silently*. [explanation] is required to
 * be non-blank by [ResolutionOutcome]'s own `init`, and [alternatives] is what makes even an
 * unresolved result useful: for `"ZZZZQQQ"` the app still shows the three nearest names with a
 * line saying it could not match, instead of an empty screen.
 */
data class ResolutionOutcome(
    /** Exactly what the user typed; the UI echoes it back. */
    val query: String,
    val status: ResolutionStatus,
    /** Chosen instrument, or `null` when [status] is `UNRESOLVED`. */
    val summary: SymbolSummary?,
    /** Plain language, safe to show verbatim. Never blank. */
    val explanation: String,
    val alternatives: List<SymbolSummary>,
    /** True when the app changed what the user typed — drives the "adjusted your search" chip. */
    val adjusted: Boolean,
    /** The corporate action behind an `ALIAS` result (plan.md §9 requirement 3), when known. */
    val corporateActionDetail: String? = null,
) {
    init {
        require(explanation.isNotBlank()) { "a resolution must explain itself (plan.md §9)" }
    }

    val isResolved: Boolean get() = summary != null
}

/**
 * Where the two identity sources stand, as the UI's "identity changes" screen shows it.
 *
 * Counts and a headline, not 1,000 rows: measured on the real pair (2,593 master rows against
 * the 2026-10-01 bhavcopy) 280 master symbols are absent from the filtered bhavcopy and 738
 * bhavcopy symbols are absent from the master, and a screen that rendered all of them would be
 * a wall of text the user cannot act on.
 */
data class MarketReconciliation(
    val asOf: LocalDate?,
    val masterCount: Int,
    val bhavcopyCount: Int,
    val matchedCount: Int,
    /** Symbols whose **identity** changed — small, actionable, and what can break a watchlist. */
    val changedIdentityCount: Int,
    /** One line, safe to show verbatim. */
    val headline: String,
    /** `true` when nothing is missing, changed or duplicated on either side. */
    val isClean: Boolean,
    /** Health of the *bhavcopy parse*, carried through so a degraded file cannot read as clean. */
    val bhavcopyHealth: ParserHealth,
)

/**
 * The user's tracked symbols. **Extension of plan.md §5.2**, required by decision OQ-4.
 */
data class WatchlistEntry(
    val summary: SymbolSummary,
    val addedOn: LocalDate,
    val alertsEnabled: Boolean,
)

/**
 * The only way `:app` reaches symbol identity.
 *
 * Everything is `suspend` and reads from the on-device store; the bundled master is parsed once
 * and cached in memory (plan.md §9 requirement 1) so a keystroke never waits on IO.
 */
interface SymbolRepository {

    /**
     * Loads (and caches) the bundled NSE symbol master.
     *
     * Returns a typed failure with [DataFailureReason.SYMBOL_BOOK_UNAVAILABLE] when the asset is
     * not bundled, rather than an empty search box — plan.md §9 forbids a blank screen.
     */
    suspend fun symbolBook(): DataResult<List<SymbolSummary>>

    /** Offline ranked search (plan.md §9 requirement 5). A blank query returns an empty list. */
    suspend fun search(query: String, limit: Int = DEFAULT_SEARCH_LIMIT): DataResult<List<SymbolHit>>

    /** Full resolution, including the explanation and near misses. */
    suspend fun resolve(query: String): DataResult<ResolutionOutcome>

    /** The stored record for an exact ticker, or a typed failure. */
    suspend fun record(symbol: String): DataResult<SymbolSummary>

    /** Today's comparison of the master against the rolling bhavcopy cache. */
    suspend fun reconciliation(): DataResult<MarketReconciliation>

    // ------------------------------------------------------------------ watchlist (OQ-4)

    suspend fun watchlist(): DataResult<List<WatchlistEntry>>

    /** Adds symbols to the watchlist, upserting their identity rows on the way in. */
    suspend fun addToWatchlist(symbols: List<String>): DataResult<List<WatchlistEntry>>

    suspend fun removeFromWatchlist(symbols: List<String>): DataResult<Int>

    suspend fun setAlertsEnabled(symbol: String, enabled: Boolean): DataResult<WatchlistEntry?>

    companion object {
        /** `SymbolBook.DEFAULT_SEARCH_LIMIT`'s value, restated so `:app` has one place to read. */
        const val DEFAULT_SEARCH_LIMIT: Int = 20
    }
}

/**
 * Where the identity data came from, for the provenance line.
 *
 * The bundled master is `NSE_EQUITY_MASTER` (plan.md §3.4); the daily comparison is
 * `NSE_BHAVCOPY` (plan.md §3.1).
 */
internal val SYMBOL_SOURCE: Source = Source.NSE_EQUITY_MASTER