package com.mia.app.ui.patterns

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mia.app.domain.compliance.Disclosure
import com.mia.app.domain.model.BreakoutTrigger
import com.mia.app.domain.model.HitRate
import com.mia.app.domain.model.Match
import com.mia.app.domain.model.MatchScoreLabels
import com.mia.app.domain.model.MeasuredMove
import com.mia.app.domain.model.ProjectedPath
import com.mia.app.domain.model.RedFlag
import com.mia.app.domain.model.Source
import com.mia.app.domain.model.Timeframe
import com.mia.app.domain.ratios.Freshness
import com.mia.app.domain.ratios.FreshnessKind
import com.mia.app.domain.ratios.FreshnessPolicy
import com.mia.app.ui.chart.ChartState
import com.mia.app.ui.chart.PriceChart
import com.mia.app.ui.common.AsOfChip
import com.mia.app.ui.common.ChangePercentText
import com.mia.app.ui.common.DisclaimerFooter
import com.mia.app.ui.common.EmptyState
import com.mia.app.ui.common.ErrorState
import com.mia.app.ui.common.EvidenceDialog
import com.mia.app.ui.common.LoadingState
import com.mia.app.ui.common.MatchScoreChip
import com.mia.app.ui.common.NOT_AVAILABLE
import com.mia.app.ui.common.RedFlagBanner
import com.mia.app.ui.common.ScoreExplainCard
import com.mia.app.ui.common.SectionHeader
import com.mia.app.ui.common.TimeframeToggle
import com.mia.app.ui.common.TrackRecordRow
import com.mia.app.ui.common.UiStrings
import com.mia.app.ui.common.displayName
import com.mia.app.ui.common.formatDate
import com.mia.app.ui.common.formatPrice
import com.mia.app.ui.common.shortLabel
import com.mia.app.ui.theme.MiaSpacing
import java.time.LocalDate

/**
 * The Patterns tab (plan.md §6.5, §6.6; decision D2 and D6).
 *
 * ### Section order, and why
 * ```
 *  red-flag banner        pinned, OUTSIDE the scroll — plan.md §8.3
 *  not-published note     "today's file is not out yet" is not an error — plan.md §3.1
 *  header                symbol, last close, change, as-of, bars-stored-vs-needed
 *  timeframe toggle       decision D1: 1D / 1W, nothing else
 *  chart                  PriceChart draws the "not enough history" refusal itself
 *  overlay legend         the three dash patterns, named, so they are readable apart
 *  pattern matches        name + MatchScoreChip + what the score means, ranked
 *  selected match         ScoreExplainCard (four factors), geometry, path, track record
 *  geometry               the measured move, labelled pattern geometry
 *  directional path       a separate section, labelled a projection, never merged with the above
 *  breakout alerts        a third section — decision D2
 *  suppressed matches     explained, not silently hidden
 *  scan notes             what could not be measured
 *  flag coverage          what could not be evaluated
 *  data-source footer     plan.md §3
 * ```
 *
 * ### The five things this screen must never do
 * 1. **Emit a verdict or a forecast level.** There is no code path here that produces either: the
 *    only level on screen is the measured move, and it is labelled
 *    [MatchScoreLabels.PROJECTION_LABEL] with [MatchScoreLabels.NOT_A_TARGET_WARNING] attached.
 * 2. **Render the score as a probability.** [MatchScoreChip] prints a bare number, and
 *    `MatchScoreLabels.SUBTITLE` + `MatchScoreLabels.DISCLAIMER` + `Disclosure.SCORE_MEANING` sit
 *    beside it on the same card.
 * 3. **Combine the geometry with the hit rate.** The two live in separate sections and no number on
 *    this screen is derived from both.
 * 4. **Hide what it suppressed.** [SuppressedSection] prints the engine's own reason for every
 *    candidate that was not drawn, including the bar gate.
 * 5. **Say "no patterns found" without saying what was looked for.** The empty state names the
 *    render threshold.
 *
 * @param viewModel obtained with `hiltViewModel()`, so `symbol` and `timeframe` come from the
 *   `patterns/{symbol}/{timeframe}` route rather than from a hand-passed argument that could disagree
 *   with the back stack.
 */
@Composable
fun PatternsScreen(
    modifier: Modifier = Modifier,
    viewModel: PatternsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var evidenceFlag by remember { mutableStateOf<RedFlag?>(null) }

    evidenceFlag?.let { flag ->
        EvidenceDialog(flag = flag, onDismissRequest = { evidenceFlag = null })
    }

    // The Explorer's glossary. Hosted here rather than inside the section so it survives the section
    // being recomposed when the window changes underneath it.
    state.glossaryFor?.let { patternName ->
        PatternGlossaryDialog(
            patternName = patternName,
            onDismiss = { viewModel.onGlossaryToggled(null) },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag(PATTERNS_TAG),
    ) {
        // plan.md §8.3: pinned at the top of the tab, not buried in a section.
        RedFlagBanner(
            flags = state.redFlags,
            onFlagClick = { evidenceFlag = it },
            modifier = Modifier.padding(horizontal = MiaSpacing.lg, vertical = MiaSpacing.sm),
        )

        state.notPublishedNote?.let { note ->
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = MiaSpacing.lg),
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = MiaSpacing.lg,
                end = MiaSpacing.lg,
                top = MiaSpacing.sm,
                bottom = MiaSpacing.xxl,
            ),
            verticalArrangement = Arrangement.spacedBy(MiaSpacing.lg),
        ) {
            item(key = HEADER_KEY) { PatternsHeader(state) }

            item(key = TIMEFRAME_KEY) {
                TimeframeToggle(
                    selected = state.timeframe,
                    onSelect = viewModel::onTimeframeSelected,
                )
            }

            // The Explorer sits directly under the timeframe toggle, before the chart, because it is
            // what the reader came for: the gate's verdict on every pattern, named. The chart and the
            // existing match list follow as the detail.
            state.evaluation?.let { evaluation ->
                item(key = WINDOW_KEY) {
                    WindowSelector(
                        state = state,
                        onSelect = viewModel::onWindowSelected,
                    )
                }
                item(key = EXPLORER_KEY) {
                    PatternExplorerSection(
                        state = state,
                        evaluation = evaluation,
                        onGlossaryClick = viewModel::onGlossaryToggled,
                    )
                }
            }

            if (state.isLoading && state.barCount == 0) {
                item(key = LOADING_KEY) { LoadingState() }
            }

            state.errorReason?.let { reason ->
                item(key = ERROR_KEY) { ErrorState(reason = reason, onRetry = viewModel::onRetry) }
            }

            item(key = CHART_KEY) { ChartSection(state) }

            item(key = GATE_KEY) { GateSection(state) }

            item(key = MATCHES_KEY) { MatchListSection(state, viewModel::onMatchSelected) }

            val selected = state.selectedMatch
            selected?.measuredMove?.let { move ->
                item(key = GEOMETRY_KEY) {
                    GeometrySection(
                        match = selected,
                        move = move,
                        asOf = state.asOf,
                        source = Source.NSE_BHAVCOPY,
                    )
                }
            }

            state.projection.path?.let { path ->
                item(key = PATH_KEY) { ProjectedPathSection(path = path) }
            }

            state.projection.trigger?.let { trigger ->
                item(key = TRIGGER_KEY) {
                    BreakoutSection(trigger = trigger, timeframe = state.timeframe)
                }
            }

            if (state.suppressed.isNotEmpty()) {
                item(key = SUPPRESSED_KEY) { SuppressedSection(state) }
            }

            if (state.scanNotes.isNotEmpty()) {
                item(key = NOTES_KEY) { NotesSection(state) }
            }

            if (state.flagCoverageIncomplete) {
                item(key = FLAG_COVERAGE_KEY) { FlagCoverageSection(state) }
            }

            item(key = FOOTER_KEY) { DisclaimerFooter(text = Disclosure.DATA_SOURCE) }
        }
    }
}

// ------------------------------------------------------------------------------ header

/**
 * Symbol, last close, day change, the as-of marker, and the bar counts against the gate.
 *
 * The counts are always visible, in both directions. They are what makes "not enough history"
 * checkable by the reader rather than something they have to take on trust, and plan.md §6.4 makes
 * the gate a hard condition rather than a preference.
 */
@Composable
private fun PatternsHeader(state: PatternsUiState, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Column {
                Text(
                    text = state.symbol.ifEmpty { NOT_AVAILABLE_SYMBOL },
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    text = "${UiStrings.LAST_CLOSE}  ${formatPrice(state.lastClose)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ChangePercentText(changePercent = state.changePercent)
        }
        state.asOf?.let { asOf ->
            AsOfChip(
                source = Source.NSE_BHAVCOPY,
                asOf = asOf,
                freshness = barFreshness(asOf),
                modifier = Modifier.padding(top = MiaSpacing.sm),
            )
        }
        Text(
            text = barCountLine(state),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = MiaSpacing.xs),
        )
        if (state.suppressed.isNotEmpty() && state.matches.isNotEmpty()) {
            Text(
                text = suppressedHintLine(state),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = MiaSpacing.xs),
            )
        }
    }
}

/**
 * The bar counts, stated as counts.
 *
 * Never a progress bar and never a percentage: "34 of 60 bars" is a fact, and a gauge would invite
 * the reader to wonder what 57 % of a pattern score would mean — which is exactly the
 * probability-shaped question plan.md §6.1 forbids.
 */
private fun barCountLine(state: PatternsUiState): String {
    val have = state.barCount
    val need = state.minimumBars
    return if (have >= need) {
        "$have ${state.timeframe.name} bars stored · $need needed · the gate is met"
    } else {
        "$have ${state.timeframe.name} bars stored · $need needed · " +
            "$GATE_SHORT ${need - have}"
    }
}

/** A pointer to the suppressed section, so its absence from above the fold is not mistaken. */
private fun suppressedHintLine(state: PatternsUiState): String =
    "${state.suppressed.size} further ${if (state.suppressed.size == 1) "formation" else "formations"} " +
        "${if (state.suppressed.size == 1) "was" else "were"} detected but not drawn. " +
        "Each one says why at the bottom of this screen."

/** EOD bars get the one-day window: the bhavcopy is a daily file (plan.md §3.1). */
private fun barFreshness(asOf: LocalDate): Freshness = FreshnessPolicy.inspect(
    asOf = asOf,
    today = FreshnessPolicy.todayInMarket(),
    kind = FreshnessKind.DAILY_CANDLE,
)

// ------------------------------------------------------------------------------ chart

/**
 * The chart and its overlays.
 *
 * [PriceChart] renders the plan.md §6.4 refusal itself when there are too few bars, so this section
 * only has to build the [ChartState] — the single place that decides which overlays reach the canvas.
 *
 * Overlays come from the **selected** match only. Drawing every match's measured move would put three
 * or four levels on a phone-sized chart and make the geometry unreadable, which is the opposite of
 * what plan.md §6.6 asks for.
 */
@Composable
private fun ChartSection(state: PatternsUiState, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MiaSpacing.sm),
    ) {
        PriceChart(
            state = ChartState(
                symbol = state.symbol,
                timeframe = state.timeframe,
                candles = state.candles,
                pivots = state.selectedMatch?.pivots.orEmpty(),
                measuredMove = state.selectedMatch?.measuredMove,
                projectedPath = state.projection.path,
                breakoutTrigger = state.projection.trigger,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        OverlayLegend(state)
    }
}

/**
 * The three overlays, named and drawn with the dash each one uses.
 *
 * ### Why a legend at all
 * The chart already distinguishes them by dash pattern and hue — that is `ui/chart`'s job, and
 * `ChartDefaults` names three separate patterns for exactly this reason. A reader who cannot tell the
 * two hues apart, or who is reading the chart at a size where a 2 px dash is invisible, needs a second
 * channel. The glyph run below is that channel: long dash for the geometry level, fine dash for the
 * directional path, dotted for the trigger, each with its own label and its own count.
 *
 * Each entry says what it is **not** as well, in one clause, so the legend itself is the guard
 * against D2's conflation.
 */
@Composable
private fun OverlayLegend(state: PatternsUiState, modifier: Modifier = Modifier) {
    val move = state.selectedMatch?.measuredMove
    val path = state.projection.path
    val trigger = state.projection.trigger
    if (move == null && path == null && trigger == null) return

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MiaSpacing.xs),
    ) {
        if (move != null) {
            DashLegendRow(
                dash = DASH_MEASURED_MOVE,
                label = MatchScoreLabels.PROJECTION_LABEL,
                detail = "${formatPrice(move.projectedLevel)} · one level from the formation's height",
            )
        }
        if (path != null) {
            DashLegendRow(
                dash = DASH_PROJECTED_PATH,
                label = path.label,
                detail = "${path.horizonBars} bars · carries no price level",
            )
        }
        if (trigger != null) {
            DashLegendRow(
                dash = DASH_TRIGGER,
                label = UiStrings.BREAKOUT_TRIGGER,
                detail = "${formatPrice(trigger.level)} · ${trigger.description.lowercase()}",
            )
        }
    }
}

/** One legend entry: the dash glyph, the label, and the one fact that identifies it. */
@Composable
private fun DashLegendRow(dash: String, label: String, detail: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = "$label. $detail" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MiaSpacing.sm),
    ) {
        Text(
            text = dash,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(48.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ------------------------------------------------------------------------------ gate

/**
 * The honest gate states.
 *
 * Three, and they are genuinely different facts:
 * * **below the bar gate** — the scan refused to score anything, and says how many bars it needs.
 * * **no bars at all** — nothing has been fetched; the chart is empty because the data is.
 * * **above the gate but nothing renderable** — the scan ran and nothing scored
 *   `MatchScorePolicy.RENDER_THRESHOLD` or more. This one names the threshold, because "no patterns
 *   found" with no threshold is indistinguishable from a bug.
 *
 * When all three are false this renders nothing: a scan that produced matches has nothing to
 * disclaim.
 */
@Composable
private fun GateSection(state: PatternsUiState, modifier: Modifier = Modifier) {
    when {
        !state.hasEnoughHistory && state.barCount == 0 -> EmptyState(
            title = UiStrings.NOT_ENOUGH_HISTORY,
            body = UiStrings.notEnoughHistoryDetail(state.barCount, state.minimumBars) + " " +
                NO_BARS_NOTE,
            modifier = modifier,
        )

        !state.hasEnoughHistory -> EmptyState(
            title = UiStrings.NOT_ENOUGH_HISTORY,
            body = UiStrings.notEnoughHistoryDetail(state.barCount, state.minimumBars) + " " +
                GATE_EXPLANATION,
            modifier = modifier,
        )

        state.isRenderEmpty -> EmptyState(
            title = ABOVE_RENDER_THRESHOLD_NONE,
            body = UiStrings.noMatchesFound(state.renderThreshold) + ". " + RENDER_THRESHOLD_NOTE,
            modifier = modifier,
        )
    }
}

// ------------------------------------------------------------------------- match list

/**
 * The ranked pattern list: name, [MatchScoreChip], and what the score means.
 *
 * The **selected** match additionally renders [ScoreExplainCard] — all four factors with their
 * weights — plus its track record. Only one is expanded, because five expanded cards of four factor
 * bars each is a wall, and the selected one is by definition the one the reader is asking about.
 * Tapping any row selects it and drives the chart's overlays with it.
 *
 * Ordering is the engine's, not a re-sort here: `PatternEngine.rankOrder` breaks ties by the most
 * recent pivot, which this tab has no way to compute correctly on its own.
 */
@Composable
private fun MatchListSection(
    state: PatternsUiState,
    onSelect: (Match) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MiaSpacing.md),
    ) {
        SectionHeader(
            title = UiStrings.PATTERN_MATCHES,
            subtitle = MATCH_LIST_SUBTITLE,
        )
        if (state.matches.isEmpty()) {
            // The gate section has already explained why. One line here so the section is not blank.
            Text(
                text = MATCH_LIST_EMPTY,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            state.matches.forEach { match ->
                val isSelected = match.patternId == state.selectedMatch?.patternId
                PatternCard(
                    match = match,
                    isSelected = isSelected,
                    onClick = { onSelect(match) },
                )
                if (isSelected) {
                    ScoreExplainCard(match = match)
                    TrackRecordSection(
                        hitRate = state.trackRecords[match.name],
                        perSymbol = state.trackRecordForSymbol,
                        patternName = match.name,
                    )
                }
            }
        }
    }
}

/**
 * One pattern: name, score chip, and the score's meaning.
 *
 * The meaning is **always** on the row, not only in the expanded card. A collapsed row that shows
 * only `72 strong fit` invites the reader to read `72` as a percentage, which is the one reading
 * plan.md §6.1 exists to prevent — so the subtitle travels with the chip.
 *
 * Selection is carried by a border **and** by the container colour, because a border alone at one
 * pixel is easy to miss on a washed-out screen.
 */
@Composable
private fun PatternCard(
    match: Match,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val border = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, border, MaterialTheme.shapes.medium)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.surface
            },
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MiaSpacing.md),
            verticalArrangement = Arrangement.spacedBy(MiaSpacing.xs),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = "${match.name}. Match score ${match.score} of 100. " +
                            MatchScoreLabels.SUBTITLE + ". " + if (isSelected) {
                            "Showing its four factors, geometry and track record."
                        } else {
                            "Tap to show its four factors, geometry and track record."
                        }
                    },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = match.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = match.category.name.lowercase().replace('_', ' ') +
                            " · detected ${formatDate(match.detectedOn)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                MatchScoreChip(score = match.score)
            }
            Text(
                text = MatchScoreLabels.SUBTITLE,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ----------------------------------------------------------------------------- geometry

/**
 * plan.md §6.6 item 1: the measured move, labelled **pattern geometry**.
 *
 * ### The rule this panel exists to enforce
 * The heading is [MatchScoreLabels.PROJECTION_LABEL] and [MatchScoreLabels.NOT_A_TARGET_WARNING] is
 * rendered immediately under the numbers, every time, with no condition to disable it. There is no
 * string in this function that could be read as a forecast, and no way to render the level without
 * the caveat attached, because they are the same `Text` pair in one `Column`.
 *
 * The three numbers are the formation's own: its height, the level its boundary sits at, and the
 * level that height projects from the breakout close. Nothing here is derived from the hit rate.
 */
@Composable
private fun GeometrySection(
    match: Match,
    move: MeasuredMove,
    asOf: LocalDate?,
    source: Source,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MiaSpacing.sm),
    ) {
        SectionHeader(
            title = MatchScoreLabels.PROJECTION_LABEL,
            subtitle = GEOMETRY_SUBTITLE,
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(MiaSpacing.md),
                verticalArrangement = Arrangement.spacedBy(MiaSpacing.xs),
            ) {
                Text(
                    text = "For ${match.name}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                LabelledNumber("Formation height", formatPrice(move.height))
                LabelledNumber("Trigger level", formatPrice(move.triggerLevel))
                LabelledNumber("Derived level", formatPrice(move.projectedLevel))
                LabelledNumber(
                    label = "Direction",
                    value = if (move.isUpward) "Upward from the trigger" else "Downward from the trigger",
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    text = MatchScoreLabels.NOT_A_TARGET_WARNING,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(text = Disclosure.MEASURED_MOVE, style = MaterialTheme.typography.bodySmall)
                Text(
                    text = geometryProvenance(source, asOf),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

/** `Derived on device · NSE EOD bhavcopy · as of 02 Oct 2026`. Never omitted, never a blank. */
private fun geometryProvenance(source: Source, asOf: LocalDate?): String {
    val dateText = asOf?.let { " · as of ${formatDate(it)}" } ?: " · as of $NOT_AVAILABLE"
    return "Derived on device · source: ${source.displayName()}$dateText"
}

// -------------------------------------------------------------------- directional path

/**
 * plan.md §6.6 item 2: the 1–5 bar directional path.
 *
 * ### Why this is a separate section from the geometry
 * decision D2 keeps the two apart because they mean different things: the geometry is a **level**
 * derived from the formation's height, and the path is a **direction** over the next few sessions with
 * no level in it at all. Putting them in one panel is how a reader comes away believing the path ends
 * at the geometry level, which would be a claim the app never made and cannot support.
 *
 * So this section states three separate things and nothing else: what the path is, what it is not,
 * and that it carries no price level.
 */
@Composable
private fun ProjectedPathSection(path: ProjectedPath, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MiaSpacing.sm),
    ) {
        SectionHeader(title = UiStrings.PROJECTION, subtitle = PATH_SUBTITLE)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(MiaSpacing.md),
                verticalArrangement = Arrangement.spacedBy(MiaSpacing.xs),
            ) {
                // `ProjectedPath.label` is already "Projected path (directional, not a forecast of
                // price)". Rendering it verbatim keeps the wording welded to the type that carries it.
                Text(
                    text = path.label,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(text = PATH_CARRIES_NO_LEVEL, style = MaterialTheme.typography.bodySmall)
                LabelledNumber("Bars projected", path.horizonBars.toString())
                LabelledNumber(
                    label = "Drawn",
                    value = "${path.points.size} ${if (path.points.size == 1) "point" else "points"}",
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(text = PATH_DISTINCT_NOTE, style = MaterialTheme.typography.bodySmall)
                Text(text = Disclosure.PROJECTION_FOOTER, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

// ----------------------------------------------------------------------- breakout alerts

/**
 * plan.md §6.6 item 3: the breakout alert — a **condition on a close**, in its own section.
 *
 * Never merged into the projection (decision D2), never given a price level of its own beyond the
 * trigger, and never phrased as something that will happen. The state line is either "this condition
 * has been met" or "this condition has not been met" — both are statements about stored bars.
 */
@Composable
private fun BreakoutSection(
    trigger: BreakoutTrigger,
    timeframe: Timeframe,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MiaSpacing.sm),
    ) {
        SectionHeader(title = UiStrings.BREAKOUT_TRIGGER, subtitle = TRIGGER_SUBTITLE)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(MiaSpacing.md),
                verticalArrangement = Arrangement.spacedBy(MiaSpacing.xs),
            ) {
                Text(text = trigger.description, style = MaterialTheme.typography.titleSmall)
                LabelledNumber("Level", formatPrice(trigger.level))
                LabelledNumber(
                    label = "State on stored ${timeframe.shortLabel()} bars",
                    value = if (trigger.fired) CONDITION_MET else CONDITION_NOT_MET,
                )
                Text(text = TRIGGER_CLOSE_BASED, style = MaterialTheme.typography.bodySmall)
                Text(text = TRIGGER_NOT_A_LEVEL, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

// --------------------------------------------------------------------------- track record

/**
 * decision D6: the track record for one formation on this stock, plus the stock's own figure.
 *
 * Rendered even when the rate is withheld, because "we have not tracked this yet" and "we have
 * tracked it and the number is withheld" are different facts. [TrackRecordRow] handles both, and
 * `HitRate.label()` always carries `n`.
 */
@Composable
private fun TrackRecordSection(
    hitRate: HitRate?,
    perSymbol: HitRate?,
    patternName: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MiaSpacing.sm),
    ) {
        SectionHeader(
            title = UiStrings.TRACK_RECORD,
            subtitle = TRACK_RECORD_SUBTITLE,
        )
        TrackRecordRow(
            perSymbolAndPattern = hitRate,
            perSymbol = perSymbol,
            patternName = patternName,
        )
    }
}

// ------------------------------------------------------------------------------ suppressed

/**
 * Every candidate the engine chose not to draw, with the reason it gives.
 *
 * Not debug output. Showing the user *why* a detected formation was hidden is how they learn the app
 * is not quietly manufacturing signals (plan.md §1.4), and it is the only honest answer to "why did
 * nothing appear for this stock?". The reasons come from `PatternScanResult.suppressed`, worded by
 * the engine, and are rendered **verbatim** — a tab that paraphrased them would be inventing
 * justifications the scorer never made.
 */
@Composable
private fun SuppressedSection(state: PatternsUiState, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MiaSpacing.sm),
    ) {
        SectionHeader(
            title = SECTION_SUPPRESSED,
            subtitle = suppressedSubtitle(state.suppressed.size),
        )
        state.suppressed.forEach { suppressed ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MiaSpacing.sm),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = suppressed.name,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.width(SUPPRESSED_NAME_WIDTH),
                )
                Column(modifier = Modifier.weight(1f)) {
                    if (suppressed.score > 0) {
                        Text(
                            text = "would have scored ${suppressed.score}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            text = "no score was computed",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = suppressed.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// -------------------------------------------------------------------------------- notes

/**
 * What the scan could not measure.
 *
 * `PatternScanResult.notes` carries the engine's own transparency list: an incomplete ADX warm-up, a
 * missing benchmark, absent delivery percentages, an unscored factor, an undecided regime. Each one
 * is a case where a factor would otherwise read as a real 0, so they are shown rather than smoothed
 * away.
 */
@Composable
private fun NotesSection(state: PatternsUiState, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MiaSpacing.xs),
    ) {
        SectionHeader(title = SECTION_NOTES)
        state.scanNotes.forEach { note ->
            Text(
                text = "$BULLET $note",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Why the red-flag banner may be missing entries (plan.md §12).
 *
 * A rule that could not be evaluated is not a rule that found nothing, and `RedFlagBanner` renders
 * nothing at all when the flag list is empty. Without this section an empty banner reads as "all
 * clear", which is the failure mode the plan calls the most dangerous one in the product.
 */
@Composable
private fun FlagCoverageSection(state: PatternsUiState, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MiaSpacing.xs),
    ) {
        Text(
            text = SECTION_FLAG_COVERAGE,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.skippedRules.forEach { skip ->
            Text(
                text = "$NOT_EVALUATED ${skip.ruleId}: ${skip.reason}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.ruleFailures.forEach { (ruleId, message) ->
            Text(
                text = "$FAILED $ruleId: $message",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

// ------------------------------------------------------------------------------- helpers

/** A label beside a value. Identical everywhere on this screen so the columns line up. */
@Composable
private fun LabelledNumber(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

// ------------------------------------------------------------------------------ the copy

/** Test tag on the tab root. */
const val PATTERNS_TAG: String = "mia_patterns"

private const val NOT_AVAILABLE_SYMBOL = "—"

private const val GATE_SHORT = "short of the gate by"

private const val NO_BARS_NOTE =
    "The chart, the indicators and the pattern scan all read stored bars. Nothing is drawn from an " +
        "empty series."
private const val GATE_EXPLANATION =
    "This is a hard gate: below it the app emits no score at all rather than a low one, because a " +
        "thin window makes every pattern look different from its textbook shape."
private const val ABOVE_RENDER_THRESHOLD_NONE = "No formation above the render threshold"
private const val RENDER_THRESHOLD_NOTE =
    "Twelve detectors ran over the shared swing-point list and every one scored below the line. The " +
        "bar gate was met, so this is a result rather than a missing-data case."

private const val MATCH_LIST_SUBTITLE =
    "Ranked by how closely each formation resembles its textbook shape. The score is geometric fit " +
        "and nothing else."
private const val MATCH_LIST_EMPTY =
    "Nothing was drawn. The reason is stated in the gate message above, and any candidate that was " +
        "detected but held back is explained at the bottom of this screen."

private const val GEOMETRY_SUBTITLE =
    "The height of the formation, drawn from its breakout point. A measurement of the chart, not a " +
        "statement about the future."
private const val PATH_SUBTITLE =
    "Where the formation's direction points over the next few sessions. A direction, not a price."
private const val PATH_CARRIES_NO_LEVEL =
    "This dashed path carries no price level of its own. It is not the geometry above and must not " +
        "be read as ending at it."
private const val PATH_DISTINCT_NOTE =
    "The chart draws it with a fine dash and the geometry with a long dash. They are two separate " +
        "outputs and this screen never combines them."

private const val TRIGGER_SUBTITLE =
    "An event, not a projection: a condition on a closing price. Scheduled separately from the " +
        "path above."
private const val TRIGGER_CLOSE_BASED =
    "A close must clear the level decisively. A move through it during the session is not one."
private const val TRIGGER_NOT_A_LEVEL =
    "The level here is the condition's own boundary. It is not a level the app expects price to " +
        "reach."
private const val CONDITION_MET = "This condition has been met."
private const val CONDITION_NOT_MET = "This condition has not been met."

private const val TRACK_RECORD_SUBTITLE =
    "How often this formation's geometry level was reached on this stock, across past tracked " +
        "outcomes. Always shown with its sample size."

private const val SECTION_SUPPRESSED = "Detected but not drawn"
private fun suppressedSubtitle(count: Int): String =
    "$count ${if (count == 1) "formation was" else "formations were"} detected and not shown. " +
        "Each reason below is the detector's own."

private const val SECTION_NOTES = "What the scan could not measure"
private const val SECTION_FLAG_COVERAGE = "Red-flag coverage"
private const val NOT_EVALUATED = "Not evaluated —"
private const val FAILED = "Failed —"
private const val BULLET = "•"

private val SUPPRESSED_NAME_WIDTH = 120.dp

/**
 * Dash glyphs for the overlay legend.
 *
 * The same three shapes `ChartDefaults` uses to draw them, in characters so they render without an
 * image. Long dash for the measured-move level, fine dash for the projected path, dots for the
 * trigger — matching `MEASURED_MOVE_DASH`, `PROJECTED_PATH_DASH` and `TRIGGER_DASH`.
 */
private const val DASH_MEASURED_MOVE = "— — — —"
private const val DASH_PROJECTED_PATH = "-·-·-·-·-·"
private const val DASH_TRIGGER = "· · · · ·"

// Lazy-list keys. Stable strings, so a rotation does not rebuild every row.
private const val HEADER_KEY = "patterns.header"
private const val TIMEFRAME_KEY = "patterns.timeframe"
private const val LOADING_KEY = "patterns.loading"
private const val ERROR_KEY = "patterns.error"
private const val CHART_KEY = "patterns.chart"
private const val GATE_KEY = "patterns.gate"
private const val MATCHES_KEY = "patterns.matches"
private const val GEOMETRY_KEY = "patterns.geometry"
private const val PATH_KEY = "patterns.path"
private const val TRIGGER_KEY = "patterns.trigger"
private const val SUPPRESSED_KEY = "patterns.suppressed"
private const val NOTES_KEY = "patterns.notes"
private const val FLAG_COVERAGE_KEY = "patterns.flag_coverage"
private const val FOOTER_KEY = "patterns.footer"