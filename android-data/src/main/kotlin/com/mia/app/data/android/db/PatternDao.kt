package com.mia.app.data.android.db

import androidx.annotation.RestrictTo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.mia.app.domain.model.Match
import com.mia.app.domain.model.PatternOutcome

/**
 * Stored detections, their pivots and their outcomes — plan.md §5.2.
 *
 * ### Why pivots are rows and not a blob
 * plan.md §5.2 states it outright: *"pattern_pivots is normalised rather than a JSON blob so
 * patterns can be re-scored if the scorer changes, without re-detecting."* [storeMatch] therefore
 * writes the pattern row and one row per pivot together, and [loadMatch] reads them back into
 * the same `Match` the detector produced.
 *
 * ### Why the symbol row is ensured inside the transaction
 * `patterns.symbol` carries a real foreign key to `symbols.symbol` with `ON DELETE CASCADE`
 * (plan.md §5.2's "FK(symbol)"). Without the ensure step, storing a detection for a symbol the
 * master has not yet written would fail the insert — and a foreign-key error surfacing as a
 * failed nightly scan is a far worse failure mode than a duplicated, ignored symbol row.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
@Dao
abstract class PatternDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertSymbol(row: SymbolEntity): Long

    /**
     * Stores one detection: symbol row (ignored if present), pattern row, then its pivots.
     *
     * @return the new `patterns.id`, which the caller uses as the FK for an outcome row.
     */
    @Transaction
    open suspend fun storeMatch(symbol: SymbolEntity, pattern: PatternEntity, pivots: List<PatternPivotEntity>): Long {
        insertSymbol(symbol)
        val id = insertPattern(pattern)
        insertPivots(pivots.map { it.copy(patternId = id) })
        return id
    }

    /** Replaces the pivots of an existing detection, for a re-score without re-detecting. */
    @Transaction
    open suspend fun replacePivots(patternRowId: Long, pivots: List<PatternPivotEntity>) {
        deletePivots(patternRowId)
        insertPivots(pivots.map { it.copy(patternId = patternRowId) })
    }

    @Insert
    abstract suspend fun insertPattern(pattern: PatternEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertPivots(pivots: List<PatternPivotEntity>)

    @Query("DELETE FROM pattern_pivots WHERE patternId = :patternRowId")
    abstract suspend fun deletePivots(patternRowId: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertOutcome(outcome: OutcomeEntity)

    @Query("SELECT * FROM patterns WHERE id = :id")
    abstract suspend fun patternById(id: Long): PatternEntity?

    @Query("SELECT * FROM pattern_pivots WHERE patternId = :patternRowId ORDER BY pivotIndex ASC")
    abstract suspend fun pivots(patternRowId: Long): List<PatternPivotEntity>

    @Query("SELECT * FROM outcomes WHERE patternId = :patternRowId")
    abstract suspend fun outcome(patternRowId: Long): OutcomeEntity?

    @Query("SELECT * FROM outcomes")
    abstract suspend fun allOutcomes(): List<OutcomeEntity>

    @Query("SELECT * FROM outcomes WHERE resolved = 0 ORDER BY projectedOn ASC")
    abstract suspend fun unresolvedOutcomes(): List<OutcomeEntity>

    @Query("SELECT * FROM outcomes WHERE symbol = :symbol ORDER BY projectedOn DESC")
    abstract suspend fun outcomesFor(symbol: String): List<OutcomeEntity>

    /** Everything tracked for one `(symbol, timeframe)` pair, newest detection first. */
    @Query(
        "SELECT * FROM patterns WHERE symbol = :symbol AND timeframe = :timeframe " +
            "ORDER BY detectedOn DESC, score DESC",
    )
    abstract suspend fun matches(symbol: String, timeframe: String): List<PatternEntity>

    /** Every detection on one day — the set the nightly scan just wrote. */
    @Query("SELECT * FROM patterns WHERE detectedOn = :date")
    abstract suspend fun matchesOn(date: String): List<PatternEntity>

    @Query("SELECT COUNT(*) FROM patterns")
    abstract suspend fun patternCount(): Int

    @Query("SELECT COUNT(*) FROM outcomes WHERE resolved = 1")
    abstract suspend fun resolvedOutcomeCount(): Int

    @Query("DELETE FROM patterns WHERE id = :patternRowId")
    abstract suspend fun deletePattern(patternRowId: Long): Int

    /**
     * Loads a detection back into the exact `Match` shape the engine produced.
     *
     * Returns `null` when the row is absent rather than a half-built match: a partially
     * reconstructed `Match` would have a `measuredMove` whose `projectedLevel` was missing,
     * which is the one thing that must never be rendered.
     */
    @Transaction
    open suspend fun loadMatch(patternRowId: Long): StoredMatch? {
        val row = patternById(patternRowId) ?: return null
        return StoredMatch(
            match = Mappers.toMatch(row, pivots(patternRowId)),
            outcome = outcome(patternRowId)?.let { Mappers.toOutcome(it) },
        )
    }
}

/**
 * A stored detection and, when one exists, its settled outcome.
 *
 * Declared in this module rather than reusing a `:domain` type because it exists only at the
 * Room boundary and must never be the type a repository returns.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
data class StoredMatch(
    val match: Match,
    val outcome: PatternOutcome?,
)