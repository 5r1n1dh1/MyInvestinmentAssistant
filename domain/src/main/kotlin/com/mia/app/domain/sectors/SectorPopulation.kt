package com.mia.app.domain.sectors

import com.mia.app.domain.model.Sector
import com.mia.app.domain.ratios.RatioId

/**
 * The peers a sector median is drawn from — and, more importantly, **the sentence that says what
 * that peer group is**.
 *
 * ### The problem this type exists to solve
 * planv2 §W3 asks for "sector median cards beside the stock's ratios, aggregated per-sector from the
 * persisted screener.in industry taxonomy". Read literally that is a trap. The persisted `ratios`
 * table holds **one row per symbol the reader has looked at**, not a sector's membership. So the
 * median computed over it is a median over however many peers happen to be cached — and on a fresh
 * install that is *one symbol: the stock itself*. Printing "sector median 12.4" there would be the
 * reader's own number relabelled as the industry's, which is the single most misleading thing this
 * screen could do.
 *
 * ### Why this does not widen the stored data instead
 * The other escape from a tiny sample is to fetch the whole sector. That is not available here:
 * screener.in publishes one page per company, the app holds no credentials and will not acquire any
 * (plan.md §3), and the live probe in `docs/LIVE-PROBE.md` records that several of the ratios §7.3
 * wants for a sector sit behind that site's login wall. Fetching several hundred peer pages would
 * also spend the one-fetch-a-day budget plan.md §13 allots, on a screen the reader may open once.
 * So the population is **what the device actually holds**, and the whole design of this file is to
 * make that population impossible to forget: [members] is public, [sampleSize] is on every figure,
 * and [describe] produces the sentence that travels with each median.
 *
 * ### Why the subject is never its own peer
 * `members` excludes the subject. A median that included the stock being judged would be a figure
 * partly made of the thing it measures, and on a device holding exactly one page it would return
 * that page's value — the fabricated benchmark this screen exists not to print. Excluding the
 * subject makes the failure mode *structural* rather than a threshold to tune: there is no value of
 * [SectorMedian.MINIMUM_SAMPLE] at which a one-page device produces a comparison.
 *
 * ### Why an unresolved sector produces no population at all
 * plan.md §7.1 sends unresolved companies to [Sector.OTHER], and [SectorResolution]'s own `init`
 * keeps them there. `OTHER` is therefore not a bucket of similar businesses — it is *everything this
 * app declined to classify*, which mixes "no keyword matched" (a genuine but unnamed business) with
 * "two sectors matched" (e.g. `LIC HOUSING FINANCE`, which is genuinely both NBFC and Real Estate).
 * A median over that pool would be a statistic about the app's own ignorance, presented as a fact
 * about a sector. So [select] returns an **empty** population carrying [notComparableReason] and the
 * screen shows that sentence instead of a number.
 *
 * @property members peers in [sector] other than the subject, sorted by symbol so the population is
 *   identical between renders.
 * @property subjectHoldingCount how many symbols the device holds a page for at all — the
 *   denominator a reader needs to judge "7 of 9". Carried because "7" alone invites the reader to
 *   assume 7 is the whole sector, and it is not.
 */
data class SectorPopulation(
    val sector: Sector,
    val subjectSymbol: String,
    val subjectHoldingCount: Int,
    val members: List<SectorCompany>,
    /** Why no median may be computed, or `null` when one may. Never blank when non-null. */
    val notComparableReason: String? = null,
) {
    init {
        require(notComparableReason.isNullOrBlank() || members.isNotEmpty()) {
            "an empty population must say why; a bare empty list is the misleading blank " +
                "plan.md §13 warns about"
        }
        require(subjectSymbol !in members.map { it.symbol }) {
            "${subjectSymbol}: the subject is never its own peer"
        }
    }

    /** True when a median may be computed for at least one ratio. */
    val isComparable: Boolean get() = notComparableReason == null && members.isNotEmpty()

    /** Peers carrying a finite, non-null figure for [id], before the freshness cut. */
    fun candidatesFor(id: RatioId): List<SectorCompany> =
        members.filter { it.readingOf(id) != null }

    /** Peers inside the freshness window — the only ones a median may be built from. */
    fun freshCandidatesFor(id: RatioId): List<SectorCompany> =
        candidatesFor(id).filterNot { it.isStale }

    /**
     * The population, in the words a reader can check.
     *
     * "[SectorResolution] — 7 of the 9 companies this device holds a page for". This is the
     * sentence the honesty contract asks for: a median is meaningless without its denominator, and
     * the denominator is never "the sector" — it is a list of pages this device happens to hold.
     */
    fun describe(n: Int): String = when {
        !isComparable -> notComparableReason!!
        members.size == 1 -> "n=$n of the 1 other ${sectorLabel()} company this device holds a " +
            "page for"

        else -> "n=$n of the ${members.size} other ${sectorLabel()} companies this device holds a " +
            "page for"
    }

    /** The denominator sentence on its own, for the header. */
    fun populationSummary(): String = when {
        !isComparable -> notComparableReason!!
        members.isEmpty() -> "This device holds a page for no other ${sectorLabel()} company, so " +
            "there is nothing to compare against. Look up a few peers and this fills in."

        else -> "The ${members.size} other ${sectorLabel()} companies this device holds a page " +
            "for are the whole of the comparison. The sector itself is not available to this app."
    }

    private fun sectorLabel(): String =
        com.mia.app.domain.ratios.SectorResolver.displayNameOf(sector)

    companion object {

        /**
         * Builds the peer group for [subject] out of everything this device holds.
         *
         * Deliberately a **pure** function over the whole cache rather than a query filtered by
         * sector. The stored rows carry screener.in's industry sentence, not a validated sector, so
         * the classification happens here where it can be tested — and where the refusal to pool
         * [Sector.OTHER] is one visible `if` instead of a convention three layers away.
         *
         * @param cached every company this device holds a page for, the subject among them.
         * @param subject the company being read. Must be in [cached]; a caller that passes a symbol
         *   the device has never fetched gets an empty population with a stated reason rather than a
         *   comparison against whatever happens to be in the cache.
         */
        fun select(subject: SectorCompany, cached: List<SectorCompany>): SectorPopulation {
            val holdingCount = cached.map { it.symbol }.distinct().size
            val others = cached.filter { it.symbol != subject.symbol }.sortedBy { it.symbol }

            if (!subject.resolution.resolved || subject.sector == Sector.OTHER) {
                return SectorPopulation(
                    sector = subject.sector,
                    subjectSymbol = subject.symbol,
                    subjectHoldingCount = holdingCount,
                    members = emptyList(),
                    notComparableReason = UNRESOLVED_REASON,
                )
            }

            // Sector first, always. A ROCE for a bank and a ROCE for an FMCG company are different
            // quantities (plan.md §7.3), so a peer group that mixed them would average two different
            // definitions of the same row. Filtering here — rather than at the median — is what makes
            // "no peer from another sector can ever reach this figure" a property of the population.
            val peers = others.filter { it.sector == subject.sector }

            return if (peers.isEmpty()) {
                SectorPopulation(
                    sector = subject.sector,
                    subjectSymbol = subject.symbol,
                    subjectHoldingCount = holdingCount,
                    members = emptyList(),
                    notComparableReason = NO_PEERS_REASON,
                )
            } else {
                SectorPopulation(
                    sector = subject.sector,
                    subjectSymbol = subject.symbol,
                    subjectHoldingCount = holdingCount,
                    members = peers,
                    notComparableReason = null,
                )
            }
        }

        /** plan.md §7.1's refusal, phrased for a reader rather than for a log. */
        const val UNRESOLVED_REASON: String =
            "No sector comparison: this device could not classify the company, so there is no " +
                "sector to compare it against. plan.md §7.1 sends an unresolved company to the " +
                "generic ratio list rather than guessing, and every company this app could not " +
                "place would otherwise be pooled into one group whose only shared property is our " +
                "uncertainty."

        /** The one-page case, named so the reader learns what would fix it. */
        const val NO_PEERS_REASON: String =
            "No sector comparison: this device holds a page for this company and no other one in " +
                "its sector, so a median would be this company's own number wearing a sector's " +
                "name. Look up a few peers and the comparison fills in."
    }
}
