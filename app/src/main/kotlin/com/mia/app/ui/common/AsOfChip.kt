package com.mia.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mia.app.domain.model.Source
import com.mia.app.domain.ratios.Freshness
import com.mia.app.domain.ratios.FreshnessState
import com.mia.app.ui.theme.MiaSpacing
import java.time.LocalDate

/**
 * plan.md §1.4 rule 3 + §10: **every** figure carries its source and its as-of date, and a stale
 * figure says so.
 *
 * ### Why the freshness verdict is a parameter and not recomputed here
 * [Freshness] carries the date arithmetic and the window it was judged against, and the domain's
 * own tests assert on that. If this composable recomputed the age it could disagree with the
 * `Ratios` snapshot the same screen is holding, and the reader would see two ages for one figure.
 * So the caller passes the verdict it already has.
 *
 * ### The three visual states
 * * **fresh** — a quiet outlined chip: `screener.in · as of 02 Oct 2026`.
 * * **stale** — the same chip in the error colour with the age: `screener.in · as of 02 Oct 2026 ·
 *   214d`, and [FreshnessState.STALE]'s window violation is in the spoken description so the
 *   visible chip stays one line wide.
 * * **missing** — `screener.in · as of —`, because a figure with no date is a fact about the data
 *   and hiding it would be dishonest.
 */
@Composable
fun AsOfChip(
    source: Source,
    asOf: LocalDate?,
    freshness: Freshness,
    modifier: Modifier = Modifier,
) {
    val isStale = freshness.state == FreshnessState.STALE
    val isMissing = freshness.state == FreshnessState.MISSING

    val borderColor = if (isStale) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }
    val textColor = if (isStale) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    val visibleText = buildString {
        append(source.displayName())
        append(" · ")
        append(asOfSuffix(asOf))
        if (isStale) {
            append(" · ")
            append(freshness.ageDays ?: 0L)
            append("d")
        }
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (isMissing) {
                    MaterialTheme.colorScheme.surfaceVariant
                } else {
                    MaterialTheme.colorScheme.surface
                },
            )
            .border(width = 1.dp, color = borderColor, shape = RoundedCornerShape(6.dp))
            .padding(horizontal = MiaSpacing.sm, vertical = 3.dp)
            // The full `Freshness.description` carries the window the value exceeded; the visible
            // chip is deliberately short.
            .semantics { contentDescription = freshness.description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MiaSpacing.xs),
    ) {
        Text(
            text = visibleText,
            style = MaterialTheme.typography.labelSmall,
            color = textColor,
        )
    }
}
