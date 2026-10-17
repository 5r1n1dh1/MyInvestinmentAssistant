package com.mia.app.data.android.db

import androidx.annotation.RestrictTo
import com.mia.app.domain.journal.ExpectationDirection
import com.mia.app.domain.journal.JournalEntry
import com.mia.app.domain.journal.JournalOutcome
import java.time.LocalDate

/**
 * `journal_entry` rows to and from `:domain` types.
 *
 * ### Why every field is read back defensively rather than trusted
 * This table is the only one in the schema whose rows are **typed by a person**, and the two columns
 * that come back as enum names are read with a fallback rather than a cast:
 *
 * * [toOutcome] falls back to `null` for an outcome name this build does not know. A row written by
 *   a build that knew a fourth [JournalOutcome] must not crash a reader's list on a later build; the
 *   price move is still on the row, so the entry keeps its evidence and loses only its label.
 * * [toDirection] falls back to [ExpectationDirection.EITHER_WAY], which is the one value that means
 *   "no stated direction" and is excluded from the tally by construction.
 *
 * The alternative — `valueOf` and a cast that throws — would mean an app update could make a
 * reader's own words unreadable, and plan.md §12's whole argument is that a parser must degrade
 * into a stated absence rather than take the screen down.
 *
 * The same rule applies in the other direction: nothing here can produce a row that
 * [JournalEntry]'s own `init` would reject, so an invalid row cannot be written by a future caller
 * that skips the draft path.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
object JournalMappers {

    /**
     * Reads a row, degrading an unrecognised resolution to an **open** entry rather than throwing.
     *
     * [JournalEntry]'s own `init` forbids a half-resolution — a `resolvedAt` without an outcome, or
     * an outcome without its measured move — because a resolution that carries no evidence is the
     * shape the whole package exists to prevent. So a name this build does not recognise drops the
     * *whole* resolution: the entry reads as still open, its move is still on the row for a future
     * build that understands the name, and the reader sees an entry awaiting review rather than a
     * crash on entry. [toOutcome]'s KDoc explains why a fallback of `null` is the right answer.
     */
    fun toEntry(row: JournalEntryEntity): JournalEntry {
        val outcome = toOutcome(row.resolutionOutcome)
        val resolved = outcome != null && row.resolvedAt != null
        return JournalEntry(
            id = row.id,
            symbol = row.symbol,
            timeframe = Mappers.timeframeOrDefault(row.timeframe),
            thesis = row.thesis,
            expectation = row.expectation,
            expectationDirection = toDirection(row.expectationDirection),
            falsification = row.falsification,
            reviewDate = LocalDate.parse(row.reviewDate),
            createdAt = LocalDate.parse(row.createdAt),
            resolvedAt = row.resolvedAt?.takeIf { resolved }?.let(LocalDate::parse),
            resolutionOutcome = outcome?.takeIf { resolved },
            resolutionChangePercent = row.resolutionChangePercent?.takeIf { resolved },
        )
    }

    fun fromEntry(entry: JournalEntry): JournalEntryEntity = JournalEntryEntity(
        id = entry.id,
        symbol = entry.symbol,
        timeframe = Mappers.timeframeKey(entry.timeframe),
        thesis = entry.thesis,
        expectation = entry.expectation,
        expectationDirection = entry.expectationDirection.name,
        falsification = entry.falsification,
        reviewDate = entry.reviewDate.toString(),
        createdAt = entry.createdAt.toString(),
        resolvedAt = entry.resolvedAt?.toString(),
        resolutionOutcome = entry.resolutionOutcome?.name,
        resolutionChangePercent = entry.resolutionChangePercent,
    )

    /** `null` for a name this build does not know. See the object KDoc. */
    private fun toOutcome(raw: String?): JournalOutcome? =
        raw?.let { name -> JournalOutcome.entries.firstOrNull { it.name == name } }

    /** `EITHER_WAY` for a name this build does not know. See the object KDoc. */
    private fun toDirection(raw: String): ExpectationDirection =
        ExpectationDirection.entries.firstOrNull { it.name == raw } ?: ExpectationDirection.EITHER_WAY
}