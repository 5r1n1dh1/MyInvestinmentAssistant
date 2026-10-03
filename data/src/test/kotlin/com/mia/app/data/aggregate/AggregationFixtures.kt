package com.mia.app.data.aggregate

import com.mia.app.data.history.HistoryResult
import com.mia.app.data.history.YahooFailureReason
import com.mia.app.data.price.BhavcopyParseResult
import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.Confidence
import com.mia.app.domain.model.Ratios
import com.mia.app.domain.model.Sector
import com.mia.app.domain.model.Source
import com.mia.app.domain.model.ParserHealth
import com.mia.app.domain.model.Timeframe
import java.time.LocalDate

/**
 * Shared builders for the aggregation tests.
 *
 * Every snapshot is stamped with [AS_OF] so freshness is a non-issue by default and the tests
 * about *staleness* are the ones that move the date. That separation matters: a test that fails
 * because a clock moved is worse than no test.
 */
internal object AggregationFixtures {

    val AS_OF: LocalDate = LocalDate.of(2026, 10, 2)

    fun screenerIn(
        symbol: String = "RELIANCE",
        asOf: LocalDate = AS_OF,
        roce: Double? = 10.3,
        roe: Double? = 8.91,
        pe: Double? = 21.2,
        pb: Double? = 1.749,
        dividendYield: Double? = null,
        marketCap: Double? = 1_832_997.0,
        promoterHolding: Double? = 50.3,
        operatingMargin: Double? = 17.5,
        fcf: Double? = 42_000.0,
        pat: Double? = 81_309.0,
        cfoToOp: Double? = 1.09,
        revenueCagr3y: Double? = 14.4,
        revenueGrowth: Double? = 8.9,
        interestCoverage: Double? = 9.2,
    ): Ratios = Ratios(
        symbol = symbol,
        sector = Sector.OTHER,
        asOf = asOf,
        source = Source.SCREENER_IN,
        confidence = Confidence.SINGLE,
        roce = roce,
        roe = roe,
        debtToEquity = null,
        interestCoverage = interestCoverage,
        operatingMargin = operatingMargin,
        fcf = fcf,
        pat = pat,
        cfoToOp = cfoToOp,
        revenueCagr3y = revenueCagr3y,
        revenueGrowth = revenueGrowth,
        grossMargin = null,
        pe = pe,
        pb = pb,
        evToEbitda = null,
        dividendYield = dividendYield,
        promoterHolding = promoterHolding,
        marketCap = marketCap,
    )

    /** The Yahoo side of plan.md §5.1: P/E and ROE only, everything else absent. */
    fun yahoo(
        symbol: String = "RELIANCE",
        asOf: LocalDate = AS_OF,
        roe: Double? = null,
        pe: Double? = 21.142,
        roce: Double? = null,
    ): Ratios = Ratios(
        symbol = symbol,
        sector = Sector.OTHER,
        asOf = asOf,
        source = Source.YAHOO,
        confidence = Confidence.SINGLE,
        roce = roce,
        roe = roe,
        pe = pe,
    )

    fun candle(
        symbol: String = "RELIANCE",
        date: LocalDate = LocalDate.of(2026, 10, 1),
        open: Double = 1180.10,
        high: Double = 1183.90,
        low: Double = 1160.80,
        close: Double = 1167.70,
        volume: Long = 16_771_221L,
        deliveryPercent: Double? = null,
        source: Source = Source.NSE_BHAVCOPY,
    ): Candle = Candle(
        symbol = symbol,
        timeframe = Timeframe.D1,
        date = date,
        open = open,
        high = high,
        low = low,
        close = close,
        volume = volume,
        deliveryPercent = deliveryPercent,
        source = source,
    )

    /** A parse result wrapping [candles]; the symbol follows the first candle. */
    fun bhavcopy(candles: List<Candle>): BhavcopyParseResult =
        BhavcopyParseResult(
            candles = candles,
            health = ParserHealth(ok = true, sectionsFound = listOf("TCKRSYMB")),
            rowsTotal = candles.size,
            tradDt = candles.firstOrNull()?.date,
        )

    fun yahooHistory(candles: List<Candle>): HistoryResult = HistoryResult(
        symbol = candles.firstOrNull()?.symbol ?: "RELIANCE",
        timeframe = Timeframe.D1,
        candles = candles,
        health = ParserHealth(ok = true, sectionsFound = listOf("timestamp")),
        rowsRequested = candles.size,
        range = "2y",
        interval = "1d",
    )

    /** The standard one-bar Yahoo session: plan.md §5.1's RELIANCE figure of 1167.7. */
    fun yahooHistory(): HistoryResult =
        yahooHistory(listOf(candle(close = 1167.7, source = Source.YAHOO)))

    fun yahooHistoryFailed(
        symbol: String = "RELIANCE",
        failure: YahooFailureReason =
            YahooFailureReason.RATE_LIMITED,
    ): HistoryResult = HistoryResult.failed(
        symbol = symbol,
        timeframe = Timeframe.D1,
        failure = failure,
        reason = "Yahoo gave no usable chart for $symbol (2y/1d): HTTP 429 on both hosts",
        range = "2y",
        interval = "1d",
    )
}