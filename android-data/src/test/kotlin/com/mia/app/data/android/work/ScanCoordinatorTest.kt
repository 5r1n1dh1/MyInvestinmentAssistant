package com.mia.app.data.android.work

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The nightly fan-out's accounting.
 *
 * plan.md §12 makes silent-empty the most dangerous possible failure, and plan.md §10 requires the
 * last outcome to be visible. These tests pin the three things that keeps honest:
 * * a scan that found nothing reads differently from a scan that could not run;
 * * a failure is counted, not folded into the success total;
 * * "0 alerts delivered" is a fact, not a defect.
 */
class ScanCoordinatorTest {

    private val asOf: LocalDate = LocalDate.parse("2026-10-01")

    private fun scanned(symbol: String, matches: Int, topScore: Int) = SymbolScanOutcome(
        symbol = symbol,
        status = SymbolScanStatus.SCANNED,
        matchCount = matches,
        topScore = topScore,
        note = "scanned",
    )

    private fun skipped(symbol: String, bars: Int) = SymbolScanOutcome(
        symbol = symbol,
        status = SymbolScanStatus.SKIPPED_NOT_ENOUGH_HISTORY,
        matchCount = 0,
        topScore = ScanCoordinator.REPORTED_SCORE_FLOOR - 1,
        note = "$bars bar(s) is below plan.md §6.4's gate; no score was emitted",
    )

    private fun failed(symbol: String, reason: String) = SymbolScanOutcome(
        symbol = symbol,
        status = SymbolScanStatus.FAILED,
        matchCount = 0,
        topScore = -1,
        note = reason,
    )

    @Test
    fun `an empty watchlist produces an empty summary rather than a failure`() {
        val summary = ScanCoordinator.summarise(emptyList(), asOf)

        assertEquals(0, summary.attempted)
        assertEquals(0, summary.scanned)
        assertEquals(0, summary.failed)
        assertTrue(!summary.isDegraded)
        assertEquals("0 of 0 watched symbol(s) scanned as of 2026-10-01, 0 alert(s) delivered", summary.headline())
    }

    @Test
    fun `a clean pass reports the counts and is not degraded`() {
        val outcomes = listOf(
            scanned("RELIANCE", 2, 78),
            scanned("TCS", 1, 64),
            scanned("INFY", 0, -1),
        )

        val summary = ScanCoordinator.summarise(outcomes, asOf, alertsDelivered = 1)

        assertEquals(3, summary.attempted)
        assertEquals(3, summary.scanned)
        assertEquals(0, summary.failed)
        assertEquals(0, summary.skipped)
        // RELIANCE (78) and TCS (64) are at or above the render threshold of 60.
        assertEquals(2, summary.reported)
        assertEquals(1, summary.alertsDelivered)
        assertTrue(!summary.isDegraded)
        assertNull(ScanCoordinator.failureNote(summary), "a clean pass must leave no failure note")
    }

    @Test
    fun `a scan that found nothing is still a successful scan`() {
        // plan.md §12: "no pattern" is a real answer; "could not check" is the dangerous one.
        val summary = ScanCoordinator.summarise(listOf(scanned("INFY", 0, -1)), asOf)

        assertEquals(1, summary.scanned)
        assertEquals(0, summary.failed)
        assertEquals(0, summary.reported)
        assertTrue(!summary.isDegraded)
    }

    @Test
    fun `insufficient history is counted separately from a failure`() {
        val summary = ScanCoordinator.summarise(
            listOf(scanned("RELIANCE", 1, 80), skipped("TCS", 41)),
            asOf,
        )

        assertEquals(1, summary.scanned)
        assertEquals(1, summary.skipped)
        assertEquals(0, summary.failed)
        // A skip is not a failure, but it is also not invisible: the note carries it.
        assertTrue(!summary.isDegraded)
        assertEquals(1, summary.notes.size)
        assertTrue(summary.notes.single().startsWith("TCS:"), summary.notes.single())
        assertTrue(summary.headline().contains("1 skipped for insufficient history"), summary.headline())
    }

    @Test
    fun `a failure is counted and never folded into the scanned total`() {
        val summary = ScanCoordinator.summarise(
            listOf(
                scanned("RELIANCE", 1, 75),
                scanned("TCS", 0, -1),
                failed("INFY", "NoSuchHostException: no route to host"),
            ),
            asOf,
        )

        assertEquals(3, summary.attempted)
        assertEquals(2, summary.scanned)
        assertEquals(1, summary.failed)
        assertTrue(summary.isDegraded)
        assertTrue(summary.headline().contains("1 failed"), summary.headline())
    }

    @Test
    fun `the failure note names the count and the first few symbols`() {
        val outcomes = (1..8).map { failed("SYM$it", "boom $it") }

        val note = ScanCoordinator.failureNote(ScanCoordinator.summarise(outcomes, asOf))

        assertTrue(note != null)
        val text = note ?: ""
        assertTrue(text.startsWith("8 of 8 watched symbol(s) failed as of 2026-10-01"), text)
        assertTrue(text.contains("SYM1"), text)
        // Bounded so `work_state.lastError` cannot become a paragraph.
        assertTrue(!text.contains("SYM8"), "only the first ${ScanCoordinator.MAX_NOTES} notes are kept: $text")
    }

    @Test
    fun `notes preserve input order so a failure is reproducible`() {
        val outcomes = listOf(failed("AAA", "a"), failed("BBB", "b"), failed("CCC", "c"))

        val notes = ScanCoordinator.summarise(outcomes, asOf).notes

        assertEquals(listOf("AAA: a", "BBB: b", "CCC: c"), notes)
    }

    @Test
    fun `zero alerts delivered is reported and is not a degradation`() {
        // The default dispatcher is a no-op, so this is the normal state on a device the user has
        // not granted notification permission to.
        val summary = ScanCoordinator.summarise(listOf(scanned("RELIANCE", 1, 80)), asOf, alertsDelivered = 0)

        assertEquals(0, summary.alertsDelivered)
        assertTrue(!summary.isDegraded)
        assertTrue(summary.headline().contains("0 alert(s) delivered"), summary.headline())
    }

    @Test
    fun `the reported floor is the domain's own render threshold`() {
        // Read from `MatchScorePolicy`, so the nightly log cannot disagree with the pattern screen.
        assertEquals(50, ScanCoordinator.REPORTED_SCORE_FLOOR)
        assertEquals(
            com.mia.app.domain.model.MatchScorePolicy.RENDER_THRESHOLD,
            ScanCoordinator.REPORTED_SCORE_FLOOR,
        )
    }

    @Test
    fun `the tally is order-independent`() {
        val a = listOf(scanned("A", 1, 80), failed("B", "x"))
        val b = listOf(failed("B", "x"), scanned("A", 1, 80))

        val first = ScanCoordinator.summarise(a, asOf)
        val second = ScanCoordinator.summarise(b, asOf)

        assertEquals(first.copy(notes = second.notes), second)
    }
}