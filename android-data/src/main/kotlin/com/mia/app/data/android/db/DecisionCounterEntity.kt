package com.mia.app.data.android.db

import androidx.annotation.RestrictTo
import androidx.room.ColumnInfo
import androidx.room.Entity
import com.mia.app.domain.pattern.ProjectionGate

/**
 * The on-device decision counter — gap F3, "silent real-world failures are invisible".
 *
 * ### Why this table exists at all
 * gap F3, verbatim: *"Silent real-world failures are invisible. The team found A1 by measurement,
 * not by monitoring; the next such bug may go unfound."*
 *
 * A1 is the proof of the claim. The projection gate used to be
 * `MatchScorePolicy.PROJECT_THRESHOLD = 70`, the highest score any of the app's own detectors ever
 * produced in 875 settled walk-forward candidates was **68**, and so the app's headline feature —
 * a projected level and its breakout notification — had never executed once. Nothing caught it,
 * because a gate nothing can cross and a gate that works produce exactly the same evidence: no
 * test, no log line, no crash. It was found by re-running the measurement.
 *
 * plan.md G1 rules out buying this observability (no crash SDK, no servers, no third-party
 * telemetry), so the only affordable substitute is a tally this device keeps for itself. That is
 * the whole design: **rows in a local table, read on one Settings row, never transmitted.**
 *
 * ### The shape is `(scope, bucket)` and not one row per event
 * The question the counter has to answer is "how did the last N decisions resolve?", not "what
 * happened at 14:03 on Tuesday?". A running tally answers the first in two rows and the second
 * never — and the second is not needed, because the thing worth noticing is a *rate* that has gone
 * to zero or gone implausibly high, not an individual decision that a reader can already see in
 * the app.
 *
 * `bucket` is stored as a `name()` string rather than an ordinal, for the reason `Entities.kt`
 * rule 3 gives for every other enum here: an ordinal is silently renumbered by a reorder, and this
 * table has to survive an app update with its history intact — it is the only record of whether the
 * feature ever ran.
 *
 * ### Why the dates are here
 * [firstRecordedOn] and [lastRecordedOn] exist so the screen can say "counting since 12 Oct" rather
 * than presenting a bare number as though it described the whole life of the install. A tally with
 * no stated window is a claim about all time that was never actually measured, which is the exact
 * shape of the dishonest figure plan.md §6.1 forbids elsewhere.
 *
 * Nothing here holds a symbol, a price, or a level: **no financial fact is persisted by this
 * table**, which is one of the reasons it is safe to display next to a "clear this" button.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
@Entity(
    tableName = "decision_counter",
    primaryKeys = ["scope", "bucket"],
)
data class DecisionCounterEntity(
    /** Which decision is being counted. See [DecisionScope]. */
    @ColumnInfo(name = "scope") val scope: String,

    /** How that decision resolved. See [DecisionBucket]. */
    @ColumnInfo(name = "bucket") val bucket: String,

    /** How many times this resolution has been observed. `Long` so a busy device cannot wrap it. */
    @ColumnInfo(name = "count") val count: Long,

    /** ISO-8601 date the bucket was first reached, or `null` for a row that has never been written. */
    @ColumnInfo(name = "firstRecordedOn") val firstRecordedOn: String?,

    /** ISO-8601 date this bucket was last reached, or `null` likewise. */
    @ColumnInfo(name = "lastRecordedOn") val lastRecordedOn: String?,
)

/**
 * The decisions this app counts, named.
 *
 * ### Why there are two and not one
 * A single "projection gate fired M times" figure cannot tell the two failure modes apart that
 * matter most:
 * * **the gate never fired** — A1, the feature is dead; and
 * * **the gate was never asked** — nothing has been scanned, or nothing survived detection, so the
 *   feature is not dead, it is simply untested by this device.
 *
 * Those look identical as one number. [PROJECTION_GATE] and [CANONICAL_SCAN] together say which
 * one it is, which is the only reason the second exists.
 *
 * [CANONICAL_SCAN] is named for `PatternScanOptions.isCanonicalScan` on purpose: the Pattern
 * Explorer's window re-scans are excluded, because counting those would let a reader inflate the
 * denominator by tapping through windows, and an inflatable denominator cannot be alarmed by.
 */
object DecisionScope {

    /** One projection-gate evaluation and how it resolved. The headline figure. */
    const val PROJECTION_GATE: String = "PROJECTION_GATE"

    /** One canonical scan that reached the gate stage at all. The denominator for the headline. */
    const val CANONICAL_SCAN: String = "CANONICAL_SCAN"

    /** Every scope this build counts, so a reader of the table can tell a new one from a typo. */
    val ALL: List<String> = listOf(PROJECTION_GATE, CANONICAL_SCAN)
}

/**
 * The buckets a [DecisionScope] can fall into.
 *
 * ### The projection-gate buckets are [ProjectionGate]'s own, plus one
 * [ProjectionGate.blocker] already returns the *first unmet condition*, and naming which one is the
 * entire value of recording: "the gate declined 900 times" is a shrug, while "900 times, all of
 * them because the level sat further than 5 % from the close" names a behaviour. The four blocker
 * names are reused verbatim rather than re-spelled here so this table cannot drift from the rule
 * that produced it.
 *
 * [FIRED] is the one name this app adds, because `blocker` returns `null` for a match that may
 * project and `null` is not something a `NOT NULL` TEXT column can hold. The mapping is
 * `null` → [FIRED] and it is made in exactly one place ([DecisionCounterDao.record]), so the
 * distinction cannot be lost.
 */
object DecisionBucket {

    /** The gate admitted the match: a level may be projected and its breakout alert may fire. */
    const val FIRED: String = "FIRED"

    /** A canonical scan reached the stage where the gate would have been asked. */
    const val SCAN_COMPLETED: String = "SCAN_COMPLETED"

    /** Every blocker [ProjectionGate] can report, as its `name()`. */
    val BLOCKERS: List<String> = ProjectionGate.Blocker.entries.map { it.name() }

    /** Every bucket a scan may fall into, so an absent row can be told from an impossible one. */
    val SCAN_BUCKETS: List<String> = listOf(SCAN_COMPLETED)

    /** Every bucket this build writes for any scope. */
    val ALL: List<String> = listOf(FIRED, SCAN_COMPLETED) + BLOCKERS
}