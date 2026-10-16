package com.mia.app.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mia.app.ui.theme.MiaSpacing

/**
 * The header's market-context row: the published index band, and the traded-value measurement.
 *
 * ```
 *  [NIFTY MIDCAP 150 · as of 2026-10-04]
 *  Mid- and small-cap indices have historically fallen further …   (the neutral note)
 *  Thin trading: median daily turnover ₹38 lakh over the last 60 …  (only when measured)
 * ```
 *
 * ### What is on screen, and what is deliberately not
 * A **name and a date**, and a **number with the window it came from**. That is the whole row.
 * There is no adjective about the stock, no comparison to other stocks, and no instruction:
 * `AdviceLanguage` forbids those (plan.md §1.4), and the cap band's value is that a reader can
 * check `NIFTY MIDCAP 150 · as of 2026-10-04` against NSE's own published list.
 *
 * The neutral note under a mid- or small-cap band is a statement about **past index moves**, not
 * about this security, and it is rendered in full because it does not fit in a chip. It is the one
 * piece of context here that is not a measurement, which is why it is a constant on
 * [IndexCohortNotes] where the compliance scan can find it.
 *
 * ### The liquidity line only appears when there is something to say
 * [StockContext.liquidityNotice] is `null` for a healthy median, so an ordinary large cap gets no
 * line at all — silence rather than a reassuring "fine". A row that always renders something
 * trains the eye to skip it, and the one time it matters is the time it is skimmed.
 *
 * @param viewModel obtained with `hiltViewModel()`, so it resolves against this screen's
 *   `NavBackStackEntry` and reads the same `ARG_SYMBOL` the header itself was opened with.
 */
@Composable
fun StockContextChips(
    modifier: Modifier = Modifier,
    viewModel: StockContextViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = state.context ?: return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(STOCK_CONTEXT_TAG),
        verticalArrangement = Arrangement.spacedBy(MiaSpacing.xs),
    ) {
        context.capBand?.let { band ->
            CapBandChip(
                band = band,
                modifier = Modifier.testTag(CAP_BAND_TAG),
            )
        }

        context.capBandUnavailableReason?.takeIf { context.capBand == null }?.let { reason ->
            Text(
                text = reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(CAP_BAND_ABSENT_TAG),
            )
        }

        context.liquidityNotice?.let { notice ->
            Text(
                text = notice,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(LIQUIDITY_NOTICE_TAG),
            )
        }
    }
}

/**
 * The cap band, as one outlined chip plus the optional neutral note.
 *
 * Visually the same quiet shape as [com.mia.app.ui.common.AsOfChip] and deliberately not an
 * error-coloured badge: index membership is a classification, not a warning, and colouring it like
 * one would be a verdict by typography.
 */
@Composable
private fun CapBandChip(
    band: CapBand,
    modifier: Modifier = Modifier,
) {
    Column(verticalArrangement = Arrangement.spacedBy(MiaSpacing.xs)) {
        Row(
            modifier = modifier
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    shape = RoundedCornerShape(6.dp),
                )
                .padding(horizontal = MiaSpacing.sm, vertical = 3.dp)
                // The visible chip is one line; the note and the vintage are spoken in full.
                .semantics { contentDescription = band.spokenLabel() },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MiaSpacing.xs),
        ) {
            Text(
                text = band.chipLabel(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = band.note,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Test tags, so a UI test can assert the row without matching on copy. */
private const val STOCK_CONTEXT_TAG: String = "mia_stock_detail_context"
private const val CAP_BAND_TAG: String = "mia_stock_detail_cap_band"
private const val CAP_BAND_ABSENT_TAG: String = "mia_stock_detail_cap_band_absent"
private const val LIQUIDITY_NOTICE_TAG: String = "mia_stock_detail_liquidity_notice"