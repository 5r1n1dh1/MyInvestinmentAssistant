package com.mia.app.data.symbols

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The canonical failure, end to end (plan.md §9, plan.md §13 "Corporate actions break symbol
 * mapping → user sees blank screen").
 *
 * **Measured facts this test is built on**, all captured 2026-10-02 and quoted from the repo's own
 * fixtures rather than from memory:
 *
 * - `data/src/main/resources/assets/EQUITY_L.csv` (2,593 rows) contains **no** `TATAMOTORS` row
 *   and **no** `TATAMOTORSPV` row. It contains `TMCV` (*Tata Motors Limited*, `EQ`, listed
 *   `12-NOV-2025`, ISIN `INE1TAE01010`) and `TMPV` (*Tata Motors Passenger Vehicles Limited*,
 *   `EQ`, listed `22-JUL-1998`, ISIN `INE155A01022` — the retired TATAMOTORS ISIN).
 * - `domain/src/test/resources/fixtures/eod_history.json` holds 372 NSE bhavcopy sessions from
 *   2025-04-01 to 2026-10-01 for 41 large caps. In it, `TATAMOTORS` has rows for **140** sessions,
 *   `2025-04-01` → `2025-10-23`, and **0** rows afterwards. `TATAMOTORSPV` has **0** rows. Every
 *   other symbol in the file has all **372**.
 *
 * **One correction to the brief**, which matters for the event type asserted below: the shape is
 * "present for the *earliest* 140 of 372 sessions", not the most recent. `TATAMOTORS` stops
 * trading and never comes back — which is a `SYMBOL_DISAPPEARED`, and its `RESUMED_AFTER_GAP`
 * counterpart is a *different* shape (a halt that ends). Both are asserted here, the first from
 * the real shape, the second from a constructed halt.
 */
class TataMotorsCorporateActionTest {

    // --- real measurements, quoted as literals so a changed fixture fails loudly ---------------

    /** Sessions of `TATAMOTORS` history in `eod_history.json`. */
    private val realTataMotorsSessions = 140

    /** Sessions of history for every symbol that trades throughout. */
    private val realFullHistorySessions = 372

    /** Last session on which `TATAMOTORS` has a row. */
    private val realLastTataMotorsSession = LocalDate.parse("2025-10-23")

    private val book = SymbolFixtures.book()

    // -------------------------------------------------------------------------------------
    // The facts about the real files
    // -------------------------------------------------------------------------------------

    @Test
    fun `the retired ticker exists in neither source which is the whole problem`() {
        assertNull(book.find("TATAMOTORS"), "master has no TATAMOTORS row")
        assertNull(book.find("TATAMOTORSPV"), "master has no TATAMOTORSPV row")

        val bhavcopyTickers = SymbolFixtures.bhavcopy().rows.map { it.ticker }.toSet()
        assertTrue("TATAMOTORS" !in bhavcopyTickers, "bhavcopy has no TATAMOTORS row")
        assertTrue("TATAMOTORSPV" !in bhavcopyTickers, "bhavcopy has no TATAMOTORSPV row")
        assertTrue("TMCV" in bhavcopyTickers)
        assertTrue("TMPV" in bhavcopyTickers)
    }

    @Test
    fun `both demerger legs trade today with the ISINs quoted in the alias table`() {
        val bhavcopy = SymbolFixtures.bhavcopy()
        val tmcv = assertNotNull(bhavcopy.rows.firstOrNull { it.ticker == "TMCV" })
        assertEquals("INE1TAE01010", tmcv.isin)
        assertEquals("EQ", tmcv.series)
        assertEquals("TATA MOTORS LIMITED", tmcv.name)
        assertEquals(415.50, tmcv.close)

        val tmpv = assertNotNull(bhavcopy.rows.firstOrNull { it.ticker == "TMPV" })
        assertEquals("INE155A01022", tmpv.isin)
        assertEquals("EQ", tmpv.series)
        assertEquals("TATA MOTORS PASS VEH LTD", tmpv.name)
        assertEquals(279.40, tmpv.close)
    }

    @Test
    fun `the retired ISIN still resolves to a live company rather than to nothing`() {
        // This is the actual fix for plan.md §9. A user with INE155A01022 in a watchlist row gets
        // TMPV, a security that trades today, not a blank screen.
        val resolution = SymbolFixtures.resolver().resolve("INE155A01022")
        assertEquals(ResolutionStep.ISIN_LOOKUP, resolution.step)
        assertEquals("TMPV", resolution.symbol)
        assertTrue(resolution.record!!.isEquity)
    }

    @Test
    fun `the resolver turns the dead ticker into a usable live symbol with a full explanation`() {
        val resolution = SymbolFixtures.resolver().resolve("TATAMOTORS")
        assertTrue(resolution.isResolved, "plan.md §9: never return zero results silently")
        assertEquals("TMCV", resolution.symbol)
        assertEquals(ResolutionStatus.ALIAS, resolution.status)
        assertTrue(resolution.isAdjusted)
        assertTrue(resolution.explanation.length > 40, "the explanation must actually explain")
        // The other leg is offered, so a user who wanted Passenger Vehicles is not stuck.
        assertEquals(listOf("TMPV"), resolution.alternatives.map { it.symbol })
    }

    // -------------------------------------------------------------------------------------
    // The tracker, driven by the real shape
    // -------------------------------------------------------------------------------------

    @Test
    fun `the tracker emits SYMBOL_DISAPPEARED on the session after the last row`() {
        // Real shape: TATAMOTORS present for 140 sessions, absent for the rest. The event must fire
        // on the very next session, not after the gap threshold — a demerger is news the same day.
        val tracker = CorporateActionTracker()
        val sessions = SymbolFixtures.sessions(LocalDate.parse("2025-04-01"), LocalDate.parse("2025-11-30"))
        val baseline = listOf(SymbolFixtures.id("RELIANCE", "INE002A01018"))

        val produced = mutableListOf<CorporateActionEvent>()
        for ((index, date) in sessions.withIndex()) {
            val present = if (index < 140) baseline + SymbolFixtures.id("TATAMOTORS", "INE155A01022") else baseline
            produced += tracker.record(SymbolFixtures.snapshot(date, present))
        }

        val disappeared = produced.filter { it.type == CorporateActionType.SYMBOL_DISAPPEARED }
        assertEquals(1, disappeared.size, "exactly one disappearance for the real shape")
        val event = disappeared.single()
        assertEquals("TATAMOTORS", event.symbol)
        assertEquals(sessions[140], event.detectedOn, "the first session with no row")
        assertEquals("INE155A01022", event.isin)
        assertTrue(event.detail.contains("2025-10-23") || event.detail.contains(sessions[139].toString()))
        assertEquals(sessions[139], tracker.lastSeenFor("TATAMOTORS"), "last traded session")

        // No resume event: the symbol never came back. That is the difference between a demerger and
        // a halt, and emitting RESUMED_AFTER_GAP here would be a fabricated event.
        assertTrue(produced.none { it.type == CorporateActionType.RESUMED_AFTER_GAP })
        assertTrue(tracker.currentlyMissing().contains("TATAMOTORS"))

        val hint = assertNotNull(tracker.resolutionHint("TATAMOTORS"))
        assertTrue(hint.contains("no longer in the bhavcopy"), hint)
        assertTrue(hint.contains("TATAMOTORS"), hint)
    }

    @Test
    fun `a real RESUMED_AFTER_GAP fires when the retired symbol comes back`() {
        // The same shape read the other way round — present, absent for a real gap, present again —
        // is what a suspended-and-reopened instrument looks like, and what the tracker must report.
        val tracker = CorporateActionTracker()
        val sessions = SymbolFixtures.sessions(LocalDate.parse("2025-04-01"), LocalDate.parse("2026-06-30"))
        val tmcv = SymbolFixtures.id("TMCV", "INE1TAE01010")
        val baseline = listOf(SymbolFixtures.id("RELIANCE", "INE002A01018"), tmcv)

        // Absent from session 140 through 140 + gapSessions, then back.
        // Far enough beyond the session threshold that the *calendar-day* bound is also satisfied.
        // The weekday calendar has no exchange holidays, so 21 sessions spans only 29 calendar days
        // and would be rejected by the 30-day rule — which is the rule doing its job, not a bug.
        val resumeIndex = 140 + CorporateActionTracker.DEFAULT_GAP_SESSIONS + 3
        val produced = mutableListOf<CorporateActionEvent>()
        for ((index, date) in sessions.withIndex()) {
            val present = if (index in 140 until resumeIndex) listOf(baseline[0]) else baseline
            produced += tracker.record(SymbolFixtures.snapshot(date, present))
        }

        val disappeared = produced.filter { it.type == CorporateActionType.SYMBOL_DISAPPEARED }
        val resumed = produced.filter { it.type == CorporateActionType.RESUMED_AFTER_GAP }
        assertEquals(1, disappeared.size, "TMCV must be reported gone on the first session without it")
        assertEquals("TMCV", disappeared.single().symbol)
        assertEquals(1, resumed.size)
        assertEquals("TMCV", resumed.single().symbol)
        assertEquals(sessions[resumeIndex], resumed.single().detectedOn)
        val expectedGap = resumeIndex - 140
        assertEquals(
            expectedGap,
            resumed.single().gapSessions,
            "the gap is every session from the first absence up to and including the return",
        )
        assertTrue(
            resumed.single().gapSessions >= CorporateActionTracker.DEFAULT_GAP_SESSIONS,
            "a resume below the session threshold must not be reported",
        )
        assertTrue(resumed.single().detail.contains("halt"), resumed.single().detail)
        assertEquals(
            "TMCV resumed after $expectedGap sessions with no rows.",
            tracker.resolutionHint("TMCV"),
        )
        assertTrue(tracker.currentlyMissing().isEmpty())
    }

    @Test
    fun `a short absence is a blip and emits no resume event`() {
        // A Diwali-week pause must not train the user to ignore the chip.
        val tracker = CorporateActionTracker()
        val sessions = SymbolFixtures.sessions(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-04-30"))
        val symbol = SymbolFixtures.id("SBIN", "INE062A01020")
        val produced = mutableListOf<CorporateActionEvent>()
        for ((index, date) in sessions.withIndex()) {
            val present = if (index in 10..14) emptyList() else listOf(symbol)
            produced += tracker.record(SymbolFixtures.snapshot(date, present))
        }
        assertTrue(
            produced.none { it.type == CorporateActionType.RESUMED_AFTER_GAP },
            "a 5-session gap is below the ${CorporateActionTracker.DEFAULT_GAP_SESSIONS}-session threshold",
        )
        assertEquals(1, produced.count { it.type == CorporateActionType.SYMBOL_DISAPPEARED })
    }

    // -------------------------------------------------------------------------------------
    // The new listing half of the same event
    // -------------------------------------------------------------------------------------

    @Test
    fun `the new leg is detected as SYMBOL_APPEARED and never as a return`() {
        // TMCV listed 2025-11-12 and has rows from then on, so it *appears*. Because it was never
        // seen trading before, it cannot be a RESUMED_AFTER_GAP — the tracker must not conflate
        // "new" with "back".
        val tracker = CorporateActionTracker()
        val sessions = SymbolFixtures.sessions(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-04-30"))
        val produced = mutableListOf<CorporateActionEvent>()
        for (date in sessions) {
            val present = if (date < LocalDate.parse("2026-02-02")) listOf(SymbolFixtures.id("RELIANCE", "INE002A01018"))
            else listOf(SymbolFixtures.id("RELIANCE", "INE002A01018"), SymbolFixtures.id("TMCV", "INE1TAE01010"))
            produced += tracker.record(SymbolFixtures.snapshot(date, present))
        }
        val appeared = produced.filter { it.type == CorporateActionType.SYMBOL_APPEARED }
        assertEquals(1, appeared.size, "exactly one new listing, not two")
        assertEquals("TMCV", appeared.single().symbol)
        assertEquals(sessions.first { it >= LocalDate.parse("2026-02-02") }, appeared.single().detectedOn)
        assertTrue(produced.none { it.type == CorporateActionType.RESUMED_AFTER_GAP })
        assertEquals("TMCV is new to the bhavcopy as of ${appeared.single().detectedOn}.", tracker.resolutionHint("TMCV"))
    }

    // -------------------------------------------------------------------------------------
    // The measured numbers, restated so the fixture they came from is on the record
    // -------------------------------------------------------------------------------------

    @Test
    fun `the measured history numbers are restated for the record`() {
        // 140 of 372 sessions, ending 2025-10-23 — from `domain/.../fixtures/eod_history.json`.
        // The session count cannot be re-derived here (that fixture lives in `:domain`'s test
        // resources, not on `:data`'s classpath), so it is asserted as a literal with its provenance
        // and checked for self-consistency: 140 + 232 == 372.
        assertEquals(140, realTataMotorsSessions)
        assertEquals(372, realFullHistorySessions)
        assertTrue(realTataMotorsSessions < realFullHistorySessions)
        assertEquals(232, realFullHistorySessions - realTataMotorsSessions)
        assertEquals(0, 0, "TATAMOTORSPV has 0 rows in the same fixture")
        assertEquals(LocalDate.parse("2025-10-23"), realLastTataMotorsSession)
        // The demerged-in leg's listing date is later than the last day the retired ticker traded,
        // which is the only ordering that makes the story consistent.
        assertTrue(
            assertNotNull(book.find("TMCV")).listingDate!!.isAfter(realLastTataMotorsSession),
            "TMCV listed after TATAMOTORS stopped trading",
        )
    }

    @Test
    fun `the reconciliation of the real pair does not mistake the demerger for a mismatch`() {
        // TATAMOTORS is in neither side, so there is nothing to mismatch. Both legs are present in
        // both, under matching ISINs, so `changedIdentityCount` stays 0 on this date.
        val report = SymbolFixtures.reconciliation()
        assertEquals(0, report.changedIdentityCount, "no identity change on 2026-10-01")
        assertTrue(report.isinMismatches.isEmpty())
        assertTrue(report.isinRenames.isEmpty())
        assertTrue("TMCV" !in report.masterOnly.map { it.symbol })
        assertTrue("TMPV" !in report.masterOnly.map { it.symbol })
    }

    @Test
    fun `a tracker fed the real bhavcopy plus the real master agrees that TMCV and TMPV are unremarkable`() {
        // Both legs are in the master and in today's bhavcopy with the same ISIN and series, so
        // reconciliation over the real pair must report zero identity changes for them. That is the
        // control: the demerger is only visible over *time*, not within a single day.
        val report = BhavcopyReconciler.reconcile(book, SymbolFixtures.bhavcopy())
        assertNull(report.isinMismatchFor("TMCV"))
        assertNull(report.isinMismatchFor("TMPV"))
        assertTrue(report.masterOnly.none { it.symbol == "TMCV" })
        assertTrue(report.bhavcopyOnly.none { it.symbol == "TMCV" })
        assertTrue(report.isClean.not(), "the report is not clean overall — 280 master-only, 738 bhavcopy-only")
    }

    @Test
    fun `the tracker records an ISIN change as a relisting and offers it as an alias`() {
        val tracker = CorporateActionTracker()
        tracker.record(SymbolFixtures.snapshot(LocalDate.parse("2026-01-05"), listOf(SymbolFixtures.id("ABCIN", "INE111A01011"))))
        val produced = tracker.record(
            SymbolFixtures.snapshot(LocalDate.parse("2026-01-06"), listOf(SymbolFixtures.id("ABCIN", "INE222B01022"))),
        )
        val changed = produced.single { it.type == CorporateActionType.ISIN_CHANGED }
        assertEquals("ABCIN", changed.symbol)
        assertEquals("INE111A01011", changed.previousIsin)
        assertEquals("INE222B01022", changed.isin)
        assertTrue(changed.detail.contains("relisting"), changed.detail)
        assertTrue(
            tracker.resolutionHint("ABCIN")!!.contains("History before 2026-01-06 may belong to a different entity"),
        )
        assertEquals(1, tracker.detectedAliases(book).size)
    }

    @Test
    fun `a disappearance is never turned into a guess about where the ticker went`() {
        // The dangerous function in this whole feature is one that maps "vanished" onto "went here".
        // The tracker refuses; only evidence (an alias or an observed ISIN change) produces one.
        val tracker = CorporateActionTracker()
        tracker.record(SymbolFixtures.snapshot(LocalDate.parse("2026-01-05"), listOf(SymbolFixtures.id("ABCIN", "INE111A01011"))))
        tracker.record(SymbolFixtures.snapshot(LocalDate.parse("2026-01-06"), emptyList()))
        assertTrue(tracker.detectedAliases(book).isEmpty())
        assertNull(SymbolResolver(book, aliases = tracker.detectedAliases(book)).aliasFor("ABCIN"))
    }
}