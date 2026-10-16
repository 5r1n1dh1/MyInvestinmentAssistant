package com.mia.app.ui.share

import com.mia.app.domain.compliance.Disclosure
import com.mia.app.domain.model.MatchScoreLabels
import com.mia.app.domain.model.Source
import com.mia.app.ui.common.NOT_AVAILABLE
import com.mia.app.ui.common.UiStrings
import com.mia.app.ui.common.displayName
import com.mia.app.ui.common.formatDate
import com.mia.app.ui.common.formatPrice
import com.mia.app.ui.common.formatSignedPercent
import com.mia.app.ui.common.shortLabel
import com.mia.app.ui.theme.DOWN_GLYPH
import com.mia.app.ui.theme.FLAT_GLYPH
import com.mia.app.ui.theme.UP_GLYPH

/**
 * The exact text of a shared brief, as data.
 *
 * ### This file is the compliance surface of P11b
 * A share image escapes the app: it lands in a WhatsApp thread, a group chat or a screenshot. That
 * makes it the copy most likely to be quoted out of context and the copy least able to be
 * corrected, so the rules it follows are stricter than the on-screen ones:
 *
 * 1. **No advice language anywhere, including in the footer** — not a softened version, not a
 *    euphemism. The strings for what a score is and what a projected level is come from
 *    [MatchScoreLabels] and [Disclosure] and are used verbatim, so a later edit to this file cannot
 *    introduce a phrase the domain's own compliance test already forbids.
 * 2. **Every figure carries a source and an as-of date, on the image** — not in the chat message
 *    that accompanied it, which the receiving app may drop.
 * 3. **The score is labelled as shape fit in the same line as the number**, so a cropped screenshot
 *    of the score still says what it is.
 * 4. **The measured move is labelled geometry**, with the not-a-level caveat on the image.
 * 5. **The two outputs are never combined.** The score and the geometry level live in different
 *    sections and neither is multiplied by, added to or annotated with the hit rate. plan.md §6.1
 *    exists because those two numbers get conflated; sharing them on one line would invite it.
 * 6. **A hit rate keeps its `n`, or its withheld count.** [com.mia.app.domain.model.HitRate.label]
 *    enforces that and this file uses nothing else.
 *
 * ### Why it is a flat list of [BriefLine] rather than a sealed tree
 * The renderer must measure before it can lay anything out, and a flat list measures in one pass.
 * It also means the whole image's text can be printed as a list of strings, which is how it was
 * reviewed against plan.md §1.4 — see `plainText`.
 */
object BriefCardContent {

    /** The lines of a brief, in draw order. Never empty. */
    fun lines(card: BriefCard): List<BriefLine> = buildList {
        addAll(header(card))
        addAll(priceSection(card))
        topMatchSection(card)?.let { addAll(it) }
        geometrySection(card)?.let { addAll(it) }
        breakoutSection(card)?.let { addAll(it) }
        trackRecordSection(card)?.let { addAll(it) }
        redFlagSection(card)?.let { addAll(it) }
        notesSection(card)?.let { addAll(it) }
        addAll(footer())
    }

    /** Convenience for review and for a future test: the image's text with no styling attached. */
    fun plainText(card: BriefCard): List<String> = lines(card).map { it.text }

    // --------------------------------------------------------------------------------- header

    private fun header(card: BriefCard): List<BriefLine> = buildList {
        add(BriefLine(Disclosure.APP_NAME, BriefLineRole.APP_NAME))
        add(
            BriefLine(
                text = if (card.companyName.isNullOrBlank()) {
                    card.symbol
                } else {
                    "${card.symbol} · ${card.companyName}"
                },
                role = BriefLineRole.TITLE,
            ),
        )
        add(
            BriefLine(
                text = "${card.timeframe.shortLabel()} end-of-day analysis · education only",
                role = BriefLineRole.SUBTITLE,
            ),
        )
    }

    // ---------------------------------------------------------------------------------- price

    private fun priceSection(card: BriefCard): List<BriefLine> = buildList {
        add(BriefLine(SECTION_PRICE, BriefLineRole.SECTION))
        add(row(LAST_CLOSE, formatPrice(card.lastClose)))
        add(
            row(
                DAY_CHANGE,
                if (card.changePercent == null) {
                    NOT_AVAILABLE
                } else {
                    moveGlyph(card.changePercent) + " " + formatSignedPercent(card.changePercent)
                },
            ),
        )
        add(
            row(
                AS_OF,
                if (card.asOf == null) {
                    "$NOT_AVAILABLE (no stored bar)"
                } else {
                    formatDate(card.asOf)
                },
            ),
        )
        add(row(SOURCE, card.priceSource.displayName()))
        if (card.barCount > 0) {
            val gate = if (card.minimumBars > 0) " $SEPARATOR ${card.minimumBars} needed" else ""
            add(row(BARS_STORED, "${card.barCount} ${card.timeframe.name} bars stored$gate"))
        }
    }

    // ----------------------------------------------------------------------- pattern and score

    private fun topMatchSection(card: BriefCard): List<BriefLine>? {
        val match = card.topMatch ?: return null
        return buildList {
            add(BriefLine(SECTION_PATTERN, BriefLineRole.SECTION))
            add(BriefLine(match.name, BriefLineRole.FIGURE_STRONG))
            add(row(MatchScoreLabels.TITLE, "${match.score} of 100 $SEPARATOR ${bandLabel(match.score)}"))
            // The meaning sits directly under the number, so a cropped screenshot of the score
            // cannot be read as anything other than geometric fit.
            add(BriefLine(MatchScoreLabels.SUBTITLE, BriefLineRole.CAVEAT))
            add(BriefLine(Disclosure.SCORE_MEANING, BriefLineRole.CAVEAT))
            match.breakdown?.let { breakdown ->
                add(
                    row(
                        FACTORS,
                        "geometry ${breakdown.geometry} $SEPARATOR structure ${breakdown.structure} " +
                            "$SEPARATOR confirmation ${breakdown.confirmation} " +
                            "$SEPARATOR context ${breakdown.context}",
                    ),
                )
            }
        }
    }

    // ------------------------------------------------------------------------------- geometry

    private fun geometrySection(card: BriefCard): List<BriefLine>? {
        val move = card.measuredMove ?: return null
        val asOfText = card.asOf?.let { " $SEPARATOR ${formatDate(it)}" } ?: ""
        return buildList {
            add(BriefLine(MatchScoreLabels.PROJECTION_LABEL, BriefLineRole.SECTION))
            add(row(FORMATION_HEIGHT, formatPrice(move.height)))
            add(row(TRIGGER_LEVEL, formatPrice(move.triggerLevel)))
            add(row(GEOMETRY_LEVEL, formatPrice(move.projectedLevel)))
            add(
                row(
                    DIRECTION,
                    if (move.isUpward) "Upward from the trigger" else "Downward from the trigger",
                ),
            )
            add(BriefLine(MatchScoreLabels.NOT_A_TARGET_WARNING, BriefLineRole.CAVEAT))
            add(BriefLine(Disclosure.MEASURED_MOVE, BriefLineRole.CAVEAT))
            // Every derived figure inherits the provenance of the bars it was computed from.
            add(
                BriefLine(
                    text = "$DERIVED_NOTE ${card.priceSource.displayName()}$asOfText",
                    role = BriefLineRole.CAVEAT,
                ),
            )
        }
    }

    // ------------------------------------------------------------------------------- breakout

    private fun breakoutSection(card: BriefCard): List<BriefLine>? {
        val trigger = card.breakoutTrigger ?: return null
        return buildList {
            add(BriefLine(SECTION_BREAKOUT, BriefLineRole.SECTION))
            add(BriefLine(trigger.description, BriefLineRole.FIGURE_STRONG))
            add(row(TRIGGER_LEVEL, formatPrice(trigger.level)))
            add(
                row(
                    CONDITION_STATE,
                    if (trigger.fired) CONDITION_MET else CONDITION_NOT_MET,
                ),
            )
            add(BriefLine(CONDITION_NOTE, BriefLineRole.CAVEAT))
        }
    }

    // -------------------------------------------------------------------------- track record

    private fun trackRecordSection(card: BriefCard): List<BriefLine>? {
        val perPattern = card.trackRecord ?: return null
        val patternName = card.topMatch?.name ?: return null
        return buildList {
            add(BriefLine(SECTION_TRACK_RECORD, BriefLineRole.SECTION))
            add(BriefLine("$patternName on ${card.symbol}", BriefLineRole.FIGURE_STRONG))
            // `HitRate.label()` withholds below MatchScorePolicy.MIN_HIT_RATE_SAMPLES and always
            // carries `n`. It is the only wording this file uses for a rate.
            add(BriefLine(perPattern.label(), BriefLineRole.FIGURE_STRONG))
            card.trackRecordForSymbol?.let { forSymbol ->
                add(row(EVERY_PATTERN_ON_THIS_STOCK, forSymbol.label()))
            }
            add(BriefLine(Disclosure.HIT_RATE, BriefLineRole.CAVEAT))
        }
    }

    // ------------------------------------------------------------------------------ red flags

    private fun redFlagSection(card: BriefCard): List<BriefLine>? {
        if (card.redFlags.isEmpty()) return null
        return buildList {
            add(BriefLine(SECTION_RED_FLAGS, BriefLineRole.SECTION))
            card.redFlags.forEach { flag ->
                add(
                    BriefLine(
                        text = flag.severity.name.lowercase().replace('_', ' ') +
                            " $SEPARATOR ${flag.title}",
                        role = BriefLineRole.FLAG,
                    ),
                )
                add(row(RED_FLAG_THRESHOLD, flag.threshold))
                flag.evidence.forEach { evidence ->
                    val asOfText = evidence.asOf?.let { " $SEPARATOR ${formatDate(it)}" }
                        ?: " $SEPARATOR $NOT_AVAILABLE"
                    add(
                        row(
                            evidence.label,
                            "${evidence.value} (${evidence.source.displayName()}$asOfText)",
                        ),
                    )
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------- notes

    private fun notesSection(card: BriefCard): List<BriefLine>? {
        if (card.notes.isEmpty()) return null
        return buildList {
            add(BriefLine(SECTION_NOTES, BriefLineRole.SECTION))
            card.notes.forEach { note -> add(BriefLine("$BULLET $note", BriefLineRole.CAVEAT)) }
        }
    }

    // --------------------------------------------------------------------------------- footer

    /**
     * The regulated block.
     *
     * All four disclosures, verbatim, on the image itself. The SEBI statement comes first and
     * unconditionally, so it cannot be cropped away by a share target that renders only the top of
     * the image.
     */
    private fun footer(): List<BriefLine> = listOf(
        BriefLine(RULE_TEXT, BriefLineRole.RULE),
        BriefLine(Disclosure.SETTINGS, BriefLineRole.FOOTER),
        BriefLine(Disclosure.MEASURED_MOVE, BriefLineRole.FOOTER),
        BriefLine(Disclosure.HIT_RATE, BriefLineRole.FOOTER),
        BriefLine(Disclosure.DATA_SOURCE, BriefLineRole.FOOTER),
    )

    // -------------------------------------------------------------------------------- helpers

    /** A `label` / `value` row. Built in one place so every block's columns are identical. */
    private fun row(label: String, value: String): BriefLine =
        BriefLine(text = "$label$SEPARATOR $value", role = BriefLineRole.FIGURE)

    /**
     * The word beside a score, read from the same source the screens use.
     *
     * A shared image outlives the session it was made in, so the band and its cut live in
     * `UiStrings` rather than being restated here: a card that said "strong fit" at 70 while the
     * Patterns screen said something else at the same number would be two answers to one question,
     * and the flat bitmap has no other way to explain itself.
     */
    private fun bandLabel(score: Int): String = UiStrings.fitBand(score)

    /** The direction glyph, so a sign in a flat image is never carried by colour alone. */
    private fun moveGlyph(changePercent: Double): String = when {
        changePercent > 0.0 -> UP_GLYPH
        changePercent < 0.0 -> DOWN_GLYPH
        else -> FLAT_GLYPH
    }
}

/**
 * One line of the image.
 *
 * @param role decides the type size, weight and colour in `renderBriefBitmap`. Roles carry no
 *   meaning of their own — every word is in [BriefLine.text] and comes from `Disclosure`,
 *   `MatchScoreLabels`, `HitRate.label()` or this package's labels.
 */
data class BriefLine(
    val text: String,
    val role: BriefLineRole,
)

/**
 * The visual roles a brief line can play.
 *
 * @property bold whether the role renders in the bold typeface. Given as an explicit flag rather
 *   than inferred from a per-role name, so a role cannot silently drop it.
 */
enum class BriefLineRole(
    /** Type size as a fraction of the renderer's body size. */
    val sizeFactor: Float,
    val bold: Boolean,
    /** ARGB. Flat colours: a shared image is viewed outside the app's theme. */
    val color: Int,
) {
    /** The app's own name, so a shared image is attributable. */
    APP_NAME(0.80f, false, GREY),

    /** `RELIANCE · Reliance Industries`. */
    TITLE(1.60f, true, INK),

    /** Timeframe, and the "education only" statement. */
    SUBTITLE(0.80f, false, GREY),

    /** A block heading: `PRICE`, `PATTERN GEOMETRY`, `BREAKOUT CONDITION`, … */
    SECTION(0.90f, true, ACCENT),

    /** The one figure a block is about. */
    FIGURE_STRONG(0.95f, true, INK),

    /** A label/value pair. */
    FIGURE(0.82f, false, INK),

    /** A red flag and its severity. */
    FLAG(0.86f, true, FLAG_INK),

    /** A caveat, a definition or a provenance note. */
    CAVEAT(0.72f, false, GREY),

    /** A horizontal rule, drawn as the [RULE_TEXT] glyph run. */
    RULE(0.60f, false, RULE_INK),

    /** The regulated footer. */
    FOOTER(0.66f, false, GREY),
}

// ------------------------------------------------------------------------------- image labels
//
// Every string below is a **label**, never a claim about the future or about the company. None can
// be read as a conclusion: "Last close" is a measurement, "Pattern geometry" is the domain's own
// name for a projected level, and "Breakout condition" names a condition rather than an event.

private const val SECTION_PRICE = "PRICE"
private const val SECTION_PATTERN = "PATTERN — SHAPE FIT ONLY"
private const val SECTION_BREAKOUT = "BREAKOUT CONDITION"
private const val SECTION_TRACK_RECORD = "TRACK RECORD ON THIS STOCK"
private const val SECTION_RED_FLAGS = "RED FLAGS IN THE DATA CHECKED"
private const val SECTION_NOTES = "WHAT THE SCAN COULD NOT MEASURE"

private const val LAST_CLOSE = "Last close"
private const val DAY_CHANGE = "Change vs previous close"
private const val AS_OF = "As of"
private const val SOURCE = "Source"
private const val BARS_STORED = "Bars stored"
private const val FACTORS = "Per-factor scores"
private const val FORMATION_HEIGHT = "Formation height"
private const val TRIGGER_LEVEL = "Trigger level"
private const val GEOMETRY_LEVEL = "Geometry level"
private const val DIRECTION = "Direction"
private const val CONDITION_STATE = "State on stored bars"
private const val CONDITION_MET = "This condition has been met."
private const val CONDITION_NOT_MET = "This condition has not been met."

/** Describes what a breakout alert watches for, without promising it. */
private const val CONDITION_NOTE =
    "An alert is scheduled only when a close satisfies this condition. A poke through the level " +
        "during the session is not one."

private const val EVERY_PATTERN_ON_THIS_STOCK = "Every tracked pattern on this stock"
private const val RED_FLAG_THRESHOLD = "Threshold"
private const val DERIVED_NOTE = "Projected from the stored bars above. Derived on device, source:"

/** Separator inside a label/value row. */
private const val SEPARATOR = "·"

/** Bullet for the transparency notes. */
private const val BULLET = "•"

/** The rule between the body and the regulated footer. A glyph run, so it needs no draw code. */
private val RULE_TEXT: String = buildString { repeat(RULE_GLYPHS) { append(RULE_GLYPH) } }

private const val RULE_GLYPH = '─'
private const val RULE_GLYPHS = 30

// ARGB colours. Deliberately not the app theme's: a shared image is read in a chat client with its
// own background, so the palette is fixed light-on-dark-free rather than theme-derived.
private const val INK = 0xFF14181F.toInt()
private const val GREY = 0xFF525A66.toInt()
private const val ACCENT = 0xFF1F4E8C.toInt()
private const val FLAG_INK = 0xFF8C2318.toInt()
private const val RULE_INK = 0xFFB9C0C9.toInt()