package com.mia.app.domain.redflag.fundamentals

import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.CashFlowDerived
import com.mia.app.domain.model.Confidence
import com.mia.app.domain.model.QuarterRecord
import com.mia.app.domain.model.Ratios
import com.mia.app.domain.model.Sector
import com.mia.app.domain.model.Source
import com.mia.app.domain.model.Timeframe
import com.mia.app.domain.redflag.RedFlagContext
import java.time.LocalDate
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * plan.md §12's property tests, applied to the fundamentals flags.
 *
 * The invariants, and why each one matters for *this* package:
 *
 * 1. **No rule ever throws.** A crash inside one rule is caught by the engine and reported, but a
 *    crash inside [FundamentalsRule.explainSkip] would be a crash on the coverage screen. The
 *    inputs here include the adversarial ones — empty maps, zero denominators, `NaN`, negative
 *    EPS, a `TTM`-only series, quarters numbered 5 — because those are what a parser hands over
 *    after a partial page load.
 * 2. **No flag ever carries empty, blank or undated evidence.** `RedFlag.init` enforces
 *    non-emptiness; the blank-label, blank-value and missing-as-of halves are this package's own
 *    contract and are what plan.md §1.4 rule 3 turns on.
 * 3. **Every title contains a digit.** plan.md §8.3: *"Always one comparative. A single value is
 *    not a flag."*
 * 4. **A suppressed sector never produces the suppressed flag**, over the whole sector list.
 *
 * The generator is seeded, so a failure is reproducible from the message.
 */
class FundamentalsPropertyTest {

    private val iterations = 400
    private val seed = 20261001

    @Test
    fun `no rule ever throws and no flag ever carries bad evidence`() {
        val random = Random(seed)
        var flagsSeen = 0
        var skipsSeen = 0

        repeat(iterations) { iteration ->
            val sector = Sector.entries[random.nextInt(Sector.entries.size)]
            val card = randomCard(random)
            val context = randomContext(random, sector)
            for (rule in FundamentalsRedFlagRules.all(card)) {
                val flags = try {
                    rule.evaluate(context)
                } catch (t: Throwable) {
                    throw AssertionError(
                        "iteration $iteration: ${rule.id} threw on a randomised context: $t",
                        t,
                    )
                }
                for (flag in flags) {
                    flagsSeen++
                    assertTrue(flag.evidence.isNotEmpty(), "${flag.id}: empty evidence")
                    for (row in flag.evidence) {
                        assertTrue(row.label.isNotBlank(), "${flag.id}: blank evidence label")
                        assertTrue(row.value.isNotBlank(), "${flag.id} / ${row.label}: blank value")
                        assertTrue(row.asOf != null, "${flag.id} / ${row.label}: no as-of date")
                    }
                    assertTrue(
                        flag.title.any { it.isDigit() },
                        "${flag.id}: title without a number -> ${flag.title}",
                    )
                    assertTrue(flag.threshold.isNotBlank(), "${flag.id}: blank threshold")
                }
                val skip = try {
                    rule.explainSkip(context)
                } catch (t: Throwable) {
                    throw AssertionError(
                        "iteration $iteration: ${rule.id}.explainSkip threw: $t",
                        t,
                    )
                }
                if (skip != null) {
                    skipsSeen++
                    assertTrue(skip.isNotBlank(), "${rule.id}: blank skip reason")
                }
            }
        }

        assertTrue(flagsSeen > iterations / 4, "the generator never tripped a flag ($flagsSeen)")
        assertTrue(skipsSeen > iterations / 4, "the generator never produced a skip ($skipsSeen)")
    }

    @Test
    fun `the full engine over randomised contexts is failure free and duplicate free`() {
        val random = Random(seed + 1)

        repeat(iterations) { iteration ->
            val sector = Sector.entries[random.nextInt(Sector.entries.size)]
            val context = randomContext(random, sector)
            val report = FundamentalsRedFlagEngine(ratiosCard = randomCard(random)).evaluate(context)

            assertEquals(emptyMap(), report.failures, "iteration $iteration: ${report.failures}")
            assertEquals(
                report.flags.map { it.id }.distinct().size,
                report.flags.size,
                "iteration $iteration: duplicate flag ids",
            )
            assertTrue(report.skipped.none { it.reason.isBlank() })
        }
    }

    @Test
    fun `aSuppressedSectorNeverProducesItsSuppressedFlag`() {
        val random = Random(seed + 2)
        val distressed = CashFlowDerived(
            cfoByYear = mapOf(2024 to -1.0, 2025 to -2.0, 2026 to -3.0),
            patByYear = mapOf(2024 to 1.0, 2025 to 2.0, 2026 to 3.0),
            debtByYear = mapOf(2025 to 30_000.0, 2026 to 40_000.0),
            equityByYear = mapOf(2025 to 10_000.0, 2026 to 10_000.0),
            ebitByYear = mapOf(2026 to 500.0),
            interestByYear = mapOf(2026 to 1_000.0),
            otherIncomeByYear = mapOf(2026 to 900.0),
        )
        val card = RatiosCardSeries(
            debtorDays = mapOf(2025 to 20.0, 2026 to 90.0),
            inventoryDays = mapOf(2025 to 20.0, 2026 to 90.0),
            rocePercent = mapOf(2024 to 40.0, 2025 to 30.0, 2026 to 20.0),
        )

        repeat(50) {
            val sector = Sector.entries[random.nextInt(Sector.entries.size)]
            val context = testContext(sector = sector, derived = distressed)
            val ids = FundamentalsRedFlagRules.all(card).flatMap { rule ->
                rule.evaluate(context).map { it.id }
            }.toSet()

            if (sector == Sector.BANKING) {
                assertTrue(FundamentalsFlagIds.DEBT_TO_EQUITY_SPIKE !in ids, "bank D/E")
                assertTrue(FundamentalsFlagIds.INTEREST_COVERAGE_BELOW_2 !in ids, "bank coverage")
            }
            if (sector in FundamentalsPolicy.INTEREST_COVERAGE_SUPPRESSED_SECTORS) {
                assertTrue(FundamentalsFlagIds.INTEREST_COVERAGE_BELOW_2 !in ids, sector.name)
            }
            if (sector in FundamentalsPolicy.OTHER_INCOME_SUPPRESSED_SECTORS) {
                assertTrue(FundamentalsFlagIds.OTHER_INCOME_AS_PCT_OF_PAT !in ids, sector.name)
            }
            if (sector in FundamentalsPolicy.WORKING_CAPITAL_NORM_SUPPRESSED_SECTORS) {
                assertTrue(FundamentalsFlagIds.RECEIVABLES_GROWING_FASTER_THAN_REVENUE !in ids, sector.name)
                assertTrue(FundamentalsFlagIds.INVENTORY_DAYS_RISING !in ids, sector.name)
            }
            if (sector == Sector.OTHER) {
                assertTrue(FundamentalsFlagIds.LOW_PE_WITH_FALLING_RETURNS !in ids, "OTHER band")
            }
        }
    }

    // ------------------------------------------------------------------ generator

    /**
     * A context assembled from values chosen to break a rule if it can be broken: `NaN`, infinite
     * values, zeros where a ratio divides, negative days, an out-of-range quarter number, and
     * series that skip years.
     */
    private fun randomContext(random: Random, sector: Sector): RedFlagContext {
        val weird = doubleArrayOf(0.0, -0.0, 1.0, -1.0, 1e9, -1e9, 1e-9, 12_345.678)
        fun value(): Double = when (random.nextInt(8)) {
            0 -> weird[random.nextInt(weird.size)]
            1 -> Double.NaN
            2 -> Double.POSITIVE_INFINITY
            3 -> Double.NEGATIVE_INFINITY
            else -> random.nextDouble(-50_000.0, 50_000.0)
        }

        fun series(years: IntRange): Map<Int, Double> {
            val result = LinkedHashMap<Int, Double>()
            for (year in years) {
                if (random.nextInt(4) == 0) continue // a missing year is normal, not an error
                result[year] = value()
            }
            return result
        }

        val years = 2019..2026
        val derived = CashFlowDerived(
            cfoByYear = series(years),
            patByYear = series(years),
            fcfByYear = series(years),
            cfoToOpByYear = series(years),
            receivablesByYear = series(years),
            inventoryByYear = series(years),
            revenueByYear = series(years),
            otherIncomeByYear = series(years),
            totalAssetsByYear = series(years),
            goodwillByYear = series(years),
            debtByYear = series(years),
            equityByYear = series(years),
            interestByYear = series(years),
            ebitByYear = series(years),
            sharesOutstanding = if (random.nextInt(2) == 0) null else random.nextDouble(),
            promoterPledgePercent = if (random.nextInt(3) == 0) random.nextDouble() else null,
        )
        val quarters = (0 until random.nextInt(14)).map {
            QuarterRecord(
                year = 2024 + random.nextInt(3),
                quarter = random.nextInt(1, 6), // 5 is not a real quarter and must be rejected
                eps = when (random.nextInt(6)) {
                    0 -> null
                    1 -> 0.0
                    2 -> -value()
                    else -> value()
                },
            )
        }
        val candles = (0 until random.nextInt(8)).map {
            Candle(
                symbol = "RANDOM",
                timeframe = if (random.nextBoolean()) Timeframe.D1 else Timeframe.W1,
                date = LocalDate.of(2025 + random.nextInt(2), 1 + random.nextInt(12), 1 + random.nextInt(28)),
                open = random.nextDouble(),
                high = random.nextDouble(),
                low = random.nextDouble(),
                close = random.nextDouble(),
                volume = random.nextLong(),
            )
        }
        return testContext(
            symbol = "RANDOM",
            sector = sector,
            ratios = Ratios(
                symbol = "RANDOM",
                sector = sector,
                asOf = Golden.CAPTURE_DATE,
                source = Source.SCREENER_IN,
                confidence = Confidence.SINGLE,
                roce = if (random.nextBoolean()) random.nextDouble() else null,
                roe = if (random.nextBoolean()) random.nextDouble() else null,
                pe = when (random.nextInt(4)) {
                    0 -> null
                    1 -> random.nextDouble()
                    else -> random.nextDouble(-5.0, 100.0)
                },
                marketCap = if (random.nextBoolean()) random.nextDouble() else null,
            ),
            derived = derived,
            quarters = quarters,
            candles = candles,
            today = Golden.CAPTURE_DATE.plusDays(random.nextLong(-400, 400)),
        )
    }

    private fun randomCard(random: Random): RatiosCardSeries? {
        if (random.nextInt(4) == 0) return null // the parser may not have run yet
        fun series(): Map<Int, Double> {
            val result = LinkedHashMap<Int, Double>()
            for (year in 2019..2026) {
                if (random.nextInt(3) == 0) continue
                result[year] = random.nextDouble(-40.0, 400.0)
            }
            return result
        }
        return RatiosCardSeries(
            debtorDays = series(),
            inventoryDays = if (random.nextBoolean()) series() else emptyMap(),
            daysPayable = if (random.nextBoolean()) series() else emptyMap(),
            rocePercent = if (random.nextBoolean()) series() else emptyMap(),
            roePercent = if (random.nextBoolean()) series() else emptyMap(),
        )
    }
}