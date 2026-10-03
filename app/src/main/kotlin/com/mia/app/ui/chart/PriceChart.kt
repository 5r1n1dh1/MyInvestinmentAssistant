package com.mia.app.ui.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.calculateCentroid
import androidx.compose.ui.input.pointer.calculatePan
import androidx.compose.ui.input.pointer.calculateZoom
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.MatchScoreLabels
import com.mia.app.domain.model.PivotKind
import com.mia.app.ui.common.EmptyState
import com.mia.app.ui.common.UiStrings
import com.mia.app.ui.common.formatDate
import com.mia.app.ui.common.formatPercent
import com.mia.app.ui.common.formatPrice
import com.mia.app.ui.common.formatVolume
import com.mia.app.ui.common.shortLabel
import com.mia.app.ui.theme.MiaMarketColors
import com.mia.app.ui.theme.MiaSpacing
import com.mia.app.ui.theme.MiaTheme
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The price chart: a hand-drawn Compose [Canvas], no chart library (plan.md §11 OQ-1).
 *
 * ### What it draws
 * * a **volume histogram** in a lower pane, filled with the bar's own direction colour, so a
 *   distribution day (plan.md §8.2) reads as a coloured spike without reading a number;
 * * **candlesticks** on `1D` and a **line + area** on `1W`, where five overlapping OHLC ranges are
 *   noise on a phone;
 * * an auto-scaled y axis on 1/2/5×10ⁿ ticks, and **monthly** (`1D`) / **quarterly** (`1W`) dates;
 * * **overlays**: pivot markers, the measured-move level as a long-dashed line labelled
 *   [MatchScoreLabels.PROJECTION_LABEL], the projected path as a fine-dashed polyline, and the
 *   breakout trigger as a dotted line. Three dash patterns and three hues, because plan.md §6.6 /
 *   decision D2 require the three outputs to stay visually separate;
 * * a **touch crosshair** with an OHLC readout;
 * * **pinch-zoom** about the touch centroid and **two-finger pan**, both clamped to a bounded bar
 *   window.
 *
 * ### The three performance rules this file obeys (plan.md §12, 2 GB-RAM target)
 * 1. **One `draw` pass per frame.** Axis labels and the readout are positioned `Text`
 *    composables over the Canvas, so the Canvas draws shapes only.
 * 2. **Geometry precomputed in a `remember`.** [ChartGeometry] holds every per-bar coordinate, both
 *    `Path`s, every dash effect and every label string.
 * 3. **No allocation in the draw loop.** The draw body indexes `FloatArray`s and passes
 *    value-class arguments. Dash effects are built once at file scope; nothing is `listOf`'d,
 *    mapped or formatted between `draw` calls.
 *
 * ### The honesty rule (plan.md §6.4)
 * Below the bar gate the chart draws **no series at all** — it says "not enough history" with the
 * counts. Drawing a 30-bar line where the engine refuses to score anything is how an app convinces
 * a reader it has a view.
 *
 * @param height the chart's box height. The caller sizes it; 260 dp is a sensible phone default.
 */
@Composable
fun PriceChart(
    state: ChartState,
    modifier: Modifier = Modifier,
    height: Dp = DEFAULT_CHART_HEIGHT,
) {
    if (!state.hasEnoughHistory) {
        EmptyState(
            title = UiStrings.NOT_ENOUGH_HISTORY,
            body = UiStrings.notEnoughHistoryDetail(
                have = state.candles.size,
                need = state.requiredBars,
            ),
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp),
        )
        return
    }

    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    // The window is chart-owned state, not caller state: zoom is a view concern, it survives a
    // crosshair move, and keeping it out of `ChartState` means a crosshair drag cannot invalidate
    // the geometry pass. Keyed on symbol + timeframe so switching stock shows the whole series.
    val windowState = remember(state.symbol, state.timeframe) {
        mutableStateOf(ChartWindow.whole(state.candles.size))
    }
    val window = windowState.value
    var crosshairBar by remember(state.symbol, state.timeframe) { mutableStateOf<Int?>(null) }

    val density = LocalDensity.current
    val marketColors = MiaTheme.marketColors

    val geometry: ChartGeometry? = remember(state, window, canvasSize, density) {
        if (canvasSize.width <= 0 || canvasSize.height <= 0) {
            null
        } else {
            ChartGeometry.build(
                state = state.copy(window = window),
                widthPx = canvasSize.width.toFloat(),
                heightPx = canvasSize.height.toFloat(),
                density = density,
            )
        }
    }

    // The gesture loop reads the *current* geometry through `State` instead of closing over it, so
    // a pinch cannot restart its own gesture handler on every frame.
    val latestGeometry = rememberUpdatedState(geometry)

    Box(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .onSizeChanged { canvasSize = it }
                .semantics { contentDescription = chartDescription(state) }
                .pointerInput(state.symbol, state.timeframe) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var multiTouch = false
                        crosshairBar = latestGeometry.value?.xToBar(down.position.x)

                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break

                            if (pressed.size > 1) {
                                multiTouch = true
                                applyZoomAndPan(event, latestGeometry.value, windowState)
                                event.changes.forEach { it.consume() }
                            } else if (multiTouch) {
                                // Continuation of a two-finger gesture: pan only, no zoom.
                                applyZoomAndPan(event, latestGeometry.value, windowState)
                                event.changes.forEach { it.consume() }
                            } else {
                                crosshairBar = latestGeometry.value?.xToBar(pressed[0].position.x)
                            }
                        }
                        crosshairBar = null
                    }
                },
        ) {
            val current = geometry
            if (current == null) {
                // A zero-sized first frame. A transparent rect keeps the lambda total; nothing is
                // drawn, because drawing anything before the geometry exists would be a guess.
                drawRect(color = Color.Transparent)
            } else {
                drawChart(
                    geometry = current,
                    state = state,
                    crosshairBar = crosshairBar,
                    crosshairColor = marketColors.crosshair,
                    marketColors = marketColors,
                )
            }
        }

        val current = geometry
        if (current != null) {
            ChartAxisLabels(geometry = current, axisTextColor = marketColors.mutedText)
            CrosshairReadout(
                candle = crosshairBar?.let { state.candleAt(it) },
                modifier = Modifier.align(Alignment.TopStart),
            )
        }
    }
}

/**
 * Applies one frame of a pinch/drag gesture to the window.
 *
 * Split out of the gesture loop because the same three lines are needed on the "two pointers down"
 * frame and the "one pointer left" frame, and duplicating gesture maths is how zoom and pan drift
 * apart.
 */
private fun applyZoomAndPan(
    event: PointerEvent,
    geometry: ChartGeometry?,
    windowState: MutableState<ChartWindow>,
) {
    if (geometry == null) return
    val zoom = event.calculateZoom()
    if (zoom.isFinite() && zoom > 0f) {
        windowState.value = ChartGeometry.zoomWindow(
            geometry = geometry,
            current = windowState.value,
            anchorX = event.calculateCentroid().x,
            zoom = zoom,
        )
    }
    val panX = event.calculatePan().x
    if (panX != 0f) {
        windowState.value = ChartGeometry.panWindow(
            geometry = geometry,
            current = windowState.value,
            panX = panX,
        )
    }
}

/** Default chart height. Comfortable one-thumb on a 5-inch screen, with room for both panes. */
private val DEFAULT_CHART_HEIGHT: Dp = 260.dp

// ------------------------------------------------------------------------------ draw pass

/**
 * The whole chart in one `DrawScope` body.
 *
 * ### Allocation audit
 * Colours come from a remembered object, dash effects from file-scope constants, widths from
 * `Float` constants. The loops index `FloatArray`s. `drawLine`/`drawRect`/`drawCircle`/`drawPath`
 * take value-class arguments, so nothing here allocates beyond the native call itself.
 */
private fun DrawScope.drawChart(
    geometry: ChartGeometry,
    state: ChartState,
    crosshairBar: Int?,
    crosshairColor: Color,
    marketColors: MiaMarketColors,
) {
    // ---- grid ---------------------------------------------------------------------------------
    for (i in geometry.priceTicks.indices) {
        val y = geometry.gridYs[i]
        if (y < geometry.plotTop - 1f || y > geometry.plotBottom + 1f) continue
        drawLine(
            color = marketColors.grid,
            start = Offset(geometry.plotLeft, y),
            end = Offset(geometry.plotRight, y),
            strokeWidth = GRID_WIDTH_PX,
        )
    }
    drawLine(
        color = marketColors.grid,
        start = Offset(geometry.plotRight, geometry.plotTop),
        end = Offset(geometry.plotRight, geometry.volumeBottom),
        strokeWidth = GRID_WIDTH_PX,
    )

    // ---- price pane ---------------------------------------------------------------------------
    clipRect(
        left = geometry.plotLeft,
        top = geometry.plotTop,
        right = geometry.plotRight,
        bottom = geometry.plotBottom,
    ) {
        when (state.style) {
            ChartStyle.CANDLESTICK -> drawCandlesticks(geometry, marketColors)
            ChartStyle.LINE_AREA -> drawLineAndArea(geometry, marketColors)
        }
        drawPivots(geometry, state, marketColors)
        drawOverlays(geometry, state, marketColors)
        drawCrosshair(geometry, crosshairBar, crosshairColor)
    }

    // ---- volume pane --------------------------------------------------------------------------
    clipRect(
        left = geometry.plotLeft,
        top = geometry.volumeTop,
        right = geometry.plotRight,
        bottom = geometry.volumeBottom,
    ) {
        val barWidthPx = geometry.barWidth * ChartDefaults.BODY_WIDTH_FRACTION
        val halfWidth = barWidthPx / 2f
        for (i in 0 until geometry.drawnCount) {
            val top = geometry.volumeTopY[i]
            drawRect(
                color = if (geometry.bullish[i]) marketColors.bullVolume else marketColors.bearVolume,
                topLeft = Offset(geometry.barX[i] - halfWidth, top),
                size = Size(barWidthPx, geometry.volumeBottom - top),
            )
        }
    }
    drawLine(
        color = marketColors.grid,
        start = Offset(geometry.plotLeft, geometry.volumeBottom),
        end = Offset(geometry.plotRight, geometry.volumeBottom),
        strokeWidth = GRID_WIDTH_PX,
    )
}

/** Wick + body per bar. A doji (open == close) still gets [ChartDefaults.MIN_BODY_PX] of body. */
private fun DrawScope.drawCandlesticks(
    geometry: ChartGeometry,
    marketColors: MiaMarketColors,
) {
    val bodyWidth = max(
        geometry.barWidth * ChartDefaults.BODY_WIDTH_FRACTION,
        ChartDefaults.MIN_BODY_PX,
    )
    val wickWidth = max(bodyWidth * 0.22f, 1f)
    val halfWidth = bodyWidth / 2f
    for (i in 0 until geometry.drawnCount) {
        val x = geometry.barX[i]
        val bullish = geometry.bullish[i]
        val stroke = if (bullish) marketColors.bull else marketColors.bear
        drawLine(
            color = stroke,
            start = Offset(x, geometry.highY[i]),
            end = Offset(x, geometry.lowY[i]),
            strokeWidth = wickWidth,
        )
        val bodyTop = minOf(geometry.openY[i], geometry.closeY[i])
        val bodyHeight = max(
            abs(geometry.closeY[i] - geometry.openY[i]),
            ChartDefaults.MIN_BODY_PX,
        )
        drawRect(
            color = if (bullish) marketColors.bullBody else marketColors.bearBody,
            topLeft = Offset(x - halfWidth, bodyTop),
            size = Size(bodyWidth, bodyHeight),
        )
    }
}

/** The weekly fallback: a stroked close line plus a translucent area under it. */
private fun DrawScope.drawLineAndArea(
    geometry: ChartGeometry,
    marketColors: MiaMarketColors,
) {
    if (geometry.drawnCount <= 1) return
    drawPath(path = geometry.areaPath, color = marketColors.bullBody.copy(alpha = AREA_ALPHA))
    drawPath(
        path = geometry.closePath,
        color = marketColors.bull,
        style = Stroke(width = SERIES_WIDTH_PX, cap = StrokeCap.Round),
    )
}

/**
 * Swing-high / swing-low markers.
 *
 * **Filled triangle pointing down for a high, hollow triangle pointing up for a low.** The fill
 * difference is the non-colour encoding, because a pattern's pivot sequence is the evidence behind
 * its score and must be checkable by a reader who cannot tell the two marker hues apart.
 */
private fun DrawScope.drawPivots(
    geometry: ChartGeometry,
    state: ChartState,
    marketColors: MiaMarketColors,
) {
    for (pivot in state.pivots) {
        val index = pivot.barIndex - geometry.window.startBar
        if (index < 0 || index >= geometry.drawnCount) continue
        val x = geometry.barX[index]
        val isHigh = pivot.kind == PivotKind.HIGH
        // Refill a geometry-owned Path rather than allocating one: see `setPivotMarker`.
        if (isHigh) {
            geometry.setPivotMarker(
                path = geometry.pivotHighPath,
                x = x,
                y = geometry.highY[index],
                halfWidthPx = PIVOT_MARKER_PX,
                downward = true,
            )
            drawPath(path = geometry.pivotHighPath, color = marketColors.pivotHigh)
        } else {
            geometry.setPivotMarker(
                path = geometry.pivotLowPath,
                x = x,
                y = geometry.lowY[index],
                halfWidthPx = PIVOT_MARKER_PX,
                downward = false,
            )
            drawPath(
                path = geometry.pivotLowPath,
                color = marketColors.pivotLow,
                style = Stroke(width = GRID_WIDTH_PX * 1.5f),
            )
        }
    }
}

/**
 * The three plan.md §6.6 outputs, in three dash patterns and three hues.
 *
 * Order is deliberate: the trigger first (behind), then the measured move, then the projected path
 * on top — the finest pattern is the easiest to lose, so it wins the z-order.
 */
private fun DrawScope.drawOverlays(
    geometry: ChartGeometry,
    state: ChartState,
    marketColors: MiaMarketColors,
) {
    state.breakoutTrigger?.let { trigger ->
        drawLevel(
            geometry = geometry,
            price = trigger.level.toFloat(),
            color = marketColors.breakoutTrigger,
            effect = TRIGGER_EFFECT,
        )
    }

    state.measuredMove?.let { move ->
        drawLevel(
            geometry = geometry,
            price = move.projectedLevel.toFloat(),
            color = marketColors.measuredMove,
            effect = MEASURED_MOVE_EFFECT,
        )
        drawLevel(
            geometry = geometry,
            price = move.triggerLevel.toFloat(),
            color = marketColors.measuredMove,
            effect = TRIGGER_EFFECT,
        )
    }

    val projected = state.projectedPath ?: return
    if (projected.points.size < 2) return
    var previousX = geometry.plotRight
    var previousY = geometry.closeY[geometry.drawnCount - 1]
    for (i in projected.points.indices) {
        val x = projectedPathX(geometry, index = i, pointCount = projected.points.size)
        val y = geometry.priceToY(projected.points[i].second.toFloat())
        drawLine(
            color = marketColors.projectedPath,
            start = Offset(previousX, previousY),
            end = Offset(x, y),
            strokeWidth = SERIES_WIDTH_PX,
            pathEffect = PROJECTED_PATH_EFFECT,
        )
        previousX = x
        previousY = y
    }
}

/**
 * One horizontal dashed level.
 *
 * A level outside the visible price range is **not** drawn and **not** clamped to the pane edge:
 * a dashed line pinned to the top of the chart reads as "the level is here", which is a claim the
 * number does not support. The exact value is always on the projection panel under the chart, and
 * the price scale in [ChartGeometry.addOverlays] is widened to bring an in-reason level into view.
 */
private fun DrawScope.drawLevel(
    geometry: ChartGeometry,
    price: Float,
    color: Color,
    effect: PathEffect,
) {
    if (!price.isFinite()) return
    val y = geometry.priceToY(price)
    if (y < geometry.plotTop || y > geometry.plotBottom) return
    drawLine(
        color = color,
        start = Offset(geometry.plotLeft, y),
        end = Offset(geometry.plotRight, y),
        strokeWidth = SERIES_WIDTH_PX,
        pathEffect = effect,
    )
}

/**
 * Where the projected path's `index`-th point sits on the x axis.
 *
 * The path is dated (`ProjectedPath.points` is `List<Pair<LocalDate, Double>>`) but the window is
 * indexed, so points are laid out evenly in the slots to the right of the last bar. That is a
 * deliberate simplification: a 1–5 bar path is a *horizon*, and even spacing makes the horizon's
 * length readable at a glance. The exact dates are on the projection panel.
 */
private fun projectedPathX(geometry: ChartGeometry, index: Int, pointCount: Int): Float =
    geometry.plotRight - (pointCount - index).toFloat() * geometry.barWidth

/** The touch crosshair: a vertical rule, and a horizontal one at the touched bar's close. */
private fun DrawScope.drawCrosshair(
    geometry: ChartGeometry,
    crosshairBar: Int?,
    color: Color,
) {
    val absolute = crosshairBar ?: return
    val index = absolute - geometry.window.startBar
    if (index < 0 || index >= geometry.drawnCount) return
    val x = geometry.barX[index]
    drawLine(
        color = color,
        start = Offset(x, geometry.plotTop),
        end = Offset(x, geometry.volumeTop),
        strokeWidth = SERIES_WIDTH_PX,
    )
    drawLine(
        color = color,
        start = Offset(geometry.plotLeft, geometry.closeY[index]),
        end = Offset(geometry.plotRight, geometry.closeY[index]),
        strokeWidth = GRID_WIDTH_PX,
    )
}

// ------------------------------------------------------------------------ Compose overlays

/**
 * The y-axis price labels (right gutter) and the x-axis date labels (below the volume pane).
 *
 * Drawn as positioned `Text` rather than with `drawText` for three reasons: `drawText` needs a
 * `TextMeasurer`, whose `rememberTextMeasurer` has moved in and out of the experimental API across
 * Compose versions and this module cannot be compiled here to check; `Text` gets font scaling and
 * accessibility for free; and the Canvas stays a pure shape pass, which is what keeps it at one
 * `draw` per frame.
 */
@Composable
private fun ChartAxisLabels(
    geometry: ChartGeometry,
    axisTextColor: Color,
) {
    val labelStyle = MaterialTheme.typography.labelSmall

    for (i in geometry.priceTicks.indices) {
        val y = geometry.gridYs[i]
        if (y < geometry.plotTop - LABEL_HALF_HEIGHT_PX || y > geometry.plotBottom + LABEL_HALF_HEIGHT_PX) {
            continue
        }
        Text(
            text = geometry.priceTickLabels[i],
            style = labelStyle,
            color = axisTextColor,
            maxLines = 1,
            textAlign = TextAlign.End,
            modifier = Modifier
                .offset {
                    IntOffset(
                        x = (geometry.plotRight + AXIS_LABEL_GAP_PX).roundToInt(),
                        y = (y - LABEL_HALF_HEIGHT_PX).roundToInt(),
                    )
                }
                .width(ChartDefaults.AXIS_GUTTER - AXIS_LABEL_GAP_PX.dp)
                .padding(start = MiaSpacing.xs),
        )
    }

    for (i in geometry.dateTicks.indices) {
        val absolute = geometry.dateTicks[i]
        if (absolute < geometry.window.startBar ||
            absolute >= geometry.window.startBar + geometry.drawnCount
        ) {
            continue
        }
        val x = geometry.barToX(absolute)
        Text(
            text = geometry.dateTickLabels[i],
            style = labelStyle,
            color = axisTextColor,
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .offset {
                    IntOffset(
                        x = (x - DATE_LABEL_HALF_WIDTH_PX).roundToInt(),
                        y = (geometry.volumeBottom + DATE_LABEL_GAP_PX).roundToInt(),
                    )
                }
                .width(DATE_LABEL_WIDTH),
        )
    }
}

/**
 * The crosshair's OHLC readout, above the plot.
 *
 * Shows the date and all four prices plus volume, because someone dragging the crosshair is
 * reading the bar, not the pattern. Delivery % is appended only when the bar carries it — the
 * legacy NSE file is published for some sessions and not others (plan.md §3.5), and a `0.0%` for a
 * missing bar would be a fabrication.
 */
@Composable
private fun CrosshairReadout(
    candle: Candle?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = MiaSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (candle == null) {
            Text(
                text = UiStrings.NO_BAR_SELECTED,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        } else {
            Text(
                text = readoutText(candle),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
            )
        }
    }
}

/**
 * The readout sentence, as a pure function so its shape is reviewable without a device.
 *
 * Written on one line because a two-line OHLC readout at `labelSmall` on a 360 dp-wide phone eats
 * the plot; the pattern overlay labels are drawn below the plot instead.
 */
internal fun readoutText(candle: Candle): String = buildString {
    append(formatDate(candle.date))
    append("  ")
    append(UiStrings.SHORT_OPEN)
    append(' ')
    append(formatPrice(candle.open))
    append("  ")
    append(UiStrings.SHORT_HIGH)
    append(' ')
    append(formatPrice(candle.high))
    append("  ")
    append(UiStrings.SHORT_LOW)
    append(' ')
    append(formatPrice(candle.low))
    append("  ")
    append(UiStrings.SHORT_CLOSE)
    append(' ')
    append(formatPrice(candle.close))
    append("  ")
    append(UiStrings.SHORT_VOLUME)
    append(' ')
    append(formatVolume(candle.volume))
    candle.deliveryPercent?.let {
        append("  ")
        append(UiStrings.SHORT_DELIVERY)
        append(' ')
        append(formatPercent(it, 1))
    }
}

/**
 * A one-sentence description of the chart for a screen reader.
 *
 * A `Canvas` is one opaque node to TalkBack, so without this the chart is simply absent from the
 * accessibility tree — which would hide the most informative element on the screen.
 */
private fun chartDescription(state: ChartState): String {
    val last = state.candles.lastOrNull()
    val close = if (last == null) UiStrings.NOT_AVAILABLE else formatPrice(last.close)
    return "Price chart, ${state.candles.size} ${state.timeframe.shortLabel()} bars, last close " +
        "$close. Dashed levels, if present, are ${MatchScoreLabels.PROJECTION_LABEL}."
}

// ---------------------------------------------------------------------------- shared constants

/** Dash patterns are file-scope constants so they are built once per process, not per frame. */
private val MEASURED_MOVE_EFFECT: PathEffect = PathEffect.dashPathEffect(ChartDefaults.MEASURED_MOVE_DASH)
private val PROJECTED_PATH_EFFECT: PathEffect = PathEffect.dashPathEffect(ChartDefaults.PROJECTED_PATH_DASH)
private val TRIGGER_EFFECT: PathEffect = PathEffect.dashPathEffect(ChartDefaults.TRIGGER_DASH)

private const val GRID_WIDTH_PX = 1f
private const val SERIES_WIDTH_PX = 2f
private const val PIVOT_MARKER_PX = 5f
private const val AREA_ALPHA = 0.12f

/** Gap between the plot's right edge and the start of a price label, in px. */
private const val AXIS_LABEL_GAP_PX = 3f

/** Half a `labelSmall` line box in px, so a label's baseline sits on its grid line. */
private const val LABEL_HALF_HEIGHT_PX = 8f

/** Gap between the volume pane's baseline and the date labels, in px. */
private const val DATE_LABEL_GAP_PX = 2f

/** Fixed width of a date label, in px. Generous enough for `Q4 26` and `Oct 26` at `labelSmall`. */
private const val DATE_LABEL_WIDTH_PX = 80f

private val DATE_LABEL_WIDTH: Dp = DATE_LABEL_WIDTH_PX.dp
private val DATE_LABEL_HALF_WIDTH_PX = DATE_LABEL_WIDTH_PX / 2f
