package com.mia.app.domain.ratios

import com.mia.app.domain.model.Sector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [RatioPriority] tests: the plan.md §7.2 order verbatim, the §7.3 overrides as **replacements**
 * rather than additions, and the "suppressed" / "not sourced" distinction that §7.3 turns on.
 */
class RatioPriorityTest {

    @Test
    fun `the plan 7 2 order is exactly ranks one to twelve`() {
        // PLAN_ORDER is the §7.2 list and nothing else. `order()` appends the supplementary
        // ownership block after it, so the plan's ranks are asserted here as well as over the
        // composed list — which also carries the unranked rows.
        assertEquals((1..12).toList(), RatioPriority.PLAN_ORDER.map { it.planRank })
        val order = RatioPriority.order(Sector.OTHER)
        assertEquals((1..12).toList(), order.take(12).map { it.planRank })
        assertEquals(
            listOf(
                RatioId.ROCE,
                RatioId.OPERATING_MARGIN,
                RatioId.DEBT_TO_EQUITY,
                RatioId.FCF_VS_PAT,
                RatioId.REVENUE_CAGR,
                RatioId.ROE,
                RatioId.GROSS_MARGIN,
                RatioId.PE_VS_SECTOR_MEDIAN,
                RatioId.PB,
                RatioId.EV_TO_EBITDA,
                RatioId.PROMOTER_HOLDING,
                RatioId.DIVIDEND_YIELD,
            ),
            order.take(12).map { it.id },
        )
        assertEquals(order.map { it.id }, order.map { it.id }.distinct(), "no repeated card")
    }

    /**
     * W4: institutional ownership is published, parsed and **unranked**.
     *
     * The three rows come after §7.2's twelve for every sector, in one card, and claim no §7.2
     * rank — the plan does not assign one, and printing "11" beside an FII holding would assert an
     * authority the plan never gave.
     */
    @Test
    fun `the ownership rows are appended unranked for every sector`() {
        assertEquals(
            listOf(RatioId.FII_HOLDING, RatioId.DII_HOLDING, RatioId.FII_TREND),
            RatioPriority.SUPPLEMENTARY_ORDER.single().ids,
        )
        for (sector in Sector.entries) {
            val last = RatioPriority.order(sector).last()
            assertEquals(SlotOrigin.SUPPLEMENTARY, last.origin, sector.name)
            assertTrue(last.isSupplementary, sector.name)
            assertFalse(last.isSectorSpecific, sector.name)
            for (id in last.ids) {
                assertEquals(0, id.planRank, "${sector.name}/${id.name}")
                // Not a §7.3 slot, so the sector has no opinion: the data decides.
                assertNull(RatioPriority.sectorStateFor(sector, id), "${sector.name}/${id.name}")
            }
        }
        // A bank gets them too: HDFCBANK's card publishes both rows and is the one fixture with no
        // Promoters row at all, so a card shared with promoter holding would have been empty there.
        assertTrue(RatioPriority.order(Sector.BANKING).any { it.id == RatioId.FII_HOLDING })
    }

    @Test
    fun `the plan 7 2 cards carry the plan's own two metric rows`() {
        // Over PLAN_ORDER, which is the §7.2 list; the supplementary ownership card is added after
        // it and is covered by its own test above.
        val order = RatioPriority.PLAN_ORDER
        assertEquals(listOf(RatioId.INTEREST_COVERAGE), order[2].companions)
        assertEquals(listOf(RatioId.DIVIDEND_PAYOUT), order[11].companions)
        // Every other row is a single number.
        for (i in order.indices.filter { it != 2 && it != 11 }) {
            assertEquals(emptyList(), order[i].companions, "slot ${order[i].id}")
        }
    }

    @Test
    fun `every plan ratio keeps the rationale and unit the plan gives it`() {
        assertEquals(1, RatioId.ROCE.planRank)
        assertEquals("Best single measure of business quality; hard to manipulate", RatioId.ROCE.rationale)
        assertEquals(RatioUnit.PERCENT, RatioId.ROCE.unit)
        assertEquals(RatioUnit.RATIO, RatioId.DEBT_TO_EQUITY.unit)
        assertEquals(RatioUnit.MULTIPLE, RatioId.INTEREST_COVERAGE.unit)
        assertEquals(RatioUnit.MULTIPLE, RatioId.PE_VS_SECTOR_MEDIAN.unit)
        assertEquals(RatioUnit.RATIO, RatioId.FCF_VS_PAT.unit)
        assertEquals(RatioUnit.PERCENT, RatioId.REVENUE_CAGR.unit)
        assertEquals(RatioUnit.DAYS, RatioId.WORKING_CAPITAL_DAYS.unit)
        // ROE is ranked below ROCE on purpose: it duplicates what leverage already says.
        assertTrue(RatioId.ROE.planRank > RatioId.ROCE.planRank)
        assertTrue(RatioId.ROE.rationale.contains("below ROCE"), RatioId.ROE.rationale)
    }

    @Test
    fun `a sector the plan does not override keeps the generic list unchanged`() {
        for (sector in listOf(
            Sector.AUTO, Sector.METALS, Sector.CEMENT, Sector.POWER, Sector.RENEWABLE,
            Sector.DEFENCE, Sector.RAILWAY, Sector.REALTY, Sector.TELECOM, Sector.AVIATION,
            Sector.DURABLES, Sector.RETAIL, Sector.LOGISTICS, Sector.CAPITAL_GOODS,
            Sector.CHEMICALS, Sector.INSURANCE, Sector.OTHER,
        )) {
            assertEquals(RatioPriority.PLAN_ORDER, RatioPriority.order(sector).take(12), sector.name)
            assertFalse(RatioPriority.sectorOverrides(sector).hasOverrides, sector.name)
        }
    }

    // ------------------------------------------------------------------------- banking

    @Test
    fun `banking promotes ROE to first and drops debt equity interest coverage and current ratio`() {
        val order = RatioPriority.order(Sector.BANKING)
        // plan.md §7.3's replacement row for a bank is "ROE, NIM, GNPA/NNPA, CASA ratio, PCR",
        // so ROE leads and the generic ROCE row follows the bank's own four.
        assertEquals(RatioId.ROE, order.first().id)
        assertEquals(RatioId.ROCE, order[5].id)
        assertEquals(listOf(RatioId.ROE to 1), RatioPriority.sectorOverrides(Sector.BANKING).promoted.toList())

        for (gone in listOf(
            RatioId.DEBT_TO_EQUITY,
            RatioId.INTEREST_COVERAGE,
            RatioId.CURRENT_RATIO,
            RatioId.OPERATING_MARGIN,
        )) {
            assertFalse(order.any { gone in it.ids }, "${gone.name} must not appear for a bank")
            assertEquals(
                RatioState.SUPPRESSED_FOR_SECTOR,
                RatioPriority.sectorStateFor(Sector.BANKING, gone),
                gone.name,
            )
            assertNotNull(RatioPriority.sectorNoteFor(Sector.BANKING, gone))
        }
    }

    @Test
    fun `banking leads with the four ratios the plan names`() {
        val order = RatioPriority.order(Sector.BANKING)
        assertEquals(
            listOf(
                RatioId.ROE,
                RatioId.NET_INTEREST_MARGIN,
                RatioId.GNPA_NNPA,
                RatioId.CASA_RATIO,
                RatioId.PROVISION_COVERAGE_RATIO,
            ),
            order.take(5).flatMap { it.ids },
        )
        // They are in the list, but they are reported as unsourced rather than left blank.
        for (id in listOf(
            RatioId.NET_INTEREST_MARGIN,
            RatioId.GNPA_NNPA,
            RatioId.CASA_RATIO,
            RatioId.PROVISION_COVERAGE_RATIO,
        )) {
            assertEquals(RatioState.NOT_SOURCED, RatioPriority.sectorStateFor(Sector.BANKING, id), id.name)
            val availability = RatioPriority.availability<Double>(Sector.BANKING, id, null)
            assertNull(availability.value)
            assertEquals(RatioState.NOT_SOURCED, availability.state)
            assertTrue(availability.note!!.contains("plan.md §7.3") || availability.note!!.contains("sources"))
        }
        // The rest of the generic list survives: the override replaces rows, it does not truncate.
        assertTrue(order.any { RatioId.REVENUE_CAGR == it.id })
        assertTrue(order.any { RatioId.PROMOTER_HOLDING == it.id })
    }

    @Test
    fun `banking never resurrects a debt to equity even when a value is handed in`() {
        val availability = RatioPriority.availability(Sector.BANKING, RatioId.DEBT_TO_EQUITY, 0.44)
        assertEquals(RatioState.SUPPRESSED_FOR_SECTOR, availability.state)
        assertNull(availability.value, "a suppressed ratio must not carry the number it was given")
    }

    // --------------------------------------------------------------------------- NBFC

    @Test
    fun `nbfc replaces the operating margin slot with its own four`() {
        val order = RatioPriority.order(Sector.NBFC)
        assertEquals(
            listOf(
                RatioId.ASSET_QUALITY,
                RatioId.STAGE_2_GNPA,
                RatioId.COST_OF_FUNDS,
                RatioId.LEVERAGE,
            ),
            order.take(4).map { it.id },
        )
        assertFalse(order.any { RatioId.OPERATING_MARGIN in it.ids })
        assertEquals(
            RatioState.SUPPRESSED_FOR_SECTOR,
            RatioPriority.sectorStateFor(Sector.NBFC, RatioId.OPERATING_MARGIN),
        )
        // Leverage is what matters here, so debt/equity stays.
        assertTrue(order.any { RatioId.DEBT_TO_EQUITY in it.ids })
        assertNull(RatioPriority.sectorStateFor(Sector.NBFC, RatioId.DEBT_TO_EQUITY))
    }

    // ------------------------------------------------------------------------------ IT

    @Test
    fun `it leads with contract value attrition constant currency and visibility`() {
        val order = RatioPriority.order(Sector.IT)
        assertEquals(
            listOf(
                RatioId.TOTAL_CONTRACT_VALUE,
                RatioId.ATTRITION,
                RatioId.CONSTANT_CURRENCY_GROWTH,
                RatioId.REVENUE_VISIBILITY,
            ),
            order.take(4).map { it.id },
        )
        // Gross margin is unpublishable for a services P&L, so it is removed, not ranked low.
        assertFalse(order.any { RatioId.GROSS_MARGIN in it.ids })
        assertEquals(RatioState.SUPPRESSED_FOR_SECTOR, RatioPriority.sectorStateFor(Sector.IT, RatioId.GROSS_MARGIN))
    }

    // --------------------------------------------------------------------------- pharma

    @Test
    fun `pharma leads with R and D intensity USFDA compliance and product concentration`() {
        val order = RatioPriority.order(Sector.PHARMA)
        assertEquals(
            listOf(
                RatioId.RD_TO_SALES,
                RatioId.USFDA_COMPLIANCE,
                RatioId.PRODUCT_CONCENTRATION,
            ),
            order.take(3).map { it.id },
        )
        // plan.md §7.2 rank 9 scopes P/B to asset-heavy businesses.
        assertFalse(order.any { RatioId.PB in it.ids })
        assertEquals(RatioState.SUPPRESSED_FOR_SECTOR, RatioPriority.sectorStateFor(Sector.PHARMA, RatioId.PB))
        assertTrue(RatioPriority.sectorNoteFor(Sector.PHARMA, RatioId.PB)!!.contains("asset-heavy"))
    }

    // --------------------------------------------------------------------------- FMCG

    @Test
    fun `fmcg promotes gross margin to second and adds volume versus price and wc days`() {
        val order = RatioPriority.order(Sector.FMCG)
        assertEquals(
            listOf(RatioId.VOLUME_VS_PRICE_GROWTH, RatioId.GROSS_MARGIN, RatioId.WORKING_CAPITAL_DAYS),
            order.take(3).map { it.id },
        )
        // GROSS_MARGIN is the only §7.2 row that moves; the two FMCG rows arrive from §7.3.
        assertEquals(
            listOf(RatioId.GROSS_MARGIN to 2),
            RatioPriority.sectorOverrides(Sector.FMCG).promoted.toList(),
        )
        assertEquals(
            listOf(RatioId.VOLUME_VS_PRICE_GROWTH, RatioId.WORKING_CAPITAL_DAYS),
            RatioPriority.sectorOverrides(Sector.FMCG).order.map { it.id },
        )
        assertEquals(
            RatioState.SUPPRESSED_FOR_SECTOR,
            RatioPriority.sectorStateFor(Sector.FMCG, RatioId.PB),
        )
    }

    // ------------------------------------------------------- override shape, not additions

    @Test
    fun `an override replaces the generic list rather than appending to it`() {
        for (sector in listOf(Sector.BANKING, Sector.NBFC, Sector.IT, Sector.PHARMA, Sector.FMCG)) {
            val plan = RatioPriority.plan(sector)
            assertEquals(sector, plan.sector)
            // Every slot in the sector plan is a surviving §7.2 row, a §7.3 slot, or the
            // supplementary ownership card, which is appended for every sector.
            for (slot in plan.order) {
                assertTrue(
                    slot.isSectorSpecific ||
                        slot.isSupplementary ||
                        RatioPriority.PLAN_ORDER.any { it.id == slot.id },
                    "${sector.name}: ${slot.id} is neither a plan row, a §7.3 slot nor supplementary",
                )
            }
            assertEquals(
                plan.order.map { it.id }.distinct().size,
                plan.order.size,
                "${sector.name}: duplicate slot",
            )
            assertTrue(plan.overrides.planNote.isNotBlank(), "${sector.name} must record its §7.3 note")
        }
    }

    @Test
    fun `every sector specific slot is marked as such and carries a zero plan rank`() {
        val sectorSpecific = RatioId.entries.filter { it.isSectorSpecific }
        assertTrue(sectorSpecific.isNotEmpty())
        for (id in sectorSpecific) {
            assertEquals(0, id.planRank, id.name)
            assertEquals(RatioState.NOT_SOURCED, RatioPriority.sectorStateFor(Sector.OTHER, id), id.name)
        }
        // §7.3 contributes 4 banking + 4 NBFC + 4 IT + 3 pharma + 2 FMCG slots.
        assertEquals(17, sectorSpecific.size)
    }

    /**
     * Three plan.md §7.2 cards can never be filled from our one source. They stay in the order —
     * §7.2 is the plan — and report [RatioState.NOT_SOURCED] instead.
     */
    @Test
    fun `gross margin ev to ebitda and the current ratio are never sourced`() {
        assertEquals(
            setOf(RatioId.GROSS_MARGIN, RatioId.EV_TO_EBITDA, RatioId.CURRENT_RATIO),
            RatioId.entries.filter { it.isNeverSourced }.toSet(),
        )
        for (sector in Sector.entries) {
            for (id in RatioId.entries.filter { it.isNeverSourced }) {
                val state = RatioPriority.sectorStateFor(sector, id)
                assertEquals(
                    if (id in RatioPriority.sectorOverrides(sector).suppressed) {
                        RatioState.SUPPRESSED_FOR_SECTOR
                    } else {
                        RatioState.NOT_SOURCED
                    },
                    state,
                    "${sector.name}/${id.name}",
                )
            }
        }
    }

    // ------------------------------------------------- available vs suppressed vs unsourced

    @Test
    fun `available suppressed and not sourced are three different states`() {
        assertTrue(RatioState.AVAILABLE != RatioState.SUPPRESSED_FOR_SECTOR)
        assertTrue(RatioState.SUPPRESSED_FOR_SECTOR != RatioState.NOT_SOURCED)
        assertTrue(RatioState.AVAILABLE != RatioState.NOT_SOURCED)
        assertEquals(5, RatioState.entries.size)
    }

    /**
     * The distinction plan.md §7.3 turns on, side by side: a bank's D/E is *not meaningful*
     * (suppressed) while EV/EBITDA is *meaningful but unsourced*, and the two must not read the
     * same to a user.
     */
    @Test
    fun `a bank debt to equity is suppressed while an unsourced ev to ebitda is not sourced`() {
        val debtToEquity = RatioPriority.availability(Sector.BANKING, RatioId.DEBT_TO_EQUITY, 0.4)
        val evToEbitda = RatioPriority.availability(Sector.BANKING, RatioId.EV_TO_EBITDA, null)
        assertEquals(RatioState.SUPPRESSED_FOR_SECTOR, debtToEquity.state)
        assertEquals(RatioState.NOT_SOURCED, evToEbitda.state)
        assertTrue(debtToEquity.isBlank && evToEbitda.isBlank)
        assertTrue(debtToEquity.note != evToEbitda.note)

        // Outside banking, D/E is available and the current ratio is not sourced, never computed.
        val autoDebtToEquity = RatioPriority.availability(Sector.AUTO, RatioId.DEBT_TO_EQUITY, 0.44)
        assertEquals(RatioState.AVAILABLE, autoDebtToEquity.state)
        assertEquals(0.44, autoDebtToEquity.value)
        val currentRatio = RatioPriority.availability(Sector.AUTO, RatioId.CURRENT_RATIO, null)
        assertEquals(RatioState.NOT_SOURCED, currentRatio.state)
    }

    @Test
    fun `a value we simply do not have is MISSING not SUPPRESSED not NOT_SOURCED`() {
        val roce = RatioPriority.availability(Sector.AUTO, RatioId.ROCE, null)
        assertEquals(RatioState.MISSING, roce.state)
        assertTrue(roce.isBlank)
        assertEquals(10.3, RatioPriority.availability(Sector.AUTO, RatioId.ROCE, 10.3).value)
    }

    @Test
    fun `availability states cannot be constructed inconsistently`() {
        assertFailsWith<IllegalArgumentException> { RatioAvailability<Double>(null, RatioState.AVAILABLE, null) }
        assertFailsWith<IllegalArgumentException> { RatioAvailability(1.0, RatioState.SUPPRESSED_FOR_SECTOR, "x") }
        assertFailsWith<IllegalArgumentException> { RatioAvailability(1.0, RatioState.NOT_SOURCED, "x") }
        assertFailsWith<IllegalArgumentException> { RatioAvailability(1.0, RatioState.MISSING, "x") }
        assertFailsWith<IllegalArgumentException> { RatioAvailability(null, RatioState.MISSING, "  ") }
        // STALE still carries a value: old is not the same as absent.
        assertEquals(RatioState.STALE, RatioAvailability.stale(21.2, "8 months old").state)
        assertTrue(RatioAvailability.stale(21.2, "8 months old").isRenderable)
    }

    @Test
    fun `plan never contains a ratio it cannot explain`() {
        for (sector in Sector.entries) {
            val plan = RatioPriority.plan(sector)
            assertTrue(plan.order.isNotEmpty(), sector.name)
            for (slot in plan.order) {
                for (id in slot.ids) {
                    val state = RatioPriority.sectorStateFor(sector, id)
                    if (slot.isSectorSpecific) {
                        assertEquals(RatioState.NOT_SOURCED, state, "${sector.name}/${id.name}")
                    } else if (state != null) {
                        // A surviving §7.2 card the sector has an opinion about, or one no
                        // source can fill: either way it must carry the reason.
                        assertEquals(state, RatioPriority.availability<Double>(sector, id, null).state)
                    }
                    if (state != null) {
                        assertNotNull(RatioPriority.sectorNoteFor(sector, id), "${sector.name}/${id.name}")
                    }
                }
            }
        }
    }
}