package com.mia.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mia.app.domain.compliance.Disclosure
import com.mia.app.domain.compliance.HonestyContract
import com.mia.app.domain.model.HitRate
import com.mia.app.domain.model.MatchScorePolicy
import com.mia.app.ui.theme.MiaSpacing

/**
 * plan.md §6.1's second number: how often this pattern's measured-move level was actually reached
 * on this stock.
 *
 * ### Two rules, both machine-enforced here
 * 1. **`n` is always present.** [HitRate.label] already emits either `Hit rate 62.5% (n=40)` or
 *    `Hit rate withheld — 12/30 samples`; [hitRateText] additionally runs the label through
 *    `HonestyContract.requireHitRateSampleSize`, so a future change to `HitRate.label` that drops
 *    the sample size fails loudly here instead of shipping a bare percentage.
 * 2. **Below 30 samples the number is withheld.** Not shown small, not shown greyed, not shown with
 *    a warning triangle — *withheld*, with the count shown instead. A 100% hit rate built from three
 *    samples is the exact failure this guard exists to stop, and a greyed 100% is still a 100% the
 *    reader will remember.
 *
 * ### What this never renders
 * No "success rate", no "win rate", no "% of the time it worked", and never adjacent to a match
 * score without [Disclosure.HIT_RATE] somewhere on the same surface — the two numbers mean
 * different things and plan.md §6.1 exists because they get conflated.
 */
@Composable
fun HitRateText(
    hitRate: HitRate,
    modifier: Modifier = Modifier,
    showDisclosure: Boolean = true,
) {
    val text = hitRateText(hitRate)
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MiaSpacing.xs),
    ) {
        Text(
            text = text,
            style = if (hitRate.isReportable) {
                MaterialTheme.typography.titleSmall
            } else {
                MaterialTheme.typography.bodyMedium
            },
            color = if (hitRate.isReportable) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        if (showDisclosure) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                text = Disclosure.HIT_RATE,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = MiaSpacing.xs),
            )
        }
    }
}

/**
 * The non-composable half of [HitRateText], so the honesty rule is checkable without a
 * composition.
 *
 * Delegates the wording to [HitRate.label] — the single source in `:domain` — and then asserts the
 * sample size is present. The assertion cannot fire on a value produced by `label()`; it exists so
 * that a future edit to that function, or a different implementation of [HitRate] from a
 * repository, fails at the call site rather than on screen.
 */
internal fun hitRateText(hitRate: HitRate): String {
    val label = hitRate.label()
    HonestyContract.requireHitRateSampleSize(label)
    return label
}

/**
 * The track-record row (decision D6: per stock **and** per pattern).
 *
 * @param perSymbolAndPattern `HitRate` for this symbol *and* this pattern — the number that
 *   actually generalises to the reader's question.
 * @param perSymbol `HitRate` for this symbol across every pattern, when the caller has one.
 *   Passing `null` renders a single row rather than an empty column.
 * @param minimumSamples defaults to `MatchScorePolicy.MIN_HIT_RATE_SAMPLES` so the withholding
 *   threshold can never be shown to the reader as something other than 30.
 */
@Composable
fun TrackRecordRow(
    perSymbolAndPattern: HitRate?,
    modifier: Modifier = Modifier,
    perSymbol: HitRate? = null,
    patternName: String? = null,
    minimumSamples: Int = MatchScorePolicy.MIN_HIT_RATE_SAMPLES,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MiaSpacing.sm),
    ) {
        if (patternName != null) {
            Text(
                text = "$UiStrings.TRACK_RECORD · $patternName on this stock",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        } else {
            Text(
                text = UiStrings.TRACK_RECORD,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        if (perSymbolAndPattern == null) {
            EmptyState(
                title = "No tracked outcomes yet",
                body = "Outcomes are recorded when a projection's evaluation window closes. " +
                    "$minimumSamples tracked samples are needed before a percentage is shown.",
            )
        } else {
            HitRateText(hitRate = perSymbolAndPattern)
        }

        if (perSymbol != null) {
            Text(
                text = "Every pattern on this stock",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HitRateText(hitRate = perSymbol, showDisclosure = false)
        }
    }
}
