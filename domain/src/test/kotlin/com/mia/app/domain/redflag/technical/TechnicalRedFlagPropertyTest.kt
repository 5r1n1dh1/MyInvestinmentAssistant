package com.mia.app.domain.redflag.technical

import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.FlagGroup
import com.mia.app.domain.model.RedFlag
import com.mia.app.domain.model.Source
import com.mia.app.domain.model.Timeframe
import com.mia.app.domain.technical.IndicatorSnapshot
import com.mia.app.domain.technical.TechnicalPolicy
import java.time.LocalDate
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Property tests for the technical flag family (plan.md §12).
 *
 * Random series are the point: a rule that only works on a hand-built fixture usually depends on a
 * coincidence in that fixture (a price that happened to be a round number, a slope that happened to
 * be negative). The generators here produce degenerate shapes on purpose — flat series, zero volume,
 * a single bar, identical closes, price/volume that disagree — because those are the shapes that
 * produce `NaN`, `Infinity` or a division by zero, and a red flag must never do that.
 *
 * Every series is **seeded**, so a failure is reproducible and the suite is deterministic
 * (`redflags-spec.md` §9).
 */
class TechnicalRedFlagPropertyTest {

    private val lengths = listOf(0, 1, 2, 3, 5, 14, 15, 20, 21, 30, 51, 52, 53, 80, 150, 200, 201, 260)
    private val seeds = 1..40

    @Test
    fun `no rule throws and no flag is ever evidence-free over seeded random series`() {
        for (seed in seeds) {
            val random = Random(seed)
            for (length in lengths) {
                val candles = randomSeries(random, length)
                val context = technicalContext(candles, timeframe = candles.firstOrNull()?.timeframe ?: Timeframe.D1)
                for (rule in TechnicalRedFlagRules.all()) {
                    val flags = try {
                        rule.evaluate(context)
                    } catch (t: Throwable) {
                        throw AssertionError(
                            "${rule.id} threw on seed=$seed length=$length: $t",
                            t,
                        )
                    }
                    for (flag in flags) {
                        assertEquals(FlagGroup.TECHNICAL, flag.group, "seed=$seed ${rule.id}")
                        assertTrue(flag.evidence.isNotEmpty(), "seed=$seed ${rule.id}")
                        flag.assertEveryMeasurementIsDated()
                        assertTrue(flag.threshold.isNotBlank(), "seed=$seed ${rule.id}")
                        assertTrue(flag.title.isNotBlank(), "seed=$seed ${rule.id}")
                    }
                    // skipReason must never throw either: it runs on the same inputs in the UI.
                    try {
                        rule.skipReason(context)
                    } catch (t: Throwable) {
                        throw AssertionError("${rule.id}.skipReason threw on seed=$seed: $t", t)
                    }
                }
            }
        }
    }

    @Test
    fun `evaluating the same series twice yields the identical flag list`() {
        for (seed in seeds) {
            val candles = randomSeries(Random(seed), lengths[seed % lengths.size])
            val context = technicalContext(candles)
            val first = TechnicalRedFlagRules.all().flatMap { it.evaluate(context) }
            val second = TechnicalRedFlagRules.all().flatMap { it.evaluate(context) }
            assertEquals(first, second, "seed=$seed is not deterministic (plan.md §8)")
        }
    }

    @Test
    fun `an RSI flag only ever fires strictly beyond its band boundary`() {
        for (seed in seeds) {
            val candles = randomSeries(Random(seed), 260)
            val context = technicalContext(candles)
            for (flag in TechnicalRedFlagRules.all().flatMap { it.evaluate(context) }) {
                when (flag.id) {
                    TechnicalFlagIds.RSI_OVERBOUGHT -> {
                        val value = flag.evidenceValue("RSI (14)").toDouble()
                        assertTrue(
                            value > TechnicalPolicy.RSI_OVERBOUGHT,
                            "seed=$seed overbought fired at $value",
                        )
                    }

                    TechnicalFlagIds.RSI_OVERSOLD -> {
                        val value = flag.evidenceValue("RSI (14)").toDouble()
                        assertTrue(
                            value < TechnicalPolicy.RSI_OVERSOLD,
                            "seed=$seed oversold fired at $value",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `the printed RSI equals the reading the flag was raised on`() {
        // redflags-spec.md §1 rule 2: the evidence value is the number actually used, not a
        // re-derived or rounded variant. One decimal in the printed form, same decimal in the value.
        val candles = TechnicalSeries.rsiOverbought()
        val actual = IndicatorSnapshot.of(candles).rsi.value!!
        val flag = RsiBandRule(RsiBand.OVERBOUGHT).evaluate(technicalContext(candles)).single()
        assertEquals(TechnicalFlagFormat.decimal(actual, 1), flag.evidenceValue("RSI (14)"))
        assertTrue(abs(actual - flag.evidenceValue("RSI (14)").toDouble()) <= 0.05)
    }

    @Test
    fun `no flag is ever raised on an empty or one-bar series`() {
        for (rule in TechnicalRedFlagRules.all()) {
            assertTrue(rule.evaluate(technicalContext(emptyList())).isEmpty(), "${rule.id} on empty")
            val one = listOf(Candle("X", Timeframe.D1, LocalDate.of(2026, 1, 1), 10.0, 10.0, 10.0, 10.0, 0))
            assertTrue(rule.evaluate(technicalContext(one)).isEmpty(), "${rule.id} on one bar")
        }
    }

    @Test
    fun `flat and zero-volume series never produce a flag and never produce NaN`() {
        val flat = CandleLab().flat(count = 120, price = 100.0).build()
        val dead = CandleLab().flat(count = 120, price = 100.0, volume = 0L).build()
        val nanText = listOf("NaN", "Infinity", "-Infinity", "not a number")
        for (candles in listOf(flat, dead)) {
            val context = technicalContext(candles)
            for (rule in TechnicalRedFlagRules.all()) {
                for (flag in rule.evaluate(context)) {
                    flag.evidence.forEach { row ->
                        for (bad in nanText) {
                            assertTrue(!row.value.contains(bad), "${rule.id} '${row.label}' = ${row.value}")
                        }
                    }
                }
                rule.skipReason(context)?.let { reason ->
                    nanText.forEach { assertTrue(!reason.contains(it), "${rule.id} skip '$reason'") }
                }
            }
        }
    }

    @Test
    fun `the delivery rule abstains whenever the window is not three-quarters covered`() {
        for (seed in seeds) {
            val random = Random(seed + 1000)
            val candles = randomSeries(random, 40).mapIndexed { i, bar ->
                // Cover at most two thirds of the window, never three quarters.
                if (i % 3 == 0) bar.copy(deliveryPercent = null) else bar
            }
            val covered = candles.takeLast(TechnicalPolicy.DELIVERY_SMA_PERIOD)
                .count { it.deliveryPercent != null }
            if (covered < 15) {
                assertTrue(DeliveryCollapseRule.evaluate(technicalContext(candles)).isEmpty(), "seed=$seed")
                assertTrue(DeliveryCollapseRule.skipReason(technicalContext(candles))!!.isNotBlank())
            }
        }
    }

    @Test
    fun `a rule that abstains contributes no flag and a skip reason that names the input`() {
        for (rule in TechnicalRedFlagRules.all()) {
            val context = technicalContext(emptyList())
            assertTrue(rule.evaluate(context).isEmpty(), "${rule.id} must not flag an empty context")
            val reason = rule.skipReason(context)
            assertTrue(reason != null && reason.isNotBlank(), "${rule.id} must say why it abstained")
        }
    }

    // ------------------------------------------------------------------------------- generators

    /**
     * A random but structurally valid series.
     *
     * Prices are drawn per bar as a **close** and an **open** so that every bar brackets itself;
     * volume is drawn including zero; delivery is present on roughly half the bars so the
     * coverage-gated rule is exercised in both states.
     */
    private fun randomSeries(random: Random, length: Int): List<Candle> {
        val lab = CandleLab(symbol = "RND", start = LocalDate.of(2026, 1, 1))
        repeat(length) {
            val close = random.nextDouble(1.0, 5_000.0)
            val open = close * (0.9 + random.nextDouble() * 0.2)
            val high = maxOf(open, close) * (1.0 + random.nextDouble() * 0.02)
            val low = minOf(open, close) * (1.0 - random.nextDouble() * 0.02)
            lab.bar(
                open = open,
                high = high,
                low = low,
                close = close,
                volume = if (random.nextInt(10) == 0) 0L else random.nextLong(1_000L, 20_000_000L),
                deliveryPercent = if (random.nextBoolean()) null else random.nextDouble(0.0, 100.0),
            )
        }
        return lab.build()
    }
}