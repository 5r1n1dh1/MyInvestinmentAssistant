package com.mia.app.data.android.repo

import com.mia.app.domain.journal.JournalEntry
import com.mia.app.domain.model.HitRate
import com.mia.app.domain.model.Source
import java.time.LocalDate

/**
 * What one resolution pass did to the reader's journal (gap E5).
 *
 * ### Why it mirrors [AccuracyPass]
 * For the same reason and not out of symmetry for its own sake: both objects exist so a screen can
 * say *what the last pass actually changed*, rather than implying the record is maintained
 * continuously. [stillOpen] is the field that matters most — an entry sitting open with no reason is
 * indistinguishable from a broken app.
 *
 * @property resolved entries this pass closed. Never revised afterwards.
 * @property stillOpen entries that are past their review date but could not be closed, each with the
 *   gate that is still shut.
 */
data class JournalPass(
    val resolved: Int,
    val stillOpen: Int,
    val asOf: LocalDate,
    val openReasons: List<JournalOpenReason> = emptyList(),
) {
    fun summary(): String =
        "$resolved entry(s) resolved as of $asOf, $stillOpen still awaiting a measurable window"
}

/**
 * One entry that is past its review date and could not be closed, with the gate that is still shut.
 *
 * @property entryId so the screen can put the sentence on that entry's own card. An open entry with
 *   a reason somewhere else on the screen is an open entry the reader has to go looking for.
 * @property reason [com.mia.app.domain.journal.JournalResolver]'s own sentence, passed through
 *   unchanged. This layer does not write its own explanations: it is the one that had the candles
 *   and the settled-outcome flag in hand when it decided, and it is the only layer that can say
 *   *which* of the four gates is shut.
 */
data class JournalOpenReason(
    val entryId: Long,
    val symbol: String,
    val reason: String,
)

/**
 * The only way `:app` reaches the reader's own journal (gap E5).
 *
 * ### The rules this interface exists to keep
 * 1. **The reader's words are written once and never edited.** There is no update method, only
 *    [record] and [delete]. An entry that can be rewritten after the fact is not a record of what
 *    was believed then, and a personal track record built from rewritten beliefs measures nothing.
 * 2. **A falsification condition is mandatory.** `JournalDraft` cannot produce an entry without one
 *    and the DAO has no column that could hold a blank; see
 *    `com.mia.app.domain.journal.JournalPolicy`.
 * 3. **Resolution goes through the app's own outcome machinery.** [resolveDue] is a no-op until the
 *    engine has settled at least one of its own outcomes for the symbol — the journal never
 *    invents a second definition of "the review period has passed". See `JournalResolver`.
 * 4. **Nothing here is a recommendation.** No method takes or returns a view on what the reader
 *    should hold, and [record] returns what was stored, not a confirmation that it was wise.
 */
interface JournalRepository {

    /**
     * Every entry on the device, newest first.
     *
     * An empty list is `Ok`: a reader who has written nothing is a real state, and the screen's
     * empty state says so with a reason rather than as a failure.
     */
    suspend fun entries(today: LocalDate = todayInMarket()): DataResult<List<JournalEntry>>

    /** One entry, or a typed failure when the id is not on this device. */
    suspend fun entry(id: Long, today: LocalDate = todayInMarket()): DataResult<JournalEntry>

    /** Symbols with at least one entry, sorted. For the journal's own coverage line. */
    suspend fun symbols(): DataResult<List<String>>

    /**
     * Writes one entry.
     *
     * The symbol is normalised (trimmed, upper-cased) here as well as in `JournalDraft`, because
     * this is the last point before storage and a `reliance` row would split one reader's history
     * across two symbols in every query that follows.
     *
     * @return the stored entry, with the row id the database assigned.
     */
    suspend fun record(entry: JournalEntry, today: LocalDate = todayInMarket()): DataResult<JournalEntry>

    /**
     * Removes one entry the reader wrote.
     *
     * Present because a journal a reader cannot delete is a ledger imposed on them. There is no bulk
     * delete, so "remove my notes" is never one tap away from "remove everything".
     */
    suspend fun delete(id: Long, today: LocalDate = todayInMarket()): DataResult<Int>

    /**
     * Closes every entry whose review date has passed, against the stored bars.
     *
     * Reads the app's own `outcomes` to decide whether the record has moved on, and the stored
     * candles for the move itself — so the personal record and the app's track record settle on the
     * same sessions and can never disagree about what "now" means.
     *
     * Idempotent by construction: a closed entry is skipped, and the write query refuses to touch a
     * row that already carries an outcome.
     */
    suspend fun resolveDue(today: LocalDate = todayInMarket()): DataResult<JournalPass>

    /**
     * The app's own track record, aggregated across every pattern and every stock.
     *
     * ### Why the journal exposes this at all
     * plan.md §W6 asks for the reader's tally to be shown *beside* the app's own rate. "Beside"
     * only works if both numbers are on the same surface, and putting the app's figure in a separate
     * data source would let the two screens drift apart in wording and in gating — which is exactly
     * how a reader ends up treating one as evidence about the other.
     *
     * The aggregate is built from `HitRateCalculator.perPattern`, which partitions every resolved
     * outcome across pattern names, so summing its hits and samples is an exact total with no
     * double counting. It is handed to the UI as a [HitRate], never as a
     * pre-formatted percentage, so `HitRate.label()`'s 30-sample gate and `n=` are applied by the
     * same code that guards the Accuracy tab.
     */
    suspend fun appTrackRecord(today: LocalDate = todayInMarket()): DataResult<AppTrackRecord>

    /**
     * Symbols with at least one **settled** engine outcome.
     *
     * ### Why this is on the interface
     * `JournalResolver` refuses to close an entry until the engine has settled an outcome for the
     * same symbol, so something has to answer that question for the UI's own "why is this still
     * open" sentences. Exposing it here — rather than having the adapter infer it from
     * [appTrackRecord], which carries no symbols — is what keeps that sentence true: it is derived
     * from the engine's own `resolved` flag, so it can never claim a symbol is settled when the
     * Accuracy tab would show it as open.
     *
     * An empty set is a real answer, not a failure: it is the state of a device that has tracked no
     * patterns, and every open entry's reason then says exactly that.
     */
    suspend fun settledOutcomeSymbols(today: LocalDate = todayInMarket()): DataResult<Set<String>>

    /** Provenance for every journal read: the reader's own words, typed on this device. */
    companion object {
        /** Where a journal entry came from. Never a market source — a person wrote it. */
        val SOURCE: Source = Source.DERIVED
    }
}

/**
 * The app's own track record, as the journal screen shows it.
 *
 * @property hitRate the aggregate, so the screen renders it through `HitRate.label()` and cannot
 *   print a percentage without `n` or below the gate.
 * @property settledOutcomes resolved outcomes behind it. `0` when the app has tracked nothing, in
 *   which case [HitRate.samples] is `0` and the label is the withheld one.
 * @property isEmpty true when nothing has been tracked at all, so the journal can say "the app has
 *   no record on this device either" instead of showing a withheld 0 %.
 */
data class AppTrackRecord(
    val hitRate: HitRate,
    val settledOutcomes: Int,
) {
    val isEmpty: Boolean get() = settledOutcomes == 0

    companion object {
        /** The app has tracked nothing. A withheld 0/0, which is what `HitRate.label()` renders. */
        val EMPTY: AppTrackRecord = AppTrackRecord(
            hitRate = HitRate(symbol = null, patternName = null, hits = 0, samples = 0),
            settledOutcomes = 0,
        )
    }
}