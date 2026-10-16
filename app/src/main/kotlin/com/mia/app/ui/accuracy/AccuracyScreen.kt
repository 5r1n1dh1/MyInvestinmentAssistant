package com.mia.app.ui.accuracy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mia.app.domain.accuracy.CalibrationReport
import com.mia.app.domain.accuracy.ReachExplanation
import com.mia.app.domain.accuracy.TrackRecordInsight
import com.mia.app.domain.compliance.Disclosure
import com.mia.app.domain.model.HitRate
import com.mia.app.domain.model.PatternOutcome
import com.mia.app.domain.model.Timeframe
import com.mia.app.domain.pattern.BucketCalibration
import com.mia.app.ui.common.DisclaimerFooter
import com.mia.app.ui.common.EmptyState
import com.mia.app.ui.common.ErrorState
import com.mia.app.ui.common.HitRateText
import com.mia.app.ui.common.LoadingState
import com.mia.app.ui.common.NOT_AVAILABLE
import com.mia.app.ui.common.SectionHeader
import com.mia.app.ui.common.formatDate
import com.mia.app.ui.common.shortLabel
import com.mia.app.ui.theme.MiaSpacing

/**
 * The Accuracy tab: the app's own track record (plan.md §6.1, decision D6).
 *
 * ### Section order, and why
 * ```
 *  heading + what a hit is     the definition, before any number, so no figure is read unexplained
 *  coverage                    how many outcomes, symbols, timeframes, when one last settled,
 *                               and how many more are needed before any rate is reportable
 *  what a hit means            "the level was reached", restated beside the tables
 *  why the rate is what it is  the measured mechanism, not a bare percentage (gap A6)
 *  calibration by distance     the hit rate against the chance of a level that size being
 *                               touched anyway — with the provenance of the data stated
 *  per symbol                  HEADLINE: the aggregate that clears the gate, labelled as
 *                               cross-pattern rather than per-pattern
 *  per stock and pattern       D6's strict grouping — withheld below the gate, with the
 *                               number of outcomes still needed to un-withhold it (gap A4)
 *  per pattern                 how a formation behaves across every tracked stock
 *  still unresolved            SEPARATE, and never averaged in
 *  data-source footer          plan.md §3
 * ```
 *
 * ### The five things this screen must never do
 * 1. **Show a rate without `n`.** Every figure goes through [HitRateText] or
 *    `BucketCalibration.labelAt`, both of which delegate to a rule that always carries the sample
 *    size.
 * 2. **Show a rate below 30 samples.** `HitRate.isReportable` withholds it and the label shows the
 *    count instead. Not greyed out — withheld. The gate is never lowered to fill a table.
 * 3. **Mix unresolved outcomes into a rate.** They get their own section, with the number of bars
 *    elapsed, because a projection whose window is open is neither a hit nor a miss.
 * 4. **Turn a hit into a profit.** The explanation says a hit is the measured-move level being
 *    *reached*, and that no price was bought or sold, because plan.md §1.4 rule 1 forbids the
 *    alternative and the arithmetic would not support it anyway.
 * 5. **Present a published measurement as the reader's own record.** Every figure here carries its
 *    `InsightProvenance`, and the published fallback is rendered inside a labelled card so a
 *    research figure can never be mistaken for a track record.
 *
 * @param viewModel obtained with `hiltViewModel()`. This tab has no route arguments — it reports on
 *   the whole record, which is what plan.md §6.1 asks for.
 */
@Composable
fun AccuracyScreen(
    modifier: Modifier = Modifier,
    viewModel: AccuracyViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag(ACCURACY_TAG),
        contentPadding = PaddingValues(
            start = MiaSpacing.lg,
            end = MiaSpacing.lg,
            top = MiaSpacing.lg,
            bottom = MiaSpacing.xxl,
        ),
        verticalArrangement = Arrangement.spacedBy(MiaSpacing.lg),
    ) {
        item(key = HEADING_KEY) { Heading(state) }

        if (state.isLoading && state.report.isEmpty) {
            item(key = LOADING_KEY) { LoadingState() }
        }

        state.errorReason?.let { reason ->
            item(key = ERROR_KEY) { ErrorState(reason = reason, onRetry = viewModel::onRetry) }
        }

        if (state.hasNoOutcomes) {
            item(key = EMPTY_KEY) {
                EmptyState(title = EMPTY_TITLE, body = EMPTY_BODY)
            }
            // The measurement and its explanation are still shown on a fresh install. They are the
            // engine's own published figures, labelled as such by [ProvenanceBadge], so the tab
            // never opens empty and never passes research off as the reader's record.
            state.insight.reach?.let { reach ->
                item(key = WHY_THE_RATE_KEY) { WhyTheRateSection(reach) }
            }
            state.insight.calibration?.let { calibration ->
                item(key = CALIBRATION_KEY) { CalibrationSection(calibration) }
            }
        } else {
            item(key = COVERAGE_KEY) { CoverageSection(state) }
            item(key = MEANING_KEY) { WhatAHitMeans() }

            state.insight.reach?.let { reach ->
                item(key = WHY_THE_RATE_KEY) { WhyTheRateSection(reach) }
            }
            state.insight.calibration?.let { calibration ->
                item(key = CALIBRATION_KEY) { CalibrationSection(calibration) }
            }

            // The per-symbol aggregate leads the tables: it is the only per-stock grouping that
            // clears the 30-sample gate on real data, and it is labelled as cross-pattern so it
            // cannot be read as "this pattern on this stock", which is the table below it.
            if (state.report.perSymbol.isNotEmpty()) {
                item(key = TABLE_SYMBOL_KEY) {
                    RateTable(
                        title = TITLE_PER_SYMBOL,
                        subtitle = TABLE_SYMBOL_SUBTITLE,
                        rows = state.report.perSymbol,
                        kind = AccuracyRowKind.SYMBOL,
                    )
                }
            }
            if (state.report.perSymbolAndPattern.isNotEmpty()) {
                item(key = TABLE_STOCK_AND_PATTERN_KEY) {
                    RateTable(
                        title = TABLE_STOCK_AND_PATTERN_TITLE,
                        subtitle = TABLE_STOCK_AND_PATTERN_SUBTITLE,
                        rows = state.report.perSymbolAndPattern,
                        kind = AccuracyRowKind.STOCK_AND_PATTERN,
                    )
                }
            }
            if (state.report.perPattern.isNotEmpty()) {
                item(key = TABLE_PATTERN_KEY) {
                    RateTable(
                        title = TITLE_PER_PATTERN,
                        subtitle = TABLE_PATTERN_SUBTITLE,
                        rows = state.report.perPattern,
                        kind = AccuracyRowKind.PATTERN,
                    )
                }
            }
            if (state.unresolved.isNotEmpty()) {
                item(key = UNRESOLVED_KEY) { UnresolvedSection(state) }
            }
        }

        item(key = FOOTER_KEY) { DisclaimerFooter(text = Disclosure.DATA_SOURCE) }
    }
}

// ------------------------------------------------------------------------------ heading

/**
 * The page heading plus the sentence that has to appear before any figure on this tab.
 *
 * [Disclosure.HIT_RATE] is not decoration here: it is the regulated definition of a hit rate, and
 * `AdviceLanguage.REQUIRED_NEAR` requires it wherever a rate is shown. It is rendered once, at the
 * top, rather than under every table.
 */
@Composable
private fun Heading(state: AccuracyUiState, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(MiaSpacing.xs)) {
        Text(
            text = TITLE_TRACK_RECORD,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = HEADING_SUBTITLE,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = Disclosure.HIT_RATE,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ------------------------------------------------------------------------------ coverage

/**
 * How much record there is to look at.
 *
 * Four tiles: outcomes tracked, outcomes settled, symbols covered, last settlement. Every one is a
 * count or a date — none is a rate, so none can be mistaken for one. The settled count is separated
 * from the tracked count because the difference is exactly the unresolved section further down, and
 * stating it twice is better than letting a reader infer a denominator they cannot see.
 */
@Composable
private fun CoverageSection(state: AccuracyUiState, modifier: Modifier = Modifier) {
    val tiles = listOf(
        TILE_TRACKED to state.report.totalOutcomes.toString(),
        TILE_SETTLED to state.settledOutcomes.toString(),
        TILE_SYMBOLS to state.trackedSymbols.size.toString(),
        TILE_PATTERNS to state.report.perPattern.size.toString(),
    )

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MiaSpacing.sm),
    ) {
        SectionHeader(title = SECTION_COVERAGE, subtitle = COVERAGE_SUBTITLE)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MiaSpacing.sm),
        ) {
            tiles.forEach { (label, value) ->
                Tile(label = label, value = value, modifier = Modifier.weight(1f))
            }
        }
        Text(
            text = lastSettledText(state),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.trackedTimeframes.isNotEmpty()) {
            Text(
                text = timeframesText(state.trackedTimeframes),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!state.hasReportableRate) {
            Text(
                text = withheldBannerText(state.minimumSamples),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // How far away the gate is, in the units the gate is counted in. Without it, an all-withheld
        // screen is indistinguishable from a broken one — the whole point of withholding below 30
        // samples is that the number is too small to mean anything, and that is a fact about the
        // sample rather than about the app.
        state.nearestReportable?.let { nearest ->
            Text(
                text = samplesUntilReportableText(state, nearest),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The nearest-cell sentence: "N more settled outcomes and X becomes reportable".
 *
 * Names the cell it is talking about, because "6 more outcomes" without a scope is not actionable.
 * Rendered only when a cell is actually below the gate — when every cell clears it, there is nothing
 * left to wait for and the sentence would be noise.
 */
private fun samplesUntilReportableText(state: AccuracyUiState, nearest: HitRate): String {
    val remaining = TrackRecordInsight.samplesUntilReportable(nearest)
    val scope = when {
        nearest.symbol != null && nearest.patternName != null ->
            "${nearest.patternName} on ${nearest.symbol}"
        nearest.patternName != null -> nearest.patternName
        nearest.symbol != null -> nearest.symbol
        else -> NOT_AVAILABLE
    }
    return "$remaining more settled ${if (remaining == 1) "outcome" else "outcomes"} and $scope " +
        "becomes reportable — it holds ${nearest.samples} of ${state.minimumSamples} now."
}

/** `Last settled verdict: 02 Oct 2026`, or an honest "never" when there is none. */
private fun lastSettledText(state: AccuracyUiState): String {
    val settledOn = state.lastSettledOn
        ?: return "$LAST_SETTLED_NEVER Verdicts settle in trading sessions, so an open window " +
        "stays open until the bars exist."
    return "$LAST_SETTLED ${formatDate(settledOn)}"
}

/** `Timeframes tracked: 1D, 1W`. Says which, because a daily-only record is a narrower claim. */
private fun timeframesText(timeframes: List<Timeframe>): String =
    "$TIMEFRAMES_TRACKED ${timeframes.joinToString(", ") { it.shortLabel() }}"

private fun withheldBannerText(minimumSamples: Int): String =
    "No rate on this screen reaches $minimumSamples tracked samples yet, so every figure below " +
        "shows its count instead of a percentage."

/** A label over a count. Four across a 360 dp screen is the limit before the labels truncate. */
@Composable
private fun Tile(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MiaSpacing.sm),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(text = label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

// ------------------------------------------------------------------------------- meaning

/**
 * "What a hit is", stated as a measurement.
 *
 * The phrase "not a profit" is the whole point of this block. plan.md §1.4 rule 1 forbids any
 * suggestion that the app tracks returns, and no price is bought or sold anywhere in this codebase —
 * a hit is the measured-move level being *reached*, which is a fact about the chart.
 */
@Composable
private fun WhatAHitMeans(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(MiaSpacing.xs)) {
        SectionHeader(title = SECTION_WHAT_A_HIT_IS)
        Text(
            text = HIT_DEFINITION,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = HIT_NOT_A_PROFIT,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = HIT_NO_PREDICTION,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ------------------------------------------------------------------- why the rate

/**
 * "Why the rate is what it is" — the teaching card (gap A6).
 *
 * ### The failure this fixes
 * The app publishes a low unfiltered hit rate and, before this card, published no explanation. A
 * number without its mechanism reads as "the app is bad at its job". The mechanism is neither secret
 * nor flattering: a measured-move level sits several percent of price away, and the median
 * five-session move in the projected direction is a small fraction of that — so a level can be
 * textbook-correct and still never be reached inside the window.
 *
 * ### Where the numbers come from
 * [ReachExplanation.sentences] carries them with their `n`, together with the chance-level control
 * they have to be read against. When this device has settled outcomes with stored bars they are the
 * device's own medians; otherwise they are the published fixture measurement, and [ProvenanceBadge]
 * says so directly above the text. There is no third path, and no path that renders a median it did
 * not measure.
 *
 * ### What this card must never do
 * Turn the explanation into a conclusion about the reader. It names a mechanism and stops there.
 */
@Composable
private fun WhyTheRateSection(reach: ReachExplanation, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MiaSpacing.sm),
    ) {
        SectionHeader(title = SECTION_WHY_THE_RATE, subtitle = WHY_THE_RATE_SUBTITLE)
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
                ProvenanceBadge(text = reach.provenance.label)
                reach.sentences().forEach { sentence ->
                    Text(
                        text = sentence,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    text = WHY_THE_RATE_NOT_ADVICE,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

// ----------------------------------------------------------------------- calibration

/**
 * The distance-bucket calibration table (W2's headline item).
 *
 * ### Why a hit rate needs its distance and its control
 * "How often a pattern's level was reached" is not a measurement until the reader knows how far the
 * level sat. Measured on the committed fixture: within 3 % of the close, 82 % (n=121); beyond 12 %,
 * 31 % (n=344); and with no pattern involved at all a level 5 % away is reached 10 % of the time
 * inside five sessions. So the table shows the rate per distance band **and** the chance-level rate
 * for a level of that size — quoting one without the other is how this kind of number gets misused.
 *
 * ### The rows cannot print a percentage without their denominator
 * Every rate comes from `BucketCalibration.labelAt`, which builds the rate, the horizon and the `n`
 * in one string; a band below [MatchScorePolicy.MIN_HIT_RATE_SAMPLES] prints its count and stops.
 * The gate is never lowered to make the table look fuller.
 */
@Composable
private fun CalibrationSection(calibration: CalibrationReport, modifier: Modifier = Modifier) {
    val horizon = calibration.horizons.firstOrNull { it == TrackRecordInsight.HORIZON_BARS }
        ?: calibration.horizons.firstOrNull()
    val otherHorizons = calibration.horizons.filterNot { it == horizon }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MiaSpacing.sm),
    ) {
        SectionHeader(title = SECTION_CALIBRATION, subtitle = CALIBRATION_SUBTITLE)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(MiaSpacing.md),
                verticalArrangement = Arrangement.spacedBy(MiaSpacing.sm),
            ) {
                ProvenanceBadge(text = calibration.provenance.label)
                Text(
                    text = calibration.sourceNote,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (horizon == null) {
                    Text(
                        text = CALIBRATION_NO_HORIZON,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    calibration.rows.forEachIndexed { index, row ->
                        if (index > 0) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                        CalibrationRow(
                            row = row,
                            horizonBars = horizon,
                            otherHorizons = otherHorizons,
                        )
                    }
                    calibration.withheldReason?.let { reason ->
                        Text(
                            text = reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    chanceLevelText(calibration, horizon)?.let { control ->
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Text(
                            text = control,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

/**
 * One distance band: the rate at the app's own horizon, then the other horizons that were measured.
 *
 * [BucketCalibration.labelAt] is the only path to a percentage here, so the `n` cannot be dropped.
 */
@Composable
private fun CalibrationRow(
    row: BucketCalibration,
    horizonBars: Int,
    otherHorizons: List<Int>,
    modifier: Modifier = Modifier,
) {
    val otherCells = if (row.isReportable) {
        otherHorizons.mapNotNull { h ->
            row.hitRatePct(h)?.let { rate -> "h$h ${"%.0f".format(rate)}%" }
        }
    } else {
        emptyList()
    }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MiaSpacing.xs),
    ) {
        Text(
            text = if (row.isReportable) {
                row.labelAt(horizonBars)
            } else {
                "${row.label}: withheld — ${row.samples}/$REPORTING_THRESHOLD samples"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (row.isReportable) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        if (otherCells.isNotEmpty()) {
            Text(
                text = "$ALSO_LABEL ${otherCells.joinToString(" · ")} · same candidates, same bands",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The chance-level control, or `null` when it was not measured at this horizon.
 *
 * Stated with the level size it belongs to, because a base rate without a stated distance is a claim
 * about no particular level at all — the figure changes completely with it.
 */
private fun chanceLevelText(calibration: CalibrationReport, horizonBars: Int): String? {
    val rate = calibration.baseRatePct(horizonBars) ?: return null
    return "Chance level: a level ${"%.1f".format(calibration.baseRateDistancePercent)}% from the " +
        "close is reached ${"%.0f".format(rate)}% of the time inside $horizonBars sessions with no " +
        "pattern involved at all. Every rate above is read against a control of this kind."
}

/**
 * Where a figure came from.
 *
 * Rendered above every measurement on this screen, in its own card, because a reader who cannot tell
 * a published research figure from their own record will read one as the other. The wording arrives
 * from the value rather than being chosen here, so a screen cannot drop the label by forgetting to
 * write it.
 */
@Composable
private fun ProvenanceBadge(text: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = MiaSpacing.sm, vertical = MiaSpacing.xs),
        )
    }
}

// ------------------------------------------------------------------------------- tables

/**
 * One grouping's table.
 *
 * Every row is a [HitRateText], never a bare `formatPercent`: that is what keeps `n` attached and
 * keeps sub-threshold rows withheld. `showDisclosure = false` because [Disclosure.HIT_RATE] is
 * rendered once at the top of the screen, and repeating it under every row would bury the numbers.
 */
@Composable
private fun RateTable(
    title: String,
    subtitle: String,
    rows: List<HitRate>,
    kind: AccuracyRowKind,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(MiaSpacing.sm)) {
        SectionHeader(title = title, subtitle = subtitle)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(MiaSpacing.md),
                verticalArrangement = Arrangement.spacedBy(MiaSpacing.md),
            ) {
                rows.forEachIndexed { index, hitRate ->
                    if (index > 0) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    RateRow(hitRate = hitRate, kind = kind)
                }
            }
        }
    }
}

/**
 * One row: the scope, then the rate.
 *
 * The scope is a statement about *what was measured*, not about the pattern: "Bullish Engulfing on
 * RELIANCE", "Bullish Engulfing on every tracked stock", "RELIANCE across every tracked pattern".
 * Same three shapes `HitRateCalculator.summaryText` produces, written here so the row heading and
 * the rate can sit on separate lines on a 360 dp screen.
 */
@Composable
private fun RateRow(hitRate: HitRate, kind: AccuracyRowKind, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MiaSpacing.xs),
    ) {
        Text(
            text = scopeLabel(hitRate, kind),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        HitRateText(hitRate = hitRate, showDisclosure = false)
        // On a withheld row, how far it is from the gate. The withholding is the rule and it stays,
        // but a cell that is silent about *why* it is empty is indistinguishable from a bug — and
        // this is the whole answer to gap A4: the table is empty because 18 months of one stock's
        // history yields 20 or 21 cases per cell, not because nothing was tracked.
        if (!hitRate.isReportable) {
            Text(
                text = TrackRecordInsight.samplesUntilReportableText(hitRate),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The scope sentence for a row. Mirrors `HitRateCalculator.summaryText`'s three shapes. */
private fun scopeLabel(hitRate: HitRate, kind: AccuracyRowKind): String = when (kind) {
    AccuracyRowKind.STOCK_AND_PATTERN ->
        "${hitRate.patternName ?: NOT_AVAILABLE} on ${hitRate.symbol ?: NOT_AVAILABLE}"

    AccuracyRowKind.PATTERN ->
        "${hitRate.patternName ?: NOT_AVAILABLE} on every tracked stock"

    AccuracyRowKind.SYMBOL ->
        "${hitRate.symbol ?: NOT_AVAILABLE} across every tracked pattern"
}

// ---------------------------------------------------------------------------- unresolved

/**
 * Outcomes still inside their evaluation window.
 *
 * ### Why this is not folded into the tables
 * `HitRateCalculator` counts only resolved outcomes, and that is the correct rule: counting a window
 * that is still open would let the app shrink its own denominator over time. Showing them separately
 * is therefore not a presentation choice, it is the visible half of that rule — the reader can see
 * how many samples are still to come and therefore how much the record is about to change.
 *
 * Each row states the pattern, the stock, the projection date and how many bars of the horizon have
 * elapsed, so an outcome that is merely stale is visibly different from one that is genuinely open.
 */
@Composable
private fun UnresolvedSection(state: AccuracyUiState, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(MiaSpacing.sm)) {
        SectionHeader(title = SECTION_UNRESOLVED, subtitle = UNRESOLVED_SUBTITLE)
        state.unresolved.forEach { outcome -> UnresolvedRow(outcome) }
        Text(
            text = UNRESOLVED_NOTE,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One open outcome. */
@Composable
private fun UnresolvedRow(outcome: PatternOutcome, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MiaSpacing.xs),
    ) {
        Text(
            text = "${outcome.patternName} on ${outcome.symbol}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "projected ${formatDate(outcome.projectedOn)} · " +
                "waiting on ${outcome.horizonBars} bars of geometry to be reached or not",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ------------------------------------------------------------------------------- the copy

/** Test tag on the tab root. */
const val ACCURACY_TAG: String = "mia_accuracy"

private const val TITLE_TRACK_RECORD = "Track record"
private const val HEADING_SUBTITLE =
    "What the app's own recorded outcomes say, grouped three ways. Every figure carries its sample " +
        "size, and a figure below the reporting threshold is withheld rather than shown small."
private const val SECTION_COVERAGE = "Coverage"
private const val COVERAGE_SUBTITLE = "Counts, not rates. No percentage appears on this row."
private const val SECTION_WHAT_A_HIT_IS = "What a hit is"

private const val HIT_DEFINITION =
    "A hit means a pattern's measured-move level was reached within its evaluation window on the " +
        "bars that followed the projection."
private const val HIT_NOT_A_PROFIT =
    "It is not a profit. No price was bought or sold, and no return is computed anywhere in this " +
        "app."
private const val HIT_NO_PREDICTION =
    "Past outcomes are chart events on past bars. They do not say what happens next."

private const val TITLE_PER_PATTERN = "By pattern"
private const val TABLE_PATTERN_SUBTITLE =
    "How often each formation's level was reached, across every stock this device has tracked."
private const val TITLE_PER_SYMBOL = "By stock — every tracked pattern combined"
private const val TABLE_SYMBOL_SUBTITLE =
    "The headline per-stock figure, and the one that clears the reporting gate: every formation " +
        "tracked on that stock, counted together. It is not a per-pattern number — one formation on " +
        "one stock is the table below."
private const val TABLE_STOCK_AND_PATTERN_TITLE = "Per stock and pattern"
private const val TABLE_STOCK_AND_PATTERN_SUBTITLE =
    "The strictest grouping, and the one next to a detected pattern: one formation on one stock. " +
        "Eighteen months of one stock's history yields 20 or 21 settled cases here, which is below " +
        "the reporting gate, so these cells show their count instead of a percentage. The gate is " +
        "not lowered to fill the table."

private const val SECTION_WHY_THE_RATE = "Why the rate is what it is"
private const val WHY_THE_RATE_SUBTITLE =
    "The measured mechanism, not a bare percentage: how far a projected level sat from price, " +
        "against how far the market actually travelled in the same window."
private const val WHY_THE_RATE_NOT_ADVICE =
    "This describes chart geometry on past bars. It is not a forecast, not a profit, and not a " +
        "recommendation."

private const val SECTION_CALIBRATION = "Calibration: how far the level sat"
private const val CALIBRATION_SUBTITLE =
    "The same measurement split by distance from the close, each cell with its sample size. Bands " +
        "are cumulative: a level within 3% is also counted in the 1% and 2% bands."
private const val CALIBRATION_NO_HORIZON =
    "No horizon has been measured for this table, so no cell of it can be quoted."
private const val ALSO_LABEL = "Also:"

private const val SECTION_UNRESOLVED = "Still unresolved"
private const val UNRESOLVED_SUBTITLE =
    "Projections whose evaluation window has not closed. They are not counted in any rate above."
private const val UNRESOLVED_NOTE =
    "A verdict is written once and never revised, so this list only ever shrinks."

private const val EMPTY_TITLE = "No tracked outcomes yet"
private const val EMPTY_BODY =
    "A track record builds as patterns are projected. Each time a formation is shown on a stock, " +
        "the app records the level its geometry projects, and a few sessions later it records " +
        "whether that level was reached. Until then there is nothing to report."

private const val TILE_TRACKED = "tracked"
private const val TILE_SETTLED = "settled"
private const val TILE_SYMBOLS = "symbols"
private const val TILE_PATTERNS = "patterns"
private const val LAST_SETTLED = "Last settled verdict: "
private const val LAST_SETTLED_NEVER = "No verdict has settled on this device yet."
private const val TIMEFRAMES_TRACKED = "Timeframes tracked:"

// Lazy-list keys.
private const val HEADING_KEY = "accuracy.heading"
private const val LOADING_KEY = "accuracy.loading"
private const val ERROR_KEY = "accuracy.error"
private const val EMPTY_KEY = "accuracy.empty"
private const val COVERAGE_KEY = "accuracy.coverage"
private const val MEANING_KEY = "accuracy.meaning"
private const val WHY_THE_RATE_KEY = "accuracy.why_the_rate"
private const val CALIBRATION_KEY = "accuracy.calibration"
private const val TABLE_STOCK_AND_PATTERN_KEY = "accuracy.table.stock_and_pattern"
private const val TABLE_PATTERN_KEY = "accuracy.table.pattern"
private const val TABLE_SYMBOL_KEY = "accuracy.table.symbol"
private const val UNRESOLVED_KEY = "accuracy.unresolved"
private const val FOOTER_KEY = "accuracy.footer"