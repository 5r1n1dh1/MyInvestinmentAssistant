package com.mia.app.ui.patterns

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.mia.app.domain.model.MatchScorePolicy
import com.mia.app.domain.pattern.EvaluationOutcome
import com.mia.app.domain.pattern.GlossaryEntry
import com.mia.app.domain.pattern.PatternCatalog
import com.mia.app.domain.pattern.PatternEvaluation
import com.mia.app.ui.theme.MiaSpacing

/**
 * The Pattern Explorer: what happened to **every** pattern on this stock, in the chosen window.
 *
 * ### Why this exists
 * `PatternScanResult` could answer "what did you find?" and "what did you hold back, and why?" but
 * not the question a reader actually has when a tab says *"No pattern scored 50 or more"*: **was
 * that a result, or did the app never look?** With one matched list the two are indistinguishable,
 * and the reader is left assuming the app is either broken or concealing something.
 *
 * So every row is one of four states, each stated in its own words:
 *
 * | Row | Means |
 * |---|---|
 * | matched | cleared [MatchScorePolicy.RENDER_THRESHOLD] |
 * | near miss | scored, held back, with the factor that fell short |
 * | blocked | could not be evaluated, with the numbers needed to fix it |
 * | not evaluated | **no detector exists** — the app never looked |
 *
 * The last row is the one a catalogue-only design loses, and it is the reason this is worth a
 * screen rather than a longer empty-state message.
 *
 * ### The copy rule
 * No probability language anywhere (plan.md §1.4 rule 3). A score is shape fit. The glossary copy
 * comes from `:domain`, which enforces that rule at construction, so this file adds no wording of
 * its own beyond state labels.
 */
@Composable
internal fun PatternExplorerSection(
    state: PatternsUiState,
    evaluation: PatternEvaluation,
    onGlossaryClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val matched = evaluation.matched()
    val nearMisses = evaluation.nearMisses()
    val blocked = evaluation.outcomes.filterIsInstance<EvaluationOutcome.CannotEvaluate>()
    val notEvaluated = evaluation.outcomes.filterIsInstance<EvaluationOutcome.NotEvaluated>()

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MiaSpacing.md),
    ) {
        Text(
            text = EXPLORER_TITLE,
            style = MaterialTheme.typography.titleMedium,
        )

        // The window is named here because a score is only true of the slice that produced it.
        Text(
            text = windowSentence(state, evaluation),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (evaluation.usedFallbackPivots) {
            // A quality signal invisible on the chart: the pivots were found with a shallower
            // zigzag pass, so the formations available at this width are structurally smaller than
            // they would be in a wider window. Measured: 17 of 20 large caps hit this at 60 bars,
            // 0 of 20 at 372.
            Text(
                text = FALLBACK_NOTE,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }

        if (matched.isEmpty()) {
            // Never a bare "nothing found": the reader is told what was looked for and what came of
            // it, with the gate named.
            Text(
                text = evaluation.emptyStateSentence() ?: EMPTY_UNEXPECTED,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        if (matched.isNotEmpty()) {
            SectionLabel(MATCHED_LABEL, matched.size)
            matched.forEach { outcome ->
                MatchedRow(
                    outcome = outcome,
                    onClick = { onGlossaryClick(outcome.name) },
                )
            }
        }

        if (nearMisses.isNotEmpty()) {
            SectionLabel(NEAR_MISS_LABEL, nearMisses.size)
            Text(
                text = NEAR_MISS_EXPLANATION,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            nearMisses.forEach { outcome ->
                NearMissRow(
                    outcome = outcome,
                    gate = MatchScorePolicy.SUPPRESS_THRESHOLD,
                    onClick = { onGlossaryClick(outcome.name) },
                )
            }
        }

        if (blocked.isNotEmpty()) {
            SectionLabel(BLOCKED_LABEL, blocked.size)
            blocked.forEach { outcome ->
                BlockedRow(outcome = outcome, onClick = { onGlossaryClick(outcome.name) })
            }
        }

        if (notEvaluated.isNotEmpty()) {
            SectionLabel(NOT_EVALUATED_LABEL, notEvaluated.size)
            Text(
                text = NOT_EVALUATED_EXPLANATION,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            notEvaluated.forEach { outcome ->
                NotEvaluatedRow(outcome = outcome, onClick = { onGlossaryClick(outcome.name) })
            }
        }
    }
}

/**
 * Window chips: 60 / 120 / 250 / whole series.
 *
 * Built from [PatternsUiState.windowOptions], which is driven by the measured sweep rather than by
 * taste — every offered value is at or above the timeframe's bar gate, because below it the scan
 * returns nothing at all.
 */
@Composable
internal fun WindowSelector(
    state: PatternsUiState,
    onSelect: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = state.windowOptions(state.timeframe, state.candles.size)
    if (options.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = WINDOW_LABEL,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MiaSpacing.sm),
        ) {
            options.forEach { bars ->
                val selected = state.windowBars == bars
                FilterChip(
                    selected = selected,
                    onClick = { onSelect(bars) },
                    label = {
                        Text(
                            text = bars?.let { "$it bars" } ?: ALL_BARS_LABEL,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                    modifier = Modifier.semantics {
                        contentDescription = (bars?.let { "Analyse the last $it bars" } ?: "Analyse all stored bars") +
                            if (selected) ", selected" else ""
                    },
                )
            }
        }
    }
}

// ------------------------------------------------------------------------------ rows

@Composable
private fun MatchedRow(outcome: EvaluationOutcome.Matched, onClick: () -> Unit) {
    RowCard(
        title = outcome.name,
        onClick = onClick,
        badge = "${outcome.score}",
        badgeDescription = "match score ${outcome.score} of 100, shape fit only",
        detail = "${outcome.match.category.name.lowercase()} · " +
            "cleared the ${MatchScorePolicy.RENDER_THRESHOLD} render threshold",
    )
}

/**
 * A scored candidate that was held back.
 *
 * Shows the score, the four factor readings, and the measures behind the **weakest** one — because
 * "scored 47" alone cannot distinguish geometry failing from structure failing from a penalty being
 * applied, which is the whole reason this row exists.
 */
@Composable
private fun NearMissRow(
    outcome: EvaluationOutcome.NearMiss,
    gate: Int,
    onClick: () -> Unit,
) {
    val breakdown = outcome.breakdown
    RowCard(
        title = outcome.name,
        onClick = onClick,
        badge = "${outcome.score}",
        badgeDescription = "scored ${outcome.score} of 100, below the $gate render threshold",
        detail = buildString {
            append("below the $gate threshold · ")
            append("geometry ${breakdown.geometry}, ")
            append("structure ${breakdown.structure}, ")
            append("confirmation ${breakdown.confirmation}, ")
            append("context ${breakdown.context}")
        },
    )
    // The weakest factor and its measures, indented under the row they explain.
    Column(modifier = Modifier.padding(start = MiaSpacing.md, top = MiaSpacing.xs)) {
        Text(
            text = "Weakest factor: ${outcome.weakestFactor.label} " +
                "(${(outcome.weakestFactor.weight)} of 100 weight)",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        outcome.measures.take(MAX_MEASURES_SHOWN).forEach { measure ->
            Text(
                text = "· ${measure.name}: ${formatMeasureValue(measure.value)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (outcome.measures.size > MAX_MEASURES_SHOWN) {
            Text(
                text = "· and ${outcome.measures.size - MAX_MEASURES_SHOWN} more measure(s)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BlockedRow(outcome: EvaluationOutcome.CannotEvaluate, onClick: () -> Unit) {
    RowCard(
        title = outcome.name,
        onClick = onClick,
        badge = "—",
        badgeDescription = "not scored",
        detail = outcome.reason,
        muted = true,
    )
}

@Composable
private fun NotEvaluatedRow(outcome: EvaluationOutcome.NotEvaluated, onClick: () -> Unit) {
    RowCard(
        title = outcome.name,
        onClick = onClick,
        badge = "n/a",
        badgeDescription = "not looked for by the app",
        detail = "The app does not look for this pattern yet. " +
            "Tap to read what it is.",
        muted = true,
    )
}

/**
 * One outcome row.
 *
 * The whole row is the touch target and opens the glossary, so every pattern — including the ones
 * that did not match — is one tap from an explanation.
 */
@Composable
private fun RowCard(
    title: String,
    onClick: () -> Unit,
    badge: String,
    badgeDescription: String,
    detail: String,
    muted: Boolean = false,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .semantics { contentDescription = "$title. $badgeDescription. $detail" },
        colors = CardDefaults.cardColors(
            containerColor = if (muted) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
            contentColor = if (muted) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSecondaryContainer
            },
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MiaSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MiaSpacing.sm),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge)
                Text(text = detail, style = MaterialTheme.typography.bodySmall)
            }
            Text(text = badge, style = MaterialTheme.typography.titleMedium)
            Text(text = DETAIL_GLYPH, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun SectionLabel(text: String, count: Int) {
    Text(
        text = "$text ($count)",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * The glossary, from `:domain`.
 *
 * Every field is rendered, including [GlossaryEntry.doesNotPredict] — which is the part that stops
 * a well-known formation being read as advice, so it is not hidden behind a disclosure.
 */
@Composable
internal fun PatternGlossaryDialog(
    patternName: String,
    onDismiss: () -> Unit,
) {
    val entry: GlossaryEntry? = PatternCatalog.byName(patternName)?.glossary
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(text = patternName, style = MaterialTheme.typography.titleMedium)
                PatternCatalog.byName(patternName)?.let { spec ->
                    Text(
                        text = "${spec.family.label} · " +
                            if (spec.isEvaluated) "evaluated on this stock" else "not yet looked for",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        text = {
            if (entry == null) {
                Text(text = NO_GLOSSARY)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(MiaSpacing.md)) {
                    Text(text = entry.whatItIs, style = MaterialTheme.typography.bodyMedium)
                    GlossaryField(label = "How it looks", value = entry.howItLooks)
                    GlossaryField(label = "What the textbook says", value = entry.textbookSays)
                    // Kept visible rather than collapsed: a named, well-known pattern is the thing
                    // most likely to be mistaken for advice.
                    GlossaryField(label = "What it does not establish", value = entry.doesNotPredict)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(text = CLOSE_LABEL) }
        },
    )
}

@Composable
private fun GlossaryField(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodySmall)
    }
}

// ----------------------------------------------------------------------------- helpers

private fun formatMeasureValue(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString()
    else String.format(java.util.Locale.ROOT, "%.2f", value)

private fun windowSentence(state: PatternsUiState, evaluation: PatternEvaluation): String =
    state.windowBars?.let { "$WINDOW_PREFIX last $it stored bars" }
        ?: "$WINDOW_PREFIX all ${evaluation.windowBars} stored bars"

private const val EXPLORER_TITLE = "Every pattern, on this stock"
private const val WINDOW_LABEL = "Window"
private const val ALL_BARS_LABEL = "All"
private const val WINDOW_PREFIX = "Analysed over"
private const val FALLBACK_NOTE =
    "Swing points at this width were found with a shallower zigzag pass, so the formations " +
        "available here are smaller than they would be in a wider window."
private const val MATCHED_LABEL = "Matched"
private const val NEAR_MISS_LABEL = "Scored below the threshold"
private const val BLOCKED_LABEL = "Could not be checked"
private const val NOT_EVALUATED_LABEL = "Not looked for by the app"
private const val NEAR_MISS_EXPLANATION =
    "These were found and scored, then held back. The weakest factor is shown with the " +
        "measurements behind it."
private const val NOT_EVALUATED_EXPLANATION =
    "The app has no detector for these yet, so it has not looked. That is different from looking " +
        "and finding none."
private const val DETAIL_GLYPH = "›"
private const val CLOSE_LABEL = "Close"
private const val NO_GLOSSARY = "No explanation is recorded for this pattern."
private const val EMPTY_UNEXPECTED = "Nothing was evaluated for this window."
private const val MAX_MEASURES_SHOWN = 4