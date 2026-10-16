package com.mia.app.domain.pattern

/**
 * Which of the textbook families a pattern belongs to.
 *
 * ### Why this is separate from `Category`
 * `Category` is a **scoring** input: it selects which penalties apply
 * (`PenaltyId.pointsFor(category)`), and changing it changes numbers. This is a **display**
 * taxonomy, free to be as granular as a reader needs.
 *
 * Keeping them apart means the catalogue can say "a wedge is a continuation" without the scorer
 * having to learn what a wedge is. Folding them together — the obvious-looking move — would couple
 * a vocabulary decision to a penalty table, and every catalogue addition would then require
 * re-testing the scorer.
 */
enum class PatternFamily(val label: String) {
    CANDLESTICK("Candlestick"),
    REVERSAL("Reversal"),
    CONTINUATION("Continuation"),
    BILATERAL("Bilateral / ranging"),
    VOLUME("Volume"),
}

/**
 * Whether the app can look for this pattern at all.
 *
 * ### Why the distinction from "not found" matters more than it looks
 * `NOT_IMPLEMENTED` means the app **never looked**. A reader shown that row must not read it as
 * "we checked and there is none here" — which is a claim about the data, not about the software.
 * Collapsing the two is how a catalogue becomes quietly dishonest, and it is why every row carries
 * one of these two states rather than a boolean.
 */
enum class DetectionStatus(val label: String) {
    /** A detector exists; the pattern is evaluated on real data and may match or fall short. */
    DETECTED("Evaluated on this stock"),

    /**
     * No detector yet. The row teaches the pattern and says plainly that the app does not look for
     * it — a gap in the software, stated as one.
     */
    NOT_IMPLEMENTED("Not yet looked for by the app"),
}

/**
 * One row of the pattern catalogue: everything the Explorer can show about a pattern that does not
 * depend on the stock being examined.
 *
 * The per-stock answer — did it match, at what score, over what window — is deliberately **not**
 * here. This is the stable half; `EvaluationOutcome` is the other.
 */
data class PatternSpec(
    /**
     * The canonical display name, matching `Match.name` exactly for every evaluated pattern.
     *
     * Matching is by name on purpose, not by a new id: `PatternIds.of` already keys on the
     * normalised name, so a catalogue row and a scan result join for free, and a rename would be
     * caught by `PatternCatalogTest` rather than silently splitting the two vocabularies.
     */
    val name: String,
    val family: PatternFamily,
    /** `true` when a detector exists and this app evaluates the pattern. */
    val detectionStatus: DetectionStatus,
    /**
     * The smallest bar count this pattern needs, in its own terms.
     *
     * Read as a **floor for teaching**, not as a promise: a detector's `minBars` is separate and
     * authoritative for evaluation, and the two happen to agree today for every evaluated row.
     */
    val minBars: Int,
    /** One line naming the shape. Describes geometry only — never a prediction, never advice. */
    val shapeSummary: String,
    /**
     * The idealised geometry, or [PatternShape.UNAVAILABLE] when none is authored yet.
     *
     * Only the 14 evaluated patterns carry a shape, and that is the honest starting position: a
     * shape is teaching material for a pattern the app can actually score. Authoring 50 overlays
     * for 36 patterns that are never evaluated would be content the reader cannot act on.
     */
    val shape: PatternShape = PatternShape.UNAVAILABLE,
    /**
     * A glossary entry. Present for every row, evaluated or not — a reader asking what a
     * `Marubozu` is should be answered even though the app cannot look for one.
     */
    val glossary: GlossaryEntry,
) {
    /** `true` when a shape can be drawn over the price chart for this pattern. */
    val hasShape: Boolean get() = shape != PatternShape.UNAVAILABLE

    /** `true` when this app evaluates the pattern on real data. */
    val isEvaluated: Boolean get() = detectionStatus == DetectionStatus.DETECTED
}

/**
 * The on-device explanation of one pattern.
 *
 * ### The rule every entry obeys
 * **No prediction language, ever.** plan.md §1.4 and §8.3 forbid claiming a probability of profit,
 * and a glossary is exactly where that temptation lives — it is prose *about* patterns, which
 * invites sentences like "signals a strong reversal".
 *
 * So an entry states: what the shape is, how it is drawn, what the textbook says the pattern is
 * *associated with*, and — the part that is easy to omit and the most useful — [doesNotPredict].
 * That last field is not a disclaimer bolted on: it is what stops a reader treating a named,
 * well-known formation as advice.
 *
 * ### Why [invalidation] is worth more than a better [doesNotPredict]
 * `gaps.md` E4: the glossary taught descriptions and stopped there. A disclaimer is **the same
 * sentence with the pattern's name swapped** — it tells the reader the app will not be held to an
 * outcome, which is true of all fifty rows and actionable for none. An invalidation condition is
 * the opposite: it is the one observation that would have to appear for this formation to be
 * wrong, which is exactly what a reader cannot get from the textbook, because the textbook defines
 * the shape and stops there.
 *
 * It also converts the pattern from a picture into a claim that can be graded. "A flag that drifts
 * up through its lower boundary was not a flag" is checkable on the next bar; "a flag is not a
 * guarantee" is not. Two rows of the catalogue sharing an invalidation sentence would show the
 * author had nothing pattern-specific to say, so `PatternCatalogTest` counts reuse.
 */
data class GlossaryEntry(
    /** What the pattern is, in one or two sentences. Geometry, not prediction. */
    val whatItIs: String,
    /** How it is recognised in a chart. */
    val howItLooks: String,
    /**
     * What the textbook associates the pattern with, stated as association and never as outcome.
     */
    val textbookSays: String,
    /**
     * What the pattern does not establish. Required, non-blank.
     *
     * Every pattern in the catalogue shares one honest answer — a named formation describes past
     * price action, not the future — but each entry should name its **specific** limitation too,
     * because "a flag is not a guarantee" is less useful than "a flag's measured pole has not yet
     * formed, so there is nothing to measure from".
     */
    val doesNotPredict: String,
    /**
     * What would prove this pattern wrong. Required, non-blank, **no default** (gaps.md E4).
     *
     * The invalidation condition belongs to the formation, so it is a property of the pattern
     * rather than a property of the app's posture: "a close back above the right shoulder's high"
     * is true about head & shoulders whether or not this app scores it. Giving it a default was
     * considered and rejected — `planv2.md` W6 initially proposed one, but a default is how a
     * field becomes optional in practice: the fiftieth row would inherit a generic sentence nobody
     * wrote, and the row would read as finished. Requiring it means every row states it, and
     * `PatternCatalogTest` counts how often a sentence is reused so "specific to this pattern"
     * is enforced rather than hoped for.
     *
     * One sentence is the unit: a reader has to be able to hold it in their head as "if I see
     * this, the pattern is wrong" without parsing three clauses about unrelated caveats.
     */
    val invalidation: String,
) {
    init {
        require(whatItIs.isNotBlank()) { "a glossary entry must say what the pattern is" }
        require(howItLooks.isNotBlank()) { "a glossary entry must say how to recognise it" }
        require(textbookSays.isNotBlank()) { "a glossary entry must say what the textbook says" }
        require(doesNotPredict.isNotBlank()) {
            "a glossary entry must state what the pattern does not establish (plan.md §1.4 rule 3)"
        }
        require(invalidation.isNotBlank()) {
            "a glossary entry must state what would prove the pattern wrong (gaps.md E4): a row " +
                "that cannot be wrong cannot be checked"
        }
    }
}