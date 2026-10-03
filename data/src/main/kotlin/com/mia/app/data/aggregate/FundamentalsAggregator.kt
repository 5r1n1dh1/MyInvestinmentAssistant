package com.mia.app.data.aggregate

import com.mia.app.domain.model.Confidence
import com.mia.app.domain.model.Merged
import com.mia.app.domain.model.Ratios
import com.mia.app.domain.model.Sector
import com.mia.app.domain.model.Source
import com.mia.app.domain.ratios.FreshnessKind
import com.mia.app.domain.ratios.FreshnessPolicy
import java.time.LocalDate

/**
 * Every field of a [Ratios] snapshot, with the source precedence plan.md §5.1 states for it.
 *
 * The precedence is carried **on the field** rather than passed in, which is what makes
 * "merge per field, never per source" structural instead of aspirational: there is no call
 * signature in which the argument order decides the winner, so `mergeRatios(yahoo, screenerIn,
 * today)` produces the same rows as `mergeRatios(screenerIn, yahoo, today)`.
 */
enum class RatioFieldId(
    val title: String,
    val unit: String,
    val precedence: FieldPrecedence,
    val why: String,
) {

    ROCE("ROCE", "%", FieldPrecedence.FUNDAMENTALS_PAGE,
        "plan.md §5.1: ROCE → screener.in. Also §7.2 rank 1."),
    ROE("ROE", "%", FieldPrecedence.FUNDAMENTALS_PAGE,
        "plan.md §5.1: ROE → screener.in. The measured TCS case (51.8 vs 47.743) is a conflict."),
    DEBT_TO_EQUITY("Debt to equity", "×", FieldPrecedence.FUNDAMENTALS_PAGE,
        "screener.in's consolidated page publishes no D/E, so this is normally absent; it is also " +
            "meaningless for banks (plan.md §7.3)"),
    INTEREST_COVERAGE("Interest coverage", "×", FieldPrecedence.FUNDAMENTALS_PAGE,
        "computed from the page's own EBIT and interest"),
    OPERATING_MARGIN("Operating margin", "%", FieldPrecedence.FUNDAMENTALS_PAGE,
        "quarterly/annual P&L row — 'Yahoo has none' (plan.md §5.1)"),
    FCF("Free cash flow", "₹ crore", FieldPrecedence.FUNDAMENTALS_PAGE,
        "cash-flow table; Yahoo has none (plan.md §5.1)"),
    PAT("PAT", "₹ crore", FieldPrecedence.FUNDAMENTALS_PAGE,
        "profit and loss table; Yahoo has none (plan.md §5.1)"),
    CFO_TO_OP("CFO / operating profit", "×", FieldPrecedence.FUNDAMENTALS_PAGE,
        "cash-flow ÷ P&L across two of the page's tables; Yahoo has neither"),
    REVENUE_CAGR3Y("Revenue CAGR (3y)", "%", FieldPrecedence.FUNDAMENTALS_PAGE,
        "computed from the page's own annual sales column"),
    REVENUE_GROWTH("Revenue growth", "%", FieldPrecedence.FUNDAMENTALS_PAGE,
        "computed from the page's own annual sales column"),
    GROSS_MARGIN("Gross margin", "%", FieldPrecedence.FUNDAMENTALS_PAGE,
        "NOT OBTAINABLE — screener.in publishes no cost-of-goods line (plan.md §3.3)"),
    PE("Stock P/E", "×", FieldPrecedence.FUNDAMENTALS_PAGE,
        "plan.md §5.1: PE → screener.in. The measured TCS case (14.0 vs 15.083) is a conflict."),
    PB("Price to book", "×", FieldPrecedence.DERIVED_FROM_PRICE,
        "plan.md §5.1: BookValue → screener.in. The page publishes book value, not P/B, so the " +
            "multiple is computed from its own current price and book value."),
    EV_TO_EBITDA("EV / EBITDA", "×", FieldPrecedence.FUNDAMENTALS_PAGE,
        "NOT OBTAINABLE — the page publishes neither enterprise value nor EBITDA (plan.md §3.3)"),
    DIVIDEND_YIELD("Dividend yield", "%", FieldPrecedence.FUNDAMENTALS_PAGE,
        "plan.md §5.1: screener.in's top-ratios block. Yahoo's dividend is on a different basis."),
    PROMOTER_HOLDING("Promoter holding", "%", FieldPrecedence.FUNDAMENTALS_PAGE,
        "shareholding pattern table; Yahoo has none (plan.md §5.1)"),
    MARKET_CAP("Market cap", "₹ crore", FieldPrecedence.FUNDAMENTALS_PAGE,
        "top-ratios block, printed in ₹ crore. Yahoo's figure is in a different unit and its " +
            "share count basis is not published, so it is never compared"),
    ;

    companion object {
        /** Declaration order — which is plan.md §5's own field order. */
        val ALL: List<RatioFieldId> = entries.toList()
    }
}

/**
 * One merged ratio row: what to show, what the other source said, and how far apart they were.
 *
 * [merged] follows the shape of [com.mia.app.domain.model.Merged] so the UI reads both kinds of
 * row the same way. The raw `screenerIn`/`other` values are kept as well because the greyed-out
 * alternate must be renderable even when [merged] is not — for instance when the value is
 * [Confidence.STALE] and the confidence slot is being used for the date instead of the
 * disagreement.
 */
data class RatioField(
    val id: RatioFieldId,
    val merged: Merged<Double>?,
    /** The precedence side's value (screener.in for every field plan.md §5.1 lists). */
    val screenerIn: Double?,
    /** The other source's value, `null` when it published none. */
    val other: Double?,
    val divergence: Double?,
) {
    val divergencePercent: Double? get() = divergence?.let { it * 100.0 }

    val confidence: Confidence? get() = merged?.confidence
    val value: Double? get() = merged?.value
    val isSingle: Boolean get() = confidence == Confidence.SINGLE

    /**
     * Beyond the tolerance, decided in **fraction** space.
     *
     * Comparing the fraction rather than the scaled percent keeps the 5.00% boundary exact:
     * `(105 − 100)/100` is the same double as the literal `0.05`, so "exactly 5.00%" is agreed,
     * while multiplying by 100 first would introduce a rounding step that neither side of the
     * boundary deserves to depend on.
     */
    val hasConflict: Boolean
        get() = divergence != null && divergence > AggregationRule.DIVERGENCE_TOLERANCE

    /** The greyed alternate, or `null` when the sources agreed or only one had the field. */
    val alternate: Double? get() = merged?.alternate

    /** The divergence chip, or `null`. Same wording as [DivergentField.chip]. */
    fun chip(): String? {
        val shown = merged ?: return null
        val other = other ?: return null
        if (!hasConflict) return null
        return DivergentField(
            field = id.name,
            shown = shown.value,
            shownSource = shown.source,
            alternate = other,
            alternateSource = shown.alternateSource ?: Source.YAHOO,
            divergence = divergence,
            unit = id.unit,
            asOf = shown.asOf,
        ).chip()
    }
}

/**
 * The full merged picture for one symbol: the per-field rows, the collapsed [Ratios] the rest of
 * the app consumes, and the [DivergenceReport] the UI renders chips from.
 *
 * [ratios] is a **lossy summary**, and its KDoc says so. `Ratios` has one confidence slot for
 * eighteen fields, so per-field truth lives in [fields] and [ratios.confidence] carries only the
 * worst verdict. Nothing should read `ratios.confidence` to decide whether a *particular* number
 * is trustworthy.
 */
data class FundamentalsMerge(
    val symbol: String,
    val asOf: LocalDate,
    val fields: List<RatioField>,
    val ratios: Ratios,
    val report: DivergenceReport,
) {
    fun field(id: RatioFieldId): RatioField? = fields.firstOrNull { it.id == id }

    /** Rows with a real value, in [RatioFieldId] declaration order. */
    val populated: List<RatioField> get() = fields.filter { it.value != null }

    val conflicts: List<DivergentField> get() = report.conflicts

    /** Fields only one source had. RELIANCE ROE is the documented case. */
    val singleSourced: List<RatioFieldId>
        get() = fields.filter { it.isSingle && it.value != null }.map { it.id }
}

/**
 * Merges fundamentals field by field (plan.md §5.1).
 *
 * ### The rule, in the plan's own words
 *
 * > Merge per field, not per source. Never pick one winning source wholesale.
 * > Precedence: … `PE/ROE/ROCE/BookValue → screener.in`; quarterly P&L, CFO/FCF/CFO-OP,
 * > shareholding → screener.in (Yahoo has none).
 * > On conflict (>5% divergence): show the primary value, render the other greyed beneath it
 * > with a divergence chip. Do NOT average. Do NOT silently pick one.
 *
 * Three consequences this class exists to guarantee:
 *
 * 1. **Never average.** [RatioField.value] is always one of the two inputs. [mergeRatios] has
 *    no arithmetic operator that could produce a third number.
 * 2. **Never hide.** Every disagreement lands in [FundamentalsMerge.report], even when the
 *    confidence slot has been spent on staleness.
 * 3. **Never blank without an explanation.** Fields no source publishes are listed in
 *    [DivergenceReport.unavailable] with the reason, which is what separates "not meaningful
 *    for a bank" from "we cannot source it" (plan.md §7.3, §13).
 *
 * ### Which snapshot is "primary"
 * [mergeRatios]'s two `Ratios` arguments are **not** treated as winner and loser. Precedence is
 * read from [RatioFieldId.precedence], which resolves to the screener.in side for every field
 * plan.md §5.1 lists; the `Ratios.source` of each argument says which argument that is. Passing
 * them the other way round returns the same rows, in the same order, with the same winners.
 */
object FundamentalsAggregator {

    /**
     * Figures plan.md's free sources cannot produce, named so a blank is explainable.
     *
     * **Sector-median P/E is in this list on purpose.** plan.md §7.2 ranks "P/E vs sector median"
     * eighth and plan.md §8.1 has a value-trap rule that needs the median. Neither is reachable:
     * computing a sector median requires the whole sector's P/Es, and our only fundamentals
     * source is one company page at a time (plan.md §3.3), with search and pagination disallowed
     * by robots.txt (plan.md §3.6). So the card shows the company's own P/E and says plainly that
     * the median is unavailable, rather than computing a median over whatever handful of stocks
     * happened to be cached — which would be a fabricated number wearing a real one's label.
     */
    val NOT_OBTAINABLE: List<UnavailableField> = listOf(
        UnavailableField(
            "SECTOR_MEDIAN_PE",
            "plan.md §7.2 rank 8 needs every comparable company's P/E; screener.in serves one " +
                "company page per request and its search and pagination endpoints are disallowed " +
                "by robots.txt (plan.md §3.6), and no other free source publishes an Indian " +
                "sector median (plan.md §3.7). The app shows the company's own P/E and never a " +
                "median of whatever happens to be cached",
        ),
        UnavailableField(
            "GROSS_MARGIN",
            "screener.in's consolidated page has no cost-of-goods line, so gross margin has no " +
                "published basis (plan.md §3.3, §7.3 for IT)",
        ),
        UnavailableField(
            "EV_TO_EBITDA",
            "neither enterprise value nor EBITDA is published by any of our sources " +
                "(plan.md §3.3, §3.7)",
        ),
        UnavailableField(
            "DEBT_TO_EQUITY",
            "screener.in publishes no D/E row, and it is not meaningful for banks at all " +
                "(plan.md §7.3). The derivation belongs in domain/ratios behind the sector override",
        ),
        UnavailableField(
            "BANK_SECTOR_RATIOS",
            "NIM, GNPA/NNPA, CASA and PCR are plan.md §7.3's banking slots and none of them is " +
                "published by any free source this app uses (verified on the HDFCBANK fixture, " +
                "plan.md §3.3)",
        ),
    )

    /**
     * One merged row per field of [Ratios], in [RatioFieldId] declaration order.
     *
     * @param today injected clock. Staleness (plan.md §10) is evaluated against it, never
     *   against `LocalDate.now()`, so a test can put both snapshots a year in the past and
     *   assert [Confidence.STALE].
     */
    fun mergeRatios(primary: Ratios, secondary: Ratios, today: LocalDate): List<RatioField> {
        val sides = sidesOf(primary, secondary)
        return RatioFieldId.ALL.map { id -> mergeField(id, sides, today) }
    }

    /**
     * The whole merge: per-field rows, the collapsed [Ratios], and the divergence report.
     */
    fun merge(primary: Ratios, secondary: Ratios, today: LocalDate): FundamentalsMerge {
        val sides = sidesOf(primary, secondary)
        val fields = RatioFieldId.ALL.map { id -> mergeField(id, sides, today) }
        val asOf = maxAsOf(primary.asOf, secondary.asOf)
        val ratios = collapse(primary, secondary, fields, today)
        return FundamentalsMerge(
            symbol = primary.symbol.ifBlank { secondary.symbol },
            asOf = asOf,
            fields = fields,
            ratios = ratios,
            report = DivergenceReport(
                symbol = primary.symbol.ifBlank { secondary.symbol },
                asOf = asOf,
                conflicts = conflictsOf(fields),
                unavailable = NOT_OBTAINABLE,
                notes = notesOf(primary, secondary, fields, today),
            ),
        )
    }

    /**
     * Merges a screener.in snapshot against a Yahoo snapshot, naming the sides so the argument
     * order cannot be misread at the call site.
     */
    fun mergeScreenerAgainstOther(
        screenerIn: Ratios,
        other: Ratios,
        today: LocalDate,
    ): FundamentalsMerge = merge(screenerIn, other, today)

    // ------------------------------------------------------------------ internals

    /**
     * Which argument holds the precedence side.
     *
     * plan.md §5.1 gives screener.in precedence for every field in [Ratios], so the side whose
     * `Ratios.source` is [Source.SCREENER_IN] is the precedence side. If neither side is tagged
     * (two anonymous snapshots) the first argument wins, which is the only remaining
     * deterministic rule — and it is documented rather than assumed.
     */
    private data class Sides(
        val precedence: Ratios,
        val other: Ratios,
        val precedenceSource: Source,
        val otherSource: Source,
        val precedenceIsFirst: Boolean,
    )

    private fun sidesOf(primary: Ratios, secondary: Ratios): Sides {
        val firstIsPage = primary.source == Source.SCREENER_IN
        val secondIsPage = secondary.source == Source.SCREENER_IN
        return when {
            firstIsPage && !secondIsPage -> Sides(primary, secondary, primary.source, secondary.source, true)
            secondIsPage && !firstIsPage -> Sides(secondary, primary, secondary.source, primary.source, false)
            else -> Sides(primary, secondary, primary.source, secondary.source, true)
        }
    }

    private fun mergeField(id: RatioFieldId, sides: Sides, today: LocalDate): RatioField {
        val shown = sides.precedence.valueOf(id)
        val other = sides.other.valueOf(id)
        return RatioField(
            id = id,
            merged = AggregationRule.merged(
                shown = shown,
                other = other,
                shownSource = sides.precedenceSource,
                otherSource = sides.otherSource,
                shownAsOf = sides.precedence.asOf,
                otherAsOf = sides.other.asOf,
                today = today,
                // A Ratios snapshot is a fundamentals page, so plan.md §10's 7-day window applies.
                kind = FreshnessKind.FUNDAMENTALS_SNAPSHOT,
            ),
            screenerIn = shown,
            other = other,
            divergence = AggregationRule.divergence(shown, other),
        )
    }

    /**
     * Folds the per-field rows back into one [Ratios].
     *
     * Lossy, and this KDoc is the warning label:
     * * one `Source` and one `asOf` for the whole snapshot, taken from the precedence side;
     * * one `confidence` for eighteen fields — the **worst** verdict among the rows, because a
     *   snapshot that hides a conflict behind an `AGREED` overall verdict would defeat the whole
     *   point of merging per field.
     */
    private fun collapse(primary: Ratios, secondary: Ratios, fields: List<RatioField>, today: LocalDate): Ratios {
        val sides = sidesOf(primary, secondary)
        var out = Ratios(
            symbol = primary.symbol.ifBlank { secondary.symbol },
            // plan.md §1.4 rule 8 and §7.1: default to OTHER, never guess. A resolved sector on
            // either side wins; an unresolved one does not block a resolved one.
            sector = sides.precedence.sector.takeIf { it != Sector.OTHER } ?: sides.other.sector,
            asOf = maxAsOf(primary.asOf, secondary.asOf),
            source = sides.precedenceSource,
            confidence = summaryConfidence(fields, sides, today),
        )
        for (field in fields) {
            out = out.withValue(field.id, field.value)
        }
        return out
    }

    /**
     * The worst verdict across the fields.
     *
     * `CONFLICT` beats `STALE` beats `AGREED` beats `SINGLE`. `SINGLE` last because it is the
     * weakest claim in the enum — it says a value exists and nothing contradicted it — so a
     * snapshot that is single-sourced everywhere is correctly labelled `SINGLE`.
     */
    private fun summaryConfidence(fields: List<RatioField>, sides: Sides, today: LocalDate): Confidence {
        val verdicts = fields.mapNotNull { it.confidence }
        return when {
            Confidence.CONFLICT in verdicts -> Confidence.CONFLICT
            Confidence.STALE in verdicts -> Confidence.STALE
            Confidence.AGREED in verdicts -> Confidence.AGREED
            verdicts.isEmpty() -> Confidence.SINGLE
            // Every row is SINGLE or missing: it is still stale as a whole if the pages are.
            else -> if (isStaleSnapshot(sides, today)) Confidence.STALE else Confidence.SINGLE
        }
    }

    private fun isStaleSnapshot(sides: Sides, today: LocalDate): Boolean =
        FreshnessPolicy.evaluate(sides.precedence.asOf, today, FreshnessKind.FUNDAMENTALS_SNAPSHOT) ==
            com.mia.app.domain.ratios.FreshnessState.STALE

    private fun conflictsOf(fields: List<RatioField>): List<DivergentField> =
        fields.mapNotNull { field ->
            val merged = field.merged ?: return@mapNotNull null
            val other = field.other ?: return@mapNotNull null
            if (!field.hasConflict) return@mapNotNull null
            DivergentField(
                field = field.id.name,
                shown = merged.value,
                shownSource = merged.source,
                alternate = other,
                alternateSource = merged.alternateSource ?: Source.YAHOO,
                divergence = field.divergence,
                unit = field.id.unit,
                asOf = merged.asOf,
            )
        }

    private fun notesOf(primary: Ratios, secondary: Ratios, fields: List<RatioField>, today: LocalDate): List<String> =
        buildList {
            add(
                "merged per field from ${primary.source} and ${secondary.source}; precedence is a " +
                    "property of the field (plan.md §5.1), not of the argument order",
            )
            add("divergence tolerance ${AggregationRule.DIVERGENCE_TOLERANCE_PERCENT}% relative to the " +
                "lower-precedence value; beyond it the primary value is shown with the other greyed " +
                "beneath it and never averaged")
            val conflicts = fields.filter { it.hasConflict }
            add(
                if (conflicts.isEmpty()) {
                    "no field disagreed beyond tolerance"
                } else {
                    "conflicting field(s): " + conflicts.joinToString(", ") {
                        "${it.id.title} ${"%.3f".format(it.value!!)} vs ${"%.3f".format(it.other!!)} " +
                            "(${"%.2f".format(it.divergencePercent!!)}% apart)"
                    }
                },
            )
            val single = fields.filter { it.isSingle && it.value != null }.map { it.id.title }
            add(
                if (single.isEmpty()) "every populated field was corroborated by a second source"
                else "single-sourced field(s), meaning nothing: ${single.joinToString(", ")}",
            )
            val staleness = FreshnessPolicy.inspect(
                maxAsOf(primary.asOf, secondary.asOf),
                today,
                FreshnessKind.FUNDAMENTALS_SNAPSHOT,
            )
            if (staleness.isStale) add(FreshnessPolicy.describe(staleness))
        }

    private fun maxAsOf(a: LocalDate, b: LocalDate): LocalDate = if (a.isAfter(b)) a else b

    /** [Ratios] → the field's value. One row per field, so one `when`. */
    private fun Ratios.valueOf(id: RatioFieldId): Double? = when (id) {
        RatioFieldId.ROCE -> roce
        RatioFieldId.ROE -> roe
        RatioFieldId.DEBT_TO_EQUITY -> debtToEquity
        RatioFieldId.INTEREST_COVERAGE -> interestCoverage
        RatioFieldId.OPERATING_MARGIN -> operatingMargin
        RatioFieldId.FCF -> fcf
        RatioFieldId.PAT -> pat
        RatioFieldId.CFO_TO_OP -> cfoToOp
        RatioFieldId.REVENUE_CAGR3Y -> revenueCagr3y
        RatioFieldId.REVENUE_GROWTH -> revenueGrowth
        RatioFieldId.GROSS_MARGIN -> grossMargin
        RatioFieldId.PE -> pe
        RatioFieldId.PB -> pb
        RatioFieldId.EV_TO_EBITDA -> evToEbitda
        RatioFieldId.DIVIDEND_YIELD -> dividendYield
        RatioFieldId.PROMOTER_HOLDING -> promoterHolding
        RatioFieldId.MARKET_CAP -> marketCap
    }

    private fun Ratios.withValue(id: RatioFieldId, value: Double?): Ratios = when (id) {
        RatioFieldId.ROCE -> copy(roce = value)
        RatioFieldId.ROE -> copy(roe = value)
        RatioFieldId.DEBT_TO_EQUITY -> copy(debtToEquity = value)
        RatioFieldId.INTEREST_COVERAGE -> copy(interestCoverage = value)
        RatioFieldId.OPERATING_MARGIN -> copy(operatingMargin = value)
        RatioFieldId.FCF -> copy(fcf = value)
        RatioFieldId.PAT -> copy(pat = value)
        RatioFieldId.CFO_TO_OP -> copy(cfoToOp = value)
        RatioFieldId.REVENUE_CAGR3Y -> copy(revenueCagr3y = value)
        RatioFieldId.REVENUE_GROWTH -> copy(revenueGrowth = value)
        RatioFieldId.GROSS_MARGIN -> copy(grossMargin = value)
        RatioFieldId.PE -> copy(pe = value)
        RatioFieldId.PB -> copy(pb = value)
        RatioFieldId.EV_TO_EBITDA -> copy(evToEbitda = value)
        RatioFieldId.DIVIDEND_YIELD -> copy(dividendYield = value)
        RatioFieldId.PROMOTER_HOLDING -> copy(promoterHolding = value)
        RatioFieldId.MARKET_CAP -> copy(marketCap = value)
    }
}