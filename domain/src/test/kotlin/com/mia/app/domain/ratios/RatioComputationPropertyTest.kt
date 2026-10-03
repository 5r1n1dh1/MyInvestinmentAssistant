package com.mia.app.domain.ratios

import com.mia.app.domain.model.AnnualRow
import com.mia.app.domain.model.CashFlowDerived
import com.mia.app.domain.model.Sector
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Property tests over synthetic [CashFlowDerived] inputs (plan.md §12).
 *
 * These assert invariants that must hold for **any** input, not only for the golden fixtures:
 *
 * * no computation ever produces `NaN` or infinity — the ratios appear on a screen, and a
 *   `NaN` ratio is a chart bug that a fixture test would miss the moment a real page publishes
 *   a zero;
 * * a division by zero yields a null with a reason, never infinity and never a fabricated 0;
 * * a null result always carries a reason (enforced by the [Computation] type, asserted here so
 *   the enforcement is not accidentally relaxed);
 * * sector suppression survives an adversarial value: no caller can hand a bank back a D/E;
 * * a missing year is never bridged: every per-year function resolves its year from the
 *   intersection of the maps it needs.
 *
 * Randomness is seeded, so a failure is reproducible from the seed printed in the message.
 */
class RatioComputationPropertyTest {

    /** A synthetic statement set that deliberately includes the nasty inputs. */
    private fun syntheticDerived(seed: Long): CashFlowDerived {
        val random = Random(seed)
        fun series(years: Int, min: Double, max: Double): Map<Int, Double> =
            (2020..2026).mapNotNull { year ->
                // Zeros, exact duplicates and negatives are all real in Indian statements.
                val roll = random.nextInt(10)
                val value = when (roll) {
                    0 -> 0.0
                    1 -> min
                    2 -> max
                    else -> min + random.nextDouble() * (max - min)
                }
                if (random.nextInt(8) == 0) null else year to value
            }.toMap()

        return CashFlowDerived(
            cfoByYear = series(7, -1e5, 1e6),
            patByYear = series(7, -1e5, 1e6),
            fcfByYear = series(7, -1e5, 1e6),
            cfoToOpByYear = series(7, -2.0, 3.0),
            revenueByYear = series(7, -1.0, 1e6),
            otherIncomeByYear = series(7, -1e4, 1e4),
            totalAssetsByYear = series(7, 0.0, 1e7),
            goodwillByYear = series(7, -1e4, 1e5),
            debtByYear = series(7, 0.0, 1e6),
            equityByYear = series(7, -1e4, 1e6),
            interestByYear = series(7, -1e4, 1e5),
            ebitByYear = series(7, -1e6, 1e6),
            sharesOutstanding = if (seed % 2 == 0L) 1.0 else null,
            promoterPledgePercent = if (seed % 3 == 0L) 12.5 else null,
        )
    }

    private fun assertFinite(what: String, computation: Computation<Double>) {
        val value = computation.value ?: return
        assertTrue(!value.isNaN(), "$what produced NaN")
        assertTrue(!value.isInfinite(), "$what produced $value")
    }

    private fun assertFiniteValues(what: String, values: Iterable<Double?>) {
        assertAllFinite(what, values)
    }

    @Test
    fun `no computed ratio is ever NaN or infinite`() {
        for (seed in 1L..60L) {
            val derived = syntheticDerived(seed)
            for (sector in Sector.entries) {
                assertFinite("debtToEquity seed=$seed $sector", RatioComputation.debtToEquity(derived, sector))
                assertFinite("interestCoverage seed=$seed $sector", RatioComputation.interestCoverage(derived, sector))
            }
            assertFinite("cfoToOp seed=$seed", RatioComputation.cfoToOp(derived))
            assertFinite("revenueCagr3 seed=$seed", RatioComputation.revenueCagr(derived, 3))
            assertFinite("revenueCagr5 seed=$seed", RatioComputation.revenueCagr(derived, 5))
            assertFinite("revenueGrowth seed=$seed", RatioComputation.revenueGrowth(derived))
            assertFinite("fcfToPat seed=$seed", RatioComputation.fcfToPat(derived))
            assertFinite("fcf seed=$seed", RatioComputation.freeCashFlow(derived))
            assertFinite("pat seed=$seed", RatioComputation.pat(derived))
            assertFinite("goodwillToAssets seed=$seed", RatioComputation.goodwillToAssets(derived))
            assertFinite("inventoryDays seed=$seed", RatioComputation.inventoryDays(derived))
            assertFinite("receivableDays seed=$seed", RatioComputation.receivableDays(derived))
            assertFinite("promoterPledge seed=$seed", RatioComputation.promoterPledge(derived))
            // A trend is not a single number, so its fields are checked for finiteness below.
            assertAllFinite(
                "grossMargin seed=$seed",
                listOfNotNull(
                    RatioComputation.grossMarginTrend(derived.syntheticGrossMargin())
                        .value?.changeAbsolute,
                ),
            )
        }
    }

    /**
     * A stand-in "gross margin series" built from the same adversarial data, so the trend
     * function is exercised over values that include zeros, negatives and near-zero denominators.
     */
    private fun CashFlowDerived.syntheticGrossMargin(): Map<Int, Double> =
        totalAssetsByYear.entries.associate { it.key to it.value / 100.0 }

    @Test
    fun `no trend point is ever NaN or infinite`() {
        for (seed in 1L..40L) {
            val random = Random(seed * 31)
            val points = (2020..2026).map {
                YearValue(it, random.nextDouble() * 200.0 - 100.0)
            }.filter { random.nextInt(4) != 0 }
            val trend = RatioComputation.fiveYearTrend(points, years = 5)
            val value = trend.value
            if (value == null) {
                assertTrue(!trend.note.isNullOrBlank())
                continue
            }
            assertFiniteValues("changeAbsolute seed=$seed", listOf(value.changeAbsolute))
            assertFiniteValues("changePercent seed=$seed", listOf(value.changePercent))
            assertFiniteValues("changePerYear seed=$seed", listOf(value.changePerYear))
            assertEquals(value.points.sortedBy { it.year }, value.points)
            assertEquals(value.latest!!.year, value.points.last().year)
            assertEquals(value.oldest!!.year, value.points.first().year)
            assertEquals(value.latest!!.year - value.oldest!!.year, value.yearsSpanned)
            assertTrue(value.yearsSpanned in 1..5, "seed=$seed span=${value.yearsSpanned}")
        }
    }

    @Test
    fun `a null result always carries a reason`() {
        for (seed in 1L..40L) {
            val derived = syntheticDerived(seed)
            val results = listOf(
                RatioComputation.debtToEquity(derived, Sector.AUTO),
                RatioComputation.interestCoverage(derived, Sector.AUTO),
                RatioComputation.cfoToOp(derived),
                RatioComputation.revenueCagr(derived, 3),
                RatioComputation.revenueGrowth(derived),
                RatioComputation.fcfToPat(derived),
                RatioComputation.goodwillToAssets(derived),
                RatioComputation.inventoryDays(derived),
            )
            for (result in results) {
                if (result.value == null) {
                    assertTrue(!result.note.isNullOrBlank(), "seed=$seed produced a bare null")
                }
            }
        }
    }

    @Test
    fun `division by zero is a null with a reason and never infinity or a fabricated zero`() {
        val zeroed = CashFlowDerived(
            cfoByYear = mapOf(2026 to 100.0),
            patByYear = mapOf(2026 to 0.0),
            fcfByYear = mapOf(2026 to 100.0),
            debtByYear = mapOf(2026 to 100.0),
            equityByYear = mapOf(2026 to 0.0),
            ebitByYear = mapOf(2026 to 100.0),
            interestByYear = mapOf(2026 to 0.0),
            totalAssetsByYear = mapOf(2026 to 0.0),
            goodwillByYear = mapOf(2026 to 10.0),
        )
        for (result in listOf(
            RatioComputation.debtToEquity(zeroed, Sector.AUTO),
            RatioComputation.interestCoverage(zeroed, Sector.AUTO),
            RatioComputation.fcfToPat(zeroed),
            RatioComputation.goodwillToAssets(zeroed),
        )) {
            assertNull(result.value)
            assertTrue(!result.note.isNullOrBlank())
        }
    }

    @Test
    fun `a year present in only one of two maps is never bridged`() {
        val mismatched = CashFlowDerived(
            debtByYear = mapOf(2024 to 10.0, 2025 to 20.0),
            equityByYear = mapOf(2025 to 40.0, 2026 to 80.0),
        )
        val resolved = assertNotNull(RatioComputation.debtToEquity(mismatched, Sector.AUTO).value)
        assertEquals(0.5, resolved, "only 2025 has both inputs, so 20/40 it is")
        // 2026 has equity but no borrowings, so asking for it must fail rather than borrow 2025's.
        assertNull(RatioComputation.debtToEquity(mismatched, Sector.AUTO, 2026).value)
    }

    @Test
    fun `no sector can be handed a value its override suppresses`() {
        for (sector in Sector.entries) {
            for (id in RatioPriority.sectorOverrides(sector).suppressed.keys) {
                for (value in listOf(0.0, 1.0, 0.4457, 1e9)) {
                    val availability = RatioPriority.availability(sector, id, value)
                    assertNull(availability.value, "${sector.name}/${id.name} resurrected $value")
                    assertEquals(RatioState.SUPPRESSED_FOR_SECTOR, availability.state)
                    assertTrue(availability.isBlank)
                }
            }
        }
    }

    @Test
    fun `every sector suppresses a bank style leverage ratio nowhere else`() {
        for (sector in Sector.entries) {
            val state = RatioPriority.sectorStateFor(sector, RatioId.DEBT_TO_EQUITY)
            val suppresses = state == RatioState.SUPPRESSED_FOR_SECTOR
            assertEquals(sector == Sector.BANKING, suppresses, sector.name)
        }
    }

    @Test
    fun `promoter trends over shuffled columns are ordered by period not by position`() {
        val random = Random(99)
        val months = listOf("Mar", "Jun", "Sep", "Dec")
        for (iteration in 1..40) {
            val count = 4 + random.nextInt(8)
            // Build `count` distinct quarterly periods, then shuffle their column order.
            val periods = (0 until count)
                .map { 2019 + it / 4 to months[it % 4] }
                .shuffled(random)
            val labels = periods.map { (year, month) -> "$month $year" }
            val values = List(count) { 10.0 + random.nextInt(50) }
            val trend = assertNotNull(
                RatioComputation.promoterHoldingTrend(labels, listOf(AnnualRow("Promoters +", values))).value,
                "iteration $iteration",
            )
            val latest = assertNotNull(trend.latestPercent)
            val latestPeriod = assertNotNull(trend.latestPeriod)
            val latestIndex = labels.indexOf(latestPeriod)
            assertNotNull(latestIndex, "iteration $iteration: $latestPeriod is not a published column")

            // The reported latest column must be the one with the greatest period key.
            val expectedLatestIndex = labels.indices.maxByOrNull { periodKeyOf(labels[it]) }!!
            assertEquals(expectedLatestIndex, latestIndex, "iteration $iteration: $labels")
            assertEquals(values[latestIndex], latest)

            // And the change must be consistent with whichever comparison was made.
            val previousPeriod = trend.previousPeriod
            val previous = trend.previousPercent
            if (previousPeriod == null || previous == null) {
                assertEquals(PromoterTrend.Comparison.NONE, trend.comparison)
                assertNull(trend.changePercentagePoints)
            } else {
                val previousIndex = labels.indexOf(previousPeriod)
                assertNotNull(previousIndex)
                assertEquals(values[previousIndex], previous)
                assertEquals(values[latestIndex] - values[previousIndex], trend.changePercentagePoints)
                val sameQuarterPriorYear = periodKeyOf(latestPeriod) - 12L == periodKeyOf(previousPeriod)
                assertEquals(
                    if (sameQuarterPriorYear) {
                        PromoterTrend.Comparison.SAME_PERIOD_PRIOR_YEAR
                    } else {
                        PromoterTrend.Comparison.PREVIOUS_COLUMN
                    },
                    trend.comparison,
                    "iteration $iteration: $latestPeriod vs $previousPeriod",
                )
            }
        }
    }

    /** `year × 12 + month index`, so period labels compare the same way the code compares them. */
    private fun periodKeyOf(label: String): Long {
        val monthName = label.substringBefore(' ')
        val year = label.substringAfter(' ').toInt()
        val month = listOf(
            "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
        ).indexOf(monthName) + 1
        return year * 12L + month
    }
}