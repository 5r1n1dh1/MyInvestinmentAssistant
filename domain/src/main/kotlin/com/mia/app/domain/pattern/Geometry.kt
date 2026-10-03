package com.mia.app.domain.pattern

import com.mia.app.domain.model.Pivot
import com.mia.app.domain.model.Timeframe
import com.mia.app.domain.technical.TrendlineFit
import com.mia.app.domain.technical.linearRegression
import com.mia.app.domain.model.MeasureBand
import kotlin.math.abs

/**
 * The shared geometric helper library (`patterns-spec.md` §3).
 *
 * Every one of the 12 detectors uses these functions and **nothing else geometric**, so that
 * "symmetry", "tilt" and "clean pivots" mean the same number in every detector — which is the
 * only way twelve patterns can be scored on one 0–100 scale and compared (plan.md §6.3).
 * `scoring-spec.md` consumes the same named measures, so a rename here breaks both files.
 *
 * All functions are pure and total: no IO, no clock, no `Random`, no Android (plan.md §12).
 */

/** `sym(a, b) = min(a,b) / max(a,b)` — `patterns-spec.md` §3.1. In `[0, 1]`; `1` ⇒ identical. */
fun symmetryRatio(a: Double, b: Double): Double {
    val hi = maxOf(a, b)
    val lo = minOf(a, b)
    if (!hi.isFinite() || !lo.isFinite() || hi <= 0.0 || lo < 0.0) return 0.0
    return lo / hi
}

/**
 * `prop(small, large) = small / large` — `patterns-spec.md` §3.1, the proportionality of an
 * ordered pair (head vs shoulder, first vs second peak). A negative numerator is allowed (a
 * *violation*, e.g. a head that failed to exceed the shoulder); a non-positive denominator is
 * not, and returns `0.0` rather than throwing so a rejected candidate still scores.
 */
fun proportionality(small: Double, large: Double): Double {
    if (!small.isFinite() || !large.isFinite() || large <= 0.0) return 0.0
    return small / large
}

/** The two peaks of a double top / the two bottoms of a double bottom are equal when this is 1. */
fun peakEquality(first: Double, second: Double): Double = symmetryRatio(first, second)

/** Bars between two pivots, `b.barIndex − a.barIndex` — `patterns-spec.md` §3.1. */
fun gap(a: Pivot, b: Pivot): Int = b.barIndex - a.barIndex

/**
 * ₹ per bar between two pivots — `patterns-spec.md` §3.1
 * `(p2.price − p1.price) / (p2.barIndex − p1.barIndex)`.
 *
 * Returns `0.0` when the two pivots share a bar, which can only happen in a hand-built list; a
 * detector must never divide by a zero-length span.
 */
fun slopePerBar(p1: Pivot, p2: Pivot): Double {
    val bars = gap(p1, p2)
    if (bars == 0) return 0.0
    return (p2.price - p1.price) / bars
}

/** `slopePerBar × 100 / p1.price` — % per bar (`patterns-spec.md` §3.1). */
fun slopePctPerBar(p1: Pivot, p2: Pivot): Double {
    if (p1.price <= 0.0) return 0.0
    return slopePerBar(p1, p2) * 100.0 / p1.price
}

/**
 * `slopePerBar × spanBars / p1.price × 100` — % of price **across a span**
 * (`patterns-spec.md` §3.1).
 *
 * This is the timeframe-independent slope measure and the one every slope tolerance in the spec
 * uses: a neckline is judged by how much of the stock's price it travels over the whole
 * formation, not by ₹ per bar, which would compare a ₹50 scrip with a ₹40,000 one.
 */
fun slopeOverSpan(p1: Pivot, p2: Pivot, spanBars: Int): Double {
    if (p1.price <= 0.0) return 0.0
    return slopePerBar(p1, p2) * spanBars / p1.price * 100.0
}

/** `(x − y) / y × 100` — signed % change (`patterns-spec.md` §3.1). */
fun barPct(x: Double, y: Double): Double {
    if (!x.isFinite() || !y.isFinite() || y == 0.0) return 0.0
    return (x - y) / y * 100.0
}

/**
 * A straight boundary line through pivots, evaluable at any bar.
 *
 * The neckline, the flag channel and the triangle's flat side are all this: two points create the
 * line, "the third touch validates it" `[Ch.16-5 §Rule 2]`.
 */
data class BoundaryLine(val slopePerBar: Double, val valueAtBarZero: Double) {

    /** The boundary price at [bar]. */
    fun at(bar: Int): Double = valueAtBarZero + slopePerBar * bar

    companion object {
        /** Horizontal line at [price]. */
        fun horizontal(price: Double): BoundaryLine = BoundaryLine(0.0, price)

        /** The line through two pivots. A shared bar yields a horizontal line at [p1]'s price. */
        fun through(p1: Pivot, p2: Pivot): BoundaryLine {
            val bars = gap(p1, p2)
            val slope = slopePerBar(p1, p2)
            return BoundaryLine(slope, p1.price - slope * p1.barIndex)
        }
    }
}

/** The line through two pivots, named after the spec's `neckline` / `support` usage. */
fun lineThrough(p1: Pivot, p2: Pivot): BoundaryLine = BoundaryLine.through(p1, p2)

/** Residual statistics of a boundary line through pivots (`patterns-spec.md` §3.3). */
data class LineFit(
    val line: BoundaryLine,
    val rSquared: Double,
    /** `max|yᵢ − ŷᵢ| / ref`; `0.0` when [ref] is not positive. */
    val residMax: Double,
    /** `mean|yᵢ − ŷᵢ| / ref`. */
    val residMean: Double,
    val pointCount: Int,
    val isDegenerate: Boolean,
)

/**
 * Least-squares fit of the boundary a set of pivots is supposed to touch, with residuals
 * expressed in units of the pattern's own scale [ref] (`patterns-spec.md` §3.3).
 *
 * [ref] is `patterns-spec.md` §3.2's per-detector pattern scale — the H&S head-to-neckline
 * height, the double top's peak-to-trough, the triangle's flat-side height. Dividing by it is
 * what makes every tolerance in the spec scale-free.
 *
 * A flat or too-short series reports `r² = 0` and `isDegenerate = true`, never `1.0`: a
 * horizontal neckline is not a *good* trendline fit, it is a line with nothing to explain
 * (`linearRegression`'s own convention, and `scoring-spec.md` DD-7).
 */
fun fitBoundary(points: List<Pivot>, ref: Double): LineFit {
    require(points.size >= 2) { "a boundary needs at least two pivots" }
    if (points.size == 2) {
        val line = BoundaryLine.through(points[0], points[1])
        return LineFit(line, 1.0, 0.0, 0.0, 2, isDegenerate = false)
    }
    val fit: TrendlineFit = linearRegression(points.map { it.price })
    val line = BoundaryLine(fit.slope, fit.intercept)
    val scale = if (ref > 0.0) ref else 0.0
    var maxResid = 0.0
    var sumResid = 0.0
    for (p in points) {
        val resid = abs(p.price - line.at(p.barIndex))
        if (resid > maxResid) maxResid = resid
        sumResid += resid
    }
    val n = points.size
    return LineFit(
        line = line,
        rSquared = if (fit.isDegenerate) 0.0 else fit.rSquared,
        residMax = if (scale > 0.0) maxResid / scale else 0.0,
        residMean = if (scale > 0.0) sumResid / n / scale else 0.0,
        pointCount = n,
        isDegenerate = fit.isDegenerate,
    )
}

/**
 * The R² a boundary reports when it is built from only two pivots: the **neutral** `0.60` of
 * `scoring-spec.md` DD-7, never `1.0`.
 *
 * Two points always lie exactly on a line, so reporting `1.0` would let every two-point neckline
 * in the app claim a perfect fit — the single easiest way to make the score meaningless.
 */
fun boundaryRSquared(points: List<Pivot>): Double =
    if (points.size < 3) PatternScoringPolicy.NEUTRAL_BOUNDARY_R2 else linearRegression(points.map { it.price }).rSquared

/** `tolerancePct(basePct) = basePct/100 × price` (`patterns-spec.md` §3.4). */
fun tolerancePct(basePct: Double, price: Double): Double = basePct / 100.0 * price

/** `toleranceAtr(mult) = mult × ATR14(bar)` (`patterns-spec.md` §3.4). */
fun toleranceAtr(mult: Double, atr: Double?): Double = if (atr == null) 0.0 else mult * atr

/**
 * The band inside which a pivot *touches* a boundary:
 * `max(tolerancePct(DEPTH_PCT × TOUCH_TOL_MULT), toleranceAtr(TOUCH_TOL_ATR))`
 * (`patterns-spec.md` §3.4).
 *
 * `[derived]`: "price rarely touches a trendline exactly, small deviations are normal"
 * `[Ch.16-5 §Rule 4]` together with "the third touch validates it" `[Ch.16-5 §Rule 2]` imply a
 * band about half the significance threshold — a touch is something that would not itself have
 * been called a pivot.
 */
fun touchTolerance(pivot: Pivot, timeframe: Timeframe, atr: Double?): Double {
    val base = ZigzagParams.forTimeframe(timeframe).depthPct
    return maxOf(tolerancePct(base * TOUCH_TOL_MULT, pivot.price), toleranceAtr(TOUCH_TOL_ATR, atr))
}

/** `patterns-spec.md` §3.4, `[derived]`. */
const val TOUCH_TOL_MULT: Double = 1.5

/** `patterns-spec.md` §3.4, `[derived]`. */
const val TOUCH_TOL_ATR: Double = 0.50

/** `true` when the pivot's price sits within [tolTolerance] of the line at its own bar. */
fun touches(pivot: Pivot, line: BoundaryLine, tolTolerance: Double): Boolean =
    abs(pivot.price - line.at(pivot.barIndex)) <= tolTolerance

/** The pivots of [points] that touch [line] within their own tolerance — "third touch" evidence. */
fun touching(points: List<Pivot>, line: BoundaryLine, timeframe: Timeframe, atrOf: (Int) -> Double?): List<Pivot> =
    points.filter { touches(it, line, touchTolerance(it, timeframe, atrOf(it.barIndex))) }

/**
 * Signed % per bar across a same-role sequence — `progressPct` of `patterns-spec.md` §3.5:
 * `(v_k − v_1) / (x_k − x_1) / v_1 × 100`.
 *
 * Positive means the series rose. The ascending triangle's S3/S4 floor test
 * (`patterns-spec.md` §16.2) reads this directly, and the wedge test reads its absolute value
 * against `1.5 × ATR/bar`.
 */
fun progressPctPerBar(points: List<Pivot>): Double {
    if (points.size < 2) return 0.0
    val first = points.first()
    val last = points.last()
    val bars = gap(first, last)
    if (bars == 0 || first.price <= 0.0) return 0.0
    return (last.price - first.price) / bars / first.price * 100.0
}

/**
 * `monoScore` — the share of consecutive same-role pairs that moved the required way
 * (`patterns-spec.md` §3.5). `1.0` ⇒ every step moved correctly.
 *
 * * [rising] `true` for the ascending triangle's lows and the bear flag's lower highs;
 *   `false` for the descending triangle's highs and the bull flag's upper channel.
 */
fun monoScore(points: List<Pivot>, rising: Boolean): Double {
    if (points.size < 2) return 0.0
    var ok = 0
    for (i in 1 until points.size) {
        val risingStep = points[i].price > points[i - 1].price
        val fallingStep = points[i].price < points[i - 1].price
        if (if (rising) risingStep else fallingStep) ok++
    }
    return ok.toDouble() / (points.size - 1)
}

/**
 * Flag-vs-pennant test, `patterns-spec.md` §18.2 S4:
 * `parallelDev = |sG − sF| / max(|sG|, |sF|, ε)`, `ε = 1e-9`.
 *
 * The book contrasts Flag = "**Parallel** boundaries" with Pennant = "**Converging**
 * boundaries" `[Ch.18-4 §Difference Between Flag and Pennant]`; `0.50` is the half-parallel point
 * and anything beyond it is a pennant, which is out of scope for v1 and must be rejected.
 */
fun parallelDeviation(firstSlope: Double, secondSlope: Double, epsilon: Double = PARALLEL_EPSILON): Double {
    val denom = maxOf(abs(firstSlope), abs(secondSlope), epsilon)
    return abs(firstSlope - secondSlope) / denom
}

private const val PARALLEL_EPSILON: Double = 1e-9

/**
 * `legScore(g) = clamp(1 − |g − gIdeal| / gTol, 0, 1)` and `spacingScore` as their product
 * (`scoring-spec.md` §4.1).
 *
 * `gIdeal` / `gTol` are per pattern and per timeframe (§4.1's table); a leg outside the window
 * scores exactly `0`, which is what makes example B in `scoring-spec.md` §10.2 collapse to a
 * zero spacing measure.
 */
fun legSpacingScore(legBars: Int, gIdeal: Double, gTol: Double): Double {
    require(gTol > 0.0) { "gTol must be positive" }
    return (1.0 - abs(legBars - gIdeal) / gTol).coerceIn(0.0, 1.0)
}

/** Product of [legSpacingScore] over every leg — `scoring-spec.md` §4.1. */
fun spacingScore(legBars: List<Int>, gIdeal: Double, gTol: Double): Double {
    if (legBars.isEmpty()) return 0.0
    var product = 1.0
    for (legs in legBars) product *= legSpacingScore(legs, gIdeal, gTol)
    return product.coerceIn(0.0, 1.0)
}

/**
 * `durationScore = LIN_LINEAR(span / spanIdeal, 0.30, 2.00)` (`scoring-spec.md` §4.2).
 *
 * The book: "The longer a pattern develops, the more significant it often becomes … a rectangle
 * lasting five days is generally less important than a rectangle lasting six months"
 * `[Ch.18-1 §Duration Matters]` — made linear and bounded so a five-day formation scores 0 and a
 * very old one saturates at 1.0 instead of dominating.
 */
fun durationScore(spanBars: Int, spanIdeal: Double): Double {
    if (spanIdeal <= 0.0) return 0.0
    val ratio = spanBars / spanIdeal
    return MeasureBand.Linear(false, PatternScoringPolicy.STRUCT_DUR_LO, PatternScoringPolicy.STRUCT_DUR_HI)
        .normalise(ratio)
}

/**
 * `priorTrendPct = (close[pivotBar] − close[pivotBar − LOOKBACK]) / close[pivotBar − LOOKBACK] × 100`
 * (`patterns-spec.md` §3.6).
 *
 * The book is emphatic that "a chart pattern must be interpreted relative to the preceding
 * trend" `[Ch.18-1 §The Importance of Trend Before the Pattern]`. This one function is the prior
 * trend for detectors 6–12 *and* the candlestick family context test, so the two can never be
 * graded differently.
 *
 * @return `null` when the look-back window does not exist — the caller must treat that as
 *   "no prior trend to judge", never as 0%.
 */
fun priorTrendPct(closes: List<Double>, barIndex: Int, lookback: Int): Double? {
    val from = barIndex - lookback
    if (barIndex !in closes.indices || from !in closes.indices) return null
    val base = closes[from]
    if (base <= 0.0) return null
    return (closes[barIndex] - base) / base * 100.0
}

/** Prior-trend look-back in bars, `20` daily / `13` weekly (`patterns-spec.md` §3.6, `[derived]`). */
fun priorTrendLookback(timeframe: Timeframe): Int = when (timeframe) {
    Timeframe.D1 -> 20
    Timeframe.W1 -> 13
}

/**
 * The prior-trend threshold a formation must clear, `±3.0 %` daily / `±5.0 %` weekly
 * (`patterns-spec.md` §3.6). Signed comparison belongs to the caller: bullish patterns need
 * `>= this`, bearish patterns `<= -this`.
 */
fun priorTrendThreshold(timeframe: Timeframe): Double = when (timeframe) {
    Timeframe.D1 -> 3.0
    Timeframe.W1 -> 5.0
}

/** Coefficient of variation of [values] — the §4.3 "context window cleanliness" measure. */
fun coefficientOfVariation(values: List<Double>): Double {
    if (values.size < 2) return 0.0
    var mean = 0.0
    for (v in values) mean += v
    mean /= values.size
    if (mean == 0.0) return 0.0
    var variance = 0.0
    for (v in values) variance += (v - mean) * (v - mean)
    variance /= values.size
    return Math.sqrt(variance) / kotlin.math.abs(mean)
}