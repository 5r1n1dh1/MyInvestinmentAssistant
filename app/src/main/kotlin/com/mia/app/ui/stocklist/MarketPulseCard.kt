package com.mia.app.ui.stocklist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import com.mia.app.domain.technical.Breadth
import com.mia.app.domain.technical.MarketBreadth
import com.mia.app.domain.technical.Measured
import com.mia.app.ui.theme.MiaSpacing
import com.mia.app.ui.theme.glass.GlassCard

/**
 * "Market pulse" — advance/decline and the history-dependent breadth, from stored bars.
 *
 * ### The rule this card is built around
 *
 * **Every figure states the population it describes.** That is not decoration. `% above its 50-day
 * average` is the most quotable number on this card and also the most dangerous one: on a device
 * that has synced six sessions, the honest answer is "0 of 6 symbols qualify", and rendering that as
 * "0%" would read as the entire market sitting below its average. So the percentage is shown as a
 * count over a denominator, and the denominator is its own figure rather than an implied "the
 * market".
 *
 * The same rule decides where a figure goes when it cannot be computed at all. [Measured] carries a
 * reason; this card renders that reason in place of the number, so "not enough history" is visible
 * instead of being smoothed into a zero.
 *
 * ### Why no sentiment language
 *
 * A card reading "broad market weakness" would be advice, and this app does not give it. These are
 * counts of what closed up and down on one session, with the population attached and nothing
 * inferred about what they mean for anyone's money.
 */
@Composable
fun MarketPulseCard(breadth: Breadth?, modifier: Modifier = Modifier) {
    GlassCard(modifier = modifier.fillMaxWidth().testTag(MARKET_PULSE_TAG)) {
        Text(
            text = "Market pulse",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() },
        )

        if (breadth == null) {
            // The "nothing stored" state. Deliberately not zeros: a breadth measured over no
            // symbols would render as a market that closed flat everywhere, which is the most
            // misleading single thing this card could show.
            Text(
                text = NOT_MEASURED_YET,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = breadth.summary(),
                style = MaterialTheme.typography.bodyMedium,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MiaSpacing.sm),
            ) {
                PulseTile(
                    label = "Advance / decline",
                    value = breadth.advanceDeclineRatio.render(),
                    modifier = Modifier.weight(1f),
                )
                PulseTile(
                    label = "Above its ${MarketBreadth.SMA_PERIOD}-day average",
                    value = breadth.percentAboveSma50.render(count = breadth.aboveSma50.valueOrNull()),
                    modifier = Modifier.weight(1f),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MiaSpacing.sm),
            ) {
                PulseTile(
                    label = "New 52-week highs",
                    value = breadth.newHighs.render(),
                    modifier = Modifier.weight(1f),
                )
                PulseTile(
                    label = "New 52-week lows",
                    value = breadth.newLows.render(),
                    modifier = Modifier.weight(1f),
                )
            }

            populationNote(breadth)

            if (breadth.unclassified > 0) {
                Text(
                    text = "${breadth.unclassified} symbol(s) carried no previous close, so they " +
                        "count as neither advancing nor declining.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The denominators, stated once under the tiles.
 *
 * Returns early — and renders nothing — when every history-dependent figure covers the whole
 * classified population, because a note explaining that nothing was excluded is noise.
 */
@Composable
private fun populationNote(breadth: Breadth) {
    val excluded = breadth.smaExcluded + breadth.extremesExcluded
    val eligible = breadth.smaEligible
    if (excluded == 0 && eligible == 0) return

    Text(
        text = if (eligible == 0) {
            "No symbol has ${MarketBreadth.SMA_PERIOD}+ stored sessions yet, so the 50-day and " +
                "52-week figures are not yet measurable. The counts above cover " +
                "${breadth.classified} symbols."
        } else {
            "${MarketBreadth.SMA_PERIOD}+ stored sessions are needed for the 50-day figure, so it " +
                "covers $eligible of ${breadth.classified} symbols; $excluded lack the history."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * A [Measured] rendered as its value, or as **its reason** when it has none.
 *
 * The reason is not an error message — it is the answer. "No symbol has 51 stored sessions yet" is
 * more informative to a reader than "not yet", and it is the difference between a card that
 * explains itself and one that appears broken.
 */
private fun <T> Measured<T>.render(count: T? = valueOrNull()): String = when (this) {
    is Measured.Value -> count?.toString() ?: "—"
    is Measured.Unavailable -> reasonOrNull().orEmpty().ifBlank { "not measurable" }
}

/**
 * One labelled figure.
 *
 * Local rather than reusing a shared tile: the one in `SettingsScreen` is `private` there, and
 * widening its visibility for a single call site would couple the watchlist's population rules to an
 * unrelated screen. This one takes its value as an already-rendered string so the population logic
 * above stays in one place.
 */
@Composable
private fun PulseTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .padding(vertical = MiaSpacing.xs)
            .semantics { contentDescription = "$label: $value" },
        verticalArrangement = Arrangement.spacedBy(MiaSpacing.xs),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.titleMedium)
    }
}

private const val MARKET_PULSE_TAG: String = "market-pulse"
private const val NOT_MEASURED_YET: String =
    "No session stored yet. This reads from the daily file already saved on this device, so it " +
        "appears after the first successful refresh."