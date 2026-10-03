package com.mia.app.domain.redflag.fundamentals

import com.mia.app.domain.model.CashFlowDerived
import com.mia.app.domain.model.Confidence
import com.mia.app.domain.model.Evidence
import com.mia.app.domain.model.FlagTab
import com.mia.app.domain.model.QuarterRecord
import com.mia.app.domain.model.Ratios
import com.mia.app.domain.model.RedFlag
import com.mia.app.domain.model.Sector
import com.mia.app.domain.model.Source
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The contract every produced flag has to satisfy, checked for every flag every rule can produce.
 *
 * plan.md §1.4 rule 3 and §8.3, plus spec §11:
 *
 * * every figure shows a source and an as-of date;
 * * every flag answers "how do you know?" with its evidence list;
 * * a title is a factual sentence containing numbers — never "this company is risky" and never a
 *   statement about the future;
 * * the threshold that tripped is shown, with the value that tripped it.
 *
 * `RedFlag.init` already refuses a flag with empty evidence, so that is not re-asserted here as a
 * rule-level obligation. What *is* asserted is that no row is blank, no row is undated, and every
 * title carries a digit.
 */
class FundamentalsEvidenceCompletenessTest {

    /** One firing context per rule, so every rule's populated branch is covered. */
    private val cases: List<Triple<String, FundamentalsRule, com.mia.app.domain.redflag.RedFlagContext>> =
        listOf(
            Triple(
                FundamentalsFlagIds.CFO_NEGATIVE_WHILE_PAT_POSITIVE,
                CfoNegativeWhilePatPositiveRule(),
                testContext(
                    derived = CashFlowDerived(
                        cfoByYear = mapOf(2024 to -10.0, 2025 to -20.0, 2026 to -30.0),
                        patByYear = mapOf(2024 to 10.0, 2025 to 20.0, 2026 to 30.0),
                    ),
                ),
            ),
            Triple(
                FundamentalsFlagIds.RECEIVABLES_GROWING_FASTER_THAN_REVENUE,
                ReceivablesGrowingFasterThanRevenueRule(
                    RatiosCardSeries(debtorDays = mapOf(2025 to 20.0, 2026 to 60.0)),
                ),
                testContext(
                    sector = Sector.IT,
                    derived = CashFlowDerived(revenueByYear = mapOf(2025 to 100.0, 2026 to 140.0)),
                ),
            ),
            Triple(
                FundamentalsFlagIds.INVENTORY_DAYS_RISING,
                InventoryDaysRisingRule(
                    RatiosCardSeries(
                        inventoryDays = mapOf(2025 to 20.0, 2026 to 60.0),
                        daysPayable = mapOf(2025 to 10.0, 2026 to 12.0),
                    ),
                ),
                testContext(sector = Sector.PHARMA),
            ),
            Triple(
                FundamentalsFlagIds.OTHER_INCOME_AS_PCT_OF_PAT,
                OtherIncomeAsPctOfPatRule(),
                testContext(
                    sector = Sector.IT,
                    derived = CashFlowDerived(
                        otherIncomeByYear = mapOf(2026 to 900.0),
                        patByYear = mapOf(2026 to 1_000.0),
                        ebitByYear = mapOf(2026 to 5_000.0),
                    ),
                ),
            ),
            Triple(
                FundamentalsFlagIds.DEBT_TO_EQUITY_SPIKE,
                DebtToEquitySpikeRule(),
                testContext(
                    sector = Sector.IT,
                    derived = CashFlowDerived(
                        debtByYear = mapOf(2025 to 10_000.0, 2026 to 30_000.0),
                        equityByYear = mapOf(2025 to 10_000.0, 2026 to 10_000.0),
                    ),
                ),
            ),
            Triple(
                FundamentalsFlagIds.INTEREST_COVERAGE_BELOW_2,
                InterestCoverageBelowTwoRule(),
                testContext(
                    sector = Sector.CEMENT,
                    derived = CashFlowDerived(
                        ebitByYear = mapOf(2026 to 1_700.0),
                        interestByYear = mapOf(2026 to 1_000.0),
                        otherIncomeByYear = mapOf(2026 to 200.0),
                    ),
                ),
            ),
            Triple(
                FundamentalsFlagIds.GOODWILL_ABOVE_20PCT_ASSETS,
                GoodwillAboveTwentyPercentAssetsRule(),
                testContext(
                    sector = Sector.PHARMA,
                    derived = CashFlowDerived(
                        goodwillByYear = mapOf(2026 to 40_000.0),
                        totalAssetsByYear = mapOf(2026 to 100_000.0),
                    ),
                ),
            ),
            Triple(
                FundamentalsFlagIds.PROMOTER_PLEDGING,
                PromoterPledgingRule(),
                testContext(derived = CashFlowDerived(promoterPledgePercent = 12.0)),
            ),
            Triple(
                FundamentalsFlagIds.LOW_PE_WITH_FALLING_RETURNS,
                LowPeWithFallingReturnsRule(
                    RatiosCardSeries(rocePercent = mapOf(2024 to 40.0, 2025 to 30.0, 2026 to 20.0)),
                ),
                testContext(
                    sector = Sector.IT,
                    ratios = Ratios(
                        symbol = "TESTCO",
                        sector = Sector.IT,
                        asOf = Golden.CAPTURE_DATE,
                        source = Source.SCREENER_IN,
                        confidence = Confidence.SINGLE,
                        pe = 12.0,
                    ),
                ),
            ),
            Triple(
                FundamentalsFlagIds.PE_RISING_WHILE_EARNINGS_FALL,
                PeRisingWhileEarningsFallRule(),
                peTestContext(),
            ),
        )

    @Test
    fun `every rule produces a flag on its firing context and each flag is complete`() {
        assertEquals(
            FundamentalsRedFlagRules.ruleIds.toSet(),
            cases.map { it.first }.toSet(),
            "every registered rule needs a firing context in this file, or its evidence is unchecked",
        )
        for ((id, rule, context) in cases) {
            val flags = rule.evaluate(context)

            assertTrue(flags.isNotEmpty(), "$id produced no flag on its firing context")
            for (flag in flags) {
                assertEquals(id, flag.id)
                assertEvidenceComplete(flag)
                assertEquals(FlagTab.FUNDAMENTALS, flag.tab)
                assertTrue(flag.threshold.isNotBlank(), "$id: blank threshold")
            }
        }
    }

    @Test
    fun `the threshold row restates the trip condition and the evidence contains its numbers`() {
        for ((id, rule, context) in cases) {
            val flag = rule.evaluate(context).single()
            val thresholdRow = flag.evidence.last { it.label == "Threshold" }

            assertEquals(flag.threshold, thresholdRow.value, id)
            assertTrue(thresholdRow.value.any { it.isDigit() }, "$id: a threshold with no number in it")
            assertTrue(
                flag.evidence.any { row -> row.value.any { it.isDigit() } },
                "$id: no evidence row shows a number",
            )
        }
    }

    @Test
    fun `evidence rows come from a fundamentals source or from our own arithmetic`() {
        for ((id, rule, context) in cases) {
            for (flag in rule.evaluate(context)) {
                for (row in flag.evidence) {
                    assertTrue(
                        row.source == Source.SCREENER_IN || row.source == Source.DERIVED,
                        "$id / ${row.label}: ${row.source} is not a fundamentals source",
                    )
                    assertTrue(row.asOf != null, "$id / ${row.label}: no as-of date")
                }
            }
        }
    }

    @Test
    fun `statement rows are dated to the fiscal year and live rows to the scrape date`() {
        val cfoFlag = CfoNegativeWhilePatPositiveRule().evaluate(
            testContext(
                derived = CashFlowDerived(
                    cfoByYear = mapOf(2025 to -20.0, 2026 to -30.0),
                    patByYear = mapOf(2025 to 20.0, 2026 to 30.0),
                ),
            ),
        ).single()

        assertEquals(LocalDate.of(2026, 3, 31), row(cfoFlag, "Operating cash flow (FY2026)").asOf)
        assertEquals(LocalDate.of(2025, 3, 31), row(cfoFlag, "Operating cash flow (FY2025)").asOf)

        val peFlag = LowPeWithFallingReturnsRule(
            RatiosCardSeries(rocePercent = mapOf(2024 to 40.0, 2025 to 30.0, 2026 to 20.0)),
        ).evaluate(
            testContext(
                sector = Sector.IT,
                ratios = Ratios(
                    symbol = "TESTCO",
                    sector = Sector.IT,
                    asOf = Golden.CAPTURE_DATE,
                    source = Source.SCREENER_IN,
                    confidence = Confidence.SINGLE,
                    pe = 12.0,
                ),
            ),
        ).single()

        assertEquals(Golden.CAPTURE_DATE, row(peFlag, "Stock P/E").asOf, "a live scrape value")
        assertEquals(LocalDate.of(2026, 3, 31), row(peFlag, "ROCE % (FY2026)").asOf, "a fiscal figure")
    }

    @Test
    fun `titles state a comparison and nothing else`() {
        for ((id, rule, context) in cases) {
            for (flag in rule.evaluate(context)) {
                assertTrue(flag.title.any { it.isDigit() }, "$id: ${flag.title}")
                assertTrue(
                    !flag.title.contains("this company", ignoreCase = true),
                    "$id: ${flag.title}",
                )
                assertTrue(flag.title.trim().endsWith("."), "$id: ${flag.title}")
                assertTrue(flag.title.length <= 400, "$id: title is ${flag.title.length} characters")
            }
        }
    }

    private fun assertEvidenceComplete(flag: RedFlag) {
        assertTrue(flag.evidence.isNotEmpty(), "${flag.id}: no evidence")
        for (row: Evidence in flag.evidence) assertEvidenceRow(flag, row)
    }

    private fun row(flag: RedFlag, label: String): Evidence = flag.evidence.first { it.label == label }

    /**
     * Nine calendar quarters whose EPS is flat then falls, plus a close at each of the two
     * quarter-end dates the rule compares. Every EPS is strictly positive so the comparison is
     * defined, and the latest close is above the earlier one per unit of falling earnings.
     */
    private fun peTestContext() = testContext(
        sector = Sector.IT,
        quarters = (0 until 9).map { index ->
            QuarterRecord(
                year = 2024 + index / 4,
                quarter = index % 4 + 1,
                eps = if (index < 5) 6.0 else 7.2 - (index - 4) * 1.2,
            )
        },
        candles = listOf(
            dailyCandle(LocalDate.of(2026, 3, 31), 190.0),
            dailyCandle(LocalDate.of(2025, 3, 31), 264.0),
        ),
    )
}