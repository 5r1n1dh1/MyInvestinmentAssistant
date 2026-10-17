package com.mia.app.domain.journal

import com.mia.app.domain.compliance.AdviceLanguage
import com.mia.app.domain.compliance.HonestyContract
import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.MatchScorePolicy
import java.time.LocalDate
import java.util.Locale

/**
 * The reader's own tally, and the honesty gate that applies to it (gap E5).
 *
 * ### The gate is the app's gate, deliberately and without exception
 * The app withholds any hit rate below [MatchScorePolicy.MIN_HIT_RATE_SAMPLES] — 30 — because a
 * percentage over three cases is a number that flatters itself (plan.md §6.1). There is no argument
 * for applying a different threshold to the reader's own record: it is the *same* class of mistake
 * (a rate over too few observations, printed where a reader will believe it), the only difference
 * is who did the guessing, and lowering the bar for the reader's number would be the app bending
 * its own standard precisely where the reader is most likely to take it at face value.
 *
 * So [MINIMUM_SAMPLES] is [MatchScorePolicy.MIN_HIT_RATE_SAMPLES], read from the same constant
 * rather than restated, and [PersonalRecord.label] returns **no percentage at all** below it.
 *
 * ### What a "match" means here, and what it does not
 * A settled entry counts as agreed when the direction the reader stated is the direction the stored
 * bars moved ([agreedWith]). That is a tally of two recorded facts and nothing more. It is **not**
 * a return, not a profit, not a measure of skill, and not a score against the reader:
 *
 * * it ignores the size of the move entirely — a 0.6 % rise and a 40 % rise count identically,
 *   which is why the count is a count and never a weighted return figure;
 * * it ignores transaction costs, entry and exit, which in India would change most of these counts;
 * * it ignores what the entry's own [JournalEntry.falsification] said, because a sentence is not a
 *   testable condition and scoring it would be the app interpreting the reader.
 *
 * Those three omissions are the price of a number that can be checked. They are stated on screen
 * ([UNSCORED_NOTE], [NOT_A_RETURN_NOTE]) rather than in this KDoc alone.
 */
object JournalLedger {

    /**
     * The one gate, read from the app's own policy so the two can never diverge.
     *
     * `const` rather than a computed `val`, which is what lets the copy below name the number inside
     * a `const val` — so the threshold a reader is *told* cannot drift from the threshold the gate
     * *enforces*.
     */
    const val MINIMUM_SAMPLES: Int = MatchScorePolicy.MIN_HIT_RATE_SAMPLES

    /**
     * Shown under a withheld tally.
     *
     * Counts up rather than down on purpose: "4 more entries" is something a reader can act on,
     * whereas a bare threshold is a rule with no shape. The threshold is still printed, because the
     * number is the honest reason for the silence.
     */
    const val WITHHELD_NOTE: String =
        "No rate is shown below $MINIMUM_SAMPLES settled entries. The count is the reason: a " +
            "percentage over a handful of cases is a statement about the handful, not about the " +
            "reading."

    /** What is left out of the tally and why. Rendered next to the withheld count. */
    const val UNSCORED_NOTE: String =
        "Entries recorded as 'no particular direction' are left out of the tally and counted " +
            "separately, because there is no stated direction to compare with what the price did."

    /** The three omissions above, in one sentence. Rendered under the rate when it is reportable. */
    const val NOT_A_RETURN_NOTE: String =
        "This counts whether the direction you wrote down is the direction the price then moved. " +
            "It is not a return, not a profit, not adjusted for cost, and it says nothing about " +
            "your reading skill or about the app's own track record."

    /** Entries that have an outcome recorded. The only ones a tally may be computed over. */
    fun settled(entries: List<JournalEntry>): List<JournalEntry> = entries.filter { it.isResolved }

    /** Entries whose review date has arrived and which still have no recorded outcome. */
    fun dueForReview(entries: List<JournalEntry>, today: LocalDate): List<JournalEntry> =
        entries.filter { it.isDueForReview(today) }

    /** Entries that are neither resolved nor yet due. Open is a state, not a failure. */
    fun open(entries: List<JournalEntry>, today: LocalDate): List<JournalEntry> =
        entries.filter { !it.isResolved && it.reviewDate.isAfter(today) }

    /** Why each due-but-unresolved entry is still open. Keyed by entry id, for the entry card. */
    fun openReasons(
        entries: List<JournalEntry>,
        candlesBySymbol: Map<String, List<Candle>>,
        settledSymbols: Set<String>,
        today: LocalDate,
    ): Map<Long, String> = dueForReview(entries, today).mapNotNull { entry ->
        val reason = JournalResolver.blockingReason(
            entry = entry,
            candles = candlesBySymbol[entry.symbol].orEmpty(),
            hasSettledOutcome = entry.symbol in settledSymbols,
            today = today,
        )
        reason?.let { entry.id to it }
    }.toMap()

    /**
     * Whether the observed move is the direction the reader stated.
     *
     * Mechanical and total, with three rules and no judgement in any of them:
     * * a non-scorable direction agrees with nothing — it is excluded from the denominator by
     *   [PersonalRecord.unscored] rather than counted as a miss;
     * * [JournalOutcome.FLAT] agrees with nothing, because a close that did not move is not the
     *   direction that was written down;
     * * anything else is compared to [ExpectationDirection.UP].
     */
    fun agreedWith(entry: JournalEntry): Boolean {
        val direction = entry.expectationDirection
        if (!direction.isScorable) return false
        return when (entry.resolutionOutcome) {
            JournalOutcome.ROSE -> direction == ExpectationDirection.UP
            JournalOutcome.FELL -> direction == ExpectationDirection.DOWN
            JournalOutcome.FLAT -> false
            null -> false
        }
    }

    /** The whole tally for one device, in one value. */
    fun record(entries: List<JournalEntry>, today: LocalDate): PersonalRecord {
        val settled = settled(entries)
        val scorable = settled.filter { it.expectationDirection.isScorable }
        return PersonalRecord(
            totalEntries = entries.size,
            settled = scorable.size,
            agreed = scorable.count { agreedWith(it) },
            unscored = settled.size - scorable.size,
            awaitingReview = dueForReview(entries, today).size,
            open = open(entries, today).size,
        )
    }
}

/**
 * One reader's own tally, always with the sample size that produced it.
 *
 * ### Why this is not a [com.mia.app.domain.model.HitRate]
 * `HitRate.label()` renders *"Hit rate 62.5% (n=40)"*, and that wording is regulated by
 * [com.mia.app.domain.compliance.Disclosure.HIT_RATE]: a hit is a pattern's **measured-move level
 * being reached** on this stock. Printing that phrase for a tally of the reader's own expectations
 * would apply a definition the number does not have — the app would be claiming its pattern
 * vocabulary for a different measurement. So the type is separate, the wording is separate, and the
 * *gate* is shared: [MINIMUM_SAMPLES] is read from [MatchScorePolicy.MIN_HIT_RATE_SAMPLES], which
 * is the part that must not drift.
 *
 * ### Why the numbers are counts first
 * [label] leads with "agreed in 17 of 30 resolved entries" rather than "56.7 %". A count cannot be
 * read as a rate before the reader has seen how many observations it rests on, which is exactly the
 * ordering plan.md §6.1 asks for; the percentage follows the two counts and never stands alone.
 *
 * @property settled resolved entries whose expectation stated a direction. The denominator.
 * @property agreed how many of those were followed by that direction.
 * @property unscored resolved entries with no stated direction. Reported, never averaged in.
 * @property awaitingReview entries past their review date with no outcome yet.
 * @property open entries whose review date has not arrived.
 */
data class PersonalRecord(
    val totalEntries: Int,
    val settled: Int,
    val agreed: Int,
    val unscored: Int,
    val awaitingReview: Int,
    val open: Int,
) {
    init {
        require(agreed <= settled) { "agreed ($agreed) cannot exceed settled ($settled)" }
        require(settled + unscored <= totalEntries) {
            "settled + unscored (${settled + unscored}) exceeds the number of entries ($totalEntries)"
        }
    }

    /** The bar below which no rate is reported. Read from the policy, so it cannot drift. */
    val minimumSamples: Int get() = JournalLedger.MINIMUM_SAMPLES

    /** True only at or above the gate. */
    val isReportable: Boolean get() = settled >= minimumSamples

    /** The percentage, or `null` below the gate — so it cannot be rendered by accident. */
    val ratePercent: Double? get() = if (!isReportable || settled <= 0) null else agreed * 100.0 / settled

    /** Settled entries still needed before any rate may be quoted at all. */
    val samplesUntilReportable: Int get() = (minimumSamples - settled).coerceAtLeast(0)

    /**
     * The one string this screen may print for the tally.
     *
     * Below the gate it contains **no percentage** — only the count and the distance to the gate.
     * At or above it, the percentage is printed with `n=`, and the string is run through the same
     * three checks every other user-facing number in this app is run through, so a future edit that
     * dropped `n`, grew advice-like wording or implied a probability would fail here rather than on
     * a user's phone.
     */
    fun label(): String {
        val text = if (isReportable) {
            val pct = String.format(Locale.ROOT, "%.1f", ratePercent ?: 0.0)
            "Your recorded expectations and the observed moves agreed in $agreed of $settled " +
                "resolved entries ($pct%, n=$settled)."
        } else {
            // "samples", not "entries": `HonestyContract.requireHitRateSampleSize` below checks for
            // that word, and the word it checks for is the right one — this is a count of settled
            // observations, the same quantity the app's own rate is gated on.
            "No rate yet — $settled of $minimumSamples samples settled. " +
                "${samplesUntilReportable} more before any percentage is printed."
        }
        HonestyContract.requireHitRateSampleSize(text)
        HonestyContract.requireNotProbabilityClaim(text)
        AdviceLanguage.requireClean(text)
        return text
    }

    /** The withheld state in words, for the panel that shows no rate. */
    fun withheldNote(): String {
        val text = if (isReportable) {
            "$agreed of $settled recorded expectations were followed by the direction that was " +
                "written down."
        } else {
            "No rate is shown below $minimumSamples settled entries. This one has $settled, so " +
                "${samplesUntilReportable} more are needed before any percentage is printed."
        }
        AdviceLanguage.requireClean(text)
        return text
    }

    companion object {
        /** A device that has written nothing yet. */
        val EMPTY: PersonalRecord = PersonalRecord(0, 0, 0, 0, 0, 0)
    }
}