package com.mia.app.data.symbols

import java.util.Locale

/**
 * Every spelling rule that turns what a human typed into a key the index can look up.
 *
 * **Why normalisation has to exist at all**: three independent spellings of the same company
 * are all correct in the wild, and the master file uses two of them.
 * - NSE itself writes `M&M`, `ARE&M`, `S&SPOWER`, `GVT&D`, `SURANAT&P` (verified: 10 of the
 *   2,593 master tickers contain `&`) and `BAJAJ-AUTO`, `BOSCH-HCIL`, `HCL-INSYS` (8 contain
 *   `-`). Stripping those characters for the *key* while keeping them in the stored ticker is
 *   what lets `BAJAJ-AUTO` and `BAJAJAUTO` find the same record.
 * - Indian users routinely type the Yahoo form (`RELIANCE.NS`) or add stray whitespace from
 *   voice input (`"reliance "`, plan.md §11 "Hinglish voice search"), so both are folded away.
 *
 * Two distinct keys are produced on purpose, because they are not the same lookup:
 * - [tickerKey] preserves `-` and `&`. It is the **exact/prefix/substring** search key, so a
 *   query for `M&M` ranks `M&M` first and a query for `MAHINDRA` still cannot be confused with
 *   it.
 * - [looseTickerKey] additionally removes them. It backs the normalisation *rung* of
 *   [SymbolResolver], where the user's spelling is known to be wrong but the intent is not.
 *
 * Collapsing `M&M` to `MM` and `M&MFIN` to `MMFIN` is lossy in principle; measured on the
 * real master, the two loose keys collide for **zero** of the 2,593 tickers, and
 * `SymbolBook` still records any collision in its health notes if a future master creates one.
 *
 * [Locale.ROOT] is mandatory rather than stylistic: the Turkish locale upper-cases `i` to
 * `İ`, so a device set to `tr-TR` would fail to find `ITC`. Same reason as
 * `NseBhavcopyParser.normaliseHeader`.
 */
object SymbolNormaliser {

    /**
     * Exchange suffixes NSE users paste from Yahoo/other terminals.
     *
     * `TATAMOTORS.NS` is not a ticker the master or the bhavcopy ever publishes, but it is
     * what a copied link or a voice search produces, and plan.md §3.2 confirms Yahoo uses
     * `{SYMBOL}.NS`. Stripping it is what turns `TATAMOTORS.NS` into a real resolution instead
     * of an unresolved one.
     */
    val EXCHANGE_SUFFIXES: List<String> = listOf(".NS", ".BO", ".BSE", ".NSE", ".L", ".IL")

    /** Ticker form used as the exact/prefix/substring search key. */
    fun tickerKey(raw: String): String = raw
        .trim()
        .let(::stripExchangeSuffix)
        .uppercase(Locale.ROOT)
        .replace(WHITESPACE, " ")
        .replace(MULTI_SPACE, " ")

    /**
     * Ticker form with **nothing** folded away except case and surrounding whitespace.
     *
     * This is what [SymbolBook.find] indexes on, and the distinction from [tickerKey] is
     * deliberate: a lookup that quietly drops a `.NS` suffix would answer `RELIANCE.NS` as
     * `ResolutionStatus.EXACT`, telling the user their input was already the ticker when it was
     * not. Callers that report *how* an answer was reached must use this strict key; callers that
     * only need the top hit may use [tickerKey].
     */
    fun strictTickerKey(raw: String): String = raw.trim().uppercase(Locale.ROOT)

    /**
     * Ticker form with every separator removed: `BAJAJ-AUTO` → `BAJAJAUTO`, `M&M` → `MM`.
     * Letters and digits only, so it can never contain a character the master lacks.
     */
    fun looseTickerKey(raw: String): String = tickerKey(raw).filter { it.isLetterOrDigit() }

    /**
     * Company name flattened to upper-case ASCII words.
     *
     * `&` becomes the word `AND` (not nothing) because that is how the master spells the
     * company anyway — `Mahindra & Mahindra Limited` — so name search finds it from both
     * `mahindra mahindra` and `mahindra and mahindra`. Every other punctuation mark becomes a
     * space so that `BAJAJ-AUTO LTD` and `BAJAJ AUTO LTD` are one key.
     */
    fun nameKey(raw: String): String = raw
        .uppercase(Locale.ROOT)
        .replace('&', ' ')
        .replace(AMPERSAND, " AND ")
        .filter { it.isLetterOrDigit() || it == ' ' }
        .replace(MULTI_SPACE, " ")
        .trim()

    /** Words of [nameKey], used for the token-overlap rung of [SymbolResolver]. */
    fun nameTokens(raw: String): List<String> {
        val key = nameKey(raw)
        return if (key.isEmpty()) emptyList() else key.split(' ')
    }

    /** An ISIN is `IN` + `E`/`B` + 9 alphanumerics + a check digit; we only need case folding. */
    fun isinKey(raw: String): String = raw.trim().uppercase(Locale.ROOT)

    /**
     * ISIN-shaped query test: 12 characters, no separators, starting `IN`.
     *
     * Used by [SymbolResolver] to decide whether a query *is* an ISIN rather than a mistyped
     * ticker. A bare `RELIANCE` is 8 characters, so the two paths cannot be confused.
     */
    fun looksLikeIsin(raw: String): Boolean {
        val key = isinKey(raw)
        return key.length == ISIN_LENGTH && key.startsWith("IN") && key.all { it.isLetterOrDigit() }
    }

    /** True when [raw] carried an exchange suffix that [tickerKey] had to remove. */
    fun strippedSuffix(raw: String): Boolean {
        val trimmed = raw.trim()
        return EXCHANGE_SUFFIXES.any { trimmed.length > it.length && trimmed.endsWith(it, ignoreCase = true) }
    }

    /**
     * Names exactly which folds turned [raw] into [ticker], for the resolver's explanation.
     *
     * **Why this is computed rather than described generically.** A resolver that always says
     * "after ignoring case and spacing" is a resolver whose explanation cannot be trusted to
     * describe what it did — and an explanation that over- or under-states the adjustment is worse
     * than none, because the user is being asked to accept it. So each fold is detected
     * independently:
     * - `"case"` when upper-casing alone produces the target;
     * - `"the exchange suffix"` when [strippedSuffix] is true;
     * - `"spacing and separators"` when the two differ only in characters removed by
     *   [looseTickerKey].
     *
     * Order is fixed so the phrase is stable across calls, and the result is never empty for a
     * [SymbolResolver] to render — a query that differs only by case yields exactly `["case"]`.
     */
    fun folds(raw: String, ticker: String): List<String> {
        val folded = mutableListOf<String>()
        if (strippedSuffix(raw)) folded += "the exchange suffix"
        if (raw.trim().uppercase(Locale.ROOT) != ticker) folded += "case"
        // Only claim spacing when the two actually differ in characters that folding removes.
        // `raw != raw.trim()` is the outer-whitespace case; a dash or `&` difference is the rest.
        // Without this guard "reliance" would be reported as having had its spacing ignored.
        if (raw != raw.trim() || looseTickerKey(raw) != ticker) folded += "spacing and separators"
        if (folded.isEmpty()) folded += "case"
        return folded
    }

    private fun stripExchangeSuffix(value: String): String {
        val trimmed = value.trim()
        return EXCHANGE_SUFFIXES.firstOrNull { trimmed.length > it.length && trimmed.endsWith(it, ignoreCase = true) }
            ?.let { trimmed.dropLast(it.length) }
            ?: trimmed
    }

    private val WHITESPACE = Regex("\\s+")
    private val MULTI_SPACE = Regex(" +")
    private val AMPERSAND = Regex("&")
    private const val ISIN_LENGTH = 12
}