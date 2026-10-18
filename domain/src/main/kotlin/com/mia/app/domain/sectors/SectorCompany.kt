package com.mia.app.domain.sectors

import com.mia.app.domain.model.Sector
import com.mia.app.domain.ratios.RatioId
import com.mia.app.domain.ratios.RatioPriority
import com.mia.app.domain.ratios.RatioSlot
import com.mia.app.domain.ratios.SectorResolution
import java.time.LocalDate

/**
 * One company this device already holds a screener.in page for, as the sector view sees it.
 *
 * ### Why this is one type and not a separate one for "the stock" and "its peers"
 * A sector comparison puts a number beside another number, and **both of them come out of the same
 * cache of pages the reader has already fetched**. The subject is not a different kind of thing from
 * a peer; it is the one peer whose column the reader opened. Splitting the two into separate types
 * would suggest the subject's figures are computed differently, which is exactly the claim this
 * screen must not make.
 *
 * ### Why the sector travels as a [SectorResolution] and not as a [Sector]
 * plan.md §7.1: *"Default to `OTHER` and show a generic list if unresolved — never guess."* That
 * rule is only auditable if the **reason** travels with the answer, and [SectorResolution] already
 * carries it — the keyword that matched, or the evidence that the classification was declined. The
 * screen has to be able to say "we could not classify this one", because the difference between a
 * company that is in Banking and a company we merely failed to place decides whether a sector median
 * is computed at all (see [SectorPopulation]).
 *
 * ### Why [readings] is a plain map of nullable values and not a list of availability objects
 * A per-company absence note ("no fiscal year publishes both 'Borrowings' and 'Equity Capital +
 * Reserves'") is worth showing when the reader is looking at that company — which the Fundamentals
 * tab and the Compare screen already do. A **median** is a different shape of claim: it never shows
 * an individual company's figure, so a note attached to one of seven peers would be a sentence
 * about a number the reader cannot see. What the reader instead needs is *how many* peers
 * contributed, which is what [SectorMedian.sampleSize] carries. Carrying notes here would invite a
 * future caller to surface them per-peer, which would re-introduce the "one page of seven" framing
 * this whole screen exists to avoid.
 *
 * @property isStale older than [com.mia.app.domain.ratios.FreshnessKind.FUNDAMENTALS_SNAPSHOT]'s
 *   window. A stale page is still **true of its own date**, so it is never silently dropped from a
 *   median — it is excluded from the sample and counted separately in [SectorMedian.excludedStale],
 *   so a median is never silently built on a different population than the one printed beside it.
 */
data class SectorCompany(
    val symbol: String,
    val name: String,
    val resolution: SectorResolution,
    /** ISO-8601 date of the stored page, or `null` when the source published none. */
    val asOf: LocalDate?,
    val isStale: Boolean,
    /** Raw value per `RatioId`, **before** any sector rule. See the class KDoc. */
    val readings: Map<RatioId, Double?>,
) {
    /** The sector this company was classified to, resolved or not. Never guessed. */
    val sector: Sector get() = resolution.sector

    /** `true` when §7.3's override table applies, rather than the generic §7.2 order. */
    val usesSectorOverrides: Boolean get() = resolution.usesSectorOverrides

    /** The stock's own figure for [id], or `null`. No sector rule applied — that is the caller's. */
    fun readingOf(id: RatioId): Double? = readings[id]?.takeIf { it.isFinite() }

    /**
     * The rows this company's sector leads with.
     *
     * Delegated to [RatioPriority] rather than restated, because §7.3's composed order is the one
     * list every ratio surface in this app must agree on. A sector view that ordered its rows any
     * other way would let a bank show D/E on this screen while the Fundamentals tab says the ratio
     * is meaningless for it.
     */
    fun planOrder(): List<RatioSlot> = RatioPriority.order(sector)
}
