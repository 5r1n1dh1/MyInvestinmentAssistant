package com.mia.app.data.history

import com.mia.app.data.price.BhavcopyParseResult
import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.ParserHealth
import com.mia.app.domain.model.Source
import com.mia.app.domain.model.Timeframe
import com.mia.app.domain.technical.CandleResampler
import java.time.LocalDate

/**
 * Where a series on screen actually came from.
 *
 * plan.md §1.4 rule 3 requires the source next to every figure and plan.md §10 requires the "as
 * of" date; a chart is a figure. The important value here is [DERIVED_WEEKLY]: the 1W tab can be
 * served from daily bhavcopy bars when Yahoo is unavailable, and the user has to be told that the
 * weekly bars were *derived* rather than published as weekly bars by a vendor. A derived weekly
 * series and a vendor one differ in ways that matter — most obviously at a week boundary
 * containing a split.
 */
enum class HistoryProvenance(val source: Source, val label: String) {

    /** Backfilled from `query{1,2}.finance.yahoo.com` (plan.md §3.2). */
    YAHOO(Source.YAHOO, "Yahoo Finance history"),

    /** The official NSE end-of-day bhavcopy (plan.md §3.1). Unadjusted, whole-market. */
    NSE_BHAVCOPY(Source.NSE_BHAVCOPY, "NSE end-of-day bhavcopy"),

    /** Weekly bars aggregated from daily bars by [CandleResampler.toWeekly]. */
    DERIVED_WEEKLY(Source.DERIVED, "weekly bars derived from NSE daily bars"),
    ;

    /** True when the bars were computed here rather than published by a source. */
    val isDerived: Boolean get() = this == DERIVED_WEEKLY
}

/**
 * What the 1D and 1W tabs should render for one symbol, with the honesty attached.
 *
 * [notes] is not a debug channel. It is the copy the UI shows next to the chart: which source,
 * which date, how many bars, and — when the bars are short — that plan.md §6.4's hard gate is
 * not met, so a thin history reads as "not enough history" instead of producing a low-confidence
 * pattern score.
 */
data class HistoryPlan(
    val symbol: String,
    val timeframe: Timeframe,
    val candles: List<Candle>,
    val provenance: HistoryProvenance,
    /** Newest bar's date. The "as of" of plan.md §1.4 rule 3. */
    val asOf: LocalDate?,
    val notes: List<String>,
    val health: ParserHealth,
    /** Set when the series had to be replaced because Yahoo could not supply it. */
    val yahooFailure: YahooFailureReason? = null,
) {
    val bars: Int get() = candles.size

    val isEmpty: Boolean get() = candles.isEmpty()

    /**
     * plan.md §6.4's `barsSufficient` hard gate: `1D ≥ 60`, `1W ≥ 40`.
     *
     * Being false must stop pattern detection, not lower a score — plan.md §6.4: *"If unmet, the
     * app must say 'not enough history' rather than emit a low-confidence score."*
     */
    val barsSufficient: Boolean
        get() = candles.size >= minBarsFor(timeframe)

    /** One line the UI can put under the chart title. */
    fun summary(): String = buildString {
        append(provenance.label)
        asOf?.let { append(" · as of ").append(it) }
        append(" · ").append(bars).append(' ').append(timeframe.name).append(" bar(s)")
        if (!barsSufficient) {
            append(" · not enough history (").append(minBarsFor(timeframe)).append(" needed)")
        }
    }

    companion object {
        /** plan.md §6.4. */
        fun minBarsFor(timeframe: Timeframe): Int = when (timeframe) {
            Timeframe.D1 -> MIN_DAILY_BARS
            Timeframe.W1 -> MIN_WEEKLY_BARS
        }

        const val MIN_DAILY_BARS: Int = 60

        const val MIN_WEEKLY_BARS: Int = 40
    }
}

/**
 * Keeps the 1D and 1W tabs alive when Yahoo cannot answer (plan.md §3.2, §13).
 *
 * ### The problem this solves
 * Yahoo is the only source of a multi-year backfill and it is also the most fragile one in the
 * project: plan.md §13 names "Yahoo 429 / crumb changes" as a risk that kills history fetch
 * outright, and it was verified again on 2026-10-03 that `getcrumb` answers `429` from some
 * networks even with a correct cookie. If the app's history depended on that one call, a
 * rate-limited device would show an empty technicals tab forever.
 *
 * The bhavcopy is unaffected — plan.md §3.1 verifies ~18 months of daily files, one request for
 * the whole market. So the daily tab can always be served from NSE, and the weekly tab can be
 * **derived** from those same daily bars by [CandleResampler.toWeekly] (the domain resampler,
 * reused rather than reimplemented).
 *
 * ### What it will not do
 * It does not pretend the derivation is equivalent. [HistoryProvenance.DERIVED_WEEKLY] exists for
 * that, and every derived plan carries notes saying how many daily bars fed it. A 19-session
 * bhavcopy cache produces 4 weekly bars; the plan says so and [HistoryPlan.barsSufficient] is
 * false, which is the correct outcome — better a truthful "not enough history" than a chart that
 * suggests five years of context it does not have.
 *
 * ### Precedence
 * plan.md §5.1: price/OHLCV comes from NSE, with Yahoo as the fallback for the deep history.
 * When Yahoo has a usable series it wins the *series* (it is the only source with 500 bars), but
 * the per-field merge of the latest session close belongs to
 * `com.mia.app.data.aggregate.PriceAggregator`, and the notes say so rather than this class
 * quietly re-deciding precedence for the newest bar.
 */
object HistoryFallbackPlanner {

    /**
     * One plan per supported timeframe, always in `D1, W1` order.
     *
     * @param bhavcopyDaily every D1 bar this symbol has from the NSE cache. More than one day is
     *   expected: the daily worker appends, so the cache grows into the weekly derivation.
     * @param yahooDaily the `2y/1d` result, when one was fetched.
     * @param yahooWeekly the `5y/1wk` result, when one was fetched.
     */
    fun plans(
        symbol: String,
        bhavcopyDaily: List<Candle>,
        yahooDaily: HistoryResult? = null,
        yahooWeekly: HistoryResult? = null,
    ): List<HistoryPlan> = listOf(
        plan(symbol, Timeframe.D1, bhavcopyDaily, yahooDaily),
        plan(symbol, Timeframe.W1, bhavcopyDaily, yahooWeekly),
    )

    /**
     * The plan for one timeframe.
     *
     * Decision order, in full:
     * 1. a usable Yahoo series for this timeframe → [HistoryProvenance.YAHOO];
     * 2. otherwise, for `W1`, derive from the NSE daily bars → [HistoryProvenance.DERIVED_WEEKLY];
     * 3. otherwise serve the NSE bars directly → [HistoryProvenance.NSE_BHAVCOPY].
     *
     * A Yahoo result that exists but failed is *not* an error here: it is recorded in
     * [HistoryPlan.yahooFailure] and in the notes, and the tab is still served.
     */
    fun plan(
        symbol: String,
        timeframe: Timeframe,
        bhavcopyDaily: List<Candle>,
        yahoo: HistoryResult?,
    ): HistoryPlan {
        val notes = mutableListOf<String>()
        val dailySorted = bhavcopyDaily.filter { it.timeframe == Timeframe.D1 }.sortedBy { it.date }
        val nseBars = dailySorted.filter { it.symbol == symbol }
        val yahooUsable = yahoo != null && yahoo.isUsable

        if (yahooUsable) {
            notes += "history backfilled from Yahoo Finance: ${yahoo!!.candles.size} ${timeframe.name} " +
                "bar(s), range=${yahoo.range ?: "?"}, interval=${yahoo.interval ?: "?"}"
            if (yahoo.splits.isNotEmpty()) {
                notes += "series is UNADJUSTED and contains ${yahoo.splits.size} split event(s) " +
                    "(${yahoo.splits.joinToString(", ") { "${it.date} ${it.description}" }}); " +
                    "the NSE bhavcopy is unadjusted too (plan.md §5.1), so levels are comparable " +
                    "across the event only after the ratio is applied"
            }
            yahoo.health.notes.firstOrNull()?.let { notes += it }
            notes += "the newest session's close is merged per field by PriceAggregator (NSE " +
                "precedence, plan.md §5.1) — this series is the backfill, not the EOD value"
            return HistoryPlan(
                symbol = symbol,
                timeframe = timeframe,
                candles = yahoo.candles,
                provenance = HistoryProvenance.YAHOO,
                asOf = yahoo.asOf,
                notes = notes,
                health = yahoo.health,
            )
        }

        val yahooReason = yahoo?.failure
        if (yahoo != null && !yahooUsable) {
            notes += "Yahoo history unavailable (${yahoo.failure ?: "unusable"}): " +
                "${yahoo.health.notes.firstOrNull() ?: "no detail"} — serving this tab from the " +
                "NSE bhavcopy cache instead (plan.md §3.1 needs no auth and covers the whole market)"
        }

        if (nseBars.isEmpty()) {
            notes += "no candles for $symbol in the NSE bhavcopy cache either; the tab cannot be " +
                "served and must say so rather than draw an empty chart"
            return HistoryPlan(
                symbol = symbol,
                timeframe = timeframe,
                candles = emptyList(),
                provenance = HistoryProvenance.NSE_BHAVCOPY,
                asOf = null,
                notes = notes,
                health = ParserHealth.failed(notes.last()),
                yahooFailure = yahooReason,
            )
        }

        val minBars = HistoryPlan.minBarsFor(timeframe)
        val note = buildString {
            append(nseBars.size).append(" NSE bhavcopy D1 bar(s) cached for ").append(symbol)
            append(" (as of ").append(nseBars.last().date).append(')')
        }

        if (timeframe == Timeframe.W1) {
            val weekly = runCatching { CandleResampler.toWeekly(nseBars) }.getOrElse { error ->
                notes += "weekly derivation failed: ${error.javaClass.simpleName}: ${error.message}"
                return HistoryPlan(
                    symbol = symbol,
                    timeframe = timeframe,
                    candles = emptyList(),
                    provenance = HistoryProvenance.DERIVED_WEEKLY,
                    asOf = null,
                    notes = notes + "the W1 tab cannot be served",
                    health = ParserHealth.failed("weekly derivation failed: ${error.message}"),
                    yahooFailure = yahooReason,
                )
            }
            notes += "W1 is DERIVED, not published: ${weekly.size} weekly bar(s) aggregated from " +
                "$note by CandleResampler.toWeekly (ISO weeks, each bar dated the last session in " +
                "its week). Yahoo's own 1wk buckets could not be compared against this — the " +
                "endpoint answers 429 from this network (verified 2026-10-03) — so a Yahoo weekly " +
                "series may differ by up to one session at each boundary"
            notes += sufficiency(weekly.size, minBars, "weekly")
            return HistoryPlan(
                symbol = symbol,
                timeframe = timeframe,
                candles = weekly,
                provenance = HistoryProvenance.DERIVED_WEEKLY,
                asOf = weekly.lastOrNull()?.date,
                notes = notes,
                health = healthOf(weekly.size, minBars, notes),
                yahooFailure = yahooReason,
            )
        }

        notes += "D1 served directly from the NSE bhavcopy: $note (unadjusted, official EOD)"
        if (nseBars.any { it.deliveryPercent != null }) {
            val withDelivery = nseBars.count { it.deliveryPercent != null }
            notes += "$withDelivery of ${nseBars.size} bar(s) carry a delivery percentage from the " +
                "legacy sec_bhavdata file (plan.md §3.5, availability unverified there)"
        }
        notes += sufficiency(nseBars.size, minBars, "daily")
        return HistoryPlan(
            symbol = symbol,
            timeframe = timeframe,
            candles = nseBars,
            provenance = HistoryProvenance.NSE_BHAVCOPY,
            asOf = nseBars.last().date,
            notes = notes,
            health = healthOf(nseBars.size, minBars, notes),
            yahooFailure = yahooReason,
        )
    }

    /**
     * Convenience for the EOD worker: plans straight from a parsed bhavcopy file plus whatever
     * Yahoo produced.
     */
    fun plansFrom(
        bhavcopy: BhavcopyParseResult?,
        symbol: String,
        yahoo: HistorySeries?,
    ): List<HistoryPlan> = plans(
        symbol = symbol,
        bhavcopyDaily = bhavcopy?.candles.orEmpty(),
        yahooDaily = yahoo?.daily,
        yahooWeekly = yahoo?.weekly,
    )

    private fun sufficiency(bars: Int, minBars: Int, label: String): String = if (bars < minBars) {
        "$bars $label bar(s) is below plan.md §6.4's hard gate of $minBars: the app must say " +
            "\"not enough history\" and must not emit a pattern score from this series"
    } else {
        "$bars $label bar(s) clears plan.md §6.4's hard gate of $minBars"
    }

    private fun healthOf(bars: Int, minBars: Int, notes: List<String>): ParserHealth = when {
        bars <= 0 -> ParserHealth.failed(notes.last())
        // Bars present but below the detection gate is a *partial* parse: the data is real, the
        // series is just too short, and the distinction is exactly what §12 asks a health flag to
        // preserve.
        bars < minBars -> ParserHealth.partial(
            found = listOf("candles"),
            missing = emptyList(),
            note = notes.joinToString("; "),
        )
        else -> ParserHealth(ok = true, sectionsFound = listOf("candles"), notes = notes)
    }
}