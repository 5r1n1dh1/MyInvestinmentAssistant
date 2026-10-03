package com.mia.app.ui.search

import com.mia.app.domain.model.Source
import javax.inject.Qualifier
import java.time.LocalDate

/**
 * The dispatcher the Search screen's parsing and ranking run on.
 *
 * Declared here for the same reason `TechnicalsBackgroundDispatcher` is declared in
 * `ui/technicals`: `:android-data` has its own IO dispatcher, but a bundled 2,593-row CSV parse and
 * a linear scan over it are CPU work with no IO, so `Dispatchers.Default` is the honest pool and
 * borrowing a module that is written in parallel would couple this file to an uncompilable one.
 *
 * Search must feel instant (plan.md §9 requirement 5), so the ViewModel keeps the parsed book in
 * memory and only pays this dispatcher once per process.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SearchBackgroundDispatcher

/**
 * One listed instrument, as the search list shows it.
 *
 * ### Why this type lives in `:app` and not `:domain`
 * plan.md §9 is explicit that **a ticker is not an identity** — `TATAMOTORS` returns zero rows on
 * Yahoo *and* is absent from the official bhavcopy because it demerged — and the fix is to carry the
 * ISIN. `:domain` has no symbol type today, so this screen declares the slice it renders and the
 * adapter maps `:data`'s `SymbolRecord` onto it. If the orchestrator later promotes it to
 * `domain/model/Market.kt`, only the package of this declaration changes.
 *
 * ### Why it is imported by `ui/stocklist` and `ui/detail`
 * Because it is *the* app-level identity projection, there is exactly one declaration of it. A
 * second, near-identical copy in the watchlist would be the same ratio-divergence mistake
 * plan.md §5.1 warns about, applied to identity: two definitions of a ticker is two answers to
 * "which company is this?".
 */
data class SymbolSummary(
    /** NSE ticker, upper-cased and trimmed. `RELIANCE`, `BAJAJ-AUTO`, `M&M`. */
    val symbol: String,
    /** `NAME OF COMPANY` verbatim, e.g. `Reliance Industries Limited`. */
    val name: String,
    /** NSE series code: `EQ` (equity), `SM` (small market), `BE` (book entry). plan.md §3.1. */
    val series: String,
    /** ISIN — **the stable identity key** (plan.md §9). A rename changes [symbol], never this. */
    val isin: String,
    /** `DATE OF LISTING`, when the master published one. */
    val listingDate: LocalDate? = null,
) {
    init {
        require(symbol.isNotBlank()) { "a symbol summary without a ticker has no identity" }
    }

    /**
     * Only `EQ` and `SM` are priceable (plan.md §3.1: "Keep `EQ` + `SM`. Drop everything else").
     * `BE` and the odd-lot series have no bhavcopy row, so opening one would show a symbol whose
     * every price is empty.
     */
    val isPriceable: Boolean get() = series == SERIES_EQUITY || series == SERIES_SM

    /** Plain listing label. Never used for maths — only for "is this a new listing" messaging. */
    fun listingLabel(): String = listingDate?.toString() ?: "unknown"

    companion object {
        const val SERIES_EQUITY: String = "EQ"
        const val SERIES_SM: String = "SM"
    }
}

/**
 * How a query matched a record, ordered best-first.
 *
 * ### Why the order is data
 * plan.md §9 requirement 5 wants ranked, instant, local search, and the ranking the reader expects
 * for a ticker box is *exact > prefix > substring > name*. Making [rank] a field rather than relying
 * on the adapter's `sortedBy` means `SearchViewModel` can re-assert the order in one place, and the
 * order is legible in review instead of being implied by a comparator three files away.
 */
enum class SymbolMatchKind(val rank: Int) {
    /** The query is the ticker. Always rank 0, whatever else also matched. */
    EXACT_TICKER(0),

    /** The ticker starts with the query. */
    TICKER_PREFIX(1),

    /** The ticker contains the query somewhere. */
    TICKER_SUBSTRING(2),

    /** The company name starts with the query. */
    NAME_PREFIX(3),

    /** The company name contains the query. */
    NAME_SUBSTRING(4),
}

/**
 * One search hit.
 *
 * [position] is the adapter's own 0-based position so a stable-key list can keep scroll position
 * across a refresh; the *rendered* order is re-derived from [kind] so the ranking rule above is
 * enforced in one place rather than trusted from the data layer.
 */
data class SymbolMatch(
    val summary: SymbolSummary,
    val kind: SymbolMatchKind,
    val position: Int = 0,
)

/**
 * The answer to "what did you mean, and how do you know?" — plan.md §9 requirement 4.
 *
 * ### The rule this type exists to enforce
 * *"Never return zero results silently — always resolve to the nearest valid symbol and tell the
 * user what happened."* A nullable `SymbolSummary?` cannot satisfy that, because a null carries no
 * explanation and no near misses; the `init` block makes [explanation] non-blank so the "why" cannot
 * be lost by a careless call site, exactly as `:data`'s own `SymbolResolution` does.
 *
 * @param status why the app landed on [summary]. Anything other than `EXACT` renders the
 *   "we adjusted your search" treatment, because a silent fuzzy match is indistinguishable from a
 *   correct one.
 * @param alternatives ranked near misses. What makes even an unresolved query useful: `"ZZZZQQQ"`
 *   still shows the three closest instruments *and* says it could not match.
 * @param corporateActionDetail the event behind an `ALIAS` result (plan.md §9 requirement 3), e.g.
 *   the TATAMOTORS demerger. `null` when no corporate action is known — never a guess.
 */
data class SymbolResolution(
    /** Exactly what the caller typed. The UI echoes this, never the normalised form. */
    val query: String,
    /** The chosen instrument, or `null` when nothing matched closely enough. */
    val summary: SymbolSummary?,
    /** Why [summary] was chosen. Never blank; show it verbatim. */
    val explanation: String,
    /** Ranked near misses, most likely first. Empty only when the book itself is empty. */
    val alternatives: List<SymbolSummary> = emptyList(),
    /** The corporate action behind a retired-ticker answer, when one is known. */
    val corporateActionDetail: String? = null,
) {
    init {
        require(explanation.isNotBlank()) {
            "every symbol resolution must explain itself — a silent answer is the failure " +
                "plan.md §9 forbids"
        }
    }

    val isResolved: Boolean get() = summary != null

    /**
     * True when the app had to change what the user typed.
     *
     * Derived from [isResolved] and the query rather than from a status enum, because the only
     * distinction the search UI actually draws is "did you get what I typed" — a screen that
     * showed five different "kinds of close" would be noise, while showing nothing about an
     * adjusted search is the untrustworthiness §9 was written to remove.
     */
    val wasAdjusted: Boolean get() = summary != null && summary.symbol != query.trim().uppercase()

    companion object {
        /** The only legal way to build a "nothing matched" result. */
        fun unresolved(
            query: String,
            explanation: String,
            alternatives: List<SymbolSummary> = emptyList(),
        ): SymbolResolution = SymbolResolution(
            query = query,
            summary = null,
            explanation = explanation,
            alternatives = alternatives,
        )
    }
}

/**
 * The exception every [SearchDataSource] implementation throws on a failed read.
 *
 * ### Why a declared type rather than any `Throwable`
 * plan.md §12: *"A parser must return a health flag, not an empty list. Silent-empty is
 * indistinguishable from 'no red flags' — the most dangerous possible failure."* The same applies
 * here: an implementation that returned an empty result list for a missing `assets/EQUITY_L.csv`
 * would render a search box that finds nothing for every query, which reads as "this app has no
 * symbol list". Throwing routes the screen to `ErrorState` with a retry, which is the truth.
 *
 * @param reason the sentence shown to the user. Must already be plain language.
 * @param isTransient whether a retry button is worth offering.
 */
class SearchUnavailableException(
    val reason: String,
    val isTransient: Boolean = true,
) : Exception(reason)

/**
 * The Search screen's entire view of the outside world.
 *
 * ### Why this interface exists in `:app` and not in `:android-data`
 * plan.md §4.3 forbids `:app` from importing `:data`, and every read here is over the **bundled**
 * `EQUITY_L.csv` (plan.md §9 requirement 1). This interface is the seam: it speaks only in
 * `:domain` types and app-owned projections, so `SearchViewModel` is compilable and reviewable on
 * its own and the adapter that satisfies it is a single small class.
 *
 * ### Contract
 * * Every function is `suspend` and must already be off the main thread — the caller wraps the load
 *   in [SearchBackgroundDispatcher].
 * * **Nothing here may touch the network.** plan.md §3.6 disallows `?q=` searches against
 *   screener.in and plan.md §9 requirement 5 says search is local; the app never issues either.
 * * `null` / empty means **"there is nothing", not "we failed"**. A function that *fails* throws
 *   [SearchUnavailableException].
 * * [search] must return results already truncated to [limit] but **not** necessarily pre-sorted by
 *   rank: the ViewModel re-sorts by [SymbolMatchKind.rank] so the ranking rule is enforced here.
 */
interface SearchDataSource {

    /**
     * The bundled NSE symbol master, parsed once and cached in memory.
     *
     * The ViewModel calls this to build its in-memory index, then answers every keystroke from that
     * index, which is what makes search instant (plan.md §9 requirement 5) and what keeps a
     * 2,593-row CSV parse off the typing path.
     *
     * @throws SearchUnavailableException when the asset is not bundled or cannot be parsed. plan.md
     *   §9 forbids degrading to an empty search box.
     */
    suspend fun symbolBook(): List<SymbolSummary>

    /**
     * Ranked hits for [query], best first, truncated to [limit].
     *
     * A blank query returns an empty list — "no query" is not an error and must not trigger the
     * resolved-solution path, which exists for a *typed* ticker that does not exist.
     */
    suspend fun search(query: String, limit: Int = DEFAULT_RESULT_LIMIT): List<SymbolMatch>

    /**
     * Full resolution for [query], including the explanation and the near misses.
     *
     * This is plan.md §9 requirement 4's mechanism: when [search] comes back empty for a
     * well-formed ticker, this call still produces an answer and a sentence explaining it, so the
     * screen can render "TATAMOTORS changed after the demerger — showing TMCV" instead of a blank.
     */
    suspend fun resolve(query: String): SymbolResolution

    /**
     * Adds [symbol] to the tracked list (plan.md decision OQ-4).
     *
     * Returns the stored summary on success so the caller can show the identity it just persisted,
     * rather than assuming the ticker it was handed is the one on disk.
     *
     * @return `null` when the symbol is not in the bundled master and therefore cannot be tracked.
     *   "Not tracked" is a real answer; it is not an error and must not read as one.
     */
    suspend fun addToWatchlist(symbol: String): SymbolSummary?

    companion object {
        /**
         * How many hits a keystroke asks for.
         *
         * 30, not 2,593: plan.md §12 targets 2 GB-RAM devices and a phone list has room for about a
         * dozen rows before the user scrolls past them. Beyond that the results are unread, not
         * merely long.
         */
        const val DEFAULT_RESULT_LIMIT: Int = 30
    }
}

/**
 * Provenance of a symbol row, for the header line under the search box.
 *
 * plan.md §3.4: the bundled master is `NSE_EQUITY_MASTER`. Stated here rather than imported so the
 * search screen never has to reach into another module for a constant it already knows.
 */
val SYMBOL_BOOK_SOURCE: Source = Source.NSE_EQUITY_MASTER