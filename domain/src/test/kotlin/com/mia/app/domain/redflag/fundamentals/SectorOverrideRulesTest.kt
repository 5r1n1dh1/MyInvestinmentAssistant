package com.mia.app.domain.redflag.fundamentals

import com.mia.app.domain.model.CashFlowDerived
import com.mia.app.domain.model.Sector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * plan.md §7.3 and spec §6.1/§6.2, exercised through the rules.
 *
 * The sector table in `RatioPriority` decides which *ratios* a bank is shown. This file proves the
 * parallel decision for *flags*, which is a different table with different entries and the same
 * purpose: a single global flag list misleads on a bank in exactly the way plan.md §7.3's heading
 * says. A bank has no meaningful debt/equity, its published interest line is interest income, and
 * its non-operating income is structural — so none of the three checks may produce a flag, and a
 * user must be told "not evaluated" rather than shown a blank or, worse, a number.
 */
class SectorOverrideRulesTest {

    private val debtRule = DebtToEquitySpikeRule()
    private val coverageRule = InterestCoverageBelowTwoRule()
    private val otherIncomeRule = OtherIncomeAsPctOfPatRule()

    /**
     * Balance-sheet figures chosen to trip **every** balance-sheet check at once: debt/equity of
     * 3.5, coverage of 0.8, other income at 90 % of profit. In any non-financial sector this is
     * three flags.
     */
    private val distressed = CashFlowDerived(
        debtByYear = mapOf(2025 to 35_000.0, 2026 to 38_500.0),
        equityByYear = mapOf(2025 to 10_000.0, 2026 to 11_000.0),
        ebitByYear = mapOf(2026 to 800.0),
        interestByYear = mapOf(2026 to 1_000.0),
        otherIncomeByYear = mapOf(2026 to 900.0),
        patByYear = mapOf(2026 to 1_000.0),
    )

    @Test
    fun `a bank gets no debt to equity flag and is told why`() {
        val context = testContext(symbol = "BANKBANK", sector = Sector.BANKING, derived = distressed)

        assertTrue(debtRule.evaluate(context).isEmpty())
        val reason = assertNotNull(debtRule.explainSkip(context))
        assertTrue(reason.contains("not meaningful for a bank"), reason)
        assertTrue(reason.contains("plan.md §7.3"), reason)
    }

    @Test
    fun `a bank gets no interest coverage flag and is told why`() {
        val context = testContext(symbol = "BANKBANK", sector = Sector.BANKING, derived = distressed)

        assertTrue(coverageRule.evaluate(context).isEmpty())
        val reason = assertNotNull(coverageRule.explainSkip(context))
        assertTrue(reason.contains("interest income"), reason)
        assertTrue(reason.contains("plan.md §7.3"), reason)
    }

    @Test
    fun `an nbfc keeps the debt to equity flag with a ceiling of three`() {
        val rule = DebtToEquitySpikeRule()

        val underCeiling = rule.evaluate(
            testContext(
                sector = Sector.NBFC,
                derived = CashFlowDerived(
                    debtByYear = mapOf(2025 to 28_000.0, 2026 to 28_000.0),
                    equityByYear = mapOf(2025 to 10_000.0, 2026 to 10_000.0),
                ),
            ),
        )
        val overCeiling = rule.evaluate(
            testContext(
                sector = Sector.NBFC,
                derived = CashFlowDerived(
                    debtByYear = mapOf(2025 to 40_000.0, 2026 to 42_000.0),
                    equityByYear = mapOf(2025 to 10_000.0, 2026 to 10_000.0),
                ),
            ),
        )

        assertTrue(underCeiling.isEmpty(), "2.8x is over the book's 2.0 but under the NBFC ceiling of 3.0")
        val flag = overCeiling.single()
        assertTrue(flag.title.contains("above the 3.0 ceiling"), flag.title)
        assertTrue(flag.title.contains("NBFC (non-bank financial)"), flag.title)
    }

    @Test
    fun `an nbfc and an insurer get no interest coverage or other income flag`() {
        for (sector in listOf(Sector.NBFC, Sector.INSURANCE)) {
            val context = testContext(sector = sector, derived = distressed)

            assertTrue(coverageRule.evaluate(context).isEmpty(), sector.name)
            assertTrue(otherIncomeRule.evaluate(context).isEmpty(), sector.name)
            assertTrue(
                assertNotNull(coverageRule.explainSkip(context)).contains("cost of goods sold"),
                sector.name,
            )
            assertTrue(assertNotNull(otherIncomeRule.explainSkip(context)).contains("structural"))
        }
    }

    @Test
    fun `an unresolved sector keeps only the debt to equity spike and not the level`() {
        val rule = DebtToEquitySpikeRule()

        val levelOnly = rule.evaluate(
            testContext(
                sector = Sector.OTHER,
                derived = CashFlowDerived(
                    debtByYear = mapOf(2025 to 12_000.0, 2026 to 12_500.0),
                    equityByYear = mapOf(2025 to 5_000.0, 2026 to 5_000.0),
                ),
            ),
        )
        val spike = rule.evaluate(
            testContext(
                sector = Sector.OTHER,
                derived = CashFlowDerived(
                    debtByYear = mapOf(2025 to 12_000.0, 2026 to 20_000.0),
                    equityByYear = mapOf(2025 to 5_000.0, 2026 to 5_000.0),
                ),
            ),
        )

        assertTrue(levelOnly.isEmpty(), "2.5x is above the generic 2.0 but the sector is unresolved")
        val flag = spike.single()
        assertTrue(flag.title.contains("rose from 2.40× to 4.00×"), flag.title)
        val evidence = flag.evidence.associate { it.label to it.value }
        assertEquals(
            FundamentalsPolicy.UNRESOLVED_SECTOR_VALUE,
            evidence["Industry benchmark (sector)"],
            "spec §6.2: an unresolved sector carries no band, and says so",
        )
    }

    @Test
    fun `an unresolved sector suppresses the working capital flags rather than lowering them`() {
        val card = RatiosCardSeries(
            debtorDays = mapOf(2025 to 10.0, 2026 to 60.0),
            inventoryDays = mapOf(2025 to 10.0, 2026 to 60.0),
        )
        val context = testContext(sector = Sector.OTHER)

        for (rule in listOf<FundamentalsRule>(
            ReceivablesGrowingFasterThanRevenueRule(card),
            InventoryDaysRisingRule(card),
        )) {
            assertTrue(rule.evaluate(context).isEmpty(), rule.id)
            assertTrue(assertNotNull(rule.explainSkip(context)).contains("unresolved"), rule.id)
        }
    }

    @Test
    fun `the norm suppressed working capital sectors are exactly the spec table`() {
        assertEquals(
            setOf(
                Sector.METALS, Sector.CEMENT, Sector.POWER, Sector.RENEWABLE, Sector.RAILWAY,
                Sector.REALTY, Sector.AVIATION, Sector.CHEMICALS, Sector.OTHER,
            ),
            FundamentalsPolicy.WORKING_CAPITAL_NORM_SUPPRESSED_SECTORS,
        )
    }

    @Test
    fun `other income is evaluated for a non financial sector and suppressed for the financials`() {
        val suppressed = FundamentalsPolicy.OTHER_INCOME_SUPPRESSED_SECTORS
        assertEquals(setOf(Sector.BANKING, Sector.NBFC, Sector.INSURANCE), suppressed)
        for (sector in Sector.entries - suppressed) {
            val context = testContext(sector = sector, derived = distressed)

            assertEquals(1, otherIncomeRule.evaluate(context).size, "${sector.name} must be evaluated")
            assertNull(
                otherIncomeRule.explainSkip(context),
                "${sector.name} ran on the data and tripped, so it is not a skip",
            )
        }
    }

    @Test
    fun `a bank never produces a debt flag and no financial sector produces a coverage flag`() {
        val bank = testContext(sector = Sector.BANKING, derived = distressed)

        assertTrue(debtRule.evaluate(bank).isEmpty())
        assertTrue(Sector.BANKING !in FundamentalsPolicy.DEBT_TO_EQUITY_LEVEL_CEILING,
            "plan.md §7.3 removes debt/equity for a bank rather than moving its band")
        for (sector in FundamentalsPolicy.INTEREST_COVERAGE_SUPPRESSED_SECTORS) {
            val context = testContext(sector = sector, derived = distressed)
            assertTrue(coverageRule.evaluate(context).isEmpty(), sector.name)
        }
        assertNull(
            FundamentalsPolicy.DERIVED_PE_BAND[Sector.OTHER],
            "an unresolved sector has no band to compare a multiple against",
        )
    }
}