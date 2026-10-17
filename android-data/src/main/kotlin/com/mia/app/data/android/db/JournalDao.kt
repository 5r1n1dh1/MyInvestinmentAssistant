package com.mia.app.data.android.db

import androidx.annotation.RestrictTo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * The reader's own entries — gap E5.
 *
 * ### Why writes are `REPLACE` and not `IGNORE`
 * `candles` is write-once and `outcomes` is insert-or-replace, because both are *derived* from a
 * source. A journal entry is neither: it is typed by a person, and the only update this DAO ever
 * performs is the one the resolution pass makes — attaching `resolvedAt`, `resolutionOutcome` and
 * `resolutionChangePercent` to a row the reader wrote. The `WHERE resolutionOutcome IS NULL` clause
 * in [resolve] is what makes that update monotone: the journal's copy of plan.md §6.1's "a verdict
 * is written once" is an invariant of the SQL, not of a caller's discipline, so a second nightly
 * pass cannot overwrite a recorded outcome with a different reading even if the domain guard were
 * somehow bypassed.
 *
 * The three writing columns are therefore the *only* mutable ones. There is no update that can
 * change `thesis`, `expectation` or `falsification`, which is deliberate: an entry that can be
 * silently rewritten after the fact is no longer a record of what the reader believed then, and a
 * personal track record built from rewritten beliefs measures nothing.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
@Dao
abstract class JournalDao {

    /** @return the new row id. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insert(entry: JournalEntryEntity): Long

    /**
     * Attaches a resolution to an open entry, and only to an open entry.
     *
     * A `WHERE resolutionOutcome IS NULL` guard rather than a plain `@Update` on the id, because an
     * update that matched on the id alone would happily rewrite a settled entry — and the only
     * columns this DAO can ever change are these three resolution columns, which makes the guard the
     * whole mutation surface. There is deliberately **no** `update` method: nothing in the app may
     * edit a reader's thesis, expectation or falsification after the fact.
     *
     * @return 1 when the row was updated, `0` when it was already resolved — the caller can tell
     *   "resolved now" from "was already resolved" without reading the row back.
     */
    @Query(
        "UPDATE journal_entry SET resolvedAt = :resolvedAt, resolutionOutcome = :outcome, " +
            "resolutionChangePercent = :changePercent WHERE id = :id AND resolutionOutcome IS NULL",
    )
    abstract suspend fun resolve(
        id: Long,
        resolvedAt: String,
        outcome: String,
        changePercent: Double,
    ): Int

    /**
     * Every entry, newest first, then by id so two entries written on the same session never swap
     * places between two reads of the same list.
     */
    @Query("SELECT * FROM journal_entry ORDER BY createdAt DESC, id DESC")
    abstract suspend fun all(): List<JournalEntryEntity>

    @Query("SELECT * FROM journal_entry WHERE symbol = :symbol ORDER BY createdAt DESC, id DESC")
    abstract suspend fun forSymbol(symbol: String): List<JournalEntryEntity>

    @Query("SELECT * FROM journal_entry WHERE id = :id")
    abstract suspend fun byId(id: Long): JournalEntryEntity?

    /** Entries past their review date with nothing recorded yet — the resolution work queue. */
    @Query(
        "SELECT * FROM journal_entry WHERE resolutionOutcome IS NULL AND reviewDate <= :today " +
            "ORDER BY reviewDate ASC, id ASC",
    )
    abstract suspend fun dueForReview(today: String): List<JournalEntryEntity>

    @Query("SELECT COUNT(*) FROM journal_entry")
    abstract suspend fun count(): Int

    /**
     * Every symbol with at least one entry, sorted.
     *
     * Used for the journal's coverage line, so the screen can say how few symbols it covers rather
     * than implying a track record over the market.
     */
    @Query("SELECT DISTINCT symbol FROM journal_entry ORDER BY symbol ASC")
    abstract suspend fun symbols(): List<String>

    /**
     * The reader may remove their own entry.
     *
     * A journal the reader cannot delete is a ledger imposed on them, and this app has no business
     * keeping a record a person did not ask it to keep. Note what is **not** here: no bulk delete,
     * no "clear journal" — only the one row, deleted deliberately.
     */
    @Query("DELETE FROM journal_entry WHERE id = :id")
    abstract suspend fun delete(id: Long): Int
}