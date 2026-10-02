package com.mia.app.domain.technical

import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.Direction
import java.time.LocalDate

/**
 * Trend strength: Wilder's ADX family, the trend-regime gate, and relative strength versus a
 * benchmark.
 *
 * plan.md §6.4 makes `regimeAligned` a **gate** on the PROJECT verdict, and §6.3 scores
 * "Context" (15%) from "ADX regime alignment, relative strength vs index". That makes ADX the
 * single most load-bearing number in the pattern pipeline, so its direction is resolved once
 * in [TechnicalAdx.regime] rather than re-derived by each caller.
 */

/** Trend regime at one bar, from ADX plus the sign of +DI − −DI. */
enum class TrendRegime {
    /** ADX ≥ 25 with +DI > −DI: an uptrend is present. */
    TRENDING_UP,

    /** ADX ≥ 25 with +DI < −DI: a downtrend is present. */
    TRENDING_DOWN,

    /** ADX < 20: no directional trend worth projecting through. */
    RANGING,

    /** Not enough history, or ADX between 20 and 25 (undecided). Never guess. */
    UNKNOWN,
    ;

    val isTrending: Boolean get() = this == TRENDING_UP || this == TRENDING_DOWN
}

/**
 * Full Wilder ADX family, aligned to `candles`.
 *
 * The alignment is the point of making this a data class rather than four loose lists: the
 * first non-null index is `period` for `diPlus`/`diMinus`/`dx` and `3*period - 3` for [adx],
 * which no caller should have to re-derive. See [TechnicalAdx.adxWarmupBars].
 */
data class TechnicalAdx(
    val period: Int,
    val adx: List<Double?>,
    val diPlus: List<Double?>,
    val diMinus: List<Double?>,
    val dx: List<Double?>,
    val regime: List<TrendRegime>,
) {
    val value: Double? get() = adx.lastOrNull()
    val diPlusValue: Double? get() = diPlus.lastOrNull()
    val diMinusValue: Double? get() = diMinus.lastOrNull()
    val dxValue: Double? get() = dx.lastOrNull()
    val latestRegime: TrendRegime get() = regime.lastOrNull() ?: TrendRegime.UNKNOWN

    /**
     * Number of leading bars that must be `null` in an ADX family computed at `period`.
     *
     * Wilder seeds the smoothed TR/+DM/−DM averages over bars `1..period`, so the first bar at
     * which a *new* smoothed value exists — and therefore the first DI and DX — is `period + 1`.
     * The ADX is then the smoothed average of DX, which needs `period` DX readings, so the first
     * ADX lands at `(period + 1) + period - 1 = 2 * period`.
     *
     * For the default `period = 14` that is **28**, i.e. an ADX panel needs 29 bars before it
     * draws anything.
     */
    fun adxWarmupBars(): Int = if (period <= 0) Int.MAX_VALUE else 2 * period

    /**
     * Directional-trend alignment, the plan.md §6.4 gate.
     *
     * `false` when the market is definitively trending the *other* way, and `null` when the
     * regime is [TrendRegime.UNKNOWN] — an undecided market may still RENDER a pattern, it may
     * never PROJECT one.
     */
    fun isRegimeAligned(direction: Direction): Boolean? {
        val wanted = when (direction) {
            Direction.UP -> TrendRegime.TRENDING_UP
            Direction.DOWN -> TrendRegime.TRENDING_DOWN
        }
        return when (latestRegime) {
            TrendRegime.TRENDING_UP, TrendRegime.TRENDING_DOWN, TrendRegime.RANGING ->
                latestRegime == wanted
            TrendRegime.UNKNOWN -> null
        }
    }
}

/** The single implementation of the DMI/ADX maths, over the arrays from [rangeArrays]. */
internal fun adxFromRaw(ranges: RangeArrays, period: Int): TechnicalAdx {
    val size = ranges.size
    val dxOut = MutableList<Double?>(size) { null }
    val plusOut = MutableList<Double?>(size) { null }
    val minusOut = MutableList<Double?>(size) { null }
    val adxOut = MutableList<Double?>(size) { null }

    if (period <= 0 || size < period + 1) {
        return TechnicalAdx(
            period = period,
            adx = adxOut,
            diPlus = plusOut,
            diMinus = minusOut,
            dx = dxOut,
            regime = MutableList(size) { TrendRegime.UNKNOWN },
        )
    }

    // Seed over bars 1..period: bar 0 has no previous close, so its +DM/-DM are 0 by
    // definition and including it would bias the seed.
    var smoothedTr = 0.0
    var smoothedPlus = 0.0
    var smoothedMinus = 0.0
    for (i in 1..period) {
        smoothedTr += ranges.tr[i]
        smoothedPlus += ranges.plusDm[i]
        smoothedMinus += ranges.minusDm[i]
    }

    var firstDxIndex = -1
    for (i in (period + 1) until size) {
        smoothedTr = smoothedTr - smoothedTr / period + ranges.tr[i]
        smoothedPlus = smoothedPlus - smoothedPlus / period + ranges.plusDm[i]
        smoothedMinus = smoothedMinus - smoothedMinus / period + ranges.minusDm[i]
        // Zero smoothed TR means a perfectly flat, directionless bar: there is no directional
        // information to report, so DI/DX/ADX stay null there rather than asserting 0.
        if (smoothedTr == 0.0) continue
        val diPlus = 100.0 * smoothedPlus / smoothedTr
        val diMinus = 100.0 * smoothedMinus / smoothedTr
        val diSum = diPlus + diMinus
        val dx = if (diSum == 0.0) 0.0 else 100.0 * Math.abs(diPlus - diMinus) / diSum
        plusOut[i] = diPlus
        minusOut[i] = diMinus
        dxOut[i] = dx
        if (firstDxIndex < 0) firstDxIndex = i
    }

    if (firstDxIndex >= 0 && size - firstDxIndex >= period) {
        var dxSum = 0.0
        for (i in firstDxIndex until (firstDxIndex + period)) dxSum += dxOut[i] ?: 0.0
        var prev = dxSum / period
        val firstAdx = firstDxIndex + period - 1
        adxOut[firstAdx] = prev
        for (i in (firstAdx + 1) until size) {
            val dx = dxOut[i] ?: continue
            prev = (prev * (period - 1) + dx) / period
            adxOut[i] = prev
        }
    }

    val regimes = MutableList(size) { TrendRegime.UNKNOWN }
    for (i in 0 until size) {
        regimes[i] = regime(adxOut[i], plusOut[i], minusOut[i])
    }
    return TechnicalAdx(period, adxOut, plusOut, minusOut, dxOut, regimes)
}

/**
 * Wilder's Average Directional Index and its +DI / −DI / DX components.
 *
 * ```
 * seed (bars 1..period):   smoothedTR = Σ TR, same for the smoothed +DM / −DM
 * thereafter:              smoothedX  = smoothedX - smoothedX/period + X[i]
 * i > period:              DI+ = 100 * smoothedPlusDM / smoothedTR
 *                          DI- = 100 * smoothedMinusDM / smoothedTR
 *                          DX  = 100 * |DI+ - DI-| / (DI+ + DI-)
 * first ADX:               mean of the first `period` DX readings
 * thereafter:              ADX = (ADX * (period - 1) + DX[i]) / period
 * ```
 *
 * All four series are guaranteed to lie in `[0, 100]` wherever they are non-null.
 */
fun adx(candles: List<Candle>, period: Int = TechnicalPolicy.ADX_PERIOD): TechnicalAdx =
    adxFromRaw(rangeArrays(candles), period)

/**
 * Regime from ADX alone, using the conventional Wilder bands.
 *
 * `ADX >= 25` → trending, `ADX < 20` → ranging, and the 20–25 no-man's land is
 * [TrendRegime.UNKNOWN] because claiming a direction there is exactly the kind of confident
 * guess the product exists to avoid.
 */
fun regime(adxValue: Double?): TrendRegime = regime(adxValue, null, null)

/**
 * Regime from the full ADX family — the signature [TechnicalAdx] itself uses, so the chart and
 * the §6.4 gate can never disagree about direction.
 *
 * Direction comes from the sign of `+DI − −DI`, Wilder's own direction rule. A trending ADX
 * with `+DI == −DI` is [TrendRegime.UNKNOWN].
 */
fun regime(adxValue: Double?, diPlusValue: Double?, diMinusValue: Double?): TrendRegime {
    val a = adxValue ?: return TrendRegime.UNKNOWN
    if (a.isNaN()) return TrendRegime.UNKNOWN
    return when {
        a >= TechnicalPolicy.ADX_TRENDING -> when {
            diPlusValue == null || diMinusValue == null -> TrendRegime.UNKNOWN
            diPlusValue > diMinusValue -> TrendRegime.TRENDING_UP
            diPlusValue < diMinusValue -> TrendRegime.TRENDING_DOWN
            else -> TrendRegime.UNKNOWN
        }

        a < TechnicalPolicy.ADX_RANGING -> TrendRegime.RANGING
        else -> TrendRegime.UNKNOWN
    }
}

// --------------------------------------------------------------- relative strength

/**
 * Relative strength against a benchmark, both aligned by **date**.
 *
 * The RS *ratio* is `stockClose / benchmarkClose` at the same date — the definition used to
 * compare a stock to NIFTY. It is deliberately **not** the ratio of two different-period moving
 * averages: that shortcut needs a second benchmark history and it smears the actual price
 * relationship the user is looking at on the chart.
 *
 * Robustness: the two lists come from two different sources and may not overlap at all, may
 * contain duplicate dates, or may be unsorted. Alignment intersects the date sets, sorts them,
 * and keeps the **first** occurrence of a duplicated date. Bars present in one list but not the
 * other are simply absent from the output — never an exception, and never a silently
 * different-length series.
 */
data class RelativeStrengthResult(
    /** Dates on which *both* series had a bar, ascending. */
    val alignedDates: List<LocalDate>,

    /** `stockClose / benchmarkClose` per aligned date. */
    val rsLine: List<Double?>,

    /** `stock / benchmark` rebased to 1.0 at the first aligned date. */
    val normalisedRsLine: List<Double?>,
) {
    val latestRsRatio: Double? get() = rsLine.lastOrNull()

    /** Latest rebased reading; 1.0 means the stock has exactly tracked the benchmark. */
    val latestNormalisedRs: Double? get() = normalisedRsLine.lastOrNull()
}

/**
 * Align [candles] to [benchmarkCandles] by date and build the relative-strength line.
 *
 * Neither list needs to be sorted; the output always is. An empty intersection gives empty
 * outputs, which is what a symbol with no overlapping history should produce.
 */
fun relativeStrength(
    candles: List<Candle>,
    benchmarkCandles: List<Candle>,
): RelativeStrengthResult {
    val benchmark = HashMap<LocalDate, Double>()
    for (c in benchmarkCandles) {
        if (isUsableClose(c.close)) benchmark.putIfAbsent(c.date, c.close)
    }
    val stock = HashMap<LocalDate, Double>()
    for (c in candles) {
        if (isUsableClose(c.close)) stock.putIfAbsent(c.date, c.close)
    }

    val dates = stock.keys.intersect(benchmark.keys).sorted()
    if (dates.isEmpty()) return RelativeStrengthResult(emptyList(), emptyList(), emptyList())

    val rs = DoubleArray(dates.size)
    for ((i, date) in dates.withIndex()) rs[i] = stock.getValue(date) / benchmark.getValue(date)

    // rs is > 0 by construction (both closes are positive), so the rebasing base is safe.
    val base = rs[0]
    val normalised = DoubleArray(dates.size) { rs[it] / base }
    return RelativeStrengthResult(dates, rs.toList(), normalised.toList())
}

private fun isUsableClose(close: Double): Boolean = close > 0.0 && !close.isNaN() && !close.isInfinite()

/**
 * Relative-strength change over a trailing window: the difference between the latest and the
 * `lookback`-bars-ago reading of [RelativeStrengthResult.normalisedRsLine].
 *
 * Positive means the stock has outperformed the benchmark over the window — the "relative
 * strength vs index" input to the §6.3 Context score. `null` when the window is not computable.
 */
fun relativeStrengthTrend(
    result: RelativeStrengthResult,
    lookback: Int = TechnicalPolicy.SMA_MID,
): Double? {
    if (lookback <= 0) return null
    val series = result.normalisedRsLine
    if (series.size < lookback + 1) return null
    val from = series[series.size - 1 - lookback] ?: return null
    val to = series.last() ?: return null
    return to - from
}