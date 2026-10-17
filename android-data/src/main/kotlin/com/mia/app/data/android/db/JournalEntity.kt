package com.mia.app.data.android.db

import androidx.annotation.RestrictTo
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One thing a reader believed about one stock, written before the outcome was known — gap E5.
 *
 * ### Why this table is the app's most important data, and the least protected by a foreign key
 * It is the only table holding something nothing else can recover: `candles` can be refetched,
 * `outcomes` can be re-derived from a scan, and every ratio comes from a page — but the reader's
 * own words as they were on the day cannot be reconstructed by any of them. So:
 *
 * * **no foreign key to `symbols`.** `patterns.symbol` carries one and deletes with the symbol,
 *   which is correct there because a detection without its bars is meaningless. A journal entry is
 *   not: it is a dated record of a belief about a company, and it has to survive the company
 *   leaving the symbol master, a demerger (plan.md §9) or a cleared cache.
 * * **`symbol` is indexed**, because every read is "this stock's entries", and a full scan of a
 *   table that only ever grows is not acceptable on the phone this app targets.
 *
 * ### Why the dates are ISO-8601 `TEXT` and why `resolvedAt` is nullable
 * Same reason as every other date column in this schema ([MiaConverters]): ISO text sorts
 * lexicographically in chronological order, so `ORDER BY reviewDate` is a date sort and the
 * mapping round-trips in a plain JVM test. `resolvedAt` is `NULL` while the entry is open — an
 * absent value in a nullable column, never a sentinel date.
 *
 * ### The three columns beyond the plan's list, and why each is load-bearing
 * planv2 §W6 names `(symbol, thesis, expectation, falsification, reviewDate, createdAt)`. This adds:
 *
 * * **`expectationDirection`** — the structured half of `expectation`. A free-text expectation
 *   cannot be scored: deciding whether "I expect it to grind up" was borne out is the app
 *   interpreting a sentence, and an interpreted sentence scored as a hit is indistinguishable on
 *   screen from a measurement. The reader states the direction separately; this column stores it.
 * * **`resolutionOutcome`** — the recorded outcome, `NULL` while open. See
 *   `com.mia.app.domain.journal.JournalOutcome`: three factual labels about price and none about
 *   the reader, so a resolution column cannot be phrased as vindication.
 * * **`resolutionChangePercent`** — the measured move that produced the outcome. Carried beside
 *   the label so the outcome always has its evidence attached and the UI never re-derives it from
 *   bars that may since have been refetched.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
@Entity(
    tableName = "journal_entry",
    indices = [
        Index(value = ["symbol"], name = "index_journal_entry_symbol"),
        Index(value = ["reviewDate"], name = "index_journal_entry_reviewDate"),
        Index(value = ["createdAt"], name = "index_journal_entry_createdAt"),
    ],
)
data class JournalEntryEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0L,
    /** NSE ticker as the reader typed it, trimmed and upper-cased. No foreign key. See the KDoc. */
    @ColumnInfo(name = "symbol") val symbol: String,
    /** `"D1"` or `"W1"` — plan.md §1.4 rule 9: no intraday. */
    @ColumnInfo(name = "timeframe") val timeframe: String,
    /** What the reader believes about the company. Required. */
    @ColumnInfo(name = "thesis") val thesis: String,
    /** What the reader expects price to do, in their words. Required. */
    @ColumnInfo(name = "expectation") val expectation: String,
    /** `ExpectationDirection.name`. Required: silence is not a direction. */
    @ColumnInfo(name = "expectationDirection") val expectationDirection: String,
    /** The condition that would prove the entry wrong. Required — see `JournalPolicy`. */
    @ColumnInfo(name = "falsification") val falsification: String,
    /** ISO-8601. When the reader intends to revisit the entry. */
    @ColumnInfo(name = "reviewDate") val reviewDate: String,
    /** ISO-8601. The session the entry was written on. */
    @ColumnInfo(name = "createdAt") val createdAt: String,
    /** ISO-8601 session the outcome was measured from. `NULL` while the entry is open. */
    @ColumnInfo(name = "resolvedAt") val resolvedAt: String?,
    /** `JournalOutcome.name`. `NULL` while open. */
    @ColumnInfo(name = "resolutionOutcome") val resolutionOutcome: String?,
    /** The measured move in percent. `NULL` while open. */
    @ColumnInfo(name = "resolutionChangePercent") val resolutionChangePercent: Double?,
)