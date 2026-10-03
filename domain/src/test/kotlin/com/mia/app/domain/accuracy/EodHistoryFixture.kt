package com.mia.app.domain.accuracy

import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.Source
import com.mia.app.domain.model.Timeframe
import java.time.LocalDate

/**
 * The real NSE history, loaded from `domain/src/test/resources/fixtures/eod_history.json`.
 *
 * 41 symbols, 372 daily sessions each from 2025-04-01 to 2026-10-01 (40 of them full-length;
 * `TATAMOTORS` stops at its 2025-10-23 demerger and `TATAMOTORSPV` has no rows at all — both are
 * real facts about the data, not fixture gaps).
 *
 * These tests deliberately run against **real** prices. A hit rate measured on a synthetic random
 * walk would prove nothing about the honesty contract: what matters is that a track record
 * computed from real bars lands where it lands, and that a user reading it is not told it means
 * more than it does.
 *
 * The fixture ships with `:domain`'s test resources; `:data` cannot see it and its copy must not
 * be edited, so this loader lives with the tests that use it.
 */
internal object EodHistoryFixture {

    const val RESOURCE: String = "fixtures/eod_history.json"

    /** Every key in the `symbols` object, including the two with no/short history. */
    const val EXPECTED_SYMBOL_COUNT: Int = 41

    /** Bars in a full-length series. */
    const val EXPECTED_FULL_BARS: Int = 372

    /** `TATAMOTORS` ends here — the demerger session (plan.md §9 item 3). */
    const val EXPECTED_TATAMOTORS_BARS: Int = 140

    val FIRST_DATE: LocalDate = LocalDate.parse("2025-04-01")
    val LAST_DATE: LocalDate = LocalDate.parse("2026-10-01")

    private val root: Map<*, *> by lazy {
        val text = resource(RESOURCE).reader(Charsets.UTF_8).readText()
        (MiniJson.parse(text) as? Map<*, *>) ?: error("$RESOURCE is not a JSON object")
    }

    private val symbolTable: Map<String, List<Map<String, Any?>>> by lazy {
        @Suppress("UNCHECKED_CAST")
        val table = root["symbols"] as? Map<String, Any?>
            ?: error("$RESOURCE has no 'symbols' object")
        table.mapValues { (_, rows) ->
            @Suppress("UNCHECKED_CAST")
            rows as List<Map<String, Any?>>
        }
    }

    /** Every symbol in the file, in file order. */
    fun symbols(): List<String> = symbolTable.keys.toList()

    /** Symbols with a full-length history: the ones a simulation may walk end to end. */
    fun symbolsWithFullHistory(): List<String> =
        symbols().filter { candles(it).size == EXPECTED_FULL_BARS }

    /**
     * One symbol's bars as domain candles, ascending by date.
     *
     * Empty for `TATAMOTORSPV`, which has no rows: an empty history is a real answer and the
     * tracker must cope with it rather than inventing data.
     */
    fun candles(symbol: String, timeframe: Timeframe = Timeframe.D1): List<Candle> {
        val bars = symbolTable[symbol]
            ?: error("fixture has no series for $symbol")
        return bars
            .sortedBy { bar -> bar["d"] as String }
            .map { bar ->
                Candle(
                    symbol = symbol,
                    timeframe = timeframe,
                    date = LocalDate.parse(bar["d"] as String),
                    open = (bar["o"] as Double).toDouble(),
                    high = (bar["h"] as Double).toDouble(),
                    low = (bar["l"] as Double).toDouble(),
                    close = (bar["c"] as Double).toDouble(),
                    volume = (bar["v"] as Double).toLong(),
                    deliveryPercent = null,
                    source = Source.NSE_BHAVCOPY,
                )
            }
    }

    /** The whole fixture as a symbol-keyed history, ready for `OutcomeTracker.evaluateAll`. */
    fun candlesBySymbol(timeframe: Timeframe = Timeframe.D1): Map<String, List<Candle>> =
        symbols().associateWith { candles(it, timeframe) }

    /** Copy of [symbol]'s real bars with delivery percentages filled in on some bars. */
    fun candlesWithDelivery(symbol: String): List<Candle> = candles(symbol).mapIndexed { i, candle ->
        if (i % 3 == 0) candle else candle.copy(deliveryPercent = 10.0 + (i % 40))
    }

    private fun resource(name: String) =
        EodHistoryFixture::class.java.classLoader.getResourceAsStream(name)
            ?: error("fixture '$name' is not on the test classpath")
}