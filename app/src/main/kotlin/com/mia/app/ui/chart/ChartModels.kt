package com.mia.app.ui.chart

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mia.app.domain.model.BreakoutTrigger
import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.MeasuredMove
import com.mia.app.domain.model.MatchScoreLabels
import com.mia.app.domain.model.MatchScorePolicy
import com.mia.app.domain.model.Pivot
import com.mia.app.domain.model.ProjectedPath
import com.mia.app.domain.model.Timeframe

/**
 * Everything the custom Canvas chart draws, and nothing else.
 *
 * ### Why the chart takes a value object and not five parameters
 * `PriceChart` is the single most expensive composable in the app (plan.md §12's 2 GB-RAM target).
 * Passing one immutable [ChartState] means the whole geometry pass can be keyed on it in one
 * `remember`, so a crosshair move cannot invalidate half the tree, and a screen that swaps the
 * timeframe swaps one object instead of coordinating six arguments.
 *
 * ### The honesty contract in this file
 * * The measured-move level is [ChartState.measuredMove] and is labelled
 *   [MatchScoreLabels.PROJECTION_LABEL] wherever it is drawn. There is no field for a "target".
 * * The projected path is [ChartState.projectedPath] and is drawn dashed, so it can never be
 *   confused with recorded price (plan.md §6.6 item 2).
 * * The breakout trigger is [ChartState.breakoutTrigger], a third, separately-styled object
 *   (plan.md §6.6 item 3, decision D2: never conflated with either of the others).
 * * [ChartState.patternOverlay] is a fourth, for the Explorer's idealised pattern shape. It is
 *   dashed, labelled "idealised", and carries [PatternOverlay.isAnchoredToDetectedGeometry] so a
 *   shape drawn at the detector's own pivots is never mistaken for one merely scaled to the window.
 *   It is excluded from the price auto-range so candles cannot rescale when a pattern is selected.
 * * [ChartState.minimumBars] gates the whole series. Below it the chart says
 *   "not enough history" rather than drawing a short line that looks like a decline.
 */

/** How the price series is drawn. */
enum class ChartStyle {
    /** Open/high/low/close bars — daily, where each session is a real event. */
    CANDLESTICK,

    /** A close-price line with a translucent area under it — weekly, where individual bars are noise. */
    LINE_AREA,
}

/**
 * An idealised chart pattern drawn over the price series, already mapped into the chart's own
 * coordinate space.
 *
 * ### Why this carries prices and not a normalised shape
 * `PatternShape` in `:domain` is authored in `0..1` space so one shape serves every symbol and
 * window. Mapping it to concrete prices is a **drawing** decision, so it happens here rather than in
 * the domain — the domain has no chart, no window and no pixel scale, and giving it one would put
 * layout knowledge into the module that is deliberately free of it.
 *
 * ### The honesty contract
 * Every point here is a textbook shape, not measured data. It is drawn dashed and labelled
 * "idealised" wherever it appears, and it is **excluded from the price auto-range** (see
 * `ChartGeometry.addOverlays`, which deliberately ignores this field) so that turning a pattern on
 * or off cannot rescale the candles and make the chart appear to move.
 */
data class PatternOverlay(
    /** Polyline through the pattern's role points, in chart price units. */
    val points: List<Pair<Float, Float>>,
    /** Additional polylines for multi-line shapes (boundaries, composites), in chart price units. */
    val lines: List<List<Pair<Float, Float>>> = emptyList(),
    /** Idealised candle bars, in chart price units. At most one of [points] and [bars] is non-empty. */
    val bars: List<PatternOverlayBar> = emptyList(),
    /** Per-point labels, oldest first. Empty when the shape carries no role names. */
    val roleLabels: List<String> = emptyList(),
    /**
     * `true` when the shape is closed — a formation whose pattern is the enclosed region, rather
     * than a continuation whose boundary lines are the pattern.
     */
    val closed: Boolean = false,
    /**
     * Whether the overlay sits where a detector actually found this pattern, or at the window's
     * scale as an illustration.
     *
     * Drawn identically but reported differently, because the two are different claims: one says
     * "here is the formation the app measured", the other "here is what the shape looks like".
     */
    val isAnchoredToDetectedGeometry: Boolean = false,
) {
    val isEmpty: Boolean get() = points.isEmpty() && lines.isEmpty() && bars.isEmpty()
}

/**
 * One idealised candle of a [PatternOverlay], in chart price units.
 *
 * @property barIndex absolute bar index the candle sits at, so the draw pass places it on the same
 *   slot a real candle of the series would occupy rather than at a proportional x.
 */
data class PatternOverlayBar(
    val barIndex: Int,
    val bodyHigh: Float,
    val bodyLow: Float,
    val wickHigh: Float,
    val wickLow: Float,
    val isBullish: Boolean,
)

/**
 * The visible slice of the series, in **absolute bar indices** (not screen offsets).
 *
 * Index arithmetic rather than a pixel offset so that a rotation, a font-scale change or a
 * different chart height can recompute the pixels without losing the reader's place in the series.
 */
data class ChartWindow(
    /** First visible absolute bar index. */
    val startBar: Int,
    /** How many bars are visible. */
    val barCount: Int,
) {
    /** Last visible absolute bar index, inclusive. */
    val endBar: Int get() = startBar + barCount - 1

    companion object {
        /**
         * The whole series.
         *
         * `count` is floored at [ChartDefaults.MIN_VISIBLE_BARS] so a series shorter than one
         * pinch-zoom stop still has a window to clamp against; the *insufficient data* decision is
         * made by [ChartState.hasEnoughHistory], not here.
         */
        fun whole(totalBars: Int): ChartWindow = ChartWindow(
            startBar = 0,
            barCount = totalBars.coerceAtLeast(1),
        )
    }
}

/**
 * The complete input to [PriceChart].
 *
 * @param minimumBars the plan.md §6.4 hard gate for this timeframe. Defaults from
 *   [MatchScorePolicy.minBars] so a caller cannot accidentally lower it and start drawing a series
 *   the engine has already refused to score.
 * @param window which slice to show. Defaults to the whole series.
 * @param style defaults to candlesticks on D1 and a line on W1 — weekly bars carry one week's
 *   information and five overlapping OHLC ranges render as noise on a phone.
 */
data class ChartState(
    val symbol: String,
    val timeframe: Timeframe,
    val candles: List<Candle>,
    val pivots: List<Pivot> = emptyList(),
    val measuredMove: MeasuredMove? = null,
    val projectedPath: ProjectedPath? = null,
    val breakoutTrigger: BreakoutTrigger? = null,
    /**
     * An idealised pattern shape to draw over the series, or `null` for none.
     *
     * A fourth overlay, styled separately from [measuredMove], [projectedPath] and
     * [breakoutTrigger] — decision D2's rule applies here too: an idealised teaching shape must
     * never be confusable with anything the app measured.
     *
     * Deliberately **excluded from the price auto-range**: [ChartGeometry.addOverlays] does not
     * read this field, so selecting a pattern cannot rescale the candles and make the chart appear
     * to move under the reader.
     */
    val patternOverlay: PatternOverlay? = null,
    val minimumBars: Int = MatchScorePolicy.minBars(timeframe),
    val window: ChartWindow = ChartWindow.whole(candles.size),
    val style: ChartStyle = defaultStyleFor(timeframe),
) {
    /** The plan.md §6.4 gate. Below it the chart must not draw a series at all. */
    val hasEnoughHistory: Boolean
        get() = MatchScorePolicy.isSufficientBars(timeframe, candles.size)

    /** Bars short of the gate. Zero when there is enough. */
    val barsShortOfGate: Int
        get() = (minimumBars - candles.size).coerceAtLeast(0)

    /** Bars still needed, for the "not enough history" copy. */
    val requiredBars: Int get() = minimumBars

    /** The candle at the crosshair, or null when no bar is selected. */
    fun candleAt(absoluteIndex: Int): Candle? = candles.getOrNull(absoluteIndex)

    companion object {
        /** The default drawing style for a timeframe. See [ChartState.style]. */
        fun defaultStyleFor(timeframe: Timeframe): ChartStyle = when (timeframe) {
            Timeframe.D1 -> ChartStyle.CANDLESTICK
            Timeframe.W1 -> ChartStyle.LINE_AREA
        }
    }
}

/**
 * Fixed layout constants for [PriceChart].
 *
 * All in `Dp` because every one of them is a legibility decision, not a maths one: a 44 dp axis
 * gutter fits `1,16,770.00` at `labelSmall` on a 5-inch screen, and a 20 dp date strip fits
 * `Oct 26` in every locale the app ships to.
 */
object ChartDefaults {
    /** Room for the price axis labels and their rule. */
    val AXIS_GUTTER: Dp = 44.dp

    /** Room for one row of date labels under the volume pane. */
    val DATE_STRIP_HEIGHT: Dp = 20.dp

    /** Room for the crosshair's OHLC readout above the plot. */
    val READOUT_HEIGHT: Dp = 40.dp

    /** Fraction of the plot height given to the volume histogram. */
    const val VOLUME_PANE_FRACTION = 0.22f

    /** Gap between the price pane and the volume pane. */
    val PANE_GAP: Dp = 6.dp

    /** Fraction of the visible high-low range added above and below, so a bar never touches an edge. */
    const val PRICE_PADDING_FRACTION = 0.06f

    /** Candle body width as a fraction of the bar slot. */
    const val BODY_WIDTH_FRACTION = 0.62f

    /**
     * Fewest visible bars an idealised pattern shape can be placed over.
     *
     * Below this a two- or three-point shape would span a handful of pixels and read as a
     * smudge rather than a pattern, so [PatternOverlayMapper] declines rather than drawing
     * something the reader cannot see or interpret.
     */
    const val MIN_OVERLAY_BARS = 12

    /** Narrowest candle body, in px, so a doji is still visible when 400 bars are on screen. */
    const val MIN_BODY_PX = 1.5f

    /** Fewest bars a pinch can zoom into. Below this a candle is a smear. */
    const val MIN_VISIBLE_BARS = 12

    /** Hard cap on bars handed to the draw pass. plan.md §12: the chart must not allocate per bar. */
    const val MAX_DRAWN_BARS = 400

    /** Minimum horizontal gap between two date labels, in px, before one is dropped. */
    val MIN_TICK_GAP: Dp = 56.dp

    /** Dash pattern for the measured-move level: long dash, gap. */
    val MEASURED_MOVE_DASH = floatArrayOf(14f, 8f)

    /**
     * Dash pattern for the projected path: short dash, gap. Deliberately *finer* than the measured
     * move's, so the two projections are distinguishable at a glance and by a reader who cannot
     * tell the two hues apart.
     */
    val PROJECTED_PATH_DASH = floatArrayOf(4f, 6f)

    /** Dash pattern for the breakout trigger: dot, gap. A third, finer pattern again. */
    val TRIGGER_DASH = floatArrayOf(2f, 6f)
}
