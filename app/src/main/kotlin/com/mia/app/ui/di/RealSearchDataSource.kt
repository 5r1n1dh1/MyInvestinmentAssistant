package com.mia.app.ui.di

import com.mia.app.data.android.repo.DataFailure
import com.mia.app.data.android.repo.DataFailureReason
import com.mia.app.data.android.repo.DataResult
import com.mia.app.data.android.repo.SymbolRepository
import com.mia.app.ui.search.SearchBackgroundDispatcher
import com.mia.app.ui.search.SearchDataSource
import com.mia.app.ui.search.SearchUnavailableException
import com.mia.app.ui.search.SymbolMatch
import com.mia.app.ui.search.SymbolMatchKind
import com.mia.app.ui.search.SymbolResolution
import com.mia.app.ui.search.SymbolSummary as AppSymbolSummary
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import com.mia.app.data.android.repo.SymbolSummary as RepoSymbolSummary

/**
 * [SearchDataSource] over `:android-data`'s [SymbolRepository].
 *
 * ### Nothing here touches the network, and that is enforced rather than promised
 * plan.md §3.6 forbids screener.in's `?q=` endpoint and §9 requirement 5 requires local search over
 * the bundled `EQUITY_L.csv`. `SymbolRepository`'s whole read path is `SymbolBookProvider`, which
 * reads a bundled asset and nothing else, so no request can be issued from this class — there is
 * no source to issue one with.
 *
 * ### Threading
 * The CSV parse and the linear scan are CPU work with no IO (2,593 rows), so every call is wrapped
 * in [SearchBackgroundDispatcher]. The repository *also* switches internally onto `:android-data`'s
 * IO dispatcher; that is redundant, not wrong, and it means the adapter is honest about its own
 * threading whether or not the repository is.
 *
 * ### The two identities, and why the alias
 * `:android-data` projects the master onto its own `SymbolSummary`, and `ui/search` declares the
 * app-level projection with the same name (its KDoc says the duplication is deliberate — `:domain`
 * has no symbol type). They are mapped field for field here, with the storage-layer type aliased,
 * so there is exactly one place where the two shapes are reconciled.
 *
 * ### Failure policy
 * A missing bundled asset is a **failure**, never an empty book. An empty search box that finds
 * nothing for every query is indistinguishable from "this app has no symbol list", which is
 * plan.md §9's forbidden blank screen — so every read throws [SearchUnavailableException] with
 * the repository's own sentence.
 */
@Singleton
class RealSearchDataSource @Inject constructor(
    private val symbolRepository: SymbolRepository,
    @SearchBackgroundDispatcher private val backgroundDispatcher: CoroutineDispatcher,
) : SearchDataSource {

    override suspend fun symbolBook(): List<AppSymbolSummary> = onBackground {
        symbolRepository.symbolBook().orThrowTyped().map { it.toApp() }
    }

    override suspend fun search(query: String, limit: Int): List<SymbolMatch> = onBackground {
        symbolRepository.search(query, limit).orThrowTyped().map { hit ->
            SymbolMatch(
                summary = hit.summary.toApp(),
                // The two enums carry the same five members in the same order, and `SymbolMatchKind.rank`
                // is the app-side ordering contract the ViewModel re-asserts. Matched by name rather
                // than by ordinal so a new member on either side cannot silently shift the meaning.
                kind = SymbolMatchKind.entries.firstOrNull { it.name == hit.kind.name }
                    ?: SymbolMatchKind.NAME_SUBSTRING,
                position = hit.position,
            )
        }
    }

    override suspend fun resolve(query: String): SymbolResolution = onBackground {
        val outcome = symbolRepository.resolve(query).orThrowTyped()
        SymbolResolution(
            // `SymbolResolution.query` is documented as "exactly what the caller typed — the UI
            // echoes this, never the normalised form", and the caller is this adapter, so the
            // argument is passed through verbatim. `ResolutionOutcome.query` is the resolver's own
            // *normalised* text and is deliberately not used here.
            query = query,
            summary = outcome.summary?.toApp(),
            // The app-side `SymbolResolution.wasAdjusted` is a *computed* property
            // (`summary.symbol != query.trim().uppercase()`), so the storage layer's own `adjusted`
            // flag has nowhere to go. Nothing is lost: the resolver's `explanation` is the sentence
            // the screen renders verbatim, and it states the adjustment in words.
            explanation = outcome.explanation,
            alternatives = outcome.alternatives.map { it.toApp() },
            corporateActionDetail = outcome.corporateActionDetail,
        )
    }

    override suspend fun addToWatchlist(symbol: String): AppSymbolSummary? = onBackground {
        val result = symbolRepository.addToWatchlist(listOf(symbol))
        val failure = result.failureOrNull()
        if (failure != null) {
            // `NOTHING_TO_DO` is the documented "not in the bundled master" answer, and the interface
            // says `null` — "not tracked" is a real answer, not an error. Every other reason *is* an
            // error: an unreachable store or a failed write must not read as "that ticker is not
            // listed", which is a statement about the market rather than about this device.
            if (failure.reason == DataFailureReason.NOTHING_TO_DO) return@onBackground null
            throw unavailable(failure)
        }
        result.valueOrNull()
            ?.firstOrNull { entry -> entry.summary.symbol.equals(symbol.trim(), ignoreCase = true) }
            ?.summary
            ?.toApp()
            ?: // The write reported success but the symbol is not in the resulting list. That is not
            // "not listed" — it means the store did not keep what it was asked to keep, which is a
            // failure and must not be reported as a successful add of a different instrument.
            throw unavailable(
                DataFailure(
                    reason = DataFailureReason.UNEXPECTED,
                    message = "$symbol was not added to your tracked symbols even though the store " +
                        "reported success. Open the app again and retry.",
                    detail = "symbol=$symbol",
                ),
            )
    }

    // --------------------------------------------------------------------------- internals

    /**
     * The identity projection, field for field.
     *
     * `series` and `isin` are carried verbatim because [AppSymbolSummary.isin] is the **stable
     * identity key** (plan.md §9: a rename changes the ticker, never the ISIN) and `isPriceable`
     * decides whether the row can be opened at all. `listingDate` stays `null` when the master
     * published none; `AppSymbolSummary.listingLabel()` renders that as "unknown" rather than a date.
     */
    private fun RepoSymbolSummary.toApp(): AppSymbolSummary = AppSymbolSummary(
        symbol = symbol,
        name = name,
        series = series,
        isin = isin,
        listingDate = listingDate,
    )

    private suspend fun <T> onBackground(block: suspend () -> T): T =
        withContext(backgroundDispatcher) { block() }

    private fun <T : Any> DataResult<T>.orThrowTyped(): T =
        valueOrNull() ?: throw unavailable(failureOrNull()!!)

    private fun unavailable(failure: DataFailure) = SearchUnavailableException(
        reason = failure.userFacingReason(),
        // A missing bundled asset will not fix itself on a retry; an unreachable store or a changed
        // source page might.
        isTransient = failure.isWorthRetrying(),
    )
}