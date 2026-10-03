package com.mia.app.ui.chart

import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.Pivot
import com.mia.app.domain.pattern.PatternShape

/**
 * Maps an idealised [PatternShape] onto a window of real candles, in chart price units.
 *
 * ### The two modes, and why they are not interchangeable
 * **Detected** anchors the shape at the pivots a detector actually found. Every point is a real
 * swing point in the data, so the overlay shows where the app's geometry was, and a reader who
 * disagrees with a match can see precisely which pivot drove the score.
 *
 * **Window** scales the shape to the visible window's own price range and time span. That is a
 * **teaching illustration** — "here is what the shape looks like, beside this price action" — and
 * nothing more. It is not evidence, which is why it carries
 * [PatternOverlay.isAnchoredToDetectedGeometry] `false` and must be labelled "idealised".
 *
 * Drawing both identically would invite the reader to take the window mode for a detection, which
 * is the single most misleading thing this feature could do.
 */
object PatternOverlayMapper {

    /**
     * Window-anchored: the shape scaled to [window]'s price range and bar span.
     *
     * @param candles the full series, ascending by date.
     * @param window the visible slice, in absolute bar indices.
     * @return `null` when the window is too short to place a shape, or the series has no price
     *   range to scale into — a flat series would otherwise divide by zero.
     */
    fun windowAnchored(
        shape: PatternShape,
        candles: List<Candle>,
        window: ChartWindow,
    ): PatternOverlay? {
        if (shape == PatternShape.UNAVAILABLE) return null
        if (window.barCount < ChartDefaults.MIN_OVERLAY_BARS) return null

        val visible = candles.drop(window.startBar).take(window.barCount)
        if (visible.size < ChartDefaults.MIN_OVERLAY_BARS) return null

        val low = visible.minOf { it.low.toFloat() }
        val high = visible.maxOf { it.high.toFloat() }
        if (!(high > low)) return null

        val first = window.startBar
        val last = window.startBar + visible.size - 1
        val span = (last - first).coerceAtLeast(1)

        fun price(normalised: Float): Float = low + normalised * (high - low)
        fun index(normalised: Float): Int = (first + normalised * span).toInt().coerceIn(first, last)

        return when (shape) {
            is PatternShape.Polyline -> PatternOverlay(
                points = shape.points.map { index(it.x).toFloat() to price(it.y) },
                roleLabels = shape.roleLabels,
                closed = shape.closed,
                isAnchoredToDetectedGeometry = false,
            )

            is PatternShape.CandleLayout -> {
                // Bars are laid out at even spacing across the window rather than using the
                // normalised x, because a two-bar layout has one `x` of 0 and one of 1 — which
                // would place the first candle on the window's left edge and the second on its
                // right, with nothing between them. `shape.points` is empty for this case, so the
                // bar indices are the only x the draw pass needs.
                val count = shape.bars.size
                val positions = if (count == 1) {
                    listOf((first + last) / 2)
                } else {
                    List(count) { i -> index(i.toFloat() / (count - 1)) }
                }
                PatternOverlay(
                    points = emptyList(),
                    bars = shape.bars.mapIndexed { i, bar ->
                        PatternOverlayBar(
                            barIndex = positions[i],
                            bodyHigh = price(bar.bodyHigh),
                            bodyLow = price(bar.bodyLow),
                            wickHigh = price(bar.wickHigh),
                            wickLow = price(bar.wickLow),
                            isBullish = bar.isBullish,
                        )
                    },
                    isAnchoredToDetectedGeometry = false,
                )
            }

            PatternShape.UNAVAILABLE -> null
        }
    }

    /**
     * Detected: the shape anchored at the pivots a detector found.
     *
     * Refuses rather than approximates. If the pivot count does not match the shape's point count
     * there is no correct mapping — stretching or collapsing points would produce a shape that
     * *looks* anchored to real pivots while pointing at the wrong ones, which is worse than
     * drawing no shape at all.
     *
     * @return `null` when the counts differ, when there are too few pivots, or for a candlestick
     *   shape (which is authored in bar-layout space and has no pivot mapping).
     */
    fun detectedAnchored(
        shape: PatternShape,
        pivots: List<Pivot>,
    ): PatternOverlay? {
        if (shape !is PatternShape.Polyline) return null
        if (pivots.size != shape.points.size) return null
        if (pivots.size < 2) return null

        return PatternOverlay(
            points = pivots.map { pivot ->
                pivot.barIndex.toFloat() to pivot.price.toFloat()
            },
            roleLabels = shape.roleLabels,
            closed = shape.closed,
            isAnchoredToDetectedGeometry = true,
        )
    }
}