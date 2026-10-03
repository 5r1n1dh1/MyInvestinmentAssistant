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
 */
@Composable
fun RedFlagBanner(
    flags: List<RedFlag>,
    onFlagClick: (RedFlag) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (flags.isEmpty()) return

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
            Text(
                text = UiStrings.RED_FLAGS,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = UiStrings.RED_FLAG_TAP_FOR_EVIDENCE,
                style = MaterialTheme.typography.bodySmall,
            )
            flags.forEach { flag ->
                RedFlagRow(flag = flag, onClick = { onFlagClick(flag) })
            }
        }
    }
}

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
