package com.mia.app.data.android.di

import com.mia.app.data.android.alert.AndroidBreakoutAlertDispatcher
import com.mia.app.data.android.alert.BreakoutAlertDispatcher
import com.mia.app.data.http.HttpTransport
import com.mia.app.data.price.IndexSource
import com.mia.app.data.price.LegacyDeliverySource
import com.mia.app.data.price.NseBhavcopySource
import com.mia.app.data.price.PriceSource
import com.mia.app.data.price.YahooIndexSource
import com.mia.app.data.history.YahooCrumbHandshake
import com.mia.app.data.history.YahooHistorySource
import com.mia.app.data.fundamentals.ScreenerInSource
import com.mia.app.data.android.repo.AccuracyRepository
import com.mia.app.data.android.repo.CandleRepository
import com.mia.app.data.android.repo.DefaultAccuracyRepository
import com.mia.app.data.android.repo.JournalRepository
import com.mia.app.data.android.repo.DefaultCandleRepository
import com.mia.app.data.android.repo.DefaultFundamentalsRepository
import com.mia.app.data.android.repo.DefaultJournalRepository
import com.mia.app.data.android.repo.DefaultIndexRepository
import com.mia.app.data.android.repo.DefaultPatternRepository
import com.mia.app.data.android.repo.DefaultRedFlagRepository
import com.mia.app.data.android.repo.DefaultSectorRepository
import com.mia.app.data.android.repo.DefaultSymbolRepository
import com.mia.app.data.android.repo.FundamentalsRepository
import com.mia.app.data.android.repo.IndexRepository
import com.mia.app.data.android.repo.PatternRepository
import com.mia.app.data.android.repo.RedFlagRepository
import com.mia.app.data.android.repo.SectorRepository
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

    @Binds
    @Singleton
    abstract fun bindIndexRepository(impl: DefaultIndexRepository): IndexRepository

    /**
     * planv2 W3: the peer group the sector median is computed over.
     *
     * Reads the `ratios` and `symbols` tables and nothing else — no fetch path, no schema change.
     * The peer group is what this device has already stored, and that constraint is the feature's
     * central honesty property rather than a limitation to route around later.
     */
    @Binds
    @Singleton
    abstract fun bindSectorRepository(impl: DefaultSectorRepository): SectorRepository

    // -------------------------------------------------------------------------- interfaces

    @Binds
    @Singleton
    abstract fun bindPriceSource(impl: NseBhavcopySource): PriceSource

    /**
     * The benchmark index source, bound by type so a test can substitute one.
     *
     * `YahooIndexSource` rather than an NSE CSV source, because docs/LIVE-PROBE.md measured all
     * three NSE index-CSV shapes answering `404` while Yahoo's chart endpoint answered `200` for
     * the same cookie+crumb handshake the app already performs. The rejected shapes stay in
     * `IndexEndpoints.NSE_CSV_CANDIDATES` so a future one that answers is a one-line change.
     */
    @Binds
    @Singleton
    abstract fun bindIndexSource(impl: YahooIndexSource): IndexSource

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

        /**
         * planv2 W3: the benchmark index series.
         *
         * Given its **own** handshake rather than the history source's. That is a considered
         * choice and not an oversight: the two are cached separately so a `429` on a symbol's
         * per-symbol history cannot invalidate the benchmark session, and so the benchmark's
         * daily fetch cannot spend the crumb a backfill is mid-way through using. Both handshakes
         * cost one cookie request per process, and plan.md §13's budget covers two.
         */
        @Provides
        @Singleton
        fun provideYahooIndexSource(transport: HttpTransport): YahooIndexSource =
            YahooIndexSource(
                transport = transport,
                handshake = YahooCrumbHandshake(transport, backoffMillis = INDEX_HANDSHAKE_BACKOFF_MILLIS),
                sleeper = { millis -> delay(millis) },
            )

        /**
         * The benchmark fetch runs at 19:00 IST alongside the bhavcopy, so it gets a longer
         * crumb backoff than the interactive path: a retry after 1.5 s on a congested evening
         * edge is a request that is very likely to be refused again.
         */
        const val INDEX_HANDSHAKE_BACKOFF_MILLIS: Long = 3_000L
    }

    // Folded in from a feature-local module that existed only while this file's owner was a
    // parallel workstream. It is here because this file states the invariant that makes it worth
    // existing: the repositories are :app's entire surface, and a @Binds in an abstract module
    // makes "is every repository bound?" a compile-time question. A JournalRepository bound
    // elsewhere would silently opt out of that question.
    @Binds
    @Singleton
    abstract fun bindJournalRepository(impl: DefaultJournalRepository): JournalRepository
}
