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
 *
 * `fallbackToDestructiveMigration()` is therefore never called: a missing migration must be a
 * loud failure at development time, not a silent data loss in the field.
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
     * Every migration, in order. `DatabaseModule` passes exactly this list to Room, which is
     * what makes "the migration exists" and "the migration is registered" the same fact.
     */
    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2)

    /**
     * A path from [MiaDatabase.DATABASE_VERSION] back down to [target] does not exist: v2 is a
     * superset of v1 and no column was dropped, so a downgrade is a reinstall, not a migration.
     * Named so a future developer adding a destructive step has to delete this and think again.
     */
    const val DOWNGRADE_NOT_SUPPORTED: String =
        "no downgrade path: v2 is additive over v1 (see DatabaseMigrations)"
}