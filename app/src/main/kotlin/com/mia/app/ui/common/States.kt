package com.mia.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mia.app.domain.compliance.Disclosure
import com.mia.app.domain.ratios.Freshness
import com.mia.app.domain.ratios.FreshnessState
import com.mia.app.ui.theme.MiaSpacing

/**
 * The screen states every tab needs, in one file so that "what does an error look like here" has
 * one answer across the app.
 *
 * plan.md §12: *"A parser must return a health flag, not an empty list. Silent-empty is
 * indistinguishable from 'no red flags' — the most dangerous possible failure."* Every state here
 * is **honest**: a missing value is never dressed up as a zero, and an error always names what
 * failed and offers the one action that might fix it.
 */

/**
 * A section heading with an optional one-line explanation underneath.
 *
 * @param subtitle put the *definition* here, not an interpretation — e.g. "average of the last 200
 *   closes", never "the market looks weak".
 */
@Composable
fun SectionHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = MiaSpacing.xs),
            )
        }
    }
}

/**
 * "There is nothing here, and that is a real answer."
 *
 * @param title what is missing, in the user's terms.
 * @param body why it is empty — a threshold, a date, a filter. An empty state without a reason is
 *   indistinguishable from a bug.
 */
@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MiaSpacing.lg),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(MiaSpacing.sm),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Text(text = body, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * A failure the user can act on.
 *
 * @param reason what went wrong in plain words, shown verbatim. It must come from an already
 *   user-facing message (a `DataFailure.message`, a `ParserHealth` note) — never an exception class
 *   name or a stack trace.
 */
@Composable
fun ErrorState(
    reason: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
                .padding(MiaSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(MiaSpacing.md),
        ) {
            Text(text = UiStrings.errorTitle(reason), style = MaterialTheme.typography.titleSmall)
            OutlinedButton(onClick = onRetry) {
                Text(text = UiStrings.RETRY)
            }
        }
    }
}

/**
 * A busy state.
 *
 * The label is a polite live region so TalkBack says something while the screen is quiet, instead
 * of leaving the user on an apparently dead list.
 */
@Composable
fun LoadingState(
    modifier: Modifier = Modifier,
    label: String = UiStrings.LOADING,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(MiaSpacing.xl)
            .semantics { liveRegion = LiveRegionMode.Polite },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MiaSpacing.md),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(32.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * plan.md §10's staleness marker: *"Staleness must be visible in the UI. A stale P/E shown as live
 * is the fastest way to lose trust."*
 *
 * Renders **nothing** when the value is fresh. A permanent "current" chip on every figure is
 * noise, and noise trains the eye to ignore the one chip that matters.
 */
@Composable
fun StaleBadge(
    freshness: Freshness,
    modifier: Modifier = Modifier,
) {
    when (freshness.state) {
        FreshnessState.FRESH -> Unit

        FreshnessState.MISSING -> Text(
            text = UiStrings.MISSING,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )

        FreshnessState.STALE -> Text(
            text = "${UiStrings.STALE} · ${freshness.ageDays ?: 0L}d",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = modifier,
        )
    }
}

/**
 * The regulated footnote at the bottom of a surface that shows a score or a projection.
 *
 * @param text pass the matching constant from `Disclosure` — there is **no default**, because
 *   choosing one for the caller would let a projection surface end up carrying only the score
 *   wording, or worse, nothing.
 */
@Composable
fun DisclaimerFooter(
    text: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Start,
            modifier = Modifier.padding(top = MiaSpacing.sm),
        )
    }
}

/** The app-wide sourcing statement (plan.md §3, §1.4 rule 3). */
@Composable
fun DataSourceFooter(modifier: Modifier = Modifier) {
    DisclaimerFooter(text = Disclosure.DATA_SOURCE, modifier = modifier)
}
