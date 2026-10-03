package com.mia.app.data.aggregate

import com.mia.app.data.aggregate.AggregationFixtures.AS_OF
import com.mia.app.domain.model.Confidence
import com.mia.app.domain.model.Ratios
import com.mia.app.domain.model.Sector
import com.mia.app.domain.model.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * plan.md §5.1 reproduced exactly, field by field.
 *
 * Every number in this file is from the plan's own evidence table. Nothing is rounded by the
 * test and nothing is invented: if the merge rule changes, these are the assertions that should
 * fail first.
 *
 * | Case | screener.in | other | expected |
 * |---|---|---|---|
 * | RELIANCE P/E | 21.2 | 21.142 | 0.27% → AGREED |
 * | RELIANCE ROE | 8.91 | null | → SINGLE |
 * | TCS P/E | 14.0 | 15.083 | **7.18% → CONFLICT** |
 * | TCS ROE | 51.8 | 47.743 | **8.50% → CONFLICT** |
 */
class FundamentalsAggregatorMeasuredTest {

    private fun merge(primary: Ratios, secondary: Ratios) =
        FundamentalsAggregator.merge(primary, secondary, AS_OF)

    private fun ratioOf(merge: FundamentalsMerge, id: RatioFieldId): RatioField {
        val field = merge.field(id)
        assertNotNull(field, "expected a row for $id")
        return field!!
    }

    // ------------------------------------------------------------------ RELIANCE P/E, agreed

    @Test
    fun `RELIANCE P E 21_2 versus 21_142 is 0_27 percent and AGREED`() {
        val result = merge(AggregationFixtures.screenerIn(pe = 21.2), AggregationFixtures.yahoo(pe = 21.142))

        val pe = ratioOf(result, RatioFieldId.PE)
        assertEquals(21.2, pe.value, "the screener.in value is the one shown (plan.md §5.1 precedence)")
        assertEquals(Source.SCREENER_IN, pe.merged?.source)
        assertEquals(Confidence.AGREED, pe.confidence)
        assertEquals(0.0027433544603159507, pe.divergence!!, "measured delta from plan.md §5.1", )
        assertEquals(0.27, round2(pe.divergencePercent!!), "the plan records 0.27%")
        assertNull(pe.alternate, "agreement needs no second number on screen")
        assertNull(pe.chip(), "an agreed field has no divergence chip")
    }

    // ------------------------------------------------------------------ RELIANCE ROE, single

    @Test
    fun `RELIANCE ROE 8_91 versus null is SINGLE and claims nothing about agreement`() {
        // Minimal snapshots on purpose: the plan's RELIANCE row is about ROE alone, and a fixture
        // that filled every other field would make "single-sourced" true of thirteen rows.
        val screener = AggregationFixtures.screenerIn(roe = 8.91, roce = 10.3).copy(
            pe = null, pb = null, marketCap = null, promoterHolding = null, operatingMargin = null,
            fcf = null, pat = null, cfoToOp = null, revenueCagr3y = null, revenueGrowth = null,
            interestCoverage = null,
        )
        val result = merge(screener, AggregationFixtures.yahoo(roe = null, pe = null))

        val roe = ratioOf(result, RatioFieldId.ROE)
        assertEquals(8.91, roe.value)
        assertEquals(Source.SCREENER_IN, roe.merged?.source)
        assertEquals(Confidence.SINGLE, roe.confidence)
        assertNull(roe.divergence, "one source publishing nothing says nothing about the value")
        assertNull(roe.divergencePercent)
        assertNull(roe.alternate)
        assertNull(roe.chip())
        assertEquals(
            listOf(RatioFieldId.ROCE, RatioFieldId.ROE),
            result.singleSourced,
            "every field Yahoo published nothing for is single-sourced; ROE is the documented one",
        )
    }

    @Test
    fun `a field only the other source has is SINGLE and keeps that source`() {
        val result = merge(AggregationFixtures.screenerIn(roe = null), AggregationFixtures.yahoo(roe = 12.5))

        val roe = ratioOf(result, RatioFieldId.ROE)
        assertEquals(12.5, roe.value)
        assertEquals(Source.YAHOO, roe.merged?.source, "precedence decides the shown value, never the availability")
        assertEquals(Confidence.SINGLE, roe.confidence)
        assertEquals(AS_OF, roe.merged?.asOf)
    }

    // ------------------------------------------------------------------ TCS P/E, conflict

    @Test
    fun `TCS P E 14_0 versus 15_083 is 7_18 percent and CONFLICT`() {
        val result = merge(AggregationFixtures.screenerIn("TCS", pe = 14.0), AggregationFixtures.yahoo("TCS", pe = 15.083))

        val pe = ratioOf(result, RatioFieldId.PE)
        assertEquals(14.0, pe.value, "the primary value stays on screen (plan.md §5.1)")
        assertEquals(Source.SCREENER_IN, pe.merged?.source)
        assertEquals(Confidence.CONFLICT, pe.confidence)
        assertEquals(0.07180269177219387, pe.divergence!!, "measured delta from plan.md §5.1")
        assertEquals(7.18, round2(pe.divergencePercent!!), "the plan records 7.18%")
        assertEquals(15.083, pe.alternate, "the other value is kept, greyed, never averaged")
        assertEquals(Source.YAHOO, pe.merged?.alternateSource)
        assertTrue(pe.merged!!.hasConflict)

        val conflict = result.report.conflicts.single()
        assertEquals("PE", conflict.field)
        assertEquals(14.0, conflict.shown)
        assertEquals(15.083, conflict.alternate)
        assertEquals(7.18, round2(conflict.divergencePercent!!))
        assertEquals(result.report.chipFor("PE"), pe.chip())
        assertTrue(pe.chip()!!.contains("7.18%"), pe.chip()!!)
    }

    // ------------------------------------------------------------------ TCS ROE, conflict

    @Test
    fun `TCS ROE 51_8 versus 47_743 is 8_50 percent and CONFLICT`() {
        val result = merge(AggregationFixtures.screenerIn("TCS", roe = 51.8), AggregationFixtures.yahoo("TCS", roe = 47.743))

        val roe = ratioOf(result, RatioFieldId.ROE)
        assertEquals(51.8, roe.value)
        assertEquals(Confidence.CONFLICT, roe.confidence)
        assertEquals(0.08497580797184917, roe.divergence!!, "measured delta from plan.md §5.1")
        assertEquals(8.50, round2(roe.divergencePercent!!), "the plan records 8.50%")
        assertEquals(47.743, roe.alternate)
        assertEquals("ROE", result.report.conflictFields.single())
    }

    @Test
    fun `the TCS snapshot conflicts on both P E and ROE and reports exactly those two`() {
        val result = merge(
            AggregationFixtures.screenerIn("TCS", pe = 14.0, roe = 51.8),
            AggregationFixtures.yahoo("TCS", pe = 15.083, roe = 47.743),
        )

        assertEquals(listOf("PE", "ROE"), result.report.conflictFields.sorted())
        assertEquals(2, result.report.conflictCount)
        assertTrue(result.report.hasConflicts)
        assertEquals(Confidence.CONFLICT, result.ratios.confidence, "the snapshot verdict is the worst field")
        // plan.md §5.1 renders both, worst first, when there is room for one chip.
        assertEquals(listOf("ROE", "PE"), result.report.worstFirst().map { it.field })
    }

    // ------------------------------------------------------------------ the price row of §5.1

    @Test
    fun `RELIANCE price 1168_0 versus 1167_7 is 0_03 percent and AGREED`() {
        val result = PriceAggregator.mergeSession(
            symbol = "RELIANCE",
            bhavcopy = AggregationFixtures.bhavcopy(
                listOf(AggregationFixtures.candle(close = 1168.0)),
            ),
            yahoo = AggregationFixtures.yahooHistory(
                listOf(AggregationFixtures.candle(close = 1167.7, source = Source.YAHOO)),
            ),
            today = AS_OF,
        )

        val close = result.field(PriceFieldId.CLOSE)!!
        assertEquals(1168.0, close.merged?.value)
        assertEquals(Source.NSE_BHAVCOPY, close.merged?.source, "plan.md §5.1: price/OHLCV → NSE")
        assertEquals(Confidence.AGREED, close.confidence)
        assertEquals(0.00025691530358821146, close.divergence!!)
        assertEquals(0.03, round2(close.divergencePercent!!), "the plan records 0.03%")
        assertFalse(result.hasConflicts)
        assertNull(close.chip())
    }

    // ------------------------------------------------------------------ never average

    @Test
    fun `no merged value is ever the average of the two inputs`() {
        val cases = listOf(
            Triple(21.2, 21.142, RatioFieldId.PE),
            Triple(8.91, null, RatioFieldId.ROE),
            Triple(14.0, 15.083, RatioFieldId.PE),
            Triple(51.8, 47.743, RatioFieldId.ROE),
            Triple(105.0, 100.0, RatioFieldId.PE),
            // A zero alternate: no percentage exists, so no midpoint claim can be made either.
            Triple(0.0, null, RatioFieldId.PE),
            Triple(-4.0, 4.0, RatioFieldId.ROE),
        )

        for ((primary, secondary, id) in cases) {
            val fields = FundamentalsAggregator.mergeRatios(
                AggregationFixtures.screenerIn(pe = null, roe = null, roce = null, pb = null).let { base ->
                    when (id) {
                        RatioFieldId.PE -> base.copy(pe = primary)
                        else -> base.copy(roe = primary)
                    }
                },
                AggregationFixtures.yahoo(pe = null, roe = null).let { base ->
                    when (id) {
                        RatioFieldId.PE -> base.copy(pe = secondary)
                        else -> base.copy(roe = secondary)
                    }
                },
                AS_OF,
            )
            val field = fields.first { it.id == id }
            val shown = field.value ?: continue
            assertTrue(
                shown == primary || shown == secondary,
                "$id: merged $shown is neither input ($primary, $secondary)",
            )
            val other = secondary
            if (other != null) {
                val a = requireNotNull(primary)
                // Every two-input case below has distinct inputs, so a midpoint can always be
                // formed — and must never be what the merge produced.
                val midpoint = (a + other) / 2.0
                assertTrue(
                    shown != midpoint,
                    "$id: $shown is the average of $a and $other — plan.md §5.1 forbids it",
                )
            }
        }
    }

    @Test
    fun `every field of every measured case is one of its two inputs`() {
        val pairs = listOf(
            AggregationFixtures.screenerIn("RELIANCE", pe = 21.2, roe = 8.91) to
                AggregationFixtures.yahoo("RELIANCE", pe = 21.142, roe = null),
            AggregationFixtures.screenerIn("TCS", pe = 14.0, roe = 51.8) to
                AggregationFixtures.yahoo("TCS", pe = 15.083, roe = 47.743),
        )

        for ((primary, secondary) in pairs) {
            val fields = FundamentalsAggregator.mergeRatios(primary, secondary, AS_OF)
            for (field in fields) {
                val value = field.value ?: continue
                assertTrue(
                    value == field.screenerIn || value == field.other,
                    "${field.id}: $value is neither ${field.screenerIn} nor ${field.other}",
                )
            }
        }
    }

    // ------------------------------------------------------------------ per field, not per source

    @Test
    fun `argument order does not change which value wins`() {
        val screener = AggregationFixtures.screenerIn("TCS", pe = 14.0, roe = 51.8)
        val other = AggregationFixtures.yahoo("TCS", pe = 15.083, roe = 47.743)

        val forwards = FundamentalsAggregator.mergeRatios(screener, other, AS_OF)
        val backwards = FundamentalsAggregator.mergeRatios(other, screener, AS_OF)

        for (a in forwards.zip(backwards)) {
            assertEquals(a.first.id, a.second.id)
            assertEquals(a.first.value, a.second.value, "${a.first.id}: the winner must follow the field's precedence")
            assertEquals(a.first.confidence, a.second.confidence)
            assertEquals(a.first.divergence, a.second.divergence)
            assertEquals(a.first.screenerIn, a.second.screenerIn)
            assertEquals(a.first.other, a.second.other)
        }
    }

    @Test
    fun `the screener value wins per field even when the other side carries more fields`() {
        // The Yahoo snapshot is richer here; plan.md §5.1 still says screener.in wins P/E.
        val screener = AggregationFixtures.screenerIn(pe = 21.2, roce = 10.3, operatingMargin = null, pat = null)
        val other = AggregationFixtures.yahoo(pe = 15.0).copy(
            roce = 9.0,
            operatingMargin = 12.0,
            pat = 1.0,
        )

        val fields = FundamentalsAggregator.mergeRatios(screener, other, AS_OF)
        val byId = fields.associateBy { it.id }

        assertEquals(21.2, byId.getValue(RatioFieldId.PE).value, "screener.in wins P/E")
        assertEquals(10.3, byId.getValue(RatioFieldId.ROCE).value, "screener.in wins ROCE")
        // A field only the other side published is taken from it — per field, not per source.
        assertEquals(12.0, byId.getValue(RatioFieldId.OPERATING_MARGIN).value)
        assertEquals(1.0, byId.getValue(RatioFieldId.PAT).value)
    }

    @Test
    fun `P B stays on the screener side because both its operands are that page's`() {
        val screener = AggregationFixtures.screenerIn(pb = 1.749)
        val other = AggregationFixtures.yahoo().copy(pb = 1.80)

        val pb = FundamentalsAggregator.mergeRatios(screener, other, AS_OF).first { it.id == RatioFieldId.PB }

        assertEquals(FieldPrecedence.DERIVED_FROM_PRICE, pb.id.precedence)
        assertEquals(1.749, pb.value)
        assertEquals(Confidence.AGREED, pb.confidence)
    }

    // ------------------------------------------------------------------ structure

    @Test
    fun `mergeRatios returns one row per Ratios field in declaration order`() {
        val fields = FundamentalsAggregator.mergeRatios(
            AggregationFixtures.screenerIn(),
            AggregationFixtures.yahoo(),
            AS_OF,
        )

        assertEquals(RatioFieldId.ALL, fields.map { it.id })
        assertEquals(17, fields.size, "Ratios publishes 17 fields and every one gets a row")
    }

    @Test
    fun `a field neither source publishes has a null row and no chip`() {
        val fields = FundamentalsAggregator.mergeRatios(
            AggregationFixtures.screenerIn().copy(grossMargin = null, evToEbitda = null),
            AggregationFixtures.yahoo(),
            AS_OF,
        )
        val grossMargin = fields.first { it.id == RatioFieldId.GROSS_MARGIN }

        assertNull(grossMargin.value, "never zero, never a blank that looks like a zero")
        assertNull(grossMargin.confidence)
        assertNull(grossMargin.chip())
        assertTrue(
            RatioFieldId.GROSS_MARGIN.why.contains("NOT OBTAINABLE"),
            "the enum's own reason must be visible to the caller: ${RatioFieldId.GROSS_MARGIN.why}",
        )
    }

    @Test
    fun `the collapsed Ratios carries the precedence source and the worst verdict`() {
        val agreed = FundamentalsAggregator.merge(
            AggregationFixtures.screenerIn(pe = 21.2),
            AggregationFixtures.yahoo(pe = 21.142),
            AS_OF,
        )
        assertEquals(Source.SCREENER_IN, agreed.ratios.source)
        assertEquals(AS_OF, agreed.ratios.asOf)
        assertEquals(Confidence.AGREED, agreed.ratios.confidence)
        assertEquals(21.2, agreed.ratios.pe)
        assertEquals(8.91, agreed.ratios.roe)

        val conflicted = FundamentalsAggregator.merge(
            AggregationFixtures.screenerIn("TCS", pe = 14.0),
            AggregationFixtures.yahoo("TCS", pe = 15.083),
            AS_OF,
        )
        assertEquals(Confidence.CONFLICT, conflicted.ratios.confidence)
        assertEquals(14.0, conflicted.ratios.pe, "the collapsed value is the precedence value, never the average")
    }

    @Test
    fun `a stale snapshot is STALE but its disagreements are still reported`() {
        val stale = FundamentalsAggregator.merge(
            AggregationFixtures.screenerIn("TCS", asOf = AS_OF.minusDays(40), pe = 14.0, roe = 51.8),
            AggregationFixtures.yahoo("TCS", asOf = AS_OF.minusDays(40), pe = 15.083, roe = 47.743),
            AS_OF,
        )

        // plan.md §10: fundamentals lag, and a stale figure must not read as current.
        assertEquals(Confidence.STALE, stale.field(RatioFieldId.PE)?.confidence)
        // plan.md §5.1: the disagreement is still surfaced — it is computed independently of freshness.
        assertEquals(listOf("PE", "ROE"), stale.report.conflictFields.sorted())
        assertEquals(7.18, round2(stale.field(RatioFieldId.PE)!!.divergencePercent!!))
        assertTrue(
            stale.report.notes.any { it.contains("older than") },
            stale.report.notes.toString(),
        )
    }

    @Test
    fun `sector is never guessed and never regressed to OTHER`() {
        val resolved = AggregationFixtures.screenerIn().copy(sector = Sector.IT)
        val other = AggregationFixtures.yahoo().copy(sector = Sector.OTHER)

        val merge = FundamentalsAggregator.merge(resolved, other, AS_OF)
        assertEquals(Sector.IT, merge.ratios.sector)

        val bothUnresolved = FundamentalsAggregator.merge(
            AggregationFixtures.screenerIn(),
            AggregationFixtures.yahoo(),
            AS_OF,
        )
        assertEquals(Sector.OTHER, bothUnresolved.ratios.sector, "plan.md §1.4 rule 8: default OTHER, never guess")
    }

    @Test
    fun `sector median P E is named as unobtainable and never computed`() {
        val merge = FundamentalsAggregator.merge(
            AggregationFixtures.screenerIn(),
            AggregationFixtures.yahoo(),
            AS_OF,
        )

        val median = merge.report.unavailable.first { it.field == "SECTOR_MEDIAN_PE" }
        assertTrue(median.reason.contains("robots.txt"), median.reason)
        assertTrue(median.reason.contains("never a"), median.reason)
        // There is no field for it at all, so there is nothing to render a fabricated median into.
        assertTrue(RatioFieldId.entries.none { it.name.contains("SECTOR_MEDIAN") })
        assertFalse(
            merge.report.notes.any { it.contains("median", ignoreCase = true) && it.contains("=") },
            "no median number may appear anywhere: ${merge.report.notes}",
        )
    }

    @Test
    fun `the notes explain the tolerance and the precedence once`() {
        val merge = FundamentalsAggregator.merge(
            AggregationFixtures.screenerIn(),
            AggregationFixtures.yahoo(),
            AS_OF,
        )

        assertTrue(
            merge.report.notes.any { it.contains("5.0%") && it.contains("lower-precedence") },
            merge.report.notes.toString(),
        )
        assertTrue(
            merge.report.notes.any { it.contains("never averaged") },
            merge.report.notes.toString(),
        )
        assertTrue(
            merge.report.notes.any { it.contains("single-sourced") },
            merge.report.notes.toString(),
        )
    }

    private fun round2(value: Double): Double = Math.round(value * 100.0) / 100.0
}