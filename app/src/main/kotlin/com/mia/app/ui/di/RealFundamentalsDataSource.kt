package com.mia.app.ui.di

import com.mia.app.data.android.repo.DataFailure
import com.mia.app.data.android.repo.FundamentalsRepository
import com.mia.app.data.android.repo.FundamentalsView
import com.mia.app.data.android.repo.RedFlagRepository
import com.mia.app.domain.model.FlagTab
import com.mia.app.domain.ratios.RatioShape
import com.mia.app.domain.ratios.SectorResolution
import com.mia.app.domain.ratios.SectorResolver
import com.mia.app.domain.redflag.RuleSkip
import com.mia.app.ui.fundamentals.FundamentalsBackgroundDispatcher
import com.mia.app.ui.fundamentals.FundamentalsDataSource
import com.mia.app.ui.fundamentals.FundamentalsPage
import com.mia.app.ui.fundamentals.FundamentalsRedFlags
import com.mia.app.ui.fundamentals.FundamentalsUnavailableException
import com.mia.app.ui.fundamentals.RatioDivergence
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [FundamentalsDataSource] over `:android-data`.
 *
 * ### The one rule this adapter must not break: never invent a sector
 * plan.md §7.1 is explicit — *"Default to `OTHER` and show a generic list if unresolved — never
 * guess"* — and §7.3 makes the sector decide which ratios are **displayed at all**, so a wrong
 * sector silently swaps a bank's debt/equity for a software company's gross margin.
 *
 * [SectorResolver.resolve] is therefore passed through **unresolved included**. The only thing added
 * is [RatioShape], which the resolver documents as *corroboration only*: it can raise a single
 * keyword match from `SINGLE` to `AGREED`, and it can never resolve a name no keyword matched.
 *
 * ### A page that was never fetched is a note, not a banner
 * [FundamentalsRepository.snapshot] returns `NO_DATA_CACHED` for a symbol the reader has just opened
 * for the first time. That is the normal state, not a failed read, so it becomes
 * `isNotPublished = true` — which the ViewModel renders as `notFetchedNote` above the content
 * rather than as `ErrorState`. The distinction the contract asks for: a genuinely unreachable source
 * (`SOURCE_UNAVAILABLE`, `UNPARSEABLE`) *does* throw, and the tab offers a retry.
 *
 * ### Staleness is a label, not a deletion
 * `snapshot()` falls back to the cached page when a refresh fails, and `Ratios.withFreshness()` has
 * already been applied by the repository. So an older, complete page is returned with its own as-of
 * date and the tab marks it stale — which is what plan.md §10 asks for — rather than being replaced
 * by nothing.
 *
 * ### Two gaps, reported rather than papered over
 * * **`profitLossPeriods` / `shareholdingPeriods` are empty.** The column headers of the annual
 *   tables are not exposed anywhere on the storage-layer projection (`AnnualRow` carries only
 *   `label` + `values`), so they cannot be plumbed through this seam today. The interface documents
 *   the consequence: `RatioComputation.operatingMarginTrend` and `.dividendPayout` degrade to a
 *   *reasoned absence*, which the tab states, rather than to a fabricated point.
 * * **[divergences] is always empty.** Only one fundamentals source is wired (`ScreenerInSource`, and
 *   `RatioMapper` writes `Confidence.SINGLE` on every field), so there is no second source that could
 *   disagree by more than plan.md §5.1's 5 %. Returning an empty list is the honest answer — and the
 *   interface requires it: "must be empty when the sources agree".
 */
@Singleton
class RealFundamentalsDataSource @Inject constructor(
    private val fundamentalsRepository: FundamentalsRepository,
    private val redFlagRepository: RedFlagRepository,
    @FundamentalsBackgroundDispatcher private val backgroundDispatcher: CoroutineDispatcher,
) : FundamentalsDataSource {

    override suspend fun fundamentals(symbol: String): FundamentalsPage = onBackground {
        val ticker = symbol.trim().uppercase()
        try {
            // `allowFetch = true` is the documented screen path: the repository reads cache first and
            // only refetches once `FreshnessKind.FUNDAMENTALS_SNAPSHOT`'s 7-day window has passed, so
            // a screen entry does not spend a screener.in request.
            val view = fundamentalsRepository.snapshot(ticker).orDataFailure()
            FundamentalsPage(
                snapshot = view.snapshot,
                sectorResolution = resolveSector(view),
                // See the class KDoc: the column headers are not exposed by the storage projection.
                profitLossPeriods = emptyList(),
                shareholdingPeriods = emptyList(),
            )
        } catch (absent: DataFailureException) {
            throw unavailable(absent.failure, notFetchedIsNote = absent.failure.isNothingStored())
        }
    }

    override suspend fun redFlags(symbol: String): FundamentalsRedFlags = onBackground {
        val ticker = symbol.trim().uppercase()
        val result = redFlagRepository.fundamentalsFlags(ticker)
        val failure = result.failureOrNull()
        if (failure != null) {
            if (failure.isNothingStored()) {
                // Never an empty flag list: that renders as "no red flags", which is plan.md §12's
                // silent-empty. A synthetic `RuleSkip` puts the reason on screen as "Not evaluated".
                return@onBackground FundamentalsRedFlags(
                    skipped = listOf(
                        RuleSkip(
                            ruleId = FUNDAMENTALS_RULE_GROUP,
                            reason = "No fundamentals page is cached for $ticker, so none of the " +
                                "fundamentals checks could be evaluated. " +
                                failure.userFacingReason(),
                        ),
                    ),
                )
            }
            throw unavailable(failure)
        }
        val view = result.valueOrNull() ?: return@onBackground FundamentalsRedFlags(
            skipped = listOf(
                RuleSkip(
                    ruleId = FUNDAMENTALS_RULE_GROUP,
                    reason = "The red-flag checks for $ticker produced no readable result and no " +
                        "reason. Treat the fundamentals checks as not evaluated.",
                ),
            ),
        )
        FundamentalsRedFlags(
            flags = view.forTab(FlagTab.FUNDAMENTALS),
            skipped = view.skipped,
            failures = view.failureReasons,
        )
    }

    /**
     * plan.md §5.1's conflict list. **Always empty**, and that is a fact about this build rather than
     * a stub: `FundamentalsRepository.ratios` returns the merged `Ratios` with a single
     * `Confidence.SINGLE` per field and no `alternate`, so the layer that *decided* the two sources
     * disagree is not reachable from this seam. Inventing a divergence here would put a conflict chip
     * on the whole screen with no disagreement behind it.
     */
    override suspend fun divergences(symbol: String): List<RatioDivergence> = emptyList()

    override suspend fun asOf(symbol: String): LocalDate? = onBackground {
        // `FundamentalsRepository.asOf` reads the newest stored ratios row directly and never
        // throws: it is the "when did anything last succeed for this symbol" question, and the tab
        // uses it precisely when its own page could not be built.
        fundamentalsRepository.asOf(symbol.trim().uppercase())
    }

    // --------------------------------------------------------------------------- internals

    /**
     * The sector, and why — passed through unresolved included.
     *
     * `about` is `null` because screener.in's industry sentence is not carried on
     * `FundamentalsSnapshot`; only the company's display name is available to match on. A keyword
     * match on the name is a *positive* match and the resolver documents that it may resolve on its
     * own, so this loses precision rather than correctness — and an unresolved answer is always
     * available.
     *
     * [RatioShape.hasDepositsRow] is read structurally: a `Deposits` line in the balance sheet is
     * the one row only a bank publishes (see `RatioShape`'s KDoc), and it corroborates a
     * BANKING keyword match. It is derived from the stored row labels, never asserted.
     */
    private fun resolveSector(
        view: FundamentalsView,
    ): SectorResolution = SectorResolver.resolve(
        name = view.snapshot.name,
        about = null,
        shape = RatioShape(hasDepositsRow = view.snapshot.balanceSheet.any { row ->
            row.label.trim().equals(DEPOSITS_ROW_LABEL, ignoreCase = true)
        }),
    )

    private suspend fun <T> onBackground(block: suspend () -> T): T =
        withContext(backgroundDispatcher) { block() }

    private fun unavailable(
        failure: DataFailure,
        notFetchedIsNote: Boolean,
    ) = FundamentalsUnavailableException(
        reason = failure.userFacingReason(),
        isTransient = failure.isWorthRetrying(),
        // A page that has never been fetched and a page whose source is unreachable both leave the
        // tab with nothing to render; only the first is a *normal* state, so it is the one that
        // becomes a note rather than a banner with a retry.
        isNotPublished = notFetchedIsNote || failure.isNotPublishedYet(),
    )

    private companion object {
        /**
         * The balance-sheet row label only a bank publishes.
         *
         * Matched exactly rather than by substring so a row called "Deposits from related parties"
         * or "Total deposits" cannot corroborate a sector it does not prove.
         */
        const val DEPOSITS_ROW_LABEL: String = "Deposits"

        /**
         * The id used for the synthetic skip when no fundamentals page exists.
         *
         * A group id rather than a rule id, because *ten* rules could not be evaluated and inventing
         * ten ids would imply ten individual rule outcomes that were never computed.
         */
        const val FUNDAMENTALS_RULE_GROUP: String = "fundamentals-checks"
    }
}