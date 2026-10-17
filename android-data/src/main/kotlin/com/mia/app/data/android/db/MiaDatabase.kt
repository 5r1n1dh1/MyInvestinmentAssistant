package com.mia.app.data.android.db

import androidx.annotation.RestrictTo
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import java.time.LocalDate

/**
 * Date conversion for the ISO-8601 `TEXT` columns.
 *
 * **Why a converter rather than a built-in `java.time` type.** Room has grown first-class
 * `java.time` support across recent 2.x releases, but which exact types are covered has moved
 * between versions, and an explicit converter is (a) pinned to behaviour this module can state,
 * (b) round-trippable in a plain JVM unit test with no Android runtime, and (c) sortable —
 * ISO-8601 text sorts lexicographically in the same order as chronologically, which is what
 * makes `ORDER BY date` a correct date sort.
 *
 * Every date column in this schema is NOT NULL, so both converters are non-null; a nullable
 * date is modelled as an absent value in a nullable column rather than as a special string.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
class MiaConverters {

    @TypeConverter
    fun localDateToIso(value: LocalDate): String = value.toString()

    @TypeConverter
    fun isoToLocalDate(value: String): LocalDate = LocalDate.parse(value)
}

/**
 * The one database — plan.md §5.2.
 *
 * ### Version and migrations
 * * [DATABASE_VERSION] is `5`. v1 was the plan.md §5.2 table set; v2 adds
 *   `statement_rows`, `derived_metrics` and `watchlist`; v3 adds `ratios.industry`, the
 *   screener.in classification that sector resolution needs; v4 adds `index_candles`, the
 *   benchmark series that finally gives `PatternScanOptions.benchmarkCandles` something to be
 *   (see `DatabaseMigrations`); v5 adds `journal_entry`, the reader's own written record (gap E5).
 * * **`fallbackToDestructiveMigration()` is never called, anywhere.** The `candles` table is
 *   write-once: its 500 daily bars per symbol cost a Yahoo round-trip per symbol to rebuild
 *   (plan.md §3.2, §13), `outcomes` is the app's published track record (plan.md §6.1) which must
 *   not evaporate on an app update, and `journal_entry` holds the one thing in this schema that
 *   nothing can reconstruct — what a reader believed before they knew the answer. A missing
 *   migration must fail loudly in development rather than quietly delete a user's data.
 *
 * ### Foreign keys
 * The [CALLBACK] turns `PRAGMA foreign_keys = ON` explicitly. Room enables foreign-key
 * enforcement itself for the connections it manages, so this is belt and braces — but the
 * `patterns → symbols` and `pattern_pivots → patterns` cascades are load-bearing (they are what
 * keeps a deleted symbol from leaving orphan geometry behind), and stating them in the schema is
 * cheaper than discovering they were off.
 */
@Database(
    entities = [
        CandleEntity::class,
        SymbolEntity::class,
        RatiosEntity::class,
        QuarterEntity::class,
        StatementRowEntity::class,
        DerivedMetricEntity::class,
        PatternEntity::class,
        PatternPivotEntity::class,
        OutcomeEntity::class,
        BhavcopyCacheEntity::class,
        WatchlistEntity::class,
        WorkStateEntity::class,
        IndexCandleEntity::class,
        JournalEntryEntity::class,
    ],
    version = MiaDatabase.DATABASE_VERSION,
    exportSchema = true,
)
@TypeConverters(MiaConverters::class)
abstract class MiaDatabase : RoomDatabase() {

    abstract fun candleDao(): CandleDao

    abstract fun symbolDao(): SymbolDao

    abstract fun watchlistDao(): WatchlistDao

    abstract fun fundamentalsDao(): FundamentalsDao

    abstract fun patternDao(): PatternDao

    abstract fun marketDao(): MarketDao

    abstract fun workStateDao(): WorkStateDao

    abstract fun indexDao(): IndexDao

    /**
     * The reader's own journal — gap E5.
     *
     * Its own DAO rather than a method on `PatternDao`, because the two tables share no foreign key
     * and no read that spans both exists: an entry is read whole, and `PatternDao`'s reads are all
     * "one detection with its pivots". Keeping them apart also keeps the journal's monotone
     * resolution query away from the track record's writes, so neither can block the other.
     */
    abstract fun journalDao(): JournalDao

    companion object {
        /** File name of the on-device database. */
        const val DATABASE_NAME: String = "mia.db"

        /** plan.md §5.2 + the five extension tables documented on the entities. */
        const val DATABASE_VERSION: Int = 5

        /** Applied at open time; see the class KDoc. */
        val CALLBACK: Callback = object : Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                db.execSQL("PRAGMA foreign_keys = ON")
            }
        }
    }
}