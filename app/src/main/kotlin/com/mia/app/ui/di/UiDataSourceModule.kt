package com.mia.app.ui.di

import com.mia.app.ui.accuracy.AccuracyDataSource
import com.mia.app.ui.detail.StockDetailDataSource
import com.mia.app.ui.fundamentals.FundamentalsDataSource
import com.mia.app.ui.patterns.PatternsDataSource
import com.mia.app.ui.search.SearchDataSource
import com.mia.app.ui.settings.SettingsDataSource
import com.mia.app.ui.stocklist.StockListDataSource
import com.mia.app.ui.technicals.TechnicalsDataSource
import com.mia.app.work.RefreshStatusSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The one place every screen's data-source seam is satisfied.
 *
 * ### Why `@Binds` and not `@Provides` with a factory call
 * Each adapter is `@Singleton @Inject constructor(...)`, so `@Binds` states the binding in one line
 * and the *constructor* states the dependencies. With `@Provides` returning `RealTechnicalsDataSource(...)`
 * the dependency list would live in this file instead of on the class, and a reviewer asking "what does
 * this adapter read?" would have to look in the wrong place.
 *
 * ### Every placeholder binding is gone
 * Each of the nine `…Module.kt` placeholder files documented a three-step recipe — write the adapter,
 * `@Inject` it, **delete the file** — and warned that leaving the placeholder in place is a duplicate
 * binding, "which is deliberate: an ambiguous graph should fail loudly at build time". That is the
 * reason this file exists as one `@Binds` block rather than nine: a type must be bound exactly once,
 * and having all nine bindings legible side by side is how a reviewer confirms it. The nine
 * placeholder files are deleted; the four dispatcher bindings they also carried are preserved in
 * [UiDispatchersModule], because losing a binding would break every screen that injects it.
 *
 * `TechnicalsBackgroundDispatcher` and `MiaBackgroundDispatcher` are untouched in their own files
 * (`TechnicalsDispatchers.kt`, `MiaDispatchers.kt`) — they were never placeholders.
 *
 * ### Scope
 * `@Singleton` on both the implementation and the binding, so one adapter per process. That matters
 * only for the adapters that hold no mutable state — they all resolve to the same `@Singleton`
 * repositories — so the scope is free and is kept for consistency with `:android-data`'s own
 * `RepositoryModule`, which scopes every repository the same way.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class UiDataSourceModule {

    @Binds
    @Singleton
    abstract fun bindTechnicalsDataSource(impl: RealTechnicalsDataSource): TechnicalsDataSource

    @Binds
    @Singleton
    abstract fun bindSearchDataSource(impl: RealSearchDataSource): SearchDataSource

    @Binds
    @Singleton
    abstract fun bindStockListDataSource(impl: RealStockListDataSource): StockListDataSource

    @Binds
    @Singleton
    abstract fun bindStockDetailDataSource(impl: RealStockDetailDataSource): StockDetailDataSource

    @Binds
    @Singleton
    abstract fun bindFundamentalsDataSource(impl: RealFundamentalsDataSource): FundamentalsDataSource

    @Binds
    @Singleton
    abstract fun bindPatternsDataSource(impl: RealPatternsDataSource): PatternsDataSource

    @Binds
    @Singleton
    abstract fun bindAccuracyDataSource(impl: RealAccuracyDataSource): AccuracyDataSource

    @Binds
    @Singleton
    abstract fun bindSettingsDataSource(impl: RealSettingsDataSource): SettingsDataSource

    @Binds
    @Singleton
    abstract fun bindRefreshStatusSource(impl: RealRefreshStatusSource): RefreshStatusSource
}