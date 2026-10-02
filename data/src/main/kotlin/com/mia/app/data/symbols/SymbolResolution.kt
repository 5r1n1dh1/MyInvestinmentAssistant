package com.mia.app.data.symbols

/**
 * How a [SymbolResolution] was reached.
 *
 * **The user-facing verb**, not the mechanism: `EXACT` means "you typed the ticker and got the
 * ticker", `FUZZY` means "your spelling was wrong and here is what we think you meant". Those
 * two must be distinguishable by the UI, because a silent fuzzy match is indistinguishable from
 * a correct one and that is exactly the untrustworthiness plan.md §9 was written to remove.
 */
enum class ResolutionStatus {
    /** The query was already the ticker. */
    EXACT,

    /** The query was the ticker modulo case, whitespace, dashes, `&` or an exchange suffix. */
    NORMALISED,

    /** The ticker is a **known corporate-action predecessor** (e.g. `TATAMOTORS` after demerger). */
    ALIAS,

    /** A near match was chosen (edit distance on the ticker, or token overlap on the name). */
    FUZZY,

    /** Nothing matched closely enough. [SymbolResolution.alternatives] carries the near misses. */
    UNRESOLVED,
}

/**
 * Which rung of the escalation ladder fired.
 *
 * Kept separate from [ResolutionStatus] because two rungs are equally *exact* to the user while
 * being different mechanisms: an ISIN handed over from a previous bhavcopy is a precise
 * identity match, not a ticker match, and the diagnostics need to say which one answered.
 */
enum class ResolutionStep {
    /** Query equals a published ticker. */
    EXACT_TICKER,

    /** Query matched after case/whitespace/dash/`&`/exchange-suffix folding. */
    NORMALISED_TICKER,

    /** Query is a known corporate-action predecessor of a live ticker. */
    ALIAS,

    /** Query is an ISIN and an ISIN-keyed record was found. */
    ISIN_LOOKUP,

    /** Nearest ticker by bounded Levenshtein distance. */
    FUZZY_TICKER,

    /** Nearest company name by token overlap. */
    FUZZY_NAME,

    /** Nothing matched; [SymbolResolution.alternatives] holds the near misses. */
    UNRESOLVED,
}

/**
 * The answer to "what did you mean, and how do you know?".
 *
 * **Why this is a value and not a `SymbolRecord?`.** plan.md §9 requirement 4 is *never return
 * zero results silently*. A nullable record cannot satisfy that, because a null carries no
 * explanation, no alternatives and no audit trail — and the `init` block below enforces it:
 * [explanation] can never be blank, so the "why" cannot be lost by a careless call site.
 *
 * [alternatives] is what makes even [ResolutionStatus.UNRESOLVED] useful: for `"ZZZZQQQ"` the
 * app still shows the three nearest names with a line saying it could not match, instead of an
 * empty screen.
 *
 * [corporateAction] links the resolution to the event that caused it, so the UI can render the
 * event's detail (plan.md §9 requirement 3) without re-deriving it.
 */
data class SymbolResolution(
    /** Exactly what the caller asked, never the normalised form — the UI echoes user input. */
    val query: String,
    val status: ResolutionStatus,
    /** The chosen record. Null only when [status] is [ResolutionStatus.UNRESOLVED]. */
    val record: SymbolRecord?,
    val step: ResolutionStep,
    /** One sentence, plain language, safe to show verbatim. Never blank. */
    val explanation: String,
    /** Ranked near misses, most likely first. Empty only when the book itself is empty. */
    val alternatives: List<SymbolRecord> = emptyList(),
    /** The corporate action behind an [ResolutionStatus.ALIAS] result, when one is known. */
    val corporateAction: CorporateActionAlias? = null,
) {
    init {
        require(explanation.isNotBlank()) {
            "every resolution must explain itself — a silent answer is the failure plan.md §9 forbids"
        }
        require(status != ResolutionStatus.UNRESOLVED || record == null) {
            "an UNRESOLVED result must not carry a record"
        }
    }

    val isResolved: Boolean get() = record != null

    /** The chosen ticker, or null when unresolved. */
    val symbol: String? get() = record?.symbol

    /** True when the app had to change what the user typed. Drives the "we adjusted your search" chip. */
    val isAdjusted: Boolean get() = status != ResolutionStatus.EXACT

    companion object {
        /**
         * The only legal way to build an unresolved result. Routing every "nothing matched" path
         * through one factory is what stops an empty explanation from ever reaching the UI.
         */
        fun unresolved(query: String, explanation: String, alternatives: List<SymbolRecord> = emptyList()) =
            SymbolResolution(
                query = query,
                status = ResolutionStatus.UNRESOLVED,
                record = null,
                step = ResolutionStep.UNRESOLVED,
                explanation = explanation,
                alternatives = alternatives,
            )
    }
}

/**
 * A published, evidence-backed mapping from a **retired ticker** to its live successor.
 *
 * **Why a hand-curated table at all**, when [CorporateActionTracker] detects events from data:
 * a tracker only learns about a demerger *after* it has seen the event, and the first user who
 * types the old ticker may be typing it precisely because their watchlist predates the event.
 * The table is the cold-start memory; the tracker is the live memory. Both are merged by
 * [SymbolResolver], and the table stays small and cited rather than speculative — every entry
 * names the event and the evidence it came from, so a wrong entry is obvious in review.
 *
 * [effectiveFrom] is the successor's listing date, which is what the user-facing explanation
 * quotes ("new listing from 2025-11-12"). It is *not* the event date; the two differ and only
 * the former is verifiable from the master file.
 */
data class CorporateActionAlias(
    /** Ticker that no longer trades — what the user types. `TATAMOTORS`. */
    val fromSymbol: String,
    /** Ticker that trades today. `TMCV`. */
    val toSymbol: String,
    /** Human event name, e.g. `DEMERGER`. */
    val event: String,
    /** Successor's first listing date as published in the master, when known. */
    val effectiveFrom: java.time.LocalDate? = null,
    /**
     * Other live tickers the user may have meant — the other leg of a demerger.
     *
     * Surfaced as [SymbolResolution.alternatives] so a user who wanted the Passenger Vehicles
     * entity finds it instead of being handed only the other leg.
     */
    val relatedSymbols: List<String> = emptyList(),
    /** One sentence naming what happened, including any sibling entity. */
    val detail: String,
) {
    /** Upper-cased [fromSymbol] for direct index lookup. */
    val key: String get() = SymbolNormaliser.tickerKey(fromSymbol)
}

/**
 * The seeded corporate-action memory.
 *
 * **Evidence rule**: an entry is added only when the repo's own captured data supports it, and
 * the entry's `detail` says which file. Adding a plausible-but-unverified alias is the failure
 * mode this project cannot afford — a wrong alias sends a user to a different company with full
 * confidence, which is worse than no answer.
 */
object SymbolAliases {

    /**
     * Verified from `data/src/main/resources/assets/EQUITY_L.csv` and
     * `domain/src/test/resources/fixtures/eod_history.json` (see `TataMotorsCorporateActionTest`):
     *
     * - the master has **no** `TATAMOTORS` row and **no** `TATAMOTORSPV` row;
     * - it has `TMCV` = *Tata Motors Limited*, `EQ`, listed `12-NOV-2025`, ISIN `INE1TAE01010`;
     * - it has `TMPV` = *Tata Motors Passenger Vehicles Limited*, `EQ`, listed `22-JUL-1998`,
     *   ISIN `INE155A01022` — i.e. the **other** leg of the demerger kept the old ISIN;
     * - in the 372-session bhavcopy history, `TATAMOTORS` has rows for the first **140**
     *   sessions only (2025-04-01 → 2025-10-23) and `TATAMOTORSPV` has **0**;
     * - `TATAMOTORS.NS` returns zero rows on Yahoo, which is what surfaced all of this.
     *
     * **Why the successor is `TMCV` and not `TMPV`.** Both legs are `EQ` and both trade today,
     * so the ticker alone does not decide it. `TMPV` is a *different company* (Tata Motors
     * **Passenger Vehicles** Limited) that inherited the retired `TATAMOTORS` ISIN; `TMCV` is the
     * same company (Tata Motors Limited) continuing under a fresh ticker and a fresh ISIN, with a
     * new listing date. A ticker-for-ticker continuation is what a user's saved watchlist means,
     * which is why `TMCV` is the alias target and `TMPV` is named in the explanation so the user
     * can find it too.
     */
    val TATAMOTORS_DEMERGER = CorporateActionAlias(
        fromSymbol = "TATAMOTORS",
        toSymbol = "TMCV",
        event = "DEMERGER",
        effectiveFrom = java.time.LocalDate.parse("2025-11-12"),
        relatedSymbols = listOf("TMPV"),
        detail = "Tata Motors demerged into Tata Motors (new listing, TMCV) and TMPV " +
            "(Tata Motors Passenger Vehicles, ISIN INE155A01022). The TATAMOTORS ticker stopped " +
            "trading and is no longer in the NSE symbol master.",
    )

    /**
     * All seeded aliases. One entry, deliberately: every addition must be justified by captured
     * evidence, and the tracker's [CorporateActionTracker.detectedAliases] covers everything that
     * happens from the day the app first runs.
     */
    val DEFAULT: List<CorporateActionAlias> = listOf(TATAMOTORS_DEMERGER)
}