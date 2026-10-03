package com.mia.app.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.mia.app.ui.theme.MiaSpacing

/**
 * plan.md §8.3: *"Red flags surface at the top of both tabs, not buried in a section. Each must be
 * tappable to show its evidence."*
 *
 * ### Why it renders nothing when there are no flags
 * A permanent "no red flags" green banner teaches the reader to ignore the banner, and the day a
 * flag does fire it is the day they skip it. Silence is the correct empty state here; the
 * *absence* of the banner is the message. When a rule could not run at all, the screen says so in
 * its own words (see `RedFlagReport.headerText`) rather than letting this component imply a clean
 * bill of health.
 *
 * ### Ordering
 * [RedFlagReport] already sorts by severity → tab → id so the banner does not reshuffle between
 * refreshes. That order is preserved here: a High flag never slides below a Medium one because the
 * list was re-read.
 *
 * ### Why it collapses, and why only past a threshold
 * With every fired flag expanded, six rows of title-plus-id pushed the chart and indicators below
 * the fold — the banner was measured at roughly three quarters of the visible screen. The reader
 * asked for it to be collapsible, and the honest way to honour that is to keep the *count* on
 * screen and move only the *detail* behind a tap:
 *
 * - **Collapsed is the default only above [COLLAPSE_AFTER] flags.** At one or two flags the block
 *   is already short, and adding a tap in front of it would cost the reader a step for no space
 *   saved. The rule is proportional to content, not a fixed default, so a stock with two flags
 *   still shows both without interaction.
 * - **Collapsed still states every severity count in words** ("1 high, 2 medium"). plan.md §8.3
 *   exists so that flags are not buried, and a silent collapse would defeat it: the reader would
 *   not know whether the banner holds nothing or holds something they have not looked at. A high
 *   flag is always visible even when collapsed, because it is the one that must not be missed.
 * - **No animation.** The app contains no animation code today, and `AnimatedVisibility` would be
 *   the first animated transition in the product for a purely cosmetic effect. A plain conditional
 *   also cannot desynchronise from a scrolling list the way a height animation can.
 */
@Composable
fun RedFlagBanner(
    flags: List<RedFlag>,
    onFlagClick: (RedFlag) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (flags.isEmpty()) return

    var expanded by rememberSaveable { mutableStateOf(flags.size <= COLLAPSE_AFTER) }

    // A reload can change the count underneath the remembered state. Keyed on the count so a stock
    // that goes from six flags to one does not stay collapsed, and so the first render of a
    // long list is always collapsed rather than briefly expanding to full height.
    val expandKey = flags.size
    LaunchedEffect(expandKey) { expanded = expandKey <= COLLAPSE_AFTER }

    // Only the High subset is needed here; `severitySummary` counts the rest itself.
    val high = flags.filter { it.severity == FlagSeverity.HIGH }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MiaSpacing.md),
            verticalArrangement = Arrangement.spacedBy(MiaSpacing.sm),
        ) {
            // The header row is the toggle. Whole-row touch target, like [RedFlagRow] below.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .clickable { expanded = !expanded }
                    .semantics {
                        contentDescription = UiStrings.RED_FLAGS + ", " +
                            severitySummary(flags) + ", " +
                            (if (expanded) EXPAND_LABEL else COLLAPSE_LABEL)
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MiaSpacing.sm),
            ) {
                Text(
                    text = UiStrings.RED_FLAGS,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (expanded) COLLAPSED_GLYPH else EXPANDED_GLYPH,
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            // Counts first, always. This is the part that keeps a collapsed banner compliant with
            // plan.md §8.3 — the reader learns that flags fired and how severe they are without
            // tapping.
            Text(
                text = severitySummary(flags),
                style = MaterialTheme.typography.bodySmall,
            )

            if (expanded) {
                Text(
                    text = UiStrings.RED_FLAG_TAP_FOR_EVIDENCE,
                    style = MaterialTheme.typography.bodySmall,
                )
                flags.forEach { flag ->
                    RedFlagRow(flag = flag, onClick = { onFlagClick(flag) })
                }
            } else {
                // A collapsed banner that hides only medium/low detail still shows every High
                // flag, so the one severity a reader must never miss is never behind a tap.
                high.forEach { flag ->
                    RedFlagRow(flag = flag, onClick = { onFlagClick(flag) })
                }
            }
        }
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
 * Above this many flags the banner starts collapsed. See the class KDoc for why the rule is
 * proportional rather than a fixed default.
 */
private const val COLLAPSE_AFTER: Int = 2

/**
 * Plain-text glyphs, because the app has no `material-icons` dependency and its house style is
 * text characters ([DISCLOSURE_GLYPH], `×`, `σ`, `—`, `▲`/`▼`).
 *
 * Each glyph names the action it performs, not the current state: [EXPANDED_GLYPH] (`⌄`) is shown
 * while collapsed and means "there is more below", so tapping it expands. Inverting that is a
 * common and quiet mistake — a control labelled with the state instead of the action reads as
 * "already done" at the moment the reader most wants it.
 */
private const val EXPANDED_GLYPH = "⌄"
private const val COLLAPSED_GLYPH = "›"

private const val EXPAND_LABEL = "double tap to expand"
private const val COLLAPSE_LABEL = "double tap to collapse"
