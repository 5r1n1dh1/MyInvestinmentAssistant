package com.mia.app.domain.technical

import com.mia.app.domain.model.Candle

/**
 * Momentum oscillators: RSI and MACD (plan.md §8.2 technical red flags, §6.3 "Confirmation:
 * RSI divergence").
 *
 * Both use Wilder smoothing, which is not an implementation detail — the textbook RSI uses
 * `alpha = 1/period`, not `2/(period+1)`, and switching between them moves a 14-period RSI by
 * several points near the extremes. plan.md §8.2 fires a red flag at RSI > 70, so the
 * smoothing has to be the one users see on their charts.
 */

// ------------------------------------------------------------------------------- RSI

/**
 * Relative Strength Index as a multi-series result rather than a bare `List<Double?>`,
 * because the pattern scorer needs the **series** to detect a divergence between two swing
 * lows while the technicals tab and the red-flag engine only need [value].
 *
 * All lists are aligned to `candles` (index 0 = oldest bar).
 */
data class RsiResult(
    /** RSI, `null` inside the warm-up window. Always in `[0, 100]` where non-null. */
    val rsi: List<Double?>,

    /** Wilder's average gain, `null` inside the warm-up window. */
    val averageGain: List<Double?>,

    /** Wilder's average loss, `null` inside the warm-up window. */
    val averageLoss: List<Double?>,
) {
    /** Latest RSI, or `null` when there is not enough history. */
    val value: Double? get() = rsi.lastOrNull()

    /** True once RSI is above [TechnicalPolicy.RSI_OVERBOUGHT]. */
    val isOverbought: Boolean
        get() {
            val v = value ?: return false
            return v > TechnicalPolicy.RSI_OVERBOUGHT
        }

    /** True once RSI is below [TechnicalPolicy.RSI_OVERSOLD]. */
    val isOversold: Boolean
        get() {
            val v = value ?: return false
            return v < TechnicalPolicy.RSI_OVERSOLD
        }
}

/**
 * Number of leading bars Wilder's RSI cannot fill: `period`, because the first RSI needs
 * `period` close-to-close changes, which exist only from index `period`.
 *
 * Returned as `Int.MAX_VALUE` for a non-positive period (a fully null series), so
 * `index < rsiWarmupBars(period)` holds everywhere rather than nowhere.
 */
fun rsiWarmupBars(period: Int): Int = if (period <= 0) Int.MAX_VALUE else period

/**
 * Wilder's RSI over a raw close array.
 *
 * ```
 * change[i] = close[i] - close[i-1]
 * seed  at i = period:  avgGain = Σ max(change, 0) / period
 *                        avgLoss = Σ max(-change, 0) / period
 * thereafter:           avgGain = (avgGain * (period - 1) + gain) / period   (alpha = 1/period)
 *                        avgLoss = (avgLoss * (period - 1) + loss) / period
 * RSI = avgLoss == 0 ? 100 : 100 - 100 / (1 + avgGain / avgLoss)
 * ```
 *
 * **Degenerate cases** — all deliberate, all documented because they change what a number
 * means:
 * * `avgLoss == 0` → RSI = **100**. The textbook convention: a run of consecutive up-closes
 *   has no losses at all, so the formula's `x/0` limit is +∞ and RSI saturates at 100. Chosen
 *   over `null` because a rising chart is not *missing* data — it genuinely is at maximum
 *   momentum, and holes in an RSI line look broken on a chart.
 * * `avgGain == 0` with `avgLoss > 0` → RSI = **0**, the symmetric limit.
 * * Both zero (a perfectly flat series) → 0 by the first branch. Documented as a convention
 *   artefact; no scorer acts on RSI at exactly 0.
 *
 * RSI is defined from index `period` onward, so `rsi[0 .. period-1]` are `null`.
 */
internal fun rsiFromCloses(closes: DoubleArray, period: Int): RsiResult {
    val size = closes.size
    val rsiOut = MutableList<Double?>(size) { null }
    val gainOut = MutableList<Double?>(size) { null }
    val lossOut = MutableList<Double?>(size) { null }
    if (period <= 0 || size < period + 1) return RsiResult(rsiOut, gainOut, lossOut)

    var gainSum = 0.0
    var lossSum = 0.0
    for (i in 1..period) {
        val change = closes[i] - closes[i - 1]
        if (change >= 0) gainSum += change else lossSum -= change
    }
    var avgGain = gainSum / period
    var avgLoss = lossSum / period

    fun emit(index: Int) {
        gainOut[index] = avgGain
        lossOut[index] = avgLoss
        rsiOut[index] = when {
            avgLoss == 0.0 -> 100.0
            avgGain == 0.0 -> 0.0
            else -> 100.0 - 100.0 / (1.0 + avgGain / avgLoss)
        }
    }

    emit(period)
    for (i in (period + 1) until size) {
        val change = closes[i] - closes[i - 1]
        val gain = if (change > 0) change else 0.0
        val loss = if (change < 0) -change else 0.0
        avgGain = (avgGain * (period - 1) + gain) / period
        avgLoss = (avgLoss * (period - 1) + loss) / period
        emit(i)
    }
    return RsiResult(rsiOut, gainOut, lossOut)
}

/** Wilder's RSI over candles. See [rsiFromCloses] for the maths and the degenerate cases. */
fun rsi(candles: List<Candle>, period: Int = TechnicalPolicy.RSI_PERIOD): RsiResult =
    rsiFromCloses(closesOf(candles), period)

/** Wilder's RSI over a close series. See [rsiFromCloses]. */
@JvmName("rsiOfValues")
fun rsi(values: List<Double>, period: Int = TechnicalPolicy.RSI_PERIOD): RsiResult =
    rsiFromCloses(values.toDoubleArray(), period)

/** Alias for [rsi] over a close series. */
fun rsiOf(values: List<Double>, period: Int = TechnicalPolicy.RSI_PERIOD): RsiResult =
    rsi(values, period)

// ------------------------------------------------------------------------------ MACD

/**
 * MACD (Gerald Appel, 1979) as three aligned series.
 *
 * [macd] and [signalLine] are `null` wherever their own EMAs are still warming up. Because
 * the EMAs are SMA-seeded at different lengths, the MACD line starts at `slow - 1` — later
 * than either EMA — and the signal line starts a further `signal - 1` bars after that.
 *
 * The MACD **line** is an unbounded difference of two price EMAs. It is deliberately not
 * clamped to `[0, 100]`: it is not a bounded oscillator and any upper bound would be invented.
 */
data class MacdResult(
    val macd: List<Double?>,
    val signalLine: List<Double?>,
    val histogram: List<Double?>,
) {
    val value: Double? get() = macd.lastOrNull()
    val signal: Double? get() = signalLine.lastOrNull()
    val histogramValue: Double? get() = histogram.lastOrNull()

    /** True when the MACD line is above its signal line at the latest bar. */
    val isBullishCrossover: Boolean
        get() {
            val m = value ?: return false
            val s = signal ?: return false
            return m > s
        }
}

/**
 * MACD from pre-computed fast and slow EMA series — the single implementation of the line,
 * signal and histogram maths.
 *
 * The signal EMA is seeded with the SMA of the first `signal` **non-null** MACD values, i.e.
 * of the first computable MACD values, not of values that do not exist yet.
 */
internal fun macdFromEmas(
    fastEma: List<Double?>,
    slowEma: List<Double?>,
    signal: Int,
): MacdResult {
    val size = minOf(fastEma.size, slowEma.size)
    val macdOut = MutableList<Double?>(size) { null }
    val signalOut = MutableList<Double?>(size) { null }
    val histogramOut = MutableList<Double?>(size) { null }
    if (signal <= 0) return MacdResult(macdOut, signalOut, histogramOut)

    val raw = DoubleArray(size)
    var first = size
    for (i in 0 until size) {
        val f = fastEma[i] ?: continue
        val s = slowEma[i] ?: continue
        raw[i] = f - s
        macdOut[i] = raw[i]
        if (first == size) first = i
    }
    if (first == size || size - first < signal) return MacdResult(macdOut, signalOut, histogramOut)

    var seed = 0.0
    for (i in first until (first + signal)) seed += raw[i]
    var prev = seed / signal
    val alpha = 2.0 / (signal + 1.0)
    val seedIndex = first + signal - 1
    signalOut[seedIndex] = prev
    histogramOut[seedIndex] = raw[seedIndex] - prev
    for (i in (seedIndex + 1) until size) {
        prev = alpha * raw[i] + (1.0 - alpha) * prev
        signalOut[i] = prev
        histogramOut[i] = raw[i] - prev
    }
    return MacdResult(macdOut, signalOut, histogramOut)
}

/**
 * `MACD = EMA(fast) - EMA(slow)`, `signal = EMA(signal)` of the MACD line,
 * `histogram = MACD - signal`.
 */
fun macd(
    candles: List<Candle>,
    fast: Int = TechnicalPolicy.MACD_FAST,
    slow: Int = TechnicalPolicy.MACD_SLOW,
    signal: Int = TechnicalPolicy.MACD_SIGNAL,
): MacdResult {
    val size = candles.size
    if (fast <= 0 || slow <= 0 || signal <= 0 || fast >= slow) {
        val empty = MutableList<Double?>(size) { null }
        return MacdResult(empty, List(size) { null }, List(size) { null })
    }
    val closes = closesOf(candles)
    return macdOf(closes, fast, slow, signal)
}

/** MACD over a raw close array. See [macd]. */
internal fun macdOf(
    closes: DoubleArray,
    fast: Int,
    slow: Int,
    signal: Int,
): MacdResult {
    val size = closes.size
    if (fast <= 0 || slow <= 0 || signal <= 0 || fast >= slow) {
        val empty = MutableList<Double?>(size) { null }
        return MacdResult(empty, List(size) { null }, List(size) { null })
    }
    return macdFromEmas(emaArray(closes, fast), emaArray(closes, slow), signal)
}

/**
 * RSI-style bullish/bearish divergence between two pivots — plan.md §6.3 lists "RSI
 * divergence" as a Confirmation input, and this is the only place the rule is defined.
 *
 * * Bullish: price makes a **lower** low while RSI makes a **higher** low.
 * * Bearish: price makes a **higher** high while RSI makes a **lower** high.
 *
 * Strict inequalities on both legs: a flat price or a flat RSI is not a divergence. `false`
 * when either RSI read is inside its warm-up window.
 */
fun isRsiDivergence(
    rsiSeries: List<Double?>,
    earlierIndex: Int,
    laterIndex: Int,
    earlierPrice: Double,
    laterPrice: Double,
): Boolean {
    if (earlierIndex !in rsiSeries.indices || laterIndex !in rsiSeries.indices) return false
    val earlierRsi = rsiSeries[earlierIndex] ?: return false
    val laterRsi = rsiSeries[laterIndex] ?: return false
    return when {
        laterPrice < earlierPrice && laterRsi > earlierRsi -> true
        laterPrice > earlierPrice && laterRsi < earlierRsi -> true
        else -> false
    }
}