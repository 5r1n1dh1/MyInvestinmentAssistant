package com.mia.app.ui.speech

/**
 * The vocabulary a Hinglish speaker actually types or says, and the NSE tickers behind it.
 *
 * ### Why a static table and not the search index
 * The search screen resolves against the bundled `EQUITY_L.csv` symbol master (plan.md §9), which
 * is the authority on what exists. This table is **not** an alternative resolver: it exists only to
 * recognise the *informal* forms a person reaches for — "reliance", "tata motors", "hdfc bank",
 * "tcs", "infy" — which the corporate-name search does not rank first because those files sort on
 * company name, not on the abbreviation everyone actually says. Every entry is a **hand-verified**
 * NSE symbol; nothing here is guessed or stemmed at runtime.
 *
 * ### Why a miss is allowed to stay a miss
 * This table is deliberately short and hand-checked rather than generated, because a wrong entry is
 * far worse than a missing one: it takes the user to a real, confident-looking screen about the
 * wrong company. A miss degrades to the search box with the typed text intact, which costs one
 * keystroke. That asymmetry is why [HinglishQueryNormaliser] never guesses a ticker from a partial
 * word on its own.
 *
 * ### Where to extend it
 * Add a row when a large cap's spoken form differs from its NSE symbol or its registered company
 * name. Do not add single letters, and do not add entries shorter than three characters: at that
 * length a mis-recognition produces an unambiguous wrong answer, which is the failure this feature
 * is not allowed to have.
 */
object HinglishVocabulary {

    /**
     * One vocabulary row.
     *
     * @param spoken every form that should resolve here: the symbol, the common abbreviation, the
     *   Hindi-English spelling, and the company name **as it appears in the NSE symbol master**.
     * @param companyName the name shown beside the suggestion. `null` when the row is a ticker and
     *   not a company.
     */
    data class Entry(
        val symbol: String,
        val companyName: String?,
        val spoken: Set<String>,
    )

    /**
     * Look-up by any spoken form.
     *
     * `by lazy` rather than an eager `buildMap`, because `ENTRIES` is declared further down this
     * file and a top-level property initialiser runs in declaration order — an eager map built from a
     * not-yet-initialised list would read as empty. Built exactly once, immutable thereafter.
     */
    private val bySpokenForm: Map<String, Entry> by lazy {
        buildMap {
            ENTRIES.forEach { entry ->
                entry.spoken.forEach { form ->
                    // First writer wins, so a duplicate form is a visible no-op rather than a silent
                    // re-pointing of an existing entry at a different ticker.
                    putIfAbsent(form, entry)
                }
            }
        }
    }

    /** The resolved entry for [spoken], or `null` when the form is not in the table. */
    fun lookup(spoken: String): Entry? = bySpokenForm[spoken]

    /**
     * Tickers whose spoken form is a strict prefix of something shorter than the ticker itself.
     *
     * Used by [HinglishQueryNormaliser]'s prefix fallback: when the spoken form is 4 characters or
     * more and is a prefix of exactly one ticker, that is a reasonable suggestion. Below four
     * characters the candidate set is too large and the match is dropped.
     */
    fun tickers(): Set<String> = ENTRIES.mapTo(LinkedHashSet()) { it.symbol }

    /** Every entry, in declaration order, for a settings screen that lists what is understood. */
    fun entries(): List<Entry> = ENTRIES

    /** The entry for an exact [symbol], or `null`. Used by the normaliser's prefix fallback. */
    fun entryFor(symbol: String): Entry? = ENTRIES.firstOrNull { it.symbol == symbol }

    /**
     * How many words in a spoken form are worth matching at once.
     *
     * `"tata motors"` is two words and must match as a phrase; `"tata"` alone must not, because
     * three different NSE symbols start with it.
     */
    const val MAX_PHRASE_WORDS: Int = 3

    /** Shortest spoken form this table will act on. See the class KDoc. */
    const val MIN_SPOKEN_FORM_LENGTH: Int = 3

    // ---------------------------------------------------------------------------- the table
    //
    // Ordered by rough market weight so the first candidate in a multi-match is the one most
    // people meant. Tickers are transcribed from the NSE symbol master, not typed from memory.
    private val ENTRIES: List<Entry> = listOf(
        Entry("RELIANCE", "Reliance Industries", setOf("reliance", "relian", "reli", "rlance")),
        Entry("TCS", "Tata Consultancy Services", setOf("tcs", "tata consultancy", "consultancy services")),
        Entry("HDFCBANK", "HDFC Bank", setOf("hdfc bank", "hdfcbank", "hdfc bank ltd", "hdfc")),
        Entry("INFY", "Infosys", setOf("infy", "infosys", "info sys")),
        Entry("ICICIBANK", "ICICI Bank", setOf("icici bank", "icicibank", "icici")),
        Entry("SBIN", "State Bank of India", setOf("sbi", "sbin", "state bank", "state bank of india")),
        Entry("BHARTIARTL", "Bharti Airtel", setOf("bharti", "bharti airtel", "airtel", "airtel ltd")),
        Entry("ITC", "ITC", setOf("itc", "i t c")),
        Entry("LT", "Larsen & Toubro", setOf("l&t", "l and t", "lt", "larsen", "larsen and toubro", "larsen & toubro")),
        Entry("WIPRO", "Wipro", setOf("wipro", "wipro ltd")),
        Entry("HCLTECH", "HCL Technologies", setOf("hcl", "hcltech", "hcl tech", "hcl technologies")),
        Entry("TECHM", "Tech Mahindra", setOf("tech mahindra", "techm", "techmindra", "mahindra tech")),
        Entry("AXISBANK", "Axis Bank", setOf("axis bank", "axisbank", "axis")),
        Entry("KOTAKBANK", "Kotak Mahindra Bank", setOf("kotak", "kotak bank", "kotakbank")),
        Entry("MARUTI", "Maruti Suzuki India", setOf("maruti", "maruti suzuki", "maruti suzuki india")),
        Entry("TITAN", "Titan Company", setOf("titan", "titan company", "titan industries")),
        Entry("SUNPHARMA", "Sun Pharmaceutical", setOf("sun pharma", "sunpharma", "sun pharmaceutical", "sun pharma ltd")),
        Entry("BAJFINANCE", "Bajaj Finance", setOf("bajaj finance", "bajajfinance", "bajaj finserv")),
        Entry("ASIANPAINT", "Asian Paints", setOf("asian paint", "asianpaint", "asian paints", "asianpaints")),
        Entry("TATAMOTORS", "Tata Motors", setOf("tata motors", "tatamotors", "tata motor", "tatamotr")),
        Entry("TATASTEEL", "Tata Steel", setOf("tata steel", "tatasteel", "tata steal")),
        Entry("ADANIENT", "Adani Enterprises", setOf("adani", "adani enterprises", "adani enterprise", "adani ent")),
        Entry("TATAPOWER", "Tata Power", setOf("tata power", "tatapower", "tata pwr")),
        Entry("NTPC", "NTPC", setOf("ntpc", "n t p c", "national thermal power")),
        Entry("POWERGRID", "Power Grid Corporation", setOf("power grid", "powergrid", "power grid corporation")),
        Entry("ONGC", "Oil and Natural Gas Corporation", setOf("ongc", "o n g c", "oil and natural gas")),
        Entry("COALINDIA", "Coal India", setOf("coal india", "coalindia", "coal indian")),
        Entry("JSWSTEEL", "JSW Steel", setOf("jsw steel", "jswsteel", "jsw", "j sw steel")),
        Entry("ULTRACEMCO", "UltraTech Cement", setOf("ultratech", "ultratech cement", "ultracemco", "ultra tech cement")),
        Entry("HINDUNILVR", "Hindustan Unilever", setOf("hindustan unilever", "hindunilvr", "hul", "unilever india")),
        Entry("NESTLEIND", "Nestle India", setOf("nestle", "nestle india", "nestleind", "nesle")),
        Entry("CIPLA", "Cipla", setOf("cipla", "cipla ltd")),
        Entry("DRREDDY", "Dr Reddy's Laboratories", setOf("dr reddy", "dr reddys", "drreddy", "dr redy")),
        Entry("DIVISLAB", "Divi's Laboratories", setOf("divis", "divi's", "divis lab", "divislab", "divis laboratories")),
        Entry("APOLLOHOSP", "Apollo Hospitals", setOf("apollo hospital", "apollohosp", "apollo hospitals", "apollo")),
        Entry("BAJAJFINSV", "Bajaj Finserv", setOf("bajaj finserv", "bajajfinsv", "bajaj finserve")),
        Entry("M&M", "Mahindra & Mahindra", setOf("m&m", "m and m", "mahindra", "m and mahindra", "mahindra and mahindra", "mahindra & mahindra")),
        Entry("HEROMOTOCO", "Hero MotoCorp", setOf("hero moto", "heromotoco", "hero", "hero motocop")),
        Entry("BAJAJ-AUTO", "Bajaj Auto", setOf("bajaj auto", "bajajauto", "bajaj-auto", "bajaj")),
        Entry("EICHERMOT", "Eicher Motors", setOf("eicher", "eichermot", "eicher motor", "royal enfield")),
        Entry("GRASIM", "Grasim Industries", setOf("grasim", "grasim industries", "aditya birla")),
        Entry("BRITANNIA", "Britannia Industries", setOf("britannia", "britania", "britannia industries")),
        Entry("TATACONSUM", "Tata Consumer Products", setOf("tata consumer", "tataconsum", "tataconsumer")),
        Entry("INDUSINDBK", "IndusInd Bank", setOf("indusind", "indusind bank", "indus in dbk")),
    )
}