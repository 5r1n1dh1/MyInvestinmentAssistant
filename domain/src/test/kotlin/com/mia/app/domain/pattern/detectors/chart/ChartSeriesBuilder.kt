package com.mia.app.domain.pattern.detectors.chart

import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.Pivot
import com.mia.app.domain.model.PivotKind
import com.mia.app.domain.model.Source
import com.mia.app.domain.model.Timeframe
import java.time.LocalDate

/**
 * A fluent builder for the synthetic series these tests detect on.
 *
 * **Why hand-build the candles at all.** `patterns-spec.md` prints its worked fixtures as *pivot
 * tables* with exact bar indices and prices (§12.7, §13, §14.7, §15, §16.7, §17.7, §18.7) plus a
 * handful of context closes. A detector reads a pivot list and a candle list, so a fixture that only
 * encoded pivots could not be asserted against a `breakoutDepthPct`, an `rvol`, a divergence gap or
 * a measured move that depends on a close. This builder therefore writes **both**: the pivots exactly
 * as printed, and a candle series whose highs, lows and closes at the printed bars make those pivots
 * and context readings real.
 *
 * **Design rules that keep the fixtures honest.**
 *
 * 1. **Prices are explicit.** Nothing is generated from a seed or a formula; every waypoint price is
 *    written down by the test, so a failing assertion points at one number.
 * 2. **A pivot bar is a real pivot bar.** `pivot(bar, price, kind)` sets that bar's `high`/`low` to
 *    `price` and its close to `price`, and records the `Pivot` — so `Match.pivots` can only ever be
 *    compared against pivots whose price is also that bar's extreme.
 * 3. **Closes can be overridden.** `closeAt(bar, price)` exists for the spec's context readings
 *    (`close[38] = 1010.00` in §12.7, `close[10] = 780.00` in §16.7), which the interpolated path
 *    would otherwise not hit.
 * 4. **Volume and delivery are explicit.** `volumeAt` / `deliveryAt` / `deliveryEvery` make
 *    `rvol`, `deliveryVsAvg` and `volumeContraction` reproducible rather than incidental.
 * 5. **One builder per fixture.** [build] returns a fresh [BuiltSeries]; the builder is not reusable,
 *    so two tests cannot see each other's overrides.
 */
internal class ChartSeriesBuilder(
    private val bars: Int,
    private val timeframe: Timeframe = Timeframe.D1,
    private val startDate: LocalDate = LocalDate.of(2025, 1, 1),
    private val symbol: String = "TEST",
) {

    /** A waypoint: the price the interpolated path passes through at [bar]. */
    private data class Waypoint(val bar: Int, val price: Double)

    private val waypoints = ArrayList<Waypoint>()
    private val pivotSpecs = ArrayList<Triple<Int, Double, PivotKind>>()
    private val closeOverrides = HashMap<Int, Double>()
    private val volumes = HashMap<Int, Long>()
    private val deliveries = HashMap<Int, Double>()

    /** A waypoint the interpolated path passes through, without recording it as a pivot. */
    fun at(bar: Int, price: Double): ChartSeriesBuilder = apply { waypoints += Waypoint(bar, price) }

    /** A swing pivot: this bar's high (or low) **is** [price], and its close is [price]. */
    fun pivot(bar: Int, price: Double, kind: PivotKind): ChartSeriesBuilder = apply {
        require(bar in 0 until bars) { "bar $bar is outside 0..${bars - 1}" }
        pivotSpecs += Triple(bar, price, kind)
        waypoints += Waypoint(bar, price)
        closeOverrides[bar] = price
    }

    /** Overrides one bar's close — the spec's context readings live here. */
    fun closeAt(bar: Int, price: Double): ChartSeriesBuilder = apply {
        require(bar in 0 until bars) { "bar $bar is outside 0..${bars - 1}" }
        closeOverrides[bar] = price
    }

    /** Overrides a bar's high (never below its close, so the bar stays structurally valid). */
    fun highAt(bar: Int, price: Double): ChartSeriesBuilder = apply {
        require(bar in 0 until bars) { "bar $bar is outside 0..${bars - 1}" }
        highOverrides[bar] = price
    }

    /** Overrides a bar's low (never above its close). */
    fun lowAt(bar: Int, price: Double): ChartSeriesBuilder = apply {
        require(bar in 0 until bars) { "bar $bar is outside 0..${bars - 1}" }
        lowOverrides[bar] = price
    }

    private val highOverrides = HashMap<Int, Double>()
    private val lowOverrides = HashMap<Int, Double>()

    /** Trades this bar's volume. The default is [DEFAULT_VOLUME] everywhere it is not set. */
    fun volumeAt(bar: Int, volume: Long): ChartSeriesBuilder = apply {
        require(bar in 0 until bars) { "bar $bar is outside 0..${bars - 1}" }
        volumes[bar] = volume
    }

    /** Sets the volume on a contiguous block of bars — the flag/triangle formation windows. */
    fun volumes(from: Int, to: Int, volume: Long): ChartSeriesBuilder = apply {
        for (b in from..to) volumes[b] = volume
    }

    /** This bar's delivery percentage. */
    fun deliveryAt(bar: Int, percent: Double): ChartSeriesBuilder = apply {
        require(bar in 0 until bars) { "bar $bar is outside 0..${bars - 1}" }
        deliveries[bar] = percent
    }

    /** The same delivery percentage on every bar that has no explicit override. */
    fun deliveryEvery(percent: Double): ChartSeriesBuilder = apply {
        for (b in 0 until bars) deliveries.putIfAbsent(b, percent)
    }

    /** Builds the candles and the matching pivot list. */
    fun build(): BuiltSeries {
        val sorted = waypoints.sortedBy { it.bar }
        val prices = interpolate(sorted)
        for ((bar, price) in closeOverrides) prices[bar] = price

        val candles = ArrayList<Candle>(bars)
        for (b in 0 until bars) {
            val close = prices[b]
            val base = closeOverrides[b] ?: close
            val high = highOverrides[b] ?: maxOf(base, close) * (1.0 + WIGGLE)
            val low = lowOverrides[b] ?: minOf(base, close) * (1.0 - WIGGLE)
            candles += Candle(
                symbol = symbol,
                timeframe = timeframe,
                date = startDate.plusDays(calendarStep(timeframe) * b.toLong()),
                open = base,
                high = high,
                low = low,
                close = close,
                volume = volumes[b] ?: DEFAULT_VOLUME,
                deliveryPercent = deliveries[b],
                source = Source.NSE_BHAVCOPY,
            )
        }

        // A pivot bar's high (or low) must be exactly the pivot price, and its close must sit on the
        // right side of the bar, so the candle and the pivot can never disagree.
        for ((bar, price, kind) in pivotSpecs) {
            val index = bar
            val existing = candles[index]
            candles[index] = when (kind) {
                PivotKind.HIGH -> existing.copy(high = price, low = minOf(existing.low, price), open = price, close = price)
                PivotKind.LOW -> existing.copy(low = price, high = maxOf(existing.high, price), open = price, close = price)
            }
        }

        val pivots = pivotSpecs
            .sortedBy { it.first }
            .mapIndexed { index, (bar, price, kind) ->
                Pivot(barIndex = bar, date = candles[bar].date, price = price, kind = kind, legIndex = index)
            }

        return BuiltSeries(candles, pivots)
    }

    private fun calendarStep(tf: Timeframe): Int = when (tf) {
        Timeframe.D1 -> 1
        Timeframe.W1 -> 7
    }

    /**
     * The close path: **linear** between consecutive waypoints, flat before the first and after the
     * last.
     *
     * Linear rather than stepped because a detector's indicators (RSI, ADX, OBV) read the whole
     * series: a stepped path would produce a run of identical closes between two pivots, and RSI
     * would then read `100` on every flat-up bar and `0` on every flat-down bar — an artefact of the
     * fixture rather than a reading of the pattern.
     */
    private fun interpolate(sorted: List<Waypoint>): DoubleArray {
        val out = DoubleArray(bars)
        if (sorted.isEmpty()) return out
        // Flat before the first waypoint.
        val first = sorted.first()
        for (b in 0..minOf(first.bar, bars - 1)) out[b] = first.price
        // Linear between consecutive waypoints. Two waypoints on the same bar (`at(136, x)` plus
        // `pivot(136, x)`, which the fixtures do write) span zero bars and are skipped rather than
        // dividing by zero.
        for (i in 0 until sorted.size - 1) {
            val from = sorted[i]
            val to = sorted[i + 1]
            val span = to.bar - from.bar
            if (span <= 0) continue
            for (b in maxOf(0, from.bar) until minOf(to.bar + 1, bars)) {
                out[b] = from.price + (to.price - from.price) * (b - from.bar) / span
            }
        }
        // Flat after the last waypoint.
        val last = sorted.last()
        for (b in maxOf(0, last.bar) until bars) out[b] = last.price
        return out
    }

    companion object {
        /** Baseline volume where a fixture says nothing, so `rvol` is 1.0 by default. */
        const val DEFAULT_VOLUME: Long = 1_000_000L

        private const val DEFAULT_PRICE: Double = 100.0

        /** How far a non-pivot bar's high/low sits from its close, as a fraction of the close. */
        private const val WIGGLE: Double = 0.004
    }
}

/** A built fixture: the candles plus the pivots the detector should find. */
internal data class BuiltSeries(
    val candles: List<Candle>,
    val pivots: List<Pivot>,
)

/** The price of [bar], for asserting a context reading the spec states as a number. */
internal fun BuiltSeries.closeAt(bar: Int): Double = candles[bar].close

/** Volume ratio against the trailing 20-bar mean, recomputed independently of the detector. */
internal fun BuiltSeries.rvolAt(bar: Int): Double? {
    val from = maxOf(0, bar - 19)
    var sum = 0.0
    var count = 0
    for (j in from..bar) {
        sum += candles[j].volume
        count++
    }
    if (count == 0 || sum == 0.0) return null
    return candles[bar].volume / (sum / count)
}

// ------------------------------------------------------------------ §18.7 flag fixtures

/**
 * `patterns-spec.md` §18.7's accepted **bull** flag, and the base for the near-miss, false-breakout
 * and failed-retest variants that `FlagDetectorTest` varies by name.
 *
 * | role | bar | price | kind |
 * |---|---|---|---|
 * | `B`  | 20 | 500 | LOW |
 * | `T`  | 44 | 620 | HIGH |
 * | `F₁` | 50 | 604 | LOW |
 * | `G₁` | 54 | 610 | HIGH |
 * | `F₂` | 58 | 600 | LOW |
 * | `G₂` | 62 | 606 | HIGH |
 * | — | 70 | 632 | HIGH (breakout bar) |
 *
 * It is a top-level fixture rather than a private helper because the real-data census test needs the
 * same flag as a **control**: the census asserts that no real window is a flag, and only a window
 * that *is* a flag proves the census is measuring the gates rather than a permanently closed door.
 */
@Suppress("LongParameterList")
internal fun bullFlagSeries(
    poleTop: Double = 620.0,
    firstLower: Double = 604.0,
    firstUpper: Double = 610.0,
    secondLower: Double = 600.0,
    secondUpper: Double = 606.0,
    secondUpperBar: Int = 62,
    closeAtBreak: Double = 632.0,
    breakoutPivot: Boolean = true,
    closeAfterBreak: List<Double>? = null,
    flagVolume: Long = 690_000L,
    baselineVolume: Long = 1_320_000L,
): BuiltSeries {
    val lastBar = 80
    val builder = ChartSeriesBuilder(bars = lastBar + 1)
        // §18.1 P3 — "Prior trend is satisfied **by construction** (the pole *is* the trend)", so
        // there is no `priorTrendPct` gate to satisfy; the run-up exists to make ATR sensible.
        .at(0, 380.0)
        // §19 EXHAUSTION looks 60 bars back, so the pre-pole level is set high enough that the
        // pole alone is not a 40 % run: `1.40 × 470 = 658 > 632`.
        .at(10, 470.0)
        .pivot(20, 500.0, PivotKind.LOW)
        .pivot(44, poleTop, PivotKind.HIGH)
        .pivot(50, firstLower, PivotKind.LOW)
        .pivot(54, firstUpper, PivotKind.HIGH)
        .pivot(58, secondLower, PivotKind.LOW)
        .pivot(secondUpperBar, secondUpper, PivotKind.HIGH)
        .at(69, 598.0)
        .at(70, closeAtBreak)
        .at(lastBar, closeAfterBreak?.lastOrNull() ?: 650.0)
        // §18.7 I4 — the 20 bars before the flag carry 1,320,000 and the flag carries 690,000.
        .volumes(30, 49, baselineVolume)
        .volumes(50, secondUpperBar, flagVolume)
        .volumeAt(70, 3_600_000L)
        .deliveryEvery(50.0)
        .deliveryAt(70, 66.0)
    if (breakoutPivot) builder.pivot(70, closeAtBreak, PivotKind.HIGH)
    if (closeAfterBreak != null) {
        closeAfterBreak.forEachIndexed { index, price -> builder.closeAt(71 + index, price) }
    }
    return builder.build()
}

/** §18.7's **bear** fixture: every price reflected about the pole's midpoint. */
internal fun bearFlagSeries(): BuiltSeries = ChartSeriesBuilder(bars = 81)
    .at(0, 720.0)
    .at(10, 680.0)
    .pivot(20, 620.0, PivotKind.HIGH)
    .pivot(44, 500.0, PivotKind.LOW)
    .pivot(50, 596.0, PivotKind.HIGH)
    .pivot(54, 590.0, PivotKind.LOW)
    .pivot(58, 600.0, PivotKind.HIGH)
    .pivot(62, 594.0, PivotKind.LOW)
    .at(69, 598.0)
    .pivot(70, 478.0, PivotKind.LOW)
    .at(80, 460.0)
    .volumes(0, 80, 1_000_000L)
    .deliveryEvery(50.0)
    .build()