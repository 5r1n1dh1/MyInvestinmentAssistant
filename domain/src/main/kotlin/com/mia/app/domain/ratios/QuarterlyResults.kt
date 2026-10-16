package com.mia.app.domain.ratios

import com.mia.app.domain.model.QuarterRecord
import java.util.Locale

/**
 * The three quarterly lines the `quarters` card publishes on every one of the five golden
 * fixtures, each in its own unit.
 *
 * ### Why an enum rather than three hand-built cards
 * The card shows the same four things per metric — the latest figure, the quarter-on-quarter
 * change, the year-on-year change and a sparkline — and the metrics differ only in which
 * `QuarterRecord` field they read and what unit they print. An enum makes "which column does the
 * EPS card read" one line of data that a test can assert, instead of a `when` repeated in three
 * places where a typo would print crore for EPS.
 *
 * ### Why [OPERATING_PROFIT] can be absent and that is not a defect
 * HDFCBANK's `quarters` table publishes `Revenue / Interest / Expenses / Financing Profit /
 * Financing Margin %` and **no** `Operating Profit` row. Substituting `Financing Profit` would be
 * a bank showing a software company's margin line, which is precisely what plan.md §7.3 exists to
 * prevent. So the card for that metric reports `MISSING` with the substitution it refused to make.
 */
enum class QuarterlyMetric(
    val title: String,
    val unit: RatioUnit,
    /** What this line answers, so the card can say why it is on the page. */
    val rationale: String,
    private val read: (QuarterRecord) -> Double?,
) {
    SALES(
        title = "Sales",
        unit = RatioUnit.CRORE,
        rationale = "The top line, quarter by quarter, straight off the published P&L",
        read = { it.sales },
    ),

    OPERATING_PROFIT(
        title = "Operating profit",
        unit = RatioUnit.CRORE,
        rationale = "Profit before financing and tax, which is what separates margin from leverage",
        read = { it.operatingProfit },
    ),

    EPS(
        title = "EPS",
        unit = RatioUnit.RUPEE_PER_SHARE,
        rationale = "Earnings per share, the only quarterly line a shareholder actually receives",
        read = { it.eps },
    ),

    ;

    /** The published value for this metric in [quarter], or `null` when the row is absent. */
    fun of(quarter: QuarterRecord): Double? = read(quarter)
}

/**
 * One quarterly observation of one metric: the figure and the calendar quarter that published it.
 *
 * [QuarterRecord.year] / [QuarterRecord.quarter] are a **calendar** identity — `Jun 2026` is
 * `(2026, Q2)` — which is what makes them strictly increasing with the date. An Indian
 * financial-quarter pair would not be: `Mar 2026` is FY2026 Q4 and would sort before `Jun 2026`,
 * FY2027 Q1. Every comparison below is therefore one integer comparison on `year * 4 + quarter`.
 */
data class QuarterlyPoint(
    val year: Int,
    val quarter: Int,
    val value: Double,
) {
    /** Chronological key. Strictly increasing in the calendar quarter, which is the point. */
    val sortKey: Long get() = year * 4L + quarter

    val period: String get() = "${MONTH_ABBREVIATIONS[(quarter - 1).coerceIn(0, 3)]} $year"

    /** The label of the quarter immediately before this one, wrapping `Q1` to the prior year's Q4. */
    val previousPeriod: String
        get() = if (quarter == 1) "Dec ${year - 1}" else "${
            MONTH_ABBREVIATIONS[(quarter - 2).coerceIn(0, 3)]} $year"
        }

    companion object {
        private val MONTH_ABBREVIATIONS = listOf("Mar", "Jun", "Sep", "Dec")
    }
}

/**
 * One metric's quarterly picture: the latest figure, the two comparisons, and the series to draw.
 *
 * ### Why the two comparisons are separate fields and not one "growth" number
 * "Quarter on quarter" and "year on quarter" answer different questions and are not substitutes:
 * an Indian festival quarter is routinely 25–30% above the one before it and flat year on year.
 * Printing whichever is convenient would let a seasonal business look like it is accelerating
 * every quarter. When either endpoint is missing the field is `null` and [notes] says why — the
 * number of comparisons the card could make is itself reported.
 */
data class QuarterlyComparison(
    val metric: QuarterlyMetric,
    /** The most recent quarter that published this metric. */
    val latest: QuarterlyPoint?,
    /** The immediately preceding quarter, or `null` when the page does not publish it. */
    val previousQuarter: QuarterlyPoint?,
    /** The same calendar quarter one year earlier, or `null`. */
    val sameQuarterPriorYear: QuarterlyPoint?,
    /** `(latest ÷ previousQuarter − 1) × 100`. `null` when either endpoint is absent. */
    val quarterOverQuarterPercent: Double?,
    /** `(latest ÷ sameQuarterPriorYear − 1) × 100`. `null` when either endpoint is absent. */
    val yearOnYearPercent: Double?,
    /** Every readable quarter, **oldest first**, gaps dropped. The sparkline's input. */
    val series: List<QuarterlyPoint> = emptyList(),
    /** One sentence per comparison the card could not make. Empty when both were possible. */
    val notes: List<String> = emptyList(),
) {
    /** True when the card has nothing to draw. The section then states the reason instead. */
    val isEmpty: Boolean get() = latest == null && series.isEmpty()

    /** How many of the two comparisons were computable. Reported so a partial card says so. */
    val comparisonCount: Int
        get() = listOf(quarterOverQuarterPercent, yearOnYearPercent).count { it != null }
}

/**
 * Quarter-on-quarter and year-on-year for the published `quarters` card.
 *
 * ### Why nothing here is interpolated or carried forward
 * screener.in publishes thirteen quarters, and a company's share count changes between them (bonus
 * issues, splits, ESOPs) — so a missing quarter is a real gap, not a hole to fill. Forward-filling
 * would turn thirteen points into a smooth line that hides the very quarter a reader is looking
 * for. Gaps are dropped from the series and named in the note.
 *
 * ### Why a bank reports fewer comparisons rather than different ones
 * HDFCBANK publishes sales (as `Revenue`), no operating profit and EPS. Two of its three cards
 * therefore answer normally and one answers with a reason; nothing is substituted.
 */
object QuarterlyResults {

    /**
     * Every published quarter of one metric, oldest first.
     *
     * Exposed separately because the card's sparkline and its two comparisons are the same read at
     * different levels of aggregation, and computing the series twice is how they drift apart.
     */
    fun series(records: List<QuarterRecord>, metric: QuarterlyMetric): List<QuarterlyPoint> =
        records.mapNotNull { record ->
            val value = metric.of(record)
            if (value == null || !value.isFinite()) return@mapNotNull null
            QuarterlyPoint(year = record.year, quarter = record.quarter, value = value)
        }.sortedBy { it.sortKey }

    /**
     * [metric] across the published quarters, with both comparisons where the endpoints exist.
     *
     * A null [QuarterlyComparison.latest] with a populated [QuarterlyComparison.notes] is the
     * documented way to say "this page publishes no such row" — `Computation`'s own contract
     * requires a reason whenever a value is absent, and this type is its quarterly equivalent.
     */
    fun compare(records: List<QuarterRecord>, metric: QuarterlyMetric): QuarterlyComparison {
        val points = series(records, metric)
        if (points.isEmpty()) {
            return QuarterlyComparison(
                metric = metric,
                latest = null,
                previousQuarter = null,
                sameQuarterPriorYear = null,
                notes = listOf(absentReason(metric)),
            )
        }
        val latest = points.last()
        val previousQuarter = points.getOrNull(points.size - 2)
        val sameQuarterPriorYear = points.firstOrNull {
            it.year == latest.year - 1 && it.quarter == latest.quarter
        }
        val notes = mutableListOf<String>()
        val qoq = growth(latest, previousQuarter, "quarter on quarter", notes)
        val yoy = growth(latest, sameQuarterPriorYear, "year on year", notes)
        gaps(points, metric)?.let { notes += it }
        return QuarterlyComparison(
            metric = metric,
            latest = latest,
            previousQuarter = previousQuarter,
            sameQuarterPriorYear = sameQuarterPriorYear,
            quarterOverQuarterPercent = qoq,
            yearOnYearPercent = yoy,
            series = points,
            notes = notes,
        )
    }

    /** All three cards, in the order the section prints them. */
    fun all(records: List<QuarterRecord>): List<QuarterlyComparison> =
        QuarterlyMetric.entries.map { compare(records, it) }

    // ----------------------------------------------------------------------- internals

    /**
     * Names the quarters between the first and last readable point that publish nothing.
     *
     * Enumerating the expected quarters and subtracting the ones present is the only correct way to
     * say "there is a hole here": a point count would not distinguish a genuinely missing quarter
     * from a shorter series, and both look like "the page published less".
     */
    private fun gaps(points: List<QuarterlyPoint>, metric: QuarterlyMetric): String? {
        if (points.size < 2) return null
        val first = points.first().sortKey
        val last = points.last().sortKey
        val present = points.map { it.sortKey }.toSet()
        val missing = (first + 1 until last).filter { it !in present }
        if (missing.isEmpty()) return null
        return "quarter(s) ${missing.joinToString(", ") { labelOf(it) }} publish no " +
            "${metric.title.lowercase(Locale.ROOT)} figure and are skipped rather than filled in"
    }

    /** `"Jun 2026"` for a `year * 4 + quarter` key. The inverse of [QuarterlyPoint.sortKey]. */
    private fun labelOf(key: Long): String {
        val year = key / 4L
        val quarter = (key % 4L).toInt() + 1
        return QuarterlyPoint(year, quarter, 0.0).period
    }

    /**
     * `(now ÷ before − 1) × 100`, with the failure written down.
     *
     * A prior value of exactly zero has no growth rate, and a negative prior value makes the ratio
     * meaningless in the other direction — a bank's quarterly financing profit is negative on most
     * fixtures, and `−2,066 → −15,342` is a +642% "growth" that describes nothing. Both are
     * reported as an absent comparison rather than as a number.
     */
    private fun growth(
        now: QuarterlyPoint,
        before: QuarterlyPoint?,
        label: String,
        notes: MutableList<String>,
    ): Double? {
        if (before == null) {
            notes += "no $label comparison: the page publishes no ${now.previousPeriod} figure " +
                "for this line, so ${now.period} cannot be compared with it"
            return null
        }
        if (before.value == 0.0) {
            notes += "no $label comparison for '${now.period}': ${before.period} is published as " +
                "exactly 0, and a change from zero has no percentage"
            return null
        }
        if (before.value < 0.0 && now.value < 0.0) {
            notes += "no $label comparison for '${now.period}': both ${before.period} " +
                "(${trim(before.value)}) and ${now.period} (${trim(now.value)}) are negative, and " +
                "the percentage between two negative figures describes no change in magnitude"
            return null
        }
        return ((now.value / before.value) - 1.0) * 100.0
    }

    /**
     * Why a metric has no series at all, naming the row the page prints instead where one is known.
     */
    private fun absentReason(metric: QuarterlyMetric): String = when (metric) {
        QuarterlyMetric.OPERATING_PROFIT ->
            "this page's quarterly table publishes no 'Operating Profit' row. A bank publishes " +
                "'Financing Profit' and 'Financing Margin %' instead, which are not an operating " +
                "margin, so no figure is shown here rather than a substituted one (plan.md §7.3)"
        QuarterlyMetric.SALES ->
            "this page's quarterly table publishes no 'Sales' or 'Revenue' row, so the figure is " +
                "absent rather than 0"
        QuarterlyMetric.EPS ->
            "this page's quarterly table publishes no 'EPS in Rs' row, so the figure is absent " +
                "rather than 0"
    }

    /** Two decimals at most; no locale grouping, so a note reads the same on every device. */
    private fun trim(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString()
        else String.format(Locale.ROOT, "%.2f", value)
}