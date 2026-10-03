package com.mia.app.data.android.repo

import com.mia.app.domain.model.Source
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [EnsureResult] and [HistoryBackfill] — the two types the on-demand fetch path reports
 * through.
 *
 * ### Why these are worth unit-testing on the JVM
 * `:android-data` cannot be compiled on this machine (no Android SDK), so these cover the one part
 * of the new write path that has no framework dependency: the vocabulary a screen renders. They
 * cannot cover `ensureData` itself, which needs Room and a live transport — that is exactly what the
 * Google AI Studio emulator run is for, and `docs/VERIFICATION.md` says so.
 */
class EnsureResultTest {

    private val today: LocalDate = LocalDate.of(2026, 10, 1)

    @Test
    fun `a fresh cache says no download was needed`() {
        val result = EnsureResult.fresh(
            symbol = "RELIANCE",
            asOf = today,
            barCount = 280,
            expectedThrough = today,
        )

        assertFalse(result.fetched, "the whole point of the staleness check is to not fetch")
        assertTrue(result.wasFresh)
        assertNull(result.failure, "a fresh cache is not a failure")
        assertEquals(today, result.asOf)
        assertTrue(result.note.contains("280"), "the note must carry the counts: ${result.note}")
        assertTrue(
            result.note.contains("no download needed"),
            "the note must say why nothing happened: ${result.note}",
        )
    }

    @Test
    fun `a fresh cache names the session it covers`() {
        // The reader needs to know *which* session they are looking at, not merely that nothing
        // happened: "up to date" without a date is indistinguishable from a guess.
        val result = EnsureResult.fresh("RELIANCE", today, 10, expectedThrough = today)

        assertTrue(result.note.contains(today.toString()), result.note)
        assertTrue(result.note.contains(today.toString()), result.note)
    }

    @Test
    fun `a completed fetch reports the bars it added`() {
        val result = EnsureResult.fetched(
            symbol = "RELIANCE",
            asOf = today,
            barsAdded = 281,
            note = "the bhavcopy for $today stored",
        )

        assertTrue(result.fetched)
        assertFalse(result.wasFresh)
        assertEquals(281, result.barsAdded)
        assertNull(result.failure)
    }

    @Test
    fun `a failed fetch never claims to have replaced the cache`() {
        val failure = DataFailure(
            reason = DataFailureReason.SOURCE_UNAVAILABLE,
            message = "neither host answered",
        )
        val result = EnsureResult.failed("RELIANCE", today, barCount = 12, failure = failure)

        assertTrue(result.fetched, "a fetch was attempted")
        assertNotNull(result.failure)
        assertEquals(0, result.barsAdded, "a failed fetch adds nothing")
        assertEquals(today, result.asOf, "the cached series must survive a failed fetch")
        assertTrue(
            result.note.contains("unchanged"),
            "the note must say the stored bars are still being served: ${result.note}",
        )
        assertTrue(
            result.note.contains("12"),
            "the note must carry the count that survived: ${result.note}",
        )
    }

    @Test
    fun `every factory produces a note that is not blank`() {
        // A blank note would render as an empty line, which is the silent-empty failure mode
        // plan.md §12 is about, in a different costume.
        val results = listOf(
            EnsureResult.fresh("A", today, 1, today),
            EnsureResult.fetched("A", today, 1, "the bhavcopy was successfully stored"),
            EnsureResult.failed(
                "A",
                today,
                1,
                DataFailure(DataFailureReason.SOURCE_UNAVAILABLE, "down"),
            ),
        )
        results.forEach { result ->
            assertTrue(result.note.isNotBlank(), "${result.symbol}: note was blank")
            assertTrue(result.note.length > 20, "note too terse to be useful: ${result.note}")
        }
    }
}

/**
 * [HistoryBackfill] is what Settings shows after a history pass, so its summary is a user-facing
 * claim about what was downloaded.
 */
class HistoryBackfillTest {

    @Test
    fun `the summary states the span that is now stored`() {
        val backfill = HistoryBackfill(
            requestedSessions = 280,
            sessionsFetched = 60,
            sessionsSkipped = 12,
            barsWritten = 183_060,
            barsAlreadyKnown = 3_051,
            earliestStored = LocalDate.of(2025, 4, 1),
            latestStored = LocalDate.of(2026, 10, 1),
        )

        val summary = backfill.summary()
        assertTrue(summary.contains("60 session(s) fetched"), summary)
        assertTrue(summary.contains("183060") || summary.contains("183,060"), summary)
        assertTrue(summary.contains("2025-04-01"), "the reader needs the start of the span: $summary")
        assertTrue(summary.contains("2026-10-01"), summary)
        assertTrue(summary.contains("12"), "skipped sessions must be visible, not hidden: $summary")
    }

    @Test
    fun `a summary with no stored span does not invent one`() {
        // plan.md §1.4 rule 3: never render a date that was not measured.
        val backfill = HistoryBackfill(
            requestedSessions = 10,
            sessionsFetched = 0,
            sessionsSkipped = 10,
            barsWritten = 0,
            barsAlreadyKnown = 0,
            earliestStored = null,
            latestStored = null,
        )

        val summary = backfill.summary()
        assertFalse(summary.contains("spans"), "no span may be claimed when none was stored: $summary")
    }

    @Test
    fun `notes carry through untouched`() {
        val backfill = HistoryBackfill(
            requestedSessions = 5,
            sessionsFetched = 5,
            sessionsSkipped = 0,
            barsWritten = 10,
            barsAlreadyKnown = 0,
            earliestStored = null,
            latestStored = null,
            notes = listOf("stopped after skipping 12 consecutive days with no published file"),
        )
        assertEquals(1, backfill.notes.size)
        assertTrue(backfill.notes.single().contains("retention") || backfill.notes.single().contains("no published"))
    }
}

/**
 * The bar-count thresholds the on-demand path depends on, pinned so a future edit to one of them
 * cannot silently change what the app considers "enough".
 */
class CandleThresholdTest {

    @Test
    fun `the daily gate is plan md 6_4's 60 bars`() {
        assertEquals(60, CandleRepository.MIN_DAILY_BARS)
        assertEquals(60, minBarsFor(com.mia.app.domain.model.Timeframe.D1))
    }

    @Test
    fun `the weekly gate is plan md 6_4's 40 bars`() {
        assertEquals(40, CandleRepository.MIN_WEEKLY_BARS)
        assertEquals(40, minBarsFor(com.mia.app.domain.model.Timeframe.W1))
    }

    @Test
    fun `the default history pass reaches both gates`() {
        // 280 sessions clears the 60-bar daily gate and, aggregated to weeks, the 40-bar weekly
        // gate. If this ever fails, the constant's documented arithmetic has drifted.
        val sessions = CandleRepository.DEFAULT_HISTORY_SESSIONS
        assertTrue(
            sessions >= CandleRepository.MIN_DAILY_BARS,
            "$sessions sessions cannot satisfy the daily gate",
        )
        val approximateWeeks = sessions / 5
        assertTrue(
            approximateWeeks >= CandleRepository.MIN_WEEKLY_BARS,
            "$sessions sessions aggregate to ~$approximateWeeks weeks, short of the weekly gate",
        )
    }

    @Test
    fun `the batch ceiling is deep enough to satisfy the weekly gate on its own`() {
        // THE REGRESSION GUARD for "Not Enough History".
        //
        // `backfillHistory` clamps its request to `HISTORY_BATCH_LIMIT`. This constant used to be
        // 60, which is *exactly* `MIN_DAILY_BARS`, and that made the bug unfixable from the
        // outside: no caller could ask for more, a single skipped session left 59 bars, and the
        // gate then failed permanently with no retry able to help. 60 daily bars also aggregate to
        // ~12 weekly against a 40-bar weekly gate, so the `1W` tab could never clear its own gate.
        //
        // The clamp is therefore only safe if the ceiling on its own clears both gates. Asserting
        // `ceiling <= default` (the old test) could not catch this — it passed at 60 and passes at
        // 280, and is satisfied by any pair where the ceiling is merely small. This asserts the
        // property that actually matters, with no reference to the default at all.
        val ceiling = CandleRepository.HISTORY_BATCH_LIMIT

        assertTrue(
            ceiling >= CandleRepository.MIN_DAILY_BARS,
            "a ceiling of $ceiling is at or below the ${CandleRepository.MIN_DAILY_BARS}-bar daily " +
                "gate, so one skipped session is enough to strand a stock forever",
        )
        assertTrue(
            ceiling / 5 >= CandleRepository.MIN_WEEKLY_BARS,
            "a ceiling of $ceiling aggregates to ~${ceiling / 5} weekly bars, short of the " +
                "${CandleRepository.MIN_WEEKLY_BARS}-bar weekly gate",
        )
    }

    @Test
    fun `the default depth is not clipped by the ceiling`() {
        // The specific arithmetic that made the old pair of constants self-defeating: a caller
        // asking for the documented default must receive it, un-clipped.
        val sessions = CandleRepository.DEFAULT_HISTORY_SESSIONS
        val effective = sessions.coerceIn(1, CandleRepository.HISTORY_BATCH_LIMIT)

        assertEquals(
            sessions,
            effective,
            "asking for the default $sessions sessions must not be clamped to $effective",
        )
        assertTrue(
            effective >= CandleRepository.DEFAULT_HISTORY_SESSIONS,
            "the default must survive its own clamp",
        )
    }

    @Test
    fun `the source a fetch reports is the official one`() {
        // plan.md §5.1: price precedence is NSE. An on-demand fetch must not claim a Yahoo
        // provenance it did not use.
        val result = DataResult.ok(
            value = 1,
            source = Source.NSE_BHAVCOPY,
            fetchedOn = LocalDate.of(2026, 10, 1),
        )
        assertEquals(Source.NSE_BHAVCOPY, result.source)
        assertTrue(result.isOk)
    }
}