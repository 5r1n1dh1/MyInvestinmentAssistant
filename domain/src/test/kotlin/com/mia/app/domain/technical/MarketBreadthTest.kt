package com.mia.app.domain.technical

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Market breadth arithmetic (planv2 W3's "market pulse").
 *
 * Everything here is **constructed**, never captured: the expected counts and percentages are
 * written out by hand so a reader can re-derive them on paper and disagree with the code if it
 * is wrong. The fixture symbols are named for what they do (`UP1`, `DOWN1`, …) so a failure says
 * which case broke.
 */
class MarketBreadthTest {

    private val session = LocalDate.parse("2026-10-01")

    /** A flat ramp of [size] closes ending at [end], so a window's max is `end` and min is `start`. */
    private fun ramp(start: Double, end: Double, size: Int): List<Double> {
        if (size <= 0) return emptyList()
        val step = (end - start) / (size - 1).toDouble()
        return List(size) { start + step * it }
    }

    /** [MarketBreadth.MIN_SESSIONS_52_WEEK] closes that never exceed [top]. */
    private fun yearEndingAt(top: Double, size: Int = MarketBreadth.MIN_SESSIONS_52_WEEK): List<Double> =
        ramp(start = 1.0, end = top, size = size)

    // ------------------------------------------------------------------- the simple case

    @Test
    fun `counts advancers decliners and unchanged from close against previous close`() {
        val day = listOf(
            InstrumentClose("AAA", close = 110.0, prevClose = 100.0), // up
            InstrumentClose("BBB", close = 90.0, prevClose = 100.0), // down
            InstrumentClose("CCC", close = 50.0, prevClose = 50.0), // unchanged
        )

        val breadth = MarketBreadth.compute(day, asOf = session)

        assertEquals(1, breadth.advancers)
        assertEquals(1, breadth.decliners)
        assertEquals(1, breadth.unchanged)
        assertEquals(3, breadth.classified)
        assertEquals(0, breadth.unclassified)
    }

    @Test
    fun `advance decline ratio is advancers divided by decliners`() {
        val day = listOf(
            InstrumentClose("AAA", close = 110.0, prevClose = 100.0),
            InstrumentClose("BBB", close = 110.0, prevClose = 100.0),
            InstrumentClose("BBB", close = 110.0, prevClose = 100.0),
            InstrumentClose("CCC", close = 90.0, prevClose = 100.0),
        )

        val breadth = MarketBreadth.compute(day, asOf = session)

        assertEquals(3.0, breadth.advanceDeclineRatio.valueOrNull()!!, 1e-9)
    }

    @Test
    fun `an all advancing session reports the ratio as unavailable rather than infinite`() {
        val day = listOf(
            InstrumentClose("AAA", close = 110.0, prevClose = 100.0),
            InstrumentClose("BBB", close = 120.0, prevClose = 100.0),
        )

        val breadth = MarketBreadth.compute(day, asOf = session)

        assertNull(breadth.advanceDeclineRatio.valueOrNull())
        val reason = assertNotNull(breadth.advanceDeclineRatio.reasonOrNull())
        // The reason has to name the counts, so the reader is told what was actually measured
        // rather than being shown a dash.
        assertTrue(reason.contains("2 advancer"), reason)
        assertTrue(reason.contains("no decliner"), reason)
    }

    @Test
    fun `a symbol with no previous close is counted as unclassified, not as unchanged`() {
        val day = listOf(
            InstrumentClose("AAA", close = 110.0, prevClose = 100.0),
            InstrumentClose("NEW", close = 42.0, prevClose = null),
            InstrumentClose("ZERO", close = 42.0, prevClose = 0.0),
        )

        val breadth = MarketBreadth.compute(day, asOf = session)

        assertEquals(1, breadth.advancers)
        assertEquals(0, breadth.decliners)
        // The failure this guards: counting these two as "unchanged" would report a flat market.
        assertEquals(0, breadth.unchanged)
        assertEquals(2, breadth.unclassified)
        assertEquals(1, breadth.classified)
        assertTrue(breadth.notes.any { it.contains("no usable previous close") }, breadth.notes.toString())
    }

    @Test
    fun `an empty session is fully unavailable rather than all zeroes`() {
        val breadth = MarketBreadth.compute(emptyList(), asOf = session)

        assertEquals(0, breadth.classified)
        assertFalse(breadth.isComplete)
        assertNotNull(breadth.advanceDeclineRatio.reasonOrNull())
        assertNotNull(breadth.newHighs.reasonOrNull())
        assertNotNull(breadth.percentAboveSma50.reasonOrNull())
    }

    @Test
    fun `symbols are matched case-insensitively and trimmed`() {
        val day = listOf(InstrumentClose("  reliance ", close = 110.0, prevClose = 100.0))
        val histories = mapOf("RELIANCE" to yearEndingAt(50.0))

        val breadth = MarketBreadth.compute(day, histories, asOf = session)

        assertEquals(1, breadth.advancers)
        // The join worked, which is the whole point: a lower-case ticker must find its history.
        assertEquals(1, breadth.extremesEligible)
        assertEquals(1, breadth.newHighs.valueOrNull())
    }

    // ------------------------------------------------------------------ 52-week extremes

    @Test
    fun `a close above the stored window high is a new 52-week high`() {
        val histories = mapOf("AAA" to yearEndingAt(100.0))
        val day = listOf(InstrumentClose("AAA", close = 101.0, prevClose = 100.0))

        val breadth = MarketBreadth.compute(day, histories, asOf = session)

        assertEquals(1, breadth.extremesEligible)
        assertEquals(1, breadth.newHighs.valueOrNull())
        assertEquals(0, breadth.newLows.valueOrNull())
    }

    @Test
    fun `a close below the stored window low is a new 52-week low`() {
        val histories = mapOf("AAA" to yearEndingAt(100.0))
        val day = listOf(InstrumentClose("AAA", close = 0.5, prevClose = 100.0))

        val breadth = MarketBreadth.compute(day, histories, asOf = session)

        assertEquals(0, breadth.newHighs.valueOrNull())
        assertEquals(1, breadth.newLows.valueOrNull())
    }

    @Test
    fun `a close exactly at the window high counts as at the high`() {
        // >= not >: a flat market must still report the extremes it is sitting on, otherwise a
        // quiet session would read as "nothing is at its high", which is a different claim.
        val histories = mapOf("AAA" to yearEndingAt(100.0))
        val day = listOf(InstrumentClose("AAA", close = 100.0, prevClose = 100.0))

        val breadth = MarketBreadth.compute(day, histories, asOf = session)

        assertEquals(1, breadth.newHighs.valueOrNull())
    }

    @Test
    fun `a symbol with too little history is excluded from the extremes, not counted as zero`() {
        val histories = mapOf(
            "LONG" to yearEndingAt(100.0),
            // One session short of the 52-week floor.
            "SHORT" to yearEndingAt(100.0, size = MarketBreadth.MIN_SESSIONS_52_WEEK - 1),
        )
        val day = listOf(
            InstrumentClose("LONG", close = 101.0, prevClose = 100.0),
            InstrumentClose("SHORT", close = 101.0, prevClose = 100.0),
        )

        val breadth = MarketBreadth.compute(day, histories, asOf = session)

        assertEquals(1, breadth.extremesEligible)
        assertEquals(1, breadth.extremesExcluded)
        // The honest answer: the eligible symbol is at a new high, and one symbol was not judged.
        assertEquals(1, breadth.newHighs.valueOrNull())
        assertTrue(
            breadth.notes.any { it.contains("fewer than 250 stored closes") },
            breadth.notes.toString(),
        )
    }

    @Test
    fun `extremes are unavailable when no symbol has a year of history`() {
        val day = listOf(InstrumentClose("AAA", close = 101.0, prevClose = 100.0))
        val histories = mapOf("AAA" to listOf(90.0, 95.0, 100.0))

        val breadth = MarketBreadth.compute(day, histories, asOf = session)

        assertNull(breadth.newHighs.valueOrNull())
        assertTrue(
            assertNotNull(breadth.newHighs.reasonOrNull()).contains("250 stored closes"),
        )
    }

    // ------------------------------------------------------------------- the SMA-50 share

    @Test
    fun `percent above the 50 day average uses only symbols with 50 stored closes`() {
        // Four symbols, 60 stored closes each.
        //
        //  AAA: last 50 closes average 100.0; today's close 110  -> above
        //  BBB: last 50 closes average 100.0; today's close 90   -> below
        //  CCC: last 50 closes average 100.0; today's close 100  -> not above (strict >)
        //  DDD: only 49 stored closes                            -> excluded
        val flat = { count: Int -> List(count) { 100.0 } }
        val histories = mapOf(
            "AAA" to flat(60),
            "BBB" to flat(60),
            "CCC" to flat(60),
            "DDD" to flat(49),
        )
        val day = listOf(
            InstrumentClose("AAA", close = 110.0, prevClose = 100.0),
            InstrumentClose("BBB", close = 90.0, prevClose = 100.0),
            InstrumentClose("CCC", close = 100.0, prevClose = 100.0),
            InstrumentClose("DDD", close = 120.0, prevClose = 100.0),
        )

        val breadth = MarketBreadth.compute(day, histories, asOf = session)

        assertEquals(3, breadth.smaEligible)
        assertEquals(1, breadth.smaExcluded)
        assertEquals(1, breadth.aboveSma50.valueOrNull())
        // 1 of 3, and the denominator is the *eligible* three rather than the four in the file.
        assertEquals(100.0 / 3.0, breadth.percentAboveSma50.valueOrNull()!!, 1e-9)
    }

    @Test
    fun `the 50 day average uses the newest 50 closes, not the highest 50`() {
        // The first 50 closes are all 1,000.0 and the newest 50 are all 10.0, so averaging the
        // newest 50 gives 10.0 and today's 12.0 is above it. An implementation that averaged the
        // highest 50 values would get 1,000.0 and report the same close as below its average.
        val window = List(50) { 1_000.0 } + List(50) { 10.0 }
        val histories = mapOf("AAA" to window)
        val day = listOf(InstrumentClose("AAA", close = 12.0, prevClose = 11.0))

        val breadth = MarketBreadth.compute(day, histories, asOf = session)

        assertEquals(1, breadth.aboveSma50.valueOrNull())
        assertEquals(100.0, breadth.percentAboveSma50.valueOrNull())
    }

    @Test
    fun `the SMA share is unavailable when no symbol reaches 50 stored closes`() {
        val day = listOf(InstrumentClose("AAA", close = 110.0, prevClose = 100.0))
        val histories = mapOf("AAA" to List(10) { 100.0 })

        val breadth = MarketBreadth.compute(day, histories, asOf = session)

        assertEquals(0, breadth.smaEligible)
        assertNull(breadth.percentAboveSma50.valueOrNull())
        assertNull(breadth.aboveSma50.valueOrNull())
        assertTrue(
            assertNotNull(breadth.percentAboveSma50.reasonOrNull()).contains("50 stored closes"),
        )
    }

    // ------------------------------------------------------------------------- the wording

    @Test
    fun `the summary reports counts and the as-of date and nothing else`() {
        val day = listOf(
            InstrumentClose("AAA", close = 110.0, prevClose = 100.0),
            InstrumentClose("BBB", close = 90.0, prevClose = 100.0),
            InstrumentClose("CCC", close = 50.0, prevClose = null),
        )

        val summary = MarketBreadth.compute(day, asOf = session).summary()

        assertTrue(summary.contains("Advancers 1"), summary)
        assertTrue(summary.contains("Decliners 1"), summary)
        assertTrue(summary.contains("Unchanged 0"), summary)
        assertTrue(summary.contains("2026-10-01"), summary)
        assertTrue(summary.contains("1 not classifiable"), summary)
    }

    @Test
    fun `a complete breadth reports every statistic as available`() {
        val histories = mapOf(
            "AAA" to yearEndingAt(100.0),
            "BBB" to yearEndingAt(100.0),
        )
        val day = listOf(
            InstrumentClose("AAA", close = 101.0, prevClose = 100.0),
            InstrumentClose("BBB", close = 99.0, prevClose = 100.0),
        )

        val breadth = MarketBreadth.compute(day, histories, asOf = session)

        assertTrue(breadth.isComplete)
    }

    @Test
    fun `non-finite and non-positive closes are never treated as real prices`() {
        val day = listOf(
            InstrumentClose("AAA", close = Double.NaN, prevClose = 100.0),
            InstrumentClose("BBB", close = 0.0, prevClose = 100.0),
            InstrumentClose("CCC", close = Double.POSITIVE_INFINITY, prevClose = 100.0),
            InstrumentClose("DDD", close = 100.0, prevClose = 100.0),
        )

        val breadth = MarketBreadth.compute(day, asOf = session)

        assertEquals(1, breadth.classified)
        assertEquals(3, breadth.unclassified)
        assertEquals(0, breadth.advancers)
        assertEquals(0, breadth.decliners)
    }

    @Test
    fun `Measured carries either a value or a stated reason and never both`() {
        val value: Measured<Int> = Measured.of(3, "unused")
        assertEquals(3, value.valueOrNull())
        assertNull(value.reasonOrNull())
        assertTrue(value.isAvailable())

        val missing: Measured<Int> = Measured.of(null, "nothing stored")
        assertNull(missing.valueOrNull())
        assertEquals("nothing stored", missing.reasonOrNull())
        assertFalse(missing.isAvailable())
    }
}