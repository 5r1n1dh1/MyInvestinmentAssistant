package com.mia.app.data.android.di

import com.mia.app.data.android.alert.AndroidBreakoutAlertDispatcher
import com.mia.app.data.android.alert.BreakoutAlertDispatcher
import com.mia.app.data.http.HttpTransport
import com.mia.app.data.price.LegacyDeliverySource
import com.mia.app.data.price.NseBhavcopySource
import com.mia.app.data.price.PriceSource
import com.mia.app.data.history.YahooCrumbHandshake
import com.mia.app.data.history.YahooHistorySource
import com.mia.app.data.fundamentals.ScreenerInSource
import com.mia.app.data.android.repo.AccuracyRepository
import com.mia.app.data.android.repo.CandleRepository
import com.mia.app.data.android.repo.DefaultAccuracyRepository
import com.mia.app.data.android.repo.DefaultCandleRepository
import com.mia.app.data.android.repo.DefaultFundamentalsRepository
import com.mia.app.data.android.repo.DefaultPatternRepository
import com.mia.app.data.android.repo.DefaultRedFlagRepository
import com.mia.app.data.android.repo.DefaultSymbolRepository
import com.mia.app.data.android.repo.FundamentalsRepository
import com.mia.app.data.android.repo.PatternRepository
import com.mia.app.data.android.repo.RedFlagRepository
import com.mia.app.data.android.repo.SymbolRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.delay
import javax.inject.Singleton

/**
 * Binds the concrete sources to their interfaces, and the repositories to theirs.
 *
 * ### Why `@Binds` for repositories and `@Provides` for sources
 * The repositories are `:app`'s entire surface, so the binding must be exact and total — a
 * `@Binds` in an abstract module makes "is every repository bound?" a compile-time question.
 * The `:data` sources are constructed with arguments, so they are provided; `PriceSource` and
 * `AssetReader` are additionally bound by type so a test can substitute them.
 *
 * ### The concrete source types are used, not the interfaces, by the repositories
 * [DefaultCandleRepository] asks for `NseBhavcopySource` rather than `PriceSource` because it
 * needs `fetchLatestOnOrBefore`, which is not on the interface. That is deliberate and it is the
 * documented blast-radius decision of plan.md §13: the interface is what `:data` publishes, and
 * the concrete class is what this module's one production path uses.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    // ---------------------------------------------------------------------- repositories

    @Binds
    @Singleton
    abstract fun bindCandleRepository(impl: DefaultCandleRepository): CandleRepository

    @Binds
    @Singleton
    abstract fun bindSymbolRepository(impl: DefaultSymbolRepository): SymbolRepository

    @Binds
    @Singleton
    abstract fun bindFundamentalsRepository(
        impl: DefaultFundamentalsRepository,
    ): FundamentalsRepository

    @Binds
    @Singleton
    abstract fun bindPatternRepository(impl: DefaultPatternRepository): PatternRepository

    @Binds
    @Singleton
    abstract fun bindAccuracyRepository(impl: DefaultAccuracyRepository): AccuracyRepository

    @Binds
    @Singleton
    abstract fun bindRedFlagRepository(impl: DefaultRedFlagRepository): RedFlagRepository

    // -------------------------------------------------------------------------- interfaces

    @Binds
    @Singleton
    abstract fun bindPriceSource(impl: NseBhavcopySource): PriceSource

    @Binds
    @Singleton
    abstract fun bindBreakoutAlertDispatcher(
        impl: AndroidBreakoutAlertDispatcher,
    ): BreakoutAlertDispatcher

    // ---------------------------------------------------------------------------- sources

    companion object {

        /**
         * plan.md §3.1: the official, whole-market, no-auth EOD file.
         *
         * The two-host mirror fallback lives inside `NseBhavcopySource`, so this is a single
         * binding and the fallback cannot be half-configured.
         */
        @Provides
        @Singleton
        fun provideNseBhavcopySource(transport: HttpTransport): NseBhavcopySource =
            NseBhavcopySource(transport)

        /**
         * plan.md §3.5: the optional legacy delivery file.
         *
         * Optional at the *source* level, not at the binding level: every failure mode here
         * degrades to an empty map plus a failed `ParserHealth`, so the price layer never
         * depends on it.
         */
        @Provides
        @Singleton
        fun provideLegacyDeliverySource(transport: HttpTransport): LegacyDeliverySource =
            LegacyDeliverySource(transport)

        /**
         * plan.md §3.2 / §3.3: screener.in, with the policy `:data` documents.
         *
         * The sleeper is injected as `delay` so a test could substitute it; the default keeps
         * the production path honest (it really does wait).
         */
        @Provides
        @Singleton
        fun provideScreenerInSource(transport: HttpTransport): ScreenerInSource =
            ScreenerInSource(transport)

        /**
         * plan.md §3.2: Yahoo history.
         *
         * The handshake is constructed explicitly rather than defaulted, so the one object that
         * owns the cookie + crumb session is visible in the graph instead of being created
         * inside a default argument.
         */
        @Provides
        @Singleton
        fun provideYahooHistorySource(transport: HttpTransport): YahooHistorySource =
            YahooHistorySource(
                transport = transport,
                handshake = YahooCrumbHandshake(transport),
                sleeper = { millis -> delay(millis) },
            )
    }
}