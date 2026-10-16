package com.mia.app.domain.technical

import com.mia.app.domain.model.Candle
import kotlin.math.roundToLong

/**
 * Median daily traded value over a stored window, and the factual sentence the UI shows about it.
 *
 * ### Why median rather than mean
 * A mean of daily turnover is dominated by the one session a quarter when a large holder trades,
 * which on an SME (NSE series `SM`) is routinely an order of magnitude above the rest of the
 * window. A mean would then report a stock as liquid on the strength of a day that tells you
 * nothing about the other 59. The **median** is the figure that describes the ordinary session,
 * which is the session a reader is asking about.
 *
 * ### Why `volume × close` and not the bhavcopy's `TtlTrfVal`
 * plan.md §5.1 makes the write-once `candles` table the price of record, and it stores only OHLCV
 * — there is no turnover column. Multiplying the two figures that *are* stored reproduces the
 * published turnover to within rounding for every ordinary equity, and it means this computation
 * works on any symbol the device holds bars for, including one whose bhavcopy row is months old.
 *
 * ### What this never says
 * The notice is a measurement with its window attached: "median daily turnover ₹38 lakh over the
 * last 60 sessions". It is not a verdict about the stock, and [Liquidity.notice] is written so it
 * cannot become one — there is no adjective in it, only the number, the window and the threshold
 * it fell below.
 */
data class LiquidityProfile(
    /** Sessions the measurement was taken over. Equals [sessionsUsed] unless the store was short. */
    val windowSessions: Int,
    /** Sessions that contributed a usable `volume × close`. */
    val sessionsUsed: Int,
    /** Median of the per-session turnover, in rupees. `null` when nothing usable was stored. */
    val medianDailyTurnover: Double?,
    /** Cheapest session in the window, in rupees. */
    val minimumDailyTurnover: Double?,
    /** Dearest session in the window, in rupees. */
    val maximumDailyTurnover: Double?,
    /**
     * The gate's verdict on [medianDailyTurnover], or the reason no verdict was reached.
     *
     * `Measured` rather than a boolean so "below the threshold" and "could not be measured" cannot
     * be mistaken for each other in a boolean's place — see [Measured].
     */
    val isThinTrading: Measured<Boolean>,
    /** Newest session in the window, or `null` when nothing was stored. */
    val asOf: java.time.LocalDate? = null,
) {
    /** True when the median exists and is under [Liquidity.THIN_TRADING_THRESHOLD_RUPEES]. */
    val isThin: Boolean get() = isThinTrading.valueOrNull() == true

    /**
     * The factual sentence for the header, or `null` when there is nothing to say.
     *
     * Two shapes, both measurements:
     * * **Thin** — the measured median, the window it came from, and the threshold it fell below.
     * * **Unmeasured** — what is missing, so the absence is explained instead of shown as a bar
     *   the reader has to interpret.
     */
    fun notice(): String? {
        if (isThin) {
            return "Thin trading: median daily turnover ${formatRupees(medianDailyTurnover!!)} over " +
                "the last $windowSessions stored session(s), below the " +
                "${formatRupees(Liquidity.THIN_TRADING_THRESHOLD_RUPEES)} the app flags. A limit " +
                "order on a stock like this may take more than one session to fill."
        }
        return when (val verdict = isThinTrading) {
            is Measured.Unavailable -> verdict.reason
            is Measured.Value -> null
        }
    }
}

/**
 * The liquidity gate — planv2 W3's SME check.
 *
 * ### The threshold, and why it is ₹50,00,000
 * ₹50 lakh of median daily turnover is the figure NSE's own market-segmentation work treats as
 * the small-orders boundary, and it is the number the app states rather than an inference from
 * it. It is a **stated threshold**, not a conclusion: the notice reports the measured median beside
 * it and lets the reader compare. A reader who disagrees with the threshold can see exactly what
 * it was applied to.
 *
 * ### The window
 * [DEFAULT_WINDOW] is 60 sessions, which is plan.md §6.2's stored daily window at the low end and
 * about three calendar months — long enough that one unusual day moves the median by very little,
 * short enough to still be present on a device that has only been refreshing weekly.
 */
object Liquidity {

    /** ₹50,00,000 — 50 lakh. Below this median daily turnover the header shows the notice. */
    const val THIN_TRADING_THRESHOLD_RUPEES: Double = 50_00_000.0

    /** Sessions measured when the caller does not choose a window. */
    const val DEFAULT_WINDOW: Int = 60

    /**
     * The fewest sessions a median may be taken over.
     *
     * Fewer than this and "median" is an average of one or two days, which says more about those
     * days than about the stock. Below it the answer is [Measured.Unavailable] rather than a
     * number computed from too little.
     */
    const val MIN_SESSIONS: Int = 20

    /**
     * The median daily turnover (₹) over the last [window] stored sessions, or `null`.
     *
     * `null` here means "not enough usable sessions"; the caller-facing form is [of], which turns
     * that into a stated reason.
     */
    fun medianDailyTurnover(candles: List<Candle>, window: Int = DEFAULT_WINDOW): Double? {
        val turnovers = turnovers(candles, window)
        return if (turnovers.size < MIN_SESSIONS) null else turnovers.sorted()[turnovers.size / 2]
    }

    /**
     * The full measurement for [candles], newest-last ordering assumed but not required.
     *
     * Sorts by date so a caller that hands over bars in descending order gets the same answer.
     * A session is skipped (not zero-filled) when its close or volume is unusable — a session
     * with a zero volume on an index day is a real bar with no trades, and dropping it is the
     * honest reading; counting it as a zero-turnover day would drag the median toward the answer
     * the notice is trying to make honestly.
     */
    fun of(candles: List<Candle>, window: Int = DEFAULT_WINDOW): LiquidityProfile {
        val ordered = candles.sortedBy { it.date }
        val take = ordered.takeLast(window.coerceAtLeast(1))
        val usable = ArrayList<Double>(take.size)
        for (candle in take) {
            if (!candle.close.isFinite() || candle.close <= 0.0) continue
            if (candle.volume < 0L) continue
            usable += candle.volume.toDouble() * candle.close
        }

        val sorted = usable.sorted()
        val median = if (sorted.size < MIN_SESSIONS) null else sorted[sorted.size / 2]
        val verdict: Measured<Boolean> = if (median == null) {
            Measured.Unavailable(
                "Turnover could not be measured: ${usable.size} usable session(s) are stored and " +
                    "a median needs at least $MIN_SESSIONS. Nothing is concluded about this stock's " +
                    "liquidity.",
            )
        } else {
            Measured.Value(median < THIN_TRADING_THRESHOLD_RUPEES)
        }

        return LiquidityProfile(
            windowSessions = take.size,
            sessionsUsed = usable.size,
            medianDailyTurnover = median,
            minimumDailyTurnover = sorted.firstOrNull(),
            maximumDailyTurnover = sorted.lastOrNull(),
            isThinTrading = verdict,
            asOf = take.lastOrNull()?.date,
        )
    }

    /**
     * Whether [series] is an NSE SME instrument, i.e. one the gate must always check.
     *
     * plan.md §3.1 keeps exactly two series, `EQ` and `SM`; `SM` is the SME series, and those are
     * the instruments where a ₹50-lakh median is the normal state rather than the exception. A
     * caller that has the series should therefore not have to remember the code — and a caller
     * that does not have it should still be able to say "unknown", which is why this is a
     * predicate over an optional [series] rather than a default of `true`.
     */
    fun isSmeSeries(series: String?): Boolean = series?.trim()?.uppercase() == SME_SERIES

    /** NSE's SME series code. See [isSmeSeries]. */
    const val SME_SERIES: String = "SM"

    private fun turnovers(candles: List<Candle>, window: Int): List<Double> =
        candles.sortedBy { it.date }
            .takeLast(window.coerceAtLeast(1))
            .filter { it.close.isFinite() && it.close > 0.0 && it.volume >= 0L }
            .map { it.volume.toDouble() * it.close }
}

/**
 * Rupees in the words an Indian finance screen actually uses: lakh and crore.
 *
 * Below one lakh the exact rupee figure is short and readable, so it is used; above one crore the
 * two-decimal form is what every quote terminal prints. The conversion rounds to whole **lakhs**
 * (or whole crores above a crore) so the string is a statement about a band, not a false precision
 * — a median of ₹38,412,993 reads as "₹38 lakh", which is what it means.
 */
fun formatRupees(rupees: Double?): String {
    if (rupees == null || !rupees.isFinite() || rupees < 0.0) return "unknown"
    val crore = rupees / 1_00_00_000.0
    if (crore >= 1.0) {
        return "₹" + round2(crore) + " crore"
    }
    val lakh = rupees / 1_00_000.0
    if (lakh >= 1.0) {
        return "₹" + (lakh.roundToLong()) + " lakh"
    }
    return "₹" + rupees.roundToLong()
}

private fun round2(value: Double): String {
    val scaled = (value * 100.0).roundToLong() / 100.0
    return if (scaled == scaled.toLong().toDouble()) {
        scaled.toLong().toString()
    } else {
        scaled.toString()
    }
}