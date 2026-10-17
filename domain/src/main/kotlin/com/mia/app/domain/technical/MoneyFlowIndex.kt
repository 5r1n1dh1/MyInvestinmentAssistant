package com.mia.app.domain.technical

import com.mia.app.domain.model.Candle
import kotlin.math.abs

/**
 * Money Flow Index (Wilder, 1978) — an RSI computed on **typical price × volume** instead of on
 * closes alone.
 *
 * ### What it is supposed to add over RSI
 *
 * RSI reads *where the close went*. MFI reads *where the close went weighted by how much traded
 * there*. The two diverge in exactly the case worth knowing about: a limit-up close on trivial
 * volume is a weak move in MFI terms even though RSI calls it overbought, and a grinding rally on
 * expanding volume is a strong move in MFI terms while RSI barely leaves 60. So MFI is not a
 * second RSI — it is RSI with a question attached: *did the volume agree?*
 *
 * ### Why it is bounded 0–100 and what the bounds do not mean
 *
 * MFI is a Wilder-smoothed ratio of positive to negative money flow, so it is mathematically
 * bounded to 0–100 by construction. That bound is **not** a claim about what is overbought. This
 * repository's existing position is that a point-in-time rate cannot distinguish a working rule
 * from a broken one — see `docs/VERIFICATION.md` on `RSI_OVERSOLD` firing at 3.34% and
 * `DISTRIBUTION_DAY` at 1.41%, both of which are plausible only if the threshold is not the thing
 * doing the work. So this function publishes the number and the sample size and **no threshold**,
 * and [TechnicalPolicy] is left without MFI overbought/oversold constants on purpose: a level here
 * would be a guess dressed as a convention.
 *
 * ### Degenerate cases, stated rather than papered over
 *
 * - **Fewer than `period + 1` bars** → all null, warmup, never a fabricated early value.
 * - **Zero typical price** → skipped, because a ₹0 bar makes the ratio undefined rather than
 *   extreme.
 * - **Zero total money flow over the window** (every bar had zero volume) → **null, not 50**. The
 *   arithmetic ratio is 0/0; 50 is the conventional "no opinion" filler, and returning it would put
 *   a fabricated reading on a chart for a stock that simply did not trade. This is the same
 *   principle as `Volume.volumeRatio` returning null rather than zero.
 * - **Zero negative money flow** (monotonically rising typical price) → 100, which *is* the honest
 *   limit and is distinguishable from the null above.
 */
object MoneyFlowIndex {

    /** Warmup: the first usable index is at bar `period + 1`, mirroring [rsi]. */
    fun warmupBars(period: Int): Int = if (period <= 0) Int.MAX_VALUE else period

    /**
     * MFI over candles, oldest first.
     *
     * @return a list aligned index-for-index with [candles]; entries before the warmup, and any
     *   window with no money flow at all, are `null`.
     */
    fun compute(candles: List<Candle>, period: Int = TechnicalPolicy.MFI_PERIOD): List<Double?> {
        val size = candles.size
        val out = MutableList<Double?>(size) { null }
        if (period <= 0 || size < period + 1) return out

        val typical = DoubleArray(size) { i ->
            val c = candles[i]
            (c.high + c.low + c.close) / 3.0
        }
        val moneyFlow = DoubleArray(size) { i ->
            // A zero typical price makes both the flow and any ratio against it meaningless.
            if (typical[i] <= 0.0 || !typical[i].isFinite()) 0.0 else typical[i] * candles[i].volume
        }

        // Seed the window, then Wilder-smooth forward — the same recursion RSI uses, so the two
        // indicators warm up and decay identically and a reader comparing them is not comparing
        // two different smoothing conventions.
        var positive = 0.0
        var negative = 0.0
        for (i in 1..period) {
            when {
                typical[i] > typical[i - 1] -> positive += moneyFlow[i]
                typical[i] < typical[i - 1] -> negative += moneyFlow[i]
                // equal typical price: neither positive nor negative flow, by definition
            }
        }
        out[period] = ratio(positive, negative)

        for (i in (period + 1) until size) {
            positive -= moneyFlow[i - period] * sign(typical[i - period] - typical[i - period - 1])
            negative -= moneyFlow[i - period] * sign(typical[i - period - 1] - typical[i - period])
            when {
                typical[i] > typical[i - 1] -> positive += moneyFlow[i]
                typical[i] < typical[i - 1] -> negative += moneyFlow[i]
            }
            out[i] = ratio(positive, negative)
        }
        return out
    }

    /**
     * The bounded ratio, with the two degenerate cases kept apart on purpose.
     *
     * `denominator == 0` happens in two different situations and they must not collapse:
     * no money flow at all → **null** (we measured nothing), and no negative flow → **100** (every
     * bar's money flow was in the rising direction, which is a real, extreme reading).
     */
    private fun ratio(positive: Double, negative: Double): Double? = when {
        positive == 0.0 && negative == 0.0 -> null
        negative == 0.0 -> 100.0
        else -> {
            val value = 100.0 - (100.0 / (1.0 + positive / negative))
            // Guard the arithmetic rather than trusting it: a NaN here would propagate into a chart.
            if (value.isFinite()) value.coerceIn(0.0, 100.0) else null
        }
    }

    private fun sign(value: Double): Double = when {
        value > 0 -> 1.0
        value < 0 -> -1.0
        else -> 0.0
    }
}

/** MFI over candles. See [MoneyFlowIndex] for the maths and the degenerate cases. */
fun mfi(candles: List<Candle>, period: Int = TechnicalPolicy.MFI_PERIOD): List<Double?> =
    MoneyFlowIndex.compute(candles, period)

/**
 * The newest MFI reading, or null when the series is still warming up or carried no money flow.
 *
 * The convenience the UI actually wants. It returns a nullable rather than throwing or defaulting,
 * because "not enough history" and "no volume" are both ordinary states on an Indian EOD install
 * and neither should be rendered as a number.
 */
fun latestMfi(candles: List<Candle>, period: Int = TechnicalPolicy.MFI_PERIOD): Double? =
    MoneyFlowIndex.compute(candles, period).lastOrNull()

/**
 * How far a stop of `percent` of price sits, in rupees and in ATR units.
 *
 * ### Why this is here at all
 *
 * A percentage stop is only interpretable once it is expressed in the instrument's own volatility.
 * "A 2% stop" on a stock whose ATR is 0.6% is roughly three ATRs of noise — a stop that gets hit
 * by ordinary movement — while the same 2% on a stock whose ATR is 3% is well inside one day's
 * range. Saying **both** numbers is the difference between a risk number and a slogan, and it is
 * the one piece of position-sizing arithmetic this app can teach honestly from measured data.
 *
 * @return `null` when ATR is unavailable or non-positive: a ratio against an undefined ATR is
 *   exactly the fabricated reading the honesty contract forbids.
 */
data class RiskLine(
    /** Stop distance in rupees from the reference price. */
    val rupees: Double,
    /** The same distance in ATR units — below ~1 is inside a typical session's range. */
    val atrMultiple: Double?,
    /** The ATR the multiple was computed from. */
    val atr: Double?,
)

/**
 * A stop at `percent` of [price], sized against the instrument's own volatility.
 *
 * @param atrValue the newest ATR, or null when it is unavailable.
 */
fun riskLine(price: Double, percent: Double, atrValue: Double?): RiskLine {
    val rupees = if (price.isFinite() && percent.isFinite()) abs(price * percent / 100.0) else 0.0
    val usable = atrValue?.takeIf { it.isFinite() && it > 0.0 }
    return RiskLine(
        rupees = rupees,
        atrMultiple = usable?.let { rupees / it },
        atr = usable,
    )
}