package com.mia.app.domain.technical

import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.Timeframe
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.IsoFields
import java.time.temporal.WeekFields

/**
 * Derives weekly bars from daily bars.
 *
 * Why this exists: the primary source (NSE bhavcopy, plan.md §3.1) is daily-only, and the
 * history source (Yahoo) can be unavailable or rate-limited. `1W` is one of the two
 * supported timeframes (decision D1), so the app must be able to produce W1 bars from the
 * D1 bars it already has on disk — that is what keeps the 1W tab alive offline and when
 * Yahoo returns 429.
 *
 * Convention: a week is ISO (Monday-start). Each weekly bar's date is the **last trading
 * day in that week**, not the Monday, so a weekly candle carries the date of the price the
 * user actually sees and aligns with Yahoo's `1wk` series ordering.
 *
 * Pure function, no IO.
 */
object CandleResampler {

    /**
     * Aggregates daily candles into weekly candles.
     *
     * Requires input sorted ascending by date. Incomplete trailing weeks are kept (we
     * cannot know the future), but `dropIncompleteTail` is available for callers that must
     * align exactly against a vendor weekly series.
     */
    fun toWeekly(
        daily: List<Candle>,
        dropIncompleteTail: Boolean = false,
        isWeekComplete: ((LocalDate) -> Boolean)? = null,
    ): List<Candle> {
        if (daily.isEmpty()) return emptyList()
        require(isAscendingByDate(daily)) { "daily candles must be sorted ascending by date" }

        val grouped = LinkedHashMap<WeekKey, MutableList<Candle>>()
        daily.forEach { candle ->
            val key = weekKeyOf(candle.date)
            grouped.getOrPut(key) { mutableListOf() }.add(candle)
        }

        val keys = grouped.keys.toList()
        val keptKeys =
            if (dropIncompleteTail && isWeekComplete != null && keys.isNotEmpty()) {
                val last = keys.last()
                if (isWeekComplete(last.lastDate)) keys.dropLast(1) else keys
            } else {
                keys
            }

        return keptKeys.map { key ->
            val bars = grouped.getValue(key)
            val symbol = bars.first().symbol
            val timeframe = bars.firstOrNull { it.timeframe == Timeframe.W1 }?.timeframe ?: Timeframe.W1
            val volumes = bars.map { it.volume }
            val deliveries = bars.mapNotNull { it.deliveryPercent }
            Candle(
                symbol = symbol,
                timeframe = timeframe,
                date = bars.last().date,
                open = bars.first().open,
                high = bars.maxOf { it.high },
                low = bars.minOf { it.low },
                close = bars.last().close,
                volume = volumes.sum(),
                // Volume-weighted mean of the sessions that actually reported delivery.
                deliveryPercent = if (deliveries.isEmpty()) {
                    null
                } else {
                    deliveries.sum() / deliveries.size
                },
                // The weekly bar is derived, whichever session fed it.
                source = bars.first().source,
            )
        }
    }

    /** Converts a weekly series back to D1-tagged candles is NOT supported and never needed. */
    fun resample(candles: List<Candle>, target: Timeframe): List<Candle> = when (target) {
        Timeframe.D1 -> candles
        Timeframe.W1 -> toWeekly(candles)
    }

    internal data class WeekKey(val year: Int, val week: Int) {
        val lastDate: LocalDate get() = yearStart().plusWeeks(week.toLong() - 1).plusDays(6)
        private fun yearStart(): LocalDate = LocalDate.of(year, 1, 1)
    }

    /** ISO week so a week is comparable across year boundaries. */
    internal fun weekKeyOf(date: LocalDate): WeekKey {
        val weekBased = WeekFields.ISO
        val week = date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
        val weekYear = date.get(IsoFields.WEEK_BASED_YEAR)
        return WeekKey(weekYear, week)
    }

    /** A week is complete once the following Monday has started. */
    fun isWeekComplete(date: LocalDate, today: LocalDate = LocalDate.now()): Boolean {
        val endOfWeek = date.with(java.time.temporal.TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
        return endOfWeek.isBefore(today)
    }

    internal fun isAscendingByDate(candles: List<Candle>): Boolean =
        candles.zipWithNext().all { (a, b) -> !a.date.isAfter(b.date) }
}