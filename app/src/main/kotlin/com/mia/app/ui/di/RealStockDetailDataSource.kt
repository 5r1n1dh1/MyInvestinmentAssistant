package com.mia.app.ui.di

import com.mia.app.data.android.repo.CandleRepository
import com.mia.app.data.android.repo.DataFailure
import com.mia.app.data.android.repo.SymbolRepository
import com.mia.app.domain.model.Timeframe
import com.mia.app.ui.detail.DetailBackgroundDispatcher
import com.mia.app.ui.detail.StockDetailDataSource
import com.mia.app.ui.detail.StockDetailUnavailableException
import com.mia.app.ui.detail.StockHeader
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [StockDetailDataSource] over `:android-data`.
 *
 * ### Deliberately four fields wide
 * The header needs the ticker, the company name, the last close, the day's change and the as-of
 * date — nothing else. Everything heavier belongs to the tabs, and each tab owns its own data source,
 * its own ViewModel and its own refresh policy. Keeping this narrow is what stops a change to the
 * indicator pass from reaching the header, which is the reason the interface exists at all.
 *
 * ### The name is a fallback chain, not a lookup
 * `SymbolRepository.record` prefers the bundled master and falls back to the durable `symbols` row,
 * which is what keeps a delisted ticker resolvable (plan.md §9). A symbol in neither is a real
 * answer — the ticker **is** the identity — so the name falls back to the ticker rather than failing,
 * and the header renders `RELIANCE` once instead of an error card.
 *
 * ### No stored bar is an explained absence, not a failure
 * A symbol added to the tracked list seconds ago has no bar yet, and `StockDetailScreen` renders
 * exactly that (`NO_CLOSE_REASON`: the bhavcopy is fetched once a day after close). Throwing would
 * replace a correct sentence with a red banner about a read that succeeded.
 *
 * @param backgroundDispatcher the header's reads are Room queries, so IO is the honest pool: a Room
 *   read on the main thread during the first frame is a dropped frame on the 2 GB devices plan.md §12
 *   targets.
 */
@Singleton
class RealStockDetailDataSource @Inject constructor(
    private val symbolRepository: SymbolRepository,
    private val candleRepository: CandleRepository,
    @DetailBackgroundDispatcher private val backgroundDispatcher: CoroutineDispatcher,
) : StockDetailDataSource {

    override suspend fun header(symbol: String): StockHeader = onBackground {
        val ticker = symbol.trim().uppercase()
        try {
            // Stale-only freshness check before reading the cache.
            //
            // The header is where a reader is closest to wanting a price — they have just searched
            // and tapped through — so it is the natural place to notice the table is behind. It
            // fetches only when the newest stored session predates the newest expected one, so
            // re-opening a stock from a warm cache costs nothing. Its failure is deliberately ignored:
            // the `storedSeries` read below then reports the real reason, which is more useful than
            // collapsing "the network is down" into "the header could not be built".
            runCatching { candleRepository.ensureData(ticker) }

            // `record` fails with `NOTHING_TO_DO` for a ticker in neither the master nor the
            // database. That is not a failed read of the header — it is the absence of a *name*.
            // Any other reason it reports (a missing bundled master) is a real failure and is
            // surfaced below, because a header that can never name a company is a broken screen.
            val name = symbolRepository.record(ticker)
                .valueOrNull()
                ?.name
                ?.takeIf { it.isNotBlank() }
                ?: ticker

            val candles = candleRepository.storedSeries(ticker, Timeframe.D1, CLOSE_BARS)
                .orNullWhenNothingStored()
                ?.ordered()
                .orEmpty()

            val last = candles.lastOrNull()
            val previous = candles.getOrNull(candles.lastIndex - 1)
            val change = if (last != null && previous != null && previous.close > 0.0) {
                (last.close - previous.close) / previous.close * 100.0
            } else {
                // A percentage against nothing is a fabricated number: a single stored bar gets `null`.
                null
            }

            StockHeader(
                symbol = ticker,
                name = name,
                lastClose = last?.close,
                changePercent = change,
                asOf = last?.date,
            )
        } catch (raw: DataFailureException) {
            throw unavailable(raw.failure)
        }
    }

    override suspend fun asOf(symbol: String, timeframe: Timeframe): LocalDate? = onBackground {
        // The two timeframes are stored separately (plan.md §3.2's backfill writes `D1` and `W1`
        // rows), so a weekly view with no bars yet is `null` and **must not** fall back to the daily
        // date: the header would then claim a weekly bar exists for a session that has none.
        try {
            candleRepository.storedSeries(symbol.trim().uppercase(), timeframe)
                .orNullWhenNothingStored()
                ?.asOf
        } catch (raw: DataFailureException) {
            throw unavailable(raw.failure)
        }
    }

    private suspend fun <T> onBackground(block: suspend () -> T): T =
        withContext(backgroundDispatcher) { block() }

    private fun unavailable(failure: DataFailure) = StockDetailUnavailableException(
        reason = failure.userFacingReason(),
        isTransient = failure.isWorthRetrying(),
        isNotPublished = failure.isNotPublishedYet(),
    )

    private companion object {
        /** Two bars, so the day's change has a previous close to measure against. */
        const val CLOSE_BARS: Int = 2
    }
}