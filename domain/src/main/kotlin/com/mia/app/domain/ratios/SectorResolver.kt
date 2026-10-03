package com.mia.app.domain.ratios

import com.mia.app.domain.model.Confidence
import com.mia.app.domain.model.Sector

/**
 * Resolves a company to a [Sector] — and, far more often, **declines to**.
 *
 * ### The rule this file exists to enforce
 * plan.md §1.4 rule 8 and §7.1: *"Default to `OTHER` and show a generic list if unresolved —
 * never guess."* So [resolve] returns `resolved = false` unless it matched a **positive
 * keyword**, and whenever two different sectors match it returns `OTHER` rather than picking
 * the one that looks nicer. A wrong sector is not a cosmetic bug here: §7.3 makes the sector
 * decide which ratios are even *displayed*, so a wrong guess silently swaps a bank's D/E for a
 * software company's gross margin.
 *
 * ### Three inputs, in strict precedence
 * 1. [resolve]'s `about` argument — screener.in's own industry sentence for the company.
 * 2. the company's display name (`<h1>`, e.g. `"Reliance Industries Ltd"`).
 * 3. [RatioShape] — the *structural* shape of the published statements.
 *
 * (1) and (2) are both positive keyword matches and are allowed to resolve on their own. (3)
 * is **corroboration only**: it can raise a single-source match from [Confidence.SINGLE] to
 * [Confidence.AGREED], but it can never resolve a name that no keyword matched. A statement
 * shape is an inference, and §7.1 forbids inference standing in for a classification.
 *
 * ### Specificity, then ambiguity
 * Matching is over **token sequences**, not substrings: input is upper-cased, punctuation is
 * split into spaces, and a keyword matches only when its whole token run appears contiguously.
 * That is why `AIRPORT` (AVIATION) cannot be found inside `ADANI PORTS` and why `PORTS`
 * (LOGISTICS) is a different keyword from `AIRPORT` — a substring matcher would classify
 * every port operator as an airline.
 *
 * Among the keywords that match, the **longest** ones win (`ADANI GREEN` beats a stray
 * `GREEN`), and only the keywords of that maximum length are considered. If that surviving
 * set still spans two sectors the result is `OTHER`, ambiguous. `LIC HOUSING FINANCE` is the
 * canonical case: it matches `HOUSING` (REALTY) and `FINANCE` (NBFC), and the app says so
 * rather than inventing a bucket for a company that genuinely is both.
 *
 * ### Confidence
 * [Confidence.AGREED] means the published industry text and the name pointed at the same
 * sector (or the name and the statement shape agreed). [Confidence.CONFLICT] means the two
 * texts pointed at **different** sectors — the sector stays `OTHER` and the evidence names
 * both matches. [Confidence.STALE] is never returned here: a sector is read from the page in
 * front of the user, so there is no older reading for it to be stale against; the field exists
 * so a [SectorResolution] carries the same provenance shape as every other value in the app.
 */
data class SectorResolution(
    val sector: Sector,
    /** `true` only for a positive keyword match. `false` always pairs with [Sector.OTHER]. */
    val resolved: Boolean,
    /** The keyword that matched, in words a user can check. Never empty prose. */
    val evidence: String,
    val confidence: Confidence,
) {
    init {
        require(resolved || sector == Sector.OTHER) {
            "an unresolved sector must stay OTHER (plan.md §7.1), was $sector"
        }
        require(evidence.isNotBlank()) { "a sector resolution must be explainable (plan.md §7.1)" }
    }

    /** True when the ratio list should use a sector override (§7.3) rather than the generic one. */
    val usesSectorOverrides: Boolean get() = resolved && sector != Sector.OTHER

    companion object {
        /** The plan-mandated default: no guess, generic list, explained. */
        fun unresolved(reason: String, confidence: Confidence = Confidence.SINGLE) =
            SectorResolution(Sector.OTHER, resolved = false, evidence = reason, confidence = confidence)
    }
}

/**
 * The structural shape of a company's published statements, used only to corroborate a
 * keyword match (see the class KDoc above).
 *
 * Only [hasDepositsRow] is used today, and only for [Sector.BANKING]: a `Deposits` line is the
 * one balance-sheet row that, structurally, only a bank publishes — verified on HDFCBANK,
 * whose collapsed balance sheet carries `Deposits` while every non-bank fixture carries none.
 * The other fields exist so a future caller can pass a richer shape without changing this
 * signature; unrecognised sectors simply get no corroboration.
 */
data class RatioShape(
    /** The balance sheet publishes a `Deposits` row. Only banks do. */
    val hasDepositsRow: Boolean = false,
    /** The balance sheet publishes a `Borrowings` / `Borrowing` row. */
    val hasBorrowingsRow: Boolean = false,
    /** The P&L publishes an `Operating Profit` row. Banks publish `Financing Profit` instead. */
    val hasOperatingProfitRow: Boolean = false,
    /** The P&L publishes an `Interest` row that is interest **expense**. */
    val hasInterestExpenseRow: Boolean = false,
) {
    /** True when [sector]'s override table is willing to trust this shape as a second opinion. */
    fun corroborates(sector: Sector): Boolean = when (sector) {
        Sector.BANKING -> hasDepositsRow
        else -> false
    }
}

/**
 * The sector vocabulary for the fundamentals tab (plan.md §7.1).
 *
 * The keyword table is data, not logic: [KEYWORDS] is a flat list of `(sector, phrases)` and
 * every behaviour below is derived from it, so adding a company is a table edit and adding a
 * sector is a table edit plus an enum constant. [Sector.OTHER] deliberately has no keywords —
 * it is the *answer*, not a bucket you can match into.
 */
object SectorResolver {

    /** One sector's keyword phrases, lower case, space separated, matched as token runs. */
    private class SectorKeywords(val sector: Sector, vararg phrases: String) {
        val phrases: List<String> = phrases.toList()
    }

    /**
     * Indian-context synonyms, one row per [Sector].
     *
     * Each phrase is a **token run** (see the class KDoc). Three naming conventions matter
     * and are respected by this table:
     *
     * * Indian financial year ends in **March**, so `"Mar 2019"` is the year column of a P&L.
     * * Company names carry the classification, not a ticker: `BAJAJ-AUTO` → `AUTO`,
     *   `PNB HOUSING` → `REALTY`, `TATA POWER` → `POWER`, `ADANI GREEN` → `RENEWABLE`.
     * * Several listed names carry no industry word at all (`LARSEN & TOUBRO`, `MOTHER DAIRY`
     *   is handled, `HDFC` post-merger is not), and those stay [Sector.OTHER] until the
     *   table grows. That is the intended failure mode: a generic list beats a wrong list.
     */
    private val KEYWORDS: List<SectorKeywords> = listOf(
        SectorKeywords(
            Sector.BANKING,
            "bank", "banks", "banking", "state bank", "federal bank", "commercial bank",
            "co operative bank", "cooperative bank", "regional rural bank", "small finance bank",
            "payment bank", "universal bank", "sbi",
        ),
        SectorKeywords(
            Sector.NBFC,
            "finance", "financial", "financing", "fina", "aakash", "muthoot", "manappuram",
        ),
        SectorKeywords(
            Sector.IT,
            "information technology", "it services", "technology", "technologies", "software",
            "computer", "computers", "consultancy services", "infosys",
        ),
        SectorKeywords(
            Sector.PHARMA,
            "pharma", "pharmaceutical", "pharmaceuticals", "drug", "drugs", "medicine",
            "medicines", "laboratories", "biotech", "biopharmaceutical", "vaccine", "vaccines",
        ),
        SectorKeywords(
            Sector.FMCG,
            "fmcg", "consumer goods", "fast moving consumer", "personal care", "personal wash",
            "household care", "detergent", "detergents", "soap", "soaps", "shampoo",
            "cosmetic", "cosmetics", "dentifrice", "toothpaste", "baby care", "dairy",
            // Names with no industry word. Added as whole-name phrases so that e.g.
            // "Tech Mahindra" cannot pick up an unrelated single-token keyword.
            "hindustan unilever", "nestle india", "dabur", "marico", "colgate", "britannia",
            "tata consumer", "godrej consumer", "emami",
        ),
        SectorKeywords(
            Sector.AUTO,
            "auto", "autos", "automobile", "automobiles", "automotive", "motor", "motors",
            "moto", "motorcycle", "motorcycles", "two wheeler", "two wheelers", "four wheeler",
            "four wheelers", "commercial vehicle", "commercial vehicles", "tyres", "tires",
            "maruti", "mahindra and mahindra", "ashok leyland", "motocorp",
        ),
        SectorKeywords(
            Sector.INSURANCE,
            "insurance", "life insurance", "general insurance", "insurer", "hdfc life",
            "sbi life", "icici prudential life", "lic",
        ),
        SectorKeywords(
            Sector.METALS,
            "steel", "steels", "metal", "metals", "aluminium", "aluminum", "alumina",
            "iron and steel", "pig iron", "ferrous", "non ferrous", "copper", "zinc", "lead",
            "nickel", "manganese", "hindalco", "vedanta",
        ),
        SectorKeywords(
            Sector.CEMENT,
            "cement", "cements", "dalbharat",
        ),
        SectorKeywords(
            Sector.POWER,
            "power", "electricity", "electric", "power grid", "powergrid", "generation",
            "thermal power", "coal india", "ntpc", "nhpc",
        ),
        SectorKeywords(
            Sector.RENEWABLE,
            "renewable", "renewables", "renewable energy", "hybrid renewable", "solar",
            "solar power", "solar energy", "wind power", "wind energy", "clean energy",
            "green energy", "green hydrogen", "adani green", "adani solar",
        ),
        SectorKeywords(
            Sector.DEFENCE,
            "defence", "defense", "aerospace", "aeronautics", "ordnance", "ordnance factory",
            "defence electronics", "bharat electronics", "hal", "bel", "bel india",
            "hindustan aeronautics",
        ),
        SectorKeywords(
            Sector.RAILWAY,
            "rail", "railway", "railways", "indian railways", "irtc", "irctc", "irfc", "concor",
            "rail tel", "railtel",
        ),
        SectorKeywords(
            Sector.REALTY,
            "realty", "real estate", "property", "properties", "housing", "construction",
            "dlf", "prestige",
        ),
        SectorKeywords(
            Sector.TELECOM,
            "telecom", "telecommunication", "telecommunications", "communication",
            "communications", "mobile", "airtel", "bharti", "telephone", "vodafone",
        ),
        SectorKeywords(
            Sector.AVIATION,
            "aviation", "airline", "airlines", "airport", "airports", "airways", "aero",
            "indigo", "air india",
        ),
        SectorKeywords(
            Sector.LOGISTICS,
            "logistics", "logistic", "port", "ports", "freight", "cargo", "shipping",
            "courier", "transport", "transports", "container", "containers", "cold chain",
            "warehousing", "adani ports", "blue dart",
        ),
        SectorKeywords(
            Sector.DURABLES,
            "durable", "durables", "consumer durable", "consumer durables", "home appliance",
            "home appliances", "appliance", "appliances", "air conditioner",
            "air conditioners", "white goods", "consumer electronics", "furniture",
            "titan company", "voltas", "havells", "blue star",
        ),
        SectorKeywords(
            Sector.RETAIL,
            "retail", "retailing", "retailer", "retailers", "e commerce", "ecommerce",
            "supermarket", "hypermarket", "apparel", "apparels", "footwear", "shoppers stop",
            "avenue supermarts",
        ),
        SectorKeywords(
            Sector.CAPITAL_GOODS,
            "capital goods", "capital equipment", "engineering", "heavy engineering",
            "industrial", "machinery", "machineries", "electrical equipment", "electricals",
            "infrastructure", "transformer", "transformers", "larsen and toubro",
        ),
        SectorKeywords(
            Sector.CHEMICALS,
            "chemical", "chemicals", "petrochemical", "petrochemicals", "agrochemical",
            "agrochemicals", "fertiliser", "fertilizer", "fertilisers", "speciality chemicals",
            "paints", "upl",
        ),
        // Sector.OTHER is intentionally absent: it is the default, never a match.
    )

    /** One keyword hit, kept for the evidence string. [start] is where the run began. */
    private data class Match(val sector: Sector, val phrase: String, val tokens: Int, val start: Int)

    /**
     * Display names for evidence copy.
     *
     * Hand-written because these strings end up in front of a user: `Sector.IT.name` is
     * `"IT"` and `Sector.AVIATION.name` is `"AVIATION"`, and naively title-casing an enum
     * constant produces `"It"` and `"Aviation"`. The evidence string is the app's explanation of
     * why it classified a company the way it did (plan.md §1.4 rule 3 in spirit), so it has to
     * read correctly.
     */
    private val SECTOR_LABELS: Map<Sector, String> = mapOf(
        Sector.BANKING to "Banking",
        Sector.NBFC to "NBFC (non-bank financial)",
        Sector.IT to "Information technology",
        Sector.PHARMA to "Pharma",
        Sector.FMCG to "FMCG (consumer goods)",
        Sector.AUTO to "Auto",
        Sector.INSURANCE to "Insurance",
        Sector.METALS to "Metals",
        Sector.CEMENT to "Cement",
        Sector.POWER to "Power",
        Sector.RENEWABLE to "Renewable energy",
        Sector.DEFENCE to "Defence",
        Sector.RAILWAY to "Railway",
        Sector.REALTY to "Realty",
        Sector.TELECOM to "Telecom",
        Sector.AVIATION to "Aviation",
        Sector.DURABLES to "Consumer durables",
        Sector.RETAIL to "Retail",
        Sector.LOGISTICS to "Logistics",
        Sector.CAPITAL_GOODS to "Capital goods",
        Sector.CHEMICALS to "Chemicals",
        Sector.OTHER to "Other",
    )

    /**
     * Resolves a company to a sector, or explains why it did not.
     *
     * @param name the company's display name, e.g. `"HDFC Bank Ltd"`. `null`/blank is fine.
     * @param about screener.in's industry sentence when one is available, e.g.
     *   `"HDFC Bank is an Indian banking company…"`. A positive match here resolves on its own.
     * @param shape the published statement shape, used only to corroborate.
     */
    fun resolve(
        name: String? = null,
        about: String? = null,
        shape: RatioShape = RatioShape(),
    ): SectorResolution {
        val nameMatches = matchesIn(name)
        val aboutMatches = matchesIn(about)

        if (nameMatches.isEmpty() && aboutMatches.isEmpty()) {
            return SectorResolution.unresolved(
                reason = "no sector keyword matched in " + describe(name, about) +
                    "; showing the generic ratio list (plan.md §7.1)",
            )
        }

        val named = distinctSectors(nameMatches)
        val described = distinctSectors(aboutMatches)

        // One text, two industries. The company genuinely spans both, so the ratio list cannot
        // be chosen: `LIC HOUSING FINANCE` is a lender *and* a real-estate business.
        if (named.size > 1 || described.size > 1) {
            return SectorResolution.unresolved(
                reason = describeMatches(nameMatches, "the company name") + " and " +
                    describeMatches(aboutMatches, "the published sector text") +
                    " disagree; showing the generic ratio list rather than picking one " +
                    "(plan.md §7.1, §7.3)",
            )
        }

        // Two texts, two industries. That is a classification disagreement between sources,
        // which plan.md §5.1 says to surface rather than resolve by preference.
        val both = (named + described).distinct()
        if (both.size > 1) {
            return SectorResolution.unresolved(
                reason = "the sources disagree — " + describeMatches(nameMatches, "the company name") +
                    " but " + describeMatches(aboutMatches, "the published sector text") +
                    "; showing the generic ratio list rather than picking one (plan.md §7.1, §5.1)",
                confidence = Confidence.CONFLICT,
            )
        }

        val sector = both.single()
        val corroboratedBy = shape.takeIf { it.corroborates(sector) }
        val agreement = when {
            named.isNotEmpty() && described.isNotEmpty() -> Confidence.AGREED
            corroboratedBy != null -> Confidence.AGREED
            else -> Confidence.SINGLE
        }
        return SectorResolution(
            sector = sector,
            resolved = true,
            evidence = evidenceFor(sector, nameMatches, aboutMatches) +
                (corroboratedBy?.let { "; confirmed by the published statement shape" } ?: ""),
            confidence = agreement,
        )
    }

    /** The keyword phrases registered for [sector], for diagnostics and tests. */
    fun keywordsFor(sector: Sector): List<String> =
        KEYWORDS.filter { it.sector == sector }.flatMap { it.phrases }.sorted()

    /** Every keyword in the table, for the "is this sector reachable at all?" test. */
    fun allKeywords(): Map<Sector, List<String>> = KEYWORDS.associate { it.sector to it.phrases }

    /** Human-readable sector name for user-facing copy. `"IT"`, not `"It"`. */
    fun displayNameOf(sector: Sector): String = SECTOR_LABELS[sector] ?: sector.name

    // -------------------------------------------------------------------------- matching

    /**
     * Every keyword run found in [text], keeping only the **most specific** of them.
     *
     * A longer token run is a more specific statement about the company: in
     * `"Adani Green Energy Ltd"`, `ADANI GREEN` (2 tokens) is the classification, and a
     * one-token keyword elsewhere in the same name would be an accident of wording. Only the
     * longest matches survive; if they still disagree, the caller turns that into
     * [Sector.OTHER].
     */
    private fun matchesIn(text: String?): List<Match> {
        val tokens = tokenize(text)
        if (tokens.isEmpty()) return emptyList()
        val hits = mutableListOf<Match>()
        for (entry in KEYWORDS) {
            for (phrase in entry.phrases) {
                // Tokens are upper-cased by [tokenize], so the phrase has to be too.
                val parts = phrase.uppercase().split(' ').filter { it.isNotBlank() }
                if (parts.isEmpty()) continue
                val start = indexOfRun(tokens, parts) ?: continue
                hits += Match(entry.sector, phrase, parts.size, start)
            }
        }
        val longest = hits.maxOfOrNull { it.tokens } ?: return emptyList()
        return hits.filter { it.tokens == longest }
    }

    /** The distinct sectors a set of matches points at. */
    private fun distinctSectors(matches: List<Match>): List<Sector> =
        matches.map { it.sector }.distinct()

    /**
     * Upper-cased tokens, with `&` expanded to `AND` and every other separator dropped.
     *
     * `&` is expanded rather than dropped so that `"Mahindra & Mahindra"` and
     * `"Larsen & Toubro"` read the way a human reads them — the keyword table matches
     * `"mahindra and mahindra"` and `"larsen and toubro"`, and a bare token run across the
     * dropped ampersand would be a spelling nobody else would write.
     */
    private fun tokenize(text: String?): List<String> =
        text.orEmpty()
            .uppercase()
            .replace("&", " AND ")
            .split(' ', '.', ',', '/', '(', ')', '-', '+', '–', '—', '\'', ':', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    /** Start index of [needle]'s first contiguous run inside [haystack], or `null`. */
    private fun indexOfRun(haystack: List<String>, needle: List<String>): Int? {
        if (needle.size > haystack.size) return null
        for (start in 0..(haystack.size - needle.size)) {
            var hit = true
            for (offset in needle.indices) {
                if (haystack[start + offset] != needle[offset]) {
                    hit = false
                    break
                }
            }
            if (hit) return start
        }
        return null
    }

    // ------------------------------------------------------------------------ evidence

    /**
     * Evidence names the keyword that appears **first** in the text, which is the one the
     * reader will find when they look for it: `"Adani Green Energy Ltd"` is described by
     * `adani green`, not by the later `green energy` that matched just as specifically.
     */
    private fun evidenceFor(sector: Sector, name: List<Match>, about: List<Match>): String {
        val fromName = name.filter { it.sector == sector }.minByOrNull { it.start }
        val fromAbout = about.filter { it.sector == sector }.minByOrNull { it.start }
        return when {
            fromName != null && fromAbout != null ->
                "matched \"${fromAbout.phrase}\" in the published sector text and " +
                    "\"${fromName.phrase}\" in the company name"
            fromAbout != null -> "matched \"${fromAbout.phrase}\" in the published sector text"
            else -> "matched \"${fromName?.phrase}\" in the company name"
        }
    }

    private fun describeMatches(matches: List<Match>, where: String): String {
        if (matches.isEmpty()) return "$where matched nothing"
        val bySector = matches.groupBy { it.sector }
        return "$where matched " + bySector.entries.joinToString(" and ") { (sector, hits) ->
            "${sector.display()} (${hits.map { "\"${it.phrase}\"" }.joinToString(", ")})"
        }
    }

    private fun describe(name: String?, about: String?): String = when {
        !name.isNullOrBlank() && !about.isNullOrBlank() ->
            "the company name \"${name.trim()}\" or the published sector text \"${about.trim()}\""
        !name.isNullOrBlank() -> "the company name \"${name.trim()}\""
        !about.isNullOrBlank() -> "the published sector text \"${about.trim()}\""
        else -> "anything (no company name and no sector text were provided)"
    }

    private fun Sector.display(): String = SECTOR_LABELS[this] ?: name
}