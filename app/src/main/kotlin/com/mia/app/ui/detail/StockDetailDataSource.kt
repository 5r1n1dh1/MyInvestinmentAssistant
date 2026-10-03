package com.mia.app.ui.detail

import com.mia.app.domain.model.Source
import com.mia.app.domain.model.Timeframe
import com.mia.app.domain.ratios.Freshness
import com.mia.app.domain.ratios.FreshnessKind
import com.mia.app.domain.ratios.FreshnessPolicy
import dagger.Qualifier
import java.time.LocalDate

/**
 * The dispatcher the Stock Detail header's read runs on.
 *
 * The header needs one close, one change and one date per symbol, all from the on-device cache, so
 * the qualifier exists for symmetry with the other three screens rather than because the work is heavy.
 * It still matters: a Room read on the main thread during the first frame is a dropped frame on the
 * 2 GB-RAM devices plan.md §12 targets.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DetailBackgroundDispatcher

/**
 * What the Stock Detail **header** needs about a symbol.
 *
 * ### Why this is so narrow
 * The header shows four things: the ticker, the company name, the last close with its day change, and
 * the as-of date. Everything else lives in the tabs. Keeping this a four-field value means switching
 * the header's timeframe cannot pull a chart, an indicator pass or a red-flag evaluation into the
 * header's frame.
 *
 * @param asOf the trading day [lastClose] is from. `null` when nothing has been stored, which is a
 *   real state for a symbol added to the watchlist seconds ago and is rendered as an explained absence
 *   rather than as a blank.
 */
data class StockHeader(
    val symbol: String,
    /** Company name from the bundled NSE symbol master, or the ticker when the master has no row. */
    val name: String,
    val lastClose: Double?,
    /** Change against the previous close, in percent. `null` when either close is absent. */
    val changePercent: Double?,
    val asOf: LocalDate? = null,
) {
    init {
        require(symbol.isNotBlank()) { "a header without a ticker has no identity" }
    }

    /** `true` when there is a close to show. */
    val hasClose: Boolean get() = lastClose != null
}

/**
 * The Stock Detail screen's entire view of the outside world.
 *
 * ### Why the header is a separate read from the tabs
 * The tabs each own their own data source (`TechnicalsDataSource`, `FundamentalsDataSource`) and each
 * owns its own lifecycle. If the header reused the technicals one, the detail screen would have to
 * know how the technicals tab assembles a load in order to render four numbers, and a change to the
 * indicators pass would reach the header. This interface is four functions wide on purpose.
 *
 * ### Contract
 * * Every function is `suspend` and must already be off the main thread — the caller wraps the load
 *   in [DetailBackgroundDispatcher].
 * * `null` / empty means **"there is nothing", not "we failed"**. A function that *fails* throws
 *   [StockDetailUnavailableException].
 */
interface StockDetailDataSource {

    /**
     * The header for [symbol].
     *
     * **Offline-first**: this reads the on-device cache. There is no "loading" phase to show, and the
     * returned [StockHeader.asOf] is what the screen judges freshness against.
     */
    suspend fun header(symbol: String): StockHeader

    /**
     * The newest `asOf` this device holds for [symbol], or `null`.
     *
     * @param timeframe `D1` reads the daily bhavcopy cache, `W1` reads the resampled weekly series.
     *   They are stored separately, so a weekly view with no bars yet is a real answer and the header
     *   must not fall back to the daily date.
     */
    suspend fun asOf(symbol: String, timeframe: Timeframe): LocalDate?
}

/**
 * The exception every [StockDetailDataSource] implementation throws on a failed read.
 *
 * @param reason the sentence shown to the user, verbatim. Never an exception class name.
 * @param isTransient whether a retry button is worth offering.
 * @param isNotPublished **not** an error: plan.md §3.1's bhavcopy for today is simply not out yet at
 *   16:00 IST, so the correct UI is the last successful session with its as-of date, not a red banner.
 */
class StockDetailUnavailableException(
    val reason: String,
    val isTransient: Boolean = true,
    val isNotPublished: Boolean = false,
) : Exception(reason)

/**
 * Freshness for an EOD close shown in the header.
 *
 * `FreshnessKind.PRICE`, one-day window, market clock — the same verdict as the watchlist row for the
 * same figure, so the two screens can never disagree about whether a price is current. Sharing the
 * helper is the point: two copies of this arithmetic is two chances to show one number as fresh and
 * the other as stale.
 */
fun priceFreshnessForDetail(asOf: LocalDate): Freshness = FreshnessPolicy.inspect(
    asOf = asOf,
    today = FreshnessPolicy.todayInMarket(),
    kind = FreshnessKind.PRICE,
)

/** The source of an EOD close. plan.md §3.1. */
val BHAVCOPY_SOURCE: Source = Source.NSE_BHAVCOPY