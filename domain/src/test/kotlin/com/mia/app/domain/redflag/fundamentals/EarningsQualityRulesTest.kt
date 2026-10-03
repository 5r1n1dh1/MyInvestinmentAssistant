package com.mia.app.domain.redflag.fundamentals

import com.mia.app.domain.model.CashFlowDerived
import com.mia.app.domain.model.Evidence
import com.mia.app.domain.model.FlagGroup
import com.mia.app.domain.model.FlagSeverity
import com.mia.app.domain.model.FlagTab
import com.mia.app.domain.model.Measure
import com.mia.app.domain.model.Sector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The four earnings-quality rules: one "fires", one "quiet" and one "cannot evaluate" case each,
 * plus the real fixtures.
 *
 * The three-case shape is the contract of this whole phase. plan.md §12's "silent-empty is
 * indistinguishable from no red flags" has a corollary that is easy to forget: a rule that
 * returns nothing because its input was absent must be **distinguishable from** a rule that ran
 * and found nothing. That is what [FundamentalsRule.explainSkip] is for, so every
 * cannot-evaluate case here asserts both halves — no flag, and a reason.
 */
class EarningsQualityRulesTest {

    private val cfoRule = CfoNegativeWhilePatPositiveRule()

    // ------------------------------------------- CFO negative while PAT positive

    @Test
    fun `two consecutive years of negative operating cash flow with positive profit is a medium flag`() {
        val context = testContext(
            derived = CashFlowDerived(
                cfoByYear = mapOf(2023 to 400.0, 2024 to 900.0, 2025 to -3_180.0, 2026 to -12_400.0),
                patByYear = mapOf(2023 to 500.0, 2024 to 800.0, 2025 to 11_240.0, 2026 to 8_910.0),
            ),
        )

        val flag = cfoRule.evaluate(context).single()

        assertEquals(FundamentalsFlagIds.CFO_NEGATIVE_WHILE_PAT_POSITIVE, flag.id)
        assertEquals(FlagGroup.EARNINGS_QUALITY, flag.group)
        assertEquals(FlagTab.FUNDAMENTALS, flag.tab)
        assertEquals(FlagSeverity.MEDIUM, flag.severity, "exactly two consecutive years")
        assertTrue(flag.title.contains("2 consecutive"), flag.title)
        assertTrue(flag.title.contains("FY2026"), flag.title)
        assertTrue(flag.title.contains("₹8,910 Cr"), flag.title)
        // 2023 and 2024 have positive operating cash flow, so the run is exactly 2025-2026.
        val labels = flag.evidence.map { it.label }
        assertTrue("Operating cash flow (FY2026)" in labels)
        assertTrue("Profit after tax (FY2026)" in labels)
        assertTrue("Operating cash flow (FY2025)" in labels)
        assertTrue("Operating cash flow (FY2023)" !in labels, flag.evidence.toString())
        assertEquals("2", flag.evidence.first { it.label == "Consecutive fiscal years meeting the condition" }.value)
        assertEquals(FiscalDates.fy2026, flag.evidence.first { it.label == "Operating cash flow (FY2026)" }.asOf)
    }

    @Test
    fun `three consecutive years of negative operating cash flow is a high flag`() {
        val context = testContext(
            derived = CashFlowDerived(
                cfoByYear = mapOf(2024 to -100.0, 2025 to -200.0, 2026 to -300.0),
                patByYear = mapOf(2024 to 10.0, 2025 to 20.0, 2026 to 30.0),
            ),
        )

        assertEquals(FlagSeverity.HIGH, cfoRule.evaluate(context).single().severity)
    }

    @Test
    fun `a single negative year with positive profit does not fire`() {
        // TITAN's real FY2025: CFO -541 against PAT 3,337, with FY2024 and FY2026 both positive.
        val context = titanContext()

        assertTrue(cfoRule.evaluate(context).isEmpty())
        assertNull(
            cfoRule.explainSkip(context),
            "the check ran on real data and did not trip, so it is not a skip",
        )
    }

    @Test
    fun `a gap in the cash flow statement breaks the run rather than being stepped over`() {
        val context = testContext(
            derived = CashFlowDerived(
                cfoByYear = mapOf(2024 to -100.0, 2026 to -200.0),
                patByYear = mapOf(2024 to 10.0, 2026 to 20.0),
            ),
        )

        assertTrue(
            cfoRule.evaluate(context).isEmpty(),
            "2024 and 2026 both qualify but are not adjacent, so the run is one year long",
        )
        assertNull(cfoRule.explainSkip(context))
    }

    @Test
    fun `cfo rule cannot evaluate without both lines and says which are missing`() {
        val context = testContext(
            derived = CashFlowDerived(cfoByYear = mapOf(2025 to -1.0, 2026 to -2.0)),
        )

        assertTrue(cfoRule.evaluate(context).isEmpty())
        val reason = assertNotNull(cfoRule.explainSkip(context))
        assertTrue(reason.contains("Cash from Operating Activity"), reason)
        assertTrue(reason.contains("Net Profit"), reason)
    }

    @Test
    fun `reliance stays quiet on cash flow quality with its real figures`() {
        val context = relianceContext()

        assertEquals(192_113.0, context.derived.cfoByYear[2026])
        assertEquals(95_754.0, context.derived.patByYear[2026])
        assertTrue(cfoRule.evaluate(context).isEmpty(), "positive operating cash flow, positive profit")
    }

    // ------------------------------------------------ receivables via debtor days

    @Test
    fun `rising days sales outstanding fires at low and carries the revenue clause`() {
        val rule = ReceivablesGrowingFasterThanRevenueRule(
            RatiosCardSeries(debtorDays = mapOf(2024 to 26.0, 2025 to 31.0, 2026 to 44.0)),
        )
        val context = testContext(
            sector = Sector.IT,
            derived = CashFlowDerived(
                revenueByYear = mapOf(2025 to 1_000.0, 2026 to 1_082.0),
            ),
        )

        val flag = rule.evaluate(context).single()

        assertEquals(FundamentalsFlagIds.RECEIVABLES_GROWING_FASTER_THAN_REVENUE, flag.id)
        assertEquals(FlagGroup.EARNINGS_QUALITY, flag.group)
        assertEquals(FlagSeverity.LOW, flag.severity, "+13 days is the LOW band")
        assertTrue(flag.title.contains("31 to 44 days"), flag.title)
        assertTrue(flag.title.contains("FY2026"), flag.title)
        assertTrue(flag.title.contains("revenue grew 8.2 %"), flag.title)
        val evidence = flag.evidence.associate { it.label to it.value }
        assertEquals("44 days", evidence["Days sales outstanding (FY2026)"])
        assertEquals("31 days", evidence["Days sales outstanding (FY2025)"])
        assertEquals("+13 days", evidence["Change"])
        assertEquals("+8.2 %", evidence["Revenue growth (FY2026)"])
        assertTrue(
            evidence.getValue("Measure used").contains("no trade-receivables line"),
            evidence.toString(),
        )
    }

    @Test
    fun `days sales outstanding severity steps up at twenty five and forty five days`() {
        fun severityFor(delta: Double): FlagSeverity {
            val rule = ReceivablesGrowingFasterThanRevenueRule(
                RatiosCardSeries(debtorDays = mapOf(2025 to 30.0, 2026 to 30.0 + delta)),
            )
            return rule.evaluate(testContext(sector = Sector.IT)).single().severity
        }

        assertEquals(FlagSeverity.LOW, severityFor(13.0))
        assertEquals(FlagSeverity.MEDIUM, severityFor(25.0))
        assertEquals(FlagSeverity.HIGH, severityFor(45.0))
        assertTrue(
            ReceivablesGrowingFasterThanRevenueRule(
                RatiosCardSeries(debtorDays = mapOf(2025 to 30.0, 2026 to 39.9)),
            ).evaluate(testContext(sector = Sector.IT)).isEmpty(),
            "9.9 days is below the ten-day floor",
        )
    }

    @Test
    fun `the revenue clause is omitted rather than left dangling`() {
        val rule = ReceivablesGrowingFasterThanRevenueRule(
            RatiosCardSeries(debtorDays = mapOf(2025 to 20.0, 2026 to 40.0)),
        )
        val context = testContext(sector = Sector.IT, derived = CashFlowDerived())

        val flag = rule.evaluate(context).single()

        assertTrue(!flag.title.contains("while revenue"), flag.title)
        assertTrue(flag.title.endsWith("."), flag.title)
        assertTrue(
            flag.evidence.none { it.label == "Revenue growth (FY2026)" },
            "no growth row without the input",
        )
    }

    @Test
    fun `reliance stays quiet on days sales outstanding and is reported as sector suppressed`() {
        val rule = ReceivablesGrowingFasterThanRevenueRule(Golden.relianceCard)

        assertEquals(20.0, Golden.relianceCard.debtorDays[2026])
        assertEquals(16.0, Golden.relianceCard.debtorDays[2025])
        assertTrue(rule.evaluate(relianceContext()).isEmpty(), "+4 days, below the ten-day floor")
        // RELIANCE's sector is unresolved, so the rule is suppressed rather than evaluated, and
        // spec §6.2 requires that to be visible rather than silent.
        val reason = assertNotNull(rule.explainSkip(relianceContext()))
        assertTrue(reason.contains("unresolved"), reason)
        assertTrue(reason.contains("industry norms"), reason)
    }

    @Test
    fun `the receivables rule cannot evaluate without a debtor days series and never guesses`() {
        val rule = ReceivablesGrowingFasterThanRevenueRule()
        val context = testContext(sector = Sector.IT, derived = Golden.relianceDerived)

        assertTrue(rule.evaluate(context).isEmpty())
        val reason = assertNotNull(rule.explainSkip(context))
        assertTrue(reason.contains("Debtor Days"), reason)
        assertTrue(reason.contains("No receivables figure is derived from revenue"), reason)
    }

    @Test
    fun `the receivables rule is suppressed for sectors whose credit terms are structurally long`() {
        val rule = ReceivablesGrowingFasterThanRevenueRule(
            RatiosCardSeries(debtorDays = mapOf(2025 to 30.0, 2026 to 90.0)),
        )
        val context = testContext(sector = Sector.METALS)

        assertTrue(rule.evaluate(context).isEmpty(), "a 60-day move in metals says nothing on its own")
        val reason = assertNotNull(rule.explainSkip(context))
        assertTrue(reason.contains("Metals"), reason)
        assertTrue(reason.contains("industry norms"), reason)
    }

    @Test
    fun `the receivables rule needs two adjacent years`() {
        val rule = ReceivablesGrowingFasterThanRevenueRule(
            RatiosCardSeries(debtorDays = mapOf(2024 to 30.0, 2026 to 60.0)),
        )
        val context = testContext(sector = Sector.IT)

        assertTrue(rule.evaluate(context).isEmpty())
        assertTrue(assertNotNull(rule.explainSkip(context)).contains("consecutive"))
    }

    // ------------------------------------------------------------ inventory days

    @Test
    fun `rising inventory days fires and adds the days payable rows`() {
        val rule = InventoryDaysRisingRule(
            RatiosCardSeries(
                inventoryDays = mapOf(2025 to 217.0, 2026 to 245.0),
                daysPayable = mapOf(2025 to 15.0, 2026 to 12.0),
            ),
        )

        val flag = rule.evaluate(testContext(sector = Sector.PHARMA)).single()

        assertEquals(FundamentalsFlagIds.INVENTORY_DAYS_RISING, flag.id)
        assertEquals(FlagSeverity.MEDIUM, flag.severity, "+28 days crosses the 25-day band")
        assertTrue(flag.title.contains("217 to 245 days"), flag.title)
        assertTrue(flag.title.contains("days payable moved from 15 to 12"), flag.title)
        val evidence = flag.evidence.associate { it.label to it.value }
        assertEquals("245 days", evidence["Inventory days (FY2026)"])
        assertEquals("217 days", evidence["Inventory days (FY2025)"])
        assertEquals("+28 days", evidence["Change"])
        assertEquals("12 days", evidence["Days payable (FY2026)"])
        assertEquals("15 days", evidence["Days payable (FY2025)"])
    }

    @Test
    fun `titan stays quiet on inventory days with its real 217 to 222 series`() {
        val rule = InventoryDaysRisingRule(Golden.titanCard)

        assertEquals(217.0, Golden.titanCard.inventoryDays[2025])
        assertEquals(222.0, Golden.titanCard.inventoryDays[2026])
        assertTrue(
            rule.evaluate(titanContext()).isEmpty(),
            "+5 days on a series that ranges over 169 to 222 is the noise floor, not a finding",
        )
    }

    @Test
    fun `the inventory rule cannot evaluate on a services company whose card has no inventory days`() {
        val rule = InventoryDaysRisingRule(Golden.tcsCard)
        val context = tcsContext()

        assertTrue(rule.evaluate(context).isEmpty())
        val reason = assertNotNull(rule.explainSkip(context))
        assertTrue(reason.contains("Inventory Days"), reason)
        assertTrue(reason.contains("cost of goods"), reason)
    }

    @Test
    fun `the inventory rule is suppressed for an unresolved sector`() {
        val rule = InventoryDaysRisingRule(
            RatiosCardSeries(inventoryDays = mapOf(2025 to 100.0, 2026 to 160.0)),
        )
        val context = testContext(sector = Sector.OTHER)

        assertTrue(rule.evaluate(context).isEmpty())
        assertTrue(assertNotNull(rule.explainSkip(context)).contains("unresolved"))
    }

    // ------------------------------------------------- other income as a share of PAT

    @Test
    fun `other income above thirty percent of profit is a medium flag and above fifty is high`() {
        val rule = OtherIncomeAsPctOfPatRule()

        val medium = rule.evaluate(
            testContext(derived = CashFlowDerived(otherIncomeByYear = mapOf(2026 to 350.0), patByYear = mapOf(2026 to 1_000.0))),
        ).single()
        val high = rule.evaluate(
            testContext(derived = CashFlowDerived(otherIncomeByYear = mapOf(2026 to 600.0), patByYear = mapOf(2026 to 1_000.0))),
        ).single()

        assertEquals(FundamentalsFlagIds.OTHER_INCOME_AS_PCT_OF_PAT, medium.id)
        assertEquals(FlagSeverity.MEDIUM, medium.severity)
        assertEquals(FlagSeverity.HIGH, high.severity)
        assertTrue(medium.title.contains("35.0 % of profit came from items outside operations"), medium.title)
        val evidence = medium.evidence.associate { it.label to it.value }
        assertEquals("₹350 Cr", evidence["Other income (FY2026)"])
        assertEquals("₹1,000 Cr", evidence["Profit after tax (FY2026)"])
        assertEquals("35.0 %", evidence["Other income as % of PAT"])
        assertTrue(evidence.getValue("Definition").contains("not its quality"))
    }

    @Test
    fun `other income exactly at thirty percent does not fire`() {
        val rule = OtherIncomeAsPctOfPatRule()

        assertTrue(
            rule.evaluate(
                testContext(
                    derived = CashFlowDerived(otherIncomeByYear = mapOf(2026 to 300.0), patByYear = mapOf(2026 to 1_000.0)),
                ),
            ).isEmpty(),
            "the spec's threshold is 'exceeds 30%', not 'reaches 30%'",
        )
    }

    @Test
    fun `tcs stays quiet on other income because it published a negative figure`() {
        val rule = OtherIncomeAsPctOfPatRule()
        val context = tcsContext()

        assertEquals(-124.0, context.derived.otherIncomeByYear[2026])
        assertTrue(rule.evaluate(context).isEmpty())
        assertNull(rule.explainSkip(context))
    }

    @Test
    fun `other income is suppressed for a bank whose own ratio is 185 percent`() {
        val rule = OtherIncomeAsPctOfPatRule()
        val context = hdfcBankContext()

        assertEquals(146_848.0, context.derived.otherIncomeByYear[2026])
        assertEquals(79_219.0, context.derived.patByYear[2026])
        assertTrue(
            rule.evaluate(context).isEmpty(),
            "185 % would fire anywhere else; for a bank non-operating income is structural",
        )
        val reason = assertNotNull(rule.explainSkip(context))
        assertTrue(reason.contains("Banking"), reason)
        assertTrue(reason.contains("§7.3"), reason)
    }

    @Test
    fun `the other income rule cannot evaluate without both lines`() {
        val rule = OtherIncomeAsPctOfPatRule()
        val context = testContext(derived = CashFlowDerived(otherIncomeByYear = mapOf(2026 to 500.0)))

        assertTrue(rule.evaluate(context).isEmpty())
        assertTrue(assertNotNull(rule.explainSkip(context)).contains("Net Profit"))
    }

    @Test
    fun `a year with zero profit is passed over and the previous usable year is used`() {
        val rule = OtherIncomeAsPctOfPatRule()
        val context = testContext(
            derived = CashFlowDerived(
                otherIncomeByYear = mapOf(2025 to 900.0, 2026 to 0.0),
                patByYear = mapOf(2025 to 100.0, 2026 to 0.0),
            ),
        )

        // FY2026 cannot produce a share, so the flag is reported on FY2025 rather than dropped.
        val flag = rule.evaluate(context).single()

        assertTrue(flag.title.contains("in FY2025"), flag.title)
        assertEquals(
            FiscalDates.fy2025,
            flag.evidence.first { it.label == "Profit after tax (FY2025)" }.asOf,
        )
        assertNull(rule.explainSkip(context), "it ran on FY2025 and tripped, so it is not a skip")
    }

    /** Every earnings-quality flag is a fundamentals-tab flag with real evidence. */
    @Test
    fun `every earnings quality flag is fully evidenced`() {
        val flags = FundamentalsRedFlagRules.all(
            RatiosCardSeries(
                debtorDays = mapOf(2025 to 20.0, 2026 to 60.0),
                inventoryDays = mapOf(2025 to 100.0, 2026 to 140.0),
                daysPayable = mapOf(2025 to 15.0, 2026 to 15.0),
            ),
        )
            .filter { it.id in EARNINGS_QUALITY_IDS }
            .flatMap {
                it.evaluate(
                    testContext(
                        sector = Sector.IT,
                        derived = CashFlowDerived(
                            cfoByYear = mapOf(2025 to -1.0, 2026 to -2.0),
                            patByYear = mapOf(2025 to 10.0, 2026 to 20.0),
                            otherIncomeByYear = mapOf(2026 to 900.0),
                            revenueByYear = mapOf(2025 to 100.0, 2026 to 140.0),
                        ),
                    ),
                )
            }

        assertEquals(4, flags.size, "all four earnings-quality rules fire on this context")
        assertTrue(flags.all { it.group == FlagGroup.EARNINGS_QUALITY })
        assertTrue(flags.all { it.tab == FlagTab.FUNDAMENTALS })
        for (flag in flags) {
            assertTrue(flag.evidence.isNotEmpty())
            for (row in flag.evidence) assertEvidenceRow(flag, row)
            assertTrue(flag.title.any { it.isDigit() }, flag.title)
        }
    }
}

/** The four earnings-quality ids, for the tests that filter the registry by group. */
internal val EARNINGS_QUALITY_IDS = setOf(
    FundamentalsFlagIds.CFO_NEGATIVE_WHILE_PAT_POSITIVE,
    FundamentalsFlagIds.RECEIVABLES_GROWING_FASTER_THAN_REVENUE,
    FundamentalsFlagIds.INVENTORY_DAYS_RISING,
    FundamentalsFlagIds.OTHER_INCOME_AS_PCT_OF_PAT,
)

/** Shared evidence assertions, so every rule's rows are held to the same contract. */
internal fun assertEvidenceRow(flag: com.mia.app.domain.model.RedFlag, row: Evidence) {
    assertTrue(row.label.isNotBlank(), "${flag.id}: blank evidence label")
    assertTrue(row.value.isNotBlank(), "${flag.id} / ${row.label}: blank evidence value")
    assertTrue(row.asOf != null, "${flag.id} / ${row.label}: evidence without an as-of date")
}

/** Fiscal-year-end dates the assertions above compare against. */
internal object FiscalDates {
    val fy2026: java.time.LocalDate = java.time.LocalDate.of(2026, 3, 31)
    val fy2025: java.time.LocalDate = java.time.LocalDate.of(2025, 3, 31)
}