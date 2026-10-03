package com.mia.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.mia.app.domain.model.Timeframe
import com.mia.app.ui.theme.MiaSpacing

/**
 * The 1D / 1W switch (decision D1: timeframes are `D1` and `W1` only — no intraday).
 *
 * ### Why exactly two chips and not a dropdown
 * A dropdown hides the option the user did not pick, which is how an app ends up with an intraday
 * mode nobody tested. Two visible, always-present chips make the product's scope a property of the
 * UI. Adding a third timeframe means changing this component, which is the intended friction.
 *
 * ### Semantics
 * Wrapped in a `selectableGroup` and given per-chip descriptions, so TalkBack announces
 * "Daily, selected" rather than reading the two labels with no relationship between them.
 */
@Composable
fun TimeframeToggle(
    selected: Timeframe,
    onSelect: (Timeframe) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(MiaSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Timeframe.entries.forEach { timeframe ->
            val isSelected = timeframe == selected
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(timeframe) },
                label = { Text(text = timeframe.shortLabel()) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
                modifier = Modifier.semantics {
                    contentDescription = timeframe.spokenLabel() + if (isSelected) ", selected" else ""
                },
            )
        }
    }
}

/** `1D` / `1W` — the labels the plan uses, so the chips read the same as the rest of the copy. */
fun Timeframe.shortLabel(): String = when (this) {
    Timeframe.D1 -> UiStrings.TIMEFRAME_D1
    Timeframe.W1 -> UiStrings.TIMEFRAME_W1
}

/** Spoken form. `1D` read by a screen reader as "one D" is useless. */
fun Timeframe.spokenLabel(): String = when (this) {
    Timeframe.D1 -> "Daily bars"
    Timeframe.W1 -> "Weekly bars"
}
