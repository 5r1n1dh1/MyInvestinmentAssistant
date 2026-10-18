package com.mia.app.domain.sectors

import com.mia.app.domain.ratios.RatioAvailability
import com.mia.app.domain.ratios.RatioId
import com.mia.app.domain.ratios.RatioPriority
import com.mia.app.domain.ratios.RatioState

/**
 * A sector median for one ratio — or, far more often on a real device, the stated reason there is
 * not one.
 *
 * ### The invariant, and why it is enforced in `init`
 * A median that carries a value must carry a non-zero [sampleSize], and a median that carries no
 * value must say why. `RatioAvailability` already forbids a `SUPPRESSED_FOR_SECTOR`, `NOT_SOURCED`
 * or `MISSING` state from carrying a number, so "median 0.0" cannot be printed for a ratio that
 * does not exist — but it cannot stop a caller from publishing a median over **one** peer. That is
 * the failure this type is here to prevent, and [sampleSize] is the field that makes it visible to
 * a reader and assertable by a test.
 *
 * @property sampleSize how many peers actually contributed a figure. This is the `n`, and it is
 *   rendered on **every** published median: the honesty contract requires a rate's sample size, and
 *   a median is the same kind of claim about a small number of points.
 * @property peersWithFigure peers that published *some* figure for this ratio, before the freshness
 *   cut — so a reader can see the difference between "the peers don't publish it" and "the peers'
 *   pages are too old to count".
 * @property excludedStale peers that published a figure but whose page is outside
 *   [com.mia.app.domain.ratios.FreshnessKind.FUNDAMENTALS_SNAPSHOT]'s window. Counted, never mixed
 *   in: silently including them would produce a median over a population the printed sentence does
 *   not describe.
 */
data class SectorMedian(
    val id: RatioId,
    val availability: RatioAvailability<Double>,
    val sampleSize: Int,
    val peersWithFigure: Int,
    val excludedStale: Int,
    /** The population sentence, printed under the figure. Never blank when a value is published. */
    val populationNote: String? = null,
) {
    init {
        require(sampleSize == 0 || availability.state == RatioState.AVAILABLE) {
            "$id: only an AVAILABLE median may carry a sample size (state is ${availability.state})"
        }
        require(sampleSize == 0 || sampleSize >= SectorBenchmark.MINIMUM_SAMPLE) {
            "$id: a median over $sampleSize peer(s) is not a sector benchmark; the floor is " +
                "${SectorBenchmark.MINIMUM_SAMPLE}"
        }
    }

    /** True when there is a median to print. */
    val isPublished: Boolean get() = availability.isRenderable && availability.value != null

    /**
     * The sentence under the figure, or the reason there is none.
     *
     * Never a bare dash and never a blank: a dash a reader has to interpret is precisely the
     * "misleading blank ratio" plan.md §13 lists as the reason users would stop trusting the app.
     */
    fun note(): String = populationNote ?: availability.note ?: FALLBACK_NOTE

    private companion object {
        const val FALLBACK_NOTE = "no sector median is computed for this ratio"
    }
}

/**
 * The median itself: the one computation in this package, and the place the tiny-sample rule lives.
 *
 * ### Why a median at all, and not a mean
 * A sector's ratios are not a tidy distribution — one bank with a provisioning blow-up, one
 * mid-cap FMCG company with a one-off land gain, and a mean of five peers is whichever of those the
 * arithmetic happened to favour. The median reports the middle peer, which is the only number that
 * does not move when a single company does. That is the whole justification for the statistic; it
 * is **not** a claim that three or four cached companies describe an industry, which is what
 * [MINIMUM_SAMPLE] is about.
 *
 * ### The decision order, and why each step outranks the one after it
 * 1. **§7.3's sector decision.** A ratio the sector removed is not "a median we happen not to have".
 *    It is not meaningful here, and it is *never* filled from another sector — the population is
 *    sector-filtered before a value is ever looked for, so a bank D/E row cannot be completed by a
 *    software company's D/E.
 * 2. **Whether a population exists at all.** An unresolved sector and a one-page device are
 *    different sentences, and both are shown rather than swallowed.
 * 3. **The count.** Below [MINIMUM_SAMPLE], no value is produced at all — not a small-sample median
 *    with a caveat, not the value with an asterisk. The honest output of "median of two companies"
 *    is the pair.
 * 4. **The median**, over peers inside the freshness window.
 */
object SectorBenchmark {

    /**
     * The smallest peer count this app will call a sector median.
     *
     * ### Why 4, stated as what it is
     * This is a **disclosure threshold, not a statistical claim**. Nothing about n = 4 makes a
     * median representative of an industry, and no n would: the population is whatever pages this
     * device happens to hold, which is why every published median prints its `n` and its
     * denominator beside it and the reader can weigh it for themselves.
     *
     * What the threshold does rule out is the two figures that are not medians at all:
     *
     * * **n = 1** — the "median" *is* that one company. This is the fresh-install case, and it is
     *   the one the task is really about: on a new device the only cached page is the stock the
     *   reader opened, so an unguarded sector median prints the reader's own number back to them
     *   wearing a sector's name.
     * * **n = 2 and 3** — the median of two numbers is their average and of three is whichever sits
     *   in the middle, so the "sector" figure would be a function of two or three specific
     *   companies that the reader can name. A benchmark they could reproduce by hand from one page
     *   each is not a benchmark.
     *
     * Below the floor the app prints **which peers it has and what it would take** instead of a
     * figure, so the reader is left knowing exactly how to get an answer rather than holding a
     * number that looks like one.
     */
    const val MINIMUM_SAMPLE: Int = 4

    /**
     * The median for [id] over [population], or the stated reason there is not one.
     *
     * Pure and total: every input produces a result, and no branch of it is allowed to return a
     * value without also returning the count behind it.
     */
    fun median(sector: com.mia.app.domain.model.Sector, id: RatioId, population: SectorPopulation): SectorMedian {
        // Step 1 — §7.3 decides before any data is consulted, exactly as it does for a single
        // company's ratio card. Handing this to RatioPriority rather than reimplementing the table
        // is what stops the sector view and the Fundamentals tab from ever disagreeing about which
        // ratios a bank has.
        val sectorState = RatioPriority.sectorStateFor(sector, id)
        if (sectorState == RatioState.SUPPRESSED_FOR_SECTOR) {
            return SectorMedian(
                id = id,
                availability = RatioAvailability.suppressed(
                    RatioPriority.sectorNoteFor(sector, id)
                        ?: "not meaningful for ${com.mia.app.domain.ratios.SectorResolver.displayNameOf(sector)}",
                ),
                sampleSize = 0,
                peersWithFigure = 0,
                excludedStale = 0,
                populationNote = null,
            )
        }
        if (sectorState == RatioState.NOT_SOURCED) {
            return SectorMedian(
                id = id,
                availability = RatioAvailability.notSourced(
                    RatioPriority.notSourcedNote(id, sector),
                ),
                sampleSize = 0,
                peersWithFigure = 0,
                excludedStale = 0,
                populationNote = null,
            )
        }

        // Step 2 — no population, no median, and the reason differs by cause.
        if (!population.isComparable) {
            return SectorMedian(
                id = id,
                availability = RatioAvailability.missing(
                    population.notComparableReason ?: SectorPopulation.NO_PEERS_REASON,
                ),
                sampleSize = 0,
                peersWithFigure = 0,
                excludedStale = 0,
                populationNote = null,
            )
        }

        // Step 3 — count the peers, and count them *honestly*: everyone who published the figure,
        // then everyone whose page is too old to use. The two numbers together are what tells a
        // reader whether the gap is "peers don't publish this" or "peers are out of date".
        val candidates = population.candidatesFor(id)
        val fresh = candidates.filterNot { it.isStale }
        val stale = candidates.size - fresh.size

        if (fresh.size < MINIMUM_SAMPLE) {
            return SectorMedian(
                id = id,
                availability = RatioAvailability.missing(tooFewNote(fresh.size, stale)),
                sampleSize = 0,
                peersWithFigure = candidates.size,
                excludedStale = stale,
                populationNote = null,
            )
        }

        // Step 4 — the median, over peers inside the window only.
        val sorted = fresh.mapNotNull { it.readingOf(id) }.sorted()
        return SectorMedian(
            id = id,
            availability = RatioAvailability.available(medianOf(sorted)),
            sampleSize = sorted.size,
            peersWithFigure = candidates.size,
            excludedStale = stale,
            populationNote = population.describe(sorted.size),
        )
    }

    /**
     * The middle of a sorted sample.
     *
     * An even-sized sample takes the mean of the two middle values, which is the conventional
     * definition and the only one that is continuous in the data — the lower median would make the
     * figure jump discontinuously whenever the sample size changed by one, and the population on
     * this screen changes size as the reader looks up companies.
     */
    fun medianOf(sorted: List<Double>): Double {
        require(sorted.isNotEmpty()) { "median of nothing" }
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2.0
    }

    /**
     * Why there is no median, in the only two shapes a reader can act on.
     *
     * Both name the count and what would change it. "Not enough data" is a dead end; "2 of the 4
     * peers you need, and 3 more have pages older than a week" is an instruction.
     */
    private fun tooFewNote(freshCount: Int, staleCount: Int): String = buildString {
        append("Median withheld: ")
        append(freshCount)
        append(if (freshCount == 1) " peer publishes " else " peers publish ")
        append("this ratio; a median needs ")
        append(SectorBenchmark.MINIMUM_SAMPLE)
        append(".")
        if (staleCount > 0) {
            append(" A further ")
            append(staleCount)
            append(
                if (staleCount == 1) {
                    " peer has a page, but it is older than the 7-day window for a fundamentals " +
                        "snapshot, so it is not counted"
                } else {
                    " peers have pages, but they are older than the 7-day window for a fundamentals " +
                        "snapshot, so they are not counted"
                },
            )
        }
    }
}
