package com.mia.app.domain.redflag.fundamentals

import com.mia.app.domain.model.CashFlowDerived
import com.mia.app.domain.model.Evidence
import com.mia.app.domain.model.FlagGroup
import com.mia.app.domain.model.FlagTab
import com.mia.app.domain.model.QuarterRecord
import com.mia.app.domain.model.RedFlag
import com.mia.app.domain.model.Sector
import com.mia.app.domain.redflag.RedFlagContext
import com.mia.app.domain.redflag.RedFlagEngine
import com.mia.app.domain.redflag.RedFlagRule
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The ten rules as [RedFlagEngine] runs them: ordering, isolation, determinism, and the skip list
 * the engine itself leaves empty.
 *
 * ### What is being tested and what is not
 * [RedFlagEngine] is another phase's file and is not modified here. These tests therefore pin the
 * **behaviour the fundamentals rules rely on** — sorted output, unique ids, a crashing rule
 * isolated into `failures`, two runs producing identical lists (plan.md §8: "All are deterministic
 * threshold checks") — so that if the engine's contract changes, this phase finds out here.
 *
 * The isolation test uses a deliberately throwing rule defined in this file, which is also the
 * cheapest proof that a rule failure cannot hide the other nine flags: a banner that empties
 * because one rule crashed is the failure mode `RedFlagEngine` exists to prevent.
 */
class FundamentalsEngineIntegrationTest {

    private val card = RatiosCardSeries(
        debtorDays = mapOf(2025 to 20.0, 2026 to 70.0),
        inventoryDays = mapOf(2025 to 20.0, 2026 to 70.0),
        daysPayable = mapOf(2025 to 10.0, 2026 to 10.0),
        rocePercent = mapOf(2024 to 40.0, 2025 to 30.0, 2026 to 20.0),
    )

    /**
     * Figures chosen to trip **every** check at once, in pharma: a sector where all four
     * earnings-quality and balance-sheet checks are meaningful, which is what lets one context
     * exercise ten rules.
     */
    private val distressed = testContext(
        symbol = "STRESSED",
        sector = Sector.PHARMA,
        ratios = Golden.titanRatios.copy(symbol = "STRESSED", sector = Sector.PHARMA, pe = 9.0),
        derived = CashFlowDerived(
            cfoByYear = mapOf(2024 to -100.0, 2025 to -200.0, 2026 to -300.0),
            patByYear = mapOf(2024 to 100.0, 2025 to 200.0, 2026 to 300.0),
            debtByYear = mapOf(2025 to 30_000.0, 2026 to 40_000.0),
            equityByYear = mapOf(2025 to 10_000.0, 2026 to 10_000.0),
            ebitByYear = mapOf(2026 to 500.0),
            interestByYear = mapOf(2026 to 1_000.0),
            otherIncomeByYear = mapOf(2026 to 900.0),
            goodwillByYear = mapOf(2026 to 50_000.0),
            totalAssetsByYear = mapOf(2026 to 100_000.0),
            promoterPledgePercent = 30.0,
            revenueByYear = mapOf(2025 to 100.0, 2026 to 120.0),
        ),
        quarters = (0 until 9).map { index ->
            QuarterRecord(2024 + index / 4, index % 4 + 1, eps = if (index < 5) 6.0 else 7.2 - (index - 4) * 1.2)
        },
        candles = listOf(
            dailyCandle(LocalDate.of(2026, 3, 31), 190.0),
            dailyCandle(LocalDate.of(2025, 3, 31), 264.0),
        ),
    )

    @Test
    fun `the ten rules produce a sorted unique failure free report`() {
        val engine = RedFlagEngine(FundamentalsRedFlagRules.all(card))
        val report = engine.evaluate(distressed)

        assertEquals(10, engine.ruleIds.size)
        assertEquals(
            report.flags.size,
            report.flags.map { it.id }.distinct().size,
            "duplicate ids",
        )
        assertTrue(report.flags.isNotEmpty())
        assertTrue(
            report.flags.map { it.id }.all { it in engine.ruleIds },
            "a flag must name a rule the engine actually ran",
        )
        assertEquals(emptyMap(), report.failures)
        assertEquals(10, report.flags.size, "every rule fires on this context: ${report.flags.map { it.id }}")
        assertTrue(
            report.flags.all { it.tab == FlagTab.FUNDAMENTALS },
            "no fundamentals rule belongs on the technicals tab",
        )
        assertTrue(
            report.flags.map { it.severity.ordinal } == report.flags.map { it.severity.ordinal }.sorted(),
            "flags are ordered by severity",
        )
        assertEquals(report.flags.size, report.forFundamentals.size)
        assertEquals(0, report.forTechnicals.size)
    }

    @Test
    fun `every group is represented and each flag keeps its evidence`() {
        val report = RedFlagEngine(FundamentalsRedFlagRules.all(card)).evaluate(distressed)

        val groups = report.flags.map { it.group }.toSet()
        assertEquals(
            setOf(FlagGroup.EARNINGS_QUALITY, FlagGroup.BALANCE_SHEET, FlagGroup.VALUATION_TRAP),
            groups,
        )
        for (flag in report.flags) {
            assertTrue(flag.evidence.isNotEmpty(), flag.id)
            for (row: Evidence in flag.evidence) assertEvidenceRow(flag, row)
        }
    }

    @Test
    fun `a throwing rule is isolated and does not hide the other flags`() {
        val engine = RedFlagEngine(listOf(ThrowingRule) + FundamentalsRedFlagRules.all(card))
        val report = engine.evaluate(distressed)

        assertEquals(setOf(ThrowingRule.id), report.failures.keys)
        assertTrue(report.failures.getValue(ThrowingRule.id).isNotBlank())
        assertEquals(
            10,
            report.flags.size,
            "a crashed rule must not empty the banner: ${report.flags.map { it.id }}",
        )
        // With no card and no profitability series, nothing else can trip, so the header has to
        // report the failure rather than read as a clean bill of health.
        assertEquals(
            "Could not check red flags",
            headerWhenNothingElseCouldBeFlagged(
                RedFlagEngine(listOf(ThrowingRule) + FundamentalsRedFlagRules.all()),
            ),
        )
    }

    @Test
    fun `the fundamentals engine fills in the skip list the engine leaves empty`() {
        // No card and no statements: every rule has nothing to work with, which is exactly the
        // case a naive engine renders as "no red flags".
        val engine = FundamentalsRedFlagEngine()
        val clean = testContext(symbol = "CLEAN", sector = Sector.PHARMA)

        val report = engine.evaluate(clean)

        assertEquals(10, engine.ruleIds.size)
        assertEquals(emptyMap(), report.failures)
        assertTrue(report.flags.isEmpty(), "no figures to trip on")
        assertEquals(
            10,
            report.skipped.size,
            "every rule on this context could not be evaluated, and the report has to say so " +
                "rather than reading as all clear (spec §11 rule 8)",
        )
        assertTrue(FundamentalsFlagIds.GOODWILL_ABOVE_20PCT_ASSETS in report.skipped.map { it.ruleId })
        assertTrue(FundamentalsFlagIds.PROMOTER_PLEDGING in report.skipped.map { it.ruleId })
        assertEquals(
            engine.skipsFor(clean).map { it.ruleId to it.reason }.toSet(),
            report.skipped.map { it.ruleId to it.reason }.toSet(),
        )
        // "No flags" over a context where nothing could be checked is not a clean bill of health,
        // and the header now says which of the two the reader is looking at (orchestrator fix:
        // the engine used to render the plain "no flags" string in both cases).
        assertEquals(
            "No red flags in the data checked (some checks skipped)",
            report.headerText(FlagTab.FUNDAMENTALS),
        )
    }

    @Test
    fun `every skip carries a reason that names what was missing`() {
        val engine = FundamentalsRedFlagEngine(ratiosCard = card)
        // The RELIANCE context has an unresolved sector and no quarterly EPS series, so several
        // rules legitimately abstain. What must hold is that each abstention explains itself
        // rather than being silently absent.
        val report = engine.evaluate(relianceContext())

        assertTrue(report.skipped.isNotEmpty(), "this context cannot evaluate every rule")
        report.skipped.forEach { skip ->
            assertTrue(
                skip.reason.isNotBlank(),
                "${skip.ruleId} abstained without saying why",
            )
            assertTrue(
                skip.reason.length > MIN_REASON_LENGTH,
                "${skip.ruleId}'s reason is too terse to be useful: \"${skip.reason}\"",
            )
        }

        // The two structurally unsourceable checks must be named as such, every time.
        val byId = report.skipped.associateBy { it.ruleId }
        listOf(
            FundamentalsFlagIds.GOODWILL_ABOVE_20PCT_ASSETS,
            FundamentalsFlagIds.PROMOTER_PLEDGING,
        ).forEach { id ->
            assertTrue(id in byId, "$id must report why it cannot run")
            assertTrue(
                "not evaluable" in byId.getValue(id).reason.lowercase(),
                "$id's reason must say it is not evaluable from our sources, not merely absent",
            )
        }
    }

    private companion object {
        /** Long enough that "n/a" or "missing" cannot pass as an explanation. */
        const val MIN_REASON_LENGTH = 40
    }

    @Test
    fun `two runs on the same context produce identical reports`() {
        val engine = FundamentalsRedFlagEngine(ratiosCard = card)

        val first = engine.evaluate(distressed)
        val second = engine.evaluate(distressed)

        assertEquals(first, second, "plan.md §8: the flags are deterministic threshold checks")
    }

    @Test
    fun `the engine accepts extra rules from another package without losing the flags`() {
        val engine = FundamentalsRedFlagEngine(extraRules = listOf(ThrowingRule), ratiosCard = card)
        val report = engine.evaluate(distressed)

        assertEquals(11, engine.ruleIds.size)
        assertTrue(ThrowingRule.id in report.failures)
        assertTrue(report.flags.isNotEmpty())
        assertTrue(report.flags.all { it.id in engine.ruleIds })
    }

    @Test
    fun `the registry lists the ten plan ids once each`() {
        assertEquals(
            listOf(
                FundamentalsFlagIds.CFO_NEGATIVE_WHILE_PAT_POSITIVE,
                FundamentalsFlagIds.RECEIVABLES_GROWING_FASTER_THAN_REVENUE,
                FundamentalsFlagIds.INVENTORY_DAYS_RISING,
                FundamentalsFlagIds.OTHER_INCOME_AS_PCT_OF_PAT,
                FundamentalsFlagIds.DEBT_TO_EQUITY_SPIKE,
                FundamentalsFlagIds.INTEREST_COVERAGE_BELOW_2,
                FundamentalsFlagIds.GOODWILL_ABOVE_20PCT_ASSETS,
                FundamentalsFlagIds.PROMOTER_PLEDGING,
                FundamentalsFlagIds.LOW_PE_WITH_FALLING_RETURNS,
                FundamentalsFlagIds.PE_RISING_WHILE_EARNINGS_FALL,
            ),
            FundamentalsRedFlagRules.ruleIds,
            "plan.md §8.1 order, once each",
        )
        assertEquals(
            FundamentalsRedFlagRules.ruleIds.size,
            FundamentalsRedFlagRules.all(Golden.relianceCard).map { it.id }.distinct().size,
        )
    }

    @Test
    fun `the declared gaps and the not implemented list name only real rule ids`() {
        val known = FundamentalsRedFlagRules.ruleIds.toSet()
        assertTrue(
            FundamentalsRedFlagRules.declaredCoverageGaps.all { it.ruleId in known },
            "a declared gap must name a rule that exists",
        )
        assertEquals(
            listOf("NEGATIVE_FCF_YIELD", "DEBT_TO_EBITDA_ELEVATED"),
            FundamentalsRedFlagRules.notImplementedInThisPhase,
            "spec §8 lists these; naming them is how their absence is declared",
        )
        assertTrue(
            FundamentalsRedFlagRules.notImplementedInThisPhase.none { it in known },
            "a rule cannot be both implemented and declared missing",
        )
    }

    private fun headerWhenNothingElseCouldBeFlagged(engine: RedFlagEngine): String {
        val report = engine.evaluate(
            testContext(symbol = "NOTHING", sector = Sector.BANKING, derived = CashFlowDerived()),
        )
        return report.headerText(FlagTab.FUNDAMENTALS)
    }

    /** A rule that always throws, to prove one crash cannot take the report down. */
    private object ThrowingRule : RedFlagRule {
        override val id: String = "TEST_THROWING_RULE"
        override val tab: FlagTab = FlagTab.FUNDAMENTALS

        override fun evaluate(context: RedFlagContext): List<RedFlag> =
            throw IllegalStateException("this rule always throws, on purpose")
    }
}