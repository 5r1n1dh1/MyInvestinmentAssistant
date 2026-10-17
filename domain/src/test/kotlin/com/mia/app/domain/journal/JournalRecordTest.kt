package com.mia.app.domain.journal

import com.mia.app.domain.compliance.AdviceLanguage
import com.mia.app.domain.compliance.HonestyContract
import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.MatchScorePolicy
import com.mia.app.domain.model.Timeframe
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The journal's two load-bearing rules, pinned.
 *
 * 1. **No rate below the gate.** [PersonalRecord.label] must contain no percentage at all under
 *    [MatchScorePolicy.MIN_HIT_RATE_SAMPLES] settled entries, and must carry `n=` above it. This is
 *    the same 30-sample gate the app's own hit rate uses, read from the same constant — a test that
 *    asserted a literal `30` would pass even after someone lowered the policy, which is precisely
 *    the drift this is here to catch.
 * 2. **A resolution is written once, and never from data that did not exist yet.** The resolver's
 *    gates are asserted one at a time, because a gate that is only tested through the happy path is
 *    a gate that will be dropped by the next person who makes the happy path faster.
 */
class JournalRecordTest {

    // ------------------------------------------------------------------------------- the gate

    @Test
    fun `the gate is the app's own gate`() {
        assertEquals(MatchScorePolicy.MIN_HIT_RATE_SAMPLES, JournalLedger.MINIMUM_SAMPLES)
    }

    @Test
    fun `below the gate there is no percentage in the label`() {
        val record = recordOf(settled = 29, agreed = 29)
        val label = record.label()
        HonestyContract.requireHitRateSampleSize(label)
        assertFalse(label.contains("%"), "a withheld tally must print no percentage at all: $label")
        assertTrue(label.contains("29"))
        assertTrue(label.contains("${MatchScorePolicy.MIN_HIT_RATE_SAMPLES}"))
    }

    @Test
    fun `at the gate a percentage appears with its n`() {
        val record = recordOf(settled = 30, agreed = 17)
        assertTrue(record.isReportable)
        val label = record.label()
        HonestyContract.requireHitRateSampleSize(label)
        HonestyContract.requireNotProbabilityClaim(label)
        AdviceLanguage.requireClean(label)
        assertTrue(label.contains("n=30"), "a reported tally must carry its sample size: $label")
        assertTrue(label.contains("56.7%"), "expected 17/30 in the label, got: $label")
    }

    @Test
    fun `an empty device prints no percentage`() {
        val label = PersonalRecord.EMPTY.label()
        assertFalse(label.contains("%"))
        assertEquals(30, PersonalRecord.EMPTY.samplesUntilReportable)
    }

    @Test
    fun `a withheld tally never claims a rate`() {
        val record = recordOf(settled = 3, agreed = 3)
        assertNull(record.ratePercent)
        assertFalse(record.isReportable)
        assertTrue(record.withheldNote().contains("No rate is shown"))
    }

    // ------------------------------------------------------------------- what counts as agreed

    @Test
    fun `an up expectation followed by a rise is agreed`() {
        assertTrue(JournalLedger.agreedWith(resolved(direction = ExpectationDirection.UP, outcome = JournalOutcome.ROSE)))
    }

    @Test
    fun `an up expectation followed by a fall is not agreed`() {
        assertFalse(
            JournalLedger.agreedWith(resolved(direction = ExpectationDirection.UP, outcome = JournalOutcome.FELL)),
        )
    }

    @Test
    fun `a down expectation followed by a fall is agreed`() {
        assertTrue(JournalLedger.agreedWith(resolved(direction = ExpectationDirection.DOWN, outcome = JournalOutcome.FELL)))
    }

    @Test
    fun `an unchanged close agrees with neither direction`() {
        assertFalse(JournalLedger.agreedWith(resolved(direction = ExpectationDirection.UP, outcome = JournalOutcome.FLAT)))
        assertFalse(JournalLedger.agreedWith(resolved(direction = ExpectationDirection.DOWN, outcome = JournalOutcome.FLAT)))
    }

    @Test
    fun `a non-directional expectation is excluded from the denominator, not counted as a miss`() {
        val entries = listOf(
            resolved(direction = ExpectationDirection.UP, outcome = JournalOutcome.ROSE),
            resolved(direction = ExpectationDirection.EITHER_WAY, outcome = JournalOutcome.FELL),
        )
        val record = JournalLedger.record(entries, TODAY)
        assertEquals(1, record.settled, "the non-directional entry must not be in the denominator")
        assertEquals(1, record.unscored, "and it must be reported as unscored, not silently dropped")
        assertEquals(1, record.agreed)
    }

    // ------------------------------------------------------------------------- the resolver

    @Test
    fun `nothing resolves before the review date`() {
        val entry = entry(reviewDate = TODAY.plusDays(7))
        val resolvedEntry = JournalResolver.resolve(entry, bars(100.0, 120.0), hasSettledOutcome = true, today = TODAY)
        assertFalse(resolvedEntry.isResolved)
        assertNotNull(JournalResolver.blockingReason(entry, bars(100.0, 120.0), true, TODAY))
    }

    @Test
    fun `nothing resolves while the app has settled no outcome for the symbol`() {
        val entry = entry(reviewDate = TODAY.minusDays(1))
        val candles = bars(100.0, 120.0)
        assertFalse(JournalResolver.resolve(entry, candles, hasSettledOutcome = false, today = TODAY).isResolved)
        val reason = JournalResolver.blockingReason(entry, candles, false, TODAY)
        assertNotNull(reason)
        assertTrue(reason.contains(HDFCBANK), "the reason must name the symbol it is waiting on: $reason")
    }

    @Test
    fun `nothing resolves without a stored bar at the review date`() {
        val entry = entry(reviewDate = TODAY.minusDays(10))
        val onlyToday = bars(100.0, 120.0, dates = listOf(TODAY.minusDays(30), TODAY))
        assertFalse(JournalResolver.resolve(entry, onlyToday, hasSettledOutcome = true, today = TODAY).isResolved)
    }

    @Test
    fun `a resolution is written from the stored closes`() {
        val entry = entry(reviewDate = TODAY.minusDays(5))
        val candles = bars(100.0, 110.0, dates = listOf(entry.createdAt, TODAY.minusDays(5)))
        val done = JournalResolver.resolve(entry, candles, hasSettledOutcome = true, today = TODAY)
        assertTrue(done.isResolved)
        assertEquals(JournalOutcome.ROSE, done.resolutionOutcome)
        assertEquals(10.0, done.resolutionChangePercent ?: 0.0, absoluteTolerance = 1e-9)
        assertEquals(TODAY.minusDays(5), done.resolvedAt)
    }

    @Test
    fun `a resolution is written once and never revised`() {
        val entry = entry(reviewDate = TODAY.minusDays(5))
        val first = JournalResolver.resolve(
            entry,
            bars(100.0, 110.0, dates = listOf(entry.createdAt, TODAY.minusDays(5))),
            hasSettledOutcome = true,
            today = TODAY,
        )
        // A later pass with more bars, showing the price has since fallen, must not move the verdict.
        val second = JournalResolver.resolve(
            entry,
            bars(100.0, 110.0, 80.0, dates = listOf(entry.createdAt, TODAY.minusDays(5), TODAY)),
            hasSettledOutcome = true,
            today = TODAY,
        )
        assertEquals(JournalOutcome.ROSE, first.resolutionOutcome)
        // Resolving the *resolved* entry is a no-op, which is where the guarantee lives.
        val again = JournalResolver.resolve(
            first,
            bars(100.0, 110.0, 80.0, dates = listOf(entry.createdAt, TODAY.minusDays(5), TODAY)),
            hasSettledOutcome = true,
            today = TODAY,
        )
        assertEquals(first, again)
        assertEquals(JournalOutcome.ROSE, second.resolutionOutcome)
    }

    @Test
    fun `a bar dated after today is never used`() {
        val entry = entry(reviewDate = TODAY)
        // The device "knows" a session dated tomorrow, which the bhavcopy cannot have published.
        val candles = bars(100.0, 110.0, dates = listOf(entry.createdAt, TODAY.plusDays(1)))
        val done = JournalResolver.resolve(entry, candles, hasSettledOutcome = true, today = TODAY)
        assertFalse(done.isResolved, "an outcome may not be computed from a bar that did not exist yet")
    }

    @Test
    fun `the flat band is a named constant and the label says so`() {
        assertEquals(JournalOutcome.FLAT, JournalResolver.outcomeOf(JournalResolver.FLAT_BAND_PERCENT))
        assertEquals(JournalOutcome.ROSE, JournalResolver.outcomeOf(JournalResolver.FLAT_BAND_PERCENT + 0.01))
        assertEquals(JournalOutcome.FELL, JournalResolver.outcomeOf(-JournalResolver.FLAT_BAND_PERCENT))
        val entry = resolved(direction = ExpectationDirection.UP, outcome = JournalOutcome.FLAT, change = 0.2)
        assertTrue(JournalResolver.resolutionNote(entry).contains("${JournalResolver.FLAT_BAND_PERCENT}%"))
        AdviceLanguage.requireClean(entry.resolutionText() ?: "")
    }

    // ---------------------------------------------------------------------------- the drafts

    @Test
    fun `a draft without a falsification cannot be saved`() {
        val draft = JournalDraft(
            symbol = "reliance ",
            timeframe = Timeframe.D1,
            thesis = "Refining capacity is under-earning today.",
            expectation = "Margin on refining recovers over four quarters.",
            direction = ExpectationDirection.UP,
            falsification = "  ",
            reviewDate = TODAY.plusDays(30),
        )
        assertFalse(draft.isComplete)
        val reason = draft.blockingReason()
        assertNotNull(reason)
        assertEquals(JournalPolicy.FALSIFICATION_REQUIRED, reason)
        assertFailsWith<IllegalArgumentException> { draft.toEntry(TODAY) }
    }

    @Test
    fun `a complete draft becomes an entry with a normalised symbol`() {
        val draft = JournalDraft(
            symbol = " reliance ",
            timeframe = Timeframe.D1,
            thesis = "Refining capacity is under-earning today.",
            expectation = "Margin on refining recovers over four quarters.",
            direction = ExpectationDirection.UP,
            falsification = "Quarterly OPM stays below 8% for two quarters.",
            reviewDate = TODAY.plusDays(30),
        )
        val entry = draft.toEntry(TODAY)
        assertEquals("RELIANCE", entry.symbol)
        assertFalse(entry.isResolved)
    }

    @Test
    fun `an entry without a falsification cannot be constructed`() {
        assertFailsWith<IllegalArgumentException> {
            entry(falsification = "")
        }
    }

    @Test
    fun `a resolved entry must carry its measured move`() {
        assertFailsWith<IllegalArgumentException> {
            JournalEntry(
                symbol = HDFCBANK,
                timeframe = Timeframe.D1,
                thesis = "A thesis long enough to store.",
                expectation = "An expectation long enough to store.",
                expectationDirection = ExpectationDirection.UP,
                falsification = "A condition long enough to store.",
                reviewDate = TODAY,
                createdAt = TODAY.minusDays(30),
                resolvedAt = TODAY,
                resolutionOutcome = JournalOutcome.ROSE,
            )
        }
    }

    // ------------------------------------------------------------------------------- fixtures

    private fun entry(
        reviewDate: LocalDate = TODAY.minusDays(1),
        falsification: String = "A condition long enough to store.",
    ): JournalEntry = JournalEntry(
        id = 1L,
        symbol = HDFCBANK,
        timeframe = Timeframe.D1,
        thesis = "A thesis long enough to store.",
        expectation = "An expectation long enough to store.",
        expectationDirection = ExpectationDirection.UP,
        falsification = falsification,
        reviewDate = reviewDate,
        createdAt = TODAY.minusDays(30),
    )

    private fun resolved(
        direction: ExpectationDirection,
        outcome: JournalOutcome,
        change: Double = 4.0,
    ): JournalEntry = entry().copy(
        expectationDirection = direction,
        resolvedAt = TODAY.minusDays(1),
        resolutionOutcome = outcome,
        resolutionChangePercent = change,
    )

    private fun recordOf(settled: Int, agreed: Int): PersonalRecord = PersonalRecord(
        totalEntries = settled,
        settled = settled,
        agreed = agreed,
        unscored = 0,
        awaitingReview = 0,
        open = 0,
    )

    /** `closes` zipped onto `dates`, padded from [BASELINE_DATE] when the caller gave fewer dates. */
    private fun bars(vararg closes: Double, dates: List<LocalDate> = emptyList()): List<Candle> =
        closes.mapIndexed { index, close ->
            Candle(
                symbol = HDFCBANK,
                timeframe = Timeframe.D1,
                date = dates.getOrElse(index) { BASELINE_DATE.plusDays(index.toLong()) },
                open = close,
                high = close,
                low = close,
                close = close,
                volume = 1L,
            )
        }

    private companion object {
        const val HDFCBANK = "HDFCBANK"
        val TODAY: LocalDate = LocalDate.of(2026, 10, 17)
        val BASELINE_DATE: LocalDate = LocalDate.of(2026, 9, 17)
    }
}