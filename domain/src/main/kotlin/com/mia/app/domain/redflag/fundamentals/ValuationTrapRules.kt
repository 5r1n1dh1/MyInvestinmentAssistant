package com.mia.app.domain.redflag.fundamentals

import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.Evidence
import com.mia.app.domain.model.FlagGroup
import com.mia.app.domain.model.FlagSeverity
import com.mia.app.domain.model.FlagTab
import com.mia.app.domain.model.QuarterRecord
import com.mia.app.domain.model.RedFlag
import com.mia.app.domain.model.Sector
import com.mia.app.domain.model.Source
import com.mia.app.domain.model.Timeframe
import com.mia.app.domain.redflag.RedFlagContext
import com.mia.app.domain.ratios.RatioComputation
import com.mia.app.domain.ratios.YearValue
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * P/E below the sector band while ROCE or ROE declines — the value trap.
 *
 * **Book**, `[Ch.48 §Mistake 1 — The Value Trap]`: *"A Value Trap is a stock that appears
 * inexpensive but continues to destroy shareholder value."* Its warning signs, all of which this
 * rule refuses to substitute for the two conditions it actually tests: declining revenue,
 * declining margins, falling ROE, increasing debt, negative free cash flow, poor capital
 * allocation, frequent equity dilution, governance concerns. And the conclusion it draws is
 * about the reader, not the stock: *"A low valuation should trigger investigation, not
 * immediate buying."*
 *
 * ### Both conditions, never one
 * `[Ch.41 §Understanding the Meaning]`: *"A low P/E is not automatically cheap"* — a low
 * multiple can be perfectly justified by fast growth. So the flag requires the profitability
 * decline too. A cheap company that is improving is not what this flag is for.
 *
 * ### The band table is a substitute, and the evidence says so
 * plan.md §8.1 asks for `PE < sector median`. A sector median needs sector listing pages, which
 * are outside the one URL shape plan.md §3.3 permits us to read, so it cannot be computed today
 * and the comparison is against [FundamentalsPolicy.DERIVED_PE_BAND] instead — an absolute table
 * named `DERIVED_…` so no reader can mistake it for a measured median, with an evidence row
 * naming it as a derived starting point awaiting review. For [Sector.OTHER] the flag is
 * suppressed entirely rather than compared against a global number, per spec §6.2.
 *
 * ### ROCE, not ROE, unless the company publishes only ROE
 * spec §10 R3: the twelve-year series in the `ratios` card is `ROCE %` for everybody, and
 * `ROE %` only for banks (the HDFCBANK fixture's `ratios` card is exactly `['ROE %']`). plan.md
 * §7.2 also ranks ROCE above ROE for exactly the reason that matters here — it is harder to
 * manipulate. plan.md §8.1's phrase "ROE declining" is read as "the published profitability
 * series is declining".
 */
class LowPeWithFallingReturnsRule(
    private val ratiosCard: RatiosCardSeries? = null,
) : FundamentalsRule {

    override val id: String = FundamentalsFlagIds.LOW_PE_WITH_FALLING_RETURNS
    override val tab: FlagTab = FlagTab.FUNDAMENTALS

    override fun evaluate(context: RedFlagContext): List<RedFlag> {
        val band = FundamentalsPolicy.DERIVED_PE_BAND[context.sector] ?: return emptyList()
        val pe = context.ratios.pe ?: return emptyList()
        if (!pe.isFinite() || pe >= band) return emptyList()
        val metric = profitabilityMetric(context.sector)
        val points = pointsFor(metric, ratiosCard) ?: return emptyList()
        val trend = RatioComputation.fiveYearTrend(
            points = points,
            years = FundamentalsPolicy.PROFITABILITY_TREND_YEARS,
            label = metric.label,
        ).orNull() ?: return emptyList()
        val perYear = trend.changePerYear ?: return emptyList()
        if (perYear > FundamentalsPolicy.PROFITABILITY_TREND_TRIGGER_PP_PER_YEAR) return emptyList()

        val latest = trend.latest ?: return emptyList()
        val oldest = trend.oldest ?: return emptyList()
        val fiscal = FundamentalsEvidence.fyLabel(latest.year)
        val title = "P/E of ${FundamentalsEvidence.multiple(pe, 1)} is below the " +
            "${FundamentalsEvidence.sectorName(context.sector)} band of " +
            "${FundamentalsEvidence.multiple(band, 1)}, while ${metric.label} fell from " +
            "${FundamentalsEvidence.number(oldest.value, 1)} % in " +
            "${FundamentalsEvidence.fyLabel(oldest.year)} to " +
            "${FundamentalsEvidence.number(latest.value, 1)} % in $fiscal " +
            "(${FundamentalsEvidence.signed(perYear, 1)} percentage points a year)."

        val asOf = FundamentalsEvidence.fiscalYearEnd(latest.year)
        val rows = mutableListOf<Evidence>()
        // The P/E is a live scrape value with no publication stamp, so its as-of date is the
        // fetch date and must not be presented as a fiscal-year figure (spec §11 rule 9).
        rows += Evidence(
            "Stock P/E",
            FundamentalsEvidence.multiple(pe, 1),
            Source.SCREENER_IN,
            context.ratios.asOf,
        )
        for (point in trend.points) {
            rows += FundamentalsEvidence.statement(
                "${metric.label} (${FundamentalsEvidence.fyLabel(point.year)})",
                "${FundamentalsEvidence.number(point.value, 1)} %",
                point.year,
            )
        }
        rows += Evidence(
            "${metric.label} trend, ${FundamentalsPolicy.PROFITABILITY_TREND_YEARS} years",
            "${FundamentalsEvidence.signed(perYear, 1)} percentage points a year over " +
                "${trend.yearsSpanned} year(s)",
            Source.DERIVED,
            asOf,
        )
        rows += Evidence(
            "Sector band used",
            "${FundamentalsEvidence.sectorName(context.sector)}: P/E below " +
                "${FundamentalsEvidence.multiple(band, 1)}; from the derived band table, not a " +
                "measured sector median, because no sector listing page is in our source set. " +
                "Marked for review before shipping.",
            Source.DERIVED,
            context.ratios.asOf,
        )
        rows += Evidence(
            "Source of P/E",
            "screener.in 'top-ratios' card, a live scrape value read on " +
                FundamentalsEvidence.formatDate(context.ratios.asOf),
            Source.SCREENER_IN,
            context.ratios.asOf,
        )
        rows += Evidence(
            "Definition caveat",
            "A low multiple is not on its own a finding: the book notes a low P/E can be " +
                "justified by a high growth rate, which is why this check also requires the " +
                "profitability decline (Ch.41). A low valuation here is a prompt to investigate, " +
                "and this row is a comparison with a derived constant rather than with peers.",
            Source.DERIVED,
            asOf,
        )
        rows += context.sectorEvidenceRows()
        rows += FundamentalsEvidence.threshold("Threshold", THRESHOLD_TEXT, asOf)

        return listOf(
            RedFlag(
                id = id,
                group = FlagGroup.VALUATION_TRAP,
                severity = if (latest.value <= 0.0) FlagSeverity.HIGH else FlagSeverity.MEDIUM,
                tab = tab,
                title = title,
                evidence = rows,
                threshold = THRESHOLD_TEXT,
            ),
        )
    }

    override fun explainSkip(context: RedFlagContext): String? {
        if (context.sector !in FundamentalsPolicy.DERIVED_PE_BAND) {
            return "Not evaluated: no P/E band is defined for " +
                "${FundamentalsEvidence.sectorName(context.sector)}, and the band table is a " +
                "derived substitute for a sector median we cannot obtain. Comparing an " +
                "company to an unrelated sector's multiple is the comparison the book forbids " +
                "(Ch.17-R5)."
        }
        if (context.ratios.pe == null) {
            return "Not evaluated: this page publishes no 'Stock P/E'. The multiple is a live " +
                "scrape value and is never derived from price and earnings here, because the " +
                "price and the earnings would come from different dates."
        }
        val metric = profitabilityMetric(context.sector)
        if (pointsFor(metric, ratiosCard) == null) {
            return "Not evaluated: the ${metric.label} series has not been supplied, or carries fewer " +
                "than the two points a trend needs. This check reads the profitability series " +
                "published in the 'ratios' card, and the page publishes it as a percentage " +
                "series rather than as the single latest value in 'top-ratios'."
        }
        return null
    }

    /** Which published profitability series this sector's check reads. */
    private fun profitabilityMetric(sector: Sector): Metric =
        if (sector in FundamentalsPolicy.PROFITABILITY_FROM_ROE) Metric.ROE else Metric.ROCE

    private fun pointsFor(metric: Metric, card: RatiosCardSeries?): List<YearValue>? {
        val series = when (metric) {
            Metric.ROCE -> card?.rocePercent
            Metric.ROE -> card?.roePercent
        }.orEmpty()
        // A single point is a value, not a series: a trend needs two, and one point is a missing
        // input rather than a check that ran and found nothing.
        if (series.size < 2) return null
        return series.entries.map { (year, value) -> YearValue(year, value) }
    }

    /** The two published profitability series, named as the page names them. */
    private enum class Metric(val label: String) {
        ROCE("ROCE %"),
        ROE("ROE %"),
    }

    companion object {
        const val THRESHOLD_TEXT =
            "P/E below the sector band while ROCE or ROE declines by at least 1.0 percentage " +
                "point a year over three years"
    }
}

/**
 * Trailing EPS falling while the multiple on those earnings rises.
 *
 * **Book**: the mirror error of `[Ch.48 §Mistake 2 — The Growth Trap]` is the one this rule
 * catches — *"Historical P/E is meaningless when earnings are at a cyclical peak or trough."*
 *
 * ### How both figures are obtained, and why not from `ratios.pe`
 * plan.md §8.1's pair is "P/E rising while earnings fall", which needs a P/E at **two** points
 * in time. `top-ratios` publishes exactly one P/E, today, so it can supply the latest value and
 * nothing else; a title built from a single P/E would not contain the comparative plan.md §8.3
 * requires. So both multiples are computed the way the definition works:
 *
 * ```
 * EPS_ttm(i) = sum of EPS over the four quarters ending at quarter i
 * PE(i)      = cached close nearest quarter i / EPS_ttm(i)
 * ```
 *
 * with the close taken from our own candle cache — weekly preferred, daily as a fallback — within
 * [FundamentalsPolicy.PRICE_MATCH_TOLERANCE_DAYS] of the quarter-end date. If there is no cached
 * close near either quarter end, the point is absent and the flag is not computed: a P/E
 * reconstructed from an unrelated date's price is a different number, not a rounded one.
 *
 * ### The cyclicality carve-out
 * The single biggest false positive here is a cyclical trough: every commodity at the top of its
 * cycle has falling earnings and a rising multiple, and at the bottom it has the mirror image.
 * For the spec's cyclical sectors a shallower fall than −40% is treated as a trough and the flag
 * does not fire. The evidence always shows the **absolute** EPS figures, so a reader can see the
 * base the multiple was struck on.
 */
class PeRisingWhileEarningsFallRule : FundamentalsRule {

    override val id: String = FundamentalsFlagIds.PE_RISING_WHILE_EARNINGS_FALL
    override val tab: FlagTab = FlagTab.FUNDAMENTALS

    override fun evaluate(context: RedFlagContext): List<RedFlag> {
        val endpoints = endpoints(context) ?: return emptyList()
        val previous = endpoints.first
        val current = endpoints.second
        // Both halves must hold: trailing earnings *falling* and the multiple on them *rising*.
        // Either one alone is ordinary, so neither is enough.
        if (previous.epsTtm <= current.epsTtm) return emptyList()
        if (current.pe <= previous.pe) return emptyList()
        val epsChangePercent = (current.epsTtm - previous.epsTtm) / previous.epsTtm * 100.0
        if (context.sector in FundamentalsPolicy.CYCLICAL_SECTORS &&
            epsChangePercent > FundamentalsPolicy.CYCLICAL_TROUGH_MAX_FALL_PERCENT
        ) {
            return emptyList()
        }

        val title = "Trailing EPS fell from ${FundamentalsEvidence.rupees(previous.epsTtm)} to " +
            "${FundamentalsEvidence.rupees(current.epsTtm)} between " +
            "${FundamentalsEvidence.formatDate(previous.date)} and " +
            "${FundamentalsEvidence.formatDate(current.date)}, while P/E on the same earnings " +
            "rose from ${FundamentalsEvidence.multiple(previous.pe, 1)} to " +
            "${FundamentalsEvidence.multiple(current.pe, 1)}."

        val rows = mutableListOf(
            Evidence(
                "EPS, trailing four quarters to ${FundamentalsEvidence.formatDate(previous.date)}",
                FundamentalsEvidence.rupees(previous.epsTtm),
                Source.DERIVED,
                previous.date,
            ),
            Evidence(
                "EPS, trailing four quarters to ${FundamentalsEvidence.formatDate(current.date)}",
                FundamentalsEvidence.rupees(current.epsTtm),
                Source.DERIVED,
                current.date,
            ),
            Evidence(
                "P/E on those earnings (${FundamentalsEvidence.formatDate(previous.date)})",
                FundamentalsEvidence.multiple(previous.pe, 1),
                Source.DERIVED,
                previous.date,
            ),
            Evidence(
                "P/E on those earnings (${FundamentalsEvidence.formatDate(current.date)})",
                FundamentalsEvidence.multiple(current.pe, 1),
                Source.DERIVED,
                current.date,
            ),
            Evidence(
                "Change in P/E",
                FundamentalsEvidence.signed(current.pe - previous.pe, 1) + "x, from " +
                    FundamentalsEvidence.number(current.pe / previous.pe, 2) + " times the " +
                    "earlier multiple",
                Source.DERIVED,
                current.date,
            ),
            Evidence(
                "Change in EPS",
                "${FundamentalsEvidence.signed(epsChangePercent, 1)} %",
                Source.DERIVED,
                current.date,
            ),
            Evidence(
                "Earnings base",
                "${FundamentalsPolicy.EPS_TTM_QUARTERS} quarterly EPS figures from the 'quarters' " +
                    "table; the close for each multiple is the cached bar nearest that " +
                    "quarter's end date",
                Source.DERIVED,
                current.date,
            ),
            Evidence(
                "Cyclicality check",
                if (context.sector in FundamentalsPolicy.CYCLICAL_SECTORS) {
                    "${FundamentalsEvidence.sectorName(context.sector)} is treated as cyclical, " +
                        "and a trailing-EPS fall shallower than " +
                        "${FundamentalsEvidence.number(FundamentalsPolicy.CYCLICAL_TROUGH_MAX_FALL_PERCENT, 0)} % " +
                        "is treated as a trough rather than a fall"
                } else {
                    "${FundamentalsEvidence.sectorName(context.sector)} is not in the cyclical set, " +
                        "so the fall is evaluated as an ordinary one"
                },
                Source.DERIVED,
                current.date,
            ),
        )
        rows += context.sectorEvidenceRows()
        rows += FundamentalsEvidence.threshold("Threshold", THRESHOLD_TEXT, current.date)

        return listOf(
            RedFlag(
                id = id,
                group = FlagGroup.VALUATION_TRAP,
                severity = if (current.pe >= previous.pe * FundamentalsPolicy.PE_RISING_HIGH_MULTIPLE) {
                    FlagSeverity.HIGH
                } else {
                    FlagSeverity.MEDIUM
                },
                tab = tab,
                title = title,
                evidence = rows,
                threshold = THRESHOLD_TEXT,
            ),
        )
    }

    override fun explainSkip(context: RedFlagContext): String? {
        val ordered = orderedQuarters(context.quarters)
        if (ordered.size < FundamentalsPolicy.MIN_QUARTERS_FOR_PE_TREND) {
            return "Not evaluated: this check compares two multiples four quarters apart, so it " +
                "needs at least ${FundamentalsPolicy.MIN_QUARTERS_FOR_PE_TREND} quarterly EPS " +
                "figures; this context carries ${ordered.size}."
        }
        if (context.candles.isEmpty()) {
            return "Not evaluated: the multiple at each quarter end needs a cached close near " +
                "that date, and no price cache was supplied with this context."
        }
        if (endpoints(context) == null) return MISSING_INPUT
        return null
    }

    /** The two comparable `(quarter-end date, TTM EPS, P/E)` points, or `null`. */
    private fun endpoints(context: RedFlagContext): Pair<Endpoint, Endpoint>? {
        val ordered = orderedQuarters(context.quarters)
        if (ordered.size < FundamentalsPolicy.MIN_QUARTERS_FOR_PE_TREND) return null
        val latestIndex = ordered.size - 1
        val previousIndex = latestIndex - FundamentalsPolicy.PE_TREND_QUARTERS
        if (previousIndex < 0) return null

        val currentTtm = ttmEps(ordered, latestIndex) ?: return null
        val previousTtm = ttmEps(ordered, previousIndex) ?: return null
        val currentDate = quarterEnd(ordered[latestIndex]) ?: return null
        val previousDate = quarterEnd(ordered[previousIndex]) ?: return null
        val currentClose = closeNear(context.candles, currentDate) ?: return null
        val previousClose = closeNear(context.candles, previousDate) ?: return null
        if (currentClose <= 0.0 || previousClose <= 0.0) return null
        return Endpoint(previousDate, previousTtm, previousClose / previousTtm) to
            Endpoint(currentDate, currentTtm, currentClose / currentTtm)
    }

    /** Quarter-end date of a `(year, quarter)` pair, using calendar quarters. */
    private fun quarterEnd(quarter: QuarterRecord): LocalDate? = when (quarter.quarter) {
        1 -> LocalDate.of(quarter.year, 3, 31)
        2 -> LocalDate.of(quarter.year, 6, 30)
        3 -> LocalDate.of(quarter.year, 9, 30)
        4 -> LocalDate.of(quarter.year, 12, 31)
        else -> null
    }

    /** Oldest first, so index arithmetic means "four quarters earlier". */
    private fun orderedQuarters(quarters: List<QuarterRecord>): List<QuarterRecord> =
        quarters.sortedWith(compareBy({ it.year }, { it.quarter }))

    /**
     * The sum of EPS over the four quarters ending at [index], or `null` when any of the four is
     * absent or non-positive.
     *
     * A negative or absent quarterly EPS makes the ratio undefined — trailing EPS of zero or less
     * has no multiple — so the whole comparison is abandoned rather than reported with a
     * negative denominator, which is what a naive division would print.
     */
    private fun ttmEps(ordered: List<QuarterRecord>, index: Int): Double? {
        val from = index - FundamentalsPolicy.EPS_TTM_QUARTERS + 1
        if (from < 0) return null
        var total = 0.0
        for (i in from..index) {
            val eps = ordered[i].eps ?: return null
            if (!eps.isFinite() || eps <= 0.0) return null
            total += eps
        }
        return total
    }

    /**
     * The cached close nearest [date]: weekly bar preferred, daily as a fallback, and `null`
     * when nothing falls inside the tolerance.
     *
     * Weekly first because the spec says so and because a weekly bar is the price the market
     * actually set nearest that date; the daily bar is the better-defined fallback for a company
     * with fewer than a year of weekly history.
     */
    private fun closeNear(candles: List<Candle>, date: LocalDate): Double? {
        val tolerance = FundamentalsPolicy.PRICE_MATCH_TOLERANCE_DAYS
        return nearestClose(candles, Timeframe.W1, date, tolerance)
            ?: nearestClose(candles, Timeframe.D1, date, tolerance)
    }

    private fun nearestClose(
        candles: List<Candle>,
        timeframe: Timeframe,
        date: LocalDate,
        tolerance: Int,
    ): Double? {
        var best: Candle? = null
        var bestDistance = Long.MAX_VALUE
        for (candle in candles) {
            if (candle.timeframe != timeframe || !candle.isValid) continue
            val distance = abs(ChronoUnit.DAYS.between(candle.date, date))
            if (distance > tolerance || distance >= bestDistance) continue
            best = candle
            bestDistance = distance
        }
        return best?.close
    }

    /** One comparable point: the quarter end, the trailing EPS, and the multiple struck on it. */
    private data class Endpoint(val date: LocalDate, val epsTtm: Double, val pe: Double)

    companion object {
        const val THRESHOLD_TEXT =
            "P/E rising over 4 quarters while trailing EPS falls"

        const val MISSING_INPUT =
            "Not evaluated: a P/E at each of two quarter-end dates four quarters apart needs " +
                "four positive quarterly EPS figures ending at each date and a cached close " +
                "near both dates. Any missing or non-positive EPS, or a missing close, makes the " +
                "comparison undefined and the flag is not computed."
    }
}