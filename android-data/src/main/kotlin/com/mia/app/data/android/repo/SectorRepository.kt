package com.mia.app.data.android.repo

import com.mia.app.domain.model.Source
import com.mia.app.domain.sectors.SectorCompany
import java.time.LocalDate

/**
 * How the sector view reaches the pages this device already holds.
 *
 * ### Why this is a repository and not a DAO read at the call site
 * The sector median is computed over a **peer group**, and building one needs three things the
 * storage layer alone cannot supply: the company's display name (to resolve a sector with the same
 * inputs the Fundamentals tab uses), the stored industry sentence, and the freshness of each page
 * relative to the others. Assembling that in `:app` would mean `:app` importing `:data` — which
 * plan.md §4.3 forbids — or a second, subtly different sector classification that disagreed with
 * the Fundamentals tab's. See `DefaultSectorRepository` for how it does it instead.
 *
 * ### What this interface deliberately does not offer
 * * **No method that fetches.** No source of ours publishes a sector's membership list, and fetching
 *   several hundred peer pages would spend the one-fetch-a-day budget plan.md §13 allots on a screen
 *   the reader may open once. The peer group is what the device holds, and the screen says so.
 * * **No method that returns a pre-computed median.** The median and — above all — the rule for when
 *   one may not be published belong in `:domain`, where they can be tested against a hand-built
 *   population. A median computed in the storage layer would be a second implementation of the
 *   sample floor, and the floor is exactly the thing that must not be implemented twice.
 */
interface SectorRepository {

    /**
     * Every company this device holds a fundamentals page for, with its sector resolved and its
     * freshness marked.
     *
     * The subject is included in the result rather than filtered out here: which company *is* the
     * subject is a question only the caller knows, and
     * [com.mia.app.domain.sectors.SectorPopulation.select] removes it from the peer group itself.
     * Keeping that in one place means no caller can accidentally build a comparison in which the
     * stock is one of its own peers.
     *
     * @param today the market day the freshness check is made against. Passed in rather than read
     *   from the system clock so the test does not depend on when it runs — the same convention
     *   [FreshnessPolicy.todayInMarket] exists to support.
     */
    suspend fun cachedCompanies(today: LocalDate): DataResult<List<SectorCompany>>

    companion object {
        /**
         * The provenance every read here is filed under.
         *
         * screener.in, because that is where all of these figures came from — one page per company,
         * fetched when the reader opened that company. A peer median is a statistic over those
         * pages; it is not a figure published by the source, and filing it under any other source
         * would misattribute a computation this app performed to a page nobody published.
         */
        val SOURCE: Source = Source.SCREENER_IN
    }
}
