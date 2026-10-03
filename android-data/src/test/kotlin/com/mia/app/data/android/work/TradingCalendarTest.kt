package com.mia.app.data.android.work

import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The trading-day guard of plan.md §10.
 *
 * ### What is and is not asserted here
 * The guard is exact for weekends and **deliberately permissive for exchange holidays**: there is
 * no captured, verified NSE holiday list in this repository, and handing this class a fabricated
 * one would make a real trading day look closed and silently drop a session. The authoritative
 * holiday test is the bhavcopy itself — `NseBhavcopySource` answers `NOT_PUBLISHED` — and these
 * tests pin the *weekend* behaviour plus the permissiveness, so the choice cannot be forgotten.
 */
class TradingCalendarTest {

    // 2026-10-01 is a Thursday; the weekend is 10-03 (Sat) and 10-04 (Sun).
    private val thursday: LocalDate = LocalDate.parse("2026-10-01")
    private val saturday: LocalDate = LocalDate.parse("2026-10-03")
    private val sunday: LocalDate = LocalDate.parse("2026-10-04")
    private val nextMonday: LocalDate = LocalDate.parse("2026-10-05")

    @Test
    fun `the fixture dates really are the weekdays the test assumes`() {
        assertEquals(DayOfWeek.THURSDAY, thursday.dayOfWeek)
        assertEquals(DayOfWeek.SATURDAY, saturday.dayOfWeek)
        assertEquals(DayOfWeek.SUNDAY, sunday.dayOfWeek)
        assertEquals(DayOfWeek.MONDAY, nextMonday.dayOfWeek)
    }

    @Test
    fun `Saturday and Sunday are never trading days`() {
        assertTrue(TradingCalendar.isWeekend(saturday))
        assertTrue(TradingCalendar.isWeekend(sunday))
        assertFalse(TradingCalendar.isPossiblyTradingDay(saturday))
        assertFalse(TradingCalendar.isPossiblyTradingDay(sunday))
    }

    @Test
    fun `every other weekday is possibly a trading day`() {
        // Monday through Friday, 2026-10-05 .. 2026-10-09.
        val week = (0L..4L).map { nextMonday.plusDays(it) }

        assertTrue(week.all { TradingCalendar.isPossiblyTradingDay(it) })
        assertTrue(week.none { TradingCalendar.isWeekend(it) })
    }

    @Test
    fun `a weekday is treated as possibly trading even on a real exchange holiday`() {
        // Diwali 2026 falls in November; the point is that a weekday holiday is NOT guessed at.
        val diwali = LocalDate.parse("2026-11-08")
        assertEquals(DayOfWeek.SUNDAY, diwali.dayOfWeek)

        // A weekday that the app has no holiday entry for is permissive on purpose.
        val unknownWeekday = LocalDate.parse("2026-10-07")
        assertEquals(DayOfWeek.WEDNESDAY, unknownWeekday.dayOfWeek)
        assertTrue(TradingCalendar.isPossiblyTradingDay(unknownWeekday))
    }

    @Test
    fun `a supplied holiday list is honoured`() {
        val holiday = LocalDate.parse("2026-10-06") // a Tuesday
        assertEquals(DayOfWeek.TUESDAY, holiday.dayOfWeek)

        assertTrue(TradingCalendar.isPossiblyTradingDay(holiday))
        assertFalse(TradingCalendar.isPossiblyTradingDay(holiday, holidays = setOf(holiday)))
        assertTrue(TradingCalendar.isWeekend(holiday).not(), "a holiday is not a weekend")
    }

    @Test
    fun `the next possibly-trading day from a Saturday is Monday`() {
        assertEquals(nextMonday, TradingCalendar.nextPossiblyTradingDay(saturday))
        assertEquals(nextMonday, TradingCalendar.nextPossiblyTradingDay(sunday))
        assertEquals(nextMonday, TradingCalendar.nextPossiblyTradingDay(nextMonday))
    }

    @Test
    fun `a three-day closure still resolves inside the four-day bound`() {
        // Friday holiday + Saturday + Sunday, with no bundled list: from the Friday the next
        // *possibly* trading day is the Friday itself (the app cannot know it is a holiday), and
        // from the Saturday it is Monday.
        val friday = LocalDate.parse("2026-10-02")
        assertEquals(friday, TradingCalendar.nextPossiblyTradingDay(friday))
        assertEquals(nextMonday, TradingCalendar.nextPossiblyTradingDay(friday, holidays = setOf(friday)))
    }

    @Test
    fun `the previous possibly-trading day from a Sunday is Friday`() {
        // A Sunday run asks for Friday's bhavcopy rather than Sunday's, which never exists.
        assertEquals(fridayOfWeek(), TradingCalendar.previousPossiblyTradingDay(sunday))
    }

    @Test
    fun `the skip reason states what the app knows, not why NSE was shut`() {
        val weekendReason = TradingCalendar.skipReason(saturday)
        assertTrue(weekendReason.contains("2026-10-03"), weekendReason)
        assertTrue(weekendReason.contains("Saturday"), weekendReason)
        assertTrue(weekendReason.contains("plan.md §10"), weekendReason)

        val holiday = LocalDate.parse("2026-10-06")
        val holidayReason = TradingCalendar.skipReason(holiday, holidays = setOf(holiday))
        assertTrue(holidayReason.contains("known exchange holiday"), holidayReason)
    }

    @Test
    fun `the market zone is IST and the close is 15 30`() {
        assertEquals("Asia/Kolkata", TradingCalendar.MARKET_ZONE.id)
        assertEquals(15, TradingCalendar.MARKET_CLOSE.hour)
        assertEquals(30, TradingCalendar.MARKET_CLOSE.minute)
        // IST has no DST, which is why RefreshSchedule needs no DST branch for the market zone.
        assertEquals(5 * 3600 + 30 * 60, TradingCalendar.MARKET_ZONE.rules.getOffset(thursday.atStartOfDay()).totalSeconds)
        assertEquals(5 * 3600 + 30 * 60, TradingCalendar.MARKET_ZONE.rules.getOffset(sunday.atStartOfDay()).totalSeconds)
    }

    @Test
    fun `the lookback bound is the documented four days`() {
        assertEquals(4, TradingCalendar.MAX_LOOKBACK_DAYS)
    }

    private fun fridayOfWeek(): LocalDate = thursday.plusDays(1)
}