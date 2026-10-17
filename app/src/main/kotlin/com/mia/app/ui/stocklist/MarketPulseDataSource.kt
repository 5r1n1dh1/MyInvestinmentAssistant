package com.mia.app.ui.stocklist

import com.mia.app.domain.technical.Breadth
import java.time.LocalDate

/**
 * The watchlist screen's view of the market as a whole — planv2 §W3.
 *
 * ### Why this belongs on the watchlist and not on a stock's page
 *
 * Breadth is context for a *decision about a stock*, and the watchlist is where that decision is
 * framed. A reader looking at one stock's chart does not need to know 1,840 of 2,900 names closed
 * up; a reader choosing which of six stocks to look at first does. Putting it on the detail screen
 * would answer a question nobody asked yet.
 *
 * ### Why the interface returns the whole [Breadth] rather than formatted strings
 *
 * Every statistic carries its own population — `smaEligible`, `extremesEligible`, `unclassified` —
 * and those denominators are the difference between a real market measure and a number that looks
 * authoritative while describing twelve symbols. If this interface handed the screen a preformatted
 * sentence, the screen could not show the population, and a coverage number nobody can see is the
 * one form of dishonesty this app is built to avoid. So the domain's object crosses the boundary
 * intact and `:app` decides how much of it to render.
 *
 * ### Contract, as for every screen's data source
 * - `suspend`, already off the main thread.
 * - `:domain` types only.
 * - `null` means **"there is nothing stored"**, not "we failed". A failure throws.
 */
interface MarketPulseDataSource {

    /**
     * Breadth for the most recent stored daily session, or `null` when no session is stored.
     *
     * `null` is the ordinary state on a fresh install and after a failed refresh, and the screen
     * renders it as "not measured yet" rather than as zeros — breadth measured over no symbols
     * would render as a total market decline, which is the single most misleading thing this card
     * could show.
     */
    suspend fun breadth(): Breadth?

    /** The session [breadth] describes, or `null` when nothing is stored. */
    suspend fun breadthAsOf(): LocalDate?
}

/** Thrown when the breadth read fails as opposed to having nothing to report. */
class MarketPulseUnavailableException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)