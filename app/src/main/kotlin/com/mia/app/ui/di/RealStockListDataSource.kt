package com.mia.app.ui.di

import com.mia.app.data.android.repo.CandleRepository
import com.mia.app.data.android.repo.DataFailure
import com.mia.app.data.android.repo.DataFailureReason
import com.mia.app.data.android.repo.DataResult
import com.mia.app.data.android.repo.RedFlagRepository
import com.mia.app.data.android.repo.SymbolRepository
import com.mia.app.data.android.repo.WatchlistEntry
import com.mia.app.domain.model.Timeframe
import com.mia.app.ui.stocklist.StockListBackgroundDispatcher
import com.mia.app.ui.stocklist.StockListDataSource
import com.mia.app.ui.stocklist.StockListUnavailableException
import com.mia.app.ui.stocklist.WatchlistRow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [StockListDataSource] over `:android-data`.
 *
 * ### Offline-first, and offline-only
 * Every read here is a cache read. `SymbolRepository.watchlist` is Room, `CandleRepository
 * .storedSeries` is Room and **never fetches**, and `RedFlagRepository.flags` reads what the engine
 * already has. Nothing in this class can issue a request, so the list renders instantly and
 * correctly with no network — which is what makes [WatchlistRow.isFromCache] `true` unconditionally
 * rather than a value that has to be discovered.
 *
 * ### Cost
 * One watchlist read plus, per tracked symbol, one two-bar read and one red-flag pass. A red-flag
 * pass builds an `IndicatorSnapshot`, so this is the one place where a per-row cost is genuinely
 * non-trivial; it is bounded by the number of symbols the user chose to track (decision OQ-4 makes
 * the nightly scan watchlist-only for the same reason), and it runs entirely on
 * [StockListBackgroundDispatcher] because it is IO against Room's own executor plus a CPU pass.
 *
 * ### The two honest absences
 * * no stored bar for a tracked symbol → `lastClose` and `changePercent` stay `null`. The row renders
 *   that as "no close stored yet". A `0.00` here would be a fabricated price for a listed company.
 * * no red-flag pass could run → [flagCoverageNotes] carries one line, and the row's count is `0`
 *   only because the chip has to render something. The two facts are distinguishable on screen by
 *   the presence of the coverage line, which is exactly what that method exists for.
 */
@Singleton
class RealStockListDataSource @Inject constructor(
    private val symbolRepository: SymbolRepository,
    private val candleRepository: CandleRepository,
    private val redFlagRepository: RedFlagRepository,
    @StockListBackgroundDispatcher private val backgroundDispatcher: CoroutineDispatcher,
) : StockListDataSource {

    override suspend fun tracked(): List<WatchlistRow> = onBackground {
        readRows(symbolRepository.watchlist().orThrowTyped())
    }

    override suspend fun add(symbol: String): WatchlistRow? = onBackground {
        val ticker = symbol.trim().uppercase()
        if (ticker.isEmpty()) return@onBackground null
        // Idempotent by construction: `WatchlistDao.insertIgnore` is `INSERT OR IGNORE`, so a double
        // tap on "add" cannot produce two rows, and the row returned below is the one already stored.
        val added = symbolRepository.addToWatchlist(listOf(ticker))
        val failure = added.failureOrNull()
        if (failure != null) {
            // `NOTHING_TO_DO` is the documented "not in the bundled NSE master" answer, and the
            // interface says `null` — "not tracked" is a real answer, not an error. Any other reason is
            // a real failure: an unreachable store must not read as "that ticker is not listed", which
            // is a statement about the market rather than about this device.
            if (failure.reason == DataFailureReason.NOTHING_TO_DO) return@onBackground null
            throw unavailable(failure)
        }
        val entry = added.valueOrNull()?.firstOrNull { candidate ->
            candidate.summary.symbol == ticker
        }
        if (entry == null) return@onBackground null
        readRows(listOf(entry)).firstOrNull()
    }

    override suspend fun remove(symbol: String): List<WatchlistRow> = onBackground {
        val ticker = symbol.trim().uppercase()
        if (ticker.isEmpty()) return@onBackground readRows(symbolRepository.watchlist().orThrowTyped())
        // Idempotent by construction: `WatchlistDao.delete` returns 0 for a row that is already gone
        // and that is not an error — removing a symbol twice is the natural result of two taps the
        // reader is sure they made.
        val removed = symbolRepository.removeFromWatchlist(listOf(ticker))
        removed.failureOrNull()?.let { throw unavailable(it) }
        readRows(symbolRepository.watchlist().orThrowTyped())
    }

    override suspend fun flagCoverageNotes(): List<String> = onBackground {
        val notes = mutableListOf<String>()
        val entries = symbolRepository.watchlist().orThrowTyped()
        for (entry in entries) {
            val ticker = entry.summary.symbol
            val result = redFlagRepository.flags(ticker, Timeframe.D1)
            val failure = result.failureOrNull()
            if (failure != null) {
                // A whole pass that could not run at all. plan.md §12: this must never render as
                // "no red flags", so it is a line above the list rather than a count of zero.
                notes += "$ticker — no red-flag check could run: ${failure.userFacingReason()}"
                continue
            }
            val view = result.valueOrNull() ?: continue
            if (view.complete) continue
            val parts = buildList {
                if (view.skipped.isNotEmpty()) {
                    add("${view.skipped.size} check(s) could not be evaluated")
                }
                if (view.failureReasons.isNotEmpty()) {
                    add("${view.failureReasons.size} check(s) failed to run")
                }
            }
            notes += "$ticker — red-flag coverage is incomplete: ${parts.joinToString(" and ")}"
        }
        notes
    }

    // --------------------------------------------------------------------------- internals

    /**
     * One row per tracked symbol, sorted by ticker.
     *
     * Sorted here rather than left to the screen: a watchlist that reorders itself between refreshes
     * is impossible to scan, and one sort in the data source is one sort.
     */
    private suspend fun readRows(entries: List<WatchlistEntry>): List<WatchlistRow> =
        entries.map { entry -> rowFor(entry) }.sortedBy { it.symbol }

    private suspend fun rowFor(entry: WatchlistEntry): WatchlistRow {
        val summary = entry.summary
        val ticker = summary.symbol
        val series = candleRepository.storedSeries(ticker, Timeframe.D1, CLOSE_BARS).valueOrNull()
        val candles = series?.ordered().orEmpty()

        val last = candles.lastOrNull()
        val previous = candles.getOrNull(candles.lastIndex - 1)
        val change = if (last != null && previous != null && previous.close > 0.0) {
            (last.close - previous.close) / previous.close * 100.0
        } else {
            // A percentage against nothing is a fabricated number, so a single stored bar gets `null`.
            null
        }

        val flagCount = redFlagRepository.flags(ticker, Timeframe.D1)
            .valueOrNull()
            ?.flags
            ?.size
            ?: 0

        return WatchlistRow(
            symbol = ticker,
            // Never blank: `WatchlistEntry.summary.name` falls back to the ticker in
            // `DefaultSymbolRepository.toEntry`, and `takeIf` covers a master row with an empty name.
            name = summary.name.takeIf { it.isNotBlank() } ?: ticker,
            series = summary.series,
            lastClose = last?.close,
            changePercent = change,
            redFlagCount = flagCount.coerceAtLeast(0),
            // `CandleSeries.asOf` is `candles.maxOf { it.date }`, so it is the newest *stored* bar's
            // trading day and never a fetch date.
            asOf = series?.asOf ?: last?.date,
            // Always true: every read above is a cache read and none of them fetches.
            isFromCache = true,
        )
    }

    private suspend fun <T> onBackground(block: suspend () -> T): T =
        withContext(backgroundDispatcher) { block() }

    private fun <T : Any> DataResult<T>.orThrowTyped(): T =
        valueOrNull() ?: throw unavailable(failureOrNull()!!)

    private fun unavailable(failure: DataFailure) = StockListUnavailableException(
        reason = failure.userFacingReason(),
        isTransient = failure.isWorthRetrying(),
    )

    private companion object {
        /**
         * Two bars, not one.
         *
         * A day change needs the previous close, so a limit of 1 would make every row's
         * `changePercent` `null` for no reason at all — and a `null` change on a row that *does* have
         * two closes stored is indistinguishable from a row with no history.
         */
        const val CLOSE_BARS: Int = 2
    }
}