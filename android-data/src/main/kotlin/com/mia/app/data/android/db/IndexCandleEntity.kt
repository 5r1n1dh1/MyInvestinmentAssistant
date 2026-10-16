package com.mia.app.data.android.db

import androidx.annotation.RestrictTo
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.Source
import com.mia.app.domain.model.Timeframe
import java.time.LocalDate

/**
 * One benchmark index session. `PK(symbol, date)` — **write-once**, like [CandleEntity].
 *
 * ### Why this is a separate table and not a few rows in `candles`
 * `candles.symbol` is read as "a symbol this app tracks": the nightly scan walks
 * `candleDao.symbols()`, `BhavcopyReconciler` diffs it against the EOD file, and breadth counts
 * it as the market. `^NSEI` is not any of those things — it is not in the bundled symbol master,
 * it never appears in a bhavcopy, and it has no ISIN. Storing it in `candles` would put a row that
 * no identity pipeline can account for into every one of those loops.
 *
 * A dedicated table makes the benchmark's existence **visible**: `SELECT COUNT(*) FROM
 * index_candles` answers "does this device have a benchmark?" without inferring it from a table
 * that is also full of equities.
 *
 * ### What is stored
 * OHLC exactly as Yahoo published it, unadjusted. An index has no splits, no bonus issues and no
 * delivery figure, so the `deliveryPercent` column [CandleEntity] carries has no analogue here and
 * [toCandle] maps it to `null` — the same rule as the equity path (plan.md §8.2).
 *
 * [symbol] is Yahoo's wire symbol (`^NSEI`), which is the only key both the source and the URL
 * agree on. The human label is not stored: it is a property of the index, not of a session, and
 * duplicating it here would be a second place for it to go stale.
 */
@Entity(
    tableName = "index_candles",
    primaryKeys = ["symbol", "date"],
    indices = [Index(value = ["symbol"], name = "index_index_candles_symbol")],
)
data class IndexCandleEntity(
    /** Yahoo's wire symbol, e.g. `^NSEI`. */
    @ColumnInfo(name = "symbol") val symbol: String,
    /** ISO-8601 session date. */
    @ColumnInfo(name = "date") val date: String,
    @ColumnInfo(name = "open") val open: Double,
    @ColumnInfo(name = "high") val high: Double,
    @ColumnInfo(name = "low") val low: Double,
    @ColumnInfo(name = "close") val close: Double,
    /**
     * Always `0`: an index has no traded share volume, and Yahoo sends nulls for it.
     *
     * Stored rather than inferred so a query that sums the column cannot invent a number.
     */
    @ColumnInfo(name = "volume") val volume: Long,
    /** `Source.name` — which source published this session. */
    @ColumnInfo(name = "source") val source: String,
) {
    /**
     * Projection into the price model the engine reads.
     *
     * The wire symbol is kept as [Candle.symbol]: the engine never looks a benchmark up by name,
     * it only aligns it by date (`relativeStrength`), and preserving the symbol means a note can
     * name the index that was actually used.
     */
    fun toCandle(): Candle = Candle(
        symbol = symbol,
        timeframe = Timeframe.D1,
        date = LocalDate.parse(date),
        open = open,
        high = high,
        low = low,
        close = close,
        volume = volume,
        // No delivery figure exists for an index. null, never 0.0 (plan.md §8.2).
        deliveryPercent = null,
        source = runCatching { Source.valueOf(source) }.getOrDefault(Source.YAHOO),
    )
}

/**
 * The stored benchmark cache.
 *
 * Mirrors [CandleDao]'s write-once contract exactly, for the same reason: a second, subtly
 * different parse of the same session must not be able to revise history. An index close that
 * silently changed would move every relative-strength reading in the app with no trace.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
@Dao
abstract class IndexDao {

    /** `INSERT OR IGNORE`. Returns `-1` per row that already existed. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertIgnore(rows: List<IndexCandleEntity>): List<Long>

    @Query("SELECT * FROM index_candles WHERE symbol = :symbol ORDER BY date ASC")
    abstract suspend fun all(symbol: String): List<IndexCandleEntity>

    @Query("SELECT COUNT(*) FROM index_candles WHERE symbol = :symbol")
    abstract suspend fun count(symbol: String): Int

    @Query("SELECT MAX(date) FROM index_candles WHERE symbol = :symbol")
    abstract suspend fun latestDate(symbol: String): String?

    @Query("SELECT DISTINCT symbol FROM index_candles ORDER BY symbol ASC")
    abstract suspend fun symbols(): List<String>
}