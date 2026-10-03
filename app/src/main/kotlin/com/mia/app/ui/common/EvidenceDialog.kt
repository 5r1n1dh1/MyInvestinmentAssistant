package com.mia.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.mia.app.domain.model.Evidence
import com.mia.app.domain.model.RedFlag
import com.mia.app.ui.theme.MiaSpacing

/**
 * The evidence behind one red flag (plan.md §8.3).
 *
 * ### The rule this dialog exists to enforce
 * `RedFlag`'s `init` block refuses to be constructed without evidence, and this dialog is the
 * other half of that contract: the numbers, the source, the as-of date, the threshold that
 * tripped, and the caveat. plan.md §8.3: *"Each must be tappable to show its evidence (the actual
 * numbers), never a bare assertion."*
 *
 * Every [Evidence] row is shown, including rows whose [Evidence.asOf] is null — a row with no date
 * is exactly the case the reader needs to see, so it is rendered as [NOT_AVAILABLE] rather than
 * skipped. Skipping it would hide the gap.
 */
@Composable
fun EvidenceDialog(
    flag: RedFlag,
    onDismissRequest: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Column {
                Text(text = flag.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "${flag.severity.label()} · ${flag.group.name.lowercase().replace('_', ' ')}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(MiaSpacing.md),
            ) {
                flag.evidence.forEach { evidence ->
                    EvidenceRow(evidence)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                LabelledValue(label = UiStrings.RED_FLAG_THRESHOLD, value = flag.threshold)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    text = CAVERN_COPY,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = "Close")
            }
        },
    )
}

/**
 * One number, its source and its date. plan.md §1.4 rule 3 makes all three mandatory on
 * **fundamentals** figures; the same triple is shown on a technical flag because an unsourced
 * technical number is just as unauditable.
 */
@Composable
private fun EvidenceRow(evidence: Evidence, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                text = evidence.label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = evidence.value,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(
            text = "${UiStrings.RED_FLAG_SOURCE}: ${evidence.source.displayName()} · " +
                asOfSuffix(evidence.asOf),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A label/value pair used for the threshold line. */
@Composable
private fun LabelledValue(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = MiaSpacing.md),
        )
    }
}

/** `as of 02 Oct 2026`, or [NOT_AVAILABLE] when the source published no date. */
internal fun asOfSuffix(asOf: java.time.LocalDate?): String =
    if (asOf == null) NOT_AVAILABLE else formatAsOf(asOf)

/**
 * The standing caveat on every flag, in the app's own words.
 *
 * plan.md §8.3's wording rule is factual-only, and the corollary is that a flag is a *measurement
 * that tripped a threshold*, not a verdict about the company. Saying so here, once, is cheaper
 * than having each of the ten rules argue it.
 */
private const val CAVERN_COPY =
    "A red flag is a published figure crossing a fixed threshold. It is a measurement, not a " +
        "judgement about the company, and it is not investment advice."
