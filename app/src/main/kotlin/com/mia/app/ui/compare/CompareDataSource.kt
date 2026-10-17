package com.mia.app.ui.compare

import com.mia.app.domain.model.Sector
import com.mia.app.domain.model.Source
import com.mia.app.domain.ratios.RatioAvailability
import com.mia.app.domain.ratios.RatioId
import com.mia.app.domain.ratios.RatioSlot
import com.mia.app.domain.ratios.RatioUnit
import com.mia.app.domain.ratios.SlotOrigin
import java.time.LocalDate

/**
 * The Compare screen's entire view of the outside world (gap E6).
 *
 * ### Contract, as for every screen's data source
 * * `suspend`, already off the main thread.
 * * `:domain` types only.
 * * A symbol with nothing cached still returns a [CompareCompany] — with
 *   [CompareCompany.unavailableReason] set — rather than `null`, because a missing company must be
 *   an **explained column**, not a hole the reader has to interpret.
 */
interface CompareDataSource {

    /**
     * Two or three companies, aligned by `RatioSlot` identity.
     *
     * @param symbols already normalised by the caller (trimmed, upper-cased, de-duplicated). The
     *   adapter does not reorder them: the order the reader typed is the order the columns appear
     *   in, because a comparison that silently reorders its own columns is one the reader cannot
     *   follow.
     */
    suspend fun compare(symbols: List<String>): CompareComparison
}

/** One company's figures, before any row alignment has been applied. */
data class CompareCompany(
    val symbol: String,
    val name: String,
    val sector: Sector,
    /** False when `SectorResolver` could not classify it, which changes which rows exist at all. */
    val sectorResolved: Boolean,
    val asOf: LocalDate?,
    val source: Source,
    /** Older than plan.md §10's window for a fundamentals snapshot. The figures are true of their date. */
    val isStale: Boolean,
    /**
     * Why this company has no figures at all, or `null` when it has some.
     *
     * Non-null means every cell for this column renders as an absent-with-reason, which is the whole
     * point: a column of dashes the reader has to interpret is a column that will be misread.
     */
    val unavailableReason: String? = null,
    /** Raw value and note per `RatioId`, with **no** sector applied yet. See the class KDoc. */
    val readings: Map<RatioId, CompareReading> = emptyMap(),
) {
    /** True when the column has a page behind it. */
    val hasFigures: Boolean get() = unavailableReason == null
}

/**
 * One ratio's raw figure for one company.
 *
 * @property note the reason the *source* gave for the figure's absence — "no fiscal year publishes
 *   both 'Borrowings' and 'Equity Capital + Reserves'". Passed to `RatioPriority.availability` so a
 *   missing cell can name the missing input rather than just being missing.
 */
data class CompareReading(
    val value: Double?,
    val note: String? = null,
)

/**
 * The aligned comparison: the columns, and one row per `RatioSlot` any of them publishes.
 *
 * @property rows in the order the first company lists them, then each later company's extra slots.
 *   Deterministic for a given set of companies, so the screen never reshuffles between renders.
 */
data class CompareComparison(
    val companies: List<CompareCompany>,
    val rows: List<CompareRow>,
) {
    val isEmpty: Boolean get() = companies.isEmpty() || rows.isEmpty()

    companion object {
        val EMPTY = CompareComparison(emptyList(), emptyList())
    }
}

/**
 * One row: a single `RatioSlot`, and what each company publishes for **that slot**.
 *
 * ### Why a row is keyed by the slot and not by a position
 * Sectors differ. `RatioPriority.order(BANKING)` and `RatioPriority.order(FMCG)` are different lists:
 * a bank has no operating margin to show and an FMCG company has no net-interest-margin story. A
 * comparison that zipped two of those lists positionally would put a bank's loan book beside an
 * FMCG company's gross margin under one label and call it a comparison — which is the exact failure
 * plan.md §7.3 exists to prevent ("a single global list misleads on banks"). Keying the row on
 * [RatioSlot] makes that impossible: a row exists because at least one company publishes it, and a
 * company that does not publish it gets a stated absence.
 *
 * @property askedBy the symbols whose composed plan contains this slot. Rendered so a reader can
 *   see *why* a row is on the screen at all — "Banking only" is a different fact from "both of them".
 * @property cells one per company, in the same order as [CompareComparison.companies]. A company
 *   with nothing cached still has a cell, carrying [CompareCompany.unavailableReason].
 */
data class CompareRow(
    val slot: RatioSlot,
    val origin: SlotOrigin,
    val askedBy: List<String>,
    val cells: List<CompareCell>,
)

/**
 * One company's cell on one row: the figures for every ratio in the slot, primary first.
 *
 * The slot may carry companions — plan.md §7.2's "debt/equity + interest coverage" is one card with
 * two numbers — and both are kept in one cell so the pairing the plan chose survives the trip. The
 * alternative, splitting them into two rows, would silently renumber the plan's ranks.
 */
data class CompareCell(
    val symbol: String,
    val sector: Sector,
    val values: List<CompareValue>,
)

/**
 * One ratio's figure for one cell, with the state that explains its absence.
 *
 * [availability] is what guarantees the central rule: `RatioAvailability.init` forbids a
 * `SUPPRESSED_FOR_SECTOR`, `NOT_SOURCED` or `MISSING` state carrying a value, so a cell cannot print
 * `0.00` for a bank's debt/equity, and cannot print a dash the reader has to interpret. Every
 * non-renderable state carries a note, and the screen renders the note.
 */
data class CompareValue(
    val id: RatioId,
    val unit: RatioUnit,
    val availability: RatioAvailability<Double>,
) {
    /**
     * True when there is a figure to print.
     *
     * `availability.isRenderable && value != null` rather than `isRenderable` alone, because
     * `RatioAvailability` accepts a `STALE` state carrying no value — the staleness rules in the
     * schema allow it — and a screen that formats a null there would crash on a ratio row. A cell
     * in that state renders its reason instead, which is the correct output anyway.
     */
    val isRenderable: Boolean get() = availability.isRenderable && availability.value != null

    /** The sentence a cell shows when there is no figure. Never blank, never a bare dash. */
    fun absenceText(): String {
        val note = availability.note ?: "this figure is not available for this company"
        return when (availability.state) {
            com.mia.app.domain.ratios.RatioState.SUPPRESSED_FOR_SECTOR ->
                "Not meaningful here — $note"

            com.mia.app.domain.ratios.RatioState.NOT_SOURCED -> "No source of ours publishes it — $note"

            com.mia.app.domain.ratios.RatioState.MISSING -> "Not published on this page — $note"

            com.mia.app.domain.ratios.RatioState.STALE -> "$STALE_PREFIX $note"

            com.mia.app.domain.ratios.RatioState.AVAILABLE -> note
        }
    }

    private companion object {
        const val STALE_PREFIX = "Older than its window —"
    }
}