package com.mia.app.data.android.work

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * When the NSE is open. Pure, JVM-testable, and deliberately small.
 *
 * ### Why a trading-day guard exists at all
 * plan.md §10 depends on the bhavcopy existing: *"15:30 IST market close, ~18:00 IST bhavcopy
 * published, 19:00 IST fetch."* On a Saturday there is no file, ever, so the nightly fetch would
 * spend two requests per host discovering a 404 that `NseBhavcopySource` would report as
 * `NOT_PUBLISHED` — correct, but four requests a night for a certainty. The guard turns that
 * certainty into a decision, and records *why* it did nothing.
 *
 * ### What this deliberately does NOT know
 * **NSE's exchange holiday list is not bundled.** There is no captured, verified fixture of it in
 * this repository, and plan.md §3.4 notes only that the symbol master is available. So:
 *
 * * [isTradingDay] is exact for weekends and **deliberately permissive for holidays** — it
 *   answers "possibly open" for any weekday, and the *authoritative* test is the file itself.
 *   `NseBhavcopySource.fetchLatestOnOrBefore` returns `FetchFailure.NOT_PUBLISHED` when every
 *   host 404s, and that is a holiday, a late publication or a broken host — all of which the
 *   worker reports with its own words.
 * * [holidays] exists so a caller that *does* have a verified list can pass it, and so a future
 *   build with a bundled list needs no change here.
 *
 * Handing a fabricated holiday list to this class would be worse than having none: it would make
 * a real trading day look closed and silently drop a session.
 */
object TradingCalendar {

    /**
     * Saturday and Sunday are never trading days in India. Nothing else is assumed.
     *
     * Note that [holidays] cannot be sourced from the symbol master: a delisted instrument and
     * an exchange holiday leave no trace in `EQUITY_L.csv`.
     */
    fun isWeekend(date: LocalDate): Boolean {
        val day: DayOfWeek = date.dayOfWeek
        return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY
    }

    /**
     * `true` when [date] *might* be a trading day.
     *
     * Named "might" on purpose — see the class KDoc. The worker's guard uses this to skip the
     * weekend only; a weekday holiday falls through to the fetch, which answers it definitively.
     */
    fun isPossiblyTradingDay(date: LocalDate, holidays: Set<LocalDate> = emptySet()): Boolean {
        if (date in holidays) return false
        return !isWeekend(date)
    }

    /** Alias for readers who want the permissive answer stated plainly. */
    fun isTradingDay(date: LocalDate, holidays: Set<LocalDate> = emptySet()): Boolean =
        isPossiblyTradingDay(date, holidays)

    /**
     * The first date at or after [date] that might be a trading day.
     *
     * Bounded by [MAX_LOOKBACK_DAYS]: four days covers a normal weekend plus one holiday, and a
     * longer silence means the archive has lost data rather than the market being shut, which the
     * caller must see as a failure rather than wait out. Mirrors `BhavcopyUrls.LOOKBACK_DAYS`'s
     * reasoning so the two layers agree on how long "closed" is plausible.
     */
    fun nextPossiblyTradingDay(
        date: LocalDate,
        holidays: Set<LocalDate> = emptySet(),
    ): LocalDate {
        var candidate = date
        var steps = 0
        while (steps <= MAX_LOOKBACK_DAYS) {
            if (isPossiblyTradingDay(candidate, holidays)) return candidate
            candidate = candidate.plusDays(1)
            steps++
        }
        return candidate
    }

    /**
     * The most recent date at or before [date] that might be a trading day. Used to pick the
     * bhavcopy date to request, so a Sunday run asks for Friday rather than Sunday.
     */
    fun previousPossiblyTradingDay(
        date: LocalDate,
        holidays: Set<LocalDate> = emptySet(),
    ): LocalDate {
        var candidate = date
        var steps = 0
        while (steps <= MAX_LOOKBACK_DAYS) {
            if (isPossiblyTradingDay(candidate, holidays)) return candidate
            candidate = candidate.minusDays(1)
            steps++
        }
        return candidate
    }

    /** Four days: one weekend plus one holiday. See [nextPossiblyTradingDay]. */
    const val MAX_LOOKBACK_DAYS: Int = 4

    /**
     * India Standard Time.
     *
     * **IST has no DST**, which is why [RefreshSchedule]'s arithmetic needs no DST branch for
     * the market zone; `RefreshSchedule` still handles a DST-observing device zone because the
     * *device* may not be set to IST.
     */
    val MARKET_ZONE: java.time.ZoneId = java.time.ZoneId.of("Asia/Kolkata")

    /** 15:30 IST close, plan.md §10. Not used to schedule anything — see [RefreshSchedule]. */
    val MARKET_CLOSE: java.time.LocalTime = java.time.LocalTime.of(15, 30)

    /**
     * A sentence for `work_state.lastError` explaining why a run did nothing.
     *
     * The wording is deliberately about *what the app knows*, not about why NSE was closed:
     * the app has no holiday list, so claiming "Diwali holiday" would be a fabrication.
     */
    fun skipReason(date: LocalDate, holidays: Set<LocalDate> = emptySet()): String {
        val dayName = date.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }
        return if (date in holidays) {
            "$date is a known exchange holiday, so NSE publishes no bhavcopy and there is nothing " +
                "to fetch (plan.md §10 depends on the file existing)."
        } else {
            "$date is a $dayName, when NSE does not trade, so no bhavcopy is published. The " +
                "nightly refresh moved to the next trading day (plan.md §10)."
        }
    }
}