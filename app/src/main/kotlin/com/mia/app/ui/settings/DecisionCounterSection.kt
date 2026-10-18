package com.mia.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mia.app.ui.common.SectionHeader
import com.mia.app.ui.theme.MiaSpacing

/**
 * Settings → Data coverage → the on-device decision counter — gap F3, planv2 §W10.
 *
 * ### What this card is for
 * A1 was a gate nothing could cross. It shipped, it installed, it drew charts, and **no test, no
 * crash and no log line could see it**, because the only evidence a dead gate produces is the
 * absence of the thing it was supposed to produce. plan.md G1 rules out buying observability, so
 * this card is the substitute: a tally this device keeps about its own behaviour, written on the
 * canonical scan path and read here.
 *
 * ### The three states, and why each one is drawn differently
 * A reader who opens this card is asking one of three questions, and all three have to be
 * answerable by looking at the card:
 * * **not measured yet** — nothing has been measured. Neutral, and says what would change that.
 * * **never asked** — scans ran, but no candidate ever reached the gate. This is a *detection*
 *   problem, not a gate problem, and conflating the two sends a reader looking in the wrong place.
 * * **not firing** — the A1 shape. Stated in words, in full, with the count.
 * * **working / firing more than measured** — the ordinary cases.
 *
 * ### Accessibility, and why each decision below is the one taken (gap F4)
 * * **The state is a word, not a colour.** [GateVerdict.label] is rendered as text and the card's
 *   tint is only an accent. A reader who cannot separate the palette, or who is in greyscale, still
 *   reads "not firing" — which is the entire point of the card.
 * * **The whole card is one TalkBack node.** `clearAndSetSemantics` replaces the ~40 nodes of
 *   heading, counts, rows and readings with one spoken paragraph. Read linearly, the individual rows
 *   are a list of numbers with no connective tissue — "12", "refused", "30 % of evaluations" — and
 *   the sentence that makes them mean anything is [DecisionCounterReport.verdictLine], which a
 *   screen reader would otherwise deliver somewhere in the middle of the list.
 * * **Font scaling cannot clip it.** Every figure is a `Row` whose label takes the remaining width
 *   and whose value is `wrapContentWidth`, so a 200 % font scale makes the row taller rather than
 *   truncating the number. No `sp` size is hard-coded anywhere in this file; every `Text` uses a
 *   `MaterialTheme.typography` style, which is what scales.
 * * **The touch target is 48 dp.** [MIN_TOUCH_TARGET] is applied with `heightIn` to the reset
 *   button as well as to the `OutlinedButton`'s own minimum, so the guarantee does not depend on the
 *   Material default holding across versions.
 *
 * @param report the tally, or `null` when it has not been read yet. `null` and "read as zero" are
 *   different states and are drawn differently: the former says it is still loading or that the
 *   read failed, the latter says the device has genuinely recorded nothing.
 * @param errorReason why the read failed, rendered **inside** this card so the rest of Settings —
 *   including the SEBI disclaimer — is unaffected.
 * @param isResetting `true` while a clear is in flight; disables the button so a double tap cannot
 *   produce two messages.
 */
@Composable
fun DecisionCounterSection(
    report: DecisionCounterReport?,
    errorReason: String?,
    isResetting: Boolean,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MiaSpacing.sm),
    ) {
        SectionHeader(
            title = COUNTER_HEADING,
            subtitle = COUNTER_SUBTITLE,
            modifier = Modifier.semantics { heading() },
        )

        if (errorReason != null) {
            Text(
                text = "$COUNTER_UNREADABLE $errorReason",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (report != null) {
            CounterBody(report)
        }

        Text(
            text = COUNTER_STORED_HERE,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Stated before the button, not after: the consequences of a destructive action belong
        // where the decision is made, not in a confirmation the reader only sees after committing.
        Text(
            text = COUNTER_RESET_EXPLAINED,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = onReset,
            enabled = !isResetting,
            modifier = Modifier
                .heightIn(min = MIN_TOUCH_TARGET)
                .testTag(COUNTER_RESET_TAG),
        ) {
            Text(text = if (isResetting) COUNTER_RESETTING else COUNTER_RESET)
        }
    }
}

/**
 * The figures themselves, as one spoken node.
 *
 * Spoken as a paragraph rather than as a table because that is the only order in which the numbers
 * mean anything: `n`, then the state, then the breakdown. A TalkBack user who heard the counts
 * without [DecisionCounterReport.verdictLine] would have exactly the ambiguity this card exists to
 * remove — "12" is not alarming until you know the gate has fired zero times.
 */
@Composable
private fun CounterBody(report: DecisionCounterReport, modifier: Modifier = Modifier) {
    val spoken = buildString {
        append(report.headline())
        append(" ")
        append(report.verdictLine())
        append(" ")
        append(report.scanLine())
        append(" ")
        append(report.windowLine())
        report.blockerRows().forEach { row ->
            append(" ")
            append("${row.name}: ${row.count}, ${row.meaning}. ${row.reading}")
        }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = spoken },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MiaSpacing.md),
            verticalArrangement = Arrangement.spacedBy(MiaSpacing.xs),
        ) {
            Text(
                text = report.headline(),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                // The word, in bold, on its own line. This is the claim; the card's tint beside it
                // is decoration. Never invert that order of importance.
                text = report.verdict.label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = report.verdictLine(),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = report.scanLine(),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = report.windowLine(),
                style = MaterialTheme.typography.labelSmall,
            )

            HorizontalDivider(
                modifier = Modifier.padding(vertical = MiaSpacing.xs),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            Text(
                text = COUNTER_BREAKDOWN_HEADING,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            report.blockerRows().forEach { row -> BlockerLine(row) }
        }
    }
}

/**
 * One condition, its count, and what that count means here.
 *
 * ### Why the number is on its own line and not beside the label
 * Because a `Row` with a fixed-width label column is the layout that clips at a 200 % font scale:
 * the label truncates with an ellipsis and the count — the thing the row exists for — moves
 * out of view. A stacked pair grows instead. The value takes the full width and the meaning sits
 * under it, so the longest string in this card (the insufficient-history explanation) wraps rather
 * than truncating at any scale.
 */
@Composable
private fun BlockerLine(row: BlockerRow, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = MiaSpacing.xs),
        verticalArrangement = Arrangement.spacedBy(MiaSpacing.xs),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            // `Arrangement.spacedBy` with no alignment override keeps the pair together at any
            // width; a `SpaceBetween` would push them to opposite edges on a wide screen and read
            // as two unrelated words.
            horizontalArrangement = Arrangement.spacedBy(MiaSpacing.sm),
        ) {
            Text(
                text = row.name,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.sizeIn(minWidth = 0.dp),
            )
            Text(
                text = row.count.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(
            text = row.meaning,
            style = MaterialTheme.typography.labelSmall,
        )
        Text(
            text = row.reading,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 48 dp, stated rather than inherited.
 *
 * The Material minimum for a button is 48 dp today, but "today" is not a guarantee this card gets
 * to keep, and an accessibility regression is exactly the kind of thing that arrives silently with
 * a dependency bump. Writing it down makes the promise reviewable — and it costs one modifier.
 */
private val MIN_TOUCH_TARGET = 48.dp

private const val COUNTER_HEADING = "Decision counter"
private const val COUNTER_SUBTITLE =
    "How often this device has asked the projection gate a question, and what it answered. " +
        "Counts are kept on this phone only."
private const val COUNTER_UNREADABLE = "The decision counts could not be read:"
private const val COUNTER_BREAKDOWN_HEADING = "What the gate decided"
private const val COUNTER_RESET = "Clear the counts"
private const val COUNTER_RESETTING = "Clearing…"
private const val COUNTER_RESET_EXPLAINED =
    "Clearing resets the counter to zero so it describes the next run rather than everything " +
        "since the app was installed. Nothing else is removed: your tracked symbols, stored bars, " +
        "detections and track record are untouched, and the counts cannot be recovered afterwards."
/** `private val` rather than `const` because it reads another object's constant. */
private val COUNTER_STORED_HERE: String = DecisionCounterReport.STORED_ON_THIS_DEVICE

/** Test tag on the clear button. */
const val COUNTER_RESET_TAG: String = "mia_decision_counter_reset"