package com.mia.app.data.android.db

import androidx.annotation.RestrictTo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.mia.app.domain.pattern.ProjectionGate

/**
 * The one write path onto the on-device decision counter — gap F3.
 *
 * ### Why the write is an increment and not an insert
 * A counter that appends an event row would grow without bound on a device that scans its
 * watchlist nightly, and would eventually need its own retention story. A tally is one row per
 * `(scope, bucket)` and an `UPSERT` that adds one, which is why this table is a fixed maximum of
 * `DecisionScope.ALL.size × DecisionBucket.ALL.size` rows: a phone that has been installed for a
 * year stores the same handful of integers a fresh install does.
 *
 * The `+ 1` is done **in the SQL** rather than as read-modify-write in Kotlin, because the nightly
 * scan and a foreground scan can overlap on a device that was left running, and a read-modify-write
 * would lose one of the two increments. The arithmetic lives in the statement so the race is not
 * possible at all.
 *
 * ### Why `blocker = null` is recorded as `FIRED` here and nowhere else
 * `ProjectionGate.blocker` answers `null` for a match that may project. `null` cannot be a primary
 * key column, and the alternative — a sentinel string invented at the call site — would leave one
 * place where `FIRED` is spelled differently from every other. So [record] is the single conversion
 * point, and the mapper in `DecisionCounterRepository` reads it back through the same mapping.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
@Dao
abstract class DecisionCounterDao {

    /**
     * Records one decision: [bucket] gains one, and its window moves forward.
     *
     * `ON CONFLICT DO UPDATE` rather than `REPLACE`, because `REPLACE` in SQLite is a delete
     * followed by an insert: it would reset `firstRecordedOn` to today on every increment, and the
     * "counting since" date is the one thing that makes the number a measurement rather than a
     * vibe. `CASE WHEN` keeps the earliest date on a conflict and takes the later one.
     *
     * @param today ISO-8601 date, passed in rather than read here so the caller controls the clock
     *   and the whole table stays a pure function of its arguments (plan.md §4.3 purity).
     */
    @Query(
        """
        INSERT INTO `decision_counter` (`scope`, `bucket`, `count`, `firstRecordedOn`, `lastRecordedOn`)
        VALUES (:scope, :bucket, 1, :today, :today)
        ON CONFLICT(`scope`, `bucket`) DO UPDATE SET
            `count` = `count` + 1,
            `firstRecordedOn` = MIN(COALESCE(`firstRecordedOn`, :today), :today),
            `lastRecordedOn` = MAX(COALESCE(`lastRecordedOn`, :today), :today)
        """,
    )
    abstract suspend fun bump(scope: String, bucket: String, today: String)

    /**
     * Records a projection-gate evaluation and its outcome.
     *
     * The `null` → [DecisionBucket.FIRED] mapping is here and nowhere else, for the reason the class
     * KDoc gives: one conversion point means the word cannot be spelled two ways.
     *
     * Two statements in one transaction rather than one, because the gate's firing is derived from
     * whether any blocker was recorded at all — see [DecisionCounterRepository] — and a crash
     * between them would leave a tally whose total does not match its parts.
     */
    @Transaction
    open suspend fun record(scope: String, blocker: ProjectionGate.Blocker?, today: String) {
        val bucket = blocker?.name() ?: DecisionBucket.FIRED
        bump(scope, bucket, today)
    }

    /** One scan reaching the gate stage. Separate from [record] so the two cannot be confused. */
    @Transaction
    open suspend fun recordScan(today: String) {
        bump(DecisionScope.CANONICAL_SCAN, DecisionBucket.SCAN_COMPLETED, today)
    }

    /** Every row, scope-major. Absent buckets are absent rows, not zero-valued rows. */
    @Query("SELECT * FROM `decision_counter` ORDER BY `scope` ASC, `bucket` ASC")
    abstract suspend fun all(): List<DecisionCounterEntity>

    /** One scope's rows. `null` rather than empty when the scope has never been recorded. */
    @Query("SELECT * FROM `decision_counter` WHERE `scope` = :scope ORDER BY `bucket` ASC")
    abstract suspend fun forScope(scope: String): List<DecisionCounterEntity>

    /**
     * Clears the tally.
     *
     * ### Why this exists at all
     * A counter a reader cannot clear becomes a permanent claim about a session they cannot
     * remember: "the gate fired 0 times" is a statement about *this install since it began*, and
     * without a reset there is no way to make the number mean "since I did the thing that should
     * make it fire". A figure that cannot be cleared is not observability, it is a verdict.
     *
     * It deletes every scope rather than only the projection gate because a reader who clears a
     * tally is asking "start again from zero", and leaving a half-cleared table behind would answer
     * a question nobody asked.
     *
     * @return how many rows went. `0` means there was nothing to clear, which the screen renders
     *   as "already zero" rather than as a failure.
     */
    @Query("DELETE FROM `decision_counter`")
    abstract suspend fun clear(): Int

    /**
     * Writes a row verbatim. Only used by tests and by a future migration that has to seed a
     * bucket; production writes always go through [bump] so the increment stays in SQL.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun put(row: DecisionCounterEntity)
}