package com.mia.app.data.android.work

import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The scheduling maths, asserted against real zone rules rather than a mock clock.
 *
 * plan.md §10:
 * ```
 * 15:30 IST   market close
 * ~18:00 IST  bhavcopy published
 * 19:00 IST   WorkManager: fetch bhavcopy → parse → replace market cache
 * 19:05 IST   WorkManager: rescan the user's tracked symbols → generate alerts
 * ```
 */
class RefreshScheduleTest {

    /** Asia/Kolkata: UTC+05:30, **no DST**, which is why IST maths has no DST branch. */
    private val ist: ZoneId = ZoneId.of("Asia/Kolkata")

    private fun istAt(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int = 0,
    ): ZonedDateTime = ZonedDateTime.of(LocalDate.of(year, month, day), java.time.LocalTime.of(hour, minute), ist)

    // ------------------------------------------------------------------- landing near 19:00

    @Test
    fun `a run scheduled at 0900 IST lands at 1900 IST the same evening`() {
        val now = istAt(2026, 10, 1, 9, 0)
        val target = RefreshSchedule.eodTarget(now, ist)

        assertEquals(LocalDate.of(2026, 10, 1), target.toLocalDate())
        assertEquals(19, target.hour)
        assertEquals(0, target.minute)
        assertEquals(Duration.ofHours(10), RefreshSchedule.eodInitialDelay(now, ist))
    }

    @Test
    fun `a run scheduled at 1831 IST still lands at 1900 IST the same evening`() {
        // The measured bhavcopy publication time is 18:32 IST (plan.md §14), so this is the
        // realistic "user opened the app shortly after the file appeared" case.
        val now = istAt(2026, 10, 1, 18, 31)
        val delay = RefreshSchedule.eodInitialDelay(now, ist)

        assertEquals(Duration.ofMinutes(29), delay)
        assertEquals(19, RefreshSchedule.eodTarget(now, ist).hour)
    }

    @Test
    fun `the pattern scan targets 1905 IST, five minutes after the refresh`() {
        val now = istAt(2026, 10, 1, 9, 0)

        assertEquals(19, RefreshSchedule.patternScanTarget(now, ist).hour)
        assertEquals(5, RefreshSchedule.patternScanTarget(now, ist).minute)
        assertEquals(Duration.ofHours(10) + Duration.ofMinutes(5), RefreshSchedule.patternScanInitialDelay(now, ist))
    }

    @Test
    fun `the two jobs are always five minutes apart`() {
        // Any two instants that are five minutes apart in local time are five minutes apart in
        // real time, because IST has no DST — which is exactly why IST needs no DST branch.
        val now = istAt(2026, 10, 1, 13, 22)
        val gap = Duration.between(
            RefreshSchedule.eodTarget(now, ist),
            RefreshSchedule.patternScanTarget(now, ist),
        )

        assertEquals(Duration.ofMinutes(5), gap)
    }

    // ------------------------------------------------------------------ already past 19:00

    @Test
    fun `a run scheduled after 1900 IST is deferred to the next evening, never to now`() {
        val now = istAt(2026, 10, 1, 20, 30)
        val delay = RefreshSchedule.eodInitialDelay(now, ist)
        val target = RefreshSchedule.eodTarget(now, ist)

        assertEquals(LocalDate.of(2026, 10, 2), target.toLocalDate())
        assertEquals(19, target.hour)
        // 22.5 hours: 3.5 hours to midnight plus 19 hours.
        assertEquals(Duration.ofMinutes(22 * 60 + 30), delay)
    }

    @Test
    fun `a run scheduled at exactly 1900 IST is deferred rather than firing immediately`() {
        val now = istAt(2026, 10, 1, 19, 0)

        // `!candidate.isAfter(now)` is true at exact equality, so the target rolls forward.
        assertEquals(LocalDate.of(2026, 10, 2), RefreshSchedule.eodTarget(now, ist).toLocalDate())
        assertEquals(Duration.ofHours(24), RefreshSchedule.eodInitialDelay(now, ist))
    }

    @Test
    fun `the pattern scan at 1900 IST is also deferred even though its own target is 1905`() {
        val now = istAt(2026, 10, 1, 19, 2)
        val delay = RefreshSchedule.patternScanInitialDelay(now, ist)

        assertEquals(LocalDate.of(2026, 10, 2), RefreshSchedule.patternScanTarget(now, ist).toLocalDate())
        // 19:02 today to 19:05 tomorrow is 24 h 3 m.
        assertEquals(Duration.ofHours(24) + Duration.ofMinutes(3), delay)
    }

    @Test
    fun `the delay is never negative at any minute of the day`() {
        var minute = 0
        while (minute < 24 * 60) {
            val now = istAt(2026, 10, 1, minute / 60, minute % 60)
            val eod = RefreshSchedule.eodInitialDelay(now, ist)
            val scan = RefreshSchedule.patternScanInitialDelay(now, ist)
            assertTrue(eod >= Duration.ZERO, "negative EOD delay at minute $minute: $eod")
            assertTrue(scan >= Duration.ZERO, "negative scan delay at minute $minute: $scan")
            assertTrue(eod >= Duration.ofMinutes(1), "zero EOD delay at minute $minute")
            assertTrue(eod <= Duration.ofHours(24), "EOD delay longer than a period at minute $minute")
            assertTrue(scan <= Duration.ofHours(24) + Duration.ofMinutes(5), "scan delay longer than a period at minute $minute")
            minute++
        }
    }

    // ------------------------------------------------------------------------- weekends

    @Test
    fun `a run scheduled on a Saturday still targets 1900, and the worker is what skips it`() {
        // 2026-10-03 is a Saturday. The *schedule* is wall-clock and does not know about trading
        // days; `TradingCalendar` is the guard that makes the run exit early.
        val saturday = istAt(2026, 10, 3, 10, 0)
        assertEquals(java.time.DayOfWeek.SATURDAY, saturday.dayOfWeek)

        val target = RefreshSchedule.eodTarget(saturday, ist)
        assertEquals(LocalDate.of(2026, 10, 3), target.toLocalDate())
        assertEquals(19, target.hour)
        assertTrue(TradingCalendar.isWeekend(target.toLocalDate()))
    }

    // ------------------------------------------------------------------------------ IST

    @Test
    fun `IST is UTC plus 5 hours 30 minutes in January and in July`() {
        // No DST: this is why plan.md §10's schedule needs no DST branch for the market zone.
        val winter = istAt(2026, 1, 15, 12, 0)
        val summer = istAt(2026, 7, 15, 12, 0)

        assertEquals("+05:30", winter.offset.toString())
        assertEquals("+05:30", summer.offset.toString())
    }

    @Test
    fun `anchoring to IST from a device in another zone still targets 1900 IST`() {
        val istZone = ZoneId.of("Asia/Kolkata")
        val berlin = ZoneId.of("Europe/Berlin")
        // 2026-10-01 08:00 Berlin (CEST, UTC+2) is 06:00 UTC, i.e. 11:30 IST.
        val now = ZonedDateTime.of(LocalDate.of(2026, 10, 1), java.time.LocalTime.of(8, 0), berlin)
        val target = RefreshSchedule.eodTarget(now, istZone)

        assertEquals(istZone, target.zone)
        assertEquals(19, target.hour)
        assertEquals(0, target.minute)
        // 11:30 IST to 19:00 IST, measured across two zones.
        assertEquals(Duration.ofHours(7) + Duration.ofMinutes(30), Duration.between(now, target))
        assertEquals(
            target.toInstant(),
            now.withZoneSameInstant(istZone).plus(RefreshSchedule.eodInitialDelayForIst(now)).toInstant(),
        )
    }

    @Test
    fun `the device-zone variant targets 1900 in the device zone`() {
        val berlin = ZoneId.of("Europe/Berlin")
        val now = ZonedDateTime.of(LocalDate.of(2026, 10, 1), java.time.LocalTime.of(8, 0), berlin)
        val target = RefreshSchedule.eodTarget(now, berlin)

        assertEquals(19, target.hour)
        assertEquals(berlin, target.zone)
    }

    // ------------------------------------------------------------------------------ DST

    @Test
    fun `a local time inside the spring-forward gap is resolved, not left invalid`() {
        val zone = ZoneId.of("America/New_York")
        // 2026-03-08: 02:00–03:00 local does not exist. 02:30 is the classic broken case, and
        // `ZonedDateTime.of` resolves it by moving past the gap rather than throwing.
        val now = ZonedDateTime.of(LocalDate.of(2026, 3, 7), java.time.LocalTime.of(12, 0), zone)
        val resolved = RefreshSchedule.target(now, zone, minutesAfterTarget = 0L, hour = 2, minute = 30)

        assertEquals(3, resolved.hour)
        assertEquals(30, resolved.minute)
        assertEquals(ZoneId.of("America/New_York"), resolved.zone)
    }

    @Test
    fun `spring forward makes the elapsed delay one hour shorter than the wall clock difference`() {
        val zone = ZoneId.of("Europe/Berlin")
        // 2026-03-29 is the European spring-forward date: 02:00 CET becomes 03:00 CEST, so the
        // evening of that day is 23 hours long.
        val now = ZonedDateTime.of(LocalDate.of(2026, 3, 29), java.time.LocalTime.of(0, 30), zone)
        val target = RefreshSchedule.eodTarget(now, zone)

        // The wall clock says 19:00 − 00:30 = 18h30m ...
        assertEquals(
            Duration.ofMinutes(18 * 60 + 30),
            Duration.between(now.toLocalTime(), java.time.LocalTime.of(19, 0)),
        )
        // ... but the real elapsed time is 17h30m, because an hour never happened.
        assertEquals(Duration.ofMinutes(17 * 60 + 30), Duration.between(now, target))
        assertEquals(19, target.hour)
    }

    @Test
    fun `fall back makes the elapsed delay one hour longer than the wall clock difference`() {
        val zone = ZoneId.of("Europe/Berlin")
        // 2026-10-25 is the European fall-back date: 03:00 CEST becomes 02:00 CET, so the
        // evening of that day is 25 hours long.
        val now = ZonedDateTime.of(LocalDate.of(2026, 10, 25), java.time.LocalTime.of(0, 30), zone)
        val target = RefreshSchedule.eodTarget(now, zone)

        // Wall clock says 18h30m; the real elapsed time is 19h30m.
        assertEquals(Duration.ofMinutes(19 * 60 + 30), Duration.between(now, target))
        assertEquals(19, target.hour)
        assertEquals(target.toLocalDate(), now.toLocalDate())
    }

    @Test
    fun `the target hour survives a DST transition on a fixed wall clock`() {
        val zone = ZoneId.of("Europe/Berlin")
        val beforeChange = ZonedDateTime.of(LocalDate.of(2026, 10, 24), java.time.LocalTime.of(12, 0), zone)
        val afterChange = ZonedDateTime.of(LocalDate.of(2026, 10, 26), java.time.LocalTime.of(12, 0), zone)

        val targetBefore = RefreshSchedule.eodTarget(beforeChange, zone)
        val targetAfter = RefreshSchedule.eodTarget(afterChange, zone)

        // Both are 19:00 *local*, but the offsets differ: CEST (+02) then CET (+01).
        assertEquals(19, targetBefore.hour)
        assertEquals(19, targetAfter.hour)
        assertEquals(2 * 3600, targetBefore.offset.totalSeconds)
        assertEquals(1 * 3600, targetAfter.offset.totalSeconds)
    }

    // ------------------------------------------------------------------ other invariants

    @Test
    fun `the period and the backoff floor are plan md section 10`() {
        assertEquals(Duration.ofHours(24), RefreshSchedule.PERIOD)
        assertEquals(Duration.ofMinutes(30), RefreshSchedule.MIN_BACKOFF)
        assertEquals(19, RefreshSchedule.TARGET_HOUR)
        assertEquals(5L, RefreshSchedule.PATTERN_SCAN_OFFSET_MINUTES)
    }

    @Test
    fun `describe names the target date, time and zone`() {
        val now = istAt(2026, 10, 1, 9, 0)
        val text = RefreshSchedule.describe(now, RefreshSchedule.eodTarget(now, ist))

        assertTrue(text.contains("2026-10-01"), text)
        assertTrue(text.contains("19:00"), text)
        assertTrue(text.contains("Asia/Kolkata"), text)
        assertTrue(text.contains("10h 0m"), text)
    }
}