package com.mia.app.domain.pattern.detectors.chart

import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.Source
import com.mia.app.domain.model.Timeframe
import com.mia.app.domain.technical.CandleResampler
import java.time.LocalDate

/**
 * Loader for `domain/src/test/resources/fixtures/eod_history.json` — **real** NSE end-of-day OHLCV
 * for 40 large caps, 372 sessions each from 2025-04-01 to 2026-10-01 (`docs/DATA-NOTES.md`).
 *
 * Self-contained on purpose: this package's tests must not depend on another agent's fixture loader,
 * because that loader is free to change while these tests are the record of the detectors' behaviour on
 * real prices.
 *
 * **⚠️ The known data hazard.** The series are **unadjusted**: they contain real corporate actions.
 * The measured gap dates are
 *
 * | date | symbol | action |
 * |---|---|---|
 * | 2025-06-16 | `BAJFINANCE` | ~1:10 split |
 * | 2025-08-26 | `HDFCBANK` | 1:2 split |
 * | 2025-10-14 | `TATAMOTORS` | ~40 % reduction |
 * | 2026-01-14 | `KOTAKBANK` | ~1:5 split |
 *
 * A gap like these manufactures a swing that looks like a chart pattern: a 50 % fall inside one session
 * is a "lower low" with an enormous `ref`, and a 2× rise is a "higher high". Every geometry detector is
 * affected, so `ChartPatternRealDataTest` **excludes the bars adjacent to those dates** when it reports
 * detection counts and says so. Nothing is repaired: the trimmed series is a genuine series with those
 * sessions deleted and its pivots recomputed on it.
 */
internal object RealEodHistory {

    private const val RESOURCE = "fixtures/eod_history.json"

    /** Symbols and dates of the measured unadjusted corporate actions in this fixture. */
    val CORPORATE_ACTIONS: Map<String, String> = linkedMapOf(
        "BAJFINANCE" to "2025-06-16",
        "HDFCBANK" to "2025-08-26",
        "TATAMOTORS" to "2025-10-14",
        "KOTAKBANK" to "2026-01-14",
    )

    private val table: Map<String, List<Candle>> by lazy { parse(read()) }

    /** Every symbol in the file, in file order. `TATAMOTORSPV` has no rows and is still listed. */
    fun symbols(): List<String> = table.keys.toList()

    /** Symbols with a full-length history — the ones a per-symbol walk covers end to end. */
    fun symbolsWithHistory(): List<String> = symbols().filter { table.getValue(it).size >= 372 }

    fun dailyBars(symbol: String): Int = table[symbol]?.size ?: 0

    fun candles(symbol: String, timeframe: Timeframe): List<Candle> {
        val daily = table[symbol].orEmpty()
        if (daily.isEmpty()) return emptyList()
        return if (timeframe == Timeframe.D1) daily else CandleResampler.toWeekly(daily)
    }

    /** The bar indices within ±[margin] sessions of a measured corporate-action gap. */
    fun corporateActionBars(margin: Int = 2): Map<String, Set<Int>> =
        CORPORATE_ACTIONS.mapValues { (symbol, date) ->
            val daily = table[symbol].orEmpty()
            val target = LocalDate.parse(date)
            daily.indices
                .filter { i ->
                    val bar = daily[i].date
                    !bar.isBefore(target.minusDays(margin.toLong())) && !bar.isAfter(target.plusDays(margin.toLong()))
                }
                .toSet()
        }

    private fun read(): String =
        (RealEodHistory::class.java.getResourceAsStream("/$RESOURCE")?.reader(Charsets.UTF_8)?.readText())
            ?: error("$RESOURCE is missing from the test resources")

    private fun parse(json: String): Map<String, List<Candle>> {
        val root = JsonReader(json).value() as? Map<*, *> ?: return emptyMap()
        val symbols = root["symbols"] as? Map<*, *> ?: return emptyMap()
        val out = LinkedHashMap<String, List<Candle>>()
        for ((key, value) in symbols) {
            val symbol = key as String
            out[symbol] = candlesOf(symbol, (value as? List<Any?>).orEmpty())
        }
        return out
    }

    /**
     * A minimal recursive-descent JSON reader — objects, arrays, strings, numbers, `true`/`false`
     * and `null` — written here rather than pulled in.
     *
     * `:domain` must not gain a JSON dependency for a test (plan.md §4.3 — no library the domain does
     * not need), and the fixture is ≈ 1 MB across 41 symbols, so an ad-hoc `indexOf` scan is a
     * correctness risk rather than an aesthetic one. Sixty lines that always make progress removes that
     * risk: every read either consumes a delimiter or advances the cursor.
     */
    private class JsonReader(private val text: String) {
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

    /** Converts the parsed rows into candles; kept separate so [JsonReader] stays a plain JSON reader. */
    private fun candlesOf(symbol: String, rows: List<Any?>): List<Candle> = rows.mapNotNull { row ->
        val fields = row as? Map<*, *> ?: return@mapNotNull null
        @Suppress("UNCHECKED_CAST")
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

}