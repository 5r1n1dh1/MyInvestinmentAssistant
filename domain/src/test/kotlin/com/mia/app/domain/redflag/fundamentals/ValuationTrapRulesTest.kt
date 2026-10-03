package com.mia.app.domain.redflag.fundamentals

import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.Confidence
import com.mia.app.domain.model.FlagGroup
import com.mia.app.domain.model.FlagSeverity
import com.mia.app.domain.model.Ratios
import com.mia.app.domain.model.Sector
import com.mia.app.domain.model.Source
import com.mia.app.domain.model.Timeframe
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two valuation-trap rules.
 *
 * Both are where the product rule bites hardest — plan.md §8.3 wants a factual comparison and
 * forbids any statement about the stock's future — so these assertions check the numbers inside
 * the title, not only that a flag appeared.
 *
 * ### The quarter layout used below
 * Nine **calendar** quarters, 2024Q1 through 2026Q1, which puts the rule's two endpoints at
 * 2025-03-31 (index 4) and 2026-03-31 (index 8) — four quarters apart, both in the past. Every
 * multiple in these tests is therefore a real division of a cached close by a four-quarter EPS
 * sum, which is what makes the arithmetic assertions meaningful.
 */
class ValuationTrapRulesTest {

    // ------------------------------------------------ P/E below band, returns down

    @Test
    fun `a low multiple with a falling ROCE fires and names the derived band`() {
        val rule = LowPeWithFallingReturnsRule(
            RatiosCardSeries(
                rocePercent = mapOf(2022 to 44.0, 2023 to 39.0, 2024 to 22.0, 2025 to 20.0, 2026 to 19.0),
            ),
        )

        val flag = rule.evaluate(
            testContext(sector = Sector.IT, ratios = ratios(pe = 14.0, sector = Sector.IT)),
        ).single()

        assertEquals(FundamentalsFlagIds.LOW_PE_WITH_FALLING_RETURNS, flag.id)
        assertEquals(FlagGroup.VALUATION_TRAP, flag.group)
        assertEquals(FlagSeverity.MEDIUM, flag.severity)
        assertTrue(flag.title.contains("P/E of 14.0×"), flag.title)
        assertTrue(flag.title.contains("Information technology band of 20.0×"), flag.title)
        assertTrue(
            flag.title.contains("ROCE % fell from 39.0 % in FY2023 to 19.0 % in FY2026"),
            flag.title,
        )
        assertTrue(flag.title.contains("-6.7 percentage points a year"), flag.title)
        val evidence = flag.evidence.associate { it.label to it.value }
        assertEquals("14.0×", evidence["Stock P/E"])
        assertEquals("19.0 %", evidence["ROCE % (FY2026)"])
        assertEquals("-6.7 percentage points a year over 3 year(s)", evidence["ROCE % trend, 3 years"])
        assertTrue(evidence.getValue("Sector band used").contains("not a measured sector median"))
        assertTrue(evidence.getValue("Source of P/E").contains("top-ratios"))
        assertEquals(
            Golden.CAPTURE_DATE,
            flag.evidence.first { it.label == "Stock P/E" }.asOf,
            "a live scrape value carries the fetch date, never a fiscal date (spec §11 rule 9)",
        )
    }

    @Test
    fun `a low multiple with improving returns does not fire`() {
        val rule = LowPeWithFallingReturnsRule(
            RatiosCardSeries(rocePercent = mapOf(2024 to 50.0, 2025 to 55.0, 2026 to 60.0)),
        )

        assertTrue(
            rule.evaluate(testContext(sector = Sector.IT, ratios = ratios(pe = 14.0, Sector.IT))).isEmpty(),
        )
    }

    @Test
    fun `a falling multiple above the band does not fire`() {
        val rule = LowPeWithFallingReturnsRule(
            RatiosCardSeries(rocePercent = mapOf(2024 to 50.0, 2025 to 40.0, 2026 to 30.0)),
        )

        assertTrue(
            rule.evaluate(testContext(sector = Sector.IT, ratios = ratios(pe = 24.0, Sector.IT))).isEmpty(),
            "24x is above the IT band of 20x even though returns are falling",
        )
    }

    @Test
    fun `a loss-making latest ROCE is the high severity`() {
        val rule = LowPeWithFallingReturnsRule(
            RatiosCardSeries(rocePercent = mapOf(2024 to 2.0, 2025 to -1.0, 2026 to -4.0)),
        )

        val flag = rule.evaluate(
            testContext(sector = Sector.CEMENT, ratios = ratios(pe = 9.0, Sector.CEMENT)),
        ).single()

        assertEquals(FlagSeverity.HIGH, flag.severity)
        assertTrue(flag.title.contains("Cement band of 18.0×"), flag.title)
        assertTrue(flag.title.contains("-4.0 % in FY2026"), flag.title)
    }

    @Test
    fun `a bank is judged on its own published ROE series, not on ROCE`() {
        // HDFCBANK's `ratios` card is exactly ['ROE %'], so ROE is the only series there is.
        val rule = LowPeWithFallingReturnsRule(Golden.hdfcBankCard)
        val context = testContext(
            symbol = "HDFCBANK",
            sector = Sector.BANKING,
            ratios = Golden.hdfcBankRatios.copy(pe = 11.0),
            derived = Golden.hdfcBankDerived,
        )

        val flag = rule.evaluate(context).single()

        assertTrue(flag.title.contains("P/E of 11.0×"), flag.title)
        assertTrue(flag.title.contains("Banking band of 12.0×"), flag.title)
        assertTrue(flag.title.contains("ROE % fell from 17.0 % in FY2023 to 14.0 % in FY2026"), flag.title)
        assertNotNull(flag.evidence.firstOrNull { it.label == "ROE % (FY2026)" })
        assertNull(flag.evidence.firstOrNull { it.label == "ROCE % (FY2026)" })
    }

    @Test
    fun `tcs stays quiet on the value trap with its real price to earnings of fourteen and a rising ROCE`() {
        val rule = LowPeWithFallingReturnsRule(Golden.tcsCard)
        val context = tcsContext()

        assertEquals(14.0, context.ratios.pe)
        assertTrue(
            rule.evaluate(context).isEmpty(),
            "P/E 14.0 is under the IT band, but ROCE went 59 -> 63 across the window, so this is " +
                "not the shape the book describes",
        )
        assertNull(rule.explainSkip(context))
    }

    @Test
    fun `reliance stays quiet on the value trap because its sector is unresolved`() {
        val rule = LowPeWithFallingReturnsRule(Golden.relianceCard)
        val context = relianceContext()

        assertEquals(21.2, context.ratios.pe)
        assertTrue(rule.evaluate(context).isEmpty())
        val reason = assertNotNull(rule.explainSkip(context))
        assertTrue(reason.contains("Other"), reason)
        assertTrue(reason.contains("sector median"), reason)
    }

    @Test
    fun `the value trap rule cannot evaluate without a profitability series`() {
        val rule = LowPeWithFallingReturnsRule()
        val context = testContext(sector = Sector.IT, ratios = ratios(pe = 14.0, Sector.IT))

        assertTrue(rule.evaluate(context).isEmpty())
        val reason = assertNotNull(rule.explainSkip(context))
        assertTrue(reason.contains("ROCE %"), reason)
        assertTrue(reason.contains("'ratios' card"), reason)
    }

    @Test
    fun `the value trap rule cannot evaluate without a published p e`() {
        val rule = LowPeWithFallingReturnsRule(
            RatiosCardSeries(rocePercent = mapOf(2024 to 50.0, 2026 to 20.0)),
        )
        val context = testContext(sector = Sector.IT, ratios = ratios(pe = null, Sector.IT))

        assertTrue(rule.evaluate(context).isEmpty())
        assertTrue(assertNotNull(rule.explainSkip(context)).contains("Stock P/E"))
    }

    @Test
    fun `a single profitability point is a missing series rather than a flat trend`() {
        val rule = LowPeWithFallingReturnsRule(RatiosCardSeries(rocePercent = mapOf(2026 to 30.0)))
        val context = testContext(sector = Sector.IT, ratios = ratios(pe = 14.0, Sector.IT))

        assertTrue(rule.evaluate(context).isEmpty())
        assertTrue(assertNotNull(rule.explainSkip(context)).contains("two points"))
    }

    @Test
    fun `the derived band table covers every sector except the unresolved one`() {
        assertEquals(
            Sector.entries.toSet() - setOf(Sector.OTHER),
            FundamentalsPolicy.DERIVED_PE_BAND.keys,
        )
        assertTrue(
            FundamentalsPolicy.DERIVED_PE_BAND.values.all { it > 0.0 && it < 100.0 },
            "a band is a multiple of earnings, not a percentage",
        )
    }

    // ------------------------------------------------ P/E rising, earnings falling

    private val peRule = PeRisingWhileEarningsFallRule()

    /** Nine calendar quarters whose latest four sum to 15.00 and whose index 1..4 sum to 23.50. */
    private val fallingEps = listOf(6.0, 6.0, 6.0, 6.5, 5.0, 4.5, 4.0, 3.5, 3.0)

    @Test
    fun `a falling trailing EPS with a rising multiple fires`() {
        val context = peContext(fallingEps, earlierClose = 264.0, latestClose = 190.0, sector = Sector.IT)

        val flag = peRule.evaluate(context).single()

        assertEquals(FundamentalsFlagIds.PE_RISING_WHILE_EARNINGS_FALL, flag.id)
        assertEquals(FlagGroup.VALUATION_TRAP, flag.group)
        assertEquals(FlagSeverity.MEDIUM, flag.severity)
        assertTrue(flag.title.contains("Trailing EPS fell from ₹23.50 to ₹15.00"), flag.title)
        assertTrue(flag.title.contains("31 Mar 2025"), flag.title)
        assertTrue(flag.title.contains("31 Mar 2026"), flag.title)
        assertTrue(flag.title.contains("rose from 11.2× to 12.7×"), flag.title)
        val evidence = flag.evidence.associate { it.label to it.value }
        assertEquals("₹23.50", evidence["EPS, trailing four quarters to 31 Mar 2025"])
        assertEquals("₹15.00", evidence["EPS, trailing four quarters to 31 Mar 2026"])
        assertEquals("11.2×", evidence["P/E on those earnings (31 Mar 2025)"])
        assertEquals("12.7×", evidence["P/E on those earnings (31 Mar 2026)"])
        assertEquals("-36.2 %", evidence["Change in EPS"])
        assertTrue(evidence.getValue("Cyclicality check").contains("not in the cyclical set"))
    }

    @Test
    fun `a multiple up by a quarter is the high severity`() {
        val flag = peRule.evaluate(
            peContext(fallingEps, earlierClose = 235.0, latestClose = 270.0),
        ).single()

        assertEquals(FlagSeverity.HIGH, flag.severity, "270 / 15.00 = 18.0x against 10.0x earlier")
        assertTrue(flag.title.contains("10.0×"), flag.title)
        assertTrue(flag.title.contains("18.0×"), flag.title)
    }

    @Test
    fun `a falling multiple on falling earnings does not fire`() {
        assertTrue(
            peRule.evaluate(
                peContext(fallingEps, earlierClose = 400.0, latestClose = 190.0),
            ).isEmpty(),
            "earnings fell but so did the multiple, which is the trough the book warns about",
        )
    }

    @Test
    fun `a rising multiple on rising earnings does not fire`() {
        assertTrue(
            peRule.evaluate(
                peContext(
                    eps = listOf(3.0, 3.0, 3.0, 3.0, 4.0, 4.5, 5.0, 5.5, 6.0),
                    earlierClose = 300.0,
                    latestClose = 300.0,
                ),
            ).isEmpty(),
        )
    }

    @Test
    fun `a cyclical trough is not reported as falling earnings`() {
        // Latest four quarters sum to 18.00 against 25.00, i.e. a 28 % fall: shallower than the
        // carve-out, so metals treats it as a trough.
        val trough = listOf(5.0, 5.0, 5.0, 5.0, 5.0, 4.8, 4.6, 4.4, 4.2)
        assertTrue(
            peRule.evaluate(
                peContext(trough, earlierClose = 200.0, latestClose = 260.0, sector = Sector.METALS),
            ).isEmpty(),
        )
        assertEquals(
            1,
            peRule.evaluate(
                peContext(trough, earlierClose = 200.0, latestClose = 260.0, sector = Sector.IT),
            ).size,
            "the same shape in IT has no cyclical excuse",
        )
        val deep = listOf(5.0, 5.0, 5.0, 5.0, 5.0, 3.0, 2.6, 2.4, 2.2)
        assertEquals(
            1,
            peRule.evaluate(
                peContext(deep, earlierClose = 200.0, latestClose = 260.0, sector = Sector.METALS),
            ).size,
            "a 59 % fall is past the carve-out even in a cyclical sector",
        )
    }

    @Test
    fun `the multiple rule cannot evaluate without a cached close`() {
        val context = peContext(fallingEps, earlierClose = 264.0, latestClose = 190.0, withCandles = false)

        assertTrue(peRule.evaluate(context).isEmpty())
        val reason = assertNotNull(peRule.explainSkip(context))
        assertTrue(reason.contains("no price cache"), reason)
    }

    @Test
    fun `the multiple rule abandons the comparison when any quarterly EPS is not positive`() {
        val context = peContext(
            eps = listOf(6.0, 6.0, 6.0, 6.5, 5.0, 4.5, -1.0, 3.5, 3.0),
            earlierClose = 264.0,
            latestClose = 190.0,
        )

        assertTrue(peRule.evaluate(context).isEmpty(), "trailing EPS of zero or less has no multiple")
        assertTrue(assertNotNull(peRule.explainSkip(context)).contains("non-positive"))
    }

    @Test
    fun `the multiple rule needs five quarters and says how many it has`() {
        val context = peContext(listOf(5.0, 4.5, 4.0, 3.5), earlierClose = 200.0, latestClose = 180.0)

        assertTrue(peRule.evaluate(context).isEmpty())
        val reason = assertNotNull(peRule.explainSkip(context))
        assertTrue(reason.contains("at least 5"), reason)
        assertTrue(reason.contains("carries 4"), reason)
    }

    @Test
    fun `a cached close outside the tolerance from the quarter end is not used`() {
        val inside = peContext(fallingEps, earlierClose = 264.0, latestClose = 190.0)
        val outside = peContext(fallingEps, earlierClose = 264.0, latestClose = 190.0, dateShiftDays = 20)

        assertEquals(1, peRule.evaluate(inside).size)
        assertTrue(
            peRule.evaluate(outside).isEmpty(),
            "a bar 20 days from the quarter end is outside the " +
                "${FundamentalsPolicy.PRICE_MATCH_TOLERANCE_DAYS}-day window",
        )
    }

    @Test
    fun `a weekly bar is preferred over a nearer daily bar`() {
        val base = peContext(fallingEps, earlierClose = 264.0, latestClose = 190.0)
        val withBoth = base.copy(
            candles = base.candles + listOf(
                weeklyCandle(LocalDate.of(2026, 3, 31), 400.0),
                weeklyCandle(LocalDate.of(2025, 3, 31), 264.0),
            ),
        )

        val flag = peRule.evaluate(withBoth).single()
        val evidence = flag.evidence.associate { it.label to it.value }

        // The weekly 2026 close of 400 gives 400 / 15.00 = 26.7x, not the daily bar's 12.7x.
        assertEquals("26.7×", evidence["P/E on those earnings (31 Mar 2026)"])
    }

    @Test
    fun `both valuation flags are fully evidenced on one context`() {
        val context = peContext(
            eps = fallingEps,
            earlierClose = 264.0,
            latestClose = 190.0,
            sector = Sector.IT,
            withRatios = ratios(pe = 14.0, sector = Sector.IT),
        )

        val flags = FundamentalsRedFlagRules.all(
            RatiosCardSeries(rocePercent = mapOf(2024 to 50.0, 2025 to 35.0, 2026 to 20.0)),
        ).flatMap { it.evaluate(context) }

        assertEquals(2, flags.size, flags.map { it.id }.toString())
        assertTrue(flags.all { it.group == FlagGroup.VALUATION_TRAP })
        for (flag in flags) {
            assertTrue(flag.evidence.isNotEmpty())
            for (row in flag.evidence) assertEvidenceRow(flag, row)
            assertTrue(flag.threshold.isNotBlank())
            assertTrue(flag.title.any { it.isDigit() }, flag.title)
        }
    }

    // --------------------------------------------------------------------------

    private fun ratios(pe: Double?, sector: Sector): Ratios = Ratios(
        symbol = "TESTCO",
        sector = sector,
        asOf = Golden.CAPTURE_DATE,
        source = Source.SCREENER_IN,
        confidence = Confidence.SINGLE,
        pe = pe,
    )

    /**
     * A context for the multiple rule: [eps] quarters from 2024Q1, plus a cached close at each
     * of the two quarter-end dates the rule compares.
     *
     * With the full nine quarters the endpoints are 2025-03-31 and 2026-03-31, four quarters
     * apart. [dateShiftDays] moves both closes away from their quarter end, for the tolerance
     * case; a weekly bar is added by the caller, which is how the weekly preference is shown.
     */
    private fun peContext(
        eps: List<Double>,
        earlierClose: Double,
        latestClose: Double,
        sector: Sector = Sector.OTHER,
        withCandles: Boolean = true,
        dateShiftDays: Long = 0,
        withRatios: Ratios? = null,
    ) = testContext(
        symbol = "TESTCO",
        sector = sector,
        ratios = withRatios ?: ratios(pe = 20.0, sector = sector),
        quarters = eps.mapIndexed { index, value ->
            quarter(2024 + index / 4, index % 4 + 1, value)
        },
        candles = if (!withCandles) {
            emptyList()
        } else {
            listOf(
                dailyCandle(LocalDate.of(2026, 3, 31).plusDays(dateShiftDays), latestClose),
                dailyCandle(LocalDate.of(2025, 3, 31).plusDays(dateShiftDays), earlierClose),
            )
        },
    )
}

/** A weekly bar, used to prove the rule prefers a weekly close to a daily one. */
internal fun weeklyCandle(date: LocalDate, close: Double): Candle = Candle(
    symbol = "TESTCO",
    timeframe = Timeframe.W1,
    date = date,
    open = close,
    high = close,
    low = close,
    close = close,
    volume = 1_000L,
)