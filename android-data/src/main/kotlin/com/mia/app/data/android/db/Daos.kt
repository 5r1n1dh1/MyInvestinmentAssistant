package com.mia.app.data.android.db

import androidx.annotation.RestrictTo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * The write-once price cache — plan.md §5.2.
 *
 * ### The write-once contract
 * [insertIgnore] is annotated `OnConflictStrategy.IGNORE`, which Room compiles to
 * `INSERT OR IGNORE`. A bar that already exists is therefore **never** updated, and a second
 * parse of the same session — a retry, a re-run of the nightly worker, a slightly different
 * unparse of the same bytes — cannot quietly revise history. plan.md §5.2 says "500 rows,
 * write-once" and plan.md §13's Yahoo risk is mitigated by caching once; this is where that
 * promise is kept.
 *
 * The **single documented exception** is [attachDelivery], which moves `deliveryPercent` from
 * `NULL` to a published value for a session that is already stored. The legacy
 * `sec_bhavdata` file (plan.md §3.5) is a different file describing the same session and
 * arrives on its own schedule; its `UPDATE … WHERE deliveryPercent IS NULL` cannot touch a bar
 * that already has a figure, and cannot touch a price.
 *
 * All methods are `suspend`, so Room dispatches them onto its own query executor and never runs
 * one on the caller's thread.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
@Dao
abstract class CandleDao {

    /**
     * `INSERT OR IGNORE`. Returns one id per input row, or `-1` for a row that already existed,
     * which is how the EOD worker counts "written" against "already known".
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertIgnore(candles: List<CandleEntity>): List<Long>

    @Query("SELECT COUNT(*) FROM candles WHERE symbol = :symbol AND timeframe = :timeframe")
    abstract suspend fun count(symbol: String, timeframe: String): Int

    @Query(
        "SELECT * FROM candles WHERE symbol = :symbol AND timeframe = :timeframe " +
            "ORDER BY date DESC LIMIT :limit",
    )
    abstract suspend fun recent(symbol: String, timeframe: String, limit: Int): List<CandleEntity>

    @Query(
        "SELECT * FROM candles WHERE symbol = :symbol AND timeframe = :timeframe ORDER BY date ASC",
    )
    abstract suspend fun all(symbol: String, timeframe: String): List<CandleEntity>

    /** Every bar of one session across all symbols — the whole market for that date. */
    @Query("SELECT * FROM candles WHERE date = :date ORDER BY symbol ASC")
    abstract suspend fun forDate(date: String): List<CandleEntity>

    @Query("SELECT MAX(date) FROM candles WHERE timeframe = :timeframe")
    abstract suspend fun latestDate(timeframe: String): String?

    @Query("SELECT MIN(date) FROM candles WHERE timeframe = :timeframe")
    abstract suspend fun earliestDate(timeframe: String): String?

    @Query("SELECT DISTINCT symbol FROM candles ORDER BY symbol ASC")
    abstract suspend fun symbols(): List<String>

    /** See the class KDoc: this is the only write that is allowed to touch an existing row. */
    @Query(
        "UPDATE candles SET deliveryPercent = :percent " +
            "WHERE symbol = :symbol AND timeframe = 'D1' AND date = :date AND deliveryPercent IS NULL",
    )
    abstract suspend fun attachDelivery(symbol: String, date: String, percent: Double): Int

    @Query("SELECT COUNT(*) FROM candles WHERE date = :date AND deliveryPercent IS NOT NULL")
    abstract suspend fun deliveryCountForDate(date: String): Int
}

/**
 * The identity table — plan.md §5.2 `symbols(symbol, name, isin, series) PK(isin)`.
 *
 * Insert is INSERT-OR-IGNORE rather than REPLACE on purpose: SQLite's REPLACE deletes the
 * conflicting row first, which would fire the `patterns.symbol ON DELETE CASCADE` foreign key
 * and erase the user's pattern history every time a symbol's display name was refreshed.
 * Mutable display fields therefore go through [updateDisplayFields], which touches nothing
 * else.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
@Dao
abstract class SymbolDao {

    /** `-1` per row that already existed. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertIgnore(symbols: List<SymbolEntity>): List<Long>

    @Query(
        "UPDATE symbols SET name = :name, series = :series, listingDate = :listingDate, " +
            "paidUpValue = :paidUpValue, lotSize = :lotSize, faceValue = :faceValue WHERE isin = :isin",
    )
    abstract suspend fun updateDisplayFields(
        isin: String,
        name: String,
        series: String,
        listingDate: String?,
        paidUpValue: Double?,
        lotSize: Long?,
        faceValue: Double?,
    ): Int

    @Query("SELECT * FROM symbols WHERE isin = :isin")
    abstract suspend fun byIsin(isin: String): SymbolEntity?

    @Query("SELECT * FROM symbols WHERE symbol = :symbol")
    abstract suspend fun bySymbol(symbol: String): SymbolEntity?

    @Query("SELECT * FROM symbols ORDER BY symbol ASC")
    abstract suspend fun all(): List<SymbolEntity>

    @Query("SELECT * FROM symbols WHERE symbol IN (:symbols) ORDER BY symbol ASC")
    abstract suspend fun bySymbols(symbols: List<String>): List<SymbolEntity>

    @Query("SELECT COUNT(*) FROM symbols")
    abstract suspend fun count(): Int
}

/**
 * The bounded nightly work list — decision OQ-4 ("watchlist nightly + on demand").
 *
 * plan.md §13's CGNAT mitigation is "1 fetch/day for the whole market, not per symbol"; this
 * table is what keeps the nightly pattern scan bounded to what the user actually asked about.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
@Dao
abstract class WatchlistDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertIgnore(rows: List<WatchlistEntity>): List<Long>

    @Query("DELETE FROM watchlist WHERE isin = :isin")
    abstract suspend fun delete(isin: String): Int

    @Query("UPDATE watchlist SET alertsEnabled = :enabled WHERE isin = :isin")
    abstract suspend fun setAlertsEnabled(isin: String, enabled: Boolean): Int

    @Query("SELECT * FROM watchlist ORDER BY addedOn ASC, symbol ASC")
    abstract suspend fun all(): List<WatchlistEntity>

    @Query("SELECT * FROM watchlist WHERE alertsEnabled = 1 ORDER BY symbol ASC")
    abstract suspend fun alertEnabled(): List<WatchlistEntity>

    @Query("SELECT COUNT(*) FROM watchlist")
    abstract suspend fun count(): Int
}

/**
 * Fundamentals storage — `ratios`, `quarters` plus the two extension tables.
 *
 * Writes are insert-or-replace *for the same primary key only*: a ratios row is a dated
 * snapshot (`PK(symbol, asOf)`), so re-fetching the same date must overwrite it — the figure
 * may have been corrected, and there is nothing to lose, because the candle cache is the
 * write-once table and this is not. `REPLACE` is safe here because no table references
 * `ratios`/`quarters`/`statement_rows`/`derived_metrics` with a foreign key.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
@Dao
abstract class FundamentalsDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putRatios(ratios: RatiosEntity)

    @Query("SELECT * FROM ratios WHERE symbol = :symbol ORDER BY asOf DESC LIMIT 1")
    abstract suspend fun latestRatios(symbol: String): RatiosEntity?

    @Query("SELECT * FROM ratios WHERE symbol = :symbol AND asOf = :asOf")
    abstract suspend fun ratiosOn(symbol: String, asOf: String): RatiosEntity?

    @Query("SELECT * FROM ratios ORDER BY symbol ASC")
    abstract suspend fun allRatios(): List<RatiosEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putQuarters(quarters: List<QuarterEntity>)

    @Query("SELECT * FROM quarters WHERE symbol = :symbol ORDER BY year DESC, quarter DESC")
    abstract suspend fun quarters(symbol: String): List<QuarterEntity>

    @Query("DELETE FROM quarters WHERE symbol = :symbol")
    abstract suspend fun clearQuarters(symbol: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putStatementRows(rows: List<StatementRowEntity>)

    @Query(
        "SELECT * FROM statement_rows WHERE symbol = :symbol AND statement = :statement " +
            "ORDER BY fiscalYear DESC, ordinal ASC",
    )
    abstract suspend fun statementRows(symbol: String, statement: String): List<StatementRowEntity>

    @Query("SELECT * FROM statement_rows WHERE symbol = :symbol ORDER BY statement ASC")
    abstract suspend fun allStatementRows(symbol: String): List<StatementRowEntity>

    @Query("DELETE FROM statement_rows WHERE symbol = :symbol")
    abstract suspend fun clearStatementRows(symbol: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putDerivedMetrics(rows: List<DerivedMetricEntity>)

    @Query("SELECT * FROM derived_metrics WHERE symbol = :symbol AND asOf = :asOf")
    abstract suspend fun derivedMetrics(symbol: String, asOf: String): List<DerivedMetricEntity>

    @Query("SELECT * FROM derived_metrics WHERE symbol = :symbol")
    abstract suspend fun allDerivedMetrics(symbol: String): List<DerivedMetricEntity>

    @Query("DELETE FROM derived_metrics WHERE symbol = :symbol AND asOf = :asOf")
    abstract suspend fun clearDerivedMetrics(symbol: String, asOf: String): Int
}

/**
 * The whole-market rolling cache — plan.md §5.2 `bhavcopy_cache(date)`.
 *
 * The single-row shape is produced by the primary key plus [replace], which deletes every other
 * row in the same transaction: "replace the market cache" is literally what happens, and it is
 * the reason the previous session's price bars survive untouched in `candles`.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
@Dao
abstract class MarketDao {

    @Query("SELECT * FROM bhavcopy_cache")
    abstract suspend fun cache(): BhavcopyCacheEntity?

    @Query("SELECT * FROM bhavcopy_cache WHERE date = :date")
    abstract suspend fun cacheFor(date: String): BhavcopyCacheEntity?

    /** Replaces the rolling cache: delete everything, then insert the new session. */
    @androidx.room.Transaction
    open suspend fun replace(row: BhavcopyCacheEntity) {
        clear()
        insert(row)
    }

    @Query("DELETE FROM bhavcopy_cache")
    abstract suspend fun clear(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insert(row: BhavcopyCacheEntity)
}

/**
 * plan.md §10: "Store last success/failure in `work_state`".
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
@Dao
abstract class WorkStateDao {

    @Query("SELECT * FROM work_state WHERE taskId = :taskId")
    abstract suspend fun byTask(taskId: String): WorkStateEntity?

    @Query("SELECT * FROM work_state ORDER BY taskId ASC")
    abstract suspend fun all(): List<WorkStateEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun put(row: WorkStateEntity)

    @Query("DELETE FROM work_state WHERE taskId = :taskId")
    abstract suspend fun delete(taskId: String): Int
}