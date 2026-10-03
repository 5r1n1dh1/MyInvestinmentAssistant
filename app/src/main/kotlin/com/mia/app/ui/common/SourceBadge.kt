package com.mia.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mia.app.domain.model.Confidence
import com.mia.app.domain.model.Merged
import com.mia.app.domain.model.Source
import com.mia.app.ui.theme.MiaSpacing

/**
 * Where a number came from (plan.md §1.4 rule 3).
 *
 * The badge's *shape* encodes the kind of source so a screen full of them is not a wall of
 * identical grey pills: a filled disc for an official exchange file or the symbol master, a ring
 * for a scraped page or a vendor API, and a square for anything computed on device. Colour is
 * never the only signal.
 */
@Composable
fun SourceBadge(
    source: Source,
    modifier: Modifier = Modifier,
) {
    val name = source.displayName()
    Row(
        modifier = modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .background(MaterialTheme.colorScheme.surface)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = MaterialTheme.shapes.extraSmall,
            )
            .padding(horizontal = MiaSpacing.sm, vertical = 2.dp)
            .semantics { contentDescription = "${UiStrings.SOURCE}: $name" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MiaSpacing.xs),
    ) {
        SourceMarker(source = source)
        Text(
            text = name,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The shape half of [SourceBadge]: official file / master → disc, vendor or scraped page → ring,
 * computed locally → square. Chosen so the three classes stay apart for a reader with a
 * colour-vision deficiency and at 2x text scale on a 5-inch screen.
 */
@Composable
private fun SourceMarker(source: Source, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    val size = 8.dp
    when (source) {
        Source.NSE_BHAVCOPY, Source.NSE_EQUITY_MASTER -> Box(
            modifier = modifier
                .size(size)
                .clip(CircleShape)
                .background(accent),
        )

        Source.YAHOO, Source.SCREENER_IN -> Box(
            modifier = modifier
                .size(size)
                .clip(CircleShape)
                .border(width = 2.dp, color = accent, shape = CircleShape),
        )

        Source.DERIVED -> Box(
            modifier = modifier
                .size(size)
                .background(accent),
        )
    }
}

/**
 * plan.md §5.1's conflict display — the one place in the app where two numbers for the same field
 * appear at once:
 *
 * > On conflict (>5% divergence): show the primary value, render the other greyed beneath it with a
 * > divergence chip. Do NOT average. Do NOT silently pick one.
 *
 * ### Why there is no averaging path
 * The plan measured 7.18% P/E divergence between screener.in and Yahoo on TCS. Averaging two
 * numbers that disagree about a company's earnings produces a third number neither source
 * published — a fabrication with extra steps. This component therefore has no average parameter and
 * no branch that could compute one.
 *
 * @param primary the merged value, rendered at full weight.
 * @param alternate the disagreeing value, greyed below.
 * @param confidence [Confidence.CONFLICT] raises the conflict warning; [Confidence.STALE] raises the
 *   staleness warning instead, because two old numbers are not a conflict worth the reader's time
 *   (`FreshnessPolicy.confidenceFor` already prefers STALE for exactly that reason).
 */
@Composable
fun DivergenceChip(
    primary: String,
    alternate: String,
    confidence: Confidence,
    modifier: Modifier = Modifier,
) {
    val isProblem = confidence == Confidence.CONFLICT || confidence == Confidence.STALE
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = primary,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = alternate,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp)
                .semantics { contentDescription = "${UiStrings.DIVERGENT_VALUE}: $alternate" },
        )
        Text(
            text = when (confidence) {
                Confidence.CONFLICT -> "${UiStrings.CONFLICT_CHIP} · ${UiStrings.NOT_AVERAGED}"
                Confidence.STALE -> "${UiStrings.STALE} · ${UiStrings.NOT_AVERAGED}"
                Confidence.AGREED -> "Both sources agree"
                Confidence.SINGLE -> "One source published this field"
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (isProblem) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/**
 * [DivergenceChip] applied to a `Merged<T>`, which is how every figure in the app arrives.
 *
 * @param format renders the value. Screens pass the field's own formatter — a P/E and a market cap
 *   are not the same shape of number.
 *
 * The alternate's own source is shown on the greyed line, so the reader can see *which* second
 * source disagreed rather than just that something did.
 */
@Composable
fun <T : Any> MergedValueRow(
    label: String,
    merged: Merged<T>,
    format: (T) -> String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val alternate = merged.alternate
        if (alternate != null && merged.hasConflict) {
            DivergenceChip(
                primary = format(merged.value),
                alternate = merged.alternateSource?.let { "${format(alternate)} · ${it.displayName()}" }
                    ?: format(alternate),
                confidence = merged.confidence,
            )
        } else {
            Text(
                text = format(merged.value),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Row(
            modifier = Modifier.padding(top = MiaSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MiaSpacing.xs),
        ) {
            SourceBadge(source = merged.source)
            Text(
                text = asOfSuffix(merged.asOf),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
