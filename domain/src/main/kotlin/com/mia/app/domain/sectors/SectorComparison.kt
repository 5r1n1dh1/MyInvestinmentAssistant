package com.mia.app.domain.sectors

import com.mia.app.domain.model.Sector
import com.mia.app.domain.ratios.RatioAvailability
import com.mia.app.domain.ratios.RatioId
import com.mia.app.domain.ratios.RatioPriority
import com.mia.app.domain.ratios.RatioSlot
import com.mia.app.domain.ratios.RatioState
import com.mia.app.domain.ratios.RatioUnit
import com.mia.app.domain.ratios.SlotOrigin

/**
 * One ratio, for the stock the reader opened: its own figure and the sector median beside it.
 *
 * ### Why the two numbers are never combined
 * There is no difference, no ratio-to-median, no percentile and no colour on this type, and that is
 * the design rather than an omission. A stock at 18% ROCE against a sector median of 12% is a
 * **measurement**; "above average", "cheaper than peers" and "green" are conclusions, and the app's
 * whole position is that it does not draw that line (plan.md §1.4). A difference column would also
 * be arithmetically dishonest in a way that is easy to miss: it would be a difference between a
 * figure read off one company's page and a figure read off a median of several *cached* pages of
 * mixed dates — a subtraction whose result looks far more solid than either input.
 *
 * So the reader is given two numbers, each with its own provenance, and left to subtract them. That
 * is the same refusal `CompareAlignment` makes, and it is the reason this screen has no scoring
 * surface to tempt a later contributor.
 *
 * @property stock the subject's own figures, one per [RatioId] in [RatioSlot.ids] and in the same
 *   order, with §7.3 applied — a bank's D/E row is suppressed here exactly as it is on the
 *   Fundamentals tab.
 * @property benchmarks the sector median for each of those ratios, each with its own population, or
 *   the stated reason there is not one.
 *
 * ### Why companions stay in one row
 * plan.md §7.2's rank 3 is "debt/equity **+** interest coverage" — one card, two numbers, because
 * they ride together. Splitting them into two rows would renumber the plan's ranks and imply the
 * plan says something it does not, so [stock] and [benchmarks] are parallel lists in
 * [RatioSlot.ids] order and each pair is rendered side by side.
 */
data class SectorRow(
    val slot: RatioSlot,
    val origin: SlotOrigin,
    val sector: Sector,
    val stock: List<SectorValue>,
    val benchmarks: List<SectorMedian>,
) {
    init {
        require(stock.size == slot.ids.size && benchmarks.size == slot.ids.size) {
            "slot ${slot.id.name} must carry one stock figure and one median per ratio"
        }
    }

    /** Every `RatioId` this row shows, primary first — plan.md §7.2's two-metric cards stay whole. */
    val ids: List<RatioId> get() = slot.ids

    /**
     * The subject's own figure for [id], or `null` when the slot does not carry it.
     *
     * Null rather than a fabricated default: a caller that asks a slot for a ratio it does not have
     * has a bug, and inventing a 0.0 would be that bug becoming a number on a screen.
     */
    fun stockOf(id: RatioId): SectorValue? = stock.getOrNull(slot.ids.indexOf(id))

    /** The sector median for [id], or `null` on the same terms as [stockOf]. */
    fun benchmarkOf(id: RatioId): SectorMedian? = benchmarks.getOrNull(slot.ids.indexOf(id))

    /** True when the row exists only because plan.md §7.3 added it for this sector. */
    val isSectorSpecific: Boolean get() = origin == SlotOrigin.SECTOR_SPECIFIC

    /**
     * True when **every** ratio in the slot has a figure on both sides.
     *
     * The screen shows two columns **only** in this case. A row with a stock figure and no median is
     * one number beside the sentence explaining the absence, which reads as a gap in a table rather
     * than as a completed comparison — and the whole point is that a missing median is a *refusal*,
     * not a gap in the data.
     */
    val hasBothFigures: Boolean
        get() = slot.ids.indices.all { i ->
            stock[i].isRenderable && benchmarks[i].isPublished
        }

    /** The primary ratio's median, which is the one a reader looks for first. */
    val headlineBenchmark: SectorMedian get() = benchmarks.first()
}

/**
 * One ratio's figure for the subject, with the state that explains its absence.
 *
 * The same discipline as `CompareValue`: a suppressed, unsourced or missing state can never carry a
 * value (`RatioAvailability`'s own `init` enforces it), every non-renderable state carries a note,
 * and there is no blank and no zero standing in for a figure that does not exist.
 */
data class SectorValue(
    val id: RatioId,
    val unit: RatioUnit,
    val availability: RatioAvailability<Double>,
) {
    val isRenderable: Boolean get() = availability.isRenderable && availability.value != null

    /** The sentence shown in place of a figure. Never blank, never a bare dash. */
    fun absenceText(): String {
        val note = availability.note ?: "this figure is not available for this company"
        return when (availability.state) {
            RatioState.SUPPRESSED_FOR_SECTOR -> "Not meaningful here — $note"
            RatioState.NOT_SOURCED -> "No source of ours publishes it — $note"
            RatioState.MISSING -> "Not published on this page — $note"
            RatioState.STALE -> "$STALE_PREFIX $note"
            RatioState.AVAILABLE -> note
        }
    }

    private companion object {
        const val STALE_PREFIX = "Older than its window —"
    }
}

/**
 * The sector comparison: the subject, the peer population it would be measured against, and one row
 * per `RatioSlot` in that sector's own plan order.
 *
 * ### Why the rows come from `RatioPriority.order(sector)` and nowhere else
 * §7.3's composed order already carries the sector's overrides — a bank leads with ROE and its NIM
 * slots and has no debt/equity row at all. Taking the order from there is what guarantees this screen
 * and the Fundamentals tab cannot disagree about which ratios a sector has. The rows are keyed by
 * `RatioSlot.id` for the same reason `CompareAlignment` keys by identity rather than position:
 * the order is a property of the sector, and a row that moved because the list was rebuilt would be
 * a comparison that silently reorders itself between renders.
 *
 * ### What this object deliberately contains
 * * **No** winner, ordering, percentile, difference or score.
 * * **No** average of the ratios — a percent, a multiple and a day count answer different
 *   questions, and a function folding them into one number would invent a measure nothing computed.
 * * **No** cross-sector pooling — every peer in [population] is in the subject's sector, by
 *   construction.
 *
 * @property population the peer group and its denominator. Rendered above the rows, because a median
 *   whose population is described *below* the table has already been read as a fact about the sector.
 */
data class SectorComparison(
    val subject: SectorCompany,
    val population: SectorPopulation,
    val rows: List<SectorRow>,
) {
    val sector: Sector get() = subject.sector

    val isEmpty: Boolean get() = rows.isEmpty()

    /** True when at least one row carries a published median, so the two-column layout has a use. */
    val hasAnyBenchmark: Boolean get() = rows.any { row -> row.benchmarks.any { it.isPublished } }

    companion object {
        val EMPTY = SectorComparison(
            subject = SectorCompany(
                symbol = "",
                name = "",
                resolution = com.mia.app.domain.ratios.SectorResolution.unresolved("no company"),
                asOf = null,
                isStale = false,
                readings = emptyMap(),
            ),
            population = SectorPopulation(
                sector = Sector.OTHER,
                subjectSymbol = "",
                subjectHoldingCount = 0,
                members = emptyList(),
                notComparableReason = SectorPopulation.NO_PEERS_REASON,
            ),
            rows = emptyList(),
        )
    }
}

/**
 * Assembling the screen's rows.
 *
 * Split out of [SectorComparison] so the data classes stay data: every rule here is about **what may
 * appear on the screen**, and each one is a place where a later contributor could otherwise widen
 * the screen by accident.
 */
object SectorComparisonBuilder {

    /**
     * Builds the full comparison for [subject] from everything this device holds.
     *
     * @param cached every company with a stored page, subject included. The subject is removed from
     *   the peer group inside [SectorPopulation.select].
     */
    fun build(subject: SectorCompany, cached: List<SectorCompany>): SectorComparison {
        val population = SectorPopulation.select(subject = subject, cached = cached)
        val sector = subject.sector
        return SectorComparison(
            subject = subject,
            population = population,
            rows = RatioPriority.order(sector).map { slot ->
                SectorRow(
                    slot = slot,
                    origin = slot.origin,
                    sector = sector,
                    stock = slot.ids.map { id -> stockFor(subject, id) },
                    // §7.3's own table decides, per ratio. A companion is mediansed on its own
                    // terms: the median of interest coverage across peers is a different quantity
                    // from the median of debt/equity, and the two happen to share a card only
                    // because the plan says they do.
                    benchmarks = slot.ids.map { id ->
                        SectorBenchmark.median(sector = sector, id = id, population = population)
                    },
                )
            },
        )
    }

    /**
     * The subject's own figure for [id], with §7.3 applied through the same entry point the
     * Fundamentals tab uses.
     *
     * Staleness is layered on top of the sector's decision exactly as `CompareAlignment` layers it:
     * an old figure is still true of its own date, so it keeps its number and gains the sentence
     * that says it is old, rather than being replaced by nothing.
     */
    private fun stockFor(subject: SectorCompany, id: RatioId): SectorValue {
        val primary = RatioPriority.availability(
            sector = subject.sector,
            id = id,
            value = subject.readingOf(id),
        )
        return SectorValue(
            id = id,
            unit = id.unit,
            availability = if (subject.isStale && primary.isRenderable) {
                RatioAvailability.stale(
                    value = requireNotNull(primary.value),
                    note = STALE_NOTE,
                )
            } else {
                primary
            },
        )
    }

    /**
     * Every figure on a stale page is stale, and the window is named.
     *
     * The identical sentence appears on every cell of the column, because a reader who is about to
     * put a stale number beside a fresh median has to be able to see that before they do.
     */
    const val STALE_NOTE: String =
        "older than the 7-day window for a fundamentals snapshot (plan.md §10); the figure is " +
            "still true of its own date, it is not current"

    /**
     * The standing statement of what this screen is not.
     *
     * Rendered under the table rather than above it, because the question it answers — "why is
     * nothing marked as better?" — arrives after the reader has looked for a winner and not found
     * one. Every clause is a refusal this package enforces in code; the sentence exists so the
     * reader learns that the absence is deliberate rather than unfinished.
     */
    const val NO_VERDICT_NOTE: String =
        "Two numbers, no verdict. This screen does not say whether a figure is good, above " +
            "average or better than peers, because that is a conclusion and this app draws " +
            "conclusions about what a number *is*, not about what to do about it. A median " +
            "describes the companies whose pages this device happens to hold — not the sector — " +
            "and its count is printed under every figure so you can weigh it."

    /**
     * What the comparison is drawn from, said before the reader sees any number.
     *
     * The most important sentence on the screen. A reader who does not know the population will
     * read "median ROCE 14.2%" as a fact about the industry, and everything else on the page is
     * then read as a comparison against the industry.
     */
    const val POPULATION_DISCLOSURE: String =
        "Peers come from the screener.in pages already stored on this device. No source of ours " +
            "publishes a sector's membership list, so this is not the sector — it is whichever " +
            "companies this device has looked up."
}
