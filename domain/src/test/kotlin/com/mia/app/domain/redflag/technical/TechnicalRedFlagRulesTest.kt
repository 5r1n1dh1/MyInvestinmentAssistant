package com.mia.app.domain.redflag.technical

import com.mia.app.domain.model.Evidence
import com.mia.app.domain.model.FlagGroup
import com.mia.app.domain.model.FlagSeverity
import com.mia.app.domain.model.FlagTab
import com.mia.app.domain.model.RedFlag
import com.mia.app.domain.redflag.RedFlagContext
import com.mia.app.domain.redflag.RedFlagEngine
import com.mia.app.domain.redflag.RedFlagRule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Engine integration for the technical family (plan.md §8.2, §12).
 *
 * The seam's job is ordering, isolation and honest reporting. These tests assert the three
 * properties the UI depends on — a stable order, unique ids, and a header that never says "all
 * clear" when a rule could not run.
 */
class TechnicalRedFlagRulesTest {

    /**
     * A 260-session series that trips **four** of the six rules at once: the 200-day death cross, an
     * RSI oversold reading, a 3× volume spike on a down bar, and the close sitting at its
     * 52-session low with a falling OBV slope.
     *
     * Built by taking [TechnicalSeries.deathCross] and replacing only the **last bar's** open and
     * volume, so the close — and therefore every moving average, the RSI and the 52-session range —
     * is byte-for-byte the death-cross fixture's. That is what makes the expected flag list below a
     * real assertion rather than a hope.
     */
    private fun multiFlagSeries(): List<com.mia.app.domain.model.Candle> {
        val base = TechnicalSeries.deathCross()
        val lab = CandleLab()
        base.dropLast(1).forEach { lab.bar(it.open, it.high, it.low, it.close, it.volume) }
        val spike = lab.volumeForRatio(3.0, base.takeLast(19).map { it.volume })
        lab.bar(open = 112.0, high = 112.5, low = 109.5, close = 110.0, volume = spike)
        return lab.build()
    }

    @Test
    fun `the family is six rules with six stable ids, all on the technicals tab`() {
        val rules = TechnicalRedFlagRules.all()
        assertEquals(
            listOf(
                TechnicalFlagIds.DEATH_CROSS,
                TechnicalFlagIds.RSI_OVERBOUGHT,
                TechnicalFlagIds.RSI_OVERSOLD,
                TechnicalFlagIds.DISTRIBUTION_DAY,
                TechnicalFlagIds.AT_52WEEK_LOW_WITH_DECLINING_OBV,
                TechnicalFlagIds.DELIVERY_COLLAPSE,
            ),
            rules.map { it.id },
        )
        assertEquals(rules.size, rules.map { it.id }.distinct().size, "ids must be unique")
        assertTrue(rules.all { it.tab == FlagTab.TECHNICALS })
        assertEquals(rules.size, TechnicalRedFlagRules.engine().ruleIds.size)
    }

    @Test
    fun `the convenience engine accepts additional rule families`() {
        val extra = object : RedFlagRule {
            override val id = "FUNDAMENTALS_PLACEHOLDER"
            override val tab = FlagTab.FUNDAMENTALS
            override fun evaluate(context: RedFlagContext): List<RedFlag> = emptyList()
        }
        assertEquals(
            TechnicalRedFlagRules.all().map { it.id } + extra.id,
            TechnicalRedFlagRules.engine(listOf(extra)).ruleIds,
        )
    }

    @Test
    fun `flags come back sorted by severity then id with no duplicate ids and no rule failures`() {
        val report = TechnicalRedFlagRules.engine().evaluate(technicalContext(multiFlagSeries()))

        assertEquals(emptyMap(), report.failures)
        assertEquals(
            listOf(
                TechnicalFlagIds.AT_52WEEK_LOW_WITH_DECLINING_OBV,
                TechnicalFlagIds.DEATH_CROSS,
                TechnicalFlagIds.DISTRIBUTION_DAY,
                TechnicalFlagIds.RSI_OVERSOLD,
            ),
            report.flags.map { it.id },
            "sorted by severity first, then id",
        )
        assertEquals(FlagSeverity.HIGH, report.flags.first().severity)
        assertTrue(report.flags.drop(1).all { it.severity == FlagSeverity.MEDIUM })
        assertEquals(report.flags.size, report.flags.map { it.id }.distinct().size)
        assertTrue(report.flags.all { it.evidence.isNotEmpty() })
        report.flags.forEach { it.assertEveryMeasurementIsDated() }
    }

    @Test
    fun `every flag lands on the technicals tab`() {
        val report = TechnicalRedFlagRules.engine().evaluate(technicalContext(multiFlagSeries()))
        assertEquals(report.flags.size, report.forTechnicals.size)
        assertEquals(emptyList<RedFlag>(), report.forFundamentals)
        assertTrue(report.flags.all { it.tab == FlagTab.TECHNICALS })
    }

    @Test
    fun `the header counts the flags when there are any`() {
        val report = TechnicalRedFlagRules.engine().evaluate(technicalContext(multiFlagSeries()))
        assertEquals("4 flags", report.headerText(FlagTab.TECHNICALS))
        // The engine counts only the flags that belong to the tab it is rendering, so a
        // fundamentals tab over a technicals-only report counts nothing. Pinned here because the
        // UI depends on the number it gets and because it is the sort of thing that changes
        // silently. (Before the fix this rendered "0 flag(s)" on the *technicals* tab, because
        // the count expression mixed the tab filter with an always-true disjunct.)
        assertEquals("No red flags in the data checked", report.headerText(FlagTab.FUNDAMENTALS))
    }

    @Test
    fun `a header saying one flag is singular`() {
        val report = TechnicalRedFlagRules.engine().evaluate(technicalContext(TechnicalSeries.rsiOverbought()))
        assertEquals(1, report.flagsFor(FlagTab.TECHNICALS).size)
        assertEquals("1 flag", report.headerText(FlagTab.TECHNICALS))
    }

    @Test
    fun `a BOTH-tab flag is shown on each tab`() {
        val both = object : RedFlagRule {
            override val id = "BOTH_TAB"
            override val tab = FlagTab.BOTH
            override fun evaluate(context: RedFlagContext): List<RedFlag> = emptyList()
        }
        val withFlag = object : RedFlagRule {
            override val id = "SHARED"
            override val tab = FlagTab.BOTH
            override fun evaluate(context: RedFlagContext): List<RedFlag> = listOf(
                RedFlag(
                    id = "SHARED",
                    group = FlagGroup.TECHNICAL,
                    severity = FlagSeverity.LOW,
                    tab = FlagTab.BOTH,
                    title = "Evidence-bearing shared flag",
                    evidence = listOf(Evidence(label = "value", value = "1")),
                    threshold = ">= 1",
                ),
            )
        }
        val report = RedFlagEngine(listOf(both, withFlag))
            .evaluate(technicalContext(TechnicalSeries.rsiOverbought()))
        assertEquals(1, report.flagsFor(FlagTab.FUNDAMENTALS).size)
        assertEquals(1, report.flagsFor(FlagTab.TECHNICALS).size)
        assertEquals("1 flag", report.headerText(FlagTab.FUNDAMENTALS))
    }

    @Test
    fun `the header says the data was checked when no flag fired`() {
        val report = TechnicalRedFlagRules.engine().evaluate(technicalContext(TechnicalSeries.rsiBetweenBands()))
        assertTrue(report.isEmpty)
        assertEquals(emptyMap(), report.failures)
        assertEquals("No red flags in the data checked", report.headerText(FlagTab.TECHNICALS))
    }

    @Test
    fun `the header refuses to claim a clean bill when a rule crashes`() {
        val exploding = object : RedFlagRule {
            override val id = "EXPLODING"
            override val tab = FlagTab.TECHNICALS
            override fun evaluate(context: RedFlagContext): List<RedFlag> =
                error("boom")
        }
        val engine = RedFlagEngine(TechnicalRedFlagRules.all() + exploding)
        val report = engine.evaluate(technicalContext(TechnicalSeries.rsiBetweenBands()))

        assertTrue(report.isEmpty)
        assertEquals(setOf("EXPLODING"), report.failures.keys)
        assertEquals("Could not check red flags", report.headerText(FlagTab.TECHNICALS))
    }

    @Test
    fun `one crashing rule does not hide the others`() {
        val exploding = object : RedFlagRule {
            override val id = "EXPLODING"
            override val tab = FlagTab.TECHNICALS
            override fun evaluate(context: RedFlagContext): List<RedFlag> =
                error("boom")
        }
        val engine = RedFlagEngine(listOf(exploding) + TechnicalRedFlagRules.all())
        val report = engine.evaluate(technicalContext(multiFlagSeries()))

        assertEquals(4, report.flags.size)
        assertEquals(setOf("EXPLODING"), report.failures.keys)
    }

    @Test
    fun `running the engine twice on the same context yields an identical report`() {
        val engine = TechnicalRedFlagRules.engine()
        val context = technicalContext(multiFlagSeries())
        assertEquals(engine.evaluate(context).flags, engine.evaluate(context).flags)
    }

    // ----------------------------------------------------------------------- the skip channel

    @Test
    fun `the skip path names the rules that could not answer and why`() {
        val skips = TechnicalRedFlagRules.skips(technicalContext(TechnicalSeries.tooShortForVolumeAverage(bars = 12)))
        val byRule = skips.associate { it.ruleId to it.reason }

        assertEquals(
            "The 200-day average needs 200 daily sessions; this series has 12.",
            byRule[TechnicalFlagIds.DEATH_CROSS],
        )
        assertEquals(
            "RSI(14) needs 15 daily sessions; this series has 12.",
            byRule[TechnicalFlagIds.RSI_OVERBOUGHT],
        )
        assertEquals(
            "RSI(14) needs 15 daily sessions; this series has 12.",
            byRule[TechnicalFlagIds.RSI_OVERSOLD],
        )
        assertEquals(
            "The 20-bar average volume needs 20 daily sessions; this series has 12.",
            byRule[TechnicalFlagIds.DISTRIBUTION_DAY],
        )
        assertEquals(
            byRule[TechnicalFlagIds.AT_52WEEK_LOW_WITH_DECLINING_OBV],
            "The 52-session high/low window needs 52 daily sessions; this series has 12. Calling " +
                "the lowest close of a shorter window a 52-session low would be a claim the data " +
                "does not support.",
        )
        assertEquals(
            "The 20-session delivery window needs 20 daily sessions; this series has 12.",
            byRule[TechnicalFlagIds.DELIVERY_COLLAPSE],
        )
        assertEquals(TechnicalRedFlagRules.all().size, skips.size)
    }

    @Test
    fun `a rule that ran and found nothing is not reported as skipped`() {
        val context = technicalContext(TechnicalSeries.rsiBetweenBands())
        val skippedIds = TechnicalRedFlagRules.skips(context).map { it.ruleId }.toSet()

        // 30 sessions: the two RSI rules and the volume rule all have their windows and answer
        // "no flag"; the 200-day average, the 52-session window and delivery cannot answer at all.
        assertEquals(
            setOf(
                TechnicalFlagIds.DEATH_CROSS,
                TechnicalFlagIds.AT_52WEEK_LOW_WITH_DECLINING_OBV,
                TechnicalFlagIds.DELIVERY_COLLAPSE,
            ),
            skippedIds,
        )
        assertTrue(TechnicalRedFlagRules.engine().evaluate(context).isEmpty)
        listOf(
            TechnicalFlagIds.RSI_OVERBOUGHT,
            TechnicalFlagIds.RSI_OVERSOLD,
            TechnicalFlagIds.DISTRIBUTION_DAY,
        ).forEach { ranQuietly ->
            assertTrue(
                ranQuietly !in skippedIds,
                "$ranQuietly evaluated and found nothing, so it must not be reported as skipped",
            )
        }
    }

    @Test
    fun `the only reason the multi-flag series skips is the missing delivery feed`() {
        val context = technicalContext(multiFlagSeries())
        val skips = TechnicalRedFlagRules.skips(context)

        assertEquals(
            listOf(TechnicalFlagIds.DELIVERY_COLLAPSE),
            skips.map { it.ruleId },
        )
        assertTrue(skips.single().reason.contains("did not publish it"))
        val report = TechnicalRedFlagRules.engine().evaluate(context)
        assertEquals(4, report.flags.size)
        assertTrue(report.flags.none { it.id == TechnicalFlagIds.DELIVERY_COLLAPSE })
    }

    @Test
    fun `every threshold string is unique and names its number`() {
        val thresholds = TechnicalRedFlagRules.allThresholdStrings()
        assertEquals(thresholds.size, thresholds.distinct().size)
        assertTrue(thresholds.all { it.any { ch -> ch.isDigit() } }, "a threshold must state a number")
    }
}