package com.mia.app.domain.redflag.fundamentals

import com.mia.app.domain.model.CashFlowDerived
import com.mia.app.domain.model.FlagSeverity
import com.mia.app.domain.model.RedFlag
import com.mia.app.domain.model.Sector
import com.mia.app.domain.redflag.RedFlagContext
import com.mia.app.domain.ratios.RatioComputation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The four captured fixtures, asserted symbol by symbol.
 *
 * These are the tests that catch a rule which is arithmetically self-consistent and factually
 * wrong. A flag on RELIANCE or TCS means a threshold, a unit or a sector decision is off, and
 * the failure is far cheaper to find here than in a screenshot.
 *
 * ### What each fixture must stay quiet on, and why
 *
 * | Symbol | Must stay quiet | Because |
 * |--------|-----------------|---------|
 * | RELIANCE | CFO-vs-PAT, D/E spike, interest coverage, value trap | CFO `192,113` is strongly positive against PAT `95,754`; D/E moves `0.4439 → 0.4457`; coverage is `8.7489`; and its sector is unresolved, which suppresses the band comparison |
 * | TCS | interest coverage `63.5354`, value trap, other income | coverage of 63x is "strong" in the book's bands, and TCS published *negative* other income in FY2026 |
 * | HDFCBANK | D/E, interest coverage, other income | plan.md §7.3 drops D/E for a bank; its `Interest` row is income, not expense; its other-income ratio is 185 % and meaningless for a bank |
 * | TITAN | CFO-vs-PAT, inventory days | CFO is negative in exactly **one** year (FY2025) and the book requires several; inventory days moved +5 on a series that ranges 169 → 222 |
 *
 * ### The one RELIANCE flag, asserted rather than hidden
 * RELIANCE's FY2026 other income is `28,846` against PAT `95,754` = **30.13 %**, above the 30 %
 * threshold the spec derived. The spec calibrated 30 % against RELIANCE's **TTM** column
 * (`20,393 / 88,167 = 23.13 %`), and our parser drops `TTM` by design, so on annual columns the
 * fixture the spec used to justify the band now trips it. Rather than quietly raise the band or
 * quietly exempt RELIANCE, the behaviour is asserted here and the calibration is flagged in
 * [FundamentalsPolicy.OTHER_INCOME_MEDIUM_PERCENT] for the owner to resolve.
 */
class GoldenFixtureRulesTest {

    private fun flagsFor(context: RedFlagContext, card: RatiosCardSeries?): Map<String, RedFlag> =
        FundamentalsRedFlagRules.all(card).flatMap { it.evaluate(context) }.associateBy { it.id }

    private fun relianceFlags() = flagsFor(relianceContext(), Golden.relianceCard)
    private fun tcsFlags() = flagsFor(tcsContext(), Golden.tcsCard)
    private fun hdfcBankFlags() = flagsFor(hdfcBankContext(), Golden.hdfcBankCard)
    private fun titanFlags() = flagsFor(titanContext(), Golden.titanCard)

    @Test
    fun `reliance stays quiet on cash flow quality debt and coverage`() {
        val flags = relianceFlags()

        for (id in listOf(
            FundamentalsFlagIds.CFO_NEGATIVE_WHILE_PAT_POSITIVE,
            FundamentalsFlagIds.DEBT_TO_EQUITY_SPIKE,
            FundamentalsFlagIds.INTEREST_COVERAGE_BELOW_2,
            FundamentalsFlagIds.RECEIVABLES_GROWING_FASTER_THAN_REVENUE,
            FundamentalsFlagIds.INVENTORY_DAYS_RISING,
            FundamentalsFlagIds.LOW_PE_WITH_FALLING_RETURNS,
            FundamentalsFlagIds.PE_RISING_WHILE_EARNINGS_FALL,
            FundamentalsFlagIds.GOODWILL_ABOVE_20PCT_ASSETS,
            FundamentalsFlagIds.PROMOTER_PLEDGING,
        )) {
            assertNull(flags[id], "$id must stay quiet on RELIANCE")
        }
    }

    @Test
    fun `reliance trips the derived other income band on its annual columns and says why`() {
        val flag = assertNotNull(relianceFlags()[FundamentalsFlagIds.OTHER_INCOME_AS_PCT_OF_PAT])

        assertEquals(FlagSeverity.MEDIUM, flag.severity)
        assertTrue(flag.title.contains("₹28,846 Cr"), flag.title)
        assertTrue(flag.title.contains("₹95,754 Cr"), flag.title)
        assertTrue(flag.title.contains("30.1 % of profit came from items outside operations"), flag.title)
        val evidence = flag.evidence.associate { it.label to it.value }
        assertEquals("30.1 %", evidence["Other income as % of PAT"])
        assertEquals("₹236,753 Cr", evidence["EBIT, operating profit plus depreciation (FY2026)"])
        assertEquals(
            FundamentalsPolicy.UNRESOLVED_SECTOR_VALUE,
            evidence["Sector"],
            "RELIANCE's sector is unresolved, so spec §6.2 requires the sector row",
        )
    }

    @Test
    fun `tcs stays quiet on coverage other income and the value trap`() {
        val context = tcsContext()
        val flags = tcsFlags()

        assertEquals(63.5354, coverageOf(Golden.tcsDerived, Sector.IT), 1e-4)
        assertEquals(51.8, context.ratios.roe)
        assertEquals(14.0, context.ratios.pe)
        assertNull(flags[FundamentalsFlagIds.INTEREST_COVERAGE_BELOW_2])
        assertNull(flags[FundamentalsFlagIds.OTHER_INCOME_AS_PCT_OF_PAT])
        assertNull(flags[FundamentalsFlagIds.LOW_PE_WITH_FALLING_RETURNS])
        assertNull(flags[FundamentalsFlagIds.CFO_NEGATIVE_WHILE_PAT_POSITIVE])
        assertNull(flags[FundamentalsFlagIds.INVENTORY_DAYS_RISING], "the card publishes no inventory days")
        assertNull(
            flags[FundamentalsFlagIds.RECEIVABLES_GROWING_FASTER_THAN_REVENUE],
            "84 -> 93 debtor days is +9, just under the ten-day floor",
        )
    }

    @Test
    fun `hdfcbank shows no debt coverage or other income flag`() {
        val flags = hdfcBankFlags()

        assertNull(flags[FundamentalsFlagIds.DEBT_TO_EQUITY_SPIKE])
        assertNull(flags[FundamentalsFlagIds.INTEREST_COVERAGE_BELOW_2])
        assertNull(flags[FundamentalsFlagIds.OTHER_INCOME_AS_PCT_OF_PAT])
        assertNull(flags[FundamentalsFlagIds.CFO_NEGATIVE_WHILE_PAT_POSITIVE])
    }

    @Test
    fun `titan shows a debt to equity level flag and stays quiet on cash flow and inventory`() {
        val flags = titanFlags()

        assertNull(flags[FundamentalsFlagIds.CFO_NEGATIVE_WHILE_PAT_POSITIVE], "one negative year only")
        assertNull(flags[FundamentalsFlagIds.INVENTORY_DAYS_RISING], "+5 days on a 169-222 range")
        assertNull(flags[FundamentalsFlagIds.OTHER_INCOME_AS_PCT_OF_PAT], "450 / 5,073 = 8.9 %")
        assertNull(flags[FundamentalsFlagIds.INTEREST_COVERAGE_BELOW_2], "7.78x is in the strong band")
        assertNull(flags[FundamentalsFlagIds.LOW_PE_WITH_FALLING_RETURNS], "P/E 68.7 is above the band")
        val debt = assertNotNull(flags[FundamentalsFlagIds.DEBT_TO_EQUITY_SPIKE])
        assertEquals(FlagSeverity.MEDIUM, debt.severity)
        assertTrue(debt.title.contains("1.95×"), debt.title)
    }

    @Test
    fun `the four fixtures produce exactly the two flags the spec predicts`() {
        assertEquals(
            listOf(FundamentalsFlagIds.OTHER_INCOME_AS_PCT_OF_PAT),
            relianceFlags().keys.toList(),
            "RELIANCE: the derived other-income band, and the documented calibration conflict",
        )
        assertTrue(tcsFlags().isEmpty(), "TCS: no flags")
        assertTrue(hdfcBankFlags().isEmpty(), "HDFCBANK: no flags")
        assertEquals(
            listOf(FundamentalsFlagIds.DEBT_TO_EQUITY_SPIKE),
            titanFlags().keys.toList(),
            "TITAN: 1.95 debt/equity against a derived consumer-durables ceiling of 1.0",
        )
    }

    @Test
    fun `every fixture value the brief pins is reproduced from the golden maps`() {
        assertEquals(192_113.0, Golden.relianceDerived.cfoByYear[2026])
        assertEquals(95_754.0, Golden.relianceDerived.patByYear[2026])
        assertEquals(402_962.0, Golden.relianceDerived.debtByYear[2026])
        assertEquals(13_532.0 + 890_498.0, Golden.relianceDerived.equityByYear[2026])
        assertEquals(179_065.0 + 57_688.0, Golden.relianceDerived.ebitByYear[2026])
        assertEquals(27_061.0, Golden.relianceDerived.interestByYear[2026])
        assertEquals(21.2, Golden.relianceRatios.pe)
        assertEquals(1.13, Golden.relianceDerived.cfoToOpByYear[2026])
        assertEquals(0.94, Golden.hdfcBankDerived.cfoToOpByYear[2026])
        assertEquals(-541.0, Golden.titanDerived.cfoByYear[2025])
        assertEquals(70_023.0, Golden.relianceDerived.fcfByYear[2026])
        assertEquals(20.0, Golden.relianceCard.debtorDays[2026])
        assertEquals(16.0, Golden.relianceCard.debtorDays[2025])
        assertEquals(88.0, Golden.relianceCard.inventoryDays[2026])
        assertEquals(222.0, Golden.titanCard.inventoryDays[2026])
        assertEquals(169.0, Golden.titanCard.inventoryDays[2015])
    }

    @Test
    fun `with no ratios card supplied three rules skip with a reason rather than guessing`() {
        val skips = FundamentalsRedFlagEngine()
            .skipsFor(relianceContext())
            .associate { it.ruleId to it.reason }

        assertNotNull(skips[FundamentalsFlagIds.RECEIVABLES_GROWING_FASTER_THAN_REVENUE])
        assertNotNull(skips[FundamentalsFlagIds.INVENTORY_DAYS_RISING])
        assertNotNull(skips[FundamentalsFlagIds.LOW_PE_WITH_FALLING_RETURNS])
        assertNotNull(skips[FundamentalsFlagIds.GOODWILL_ABOVE_20PCT_ASSETS])
        assertNotNull(skips[FundamentalsFlagIds.PROMOTER_PLEDGING])
        assertTrue(skips.values.none { it.isBlank() }, "every skip reason is user-facing prose")
    }

    private fun coverageOf(derived: CashFlowDerived, sector: Sector): Double =
        checkNotNull(RatioComputation.interestCoverage(derived, sector).orNull())
}