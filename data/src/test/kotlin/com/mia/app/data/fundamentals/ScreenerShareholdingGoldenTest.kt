package com.mia.app.data.fundamentals

import com.mia.app.domain.model.AnnualRow
import com.mia.app.domain.ratios.ShareholdingMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `shareholding` card's FII / DII rows, pinned to the exact figures on all five captured
 * pages.
 *
 * ### Why this file exists at all
 * The card has been parsed since this module was written and only `Promoters` was ever read off
 * it. `FIIs` and `DIIs` are published on **every** fixture and were dropped on the floor, so the
 * institutional-ownership card was built out of data the parser already had. These assertions are
 * what stops that from recurring — and, per docs/LIVE-PROBE.md §3, they are the shape any future
 * `NOT_SOURCED → AVAILABLE` promotion is required to take: **a parsed number, asserted against a
 * golden page**, never a label grep.
 *
 * Every expected value below was read out of the fixture bytes, not out of the implementation.
 */
class ScreenerShareholdingGoldenTest {

    /** The published `shareholding` headers: 12 quarters, `Sep 2023` … `Jun 2026`. */
    private val periods = listOf(
        "Sep 2023", "Dec 2023", "Mar 2024", "Jun 2024", "Sep 2024", "Dec 2024", "Mar 2025",
        "Jun 2025", "Sep 2025", "Dec 2025", "Mar 2026", "Jun 2026",
    )

    /** RELIANCE's published FII series, oldest first, as the page prints it. */
    private val relianceFii = listOf(
        22.60, 22.13, 22.06, 21.75, 21.30, 19.16, 19.07, 19.21, 18.65, 19.09, 18.67, 17.19,
    )

    private fun pattern(symbol: String) = ScreenerFixtures.parse(symbol).shareholdingPattern

    // ------------------------------------------------------------------------- FII/DII

    @Test
    fun `every fixture publishes a dated FIIs and DIIs row`() {
        for (symbol in ScreenerFixtures.symbols) {
            val pattern = pattern(symbol)
            assertEquals(periods.size, pattern.fii.points.size, "$symbol FIIs column count")
            assertEquals(periods.size, pattern.dii.points.size, "$symbol DIIs column count")
            assertEquals(periods, pattern.fii.points.map { it.period }, "$symbol FIIs headers")
        }
    }

    /**
     * The measured latest figures. Each was read out of the fixture's `shareholding` table:
     * RELIANCE 17.19 / 21.10, HDFCBANK 41.82 / 41.75, TITAN 15.39 / 15.15.
     */
    @Test
    fun `the latest FII and DII holdings are the Jun 2026 figures`() {
        val expected = mapOf(
            "RELIANCE" to (17.19 to 21.10),
            "TCS" to (9.07 to 13.41),
            "HDFCBANK" to (41.82 to 41.75),
            "INFY" to (27.09 to 42.78),
            "TITAN" to (15.39 to 15.15),
        )
        for ((symbol, pair) in expected) {
            val fii = assertNotNull(pattern(symbol).fii.latest, "$symbol FIIs")
            val dii = assertNotNull(pattern(symbol).dii.latest, "$symbol DIIs")
            assertEquals("Jun 2026", fii.period, symbol)
            assertEquals(pair.first, fii.percent, symbol)
            assertEquals(pair.second, dii.percent, symbol)
        }
    }

    /**
     * The quarter-on-quarter change, in **percentage points**.
     *
     * RELIANCE's foreign share fell from 18.67% to 17.19% — 1.48 points, which is 7.9% of the
     * earlier figure. Printing the second number with a `%` glyph would describe a relative change
     * as if it were a change in the register.
     */
    @Test
    fun `the FII quarter on quarter change is in percentage points`() {
        val expected = mapOf(
            "RELIANCE" to -1.48,
            "TCS" to -0.59,
            "HDFCBANK" to -2.23,
            "INFY" to -1.36,
            "TITAN" to -0.26,
        )
        for ((symbol, change) in expected) {
            val computed = ShareholdingMetrics.fiiHoldingQuarterlyChange(pattern(symbol))
            assertEquals(change, assertNotNull(computed.value, symbol), 1e-9, symbol)
            assertTrue(computed.note!!.contains("percentage points"), symbol)
            assertTrue(computed.note!!.contains("Mar 2026"), symbol)
            assertTrue(computed.note!!.contains("Jun 2026"), symbol)
        }
    }

    // ------------------------------------------- the fixture that breaks the usual row pattern

    /**
     * HDFCBANK publishes FIIs and DIIs and **no Promoters row** — the one fixture of five where
     * the usual three rows are not all there.
     *
     * This is the case that decided the ownership card could not live inside the promoter-holding
     * card: on this page a shared card would show two figures beside one unexplained blank, and the
     * blank would read as "the bank holds no promoter equity", which is not a thing.
     */
    @Test
    fun `HDFCBANK publishes no promoters row and that is reported rather than zero`() {
        val pattern = pattern("HDFCBANK")
        assertTrue(pattern.promoters.points.isEmpty(), "HDFCBANK must not gain a promoters row")
        assertTrue(
            pattern.promoters.publishedRowLabels.any { it.contains("FII", ignoreCase = true) },
            "the card does publish holder rows: ${pattern.promoters.rowsPresent}",
        )
        val note = assertNotNull(ShareholdingMetrics.promoterHolding(pattern).note)
        assertTrue(note.contains("publishes no 'Promoters' row"), note)
        assertTrue(note.contains("absent rather than 0"), note)
    }

    @Test
    fun `promoter holding is present on the four non-bank fixtures`() {
        for (symbol in listOf("RELIANCE", "TCS", "INFY", "TITAN")) {
            assertTrue(pattern(symbol).promoters.points.isNotEmpty(), "$symbol promoters")
        }
    }

    // ---------------------------------------------------------------------- provenance

    /**
     * Every figure carries the quarter that published it, and the series is in date order.
     *
     * The card carries no `data-date-key` attributes (verified across all five fixtures), so the
     * printed header is the only period information that exists. A figure without one would be a
     * number of unknown date — and the whole card is a trend card.
     */
    @Test
    fun `every point is dated and the series is ordered by period`() {
        for (symbol in ScreenerFixtures.symbols) {
            val points = pattern(symbol).fii.points
            assertEquals(periods, points.map { it.period }, "$symbol header sequence")
            assertEquals(points.map { it.sortKey }.sorted(), points.map { it.sortKey }, symbol)
            for (point in points) assertTrue(point.percent in 0.0..100.0, "$symbol ${point.period}")
        }
    }

    @Test
    fun `columns out of order are ordered by period not by position`() {
        val reversed = ShareholdingMetrics.of(
            periods.reversed(),
            listOf(
                AnnualRow("FIIs +", relianceFii.reversed()),
                AnnualRow("DIIs +", List(12) { 10.0 }),
            ),
        )
        val live = pattern("RELIANCE")
        assertEquals(live.fii.latest?.percent, reversed.fii.latest?.percent)
        assertEquals("Jun 2026", reversed.fii.latest?.period)
        assertEquals(live.fii.points.size, reversed.fii.points.size)
    }

    @Test
    fun `the source's row labels are matched through the plus-button markup`() {
        // The page prints "FIIs +" — the `+` is an expand control inside the label cell, and
        // `labelKey` drops it. Matching on the raw string would find nothing on any fixture.
        val built = ShareholdingMetrics.of(
            listOf("Jun 2026", "Mar 2026"),
            listOf(AnnualRow("FIIs +", listOf(17.19, 18.67))),
        )
        assertEquals(17.19, ShareholdingMetrics.fiiHolding(built).value)
        assertEquals(-1.48, assertNotNull(ShareholdingMetrics.fiiHoldingQuarterlyChange(built).value), 1e-9)
    }

    // ------------------------------------------------------------------------ honesty

    /**
     * Without column headers nothing is dated, and nothing is guessed.
     */
    @Test
    fun `no headers at all means no figure and an explicit reason`() {
        val rows = ScreenerFixtures.parse("RELIANCE").snapshot.shareholding
        val undated = ShareholdingMetrics.of(emptyList(), rows)
        assertTrue(undated.isEmpty)
        assertFalse(undated.isUndated, "no card is not the same as a card whose headers do not parse")

        val computed = ShareholdingMetrics.fiiHolding(undated)
        assertNull(computed.value)
        assertTrue(computed.note!!.contains("absent rather than 0"), computed.note!!)
        assertTrue(ShareholdingMetrics.fiiHoldingQuarterlyChange(undated).value == null)
    }

    /**
     * A row that is present but whose headers are all unreadable is a **third** state, distinct from
     * both a published row and an absent one — and it is stated as such.
     */
    @Test
    fun `an undated card is its own state and says so`() {
        val undated = ShareholdingMetrics.of(
            listOf("", ""),
            listOf(
                AnnualRow("FIIs +", listOf(17.19, 18.67)),
                AnnualRow("DIIs +", listOf(21.10, 20.46)),
            ),
        )
        assertTrue(undated.fii.isUndated, "present but undated")
        val note = assertNotNull(ShareholdingMetrics.fiiHolding(undated).note)
        assertTrue(note.contains("none of its columns resolved to a quarter"), note)
        assertTrue(note.contains("right-hand column"), note)
    }

    @Test
    fun `a single readable quarter yields a value but no change`() {
        val single = ShareholdingMetrics.of(
            listOf("Jun 2026"),
            listOf(AnnualRow("FIIs +", listOf(17.19)), AnnualRow("DIIs +", listOf(21.10))),
        )
        assertEquals(17.19, ShareholdingMetrics.fiiHolding(single).value)
        val change = ShareholdingMetrics.fiiHoldingQuarterlyChange(single)
        assertNull(change.value, "one quarter is a value, not a change")
        assertTrue(change.note!!.contains("exactly one readable 'FIIs' quarter"), change.note!!)
    }

    /** The parse notes name the card, its columns and any holder row the page omits. */
    @Test
    fun `the parse notes state what the shareholding card published`() {
        val reliance = ScreenerFixtures.parse("RELIANCE").health.notes
        assertTrue(
            reliance.any { it.contains("'shareholding' card publishes") && it.contains("quarterly") },
            "no note naming the quarterly columns",
        )
        assertTrue(
            reliance.none { it.contains("publishes no 'FIIs' row") },
            "RELIANCE publishes FIIs; a note claiming otherwise would be a parser bug",
        )
        val bank = ScreenerFixtures.parse("HDFCBANK").health.notes
        assertTrue(
            bank.any { it.contains("publishes no 'Promoters' row") },
            "the bank's missing promoters row must be reported",
        )
    }

    /** Nothing in this file may ever render an absent holder as 0. */
    @Test
    fun `an absent holder is never zero`() {
        for (symbol in ScreenerFixtures.symbols) {
            val pattern = pattern(symbol)
            for ((name, series) in listOf(
                "FII" to pattern.fii,
                "DII" to pattern.dii,
                "promoter" to pattern.promoters,
            )) {
                if (series.points.isNotEmpty()) continue
                val note = assertNotNull(
                    ShareholdingMetrics.promoterHolding(
                        pattern.copy(promoters = series),
                    ).note,
                    "$symbol $name",
                )
                assertTrue(note.contains("absent rather than 0"), "$symbol $name: $note")
            }
        }
    }
}