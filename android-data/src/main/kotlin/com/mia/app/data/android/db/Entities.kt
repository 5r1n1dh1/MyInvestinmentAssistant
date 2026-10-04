package com.mia.app.data.android.db

import androidx.annotation.RestrictTo
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entities for plan.md §5.2, verbatim in shape.
 *
 * ### Rules these tables encode
 *
 * 1. **`candles` is write-once.** `PK(symbol, timeframe, date)` plus `OnConflictStrategy.IGNORE`
 *    means a session's OHLCV is written exactly once and can never be silently revised by a
 *    second, subtly different parse. See `CandleDao`.
 * 2. **`symbols` is keyed on ISIN**, because a ticker is a mutable label and the ISIN is the
 *    stable identity (plan.md §9, the `TATAMOTORS` demerger). The ticker carries a UNIQUE index
 *    so `patterns.symbol` can be a real foreign key.
 * 3. **Enums are stored as their `name`.** Not as ordinals — an ordinal is silently renumbered
 *    the moment someone reorders an enum, and this database is supposed to survive app updates
 *    with its accuracy history intact (plan.md §6.1).
 * 4. **Dates are stored as ISO-8601 `TEXT`** (`yyyy-MM-dd`) through `MiaConverters`, rather than
 *    relying on a `java.time` built-in type converter. ISO text sorts lexicographically in the
 *    same order as chronologically, so `ORDER BY date` is a date sort, and the mapping is
 *    testable on a plain JVM.
 * 5. **Nothing here leaks to `:app`.** Every type is `@RestrictTo(LIBRARY)`; repositories return
 *    `:domain` types through `Mappers`.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
object DbSchema {
    /** plan.md §3.2: the deep backfill is 2 years of daily bars. */
    const val DAILY_BACKFILL_ROWS: Int = 500

    /** plan.md §3.2: the deep backfill is 5 years of weekly bars. */
    const val WEEKLY_BACKFILL_ROWS: Int = 261

    /** plan.md §5.2: the bhavcopy cache is a single rolling row of the whole market. */
    const val BHAVCOPY_ROW_LIMIT: Int = 1
}

/**
 * One OHLCV bar, `PK(symbol, timeframe, date)` — **write-once** (plan.md §5.2).
 *
 * [deliveryPercent] is the single mutable column and only ever moves from `NULL` to a published
 * figure, because the legacy `sec_bhavdata` file (plan.md §3.5) is a *different* file for the
 * *same* session and arrives whenever it arrives. `CandleDao.attachDelivery` enforces that
 * direction with `WHERE deliveryPercent IS NULL`.
 */
@Entity(
    tableName = "candles",
    primaryKeys = ["symbol", "timeframe", "date"],
    indices = [Index(value = ["date"], name = "index_candles_date")],
)
data class CandleEntity(
    @ColumnInfo(name = "symbol") val symbol: String,
    /** `"D1"` or `"W1"` — plan.md §1.4 rule 9: no intraday. */
    @ColumnInfo(name = "timeframe") val timeframe: String,
    /** ISO-8601, e.g. `2026-10-01`. */
    @ColumnInfo(name = "date") val date: String,
    @ColumnInfo(name = "open") val open: Double,
    @ColumnInfo(name = "high") val high: Double,
    @ColumnInfo(name = "low") val low: Double,
    @ColumnInfo(name = "close") val close: Double,
    @ColumnInfo(name = "volume") val volume: Long,
    /** Null whenever the legacy file published no figure. Never defaulted to 0.0 (plan.md §8.2). */
    @ColumnInfo(name = "deliveryPercent") val deliveryPercent: Double?,
    /** `Source.name`. */
    @ColumnInfo(name = "source") val source: String,
)

/**
 * One listed instrument. `PK(isin)` — plan.md §5.2.
 *
 * Insert strategy is INSERT-OR-IGNORE: identity is write-once and a later conflicting row can
 * never *replace* an identity in place, because a REPLACE would cascade-delete that symbol's
 * `patterns` rows through the foreign key below.
 */
@Entity(
    tableName = "symbols",
    primaryKeys = ["isin"],
    indices = [Index(value = ["symbol"], unique = true, name = "index_symbols_symbol")],
)
data class SymbolEntity(
    /** Human-facing ticker. `UNIQUE` so `patterns.symbol` can reference it. */
    @ColumnInfo(name = "symbol") val symbol: String,
    @ColumnInfo(name = "name") val name: String,
    /** **The primary key.** Survives every rename and demerger (plan.md §9). */
    @ColumnInfo(name = "isin") val isin: String,
    @ColumnInfo(name = "series") val series: String,
    /** ISO-8601 listing date, or `NULL` when the master published none. */
    @ColumnInfo(name = "listingDate") val listingDate: String?,
    @ColumnInfo(name = "paidUpValue") val paidUpValue: Double?,
    @ColumnInfo(name = "lotSize") val lotSize: Long?,
    @ColumnInfo(name = "faceValue") val faceValue: Double?,
    /** `Source.name`. */
    @ColumnInfo(name = "source") val source: String,
)

/**
 * Merged fundamentals for one symbol and one as-of date. `PK(symbol, asOf)` — plan.md §5.2.
 *
 * Every ratio is nullable because a blank in the source must never become `0.0` (plan.md §3.3,
 * §7.3: a bank's `Debt/Equity` is *absent*, not zero).
 */
@Entity(
    tableName = "ratios",
    primaryKeys = ["symbol", "asOf"],
    indices = [Index(value = ["symbol"], name = "index_ratios_symbol")],
)
data class RatiosEntity(
    @ColumnInfo(name = "symbol") val symbol: String,
    /** ISO-8601. plan.md §10: the UI must always be able to show this. */
    @ColumnInfo(name = "asOf") val asOf: String,
    @ColumnInfo(name = "sector") val sector: String,
    /**
     * screener.in's own industry classification, e.g. `"Energy, Oil, Gas & Consumable Fuels, …"`.
     *
     * Stored because `SectorResolver`'s highest-precedence input is this text, not the company's
     * name. It was previously carried only on the in-memory parse, so **every cached read** rebuilt
     * a snapshot with `industry == null` and sector resolution fell back to matching words in the
     * name — which is why a company whose page says `Energy` still read "could not resolve" once
     * cached. Nullable so rows written before v3 are legal rather than a migration failure; a null
     * here simply means "re-resolve from the name, or refetch".
     */
    @ColumnInfo(name = "industry") val industry: String?,
    @ColumnInfo(name = "source") val source: String,
    @ColumnInfo(name = "confidence") val confidence: String,
    @ColumnInfo(name = "roce") val roce: Double?,
    @ColumnInfo(name = "roe") val roe: Double?,
    @ColumnInfo(name = "debtToEquity") val debtToEquity: Double?,
    @ColumnInfo(name = "interestCoverage") val interestCoverage: Double?,
    @ColumnInfo(name = "operatingMargin") val operatingMargin: Double?,
    @ColumnInfo(name = "fcf") val fcf: Double?,
    @ColumnInfo(name = "pat") val pat: Double?,
    @ColumnInfo(name = "cfoToOp") val cfoToOp: Double?,
    @ColumnInfo(name = "revenueCagr3y") val revenueCagr3y: Double?,
    @ColumnInfo(name = "revenueGrowth") val revenueGrowth: Double?,
    @ColumnInfo(name = "grossMargin") val grossMargin: Double?,
    @ColumnInfo(name = "pe") val pe: Double?,
    @ColumnInfo(name = "pb") val pb: Double?,
    @ColumnInfo(name = "evToEbitda") val evToEbitda: Double?,
    @ColumnInfo(name = "dividendYield") val dividendYield: Double?,
    @ColumnInfo(name = "promoterHolding") val promoterHolding: Double?,
    @ColumnInfo(name = "marketCap") val marketCap: Double?,
)

/**
 * One row of the screener.in `quarters` table. `PK(symbol, year, quarter)` — plan.md §5.2.
 * Thirteen quarters are published per page (plan.md §14).
 */
@Entity(
    tableName = "quarters",
    primaryKeys = ["symbol", "year", "quarter"],
    indices = [Index(value = ["symbol"], name = "index_quarters_symbol")],
)
data class QuarterEntity(
    @ColumnInfo(name = "symbol") val symbol: String,
    @ColumnInfo(name = "year") val year: Int,
    @ColumnInfo(name = "quarter") val quarter: Int,
    @ColumnInfo(name = "sales") val sales: Double?,
    @ColumnInfo(name = "expenses") val expenses: Double?,
    @ColumnInfo(name = "operatingProfit") val operatingProfit: Double?,
    @ColumnInfo(name = "opmPercent") val opmPercent: Double?,
    @ColumnInfo(name = "otherIncome") val otherIncome: Double?,
    @ColumnInfo(name = "interest") val interest: Double?,
    @ColumnInfo(name = "depreciation") val depreciation: Double?,
    @ColumnInfo(name = "eps") val eps: Double?,
)

/**
 * One row of an annual statement table, normalised. **Extension of plan.md §5.2.**
 *
 * The plan lists no annual-statement table, but `FundamentalsSnapshot` carries
 * `profitLoss` / `balanceSheet` / `cashFlow` / `shareholding` as `List<AnnualRow>`, and the
 * red-flag evidence view renders the actual published figures (plan.md §8.3). Dropping them on
 * the floor would mean a cached symbol could not produce a red flag at all, which is precisely
 * the "silently empty" failure plan.md §12 forbids.
 *
 * `ordinal` is stored rather than inferred from [label] because two statement rows can share a
 * label in different sections, and because the published order is part of the evidence.
 */
@Entity(
    tableName = "statement_rows",
    primaryKeys = ["symbol", "statement", "fiscalYear", "ordinal"],
)
data class StatementRowEntity(
    @ColumnInfo(name = "symbol") val symbol: String,
    /** `profitLoss` | `balanceSheet` | `cashFlow` | `shareholding`. */
    @ColumnInfo(name = "statement") val statement: String,
    /** Indian fiscal year (an FY ending 31 March is labelled by its starting year). */
    @ColumnInfo(name = "fiscalYear") val fiscalYear: Int,
    @ColumnInfo(name = "ordinal") val ordinal: Int,
    @ColumnInfo(name = "label") val label: String,
    @ColumnInfo(name = "value") val value: Double?,
)

/**
 * One `(metric, fiscal year)` pair of `CashFlowDerived`. **Extension of plan.md §5.2.**
 *
 * Normalised rather than a JSON blob for the same reason plan.md §5.2 normalises
 * `pattern_pivots`: the numbers must survive a schema change to the map that produced them,
 * and they must be queryable when a flag's evidence is rendered.
 */
@Entity(
    tableName = "derived_metrics",
    primaryKeys = ["symbol", "asOf", "metric", "fiscalYear"],
)
data class DerivedMetricEntity(
    @ColumnInfo(name = "symbol") val symbol: String,
    @ColumnInfo(name = "asOf") val asOf: String,
    /** Stable metric key, e.g. `cfo`, `pat`, `fcf`, `receivables`, `totalAssets`, … */
    @ColumnInfo(name = "metric") val metric: String,
    @ColumnInfo(name = "fiscalYear") val fiscalYear: Int,
    @ColumnInfo(name = "value") val value: Double,
)

/**
 * One detection. plan.md §5.2: `patterns(id, symbol, timeframe, name, score, detectedOn)` FK(symbol).
 *
 * [id] is the **row** id (`autoGenerate = true`), because `pattern_pivots.patternId` and
 * `outcomes.patternId` must point at one *detection*, not at a pattern *name* — the same
 * `Double Top` fires on the same stock every day (spec DD-12) and each firing has its own
 * geometry and its own outcome. The stable cross-run identity lives in [patternId]
 * (`com.mia.app.domain.pattern.PatternIds`), which is what groups the accuracy table
 * (plan.md §6.1, decision D6).
 *
 * The measured move and the score breakdown are stored here rather than recomputed, and the
 * pivots are stored as rows rather than as a blob, so the scorer can be re-run over a stored
 * detection without re-detecting it — the explicit reason plan.md §5.2 normalises
 * `pattern_pivots`.
 */
@Entity(
    tableName = "patterns",
    foreignKeys = [
        ForeignKey(
            entity = SymbolEntity::class,
            parentColumns = ["symbol"],
            childColumns = ["symbol"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["symbol"], name = "index_patterns_symbol"),
        Index(value = ["detectedOn"], name = "index_patterns_detectedOn"),
    ],
)
data class PatternEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0L,
    /** `PatternIds.of(name)` — stable across app updates (plan.md §6.1). */
    @ColumnInfo(name = "patternId") val patternId: Long,
    /** Foreign key to `symbols.symbol`. */
    @ColumnInfo(name = "symbol") val symbol: String,
    @ColumnInfo(name = "timeframe") val timeframe: String,
    /** Canonical display name — the accuracy-table key. */
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "score") val score: Int,
    /** ISO-8601; the newest bar of the scan (spec DD-12). */
    @ColumnInfo(name = "detectedOn") val detectedOn: String,
    @ColumnInfo(name = "category") val category: String,
    /** `MatchScorePolicy.Verdict.name` at scan time. */
    @ColumnInfo(name = "verdict") val verdict: String,
    @ColumnInfo(name = "geometry") val geometry: Int?,
    @ColumnInfo(name = "structure") val structure: Int?,
    @ColumnInfo(name = "confirmation") val confirmation: Int?,
    @ColumnInfo(name = "context") val context: Int?,
    @ColumnInfo(name = "moveHeight") val moveHeight: Double?,
    @ColumnInfo(name = "moveTrigger") val moveTrigger: Double?,
    @ColumnInfo(name = "moveProjected") val moveProjected: Double?,
    /** `Direction.name`. Null when the match has no measured move (spec DD-1). */
    @ColumnInfo(name = "moveDirection") val moveDirection: String?,
    @ColumnInfo(name = "barCount") val barCount: Int,
    @ColumnInfo(name = "notes") val notes: String,
)

/**
 * One pivot of one detection — plan.md §5.2 `pattern_pivots(patternId, index, price, kind)`,
 * deliberately **normalised, not a JSON blob** so a stored detection can be re-scored.
 *
 * The column plan.md spells `index` is called `pivotIndex` here: `INDEX` is an unreserved
 * SQLite keyword, but Room does not quote generated identifiers and this module cannot be
 * compiled on the build machine, so the safer name was chosen over the literal one.
 *
 * [barIndex], [date] and [legIndex] are stored in addition to plan.md's four columns because
 * `com.mia.app.domain.model.Pivot` carries them and losing any of them would make a re-score
 * impossible without re-reading the candle series.
 */
@Entity(
    tableName = "pattern_pivots",
    primaryKeys = ["patternId", "pivotIndex"],
    foreignKeys = [
        ForeignKey(
            entity = PatternEntity::class,
            parentColumns = ["id"],
            childColumns = ["patternId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["patternId"], name = "index_pattern_pivots_patternId")],
)
data class PatternPivotEntity(
    /** FK to `patterns.id`. */
    @ColumnInfo(name = "patternId") val patternId: Long,
    /** plan.md's `index`: 0-based position in the pivot sequence. */
    @ColumnInfo(name = "pivotIndex") val pivotIndex: Int,
    @ColumnInfo(name = "price") val price: Double,
    /** `PivotKind.name`: `HIGH` | `LOW`. */
    @ColumnInfo(name = "kind") val kind: String,
    @ColumnInfo(name = "barIndex") val barIndex: Int,
    /** ISO-8601. */
    @ColumnInfo(name = "date") val date: String,
    @ColumnInfo(name = "legIndex") val legIndex: Int,
)

/**
 * The outcome of one detection — plan.md §5.2 `outcomes(patternId, projectedHigh, actualHigh,
 * actualLow, hit)`.
 *
 * The remaining columns are what `com.mia.app.domain.model.PatternOutcome` needs to round-trip
 * without the `patterns` row (a query per group would otherwise be needed to build an accuracy
 * report). `hit` and `resolved` encode the two rules plan.md §6.1 depends on: a verdict is
 * written once, and it is only written when the whole evaluation window is in hand.
 */
@Entity(
    tableName = "outcomes",
    primaryKeys = ["patternId"],
    foreignKeys = [
        ForeignKey(
            entity = PatternEntity::class,
            parentColumns = ["id"],
            childColumns = ["patternId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["patternId"], unique = true, name = "index_outcomes_patternId"),
        Index(value = ["symbol"], name = "index_outcomes_symbol"),
    ],
)
data class OutcomeEntity(
    /** FK to `patterns.id`, unique: one outcome per detection. */
    @ColumnInfo(name = "patternId") val patternId: Long,
    @ColumnInfo(name = "symbol") val symbol: String,
    @ColumnInfo(name = "patternName") val patternName: String,
    @ColumnInfo(name = "timeframe") val timeframe: String,
    @ColumnInfo(name = "category") val category: String,
    /** ISO-8601 date the geometry was projected on. */
    @ColumnInfo(name = "projectedOn") val projectedOn: String,
    @ColumnInfo(name = "score") val score: Int,
    @ColumnInfo(name = "direction") val direction: String,
    /** plan.md's `projectedHigh`: the measured-move level. Geometry, never a price target. */
    @ColumnInfo(name = "projectedLevel") val projectedLevel: Double,
    @ColumnInfo(name = "actualHigh") val actualHigh: Double?,
    @ColumnInfo(name = "actualLow") val actualLow: Double?,
    /** Nullable: null while the evaluation window is still open. */
    @ColumnInfo(name = "hit") val hit: Boolean?,
    @ColumnInfo(name = "resolved") val resolved: Boolean,
    @ColumnInfo(name = "horizonBars") val horizonBars: Int,
)

/**
 * The single rolling row of the whole market — plan.md §5.2 `bhavcopy_cache(date)`.
 *
 * [presence] is a compact `SYMBOL|ISIN|SERIES` text encoding of every kept row, which is what
 * `CorporateActionTracker` and `BhavcopyReconciler` need. The OHLCV itself is **not** stored
 * here: it goes to the write-once `candles` table, so a cache replacement can never rewrite
 * history. A ~3,051-row payload is roughly 75 KB of text, which is cheap next to the 208 KB
 * zip the file arrived in.
 */
@Entity(tableName = "bhavcopy_cache", primaryKeys = ["date"])
data class BhavcopyCacheEntity(
    /** ISO-8601 session date; the primary key makes the cache single-row by construction. */
    @ColumnInfo(name = "date") val date: String,
    /** ISO-8601 date the file was fetched. */
    @ColumnInfo(name = "fetchedOn") val fetchedOn: String,
    /** Data rows in the file, including series the parser filtered out. */
    @ColumnInfo(name = "rowsTotal") val rowsTotal: Int,
    /** Rows kept after the `EQ`/`SM` series filter. */
    @ColumnInfo(name = "rowsKept") val rowsKept: Int,
    /** How many of [rowsKept] got a delivery percentage attached. */
    @ColumnInfo(name = "deliveryAttached") val deliveryAttached: Int,
    @ColumnInfo(name = "healthOk") val healthOk: Boolean,
    @ColumnInfo(name = "notes") val notes: String,
    /** `SYMBOL|ISIN|SERIES` per line, `\\n` separated, UTF-8. */
    @ColumnInfo(name = "presence") val presence: String,
)

/**
 * The user's watchlist. **Extension of plan.md §5.2**, required by decision OQ-4
 * ("watchlist nightly + on demand"): without it the nightly scan has no bounded work list and
 * the only alternative is scanning the whole market, which plan.md §13 rules out.
 *
 * Keyed by ISIN so an entry survives a rename (plan.md §9).
 */
@Entity(
    tableName = "watchlist",
    primaryKeys = ["isin"],
    indices = [Index(value = ["symbol"], name = "index_watchlist_symbol")],
)
data class WatchlistEntity(
    @ColumnInfo(name = "isin") val isin: String,
    /** Denormalised ticker so the nightly scan can join without a second read. */
    @ColumnInfo(name = "symbol") val symbol: String,
    /** ISO-8601. */
    @ColumnInfo(name = "addedOn") val addedOn: String,
    @ColumnInfo(name = "alertsEnabled") val alertsEnabled: Boolean,
)

/**
 * plan.md §10: "Store last success/failure in `work_state`".
 *
 * A row is written for every run of every task. [lastSuccess] and [lastError] are both nullable
 * and mutually exclusive by construction (`recordSuccess` sets the first and clears the second),
 * so the UI's "last refreshed" / "last failed" pair cannot both be populated by accident.
 */
@Entity(tableName = "work_state", primaryKeys = ["taskId"])
data class WorkStateEntity(
    @ColumnInfo(name = "taskId") val taskId: String,
    /** ISO-8601 date-time of the last successful run, or null if it has never succeeded. */
    @ColumnInfo(name = "lastSuccess") val lastSuccess: String?,
    /** Human-readable reason of the last failure; cleared by a success. */
    @ColumnInfo(name = "lastError") val lastError: String?,
    /** ISO-8601 date-time of the last attempt, successful or not. */
    @ColumnInfo(name = "lastAttempt") val lastAttempt: String?,
    /** Short machine-ish status: `OK`, `NOT_PUBLISHED`, `NOT_TRADING_DAY`, … */
    @ColumnInfo(name = "status") val status: String?,
)