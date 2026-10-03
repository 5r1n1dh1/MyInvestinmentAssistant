package com.mia.app.domain.pattern

import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.Source
import com.mia.app.domain.model.Timeframe
import java.time.LocalDate

/**
 * Loader for `domain/src/test/resources/fixtures/eod_history.json` — real NSE end-of-day OHLCV
 * for ~40 large caps (`docs/DATA-NOTES.md`).
 *
 * Schema: `{"symbols": {"SYM": [{"d":"2025-04-01","o":…,"h":…,"l":…,"c":…,"v":…}, …]}}`.
 *
 * Hand-parsed on purpose: `:domain` has no JSON dependency (plan.md §4.3 — no library the domain
 * does not need), and adding `kotlinx-serialization` to a module whose only consumer of it is a
 * test would be the wrong trade.
 */
internal object EodHistory {

    val SYMBOLS: List<String> = listOf(
        "RELIANCE",
        "TCS",
        "HDFCBANK",
        "INFY",
        "ICICIBANK",
        "SBIN",
        "BHARTIARTL",
        "ITC",
        "LT",
        "AXISBANK",
        "KOTAKBANK",
        "HINDUNILVR",
        "BAJFINANCE",
        "MARUTI",
        "SUNPHARMA",
        "TITAN",
        "ULTRACEMCO",
        "ASIANPAINT",
        "NTPC",
        "POWERGRID",
    )

    private val parsed: Map<String, List<Candle>> by lazy { parse(read()) }

    /** Daily candles for [symbol], ascending by date, or an empty list when the symbol is absent. */
    fun candles(symbol: String): List<Candle> = parsed[symbol].orEmpty()

    /** Symbols that carry a full 372-bar history — the ones the pivot-count assertions use. */
    fun fullHistorySymbols(count: Int = 8): List<String> =
        SYMBOLS.filter { candles(it).size >= 372 }.take(count)

    private fun read(): String {
        val stream = EodHistory::class.java.getResourceAsStream("/fixtures/eod_history.json")
            ?: error("fixtures/eod_history.json is missing from the test resources")
        return stream.bufferedReader().use { it.readText() }
    }

    private fun parse(json: String): Map<String, List<Candle>> {
        val scanner = Scanner(json)
        // The fixture's envelope also carries `source`, `note` and `seriesFilter` keys, so skip to
        // the one we want rather than assuming it is first.
        scanner.seek("\"symbols\"")
        scanner.skip("\"symbols\"")
        scanner.expect(":")
        return scanner.readSymbolObject()
    }

    /** A minimal cursor over this fixture's own grammar — no escapes, no nesting beyond one level. */
    private class Scanner(private val text: String) {
        private var pos = 0

        fun expect(literal: String) {
            skipWhitespace()
            require(text.startsWith(literal, pos)) {
                "expected \"$literal\" at offset $pos but found \"${text.substring(pos, minOf(pos + 24, text.length))}\""
            }
            pos += literal.length
        }

        /** Moves the cursor to the next occurrence of [literal], ignoring anything before it. */
        fun seek(literal: String) {
            val found = text.indexOf(literal, pos)
            require(found >= 0) { "could not find \"$literal\" in the fixture" }
            pos = found
        }

        /** Advances past [literal] without checking it, for use right after [seek]. */
        fun skip(literal: String) {
            pos += literal.length
        }

        fun readSymbolObject(): Map<String, List<Candle>> {
            expect("{")
            val out = LinkedHashMap<String, List<Candle>>()
            skipWhitespace()
            if (peek() == '}') {
                pos++
                return out
            }
            while (true) {
                skipWhitespace()
                if (peek() != '"') error("expected a symbol name at offset $pos")
                val symbol = readString()
                expect(":")
                skipWhitespace()
                require(peek() == '[') { "symbol $symbol should map to an array at offset $pos" }
                out[symbol] = readBarArray(symbol)
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return out
                    }

                    else -> error("expected , or } at offset $pos")
                }
            }
        }

        private fun readBarArray(symbol: String): List<Candle> {
            expect("[")
            val bars = ArrayList<Candle>()
            skipWhitespace()
            if (peek() == ']') {
                pos++
                return bars
            }
            while (true) {
                expect("{")
                val fields = LinkedHashMap<String, String>()
                skipWhitespace()
                if (peek() != '}') {
                    while (true) {
                        val key = readString()
                        expect(":")
                        fields[key] = readScalar()
                        skipWhitespace()
                        if (peek() == ',') {
                            pos++
                            continue
                        }
                        expect("}")
                        break
                    }
                } else {
                    pos++
                }
                bars += toCandle(symbol, fields)
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return bars
                    }

                    else -> error("expected , or ] at offset $pos")
                }
            }
        }

        private fun toCandle(symbol: String, fields: Map<String, String>): Candle {
            fun num(key: String): Double =
                fields[key]?.toDouble() ?: error("$symbol bar is missing \"$key\"")

            return Candle(
                symbol = symbol,
                timeframe = Timeframe.D1,
                date = LocalDate.parse(fields["d"] ?: error("$symbol bar is missing \"d\"")),
                open = num("o"),
                high = num("h"),
                low = num("l"),
                close = num("c"),
                volume = num("v").toLong(),
                deliveryPercent = fields["dl"]?.toDouble(),
                source = Source.NSE_BHAVCOPY,
            )
        }

        private fun readScalar(): String {
            skipWhitespace()
            val start = pos
            while (pos < text.length && text[pos] !in ",}] \t\r\n") pos++
            // A string value keeps its quotes in the raw scan; the caller wants the text.
            return text.substring(start, pos).trim('"')
        }

        private fun readString(): String {
            skipWhitespace()
            require(peek() == '"') { "expected a string at offset $pos" }
            pos++
            val start = pos
            while (pos < text.length && text[pos] != '"') pos++
            val value = text.substring(start, pos)
            pos++
            return value
        }

        private fun peek(): Char =
            if (pos < text.length) text[pos] else error("unexpected end of fixture")

        private fun skipWhitespace() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }
    }
}