package com.mia.app.domain.accuracy

import com.mia.app.domain.compliance.AdviceLanguage
import com.mia.app.domain.compliance.HonestyContract
import com.mia.app.domain.model.AccuracyReport
import com.mia.app.domain.model.HitRate
import com.mia.app.domain.model.MatchScorePolicy
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Aggregation and the reporting gate (plan.md §6.1 rule 6, decision D6).
 *
 * The gate is the product's promise: below 30 settled cases the app says so instead of printing a
 * percentage, because a 100% hit rate built from three cases is the single most misleading thing
 * this screen could show.
 */
class HitRateCalculatorTest {

    private val d = AccuracyTestData::date

    // -----------------------------------------------------------------------------------
    // The 30-sample gate
    // -----------------------------------------------------------------------------------

    @Test
    fun `29 settled cases are withheld and say by how much`() {
        val hitRate = HitRateCalculator.perStockAndPattern(
            AccuracyTestData.resolvedOutcomes(count = 29, hits = 29),
        ).single()

        assertEquals(29, hitRate.samples)
        assertEquals(29, hitRate.hits)
        assertEquals(100.0, hitRate.hitRate)
        assertFalse(hitRate.isReportable)
        assertTrue(hitRate.label().contains("withheld"))
        // "Withheld" alone would be an invitation to guess; the gap must be visible.
        assertTrue(hitRate.label().contains("29/30 samples"), "label was: ${hitRate.label()}")
        // Even a perfect rate is not printed.
        assertFalse(hitRate.label().contains("100"))
        assertEquals(30, MatchScorePolicy.MIN_HIT_RATE_SAMPLES)
    }

    @Test
    fun `exactly 30 settled cases clears the gate`() {
        val hitRate = HitRateCalculator.perStockAndPattern(
            AccuracyTestData.resolvedOutcomes(count = 30, hits = 18),
        ).single()

        assertTrue(hitRate.isReportable)
        assertEquals(18, hitRate.hits)
        assertEquals(30, hitRate.samples)
        assertEquals(60.0, hitRate.hitRate)
        assertEquals("Hit rate 60.0% (n=30)", hitRate.label())
    }

    @Test
    fun `31 cases out of 31 may read 100 percent but must still carry n`() {
        val hitRate = HitRateCalculator.perStockAndPattern(
            AccuracyTestData.resolvedOutcomes(count = 31, hits = 31),
        ).single()

        assertTrue(hitRate.isReportable)
        assertEquals(100.0, hitRate.hitRate)
        assertEquals("Hit rate 100.0% (n=31)", hitRate.label())
        assertTrue(hitRate.label().contains("n=31"))
        HonestyContract.requireHitRateSampleSize(hitRate.label())
    }

    @Test
    fun `the same gate applies to every grouping`() {
        val outcomes = AccuracyTestData.resolvedOutcomes(count = 31, hits = 15)

        assertTrue(HitRateCalculator.perPattern(outcomes).single().isReportable)
        assertTrue(HitRateCalculator.perSymbol(outcomes).single().isReportable)
        assertTrue(HitRateCalculator.perStockAndPattern(outcomes).single().isReportable)
    }

    // -----------------------------------------------------------------------------------
    // Resolved only
    // -----------------------------------------------------------------------------------

    @Test
    fun `an open window is not a sample and not a miss`() {
        val settled = AccuracyTestData.resolvedOutcomes(count = 10, hits = 10)
        val open = listOf(
            AccuracyTestData.outcome(projectedOn = d(0), patternId = 100L),
            AccuracyTestData.outcome(projectedOn = d(0), patternId = 101L),
        )

        val withOpenWindows = HitRateCalculator.perStockAndPattern(settled + open).single()

        assertEquals(10, withOpenWindows.samples, "open windows must not enter the denominator")
        assertEquals(10, withOpenWindows.hits)
        assertEquals(100.0, withOpenWindows.hitRate)
        assertEquals(2, HitRateCalculator.unresolvedCount(settled + open))
        assertEquals(10, HitRateCalculator.resolved(settled + open).size)
    }

    @Test
    fun `a resolved outcome with no verdict is treated as open, not as a miss`() {
        val contradictory = AccuracyTestData.outcome(resolved = true, hit = null)

        assertEquals(0, HitRateCalculator.resolved(listOf(contradictory)).size)
        assertEquals(1, HitRateCalculator.unresolvedCount(listOf(contradictory)))
        assertEquals(0, HitRateCalculator.perStockAndPattern(listOf(contradictory)).single().samples)
    }

    @Test
    fun `a group with nothing settled yet is visible as withheld rather than dropped`() {
        val allOpen = listOf(
            AccuracyTestData.outcome(symbol = "RELIANCE", patternName = "Bull flag"),
            AccuracyTestData.outcome(symbol = "RELIANCE", patternName = "Bull flag", patternId = 2L),
        )

        val cell = HitRateCalculator.perStockAndPattern(allOpen).single()

        assertEquals(0, cell.samples)
        assertNull(cell.hitRate)
        assertFalse(cell.isReportable)
        assertTrue(cell.label().contains("0/30 samples"))
    }

    // -----------------------------------------------------------------------------------
    // report()
    // -----------------------------------------------------------------------------------

    @Test
    fun `a report counts the total and keeps the open windows out of it`() {
        val outcomes = AccuracyTestData.resolvedOutcomes(count = 40, hits = 22, symbol = "RELIANCE") +
            AccuracyTestData.outcome(symbol = "RELIANCE", projectedOn = d(0), patternId = 900L)

        val report = HitRateCalculator.report(outcomes, today = d(2))

        assertEquals(41, report.totalOutcomes)
        assertEquals(1, report.unresolved)
        assertEquals(40, report.perSymbolAndPattern.single().samples)
        assertEquals(22, report.perSymbolAndPattern.single().hits)
        assertEquals(22, report.perPattern.single().hits)
        assertEquals(22, report.perSymbol.single().hits)
        assertFalse(report.isEmpty)
    }

    @Test
    fun `an empty history reports as empty instead of throwing`() {
        val report = HitRateCalculator.report(emptyList(), today = d(0))

        assertEquals(AccuracyReport.EMPTY, report)
        assertTrue(report.isEmpty)
        assertEquals(0, report.unresolved)
        assertTrue(report.perSymbolAndPattern.isEmpty())
        assertTrue(report.perPattern.isEmpty())
        assertTrue(report.perSymbol.isEmpty())
    }

    @Test
    fun `a report refuses outcomes projected in the future`() {
        val fromTheFuture = AccuracyTestData.resolvedOutcomes(
            count = 31,
            hits = 10,
            asOf = d(10),
        )

        val failure = assertFailsWith<IllegalArgumentException> {
            HitRateCalculator.report(fromTheFuture, today = d(5))
        }
        assertTrue(failure.message!!.contains("future"))
    }

    @Test
    fun `report tables are ordered by symbol then pattern`() {
        val outcomes = AccuracyTestData.resolvedOutcomes(count = 31, hits = 5, symbol = "TCS", patternName = "Bull flag") +
            AccuracyTestData.resolvedOutcomes(count = 31, hits = 6, symbol = "RELIANCE", patternName = "Head & shoulders") +
            AccuracyTestData.resolvedOutcomes(count = 31, hits = 7, symbol = "RELIANCE", patternName = "Bull flag")

        val cells = HitRateCalculator.report(outcomes, today = d(2)).perSymbolAndPattern

        assertEquals(
            listOf("RELIANCE" to "Bull flag", "RELIANCE" to "Head & shoulders", "TCS" to "Bull flag"),
            cells.map { it.symbol to it.patternName },
        )
        assertEquals(
            listOf("Bull flag", "Head & shoulders"),
            HitRateCalculator.perPattern(outcomes).map { it.patternName },
        )
        assertEquals(listOf("RELIANCE", "TCS"), HitRateCalculator.perSymbol(outcomes).map { it.symbol })
    }

    @Test
    fun `per pattern and per symbol drop the axis they do not describe`() {
        val outcomes = AccuracyTestData.resolvedOutcomes(
            count = 31,
            hits = 20,
            symbol = "RELIANCE",
            patternName = "Bull flag",
        )

        assertNull(HitRateCalculator.perPattern(outcomes).single().symbol)
        assertEquals("Bull flag", HitRateCalculator.perPattern(outcomes).single().patternName)
        assertNull(HitRateCalculator.perSymbol(outcomes).single().patternName)
        assertEquals("RELIANCE", HitRateCalculator.perSymbol(outcomes).single().symbol)
    }

    // -----------------------------------------------------------------------------------
    // Copy
    // -----------------------------------------------------------------------------------

    @Test
    fun `summary text names the scope and repeats the sample size`() {
        val cell = HitRate("RELIANCE", "Double bottom", hits = 18, samples = 31)

        val text = HitRateCalculator.summaryText(cell)

        assertEquals("Double bottom on RELIANCE — Hit rate 58.1% (n=31).", text)
        assertTrue(text.contains("n=31"))
        AdviceLanguage.requireClean(text)
        HonestyContract.requireNotProbabilityClaim(text)
        HonestyContract.requireHitRateSampleSize(text)
    }

    @Test
    fun `summary text withholds below the threshold instead of showing a small percentage`() {
        val text = HitRateCalculator.summaryText(HitRate("RELIANCE", "Double bottom", hits = 2, samples = 4))

        assertEquals("Double bottom on RELIANCE — Hit rate withheld — 4/30 samples.", text)
        assertFalse(text.contains("50.0%"))
        assertTrue(text.contains("samples"))
    }

    @Test
    fun `summary text covers the aggregate rows too`() {
        val texts = listOf(
            HitRateCalculator.summaryText(HitRate(null, "Bull flag", 20, 31)),
            HitRateCalculator.summaryText(HitRate("RELIANCE", null, 20, 31)),
            HitRateCalculator.summaryText(HitRate(null, null, 20, 31)),
        )

        assertEquals(
            listOf(
                "Bull flag on every tracked stock — Hit rate 64.5% (n=31).",
                "RELIANCE across every tracked pattern — Hit rate 64.5% (n=31).",
                "Every tracked pattern on every tracked stock — Hit rate 64.5% (n=31).",
            ),
            texts,
        )
        texts.forEach {
            AdviceLanguage.requireClean(it)
            HonestyContract.requireNotProbabilityClaim(it)
        }
    }

    @Test
    fun `summary table keeps the row order it was given`() {
        val rows = listOf(HitRate("TCS", "Bull flag", 1, 1), HitRate("INFY", "Bull flag", 2, 2))

        assertEquals(HitRateCalculator.summaryText(rows[0]), HitRateCalculator.summaryTable(rows)[0])
        assertEquals(HitRateCalculator.summaryText(rows[1]), HitRateCalculator.summaryTable(rows)[1])
    }

    @Test
    fun `a cell with no samples at all still renders as withheld`() {
        val text = HitRateCalculator.summaryText(HitRate("TATAMOTORSPV", "Bull flag", hits = 0, samples = 0))

        assertEquals("Bull flag on TATAMOTORSPV — Hit rate withheld — 0/30 samples.", text)
        assertNull(HitRate("TATAMOTORSPV", "Bull flag", 0, 0).hitRate)
    }

    @Test
    fun `the report date is a parameter, not a clock read`() {
        // Two calls a year apart must agree when given the same explicit date.
        val outcomes = AccuracyTestData.resolvedOutcomes(count = 31, hits = 20, asOf = LocalDate.of(2026, 6, 1))

        assertEquals(
            HitRateCalculator.report(outcomes, today = LocalDate.of(2026, 6, 2)),
            HitRateCalculator.report(outcomes, today = LocalDate.of(2026, 6, 2)),
        )
        assertFailsWith<IllegalArgumentException> {
            HitRateCalculator.report(outcomes, today = LocalDate.of(2025, 6, 2))
        }
    }
}