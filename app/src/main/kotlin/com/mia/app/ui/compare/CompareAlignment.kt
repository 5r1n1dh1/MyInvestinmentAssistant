package com.mia.app.ui.compare

import com.mia.app.domain.ratios.RatioAvailability
import com.mia.app.domain.ratios.RatioPriority
import com.mia.app.domain.ratios.RatioSlot
import com.mia.app.domain.ratios.SlotOrigin

/**
 * Turning three companies' ratio lists into rows that mean the same thing on every column (gap E6).
 *
 * ### The one hard requirement, and why positional alignment is a lie
 * "Cards align by `RatioSlot` identity, not by position." Sectors differ, so each company's list of
 * display rows is different: a bank's is ROE, NIM, GNPA, CASA, PCR, … and an FMCG company's is
 * volume-vs-price growth, gross margin, working-capital days, ROCE, …. Zipping those two lists by
 * index would render a bank's net-interest margin beside an FMCG company's working-capital days
 * under one label, in one row, and the reader would be comparing two things that have nothing to do
 * with each other. The table would *look* like a comparison and contain none.
 *
 * So the algorithm is:
 * 1. For each company, in the order the reader typed them, walk **that company's own** composed
 *    order — `RatioPriority.order(sector)`, which already carries the plan.md §7.3 overrides for
 *    that sector. A row is created the first time any company mentions it, keyed on
 *    [RatioSlot.id].
 * 2. Every company then gets a cell on that row, whether or not the row is in its own plan. The
 *    sector's decision is applied **per cell** by [RatioPriority.availability], so a company's cell
 *    is `SUPPRESSED_FOR_SECTOR` exactly when §7.3 says the row is not meaningful for it, and
 *    `NOT_SOURCED` exactly when no source of ours publishes it. The two reasons stay apart, which is
 *    plan.md §13's "misleading blank ratios → user distrusts app" avoided rather than caused.
 * 3. The order is first-mention-wins, which is deterministic for a given set of companies and reads
 *    naturally: the first company's own ranking leads, and the rows only they have appear after it.
 *
 * ### Why the slot's *companions* stay in one cell
 * plan.md §7.2's rank 3 is "debt/equity **+** interest coverage" — one card, two numbers, because
 * they ride together. Splitting them into two rows would renumber the plan's ranks and imply the
 * plan says something it does not, so a cell holds [RatioSlot.ids] in order.
 *
 * ### What this deliberately does not produce
 * No winner, no ordering by value, no "best in class" marker, no total, and no score combining slots.
 * plan.md's two-number design exists because a percent, a multiple and a day count answer different
 * questions; any function that folded them into one number would be inventing a measure that nothing
 * in the app computed. See `CompareScreen`'s KDoc for what is said on screen instead.
 */
object CompareAlignment {

    /**
     * One row per `RatioSlot` any of [companies] publishes, in first-mention order.
     *
     * A company with nothing cached still produces cells on every row, each carrying its
     * [CompareCompany.unavailableReason] — so the row's height is set by the companies that *do*
     * publish it, and the missing company is an explained absence rather than a shorter column.
     */
    fun rows(companies: List<CompareCompany>): List<CompareRow> {
        val order = LinkedHashMap<String, RatioSlot>()
        val askedBy = LinkedHashMap<String, MutableList<String>>()
        for (company in companies) {
            if (!company.hasFigures) continue
            for (slot in RatioPriority.order(company.sector)) {
                val key = slot.id.name
                if (!order.containsKey(key)) {
                    order[key] = slot
                    askedBy[key] = mutableListOf()
                }
                askedBy.getValue(key).add(company.symbol)
            }
        }
        return order.map { (key, slot) ->
            CompareRow(
                slot = slot,
                origin = slot.origin,
                askedBy = askedBy.getValue(key).distinct(),
                cells = companies.map { company -> cellFor(company, slot) },
            )
        }
    }

    /**
     * One company's figures for [slot], with the sector's own decisions applied.
     *
     * ### Why the value is handed to the domain rather than printed here
     * [RatioPriority.availability] is the one place that knows §7.3's suppression table, and it
     * refuses to let a suppressed ratio keep a value even if this file handed it one. That is what
     * makes "a bank can never be shown a debt/equity" a property of the type rather than a review
     * checklist item.
     *
     * A cell for a company with no cached page is a [RatioAvailability.missing] for every ratio in
     * the slot, carrying the page-level reason — "no fundamentals page has been fetched for X on
     * this device" — which is a *different* statement from "X publishes no such row" and the reader
     * is owed the difference.
     */
    private fun cellFor(company: CompareCompany, slot: RatioSlot): CompareCell = CompareCell(
        symbol = company.symbol,
        sector = company.sector,
        values = slot.ids.map { id ->
            val reading = company.readings[id]
            val availability = if (!company.hasFigures) {
                RatioAvailability.missing(
                    company.unavailableReason ?: "no fundamentals page is stored for this company",
                )
            } else {
                RatioPriority.availability(
                    sector = company.sector,
                    id = id,
                    value = reading?.value,
                    note = reading?.note,
                ).let { base ->
                    // Staleness outranks the value, exactly as it does on the Fundamentals tab: an
                    // old figure is still true of its own date, and the cell says so rather than
                    // presenting it as current.
                    if (company.isStale && base.isRenderable) {
                        RatioAvailability.stale(
                            value = requireNotNull(base.value),
                            note = STALE_NOTE,
                        )
                    } else {
                        base
                    }
                }
            }
            CompareValue(id = id, unit = id.unit, availability = availability)
        },
    )

    /**
     * Why every figure on a stale company is stale.
     *
     * plan.md §10 makes staleness a visible fact, not a silent one, and the window is named rather
     * than left for the reader to infer. The identical sentence appears on every cell of the column,
     * because a reader comparing two figures must be able to see that one of them is older before
     * they compare it.
     */
    const val STALE_NOTE: String =
        "older than the 7-day window for a fundamentals snapshot (plan.md §10); the figure is " +
            "still true of its own date, it is not current"

    /**
     * Where a row came from, in the reader's terms.
     *
     * A row the plan ranks, a row §7.3 added for a sector, and a row the plan does not rank at all
     * are three different kinds of claim, and [SlotOrigin] keeps them apart so this screen can say
     * which is which instead of printing an undifferentiated list of numbers.
     */
    fun originLabel(origin: SlotOrigin): String = when (origin) {
        SlotOrigin.PLAN -> "Ranked by the plan"
        SlotOrigin.SECTOR_SPECIFIC -> "Required for one sector's reading"
        SlotOrigin.SUPPLEMENTARY -> "Published by our source, ranked by no plan row"
    }
}