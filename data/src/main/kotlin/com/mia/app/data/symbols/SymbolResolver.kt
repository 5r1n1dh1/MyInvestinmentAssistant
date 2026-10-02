package com.mia.app.data.symbols

import kotlin.math.max
import kotlin.math.min

/**
 * Turns anything a user types into a symbol that actually trades, and says what it did.
 *
 * **Why the ladder exists in this order** (plan.md §9 requirements 1–5): each rung costs more
 * certainty than the one before it, so the cheapest certain answer wins.
 * 1. `EXACT_TICKER` — nothing to explain, nothing to doubt.
 * 2. `NORMALISED_TICKER` — the intent was unambiguous; only the spelling was wrong. Folding
 *    `BAJAJ-AUTO`/`BAJAJAUTO`, `M&M`, `"reliance "` and `RELIANCE.NS` here is safe because a
 *    normalised key that exists in the master can only mean that one instrument.
 * 3. `ALIAS` — the ticker is *retired*. This rung is why the app does not show a blank screen
 *    for `TATAMOTORS`, the failure measured in plan.md §9.
 * 4. `ISIN_LOOKUP` — the caller already holds an ISIN (from a previous bhavcopy, plan.md §3.1),
 *    which is the strongest key there is; it is placed after the cheap string rungs because it
 *    costs nothing to try later.
 * 5. `FUZZY_TICKER` then `FUZZY_NAME` — a genuine guess, explicitly labelled as one.
 * 6. `UNRESOLVED` — an explicit answer with near misses, never an empty list and never a crash.
 *
 * **No network, no clock, no IO.** The class holds a [SymbolBook], an alias table and optionally
 * a [CorporateActionTracker]; that is the entire state. Nothing here can be slow or flaky, which
 * is what plan.md §9 requirement 5 ("instant, offline, robots.txt-compliant") reduces to.
 *
 * Immutable and thread-safe: `SymbolBook` is immutable and this class only reads it.
 */
class SymbolResolver(
    private val book: SymbolBook,
    /** Seeded + learned corporate actions. Later entries win, so a fresh detection overrides a guess. */
    private val aliases: List<CorporateActionAlias> = SymbolAliases.DEFAULT,
    /** Optional live memory, so the explanation can name the event detected *today*. */
    private val tracker: CorporateActionTracker? = null,
    /** Edit-distance ceiling; see [fuzzyTicker]. */
    private val maxEditDistance: Int = DEFAULT_MAX_EDIT_DISTANCE,
) {

    /**
     * Resolves [query], never to a silent empty list (plan.md §9 requirement 4).
     *
     * Never throws: a blank query, a 1-character query, a query of pure punctuation and a
     * nonsense query all come back as a [SymbolResolution] with a non-blank [explanation].
     */
    fun resolve(query: String): SymbolResolution {
        val original = query
        if (query.isBlank()) {
            return SymbolResolution.unresolved(original, "Type at least one character to search.")
        }

        // Rung 1 — the ticker byte-for-byte as published.
        //
        // **Deliberately stricter than [SymbolBook.find]**, which folds case and whitespace.
        // `ResolutionStatus.EXACT` is the user's signal that nothing was adjusted, and an
        // explanation that says "RELIANCE is an exact match for 'reliance '" would be a lie about
        // what the app did — while reporting every lowercase query as NORMALISED would be honest
        // and, for a search box, exactly right: the UI shows the "adjusted your search" chip for
        // the cases that genuinely involved a decision.
        book.find(query)?.takeIf { it.symbol == original }?.let { exact ->
            return SymbolResolution(
                query = original,
                status = ResolutionStatus.EXACT,
                record = exact,
                step = ResolutionStep.EXACT_TICKER,
                explanation = "${exact.symbol} is an exact match for \"$original\".",
                alternatives = alternativesFor(exact, limit = 4),
            )
        }

        // Rung 2 — the intent was right, the spelling was not.
        val normalisedKey = SymbolNormaliser.looseTickerKey(query)
        if (normalisedKey.isNotEmpty()) {
            book.findNormalised(query)?.let { normalised ->
                // Name only the folds that actually happened, so the explanation is a description of
                // this query rather than a generic paragraph. `"RELIANCE.NS"` must not be told its
                // "spacing and separators" were the issue when an exchange suffix was.
                // Compared against the *raw* query, not the key: `strictTickerKey("reliance")` is
                // already "RELIANCE", so comparing keys would report that no fold happened and the
                // explanation would understate what the app actually did to the user's input.
                val folds = SymbolNormaliser.folds(query, normalised.symbol)
                return SymbolResolution(
                    query = original,
                    status = ResolutionStatus.NORMALISED,
                    record = normalised,
                    step = ResolutionStep.NORMALISED_TICKER,
                    explanation = "Read \"$original\" as ${normalised.symbol} after ignoring " +
                        folds.joinToString(" and ") + ".",
                    alternatives = alternativesFor(normalised, limit = 4),
                )
            }
        }

        // Rung 3 — a retired ticker. A known alias beats a fuzzy guess: it is a fact, not a guess.
        aliasFor(query)?.let { alias ->
            val successor = book.find(alias.toSymbol) ?: book.findNormalised(alias.toSymbol)
            if (successor != null) {
                val event = tracker?.eventsFor(alias.fromSymbol)?.lastOrNull()
                val since = alias.effectiveFrom?.toString()
                val tail = since?.let { " (new listing from $it)" }.orEmpty()
                val siblings = alias.relatedSymbols.mapNotNull { book.find(it) }
                return SymbolResolution(
                    query = original,
                    status = ResolutionStatus.ALIAS,
                    record = successor,
                    step = ResolutionStep.ALIAS,
                    explanation = "${alias.fromSymbol} changed after the ${alias.event.lowercase()}" +
                        "$tail — showing ${successor.symbol}. ${alias.detail}" +
                        (event?.let { " Detected ${it.type} on ${it.detectedOn}." } ?: ""),
                    alternatives = siblings,
                    corporateAction = alias,
                )
            }
        }

        // Rung 4 — the caller brought an ISIN. Strongest key, tried last because it is rare.
        if (SymbolNormaliser.looksLikeIsin(query)) {
            book.findByIsin(query)?.let { byIsin ->
                return SymbolResolution(
                    query = original,
                    status = ResolutionStatus.EXACT,
                    record = byIsin,
                    step = ResolutionStep.ISIN_LOOKUP,
                    explanation = "${byIsin.symbol} matched by ISIN ${byIsin.isin} — the stable " +
                        "identity key, so this survives a ticker change (plan.md §9).",
                    alternatives = alternativesFor(byIsin, limit = 4),
                )
            }
        }

        // Rung 5a — nearest ticker, explicitly a guess.
        fuzzyTicker(query)?.let { (record, distance) ->
            return SymbolResolution(
                query = original,
                status = ResolutionStatus.FUZZY,
                record = record,
                step = ResolutionStep.FUZZY_TICKER,
                explanation = "No symbol matches \"$original\" exactly. Closest is ${record.symbol} " +
                    "($distance character${if (distance == 1) "" else "s"} apart).",
                alternatives = searchAlternatives(query, exclude = record.symbol),
            )
        }

        // Rung 5b — nearest company name, still explicitly a guess.
        fuzzyName(query)?.let { (record, score) ->
            return SymbolResolution(
                query = original,
                status = ResolutionStatus.FUZZY,
                record = record,
                step = ResolutionStep.FUZZY_NAME,
                explanation = "No ticker matches \"$original\". Closest company name is " +
                    "${record.name} (${record.symbol}), ${percent(score)} of its name words match.",
                alternatives = searchAlternatives(query, exclude = record.symbol),
            )
        }

        // Rung 6 — say so, and hand back the near misses rather than nothing.
        val near = searchAlternatives(query, exclude = null, limit = 5)
        return SymbolResolution.unresolved(
            query = original,
            explanation = buildString {
                append("No NSE symbol matches \"$original\"")
                if (book.size == 0) {
                    append(" — the symbol master is empty, so search cannot run")
                } else {
                    append(" (searched ${book.size} listed instruments)")
                }
                append(". ")
                if (near.isEmpty()) {
                    append("Try the ticker, e.g. RELIANCE, or the company name.")
                } else {
                    append("Closest: ${near.joinToString(", ") { "${it.symbol} (${it.name})" }}.")
                }
            },
            alternatives = near,
        )
    }

    /**
     * Ranked offline search (plan.md §9 requirement 5).
     *
     * Thin pass-through to [SymbolBook.search] so there is exactly **one** ranking
     * implementation in the app. Two rankings would drift, and the drift would be invisible.
     */
    fun search(query: String, limit: Int = SymbolBook.DEFAULT_SEARCH_LIMIT): List<SymbolSearchHit> =
        book.search(query, limit)

    /** The corporate-action alias for [query], if the ticker is a known retired one. */
    fun aliasFor(query: String): CorporateActionAlias? {
        val key = SymbolNormaliser.tickerKey(query)
        val loose = SymbolNormaliser.looseTickerKey(query)
        return aliases.lastOrNull { it.key == key }
            ?: aliases.lastOrNull { SymbolNormaliser.looseTickerKey(it.key) == loose }
    }

    /**
     * Nearest ticker by bounded Levenshtein distance.
     *
     * **Bounded on purpose.** An unbounded nearest-neighbour over 2,593 strings will always
     * return *something*, and `"ZZZZQQQ"` would come back as a confident-looking wrong answer —
     * precisely the silent-wrong-answer failure plan.md §9 forbids. So the ceiling is
     * `max(1, min(maxEditDistance, length / 4))`:
     * - queries shorter than [MIN_FUZZY_LENGTH] never fuzz (a 1–2 character query cannot
     *   discriminate between 2,593 tickers);
     * - a 7-character query may be 1 character wrong, which admits `"RELIANC"` → `RELIANCE`
     *   (distance 1) but not `"RELIANCEIND"` (11 characters, ceiling 2, distance 3);
     * - never more than [maxEditDistance], however long the query — a 20-character rewrite of a
     *   ticker is a different company, not a typo;
     * - ties break on the shorter ticker then alphabetically, so the result never depends on file
     *   order.
     *
     * Cost is `O(records × query length)` with the standard two-row DP — ~2,593 × 8 cells,
     * sub-millisecond, and it only runs after the four cheap rungs have already failed.
     *
     * Returns the record **and** the distance so the explanation can state how wrong the guess was.
     */
    private fun fuzzyTicker(query: String): Pair<SymbolRecord, Int>? {
        val key = SymbolNormaliser.tickerKey(query)
        if (key.length < MIN_FUZZY_LENGTH) return null
        val ceiling = max(MIN_FUZZY_DISTANCE, min(maxEditDistance, key.length / FUZZY_LENGTH_DIVISOR))

        var best: SymbolRecord? = null
        var bestDistance = ceiling + 1
        for (entry in book.searchKeys) {
            val record = entry.record
            // Length is a lower bound on distance, so most tickers are rejected without any DP.
            val lengthGap = kotlin.math.abs(entry.tickerKey.length - key.length)
            if (lengthGap > bestDistance - 1) continue
            val distance = levenshtein(key, entry.tickerKey, ceiling)
            if (distance < bestDistance ||
                (distance == bestDistance && best != null && record.symbol < best!!.symbol)
            ) {
                best = record
                bestDistance = distance
            }
            if (bestDistance == MIN_FUZZY_DISTANCE) break
        }
        return best?.let { it to bestDistance }
    }

    /**
     * Nearest company name by token overlap (Jaccard over the name's words).
     *
     * Complements [fuzzyTicker] for the input shape tickers cannot help with: a user who types
     * `"hdfc bank"` or `"axis bank"` is not one typo away from a ticker, they are describing the
     * company. `HDFC BANK LIMITED` scores 2/3 against `"hdfc bank"` — matched on the words the
     * user actually typed, not on a fuzzy spelling of a short symbol.
     */
    private fun fuzzyName(query: String): Pair<SymbolRecord, Double>? {
        val queryTokens = SymbolNormaliser.nameTokens(query).toSet()
        if (queryTokens.isEmpty()) return null

        var best: SymbolRecord? = null
        var bestScore = MIN_TOKEN_OVERLAP - 1e-9
        for (record in book.records) {
            val nameTokens = SymbolNormaliser.nameTokens(record.name).toSet()
            if (nameTokens.isEmpty()) continue
            val intersection = queryTokens.count { it in nameTokens }
            if (intersection == 0) continue
            val union = queryTokens.size + nameTokens.size - intersection
            val score = intersection.toDouble() / union
            if (score > bestScore || (score == bestScore && best != null && record.symbol < best.symbol)) {
                best = record
                bestScore = score
            }
        }
        return best?.takeIf { bestScore >= MIN_TOKEN_OVERLAP }?.let { it to bestScore }
    }

    /** [book.search] restricted to plausible alternates for an already-chosen record. */
    private fun alternativesFor(record: SymbolRecord, limit: Int): List<SymbolRecord> =
        book.search(record.symbol, limit = limit + 1)
            .map { it.record }
            .filter { it.symbol != record.symbol }
            .take(limit)

    private fun searchAlternatives(query: String, exclude: String?, limit: Int = 5): List<SymbolRecord> =
        book.search(query, limit = limit + 2)
            .map { it.record }
            .filter { it.symbol != exclude }
            .distinctBy { it.symbol }
            .take(limit)

    private fun percent(score: Double): String = "${(score * 100).toInt()}%"

    companion object {
        /** Below this, a query is not specific enough to guess with: `R` matches a hundred tickers. */
        const val MIN_FUZZY_LENGTH: Int = 3

        /** Best achievable distance; used to stop the scan early and as the sanity ceiling. */
        const val MIN_FUZZY_DISTANCE: Int = 1

        const val FUZZY_LENGTH_DIVISOR: Int = 4
        const val DEFAULT_MAX_EDIT_DISTANCE: Int = 2

        /** Share of a name's words that must match for [fuzzyName] to answer at all. */
        const val MIN_TOKEN_OVERLAP: Double = 0.34

        /**
         * Levenshtein distance with the standard two-row dynamic program.
         *
         * `maxEditDistance` is enforced as a **row floor**: once every cell in a row is above the
         * ceiling the answer cannot come back under it, so the scan stops. That keeps the fuzzy
         * rung's worst case bounded instead of quadratic across the whole book.
         */
        fun levenshtein(a: String, b: String, maxEditDistance: Int = Int.MAX_VALUE): Int {
            if (a == b) return 0
            if (a.isEmpty()) return b.length
            if (b.isEmpty()) return a.length
            if (kotlin.math.abs(a.length - b.length) > maxEditDistance) return maxEditDistance + 1

            var previous = IntArray(b.length + 1) { it }
            var current = IntArray(b.length + 1)
            for (i in 1..a.length) {
                current[0] = i
                var rowMin = current[0]
                val ca = a[i - 1]
                for (j in 1..b.length) {
                    val cost = if (ca == b[j - 1]) 0 else 1
                    current[j] = min(min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost)
                    if (current[j] < rowMin) rowMin = current[j]
                }
                if (rowMin > maxEditDistance) return maxEditDistance + 1
                val swap = previous
                previous = current
                current = swap
            }
            return previous[b.length]
        }
    }
}