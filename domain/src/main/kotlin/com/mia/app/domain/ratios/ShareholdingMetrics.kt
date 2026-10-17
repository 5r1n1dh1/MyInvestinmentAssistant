package com.mia.app.domain.ratios

import com.mia.app.domain.model.AnnualRow
import java.util.Locale

/**
 * One published shareholding figure: the holder's percentage of the company and the quarter that
 * published it.
 *
 * ### Why the period travels with the value
 * The `shareholding` card is **quarterly** — thirteen columns from `Sep 2023` to `Jun 2026` on
 * every one of the five golden fixtures — while `AnnualRow.values` is index-aligned with headers
 * the snapshot does not carry. An FII percentage without its quarter is a number of unknown date,
 * and the whole point of a *trend* is that the two endpoints are comparable periods. So a point is
 * only built when a header resolves to a real year and month; a value under an unresolvable
 * column is dropped rather than dated by position.
 *
 * ### Why [sortKey] is `year * 12 + month`
 * A financial-quarter pair is not monotone in the period-end date (`Mar 2026` is FY2026 Q4 and
 * sorts before `Jun 2026`, which is FY2027 Q1), which is the bug `ScreenerHtml.quarterOfMonth`
 * was introduced to stop repeating. Ordering on the calendar month keeps "the previous quarter" and
 * "the same quarter a year earlier" one comparison away and independent of column order.
 */
data class ShareholdingPoint(
    /** The source's own column label, e.g. `"Jun 2026"`. Shown verbatim (plan.md §1.4 rule 3). */
    val period: String,
    val year: Int,
    /** 1..12. 3 = the March quarter, which is also the fiscal year end. */
    val month: Int,
    /** Percentage of the company's shares held by this category, as published. */
    val percent: Double,
) {
    val sortKey: Long get() = year * 12L + month
}

/**
 * One holder's published series, oldest first, with unreadable quarters dropped.
 *
 * [holder] is the row label the source printed (`"FIIs"`, `"DIIs"`, `"Promoters"`) and
 * [publishedRowLabels] is every row the card did carry. Both exist for the same reason: an absence
 * note has to say **which** row it looked for and **what else was there**. "This page publishes no
 * FIIs row (it publishes Promoters, DIIs, Government, Public)" is a fact about the source;
 * "FII holding is 0" is a statement about the company. plan.md §12 is about not confusing them.
 */
data class ShareholdingSeries(
    val holder: String,
    val points: List<ShareholdingPoint> = emptyList(),
    /** `true` when the card published **this holder's** row, dated figures or not. */
    val rowPublished: Boolean = false,
    /** Every row label the card published, so an absent holder can name its neighbours. */
    val publishedRowLabels: List<String> = emptyList(),
) {
    /** No dated observation for this holder. Never a series of zeros — see the class KDoc. */
    val isEmpty: Boolean get() = points.isEmpty()

    /**
     * `true` when the card published this holder's row but no column resolved to a quarter.
     *
     * Carries [rowPublished] explicitly rather than inferring it from [publishedRowLabels] being
     * non-empty: every holder's series lists the *card's* rows, so inferring would report HDFCBANK's
     * missing `Promoters` row as "present but undated" — a statement about the bank that is simply
     * untrue, and one that would send a reader looking for a figure the company does not disclose.
     */
    val isUndated: Boolean get() = rowPublished && points.isEmpty()

    val latest: ShareholdingPoint? get() = points.lastOrNull()

    /** The quarter immediately before [latest] **in this series**, gaps dropped. */
    val previousQuarter: ShareholdingPoint? get() = points.getOrNull(points.size - 2)

    /** The same calendar quarter one year earlier, or `null` when the card does not reach back. */
    val sameQuarterPriorYear: ShareholdingPoint?
        get() = points.lastOrNull()?.let { last ->
            points.firstOrNull { it.year == last.year - 1 && it.month == last.month }
        }

    /** [latest] − [previousQuarter], in percentage points. */
    val changePercentagePoints: Double?
        get() {
            val now = latest ?: return null
            val before = previousQuarter ?: return null
            return now.percent - before.percent
        }

    /** What the card did publish, as a sentence fragment. Never an empty string. */
    val rowsPresent: String
        get() = publishedRowLabels.joinToString(", ").ifEmpty { "no data rows" }

    companion object {
        /** A holder with no card at all. Never a series of zeros — see the class KDoc. */
        fun absent(holder: String): ShareholdingSeries = ShareholdingSeries(holder)
    }
}

/**
 * The `shareholding` card's holder rows, period-resolved.
 *
 * ### Why this exists when `promoterHoldingTrend` already exists
 * It does not: this is the same read generalised past promoters. The card publishes an `FIIs` and
 * a `DIIs` row for **all five** fixtures, and both were parsed and thrown away, while the one row
 * that was consumed — promoters — is the single row HDFCBANK does *not* publish. The data on the
 * floor was therefore exactly the data the bank case needed, on a card that showed nothing for it.
 */
data class ShareholdingPattern(
    val fii: ShareholdingSeries = ShareholdingSeries.absent(ShareholdingPattern.FII_LABEL),
    val dii: ShareholdingSeries = ShareholdingSeries.absent(ShareholdingPattern.DII_LABEL),
    val promoters: ShareholdingSeries = ShareholdingSeries.absent(ShareholdingPattern.PROMOTER_LABEL),
) {
    val isEmpty: Boolean get() = fii.isEmpty && dii.isEmpty && promoters.isEmpty

    /** `true` when the card was read but no holder row could be dated. */
    val isUndated: Boolean get() = fii.isUndated && dii.isUndated && promoters.isUndated

    /** The series for one of the three holder labels, or `null` for anything else. */
    fun seriesOf(holder: String): ShareholdingSeries? = when (holder) {
        FII_LABEL -> fii
        DII_LABEL -> dii
        PROMOTER_LABEL -> promoters
        else -> null
    }

    companion object {
        /** No shareholding card at all — distinct from a card whose rows carry no quarter. */
        val EMPTY = ShareholdingPattern()

        /** The row labels this object reads, exactly as the source prints them. */
        const val FII_LABEL: String = "FIIs"
        const val DII_LABEL: String = "DIIs"
        const val PROMOTER_LABEL: String = "Promoters"
    }
}

/**
 * FII / DII holding and the quarter-on-quarter change in each.
 *
 * ### The one thing this must never do
 * Guess a quarter. The shareholding card carries no `data-date-key` attributes (verified across all
 * five fixtures), so the printed header is the only period information that exists. When the
 * headers are unavailable — which is the state a cached read is in, because
 * `Mappers.fromStatementRows` collapses the quarterly columns to one per fiscal year — every
 * function below answers `null` **with a reason** rather than treating the right-hand column as
 * "the latest quarter". A shareholding figure attached to the wrong date is not a stale number, it
 * is a wrong number, and it is the one failure mode that would make this whole card a source of
 * confident error.
 *
 * ### Percentage points, not percent
 * [RatioId.FII_TREND] is a change between two percentages. 18.67% → 17.19% is −1.48 points and
 * −7.9%; only the first says how much of the register actually moved.
 */
object ShareholdingMetrics {

    /**
     * The shareholding card, period-resolved.
     *
     * @param periods the table's column headers, oldest first, e.g.
     *   `["Sep 2023", …, "Jun 2026"]`. Empty means "we cannot date anything", which every
     *   downstream function then reports rather than working around.
     * @param shareholding the card's rows, exactly as `FundamentalsSnapshot.shareholding` carries
     *   them. `AnnualRow.values` is index-aligned with [periods].
     */
    fun of(periods: List<String>, shareholding: List<AnnualRow>): ShareholdingPattern {
        if (periods.isEmpty() || shareholding.isEmpty()) return ShareholdingPattern.EMPTY
        val present = shareholding.map { it.label.trim() }.filter { it.isNotEmpty() }
        return ShareholdingPattern(
            fii = seriesOf(ShareholdingPattern.FII_LABEL, periods, shareholding, present),
            dii = seriesOf(ShareholdingPattern.DII_LABEL, periods, shareholding, present),
            promoters = seriesOf(ShareholdingPattern.PROMOTER_LABEL, periods, shareholding, present),
        )
    }

    /** The latest published FII holding, in percent. */
    fun fiiHolding(pattern: ShareholdingPattern): Computation<Double> =
        latest(pattern.fii, "FII holding")

    /** The latest published DII holding, in percent. */
    fun diiHolding(pattern: ShareholdingPattern): Computation<Double> =
        latest(pattern.dii, "DII holding")

    /**
     * The latest published promoter holding, in percent.
     *
     * The same read [RatioComputation.promoterHolding] performs, over a card that has already been
     * dated. Two entry points exist because the older one takes the raw `(periods, rows)` pair and
     * is reachable from a snapshot while this one takes an already-resolved card; both go through
     * [seriesOf], so they cannot disagree.
     */
    fun promoterHolding(pattern: ShareholdingPattern): Computation<Double> =
        latest(pattern.promoters, "promoter holding")

    /**
     * The change in the FII share between the two most recent published quarters, in
     * **percentage points**.
     *
     * Consecutive quarters only, for the same reason [RatioComputation.revenueGrowth] is strictly
     * one year: comparing `Jun 2026` with `Dec 2025` because `Mar 2026` was unreadable would
     * report a two-quarter move as a one-quarter one.
     */
    fun fiiHoldingQuarterlyChange(pattern: ShareholdingPattern): Computation<Double> =
        quarterlyChange(pattern.fii, "the FII holding change")

    /** The same change for the domestic share. Not a `RatioId` today; it feeds the same card. */
    fun diiHoldingQuarterlyChange(pattern: ShareholdingPattern): Computation<Double> =
        quarterlyChange(pattern.dii, "the DII holding change")

    // ----------------------------------------------------------------------- internals

    private fun seriesOf(
        holder: String,
        periods: List<String>,
        rows: List<AnnualRow>,
        present: List<String>,
    ): ShareholdingSeries {
        val key = labelKeyOf(holder)
        val row = rows.firstOrNull { labelKeyOf(it.label) == key }
            ?: return ShareholdingSeries(holder, emptyList(), rowPublished = false, present)
        val points = mutableListOf<ShareholdingPoint>()
        for (index in row.values.indices) {
            val percent = row.values[index] ?: continue
            if (!percent.isFinite()) continue
            val label = periods.getOrNull(index) ?: continue
            val column = PeriodColumn.of(label)
            val year = column.year ?: continue
            points += ShareholdingPoint(
                period = label.trim(),
                year = year,
                month = column.month ?: FISCAL_YEAR_END,
                percent = percent,
            )
        }
        return ShareholdingSeries(
            holder = holder,
            points = points.sortedBy { it.sortKey },
            rowPublished = true,
            publishedRowLabels = present,
        )
    }

    private fun latest(series: ShareholdingSeries, label: String): Computation<Double> {
        val now = series.latest ?: return absent(series, label)
        return Computation.of(
            now.percent,
            "${series.holder} held ${trim(now.percent)}% of the company's shares in ${now.period}, " +
                "published by the shareholding-pattern card",
        )
    }

    private fun quarterlyChange(series: ShareholdingSeries, label: String): Computation<Double> {
        val now = series.latest ?: return absent(series, label)
        val before = series.previousQuarter ?: return Computation.missing(
            "the shareholding card publishes exactly one readable '${series.holder}' quarter " +
                "(${now.period}, ${trim(now.percent)}%), and a quarter-on-quarter change needs the " +
                "one before it, so $label is absent rather than 0",
        )
        val change = now.percent - before.percent
        return Computation.of(
            change,
            "${series.holder} holding ${trim(before.percent)}% in ${before.period} → " +
                "${trim(now.percent)}% in ${now.period}: ${signed(change)} percentage points across " +
                "one quarter",
        )
    }

    /**
     * Why a holder has no figure.
     *
     * Four states, kept distinct because each names a different thing that is absent, and a reader
     * who is told the wrong one goes looking in the wrong place:
     *
     * | state | what is actually missing |
     * |---|---|
     * | no card at all | nothing was read |
     * | no row for this holder | the company does not disclose that category |
     * | row present, headers undated | the figures exist but cannot be attached to a quarter |
     * | row present, headers dated, no readable cell | the row published only placeholders |
     */
    private fun absent(series: ShareholdingSeries, label: String): Computation<Double> =
        Computation.missing(
            when {
                series.publishedRowLabels.isEmpty() ->
                    "no shareholding-pattern card was read for this symbol, so $label is absent " +
                        "rather than 0"
                series.isUndated ->
                    "the shareholding-pattern card publishes a '${series.holder}' row but none of " +
                        "its columns resolved to a quarter, so $label cannot be dated and is absent " +
                        "rather than being attributed to the right-hand column (rows present: " +
                        "${series.rowsPresent})"
                series.rowPublished ->
                    "the shareholding-pattern card's '${series.holder}' row publishes no readable " +
                        "figure, so $label is absent rather than 0"
                else ->
                    "the shareholding-pattern card publishes no '${series.holder}' row (rows " +
                        "present: ${series.rowsPresent}), so $label is absent rather than 0 — a " +
                        "category the company does not disclose is not the same as a category it " +
                        "does not hold"
            },
        )

    private fun labelKeyOf(label: String?): String {
        val source = label.orEmpty()
        return buildString(source.length) {
            for (ch in source) if (ch.isLetterOrDigit()) append(ch.lowercaseChar())
        }
    }

    /** Two decimals at most, and no thousands separator: a shareholding figure is under 100. */
    private fun trim(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString()
        else String.format(Locale.ROOT, "%.2f", value)

    private fun signed(value: Double): String = (if (value >= 0) "+" else "") + trim(value)

    /** March, the Indian fiscal year end — the fallback when a header names a year but no month. */
    private const val FISCAL_YEAR_END: Int = RatioComputation.FISCAL_YEAR_END_MONTH
}