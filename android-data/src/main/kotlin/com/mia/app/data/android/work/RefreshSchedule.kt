package com.mia.app.data.android.work

import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * When the nightly refresh runs. **Pure arithmetic**, no Android, no clock read, no WorkManager.
 *
 * Every function takes "now" as a parameter and returns a [Duration] or a [ZonedDateTime], which
 * is what makes the whole schedule assertable in a plain JVM test — the reason this object exists
 * separately from `RefreshWork`, which is the part that cannot be tested here.
 *
 * ---
 *
 * ## The maths
 *
 * plan.md §10:
 *
 * ```
 * 15:30 IST   market close
 * ~18:00 IST  bhavcopy published
 * 19:00 IST   WorkManager: fetch bhavcopy → parse → replace market cache
 * 19:05 IST   WorkManager: rescan the user's tracked symbols → generate alerts
 * ```
 *
 * WorkManager's `PeriodicWorkRequest.setInitialDelay` takes a **duration**, not a wall-clock
 * time, so the target has to be turned into an offset. The offset is:
 *
 * ```
 * target(now)   = ZonedDateTime(now).toLocalDate().atTime(19:00) in now's zone
 * target(now)  += +5 minutes                                   (PatternScanWorker only)
 * if target <= now: target += 1 day                           (the "already past 19:00" case)
 * delay          = Duration.between(now, target)
 * ```
 *
 * ### Why the "+1 day" is required, not an optimisation
 * At 19:00 IST the bhavcopy is only *just* published (plan.md §3.1 measured 18:32 IST, and the
 * 19:00 run is deliberately one hour of slack). A periodic worker's first run and every
 * subsequent run land `24 h` after the previous one, which drifts: Doze, battery saver and a
 * device that was off all move it. The initial delay is therefore computed once, at schedule
 * time, against the moment of scheduling — and WorkManager then re-anchors every period from
 * whatever time it actually ran, so the *worst* case is that a run lands early in the evening
 * rather than at 3 a.m. On the next run the offset is recomputed from the then-current time, so
 * a run that fired at 03:00 is followed by one 16 hours later, at 19:00.
 *
 * ### The "already past 19:00" case
 * At 20:30 the target for *today* is in the past. Scheduling a negative delay is impossible, and
 * scheduling a zero delay means "run immediately", which on a first launch would fire a
 * whole-market download in the middle of the evening for a file NSE published two hours ago.
 * Taking the **next** day keeps the invariant `delay >= 0` and keeps the first run inside the
 * same evening window, one day later.
 *
 * ### Time zone
 * The delay is computed **in the device's own zone**, so a user whose phone says
 * `Europe/Berlin` gets the refresh at 19:00 Berlin time. For the app's actual audience — an
 * on-device Indian market app — that *is* 19:00 IST, because IST has no DST and
 * `Asia/Kolkata` is UTC+05:30 year-round. `initialDelayForZone` exists for the case where the
 * schedule must be anchored to IST regardless of the device clock, which is what a build that
 * wants "19:00 IST" as a market fact rather than a wall-clock fact would use.
 *
 * ### DST
 * Two things keep the arithmetic honest across a DST transition:
 * 1. The target is built with `ZonedDateTime.of(date, time, zone)`, which **resolves** a local
 *    time that does not exist (spring forward) by moving it past the gap, and keeps the offset
 *    correct across the change.
 * 2. The day is added with `plusDays(1)` on the `ZonedDateTime`, which adds a **calendar** day
 *    and preserves local wall-clock time. The offset may change; the hour stays 19:00.
 *
 * Because `Duration.between` compares instants, the delay after a spring-forward is one hour
 * *shorter* than before and after a fall-back one hour *longer*, which is exactly right: the
 * wall-clock target moved by an hour.
 */
object RefreshSchedule {

    /** plan.md §10: the bhavcopy fetch. */
    const val EOD_TARGET_HOUR: Int = 19

    /** plan.md §10: "19:05 IST rescan the user's tracked symbols". */
    const val PATTERN_SCAN_OFFSET_MINUTES: Long = 5L

    /**
     * ~24 h, exactly. plan.md §10 asks for "periodic ~24h".
     *
     * 24 h rather than 15 min because the whole point is one whole-market fetch per day
     * (plan.md §13's CGNAT mitigation); the interval is not a freshness knob.
     */
    val PERIOD: Duration = Duration.ofHours(24)

    /** plan.md §10: "Backoff: exponential, min 30 min". */
    val MIN_BACKOFF: Duration = Duration.ofMinutes(30)

    /** plan.md §10's constraint, restated so the worker and its test read one number. */
    const val TARGET_HOUR: Int = EOD_TARGET_HOUR

    /** The target wall-clock time in the device's zone. */
    val TARGET_TIME: LocalTime = LocalTime.of(EOD_TARGET_HOUR, 0)

    // --------------------------------------------------------------------- EOD (bhavcopy)

    /**
     * The instant the next EOD run should fire, given [now] and a target zone.
     *
     * @param zone the zone the 19:00 target is expressed in. Defaults to [now]'s own zone.
     */
    fun eodTarget(now: ZonedDateTime, zone: ZoneId = now.zone): ZonedDateTime =
        target(now, zone, minutesAfterTarget = 0L)

    /** [eodTarget] as a duration. Never negative; see the class KDoc for the maths. */
    fun eodInitialDelay(now: ZonedDateTime, zone: ZoneId = now.zone): Duration =
        Duration.between(now, eodTarget(now, zone))

    /** Anchored to IST rather than to the device clock. See the class KDoc. */
    fun eodInitialDelayForIst(now: ZonedDateTime): Duration =
        eodInitialDelay(now, TradingCalendar.MARKET_ZONE)

    // ------------------------------------------------------------------ pattern scan (19:05)

    /** The instant the next watchlist rescan should fire — 19:05 by plan.md §10. */
    fun patternScanTarget(now: ZonedDateTime, zone: ZoneId = now.zone): ZonedDateTime =
        eodTarget(now, zone).plusMinutes(PATTERN_SCAN_OFFSET_MINUTES)

    /** [patternScanTarget] as a duration. Never negative. */
    fun patternScanInitialDelay(now: ZonedDateTime, zone: ZoneId = now.zone): Duration =
        Duration.between(now, patternScanTarget(now, zone))

    // ------------------------------------------------------------------------------ shared

    /**
     * The one place the maths lives.
     *
     * Steps, in order:
     * 1. Take [now]'s date in [zone] — the *local* calendar day, which is what "19:00" means.
     * 2. Build 19:00 (plus [minutesAfterTarget]) at that date in [zone].
     * 3. If that instant is not after [now], roll forward one calendar day.
     * 4. Return the instant; the caller measures the gap.
     */
    fun target(
        now: ZonedDateTime,
        zone: ZoneId = now.zone,
        minutesAfterTarget: Long = 0L,
        hour: Int = EOD_TARGET_HOUR,
        minute: Int = 0,
    ): ZonedDateTime {
        val here = now.withZoneSameInstant(zone)
        val localDate = here.toLocalDate()
        val time = LocalTime.of(hour, minute).plusMinutes(minutesAfterTarget)
        // `ZonedDateTime.of` resolves a local time inside a DST gap by moving it past the gap,
        // so a device in a zone that springs forward at 19:00 gets the first valid instant of
        // that evening rather than a nonexistent one.
        var candidate = ZonedDateTime.of(localDate, time, zone)
        if (!candidate.isAfter(now)) {
            // `plusDays` on a ZonedDateTime adds a calendar day and preserves the local
            // wall-clock time, re-resolving the offset — which is what keeps 19:00 at 19:00
            // across a DST change.
            candidate = candidate.plusDays(1)
        }
        return candidate
    }

    /**
     * Whole days between two instants, used by the worker's staleness check.
     *
     * Deliberately a [Long] count rather than a boolean: "2 days old" and "31 days old" lead to
     * different copy, and the difference is a subtraction.
     */
    fun daysBetween(from: ZonedDateTime, to: ZonedDateTime): Long =
        ChronoUnit.DAYS.between(from, to)

    /** A one-line explanation of a computed delay, for `work_state` and for logs. */
    fun describe(now: ZonedDateTime, target: ZonedDateTime): String {
        val delay = Duration.between(now, target)
        val minutes = delay.toMinutes()
        return "scheduled for ${target.toLocalDate()} " +
            "${String.format("%02d", target.hour)}:${String.format("%02d", target.minute)} " +
            "${target.zone.id} (in ${minutes / 60}h ${minutes % 60}m from now)"
    }
}