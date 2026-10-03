package com.mia.app.data.history

import com.mia.app.domain.model.Timeframe
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Chart payloads for the history tests.
 *
 * ### What is real and what is hand-written
 *
 * [sample] loads the hand-written document in
 * `data/src/test/resources/fixtures/yahoo_chart_reliance_2y_1d_sample.json`, which reproduces
 * Yahoo's real v8 schema — including the RELIANCE 2026-10-01 close of `1167.70` that the real
 * bhavcopy fixture also carries — with deliberate nulls and one split event.
 *
 * **No payload here was captured live.** Yahoo answers `429 Too Many Requests` from the build
 * machine even after the cookie handshake (verified 2026-10-03), so there is nothing to capture.
 * The 500-row and 261-row documents below are therefore *generated* from a documented recipe
 * rather than recorded, and the tests assert the **shapes plan.md §3.2 documents** — `1d/2y → 500`
 * and `1wk/5y → 261` — which are themselves from plan.md §14's evidence log, i.e. also not
 * re-verified here. Both facts belong in the P3 sign-off, not buried in a test name.
 */
internal object YahooChartFixtures {

    private val IST: ZoneId = ZoneId.of("Asia/Calcutta")

    /** Hand-written golden document with nulls, an adjclose block and a split event. */
    fun sample(): String = text("yahoo_chart_reliance_2y_1d_sample.json")

    /** Hand-written, tiny, for the parser-resilience cases (no `meta`, empty result, error set). */
    fun minimal(close: Double = 100.0, timestamp: Long = 1_700_000_000L): String = """
        {"chart":{"result":[{"timestamp":[$timestamp],"indicators":{"quote":[{"open":[$close],
        "high":[$close],"low":[$close],"close":[$close],"volume":[1000]}]}}],"error":null}}
    """.trimIndent()

    /** `chart.error` populated — the plan.md §9 "this symbol does not exist" answer. */
    fun errored(code: String = "Not Found", description: String = "No data found, symbol may be delisted"): String =
        """{"chart":{"result":null,"error":{"code":"$code","description":"$description"}}}"""

    /** 2xx with something that is not chart JSON at all. */
    fun notChartJson(): String = "<html><body>Too Many Requests</body></html>"

    /** `chart.result` is an empty array. */
    fun emptyResult(): String = """{"chart":{"result":[],"error":null}}"""

    /**
     * A daily document with exactly [rows] trading sessions, as plan.md §3.2's `1d, period=2y`
     * shape describes.
     *
     * Recipe: weekdays only (Indian markets do not trade at weekends), one bar per weekday,
     * timestamped at 09:15 IST = 03:45 UTC, which is how Yahoo stamps an NSE daily bar. Prices
     * walk deterministically off the row index so a failing assertion names a specific bar.
     */
    fun dailyDocument(
        rows: Int = YahooEndpoints.EXPECTED_DAILY_ROWS,
        firstDate: LocalDate = LocalDate.of(2024, 10, 1),
    ): String {
        val dates = tradingDays(firstDate, rows)
        return document(
            symbol = "RELIANCE.NS",
            granularity = "1d",
            range = "2y",
            timestamps = dates.map { it.atTime(LocalTime.of(9, 15)).atZone(IST).toEpochSecond() },
            ohlcv = dates.indices.map { i -> bar(1150.0 + i * 0.35, 60_000L + i * 137L) },
            adjusted = dates.indices.map { i -> 1150.0 + i * 0.35 - 3.0 },
        )
    }

    /**
     * A weekly document with exactly [rows] buckets, as plan.md §3.2's `1wk, period=5y` shape
     * describes (plan.md §14: 261 rows).
     *
     * **The weekly timestamp convention is unverified.** One bar per ISO week, stamped at Monday
     * 00:00 UTC. Yahoo may instead stamp the last session of the week; the parser takes whatever
     * date the timestamp carries rather than guessing, and `CandleResampler.toWeekly` uses "the
     * last trading day in the week" for its own derivation. The two conventions can therefore
     * differ by up to one session per bar, which
     * `HistoryFallbackPlanner` says out loud in its notes rather than papering over.
     */
    fun weeklyDocument(
        rows: Int = YahooEndpoints.EXPECTED_WEEKLY_ROWS,
        firstMonday: LocalDate = LocalDate.of(2021, 10, 4),
    ): String {
        val dates = (0 until rows).map { firstMonday.plusWeeks(it.toLong()) }
        return document(
            symbol = "RELIANCE.NS",
            granularity = "1wk",
            range = "5y",
            timestamps = dates.map { it.atStartOfDay(ZoneOffset.UTC).toEpochSecond() },
            ohlcv = dates.indices.map { i -> bar(1100.0 + i * 1.9, 480_000L + i * 913L) },
            adjusted = dates.indices.map { i -> 1100.0 + i * 1.9 - 3.0 },
        )
    }

    /** [rows] consecutive weekdays from [start]. */
    fun tradingDays(start: LocalDate, rows: Int): List<LocalDate> {
        val out = ArrayList<LocalDate>(rows)
        var date = start
        while (out.size < rows) {
            if (date.dayOfWeek != DayOfWeek.SATURDAY && date.dayOfWeek != DayOfWeek.SUNDAY) out += date
            date = date.plusDays(1)
        }
        return out
    }

    private data class Bar(val open: Double, val high: Double, val low: Double, val close: Double, val volume: Long)

    private fun bar(close: Double, volume: Long) = Bar(
        open = close - 1.25,
        high = close + 2.5,
        low = close - 3.75,
        close = close,
        volume = volume,
    )

    private fun document(
        symbol: String,
        granularity: String,
        range: String,
        timestamps: List<Long>,
        ohlcv: List<Bar>,
        adjusted: List<Double>,
    ): String = buildString {
        append("{\"chart\":{\"result\":[{")
        append("\"meta\":{\"currency\":\"INR\",\"symbol\":\"$symbol\",\"exchangeName\":\"NS\",")
        append("\"instrumentType\":\"EQUITY\",\"regularMarketPrice\":${ohlcv.last().close},")
        append("\"chartPreviousClose\":${ohlcv.first().close},\"previousClose\":${ohlcv.first().close},")
        append("\"timezone\":\"IST\",\"exchangeTimezoneName\":\"Asia/Calcutta\",")
        append("\"dataGranularity\":\"$granularity\",\"range\":\"$range\"},")
        append("\"timestamp\":[${timestamps.joinToString(",")}],")
        append("\"events\":{},")
        append("\"indicators\":{\"quote\":[{")
        append("\"open\":[${ohlcv.joinToString(",") { it.open.str() }}],")
        append("\"high\":[${ohlcv.joinToString(",") { it.high.str() }}],")
        append("\"low\":[${ohlcv.joinToString(",") { it.low.str() }}],")
        append("\"close\":[${ohlcv.joinToString(",") { it.close.str() }}],")
        append("\"volume\":[${ohlcv.joinToString(",") { it.volume.toString() }}]")
        append("}],\"adjclose\":[{\"adjclose\":[${adjusted.joinToString(",") { it.str() }}]}]}")
        append("}],\"error\":null}}")
    }

    private fun Double.str(): String = if (this == toLong().toDouble()) toLong().toString() else toString()

    private fun text(name: String): String =
        (YahooChartFixtures::class.java.getResourceAsStream("/fixtures/$name")
            ?: error("missing fixture /fixtures/$name")).readBytes().toString(Charsets.UTF_8)

    /** Convenience for tests that need a [Timeframe] and a chart for it. */
    fun intervalFor(timeframe: Timeframe): YahooInterval = YahooInterval.forTimeframe(timeframe)
}