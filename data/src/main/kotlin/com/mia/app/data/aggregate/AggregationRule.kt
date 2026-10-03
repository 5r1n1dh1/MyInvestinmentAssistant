package com.mia.app.data.aggregate

import com.mia.app.domain.model.Confidence
import com.mia.app.domain.model.Merged
import com.mia.app.domain.model.Source
import com.mia.app.domain.ratios.FreshnessKind
import com.mia.app.domain.ratios.FreshnessPolicy
import java.time.LocalDate

/**
 * Which source wins **for one field**, as a stated fact rather than an argument order.
 *
 * plan.md §5.1's headline rule is *"merge per field, not per source"*. A merge that takes a
 * `primary` snapshot and uses its numbers wholesale is exactly the thing the plan forbids, and
 * the way to make that impossible is for the precedence to be a property of the **field**, not of
 * the call site. [FundamentalsAggregator] reads it off each field's id; so a caller that hands
 * the arguments the wrong way round gets the documented winner anyway.
 */
enum class FieldPrecedence(val note: String) {

    /**
     * OHLCV and delivery. plan.md §5.1: `price/OHLCV → NSE` (then Yahoo). The NSE bhavcopy is
     * the official end-of-day file, it needs no auth, one request covers the whole market, and
     * it is unadjusted — which is what makes it comparable with a backfilled Yahoo series.
     */
    PRICE_LAYER("NSE bhavcopy first, Yahoo Finance second (plan.md §5.1)"),

    /**
     * Everything plan.md §5.1 attributes to screener.in: PE/ROE/ROCE/BookValue, plus the
     * quarterly P&L, CFO/FCF/CFO-OP and shareholding that *"Yahoo has none"* of.
     */
    FUNDAMENTALS_PAGE(
        "screener.in's consolidated page is the only source of this field (plan.md §5.1: " +
            "quarterly P&L, CFO/FCF/CFO-OP and shareholding — Yahoo has none)",
    ),

    /**
     * A multiple computed from published figures rather than published itself (P/B is
     * `price ÷ book value`). It still resolves to the screener.in side, because both operands
     * come from that page, but it is called out so nobody later adds a Yahoo book value to it.
     */
    DERIVED_FROM_PRICE("computed from the screener.in page's own price and book value"),
}

/**
 * The one place two numbers from two sources are reconciled (plan.md §5.1).
 *
 * ### The measured rule, from plan.md §5.1
 *
 * | Field | screener.in | Yahoo | delta | verdict |
 * |---|---|---|---|---|
 * | RELIANCE price | 1168.0 | 1167.7 | 0.03% | agreed |
 * | RELIANCE P/E | 21.2 | 21.142 | 0.27% | agreed |
 * | RELIANCE ROE | 8.91 | null | — | single |
 * | TCS P/E | 14.0 | 15.083 | **7.18%** | **conflict** |
 * | TCS ROE | 51.8 | 47.743 | **8.50%** | **conflict** |
 *
 * ### Divergence is measured relative to the **lower-precedence** value
 *
 * ```
 * RELIANCE price  |1168.0 − 1167.7| ÷ 1167.7 = 0.0257%  → 0.03%
 * RELIANCE P/E    |21.2 − 21.142|  ÷ 21.142  = 0.2736%  → 0.27%
 * TCS P/E         |14.0 − 15.083|  ÷ 15.083  = 7.1801%  → 7.18%
 * TCS ROE         |51.8 − 47.743|  ÷ 47.743  = 8.4968%  → 8.50%
 * ```
 *
 * All four of the plan's numbers fall out of that one formula. Dividing by the *higher* value
 * instead would give TCS ROE 7.83% and not the 8.50% the plan recorded, and dividing by the
 * *shown* value would give TCS P/E 7.74% instead of 7.18%. Measuring against the value that is
 * being shown greyed underneath also makes the metric invariant to argument order: the same two
 * numbers give the same percentage whichever source is passed as `primary`.
 *
 * ### The three rules that follow
 * 1. **Within tolerance → [Confidence.AGREED]**, and the losing value is dropped. Two sources
 *    saying the same thing to within 5% is corroboration, not a second number to display.
 * 2. **Beyond tolerance → [Confidence.CONFLICT]**, the precedence value stays as
 *    [Merged.value] and the other becomes [Merged.alternate]. **Never averaged, never silently
 *    dropped** — plan.md §5.1: *"Do NOT average. Do NOT silently pick one."*
 * 3. **Only one side has the field → [Confidence.SINGLE]**, with no divergence claim at all.
 *    RELIANCE's ROE is the documented case: Yahoo publishes none, which says nothing about
 *    whether 8.91 is right.
 */
object AggregationRule {

    /**
     * plan.md §5.1's tolerance: *"On conflict (>5% divergence)"*. Stored as a fraction; the
     * comparison is strictly greater-than, so exactly 5.00% is **agreed** and 5.01% conflicts.
     */
    const val DIVERGENCE_TOLERANCE: Double = 0.05

    /** The same number in the units the UI renders. */
    const val DIVERGENCE_TOLERANCE_PERCENT: Double = DIVERGENCE_TOLERANCE * 100.0

    /**
     * `|shown − other| ÷ |other|`, as a **fraction**, or `null` when it is undefined.
     *
     * `null` happens for exactly two reasons: a side has no value (nothing to compare — the
     * field is [Confidence.SINGLE]) or the *other* side is zero (a ratio against zero has no
     * percentage, and inventing one would produce an infinite "divergence" chip). The second
     * case is still a conflict — 0 against 21 is not agreement — which is why [verdict] handles
     * it separately.
     */
    fun divergence(shown: Double?, other: Double?): Double? {
        val a = shown ?: return null
        val b = other ?: return null
        if (!a.isFinite() || !b.isFinite()) return null
        val denominator = kotlin.math.abs(b)
        if (denominator == 0.0) return null
        return kotlin.math.abs(a - b) / denominator
    }

    /** [divergence] in percent, for display. `null` when undefined. */
    fun divergencePercent(shown: Double?, other: Double?): Double? = divergence(shown, other)?.let { it * 100.0 }

    /**
     * The confidence for one field pair, **before** freshness is applied.
     *
     * * neither side present → `null` (no row to render)
     * * one side present → [Confidence.SINGLE]
     * * both, within [DIVERGENCE_TOLERANCE] → [Confidence.AGREED]
     * * both, beyond it → [Confidence.CONFLICT]
     */
    fun verdict(shown: Double?, other: Double?): Confidence? = when {
        shown == null && other == null -> null
        shown == null || other == null -> Confidence.SINGLE
        divergence(shown, other) == null ->
            // Undefined percentage: only possible when the alternate is zero. Disagreement is
            // total there, so it is still a conflict rather than an agreement we cannot measure.
            if (shown == other) Confidence.AGREED else Confidence.CONFLICT
        divergence(shown, other)!! > DIVERGENCE_TOLERANCE -> Confidence.CONFLICT
        else -> Confidence.AGREED
    }

    /**
     * Builds the [Merged] row for one field, applying plan.md §10's staleness rule last.
     *
     * Staleness comes from [FreshnessPolicy], which overrides provenance — including
     * [Confidence.CONFLICT] — because *"a conflict between two stale sources is not a conflict
     * worth showing — it's two old numbers"*. The disagreement is not thrown away by that: it is
     * computed independently and reported in [DivergenceReport], so the divergence chip still
     * appears on a stale figure.
     */
    fun merged(
        shown: Double?,
        other: Double?,
        shownSource: Source,
        otherSource: Source,
        shownAsOf: LocalDate?,
        otherAsOf: LocalDate?,
        today: LocalDate,
        kind: FreshnessKind,
    ): Merged<Double>? {
        val parsed = verdict(shown, other) ?: return null
        // With one side absent there is nothing to compare, so the SINGLE row is built by [single]
        // rather than by this function — one place decides its shape.
        if (parsed == Confidence.SINGLE) {
            val value = shown ?: other ?: return null
            return single(
                value = value,
                source = if (shown != null) shownSource else otherSource,
                asOf = if (shown != null) shownAsOf else otherAsOf,
                today = today,
                kind = kind,
            )
        }
        val alternate = if (parsed == Confidence.CONFLICT) other else null
        val asOf = if (shown != null) shownAsOf else otherAsOf
        val value = shown ?: other ?: return null
        val source = if (shown != null) shownSource else otherSource
        return Merged(
            value = value,
            source = source,
            confidence = FreshnessPolicy.confidenceFor(asOf, today, kind, parsed),
            asOf = asOf ?: today,
            alternate = alternate,
            alternateSource = if (alternate != null) otherSource else null,
        )
    }

    /**
     * A [Merged] row for a field only one source has.
     *
     * Exposed because [merged] already covers this case and a caller merging a bespoke pair of
     * numbers (a shareholding pattern, a quarterly row) should not have to fake a null on the
     * other side to reach [Confidence.SINGLE]. [merged] delegates here for the same reason, so
     * there is exactly one place that decides a [Confidence.SINGLE] row's shape.
     */
    fun single(value: Double?, source: Source, asOf: LocalDate?, today: LocalDate, kind: FreshnessKind): Merged<Double>? {
        if (value == null) return null
        return Merged(
            value = value,
            source = source,
            confidence = FreshnessPolicy.confidenceFor(asOf, today, kind, Confidence.SINGLE),
            asOf = asOf ?: today,
        )
    }

}

/**
 * One field where the two sources disagreed by more than 5% (plan.md §5.1).
 *
 * This is the data behind the **divergence chip**: the shown value stays on screen, and this row
 * is what gets rendered greyed beneath it. [chip] is that copy, written so that it states both
 * numbers, both sources and the gap — the pattern of a red-flag's [com.mia.app.domain.model.Evidence],
 * because a conflict shown without its arithmetic is just an assertion.
 */
data class DivergentField(
    val field: String,
    val shown: Double,
    val shownSource: Source,
    val alternate: Double,
    val alternateSource: Source,
    /** Fraction, `0.0501` = 5.01%. `null` when the alternate is zero and no percentage exists. */
    val divergence: Double?,
    val unit: String,
    val asOf: LocalDate,
) {
    /** `null` only when the alternate is zero, where a percentage would be meaningless. */
    val divergencePercent: Double? get() = divergence?.let { it * 100.0 }

    /** plan.md §5.1: the primary value plus the other one, greyed, with the gap spelled out. */
    fun chip(): String {
        val gap = divergencePercent?.let { " — %.2f%% apart".format(it) }
            ?: " — not comparable as a percentage"
        return "${format(shown)} ($shownSource) vs ${format(alternate)} ($alternateSource)$gap"
    }

    private fun format(value: Double): String =
        if (unit == "shares") value.toLong().toString() else "%.3f".format(value)
}

/**
 * A figure that plan.md's sources cannot produce, so that a blank is explained rather than
 * merely displayed.
 *
 * plan.md §13 lists *"misleading blank ratios → user distrusts app"* as a named risk. The cure is
 * not to fill the blank but to say which of the three things happened: not meaningful here,
 * no free source publishes it, or this page did not carry it.
 */
data class UnavailableField(val field: String, val reason: String)

/**
 * Every disagreement across a merge, plus what could not be obtained at all.
 *
 * The UI needs two things from the aggregation layer and this is both of them: the values
 * ([com.mia.app.domain.model.Merged.alternate] per field) and the *list* of fields where the two
 * sources fought, so the fundamentals tab can put a chip next to exactly those rows instead of
 * next to all sixteen.
 */
data class DivergenceReport(
    val symbol: String,
    val asOf: LocalDate,
    /** [AggregationRule.DIVERGENCE_TOLERANCE]. */
    val tolerance: Double = AggregationRule.DIVERGENCE_TOLERANCE,
    val conflicts: List<DivergentField> = emptyList(),
    val unavailable: List<UnavailableField> = emptyList(),
    val notes: List<String> = emptyList(),
) {
    val hasConflicts: Boolean get() = conflicts.isNotEmpty()
    val conflictCount: Int get() = conflicts.size
    val conflictFields: List<String> get() = conflicts.map { it.field }

    /** The chip for [field], or `null` when that field agreed. */
    fun chipFor(field: String): String? =
        conflicts.firstOrNull { it.field == field }?.chip()

    /** The most disagreeing fields first, so a UI with room for one chip shows the worst one. */
    fun worstFirst(): List<DivergentField> =
        conflicts.sortedByDescending { it.divergence ?: Double.POSITIVE_INFINITY }
}