package com.mia.app.data.android.di

import android.content.Context
import androidx.room.Room
import androidx.hilt.work.HiltWorkerFactory
import com.mia.app.data.android.db.CandleDao
import com.mia.app.data.android.db.DatabaseMigrations
import com.mia.app.data.android.db.DecisionCounterDao
import com.mia.app.data.android.db.FundamentalsDao
import com.mia.app.data.android.db.IndexDao
import com.mia.app.data.android.db.JournalDao
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
 * `candles` is write-once history that costs a Yahoo round-trip per symbol to rebuild,
 * `outcomes` is the app's published accuracy record (plan.md §6.1), and `journal_entry` is what
 * the reader believed before they knew the answer (gap E5) — the one table in this schema that
 * nothing else can reconstruct. Losing any of them to a missing migration would be worse than
 * failing to open the database, so `DatabaseMigrations.ALL` is passed explicitly and nothing
 * falls back.
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

    /**
     * The reader's own journal — gap E5.
     *
     * Its own provider because it is its own DAO, on the same footing as every other table here: the
     * binding is one line and "is every DAO provided?" stays a question this file answers by
     * reading it.
     */
    @Provides
    fun provideJournalDao(database: MiaDatabase): JournalDao = database.journalDao()

    /**
     * The on-device decision counter — gap F3.
     *
     * One more line in the same list for the same reason as every line above: "is every DAO
     * provided?" is a question this file answers by being read end to end, and a DAO bound in a
     * feature-local module would opt out of it.
     */
    @Provides
    fun provideDecisionCounterDao(database: MiaDatabase): DecisionCounterDao = database.decisionCounterDao()
}