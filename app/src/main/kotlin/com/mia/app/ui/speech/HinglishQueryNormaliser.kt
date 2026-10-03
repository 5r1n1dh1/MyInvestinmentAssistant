package com.mia.app.ui.speech

import java.text.Normalizer

/**
 * The transliteration layer: recognised speech / typed Hinglish → an NSE ticker, or an honest miss.
 *
 * ### Why this exists at all
 * A Hindi speaker searching for Reliance Industries types `reliance`, and the on-device recogniser
 * configured for `hi-IN` returns the Devanagari script — which the symbol master cannot match.
 * This object takes either of those and produces the Latin form an NSE symbol is written in, by
 * mapping the small closed set of Romanised-Hindi and abbreviated forms that users actually type
 * into the tickers in [HinglishVocabulary].
 *
 * ### It is pure Kotlin on purpose
 * No Android import, no clock, no locale-dependent behaviour, no IO. It takes a `String` and
 * returns a value. That means it is unit-testable the moment a JVM test module exists, without an
 * emulator, without a device and without a recogniser — which is the only way the honesty rules
 * below can be *machine-checked* rather than reviewed by eye.
 *
 * ### The three honesty rules this object enforces
 * 1. **Never a silent wrong stock.** [normalise] resolves a symbol only for an unambiguous
 *    full-text match. Everything else returns candidates *and* the cleaned text, and
 *    [HinglishQuery.requiresConfirmation] is unconditionally `true`.
 * 2. **Never invent a ticker.** Nothing here derives a symbol by string surgery. A spoken form
 *    that is not in the table stays text.
 * 3. **Always explain what changed.** [HinglishQuery.notes] records every step, so
 *    "reliance dikhao" → `RELIANCE` is visible rather than magic.
 *
 * ### What normalisation does, in order
 * ```
 *  1. Unicode NFD + strip combining marks      "réliance"   -> "reliance"
 *  2. Devanagari digits -> ASCII digits        "१२३"        -> "123"
 *  3. Lowercase, collapse whitespace           "Reliance "  -> "reliance"
 *  4. Whole-phrase lookup of the vocabulary
 *  5. Drop voice-command words                 "reliance dikhao" -> "reliance"
 *  6. Weld single-letter runs                  "t c s"      -> "tcs"
 *  7. Phrase lookup, then word-by-word, then a 4+ character unique prefix
 * ```
 *
 * ### The limitation this version does **not** hide
 * Step 1 strips Devanagari characters, so a recogniser that returns `रिलायंस` leaves nothing to look
 * up. That is deliberate for v1: a full transliteration table is a dictionary project, and shipping a
 * wrong transliteration is exactly the silent-wrong-stock failure above. A Devanagari result
 * therefore degrades to the edit box with the original text intact and a note saying so
 * ([DEVANAGARI_NOTE]).
 */
object HinglishQueryNormaliser {

    /**
     * Normalises one phrase.
     *
     * @param raw what the recogniser returned, or what the user typed. May be blank.
     * @return never null. Blank input yields an empty query with `HinglishMatchKind.NONE`.
     */
    fun normalise(raw: String): HinglishQuery {
        if (raw.isBlank()) return HinglishQuery.empty()

        val notes = mutableListOf<String>()
        if (raw.any { it.isDevanagari() }) notes += DEVANAGARI_NOTE

        val folded = foldToLatin(raw)
        val cleaned = joinSpacedLetters(dropVoiceCommands(folded, notes))

        if (cleaned != folded) notes += "Command words and spaced letters were cleaned up."

        return buildQuery(raw = raw, cleaned = cleaned, notes = notes)
    }

    /**
     * Steps 1–3: a comparable Latin form.
     *
     * `Normalizer.NFD` rather than `NFKD`, so a legitimate compatibility character is not
     * decomposed into something the look-up would then half-match. Combining marks are dropped
     * next, which turns `é`, `ñ` and every Devanagari matra into their ASCII base — or into nothing
     * at all, which is what produces the honest [DEVANAGARI_NOTE] degradation.
     */
    private fun foldToLatin(input: String): String {
        val withoutMarks = Normalizer.normalize(input, Normalizer.Form.NFD)
            .filterNot { it.isCombiningMark() || it.isDevanagari() }
        val digitsMapped = StringBuilder(withoutMarks.length)
        withoutMarks.forEach { character ->
            if (character in DEVANAGARI_ZERO..DEVANAGARI_NINE) {
                digitsMapped.append('0' + (character - DEVANAGARI_ZERO))
            } else {
                digitsMapped.append(character)
            }
        }
        return digitsMapped.toString()
            .lowercase()
            .replace(NON_BREAKING_SPACE, ' ')
            .replace(AMPERSAND.toString(), " and ")
            .replace(PLUS, ' ')
            .replace(HYPHEN, ' ')
            .trim()
            .replace(WHITESPACE_RUN, " ")
    }

    /**
     * Step 5: drop the words a user says *to the assistant* rather than to the search box.
     *
     * The whole-phrase look-up runs **before** this sweep, so a spoken form that happens to
     * contain one of the command words still matched intact in step 4 and never reaches here.
     * That ordering is the safety property: the sweep can only affect text the table did not
     * recognise, so it can never damage a name it understood.
     */
    private fun dropVoiceCommands(input: String, notes: MutableList<String>): String {
        if (input.isBlank()) return input
        val stripped = tokensOf(input)
            .filterNot { token -> token in VOICE_COMMAND_WORDS }
            .joinToString(" ")
            .trim()
        if (stripped.isEmpty() || stripped == input) return input
        notes += "Words addressed to the assistant, such as \"dikhao\", were ignored."
        return stripped
    }

    /**
     * Step 6: `"t c s"` → `"tcs"`.
     *
     * Only for runs of **single-character** tokens, and only when the run is at least
     * [MIN_JOINED_LETTERS] long, so the English phrase "a b" is never welded into a ticker.
     */
    private fun joinSpacedLetters(input: String): String {
        val tokens = tokensOf(input)
        if (tokens.size < MIN_JOINED_LETTERS) return input

        val out = mutableListOf<String>()
        var pending = mutableListOf<String>()

        fun flush() {
            if (pending.size >= MIN_JOINED_LETTERS) {
                out += pending.joinToString("")
            } else {
                out += pending.toList()
            }
            pending = mutableListOf()
        }

        tokens.forEach { token ->
            if (token.length == 1) {
                pending += token
            } else {
                flush()
                out += token
            }
        }
        flush()
        return out.joinToString(" ")
    }

    /** Steps 4 and 7: build the candidates for an already-cleaned phrase. */
    private fun buildQuery(raw: String, cleaned: String, notes: MutableList<String>): HinglishQuery {
        if (cleaned.isBlank()) {
            return HinglishQuery(
                originalText = raw,
                cleanedText = "",
                candidates = emptyList(),
                notes = notes + NOTHING_LEFT_NOTE,
            )
        }

        // (a) The whole phrase is a known spoken form. The only EXACT case in the feature.
        HinglishVocabulary.lookup(cleaned)?.let { entry ->
            return HinglishQuery(
                originalText = raw,
                cleanedText = cleaned,
                candidates = listOf(entry.toCandidate(HinglishMatchKind.EXACT, cleaned)),
                notes = notes,
            )
        }

        // (b) A known phrase inside a longer utterance: "reliance ka kuch".
        findPhrase(cleaned)?.let { hit ->
            return HinglishQuery(
                originalText = raw,
                cleanedText = hit.matched,
                candidates = listOf(hit.toCandidate(HinglishMatchKind.SUGGESTED)),
                notes = notes + PARTIAL_PHRASE_NOTE,
            )
        }

        // (c) Single tokens: "airtel" inside "airtel ke share".
        val tokenMatches = tokensOf(cleaned).mapNotNull { token ->
            HinglishVocabulary.lookup(token)?.let { entry -> token to entry }
        }
        if (tokenMatches.isNotEmpty()) {
            val candidates = tokenMatches
                .distinctBy { (_, entry) -> entry.symbol }
                .map { (token, entry) -> entry.toCandidate(HinglishMatchKind.SUGGESTED, token) }
            return HinglishQuery(
                originalText = raw,
                cleanedText = cleaned,
                candidates = candidates,
                notes = notes + WORD_BY_WORD_NOTE,
            )
        }

        // (d) Prefix fallback: the user typed the first letters of a ticker. Requires a long enough
        // form, because three characters collide constantly in this market.
        if (cleaned.length >= MIN_PREFIX_MATCH_LENGTH) {
            val prefixes = HinglishVocabulary.tickers()
                .filter { ticker -> ticker.startsWith(cleaned, ignoreCase = true) && ticker != cleaned }
            if (prefixes.isNotEmpty()) {
                val candidates = prefixes.map { symbol ->
                    HinglishVocabulary.entryFor(symbol)
                        ?.toCandidate(HinglishMatchKind.SUGGESTED, cleaned)
                        ?: HinglishCandidate(
                            symbol = symbol,
                            companyName = null,
                            kind = HinglishMatchKind.SUGGESTED,
                            matchedWords = listOf(cleaned),
                        )
                }
                return HinglishQuery(
                    originalText = raw,
                    cleanedText = cleaned,
                    candidates = candidates,
                    notes = notes + if (candidates.size == 1) PREFIX_ONE_NOTE else PREFIX_MANY_NOTE,
                )
            }
        }

        // (e) No match. The text survives verbatim, which is the point: the search screen's own
        // symbol-master resolver is a better answer than anything this table could invent.
        return HinglishQuery(
            originalText = raw,
            cleanedText = cleaned,
            candidates = emptyList(),
            notes = notes + NO_MATCH_NOTE,
        )
    }

    /** The longest known spoken form inside [phrase]. Longer wins; ties resolve to the earliest. */
    private fun findPhrase(phrase: String): PhraseHit? {
        val words = tokensOf(phrase)
        if (words.size < 2) return null
        var best: PhraseHit? = null
        val maxLength = minOf(HinglishVocabulary.MAX_PHRASE_WORDS, words.size)
        for (length in maxLength downTo 2) {
            for (start in 0..(words.size - length)) {
                val slice = words.subList(start, start + length).joinToString(" ")
                val entry = HinglishVocabulary.lookup(slice) ?: continue
                val hit = PhraseHit(matched = slice, entry = entry)
                if (best == null || hit.matched.length > best.matched.length) best = hit
            }
        }
        return best
    }

    private fun tokensOf(phrase: String): List<String> =
        phrase.split(' ').filter { it.isNotBlank() }

    /** A vocabulary form found inside a longer phrase. */
    private data class PhraseHit(
        val matched: String,
        val entry: HinglishVocabulary.Entry,
    ) {
        fun toCandidate(kind: HinglishMatchKind): HinglishCandidate =
            entry.toCandidate(kind, matched)
    }

    /** Builds the candidate for a vocabulary entry, keeping the matched words visible. */
    private fun HinglishVocabulary.Entry.toCandidate(
        kind: HinglishMatchKind,
        matchedWord: String,
    ): HinglishCandidate = HinglishCandidate(
        symbol = symbol,
        companyName = companyName,
        kind = kind,
        matchedWords = listOf(matchedWord),
    )

    // ------------------------------------------------------------------------------- constants

    /**
     * The command words dropped before look-up.
     *
     * Romanised Hindi imperatives plus two English fillers. Every one of these is a word that
     * cannot be the whole spoken form of a name in the table, which is what makes dropping them
     * safe — see [dropVoiceCommands].
     */
    private val VOICE_COMMAND_WORDS: Set<String> = setOf(
        // Romanised Hindi
        "dikhao", "dikha", "dikhawa", "dikhaye", "dikhana", "dekhao",
        "batao", "bata", "bataye", "batana",
        "dekho", "dekh", "dekha", "dekhna",
        "karo", "kro", "kardo", "kar", "karna",
        "chalo", "chalta", "chalna",
        "dhundho", "dhundo", "dhundna",
        "khojo", "khoj", "khojna",
        "samjhao", "samjha",
        // English fillers
        "search", "find", "show", "please",
        "stock", "stocks", "share", "shares", "price", "prices",
    )

    /**
     * Shortest prefix that may produce a suggestion: four characters, because three collide
     * constantly in this market. Below this, a suggestion would be a coin toss.
     */
    private const val MIN_PREFIX_MATCH_LENGTH = 4

    /** Minimum single-letter run that may be welded together. */
    private const val MIN_JOINED_LETTERS = 2

    /** U+0966 DEVANAGARI DIGIT ZERO. */
    private const val DEVANAGARI_ZERO = '०'

    /** U+096F DEVANAGARI DIGIT NINE. */
    private const val DEVANAGARI_NINE = '९'

    /** The whole Devanagari block, U+0900..U+097F. */
    private val DEVANAGARI_RANGE = 'ऀ'..'ॿ'

    private val WHITESPACE_RUN = Regex("\\s+")
    private const val NON_BREAKING_SPACE = ' '
    private const val AMPERSAND = '&'
    private const val PLUS = '+'
    private const val HYPHEN = '-'

    private fun Char.isDevanagari(): Boolean = this in DEVANAGARI_RANGE

    private fun Char.isCombiningMark(): Boolean = when (Character.getType(this.code)) {
        Character.NON_SPACING_MARK.toInt(),
        Character.COMBINING_SPACING_MARK.toInt(),
        Character.ENCLOSING_MARK.toInt(),
        -> true

        else -> false
    }

    // ------------------------------------------------------------------------------- the notes

    /**
     * Note text, kept as named constants so the whole set is one reviewable list.
     *
     * Every one of these is **app-authored**, so a compliance scan over this file covers them, and
     * they never quote recogniser output — only [HinglishQuery.banner] does that, and it says so.
     */
    const val DEVANAGARI_NOTE: String =
        "The recogniser returned Devanagari script. This build does not transliterate it; type " +
            "the ticker in English letters instead."
    const val PARTIAL_PHRASE_NOTE: String =
        "Only part of what was heard named a company."
    const val WORD_BY_WORD_NOTE: String =
        "Company names were matched one word at a time."
    const val PREFIX_ONE_NOTE: String =
        "Matched by the first few letters of a ticker. Check which one."
    const val PREFIX_MANY_NOTE: String =
        "Several tickers start with those letters. Pick the one you meant."
    const val NO_MATCH_NOTE: String =
        "No ticker in the on-device list matched those words."
    const val NOTHING_LEFT_NOTE: String =
        "Nothing searchable was left after cleaning up the words."
}