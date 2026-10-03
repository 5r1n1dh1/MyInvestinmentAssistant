package com.mia.app.ui.speech

import com.mia.app.domain.compliance.AdviceLanguage
import com.mia.app.domain.compliance.HonestyContract

/**
 * How a ticker was arrived at. Never a probability, never a confidence percentage.
 *
 * ### Why an enum and not a score
 * The temptation with a fuzzy matcher is to emit `0.87` and a progress bar. That number would be a
 * probability claim about something the user cares about — *which stock* — and plan.md §1.4 rule 1
 * plus [HonestyContract] forbid exactly that shape of wording anywhere in this app. A three-value
 * enum says exactly as much as the user can act on and cannot be mistaken for a likelihood.
 */
enum class HinglishMatchKind {
    /**
     * The spoken text, after normalisation, **is** this ticker or this company's name.
     *
     * Still requires the user to submit: this is a guess, and the recogniser guesses.
     */
    EXACT,

    /**
     * A vocabulary entry matched part of the text, or a prefix matched. The user chose between
     * several, so the app must show the alternatives rather than pick one.
     */
    SUGGESTED,

    /**
     * Nothing matched. The text is kept verbatim and the user types or corrects it.
     */
    NONE,
}

/**
 * One ticker the normaliser thinks the user may have meant.
 *
 * @param matchedWords the words in the transcript that produced this candidate, verbatim. Shown to
 *   the user so they can see *why* the app proposed it — a suggestion with no visible reason is a
 *   suggestion the user has to trust blindly.
 * @param companyName the name from the vocabulary, when it has one. Rendered beside the ticker.
 */
data class HinglishCandidate(
    val symbol: String,
    val companyName: String?,
    val kind: HinglishMatchKind,
    val matchedWords: List<String>,
) {
    /** `RELIANCE — Reliance Industries` when a name is known, otherwise just the ticker. */
    val displayLabel: String
        get() = if (companyName.isNullOrBlank()) symbol else "$symbol — $companyName"

    /**
     * The sentence under the suggestion.
     *
     * Worded as a possibility the user checks, never as a recognition the app is confident about:
     * the recogniser can and does return the wrong word, and a UI that says "heard X" pushes the
     * user to trust a result that is, by construction, a guess.
     */
    val explanation: String
        get() = when (kind) {
            HinglishMatchKind.EXACT ->
                "\"${matchedWords.joinToString(" ")}\" matches this ticker. Check it before searching."

            HinglishMatchKind.SUGGESTED ->
                "\"${matchedWords.joinToString(" ")}\" partly matches this ticker. " +
                    "Check it before searching."

            HinglishMatchKind.NONE ->
                "No ticker matched. Type the NSE symbol instead."
        }
}

/**
 * The result of normalising one recognised phrase.
 *
 * ### The one rule this type exists to enforce
 * [resolvedSymbol] is only ever populated when [kind] is
 * [HinglishMatchKind.EXACT] **and** exactly one candidate was found. There is no code path that
 * turns a *suggested* list into a single symbol, because choosing between "tata motors" and "tata
 * power" on the user's behalf is precisely the silent-wrong-stock failure this feature must not
 * have. When in doubt the field is `null` and the caller shows the alternatives.
 *
 * @param originalText the recogniser's raw output, kept so the user can see what was heard.
 * @param cleanedText [originalText] after normalisation. This is what populates the search box.
 * @param candidates never empty unless [kind] is [HinglishMatchKind.NONE].
 * @param notes the normalisation steps that changed the text, rendered as a small caption. Shown
 *   so "reliance dikhao" becoming "RELIANCE" is explained rather than mysterious.
 */
data class HinglishQuery(
    val originalText: String,
    val cleanedText: String,
    val candidates: List<HinglishCandidate>,
    val notes: List<String>,
) {
    /** The overall verdict. `EXACT` only for an unambiguous full-text match. */
    val kind: HinglishMatchKind
        get() = when {
            candidates.isEmpty() -> HinglishMatchKind.NONE
            candidates.size == 1 && candidates.single().kind == HinglishMatchKind.EXACT ->
                HinglishMatchKind.EXACT

            else -> HinglishMatchKind.SUGGESTED
        }

    /**
     * The single ticker the caller may pre-fill — and only then.
     *
     * `null` for a suggestion list of any length, including a single *partial* match, so that a
     * partial match is never silently upgraded to a decision.
     */
    val resolvedSymbol: String?
        get() = if (kind == HinglishMatchKind.EXACT) candidates.single().symbol else null

    /**
     * Whether the caller must show the edit box.
     *
     * **Always `true`**, by design. Recognition is a guess on every device, in every language, and
     * a UI that can skip the edit step will eventually skip it on the one phrase that matters. The
     * field exists so that a future on-device recogniser with a per-utterance confidence can be
     * honoured *without* changing any call site.
     */
    val requiresConfirmation: Boolean = true

    /**
     * The text to put in the search box.
     *
     * The cleaned text when it exists, else the resolved ticker, else the original. The last case
     * is the important one: a phrase the normaliser could not improve is still better than a blank
     * box, because the user can see and fix what was heard.
     */
    val searchText: String
        get() = if (cleanedText.isNotBlank()) {
            cleanedText
        } else {
            // `resolvedSymbol` is a computed property, so a `when` branch cannot smart-cast it to a
            // non-null `String`; the elvis keeps the whole expression a `String`.
            resolvedSymbol ?: originalText
        }

    /**
     * The banner under the field. Never claims certainty.
     *
     * ### Why no compliance assertion runs on this string
     * [originalText] is **recogniser output** — untrusted, arbitrary input the app did not author.
     * A user who says a word the recogniser renders as one of `AdviceLanguage.FORBIDDEN` would make
     * `requireClean` throw from inside a `get`, crashing the screen over a transcription the app
     * never wrote. The app-authored half is instead checked **once**, in the companion object's
     * initialiser, against a probe built from the same constants.
     */
    val banner: String
        get() = buildString {
            append(HEARD_PREFIX)
            append('"')
            append(originalText.trim())
            append("\". ")
            append(CANNOT_CONFIRM)
            if (notes.isNotEmpty()) {
                append(" ")
                append(notes.joinToString(" "))
            }
        }

    /** Just the recogniser's words, for the edit box's label. Never a claim about them. */
    val heardLabel: String get() = "$HEARD_PREFIX\"${originalText.trim()}\""

    companion object {
        /**
         * Appended to every recognition banner.
         *
         * "Voice recognition reads the words it thinks it heard and often gets Indian names and
         * company names wrong. Read the text and change it before searching."
         *
         * This is not filler. Every offline recogniser for `hi-IN`/`en-IN` in the wild returns
         * English-looking company names as something adjacent, and a user who searches the wrong
         * ticker gets a real screen about the wrong stock — which reads as the app being wrong
         * rather than the recogniser.
         */
        const val CANNOT_CONFIRM: String =
            "Voice recognition reads the words it thinks it heard and often gets Indian names " +
                "and company names wrong. Read the text and change it before searching."

        /** An empty query, for "no recognition yet". */
        fun empty(): HinglishQuery = HinglishQuery(
            originalText = "",
            cleanedText = "",
            candidates = emptyList(),
            notes = emptyList(),
        )

        /** Prefix before the recognised words. Quotation marks are part of it. */
        const val HEARD_PREFIX: String = "Heard: \""

        /**
         * One-shot self-check of the app-authored copy in this file.
         *
         * The probe substitutes a placeholder for the recogniser's output, so what is asserted is
         * exactly the text this app wrote. It runs when the companion is first touched, which is
         * before any of the copy can reach a screen.
         */
        private val PROBE: String = "$HEARD_PREFIX\".\". $CANNOT_CONFIRM"

        init {
            AdviceLanguage.requireClean(PROBE)
            HonestyContract.requireNotProbabilityClaim(PROBE)
        }
    }
}