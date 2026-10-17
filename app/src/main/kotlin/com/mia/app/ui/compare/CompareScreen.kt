package com.mia.app.ui.compare

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mia.app.domain.compliance.Disclosure
import com.mia.app.domain.ratios.RatioState
import com.mia.app.domain.ratios.RatioUnit
import com.mia.app.domain.ratios.SectorResolver
import com.mia.app.domain.ratios.SlotOrigin
import com.mia.app.ui.common.DisclaimerFooter
import com.mia.app.ui.common.EmptyState
import com.mia.app.ui.common.ErrorState
import com.mia.app.ui.common.LoadingState
import com.mia.app.ui.common.SectionHeader
import com.mia.app.ui.common.formatAsOf
import com.mia.app.ui.common.formatFixed
import com.mia.app.ui.common.formatSigned
import com.mia.app.ui.theme.MiaSpacing

/**
 * Two or three companies side by side (planv2 §W6, gap E6).
 *
 * ### Why alignment is the whole feature
 * A bank has no operating margin to show and an FMCG company has no net-interest-margin story, so
 * each company's list of rows is *different* — `RatioPriority.order(sector)` is plan.md §7.2 as
 * amended by §7.3 for that sector. A table that lined those lists up by position would put a bank's
 * loan book beside an FMCG company's working-capital days under one label and read as a comparison
 * while containing none. So rows are keyed by `RatioSlot` identity (see `CompareAlignment`), and a
 * company that does not publish a row gets a **stated** absence:
 *
 * | state | what the cell says |
 * |---|---|
 * | has a figure | the figure, with its own as-of date on the column |
 * | `SUPPRESSED_FOR_SECTOR` | "Not meaningful here — §7.3's reason for this sector" |
 * | `NOT_SOURCED` | "No source of ours publishes it — what we would need" |
 * | `MISSING` | "Not published on this page — which row is missing" |
 * | stale | the figure, plus "older than its window" |
 * | no page at all | the reason no page is stored, once per cell |
 *
 * There is no blank cell, no bare dash, and no zero standing in for an absent figure: a dash a reader
 * has to interpret is exactly what plan.md §13 calls a misleading blank ratio.
 *
 * ### The three things this screen must never do
 * 1. **Name a winner.** No ordering by value, no "best in class", no highlighting of the highest
 *    number in a row. plan.md's two-number design exists because a percent, a multiple and a day
 *    count answer different questions, and "higher is better" is not even true across them — a
 *    higher P/E is not better, it is a different price.
 * 2. **Combine slots into a score.** No total, no index, no weighted sum. Any such number would be a
 *    measure nothing in the app computed, presented with the authority of one it did.
 * 3. **Invent a sector.** `SectorResolver`'s own answer travels with each column, and an unresolved
 *    one is labelled as unresolved rather than quietly given the generic list as if it were right.
 *
 * @param viewModel obtained with `hiltViewModel()`. The route carries no arguments: the compared set
 *   is chosen on the screen, and a route argument would freeze it at navigation time.
 */
@Composable
fun CompareScreen(
    modifier: Modifier = Modifier,
    viewModel: CompareViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val comparison = state.comparison

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag(COMPARE_TAG),
        contentPadding = PaddingValues(
            start = MiaSpacing.lg,
            end = MiaSpacing.lg,
            top = MiaSpacing.lg,
            bottom = MiaSpacing.xxl,
        ),
        verticalArrangement = Arrangement.spacedBy(MiaSpacing.lg),
    ) {
        item(key = HEADING_KEY) { Heading() }

        item(key = CHOOSER_KEY) {
            SymbolChooser(state = state, onSymbolChanged = viewModel::onSymbolChanged)
        }

        state.errorReason?.let { reason ->
            item(key = ERROR_KEY) { ErrorState(reason = reason, onRetry = viewModel::onRetry) }
        }

        if (state.isLoading) {
            item(key = LOADING_KEY) { LoadingState() }
        }

        if (state.canCompare && comparison.companies.isEmpty() && !state.isLoading) {
            item(key = EMPTY_KEY) { EmptyState(title = EMPTY_TITLE, body = EMPTY_BODY) }
        }

        if (comparison.companies.isNotEmpty()) {
            item(key = COLUMNS_KEY) { ColumnHeaders(comparison.companies) }
        }

        items(
            count = comparison.rows.size,
            key = { index -> "row-${comparison.rows[index].slot.id.name}" },
        ) { index ->
            CompareRowCard(row = comparison.rows[index])
        }

        if (comparison.rows.isNotEmpty()) {
            item(key = NO_WINNER_KEY) { NoWinnerNote() }
        }

        item(key = FOOTER_KEY) { DisclaimerFooter(text = Disclosure.DATA_SOURCE) }
    }
}

// ------------------------------------------------------------------------------ heading

@Composable
private fun Heading(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MiaSpacing.xs),
    ) {
        Text(
            text = TITLE,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = HEADING_SUBTITLE,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The three symbol fields.
 *
 * Text fields rather than a picker because this route takes no symbol argument and a watchlist picker
 * would pull the whole stock list into a screen whose job is alignment — the same trade
 * `SettingsScreen` makes for its share field, and documented there for the same reason.
 */
@Composable
private fun SymbolChooser(
    state: CompareUiState,
    onSymbolChanged: (Int, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MiaSpacing.sm),
    ) {
        SectionHeader(title = SECTION_CHOOSE, subtitle = CHOOSE_SUBTITLE)
        state.symbols.forEachIndexed { index, value ->
            OutlinedTextField(
                value = value,
                onValueChange = { text -> onSymbolChanged(index, text.uppercase()) },
                label = { Text(text = "Company ${index + 1}") },
                placeholder = { Text(text = SYMBOL_PLACEHOLDER) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(SYMBOL_FIELD_TAG_PREFIX + index),
            )
        }
        state.blockingReason()?.let { reason ->
            Text(
                text = reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(COMPARE_BLOCKING_TAG),
            )
        }
    }
}

// ----------------------------------------------------------------------------- the table

/**
 * One header per company: the symbol, the sector it resolved to, and its as-of date.
 *
 * The sector is on the header rather than buried per cell because it is what makes a row's absence
 * interpretable: "not meaningful here" only means something if the reader knows "here" is Banking.
 * An unresolved sector says so in the same words the Fundamentals tab uses, rather than presenting
 * the generic ratio list as though it were the company's own.
 */
@Composable
private fun ColumnHeaders(companies: List<CompareCompany>, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MiaSpacing.md),
            horizontalArrangement = Arrangement.spacedBy(MiaSpacing.sm),
        ) {
            companies.forEach { company ->
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(MiaSpacing.xs),
                ) {
                    Text(
                        text = company.symbol,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.testTag(HEADER_TAG_PREFIX + company.symbol),
                    )
                    Text(
                        text = sectorLabel(company),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    company.asOf?.let {
                        Text(text = formatAsOf(it), style = MaterialTheme.typography.labelSmall)
                    }
                    if (company.isStale) {
                        Text(
                            text = STALE_CHIP,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

/** `Banking` when resolved; `Sector unresolved — generic list` when not. */
private fun sectorLabel(company: CompareCompany): String =
    if (company.sectorResolved) {
        SectorResolver.displayNameOf(company.sector)
    } else {
        "$SECTOR_UNRESOLVED — $GENERIC_LIST_NOTE"
    }

/**
 * One `RatioSlot`, and every company's figure for it.
 *
 * The row's subtitle carries three separate facts the reader would otherwise have to infer: where the
 * row came from (a plan rank, a §7.3 sector slot, or a supplementary row the plan never ranked), and
 * * which companies asked for it. "Banking only" and "both" are different rows, and saying which one
 * this is stops a reader from wondering why a row is on the screen at all.
 */
@Composable
private fun CompareRowCard(row: CompareRow, modifier: Modifier = Modifier) {
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
                .padding(MiaSpacing.md),
            verticalArrangement = Arrangement.spacedBy(MiaSpacing.sm),
        ) {
            Text(
                text = row.slot.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = rowSubtitle(row),
                style = MaterialTheme.typography.labelSmall,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MiaSpacing.sm),
            ) {
                row.cells.forEach { cell ->
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(MiaSpacing.xs),
                    ) {
                        cell.values.forEach { value ->
                            ValueCell(value = value)
                        }
                    }
                }
            }
        }
    }
}

/** `Ranked by the plan · §7.2 rank 3 · listed for HDFCBANK`, or "for all 3 companies". */
private fun rowSubtitle(row: CompareRow): String {
    val rank = if (row.origin == SlotOrigin.PLAN && row.slot.planRank > 0) {
        " · $RANK_LABEL ${row.slot.planRank}"
    } else {
        ""
    }
    val askedBy = if (row.askedBy.size == row.cells.size) {
        "$ASKED_BY_ALL ${row.cells.size} companies"
    } else if (row.askedBy.size == 1) {
        "$ASKED_BY_SINGULAR ${row.askedBy.first()}"
    } else {
        "$ASKED_BY_PLURAL ${row.askedBy.joinToString(", ")}"
    }
    return "${CompareAlignment.originLabel(row.origin)}$rank · $askedBy"
}

/**
 * One ratio's figure, or the reason there is none.
 *
 * A stale figure keeps its number and gains the reason beside it: it is still true of its own date,
 * and plan.md §10's rule is that an old value must be *visible* as old, not replaced by nothing.
 */
@Composable
private fun ValueCell(value: CompareValue, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MiaSpacing.xs),
    ) {
        Text(text = value.id.title, style = MaterialTheme.typography.labelSmall)
        if (value.isRenderable) {
            Text(
                text = formatValue(value),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (value.availability.state == RatioState.STALE) {
                Text(
                    text = STALE_CELL_NOTE,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        } else {
            Text(
                text = value.absenceText(),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag(ABSENT_TAG_PREFIX + value.id.name),
            )
        }
    }
}

/**
 * The figure, with its unit.
 *
 * The unit comes from `RatioId.unit` rather than from the value, so a percent can never be printed
 * as a multiple — the failure mode of formatting a bare `Double` in a column where several kinds of
 * figure sit next to each other.
 */
private fun formatValue(value: CompareValue): String {
    val number = requireNotNull(value.availability.value)
    return when (value.unit) {
        RatioUnit.PERCENT -> "${formatFixed(number, 2)}%"
        RatioUnit.RATIO -> formatFixed(number, 2)
        RatioUnit.MULTIPLE -> "${formatFixed(number, 2)}×"
        RatioUnit.CRORE -> "₹${formatFixed(number, 2)} Cr"
        RatioUnit.DAYS -> "${formatFixed(number, 0)} days"
        // Rendered as points with their sign, because "the FII holding change" is a move between two
        // quarters and "-1.48%" is a different statement from "down 1.48 points".
        RatioUnit.PERCENTAGE_POINTS -> "${formatSigned(number, 2)} pts"
        RatioUnit.RUPEE_PER_SHARE -> "₹${formatFixed(number, 2)} per share"
    }
}

/**
 * The statement that keeps this a comparison rather than a contest.
 *
 * Rendered under the table rather than above it, because the question it answers — "why is nothing
 * marked as better?" — arrives after the reader has been looking for a winner and not found one.
 */
@Composable
private fun NoWinnerNote(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MiaSpacing.md),
            verticalArrangement = Arrangement.spacedBy(MiaSpacing.xs),
        ) {
            Text(text = NO_WINNER_HEADING, style = MaterialTheme.typography.titleSmall)
            Text(text = NO_WINNER_BODY, style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ------------------------------------------------------------------------------ the copy

private const val TITLE = "Compare stocks"
private const val HEADING_SUBTITLE =
    "Two or three companies, with each row lined up by the ratio it actually is. A bank has no " +
        "operating margin to show and an FMCG company has no net-interest-margin story, so a row " +
        "either is one of their rows or it says why it is not."

private const val SECTION_CHOOSE = "Which companies"
private const val CHOOSE_SUBTITLE =
    "Two at a time is the most that reads well on a phone. Leave a box empty to compare two."
private const val SYMBOL_PLACEHOLDER = "RELIANCE"

private const val EMPTY_TITLE = "Nothing to compare yet"
private const val EMPTY_BODY =
    "Name two or three companies above. Their figures come from what this device already holds, " +
        "read exactly as the Fundamentals tab reads them — so a page older than its freshness " +
        "window is refetched from screener.in when you name that company here, and one that has " +
        "never been fetched is a column with that reason on it."

private const val SECTOR_UNRESOLVED = "Sector unresolved"
private const val GENERIC_LIST_NOTE = "generic ratio list"
private const val STALE_CHIP = "Older than 7 days"

private const val RANK_LABEL = "§7.2 rank"
private const val ASKED_BY_SINGULAR = "listed for"
private const val ASKED_BY_PLURAL = "listed for"
private const val ASKED_BY_ALL = "listed for all"

private const val STALE_CELL_NOTE = "Older than its window; true of its own date, not current"

private const val NO_WINNER_HEADING = "Nothing here is ranked"
private const val NO_WINNER_BODY =
    "There is no best company on this screen and no score that sums these rows, because a " +
        "percentage, a multiple and a day count answer different questions and 'higher' is not " +
        "better in any of them. Compare the rows your two companies actually share, and take the " +
        "reading to the company's own fundamentals tab for the full picture."

// Test tags.
private const val COMPARE_TAG = "mia_compare"
private const val SYMBOL_FIELD_TAG_PREFIX = "mia_compare_symbol_"
private const val COMPARE_BLOCKING_TAG = "mia_compare_blocking"
private const val HEADER_TAG_PREFIX = "mia_compare_header_"
private const val ABSENT_TAG_PREFIX = "mia_compare_absent_"

// Lazy-list keys.
private const val HEADING_KEY = "compare.heading"
private const val CHOOSER_KEY = "compare.chooser"
private const val ERROR_KEY = "compare.error"
private const val LOADING_KEY = "compare.loading"
private const val EMPTY_KEY = "compare.empty"
private const val COLUMNS_KEY = "compare.columns"
private const val NO_WINNER_KEY = "compare.no_winner"
private const val FOOTER_KEY = "compare.footer"