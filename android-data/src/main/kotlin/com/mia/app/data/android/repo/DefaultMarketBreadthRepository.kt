package com.mia.app.data.android.repo

import com.mia.app.data.android.db.CandleDao
import com.mia.app.data.android.db.Mappers
import com.mia.app.domain.model.Timeframe
import com.mia.app.domain.technical.Breadth
import com.mia.app.domain.technical.InstrumentClose
import com.mia.app.domain.technical.MarketBreadth
import com.mia.app.work.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Market breadth over the whole market, computed from stored daily bars.
 *
 * ### Why breadth needs no network at all
 *
 * `DefaultCandleRepository.storeMarket` writes **every** row of each bhavcopy file to `candles`,
 * not just the watchlist's symbols — which means the table already holds the whole market for every
 * session it has seen. Breadth is therefore the one piece of market context this app can produce
 * from data it already has, with no new source, no rate limit and no possibility of an unreachable
 * dependency. That is why it is worth rendering at all when the index series (a network read) may or
 * may not be available.
 *
 * ### Why the two sessions are read separately
 *
 * A/D needs each symbol's **previous** close, and the bars table has no `prevClose` column — it is
 * derived from the prior session. So the computation reads the newest session and the one before it
 * and joins them in memory. That is two indexed queries rather than a window function, and it keeps
 * the arithmetic in `MarketBreadth`, which is where it is tested.
 *
 * ### The population is smaller than it looks, and says so
 *
 * The history-dependent statistics (% above SMA 50, new 52-week highs and lows) can only speak about
 * symbols this device has a full window for — on a fresh install, none of them. `MarketBreadth`
 * already reports `smaEligible` / `extremesEligible` alongside every figure, and those denominators
 * are carried through untouched so the card can state the population rather than imply the whole
 * market. Passing the unfiltered market as `histories` would be the easy version and would quietly
 * report "0% above its 50-day average, no data" as if it were a market fact.
 */
@Singleton
class DefaultMarketBreadthRepository @Inject constructor(
    private val candleDao: CandleDao,
    @IoDispatcher private val dispatcher: CoroutineDispatcher,
) {

    /** The newest stored daily session, or `null` when nothing is stored. */
    suspend fun latestSession(): LocalDate? = withContext(dispatcher) {
        candleDao.latestDate(Mappers.timeframeKey(Timeframe.D1))?.let { raw ->
            runCatching { LocalDate.parse(raw) }.getOrNull()
        }
    }

    /**
     * Breadth for the newest stored session, or `null` when no session is stored.
     *
     * `null` is a value, not a failure: a fresh install and a failed refresh both land here, and
     * the card renders "not measured yet" for both rather than a market of zero decliners.
     */
    suspend fun breadth(): Breadth? = withContext(dispatcher) {
        val key = Mappers.timeframeKey(Timeframe.D1)
        val latest = candleDao.latestDate(key)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: return@withContext null

        val day = candleDao.forDate(latest.toString())
        if (day.isEmpty()) return@withContext null

        val previous = priorSession(key, latest)
        val previousCloses = if (previous == null) {
            emptyMap()
        } else {
            candleDao.forDate(previous.toString()).associate { it.symbol to it.close }
        }

        MarketBreadth.compute(
            day = day.map {
                InstrumentClose(
                    symbol = it.symbol,
                    close = it.close,
                    prevClose = previousCloses[it.symbol],
                )
            },
            histories = historiesFor(day.map { it.symbol }, key, latest),
            asOf = latest,
        )
    }

    /**
     * The session before [latest], or `null` when [latest] is the only one stored.
     *
     * Derived from the table rather than assumed to be the previous calendar day: Indian markets
     * close on weekends and for holidays, so "yesterday" is routinely not a trading session and an
     * A/D computed against a non-session would classify every symbol as an advancer.
     */
    private suspend fun priorSession(key: String, latest: LocalDate): LocalDate? =
        candleDao.sessionBefore(key, latest.toString())

    /**
     * Stored closes per symbol, oldest first, bounded to the window the statistics actually need.
     *
     * Only symbols present in [symbols] are requested, and only over the dates range the two
     * statistics need. A fresh install therefore returns a map of short or empty lists rather than
     * a full-market history, which is what makes `smaEligible` small and the card honest about it.
     */
    private suspend fun historiesFor(
        symbols: List<String>,
        key: String,
        latest: LocalDate,
    ): Map<String, List<Double>> {
        val wanted = symbols.toSet()
        val from = latest.minusDays(HISTORY_CALENDAR_DAYS)
        // `closesBetween` already returns rows grouped by symbol and ordered by date within each
        // group, so `groupBy` here preserves that order. Breadth compares a close against its own
        // 50-day average and its own 52-week extreme, so a reversed series would produce numbers
        // that are plausible and wrong rather than failing.
        return candleDao.closesBetween(key, from.toString(), latest.toString())
            .asSequence()
            .filter { it.symbol in wanted }
            .groupBy({ it.symbol }, { it.close })
    }

    private companion object {
        /**
         * 260 calendar days, comfortably over the 250 sessions `MarketBreadth.MIN_SESSIONS_52_WEEK`
         * needs while staying inside one year. Using sessions rather than days would require reading
         * the table twice to count them.
         */
        const val HISTORY_CALENDAR_DAYS: Long = 260
    }
}