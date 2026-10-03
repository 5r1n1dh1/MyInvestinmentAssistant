package com.mia.app.domain.pattern.detectors.candlestick

import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.Source
import com.mia.app.domain.model.Timeframe
import java.time.LocalDate

/**
 * The synthetic candle builder every candlestick test constructs its fixtures with.
 *
 * **Why it exists and why it is deliberately dumb.** A candlestick rule is a rule about *one bar's*
 * anatomy (`bodyFrac`, `lowerFrac`, …) sitting in a *specific* context window (`priorTrendPct` over
 * the previous 20 bars, `low` versus the window's own low). Writing those fixtures as
 * `List(60) { Candle(...) }` and hand-patching three indices produced fixtures that were wrong in
 * ways no assertion could see — two of the spec's own five candlestick fixtures had to be corrected
 * for exactly that reason (see `BullishEngulfingDetectorTest` and `HammerDetectorTest`).
 *
 * So the builder makes the three things a fixture has to get right impossible to fudge:
 *
 * 1. **Explicit bars** — every O/H/L/C/V is stated; nothing is generated behind the caller's back.
 * 2. **A context window placed by index** — [contextRamp] lays down exactly
 *    `contextLookback(timeframe)` bars *ending where the spec says*, ramping between the two closes
 *    the context test reads, so `priorTrendPct` is arithmetic rather than a hope.
 * 3. **Volume solved from the target `rvol`** — [volumeForRvol] returns the volume that makes a
 *    bar's `volume / volumeSma20` exactly the number the spec names (3.0 for "twice … three times
 *    the average" `[Ch.17-4 §Layer 3]`), instead of a test asserting `rvol ≈ 2.97`.
 *
 * Dates are synthetic consecutive days: every indicator in `:domain` is index-aligned, so the
 * calendar carries no meaning here and the real-data fixture keeps its own dates.
 */
class CandleSeries(
    private val symbol: String = "TEST",
    private val timeframe: Timeframe = Timeframe.D1,
    startDate: LocalDate = LocalDate.of(2026, 1, 1),
) {

    private val candles = ArrayList<Candle>()
    private var cursor: LocalDate = startDate

    /** Bars appended so far. */
    val size: Int get() = candles.size

    /** The last bar appended, or `null`. */
    val last: Candle? get() = candles.lastOrNull()

    /** Appends one bar with every price level stated. */
    fun bar(
        open: Double,
        high: Double,
        low: Double,
        close: Double,
        volume: Long = DEFAULT_VOLUME,
        deliveryPercent: Double? = null,
    ): CandleSeries = apply {
        require(high >= maxOf(open, close, low) && low <= minOf(open, close, high)) {
            "bar ${candles.size}: high=$high low=$low must bracket open=$open close=$close"
        }
        require(open > 0.0 && close > 0.0) { "bar ${candles.size}: prices must be positive" }
        candles += Candle(
            symbol = symbol,
            timeframe = timeframe,
            date = cursor,
            open = open,
            high = high,
            low = low,
            close = close,
            volume = volume,
            deliveryPercent = deliveryPercent,
            source = Source.NSE_BHAVCOPY,
        )
        cursor = cursor.plusDays(1)
    }

    /** A flat bar at [price]. */
    fun flat(price: Double, volume: Long = DEFAULT_VOLUME): CandleSeries =
        bar(price, price, price, price, volume)

    /** Appends [count] filler bars at [price]. */
    fun filler(count: Int, price: Double, volume: Long = DEFAULT_VOLUME): CandleSeries = apply {
        repeat(count) { flat(price, volume) }
    }

    /**
     * Appends [count] bars whose closes ramp linearly from [fromClose] to [toClose] inclusive.
     *
     * Only the **endpoint closes** matter to the detectors — they are what `priorTrendPct` reads —
     * and those are exact, so the context test is literally `(toClose − fromClose) / fromClose`.
     *
     * @param lastBarLow pins the **last** bar's low. The spec's context fixtures are stated as
     *   *"min low over the context window = X"*, and on a declining ramp that low belongs to the
     *   final bar, so one hook expresses it without an index arithmetic mistake.
     * @param lastBarHigh the mirror of [lastBarLow] for a rising ramp.
     */
    fun ramp(
        count: Int,
        fromClose: Double,
        toClose: Double,
        volume: Long = DEFAULT_VOLUME,
        bodySize: Double = 1.0,
        wickSize: Double = 0.60,
        deliveryPercent: Double? = null,
        lastBarLow: Double? = null,
        lastBarHigh: Double? = null,
    ): CandleSeries = apply {
        require(count >= 2) { "a ramp needs at least two bars to have endpoints" }
        val step = (toClose - fromClose) / (count - 1)
        for (i in 0 until count) {
            val close = fromClose + step * i
            val open = close + bodySize
            val low = if (i == count - 1 && lastBarLow != null) {
                lastBarLow
            } else {
                minOf(open, close) - wickSize
            }
            val high = if (i == count - 1 && lastBarHigh != null) {
                lastBarHigh
            } else {
                maxOf(open, close) + wickSize
            }
            bar(open, high, low, close, volume, deliveryPercent)
        }
    }

    /**
     * The context window for a signal bar at [signalBar]: exactly `contextLookback(timeframe)`
     * bars ending at `signalBar − formationGap`, ramping [fromClose] → [toClose].
     *
     * `formationGap` is `1` for a two-bar or one-bar pattern (window ends at `n−1` / `n−2` per
     * §22 **DD-11**) and `2` for the star (§10.1 **P2**, window ends at `n−3`).
     */
    fun contextRamp(
        signalBar: Int,
        fromClose: Double,
        toClose: Double,
        formationGap: Int,
        timeframe: Timeframe = this.timeframe,
        volume: Long = DEFAULT_VOLUME,
        deliveryPercent: Double? = null,
        lastBarLow: Double? = null,
        lastBarHigh: Double? = null,
    ): CandleSeries = apply {
        val lookback = if (timeframe == Timeframe.D1) 20 else 13
        val last = signalBar - formationGap
        val first = last - lookback + 1
        require(first >= 0) { "signalBar=$signalBar leaves no room for a $lookback-bar context" }
        require(size == first) { "context must start at bar $first but the series holds $size bars" }
        ramp(
            count = lookback,
            fromClose = fromClose,
            toClose = toClose,
            volume = volume,
            deliveryPercent = deliveryPercent,
            lastBarLow = lastBarLow,
            lastBarHigh = lastBarHigh,
        )
    }

    /**
     * The volume a bar must carry for its `volume / volumeSma20` to be exactly [targetRvol], given
     * the volumes of the other bars in the same 20-bar window.
     *
     * `volumeSma20[j]` is the mean of the 20 bars ending at `j` **inclusive**
     * (`domain/technical.smaArray`), so with the signal bar's own volume `v` and the sum `s` of
     * the other 19:
     *
     * ```
     * v / ((s + v) / 20) = target   ⇒   v = target · s / (20 − target)
     * ```
     */
    fun volumeForRvol(targetRvol: Double, otherVolumes: List<Long>): Long {
        require(targetRvol in 0.0..19.0) { "rvol $targetRvol cannot occur in a 20-bar mean" }
        val sum = otherVolumes.sum().toDouble()
        return Math.round(targetRvol * sum / (20.0 - targetRvol)).toLong().coerceAtLeast(1L)
    }

    /** The volume the next bar needs for [targetRvol], from this series' last 19 volumes. */
    fun nextVolumeForRvol(targetRvol: Double): Long {
        require(size >= 19) { "need 19 existing bars to solve a 20-bar mean, have $size" }
        return volumeForRvol(targetRvol, candles.takeLast(19).map { it.volume })
    }

    /** The immutable series. */
    fun build(): List<Candle> = candles.toList()

    companion object {
        /** Ordinary turnover for a filler bar; the specs' own volumes run 1–5 M shares. */
        const val DEFAULT_VOLUME: Long = 1_000_000L
    }
}

/**
 * Runs [detector] over [candles] and returns its matches.
 *
 * Candlestick detectors read no pivots (§4 rule 7), so an empty pivot list is exactly what the
 * engine would hand them for a series that produces none — and asserting the result is identical
 * with a non-empty one is what proves rule 7 is honoured.
 */
internal fun detectOn(
    detector: com.mia.app.domain.model.PatternDetector,
    candles: List<Candle>,
) = detector.detect(emptyList(), candles)