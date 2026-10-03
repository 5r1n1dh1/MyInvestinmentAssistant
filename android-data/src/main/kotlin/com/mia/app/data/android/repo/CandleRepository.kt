package com.mia.app.data.android.repo

import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.ParserHealth
import com.mia.app.domain.model.Source
import com.mia.app.domain.model.Timeframe
import java.time.LocalDate

/**
 * A stored price series, with the honesty flags plan.md §6.4 and §10 require on screen.
 *
 * [barsSufficient] is carried rather than left for the UI to derive: plan.md §6.4 makes it a
 * *hard gate* — below it the app must say "not enough history" instead of emitting a
 * low-confidence pattern score — and a gate that each screen recomputes is a gate one screen
 * forgets.
 */
data class CandleSeries(
    val symbol: String,
    val timeframe: Timeframe,
    val candles: List<Candle>,
    /** Newest bar's date. The "as of" of plan.md §1.4 rule 3. */
    val asOf: LocalDate?,
    val barCount: Int,
    val minBars: Int,
    /** `false` means: do not run detection, say why. */
    val barsSufficient: Boolean,
    /** Which source each bar came from, when the series was merged (plan.md §5.1). */
    val sources: Set<Source> = emptySet(),
    val notes: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = candles.isEmpty()

    /** Bars in ascending date order, which is what every indicator expects. */
    fun ordered(): List<Candle> = candles.sortedBy { it.date }

    /** How many bars carry a published delivery percentage. 0 means plan.md §8.2 cannot fire. */
    fun barsWithDelivery(): Int = candles.count { it.deliveryPercent != null }
}

/**
 * What one EOD refresh achieved. Reported by `EodRefreshWorker` through `work_state`, so the UI
 * can say "refreshed 3,051 symbols as of 2026-10-01" instead of "refreshed".
 */
data class MarketRefresh(
    /** The session date the file was actually published for — **not** necessarily today. */
    val asOf: LocalDate,
    /** Bars written into the write-once `candles` table by this run. */
    val barsWritten: Int,
    /** Bars that already existed and were therefore left alone (the write-once guarantee). */
    val barsAlreadyKnown: Int,
    /** Symbols in the file after the `EQ`/`SM` filter. */
    val symbolsInFile: Int,
    /** How many bars got a delivery percentage attached from the legacy file. */
    val deliveryAttached: Int,
    /** Identity changes the `CorporateActionTracker` observed today. */
    val identityEvents: Int,
    val health: ParserHealth,
    val notes: List<String> = emptyList(),
)

/**
 * Where a series came from, as the UI must state it (plan.md §1.4 rule 3).
 *
 * This is a local projection of `:data`'s `HistoryProvenance` so that `:app` never imports
 * `:data` (docs/CONTRACTS.md). `DERIVED_WEEKLY` matters most: a weekly tab aggregated from
 * daily bars is not a vendor series, and saying so is mandatory.
 */
enum class SeriesOrigin(val label: String) {
    /** Backfilled from Yahoo Finance (plan.md §3.2). */
    YAHOO("Yahoo Finance history"),

    /** The official NSE end-of-day bhavcopy (plan.md §3.1). Unadjusted, whole-market. */
    NSE_BHAVCOPY("NSE end-of-day bhavcopy"),

    /** Weekly bars aggregated from NSE daily bars. Derived here, not published by anyone. */
    DERIVED_WEEKLY("weekly bars derived from NSE daily bars"),

    /** Merged from both, per field, NSE precedence (plan.md §5.1). */
    MERGED("NSE bhavcopy with a Yahoo backfill"),

    /** Served from the write-once local cache; no fetch was made or needed. */
    CACHE("on-device cache"),
}

/**
 * One timeframe's renderable series plus the sentence that belongs under its title.
 */
data class SeriesView(
    val symbol: String,
    val timeframe: Timeframe,
    val series: CandleSeries,
    val origin: SeriesOrigin,
    /** Number of fields where the sources disagreed by more than plan.md §5.1's 5 % tolerance. */
    val divergenceCount: Int = 0,
    val notes: List<String> = emptyList(),
) {
    /** The line plan.md §10 asks for under the chart title. */
    fun subtitle(): String = buildString {
        append(origin.label)
        series.asOf?.let { append(" · as of ").append(it) }
        append(" · ").append(series.barCount).append(' ').append(timeframe.name).append(" bar(s)")
        if (!series.barsSufficient) {
            append(" · not enough history (").append(series.minBars).append(" needed)")
        }
    }
}

/**
 * The only way `:app` reaches price data.
 *
 * ### Contract
 * * Every method is `suspend` and moves work off the caller's thread. Nothing here touches the
 *   main thread, and nothing here blocks.
 * * Every method returns a [DataResult]. There is no overload that returns a bare list, so
 *   "the source was unavailable" cannot arrive as an empty chart.
 * * Every method is cancellable: the suspend points are the coroutine's own, and none of them
 *   installs a non-cancellable block.
 */
interface CandleRepository {

    /**
     * The stored series for `(symbol, timeframe)`, newest first.
     *
     * Never fetches. `limit` bounds the read because plan.md §6.2 feeds the pattern engine 500
     * bars and a stock with three years of daily history has 750.
     */
    suspend fun storedSeries(
        symbol: String,
        timeframe: Timeframe,
        limit: Int = DEFAULT_SERIES_LIMIT,
    ): DataResult<CandleSeries>

    /**
     * What the 1D and 1W tabs should render, merged per field (plan.md §5.1).
     *
     * Reads only from storage: plan.md §3.1's bhavcopy and plan.md §3.2's backfill both land
     * through [refreshMarket] and [backfill], and this method is the offline-safe read path a
     * cold launch on a 2 GB phone with no connection depends on.
     */
    suspend fun seriesViews(symbol: String): DataResult<List<SeriesView>>

    /**
     * The newest session's close for `symbol`, preferring NSE (plan.md §5.1's price precedence).
     */
    suspend fun latestCandle(symbol: String): DataResult<Candle>

    /** Every symbol this device holds at least one bar for. Used to bound the detail screen. */
    suspend fun cachedSymbols(): DataResult<List<String>>

    /**
     * Fetches, parses and stores one session's whole-market bhavcopy. **This is what
     * `EodRefreshWorker` calls**; it is exposed here so `:app` can also offer a manual refresh.
     */
    suspend fun refreshMarket(today: LocalDate = todayInMarket()): DataResult<MarketRefresh>

    /**
     * Backfills history from Yahoo when the write-once cache is short (plan.md §3.2, phase P3).
     *
     * Never overwrites an existing bar: the cache is write-once, so a re-run of this method
     * writes only what is missing.
     */
    suspend fun backfill(
        symbol: String,
        timeframes: Set<Timeframe> = setOf(Timeframe.D1, Timeframe.W1),
    ): DataResult<Int>

    companion object {
        /** plan.md §3.2: 500 daily bars is the documented backfill shape. */
        const val DEFAULT_SERIES_LIMIT: Int = 500

        /** plan.md §6.4: 60 daily bars is the detection hard gate. */
        const val MIN_DAILY_BARS: Int = 60

        /** plan.md §6.4: 40 weekly bars. */
        const val MIN_WEEKLY_BARS: Int = 40
    }
}

/** Shared minimum-bar policy so the repository and the UI cannot disagree about the gate. */
fun minBarsFor(timeframe: Timeframe): Int = when (timeframe) {
    Timeframe.D1 -> CandleRepository.MIN_DAILY_BARS
    Timeframe.W1 -> CandleRepository.MIN_WEEKLY_BARS
}