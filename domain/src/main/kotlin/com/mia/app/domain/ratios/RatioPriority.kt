package com.mia.app.domain.ratios

import com.mia.app.domain.model.Sector

/**
 * Why a ratio is on the list, suppressed, or simply not obtainable — the three states that
 * plan.md §7.3 turns on.
 *
 * The plan's own words: *"HDFCBANK returns `null` for `debtToEquity` and `currentRatio` —
 * these are simply not meaningful for banks. The override table is what prevents a misleading
 * blank."* That sentence is the whole design. A blank is only honest when the user is told
 * **which** of three very different things happened:
 *
 * | State | Means | Example |
 * |---|---|---|
 * | [AVAILABLE] | we have the figure | RELIANCE operating margin 17% |
 * | [SUPPRESSED_FOR_SECTOR] | the figure is **not meaningful** here, on purpose | bank debt/equity |
 * | [NOT_SOURCED] | meaningful, but no source of ours publishes it | EV/EBITDA, bank NIM |
 * | [STALE] | we have it and it is older than its window | a 9-month-old fundamentals page |
 * | [MISSING] | meaningful, sourced in principle, absent this time | a company with no promoter row |
 *
 * Collapsing [SUPPRESSED_FOR_SECTOR] into [MISSING] is the failure mode plan.md §13 lists as
 * "misleading blank ratios → user distrusts app"; collapsing [NOT_SOURCED] into
 * [SUPPRESSED_FOR_SECTOR] is the same failure with a different excuse.
 */
enum class RatioState {
    /** We have the figure and it is inside its freshness window. */
    AVAILABLE,

    /** Not meaningful for this sector, deliberately removed from the list (plan.md §7.3). */
    SUPPRESSED_FOR_SECTOR,

    /** Meaningful for this sector but no source of ours publishes it (plan.md §3.3, §3.7). */
    NOT_SOURCED,

    /** The figure exists but is older than the window for its kind (plan.md §10). */
    STALE,

    /** Meaningful and normally available, but this page did not carry it. */
    MISSING,
}

/**
 * A ratio plus the state of its own availability.
 *
 * The invariants are enforced in `init`, not by convention: a ratio that is suppressed,
 * unsourced or missing **must not carry a value**. That is the rule that stops a caller from
 * rendering "0.00" for a bank D/E, and it is a compile-time-adjacent guarantee rather than a
 * review checklist item.
 */
data class RatioAvailability<out T : Any>(
    val value: T?,
    val state: RatioState,
    /** Why it is in this state. Mandatory for every state except [RatioState.AVAILABLE]. */
    val note: String? = null,
) {
    init {
        require(state == RatioState.AVAILABLE || state == RatioState.STALE || value == null) {
            "state $state carries a value; a suppressed, unsourced or missing ratio must have none"
        }
        require(state != RatioState.AVAILABLE || value != null) {
            "RatioState.AVAILABLE requires a value"
        }
        require(state == RatioState.AVAILABLE || !note.isNullOrBlank()) {
            "state $state must say why (plan.md §7.3: a blank the user cannot interpret is a lie)"
        }
    }

    /** True when the UI has something to print: a real figure, fresh or stale. */
    val isRenderable: Boolean get() = state == RatioState.AVAILABLE || state == RatioState.STALE

    /** True when the absence itself is the message — the two states §7.3 is about. */
    val isBlank: Boolean get() = !isRenderable

    /** The value, or `null`. */
    fun orNull(): T? = value

    companion object {
        fun <T : Any> available(value: T, note: String? = null): RatioAvailability<T> =
            RatioAvailability(value, RatioState.AVAILABLE, note)

        fun <T : Any> stale(value: T, note: String? = null): RatioAvailability<T> =
            RatioAvailability(value, RatioState.STALE, note)

        fun <T : Any> suppressed(note: String): RatioAvailability<T> =
            RatioAvailability(null, RatioState.SUPPRESSED_FOR_SECTOR, note)

        fun <T : Any> notSourced(note: String): RatioAvailability<T> =
            RatioAvailability(null, RatioState.NOT_SOURCED, note)

        fun <T : Any> missing(note: String): RatioAvailability<T> =
            RatioAvailability(null, RatioState.MISSING, note)

        /** The common case: a value if we got one, [missing] if we did not. */
        fun <T : Any> from(value: T?, note: String? = null): RatioAvailability<T> =
            if (value == null) {
                missing(note ?: "not published by our sources")
            } else {
                available(value, note)
            }
    }
}

/**
 * One displayable ratio, with its unit, its plan rank and the reason it is ranked there.
 *
 * The [rationale] strings are lifted from plan.md §7.2's rationale column. They are data
 * rather than comments because the app has to *say* them: a user who sees ROCE above ROE is
 * owed the reason, and plan.md §6.1's two-number design is only trustworthy if the UI can
 * explain its own ordering.
 */
enum class RatioId(
    val title: String,
    /** Unit of the value, so the UI cannot print "₹ crore" on a percentage. */
    val unit: RatioUnit,
    /** 1–12 from plan.md §7.2, or `0` for a slot §7.3 adds for one sector. */
    val planRank: Int,
    val rationale: String,
) {
    ROCE("Return on capital employed", RatioUnit.PERCENT, 1,
        "Best single measure of business quality; hard to manipulate"),
    OPERATING_MARGIN("Operating margin", RatioUnit.PERCENT, 2,
        "Pricing power and cost control"),
    DEBT_TO_EQUITY("Debt to equity", RatioUnit.RATIO, 3,
        "Survival"),
    INTEREST_COVERAGE("Interest coverage", RatioUnit.MULTIPLE, 3,
        "Survival; rides with debt/equity in the §7.2 row 3 card"),
    FCF_VS_PAT("Free cash flow vs PAT", RatioUnit.RATIO, 4,
        "#1 earnings-quality tell"),
    REVENUE_CAGR("Revenue CAGR", RatioUnit.PERCENT, 5,
        "Trajectory"),
    ROE("Return on equity", RatioUnit.PERCENT, 6,
        "Duplicated leverage → deliberately ranked below ROCE"),
    GROSS_MARGIN("Gross margin", RatioUnit.PERCENT, 7,
        "Input-cost pressure"),
    PE_VS_SECTOR_MEDIAN("P/E vs sector median", RatioUnit.MULTIPLE, 8,
        "Valuation; a low P/E often signals a problem"),
    PB("Price to book", RatioUnit.MULTIPLE, 9,
        "Pairs with ROE; asset-heavy only"),
    EV_TO_EBITDA("EV / EBITDA", RatioUnit.MULTIPLE, 10,
        "Capital-structure neutral"),
    PROMOTER_HOLDING("Promoter holding", RatioUnit.PERCENT, 11,
        "Capital-allocation confidence"),
    DIVIDEND_YIELD("Dividend yield", RatioUnit.PERCENT, 12,
        "Discipline"),
    DIVIDEND_PAYOUT("Dividend payout ratio", RatioUnit.PERCENT, 12,
        "Discipline; rides with dividend yield in the §7.2 row 12 card"),

    /**
     * Named by plan.md §7.3 as something to drop for banks, but **never computed for any
     * sector**: our one fundamentals source publishes neither a current ratio nor the
     * components (current liabilities / current assets are not on the collapsed balance-sheet
     * summary card, plan.md §3.3). It is in the enum so the distinction §7.3 asks for is
     * expressible — see [RatioPriority.sectorStateFor].
     */
    CURRENT_RATIO("Current ratio", RatioUnit.RATIO, 0,
        "Not computed: our source publishes neither the ratio nor its components"),

    // ---- §7.3 banking
    NET_INTEREST_MARGIN("Net interest margin", RatioUnit.PERCENT, 0,
        "plan.md §7.3 banking: a bank's core margin replaces operating margin"),
    GNPA_NNPA("Gross / net NPA ratio", RatioUnit.PERCENT, 0,
        "plan.md §7.3 banking: asset quality is the first question for a bank"),
    CASA_RATIO("CASA ratio", RatioUnit.PERCENT, 0,
        "plan.md §7.3 banking: the cheapest funding a bank has"),
    PROVISION_COVERAGE_RATIO("Provision coverage ratio", RatioUnit.PERCENT, 0,
        "plan.md §7.3 banking: PCR — the buffer against bad loans"),

    // ---- §7.3 NBFC
    ASSET_QUALITY("Asset quality", RatioUnit.RATIO, 0,
        "plan.md §7.3 NBFC: replaces operating margin"),
    STAGE_2_GNPA("Stage-2 GNPA ratio", RatioUnit.PERCENT, 0,
        "plan.md §7.3 NBFC: loans migrating but not yet bad"),
    COST_OF_FUNDS("Cost of funds", RatioUnit.PERCENT, 0,
        "plan.md §7.3 NBFC: an NBFC's margin is funding cost, not operating margin"),
    LEVERAGE("Leverage", RatioUnit.MULTIPLE, 0,
        "plan.md §7.3 NBFC: balance-sheet amplification, sector-specific"),

    // ---- §7.3 IT
    TOTAL_CONTRACT_VALUE("Total contract value", RatioUnit.RATIO, 0,
        "plan.md §7.3 IT: booked, not yet recognised"),
    ATTRITION("Attrition rate", RatioUnit.PERCENT, 0,
        "plan.md §7.3 IT: delivery capacity, not assets"),
    CONSTANT_CURRENCY_GROWTH("Constant-currency growth", RatioUnit.PERCENT, 0,
        "plan.md §7.3 IT: growth stripped of currency and of acquisitions"),
    REVENUE_VISIBILITY("Revenue visibility", RatioUnit.PERCENT, 0,
        "plan.md §7.3 IT: the share of the next year already booked"),

    // ---- §7.3 pharma
    RD_TO_SALES("R&D as % of sales", RatioUnit.PERCENT, 0,
        "plan.md §7.3 pharma: pipeline investment is the moat"),
    USFDA_COMPLIANCE("USFDA compliance", RatioUnit.RATIO, 0,
        "plan.md §7.3 pharma: an observation letter is an existential event"),
    PRODUCT_CONCENTRATION("Product concentration", RatioUnit.PERCENT, 0,
        "plan.md §7.3 pharma: how much rides on the top molecules"),

    // ---- §7.3 FMCG
    VOLUME_VS_PRICE_GROWTH("Volume vs price growth", RatioUnit.PERCENT, 0,
        "plan.md §7.3 FMCG: growth without price growth is not growth"),
    WORKING_CAPITAL_DAYS("Working capital days", RatioUnit.DAYS, 0,
        "plan.md §7.3 FMCG: cash trapped in the channel"),

    // ---- supplementary: the shareholding pattern (W4)
    //
    // plan.md §7.2 ranks twelve rows and says nothing about institutional ownership, yet
    // screener.in's `shareholding` card publishes an `FIIs` and a `DIIs` row for every one of the
    // five golden fixtures, and the parser has been carrying those rows since before this wave —
    // unused. They are appended after the plan's list rather than inserted into it, because
    // inserting them would invent a §7.2 rank the plan does not assign (see
    // [RatioPriority.SUPPLEMENTARY_ORDER] and [SlotOrigin.SUPPLEMENTARY]).
    //
    // They are **not** sector-specific. A bank's FII holding is exactly as meaningful as an
    // oil company's — in fact more so — and HDFCBANK's fixture publishes both rows while
    // publishing no `Promoters` row at all, which is the case that made a shared card wrong.

    FII_HOLDING("FII holding", RatioUnit.PERCENT, 0,
        "Foreign portfolio investors' share of the company, as published each quarter"),
    DII_HOLDING("DII holding", RatioUnit.PERCENT, 0,
        "Domestic institutions' share of the company, as published each quarter"),
    FII_TREND("FII holding change, quarter on quarter", RatioUnit.PERCENTAGE_POINTS, 0,
        "How much of the company institutions added or gave back in the last published quarter"),

    ;

    companion object {
        /**
         * Ratios **no source of ours can produce**, whatever the sector.
         *
         * Measured on all five screener.in fixtures (plan.md §3.3):
         *
         * * [CURRENT_RATIO] — neither the ratio nor its components are published; the collapsed
         *   balance-sheet summary has no current-liabilities/current-assets split.
         * * [GROSS_MARGIN] — there is no cost-of-goods line anywhere on the page.
         * * [EV_TO_EBITDA] — neither an enterprise value nor an EBITDA line is published.
         *
         * These stay in the plan.md §7.2 order, because §7.2 is the plan, and their
         * [RatioState.NOT_SOURCED] is what tells the user the app knows the card and cannot
         * fill it. Removing them from the order would hide the gap; this is plan.md §13's
         * "misleading blank ratios → user distrusts app" avoided rather than caused.
         */
        private val NEVER_SOURCED: Set<RatioId> =
            setOf(CURRENT_RATIO, GROSS_MARGIN, EV_TO_EBITDA)

        /**
         * The ratios that exist **only** because plan.md §7.3 named them for one sector: 4 banking
         * + 4 NBFC + 4 IT + 3 pharma + 2 FMCG.
         *
         * ### Why this is an explicit list and not `planRank == 0`
         * It used to be inferred: "no §7.2 rank ⇒ §7.3 added it". The supplementary shareholding
         * rows [FII_HOLDING], [DII_HOLDING] and [FII_TREND] have no §7.2 rank either — the plan
         * does not mention them — and inferring would have classed them as sector-specific, which
         * [RatioPriority.sectorStateFor] answers with [RatioState.NOT_SOURCED]. The card would then
         * have been permanently empty *and* permanently mislabelled, on a ratio every one of the
         * five fixtures publishes. Being sector-specific is a statement about the plan, so it is
         * now written down rather than deduced from a number that two different reasons can zero.
         */
        private val SECTOR_SPECIFIC: Set<RatioId> = setOf(
            // §7.3 banking
            NET_INTEREST_MARGIN, GNPA_NNPA, CASA_RATIO, PROVISION_COVERAGE_RATIO,
            // §7.3 NBFC
            ASSET_QUALITY, STAGE_2_GNPA, COST_OF_FUNDS, LEVERAGE,
            // §7.3 IT
            TOTAL_CONTRACT_VALUE, ATTRITION, CONSTANT_CURRENCY_GROWTH, REVENUE_VISIBILITY,
            // §7.3 pharma
            RD_TO_SALES, USFDA_COMPLIANCE, PRODUCT_CONCENTRATION,
            // §7.3 FMCG
            VOLUME_VS_PRICE_GROWTH, WORKING_CAPITAL_DAYS,
        )
    }

    /** True when the ratio exists only because plan.md §7.3 added it for one sector. */
    val isSectorSpecific: Boolean get() = this in SECTOR_SPECIFIC

    /** True when the ratio is one our single fundamentals source never publishes. */
    val isNeverSourced: Boolean get() = this in NEVER_SOURCED

    /**
     * True when no plan.md §7.2 rank applies — either because §7.3 added it for a sector, or
     * because it is one of the [RatioPriority.SUPPLEMENTARY_ORDER] rows the plan never ranked.
     */
    val isUnranked: Boolean get() = planRank == 0
}

/** Unit of a ratio value. Kept in code so a formatter cannot mix percent with ₹ crore. */
enum class RatioUnit {
    /** `17.0` means 17%. */
    PERCENT,

    /** `1.13` means 1.13×. */
    RATIO,

    /** `63.5` means 63.5× — a valuation or coverage multiple. */
    MULTIPLE,

    /** `70,023` means ₹70,023 crore. */
    CRORE,

    /** `412` means 412 days. */
    DAYS,

    /**
     * `-1.48` means the share moved down by 1.48 **percentage points** — 18.67% to 17.19%.
     *
     * Its own unit rather than [PERCENT] because the two answer different questions and the
     * difference is exactly what a quarterly shareholding change turns on. An FII holding that
     * falls from 18.67% to 17.19% is down 1.48 points, or down 7.9%; printing either number with a
     * `%` glyph and calling it the other one is how "the share rose" gets reported for a company
     * whose share did not.
     */
    PERCENTAGE_POINTS,

    /** `15.48` means ₹15.48 per share — EPS, which is a price per share and not a multiple. */
    RUPEE_PER_SHARE,
}

/** Where a display slot came from: the §7.2 list, a §7.3 override, or neither. */
enum class SlotOrigin {
    /** One of plan.md §7.2's twelve rows. */
    PLAN,

    /** Added or promoted for one sector by plan.md §7.3. */
    SECTOR_SPECIFIC,

    /**
     * Published by our source, read from the page, and ranked by **no** plan.md row.
     *
     * Distinct from [SECTOR_SPECIFIC] because the two must not be rendered the same way: a §7.3
     * slot was added *because* of the sector, while a supplementary slot would be wrong to omit for
     * every sector. Giving it its own origin is what lets the UI say "Ownership" rather than
     * claiming a §7.2 rank the plan never assigned — or printing "0".
     */
    SUPPLEMENTARY,
}

/**
 * One row of the fundamentals tab.
 *
 * A §7.2 row can carry more than one metric — "Debt/Equity + interest coverage" is one card
 * with two numbers — so [companions] ride along with the [id] that names the card.
 */
data class RatioSlot(
    val id: RatioId,
    val companions: List<RatioId> = emptyList(),
    val origin: SlotOrigin = if (id.isSectorSpecific) SlotOrigin.SECTOR_SPECIFIC else SlotOrigin.PLAN,
    val title: String = id.title,
) {
    init {
        require(id !in companions) { "slot ${id.name} cannot be its own companion" }
    }

    /** Every ratio this row shows, primary first. */
    val ids: List<RatioId> get() = listOf(id) + companions

    /** The §7.2 rank of the row this slot came from; `0` for a §7.3 slot. */
    val planRank: Int get() = id.planRank

    val isSectorSpecific: Boolean get() = origin == SlotOrigin.SECTOR_SPECIFIC

    /** `true` for the rows the plan does not rank — see [SlotOrigin.SUPPLEMENTARY]. */
    val isSupplementary: Boolean get() = origin == SlotOrigin.SUPPLEMENTARY
}

/**
 * plan.md §7.3's instructions for one sector, as data.
 *
 * An override is a **replacement**, not an addition: [order] is the sector's own leading
 * block, [suppressed] removes rows from the §7.2 list, and [promoted] moves a §7.2 row to an
 * absolute position in the final list. Nothing is ever appended below the generic list and
 * shown alongside it — a bank does not get D/E *and* NIM, it gets NIM.
 */
data class SectorOverrides(
    val sector: Sector,
    /** Sector-specific slots, in the order they must appear. Placed before the §7.2 rows. */
    val order: List<RatioSlot> = emptyList(),
    /** Ratio → the reason it is not shown for this sector. */
    val suppressed: Map<RatioId, String> = emptyMap(),
    /** Ratio → its absolute 1-based position in the composed order. */
    val promoted: Map<RatioId, Int> = emptyMap(),
    /** One line per §7.3 row this override implements, for the UI and the report. */
    val planNote: String = "",
) {
    init {
        require(order.all { it.isSectorSpecific }) {
            "${sector.name}: only sector-specific slots belong in the override order"
        }
        require(order.map { it.id }.distinct().size == order.size) {
            "${sector.name}: duplicate slot in the override order"
        }
        require(suppressed.keys.none { it in order.map(RatioSlot::id) }) {
            "${sector.name}: a slot cannot be both inserted and suppressed"
        }
    }

    val hasOverrides: Boolean get() = order.isNotEmpty() || suppressed.isNotEmpty() || promoted.isNotEmpty()
}

/** A composed sector-specific display plan: the order plus the override that produced it. */
data class RatioPlan(
    val sector: Sector,
    val order: List<RatioSlot>,
    val overrides: SectorOverrides,
) {
    val ids: List<RatioId> get() = order.flatMap { it.ids }

    fun contains(id: RatioId): Boolean = order.any { id in it.ids }
}

/**
 * The authoritative display order for the fundamentals tab (plan.md §7.2) and its mandatory
 * per-sector overrides (§7.3).
 *
 * ### The single global list misleads, and §7.3 is the fix
 * plan.md §7.3 is titled *"Sector overrides (mandatory — a single global list misleads on
 * banks)"*. So [order] is never just a filtered §7.2 list: for the five sectors §7.3 names,
 * the sector's own slots **replace** whole rows, promote a different ratio to the top, and
 * remove the rows that are not meaningful. For every other sector — including [Sector.OTHER],
 * which is what an unresolved company gets — the plan.md §7.2 order is used unchanged.
 *
 * ### Where the unsourced bank slots go
 * A bank's NIM, GNPA/NNPA, CASA and PCR are real, important and **absent from our sources**:
 * screener.in's consolidated page publishes no bank ratio table (plan.md §3.3, and verified on
 * the HDFCBANK fixture). They therefore appear in [RatioPlan.order] — a bank user must see
 * that the app knows the slot exists — with [RatioState.NOT_SOURCED] and a note saying so.
 * That is the difference §7.3 asks for between "not meaningful here" and "we cannot source it",
 * and it is why [RatioAvailability] carries the two states separately.
 */
object RatioPriority {

    /**
     * plan.md §7.2 ranks 1–12, verbatim, with the plan's own two-metric rows kept as one card.
     */
    val PLAN_ORDER: List<RatioSlot> = listOf(
        RatioSlot(RatioId.ROCE),
        RatioSlot(RatioId.OPERATING_MARGIN),
        RatioSlot(RatioId.DEBT_TO_EQUITY, companions = listOf(RatioId.INTEREST_COVERAGE)),
        RatioSlot(RatioId.FCF_VS_PAT),
        RatioSlot(RatioId.REVENUE_CAGR),
        RatioSlot(RatioId.ROE),
        RatioSlot(RatioId.GROSS_MARGIN),
        RatioSlot(RatioId.PE_VS_SECTOR_MEDIAN),
        RatioSlot(RatioId.PB),
        RatioSlot(RatioId.EV_TO_EBITDA),
        RatioSlot(RatioId.PROMOTER_HOLDING),
        RatioSlot(RatioId.DIVIDEND_YIELD, companions = listOf(RatioId.DIVIDEND_PAYOUT)),
    )

    /**
     * Rows the source publishes that plan.md §7.2 does not rank — appended to **every** sector's
     * list, never inserted into §7.2's.
     *
     * ### Why "appended" and not "inserted at rank 11"
     * §7.2's rank 11 is promoter holding, rationalised as "capital-allocation confidence". FII and
     * DII holdings are a different question — who owns the register rather than what the owners
     * do with it — and the plan gives them no rank. Inserting them at 11 would print a number the
     * plan never assigned, which is the same mistake in a smaller font as promoting a bank's ROE
     * to rank 1: the display order would claim an authority it does not have.
     *
     * ### Why the three live in one card
     * They are one published table. Splitting them across three cards would repeat the same
     * as-of chip and the same provenance sentence three times and imply three separate judgments,
     * when in fact they are three rows read out of one `shareholding` section in one pass.
     *
     * ### Why no sector suppresses any of them
     * Ownership structure is not sector-conditioned: a bank's foreign holding is at least as
     * meaningful as an oil company's. HDFCBANK's fixture publishes both rows — and, uniquely
     * among the five, publishes **no** `Promoters` row, which is precisely why this could not have
     * been folded into the promoter-holding card without leaving a bank with an empty one.
     */
    val SUPPLEMENTARY_ORDER: List<RatioSlot> = listOf(
        RatioSlot(
            id = RatioId.FII_HOLDING,
            companions = listOf(RatioId.DII_HOLDING, RatioId.FII_TREND),
            origin = SlotOrigin.SUPPLEMENTARY,
            title = "Institutional ownership",
        ),
    )

    /**
     * The five overrides plan.md §7.3 makes mandatory.
     *
     * Two kinds of suppression appear here and the difference is deliberate:
     *
     * * **Not meaningful** — a bank's D/E (§7.3 by name) and a bank's operating margin, which
     *   the HDFCBANK fixture proves does not exist as a published row (it carries
     *   `Financing Margin %` and no `Operating Profit`, so both OPM and any EBIT-based
     *   coverage ratio are structurally absent). State: [RatioState.SUPPRESSED_FOR_SECTOR].
     * * **Wrong lens** — price/book for a pharma or an FMCG company. plan.md §7.2 rank 9 says
     *   P/B is "asset-heavy only", and these are not asset-heavy; a high P/B there is a
     *   capitalisation artifact rather than a valuation signal.
     */
    private val OVERRIDES: Map<Sector, SectorOverrides> = mapOf(
        Sector.BANKING to SectorOverrides(
            sector = Sector.BANKING,
            order = listOf(
                slot(RatioId.NET_INTEREST_MARGIN),
                slot(RatioId.GNPA_NNPA),
                slot(RatioId.CASA_RATIO),
                slot(RatioId.PROVISION_COVERAGE_RATIO),
            ),
            suppressed = mapOf(
                RatioId.DEBT_TO_EQUITY to
                    "Debt/equity is not meaningful for a bank: its deposits and borrowings are " +
                    "the business, not leverage (plan.md §7.3)",
                RatioId.INTEREST_COVERAGE to
                    "A bank's published 'Interest' row is interest income, not interest " +
                    "expense, so EBIT/interest is meaningless (verified on the HDFCBANK fixture)",
                RatioId.OPERATING_MARGIN to
                    "A bank publishes 'Financing Margin %', not 'Operating Profit'/'OPM %', so " +
                    "there is no operating margin to show (verified on the HDFCBANK fixture)",
                RatioId.CURRENT_RATIO to
                    "The current ratio is not meaningful for a bank (plan.md §7.3 names it in " +
                    "the same sentence as D/E)",
            ),
            promoted = mapOf(RatioId.ROE to 1),
            planNote = "ROE first, then NIM/GNPA/CASA/PCR; D/E, interest coverage, operating " +
                "margin and the current ratio dropped (plan.md §7.3)",
        ),
        Sector.NBFC to SectorOverrides(
            sector = Sector.NBFC,
            order = listOf(
                slot(RatioId.ASSET_QUALITY),
                slot(RatioId.STAGE_2_GNPA),
                slot(RatioId.COST_OF_FUNDS),
                slot(RatioId.LEVERAGE),
            ),
            suppressed = mapOf(
                RatioId.OPERATING_MARGIN to
                    "An NBFC prices off its funding spread and publishes no 'Operating Profit' " +
                    "row; its margin question is cost of funds, not OPM (plan.md §7.3)",
            ),
            planNote = "Asset quality, stage-2 GNPA, cost of funds and leverage replace the " +
                "operating-margin slot (plan.md §7.3)",
        ),
        Sector.IT to SectorOverrides(
            sector = Sector.IT,
            order = listOf(
                slot(RatioId.TOTAL_CONTRACT_VALUE),
                slot(RatioId.ATTRITION),
                slot(RatioId.CONSTANT_CURRENCY_GROWTH),
                slot(RatioId.REVENUE_VISIBILITY),
            ),
            suppressed = mapOf(
                RatioId.GROSS_MARGIN to
                    "A services P&L has no cost of goods sold, so 'gross margin' would be " +
                    "revenue minus an unallocated employee cost — not a published figure " +
                    "(plan.md §3.3)",
            ),
            planNote = "TCS, attrition, constant-currency growth and revenue visibility lead; " +
                "gross margin dropped as unpublishable for a services P&L (plan.md §7.3)",
        ),
        Sector.PHARMA to SectorOverrides(
            sector = Sector.PHARMA,
            order = listOf(
                slot(RatioId.RD_TO_SALES),
                slot(RatioId.USFDA_COMPLIANCE),
                slot(RatioId.PRODUCT_CONCENTRATION),
            ),
            suppressed = mapOf(
                RatioId.PB to
                    "plan.md §7.2 rank 9 scopes price/book to asset-heavy businesses; a pharma " +
                    "company's book value is mostly intangibles, so P/B misreads it",
            ),
            planNote = "R&D as % of sales, USFDA compliance and product concentration lead the " +
                "list (plan.md §7.3)",
        ),
        Sector.FMCG to SectorOverrides(
            sector = Sector.FMCG,
            order = listOf(
                slot(RatioId.VOLUME_VS_PRICE_GROWTH),
                slot(RatioId.WORKING_CAPITAL_DAYS),
            ),
            suppressed = mapOf(
                RatioId.PB to
                    "plan.md §7.2 rank 9 scopes price/book to asset-heavy businesses; a brand " +
                    "and distribution business is neither",
            ),
            promoted = mapOf(RatioId.GROSS_MARGIN to 2),
            planNote = "Volume-vs-price growth, gross margin and working-capital days lead " +
                "(plan.md §7.3)",
        ),
    )

    /** plan.md §7.3 for [sector]. Empty — never `null` — for every sector it does not name. */
    fun sectorOverrides(sector: Sector): SectorOverrides = OVERRIDES[sector] ?: SectorOverrides(sector)

    /** The display order for [sector]: §7.2 as amended by §7.3. Never empty. */
    fun order(sector: Sector = Sector.OTHER): List<RatioSlot> = plan(sector).order

    /** [order] together with the override that produced it, for callers that need both. */
    fun plan(sector: Sector = Sector.OTHER): RatioPlan =
        RatioPlan(sector = sector, order = compose(sector), overrides = sectorOverrides(sector))

    /**
     * What the **sector** decides about a ratio, independently of the data.
     *
     * `null` means "the data decides" — the ratio is in the plan's list and is shown when we
     * have it. [RatioState.SUPPRESSED_FOR_SECTOR] means the sector removed it. The §7.3
     * banking pair is the case the plan calls out: a bank's D/E is
     * [RatioState.SUPPRESSED_FOR_SECTOR] while EV/EBITDA is [RatioState.NOT_SOURCED]
     * everywhere, and the user-facing wording has to be different.
     */
    fun sectorStateFor(sector: Sector, id: RatioId): RatioState? {
        sectorOverrides(sector).suppressed[id]?.let { return RatioState.SUPPRESSED_FOR_SECTOR }
        if (id.isSectorSpecific) return RatioState.NOT_SOURCED
        if (id.isNeverSourced) return RatioState.NOT_SOURCED
        return null
    }

    /** The reason behind [sectorStateFor], for the availability note. */
    fun sectorNoteFor(sector: Sector, id: RatioId): String? =
        sectorOverrides(sector).suppressed[id] ?: notSourcedNote(id, sector)

    /**
     * Combines the sector's decision with the data actually in hand.
     *
     * A suppressed or unsourced ratio keeps its sector reason and never takes a value, even if
     * a caller hands one in — otherwise the caller could quietly resurrect a bank D/E that
     * §7.3 removed.
     */
    fun <T : Any> availability(
        sector: Sector,
        id: RatioId,
        value: T?,
        note: String? = null,
    ): RatioAvailability<T> = when (sectorStateFor(sector, id)) {
        RatioState.SUPPRESSED_FOR_SECTOR ->
            RatioAvailability.suppressed(sectorNoteFor(sector, id) ?: "not meaningful for ${sector.name}")
        RatioState.NOT_SOURCED ->
            RatioAvailability.notSourced(sectorNoteFor(sector, id) ?: notSourcedNote(id, sector))
        else -> RatioAvailability.from(value, note)
    }

    /** The §7.3 rationale for a ratio we cannot source, phrased for a user. */
    fun notSourcedNote(id: RatioId, sector: Sector): String = when {
        id.isNeverSourced -> "${id.title} is not published by screener.in's consolidated page, " +
            "and the collapsed balance-sheet summary does not carry its components, so it is " +
            "never computed (plan.md §3.3)"
        id.isSectorSpecific -> "${id.title} is the slot plan.md §7.3 requires for " +
            "${SectorResolver.displayNameOf(sector)}, but no source of ours publishes it " +
            "(plan.md §3.7: no bank or sector ratio table is free, so v1 does not have one)"
        else -> "${id.title} is not available from our sources"
    }

    private fun compose(sector: Sector): List<RatioSlot> {
        val overrides = sectorOverrides(sector)
        val survivors = PLAN_ORDER.filterNot { slot ->
            slot.ids.any { it in overrides.suppressed }
        }
        val result = ArrayList<RatioSlot>(survivors.size + overrides.order.size + SUPPLEMENTARY_ORDER.size)
        result.addAll(overrides.order)
        result.addAll(survivors)
        // Promotions are applied last so they address absolute positions in the final list.
        for ((id, position) in overrides.promoted.entries.sortedBy { it.value }) {
            val at = result.indexOfFirst { it.id == id }
            if (at >= 0 && position in 1..result.size) result.add(position - 1, result.removeAt(at))
        }
        // Appended after the promotions, deliberately: §7.3 positions are absolute within the list
        // §7.3 composed, and appending first would shift every one of them down by one.
        result.addAll(SUPPLEMENTARY_ORDER)
        check(result.map { it.id }.distinct().size == result.size) {
            "${sector.name}: §7.3 override produced a duplicate slot"
        }
        return result
    }

    private fun slot(id: RatioId) = RatioSlot(id, origin = SlotOrigin.SECTOR_SPECIFIC)
}