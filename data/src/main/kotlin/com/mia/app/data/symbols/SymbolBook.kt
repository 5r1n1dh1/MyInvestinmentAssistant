package com.mia.app.data.symbols

import com.mia.app.domain.model.ParserHealth

/**
 * How a record matched a search query.
 *
 * **The order of this enum is the ranking.** Lower ordinal wins, which is why
 * [SymbolBook.search] sorts on it directly instead of on a hand-assigned weight: the ranking
 * required by plan.md §9 is then a single `sortedBy { it.kind.ordinal }` that cannot drift out
 * of order when someone adds a tier.
 */
enum class SearchMatchKind {
    /** The normalised ticker is character-for-character the query. `RELIANCE`. */
    EXACT_TICKER,

    /** The ticker starts with the query. `RELI` → `RELIANCE`. */
    TICKER_PREFIX,

    /** The ticker contains the query somewhere. `LIAN` → `RELIANCE`. */
    TICKER_SUBSTRING,

    /** The company name starts with the query. `reliance` → `Reliance Industries Limited`. */
    NAME_PREFIX,

    /** The company name contains the query. `bank` → `Axis Bank Limited`. */
    NAME_SUBSTRING,
}

/** One search result: the record, how it matched, and its 0-based rank position. */
data class SymbolSearchHit(
    val record: SymbolRecord,
    val kind: SearchMatchKind,
    /** 0-based position in the returned list, after ordering. Stable and asserted by tests. */
    val position: Int,
)

/**
 * Immutable, in-memory identity index built once at startup from the bundled master.
 *
 * **Why the whole market fits in memory and still needs care** (plan.md §13 flags 2 GB/2 GB-RAM
 * devices): 2,593 records at 183 KB of source is ~1 MB of heap including index overhead, so
 * there is no reason to reach for a database on the search path. That matters because
 * plan.md §9 requirement 5 says search must be **local, offline and instant**, and a Room query
 * per keystroke is neither instant nor something that can be guaranteed to work on a cold
 * offline launch. The database is for the *durable* identity (`symbols` table, `PK(isin)`);
 * this class is the *read* path.
 *
 * **No network, by construction.** There is no transport, no URL, no client and no clock in this
 * type or in anything it holds — the only inputs are the parsed records and the caller's query
 * string. That is what makes the search path robots.txt-irrelevant (plan.md §3.6 forbids
 * `?q=` searches, and a local index is the only search this app performs) and what lets
 * `SymbolBookOfflineTest` assert the absence structurally.
 *
 * Instances are immutable: the indexes are built once in the constructor and never mutated, so
 * `book` can be shared across threads and ViewModels without synchronisation.
 */
class SymbolBook private constructor(
    /** Every accepted record, sorted by ticker so iteration and golden assertions are stable. */
    val records: List<SymbolRecord>,
    /** Parse health of the master that produced this book, carried through for the UI. */
    val health: ParserHealth,
    private val byTicker: Map<String, SymbolRecord>,
    private val byIsin: Map<String, SymbolRecord>,
    private val byLooseTicker: Map<String, SymbolRecord>,
    /**
     * Pre-computed search keys, parallel to [records], so a keystroke costs one string compare
     * instead of four normalisations over 2,593 records.
     *
     * `internal` rather than private so [SymbolResolver]'s fuzzy rung compares against the same
     * normalised keys the search index uses — a second, differently-normalised key list is how two
     * components end up disagreeing about what a ticker is.
     */
    internal val searchKeys: List<SearchKey>,
) {

    /**
     * Per-record search keys.
     *
     * Held here rather than on [SymbolRecord] because they are an *index* concern: the record is
     * the data the UI shows, these are the data the search reads, and keeping them apart stops a
     * Room entity (`:android-data`, PK on ISIN) from having to grow three derived columns.
     */
    internal class SearchKey(
        val record: SymbolRecord,
        /** Ticker with the exchange suffix stripped, so a pasted `X.NS` still matches. */
        val tickerKey: String,
        val looseTickerKey: String,
        val nameKey: String,
        val nameTokens: Set<String>,
    )

    /** Number of listed instruments. 2,593 in the 2026-10 capture of the master. */
    val size: Int get() = records.size

    /**
     * Exact ticker lookup, case-insensitive and whitespace-trimmed. Deliberately *exact*: a
     * lookup that guessed would make "did we find this symbol or a near miss?" unanswerable,
     * which is the ambiguity plan.md §9 is trying to remove. Use [resolve] when guessing is
     * wanted, and it will say that it guessed.
     *
     * Nothing else is folded — not a `.NS` exchange suffix, not dashes — so `find("BAJAJ-AUTO")`
     * hits and `find("BAJAJAUTO")` does not. That is [findNormalised]'s job.
     */
    fun find(symbol: String): SymbolRecord? = byTicker[SymbolNormaliser.strictTickerKey(symbol)]

    /** Exact ISIN lookup — the stable identity path (plan.md §9 requirement 2/3). */
    fun findByIsin(isin: String): SymbolRecord? = byIsin[SymbolNormaliser.isinKey(isin)]

    /** All records, sorted by ticker. */
    fun all(): List<SymbolRecord> = records

    /** Series codes present in the master with counts, e.g. `EQ 2319, BE 248, BZ 26`. */
    fun seriesCounts(): Map<String, Int> = records.groupingBy { it.series }.eachCount()

    /**
     * Offline ranked search (plan.md §9 requirement 5).
     *
     * Ranking is exactly, in order: [SearchMatchKind.EXACT_TICKER] > [SearchMatchKind.TICKER_PREFIX]
     * > [SearchMatchKind.TICKER_SUBSTRING] > [SearchMatchKind.NAME_PREFIX] > [SearchMatchKind.NAME_SUBSTRING].
     * Within a tier the order is **shortest ticker, then alphabetical**, which is deterministic
     * and puts `RELIANCE` before `RELIANCEENERGY` for the query `RELIANCE` instead of depending
     * on file order.
     *
     * A blank query returns an empty list rather than the whole market: "show me everything" is
     * a browse action the UI triggers explicitly, and returning 2,593 rows for an empty box is
     * what makes a search box feel broken.
     *
     * Cost is one pass over [size] records with pre-computed keys — sub-millisecond on a budget
     * device, which is the whole point of doing this locally.
     */
    fun search(query: String, limit: Int = DEFAULT_SEARCH_LIMIT): List<SymbolSearchHit> {
        val key = SymbolNormaliser.tickerKey(query)
        if (key.isEmpty() || limit <= 0) return emptyList()
        val looseKey = SymbolNormaliser.looseTickerKey(query)
        val nameKey = SymbolNormaliser.nameKey(query)

        val matches = ArrayList<Pair<SymbolRecord, SearchMatchKind>>(16)
        for (entry in searchKeys) {
            val kind = rank(entry, key, looseKey, nameKey) ?: continue
            matches += entry.record to kind
        }

        matches.sortWith(
            compareBy<Pair<SymbolRecord, SearchMatchKind>> { it.second.ordinal }
                .thenBy { it.first.symbol.length }
                .thenBy { it.first.symbol },
        )

        val out = ArrayList<SymbolSearchHit>(minOf(limit, matches.size))
        for (i in matches.indices) {
            if (out.size >= limit) break
            val (record, kind) = matches[i]
            out += SymbolSearchHit(record, kind, out.size)
        }
        return out
    }

    /**
     * The single best match for [query], or null when nothing matched at all.
     *
     * Ranked exactly as [search]; only the top hit is returned.
     */
    fun bestMatch(query: String): SymbolSearchHit? = search(query, limit = 1).firstOrNull()

    /** Same lookup as [find], after the normalisation that cannot lose meaning (rung b of §9). */
    fun findNormalised(symbol: String): SymbolRecord? =
        byLooseTicker[SymbolNormaliser.looseTickerKey(symbol)]

    /**
     * Records whose company name **begins with** [name], matched on the normalised name.
     *
     * Separate from [search] because it answers a different question: not "what should I show as
     * the user types" but "which companies does this name belong to". Used for the confirmation
     * list under a search box, where showing a substring hit as though it were the same company
     * would be misleading.
     */
    fun findByNamePrefix(name: String): List<SymbolRecord> {
        val key = SymbolNormaliser.nameKey(name)
        if (key.isEmpty()) return emptyList()
        return searchKeys.filter { it.nameKey.startsWith(key) }.map { it.record }
    }

    /**
     * Resolves [query] all the way, never to a silent empty list (plan.md §9 requirement 4).
     *
     * This is a convenience for the default configuration (no learned corporate actions); the
     * EOD worker should construct its own [SymbolResolver] with the day's [CorporateActionTracker]
     * so the explanation can name the event it detected.
     */
    fun resolve(query: String): SymbolResolution = SymbolResolver(this).resolve(query)

    /**
     * Tier assignment for one entry. Null means "no match at all".
     *
     * Note the ticker tiers are tested against [key] (dashes and `&` preserved) and the name
     * tiers against [nameKey], so a query for `M&M` matches the ticker `M&M` exactly instead of
     * being ranked against `MMFIN`.
     */
    private fun rank(entry: SearchKey, key: String, looseKey: String, nameKey: String): SearchMatchKind? = when {
        entry.tickerKey == key || entry.looseTickerKey == looseKey -> SearchMatchKind.EXACT_TICKER
        entry.tickerKey.startsWith(key) -> SearchMatchKind.TICKER_PREFIX
        entry.tickerKey.contains(key) -> SearchMatchKind.TICKER_SUBSTRING
        nameKey.isNotEmpty() && entry.nameKey.startsWith(nameKey) -> SearchMatchKind.NAME_PREFIX
        nameKey.isNotEmpty() && entry.nameKey.contains(nameKey) -> SearchMatchKind.NAME_SUBSTRING
        else -> null
    }

    companion object {
        /** Android's `SearchView` default and a sane upper bound for a dropdown list. */
        const val DEFAULT_SEARCH_LIMIT: Int = 20

        /**
         * Builds the book.
         *
         * Ties in the loose-ticker index are resolved to the **first** record in ticker order
         * and reported through [health], because a silent choice would make two different
         * companies interchangeable. Measured on the real master: **zero** such collisions among
         * the 2,593 tickers, so the note is defensive rather than routine.
         */
        fun of(result: SymbolBookParseResult): SymbolBook {
            val sorted = result.records.sortedBy { it.symbol }
            val byTicker = LinkedHashMap<String, SymbolRecord>(sorted.size * 2)
            val byIsin = LinkedHashMap<String, SymbolRecord>(sorted.size * 2)
            val byLoose = LinkedHashMap<String, SymbolRecord>(sorted.size * 2)
            val entries = ArrayList<SearchKey>(sorted.size)
            val looseCollisions = mutableListOf<String>()
            val isinCollisions = mutableListOf<String>()

            for (record in sorted) {
                val tickerKey = SymbolNormaliser.strictTickerKey(record.symbol)
                // Search also indexes the separator-stripped form, so `M&M` still ranks as an exact
                // ticker for the query `M&M` (which folding would have turned into `MM`).
                val searchKey = SymbolNormaliser.tickerKey(record.symbol)
                val looseKey = SymbolNormaliser.looseTickerKey(record.symbol)
                val nameKey = SymbolNormaliser.nameKey(record.name)

                byTicker[tickerKey] = record
                // First ISIN wins, and in ticker order because [sorted] is sorted: two tickers
                // sharing one ISIN would otherwise merge into a single identity, which is precisely
                // the bug the ISIN key exists to prevent (plan.md §9).
                val isinKey = SymbolNormaliser.isinKey(record.isin)
                if (byIsin.containsKey(isinKey)) {
                    isinCollisions += record.symbol
                } else {
                    byIsin[isinKey] = record
                }
                if (byLoose.put(looseKey, record) != null) looseCollisions += record.symbol

                entries += SearchKey(
                    record = record,
                    tickerKey = searchKey,
                    looseTickerKey = looseKey,
                    nameKey = nameKey,
                    nameTokens = if (nameKey.isEmpty()) emptySet() else nameKey.split(' ').toSet(),
                )
            }

            val extraNotes = buildList {
                if (looseCollisions.isNotEmpty()) {
                    add("loose-ticker index collision(s), first ticker wins: ${looseCollisions.joinToString()}")
                }
                if (isinCollisions.isNotEmpty()) {
                    add("duplicate ISIN across tickers, first ticker wins: ${isinCollisions.joinToString()}")
                }
            }
            // An index collision makes an exact lookup ambiguous, so it must never read as healthy.
            val health = if (extraNotes.isEmpty()) {
                result.health
            } else {
                ParserHealth(
                    ok = false,
                    sectionsFound = result.health.sectionsFound,
                    sectionsMissing = result.health.sectionsMissing,
                    notes = result.health.notes + extraNotes,
                )
            }

            return SymbolBook(
                records = sorted,
                health = health,
                byTicker = byTicker,
                byIsin = byIsin,
                byLooseTicker = byLoose,
                searchKeys = entries,
            )
        }

        /** Builds a book straight from records; the health is then only about index collisions. */
        fun of(records: List<SymbolRecord>): SymbolBook = of(SymbolBookParseResult(records = records))
    }
}