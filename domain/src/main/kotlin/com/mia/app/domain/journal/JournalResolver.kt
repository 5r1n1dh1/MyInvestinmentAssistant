package com.mia.app.domain.journal

import com.mia.app.domain.accuracy.OutcomeTracker
import com.mia.app.domain.model.Candle
import java.time.LocalDate
import kotlin.math.abs

/**
 * Turning an open journal entry into a recorded outcome — gap E5, on the one resolution path that
 * already exists.
 *
 * ### Why this is not a second way for outcomes to settle
 * plan.md §6.1 has exactly one resolution path, [OutcomeTracker], and three rules make it
 * trustworthy: a verdict is written once, it is written only when the whole window is in hand, and
 * it may not be computed from a bar dated after today. A journal that grew its own, looser
 * resolver would be a second path with none of those guarantees — and the personal rate is the
 * number a reader is most likely to trust, so it is the last place to relax them.
 *
 * So this object borrows the rules rather than re-deciding them, and adds the one thing the app's
 * own outcomes cannot answer — *what did the price do after this person wrote this down*:
 *
 * 1. **The app's own outcome machinery must have settled something for this symbol**
 *    ([hasSettledOutcome]). The journal does not decide when the record has moved on; the same
 *    table that settles the engine's verdicts does it, so a journal entry and the track record on
 *    the Accuracy tab can never disagree about whether "now" has arrived.
 * 2. **The reader's own review date must have passed.** This is the only genuinely new gate, and it
 *    is the reader's: the app will not close a book before its date.
 * 3. **The bars are selected by [OutcomeTracker]'s own date rules.** Same symbol, same timeframe,
 *    none dated after today. [OutcomeTracker.horizonWindow] also excludes the projection bar
 *    itself, and this does the same thing: the close the entry was written against is the baseline,
 *    not part of the move.
 * 4. **A resolution is written once.** [resolve] returns an already-resolved entry untouched, so a
 *    second nightly pass with more bars cannot move `ROSE` to `FELL`. This is the same structural
 *    guarantee [OutcomeTracker.evaluate] gives, reached the same way — by the early return rather
 *    than by a flag the caller must remember to check.
 *
 * ### What it deliberately does not do
 * It never reads [JournalEntry.falsification] and never asks whether the reader's condition was
 * met. That sentence is prose; deciding what it meant would be the app interpreting a person, and a
 * tally built on its interpretation would be untestable and unarguable. The falsification is shown
 * back to the reader next to the measured move, and the comparison is theirs.
 *
 * Pure functions, no IO, and `today` is a parameter so every resolution is reproducible in a test.
 */
object JournalResolver {

    /**
     * Half-width of the "materially unchanged" band, in percent of the baseline close.
     *
     * ### Why a band at all
     * An exact equality test would classify a stock that moved 0.02 % as having "risen", and the
     * resulting tally would be a statement about floating-point noise rather than about anything a
     * reader wrote. The band is a named constant rather than a magic number at the comparison site
     * because it is a *choice*, and a choice the reader is entitled to see: [resolutionNote] prints
     * it beside every [JournalOutcome.FLAT], and the measured percentage is carried beside the
     * label, so the band narrows a word and never hides a number.
     *
     * Half a percent is roughly one day's noise on a liquid Indian large cap. It is **not** a
     * materiality threshold for any decision, and nothing in the app treats a move inside it as
     * unimportant — it only decides which of three factual labels to print.
     */
    const val FLAT_BAND_PERCENT: Double = 0.5

    /**
     * The bar the entry's move is measured **from**: the last stored close on or before
     * [JournalEntry.createdAt].
     *
     * `null` when the device holds no bar for that session yet — the reader wrote the entry from a
     * chart, so the bar they were looking at is either stored or it is not, and there is no honest
     * substitute. [blockingReason] says so rather than reaching for the first available close, which
     * would silently rebase the entry onto a different day.
     */
    fun baseline(entry: JournalEntry, candles: List<Candle>): Candle? = candles
        .asSequence()
        .filter { it.symbol == entry.symbol && it.timeframe == entry.timeframe }
        .filter { !it.date.isAfter(entry.createdAt) }
        .maxByOrNull { it.date }

    /**
     * The bar the move is measured **to**: the last stored close on or before the review date, never
     * after [today], and **strictly after** [JournalEntry.createdAt].
     *
     * The review date rather than today is the point: the entry is about the window the reader
     * chose, and resolving it a week later must not quietly extend that window to whatever the
     * device happens to hold now.
     *
     * The strict `isAfter` is [OutcomeTracker]'s third date rule applied here — the bar the entry was
     * written against is the baseline and never part of the move. Without it an entry written
     * between two sessions, or one whose review date falls before its first stored bar after the
     * entry, would be "resolved" by comparing a close with itself and be recorded as a 0.0 % move.
     */
    fun resolutionBar(entry: JournalEntry, candles: List<Candle>, today: LocalDate): Candle? = candles
        .asSequence()
        .filter { it.symbol == entry.symbol && it.timeframe == entry.timeframe }
        .filter { it.date.isAfter(entry.createdAt) }
        .filter { !it.date.isAfter(entry.reviewDate) }
        .filter { !it.date.isAfter(today) }
        .maxByOrNull { it.date }

    /**
     * Records what the stored bars showed, when — and only when — all four gates in the class KDoc
     * are satisfied.
     *
     * Returns [entry] unchanged in every other case, which is what "not yet" means here: the
     * [blockingReason] for the same inputs names the gate that is still shut.
     */
    fun resolve(
        entry: JournalEntry,
        candles: List<Candle>,
        hasSettledOutcome: Boolean,
        today: LocalDate,
    ): JournalEntry {
        if (blockingReason(entry, candles, hasSettledOutcome, today) != null) return entry
        val from = baseline(entry, candles) ?: return entry
        val to = resolutionBar(entry, candles, today) ?: return entry
        // A baseline close of zero or less is a bar this app would never have stored, and dividing by
        // it would produce a number that looks like a move. Unresolvable beats fabricated.
        val move = changePercent(from.close, to.close) ?: return entry
        return entry.copy(
            resolvedAt = to.date,
            resolutionOutcome = outcomeOf(move),
            resolutionChangePercent = move,
        )
    }

    /**
     * Why [entry] has no recorded outcome yet, or `null` when it does (or already has one).
     *
     * Every answer is a sentence the UI can show verbatim, because an entry stuck "open" forever
     * with no reason is indistinguishable from a bug — the same argument `DataResult` makes for
     * every other read in this app.
     */
    fun blockingReason(
        entry: JournalEntry,
        candles: List<Candle>,
        hasSettledOutcome: Boolean,
        today: LocalDate,
    ): String? {
        if (entry.isResolved) return null
        if (entry.reviewDate.isAfter(today)) {
            return "Review date ${entry.reviewDate} has not arrived yet."
        }
        if (!hasSettledOutcome) {
            return "The app has not settled any of its own pattern outcomes for ${entry.symbol} " +
                "yet, so there is no session on this device that a review can be measured against. " +
                "The entry stays open until one settles."
        }
        if (baseline(entry, candles) == null) {
            return "No ${entry.symbol} bar on or before ${entry.createdAt} is stored on this " +
                "device, so there is no close to measure the entry from."
        }
        if (resolutionBar(entry, candles, today) == null) {
            return "No ${entry.symbol} ${entry.timeframe.name} bar is stored between ${entry.createdAt} " +
                "and ${entry.reviewDate} yet, so the window this entry asked about has not been " +
                "observed."
        }
        val baselineClose = baseline(entry, candles)?.close ?: 0.0
        if (baselineClose <= 0.0) {
            return "The stored ${entry.symbol} close on or before ${entry.createdAt} is not a " +
                "usable price, so no move can be measured from it."
        }
        return null
    }

    /**
     * The three factual labels, from one measured percentage.
     *
     * Written in terms of [isFlat] rather than as two separate comparisons, because the band has to
     * be **inclusive at both ends** and two hand-written inequalities are how a resolver ends up
     * labelling a −0.5 % move "fell" while `isFlat` calls the same number unchanged — two
     * functions, one threshold, one answer. `JournalRecordTest` asserts the boundary from both
     * sides so the two cannot drift apart again.
     */
    fun outcomeOf(changePercent: Double): JournalOutcome = when {
        isFlat(changePercent) -> JournalOutcome.FLAT
        changePercent > 0.0 -> JournalOutcome.ROSE
        else -> JournalOutcome.FELL
    }

    /** `(to − from) / from × 100`, or `null` when the baseline close is unusable. */
    fun changePercent(fromClose: Double, toClose: Double): Double? =
        if (fromClose <= 0.0) null else (toClose - fromClose) / fromClose * 100.0

    /** True when [move] is inside the flat band. Exposed so the screen can say so in words. */
    fun isFlat(move: Double): Boolean = abs(move) <= FLAT_BAND_PERCENT

    /**
     * The footnote attached to every resolved entry.
     *
     * Names the band and the two sessions, so the label is auditable: a reader who thinks
     * "unchanged" should have been "rose" can see the number and the threshold and disagree with
     * the app on the evidence rather than on the wording.
     */
    fun resolutionNote(entry: JournalEntry): String {
        val resolvedOn = entry.resolvedAt ?: return ""
        return "Measured from the stored ${entry.timeframe.name} closes on ${entry.createdAt} and " +
            "$resolvedOn. A move inside $FLAT_BAND_PERCENT% is labelled unchanged; the measured " +
            "percentage is shown above, so the label never replaces the number."
    }
}