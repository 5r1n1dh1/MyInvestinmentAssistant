package com.mia.app.domain.pattern.detectors.chart

import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.Direction
import com.mia.app.domain.model.MatchScorePolicy
import com.mia.app.domain.model.MeasuredMove
import com.mia.app.domain.model.Pivot
import com.mia.app.domain.model.PivotKind
import com.mia.app.domain.model.Timeframe
import com.mia.app.domain.pattern.BoundaryLine
import com.mia.app.domain.pattern.BreakoutEvaluator
import com.mia.app.domain.pattern.Measure
import com.mia.app.domain.pattern.PatternScoringPolicy
import com.mia.app.domain.pattern.Penalty
import com.mia.app.domain.pattern.ScoreFactor
import com.mia.app.domain.pattern.ZigzagParams
import com.mia.app.domain.pattern.priorTrendLookback
import com.mia.app.domain.pattern.priorTrendPct
import com.mia.app.domain.pattern.priorTrendThreshold
import com.mia.app.domain.pattern.spacingScore
import com.mia.app.domain.pattern.touchTolerance
import com.mia.app.domain.pattern.touches
import com.mia.app.domain.technical.adx
import com.mia.app.domain.technical.atr
import com.mia.app.domain.technical.deliveryAveragePercent
import com.mia.app.domain.technical.obv
import com.mia.app.domain.technical.rsi
import com.mia.app.domain.technical.trendlineFit
import com.mia.app.domain.technical.volumeSma
import kotlin.math.abs

/**
 * Everything the seven chart-pattern detectors (spec §12–§18) share.
 *
 * **Why this file exists.** `patterns-spec.md` §3 is one shared geometric library and §19 one
 * shared penalty catalogue; this is the *shared detector-side* remainder — the context that reads
 * the indicator series once instead of per-measure, the role-window arithmetic, the breakout and
 * failure detection of §3.7 / §11.1, and the constants of §21 written out with their sections so
 * no detector can quietly disagree with another about what "3 ATR" or "small" means.
 *
 * **Nothing here scores anything.** A measure is emitted with the normalisation band the scorer
 * expects and the arithmetic lives in `FactorScorer` (`docs/CONTRACTS.md`, "The detector ↔ scorer
 * seam (locked)").
 */

/**
 * One candidate formation found by a detector, before it becomes a `Match`.
 *
 * Kept separate from `Match` so the measures, the failure-mode penalties and the measured move can
 * be asserted without going through a score, and so a detector can scan a pivot list, collect
 * candidates and only then build matches (the "at most one `Match` per (formation, breakout) pair"
 * rule of `patterns-spec.md` §4 rule 6).
 *
 * @param breakoutBar `-1` while the formation is still `FORMING` (§11.1), which is also when
 *   [measuredMove] is `null`.
 */
internal data class ChartCandidate(
    val name: String,
    /** Exactly the role pivots the detector used, oldest first (§4 rule 8). */
    val pivots: List<Pivot>,
    val measures: List<Measure>,
    val penalties: List<Penalty>,
    val measuredMove: MeasuredMove?,
    val formationStartBar: Int,
    val breakoutBar: Int = -1,
) {
    /** `true` when the trigger has not been closed through yet (`patterns-spec.md` §11.1). */
    val isForming: Boolean get() = breakoutBar < 0
}

/**
 * The `[derived]` constants of `patterns-spec.md` §21 for detectors 6–12, plus the volume,
 * divergence, breakout and duration thresholds of §3.7 / §3.8 / §11.2 / §19.
 *
 * Every name is the spec's own name and every value is the spec's own value, so a detector can be
 * checked against the appendix without reading prose. Nothing here is invented except where the
 * spec itself says `[derived]`, and those rows carry the section they come from.
 */
internal object ChartSpec {

    // ------------------------------------------------------------ §3.7 / §11.2: windows

    /**
     * Bars a false breakout has to close back inside: `3` daily / `2` weekly.
     *
     * `[derived]`: the book waits for follow-through of "the next one or two sessions"
     * `[Ch.18-5 §Confirmation Techniques §3]`; 3 daily / 2 weekly keeps roughly the same
     * wall-clock window on both timeframes.
     */
    fun trapWindow(timeframe: Timeframe): Int = when (timeframe) {
        Timeframe.D1 -> 3
        Timeframe.W1 -> 2
    }

    /** Bars a throwback/pullback has to fail within (§3.7 `isFailedRetest`). */
    const val FAILED_WINDOW_BARS: Int = 10

    /** Bars after the last formation pivot before the pattern is stale (§16.3 `I5`, §18.3). */
    const val STALE_BARS: Int = 10

    /** Bars the breakout failure modes look ahead (§12.3 `I4`, §18.3 `I3`). */
    const val FAILURE_LOOKAHEAD_BARS: Int = 10

    // -------------------------------------------------------------------- §3.8: volume

    /** `rvol` that confirms: 2.0 ("Breakout volume 2× average" `[Ch.22 §22.34]`). */
    const val RVOL_CONFIRM: Double = 2.0

    /** `rvol` below which a break is on weak volume ("A neckline break on weak volume"). */
    const val RVOL_WEAK: Double = 1.5

    /** `volumeContraction` a healthy continuation formation stays at or below (§3.8). */
    const val VOLUME_CONTRACTION_OK: Double = 1.00

    /** `volumeContraction` at or below which contraction is "ideal" (§3.8). */
    const val VOLUME_CONTRACTION_IDEAL: Double = 0.80

    /** Above this the formation expanded — "unhealthy continuation" `[Ch.18-4 §Role of Volume]`. */
    const val VOLUME_CONTRACTION_UNHEALTHY: Double = 1.10

    /** Bars before the formation that `volumeContraction` compares against (§3.8). */
    const val VOLUME_BASELINE_BARS: Int = 20

    /** `deliveryVsAvg` that confirms (`[Ch.21 §21.19]`). */
    const val DELIVERY_CONFIRM: Double = 1.00

    // ----------------------------------------------------------------- §3.8: divergence

    /** Minimum bars between the two pivots of a divergence (§3.8). */
    const val DIVERGENCE_MIN_BARS: Int = 3

    /** RSI gap, in points, below which a divergence is sampling noise (§3.8, `[derived]`). */
    const val DIVERGENCE_MIN_GAP_RSI: Double = 3.0

    // ----------------------------------------------------------------------- §19: EXHAUSTION

    /** §19: `close[n] >= 1.40 × close[n − 60]` (D1) / `close[n − 40]` (W1). */
    const val EXHAUSTION_MULTIPLE: Double = 1.40

    fun exhaustionLookback(timeframe: Timeframe): Int = when (timeframe) {
        Timeframe.D1 -> 60
        Timeframe.W1 -> 40
    }

    // ------------------------------------------------------------------ §19: RANGING_REGIME

    /** §19: `adx < 20` at the trigger bar — "breakouts fail frequently" `[Ch.20 §Regime 3]`. */
    const val ADX_RANGING: Double = 20.0

    // ------------------------------------------------------ §12.2: head & shoulders

    /** §12.2 S2 `[derived]`: the head must clear the higher shoulder by this fraction of `ref`. */
    const val HS_HEAD_DOMINANCE: Double = 0.10

    /** §12.2 S3 `[derived]`: shoulder-symmetry floor — "Uneven shoulders" are legitimate. */
    const val HS_SHOULDER_SYM: Double = 0.90

    /** §12.2 S6 `[derived]`: `|necklineTilt|` across the whole formation, as a % of price. */
    const val HS_NECKLINE_TILT_PCT: Double = 6.00

    fun hsMinLegBars(timeframe: Timeframe): Int = when (timeframe) {
        Timeframe.D1 -> 3
        Timeframe.W1 -> 2
    }

    fun hsMaxLegBars(timeframe: Timeframe): Int = when (timeframe) {
        Timeframe.D1 -> 40
        Timeframe.W1 -> 20
    }

    fun hsMinSpanBars(timeframe: Timeframe): Int = when (timeframe) {
        Timeframe.D1 -> 15
        Timeframe.W1 -> 8
    }

    fun hsMaxSpanBars(timeframe: Timeframe): Int = when (timeframe) {
        Timeframe.D1 -> 120
        Timeframe.W1 -> 60
    }

    /** §12.2 S9 `[derived]`: leg-length symmetry floor on both swing pairs. */
    const val HS_TIME_SYM: Double = 0.50

    // --------------------------------------------------- §14.2: double top / bottom

    /** §14.2 S1 `[derived]`: the book's own "near" is ±3 %; its worked example is ±0.5 %. */
    const val DT_PEAK_SYM: Double = 0.97

    /**
     * §14.2 S2 `[derived]`: "buyers were given **two opportunities** to overcome resistance" —
     * two peaks 0.3 ATR apart are one peak, not two.
     */
    const val DT_MIN_TROUGH_ATR: Double = 2.0

    fun dtMinSepBars(timeframe: Timeframe): Int = when (timeframe) {
        Timeframe.D1 -> 5
        Timeframe.W1 -> 3
    }

    fun dtMaxSepBars(timeframe: Timeframe): Int = when (timeframe) {
        Timeframe.D1 -> 80
        Timeframe.W1 -> 40
    }

    // ------------------------------------------------------------------ §16.2: triangles

    /** §16.2 S1 `[derived]`: equal to `DEPTH_PCT` — the flat side must sit inside noise. */
    fun flatBandPct(timeframe: Timeframe): Double = when (timeframe) {
        Timeframe.D1 -> 1.50
        Timeframe.W1 -> 2.50
    }

    /** §16.2 S3 `[derived]`: % per bar the rising (or falling) boundary must progress at least. */
    fun risingFloorPctPerBar(timeframe: Timeframe): Double = when (timeframe) {
        Timeframe.D1 -> 0.05
        Timeframe.W1 -> 0.10
    }

    /** §16.2 S4 `[derived]`: beyond this the boundary is a wedge, which is out of scope for v1. */
    const val TRI_WEDGE_ATR_MULTIPLE: Double = 1.5

    fun triMinSpanBars(timeframe: Timeframe): Int = when (timeframe) {
        Timeframe.D1 -> 10
        Timeframe.W1 -> 6
    }

    fun triMaxSpanBars(timeframe: Timeframe): Int = when (timeframe) {
        Timeframe.D1 -> 120
        Timeframe.W1 -> 60
    }

    /** §16.1 P2 `[derived]`: two highs on the flat side, i.e. the line plus a "third touch". */
    const val TRI_MIN_TOUCHES: Int = 2

    /**
     * Longest triangle window scanned: five touches (`2m + 1 = 11` pivots).
     *
     * `[derived]` bound, not a spec rule: §16.1 sets no maximum `m`, and an unbounded window lets
     * one 60-pivot series produce dozens of overlapping "triangles". A formation with more than
     * five touches is a rectangle, which is a different pattern with no v1 detector.
     */
    const val TRI_MAX_TOUCHES: Int = 5

    // ------------------------------------------------------------------------ §18.2: flag

    /** §18.2 S1 `[derived]`: "sharp directional movement", as a % of the pole base. */
    fun poleMinPct(timeframe: Timeframe): Double = when (timeframe) {
        Timeframe.D1 -> 10.0
        Timeframe.W1 -> 8.0
    }

    /** §18.2 S1 `[derived]`: four ATR — "at least two stops long" `[Ch.22 §22.27]`. */
    fun poleMinAtr(timeframe: Timeframe): Double = when (timeframe) {
        Timeframe.D1 -> 4.0
        Timeframe.W1 -> 3.0
    }

    /** §18.2 S2 `[derived]`: floor of "small compared to the flagpole". */
    const val FLAG_DEPTH_MIN: Double = 0.05

    /** §18.2 S2 `[derived]`: ceiling — past this it is a deep correction, not a flag. */
    const val FLAG_DEPTH_MAX: Double = 0.50

    /** §18.2 S4 `[derived]`: half-parallel point; beyond it the shape is a pennant (out of scope). */
    const val FLAG_PARALLEL_MAX: Double = 0.50

    /** §18.1 P2: at least two counter-trend pivots per side, i.e. four flag pivots. */
    const val FLAG_MIN_TOUCH_PAIRS: Int = 2

    fun flagMinBars(timeframe: Timeframe): Int = when (timeframe) {
        Timeframe.D1 -> 3
        Timeframe.W1 -> 2
    }

    fun flagMaxBars(timeframe: Timeframe): Int = when (timeframe) {
        Timeframe.D1 -> 20
        Timeframe.W1 -> 12
    }

    /**
     * Longest flag window scanned: four counter-trend pairs (8 flag pivots).
     *
     * `[derived]` bound, for the same reason as [TRI_MAX_TOUCHES]: §18.2 caps the flag's *duration*
     * at 20 daily bars, which already bounds the realistic width, and this stops a 60-pivot series
     * from producing a flag per starting index.
     */
    const val FLAG_MAX_TOUCH_PAIRS: Int = 4

    // ---------------------------------------------- scoring-spec.md §4.1: spacing ideals

    fun shoulderLegIdeal(timeframe: Timeframe): Double = if (timeframe == Timeframe.D1) 15.0 else 8.0

    fun shoulderLegTolerance(timeframe: Timeframe): Double = if (timeframe == Timeframe.D1) 12.0 else 7.0

    fun doubleTopSpacingIdeal(timeframe: Timeframe): Double = if (timeframe == Timeframe.D1) 30.0 else 15.0

    fun doubleTopSpacingTolerance(timeframe: Timeframe): Double = if (timeframe == Timeframe.D1) 25.0 else 12.0

    fun triangleLegIdeal(timeframe: Timeframe): Double = if (timeframe == Timeframe.D1) 12.0 else 6.0

    fun triangleLegTolerance(timeframe: Timeframe): Double = if (timeframe == Timeframe.D1) 10.0 else 5.0

    fun flagPoleIdeal(timeframe: Timeframe): Double = if (timeframe == Timeframe.D1) 24.0 else 12.0

    fun flagPoleTolerance(timeframe: Timeframe): Double = if (timeframe == Timeframe.D1) 16.0 else 8.0

    fun flagLegIdeal(timeframe: Timeframe): Double = if (timeframe == Timeframe.D1) 5.0 else 3.0

    fun flagLegTolerance(timeframe: Timeframe): Double = if (timeframe == Timeframe.D1) 4.0 else 2.0

    // ------------------------------------------- scoring-spec.md §4.2: duration ideals

    fun shouldersSpanIdeal(timeframe: Timeframe): Double = if (timeframe == Timeframe.D1) 68.0 else 34.0

    fun doubleTopSpanIdeal(timeframe: Timeframe): Double = if (timeframe == Timeframe.D1) 42.0 else 21.0

    fun triangleSpanIdeal(timeframe: Timeframe): Double = if (timeframe == Timeframe.D1) 65.0 else 32.0

    fun flagSpanIdeal(timeframe: Timeframe): Double = if (timeframe == Timeframe.D1) 44.0 else 22.0

    // ------------------------------------------------------------------------------ OBV

    /** `scoring-spec.md` §5.1 C5: OBV slope over the 5 bars ending at the breakout. */
    const val OBV_SLOPE_BARS: Int = 5
}

/**
 * The indicator readings every chart detector needs, computed **once per `detect()` call**.
 *
 * `PatternDetector.detect` receives only `(pivots, candles)` — the engine already holds an
 * `IndicatorSnapshot` but the locked interface does not pass it — so each detector builds this from
 * the candles. That is one ATR/RSI/ADX/volume pass per detector per symbol rather than the four or
 * five a naive implementation would do, and it keeps all seven detectors reading *the same* numbers,
 * which is what makes their scores comparable (plan.md §6.3).
 *
 * Every accessor returns `null` for "insufficient history" and never a fabricated zero
 * (`Indicators.kt`'s stated contract), so a warm-up gap can never masquerade as a reading.
 */
internal class ChartContext private constructor(
    val candles: List<Candle>,
    val timeframe: Timeframe,
    private val closes: List<Double>,
    private val atrSeries: List<Double?>,
    private val rsiSeries: List<Double?>,
    private val obvSeries: List<Double>,
    private val volumeRatioSeries: List<Double?>,
    private val deliveryAverageSeries: List<Double?>,
    private val adxSeries: List<Double?>,
    private val diPlusSeries: List<Double?>,
    private val diMinusSeries: List<Double?>,
    private val trendlineRSquared: Double,
) {

    val barCount: Int get() = candles.size

    /** `true` when the series clears the plan.md §6.4 history gate for this timeframe. */
    val hasSufficientBars: Boolean
        get() = MatchScorePolicy.isSufficientBars(timeframe, barCount)

    fun closeAt(bar: Int): Double? = closes.getOrNull(bar)

    /** `ATR14` at [bar]; `null` inside the warm-up, at a non-positive reading, or off-series. */
    fun atrAt(bar: Int): Double? = atrSeries.getOrNull(bar)?.takeIf { it.isFinite() && it > 0.0 }

    fun rsiAt(bar: Int): Double? = rsiSeries.getOrNull(bar)

    /** `rvol[bar] = volume / volumeSma20[bar]` (`patterns-spec.md` §3.8). */
    fun rvolAt(bar: Int): Double? = volumeRatioSeries.getOrNull(bar)?.takeIf { it.isFinite() }

    /** `deliveryVsAvg(bar)` (`patterns-spec.md` §3.8); `null` when delivery is unavailable. */
    fun deliveryVsAvgAt(bar: Int): Double? {
        val delivery = candles.getOrNull(bar)?.deliveryPercent ?: return null
        val average = deliveryAverageSeries.getOrNull(bar) ?: return null
        if (average <= 0.0 || !delivery.isFinite()) return null
        return delivery / average
    }

    /**
     * OBV slope over the [lookback] bars ending at [bar], normalised by that window's gross
     * volume — the same definition `technical.obvSlope` uses at the last bar, so a breakout reading
     * and a latest reading are on one scale. `null` when the window does not exist.
     */
    fun obvSlopeAt(bar: Int, lookback: Int = ChartSpec.OBV_SLOPE_BARS): Double? {
        if (lookback <= 0 || bar < lookback || bar >= candles.size) return null
        var gross = 0.0
        for (j in (bar - lookback + 1)..bar) gross += candles[j].volume
        if (gross == 0.0) return null
        return (obvSeries[bar] - obvSeries[bar - lookback]) / gross
    }

    fun adxAt(bar: Int): Double? = adxSeries.getOrNull(bar)

    /** `+DI > −DI` for an upside pattern, `−DI > +DI` for a downside one; `null` when unavailable. */
    fun diAlignedAt(bar: Int, direction: Direction): Boolean? {
        val plus = diPlusSeries.getOrNull(bar) ?: return null
        val minus = diMinusSeries.getOrNull(bar) ?: return null
        return when (direction) {
            Direction.UP -> plus > minus
            Direction.DOWN -> minus > plus
        }
    }

    /** `IndicatorSnapshot.trendlineFitRSquared`: the 30-bar close regression, `0.0` if degenerate. */
    fun trendlineFitRSquared(): Double = trendlineRSquared

    /**
     * The pivot's touch tolerance (`patterns-spec.md` §3.4):
     * `max(tolerancePct(DEPTH_PCT × TOUCH_TOL_MULT), toleranceAtr(TOUCH_TOL_ATR))`.
     */
    fun touchToleranceAt(pivot: Pivot): Double =
        touchTolerance(pivot, timeframe, atrAt(pivot.barIndex))

    /**
     * The same tolerance for a *level* rather than a pivot — used by the retest tests, which have a
     * boundary price at a bar but no swing point there.
     */
    fun touchToleranceAt(bar: Int, price: Double): Double =
        touchTolerance(Pivot(bar, candles.getOrNull(bar)?.date ?: java.time.LocalDate.MIN, price, PivotKind.LOW, 0), timeframe, atrAt(bar))

    /**
     * The confirmed pivots of [pivots] (`patterns-spec.md` §2.5).
     *
     * **The consequence worth stating.** A formation whose newest role pivot is the last pivot of
     * the series cannot match, because that pivot is by definition unconfirmed. That is the spec's
     * deliberate trade: "a same-day extreme reverses tomorrow and re-scores the whole formation",
     * and a one-bar lag costs one evening of freshness to remove day-to-day flicker (plan.md §11
     * refreshes at 19:00 IST). Nothing here lowers a shape threshold to compensate.
     */
    fun confirmed(pivots: List<Pivot>): List<Pivot> {
        if (barCount == 0) return emptyList()
        val lag = ZigzagParams.forTimeframe(timeframe).confirmBars
        return pivots.filter { (barCount - 1 - it.barIndex) >= lag }
    }

    /** `priorTrendPct` measured at [bar] (`patterns-spec.md` §3.6). */
    fun priorTrendPctAt(bar: Int): Double? =
        priorTrendPct(closes, bar, priorTrendLookback(timeframe))

    /**
     * The prior-trend gate of §12.1 P3 / §14.1 P3 / §16.1 P3.
     *
     * @param direction `UP` when the book needs a prior **rise** ("Head & Shoulders: Requires a
     *   clear uptrend before the pattern" `[Ch.18-2 §The Role of Trend]`), `DOWN` for a prior fall.
     * @return `true` only when the look-back window exists *and* clears the spec's threshold. A
     *   missing window is a refusal, never a pass: there is no way to see a clear uptrend from a
     *   window that does not exist, and "Without a prior decline … it is simply two similar lows"
     *   `[Ch.18-1]` is the book's own reason to refuse.
     */
    fun hasPriorTrend(bar: Int, direction: Direction): Boolean {
        val value = priorTrendPctAt(bar) ?: return false
        val threshold = priorTrendThreshold(timeframe)
        return when (direction) {
            Direction.UP -> value >= threshold
            Direction.DOWN -> value <= -threshold
        }
    }

    companion object {
        /** Builds the context. Empty input yields a context that answers `null` everywhere. */
        fun of(candles: List<Candle>): ChartContext {
            val timeframe = candles.firstOrNull()?.timeframe ?: Timeframe.D1
            if (candles.isEmpty()) return empty(timeframe)
            val closes = candles.map { it.close }
            val volumeAverages = volumeSma(candles)
            val dmi = adx(candles)
            val fit = trendlineFit(closes)
            return ChartContext(
                candles = candles,
                timeframe = timeframe,
                closes = closes,
                atrSeries = atr(candles),
                rsiSeries = rsi(candles).rsi,
                obvSeries = obv(candles),
                volumeRatioSeries = candles.mapIndexed { i, candle ->
                    val average = volumeAverages.getOrNull(i)
                    if (average == null || average == 0.0) null else candle.volume.toDouble() / average
                },
                deliveryAverageSeries = deliveryAveragePercent(candles),
                adxSeries = dmi.adx,
                diPlusSeries = dmi.diPlus,
                diMinusSeries = dmi.diMinus,
                trendlineRSquared = if (fit.isDegenerate) 0.0 else fit.rSquared,
            )
        }

        private fun empty(timeframe: Timeframe): ChartContext = ChartContext(
            candles = emptyList(),
            timeframe = timeframe,
            closes = emptyList(),
            atrSeries = emptyList(),
            rsiSeries = emptyList(),
            obvSeries = emptyList(),
            volumeRatioSeries = emptyList(),
            deliveryAverageSeries = emptyList(),
            adxSeries = emptyList(),
            diPlusSeries = emptyList(),
            diMinusSeries = emptyList(),
            trendlineRSquared = 0.0,
        )
    }
}

// ---------------------------------------------------------------------------- role windows

/** Every window of [length] consecutive pivots starting at or after [from], oldest first (§4 rule 3). */
internal fun <T> List<T>.windowsOf(length: Int, from: Int = 0): Sequence<List<T>> {
    if (length <= 0 || size < length) return emptySequence()
    val start = from.coerceIn(0, size - length)
    return (start..(size - length)).asSequence().map { subList(it, it + length) }
}

/** `true` when the window's kinds are exactly [kinds], in order (§12.1 P2 role assignment). */
internal fun List<Pivot>.hasKindSequence(kinds: List<PivotKind>): Boolean =
    size == kinds.size && indices.all { this[it].kind == kinds[it] }

/**
 * `true` when no two consecutive pivots share a kind — `patterns-spec.md` §2.5's alternation
 * invariant, which the shared zigzag guarantees and plan.md §12 asserts.
 *
 * A window that violates it is not a formation: it is a formation plus a swing that has already
 * broken the boundary, and counting that swing as a "touch" of the flat side is exactly the mistake
 * that would make a completed triangle look like a forming one.
 */
internal fun List<Pivot>.hasAlternatingKinds(): Boolean =
    (1 until size).all { this[it].kind != this[it - 1].kind }

/** Bars between the first and the last pivot. */
internal fun List<Pivot>.spanBars(): Int = if (size < 2) 0 else last().barIndex - first().barIndex

/** Every consecutive leg length in bars. */
internal fun List<Pivot>.legGaps(): List<Int> =
    (1 until size).map { this[it].barIndex - this[it - 1].barIndex }

/**
 * The pivots of [points] that touch [line] inside their own tolerance, excluding [excludeBars].
 *
 * §12.2 S5 and `[Ch.16-5 §Rule 2]` ("the third touch validates it"): a boundary built from two
 * points reports `residMax = 0.0` and the neutral `r²` of DD-7, so a third pivot is what upgrades
 * the structure reading from "two points always fit" to an actual measurement.
 */
internal fun ChartContext.thirdTouch(points: List<Pivot>, line: BoundaryLine, excludeBars: Set<Int>): List<Pivot> =
    points.filter { candidate ->
        !excludeBars.contains(candidate.barIndex) && touches(candidate, line, touchToleranceAt(candidate))
    }

// ------------------------------------------------------------------------------ breakout

/** `close` beyond `level` by at least [bufferPct] per cent, in [direction] (§3.7, §11.2). */
internal fun clearsDecisively(close: Double, level: Double, direction: Direction, bufferPct: Double): Boolean {
    if (!close.isFinite() || !level.isFinite()) return false
    return when (direction) {
        Direction.UP -> close > level * (1.0 + bufferPct / 100.0)
        Direction.DOWN -> close < level * (1.0 - bufferPct / 100.0)
    }
}

/**
 * The first bar at or after [fromBar] whose **close** clears a *sloping* boundary decisively
 * (`patterns-spec.md` §3.7 `isBreakout`, §11.1, §11.2).
 *
 * `BreakoutEvaluator.firstBreakoutBar` takes a flat level because every boundary a candlestick
 * detector owns is flat; a neckline and a flag channel both move, so the level has to be read at
 * each candidate bar. The buffer, the "close not high" rule and the "first `j >= from`" rule are
 * identical to that evaluator's — this is the sloping-boundary form of the same test.
 */
internal fun ChartContext.firstSlopedBreakout(
    line: BoundaryLine,
    fromBar: Int,
    direction: Direction,
    bufferPct: Double = BreakoutEvaluator.bufferPct(timeframe),
): Int? {
    for (j in fromBar.coerceAtLeast(0) until barCount) {
        if (clearsDecisively(candles[j].close, line.at(j), direction, bufferPct)) return j
    }
    return null
}

/** `breakoutDepthPct` (§3.7): how far *past* the level the close sits, as a %. Never negative. */
internal fun breakoutDepthPct(close: Double, level: Double, direction: Direction): Double {
    if (!close.isFinite() || !level.isFinite() || level == 0.0) return 0.0
    val raw = when (direction) {
        Direction.UP -> (close - level) / level * 100.0
        Direction.DOWN -> (level - close) / level * 100.0
    }
    return if (raw.isFinite()) abs(raw) else 0.0
}

/**
 * `isBullTrap` / `isBearTrap` as an **event count** (`patterns-spec.md` §3.7).
 *
 * "Within `TRAP_WINDOW` bars a close beyond `level` was followed by a close back inside"
 * `[Ch.18-5 §Bull Trap]`. The scan starts **armed**, because the close at [breakoutBar] is by
 * definition already beyond the level — that close is the "a close beyond `level`" of the rule, and
 * a trap is the *next* close that undoes it. Returned as a count because `scoring-spec.md` §7.2 caps
 * the trap penalty at "once per trigger event, max 2 events".
 */
internal fun ChartContext.trapEvents(line: BoundaryLine, breakoutBar: Int, direction: Direction): Int {
    val window = ChartSpec.trapWindow(timeframe)
    val bufferPct = BreakoutEvaluator.bufferPct(timeframe)
    var events = 0
    var armed = true
    for (j in (breakoutBar + 1)..(breakoutBar + window)) {
        if (j !in 0 until barCount) break
        val level = line.at(j)
        val close = candles[j].close
        if (armed && closesBackInside(close, level, direction)) {
            events++
            armed = false
            continue
        }
        if (!armed && clearsDecisively(close, level, direction, bufferPct)) armed = true
    }
    return events
}

/** A close back on the *original* side of the boundary — "a close back inside" (§3.7). */
private fun closesBackInside(close: Double, level: Double, direction: Direction): Boolean = when (direction) {
    Direction.UP -> close < level
    Direction.DOWN -> close > level
}

/**
 * `isFailedRetest` (`patterns-spec.md` §3.7, §12.3 `I5`, §14.3 `I6`).
 *
 * The book's throwback "that is a failure of the pattern" `[Ch.18-2 §Throwback]`: price comes back
 * to the broken level and a close goes decisively back through it **against** the break. A
 * throwback that *holds* is confirmation, not a penalty (`scoring-spec.md` §5.1 C4), which is why
 * this asks only for the close that goes the wrong way.
 */
internal fun ChartContext.isFailedRetest(
    line: BoundaryLine,
    breakoutBar: Int,
    direction: Direction,
    tolerance: Double,
): Boolean {
    val window = minOf(breakoutBar + ChartSpec.FAILED_WINDOW_BARS, barCount - 1)
    if (breakoutBar >= window) return false
    for (j in (breakoutBar + 1)..window) {
        val level = line.at(j)
        val against = when (direction) {
            Direction.UP -> candles[j].close < level - tolerance
            Direction.DOWN -> candles[j].close > level + tolerance
        }
        if (against) return true
    }
    return false
}

/**
 * Price reclaimed (or re-broke) an extreme within [lookahead] bars of [barFrom] — the test behind
 * §12.3 `I4`/`I6` (a "failed Head & Shoulders" re-taking its head) and §18.3 `I3`.
 */
internal fun ChartContext.passedLevel(barFrom: Int, level: Double, direction: Direction, lookahead: Int): Boolean {
    if (barFrom >= barCount || barFrom < 0) return false
    val until = minOf(barFrom + lookahead, barCount - 1)
    for (j in barFrom..until) {
        val beyond = when (direction) {
            Direction.UP -> candles[j].high > level
            Direction.DOWN -> candles[j].low < level
        }
        if (beyond) return true
    }
    return false
}

/**
 * `INTRADAY_SPIKE_ONLY` (`patterns-spec.md` §19): the boundary was crossed by `high`/`low` but
 * never by a close. "Professional analysts place much greater importance on closing prices than on
 * temporary intraday movements" `[Ch.18-5 §Breakout vs Intraday Spike]`.
 */
internal fun ChartContext.intradaySpikeOnly(
    line: BoundaryLine,
    fromBar: Int,
    direction: Direction,
    bufferPct: Double = BreakoutEvaluator.bufferPct(timeframe),
): Boolean {
    for (j in fromBar.coerceAtLeast(0) until barCount) {
        val level = line.at(j)
        val poked = when (direction) {
            Direction.UP -> candles[j].high > level * (1.0 + bufferPct / 100.0)
            Direction.DOWN -> candles[j].low < level * (1.0 - bufferPct / 100.0)
        }
        if (poked) return true
    }
    return false
}

/**
 * `EXHAUSTION` (`patterns-spec.md` §19): the trigger bar is at least 40 % beyond the bar
 * [ChartSpec.exhaustionLookback] bars earlier, in the pattern's direction.
 */
internal fun ChartContext.isExhausted(triggerBar: Int, direction: Direction): Boolean {
    val base = closeAt(triggerBar - ChartSpec.exhaustionLookback(timeframe)) ?: return false
    val close = closeAt(triggerBar) ?: return false
    if (base <= 0.0) return false
    return when (direction) {
        Direction.UP -> close >= ChartSpec.EXHAUSTION_MULTIPLE * base
        Direction.DOWN -> close <= base / ChartSpec.EXHAUSTION_MULTIPLE
    }
}

// ------------------------------------------------------------------- volume and divergence

/**
 * `volumeContraction = mean(volume over the formation) / mean(volume over the 20 bars before it)`
 * (`patterns-spec.md` §3.8). `null` when either window is missing or carried no traded volume.
 */
internal fun ChartContext.volumeContraction(fromBar: Int, toBar: Int): Double? {
    if (fromBar < 0 || toBar > barCount - 1 || fromBar > toBar) return null
    val baselineFrom = maxOf(0, fromBar - ChartSpec.VOLUME_BASELINE_BARS)
    if (baselineFrom >= fromBar) return null
    var inside = 0.0
    var insideBars = 0
    for (j in fromBar..toBar) {
        inside += candles[j].volume
        insideBars++
    }
    var baseline = 0.0
    var baselineBars = 0
    for (j in baselineFrom until fromBar) {
        baseline += candles[j].volume
        baselineBars++
    }
    if (insideBars == 0 || baselineBars == 0 || baseline <= 0.0) return null
    val ratio = (inside / insideBars) / (baseline / baselineBars)
    return if (ratio.isFinite()) ratio else null
}

/**
 * The RSI gap of a divergence between two same-role pivots, or `0.0` when there is none.
 *
 * `patterns-spec.md` §3.8's formal rule is `bearishDivergence(pA, pB) == pB.price > pA.price AND
 * rsiB < rsiA` and `bullishDivergence(pA, pB) == pB.price < pA.price AND rsiB > rsiA`, the pair must
 * be at least [ChartSpec.DIVERGENCE_MIN_BARS] bars apart, and only a gap of at least
 * [ChartSpec.DIVERGENCE_MIN_GAP_RSI] points counts.
 *
 * **Why the price condition is dropped, which subsumes two readings of the rule.** §12.1 S1 forces a
 * shoulder pattern's right shoulder *below* its head and a double top's second peak below its first,
 * so for detectors 6, 7, 8 and 9 the strict price condition can never hold on the leg the spec names —
 * and §12.7 and §14.7 both *demonstrate* the rule firing anyway ("price 1140 < 1200 and RSI 63.8 <
 * 71.4 ⇒ bearish divergence"). The two readings therefore differ only in which RSI direction they
 * accept, and both accept the same one: **RSI must move against the pattern** — down for a top, up
 * for a bottom — by at least three points between the two extremes. That is what this computes, and
 * it accepts the textbook case (§3.8's formal definition: `LS → H` on a shoulder pattern, where
 * price *does* extend and RSI does not) as well as the spec's demonstrated case (`H → RS`, where
 * neither extends). §24 check 9 makes the worked fixtures normative, so neither is dropped. The
 * candlestick detectors keep `technical.isRsiDivergence`'s strict price-strict definition, because
 * their two pivots are consecutive bars and the price relation is meaningful there.
 *
 * @param want `Direction.DOWN` for the bearish divergence a top needs, `Direction.UP` for the
 *   bullish divergence a bottom needs (§3.8's direction-mapping table).
 */
internal fun ChartContext.divergenceGapRsi(first: Pivot, second: Pivot, want: Direction): Double {
    if (first.kind != second.kind) return 0.0
    if (second.barIndex - first.barIndex < ChartSpec.DIVERGENCE_MIN_BARS) return 0.0
    val earlierRsi = rsiAt(first.barIndex) ?: return 0.0
    val laterRsi = rsiAt(second.barIndex) ?: return 0.0
    val rsiExtends = laterRsi > earlierRsi
    val qualifies = if (want == Direction.DOWN) !rsiExtends else rsiExtends
    if (!qualifies) return 0.0
    val gap = abs(laterRsi - earlierRsi)
    return if (gap >= ChartSpec.DIVERGENCE_MIN_GAP_RSI) gap else 0.0
}

/**
 * The largest qualifying RSI gap over the same-role [pairs] **inside the formation**.
 *
 * §3.8 requires both pivots to be inside the pattern; detectors pass only pairs their role
 * assignment produced, which is what stops this measuring a divergence between two unrelated swings
 * months apart.
 */
internal fun ChartContext.bestDivergenceGapRsi(pairs: List<Pair<Pivot, Pivot>>, want: Direction): Double {
    var best = 0.0
    for ((first, second) in pairs) {
        val gap = divergenceGapRsi(first, second, want)
        if (gap > best) best = gap
    }
    return best
}

// ------------------------------------------------------------------------------- penalties

/** `WEAK_VOLUME` on the breakout bar (§12.3 `I2`, §14.3 `I2`, §16.3 `I2`, §18.3 `I2`). */
internal fun ChartContext.weakBreakoutVolume(breakoutBar: Int): Boolean {
    val rvol = rvolAt(breakoutBar) ?: return false
    return rvol < ChartSpec.RVOL_WEAK
}

/** `RANGING_REGIME` (§19): `adx < 20` at the trigger bar. */
internal fun ChartContext.isRanging(triggerBar: Int): Boolean {
    val value = adxAt(triggerBar) ?: return false
    return value < ChartSpec.ADX_RANGING
}

/** `STALE_PATTERN` (§16.3 `I5`, §18.3): no breakout within 10 bars of the last formation pivot. */
internal fun ChartContext.isStale(lastFormationBar: Int): Boolean =
    barCount - 1 - lastFormationBar > ChartSpec.STALE_BARS

// ----------------------------------------------------------------------- measure shortcuts

/**
 * `S1 residMax` (`scoring-spec.md` §4) as a decay measure: 100 at zero residual, 0 at
 * [PatternScoringPolicy.STRUCT_RESID_FLOOR] of the pattern's own scale.
 */
internal fun residMaxMeasure(value: Double, name: String = "S1 residMax"): Measure =
    Measure.decay(
        name,
        abs(value),
        25,
        ScoreFactor.STRUCTURE,
        0.0,
        PatternScoringPolicy.STRUCT_RESID_FLOOR,
    )

/**
 * `C3 follow-through` (`scoring-spec.md` §5.1): `1` when the next one or two closes continue in
 * the breakout direction, `0.5` when the series ends first, `0` when the next bar reversed.
 *
 * "Professionals wait for follow-through of the next one or two sessions"
 * `[Ch.18-5 §Confirmation §3]`.
 */
internal fun ChartContext.followThroughScore(breakoutBar: Int, direction: Direction): Double {
    val next = breakoutBar + 1
    if (next !in 0 until barCount) return 0.5
    val reference = candles[breakoutBar].close
    val continued = when (direction) {
        Direction.UP -> candles[next].close > reference
        Direction.DOWN -> candles[next].close < reference
    }
    if (!continued) return 0.0
    val second = breakoutBar + 2
    if (second !in 0 until barCount) return 0.75
    val held = when (direction) {
        Direction.UP -> candles[second].close > reference
        Direction.DOWN -> candles[second].close < reference
    }
    return if (held) 1.0 else 0.75
}

/**
 * `C4 retest held` (`scoring-spec.md` §5.1): `1` when price returned to the broken level and did
 * *not* close back through it, `0` when it did, `0.5` while no retest has happened yet.
 *
 * "Throwback … old resistance has become new support" `[Ch.18-5 §Throwbacks and Pullbacks]` — the
 * retest that holds is confirmation, which is exactly what a `1` means here.
 */
internal fun ChartContext.retestScore(
    line: BoundaryLine,
    breakoutBar: Int,
    direction: Direction,
    tolerance: Double,
): Double {
    if (isFailedRetest(line, breakoutBar, direction, tolerance)) return 0.0
    val window = minOf(breakoutBar + ChartSpec.FAILED_WINDOW_BARS, barCount - 1)
    if (breakoutBar >= window) return 0.5
    for (j in (breakoutBar + 1)..window) {
        val level = line.at(j)
        val close = candles[j].close
        val atLevel = abs(close - level) <= tolerance
        // Back on the original side of the broken level is a retest too, whether it stopped at the
        // level or blew straight through it.
        val beyondWrongWay = when (direction) {
            Direction.UP -> close < level
            Direction.DOWN -> close > level
        }
        if (atLevel || beyondWrongWay) return 1.0
    }
    return 0.5
}

/**
 * `C5 OBV` (`scoring-spec.md` §5.1): `1` when the 5-bar OBV slope agrees with the breakout
 * direction, `0.5` when it is flat or unavailable, `0` when it disagrees
 * `[Ch.22 §22.29]`.
 */
internal fun ChartContext.obvAgreementScore(triggerBar: Int, direction: Direction): Double {
    val slope = obvSlopeAt(triggerBar) ?: return 0.5
    return when {
        slope == 0.0 -> 0.5
        (slope > 0.0) == (direction == Direction.UP) -> 1.0
        else -> 0.0
    }
}

/**
 * C1–C7 of `scoring-spec.md` §5.1 for any chart pattern (6–12), against a boundary that moves.
 *
 * A `FORMING` pattern (no breakout bar yet) scores `C1 = 0`, `C2 = 0`, `C3 = 0` and `C4 = 0.5` —
 * §5.1's own "C1 at `FORMING`" rule — which is why a formation that has not triggered is capped
 * below `PROJECT` by DD-5 rather than by a fabricated confirmation.
 *
 * @param boundary the line the trigger is read from: a neckline, a channel boundary or, for a
 *   double top / bottom, the horizontal confirmation level.
 * @param divergenceGap the RSI gap from [divergenceGapRsi], already `0.0` when there is none.
 * @param atrAtTrigger how much of an extra [Measure] name to record; unused by the maths and kept
 *   out of the signature.
 */
internal fun chartConfirmationMeasures(
    context: ChartContext,
    boundary: BoundaryLine,
    breakoutBar: Int?,
    direction: Direction,
    divergenceGap: Double,
    volumeContraction: Double? = null,
): List<Measure> {
    val close = breakoutBar?.let { context.candles[it].close }
    val level = breakoutBar?.let { boundary.at(it) }
    val measures = ArrayList<Measure>(8)

    measures += Measure.saturating(
        "C1 breakoutDepth",
        if (close != null && level != null) breakoutDepthPct(close, level, direction) else 0.0,
        25, ScoreFactor.CONFIRMATION, PatternScoringPolicy.CONF_BREAKDEPTH_SAT,
    )
    measures += Measure.saturating(
        "C2 rvolBreakout",
        breakoutBar?.let { context.rvolAt(it) } ?: 0.0,
        25, ScoreFactor.CONFIRMATION, PatternScoringPolicy.CONF_RVOL_SAT,
    )
    measures += Measure.identity(
        "C3 followThrough",
        if (breakoutBar != null) context.followThroughScore(breakoutBar, direction) else 0.0,
        10, ScoreFactor.CONFIRMATION,
    )
    measures += Measure.identity(
        "C4 retestHeld",
        if (breakoutBar != null) {
            context.retestScore(
                boundary,
                breakoutBar,
                direction,
                context.touchToleranceAt(breakoutBar, level ?: 0.0),
            )
        } else {
            0.5
        },
        10, ScoreFactor.CONFIRMATION,
    )
    measures += Measure.identity(
        "C5 obvAgrees",
        if (breakoutBar != null) context.obvAgreementScore(breakoutBar, direction) else 0.5,
        10, ScoreFactor.CONFIRMATION,
    )
    // §5.1 C6: 0 — not neutral — when delivery is unavailable, so the UI must label it.
    measures += Measure.saturating(
        "C6 deliveryVsAvg",
        (breakoutBar?.let { context.deliveryVsAvgAt(it) } ?: 0.0).coerceAtLeast(0.0),
        10, ScoreFactor.CONFIRMATION, PatternScoringPolicy.CONF_DELIVERY_SAT,
    )
    measures += Measure.linear(
        "C7 divergenceGapRsi", divergenceGap.coerceAtLeast(0.0), 10, ScoreFactor.CONFIRMATION,
        0.0, PatternScoringPolicy.CONF_RSI_GAP_HI,
    )
    if (volumeContraction != null) {
        // §3.8 / §16.3 I4: continuation patterns additionally need volume to have contracted.
        measures += Measure.linear(
            "C8 volumeContraction", volumeContraction.coerceAtLeast(0.0), 10, ScoreFactor.CONFIRMATION,
            0.0, 1.0,
        )
    }
    return measures
}

/**
 * X1–X6 of `scoring-spec.md` §6, in the pattern's direction.
 *
 * **Two of the six cannot be computed from `PatternDetector.detect(pivots, candles)`** and are
 * therefore emitted as the spec's own "unknown" readings rather than as a measurement:
 *
 * * `X4 relativeStrength` needs a benchmark index series. §6 is explicit — "`relativeStrength` is
 *   `null` ⇒ `X4 = 0.5` (neutral), and the UI must not claim relative strength it did not compute".
 * * `X5 htfAgrees` needs the *other* timeframe's regime. §6 gives `0.5` for "unknown" and DD-9
 *   makes multi-timeframe checks a penalty rather than a gate, so an absent series reads unknown.
 *
 * `X6 marketBreadth` needs the same benchmark (§19.2) and is marked **inapplicable** instead: a
 * breadth reading the detector was never given is missing, not "not weak", and `scoring-spec.md`
 * §6.1 says an inapplicable measure's weight is redistributed rather than filled with a neutral.
 *
 * @param reversal `true` for detectors 6–9, which want ADX **low** ("a reversal is a failure of
 *   trend" §6 X2); `false` for continuation, which wants ADX high.
 * @param priorTrendPct the §3.6 prior-trend reading, passed as a **magnitude** because §6 X1
 *   scores it "in the pattern's direction".
 * @param priorTrendByConstruction `true` for the flag family, where §6 says "`X1 = 100`
 *   automatically — the pole *is* the prior trend" and §18.1 P3 says "Prior trend is satisfied **by
 *   construction**". The measure then reads exactly `1.0` instead of a ratio the detector has no
 *   way to compute.
 */
internal fun chartContextMeasures(
    context: ChartContext,
    priorTrendPct: Double,
    triggerBar: Int,
    direction: Direction,
    reversal: Boolean,
    priorTrendByConstruction: Boolean = false,
): List<Measure> {
    val adx = context.adxAt(triggerBar)
    val adxValue = if (reversal) {
        PatternScoringPolicy.CTX_ADX_HI - (adx ?: PatternScoringPolicy.CTX_ADX_HI)
    } else {
        adx ?: 0.0
    }
    return listOf(
        if (priorTrendByConstruction) {
            Measure.identity("X1 priorTrendPct (the pole)", 1.0, 35, ScoreFactor.CONTEXT)
        } else {
            Measure.linear(
                "X1 priorTrendPct", abs(priorTrendPct), 35, ScoreFactor.CONTEXT,
                PatternScoringPolicy.CTX_PRIOR_LO, PatternScoringPolicy.CTX_PRIOR_HI,
            )
        },
        Measure.linear(
            "X2 adxRegime", adxValue.coerceAtLeast(0.0), 25, ScoreFactor.CONTEXT,
            0.0, PatternScoringPolicy.CTX_ADX_LO,
        ),
        Measure.binary("X3 diAligned", context.diAlignedAt(triggerBar, direction) == true, 10, ScoreFactor.CONTEXT),
        Measure.identity("X4 relativeStrength", 0.5, 15, ScoreFactor.CONTEXT),
        Measure.identity("X5 htfAgrees", 0.5, 10, ScoreFactor.CONTEXT),
        Measure.notApplicable("X6 marketBreadth", ScoreFactor.CONTEXT),
    )
}

/** `S1 residMax` (`scoring-spec.md` §4) as a decay measure: 100 at zero residual, 0 at [PatternScoringPolicy.STRUCT_RESID_FLOOR]. */
internal fun boundaryStructureMeasures(
    context: ChartContext,
    residMax: Double,
    boundaryR2: Double,
    legs: List<Int>,
    legIdeal: Double,
    legTolerance: Double,
    spanBars: Int,
    spanIdeal: Double,
    touchCount: Int?,
    boundaryR2Applicable: Boolean = true,
): List<Measure> = listOf(
    residMaxMeasure(residMax),
    Measure.linear(
        "S2 trendlineFitRSquared", context.trendlineFitRSquared(), 20, ScoreFactor.STRUCTURE,
        PatternScoringPolicy.STRUCT_R2_LO, PatternScoringPolicy.STRUCT_R2_HI,
    ),
    if (boundaryR2Applicable) {
        Measure.linear(
            "S3 boundaryR2", boundaryR2, 15, ScoreFactor.STRUCTURE,
            PatternScoringPolicy.STRUCT_BOUNDARY_R2_LO, PatternScoringPolicy.STRUCT_BOUNDARY_R2_HI,
        )
    } else {
        Measure.notApplicable("S3 boundaryR2", ScoreFactor.STRUCTURE)
    },
    Measure.linear(
        "S4 spacingScore", spacingScore(legs, legIdeal, legTolerance), 15, ScoreFactor.STRUCTURE, 0.0, 1.0,
    ),
    Measure.linear(
        "S5 durationFit", if (spanIdeal > 0.0) spanBars / spanIdeal else 0.0, 15, ScoreFactor.STRUCTURE,
        PatternScoringPolicy.STRUCT_DUR_LO, PatternScoringPolicy.STRUCT_DUR_HI,
    ),
    if (touchCount == null) {
        Measure.notApplicable("S6 touchCount", ScoreFactor.STRUCTURE)
    } else {
        // `scoring-spec.md` §4 S6: `STEP(m, 3, 100, 2, 60)` — "the third touch validates it".
        Measure.linear(
            "S6 touchCount", if (touchCount >= 3) 1.0 else 0.6, 10, ScoreFactor.STRUCTURE, 0.0, 1.0,
        )
    },
)