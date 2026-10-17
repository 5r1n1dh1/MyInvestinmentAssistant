package com.mia.app.domain.journal

import com.mia.app.domain.compliance.AdviceLanguage
import com.mia.app.domain.model.Timeframe
import java.time.LocalDate

/**
 * One thing a person believed about one stock, written **before** the outcome was known (gap E5).
 *
 * ### Why the app needs this at all
 * plan.md §6 publishes the *engine's* record. It says nothing about the reader: it cannot tell a
 * person whether the way they read a chart matches the way it turned out, because the app has no
 * record of what they thought. The journal is that record, and it is the only surface in the app
 * where the reader's own words are the primary datum.
 *
 * ### The three rules that shape this type
 *
 * 1. **The four fields are separate because they do separate jobs.** [thesis] is a claim about the
 *    business, [expectation] is a claim about price, [falsification] is the condition that would
 *    make the reader wrong, and [reviewDate] is when they intend to check. Collapsing them into one
 *    "notes" box is what turns a journal into a diary: a diary is read after the fact and rewritten
 *    by memory, which is the one thing that makes a personal track record worthless.
 * 2. **[falsification] is required, with no default.** See [JournalPolicy.whyFalsificationIsRequired].
 * 3. **[resolutionOutcome] is a measurement, never a verdict.** See [JournalOutcome].
 *
 * ### Why there is no foreign key to a symbol
 * A journal entry is the reader's own words about a company. It must survive the company leaving
 * the device's symbol master, a demerger (plan.md §9) or a cleared cache, because the one thing it
 * records that nothing else can recover is *what the reader believed at the time*. So `symbol` is a
 * plain column and the storage layer keeps no reference to `symbols.symbol` — deliberately the
 * opposite of `patterns`, whose geometry is meaningless without the bars it was drawn from.
 *
 * @property id `0` for a row that has not been written yet; the storage layer assigns the rest.
 * @property expectationDirection the **structured** half of [expectation]. See the field KDoc: it
 *   exists because a free-text expectation cannot be scored, and scoring one by reading the words
 *   would be fabrication rather than measurement.
 * @property resolvedAt the session the outcome was measured from, or `null` while the entry is open.
 * @property resolutionOutcome what the stored bars showed. `null` until it is resolved.
 * @property resolutionChangePercent the measured move, as a percentage of the close on
 *   [createdAt]. Carried beside the outcome so the column always has its evidence with it and the
 *   UI never has to re-derive it from bars that may since have been refetched.
 */
data class JournalEntry(
    val id: Long = 0L,
    val symbol: String,
    val timeframe: Timeframe,
    /** What the reader believes about the company, in their words. */
    val thesis: String,
    /** What the reader expects price to do, in their words. */
    val expectation: String,
    val expectationDirection: ExpectationDirection,
    /** The condition that would prove the entry wrong. Required — see [JournalPolicy]. */
    val falsification: String,
    /** When the reader intends to revisit it. Never in the past at the moment of writing. */
    val reviewDate: LocalDate,
    val createdAt: LocalDate,
    val resolvedAt: LocalDate? = null,
    val resolutionOutcome: JournalOutcome? = null,
    val resolutionChangePercent: Double? = null,
) {
    init {
        require(symbol.isNotBlank()) { "a journal entry must name the stock it is about" }
        require(thesis.isNotBlank()) { "a journal entry must state what is believed about the company" }
        require(expectation.isNotBlank()) { "a journal entry must state what is expected of price" }
        require(falsification.isNotBlank()) {
            "a journal entry without a falsification condition cannot be wrong, and therefore cannot " +
                "be right either (see JournalPolicy.whyFalsificationIsRequired)"
        }
        require(!reviewDate.isBefore(createdAt)) {
            "a review date cannot precede the entry's own creation date: " +
                "createdAt=$createdAt reviewDate=$reviewDate"
        }
        // Resolution is written once and carries its evidence: a resolved entry always has both the
        // outcome and the measured move, and an open entry has neither. This is the journal-side
        // copy of OutcomeTracker's "a verdict is written once" rule, and it holds even if a caller
        // resolves the same entry twice with different bars.
        require((resolvedAt == null) == (resolutionOutcome == null)) {
            "resolvedAt and resolutionOutcome must be set together: " +
                "resolvedAt=$resolvedAt outcome=$resolutionOutcome"
        }
        require((resolutionOutcome == null) == (resolutionChangePercent == null)) {
            "resolutionOutcome and resolutionChangePercent must be set together: " +
                "outcome=$resolutionOutcome change=$resolutionChangePercent"
        }
        require(resolvedAt == null || !resolvedAt.isBefore(createdAt)) {
            "an outcome cannot predate the entry that produced it: " +
                "createdAt=$createdAt resolvedAt=$resolvedAt"
        }
    }

    /** True once an outcome has been recorded. A resolution is never revised afterwards. */
    val isResolved: Boolean get() = resolutionOutcome != null

    /** True when the review date has arrived but no outcome could be recorded yet. */
    fun isDueForReview(today: LocalDate): Boolean = !isResolved && !reviewDate.isAfter(today)

    /**
     * The one-sentence statement of what was recorded, for the entry card.
     *
     * Deliberately a description of the data and not an assessment of the reader: it names the
     * direction, the measured move and the two sessions the move was measured between, and stops
     * there. A card that said "right call" here would be the app grading a person, which is both
     * outside its remit (plan.md §1.4) and the thing that makes a personal track record agreeable
     * rather than informative.
     */
    fun resolutionText(): String? {
        val outcome = resolutionOutcome ?: return null
        val resolvedOn = resolvedAt ?: return null
        val move = resolutionChangePercent ?: return null
        val from = formatIso(createdAt)
        val to = formatIso(resolvedOn)
        val text = "${outcome.label} — a move of ${formatPercent(move)} between the close on " +
            "$from and the close on $to, measured from stored ${timeframe.name} bars. This is a " +
            "record of what the price did, not a judgement about the entry."
        AdviceLanguage.requireClean(text)
        return text
    }

    private fun formatIso(date: LocalDate): String = date.toString()

    private fun formatPercent(value: Double): String = String.format(java.util.Locale.ROOT, "%+.1f%%", value)
}

/**
 * The direction a reader's expectation points, as a structured value.
 *
 * ### Why the expectation needs a structured half at all
 * [JournalEntry.expectation] is free text, and free text cannot be scored: reading "I expect it to
 * grind up if the promoter pledge is resolved" and deciding whether that happened would be the
 * app guessing at meaning, and a guess scored at 100 % would be indistinguishable on screen from
 * a measurement. So the reader states the direction **separately**, as one of three values, and the
 * tally below counts only that. [EITHER_WAY] exists because "I expect this to be interesting
 * whatever it does" is a perfectly good entry — it just cannot be scored, and the tally says how
 * many entries were left out rather than quietly folding them into the denominator.
 *
 * The three values are named after the direction of the expected price move and nothing else. No
 * member of this enum is a recommendation, and none of them is a forecast the app makes.
 */
enum class ExpectationDirection(val label: String) {
    /** The reader expects price to be higher than it was when they wrote the entry. */
    UP("Higher than when I wrote this"),

    /** The reader expects price to be lower than it was when they wrote the entry. */
    DOWN("Lower than when I wrote this"),

    /**
     * The reader expects no particular direction.
     *
     * Kept rather than dropped because refusing to record such an entry would push the reader to
     * invent a direction they do not hold, which is the one thing a falsification discipline is
     * meant to prevent. Such entries are excluded from the tally and counted separately.
     */
    EITHER_WAY("No particular direction"),
    ;

    /** True when this direction can be scored against an observed move. */
    val isScorable: Boolean get() = this != EITHER_WAY
}

/**
 * What the stored bars showed between an entry's creation and its resolution.
 *
 * ### Every member is a statement about the price, never about the reader
 * There is deliberately no `CORRECT`, no `WRONG` and no `VINDICATED` member, and no member that
 * compares the move to the expectation. Three names, all of which can be printed without knowing
 * anything about the reader's expectation, is the shape that makes it impossible for a resolution
 * column to read as vindication — the comparison happens once, in aggregate, in [PersonalRecord],
 * where the denominator and the sample size are on screen with it.
 *
 * [FLAT] is a named band rather than an exact comparison because exact equality is not a thing a
 * price does: [JournalResolver.FLAT_BAND_PERCENT] is the width, it is stated on screen with the
 * measured move, and the measured percentage is always carried beside the label so the band never
 * hides a number.
 */
enum class JournalOutcome(val label: String) {
    /** The close on the resolution session was above the close the reader wrote the entry against. */
    ROSE("The close rose"),

    /** The close on the resolution session was below the close the reader wrote the entry against. */
    FELL("The close fell"),

    /** The move was inside the flat band, so the close is materially where it started. */
    FLAT("The close is materially unchanged"),
}

/**
 * The rules a journal entry must satisfy, as pure functions — no IO, no Android, no clock read
 * that is not a parameter (plan.md §4.3).
 *
 * ### Why falsification is **required**, not encouraged
 * An entry without a falsification condition cannot be wrong. And an entry that cannot be wrong
 * cannot be right either, which means it contributes nothing to any tally — it is decoration that
 * looks like discipline. Worse, it is the exact failure the review process is supposed to catch: a
 * reader who writes "this compounder keeps compounding" can accommodate any outcome at all, and a
 * journal that accepted that sentence would be recording unfalsifiable beliefs and calling the
 * result a track record.
 *
 * So it is required at two levels, deliberately:
 * * [JournalDraft.isComplete] — the UI cannot enable "save" without it, and says why;
 * * [JournalEntry]'s own `init` — the storage layer cannot write a row without it, so a future
 *   screen, a migration or an import cannot route around the first level.
 *
 * What is *not* required is any particular wording, length or shape. The app states the principle
 * and refuses the blank; it does not grade the sentence.
 */
object JournalPolicy {

    /**
     * The sentence shown in place of a missing falsification.
     *
     * It states the requirement, not a verdict: the app is refusing to record an entry it could
     * never score, and the reader is the only one who can supply the condition.
     */
    const val FALSIFICATION_REQUIRED: String =
        "Write the condition that would prove this entry wrong. Without it the entry cannot be " +
            "wrong, so it cannot be scored either way, and a personal record built from unscorable " +
            "entries would measure nothing."

    /** Shown beside the field while it is empty. Same rule as [FALSIFICATION_REQUIRED], shortened. */
    const val FALSIFICATION_HINT: String =
        "What would prove you wrong? A price level, a published number, a dated event."

    /** Shortest falsification the app will store, in characters, whitespace collapsed. */
    const val MIN_FALSIFICATION_CHARS: Int = 10

    /** Shortest thesis or expectation the app will store. */
    const val MIN_TEXT_CHARS: Int = 10

    /**
     * The number the entry must be scored against, stated to the reader as a choice of unit.
     *
     * None of the three is a level, and the phrasing is the point: a reader who writes "below ₹900"
     * has written a **condition**, and the app compares it against what the stored bars did rather
     * than pretending to know what the condition was worth.
     */
    const val CONDITION_UNIT_NOTE: String =
        "A condition, not a level the app will act on. Write the level you had in mind; the app " +
            "records the move that happened and leaves the comparison to you."

    /** True when [text] is substantive enough to store, ignoring surrounding whitespace. */
    fun isSubstantive(text: String, minimumChars: Int = MIN_TEXT_CHARS): Boolean =
        text.trim().length >= minimumChars

    /** The copy the UI shows when a field is too short to store, or `null` when it is fine. */
    fun tooShortReason(text: String, minimumChars: Int = MIN_TEXT_CHARS, field: String): String? =
        if (isSubstantive(text, minimumChars)) {
            null
        } else {
            "$field needs at least $minimumChars characters, so it is a sentence rather than a " +
                "single word."
        }
}

/**
 * What the reader has typed, before it is an entry.
 *
 * ### Why a draft is its own type and not a [JournalEntry] with blank fields
 * A half-typed form is a legitimate state that must be *representable* — holding it in a
 * `JournalEntry` would mean either dropping the `require`s that protect the stored row, or
 * constructing an invalid object on every keystroke. So the draft carries no `init` requirements at
 * all, and [blockingReason] is what the form asks: one sentence, or `null` when it may be saved.
 *
 * The save path still ends at [toEntry], which re-checks everything, so the draft's permissiveness
 * cannot become a row that violates [JournalEntry]'s invariants.
 */
data class JournalDraft(
    val symbol: String,
    val timeframe: Timeframe,
    val thesis: String,
    val expectation: String,
    /** `null` until the reader picks one. Not defaulted: silence is not a direction. */
    val direction: ExpectationDirection?,
    val falsification: String,
    /** `null` until the reader picks a date. */
    val reviewDate: LocalDate?,
) {
    /** The symbol as it will be stored: trimmed and upper-cased, like every other ticker in the app. */
    val normalisedSymbol: String get() = symbol.trim().uppercase()

    /**
     * The reason this draft cannot be saved, or `null` when it can.
     *
     * Ordered by how load-bearing the field is, so the message a reader sees first is the one about
     * the field that makes the entry meaningful rather than the one they happened to leave last.
     */
    fun blockingReason(): String? = when {
        normalisedSymbol.isBlank() -> "Enter the NSE symbol this entry is about."
        reviewDate == null -> "Choose the date you intend to review this entry on."
        !JournalPolicy.isSubstantive(thesis) ->
            "Write what you believe about the company, in a sentence."
        !JournalPolicy.isSubstantive(expectation) ->
            "Write what you expect price to do, in a sentence."
        direction == null -> "Choose which way you expect price to move, or 'no particular direction'."
        !JournalPolicy.isSubstantive(falsification, JournalPolicy.MIN_FALSIFICATION_CHARS) ->
            JournalPolicy.FALSIFICATION_REQUIRED
        else -> null
    }

    /** True when [blockingReason] is `null`. */
    val isComplete: Boolean get() = blockingReason() == null

    /**
     * The stored entry.
     *
     * @param createdAt today at the moment of saving, passed in rather than read so the value is
     *   reproducible in a test and cannot disagree with the `reviewDate` a caller displayed.
     * @throws IllegalArgumentException when the draft is incomplete. The UI asks
     *   [blockingReason] first; this is the backstop that keeps the stored row valid whatever a
     *   future caller does.
     */
    fun toEntry(createdAt: LocalDate, id: Long = 0L): JournalEntry {
        val reason = blockingReason()
        require(reason == null) { reason ?: "incomplete draft" }
        return JournalEntry(
            id = id,
            symbol = normalisedSymbol,
            timeframe = timeframe,
            thesis = thesis.trim(),
            expectation = expectation.trim(),
            expectationDirection = requireNotNull(direction),
            falsification = falsification.trim(),
            reviewDate = requireNotNull(reviewDate),
            createdAt = createdAt,
        )
    }
}