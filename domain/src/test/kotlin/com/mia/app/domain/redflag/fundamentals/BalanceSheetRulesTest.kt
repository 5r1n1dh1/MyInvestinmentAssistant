package com.mia.app.domain.redflag.fundamentals

import com.mia.app.domain.model.CashFlowDerived
import com.mia.app.domain.model.Confidence
import com.mia.app.domain.model.FlagGroup
import com.mia.app.domain.model.FlagSeverity
import com.mia.app.domain.model.Ratios
import com.mia.app.domain.model.Sector
import com.mia.app.domain.model.Source
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The four balance-sheet rules, including the two that can never fire.
 *
 * The two unsourceable rules are tested the way the spec demands (`docs/spec/redflags-spec.md`
 * §12: *"the three suppressed flags' unit tests assert evaluate() == null"*) — and also tested
 * against a context that **does** publish the input, because a rule that cannot ever fire is
 * indistinguishable from a rule that was never written. Both halves are needed: the first proves
 * nothing is faked, the second proves the arithmetic is right the day a source supplies the line.
 */
class BalanceSheetRulesTest {

    private val debtRule = DebtToEquitySpikeRule()
    private val coverageRule = InterestCoverageBelowTwoRule()

    // ------------------------------------------------------- debt/equity spike

    @Test
    fun `an absolute jump of half a point in debt to equity fires`() {
        val context = testContext(
            sector = Sector.IT,
            derived = CashFlowDerived(
                debtByYear = mapOf(2025 to 10_000.0, 2026 to 22_000.0),
                equityByYear = mapOf(2025 to 20_000.0, 2026 to 20_000.0),
            ),
        )

        val flag = debtRule.evaluate(context).single()

        assertEquals(FundamentalsFlagIds.DEBT_TO_EQUITY_SPIKE, flag.id)
        assertEquals(FlagGroup.BALANCE_SHEET, flag.group)
        assertTrue(flag.title.contains("rose from 0.50× to 1.10×"), flag.title)
        assertTrue(flag.title.contains("+0.60"), flag.title)
        assertTrue(flag.title.contains("FY2025"), flag.title)
        assertTrue(flag.title.contains("FY2026"), flag.title)
        val evidence = flag.evidence.associate { it.label to it.value }
        assertEquals("₹22,000 Cr", evidence["Borrowings (FY2026)"])
        assertEquals("₹20,000 Cr", evidence["Shareholders' equity, equity capital plus reserves (FY2026)"])
        assertEquals("1.10×", evidence["Debt/equity (FY2026)"])
        assertEquals("0.50×", evidence["Debt/equity (FY2025)"])
        assertEquals("1 to 2 — high", evidence["Book band (FY2026)"])
        assertTrue(evidence.getValue("Industry benchmark (sector)").contains("derived"), evidence.toString())
    }

    @Test
    fun `a fifty percent relative jump fires when the earlier year is above the floor`() {
        val flag = debtRule.evaluate(
            testContext(
                sector = Sector.PHARMA,
                derived = CashFlowDerived(
                    debtByYear = mapOf(2025 to 3_000.0, 2026 to 4_600.0),
                    equityByYear = mapOf(2025 to 10_000.0, 2026 to 10_000.0),
                ),
            ),
        ).single()

        assertTrue(flag.title.contains("rose from 0.30× to 0.46×"), flag.title)
    }

    @Test
    fun `a large relative jump from a tiny base does not fire`() {
        // 0.02 -> 0.05 is +150 %, and the book's four bands call both of them "very conservative".
        assertTrue(
            debtRule.evaluate(
                testContext(
                    sector = Sector.PHARMA,
                    derived = CashFlowDerived(
                        debtByYear = mapOf(2025 to 200.0, 2026 to 500.0),
                        equityByYear = mapOf(2025 to 10_000.0, 2026 to 10_000.0),
                    ),
                ),
            ).isEmpty(),
        )
    }

    @Test
    fun `a level above the sector ceiling fires without a spike`() {
        // IT ceiling is 0.5 (the book calls software leverage "near zero").
        val flag = debtRule.evaluate(
            testContext(
                sector = Sector.IT,
                derived = CashFlowDerived(
                    debtByYear = mapOf(2025 to 6_000.0, 2026 to 6_400.0),
                    equityByYear = mapOf(2025 to 10_000.0, 2026 to 10_000.0),
                ),
            ),
        ).single()

        assertTrue(flag.title.contains("above the 0.5 ceiling"), flag.title)
        assertTrue(flag.title.contains("Information technology"), flag.title)
        assertEquals(FlagSeverity.MEDIUM, flag.severity)
    }

    @Test
    fun `a level above two is the high severity`() {
        // AUTO's ceiling is 1.5, so 2.6 crosses it; anything above the book's 2.0 is HIGH.
        val flag = debtRule.evaluate(
            testContext(
                sector = Sector.AUTO,
                derived = CashFlowDerived(
                    debtByYear = mapOf(2025 to 12_000.0, 2026 to 13_000.0),
                    equityByYear = mapOf(2025 to 5_000.0, 2026 to 5_000.0),
                ),
            ),
        ).single()

        assertEquals(FlagSeverity.HIGH, flag.severity)
        assertTrue(
            flag.evidence.first { it.label == "Book band (FY2026)" }.value
                .startsWith("Above 2.0"),
            flag.evidence.first { it.label == "Book band (FY2026)" }.value,
        )
        assertTrue(flag.title.contains("2.60×"), flag.title)
    }

    @Test
    fun `reliance stays quiet on debt to equity with its real zero point 44 to 45 ratio`() {
        val context = relianceContext()

        assertEquals(374_313.0 / 843_200.0, com.mia.app.domain.ratios.RatioComputation.debtToEquity(Golden.relianceDerived, Sector.OTHER, 2025).value())
        assertEquals(402_962.0 / 904_030.0, com.mia.app.domain.ratios.RatioComputation.debtToEquity(Golden.relianceDerived, Sector.OTHER, 2026).value())
        assertTrue(debtRule.evaluate(context).isEmpty(), "+0.0018 is not a spike")
        assertNull(debtRule.explainSkip(context))
    }

    @Test
    fun `tcs stays quiet on debt to equity with its real zero point 10 to 11 ratio`() {
        assertEquals(9_392.0 / 94_756.0, com.mia.app.domain.ratios.RatioComputation.debtToEquity(Golden.tcsDerived, Sector.IT, 2025).value())
        assertEquals(11_283.0 / 107_240.0, com.mia.app.domain.ratios.RatioComputation.debtToEquity(Golden.tcsDerived, Sector.IT, 2026).value())
        assertTrue(debtRule.evaluate(tcsContext()).isEmpty(), "no spike, and 0.11 is under the IT 0.5 ceiling")
    }

    @Test
    fun `titan fires on its real debt to equity of one point nine five against a durables ceiling of one`() {
        val flag = debtRule.evaluate(titanContext()).single()

        assertEquals(FundamentalsFlagIds.DEBT_TO_EQUITY_SPIKE, flag.id)
        assertEquals(FlagGroup.BALANCE_SHEET, flag.group)
        assertTrue(flag.title.contains("above the 1.0 ceiling"), flag.title)
        assertTrue(flag.title.contains("Consumer durables"), flag.title)
        val evidence = flag.evidence.associate { it.label to it.value }
        assertEquals("₹30,621 Cr", evidence["Borrowings (FY2026)"])
        assertEquals("1.95×", evidence["Debt/equity (FY2026)"])
        assertEquals("1.79×", evidence["Debt/equity (FY2025)"])
        assertEquals("1 to 2 — high", evidence["Book band (FY2026)"])
    }

    @Test
    fun `the debt rule cannot evaluate without a balance sheet`() {
        val context = testContext(derived = CashFlowDerived())

        assertTrue(debtRule.evaluate(context).isEmpty())
        val reason = assertNotNull(debtRule.explainSkip(context))
        assertTrue(reason.contains("Borrowings"), reason)
        assertTrue(reason.contains("Equity Capital + Reserves"), reason)
    }

    @Test
    fun `the debt rule cannot evaluate when the two years are not adjacent`() {
        val rule = DebtToEquitySpikeRule()
        val context = testContext(
            sector = Sector.IT,
            derived = CashFlowDerived(
                debtByYear = mapOf(2024 to 10_000.0, 2026 to 30_000.0),
                equityByYear = mapOf(2024 to 10_000.0, 2026 to 10_000.0),
            ),
        )

        assertTrue(rule.evaluate(context).isEmpty())
        assertTrue(assertNotNull(rule.explainSkip(context)).contains("adjacent"))
    }

    // ------------------------------------------------------ interest coverage

    @Test
    fun `coverage below two fires at medium and below one at high`() {
        val weak = testContext(
            sector = Sector.CEMENT,
            derived = CashFlowDerived(
                ebitByYear = mapOf(2026 to 1_700.0),
                interestByYear = mapOf(2026 to 1_000.0),
                otherIncomeByYear = mapOf(2026 to 300.0),
            ),
        )
        val distressed = testContext(
            sector = Sector.CEMENT,
            derived = CashFlowDerived(
                ebitByYear = mapOf(2026 to 900.0),
                interestByYear = mapOf(2026 to 1_000.0),
            ),
        )

        val mediumFlag = coverageRule.evaluate(weak).single()
        assertEquals(FundamentalsFlagIds.INTEREST_COVERAGE_BELOW_2, mediumFlag.id)
        assertEquals(FlagGroup.BALANCE_SHEET, mediumFlag.group)
        assertEquals(FlagSeverity.MEDIUM, mediumFlag.severity)
        assertEquals(FlagSeverity.HIGH, coverageRule.evaluate(distressed).single().severity)
        assertTrue(mediumFlag.title.contains("covered interest 1.70×"), mediumFlag.title)
        assertTrue(mediumFlag.title.contains("Including other income the figure is 2.00×"), mediumFlag.title)
        val evidence = mediumFlag.evidence.associate { it.label to it.value }
        assertEquals("₹1,700 Cr", evidence["EBIT, operating profit plus depreciation (FY2026)"])
        assertEquals("₹1,000 Cr", evidence["Interest expense (FY2026)"])
        assertEquals("2.00×", evidence["Interest coverage including other income (FY2026)"])
        assertEquals("1 to 2 — weak", evidence["Book band"])
        assertEquals(
            FiscalDates.fy2026,
            mediumFlag.evidence.first { it.label == "Interest expense (FY2026)" }.asOf,
        )
        assertTrue(
            mediumFlag.evidence.first { it.label == "Debt maturity profile" }.value
                .startsWith("Not available from our sources"),
        )
    }

    @Test
    fun `reliance stays quiet on interest coverage with its real eight point seven five`() {
        val context = relianceContext()

        assertEquals(
            236_753.0 / 27_061.0,
            com.mia.app.domain.ratios.RatioComputation.interestCoverage(Golden.relianceDerived, Sector.OTHER).value(),
        )
        assertTrue(coverageRule.evaluate(context).isEmpty())
        assertNull(coverageRule.explainSkip(context))
    }

    @Test
    fun `tcs stays quiet on interest coverage with its real sixty three point five`() {
        val context = tcsContext()

        assertEquals(
            77_958.0 / 1_227.0,
            com.mia.app.domain.ratios.RatioComputation.interestCoverage(Golden.tcsDerived, Sector.IT).value(),
        )
        assertEquals(51.8, context.ratios.roe)
        assertTrue(coverageRule.evaluate(context).isEmpty())
    }

    @Test
    fun `the coverage rule cannot evaluate without an EBIT and an interest line`() {
        val context = testContext(
            derived = CashFlowDerived(ebitByYear = mapOf(2026 to 500.0)),
        )

        assertTrue(coverageRule.evaluate(context).isEmpty())
        val reason = assertNotNull(coverageRule.explainSkip(context))
        assertTrue(reason.contains("interest expense"), reason)
    }

    @Test
    fun `a year with zero interest expense is skipped rather than dividing by zero`() {
        // INFY's real FY2019 publishes Interest as 0.
        val context = testContext(
            sector = Sector.IT,
            derived = CashFlowDerived(
                ebitByYear = mapOf(2026 to 150.0),
                interestByYear = mapOf(2019 to 0.0, 2026 to 100.0),
            ),
        )

        val flag = coverageRule.evaluate(context).single()

        assertTrue(flag.title.contains("1.50×"), flag.title)
        assertNull(
            coverageRule.explainSkip(context),
            "it ran on FY2026 and did not trip, so the unusable FY2019 row is not a skip",
        )
    }

    // --------------------------------------------------------- goodwill (no input)

    @Test
    fun `the goodwill rule cannot evaluate on any real fixture and names the missing source`() {
        val rule = GoodwillAboveTwentyPercentAssetsRule()

        for (context in listOf(relianceContext(), tcsContext(), hdfcBankContext(), titanContext())) {
            assertTrue(
                rule.evaluate(context).isEmpty(),
                "${context.symbol}: goodwill is absent from every fixture, so nothing may fire",
            )
            val reason = assertNotNull(rule.explainSkip(context))
            assertTrue(reason.contains("no Goodwill line"), reason)
            assertTrue(reason.contains("Fixed Assets"), reason)
            assertTrue(reason.contains("zero hits"), reason)
        }
    }

    @Test
    fun `the goodwill rule fires when a source does publish the line`() {
        val rule = GoodwillAboveTwentyPercentAssetsRule()
        val context = testContext(
            sector = Sector.PHARMA,
            derived = CashFlowDerived(
                goodwillByYear = mapOf(2025 to 20_000.0, 2026 to 30_000.0),
                totalAssetsByYear = mapOf(2025 to 100_000.0, 2026 to 100_000.0),
            ),
        )

        val flag = rule.evaluate(context).single()

        assertEquals(FundamentalsFlagIds.GOODWILL_ABOVE_20PCT_ASSETS, flag.id)
        assertEquals(FlagGroup.BALANCE_SHEET, flag.group)
        assertEquals(FlagSeverity.MEDIUM, flag.severity)
        assertTrue(flag.title.contains("30.0 % of assets"), flag.title)
        val evidence = flag.evidence.associate { it.label to it.value }
        assertEquals("₹30,000 Cr", evidence["Goodwill (FY2026)"])
        assertEquals("₹100,000 Cr", evidence["Total assets (FY2026)"])
        assertEquals("30.0 %", evidence["Goodwill as % of total assets"])
        assertNull(rule.explainSkip(context))
    }

    @Test
    fun `goodwill at exactly twenty percent does not fire`() {
        val rule = GoodwillAboveTwentyPercentAssetsRule()
        val context = testContext(
            derived = CashFlowDerived(
                goodwillByYear = mapOf(2026 to 20.0),
                totalAssetsByYear = mapOf(2026 to 100.0),
            ),
        )

        assertTrue(rule.evaluate(context).isEmpty(), "the spec's threshold is 'above 20%'")
    }

    @Test
    fun `both permanently unsourceable checks are declared by id`() {
        val gaps = FundamentalsRedFlagRules.declaredCoverageGaps

        assertEquals(
            listOf(
                FundamentalsFlagIds.GOODWILL_ABOVE_20PCT_ASSETS,
                FundamentalsFlagIds.PROMOTER_PLEDGING,
            ),
            gaps.map { it.ruleId },
        )
        assertTrue(
            gaps.all { it.ruleId in FundamentalsRedFlagRules.ruleIds },
            "a declared gap must name a rule that actually exists, or it is a comment",
        )
        val goodwillGap = gaps.first()
        assertTrue(goodwillGap.missingSource.contains("not published"), goodwillGap.missingSource)
        assertTrue(goodwillGap.note.contains("subset of fixed assets"), goodwillGap.note)
    }

    // ------------------------------------------------------ pledge (no input)

    @Test
    fun `the pledge rule cannot evaluate on any real fixture and never substitutes holding`() {
        val rule = PromoterPledgingRule()

        for (context in listOf(relianceContext(), tcsContext(), hdfcBankContext(), titanContext())) {
            assertTrue(rule.evaluate(context).isEmpty(), context.symbol)
            val reason = assertNotNull(rule.explainSkip(context))
            assertTrue(reason.contains("zero hits for 'pledg'"), reason)
            assertTrue(reason.contains("different number"), reason)
        }
        assertEquals(null, Golden.relianceDerived.promoterPledgePercent)
    }

    @Test
    fun `the pledge rule fires and grades when a source does publish the figure`() {
        val rule = PromoterPledgingRule()
        val medium = rule.evaluate(
            testContext(
                derived = Golden.relianceDerived.copy(promoterPledgePercent = 4.5),
                ratios = Ratios(
                    "RELIANCE", Sector.OTHER, LocalDate.of(2026, 10, 1), Source.SCREENER_IN,
                    Confidence.SINGLE,
                ),
            ),
        ).single()
        val high = rule.evaluate(
            testContext(derived = Golden.relianceDerived.copy(promoterPledgePercent = 40.0)),
        ).single()

        assertEquals(FundamentalsFlagIds.PROMOTER_PLEDGING, medium.id)
        assertEquals(FlagSeverity.MEDIUM, medium.severity)
        assertEquals(FlagSeverity.HIGH, high.severity)
        assertTrue(medium.title.contains("4.5 %"), medium.title)
        assertEquals(
            Golden.CAPTURE_DATE,
            medium.evidence.first { it.label == "Promoter pledge" }.asOf,
            "a pledge percentage is a live value, so it carries the scrape date",
        )
        assertTrue(
            medium.evidence.first { it.label == "Promoter holding is not pledge" }.value
                .contains("never presented as a pledge"),
        )
        assertTrue(
            rule.evaluate(testContext(derived = Golden.relianceDerived.copy(promoterPledgePercent = 0.0)))
                .isEmpty(),
            "zero is not a pledge; the source simply did not publish one",
        )
    }

    private fun com.mia.app.domain.ratios.Computation<Double>.value(): Double? = orNull()
}