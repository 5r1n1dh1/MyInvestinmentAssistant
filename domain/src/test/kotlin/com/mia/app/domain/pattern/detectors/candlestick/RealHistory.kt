package com.mia.app.domain.pattern.detectors.candlestick

import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.PatternDetector

/**
 * The real-data sweep this package is regression-tested on.
 *
 * `domain/src/test/resources/fixtures/eod_history.json` holds **41** NSE series: **39** large caps
 * with a full 372-session daily history, one with 140 bars (TATAMOTORS, listed after its demerger)
 * and one empty (TATAMOTORSPV, the stub the exchange archive carries for a symbol with no
 * qualifying series). Only the 39 full-history symbols are swept: a 140-bar series cannot support
 * a 60-bar history gate *and* a 20-bar context window honestly, and an empty one cannot be scored
 * at all.
 *
 * The data is real NSE end-of-day OHLCV captured from the exchange archives — the same file the
 * chart-pattern family and the P7 discrimination gate use, so a change that moves the family off
 * the real distribution shows up here first.
 */
internal object RealHistory {

    /** The 39 symbols with a full 372-bar history, in fixture order. */
    val SYMBOLS: List<String> = listOf(
        "RELIANCE", "TCS", "HDFCBANK", "INFY", "ICICIBANK", "BHARTIARTL", "ITC", "LT",
        "SBIN", "AXISBANK", "HINDUNILVR", "BAJFINANCE", "KOTAKBANK", "MARUTI", "SUNPHARMA",
        "TITAN", "ASIANPAINT", "ULTRACEMCO", "NTPC", "WIPRO", "HCLTECH", "TATAPOWER",
        "TATASTEEL", "POWERGRID", "JSWSTEEL", "COALINDIA", "UPL", "ONGC", "ADANIENT",
        "ADANIPORTS", "GRASIM", "HINDALCO", "BAJAJFINSV", "SBILIFE", "HDFCLIFE", "DRREDDY",
        "CIPLA", "DIVISLAB", "APOLLOHOSP",
    ).filter { com.mia.app.domain.pattern.EodHistory.candles(it).size >= 372 }

    /** The trailing window the brief asks the regression to sweep: the last 100 daily bars. */
    const val LAST_N_BARS: Int = 100

    fun candles(symbol: String): List<Candle> = com.mia.app.domain.pattern.EodHistory.candles(symbol)

    fun lastBars(symbol: String, count: Int = LAST_N_BARS): List<Candle> =
        candles(symbol).takeLast(count)
}

/** The five candlestick detectors, in `PatternIds` id order (1, 2, 3, 4, and the star family). */
internal fun allCandlestickDetectors(): List<PatternDetector> = listOf(
    BullishEngulfingDetector(),
    BearishEngulfingDetector(),
    HammerDetector(),
    ShootingStarDetector(),
    StarDetector(),
)