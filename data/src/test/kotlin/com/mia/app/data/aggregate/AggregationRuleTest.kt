package com.mia.app.data.aggregate

import com.mia.app.data.aggregate.AggregationFixtures.AS_OF
import com.mia.app.data.history.YahooFailureReason
import com.mia.app.domain.model.Confidence
import com.mia.app.domain.model.Source
import com.mia.app.domain.model.Timeframe
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The tolerance boundary and the arithmetic, isolated from any source.
 *
 * The 5.00% / 5.01% pair is the whole point: plan.md §5.1 says a conflict is *"more than 5%
 * divergence"*, so exactly 5.00% must be **agreed**. A rule written as `>=` would put TCS P/E
 * (7.18%) and RELIANCE P/E (0.27%) on the same side of a boundary nothing in the plan measured,
 * and would flag a pair of sources that genuinely corroborate each other.
 */
class AggregationRuleTest {

    // ------------------------------------------------------------------ the boundary

    @Test
    fun `exactly five percent divergence is AGREED`() {
        // |105.0 − 100.0| ÷ 100.0 = 0.05 exactly in binary floating point.
        assertEquals(0.05, AggregationRule.divergence(105.0, 100.0))
        assertEquals(5.0, AggregationRule.divergencePercent(105.0, 100.0), "exactly 5.0%, not 5.0000001%")
        assertEquals(Confidence.AGREED, AggregationRule.verdict(105.0, 100.0))
    }

    @Test
    fun `five point zero one percent divergence is CONFLICT`() {
        val divergence = AggregationRule.divergence(105.01, 100.0)!!
        assertTrue(divergence > 0.05, "5.01% must exceed the tolerance")
        assertEquals(Confidence.CONFLICT, AggregationRule.verdict(105.01, 100.0))
    }

    @Test
    fun `the boundary is symmetric in which side is the shown value`() {
        // 100.0 shown against 105.0 is the same gap measured from the other base.
        assertEquals(Confidence.CONFLICT, AggregationRule.verdict(100.0, 94.7619))
        assertEquals(Confidence.AGREED, AggregationRule.verdict(100.0, 95.2381))
    }

    @Test
    fun `the tolerance constant is five percent and documented as a fraction`() {
        assertEquals(0.05, AggregationRule.DIVERGENCE_TOLERANCE)
        assertEquals(5.0, AggregationRule.DIVERGENCE_TOLERANCE_PERCENT)
    }

    // ------------------------------------------------------------------ the three verdicts

    @Test
    fun `one side only is SINGLE and divergence is undefined`() {
        assertEquals(Confidence.SINGLE, AggregationRule.verdict(8.91, null))
        assertEquals(Confidence.SINGLE, AggregationRule.verdict(null, 8.91))
        assertNull(AggregationRule.divergence(8.91, null))
        assertNull(AggregationRule.divergence(null, 8.91))
    }

    @Test
    fun `neither side is no row at all`() {
        assertNull(AggregationRule.verdict(null, null))
        assertNull(AggregationRule.divergence(null, null))
    }

    @Test
    fun `identical values are AGREED with zero divergence`() {
        assertEquals(0.0, AggregationRule.divergence(21.2, 21.2))
        assertEquals(Confidence.AGREED, AggregationRule.verdict(21.2, 21.2))
    }

    @Test
    fun `a zero alternate is a conflict even though no percentage exists`() {
        // 0 against 21 is not agreement, and an infinite percentage chip would be nonsense.
        assertNull(AggregationRule.divergence(21.0, 0.0))
        assertEquals(Confidence.CONFLICT, AggregationRule.verdict(21.0, 0.0))
        assertEquals(Confidence.AGREED, AggregationRule.verdict(0.0, 0.0))
    }

    @Test
    fun `a non-finite value produces no percentage rather than NaN`() {
        assertNull(AggregationRule.divergence(Double.NaN, 1.0))
        assertNull(AggregationRule.divergence(Double.POSITIVE_INFINITY, 1.0))
    }

    @Test
    fun `divergence is measured against the lower-precedence value`() {
        // All four measured rows of plan.md §5.1 fall out of this one formula.
        assertEquals(0.0257, pct(1168.0, 1167.7), "RELIANCE price 0.03%")
        assertEquals(0.2743, pct(21.2, 21.142), "RELIANCE P/E 0.27%")
        assertEquals(7.1803, pct(14.0, 15.083), "TCS P/E 7.18%")
        assertEquals(8.4976, pct(51.8, 47.743), "TCS ROE 8.50%")

        // Exactly the two decimals plan.md §5.1 recorded.
        assertEquals(0.03, round2(pct(1168.0, 1167.7)))
        assertEquals(0.27, round2(pct(21.2, 21.142)))
        assertEquals(7.18, round2(pct(14.0, 15.083)))
        assertEquals(8.50, round2(pct(51.8, 47.743)))

        // Dividing by the *shown* value instead would put TCS P/E at 7.74% and TCS ROE at 7.83%,
        // i.e. neither of the numbers the plan measured. That is the whole reason the base is
        // stated as the lower-precedence value.
        assertTrue(round2(pct(14.0, 15.083)) != 7.74)
        assertTrue(round2(pct(51.8, 47.743)) != 7.83)
        assertEquals(MEASURED.size, 4)
    }

    private fun pct(shown: Double, other: Double): Double =
        Math.round(AggregationRule.divergencePercent(shown, other)!! * 10_000.0) / 10_000.0

    /**
     * The measured deltas of plan.md §5.1 recomputed from its own inputs.
     *
     * The plan records two decimal places, so those are what is asserted; the four-decimal form is
     * pinned here too, so that a change to the *base* of the ratio (which would still round to the
     * same two decimals for two of the four rows) cannot pass unnoticed.
     */
    private val MEASURED = mapOf(
        "RELIANCE price" to (1168.0 to 1167.7),
        "RELIANCE P/E" to (21.2 to 21.142),
        "TCS P/E" to (14.0 to 15.083),
        "TCS ROE" to (51.8 to 47.743),
    )

    // ------------------------------------------------------------------ Merged construction

    @Test
    fun `a conflict carries the primary as the value and the other as the alternate`() {
        val merged = AggregationRule.merged(
            shown = 14.0,
            other = 15.083,
            shownSource = Source.SCREENER_IN,
            otherSource = Source.YAHOO,
            shownAsOf = AS_OF,
            otherAsOf = AS_OF,
            today = AS_OF,
            kind = com.mia.app.domain.ratios.FreshnessKind.FUNDAMENTALS_SNAPSHOT,
        )

        assertNotNull(merged)
        assertEquals(14.0, merged.value)
        assertEquals(Source.SCREENER_IN, merged.source)
        assertEquals(Confidence.CONFLICT, merged.confidence)
        assertEquals(15.083, merged.alternate)
        assertEquals(Source.YAHOO, merged.alternateSource)
        assertTrue(merged.hasConflict)
    }

    @Test
    fun `agreement carries no alternate`() {
        val merged = AggregationRule.merged(
            shown = 21.2,
            other = 21.142,
            shownSource = Source.SCREENER_IN,
            otherSource = Source.YAHOO,
            shownAsOf = AS_OF,
            otherAsOf = AS_OF,
            today = AS_OF,
            kind = com.mia.app.domain.ratios.FreshnessKind.FUNDAMENTALS_SNAPSHOT,
        )

        assertNotNull(merged)
        assertNull(merged.alternate)
        assertFalse(merged.hasConflict)
    }

    @Test
    fun `staleness overrides a conflict on the confidence slot but not on the report`() {
        val stale = AS_OF.minusDays(30)
        val merged = AggregationRule.merged(
            shown = 14.0,
            other = 15.083,
            shownSource = Source.SCREENER_IN,
            otherSource = Source.YAHOO,
            shownAsOf = stale,
            otherAsOf = stale,
            today = AS_OF,
            kind = com.mia.app.domain.ratios.FreshnessKind.FUNDAMENTALS_SNAPSHOT,
        )

        assertEquals(Confidence.STALE, merged?.confidence)
        assertEquals(15.083, merged?.alternate, "the alternate survives: the chip needs it")
    }

    @Test
    fun `the as-of date follows the side the shown value came from`() {
        val early = AS_OF.minusDays(2)
        val late = AS_OF.minusDays(1)

        val fromOther = AggregationRule.merged(
            shown = null,
            other = 8.91,
            shownSource = Source.SCREENER_IN,
            otherSource = Source.YAHOO,
            shownAsOf = early,
            otherAsOf = late,
            today = AS_OF,
            kind = com.mia.app.domain.ratios.FreshnessKind.FUNDAMENTALS_SNAPSHOT,
        )

        assertEquals(late, fromOther?.asOf, "the value is Yahoo's, so Yahoo's date is the as-of")
    }

    @Test
    fun `single never attaches an alternate`() {
        val merged = AggregationRule.single(
            value = 8.91,
            source = Source.SCREENER_IN,
            asOf = AS_OF,
            today = AS_OF,
            kind = com.mia.app.domain.ratios.FreshnessKind.FUNDAMENTALS_SNAPSHOT,
        )

        assertEquals(Confidence.SINGLE, merged?.confidence)
        assertNull(merged?.alternate)
        assertNull(AggregationRule.single(null, Source.SCREENER_IN, AS_OF, AS_OF, com.mia.app.domain.ratios.FreshnessKind.PRICE))
    }

    // ------------------------------------------------------------------ the report

    @Test
    fun `a divergence chip states both numbers both sources and the gap`() {
        val field = DivergentField(
            field = "PE",
            shown = 14.0,
            shownSource = Source.SCREENER_IN,
            alternate = 15.083,
            alternateSource = Source.YAHOO,
            divergence = AggregationRule.divergence(14.0, 15.083),
            unit = "×",
            asOf = AS_OF,
        )

        val chip = field.chip()
        assertTrue(chip.contains("14.000"), chip)
        assertTrue(chip.contains("SCREENER_IN"), chip)
        assertTrue(chip.contains("15.083"), chip)
        assertTrue(chip.contains("YAHOO"), chip)
        assertTrue(chip.contains("7.18% apart"), chip)
        assertEquals(7.18, Math.round(field.divergencePercent!! * 100.0) / 100.0)
    }

    @Test
    fun `a zero alternate chip admits it is not a percentage`() {
        val field = DivergentField("ROE", 21.0, Source.SCREENER_IN, 0.0, Source.YAHOO, null, "%", AS_OF)

        assertTrue(field.chip().contains("not comparable"), field.chip())
    }

    private fun round2(value: Double): Double = Math.round(value * 100.0) / 100.0

    @Test
    fun `an empty report is not a conflict`() {
        val report = DivergenceReport("RELIANCE", AS_OF)

        assertFalse(report.hasConflicts)
        assertEquals(0, report.conflictCount)
        assertNull(report.chipFor("PE"))
        assertEquals(emptyList(), report.worstFirst())
    }
}

/**
 * The price half of plan.md §5.1: OHLCV comes from NSE, Yahoo is the fallback, and the two are
 * compared per field so a disagreement is visible rather than averaged away.
 */
class PriceAggregatorTest {

    @Test
    fun `the NSE bar wins every field it published`() {
        val result = PriceAggregator.mergeSession(
            symbol = "RELIANCE",
            bhavcopy = AggregationFixtures.bhavcopy(listOf(AggregationFixtures.candle(close = 1168.0))),
            yahoo = AggregationFixtures.yahooHistory(
                listOf(AggregationFixtures.candle(close = 1167.7, source = Source.YAHOO)),
            ),
            today = AS_OF,
        )

        val bar = assertNotNull(result.candle)
        assertEquals(Source.NSE_BHAVCOPY, bar.source, "plan.md §5.1: price/OHLCV → NSE")
        assertEquals(1168.0, bar.close)
        assertEquals(1180.10, bar.open)
        assertEquals(1183.90, bar.high)
        assertEquals(1160.80, bar.low)
        assertEquals(16_771_221L, bar.volume)
        assertEquals(Timeframe.D1, bar.timeframe)
        assertEquals(LocalDate.of(2026, 10, 1), bar.date)
        assertTrue(bar.isValid)
    }

    @Test
    fun `a conflicting close surfaces a chip and keeps the NSE value`() {
        val result = PriceAggregator.mergeSession(
            symbol = "RELIANCE",
            bhavcopy = AggregationFixtures.bhavcopy(listOf(AggregationFixtures.candle(close = 1000.0))),
            yahoo = AggregationFixtures.yahooHistory(
                listOf(AggregationFixtures.candle(close = 1200.0, source = Source.YAHOO)),
            ),
            today = AS_OF,
        )

        val close = result.field(PriceFieldId.CLOSE)!!
        assertEquals(1000.0, close.merged?.value)
        assertEquals(Confidence.CONFLICT, close.confidence)
        assertEquals(1200.0, close.merged?.alternate)
        assertTrue(result.hasConflicts)
        assertEquals(listOf("CLOSE"), result.report.conflictFields)
        // 16.67%, not 20%: the gap is measured against the greyed value (plan.md §5.1's formula).
        assertTrue(close.chip()!!.contains("16.67%"), close.chip()!!)
    }

    @Test
    fun `a different session is not a disagreement`() {
        val result = PriceAggregator.mergeSession(
            symbol = "RELIANCE",
            bhavcopy = AggregationFixtures.bhavcopy(
                listOf(AggregationFixtures.candle(date = LocalDate.of(2026, 10, 1), close = 1167.70)),
            ),
            yahoo = AggregationFixtures.yahooHistory(
                listOf(AggregationFixtures.candle(date = LocalDate.of(2026, 9, 30), close = 900.0, source = Source.YAHOO)),
            ),
            today = AS_OF,
        )

        assertFalse(result.hasConflicts, "two different sessions are not two opinions")
        assertTrue(
            result.report.notes.any { it.contains("not two opinions") },
            result.report.notes.toString(),
        )
    }

    @Test
    fun `Yahoo alone still produces an NSE-less bar tagged with its own source`() {
        val result = PriceAggregator.mergeSession(
            symbol = "RELIANCE",
            bhavcopy = AggregationFixtures.bhavcopy(emptyList()),
            yahoo = AggregationFixtures.yahooHistory(
                listOf(AggregationFixtures.candle(close = 1167.7, source = Source.YAHOO)),
            ),
            today = AS_OF,
        )

        val bar = assertNotNull(result.candle)
        assertEquals(Source.YAHOO, bar.source, "plan.md §1.4 rule 3: never hide where a number came from")
        assertEquals(1167.7, bar.close)
        assertTrue(
            result.report.notes.any { it.contains("comes from Yahoo alone") },
            result.report.notes.toString(),
        )
    }

    @Test
    fun `the NSE bhavcopy alone produces a bar tagged NSE_BHAVCOPY`() {
        val result = PriceAggregator.mergeSession(
            symbol = "RELIANCE",
            bhavcopy = AggregationFixtures.bhavcopy(listOf(AggregationFixtures.candle())),
            yahoo = null,
            today = AS_OF,
        )

        val bar = assertNotNull(result.candle)
        assertEquals(Source.NSE_BHAVCOPY, bar.source)
        assertEquals(1167.70, bar.close)
        assertFalse(result.hasConflicts)
        assertTrue(result.report.notes.any { it.contains("Yahoo alone") == false }, result.report.notes.toString())
    }

    @Test
    fun `neither source produces no bar rather than a zero bar`() {
        val result = PriceAggregator.mergeSession(
            symbol = "RELIANCE",
            bhavcopy = null,
            yahoo = null,
            today = AS_OF,
        )

        assertNull(result.candle)
        assertNull(result.close)
    }

    @Test
    fun `a missing delivery percentage stays null so no delivery-collapse flag can fire`() {
        val result = PriceAggregator.mergeSession(
            symbol = "RELIANCE",
            bhavcopy = AggregationFixtures.bhavcopy(listOf(AggregationFixtures.candle())),
            yahoo = null,
            today = AS_OF,
        )

        assertNull(result.candle?.deliveryPercent)
        assertTrue(
            result.report.notes.any { it.contains("delivery percentage is absent") },
            result.report.notes.toString(),
        )
        assertEquals(Confidence.SINGLE, result.field(PriceFieldId.DELIVERY_PERCENT)?.confidence ?: Confidence.SINGLE)
        assertNull(result.field(PriceFieldId.DELIVERY_PERCENT)?.merged)
    }

    @Test
    fun `Yahoo publishes no delivery figure so that field is never compared`() {
        val result = PriceAggregator.mergeSession(
            symbol = "RELIANCE",
            bhavcopy = AggregationFixtures.bhavcopy(listOf(AggregationFixtures.candle())),
            yahoo = AggregationFixtures.yahooHistory(),
            today = AS_OF,
        )

        val delivery = result.field(PriceFieldId.DELIVERY_PERCENT)!!
        assertNull(delivery.yahoo, "plan.md §3.5: only the legacy NSE file has DELIV_PER")
        assertNull(delivery.divergence)
    }

    @Test
    fun `a stale price is STALE so a two-day-old close cannot read as today's`() {
        val result = PriceAggregator.mergeSession(
            symbol = "RELIANCE",
            bhavcopy = AggregationFixtures.bhavcopy(
                listOf(AggregationFixtures.candle(date = AS_OF.minusDays(3), close = 1167.70)),
            ),
            yahoo = null,
            today = AS_OF,
        )

        assertEquals(Confidence.STALE, result.close?.confidence)
        // plan.md §10's price window is one day, and the boundary is inclusive.
        val fresh = PriceAggregator.mergeSession(
            symbol = "RELIANCE",
            bhavcopy = AggregationFixtures.bhavcopy(listOf(AggregationFixtures.candle(date = AS_OF.minusDays(1)))),
            yahoo = null,
            today = AS_OF,
        )
        assertEquals(Confidence.SINGLE, fresh.close?.confidence)
    }

    @Test
    fun `mergeSeries prefers NSE per date and uses Yahoo to fill the rest`() {
        val nse = listOf(AggregationFixtures.candle(date = LocalDate.of(2026, 10, 1), close = 1167.70))
        val yahoo = listOf(
            AggregationFixtures.candle(date = LocalDate.of(2026, 9, 30), close = 1160.0, source = Source.YAHOO),
            AggregationFixtures.candle(date = LocalDate.of(2026, 10, 1), close = 1167.7, source = Source.YAHOO),
            AggregationFixtures.candle(date = LocalDate.of(2026, 9, 29), close = 1150.0, source = Source.YAHOO),
        )

        val merged = PriceAggregator.mergeSeries(nse, yahoo)

        assertEquals(3, merged.size)
        assertEquals(
            listOf(LocalDate.of(2026, 9, 29), LocalDate.of(2026, 9, 30), LocalDate.of(2026, 10, 1)),
            merged.map { it.date },
            "the result is sorted by date whatever the input order",
        )
        assertEquals(Source.YAHOO, merged.first().source)
        val latest = merged.last()
        assertEquals(Source.NSE_BHAVCOPY, latest.source, "NSE wins the dates it covers")
        assertEquals(1167.70, latest.close, "and it wins with its own value, not Yahoo's")
    }

    @Test
    fun `the unavailable price fields are named`() {
        assertTrue(
            PriceAggregator.NOT_OBTAINABLE.any { it.field.contains("DELIVERY") },
            "the delivery gap must be explained, not left blank",
        )
    }

    @Test
    fun `a Yahoo failure is not a price failure`() {
        val result = PriceAggregator.mergeSession(
            symbol = "RELIANCE",
            bhavcopy = AggregationFixtures.bhavcopy(listOf(AggregationFixtures.candle())),
            yahoo = AggregationFixtures.yahooHistoryFailed(failure = YahooFailureReason.RATE_LIMITED),
            today = AS_OF,
        )

        assertEquals(1167.70, result.candle?.close)
        assertEquals(Source.NSE_BHAVCOPY, result.candle?.source)
        assertFalse(result.hasConflicts)
        assertEquals(Confidence.SINGLE, result.close?.confidence)
    }
}