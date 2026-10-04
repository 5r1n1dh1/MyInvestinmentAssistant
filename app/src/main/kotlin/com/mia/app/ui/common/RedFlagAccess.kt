package com.mia.app.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mia.app.domain.model.FlagSeverity
import com.mia.app.domain.model.RedFlag
import com.mia.app.domain.redflag.RuleSkip
import com.mia.app.ui.theme.MiaSpacing

/**
 * plan.md §8.3: *"Red flags surface at the top of both tabs, not buried in a section. Each must be
 * tappable to show its evidence."*
 *
 * ### One always-present row, and the detail behind a tap
 * This used to be an inline banner that expanded in place. Two things were wrong with it, and both
 * were found in use rather than in review:
 *
 * - **It rendered nothing at all when no flag had fired.** An early return on an empty list meant
 *   that for a stock with a clean report the whole feature simply did not exist on screen — there
 *   was no button, no count, nothing. A reader who had seen red flags on one stock and then found
 *   none on the next had no way to tell "nothing fired" from "this view is broken".
 * - **Expanded, it consumed most of the screen.** Six titles plus rule ids pushed the chart and the
 *   indicators below the fold, which is what prompted the collapse threshold that then made the
 *   feature feel absent in the other direction.
 *
 * Both failures are the same mistake: the detail and the *evidence that the check ran* were the same
 * object. The fix is to make the always-visible part say only what it can honestly say — how many
 * flags, how severe, and how many checks could not run — and to put the whole report behind one
 * deliberate tap. The row is present on every stock whether or not anything fired, because
 * "nothing fired" is a result worth being able to confirm.
 *
 * ### What the row never claims
 * It does not say "all clear". When rules were skipped or crashed, the row says so in the same
 * breath as the counts, because an absent flag and an unevaluated rule are different facts
 * (plan.md §12 calls the silent empty the most dangerous failure in the product). The full
 * explanation of each missing check is in the dialog.
 *
 * ### Why the dialog opens instead of inlining
 * The dialog owns its own scroll, so a stock with twenty flags does not push the tab's own content
 * around, and a stock with none does not leave an empty box where the chart should be. It is a
 * separate window the reader chose to open, which is also why the detail can afford to be complete.
 */
@Composable
fun RedFlagAccess(
    flags: List<RedFlag>,
    skippedRules: List<RuleSkip>,
    ruleFailures: Map<String, String>,
    onFlagClick: (RedFlag) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by rememberSaveable { mutableStateOf(false) }

    // Severity drives the colour. A stock with nothing to report is not tinted like a stock with a
    // high-severity flag: an error-coloured card on every clean stock is how the reader learns to
    // stop reading the card at all, which is the exact failure the old banner was meant to prevent.
    val alarming = flags.isNotEmpty()

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (alarming) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            contentColor = if (alarming) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .clickable { open = true }
                .padding(horizontal = MiaSpacing.md, vertical = MiaSpacing.sm)
                // Whole-row target, like every other tap in this component.
                .semantics {
                    contentDescription = UiStrings.RED_FLAGS + ", " +
                        accessSummary(flags, skippedRules, ruleFailures) + ", " + OPEN_LABEL
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MiaSpacing.sm),
        ) {
            // Drawn only when something fired. On a clean stock the row carries no severity,
            // because there is none, and a grey disc would imply one that does not exist.
            worstSeverity(flags)?.let { SeverityMarker(it) }
            Column(modifier = Modifier.weight(1f)) {
                Text(text = UiStrings.RED_FLAGS, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = accessSummary(flags, skippedRules, ruleFailures),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(text = DISCLOSURE_GLYPH, style = MaterialTheme.typography.titleMedium)
        }
    }

    if (open) {
        RedFlagDialog(
            flags = flags,
            skippedRules = skippedRules,
            ruleFailures = ruleFailures,
            onFlagClick = { flag ->
                // Close first, so the evidence dialog is the only thing on screen. Two stacked
                // AlertDialogs both claim the back gesture and the reader cannot tell which one
                // dismissed.
                open = false
                onFlagClick(flag)
            },
            onDismiss = { open = false },
        )
    }
}

/**
 * The whole report, as one list the reader opened deliberately.
 *
 * Ordering inside is the report's own: `RedFlagReport` sorts by severity → tab → id, so a High flag
 * is the first row here without this component sorting anything.
 */
@Composable
private fun RedFlagDialog(
    flags: List<RedFlag>,
    skippedRules: List<RuleSkip>,
    ruleFailures: Map<String, String>,
    onFlagClick: (RedFlag) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = UiStrings.RED_FLAGS, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(MiaSpacing.sm),
            ) {
                Text(
                    text = accessSummary(flags, skippedRules, ruleFailures),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (flags.isEmpty()) {
                    // With nothing to list, the coverage block below is the entire content of this
                    // dialog, so it needs saying plainly rather than under a heading that presumes
                    // a list.
                    Text(
                        text = coverageLeadIn(skippedRules, ruleFailures),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = UiStrings.RED_FLAG_TAP_FOR_EVIDENCE,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    flags.forEach { flag ->
                        RedFlagRow(flag = flag, onClick = { onFlagClick(flag) })
                    }
                }
                FlagCoverageLines(skippedRules = skippedRules, ruleFailures = ruleFailures)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(text = CLOSE_LABEL) }
        },
    )
}

/**
 * Which checks did not produce a verdict (plan.md §12).
 *
 * Shown here rather than only in the tab body because the dialog has to be self-sufficient: a
 * reader who opens it to check what fired is owed the answer to "and what didn't get a look".
 *
 * Not collapsed. The reader has already paid for one tap, and this is the part that is only ever
 * false when omitted — the one place where being terse costs more than it saves.
 */
@Composable
fun RedFlagCoverageSection(
    skippedRules: List<RuleSkip>,
    ruleFailures: Map<String, String>,
    modifier: Modifier = Modifier,
) {
    if (skippedRules.isEmpty() && ruleFailures.isEmpty()) return
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MiaSpacing.xs),
    ) {
        Text(
            text = coverageLeadIn(skippedRules, ruleFailures),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlagCoverageLines(skippedRules = skippedRules, ruleFailures = ruleFailures)
    }
}

/**
 * The skipped/failed rules, one line each, in the same wording as
 * [RedFlagReport.headerText] uses for the same fact.
 *
 * Shared by the dialog and by the in-page coverage section so the two cannot drift into saying
 * different things about the same gap.
 */
@Composable
private fun FlagCoverageLines(
    skippedRules: List<RuleSkip>,
    ruleFailures: Map<String, String>,
) {
    skippedRules.forEach { skip ->
        Text(
            text = "Not evaluated — " + skip.ruleId + ": " + skip.reason,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    ruleFailures.forEach { (ruleId, message) ->
        Text(
            text = "Failed — " + ruleId + ": " + message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/** Heading for the coverage block, phrased as the count of checks that produced no verdict. */
private fun coverageLeadIn(skippedRules: List<RuleSkip>, ruleFailures: Map<String, String>): String {
    val unchecked = skippedRules.size + ruleFailures.size
    if (unchecked == 0) return "Every check produced a verdict"
    return "Checks that produced no verdict ($unchecked)"
}

// ------------------------------------------------------------------------------- summary copy

/**
 * What the row and the dialog both say in one line: how many flags, how severe, and how many
 * checks could not run.
 *
 * Three facts in one string because the reader's question is never only "did anything fire" — it
 * is "did anything fire, and how bad, and am I sure the rest was looked at". Answering them in
 * three places would put the reassuring fact next to a caveat and the alarm next to a count.
 */
private fun accessSummary(
    flags: List<RedFlag>,
    skippedRules: List<RuleSkip>,
    ruleFailures: Map<String, String>,
): String {
    val base = when {
        flags.isEmpty() -> UiStrings.NO_RED_FLAGS
        flags.size == 1 -> "1 flag"
        else -> "${flags.size} flags"
    }
    val severities = severitySummary(flags)
    val withSeverities = if (severities.isEmpty()) base else "$base · $severities"
    val unchecked = skippedRules.size + ruleFailures.size
    return if (unchecked == 0) {
        withSeverities
    } else {
        withSeverities + " · " + plural(unchecked, "check") + " gave no verdict"
    }
}

/**
 * "1 high, 2 medium, 1 low" — every fired severity, in words.
 *
 * Names only the severities that actually fired. A "0 high" would imply the reader should go
 * looking for a missing high flag, which is a different claim from "no high flag fired".
 */
private fun severitySummary(flags: List<RedFlag>): String {
    val parts = buildList {
        val high = flags.count { it.severity == FlagSeverity.HIGH }
        val medium = flags.count { it.severity == FlagSeverity.MEDIUM }
        val low = flags.count { it.severity == FlagSeverity.LOW }
        if (high > 0) add(plural(high, "high"))
        if (medium > 0) add(plural(medium, "medium"))
        if (low > 0) add(plural(low, "low"))
    }
    return parts.joinToString(", ")
}

private fun plural(count: Int, word: String): String = "$count $word" + if (count == 1) "" else "s"

/**
 * The most severe severity present, or `null` when nothing fired.
 *
 * The row draws one marker, and it must be the worst one — a row showing a small Low dot beside
 * "1 high, 1 low" would understate the report at a glance. Scans in declaration order
 * (`HIGH, MEDIUM, LOW`) so the first hit is the worst, which is the enum's own ordering rather than
 * a severity number restated here.
 */
private fun worstSeverity(flags: List<RedFlag>): FlagSeverity? =
    FlagSeverity.entries.firstOrNull { severity -> flags.any { it.severity == severity } }

// ------------------------------------------------------------------------------------- rows

/**
 * One flag. The whole row is the touch target — a 24 dp marker plus text is under the 48 dp
 * minimum, and these are the most important taps in the app.
 */
@Composable
private fun RedFlagRow(
    flag: RedFlag,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = "${flag.severity.label()}. ${flag.title}. " +
                    UiStrings.RED_FLAG_TAP_FOR_EVIDENCE
            },
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = MiaSpacing.sm, horizontal = MiaSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MiaSpacing.sm),
        ) {
            // Severity as a shape *and* a colour: a filled disc for HIGH, a ring for MEDIUM, a
            // small dot for LOW. Two encodings, because severity is the thing a reader scans for.
            SeverityMarker(flag.severity)
            Column(modifier = Modifier.weight(1f)) {
                Text(text = flag.title, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = flag.id,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Text(
                text = DISCLOSURE_GLYPH,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

/**
 * Severity as geometry, not just colour: HIGH is a filled disc, MEDIUM an annulus, LOW a small
 * filled dot. Together with the severity word in the row's spoken description, a user who cannot
 * distinguish the three container shades can still rank them.
 */
@Composable
private fun SeverityMarker(severity: FlagSeverity, modifier: Modifier = Modifier) {
    val color = when (severity) {
        FlagSeverity.HIGH -> MaterialTheme.colorScheme.error
        FlagSeverity.MEDIUM -> MaterialTheme.colorScheme.tertiary
        FlagSeverity.LOW -> MaterialTheme.colorScheme.onErrorContainer
    }
    val size = when (severity) {
        FlagSeverity.HIGH -> 12.dp
        FlagSeverity.MEDIUM -> 12.dp
        FlagSeverity.LOW -> 8.dp
    }
    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.size(size),
            color = color,
            shape = CircleShape,
        ) {}
        if (severity == FlagSeverity.MEDIUM) {
            // Punch a hole so MEDIUM reads as a ring against the container colour.
            Surface(
                modifier = Modifier.size(6.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                shape = CircleShape,
            ) {}
        }
    }
}

/** Severity as the word the screen reader announces, and as a debug-friendly label. */
fun FlagSeverity.label(): String = when (this) {
    FlagSeverity.HIGH -> "High severity"
    FlagSeverity.MEDIUM -> "Medium severity"
    FlagSeverity.LOW -> "Low severity"
}

private const val DISCLOSURE_GLYPH = "›"

/**
 * Plain-text glyphs, because the app has no `material-icons` dependency and its house style is
 * text characters ([DISCLOSURE_GLYPH], `×`, `σ`, `—`, `▲`/`▼`).
 *
 * [DISCLOSURE_GLYPH] (`›`) names the action it performs, not the current state: it means "there is
 * more inside", so tapping it opens the report. Inverting that is a common and quiet mistake — a
 * control labelled with the state instead of the action reads as "already done" at the moment the
 * reader most wants it.
 */
private const val OPEN_LABEL = "double tap to open the full report"
private const val CLOSE_LABEL = "Close"
