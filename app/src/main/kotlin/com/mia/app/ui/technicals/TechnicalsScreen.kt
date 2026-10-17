package com.mia.app.ui.technicals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mia.app.domain.compliance.Disclosure
import com.mia.app.domain.model.BreakoutTrigger
import com.mia.app.domain.model.MatchScoreLabels
import com.mia.app.domain.model.MatchScorePolicy
import com.mia.app.domain.model.MeasuredMove
import com.mia.app.domain.model.ProjectedPath
import com.mia.app.domain.model.RedFlag
import com.mia.app.ui.common.RedFlagAccess
import com.mia.app.ui.common.RedFlagCoverageSection
import com.mia.app.domain.model.Source
import com.mia.app.domain.model.Timeframe
import com.mia.app.domain.ratios.Freshness
import com.mia.app.domain.ratios.FreshnessKind
import com.mia.app.domain.ratios.FreshnessPolicy
import com.mia.app.domain.technical.BollingerLatest
import com.mia.app.domain.technical.IndicatorSnapshot
import com.mia.app.domain.technical.TechnicalPolicy
import com.mia.app.domain.technical.TrendRegime
import com.mia.app.domain.technical.riskLine
import com.mia.app.ui.chart.ChartState
import com.mia.app.ui.chart.PriceChart
import com.mia.app.ui.common.AsOfChip
import com.mia.app.ui.common.ChangePercentText
import com.mia.app.ui.common.DisclaimerFooter
import com.mia.app.ui.common.EmptyState
import com.mia.app.ui.common.ErrorState
import com.mia.app.ui.common.EvidenceDialog
import com.mia.app.ui.common.LoadingState
import com.mia.app.ui.common.ScoreExplainCard
import com.mia.app.ui.common.SectionHeader
import com.mia.app.ui.common.TimeframeToggle
import com.mia.app.ui.common.TrackRecordRow
import com.mia.app.ui.common.UiStrings
import com.mia.app.ui.common.ValueAgainstLevel
import com.mia.app.ui.common.formatDate
import com.mia.app.ui.common.formatFixed
import com.mia.app.ui.common.formatPercent
import com.mia.app.ui.common.formatPrice
import com.mia.app.ui.common.formatVolume
import com.mia.app.ui.theme.MiaSpacing
import com.mia.app.ui.theme.glass.GlassCard
import com.mia.app.ui.theme.glass.GlassScreenBackdrop
import com.mia.app.ui.theme.glass.glassEntrance

/**
 * The Technicals tab (plan.md P4).
 *
 * ### Section order, and why
 * ```
 *  red-flag banner            pinned, OUTSIDE the scroll — plan.md §8.3
 *  not-published note         "today's file is not out yet" is not an error — plan.md §3.1
 *  header                     symbol, last close, change, as-of + freshness
 *  timeframe toggle           decision D1: 1D / 1W, nothing else
 *  chart                      PriceChart draws the "not enough history" refusal itself
 *  indicators                 the families plan.md §4.2 names, honest about warm-up
 *  trend                      ADX regime, stated as a band not a verdict
 *  pattern matches            score chip + all four factors, best score first
 *  measured move + projection one panel, both labelled as geometry
 *  breakout trigger           a third, separate output — decision D2
 *  track record               decision D6: per stock and per pattern, always with n
 *  red-flag coverage          what could not be evaluated — plan.md §12
 *  data-source footer         plan.md §3
 * ```
 *
 * The banner is outside the `LazyColumn` on purpose. plan.md §8.3 says flags must be at the top of
 * the tab; a banner inside a scroll container is at the top only until the reader scrolls, and a
 * flag that has scrolled away is a flag that was not surfaced.
 *
 * ### Every state is honest
 * Loading, error, empty, "not enough history", an indicator inside its warm-up window, and a hit
 * rate below 30 samples each have their own rendering. None of them falls back to a zero or a blank
 * cell, because `IndicatorSnapshot`'s `null` means *insufficient history* and plan.md §6.4 forbids
 * substituting a number for that.
 *
 * ### Glass (planv2 W7)
 * ```
 *  GlassScreenBackdrop  one blurred background for this screen, unless a frame already drew one
 *  GlassCard            the sections, the header block and the two geometry panels: a pane and a
 *                       hairline, and NO blur, because every one of them is a `LazyColumn` item
 * ```
 * [GlassScreenBackdrop] rather than a bare [com.mia.app.ui.theme.glass.GlassBackdrop] because this
 * screen has two entry points: it is a standalone nav destination *and* the Technicals tab inside
 * [com.mia.app.ui.detail.StockDetailScreen], which draws the frame's own single layer. The same call
 * is therefore correct in both, and the tab cannot end up with two full-screen blurs on screen at
 * once. The pinned [RedFlagAccess] banner stays as it is — it is a shared component owned with every
 * other screen's, not this one.
 *
 * @param viewModel obtained with `hiltViewModel()`, which resolves the `SavedStateHandle` for this
 *   `NavBackStackEntry` — so `symbol` and `timeframe` come from the route rather than from a
 *   hand-passed argument that could disagree with the back stack.
 */
@Composable
fun TechnicalsScreen(
    modifier: Modifier = Modifier,
    viewModel: TechnicalsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var evidenceFlag by remember { mutableStateOf<RedFlag?>(null) }

    evidenceFlag?.let { flag ->
        EvidenceDialog(flag = flag, onDismissRequest = { evidenceFlag = null })
    }

    GlassScreenBackdrop(
        modifier = modifier
            .fillMaxSize()
            .testTag(TECHNICALS_TAG),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // plan.md §8.3: pinned at the top of the tab, not buried in a section.
            RedFlagAccess(
                flags = state.redFlags,
                skippedRules = state.skippedRules,
                ruleFailures = state.ruleFailures,
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

            // The stagger index for [glassEntrance], one per section as it is emitted. A plain local
            // counter rather than a number per item, so a section that is added or removed re-staggers
            // itself. `LazyColumn` emits top to bottom, so it is read in the order the reader sees.
            var entrance = 0

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
                item(key = HEADER_KEY) {
                    TechnicalsHeader(state, Modifier.glassEntrance(index = entrance++))
                }

                item(key = TIMEFRAME_KEY) {
                    TimeframeToggle(
                        selected = state.timeframe,
                        onSelect = viewModel::onTimeframeSelected,
                        modifier = Modifier.glassEntrance(index = entrance++),
                    )
                }

                if (state.isLoading && state.barCount == 0) {
                    item(key = LOADING_KEY) { LoadingState() }
                }

                state.errorReason?.let { reason ->
                    item(key = ERROR_KEY) {
                        ErrorState(reason = reason, onRetry = viewModel::onRetry)
                    }
                }

                // An honest absence is **not** an error banner. "Nothing has been downloaded yet" and
                // "the published files contain no rows for this ticker" both leave the tab empty, but
                // only the first is fixable by pressing a button — so the button is shown only for that
                // one, because a button that cannot work teaches the reader to distrust it.
                state.absenceReason?.let { reason ->
                    item(key = ABSENCE_KEY) {
                        EmptyState(
                            title = if (state.absenceOffersFetch) {
                                UiStrings.NO_DATA_DOWNLOADED
                            } else {
                                UiStrings.NO_DATA_FOR_SYMBOL
                            },
                            body = reason,
                            modifier = Modifier,
                        )
                    }
                }

                // The chart is left as it is: `PriceChart` is `ui/chart`'s, it paints its own canvas
                // and its own surface, and a pane behind it would add nothing a reader can see.
                item(key = CHART_KEY) { ChartSection(state) }

                // A suspected unadjusted corporate action inside the window. Shown immediately below
                // the chart because its effect *is* the chart: a 1:2 split makes an unbroken price
                // look broken, and a reader who is not told will read the cliff as a market event.
                state.seriesNotes.takeIf { it.isNotEmpty() }?.let { notes ->
                    item(key = SERIES_NOTES_KEY) {
                        SeriesNotesSection(
                            notes = notes,
                            modifier = Modifier.glassEntrance(index = entrance++),
                        )
                    }
                }

                state.snapshot?.let { snapshot ->
                    item(key = INDICATORS_KEY) {
                        IndicatorSection(
                            state = state,
                            snapshot = snapshot,
                            modifier = Modifier.glassEntrance(index = entrance++),
                        )
                    }
                    item(key = TREND_KEY) {
                        TrendSection(
                            state = state,
                            modifier = Modifier.glassEntrance(index = entrance++),
                        )
                    }
                }

                item(key = MATCHES_KEY) {
                    MatchSection(state, Modifier.glassEntrance(index = entrance++))
                }

                state.primaryMatch?.measuredMove?.let { move ->
                    item(key = MEASURED_MOVE_KEY) {
                        MeasuredMovePanel(
                            move = move,
                            path = state.projectedPath,
                            modifier = Modifier
                                .padding(top = MiaSpacing.lg)
                                .glassEntrance(index = entrance++),
                        )
                    }
                }

                state.projectedPath?.takeIf { state.primaryMatch?.measuredMove == null }?.let { path ->
                    item(key = PROJECTION_ONLY_KEY) {
                        MeasuredMovePanel(
                            move = null,
                            path = path,
                            modifier = Modifier
                                .padding(top = MiaSpacing.lg)
                                .glassEntrance(index = entrance++),
                        )
                    }
                }

                state.breakoutTrigger?.let { trigger ->
                    item(key = TRIGGER_KEY) {
                        BreakoutPanel(
                            trigger = trigger,
                            timeframe = state.timeframe,
                            modifier = Modifier.glassEntrance(index = entrance++),
                        )
                    }
                }

                item(key = TRACK_RECORD_KEY) {
                    TrackRecordSection(state, Modifier.glassEntrance(index = entrance++))
                }

                if (state.flagCoverageIncomplete) {
                    item(key = FLAG_COVERAGE_KEY) {
                        RedFlagCoverageSection(state.skippedRules, state.ruleFailures)
                    }
                }

                item(key = FOOTER_KEY) {
                    DisclaimerFooter(
                        text = Disclosure.DATA_SOURCE,
                        modifier = Modifier.glassEntrance(index = entrance++),
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------ header

/**
 * Symbol, last close, day change, and the as-of marker.
 *
 * The change is [ChangePercentText], so it carries a ▲/▼ glyph as well as a colour. The marker is
 * [AsOfChip], because plan.md §10 makes staleness visible and §1.4 rule 3 makes the date mandatory.
 *
 * A [GlassCard] and nothing more: the identity block is the one pane on this tab that a reader looks
 * for before anything else, and `Arrangement.Top` keeps the block's own spacing exactly as it was
 * rather than letting the card's default gap slide the lines apart.
 */
@Composable
private fun TechnicalsHeader(state: TechnicalsUiState, modifier: Modifier = Modifier) {
    GlassCard(
        modifier = modifier.fillMaxWidth(),
        arrangement = Arrangement.Top,
    ) {
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
            ChangePercentText(
                changePercent = state.changePercent,
                style = MaterialTheme.typography.titleLarge,
            )
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
            text = "${state.barCount} ${state.timeframe.name} bars stored · " +
                "${state.minimumBars} needed",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = MiaSpacing.xs),
        )
    }
}

/**
 * Freshness for the EOD bars.
 *
 * [FreshnessKind.DAILY_CANDLE] (a one-day window), not the fundamentals window: the bars come from
 * the same daily bhavcopy, and a week-old close is genuinely stale for a chart whose whole premise is
 * "read this after today's close".
 */
private fun barFreshness(
    asOf: java.time.LocalDate,
): Freshness = FreshnessPolicy.inspect(
    asOf = asOf,
    today = FreshnessPolicy.todayInMarket(),
    kind = FreshnessKind.DAILY_CANDLE,
)

// ------------------------------------------------------------------------------- chart

/**
 * The chart and its overlays.
 *
 * [PriceChart] itself renders the plan.md §6.4 refusal when there are too few bars, so this section
 * only has to build the [ChartState] — the single place that decides which overlays reach the canvas.
 *
 * Overlays come from the **best-scoring match only**. Drawing every match's measured move would put
 * three or four dashed levels on a phone-sized chart and make the geometry unreadable, which is the
 * opposite of plan.md §6.6's purpose.
 */
@Composable
private fun ChartSection(state: TechnicalsUiState, modifier: Modifier = Modifier) {
    val primary = state.primaryMatch
    PriceChart(
        state = ChartState(
            symbol = state.symbol,
            timeframe = state.timeframe,
            candles = state.candles,
            pivots = primary?.pivots.orEmpty(),
            measuredMove = primary?.measuredMove,
            projectedPath = state.projectedPath,
            breakoutTrigger = state.breakoutTrigger,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

// -------------------------------------------------------------------------- indicators

/** One labelled measurement: a value, and the note that says what it is and whether it is ready. */
private data class Reading(
    val label: String,
    val value: String?,
    val note: String? = null,
    /** Bars the calculation needs, when a `null` value is a warm-up gap rather than a missing source. */
    val need: Int? = null,
    /** Bars actually stored. */
    val have: Int? = null,
)

/**
 * The indicator families plan.md §4.2 names, each as a measurement.
 *
 * ### The warm-up honesty rule
 * Every reading comes from the snapshot's `List<Double?>`, and a `null` renders as "Not available —
 * needs N bars, M stored", never as `0`. A 200-day average that has not warmed up is not zero, and
 * printing `0` beside a price of 1,200 is exactly the failure `IndicatorSnapshot`'s nullability
 * exists to prevent.
 *
 * One pane for the whole family rather than one per reading: the readings are a single table with
 * two columns, and a card per cell would break the alignment that makes it readable — and would put
 * fourteen panes on a tab that already has five sections.
 */
@Composable
private fun IndicatorSection(
    state: TechnicalsUiState,
    snapshot: IndicatorSnapshot,
    modifier: Modifier = Modifier,
) {
    val bars = state.barCount
    val bollinger: BollingerLatest? = snapshot.bollinger.latest()

    GlassCard(
        modifier = modifier.fillMaxWidth(),
        arrangement = Arrangement.spacedBy(MiaSpacing.md),
    ) {
        SectionHeader(
            title = UiStrings.INDICATORS,
            subtitle = "Each figure is a calculation over stored bars, shown with its period.",
        )

        ReadingRow(
            title = UiStrings.MOMENTUM,
            first = Reading(
                label = UiStrings.indicatorWithPeriod("RSI", TechnicalPolicy.RSI_PERIOD),
                value = snapshot.rsi.value?.let { formatFixed(it, 1) },
                note = rsiNote(snapshot),
                need = TechnicalPolicy.RSI_PERIOD + 1,
                have = bars,
            ),
            second = Reading(
                label = macdLabel(),
                value = snapshot.macd.value?.let { formatFixed(it, 2) },
                note = "signal " + (snapshot.macd.signal?.let { formatFixed(it, 2) } ?: NOT_YET) +
                    " · histogram " +
                    (snapshot.macd.histogramValue?.let { formatFixed(it, 2) } ?: NOT_YET),
                need = TechnicalPolicy.MACD_SLOW + TechnicalPolicy.MACD_SIGNAL,
                have = bars,
            ),
        )

        ReadingRow(
            title = UiStrings.VOLATILITY,
            first = Reading(
                label = UiStrings.indicatorWithPeriod("ATR", TechnicalPolicy.ATR_PERIOD),
                value = snapshot.atrValue?.let { formatFixed(it, 2) },
                note = "average true range in rupees",
                need = TechnicalPolicy.ATR_PERIOD,
                have = bars,
            ),
            second = Reading(
                label = bollingerLabel(),
                value = bollinger?.let { formatFixed(it.middle, 2) },
                note = "band width " +
                    (bollinger?.bandwidthPercent?.let { formatPercent(it, 1) } ?: NOT_YET) +
                    " · %B " + (bollinger?.percentB?.let { formatFixed(it, 3) } ?: NOT_YET),
                need = TechnicalPolicy.BOLLINGER_PERIOD,
                have = bars,
            ),
        )

        ReadingRow(
            title = UiStrings.VOLUME_FLOW,
            first = Reading(
                label = "OBV",
                value = formatVolume(snapshot.obvValue.toLong()),
                note = "cumulative signed volume; only its slope carries meaning",
            ),
            second = Reading(
                label = "Volume vs 20-bar average",
                value = snapshot.latestVolumeRatio?.let { formatFixed(it, 2) + MULTIPLIES },
                note = "20-bar average " +
                    (snapshot.volumeAverage?.let { formatVolume(it.toLong()) } ?: NOT_YET),
                need = TechnicalPolicy.VOLUME_SMA_PERIOD,
                have = bars,
            ),
        )

        ReadingRow(
            title = UiStrings.MOMENTUM,
            first = Reading(
                label = UiStrings.indicatorWithPeriod("MFI", TechnicalPolicy.MFI_PERIOD),
                value = snapshot.mfiValue?.let { formatFixed(it, 1) },
                // No overbought/oversold reading, and the note says why rather than leaving the
                // absence unexplained. See `TechnicalPolicy.MFI_PERIOD`'s KDoc for the measurement
                // behind refusing to publish a level.
                note = "RSI weighted by volume — did the volume agree with the move? " +
                    "No overbought or oversold level is applied, because none is measured here",
                need = TechnicalPolicy.MFI_PERIOD + 1,
                have = bars,
            ),
            second = Reading(
                label = emaCrossoverLabel(),
                value = emaCrossoverNote(snapshot),
                note = "the faster line above or below the slower one; a state, not an event",
                need = TechnicalPolicy.EMA_MID + TechnicalPolicy.EMA_FAST,
                have = bars,
            ),
        )

        RiskStopSection(snapshot = snapshot)

        ReadingRow(
            title = UiStrings.MOVING_AVERAGES,
            first = Reading(
                label = UiStrings.indicatorWithPeriod("SMA", TechnicalPolicy.SMA_MID),
                value = snapshot.sma50Value?.let { formatFixed(it, 2) },
                need = TechnicalPolicy.SMA_MID,
                have = bars,
            ),
            second = Reading(
                label = UiStrings.indicatorWithPeriod("SMA", TechnicalPolicy.SMA_LONG),
                value = snapshot.sma200Value?.let { formatFixed(it, 2) },
                need = TechnicalPolicy.SMA_LONG,
                have = bars,
            ),
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        DeathCrossState(snapshot = snapshot, bars = bars)
        RsiState(snapshot = snapshot, bars = bars)
    }
}

private fun macdLabel(): String = "MACD (" + TechnicalPolicy.MACD_FAST + "/" + TechnicalPolicy.MACD_SLOW +
    "/" + TechnicalPolicy.MACD_SIGNAL + ")"

private fun bollingerLabel(): String = "Bollinger (" + TechnicalPolicy.BOLLINGER_PERIOD + ", " +
    formatFixed(TechnicalPolicy.BOLLINGER_MULTIPLIER, 1) + SIGMA + ")"

/**
 * The RSI reading with its band stated as a measurement.
 *
 * plan.md §8.2 flags RSI > 70 / < 30, so the note names the line being compared against. It does
 * **not** say "sell" or "expect a fall": the band describes where the oscillator is, and plan.md §6.1's
 * control test over 20,780 next-day returns found momentum readings carry no directional edge at all.
 */
private fun rsiNote(snapshot: IndicatorSnapshot): String {
    val value = snapshot.rsi.value ?: return NOT_YET
    val band = when {
        snapshot.isRsiOverbought -> " · above the upper band"
        snapshot.isRsiOversold -> " · below the lower band"
        else -> " · between the bands"
    }
    return "bands " + formatFixed(TechnicalPolicy.RSI_OVERSOLD, 0) + " and " +
        formatFixed(TechnicalPolicy.RSI_OVERBOUGHT, 0) + band
}

/**
 * plan.md §8.2's death cross, stated as its two independent halves.
 *
 * The condition is `close < SMA200 AND SMA50 < SMA200`, and the app reports **both** separately.
 * Collapsing them into one "death cross: yes/no" would hide the case that matters most — the 50-day
 * has crossed below while price has not yet closed below the 200-day — and that is the state a
 * reader most needs to see. When there is not enough history for a 200-day average, the row says so
 * with the bar counts rather than reporting "not met".
 */
@Composable
private fun DeathCrossState(snapshot: IndicatorSnapshot, bars: Int, modifier: Modifier = Modifier) {
    val text = if (snapshot.sma200Value == null) {
        UiStrings.unavailable(
            "the 200-day average needs " + TechnicalPolicy.SMA_LONG + " bars and $bars are stored",
        )
    } else {
        UiStrings.deathCrossState(
            sma50BelowSma200 = snapshot.isDeathCross,
            closeBelowSma200 = snapshot.isBelowLongTermAverage,
        )
    }
    ReadingBlock(
        title = "200-day moving average state",
        value = if (snapshot.isDeathCrossCondition()) "Both conditions met" else "Condition not fully met",
        note = text,
        modifier = modifier,
    )
}

/** The RSI band as its own row, so it is visible without scrolling back to the momentum block. */
@Composable
private fun RsiState(snapshot: IndicatorSnapshot, bars: Int, modifier: Modifier = Modifier) {
    val value = snapshot.rsi.value
    val note = if (value == null) {
        UiStrings.unavailable(
            "the " + TechnicalPolicy.RSI_PERIOD + "-period RSI needs " +
                (TechnicalPolicy.RSI_PERIOD + 1) + " bars and $bars are stored",
        )
    } else {
        when {
            snapshot.isRsiOverbought ->
                "Above the " + formatFixed(TechnicalPolicy.RSI_OVERBOUGHT, 0) + " line"

            snapshot.isRsiOversold ->
                "Below the " + formatFixed(TechnicalPolicy.RSI_OVERSOLD, 0) + " line"

            else ->
                "Between the " + formatFixed(TechnicalPolicy.RSI_OVERSOLD, 0) + " and " +
                    formatFixed(TechnicalPolicy.RSI_OVERBOUGHT, 0) + " lines"
        }
    }
    ReadingBlock(
        title = "RSI band state",
        value = value?.let { formatFixed(it, 1) } ?: NOT_YET,
        note = note,
        modifier = modifier,
    )
}

/**
 * The ADX regime as a band, never as a direction the app is "in".
 *
 * Wilder's own 20–25 band is [TrendRegime.UNKNOWN] by design in `:domain` — claiming a direction
 * there is the confident guess the product exists to avoid — and the UI repeats that rather than
 * smoothing it into "sideways".
 */
@Composable
private fun TrendSection(state: TechnicalsUiState, modifier: Modifier = Modifier) {
    val snapshot = state.snapshot ?: return
    val regime = state.trendRegime
    GlassCard(
        modifier = modifier.fillMaxWidth(),
        arrangement = Arrangement.spacedBy(MiaSpacing.xs),
    ) {
        SectionHeader(title = UiStrings.TREND_REGIME)
        ReadingBlock(
            title = UiStrings.indicatorWithPeriod("ADX", TechnicalPolicy.ADX_PERIOD),
            value = snapshot.adx.value?.let { formatFixed(it, 1) } ?: NOT_YET,
            note = "trendline fit R² " + formatFixed(snapshot.trendlineFitRSquared, 3),
        )
        LabelledNumber("Regime", regime.label())
        LabelledNumber(
            "Directional index",
            "+DI " + (snapshot.adx.diPlusValue?.let { formatFixed(it, 1) } ?: NOT_YET) +
                " · −DI " + (snapshot.adx.diMinusValue?.let { formatFixed(it, 1) } ?: NOT_YET),
        )
        // Price against its own 200-day average, as a signed distance plus the level itself — the
        // other half of the §8.2 death-cross condition, shown as a measurement.
        ValueAgainstLevel(
            label = "Close against the 200-day average",
            value = snapshot.sma200Value,
            actual = snapshot.lastClose,
        )
    }
}

/** The regime as a plain measurement. `UNKNOWN` is spelled out, never rendered as sideways. */
private fun TrendRegime.label(): String = when (this) {
    TrendRegime.TRENDING_UP -> "Trending up (ADX at or above " +
        formatFixed(TechnicalPolicy.ADX_TRENDING, 0) + " with +DI above −DI)"

    TrendRegime.TRENDING_DOWN -> "Trending down (ADX at or above " +
        formatFixed(TechnicalPolicy.ADX_TRENDING, 0) + " with −DI above +DI)"

    TrendRegime.RANGING -> "Ranging (ADX below " + formatFixed(TechnicalPolicy.ADX_RANGING, 0) + ")"

    TrendRegime.UNKNOWN -> "Undecided (ADX between " +
        formatFixed(TechnicalPolicy.ADX_RANGING, 0) + " and " +
        formatFixed(TechnicalPolicy.ADX_TRENDING, 0) + ", or not enough history)"
}

// --------------------------------------------------------------------------- matches

/**
 * Every renderable match, best score first.
 *
 * Below `MatchScorePolicy.RENDER_THRESHOLD` the engine suppresses a match entirely (plan.md §6.4), so
 * "none found" means "nothing scored 50 or more" — a result, not a failure, and worded that way.
 */
@Composable
private fun MatchSection(state: TechnicalsUiState, modifier: Modifier = Modifier) {
    GlassCard(
        modifier = modifier.fillMaxWidth(),
        arrangement = Arrangement.spacedBy(MiaSpacing.md),
    ) {
        SectionHeader(
            title = UiStrings.PATTERN_MATCHES,
            subtitle = "Ranked by how closely the shape resembles the textbook pattern.",
        )
        when {
            state.matches.isNotEmpty() -> state.matches.forEach { match ->
                ScoreExplainCard(match = match)
            }

            !state.hasEnoughHistory -> EmptyState(
                title = UiStrings.NOT_ENOUGH_HISTORY,
                body = UiStrings.notEnoughHistoryDetail(state.barCount, state.minimumBars),
            )

            state.barCount == 0 -> EmptyState(
                title = "No bars stored for this symbol yet",
                body = "The chart, the indicators and the pattern scan all need stored bars. " +
                    "Nothing is drawn from an empty series.",
            )

            else -> EmptyState(
                title = "No match above the threshold",
                body = UiStrings.noMatchesFound(MatchScorePolicy.RENDER_THRESHOLD),
            )
        }
    }
}

// ---------------------------------------------------------------- measured move / path

/**
 * plan.md §6.6 items 1 and 2 in one panel: the measured move and the dashed path.
 *
 * The headline label is [MatchScoreLabels.PROJECTION_LABEL], the numbers are the formation's own, and
 * the panel closes with [Disclosure.PROJECTION_FOOTER]. There is no code path in this composable that
 * can render the word "target" — the copy comes from `MatchScoreLabels`, which is the domain's single
 * source for it.
 *
 * @param move `null` when the best match has no measured move; the path is still shown, because
 *   decision D2 requires both outputs and either may exist without the other.
 */
@Composable
private fun MeasuredMovePanel(
    move: MeasuredMove?,
    path: ProjectedPath?,
    modifier: Modifier = Modifier,
) {
    GlassCard(
        modifier = modifier.fillMaxWidth(),
        arrangement = Arrangement.spacedBy(MiaSpacing.sm),
        // What the `CardDefaults.cardColors(contentColor = onSurfaceVariant)` call set.
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            text = UiStrings.PROJECTION,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = MatchScoreLabels.PROJECTION_LABEL,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )

        if (move != null) {
            LabelledNumber("Formation height", formatPrice(move.height))
            LabelledNumber("Trigger level", formatPrice(move.triggerLevel))
            LabelledNumber("Derived level", formatPrice(move.projectedLevel))
            LabelledNumber(
                "Direction",
                if (move.isUpward) "Upward from the trigger" else "Downward from the trigger",
            )
            Text(
                text = MatchScoreLabels.NOT_A_TARGET_WARNING,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (path != null) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            // `ProjectedPath.label` is already "Projected path (directional, not a forecast of
            // price)"; rendering it verbatim keeps the wording welded to the type that carries it.
            Text(text = path.label, style = MaterialTheme.typography.labelLarge)
            LabelledNumber("Bars projected", path.horizonBars.toString())
            path.points.forEach { point ->
                LabelledNumber(label = formatDate(point.first), value = formatPrice(point.second))
            }
        }

        if (move != null || path != null) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                text = Disclosure.PROJECTION_FOOTER,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * plan.md §6.6 item 3: the breakout alert — a condition on a close.
 *
 * Kept in its own panel, never merged into the projection: decision D2 requires the two to stay
 * separate outputs with separate schedules, and a single "projection" card containing both is how
 * they get conflated.
 */
@Composable
private fun BreakoutPanel(
    trigger: BreakoutTrigger,
    timeframe: Timeframe,
    modifier: Modifier = Modifier,
) {
    GlassCard(
        modifier = modifier.fillMaxWidth(),
        arrangement = Arrangement.spacedBy(MiaSpacing.sm),
        // What the `CardDefaults.cardColors(contentColor = onSurfaceVariant)` call set.
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            text = UiStrings.BREAKOUT_TRIGGER,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(text = trigger.description, style = MaterialTheme.typography.bodyMedium)
        LabelledNumber("Level", formatPrice(trigger.level))
        LabelledNumber(
            "State on stored ${timeframe.name} bars",
            if (trigger.fired) {
                "This condition has been met."
            } else {
                "This condition has not been met."
            },
        )
    }
}

// ----------------------------------------------------------------------- track record

/**
 * The app's own track record (plan.md §6.1, decision D6).
 *
 * Always rendered, even with no outcomes, because "we have not tracked this yet" and "we have
 * tracked it and it is withheld" are different facts. Below 30 samples [TrackRecordRow] shows the
 * count instead of a percentage, which is the guard against a 100% built from three samples.
 */
@Composable
private fun TrackRecordSection(state: TechnicalsUiState, modifier: Modifier = Modifier) {
    GlassCard(
        modifier = modifier.fillMaxWidth(),
        arrangement = Arrangement.spacedBy(MiaSpacing.sm),
    ) {
        SectionHeader(
            title = UiStrings.TRACK_RECORD,
            subtitle = "How often a pattern's geometry level was reached on this stock, across " +
                "past tracked outcomes.",
        )
        if (state.trackRecord == null && state.trackRecordForSymbol == null) {
            EmptyState(
                title = "No tracked outcomes yet",
                body = "Outcomes are recorded when a projection's evaluation window closes. " +
                    MatchScorePolicy.MIN_HIT_RATE_SAMPLES +
                    " tracked samples are needed before any percentage is shown.",
            )
        } else {
            TrackRecordRow(
                perSymbolAndPattern = state.trackRecord,
                perSymbol = state.trackRecordForSymbol,
                patternName = state.primaryMatch?.name,
            )
        }
    }
}

/**
 * Facts about the stored series that change how its numbers should be read.
 *
 * Currently one: a suspected corporate action inside the window. The series is stored unadjusted,
 * so a split shows up as a price cliff, and every indicator spanning it is skewed. The copy is
 * generated in `:domain` (one rule, one JVM test) and passed through untouched — the screen adds no
 * interpretation of its own, so there is no second place for the wording to drift.
 *
 * Rendered in the tertiary container rather than the error container: nothing has failed, and a
 * red card would imply the app is broken rather than that the source is unadjusted.
 *
 * The pane keeps the neutral glass fill while the *type* stays on the tertiary pair, because here
 * the colour is saying "this is a caveat, not a fault" and it is the type that has to be legible.
 */
@Composable
private fun SeriesNotesSection(notes: List<String>, modifier: Modifier = Modifier) {
    GlassCard(
        modifier = modifier.fillMaxWidth(),
        arrangement = Arrangement.spacedBy(MiaSpacing.xs),
    ) {
        Text(
            text = SERIES_NOTES_TITLE,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
        notes.forEach { note ->
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}

// --------------------------------------------------------------------------- primitives

/** Two readings side by side. A plain `Row`, not a `FlowRow`: the layout is fixed, not adaptive. */
@Composable
private fun ReadingRow(
    title: String,
    first: Reading,
    second: Reading,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MiaSpacing.sm),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MiaSpacing.md),
        ) {
            ReadingCell(reading = first, modifier = Modifier.weight(1f))
            ReadingCell(reading = second, modifier = Modifier.weight(1f))
        }
    }
}

/**
 * A single reading.
 *
 * `value == null` is the case this file exists to handle correctly: it renders "Not available — needs
 * N bars, M stored" rather than a zero.
 */
@Composable
private fun ReadingCell(reading: Reading, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = reading.label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = reading.value ?: unavailableNote(reading),
            style = MaterialTheme.typography.titleMedium,
            color = if (reading.value == null) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
        reading.note?.takeIf { reading.value != null }?.let { note ->
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A reading with a title of its own, used by the death-cross, RSI and ADX state rows. */
@Composable
private fun ReadingBlock(
    title: String,
    value: String,
    note: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = note,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A label beside a number. Used across the geometry, trigger and track-record panels. */
@Composable
private fun LabelledNumber(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
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

private fun unavailableNote(reading: Reading): String {
    val need = reading.need
    val have = reading.have
    return if (need != null && have != null) {
        UiStrings.unavailable("needs $need bars, $have stored")
    } else {
        NOT_YET
    }
}

/**
 * The risk line: a 2% stop, in rupees and in ATR units.
 *
 * ### Why this earns a card rather than a footnote
 *
 * "Use a 2% stop" is the most common piece of risk advice in retail trading and it is close to
 * meaningless on its own — 2% is roughly three days of noise on a quiet large cap and less than a
 * session's range on a volatile one. The number that makes it mean something is the ATR beside it,
 * and the only way to get both in front of a reader is to render them together.
 *
 * ### What it does not do
 *
 * It does not suggest a stop, recommend a position size, or imply that either is appropriate for
 * anybody. It converts one percentage into two measurable distances and stops there. The rupee
 * figure is always reportable; the ATR multiple appears only when ATR is available, because a
 * ratio against an undefined ATR is precisely the fabricated reading this app does not publish.
 */
@Composable
private fun RiskStopSection(snapshot: IndicatorSnapshot) {
    val price = snapshot.lastClose
    val line = if (price != null && price > 0.0) {
        riskLine(price, RISK_STOP_PERCENT, snapshot.atrValue)
    } else {
        null
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MiaSpacing.xs),
    ) {
        SectionHeader(title = RISK_LINE_TITLE, subtitle = RISK_LINE_EXPLAINER)
        ReadingBlock(
            title = "A $RISK_STOP_PERCENT% stop sits",
            value = line?.let { "₹" + formatFixed(it.rupees, 2) } ?: NOT_YET,
            note = price?.let { "from the last close of ₹" + formatFixed(it, 2) }
                ?: "no stored close yet, so there is nothing to measure a stop from",
        )
        ReadingBlock(
            title = "That is",
            value = line?.atrMultiple?.let { formatFixed(it, 2) + MULTIPLIES + " ATR" } ?: NOT_YET,
            note = line?.atr
                ?.let { "against an average true range of ₹" + formatFixed(it, 2) + " per bar" }
                ?: "no average true range yet, so the volatility multiple cannot be computed",
        )
    }
}

/**
 * EMA(20/50) crossover, as a state rather than as an event.
 *
 * Labelled "state, not an event" for the same reason `DEATH_CROSS` leads with its date in the
 * fundamentals screen: `docs/VERIFICATION.md` records that a cross persists across roughly half of
 * all rolling observations, so presenting it as news would misrepresent a persistent condition as a
 * fresh signal.
 */
private fun emaCrossoverLabel(): String =
    UiStrings.indicatorWithPeriod("EMA", TechnicalPolicy.EMA_FAST) + " / " +
        TechnicalPolicy.EMA_MID + " crossover"

private fun emaCrossoverNote(snapshot: IndicatorSnapshot): String {
    val fast = snapshot.ema20Value
    val slow = snapshot.ema50Value
    if (fast == null || slow == null || slow == 0.0) return NOT_YET
    val gap = (fast - slow) / slow * 100.0
    val side = if (fast > slow) "above" else "below"
    return "$side by " + formatFixed(kotlin.math.abs(gap), 2) + "%"
}

/** Shown wherever a sub-reading is missing but its parent exists. Never a zero. */
private const val NOT_YET: String = "not yet"

/** The stop percentage the risk line is drawn at. planv2 §W5 specifies 2%. */
private const val RISK_STOP_PERCENT: Double = 2.0

private const val RISK_LINE_TITLE: String = "Risk line"
private const val RISK_LINE_EXPLAINER: String =
    "A percentage stop only means something against the instrument's own volatility. " +
        "2% on a stock whose average true range is 0.6% is about three days of ordinary movement; " +
        "the same 2% where the range is 3% sits inside a single session."

/** Multiplier suffix for a volume ratio. `×` reads as "times" without the reader decoding it. */
private const val MULTIPLIES: String = "×"

/** Sigma suffix for the Bollinger label. */
private const val SIGMA: String = "σ"

/** Stand-in when a route arrived without a symbol; see `ScreenArgs.symbolOf`. */
private const val NOT_AVAILABLE_SYMBOL: String = "—"

/** Test tag on the tab root. */
const val TECHNICALS_TAG: String = "mia_technicals"

// Lazy-list keys. Stable strings, so a rotation does not rebuild every row.
private const val HEADER_KEY = "technicals.header"
private const val TIMEFRAME_KEY = "technicals.timeframe"
private const val LOADING_KEY = "technicals.loading"
private const val ERROR_KEY = "technicals.error"
private const val ABSENCE_KEY = "technicals.absence"
private const val CHART_KEY = "technicals.chart"
private const val INDICATORS_KEY = "technicals.indicators"
private const val TREND_KEY = "technicals.trend"
private const val MATCHES_KEY = "technicals.matches"
private const val MEASURED_MOVE_KEY = "technicals.measured_move"
private const val PROJECTION_ONLY_KEY = "technicals.projection"
private const val TRIGGER_KEY = "technicals.trigger"
private const val TRACK_RECORD_KEY = "technicals.track_record"
private const val FLAG_COVERAGE_KEY = "technicals.flag_coverage"
private const val SERIES_NOTES_KEY = "technicals.series_notes"

/** Heading for the series-quality section. Neutral wording: nothing has failed. */
private const val SERIES_NOTES_TITLE = "About this series"
private const val FOOTER_KEY = "technicals.footer"

/**
 * The signature of the change read-out. Aliased so a reader searching for the tab's number styles
 * finds one name instead of two spellings of `TextStyle`.
 */
internal typealias TechnicalsTextStyle = TextStyle
