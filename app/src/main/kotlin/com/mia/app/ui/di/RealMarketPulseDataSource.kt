package com.mia.app.ui.di

import com.mia.app.data.android.repo.DefaultMarketBreadthRepository
import com.mia.app.domain.technical.Breadth
import com.mia.app.ui.stocklist.MarketPulseDataSource
import com.mia.app.ui.stocklist.MarketPulseUnavailableException
import com.mia.app.work.MiaBackgroundDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [MarketPulseDataSource] over the stored bars — no network, no new source, no rate limit.
 *
 * ### Why this cannot fail in the usual ways
 *
 * `DefaultCandleRepository.storeMarket` writes every bhavcopy row to `candles`, so breadth needs
 * nothing that this app does not already have on disk. That is the whole appeal: the index series
 * (W3's `^NSEI`) is a network read that may be rate-limited, stale or unavailable, whereas the
 * advance/decline line is computable the moment a session has been stored and stays correct
 * offline. Breadth is therefore the market-context feature that still works with the radio off.
 *
 * ### Nothing is computed here
 *
 * `MarketBreadth.compute` owns the arithmetic and `Breadth.summary()` owns its wording, both in
 * `:domain` and both tested there. This adapter reads and hands the result over. Recomputing
 * anything here would create a second implementation that can disagree with the one under test,
 * which is the shape of drift this codebase has been bitten by before.
 *
 * ### "Nothing stored" and "failed" stay distinct
 *
 * A fresh install has no session and returns `null`, which the card renders as *not measured yet*.
 * A genuine read failure throws [MarketPulseUnavailableException] and renders as an error with its
 * reason. Collapsing the two would mean a device that has never synced showing a market of zero
 * decliners — which is the single most misleading thing this card could display.
 */
@Singleton
class RealMarketPulseDataSource @Inject constructor(
    private val repository: DefaultMarketBreadthRepository,
    @MiaBackgroundDispatcher private val dispatcher: CoroutineDispatcher,
) : MarketPulseDataSource {

    override suspend fun breadth(): Breadth? = withContext(dispatcher) {
        try {
            repository.breadth()
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            throw MarketPulseUnavailableException(
                "The stored bars could not be read to compute market breadth: ${failure.message}",
                failure,
            )
        }
    }

    override suspend fun breadthAsOf(): LocalDate? = withContext(dispatcher) {
        try {
            repository.latestSession()
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            throw MarketPulseUnavailableException(
                "The newest stored session could not be read: ${failure.message}",
                failure,
            )
        }
    }
}