package com.mia.app.domain.ratios

import com.mia.app.domain.model.AnnualRow
import com.mia.app.domain.model.CashFlowDerived
import com.mia.app.domain.model.Direction
import com.mia.app.domain.model.Sector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [RatioComputation] golden tests.
 *
 * Every expected figure below was read out of the real screener.in fixtures captured
 * 2026-10-02 (the HTML files in `data/src/test/resources/fixtures`), not out of plan.md. The
 * four ratios
 * `RatioMapper` cannot publish are computed here instead: debt/equity is a division of published
 * balance-sheet lines, and the others are pass-throughs or arithmetic on published figures.
 */
class RatioComputationTest {

    // ------------------------------------------------------------------- debt to equity

    @Test
    fun `reliance debt to equity is borrowings over equity capital plus reserves`() {
        // Borrowings 402,962; Equity Capital 13,532 + Reserves 890,498 = 904,030.
        val result = RatioComputation.debtToEquity(relianceDerived(), Sector.POWER)
        assertRatioClose(402_962.0 / 904_030.0, result.value)
        assertRatioClose(0.4457, result.value, 5e-5)
        assertTrue(result.note!!.contains("402962"), result.note!!)
        assertTrue(result.note!!.contains("904030"), result.note!!)
    }

    @Test
    fun `titan debt to equity matches the published balance sheet`() {
        // Borrowings 30,621; Equity Capital + Reserves 15,703.
        val result = RatioComputation.debtToEquity(titanDerived(), Sector.DURABLES)
        assertRatioClose(30_621.0 / 15_703.0, result.value)
        assertRatioClose(1.95, result.value, 5e-3)
    }

    @Test
    fun `debt to equity is suppressed for a bank because it is not meaningful`() {
        // HDFCBANK's own figures: Borrowing 588,485 over net worth 586,059 is a "D/E" of 1.004,
        // which is exactly the number §7.3 says must never be shown: it says nothing about risk.
        val result = RatioComputation.debtToEquity(hdfcBankDerived(), Sector.BANKING)
        assertNull(result.value)
        assertTrue(result.note!!.contains("not meaningful for a bank"), result.note!!)
        assertTrue(result.note!!.contains("§7.3"), result.note!!)
    }

    @Test
    fun `debt to equity for a non bank is never suppressed`() {
        for (sector in listOf(Sector.AUTO, Sector.IT, Sector.PHARMA, Sector.OTHER, Sector.NBFC)) {
            assertNotNull(
                RatioComputation.debtToEquity(relianceDerived(), sector).value,
                sector.name,
            )
        }
    }

    @Test
    fun `debt to equity never mixes two years`() {
        // Debt exists for 2026 only; asking for 2025 must fail rather than borrow 2026's debt.
        val derived = relianceDerived()
        val result = RatioComputation.debtToEquity(derived, Sector.POWER, year = 2025)
        assertNull(result.value)
        assertTrue(result.note!!.contains("2025"), result.note!!)
    }

    @Test
    fun `debt to equity with zero net worth is undefined rather than infinite`() {
        val derived = CashFlowDerived(debtByYear = mapOf(2026 to 100.0), equityByYear = mapOf(2026 to 0.0))
        val result = RatioComputation.debtToEquity(derived, Sector.POWER)
        assertNull(result.value)
        assertTrue(result.note!!.contains("zero"), result.note!!)
    }

    // --------------------------------------------------------------- interest coverage

    @Test
    fun `reliance interest coverage is ebit over interest`() {
        // EBIT 179,065 + 57,688 = 236,753; interest 27,061.
        val result = RatioComputation.interestCoverage(relianceDerived(), Sector.POWER)
        assertRatioClose(236_753.0 / 27_061.0, result.value)
        assertRatioClose(8.74886, result.value, 1e-5)
    }

    @Test
    fun `tcs interest coverage is a multiple not a percentage`() {
        // EBIT 72,398 + 5,560 = 77,958; interest 1,227.
        val result = RatioComputation.interestCoverage(tcsDerived(), Sector.IT)
        assertRatioClose(77_958.0 / 1_227.0, result.value)
        assertRatioClose(63.53545, result.value, 1e-5)
    }

    @Test
    fun `a bank has no meaningful interest coverage and hdfcbank has no ebit either`() {
        val bank = RatioComputation.interestCoverage(hdfcBankDerived(), Sector.BANKING)
        assertNull(bank.value, "the sector decides before the data is even looked at")
        assertTrue(bank.note!!.contains("interest income"), bank.note!!)

        // Even without the sector override there is nothing to divide: no Operating Profit row.
        val unclassified = RatioComputation.interestCoverage(hdfcBankDerived(), Sector.OTHER)
        assertNull(unclassified.value)
        assertTrue(unclassified.note!!.contains("EBIT"), unclassified.note!!)
    }

    @Test
    fun `interest coverage with zero interest is undefined`() {
        val derived = CashFlowDerived(ebitByYear = mapOf(2026 to 500.0), interestByYear = mapOf(2026 to 0.0))
        val result = RatioComputation.interestCoverage(derived, Sector.POWER)
        assertNull(result.value)
        assertTrue(result.note!!.contains("zero"), result.note!!)
    }

    // ------------------------------------------------------------------------ cfo / op

    @Test
    fun `cfo to op is passed through as the ratio the page means`() {
        val reliance = RatioComputation.cfoToOp(relianceDerived())
        assertEquals(1.13, reliance.value)
        assertTrue(reliance.note!!.contains("ratio"), reliance.note!!)
        assertEquals(0.92, RatioComputation.cfoToOp(tcsDerived().copy(cfoToOpByYear = mapOf(2026 to 0.92))).value)
    }

    // ---------------------------------------------------------------------------- cagr

    @Test
    fun `reliance three year revenue cagr matches the published figure`() {
        val result = RatioComputation.revenueCagr(relianceDerived(), years = 3)
        // 2023 → 2026: 963,889 → 1,055,780.
        assertRatioClose(6.4039330737, result.value, 1e-8)
    }

    @Test
    fun `revenue growth is the year on year change in percent`() {
        // 2025 → 2026: 1,063,009 → 1,055,780.
        val result = RatioComputation.revenueGrowth(relianceDerived())
        assertRatioClose(9.6549718535, result.value, 1e-8, "expected value from the RELIANCE fixture")
    }

    /**
     * The gap rule: a year the page did not publish is skipped, and the exponent is the number
     * of years the two endpoints actually span.
     */
    @Test
    fun `a gap in the revenue series is skipped and the exponent is the elapsed span`() {
        val gapped = CashFlowDerived(
            revenueByYear = mapOf(2018 to 100.0, 2020 to 121.0, 2021 to 133.1),
        )
        val result = RatioComputation.revenueCagr(gapped, years = 3)
        assertRatioClose(10.0, result.value, 1e-9)
        assertTrue(result.note!!.contains("2019"), result.note!!)
        assertTrue(result.note!!.contains("skipped"), result.note!!)
    }

    @Test
    fun `a gap outside the window does not shorten the exponent`() {
        val gapped = CashFlowDerived(revenueByYear = mapOf(2017 to 100.0, 2022 to 121.0))
        // Window is 2020..2022, so only one endpoint lands inside it.
        val result = RatioComputation.revenueCagr(gapped, years = 3)
        assertNull(result.value)
        assertTrue(result.note!!.contains("cannot be computed"), result.note!!)
    }

    @Test
    fun `two endpoints two years apart use a two year exponent`() {
        val sparse = CashFlowDerived(revenueByYear = mapOf(2019 to 100.0, 2021 to 121.0))
        val result = RatioComputation.revenueCagr(sparse, years = 3)
        assertRatioClose(10.0, result.value, 1e-9, "two elapsed years, not three")
    }

    @Test
    fun `year on year growth refuses to compare across a gap`() {
        val sparse = CashFlowDerived(revenueByYear = mapOf(2019 to 100.0, 2021 to 121.0))
        val result = RatioComputation.revenueGrowth(sparse)
        assertNull(result.value)
        assertTrue(result.note!!.contains("2020"), result.note!!)
    }

    @Test
    fun `five year cagr is the five year window`() {
        // 2021 → 2026 over five years.
        val derived = CashFlowDerived(
            revenueByYear = mapOf(2021 to 100.0, 2026 to 161.051),
        )
        assertRatioClose(10.0, RatioComputation.revenueCagr(derived, years = 5).value, 1e-9)
    }

    // ---------------------------------------------------------------------- earnings quality

    @Test
    fun `reliance free cash flow to pat is the ratio of the two published figures`() {
        // FCF 70,023; PAT 95,754.
        val result = RatioComputation.fcfToPat(relianceDerived())
        assertRatioClose(70_023.0 / 95_754.0, result.value)
        assertRatioClose(0.731350, result.value, 1e-6)
        assertEquals(70_023.0, RatioComputation.freeCashFlow(relianceDerived()).value)
        assertEquals(95_754.0, RatioComputation.pat(relianceDerived()).value)
    }

    @Test
    fun `fcf to pat with zero pat is undefined`() {
        val derived = CashFlowDerived(fcfByYear = mapOf(2026 to 10.0), patByYear = mapOf(2026 to 0.0))
        val result = RatioComputation.fcfToPat(derived)
        assertNull(result.value)
        assertTrue(result.note!!.contains("PAT is zero"), result.note!!)
    }

    // --------------------------------------------------------------------- balance sheet

    @Test
    fun `goodwill to assets is a percentage when goodwill is published`() {
        val derived = CashFlowDerived(
            goodwillByYear = mapOf(2026 to 100_000.0),
            totalAssetsByYear = mapOf(2026 to 1_000_000.0),
        )
        val result = RatioComputation.goodwillToAssets(derived)
        assertEquals(10.0, result.value)
        assertTrue(result.note!!.contains("total assets"), result.note!!)
    }

    /**
     * The measured state of the source: the collapsed balance-sheet summary card publishes no
     * Goodwill line at all, so this must be null **with the reason**, never 0.0 — a zero would
     * read as "this company wrote off its goodwill", which is a completely different statement.
     */
    @Test
    fun `goodwill to assets is null with a reason when goodwill is absent`() {
        val result = RatioComputation.goodwillToAssets(relianceDerived())
        assertNull(result.value)
        assertTrue(result.note!!.contains("Goodwill"), result.note!!)
        assertTrue(result.note!!.contains("collapsed balance-sheet summary"), result.note!!)
        assertTrue(result.note!!.contains("rather than zero"), result.note!!)
    }

    @Test
    fun `inventory and receivable days are never derived without cost of goods`() {
        val inventory = RatioComputation.inventoryDays(relianceDerived())
        assertNull(inventory.value)
        assertTrue(inventory.note!!.contains("cost of goods"), inventory.note!!)

        val debtors = RatioComputation.receivableDays(relianceDerived())
        assertNull(debtors.value)
        assertTrue(debtors.note!!.contains("Trade Receivables"), debtors.note!!)
    }

    /**
     * The future [RatioExtras] path: when a parser owns the `ratios` card, the published figure
     * is used **as published** — no re-derivation, no unit conversion.
     */
    @Test
    fun `published days figures are used when the ratios card has been parsed`() {
        val extras = RatioExtras(
            debtorDays = mapOf("Mar 2026" to 41.0),
            inventoryDays = mapOf("Mar 2026" to 62.0),
        )
        assertEquals(62.0, RatioComputation.inventoryDays(relianceDerived(), extras = extras).value)
        assertEquals(41.0, RatioComputation.receivableDays(relianceDerived(), extras = extras).value)
        // Asking for a year the card does not carry still falls back to the honest reason.
        val missingYear = RatioComputation.inventoryDays(relianceDerived(), 2020, extras)
        assertNull(missingYear.value)
        assertTrue(missingYear.note!!.contains("cost of goods"), missingYear.note!!)
    }

    @Test
    fun `an empty extras object behaves exactly like no extras at all`() {
        val extras = RatioExtras()
        assertTrue(extras.isEmpty)
        assertNull(RatioComputation.inventoryDays(relianceDerived(), extras = extras).value)
    }

    @Test
    fun `promoter pledge stays null because the page publishes none`() {
        val result = RatioComputation.promoterPledge(relianceDerived())
        assertNull(result.value)
        assertTrue(result.note!!.contains("never 0"), result.note!!)
        assertEquals(4.2, RatioComputation.promoterPledge(relianceDerived().copy(promoterPledgePercent = 4.2)).value)
    }

    // ---------------------------------------------------------------------- shareholding

    @Test
    fun `promoter holding compares the same quarter a year earlier`() {
        val result = RatioComputation.promoterHoldingTrend(
            relianceShareholdingPeriods,
            listOf(relianceShareholdingRow),
        )
        val trend = assertNotNull(result.value)
        assertEquals(50.48, trend.latestPercent)
        assertEquals("Jun 2026", trend.latestPeriod)
        assertEquals(50.47, trend.previousPercent)
        assertEquals("Jun 2025", trend.previousPeriod)
        assertEquals(PromoterTrend.Comparison.SAME_PERIOD_PRIOR_YEAR, trend.comparison)
        // 50.48 − 50.47, in percentage points, not a percent change.
        assertRatioClose(0.01, trend.changePercentagePoints, 1e-9)
        assertTrue(trend.isRising)
        assertTrue(result.note!!.contains("Jun 2026"), result.note!!)
    }

    @Test
    fun `promoter holding falls back to the previous column and says so`() {
        // Quarterly columns that do not reach back a full year.
        val periods = listOf("Dec 2025", "Mar 2026", "Jun 2026")
        val row = AnnualRow("Promoters +", listOf(51.0, 50.0, 49.0))
        val trend = assertNotNull(RatioComputation.promoterHoldingTrend(periods, listOf(row)).value)
        assertEquals(PromoterTrend.Comparison.PREVIOUS_COLUMN, trend.comparison)
        assertEquals(50.0, trend.previousPercent)
        assertEquals(-1.0, trend.changePercentagePoints)
        assertTrue(trend.isDeclining)
    }

    @Test
    fun `a shareholding card with no promoters row returns null with a reason`() {
        // Verified on the HDFCBANK fixture: its card publishes no Promoters row.
        val result = RatioComputation.promoterHoldingTrend(
            listOf("Mar 2025", "Jun 2026"),
            listOf(AnnualRow("FIIs +", listOf(60.0, 61.0)), AnnualRow("DIIs +", listOf(15.0, 16.0))),
        )
        assertNull(result.value)
        assertTrue(result.note!!.contains("Promoters"), result.note!!)
        assertNull(RatioComputation.promoterHolding(listOf("Mar 2025"), listOf(AnnualRow("DIIs +", listOf(1.0)))).value)
    }

    @Test
    fun `a TTM column is never treated as a shareholding quarter`() {
        val periods = listOf("Mar 2026", "Jun 2026", "TTM")
        val row = AnnualRow("Promoters +", listOf(50.0, 50.48, 999.0))
        val trend = assertNotNull(RatioComputation.promoterHoldingTrend(periods, listOf(row)).value)
        assertEquals("Jun 2026", trend.latestPeriod)
        assertEquals(50.48, trend.latestPercent)
    }

    @Test
    fun `promoter columns out of order are still compared by period`() {
        val periods = listOf("Jun 2026", "Mar 2026", "Jun 2025")
        val row = AnnualRow("Promoters +", listOf(50.48, 50.2, 50.47))
        val trend = assertNotNull(RatioComputation.promoterHoldingTrend(periods, listOf(row)).value)
        assertEquals("Jun 2026", trend.latestPeriod)
        assertEquals("Jun 2025", trend.previousPeriod)
        assertEquals(PromoterTrend.Comparison.SAME_PERIOD_PRIOR_YEAR, trend.comparison)
    }

    // ----------------------------------------------------------------------------- trends

    @Test
    fun `operating margin trend spans five years from the published OPM row`() {
        val result = RatioComputation.operatingMarginTrend(
            relianceProfitLossPeriods,
            listOf(relianceOpmRow),
        )
        val trend = assertNotNull(result.value)
        // Window is Mar 2022..Mar 2026 (the TTM column is not a fiscal year), five points.
        assertEquals(5, trend.points.size)
        assertEquals(5, trend.yearsSpanned)
        assertEquals(2022, trend.oldest!!.year)
        assertEquals(2026, trend.latest!!.year)
        assertEquals(11.55, trend.oldest!!.value)
        assertEquals(17.0, trend.latest!!.value)
        assertRatioClose(5.45, trend.changeAbsolute, 1e-9, "percentage points")
        assertEquals(Direction.UP, trend.direction)
        assertRatioClose(1.09, trend.changePerYear, 1e-9)
    }

    @Test
    fun `an operating margin trend with a missing year reports the years that exist`() {
        val periods = listOf("Mar 2022", "Mar 2023", "Mar 2024", "Mar 2025", "Mar 2026")
        val row = AnnualRow("OPM %", listOf(10.0, 12.0, null, 16.0, 18.0))
        val trend = assertNotNull(RatioComputation.operatingMarginTrend(periods, listOf(row)).value)
        assertEquals(listOf(2022, 2023, 2025, 2026), trend.points.map { it.year })
        assertEquals(4, trend.yearsSpanned)
        assertRatioClose(2.0, trend.changePerYear, 1e-9, "8 points over 4 years")
        assertTrue(result_note_mentions(trend))
    }

    private fun result_note_mentions(trend: Trend): Boolean =
        RatioComputation.operatingMarginTrend(
            listOf("Mar 2022", "Mar 2023", "Mar 2024", "Mar 2025", "Mar 2026"),
            listOf(AnnualRow("OPM %", listOf(10.0, 12.0, null, 16.0, 18.0))),
        ).note!!.contains("2024")

    @Test
    fun `a bank publishes no operating profit row so the trend explains why`() {
        val bank = listOf(AnnualRow("Financing Margin %", listOf(2.1, 2.4)))
        val result = RatioComputation.operatingMarginTrend(listOf("Mar 2025", "Mar 2026"), bank)
        assertNull(result.value)
        assertTrue(result.note!!.contains("Financing Margin"), result.note!!)
        assertTrue(result.note!!.contains("§7.3"), result.note!!)
    }

    @Test
    fun `gross margin trend is absent because no source publishes cost of goods`() {
        val result = RatioComputation.grossMarginTrend(emptyMap())
        assertNull(result.value)
        assertTrue(result.note!!.contains("cost of goods"), result.note!!)

        // With a series it behaves exactly like the operating margin trend.
        val populated = RatioComputation.grossMarginTrend(
            mapOf(2022 to 40.0, 2023 to 42.0, 2024 to 41.0, 2025 to 45.0, 2026 to 47.0),
        )
        val trend = assertNotNull(populated.value)
        assertEquals(47.0, trend.latest!!.value)
        assertEquals(Direction.UP, trend.direction)
        assertRatioClose(7.0, trend.changeAbsolute, 1e-9)
    }

    @Test
    fun `a flat trend is neither up nor down`() {
        val result = RatioComputation.fiveYearTrend(
            listOf(YearValue(2022, 10.0), YearValue(2023, 10.0), YearValue(2024, 10.0)),
        )
        val trend = assertNotNull(result.value)
        assertNull(trend.direction, "no change is a finding, not an up or a down")
        assertEquals(0.0, trend.changeAbsolute)
    }

    @Test
    fun `a single point is a value not a trend`() {
        val result = RatioComputation.fiveYearTrend(listOf(YearValue(2026, 17.0)))
        assertNull(result.value)
        assertTrue(result.note!!.contains("needs two"), result.note!!)
    }

    @Test
    fun `dividend payout is read from the published row`() {
        val periods = listOf("Mar 2025", "Mar 2026")
        val rows = listOf(AnnualRow("Dividend Payout %", listOf(19.42, 0.0)))
        assertEquals(19.42, RatioComputation.dividendPayout(periods, rows, 2025).value)
        // A published zero is data, not a missing value.
        assertEquals(0.0, RatioComputation.dividendPayout(periods, rows, 2026).value)
        assertTrue(RatioComputation.dividendPayout(periods, rows, 2020).note!!.contains("2020"))
    }

    // ------------------------------------------------------------------ all-null input

    /**
     * The loudest version of plan.md §12's silent-empty rule: with nothing to work from, every
     * function returns null **with a reason** and not one of them returns 0.
     */
    @Test
    fun `an empty statement set produces nulls with reasons and never a zero`() {
        val empty = CashFlowDerived()
        val periods = emptyList<String>()
        val rows = emptyList<AnnualRow>()

        val results = listOf(
            "debtToEquity" to RatioComputation.debtToEquity(empty, Sector.POWER),
            "debtToEquity(bank)" to RatioComputation.debtToEquity(empty, Sector.BANKING),
            "interestCoverage" to RatioComputation.interestCoverage(empty, Sector.POWER),
            "cfoToOp" to RatioComputation.cfoToOp(empty),
            "revenueCagr" to RatioComputation.revenueCagr(empty),
            "revenueGrowth" to RatioComputation.revenueGrowth(empty),
            "fcfToPat" to RatioComputation.fcfToPat(empty),
            "freeCashFlow" to RatioComputation.freeCashFlow(empty),
            "pat" to RatioComputation.pat(empty),
            "inventoryDays" to RatioComputation.inventoryDays(empty),
            "receivableDays" to RatioComputation.receivableDays(empty),
            "goodwillToAssets" to RatioComputation.goodwillToAssets(empty),
            "promoterPledge" to RatioComputation.promoterPledge(empty),
            "promoterTrend" to RatioComputation.promoterHoldingTrend(periods, rows),
            "promoterHolding" to RatioComputation.promoterHolding(periods, rows),
            "opmTrend" to RatioComputation.operatingMarginTrend(periods, rows),
            "grossMarginTrend" to RatioComputation.grossMarginTrend(emptyMap()),
            "dividendPayout" to RatioComputation.dividendPayout(periods, rows),
        )

        for ((name, result) in results) {
            assertNull(result.value, "$name must not invent a value")
            assertTrue(!result.note.isNullOrBlank(), "$name must say why")
        }
        assertEquals(18, results.size)
    }

    @Test
    fun `an unavailable ratio never prints as zero`() {
        // Zero is a legitimate *published* value (INFY publishes Interest 0.0 for 2019), so the
        // only safe rule is: absent input produces null, and a real zero passes through.
        assertNull(RatioComputation.interestCoverage(relianceDerived(), Sector.POWER, 2019).value)
        val realZero = RatioComputation.interestCoverage(
            CashFlowDerived(ebitByYear = mapOf(2019 to 100.0), interestByYear = mapOf(2019 to 0.0)),
            Sector.IT,
        )
        assertNull(realZero.value, "coverage with no interest expense is undefined, not zero")
    }
}