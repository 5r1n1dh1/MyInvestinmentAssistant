package com.mia.app.ui.journal

import com.mia.app.domain.journal.JournalDraft
import com.mia.app.domain.journal.JournalEntry
import com.mia.app.domain.journal.PersonalRecord
import com.mia.app.domain.model.HitRate
import java.time.LocalDate

/**
 * The Journal screen's entire view of the outside world (gap E5).
 *
 * ### Contract, as for every screen's data source
 * * `suspend`, already off the main thread.
 * * `:domain` types only.
 * * `null` / empty means **"there is nothing"**, not "we failed". A function that *fails* throws
 *   [JournalUnavailableException].
 *
 * ### Why the two records arrive through one call rather than two
 * [JournalLoad] carries the reader's own tally ([record]) and the app's engine record
 * ([appHitRate]) in a single value, for the same reason `AccuracyLoad` groups its reads: there must
 * be no intermediate state in which one rate is on screen and the other is not. Two rates shown
 * "beside" each other, loaded separately, would flicker into a state where only the app's is
 * visible — which is precisely the arrangement that invites a reader to read one as a verdict on
 * the other.
 */
interface JournalDataSource {

    /**
     * Everything the screen renders: the entries, the reader's tally over them, and the app's own
     * record for the same device.
     *
     * An empty list of entries is a real answer — a reader who has written nothing — and is
     * rendered as an empty state with a reason rather than as an error.
     */
    suspend fun load(): JournalLoad

    /**
     * Writes one entry from [draft].
     *
     * @return the stored entry, with the id the device assigned.
     * @throws IllegalArgumentException when the draft is incomplete. The form never calls this with
     *   an incomplete draft — [JournalDraft.blockingReason] gates the button — so a throw here is a
     *   bug rather than a user error, and it is deliberately not swallowed into a message.
     */
    suspend fun save(draft: JournalDraft, today: LocalDate): JournalEntry

    /** Removes one entry the reader wrote. Never anything else. */
    suspend fun delete(id: Long)
}

/**
 * The exception every implementation throws on a failed read or write.
 *
 * Separate from `AccuracyUnavailableException` for the same reason that type exists: a failed read
 * here means the app cannot show a person's own record back to them, which is a different failure
 * from the Accuracy tab's and deserves its own message rather than a shared one.
 */
class JournalUnavailableException(
    /** Plain language, shown verbatim. Never an exception class name. */
    val reason: String,
    val isTransient: Boolean = true,
) : Exception(reason)

/**
 * Everything the screen loaded, assembled off the main thread.
 *
 * @property openReasons why each past-its-review-date entry is still open, keyed by entry id. Built
 *   here rather than in the screen so the same sentence is used on the entry card and in the
 *   coverage line, and so an entry that can never close says which gate is shut instead of silently
 *   staying open.
 */
data class JournalLoad(
    val entries: List<JournalEntry> = emptyList(),
    val record: PersonalRecord = PersonalRecord.EMPTY,
    val appHitRate: HitRate = HitRate(symbol = null, patternName = null, hits = 0, samples = 0),
    val openReasons: Map<Long, String> = emptyMap(),
    val today: LocalDate = LocalDate.now(),
) {
    /** True when this device has no journal entries at all. The honest empty state. */
    val hasNoEntries: Boolean get() = entries.isEmpty()

    companion object {
        val EMPTY = JournalLoad()
    }
}