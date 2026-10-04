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
 * What one history-deepening pass achieved.
 *
 * Reported so the UI can be specific about a short series: "280 of 60 required bars" and "the
 * archive reached back to 2025-04-01" is a *bounded* answer a reader can reason about, whereas
 * "not enough history" is a dead end that invites a refresh button which cannot help.
 */
data class HistoryBackfill(
    /** Sessions requested to fetch. */
    val requestedSessions: Int,
    /** Sessions actually downloaded (excludes weekends, holidays and already-stored days). */
    val sessionsFetched: Int,
    /** Sessions that 404'd: weekends, holidays, or days the archive has purged. */
    val sessionsSkipped: Int,
    /** Bar rows written across all symbols (whole market per session). */
    val barsWritten: Int,
    /** Rows that already existed and were left alone (the write-once guarantee). */
    val barsAlreadyKnown: Int,
    /** Oldest session now stored. `null` when nothing was written. */
    val earliestStored: LocalDate?,
    /** Newest session now stored. */
    val latestStored: LocalDate?,
    /** Per-session failures worth reporting, e.g. a host that was unreachable throughout. */
    val notes: List<String> = emptyList(),
) {
    /** A sentence for the settings/coverage panel. */
    fun summary(): String = buildString {
        append("$sessionsFetched session(s) fetched (")
        append(sessionsSkipped).append(" not published or already stored), ")
        append("$barsWritten bar(s) written across the whole market")
        if (earliestStored != null && latestStored != null) {
            append("; the stored history now spans ")
            append(earliestStored).append(" to ").append(latestStored)
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
     *
     * `NOT_ATTEMPTED` is returned rather than `NO_DATA_CACHED` when nothing has ever been
     * downloaded on this device, so the caller can offer a fetch instead of a dead end.
     */
    suspend fun storedSeries(
        symbol: String,
        timeframe: Timeframe,
        limit: Int = DEFAULT_SERIES_LIMIT,
    ): DataResult<CandleSeries>

    /**
     * Makes sure the symbol's bars are present and current enough to render — **fetching only
     * when the cache is stale**.
     *
     * ### The staleness rule
     * A fetch happens when, and only when, the newest stored bar is **older than the newest
     * session that should exist**: the last trading day, or today once today's bhavcopy has been
     * published. A cache that already covers that date is served without a request, so opening
     * ten stocks in a row costs one fetch, not ten.
     *
     * ### Why this method exists at all
     * The nightly worker alone left the app unusable on a fresh install: nothing is ever fetched
     * until the 19:00 IST run, so every tab said "no bars stored" and stayed that way for the
     * rest of the day. A read path with no write path behind it is a table that stays empty.
     *
     * ### Contract
     * * **Never throws.** A failed fetch returns [EnsureResult.failed] and leaves the cache
     *   exactly as it was — a network problem must never turn a working tab into an empty one.
     * * **Never overwrites.** All writes go through `INSERT OR IGNORE`.
     * * **Idempotent.** Calling it twice in a row performs one fetch.
     * * Does **not** deepen history; that is [backfillHistory]'s job. Fetching the whole market
     *   is one request, whereas history is many, so they are separate decisions.
     */
    suspend fun ensureData(
        symbol: String,
        today: LocalDate = todayInMarket(),
    ): EnsureResult

    /**
     * Deepens the price cache by walking backwards through the NSE archive, one whole-market
     * bhavcopy per session.
     *
     * ### Why this is market-wide rather than per symbol
     * Every bhavcopy file contains **every** instrument (plan.md §3.1: 3,712 rows, one fetch is
     * the whole market). So one pass of `sessions` requests yields `sessions` bars for *all*
     * ~3,050 retained symbols, not for one. Fetching 280 sessions to give RELIANCE 280 bars while
     * 2,999 other symbols get nothing would waste the single property that makes this source free.
     *
     * Yahoo is the plan's intended deep-history source (plan.md §3.2), but it answers 429 from
     * some networks, so the archive is the fallback that always works. [backfill] still runs and
     * still wins when Yahoo is reachable — this only fills what Yahoo could not.
     *
     * Already-known sessions are skipped without a request, so a re-run is nearly free.
     */
    suspend fun backfillHistory(
        sessions: Int = DEFAULT_HISTORY_SESSIONS,
        today: LocalDate = todayInMarket(),
    ): DataResult<HistoryBackfill>

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
     *
     * ### Why this is the per-symbol path and [backfillHistory] is not
     * [backfillHistory] walks the NSE archive, one **whole-market** bhavcopy per session, so it
     * deepens every symbol at once and costs one request per session. That is the cheap path and it
     * is the right one for the first run. But it is bounded by the archive walk's own cadence, and
     * it cannot be aimed at one ticker.
     *
     * This is the aimed path: Yahoo, one symbol, up to [DEFAULT_SERIES_LIMIT] bars, and it answers
     * only for the symbol named. A reader who has just opened a stock and wants *that* stock
     * analysed now should not have to wait for the nightly worker to reach it — which is what
     * makes this the method behind the manual "fetch history for this stock" action.
     *
     * Both exist because the sources fail differently. The archive always works but is
     * market-wide and slow to deepen; Yahoo is fast and precise but answers `429` from some
     * networks. The caller is expected to try this first and fall back, not to choose one.
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

        /**
         * How far back a first-run history pass walks.
         *
         * 280 sessions is chosen by arithmetic, not taste:
         * * it clears plan.md §6.4's 1D gate of 60 with a wide margin, so every symbol can be
         *   scored immediately;
         * * 280 daily bars aggregate to **~56 weekly bars**, which clears the W1 gate of 40 — the
         *   reason it is 280 and not the minimum that would satisfy the daily gate;
         * * it stays inside plan.md §3.1's measured retention of "roughly 18 months", so the walk
         *   is not mostly 404s at the tail;
         * * at ~208 KB per whole-market session that is ~58 MB, once, in the background.
         */
        const val DEFAULT_HISTORY_SESSIONS: Int = 280

        /**
         * How many sessions one call to [backfillHistory] will *request* in a single pass.
         *
         * ### Why this equals [DEFAULT_HISTORY_SESSIONS] and not less
         * This was previously `60`, applied as `sessions.coerceAtMost(HISTORY_BATCH_LIMIT)` — which
         * silently defeated the 280-session default: no caller could ever fetch more than 60
         * sessions, no matter what it asked for.
         *
         * 60 was also the worst possible ceiling, for two arithmetic reasons:
         * * it is **exactly** plan.md §6.4's 60-bar daily gate, so a single skipped session
         *   (holiday, archive purge, one failed request) left 59 bars and the tab reported
         *   "Not Enough History" *permanently*, with no retry able to fix it;
         * * 60 daily bars aggregate to ~12 weekly bars against a 40-bar weekly gate, so the `1W`
         *   tab could never clear its own gate at all.
         *
         * ### What this now means
         * A pass walks the whole requested depth in one call. It remains **resumable** — every
         * already-stored session is skipped without a request — so an interrupted pass continues
         * where it stopped rather than restarting, and a reader who needs more depth can ask for
         * more and get it.
         *
         * The value is still a ceiling rather than an unbounded walk: it bounds one pass's
         * request count so a misconfigured caller cannot trigger an entire 18-month archive pull.
         */
        const val HISTORY_BATCH_LIMIT: Int = 280
    }
}

/** Shared minimum-bar policy so the repository and the UI cannot disagree about the gate. */
fun minBarsFor(timeframe: Timeframe): Int = when (timeframe) {
    Timeframe.D1 -> CandleRepository.MIN_DAILY_BARS
    Timeframe.W1 -> CandleRepository.MIN_WEEKLY_BARS
}