package com.mia.app.domain.pattern

/**
 * The idealised geometry of a textbook chart pattern, in **normalised `0..1` coordinates**.
 *
 * ### Why normalised, and not prices
 * A pattern is a shape, not a price. `Ascending Triangle` means "a flat ceiling with rising
 * lows", and that statement holds at ₹10 or ₹10,000. So a shape is authored once in a unit square
 * — `x` left→right through the formation, `y` bottom→top in price — and mapped onto whatever
 * window the reader selected.
 *
 * That is what lets one authored shape serve every symbol, timeframe and window, and it is why
 * [NormalisedPoint] is not permitted outside `0..1`: a shape that could escape the unit square
 * could not be mapped without rescaling judgement baked into the data.
 *
 * ### What this is NOT, and the honesty that requires
 * An overlay drawn from one of these is a **teaching aid**, not evidence that the pattern is
 * present. The bars under it are real and unadjusted; the line over them is idealised. A reader who
 * cannot tell those apart would conclude a chart contains a formation it does not, which is worse
 * than showing nothing. Every surface that draws one must therefore carry an "idealised" label —
 * see `Plan.DISCLAIMER` for the sentence.
 */
data class NormalisedPoint(
    val x: Float,
    val y: Float,
) {
    init {
        require(x in 0f..1f) { "x must be normalised to 0..1, was $x" }
        require(y in 0f..1f) { "y must be normalised to 0..1, was $y" }
    }

    companion object {
        val BOTTOM_LEFT = NormalisedPoint(0f, 0f)
        val BOTTOM_RIGHT = NormalisedPoint(1f, 0f)
        val TOP_LEFT = NormalisedPoint(0f, 1f)
        val TOP_RIGHT = NormalisedPoint(1f, 1f)
        val CENTRE = NormalisedPoint(0.5f, 0.5f)
    }
}

/**
 * The idealised shape of one pattern.
 *
 * ### Why this is sealed and not a bare `List<NormalisedPoint>`
 * The families are drawn differently and there is no single representation that suits all of
 * them. Chart patterns are open or closed **polylines** through swing points; candlestick patterns
 * are a small **bar layout** where each candle's open/close/body/wick matter individually. Forcing
 * candles into a polyline loses the body and the wick — the parts that define the pattern — so the
 * renderer would be silently drawing a chart pattern for a candlestick name.
 *
 * [UNAVAILABLE] is the third case, and it is the honest one: a pattern with no authored shape yet
 * must be representable so the catalogue can list it and the screen can say "shape not yet drawn",
 * rather than pretending the pattern does not exist or omitting it silently.
 */
sealed interface PatternShape {

    /**
     * An open or closed polyline through the pattern's role points, oldest first.
     *
     * @property closed whether the last point joins the first. Chart *formations* (double top,
     *   head and shoulders) are closed because the pattern is the enclosed region; continuations
     *   (triangle, flag) are open because only the boundary lines are the pattern.
     * @property roleLabels one label per point, used to annotate the overlay. A `y` value means a
     *   swing high at that `x`; a `y` of `1f` or `0f` with a `roleLabels` entry means a boundary
     *   line rather than a point — see `ChartRole` for the vocabulary.
     */
    data class Polyline(
        val points: List<NormalisedPoint>,
        val closed: Boolean,
        val roleLabels: List<String> = emptyList(),
    ) : PatternShape {
        init {
            require(points.size >= 2) { "a polyline needs at least two points, had ${points.size}" }
        }
    }

    /**
     * Two independent boundary lines, which is what a triangle, rectangle, wedge or flag actually
     * is.
     *
     * Added after the first draft modelled an ascending triangle as a single point list
     * `[(0,.55), (.5,.30), (1,.10), (0,.80), (1,.80)]` — support followed by resistance. That
     * produced **non-monotonic x** (`0, 0.5, 1, 0, 1`), and since the draw pass joins points in
     * list order it would have rendered a zigzag that leaps back to the left edge. A pattern made
     * of two lines needs two lines.
     */
    data class Boundaries(
        val upper: List<NormalisedPoint>,
        val lower: List<NormalisedPoint>,
    ) : PatternShape {
        init {
            require(upper.size >= 2) { "an upper boundary needs at least two points" }
            require(lower.size >= 2) { "a lower boundary needs at least two points" }
            requireAscending(upper, "upper")
            requireAscending(lower, "lower")
        }

        private fun requireAscending(points: List<NormalisedPoint>, which: String) {
            for (i in 1 until points.size) {
                require(points[i].x >= points[i - 1].x) {
                    "the $which boundary must ascend in x: ${points.map { it.x }}"
                }
            }
        }
    }

    /**
     * Several shapes drawn as one figure.
     *
     * Exists for the flags, where the pole is a single stroke and the flag body is a separate
     * channel. Listing them in one point list would have joined the pole's top to the flag's first
     * touch with a line that is not part of the pattern — a shape that looks plausible and is wrong,
     * which is worse than no shape.
     */
    data class Composite(val parts: List<PatternShape>) : PatternShape {
        init {
            require(parts.size >= 2) { "a composite needs at least two parts" }
        }
    }

    /**
     * A candlestick pattern: a fixed sequence of bars, each described independently.
     *
     * @property bars oldest first, each `x` equal to its index over `bars.size`.
     */
    data class CandleLayout(val bars: List<NormalisedCandle>) : PatternShape {
        init {
            require(bars.isNotEmpty()) { "a candle layout needs at least one bar" }
        }
    }

    /**
     * No authored shape yet.
     *
     * Distinct from a shape being *absent from the catalogue row*: the row exists, the glossary
     * teaches it, and only the geometry is missing. Carried as a value rather than a null so no
     * caller has to invent a default shape, which would be the worst outcome — a reader would see
     * a confident overlay built from a guess.
     */
    data object UNAVAILABLE : PatternShape
}

/**
 * One candle of an idealised [PatternShape.CandleLayout].
 *
 * All values are normalised `0..1` in the same price space as [NormalisedPoint.y]. [bodyHigh] and
 * [bodyLow] are the open and close (in whichever order they fall); [wickHigh] and [wickLow] the
 * extremes. The wicks are carried separately because for several patterns — a hammer's long lower
 * wick, a shooting star's long upper one — the wick *is* the signal, and a body-only drawing
 * would erase it.
 */
data class NormalisedCandle(
    val bodyHigh: Float,
    val bodyLow: Float,
    val wickHigh: Float,
    val wickLow: Float,
) {
    init {
        require(bodyHigh in 0f..1f && bodyLow in 0f..1f) { "body must be normalised to 0..1" }
        require(wickHigh in 0f..1f && wickLow in 0f..1f) { "wick must be normalised to 0..1" }
        require(wickHigh >= bodyHigh) { "wickHigh ($wickHigh) must be at or above bodyHigh ($bodyHigh)" }
        require(wickLow <= bodyLow) { "wickLow ($wickLow) must be at or below bodyLow ($bodyLow)" }
    }

    /** `true` when the close is above the open. The body has no direction of its own. */
    val isBullish: Boolean get() = bodyHigh >= bodyLow

    companion object {
        /** A full-range candle with no visible body — a doji, or a generic placeholder bar. */
        val DOJI = NormalisedCandle(bodyHigh = 0.5f, bodyLow = 0.5f, wickHigh = 0.9f, wickLow = 0.1f)
    }
}