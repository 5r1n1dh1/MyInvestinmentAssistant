package com.mia.app.data.android.di

import android.content.Context
import androidx.room.Room
import androidx.hilt.work.HiltWorkerFactory
import com.mia.app.data.android.db.CandleDao
import com.mia.app.data.android.db.DatabaseMigrations
import com.mia.app.data.android.db.FundamentalsDao
import com.mia.app.data.android.db.IndexDao
import com.mia.app.data.android.db.MarketDao
import com.mia.app.data.android.db.MiaDatabase
import com.mia.app.data.android.db.PatternDao
import com.mia.app.data.android.db.SymbolDao
import com.mia.app.data.android.db.WatchlistDao
import com.mia.app.data.android.db.WorkStateDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Database and worker-factory bindings.
 *
 * ### `fallbackToDestructiveMigration()` is deliberately absent
 * `candles` is write-once history that costs a Yahoo round-trip per symbol to rebuild, and
 * `outcomes` is the app's published accuracy record (plan.md §6.1). Losing either to a missing
 * migration would be worse than failing to open the database, so `DatabaseMigrations.ALL` is
 * passed explicitly and nothing falls back.
 *
 * ### On `:app`
 * `:app` must
 * 1. annotate its `Application` with `@HiltAndroidApp`,
 * 2. implement `Configuration.Provider` returning a `Configuration` built from the
 *    [HiltWorkerFactory] provided here, and
 * 3. remove WorkManager's default initialiser from its own manifest
 *    (`androidx.startup.InitializationProvider` / the `workManagerInitializer` provider).
 *
 * Those three steps are `:app`'s to make because only `:app` has an `Application`. This module
 * supplies everything else.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): MiaDatabase =
        Room.databaseBuilder(context, MiaDatabase::class.java, MiaDatabase.DATABASE_NAME)
            .addMigrations(*DatabaseMigrations.ALL)
            .addCallback(MiaDatabase.CALLBACK)
            // NOTE: no `.fallbackToDestructiveMigration()` and no `.fallbackToDestructiveMigrationOnDowngrade()`.
            .build()

    @Provides
    fun provideCandleDao(database: MiaDatabase): CandleDao = database.candleDao()

    @Provides
    fun provideSymbolDao(database: MiaDatabase): SymbolDao = database.symbolDao()

    @Provides
    fun provideWatchlistDao(database: MiaDatabase): WatchlistDao = database.watchlistDao()

    @Provides
    fun provideFundamentalsDao(database: MiaDatabase): FundamentalsDao = database.fundamentalsDao()

    @Provides
    fun providePatternDao(database: MiaDatabase): PatternDao = database.patternDao()

    @Provides
    fun provideMarketDao(database: MiaDatabase): MarketDao = database.marketDao()

    @Provides
    fun provideWorkStateDao(database: MiaDatabase): WorkStateDao = database.workStateDao()

    /** The benchmark index cache. Its own DAO, separate from `candles` — see `IndexCandleEntity`. */
    @Provides
    fun provideIndexDao(database: MiaDatabase): IndexDao = database.indexDao()


}