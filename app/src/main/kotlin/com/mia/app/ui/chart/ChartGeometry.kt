package com.mia.app.ui.chart

import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Density
import com.mia.app.domain.model.Timeframe
import com.mia.app.ui.common.formatFixed
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The chart's pixel geometry, computed **once** outside the draw pass.
 *
 * ### The performance contract (plan.md §12, 2 GB-RAM target)
 * [PriceChart]'s `Canvas` runs `draw` once per frame and, during a crosshair drag, once per
 * touch event. Every number that does not change between two frames lives here:
 *
 * * five parallel [FloatArray]s of per-bar screen coordinates,
 * * two pre-built [Path]s for the line/area style,
 * * pre-formatted axis labels as `String`s,
 * * the price and volume scales.
 *
 * The draw pass therefore contains **no allocation, no arithmetic and no string formatting** — only
 * `drawLine` / `drawRect` / `drawCircle` / `drawPath` calls with pre-computed arguments. A 400-bar
 * chart is 400 `drawLine` + 400 `drawRect` calls with nothing allocated, which is well inside a
 * frame budget even on a 2019 budget SoC.
 *
 * ### Why arrays rather than objects per bar
 * A `List<Float>` per field, or a `data class` per bar, would allocate 400 objects × 5 fields on
 * every window change (every pinch frame). Five `FloatArray`s sized once per geometry are a single
 * allocation each, and they are reused by index from the draw loop.
 */
internal class ChartGeometry(
    /** Total bars in the series, not just the visible window. */
    val totalBars: Int,
    val window: ChartWindow,
    /** How many bars are actually laid out (≤ [ChartDefaults.MAX_DRAWN_BARS]). */
    val drawnCount: Int,
    val barWidth: Float,
    val plotLeft: Float,
    val plotTop: Float,
    val plotRight: Float,
    val plotBottom: Float,
    val volumeTop: Float,
    val volumeBottom: Float,
    val priceMin: Float,
    val priceMax: Float,
    val volumeMax: Float,
    /** Centre x of visible bar `i`, for `i` in `0 until drawnCount`. */
    val barX: FloatArray,
    val openY: FloatArray,
    val highY: FloatArray,
    val lowY: FloatArray,
    val closeY: FloatArray,
    /** Top y of volume bar `i`. The bar is drawn from here down to [volumeBottom]. */
    val volumeTopY: FloatArray,
    /** True where visible bar `i` closed at or above its open. Drives the body fill. */
    val bullish: BooleanArray,
    /** Pre-built close-price polyline. */
    val closePath: Path,
    /** Pre-built close-price polyline closed against the pane floor. */
    val areaPath: Path,
    /**
     * Two reusable triangle markers, one per pivot kind.
     *
     * They exist so the draw pass can `rewind()` and refill them instead of allocating a `Path` per
     * pivot per frame — the third rule in [PriceChart]'s KDoc. `Path.rewind()` keeps the underlying
     * native buffer, so after the first frame these cost no allocation at all.
     */
    val pivotHighPath: Path,
    val pivotLowPath: Path,
    /** Price values on the y axis, ascending. */
    val priceTicks: FloatArray,
    /** Formatted labels matching [priceTicks]. */
    val priceTickLabels: List<String>,
    /** Absolute bar indices on the x axis. */
    val dateTicks: IntArray,
    /** Formatted labels matching [dateTicks]. */
    val dateTickLabels: List<String>,
    /** Horizontal rules, pre-computed y positions for [priceTicks]. */
    val gridYs: FloatArray,
) {
    /** Usable plot width in px. */
    val plotWidth: Float get() = plotRight - plotLeft

    /** Usable price pane height in px. */
    val priceHeight: Float get() = plotBottom - plotTop

    private val priceSpan: Float get() = (priceMax - priceMin).takeIf { it > 0f } ?: 1f

    /** Price → y. Linear; clamps are the caller's job so off-pane overlays can be detected. */
    fun priceToY(price: Float): Float =
        plotBottom - ((price - priceMin) / priceSpan) * priceHeight

    /** Absolute bar index → centre x. No clamping: callers check `window` first. */
    fun barToX(absoluteIndex: Int): Float =
        plotLeft + (absoluteIndex - window.startBar + 0.5f) * barWidth

    /**
     * Refills one of [pivotHighPath] / [pivotLowPath] with a triangle at [x].
     *
     * `rewind()` rather than a fresh `Path()`: the native buffer is retained, so a 400-bar chart
     * with a dozen pivots draws its markers without allocating anything.
     *
     * @param downward `true` for a swing high (apex above the base), `false` for a swing low.
     */
    fun setPivotMarker(path: Path, x: Float, y: Float, halfWidthPx: Float, downward: Boolean) {
        path.rewind()
        val apexY = if (downward) y - halfWidthPx * 2f else y + halfWidthPx * 2f
        val baseY = if (downward) y + halfWidthPx * 2f else y - halfWidthPx * 2f
        path.moveTo(x, apexY)
        path.lineTo(x - halfWidthPx, baseY)
        path.lineTo(x + halfWidthPx, baseY)
        path.close()
    }

    /**
     * Touch x → absolute bar index, or `null` when the touch is outside the plot.
     *
     * Rounds to the **nearest** bar rather than flooring, because on a 400-bar chart a bar slot is
     * under 1 px wide and flooring would make the readout stick to the previous bar for half the
     * time.
     */
    fun xToBar(x: Float): Int? {
        if (x < plotLeft || x > plotRight) return null
        val relative = ((x - plotLeft) / barWidth - 0.5f).roundToInt()
        return (window.startBar + relative).coerceIn(window.startBar, window.endBar)
    }

    companion object {

        /** Month abbreviations. `Locale.ROOT` formatting is avoided because the chart draws ~8 labels. */
        private val MONTHS = listOf(
            "Jan", "Feb", "Mar", "Apr", "May", "Jun",
            "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
        )

        /**
         * Builds the geometry for [state] at [widthPx] × [heightPx].
         *
         * @param density used only to convert the `Dp` layout constants to px, once.
         * @return `null` when the state has nothing to draw (empty series, or a zero-sized canvas).
         */
        fun build(
            state: ChartState,
            widthPx: Float,
            heightPx: Float,
            density: Density,
        ): ChartGeometry? {
            val total = state.candles.size
            if (total <= 0) return null
            if (widthPx <= 0f || heightPx <= 0f) return null

            val gutterPx = with(ChartDefaults.AXIS_GUTTER) { density.toPx() }
            val dateStripPx = with(ChartDefaults.DATE_STRIP_HEIGHT) { density.toPx() }
            val readoutPx = with(ChartDefaults.READOUT_HEIGHT) { density.toPx() }
            val paneGapPx = with(ChartDefaults.PANE_GAP) { density.toPx() }

            val plotLeft = 0f
            val plotRight = max(gutterPx, widthPx - gutterPx)
            val plotWidth = plotRight - plotLeft
            if (plotWidth <= 1f) return null

            val topOfPanes = readoutPx
            val bottomOfPanes = max(topOfPanes + 8f, heightPx - dateStripPx)
            val panesHeight = bottomOfPanes - topOfPanes
            if (panesHeight <= 8f) return null

            val volumeHeight = panesHeight * ChartDefaults.VOLUME_PANE_FRACTION
            val priceHeight = panesHeight - volumeHeight - paneGapPx
            if (priceHeight <= 8f) return null

            val plotTop = topOfPanes
            val plotBottom = topOfPanes + priceHeight
            val volumeTop = plotBottom + paneGapPx
            val volumeBottom = bottomOfPanes

            // ---- window -----------------------------------------------------------------
            val window = clampWindow(state.window, total, plotWidth)
            val drawnCount = min(window.barCount, ChartDefaults.MAX_DRAWN_BARS)
            val barWidth = plotWidth / window.barCount

            // ---- scales ------------------------------------------------------------------
            var low = Float.MAX_VALUE
            var high = -Float.MAX_VALUE
            var volumeHigh = 0L
            for (i in 0 until drawnCount) {
                val candle = state.candles[window.startBar + i]
                if (!candle.isValid) continue
                if (candle.low < low) low = candle.low.toFloat()
                if (candle.high > high) high = candle.high.toFloat()
                if (candle.volume > volumeHigh) volumeHigh = candle.volume
            }
            if (low > high) {
                // Every visible bar is structurally invalid (a suspended or mis-parsed session).
                // Fall back to the closes so the chart shows *something* rather than dividing by
                // zero; the caller has already gated on `hasEnoughHistory`.
                for (i in 0 until drawnCount) {
                    val close = state.candles[window.startBar + i].close.toFloat()
                    if (close.isFinite()) {
                        if (close < low) low = close
                        if (close > high) high = close
                    }
                }
            }
            if (low > high || !low.isFinite() || !high.isFinite()) return null

            val padded = addOverlays(state, low, high)
            val priceMin = padded.first
            val priceMax = padded.second
            val priceSpan = (priceMax - priceMin).takeIf { it > 0f } ?: 1f
            val priceHeightFinal = plotBottom - plotTop
            val volumeMax = volumeHigh.toFloat().takeIf { it > 0f } ?: 1f
            val volumeHeightFinal = volumeBottom - volumeTop

            fun toY(price: Float): Float =
                plotBottom - ((price - priceMin) / priceSpan) * priceHeightFinal

            fun volumeToY(volume: Float): Float =
                volumeBottom - (volume / volumeMax) * volumeHeightFinal

            // ---- per-bar coordinates ------------------------------------------------------
            val barX = FloatArray(drawnCount)
            val openY = FloatArray(drawnCount)
            val highY = FloatArray(drawnCount)
            val lowY = FloatArray(drawnCount)
            val closeY = FloatArray(drawnCount)
            val volumeTopY = FloatArray(drawnCount)
            val bullish = BooleanArray(drawnCount)

            val closePath = Path()
            val areaPath = Path()

            for (i in 0 until drawnCount) {
                val candle = state.candles[window.startBar + i]
                val x = plotLeft + (i + 0.5f) * barWidth
                barX[i] = x
                val candleValid = candle.isValid
                val o = if (candleValid) candle.open.toFloat() else candle.close.toFloat()
                val h = if (candleValid) candle.high.toFloat() else candle.close.toFloat()
                val l = if (candleValid) candle.low.toFloat() else candle.close.toFloat()
                val c = candle.close.toFloat()
                openY[i] = toY(o)
                highY[i] = toY(h)
                lowY[i] = toY(l)
                closeY[i] = toY(c)
                volumeTopY[i] = volumeToY(candle.volume.toFloat())
                bullish[i] = candle.isBullish

                if (i == 0) {
                    closePath.moveTo(x, closeY[0])
                    areaPath.moveTo(x, plotBottom)
                    areaPath.lineTo(x, closeY[0])
                } else {
                    closePath.lineTo(x, closeY[i])
                    areaPath.lineTo(x, closeY[i])
                }
            }
            if (drawnCount > 0) {
                val lastX = barX[drawnCount - 1]
                areaPath.lineTo(lastX, plotBottom)
                areaPath.close()
            }

            // ---- axes ---------------------------------------------------------------------
            val priceTicks = niceTicks(priceMin, priceMax, targetCount = 5)
            val priceTickLabels = priceTicks.map { formatFixed(it.toDouble(), 2) }
            val gridYs = FloatArray(priceTicks.size) { toY(priceTicks[it]) }

            val tickIndices = dateTickIndices(state, window, drawnCount, barWidth)
            val dateTicks = IntArray(tickIndices.size)
            val dateTickLabels = ArrayList<String>(tickIndices.size)
            for (i in tickIndices.indices) {
                val absolute = tickIndices[i]
                dateTicks[i] = absolute
                dateTickLabels += axisLabel(state.candles[absolute].date, state.timeframe)
            }

            return ChartGeometry(
                totalBars = total,
                window = window,
                drawnCount = drawnCount,
                barWidth = barWidth,
                plotLeft = plotLeft,
                plotTop = plotTop,
                plotRight = plotRight,
                plotBottom = plotBottom,
                volumeTop = volumeTop,
                volumeBottom = volumeBottom,
                priceMin = priceMin,
                priceMax = priceMax,
                volumeMax = volumeMax,
                barX = barX,
                openY = openY,
                highY = highY,
                lowY = lowY,
                closeY = closeY,
                volumeTopY = volumeTopY,
                bullish = bullish,
                closePath = closePath,
                areaPath = areaPath,
                pivotHighPath = Path(),
                pivotLowPath = Path(),
                priceTicks = priceTicks,
                priceTickLabels = priceTickLabels,
                dateTicks = dateTicks,
                dateTickLabels = dateTickLabels,
                gridYs = gridYs,
            )
        }

        /**
         * Expands the price range to include the overlay levels, but **only within a cap**.
         *
         * A projected level far outside the visible range would squash the candles into a
         * hairline, so the total range is limited to 2× the price-only range. Beyond that the
         * overlay is clipped out of the pane (the draw pass clips) and its exact value is still
         * shown in the projection panel below the chart — so nothing is ever silently lost, and the
         * candles are never made unreadable to fit a number on the axis.
         */
        private fun addOverlays(
            state: ChartState,
            low: Float,
            high: Float,
        ): Pair<Float, Float> {
            val candidates = ArrayList<Float>(4)
            state.measuredMove?.let { move ->
                candidates += move.projectedLevel.toFloat()
                candidates += move.triggerLevel.toFloat()
            }
            state.breakoutTrigger?.let { trigger -> candidates += trigger.level.toFloat() }
            state.projectedPath?.points?.forEach { candidates += it.second.toFloat() }
            if (candidates.isEmpty()) return low to high

            val priceSpan = max(high - low, 1e-6f)
            val capHigh = high + priceSpan
            val capLow = low - priceSpan

            var outLow = low
            var outHigh = high
            for (candidate in candidates) {
                if (!candidate.isFinite()) continue
                if (candidate > outHigh) outHigh = min(candidate, capHigh)
                if (candidate < outLow) outLow = max(candidate, capLow)
            }
            if (outHigh <= outLow) return low to high

            val padding = (outHigh - outLow) * ChartDefaults.PRICE_PADDING_FRACTION
            return (outLow - padding) to (outHigh + padding)
        }

        /**
         * Clamps a requested window into something drawable.
         *
         * Three things can be wrong with a window: it can be wider than the series (the series was
         * re-fetched and got shorter), wider than the cap, or narrower than the zoom floor. All
         * three are corrected here rather than at every call site, and the result always has at
         * least one bar so `barWidth` can never be infinite.
         */
        fun clampWindow(window: ChartWindow, totalBars: Int, plotWidth: Float): ChartWindow {
            // A slot narrower than ~0.6 px cannot be told from its neighbour, so stop zooming there…
            val slotFloor = max(ChartDefaults.MIN_VISIBLE_BARS, (plotWidth / 0.6f).roundToInt())
            // …but never show more bars than the draw pass is allowed to lay out, or the window and
            // the arrays would disagree and the right-hand side of the chart would be blank.
            val maxCount = min(totalBars, min(max(slotFloor, ChartDefaults.MIN_VISIBLE_BARS), ChartDefaults.MAX_DRAWN_BARS))
            val count = window.barCount.coerceIn(
                min(ChartDefaults.MIN_VISIBLE_BARS, max(totalBars, 1)),
                max(maxCount, 1),
            )
            val start = window.startBar.coerceIn(0, max(totalBars - count, 0))
            return ChartWindow(startBar = start, barCount = count)
        }

        /**
         * Zoom about a fixed anchor bar (plan.md: "pinch-zoom over a bounded bar window").
         *
         * @param anchorX touch centroid x in px; the bar under it stays under it.
         * @param zoom the gesture's zoom factor; `> 1` means fewer, wider bars.
         */
        fun zoomWindow(
            geometry: ChartGeometry,
            current: ChartWindow,
            anchorX: Float,
            zoom: Float,
        ): ChartWindow {
            if (!zoom.isFinite() || zoom <= 0f) return current
            val anchorBar = geometry.xToBar(anchorX)?.toFloat() ?: return current
            val anchorInWindow = anchorBar - current.startBar
            val factor = 1f / zoom
            val newCount = (current.barCount * factor).roundToInt()
            val newStart = (anchorBar - anchorInWindow * factor).roundToInt()
            return clampWindow(
                ChartWindow(startBar = newStart, barCount = newCount),
                geometry.totalBars,
                geometry.plotWidth,
            )
        }

        /**
         * Horizontal pan by a pixel delta.
         *
         * Positive `panX` is a finger moving right, which reveals older bars, so the window start
         * moves left.
         */
        fun panWindow(
            geometry: ChartGeometry,
            current: ChartWindow,
            panX: Float,
        ): ChartWindow {
            if (panX == 0f || geometry.barWidth <= 0f) return current
            val barsMoved = (-panX / geometry.barWidth).roundToInt()
            if (barsMoved == 0) return current
            return clampWindow(
                ChartWindow(
                    startBar = current.startBar + barsMoved,
                    barCount = current.barCount,
                ),
                geometry.totalBars,
                geometry.plotWidth,
            )
        }

        /**
         * "Nice" axis ticks on 1/2/5 × 10ⁿ steps.
         *
         * A hand-rolled version of what a charting library would do, because the alternative —
         * pulling in a chart library for five grid lines — is exactly the dependency the plan's
         * decision OQ-1 closed in favour of the custom canvas.
         */
        fun niceTicks(min: Float, max: Float, targetCount: Int = 5): FloatArray {
            val span = max - min
            if (!span.isFinite() || span <= 0f) return floatArrayOf(min)
            val rough = span / max(targetCount, 2)
            val magnitude = Math.pow(10.0, Math.floor(Math.log10(rough.toDouble()))).toFloat()
            val normalised = rough / magnitude
            val stepMultiple = when {
                normalised <= 1f -> 1f
                normalised <= 2f -> 2f
                normalised <= 5f -> 5f
                else -> 10f
            }
            val step = stepMultiple * magnitude
            val first = kotlin.math.ceil(min / step) * step
            val out = ArrayList<Float>(targetCount + 2)
            var value = first
            while (value <= max + step * 0.001f && out.size < targetCount + 3) {
                out += value
                value += step
            }
            return if (out.isEmpty()) floatArrayOf(min, max) else FloatArray(out.size) { out[it] }
        }

        /**
         * Which visible bars get a date label.
         *
         * Monthly on D1, quarterly on W1 (plan.md), then thinned so labels never overlap: the
         * previous label's x position is tracked and a candidate closer than
         * [ChartDefaults.MIN_TICK_GAP] is dropped. The **first** tick is always kept so the reader
         * can orient, and the **last** is kept when it does not collide.
         */
        private fun dateTickIndices(
            state: ChartState,
            window: ChartWindow,
            drawnCount: Int,
            barWidth: Float,
        ): List<Int> {
            if (drawnCount <= 0) return emptyList()
            val stepMonths = if (state.timeframe == Timeframe.D1) 1 else 3
            // The chart is not density-aware here; a fixed px budget is enough because this rule
            // only decides *which* labels to drop.
            val minGapPx = ChartDefaults.MIN_TICK_GAP.value * MIN_TICK_GAP_DP_PX
            val minGapBars = max(1, (minGapPx / max(barWidth, 0.5f)).roundToInt())

            val out = ArrayList<Int>(8)
            var lastEmitted = Int.MIN_VALUE / 2
            var previousMonthKey = Int.MIN_VALUE
            for (i in 0 until drawnCount) {
                val absolute = window.startBar + i
                val date = state.candles[absolute].date
                val monthKey = (date.year * 100) + monthIndexOf(date.monthValue) / stepMonths
                if (monthKey == previousMonthKey) continue
                previousMonthKey = monthKey
                if (out.isNotEmpty() && absolute - lastEmitted < minGapBars) continue
                out += absolute
                lastEmitted = absolute
            }
            return out
        }

        /** Month index → 0-based, for the quarter bucketing in [dateTickIndices]. */
        private fun monthIndexOf(month: Int): Int = month - 1

        /**
         * `Oct 26` on D1, `Q4 26` on W1.
         *
         * Quarterly weekly bars get the quarter because "Oct 26" on a five-year weekly chart is
         * three labels for one year and the reader cannot tell the years apart.
         */
        private fun axisLabel(date: java.time.LocalDate, timeframe: Timeframe): String {
            val month = MONTHS[date.monthValue - 1]
            return if (timeframe == Timeframe.D1) {
                "$month ${date.year % 100}"
            } else {
                val quarter = ((date.monthValue - 1) / 3) + 1
                "Q$quarter ${date.year % 100}"
            }
        }

        /**
         * Converts [ChartDefaults.MIN_TICK_GAP] from Dp to a rough px figure without a `Density`.
         *
         * A real density is available at the call site, but this rule only decides *which* labels
         * to drop; being 10% off changes nothing a reader can see, and threading a density through
         * would make the helper harder to read for no benefit.
         */
        private const val MIN_TICK_GAP_DP_PX = 2.75f
    }
}
