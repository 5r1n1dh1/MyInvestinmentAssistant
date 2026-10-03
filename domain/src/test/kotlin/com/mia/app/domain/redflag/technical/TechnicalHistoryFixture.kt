package com.mia.app.domain.redflag.technical

import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.Source
import com.mia.app.domain.model.Timeframe
import java.time.LocalDate

/**
 * A self-contained loader for `domain/src/test/resources/fixtures/eod_history.json` — **real** NSE
 * end-of-day OHLCV, 40 large caps, 372 sessions from 2025-04-01 to 2026-10-01.
 *
 * Deliberately its own copy, for the same reason the chart-pattern and accuracy packages have one:
 * another agent owns those loaders, they are free to change, and this file is the record of how the
 * technical flags behave on real prices. `:domain` also has no JSON dependency for a test
 * (plan.md §4.3), so a ~60-line reader is written here rather than pulled in.
 *
 * ### ⚠️ The known data hazard
 *
 * The series are **unadjusted** and therefore contain real corporate actions. The measured gaps:
 *
 * | date | symbol | action |
 * |---|---|---|
 * | 2025-06-16 | `BAJFINANCE` | ~1:10 split |
 * | 2025-08-26 | `HDFCBANK` | 1:2 split |
 * | 2025-10-14 | `TATAMOTORS` | ~40 % reduction |
 * | 2026-01-14 | `KOTAKBANK` | ~1:5 split |
 *
 * A −50 % or −90 % bar inside one session **legitimately** trips every moving-average and oscillator
 * rule in this package: a −50 % close is `close < SMA200` on any series, and the resulting RSI and
 * volume readings are artefacts of the gap, not of trading. Nothing is repaired — the trimmed
 * series is a genuine series with those sessions deleted — but the per-rule fire rates are reported
 * both ways, with and without the affected bars, so a reader can tell a real signal from a gap.
 */
internal object TechnicalHistoryFixture {

    private const val RESOURCE = "/fixtures/eod_history.json"

    /** Sessions in a full-length series. */
    const val FULL_BARS: Int = 372

    /** The brief's regression window. */
    const val LAST_BARS: Int = 150

    /** Bar dates within ±[MARGIN_BARS] sessions of a measured corporate action. */
    const val MARGIN_BARS: Int = 3

    /** Unadjusted corporate actions present in the fixture, with their gap dates. */
    val CORPORATE_ACTIONS: Map<String, String> = linkedMapOf(
        "BAJFINANCE" to "2025-06-16",
        "HDFCBANK" to "2025-08-26",
        "TATAMOTORS" to "2025-10-14",
        "KOTAKBANK" to "2026-01-14",
    )

    private val table: Map<String, List<Candle>> by lazy { parse(read()) }

    fun symbols(): List<String> = table.keys.toList()

    /** Symbols with a full-length history — the ones a per-symbol walk covers end to end. */
    fun symbolsWithHistory(): List<String> = symbols().filter { table.getValue(it).size >= FULL_BARS }

    fun candles(symbol: String): List<Candle> = table[symbol].orEmpty()

    fun lastBars(symbol: String, count: Int = LAST_BARS): List<Candle> = candles(symbol).takeLast(count)

    /**
     * The same series with the sessions adjacent to [symbol]'s corporate action removed.
     *
     * Returns the full series unchanged for a symbol the fixture has no recorded action for, so a
     * caller can sweep every symbol through one code path.
     */
    fun withoutCorporateActionBars(symbol: String, margin: Int = MARGIN_BARS): List<Candle> {
        val bars = candles(symbol)
        val date = CORPORATE_ACTIONS[symbol] ?: return bars
        val target = LocalDate.parse(date)
        val from = target.minusDays(margin.toLong())
        val to = target.plusDays(margin.toLong())
        return bars.filterNot { !it.date.isBefore(from) && !it.date.isAfter(to) }
    }

    /** Real daily bars with delivery percentages attached, for exercising the delivery rule. */
    fun withDelivery(symbol: String): List<Candle> {
        val bars = candles(symbol)
        return bars.mapIndexed { i, bar ->
            if (i % 4 == 0) bar else bar.copy(deliveryPercent = 30.0 + (i % 20))
        }
    }

    private fun read(): String =
        TechnicalHistoryFixture::class.java.getResourceAsStream(RESOURCE)?.reader(Charsets.UTF_8)?.readText()
            ?: error("$RESOURCE is missing from the test resources")

    private fun parse(json: String): Map<String, List<Candle>> {
        val root = Reader(json).value() as? Map<*, *> ?: return emptyMap()
        val symbols = root["symbols"] as? Map<*, *> ?: return emptyMap()
        val out = LinkedHashMap<String, List<Candle>>()
        for ((key, value) in symbols) {
            val symbol = key as String
            out[symbol] = candlesOf(symbol, (value as? List<Any?>).orEmpty())
        }
        return out
    }

    private fun candlesOf(symbol: String, rows: List<Any?>): List<Candle> = rows.mapNotNull { row ->
        val fields = row as? Map<*, *> ?: return@mapNotNull null
        fun number(key: String): Double = (fields[key] as Number).toDouble()

        Candle(
            symbol = symbol,
            timeframe = Timeframe.D1,
            date = LocalDate.parse(fields["d"] as String),
            open = number("o"),
            high = number("h"),
            low = number("l"),
            close = number("c"),
            volume = number("v").toLong(),
            deliveryPercent = (fields["dl"] as? Number)?.toDouble(),
            source = Source.NSE_BHAVCOPY,
        )
    }

    /**
     * A minimal recursive-descent JSON reader: objects, arrays, strings, numbers, `true`/`false` and
     * `null`. Every read either consumes a delimiter or advances the cursor, so the ≈ 1 MB fixture
     * cannot spin.
     */
    private class Reader(private val text: String) {
        private var i = 0

        fun value(): Any? {
            skipWhitespace()
            check(i < text.length) { "unexpected end of fixture at $i" }
            return when (text[i]) {
                '{' -> objectValue()
                '[' -> arrayValue()
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> number()
            }
        }

        private fun objectValue(): Map<String, Any?> {
            expect('{')
            val out = LinkedHashMap<String, Any?>()
            while (true) {
                skipWhitespace()
                if (peek() == '}') {
                    i++
                    return out
                }
                val key = string()
                expect(':')
                out[key] = value()
                skipWhitespace()
                if (peek() == ',') i++
            }
        }

        private fun arrayValue(): List<Any?> {
            expect('[')
            val out = ArrayList<Any?>()
            while (true) {
                skipWhitespace()
                if (peek() == ']') {
                    i++
                    return out
                }
                out += value()
                skipWhitespace()
                if (peek() == ',') i++
            }
        }

        private fun string(): String {
            skipWhitespace()
            check(text[i] == '"') { "expected a string at $i but found '${text[i]}'" }
            i++
            val start = i
            while (i < text.length && text[i] != '"') {
                if (text[i] == '\\') i++
                i++
            }
            val value = text.substring(start, i)
            i++
            return value
        }

        private fun number(): Double {
            skipWhitespace()
            val start = i
            while (i < text.length && text[i] !in ",}] \t\r\n") i++
            check(i > start) { "expected a number at $start but found '${text[start]}'" }
            return text.substring(start, i).toDouble()
        }

        private fun <T> literal(token: String, value: T): T {
            check(text.startsWith(token, i)) { "expected '$token' at $i" }
            i += token.length
            return value
        }

        private fun peek(): Char {
            skipWhitespace()
            check(i < text.length) { "unexpected end of fixture at $i" }
            return text[i]
        }

        private fun expect(c: Char) {
            skipWhitespace()
            check(text[i] == c) { "expected '$c' at $i but found '${text[i]}'" }
            i++
        }

        private fun skipWhitespace() {
            while (i < text.length && text[i].isWhitespace()) i++
        }
    }
}