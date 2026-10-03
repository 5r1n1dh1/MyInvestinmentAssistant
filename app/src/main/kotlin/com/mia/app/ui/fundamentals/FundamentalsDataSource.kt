package com.mia.app.ui.fundamentals

import com.mia.app.domain.model.FundamentalsSnapshot
import com.mia.app.domain.model.RedFlag
import com.mia.app.domain.model.Source
import com.mia.app.domain.ratios.RatioId
import com.mia.app.domain.ratios.SectorResolution
import com.mia.app.domain.redflag.RuleSkip
import dagger.Qualifier
import java.time.LocalDate

/**
 * The dispatcher the Fundamentals tab's derivation runs on.
 *
 * The heavy part here is not IO: it is composing a dozen `RatioComputation` calls, two of which walk
 * up to ten years of annual columns, and then sorting them into the plan.md §7.2 order. That is CPU
 * work with no IO, so `Dispatchers.Default` is the honest pool — see
 * `TechnicalsBackgroundDispatcher` for the same reasoning.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class FundamentalsBackgroundDispatcher

/**
 * Everything the Fundamentals tab needs about one symbol, in one read.
 *
 * ### Why the periods are passed alongside the snapshot
 * `FundamentalsSnapshot.profitLoss` is a `List<AnnualRow>` whose values are **index-aligned with the
 * table's column headers** — and the headers themselves are not in the snapshot. plan.md §7.2 ranks 2
 * (operating margin + 5-year trend) and 7 (gross margin trend) on annual series, and
 * `RatioComputation.operatingMarginTrend` needs the labels (`"Mar 2026"`) to know which fiscal year a
 * column belongs to. So the headers travel with the rows.
 *
 * The two lists default to empty so an adapter written before the headers were plumbed still
 * compiles. The consequence is stated in the screen: with no headers the two margin trends degrade
 * to a *reasoned absence*, never to a fabricated point.
 *
 * @param sectorResolution the sector **and why**. plan.md §7.1: *"Default to `OTHER` and show a
 *   generic list if unresolved — never guess"*, which is only auditable if the reason travels with
 *   the answer. [com.mia.app.domain.ratios.SectorResolution] enforces in its own `init` that an
 *   unresolved sector stays `OTHER`.
 */
data class FundamentalsPage(
    val snapshot: FundamentalsSnapshot,
    val sectorResolution: SectorResolution,
    /** Column headers of the annual P&L, e.g. `["Mar 2024", …, "Mar 2026"]`. */
    val profitLossPeriods: List<String> = emptyList(),
    /** Column headers of the quarterly shareholding card, e.g. `["Sep 2025", …, "Jun 2026"]`. */
    val shareholdingPeriods: List<String> = emptyList(),
) {
    /** The sector the ratio list must be composed for. [Sector.OTHER] means "generic list". */
    val sector: com.mia.app.domain.model.Sector get() = sectorResolution.sector
}

/**
 * One field where two sources disagreed by more than 5% (plan.md §5.1).
 *
 * ### Why only the alternate travels
 * The **primary** value is already in `Ratios`, which is the output of plan.md §5.1's per-field
 * merge. Duplicating it here would create two answers to "what is this stock's P/E", and the one
 * that drifted is exactly the number a reader would act on.
 *
 * ### Why [deltaPercent] is carried and not recomputed
 * The merge that decided *these two disagree* is the merge that computed the divergence. Recomputing
 * it in the UI could disagree with the threshold that classified it, and then the screen would show
 * a conflict chip beside a delta under the threshold. Stated once, by the layer that decided it.
 *
 * @param deltaPercent `(alternate − primary) / |primary| × 100`, signed. plan.md §5.1's threshold is
 *   5%; the value is rendered verbatim with [com.mia.app.ui.common.UiStrings.conflictExplanation].
 */
data class RatioDivergence(
    val id: RatioId,
    /** The other source's value for the same field. Rendered greyed **beneath** the primary. */
    val alternateValue: Double,
    /** Which source disagreed. Shown so the reader can judge, not just that something did. */
    val alternateSource: Source,
    val deltaPercent: Double,
)

/**
 * The red flags plus the checks that could not run.
 *
 * The same shape as `TechnicalsRedFlags`, re-declared rather than shared because each screen's
 * interface owns the slice it renders: plan.md §12's "a rule that could not be evaluated is not a rule
 * that found nothing" has to be visible on **both** tabs, and one tab's copy must not be able to
 * grow a field the other does not have.
 */
data class FundamentalsRedFlags(
    /** Flags for this tab, in `RedFlagReport`'s stable severity → tab → id order. */
    val flags: List<RedFlag> = emptyList(),
    /** Rules that abstained, each with the reason. Rendered as "Not evaluated — …". */
    val skipped: List<RuleSkip> = emptyList(),
    /** Rules that threw, keyed by rule id. A crash must not read as "clean". */
    val failures: Map<String, String> = emptyMap(),
) {
    /** True when the banner must not imply a clean bill of health. */
    val coverageIncomplete: Boolean get() = skipped.isNotEmpty() || failures.isNotEmpty()

    companion object {
        /** Nothing to report, and nothing was skipped. */
        val EMPTY = FundamentalsRedFlags()
    }
}

/**
 * The exception every [FundamentalsDataSource] implementation throws on a failed read.
 *
 * @param reason the sentence shown to the user, verbatim. Never an exception class name.
 * @param isTransient whether a retry button is worth offering.
 * @param isNotPublished **not** an error at all: a fundamentals page that has never been fetched is
 *   the normal state of a symbol the reader has not opened yet, and the correct UI is an explained
 *   empty state rather than a red banner. plan.md §3.3's source has no "not yet" case the way a
 *   bhavcopy does, but a not-yet-cached page behaves the same way and gets the same treatment.
 */
class FundamentalsUnavailableException(
    val reason: String,
    val isTransient: Boolean = true,
    val isNotPublished: Boolean = false,
) : Exception(reason)

/**
 * The Fundamentals tab's entire view of the outside world (plan.md §7).
 *
 * ### Why this interface exists in `:app` and not in `:android-data`
 * plan.md §4.3 forbids `:app` from importing `:data`, and the storage layer's `FundamentalsView`
 * already exists as a projection — but it is not compiled on this machine, so referencing it here
 * would make this file uncompilable-and-unreviewable for a different reason than the technicals tab
 * already has. This interface keeps the tab reviewable on its own and gives the adapter a
 * two-line surface.
 *
 * ### Contract
 * * Every function is `suspend` and must already be off the main thread — the caller wraps the load
 *   in [FundamentalsBackgroundDispatcher].
 * * `null` / empty means **"there is nothing", not "we failed"**. A function that *fails* throws
 *   [FundamentalsUnavailableException].
 * * [fundamentals] must **not** invent a sector. The adapter passes
 *   [com.mia.app.domain.ratios.SectorResolver]'s own result through, unresolved included;
 *   substituting a guess is plan.md §7.1's explicit prohibition and it silently swaps a bank's
 *   leverage ratios for a software company's gross margin (§7.3).
 * * [divergences] must be **empty** when the sources agree. Returning every field with a zero delta
 *   would put a conflict chip on the whole screen.
 */
interface FundamentalsDataSource {

    /**
     * The fundamentals page for [symbol], from the cache when one exists.
     *
     * plan.md §10: **staleness is not an error**. An older, complete page is a better answer than a
     * freshly failed one, so a cached page is returned with its own `asOf` and the tab marks it stale
     * rather than replacing it with nothing.
     *
     * @throws FundamentalsUnavailableException when nothing is cached and the fetch failed.
     */
    suspend fun fundamentals(symbol: String): FundamentalsPage

    /**
     * Red flags for the Fundamentals tab, already ordered by `RedFlagReport`'s rule.
     *
     * Only flags whose `FlagTab` is `FUNDAMENTALS` or `BOTH` belong here.
     */
    suspend fun redFlags(symbol: String): FundamentalsRedFlags

    /**
     * The fields where two sources disagreed by more than plan.md §5.1's 5% tolerance.
     *
     * Each carries the **alternate** value and the delta the merge computed; the primary is in
     * `FundamentalsPage.snapshot.ratios`. The tab shows both, greys the alternate, and never
     * averages — [RatioDivergence] exists because plan.md §5.1 says "do NOT average, do NOT silently
     * pick one", and a type that carried only one number could not render both.
     */
    suspend fun divergences(symbol: String): List<RatioDivergence>

    /**
     * The newest `asOf` this device holds for [symbol], or `null`.
     *
     * Used for the section header's staleness line when the tab's own page is unavailable, so the
     * reader still learns *when* the last successful fetch happened rather than only that this one
     * failed.
     */
    suspend fun asOf(symbol: String): LocalDate?
}