package com.mia.app.data.android.db

import androidx.annotation.RestrictTo
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Explicit migrations for [MiaDatabase]. **No destructive fallback, by design.**
 *
 * ### Why this file exists at all
 * The two tables worth protecting are irreplaceable in practice:
 *
 * * `candles` — write-once, ~500 daily bars and ~261 weekly bars per symbol. Rebuilding a
 *   symbol's history costs a Yahoo handshake plus two chart requests (plan.md §3.2), which is
 *   exactly the traffic plan.md §13's CGNAT mitigation is designed to *avoid* asking for
 *   twice. plan.md §5.2 calls the table "write-once" for this reason.
 * * `outcomes` — the app's published accuracy record (plan.md §6.1, decision D6). Losing it on
 *   an app update would reset every hit-rate table to "not enough tracked cases yet", which is
 *   the one thing plan.md §6.1 says must not happen silently.
 * * `journal_entry` — what the reader wrote down about a stock before the outcome was known
 *   (gap E5). Nothing else in the schema can reconstruct it: bars can be refetched and outcomes
 *   re-derived, but a dated record of a belief exists only because it was written down.
 *
 * `fallbackToDestructiveMigration()` is therefore never called: a missing migration must be a
 * loud failure at development time, not a silent data loss in the field.
 *
 * ### The 5 → 6 migration
 * v6 adds `decision_counter` — the on-device tally of gate decisions (gap F3). Additive like every
 * version so far, and the cheapest one to lose: it holds no market data and nothing else in this
 * schema can reconstruct it, because it is a record of what *this install of the app* did, not of
 * anything about a stock. A reader who clears it has asked for that, and a reader who has not must
 * not lose it to an app update. See [MIGRATION_5_6].
 *
 * ### The 4 → 5 migration
 * v5 adds `journal_entry` — what the reader themselves wrote down about a stock before the outcome
 * was known (gap E5). Purely additive like every version so far: no existing table is touched, so
 * the upgrade is lossless and a downgrade is still impossible rather than merely inadvisable.
 *
 * ### The 3 → 4 migration
 * v4 adds `index_candles` — the benchmark index series. Purely additive: no existing table is
 * touched, so the upgrade is lossless and a downgrade is still impossible rather than merely
 * inadvisable. See [DOWNGRADE_NOT_SUPPORTED].
 *
 * ### The 1 → 2 migration
 * v2 is purely additive: `statement_rows` and `derived_metrics` (so a cached fundamentals page
 * can still produce red-flag evidence — plan.md §8.3) and `watchlist` (so decision OQ-4's
 * "watchlist nightly" has a bounded work list). Nothing in v1 is dropped or retyped, so a
 * downgrade is impossible but an upgrade is lossless.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
object DatabaseMigrations {

    /**
     * 1 → 2.
     *
     * The `IF NOT EXISTS` on every statement makes the migration idempotent, which matters
     * because Room runs a migration inside a transaction and a partially applied one from a
     * killed process would otherwise be unrecoverable without a wipe.
     */
    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // statement_rows: normalised annual statement rows (profitLoss / balanceSheet /
            // cashFlow / shareholding) so plan.md §8.3's evidence view works from cache.
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `statement_rows` (
                    `symbol` TEXT NOT NULL,
                    `statement` TEXT NOT NULL,
                    `fiscalYear` INTEGER NOT NULL,
                    `ordinal` INTEGER NOT NULL,
                    `label` TEXT NOT NULL,
                    `value` REAL,
                    PRIMARY KEY(`symbol`, `statement`, `fiscalYear`, `ordinal`)
                )
                """.trimIndent(),
            )

            // derived_metrics: normalised (metric, fiscalYear) pairs of CashFlowDerived.
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `derived_metrics` (
                    `symbol` TEXT NOT NULL,
                    `asOf` TEXT NOT NULL,
                    `metric` TEXT NOT NULL,
                    `fiscalYear` INTEGER NOT NULL,
                    `value` REAL NOT NULL,
                    PRIMARY KEY(`symbol`, `asOf`, `metric`, `fiscalYear`)
                )
                """.trimIndent(),
            )

            // watchlist: decision OQ-4's bounded nightly work list.
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `watchlist` (
                    `isin` TEXT NOT NULL,
                    `symbol` TEXT NOT NULL,
                    `addedOn` TEXT NOT NULL,
                    `alertsEnabled` INTEGER NOT NULL,
                    PRIMARY KEY(`isin`)
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_watchlist_symbol` ON `watchlist` (`symbol`)",
            )
        }
    }

    /**
     * 2 → 3.
     *
     * Adds `ratios.industry`, the screener.in classification `SectorResolver` needs as its
     * highest-precedence input.
     *
     * ### Why `ALTER TABLE` and not a rewrite
     * The column is nullable and the table holds one row per `(symbol, asOf)`. Adding it in place
     * preserves every cached ratio the device already has, which matters on a 2 GB phone where a
     * full refetch is the expensive path. `IF NOT EXISTS` is not valid on `ADD COLUMN` in every
     * SQLite build, so idempotency comes from Room's own migration bookkeeping instead — a
     * partially applied 2 → 3 cannot occur, because the statement is a single atomic `ALTER`.
     *
     * ### What the existing rows say
     * Rows written before v3 carry `industry = NULL`, which the resolver reads honestly: it falls
     * back to matching the company's name and says so. They are not silently wrong, and
     * `DefaultFundamentalsRepository` treats `sector = OTHER AND industry IS NULL` as worth a
     * refetch so a corrected classification reaches the device without waiting the 7-day window.
     */
    val MIGRATION_2_3: Migration = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `ratios` ADD COLUMN `industry` TEXT")
        }
    }

    /**
     * 3 → 4.
     *
     * Adds `index_candles(symbol, date, open, high, low, close, volume, source)` — the benchmark
     * that `PatternScanOptions.benchmarkCandles` reads.
     *
     * ### Why the table is created rather than seeded
     * A benchmark that ships pre-populated would be a series of *some other date range*, and the
     * relative-strength ratio would silently compare a stored stock close against a stale index
     * level. The table starts empty; `EodRefreshWorker` fills it after the first successful
     * fetch, and until then every scan reads the engine's documented "no benchmark — unscored"
     * state. An honest absence costs a display line; a fabricated one costs every Context score.
     *
     * `IF NOT EXISTS` for the same reason as 1 → 2: Room runs a migration in a transaction, and an
     * interrupted one must be re-runnable rather than unrecoverable without a wipe.
     */
    val MIGRATION_3_4: Migration = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `index_candles` (
                    `symbol` TEXT NOT NULL,
                    `date` TEXT NOT NULL,
                    `open` REAL NOT NULL,
                    `high` REAL NOT NULL,
                    `low` REAL NOT NULL,
                    `close` REAL NOT NULL,
                    `volume` INTEGER NOT NULL,
                    `source` TEXT NOT NULL,
                    PRIMARY KEY(`symbol`, `date`)
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_index_candles_symbol` " +
                    "ON `index_candles` (`symbol`)",
            )
        }
    }

    /**
     * 4 → 5.
     *
     * Adds `journal_entry` — the reader's own written record (gap E5).
     *
     * ### Why this table is the one that must never be dropped
     * `candles` is expensive to rebuild and `outcomes` is the app's published record, so both are
     * protected by refusing any destructive fallback. This table is protected for a stronger reason:
     * **nothing else in this schema can reconstruct it.** The bars can be refetched and the outcomes
     * re-derived from a scan, but what a person believed about a company on a given date exists only
     * because they typed it. A schema change that recreated this table would quietly convert the
     * reader's record into an empty one, which is the single most destructive thing this app could
     * do — and would do it while appearing to work.
     *
     * ### Why the columns are written out rather than derived
     * The SQL is hand-written and therefore reviewable against `JournalEntryEntity` line by line,
     * which is the only way a reviewer can confirm that the stored schema and the entity agree on
     * nullability — and nullability is load-bearing here: the three resolution columns are `NULL`
     * while an entry is open, and `resolvedAt`/`resolutionOutcome`/`resolutionChangePercent` are
     * only ever set together.
     *
     * `IF NOT EXISTS` for the same reason as 1 → 2 and 3 → 4: Room runs a migration inside a
     * transaction, and an interrupted one has to be re-runnable rather than unrecoverable.
     */
    val MIGRATION_4_5: Migration = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `journal_entry` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `symbol` TEXT NOT NULL,
                    `timeframe` TEXT NOT NULL,
                    `thesis` TEXT NOT NULL,
                    `expectation` TEXT NOT NULL,
                    `expectationDirection` TEXT NOT NULL,
                    `falsification` TEXT NOT NULL,
                    `reviewDate` TEXT NOT NULL,
                    `createdAt` TEXT NOT NULL,
                    `resolvedAt` TEXT,
                    `resolutionOutcome` TEXT,
                    `resolutionChangePercent` REAL
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_journal_entry_symbol` " +
                    "ON `journal_entry` (`symbol`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_journal_entry_reviewDate` " +
                    "ON `journal_entry` (`reviewDate`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_journal_entry_createdAt` " +
                    "ON `journal_entry` (`createdAt`)",
            )
        }
    }

    /**
     * 5 → 6.
     *
     * Adds `decision_counter(scope, bucket, count, firstRecordedOn, lastRecordedOn)` — the
     * projection gate's on-device tally (gap F3).
     *
     * ### Why this table is additive and, unlike most in this schema, disposable
     * `candles`, `outcomes` and `journal_entry` are all protected by refusing any destructive
     * fallback, because they cost a network round-trip, or a published record, or the reader's own
     * words to rebuild. This table costs **nothing** to rebuild — it starts empty and refills from
     * the next scan — so the only thing an update must not do is delete it *silently*, and an
     * explicit migration is what prevents that: without this step the app would refuse to open at
     * all, which is louder and better than opening with the counter quietly at zero.
     *
     * ### Why there is no index
     * The table is a fixed handful of rows — `DecisionScope.ALL.size × DecisionBucket.ALL.size`, at
     * most nine — and every read is `SELECT *` or `SELECT * WHERE scope = ?`. A full scan of nine
     * rows is not a performance problem, and an index on a nine-row table would be a
     * `CREATE INDEX` on every device's open for no measurable gain. Room will not require one here,
     * and if the table ever grows a partition key, the index belongs in the same migration as the
     * column that needs it.
     *
     * The column types match `DecisionCounterEntity` exactly, including `firstRecordedOn` and
     * `lastRecordedOn` being nullable: a row inserted by a build that predates the window columns
     * would otherwise fail to read, and a counter that cannot be read is the one failure this
     * whole feature exists to prevent.
     */
    val MIGRATION_5_6: Migration = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `decision_counter` (
                    `scope` TEXT NOT NULL,
                    `bucket` TEXT NOT NULL,
                    `count` INTEGER NOT NULL,
                    `firstRecordedOn` TEXT,
                    `lastRecordedOn` TEXT,
                    PRIMARY KEY(`scope`, `bucket`)
                )
                """.trimIndent(),
            )
        }
    }

    /**
     * Every migration, in order. `DatabaseModule` passes exactly this list to Room, which is
     * what makes "the migration exists" and "the migration is registered" the same fact.
     */
    val ALL: Array<Migration> = arrayOf(
        MIGRATION_1_2,
        MIGRATION_2_3,
        MIGRATION_3_4,
        MIGRATION_4_5,
        MIGRATION_5_6,
    )

    /**
     * A path from [MiaDatabase.DATABASE_VERSION] back down to [target] does not exist: v3 is a
     * superset of v2 and no column was dropped, so a downgrade is a reinstall, not a migration.
     * Named so a future developer adding a destructive step has to delete this and think again.
     */
    const val DOWNGRADE_NOT_SUPPORTED: String =
        "no downgrade path: every version so far is additive over the one before it (see " +
            "DatabaseMigrations)"
}