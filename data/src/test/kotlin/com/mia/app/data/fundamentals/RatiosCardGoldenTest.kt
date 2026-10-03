package com.mia.app.data.fundamentals

import com.mia.app.domain.ratios.RatioExtras
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Golden assertions over the `ratios` card in the five real pages captured 2026-10-02.
 *
 * ### Every number below was read out of the fixture bytes
 * The `<section id="ratios">` element holds a 12-column annual table (Mar 2015 … Mar 2026) whose
 * rows are working-capital days and one returns percentage. Nothing here was taken from plan.md,
 * and nothing is recomputed: `assertEquals(20.0, …)` is an assertion that the parser reproduces
 * the published cell, not a rounded comparison.
 *
 * ### The shape assertions matter as much as the values
 * HDFCBANK's card publishes **one** row and TCS/INFY publish `Inventory Days` with **every cell
 * blank**. Those are the two states that a careless parser turns into `0.0` — "no inventory" and
 * "a bank with no inventory" would then print a flag. Each is asserted here as an *absence*:
 * an absent key, an empty series, and a row that exists with no value in it.
 */
class RatiosCardGoldenTest {

    /** The 12 annual column labels the card publishes, oldest first. */
    private val annualPeriods = (2015..2026).map { "Mar $it" }

    // ------------------------------------------------------------------ element identity

    @Test
    fun `the card is the section with id ratios and carries twelve annual columns`() {
        for (symbol in ScreenerFixtures.symbols) {
            val card = card(symbol)
            assertTrue(card.isUsable, "$symbol: ${card.notes}")
            assertFalse(card.isAbsent, symbol)
            assertFalse(card.isEmpty, symbol)
            assertEquals(RatiosCardParser.SECTION_ID, "ratios", "the element id this parser matched on")
            assertEquals(annualPeriods, card.periods, symbol)
            assertEquals(12, card.periods.size, symbol)
            assertEquals(
                (2015..2026).map { "$it-03-31" },
                card.dateKeys,
                "$symbol: the card publishes machine-readable data-date-key headers",
            )
        }
    }

    @Test
    fun `debtor days are keyed by the page's own period labels wherever the row is published`() {
        // HDFCBANK is the exception and is asserted separately: a bank has no debtor-days row.
        for (symbol in ScreenerFixtures.symbols - "HDFCBANK") {
            val card = card(symbol)
            assertEquals(
                annualPeriods,
                card.debtorDays.keys.toList(),
                "$symbol debtor days must be keyed by the page's own period labels",
            )
        }
        assertTrue(card("HDFCBANK").debtorDays.isEmpty(), "HDFCBANK publishes no Debtor Days row")
    }

    // ------------------------------------------------------------------- RELIANCE exactly

    @Test
    fun `reliance debtor days and inventory days are exactly the twelve published figures`() {
        val card = card("RELIANCE")
        assertEquals(
            listOf(5.0, 6.0, 10.0, 16.0, 19.0, 12.0, 15.0, 12.0, 12.0, 13.0, 16.0, 20.0),
            annualPeriods.map { card.debtorDays.getValue(it) },
        )
        assertEquals(
            listOf(66.0, 90.0, 84.0, 83.0, 63.0, 67.0, 102.0, 83.0, 87.0, 95.0, 85.0, 88.0),
            annualPeriods.map { card.inventoryDays.getValue(it) },
        )
        // The brief's headline figures, pinned by their column so a reordering cannot pass.
        assertEquals(20.0, card.debtorDays.getValue("Mar 2026"))
        assertEquals(88.0, card.inventoryDays.getValue("Mar 2026"))
    }

    @Test
    fun `reliance publishes the full working capital block`() {
        val card = card("RELIANCE")
        assertEquals(
            listOf(
                "debtordays", "inventorydays", "dayspayable", "cashconversioncycle",
                "workingcapitaldays", "roce",
            ),
            card.rowKeys,
        )
        assertEquals(
            listOf(
                "Debtor Days", "Inventory Days", "Days Payable", "Cash Conversion Cycle",
                "Working Capital Days", "ROCE %",
            ),
            card.rowLabels,
        )
        assertEquals(84.0, card.daysPayable.getValue("Mar 2026"))
        assertEquals(25.0, card.cashConversionCycle.getValue("Mar 2026"))
        assertEquals(-66.0, card.workingCapitalDays.getValue("Mar 2026"))
        // The card prints "10%"; the parser stores the percentage, not the ratio.
        assertEquals(10.0, card.rocePercent.getValue("Mar 2026"))
        assertEquals(emptyMap(), card.roePercent, "RELIANCE's card carries ROCE %, not ROE %")
        assertEquals(emptyMap(), card.cashFromOperations, "CFO lives on the cash-flow statement")
    }

    // ------------------------------------------------------ the full series, all five cards

    @Test
    fun `every fixture debtor days series is reported in full`() {
        val expected = mapOf(
            "RELIANCE" to listOf(5.0, 6.0, 10.0, 16.0, 19.0, 12.0, 15.0, 12.0, 12.0, 13.0, 16.0, 20.0),
            "TCS" to listOf(79.0, 81.0, 70.0, 74.0, 68.0, 71.0, 67.0, 80.0, 81.0, 81.0, 84.0, 93.0),
            "HDFCBANK" to emptyList(),
            "INFY" to listOf(66.0, 66.0, 66.0, 68.0, 65.0, 74.0, 70.0, 68.0, 63.0, 72.0, 70.0, 72.0),
            "TITAN" to listOf(6.0, 6.0, 6.0, 7.0, 8.0, 5.0, 6.0, 7.0, 6.0, 7.0, 6.0, 4.0),
        )
        for (symbol in ScreenerFixtures.symbols) {
            val series = card(symbol).debtorDays
            val values = annualPeriods.mapNotNull { series[it] }
            assertEquals(expected.getValue(symbol), values, "$symbol debtor days")
            assertEquals(
                expected.getValue(symbol).size,
                series.size,
                "$symbol: a missing column must be absent from the series, not zero-filled",
            )
        }
    }

    @Test
    fun `every fixture inventory days series is reported in full`() {
        val expected = mapOf(
            "RELIANCE" to listOf(66.0, 90.0, 84.0, 83.0, 63.0, 67.0, 102.0, 83.0, 87.0, 95.0, 85.0, 88.0),
            "TCS" to emptyList(),
            "HDFCBANK" to emptyList(),
            "INFY" to emptyList(),
            "TITAN" to listOf(169.0, 198.0, 189.0, 185.0, 178.0, 195.0, 187.0, 230.0, 199.0, 176.0, 217.0, 222.0),
        )
        for (symbol in ScreenerFixtures.symbols) {
            val series = card(symbol).inventoryDays
            val values = annualPeriods.mapNotNull { series[it] }
            assertEquals(expected.getValue(symbol), values, "$symbol inventory days")
            assertEquals(expected.getValue(symbol).size, series.size, "$symbol")
        }
    }

    @Test
    fun `tcs publishes an inventory days row whose every cell is blank so the series is empty`() {
        val card = card("TCS")
        // The ROW exists…
        val row = assertNotNull(card.row(*RatiosCardParser.INVENTORY_DAYS_KEYS), "TCS prints the row")
        assertEquals("Inventory Days", row.label)
        assertEquals(12, row.values.size, "the row is aligned to all twelve columns")
        // …and every cell is a null, so nothing was invented.
        assertTrue(row.values.all { it == null }, "TCS Inventory Days cells: ${row.values}")
        assertTrue(row.isEmpty, "an all-blank row publishes no figure")
        assertFalse(row.hasAnyValue)
        assertEquals(emptyMap(), card.inventoryDays, "never twelve zeros")
        assertEquals(0, card.inventoryDays.size)
    }

    @Test
    fun `infy publishes an inventory days row whose every cell is blank`() {
        val card = card("INFY")
        val row = assertNotNull(card.row(*RatiosCardParser.INVENTORY_DAYS_KEYS))
        assertTrue(row.values.all { it == null }, "INFY Inventory Days cells: ${row.values}")
        assertEquals(emptyMap(), card.inventoryDays)
        // The row is still counted as published, which is why the note names it specifically.
        assertTrue(
            card.notes.any { it.startsWith("row 'Inventory Days' is present") },
            card.notes.toString(),
        )
    }

    @Test
    fun `hdfcbank card holds only ROE percent and no debtor or inventory row`() {
        val card = card("HDFCBANK")
        assertEquals(listOf("roe"), card.rowKeys, "a bank's card publishes exactly one row")
        assertEquals(listOf("ROE %"), card.rowLabels)
        assertEquals(
            listOf(20.0, 19.0, 18.0, 18.0, 17.0, 16.0, 16.0, 17.0, 17.0, 17.0, 14.0, 14.0),
            annualPeriods.map { card.roePercent.getValue(it) },
        )
        // The three absences are distinct from one another and from a zero.
        assertNull(card.row(*RatiosCardParser.DEBTOR_DAYS_KEYS), "no Debtor Days row at all")
        assertEquals(emptyMap(), card.debtorDays)
        assertEquals(emptyMap(), card.inventoryDays)
        assertEquals(emptyMap(), card.daysPayable)
        assertEquals(emptyMap(), card.rocePercent)
        // The card itself is still usable — one readable row is a usable card.
        assertTrue(card.isUsable, "a bank card with ROE % is a found section")
    }

    @Test
    fun `hdfcbank inventory days is absent by name rather than reported as zero days`() {
        val derived = ratioCard("HDFCBANK")
        assertFalse(derived.inventoryDaysByYear.containsKey(2026))
        assertTrue(derived.inventoryDaysByYear.isEmpty())
        assertTrue(derived.dsoByYear.isEmpty())
        // ROE does come through, because the bank does publish it.
        assertEquals(14.0, derived.roePercentByYear.getValue(2026))
        assertEquals(emptyMap(), derived.extras.inventoryDays)
        assertEquals(emptyMap(), derived.extras.debtorDays)
    }

    // --------------------------------------------------------- per-year keys and the hand-off

    @Test
    fun `byYear maps every published column to its fiscal year`() {
        val derived = ratioCard("RELIANCE")
        assertEquals(annualPeriods, derived.dsoByYear.keys.map { "Mar $it" }, "12 years, oldest first")
        assertEquals(20.0, derived.dsoByYear[2026])
        assertEquals(5.0, derived.dsoByYear[2015])
        assertEquals(88.0, derived.inventoryDaysByYear[2026])
        assertEquals(169.0, ratioCard("TITAN").inventoryDaysByYear[2015])
        assertEquals(222.0, ratioCard("TITAN").inventoryDaysByYear[2026])
        assertEquals(2026, derived.latestYear)
    }

    @Test
    fun `a TTM column would be dropped from the per-year maps never treated as a fiscal year`() {
        val card = card("RELIANCE")
        // The card has no TTM today; assert the rule on the accessor rather than on the fixture.
        assertNull(ScreenerHtml.yearOf("TTM"))
        assertEquals(
            annualPeriods.size,
            card.byYear(*RatiosCardParser.DEBTOR_DAYS_KEYS).size,
        )
    }

    @Test
    fun `extras is the domain RatioExtras keyed by the page's own period labels`() {
        val extras: RatioExtras = card("RELIANCE").extras()
        assertEquals(20.0, extras.debtorDays.getValue("Mar 2026"))
        assertEquals(88.0, extras.inventoryDays.getValue("Mar 2026"))
        assertEquals(12, extras.debtorDays.size)
        assertEquals(12, extras.inventoryDays.size)
        // cashFromOperations is empty: the card does not publish it, and the domain's accessor
        // must therefore return null rather than a fabricated figure.
        assertEquals(emptyMap(), extras.cashFromOperations)
        assertNull(extras.cashFromOperationsFor(2026))
        assertEquals(20.0, extras.debtorDaysFor(2026))
        assertEquals(88.0, extras.inventoryDaysFor(2026))
        assertFalse(extras.isEmpty)
    }

    @Test
    fun `the domain ratio computation now returns the published figures instead of a null`() {
        val result = ScreenerFixtures.parse("RELIANCE")
        val extras = result.ratioCard.extras
        val computed = com.mia.app.domain.ratios.RatioComputation.receivableDays(
            derived = result.snapshot.cashFlowDerived,
            year = 2026,
            extras = extras,
        )
        assertEquals(20.0, computed.value, "the published debtor days, not a derived guess")
        assertTrue(assertNotNull(computed.note).contains("as published by the source"), computed.note)

        val inventory = com.mia.app.domain.ratios.RatioComputation.inventoryDays(
            derived = result.snapshot.cashFlowDerived,
            year = 2026,
            extras = extras,
        )
        assertEquals(88.0, inventory.value)
    }

    @Test
    fun `tcs inventory days stays a reasoned null in the domain even though the row exists`() {
        val result = ScreenerFixtures.parse("TCS")
        val computed = com.mia.app.domain.ratios.RatioComputation.inventoryDays(
            derived = result.snapshot.cashFlowDerived,
            year = 2026,
            extras = result.ratioCard.extras,
        )
        assertNull(computed.value, "a blank cell is not zero inventory days")
        assertTrue(assertNotNull(computed.note).contains("cost of goods"), computed.note)
    }

    @Test
    fun `toRatiosCardSeries fills the five series the red flag phase consumes`() {
        val series = ratioCard("RELIANCE").toRatiosCardSeries()
        assertEquals(20.0, series.debtorDays[2026])
        assertEquals(88.0, series.inventoryDays[2026])
        assertEquals(84.0, series.daysPayable[2026])
        assertEquals(10.0, series.rocePercent[2026])
        assertTrue(series.roePercent.isEmpty(), "RELIANCE's card has ROCE %, not ROE %")
        assertFalse(series.isEmpty)
        // The year-over-year comparison the working-capital flags depend on.
        // Returns `(previousYear, (latestYear, latestValue))`.
        val pair = assertNotNull(series.latestConsecutivePair(series.debtorDays), "2025 and 2026")
        assertEquals(2025, pair.first)
        assertEquals(2026, pair.second.first)
        assertEquals(20.0, pair.second.second)
    }

    // ------------------------------------------------------------------------ gross margin

    @Test
    fun `no fixture card carries a gross profit cost of goods or ebitda row`() {
        for (symbol in ScreenerFixtures.symbols) {
            val labels = card(symbol).rowLabels
            for (forbidden in listOf("Gross Profit", "Operating Profit", "Expenses", "EBITDA")) {
                assertTrue(
                    labels.none { it.contains(forbidden, ignoreCase = true) },
                    "$symbol unexpectedly publishes $forbidden on the ratios card: $labels",
                )
            }
        }
    }

    @Test
    fun `no fixture publishes gross profit or cost of goods anywhere on the page`() {
        // The card was the last unparsed section, so this is the whole-page confirmation that
        // gross margin has no source at all and not merely one in the wrong place.
        for (symbol in ScreenerFixtures.symbols) {
            val html = ScreenerFixtures.html(symbol)
            for (forbidden in listOf("Gross Profit", "Cost of Goods", "Cost of Revenue", "Raw Material")) {
                assertFalse(
                    html.contains(forbidden, ignoreCase = true),
                    "$symbol mentions '$forbidden' somewhere the parser should have read",
                )
            }
        }
    }

    @Test
    fun `gross margin and ev to ebitda are reported unsourceable with a measured reason`() {
        for (symbol in ScreenerFixtures.symbols) {
            val notes = ScreenerFixtures.parse(symbol).health.notes
            val gross = assertNotNull(
                notes.firstOrNull { it.startsWith("gross margin is not derivable") },
                "$symbol: gross margin needs an unsourceable reason, never a blank",
            )
            assertTrue(gross.contains("cost of goods"), gross)
            assertTrue(gross.contains("never 0"), gross)

            val ev = assertNotNull(
                notes.firstOrNull { it.startsWith("EV/EBITDA is not derivable") },
                "$symbol: EV/EBITDA needs an unsourceable reason",
            )
            assertTrue(ev.contains("enterprise value"), ev)
            assertTrue(ev.contains("never 0"), ev)
            // The one 'EV / EBITDA' string on the page is a chart-metric button, not a figure, and
            // the note must say so rather than implying the page never mentions it.
            assertTrue(ev.contains("chart-metric selector"), ev)

            val proxy = assertNotNull(
                notes.firstOrNull { it.startsWith("no EBITDA proxy is available") },
                "$symbol: an EBITDA proxy must be declared unavailable, not invented",
            )
            assertTrue(proxy.contains("no proxy is constructed"), proxy)
        }
    }

    @Test
    fun `gross margin and ev to ebitda remain null on every snapshot`() {
        for (symbol in ScreenerFixtures.symbols) {
            val ratios = ScreenerFixtures.parse(symbol).snapshot.ratios
            assertNull(ratios.grossMargin, "$symbol")
            assertNull(ratios.evToEbitda, "$symbol")
        }
    }

    // ---------------------------------------------------------------------------- helpers

    private fun card(symbol: String): RatiosCard =
        RatiosCardParser.parse(ScreenerHtml.document(ScreenerFixtures.html(symbol)))

    private fun ratioCard(symbol: String): RatioCardDerived =
        DerivedMetrics.ratioCard(card(symbol))
}