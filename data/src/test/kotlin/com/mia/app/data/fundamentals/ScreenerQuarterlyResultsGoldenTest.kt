package com.mia.app.data.fundamentals

import com.mia.app.domain.ratios.QuarterlyComparison
import com.mia.app.domain.ratios.QuarterlyMetric
import com.mia.app.domain.ratios.QuarterlyResults
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Quarter-on-quarter and year-on-year for the published `quarters` card, pinned to the figures on
 * all five captured pages.
 *
 * ### Why these numbers are asserted rather than computed in the test
 * The QoQ/YoY card is the first place the app does arithmetic on a **published quarterly series**,
 * and the two errors that matter are both invisible without a fixture: reading the wrong column as
 * "the previous quarter" (the page prints oldest-left, so the previous quarter is the *second from
 * the right*), and reading the wrong column as "the same quarter last year" (five from the right,
 * not four, because the series ends in `Jun` and begins in `Jun` — `Mar 2026` is not `Jun 2025`
 * minus one in the naive sense, it is the same calendar quarter). Every value below was computed
 * from the fixture's own cells.
 */
class ScreenerQuarterlyResultsGoldenTest {

    /** plan.md §3.3: a live `quarters` table publishes 13 quarters. */
    @Test
    fun `every fixture publishes thirteen quarters ending Jun 2026`() {
        for (symbol in ScreenerFixtures.symbols) {
            val records = ScreenerFixtures.parse(symbol).snapshot.quarters
            assertEquals(13, records.size, symbol)
            val latest = assertNotNull(records.firstOrNull(), symbol)
            assertEquals(2026, latest.year, symbol)
            assertEquals(2, latest.quarter, symbol)
        }
    }

    // ------------------------------------------------------------------------ sales

    @Test
    fun `sales growth is the Jun 2026 quarter against Mar 2026 and Jun 2025`() {
        val expected = mapOf(
            // RELIANCE: 309,468 ÷ 294,059 − 1 = +5.24%; ÷ 243,632 − 1 = +27.02%
            "RELIANCE" to Triple(309468.0, 5.2401, 27.0227),
            // TCS: 72,275 ÷ 70,698 = +2.23%; ÷ 63,437 = +13.93%
            "TCS" to Triple(72275.0, 2.2306, 13.9319),
            // HDFCBANK publishes "Revenue", not "Sales": 90,575 ÷ 87,182 = +3.89%
            "HDFCBANK" to Triple(90575.0, 3.8919, 3.6659),
            "INFY" to Triple(48211.0, 3.8985, 14.0306),
            // TITAN: 21,356 ÷ 26,920 = −20.67%; ÷ 16,523 = +29.25%
            "TITAN" to Triple(21356.0, -20.6686, 29.2501),
        )
        for ((symbol, triple) in expected) {
            val comparison = compare(symbol, QuarterlyMetric.SALES)
            assertEquals(triple.first, comparison.latest!!.value, "$symbol latest sales")
            assertEquals("Jun 2026", comparison.latest!!.period, symbol)
            assertEquals("Mar 2026", comparison.previousQuarter!!.period, symbol)
            assertEquals("Jun 2025", comparison.sameQuarterPriorYear!!.period, symbol)
            assertEquals(triple.second, comparison.quarterOverQuarterPercent!!, 1e-3, "$symbol QoQ")
            assertEquals(triple.third, comparison.yearOnYearPercent!!, 1e-3, "$symbol YoY")
            assertEquals(2, comparison.comparisonCount, symbol)
        }
    }

    // ------------------------------------------------------------ operating profit

    @Test
    fun `operating profit growth reproduces on the four non-bank fixtures`() {
        val expected = mapOf(
            "RELIANCE" to Triple(47517.0, 7.6482, 10.7493),
            "TCS" to Triple(18556.0, -3.7352, 9.9615),
            "INFY" to Triple(11409.0, 2.1671, 14.7440),
            "TITAN" to Triple(2890.0, 49.1228, 57.9235),
        )
        for ((symbol, triple) in expected) {
            val comparison = compare(symbol, QuarterlyMetric.OPERATING_PROFIT)
            assertEquals(triple.first, comparison.latest!!.value, symbol)
            assertEquals(triple.second, comparison.quarterOverQuarterPercent!!, 1e-3, "$symbol QoQ")
            assertEquals(triple.third, comparison.yearOnYearPercent!!, 1e-3, "$symbol YoY")
        }
    }

    /**
     * The bank case, and the reason this card cannot borrow a neighbouring row.
     *
     * HDFCBANK's `quarters` table publishes `Financing Profit` and `Financing Margin %` and **no**
     * `Operating Profit`. Showing `Financing Profit` under a card titled "Operating profit" would
     * be a confident wrong number on the one sector where plan.md §7.3 exists — so the card is
     * absent and says why.
     */
    @Test
    fun `a bank publishes no operating profit and the card says which row it does publish`() {
        val comparison = compare("HDFCBANK", QuarterlyMetric.OPERATING_PROFIT)
        assertNull(comparison.latest, "there is no operating profit to show")
        assertTrue(comparison.isEmpty)
        val note = comparison.notes.single()
        assertTrue(note.contains("no 'Operating Profit' row"), note)
        assertTrue(note.contains("Financing Profit"), note)
        assertTrue(note.contains("plan.md §7.3"), note)
    }

    // ------------------------------------------------------------------------- EPS

    @Test
    fun `EPS growth is read in rupees per share and not as a multiple`() {
        val expected = mapOf(
            "RELIANCE" to Triple(15.48, 23.4450, -22.4060),
            "TCS" to Triple(36.90, -2.6899, 4.6215),
            "HDFCBANK" to Triple(12.50, -5.4463, 17.9245),
            "INFY" to Triple(19.15, -8.6355, 14.9460),
            "TITAN" to Triple(20.02, 50.7530, 62.8967),
        )
        for ((symbol, triple) in expected) {
            val comparison = compare(symbol, QuarterlyMetric.EPS)
            assertEquals(triple.first, comparison.latest!!.value, "$symbol EPS")
            assertEquals(triple.second, comparison.quarterOverQuarterPercent!!, 1e-3, "$symbol QoQ")
            assertEquals(triple.third, comparison.yearOnYearPercent!!, 1e-3, "$symbol YoY")
        }
    }

    // ------------------------------------------------------------------- sparkline

    /**
     * The sparkline's input: every readable quarter, **oldest first**, in calendar order.
     *
     * Oldest-first because a bar chart drawn in the page's own order would read right-to-left; and
     * calendar order because the page prints `Jun 2023 … Jun 2026` and a financial-quarter sort
     * would put `Mar 2026` before `Jun 2026` incorrectly.
     */
    @Test
    fun `the sparkline series is oldest first and calendar ordered`() {
        for (symbol in ScreenerFixtures.symbols) {
            val series = QuarterlyResults.series(
                ScreenerFixtures.parse(symbol).snapshot.quarters,
                QuarterlyMetric.SALES,
            )
            assertEquals(13, series.size, symbol)
            assertEquals("Jun 2023", series.first().period, symbol)
            assertEquals("Jun 2026", series.last().period, symbol)
            assertEquals(series.map { it.sortKey }.sorted(), series.map { it.sortKey }, symbol)
        }
    }

    // --------------------------------------------------------------------- honesty

    /**
     * A bank with no operating profit row still gets a **series-shaped absence**, not a zeroed one.
     *
     * The measure that gets the "not published" sentence is the whole comparison; the other two
     * answer normally, so a reader is not told the bank's quarterly numbers are missing.
     */
    @Test
    fun `one absent metric does not suppress the other two cards`() {
        val all = QuarterlyResults.all(ScreenerFixtures.parse("HDFCBANK").snapshot.quarters)
        assertEquals(3, all.size)
        assertTrue(all.first { it.metric == QuarterlyMetric.SALES }.latest != null)
        assertTrue(all.first { it.metric == QuarterlyMetric.EPS }.latest != null)
        assertTrue(all.first { it.metric == QuarterlyMetric.OPERATING_PROFIT }.isEmpty)
    }

    /**
     * A gap in the published series is **named**, never filled.
     *
     * Forward-filling would turn thirteen points into a smooth line that hides the quarter the
     * reader opened the screen to look at, and a share count changes between quarters — bonus
     * issues, splits, ESOPs — so an absent quarter is a real gap rather than a hole to smooth over.
     */
    @Test
    fun `a gap is named and the comparison across it is refused`() {
        val withGap = listOf(
            // Sep 2025, then Dec 2025 and Mar 2026 absent, then Jun 2026.
            com.mia.app.domain.model.QuarterRecord(2025, 3, sales = 100.0),
            com.mia.app.domain.model.QuarterRecord(2026, 2, sales = 140.0),
        )
        val comparison = QuarterlyResults.compare(withGap, QuarterlyMetric.SALES)
        assertEquals(2, comparison.series.size)
        val gapNote = comparison.notes.firstOrNull { it.contains("skipped") }
        assertNotNull(gapNote, "the missing quarters must be named: ${comparison.notes}")
        assertTrue(gapNote.contains("Dec 2025"), gapNote)
        assertTrue(gapNote.contains("Mar 2026"), gapNote)
        // And the comparison is refused rather than made against Sep 2025, which is two quarters back.
        assertNull(comparison.previousQuarter)
        assertNull(comparison.quarterOverQuarterPercent)
        assertTrue(comparison.notes.any { it.contains("no quarter on quarter comparison") })
        // Year on year is refused on the same rule: there is no Jun 2025 here either.
        assertNull(comparison.sameQuarterPriorYear)
        assertEquals(0, comparison.comparisonCount)
    }

    /**
     * A gap that is **not** adjacent does not break the adjacent comparison.
     *
     * `Sep 2025 → Mar 2026 → Jun 2026` with `Dec 2025` missing: Mar 2026 is the immediately
     * preceding quarter of Jun 2026, so the QoQ figure is legitimate and only the gap is reported.
     */
    @Test
    fun `a gap away from the latest quarter does not suppress the adjacent comparison`() {
        val records = listOf(
            com.mia.app.domain.model.QuarterRecord(2025, 3, sales = 100.0),
            com.mia.app.domain.model.QuarterRecord(2026, 1, sales = 130.0),
            com.mia.app.domain.model.QuarterRecord(2026, 2, sales = 140.0),
        )
        val comparison = QuarterlyResults.compare(records, QuarterlyMetric.SALES)
        assertEquals("Mar 2026", comparison.previousQuarter!!.period)
        assertEquals(7.6923, comparison.quarterOverQuarterPercent!!, 1e-3)
        assertTrue(comparison.notes.any { it.contains("Dec 2025") }, "${comparison.notes}")
    }

    /** A prior quarter of exactly zero has no percentage. Reported, not divided. */
    @Test
    fun `growth from zero is absent rather than infinite`() {
        val records = listOf(
            com.mia.app.domain.model.QuarterRecord(2026, 1, sales = 0.0),
            com.mia.app.domain.model.QuarterRecord(2026, 2, sales = 140.0),
        )
        val comparison = QuarterlyResults.compare(records, QuarterlyMetric.SALES)
        assertEquals(140.0, comparison.latest!!.value)
        assertNull(comparison.quarterOverQuarterPercent)
        assertTrue(comparison.notes.any { it.contains("exactly 0") }, "${comparison.notes}")
        assertEquals(0, comparison.comparisonCount)
    }

    /**
     * Two negative quarters produce no percentage.
     *
     * HDFCBANK's `Financing Profit` is negative on 11 of 13 quarters. `−15,342` against `−2,066` is
     * a "+642% growth" that describes nothing about the business, so the arithmetic refuses it and
     * writes down why — the reason being specific to negatives rather than a generic failure.
     */
    @Test
    fun `two negative quarters produce no percentage and say why`() {
        val records = listOf(
            com.mia.app.domain.model.QuarterRecord(2026, 1, operatingProfit = -2066.0),
            com.mia.app.domain.model.QuarterRecord(2026, 2, operatingProfit = -15342.0),
        )
        val comparison = QuarterlyResults.compare(records, QuarterlyMetric.OPERATING_PROFIT)
        assertEquals(-15342.0, comparison.latest!!.value)
        assertNull(comparison.quarterOverQuarterPercent)
        assertTrue(comparison.notes.any { it.contains("negative") }, "${comparison.notes}")
    }

    /** An empty quarter list is an absence with a reason on all three cards, never a zero. */
    @Test
    fun `an empty quarter list produces three reasoned absences`() {
        val all = QuarterlyResults.all(emptyList())
        assertEquals(3, all.size)
        for (comparison in all) {
            assertTrue(comparison.isEmpty, comparison.metric.name)
            assertTrue(comparison.notes.isNotEmpty(), comparison.metric.name)
            assertTrue(comparison.notes.single().contains("absent rather than 0"), "${comparison.notes}")
        }
    }

    private fun compare(symbol: String, metric: QuarterlyMetric): QuarterlyComparison =
        QuarterlyResults.compare(ScreenerFixtures.parse(symbol).snapshot.quarters, metric)
}