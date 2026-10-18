package com.mia.app.ui.sectors

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
import androidx.compose.ui.graphics.Color
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
import com.mia.app.domain.sectors.SectorBenchmark
import com.mia.app.domain.sectors.SectorComparison
import com.mia.app.domain.sectors.SectorComparisonBuilder
import com.mia.app.domain.sectors.SectorMedian
import com.mia.app.domain.sectors.SectorRow
import com.mia.app.domain.sectors.SectorValue
import com.mia.app.ui.common.DisclaimerFooter
import com.mia.app.ui.common.EmptyState
import com.mia.app.ui.common.ErrorState
import com.mia.app.ui.common.LoadingState
import com.mia.app.ui.common.formatAsOf
import com.mia.app.ui.common.formatFixed
import com.mia.app.ui.common.formatSigned
import com.mia.app.ui.theme.MiaSpacing

/**
 * One company's ratios beside the sector median for the same ratio (planv2 §W3).
 *
 * ### The sentence that is on screen before any number
 * [POPULATION_DISCLOSURE] is not a disclaimer parked at the bottom. It is the first card, above
 * every figure, because the failure this screen has to prevent is a reader seeing
 * "median ROCE 14.2%" and believing it is a fact about the industry. Every peer in a median here is
 * a screener.in page **this device already holds** — no source of ours publishes a sector's
 * membership list (plan.md §3) — so the honest framing has to be established before the first
 * number, not explained afterwards.
 *
 * ### What a reader sees, per row
 * ```
 *   Return on capital employed · Ranked by the plan · §7.2 rank 1
 *   This company          18.42%
 *   Sector median         14.10%   n=7 of the 9 other IT companies this device holds a page for
 * ```
 * Two numbers, nothing derived from them. There is no difference, no ratio-to-median, no
 * percentile and no colour anywhere in this file — see `SectorRow`'s KDoc for why a subtraction
 * between one company's page and a median of several cached pages of mixed dates would look far
 * more solid than either input is.
 *
 * ### Where the four possible answers appear
 * | the median is… | the screen says |
 * |---|---|
 * | published | the figure, then `n` of the denominator |
 * | withheld, too few peers | the count it has and the count it needs |
 * | `SUPPRESSED_FOR_SECTOR` | "Not meaningful here" + §7.3's reason for **this** sector |
 * | `NOT_SOURCED` | "No source of ours publishes it" + what we would need |
 *
 * The suppression case is the one worth stating plainly: a bank's debt/equity row shows *no median
 * at all*, and the peers are filtered by sector before a value is ever looked for, so the row can
 * never be completed by borrowing another sector's figures. A blank there would read as "no data";
 * the reason reads as "not a meaningful question for this sector", which is the difference
 * plan.md §13 is about.
 *
 * ### The three things this screen must never do
 * 1. **Render a median without its sample size.** There is no code path that prints a value
 *    without [SectorMedian.populationNote] beside it — [SectorMedian] cannot be constructed that
 *    way.
 * 2. **Pool an unresolved sector.** `Sector.OTHER` is everything the app declined to classify; its
 *    population is empty with a stated reason, and the reason is printed instead of a number.
 * 3. **Rank.** Nothing here compares a figure to another figure in a way that produces a verdict.
 *
 * @param viewModel obtained with `hiltViewModel()`. The route carries no symbol argument, matching
 *   `CompareScreen`: the company is chosen on the screen, and a route argument would freeze it at
 *   navigation time.
 */
@Composable
fun SectorScreen(
    modifier: Modifier = Modifier,
    viewModel: SectorViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val comparison = state.comparison

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag(SECTOR_TAG),
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

        if (comparison == null && !state.isLoading && state.errorReason == null) {
            item(key = EMPTY_KEY) { EmptyState(title = EMPTY_TITLE, body = EMPTY_BODY) }
        }

        if (comparison != null) {
            item(key = DISCLOSURE_KEY) { DisclosureCard() }
            item(key = SUBJECT_KEY) { SubjectCard(comparison = comparison) }

            state.coverageNotes.forEachIndexed { index, note ->
                item(key = "coverage-$index") { CoverageNote(note) }
            }

            items(
                count = comparison.rows.size,
                key = { index -> "row-${comparison.rows[index].slot.id.name}" },
            ) { index ->
                SectorRowCard(row = comparison.rows[index])
            }

            item(key = POPULATION_KEY) { PopulationCard(comparison = comparison) }
            item(key = NO_VERDICT_KEY) { NoVerdictNote() }
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
 * The symbol field.
 *
 * A text field for the same reason `CompareScreen` uses one: this route takes no symbol argument,
 * and a watchlist picker would pull the whole stock list into a screen whose job is the median, not
 * the selection. The reader types the company whose sector they want to read.
 */
@Composable
private fun SymbolChooser(
    state: SectorUiState,
    onSymbolChanged: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MiaSpacing.sm),
    ) {
        OutlinedTextField(
            value = state.symbol,
            onValueChange = onSymbolChanged,
            label = { Text(text = SYMBOL_LABEL) },
            placeholder = { Text(text = SYMBOL_PLACEHOLDER) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(SYMBOL_FIELD_TAG),
        )
        state.blockingReason()?.let { reason ->
            Text(
                text = reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(SECTOR_BLOCKING_TAG),
            )
        }
    }
}

// --------------------------------------------------------------------- the two mandatory cards

/**
 * What the peers are, said before any figure.
 *
 * Rendered above every row and not once at the bottom, because the reading a reader brings to the
 * first number is the thing being corrected here.
 */
@Composable
private fun DisclosureCard(modifier: Modifier = Modifier) {
    NoteCard(
        heading = DISCLOSURE_HEADING,
        body = SectorComparisonBuilder.POPULATION_DISCLOSURE,
        container = MaterialTheme.colorScheme.secondaryContainer,
        content = MaterialTheme.colorScheme.onSecondaryContainer,
        testTag = DISCLOSURE_TAG,
        modifier = modifier,
    )
}

/**
 * Who is being compared, and what this device knows about them.
 *
 * The sector is on this card rather than per cell because it is what makes an absence
 * interpretable: "not meaningful here" only means something if the reader can see that "here" is
 * Banking. An unresolved sector says so in the same words the Fundamentals tab uses rather than
 * quietly presenting the generic list as though it were the company's own.
 */
@Composable
private fun SubjectCard(comparison: SectorComparison, modifier: Modifier = Modifier) {
    val subject = comparison.subject
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MiaSpacing.xs),
    ) {
        Text(
            text = subject.symbol,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.testTag(SUBJECT_TAG),
        )
        Text(
            text = subject.name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = if (subject.resolution.resolved) {
                SectorResolver.displayNameOf(subject.sector)
            } else {
                "$SECTOR_UNRESOLVED — $GENERIC_LIST_NOTE"
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(SUBJECT_SECTOR_TAG),
        )
        subject.asOf?.let {
            Text(text = formatAsOf(it), style = MaterialTheme.typography.labelSmall)
        }
        if (subject.isStale) {
            Text(
                text = STALE_CHIP,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (!subject.resolution.resolved) {
            Text(
                text = subject.resolution.evidence,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * One storage-layer note — why some rows carry no median even on a well-populated device.
 *
 * Rendered once, above the rows, so the reader learns the reason once rather than meeting an
 * unexplained absence on three separate rows and drawing a conclusion about the peers.
 */
@Composable
private fun CoverageNote(note: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(COVERAGE_TAG),
        verticalArrangement = Arrangement.spacedBy(MiaSpacing.xs),
    ) {
        Text(
            text = COVERAGE_HEADING,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = note, style = MaterialTheme.typography.bodySmall)
    }
}

// ------------------------------------------------------------------------------ the rows

/**
 * One `RatioSlot`: the subject's figure and the sector median, side by side.
 *
 * The row's subtitle carries two separate facts the reader would otherwise have to infer — where
 * the row came from (a plan rank, a §7.3 sector slot, or a supplementary row the plan never
 * ranked), and the sector whose medians fill the right-hand column. Each ratio in the slot gets its
 * own pair of lines, because plan.md §7.2's "debt/equity + interest coverage" is one card with two
 * numbers and the two medians are different quantities.
 */
@Composable
private fun SectorRowCard(row: SectorRow, modifier: Modifier = Modifier) {
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
            Text(text = rowSubtitle(row), style = MaterialTheme.typography.labelSmall)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            row.ids.forEachIndexed { index, id ->
                RatioPair(
                    title = id.title,
                    stock = row.stock[index],
                    benchmark = row.benchmarks[index],
                )
            }
        }
    }
}

/** `Ranked by the plan · §7.2 rank 3 · Medians from the Banking peers on this device`. */
private fun rowSubtitle(row: SectorRow): String {
    val origin = if (row.origin == SlotOrigin.PLAN && row.slot.planRank > 0) {
        "$ORIGIN_PLAN · $RANK_LABEL ${row.slot.planRank}"
    } else {
        when (row.origin) {
            SlotOrigin.PLAN -> ORIGIN_PLAN
            SlotOrigin.SECTOR_SPECIFIC -> ORIGIN_SECTOR_SPECIFIC
            SlotOrigin.SUPPLEMENTARY -> ORIGIN_SUPPLEMENTARY
        }
    }
    val peers = if (row.isSectorSpecific) {
        "$PEER_LABEL ${SectorResolver.displayNameOf(row.sector)}"
    } else {
        PEER_LABEL_ALL
    }
    return "$origin · $peers"
}

/**
 * One ratio's two figures, or the reason one of them is absent.
 *
 * The right column is never empty and never a dash. A published median always carries
 * [SectorMedian.populationNote]; an absent one carries the reason it was withheld. There is no
 * third possibility, which is why `SectorBenchmark` refuses to produce a value below its floor
 * rather than producing one and letting the UI decide how to caveat it.
 */
@Composable
private fun RatioPair(
    title: String,
    stock: SectorValue,
    benchmark: SectorMedian,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(PAIR_TAG_PREFIX + benchmark.id.name),
        verticalArrangement = Arrangement.spacedBy(MiaSpacing.xs),
    ) {
        Text(text = title, style = MaterialTheme.typography.labelSmall)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MiaSpacing.sm),
        ) {
            FigureColumn(
                label = STOCK_COLUMN,
                modifier = Modifier.weight(1f),
            ) {
                if (stock.isRenderable) {
                    FigureText(value = requireNotNull(stock.availability.value), unit = stock.unit)
                    if (stock.availability.state == RatioState.STALE) {
                        Text(
                            text = STALE_CELL_NOTE,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                } else {
                    Text(
                        text = stock.absenceText(),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag(ABSENT_STOCK_TAG_PREFIX + benchmark.id.name),
                    )
                }
            }
            FigureColumn(
                label = MEDIAN_COLUMN,
                modifier = Modifier.weight(1f),
            ) {
                if (benchmark.isPublished) {
                    FigureText(
                        value = requireNotNull(benchmark.availability.value),
                        unit = benchmark.id.unit,
                    )
                    // The `n` sits under the figure, not in a tooltip and not in the header,
                    // because a median read without its sample size is exactly the claim this
                    // screen refuses to make.
                    Text(
                        text = requireNotNull(benchmark.populationNote),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.testTag(SAMPLE_TAG_PREFIX + benchmark.id.name),
                    )
                } else {
                    Text(
                        text = benchmarkStatePrefix(benchmark.availability.state) +
                            benchmark.note(),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag(ABSENT_MEDIAN_TAG_PREFIX + benchmark.id.name),
                    )
                }
            }
        }
    }
}

@Composable
private fun FigureColumn(
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(MiaSpacing.xs),
    ) {
        Text(text = label, style = MaterialTheme.typography.labelSmall)
        content()
    }
}

@Composable
private fun FigureText(value: Double, unit: RatioUnit) {
    Text(
        text = formatValue(value, unit),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

/**
 * The two states that are *decisions* rather than gaps, named before the reason.
 *
 * A suppressed ratio and an unsourced one read identically — no figure — unless the reader is told
 * which happened, and the two call for completely different responses: one says "don't ask this of
 * a bank", the other says "we have no source for it".
 */
private fun benchmarkStatePrefix(state: RatioState): String = when (state) {
    RatioState.SUPPRESSED_FOR_SECTOR -> "Not meaningful for this sector — "
    RatioState.NOT_SOURCED -> "No source of ours publishes it — "
    RatioState.MISSING -> "Median withheld — "
    RatioState.STALE, RatioState.AVAILABLE -> ""
}

// ------------------------------------------------------------------------------- the close

/**
 * What the peer group actually is, restated after the table.
 *
 * The reader has just seen several `n=… of the …` lines; this collects them into one fact, and names
 * the floor so a reader who wonders why a ratio is missing learns that the answer is a count rather
 * than a bug.
 */
@Composable
private fun PopulationCard(comparison: SectorComparison, modifier: Modifier = Modifier) {
    NoteCard(
        heading = POPULATION_HEADING,
        body = comparison.population.populationSummary() + "\n\n" + FLOOR_NOTE,
        container = MaterialTheme.colorScheme.surfaceVariant,
        content = MaterialTheme.colorScheme.onSurfaceVariant,
        testTag = POPULATION_TAG,
        modifier = modifier,
    )
}

/**
 * The statement that keeps this a measurement rather than a verdict.
 *
 * Rendered under the table rather than above it, because the question it answers — "why is nothing
 * marked as better?" — arrives after the reader has been looking for a winner and not found one.
 */
@Composable
private fun NoVerdictNote(modifier: Modifier = Modifier) {
    NoteCard(
        heading = NO_VERDICT_HEADING,
        body = SectorComparisonBuilder.NO_VERDICT_NOTE,
        container = MaterialTheme.colorScheme.secondaryContainer,
        content = MaterialTheme.colorScheme.onSecondaryContainer,
        testTag = NO_VERDICT_TAG,
        modifier = modifier,
    )
}

@Composable
private fun NoteCard(
    heading: String,
    body: String,
    container: Color,
    content: Color,
    testTag: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag(testTag),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MiaSpacing.md),
            verticalArrangement = Arrangement.spacedBy(MiaSpacing.xs),
        ) {
            Text(text = heading, style = MaterialTheme.typography.titleSmall)
            Text(text = body, style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ---------------------------------------------------------------------------- formatting

/**
 * The figure, with its unit.
 *
 * The unit comes from `RatioId.unit` rather than from the value, so a percent can never be printed
 * as a multiple — the failure mode of formatting a bare `Double` in a column where several kinds of
 * figure sit next to each other.
 */
private fun formatValue(value: Double, unit: RatioUnit): String = when (unit) {
    RatioUnit.PERCENT -> "${formatFixed(value, 2)}%"
    RatioUnit.RATIO -> formatFixed(value, 2)
    RatioUnit.MULTIPLE -> "${formatFixed(value, 2)}×"
    RatioUnit.CRORE -> "₹${formatFixed(value, 2)} Cr"
    RatioUnit.DAYS -> "${formatFixed(value, 0)} days"
    RatioUnit.PERCENTAGE_POINTS -> "${formatSigned(value, 2)} pts"
    RatioUnit.RUPEE_PER_SHARE -> "₹${formatFixed(value, 2)} per share"
}

// ------------------------------------------------------------------------------ the copy

private const val TITLE = "Sector comparison"
private const val HEADING_SUBTITLE =
    "A company's own ratios beside the median for its sector. The median is over the peers this " +
        "device has already stored, never over an industry we have a list of — because we do not " +
        "have one."

private const val SYMBOL_LABEL = "Company"
private const val SYMBOL_PLACEHOLDER = "RELIANCE"

private const val EMPTY_TITLE = "Nothing to compare yet"
private const val EMPTY_BODY =
    "Name a company above. Its ratios and the sector medians beside them come from the pages this " +
        "device already holds, read exactly as the Fundamentals tab reads them. A median needs " +
        "several peers in the same sector, so a company you have just opened once will show its own " +
        "figures and, for every ratio, the reason no median exists yet."

private const val DISCLOSURE_HEADING = "Where the peers come from"
private const val SECTOR_UNRESOLVED = "Sector unresolved"
private const val GENERIC_LIST_NOTE = "generic ratio list"
private const val STALE_CHIP = "Older than 7 days"
private const val STALE_CELL_NOTE = "Older than its window; true of its own date, not current"

private const val STOCK_COLUMN = "This company"
private const val MEDIAN_COLUMN = "Sector median"

private const val ORIGIN_PLAN = "Ranked by the plan"
private const val ORIGIN_SECTOR_SPECIFIC = "Required for this sector's reading (plan.md §7.3)"
private const val ORIGIN_SUPPLEMENTARY = "Published by our source, ranked by no plan row"
private const val RANK_LABEL = "§7.2 rank"
private const val PEER_LABEL = "Medians from the"
private const val PEER_LABEL_ALL = "Medians from this sector's peers on this device"

private const val COVERAGE_HEADING = "What has no median, and why"

private const val POPULATION_HEADING = "The peer group"
private const val FLOOR_NOTE =
    "A median is published only over at least ${SectorBenchmark.MINIMUM_SAMPLE} peers with " +
        "figures inside their freshness window. Below that this screen shows the count instead of " +
        "the number, because a median of one company is that company's own figure and a median of " +
        "two is their average — neither is a sector, and printing either would invite a reading the " +
        "figure cannot support."

private const val NO_VERDICT_HEADING = "Nothing here is ranked"

// Test tags.
private const val SECTOR_TAG = "mia_sector"
private const val SYMBOL_FIELD_TAG = "mia_sector_symbol"
private const val SECTOR_BLOCKING_TAG = "mia_sector_blocking"
private const val DISCLOSURE_TAG = "mia_sector_disclosure"
private const val SUBJECT_TAG = "mia_sector_subject"
private const val SUBJECT_SECTOR_TAG = "mia_sector_subject_sector"
private const val COVERAGE_TAG = "mia_sector_coverage"
private const val POPULATION_TAG = "mia_sector_population"
private const val NO_VERDICT_TAG = "mia_sector_no_verdict"
private const val PAIR_TAG_PREFIX = "mia_sector_pair_"
private const val SAMPLE_TAG_PREFIX = "mia_sector_sample_"
private const val ABSENT_STOCK_TAG_PREFIX = "mia_sector_absent_stock_"
private const val ABSENT_MEDIAN_TAG_PREFIX = "mia_sector_absent_median_"

// Lazy-list keys.
private const val HEADING_KEY = "sector.heading"
private const val CHOOSER_KEY = "sector.chooser"
private const val ERROR_KEY = "sector.error"
private const val LOADING_KEY = "sector.loading"
private const val EMPTY_KEY = "sector.empty"
private const val DISCLOSURE_KEY = "sector.disclosure"
private const val SUBJECT_KEY = "sector.subject"
private const val POPULATION_KEY = "sector.population"
private const val NO_VERDICT_KEY = "sector.no_verdict"
private const val FOOTER_KEY = "sector.footer"
