package com.mia.app.domain.pattern

import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.Pivot
import com.mia.app.domain.model.PivotKind
import com.mia.app.domain.model.Timeframe
import com.mia.app.domain.technical.TechnicalPolicy
import com.mia.app.domain.technical.atr

/**
 * The shared swing-point extractor (plan.md §6.2, `patterns-spec.md` §2).
 *
 * **Why this exists and why it is computed once.** All 12 detectors consume the *same* pivot
 * list (plan.md §6.2: "must be shared across all 12 detectors — this is the single biggest
 * performance win"), and the list is additionally re-readable from `pattern_pivots` later
 * without re-detecting (plan.md §5.2). Extracting pivots per detector would be twelve passes
 * over the same bars and would let two detectors disagree about where a swing high was, which
 * would make the score meaningless.
 *
 * **What the book says.** "Professionals focus on **significant** swing highs and swing lows
 * rather than every tick" `[Ch.16-1 §Noise vs Trend]`; trendlines are "best drawn using
 * significant swing highs and swing lows, not every minor fluctuation" `[Ch.16-5]`. The book
 * never gives a percentage or an ATR multiple for "significant", so every number here is
 * `[derived]` from the book's *own* volatility yardstick (ATR, `[Ch.22 §22.26]`) with a
 * percentage floor so a dead-flat scrip cannot degenerate into a tick-by-tick zigzag.
 *
 * The algorithm is `patterns-spec.md` §2.3's state machine, with the §2.4 fallback pass, the
 * §2.5 confirmation lag and the §12 property invariants (alternation, increasing bar index,
 * price == the candle's own high/low).
 */

/**
 * Depth and bound parameters of one zigzag pass.
 *
 * | Field | Meaning | Spec |
 * |-------|---------|------|
 * | [depthPct] | significance floor, % of the pivot's own price | `patterns-spec.md` §2.2 `DEPTH_PCT` |
 * | [atrMultiple] | significance as a multiple of `ATR14` at the reversal bar | §2.2 `DEPTH_ATR` |
 * | [minBarsBetween] | shortest legal leg between two emitted pivots | §2.3 (see [D1]/[W1] KDoc) |
 * | [maxPivots] | hard bound on the output size | §2.3 (see [D1]/[W1] KDoc) |
 * | [confirmBars] | bars that must elapse after a pivot before it counts as confirmed | §2.5 `confirmLagBars` |
 *
 * All values are per **timeframe** and never per pattern (`patterns-spec.md` §2.4): every one of
 * the 12 detectors must read the identical list, or their scores are not comparable.
 */
data class ZigzagParams(
    val depthPct: Double,
    val atrMultiple: Double,
    val minBarsBetween: Int,
    val maxPivots: Int,
    val confirmBars: Int,
) {
    init {
        require(depthPct > 0.0 && !depthPct.isNaN()) { "depthPct must be positive" }
        require(atrMultiple > 0.0 && !atrMultiple.isNaN()) { "atrMultiple must be positive" }
        require(minBarsBetween >= 1) { "minBarsBetween must be at least 1" }
        require(maxPivots >= 2) { "maxPivots must leave room for a high and a low" }
        require(confirmBars >= 0) { "confirmBars must not be negative" }
    }

    /** The §2.4 fallback pass: depth scaled by [factor], every bound unchanged. */
    fun scaledDepth(factor: Double): ZigzagParams {
        require(factor > 0.0) { "factor must be positive" }
        return copy(depthPct = depthPct * factor, atrMultiple = atrMultiple * factor)
    }

    companion object {
        // --- patterns-spec.md §2.2 / §21, depth table -------------------------------------
        // "The book's only stated price-magnitude rule is a 2-ATR stop [Ch.22 §22.27]. A swing
        //  that retraces less than 2 ATR is inside the book's own definition of noise. We use
        //  3.0/2.5 (a superset) so that no pivot the book would call significant is discarded."
        const val D1_DEPTH_ATR: Double = 3.0
        const val W1_DEPTH_ATR: Double = 2.5

        // "ATR collapses in a dead-flat stock … The percentage floor stops the zigzag from
        //  degenerating. Weekly needs more because a weekly bar's ATR understates a multi-week
        //  base."
        const val D1_DEPTH_PCT: Double = 1.5
        const val W1_DEPTH_PCT: Double = 2.5

        // --- patterns-spec.md §2.4 -------------------------------------------------------
        /** "an H&S needs 5 and a flag needs 6, so 8 leaves one candidate formation per detector". */
        const val MIN_PIVOTS: Int = 8

        /** §2.4: 0.6 keeps the W1 ATR floor above the book's 2-ATR stop (`2.5 × 0.6 = 1.5`). */
        const val FALLBACK_DEPTH_FACTOR: Double = 0.60

        // --- patterns-spec.md §2.5 -------------------------------------------------------
        const val D1_CONFIRM_BARS: Int = 1
        const val W1_CONFIRM_BARS: Int = 1

        /**
         * `minBarsBetween` = the shortest leg the book itself accepts for a reversal swing
         * (`patterns-spec.md` §12.2 S7 `HS_MIN_LEG_BARS = 3` daily / `2` weekly, "[derived] —
         * 3 daily bars is the book's *reaction* rather than noise"). Using it here means the
         * zigzag can never emit a "swing" two bars apart, which no detector would accept.
         */
        const val D1_MIN_BARS_BETWEEN: Int = 3
        const val W1_MIN_BARS_BETWEEN: Int = 2

        /**
         * `maxPivots` is the bound that keeps a pathological input cheap (`patterns-spec.md` §2.3
         * asks for "a bounded output size"). `[derived]`: 120 daily pivots is a leg every ~4 bars
         * across plan.md §6.2's 500-bar window, which is far past "significant"; 60 weekly pivots
         * is the same idea on the far shorter weekly window. When the bound binds, the **most
         * recent** pivots are kept — every detector in v1 scans recent formations.
         */
        const val D1_MAX_PIVOTS: Int = 120
        const val W1_MAX_PIVOTS: Int = 60

        /** Daily defaults: 3.0 ATR / 1.5 %, 3-bar minimum leg, 120-pivot bound, 1-bar confirm. */
        val D1: ZigzagParams = ZigzagParams(
            depthPct = D1_DEPTH_PCT,
            atrMultiple = D1_DEPTH_ATR,
            minBarsBetween = D1_MIN_BARS_BETWEEN,
            maxPivots = D1_MAX_PIVOTS,
            confirmBars = D1_CONFIRM_BARS,
        )

        /** Weekly defaults: 2.5 ATR / 2.5 %, 2-bar minimum leg, 60-pivot bound, 1-bar confirm. */
        val W1: ZigzagParams = ZigzagParams(
            depthPct = W1_DEPTH_PCT,
            atrMultiple = W1_DEPTH_ATR,
            minBarsBetween = W1_MIN_BARS_BETWEEN,
            maxPivots = W1_MAX_PIVOTS,
            confirmBars = W1_CONFIRM_BARS,
        )

        /** The locked per-timeframe defaults (`patterns-spec.md` §2.2/§2.4/§2.5, §21). */
        fun forTimeframe(timeframe: Timeframe): ZigzagParams = when (timeframe) {
            Timeframe.D1 -> D1
            Timeframe.W1 -> W1
        }
    }
}

/**
 * The extracted pivots plus the lookups detectors actually need, and the ATR reading that was
 * in force at each pivot bar (so a detector never has to re-walk the candle list to find it).
 *
 * [pivots] is always ordered oldest → newest with **strictly increasing** [Pivot.barIndex] and
 * **strictly alternating** [Pivot.kind]; [Pivot.legIndex] is the pivot's own index in the list.
 * Those three are the plan.md §12 property tests and they hold by construction in
 * [ZigzagPivots.pass].
 */
data class PivotSet(
    val pivots: List<Pivot>,
    /** The parameters **actually used**, i.e. the shallower fallback pass if it was selected. */
    val params: ZigzagParams,
    /** `ATR14` at each pivot's bar, in the same order as [pivots]. `0.0` where unavailable. */
    val atrAtPivot: List<Double>,
    /** Bars in the series the pivots came from; the confirmation lag is measured against it. */
    val barCount: Int = (pivots.maxOfOrNull { it.barIndex } ?: -1) + 1,
    /** `true` when the §2.4 shallower fallback pass produced the list that was kept. */
    val usedFallbackDepth: Boolean = false,
) {
    init {
        require(atrAtPivot.size == pivots.size) { "atrAtPivot must align with pivots" }
    }

    val size: Int get() = pivots.size
    val isEmpty: Boolean get() = pivots.isEmpty()

    /** By index — the form detectors use most. */
    operator fun get(index: Int): Pivot = pivots[index]

    /** Every swing high, oldest first. */
    fun highs(): List<Pivot> = pivots.filter { it.kind == PivotKind.HIGH }

    /** Every swing low, oldest first. */
    fun lows(): List<Pivot> = pivots.filter { it.kind == PivotKind.LOW }

    /** By kind — the same list as [highs]/[lows] selected by argument. */
    fun ofKind(kind: PivotKind): List<Pivot> = pivots.filter { it.kind == kind }

    /** The pivot sitting on [barIndex], or `null` when that bar is not a pivot. */
    fun pivotAt(barIndex: Int): Pivot? = pivots.firstOrNull { it.barIndex == barIndex }

    /** Price of the pivot on [barIndex], or `null` when that bar is not a pivot. */
    fun priceAt(barIndex: Int): Double? = pivotAt(barIndex)?.price

    /** `ATR14` at pivot [index]; `0.0` when the ATR warm-up had not finished there. */
    fun atrAt(index: Int): Double = atrAtPivot.getOrElse(index) { 0.0 }

    /**
     * `patterns-spec.md` §2.5: a pivot is confirmed once price has traded away from it by the
     * required move, i.e. at least [ZigzagParams.confirmBars] bars have elapsed since it.
     * Structural detectors (6–12) drop unconfirmed pivots; candlestick detectors (1–5) never
     * look at pivots at all.
     */
    fun isConfirmed(index: Int): Boolean =
        index in pivots.indices && (barCount - 1 - pivots[index].barIndex) >= params.confirmBars

    /** Only the confirmed pivots, in order — the list detectors 6–12 scan. */
    fun confirmed(): List<Pivot> = pivots.filterIndexed { index, _ -> isConfirmed(index) }

    /** Bars between pivot [index] and its predecessor; `0` for the first pivot. */
    fun gapBefore(index: Int): Int =
        if (index <= 0 || index >= pivots.size) 0 else pivots[index].barIndex - pivots[index - 1].barIndex

    /** Every leg length in bars: `gapBefore(1) … gapBefore(size - 1)`. */
    fun legGaps(): List<Int> = (1 until pivots.size).map { gapBefore(it) }

    companion object {
        val EMPTY: PivotSet = PivotSet(emptyList(), ZigzagParams.D1, emptyList(), 0, false)
    }
}

/** The single implementation of `patterns-spec.md` §2.3 + §2.4. */
object ZigzagPivots {

    /**
     * Extracts the shared pivot list.
     *
     * @param atr Wilder `ATR14` aligned to [candles], `null` inside the warm-up window. Pass the
     *   engine's `IndicatorSnapshot.atr` when one already exists so the series is not computed
     *   twice (plan.md §6.2 performance requirement); it is derived here only when omitted.
     * @param barCount bars in the series. Defaults to [candles].size; a caller that trims the
     *   series must pass the count the confirmation lag should be measured against.
     */
    fun compute(
        candles: List<Candle>,
        timeframe: Timeframe,
        params: ZigzagParams = ZigzagParams.forTimeframe(timeframe),
        atr: List<Double?>? = null,
        barCount: Int = candles.size,
    ): PivotSet {
        if (candles.isEmpty()) {
            return PivotSet(emptyList(), params, emptyList(), 0, false)
        }
        val atrSeries = atr ?: atr(candles, TechnicalPolicy.ATR_PERIOD)

        val primary = pass(candles, params, atrSeries)
        if (primary.size >= ZigzagParams.MIN_PIVOTS) {
            return build(primary, params, atrSeries, barCount, fallback = false)
        }

        // §2.4 fallback pass: some detectors need 5 consecutive alternating pivots, so if the
        // primary depth starved them, re-run shallower and keep whichever found more.
        val shallowParams = params.scaledDepth(ZigzagParams.FALLBACK_DEPTH_FACTOR)
        val shallow = pass(candles, shallowParams, atrSeries)
        if (shallow.size > primary.size) {
            return build(shallow, shallowParams, atrSeries, barCount, fallback = true)
        }
        return build(primary, params, atrSeries, barCount, fallback = false)
    }

    /**
     * One depth pass, with no fallback. Exposed because the property tests assert the state
     * machine itself, independently of the fallback choice in [compute].
     */
    fun pass(candles: List<Candle>, params: ZigzagParams, atrSeries: List<Double?>): List<Pivot> {
        val n = candles.size
        if (n < 2) return emptyList()

        val emitted = ArrayList<Pivot>(n / 4 + 8)
        // `ATR14` is undefined for the first `period - 1` bars. Falling back to the ATR term being
        // *zero* there would shrink the significance threshold to the percentage floor alone and
        // let bar noise through exactly where the series is least understood, so the average true
        // range so far stands in — the same quantity, without Wilder's smoothing.
        val trMean = averageTrueRangeSoFar(candles)
        var seekingHigh = true
        var extremeBar = 0
        var extremePrice = candles[0].high

        for (i in 0 until n) {
            val candle = candles[i]
            val high = candle.high
            val low = candle.low
            // A structurally invalid bar (non-finite or non-positive) can never be a swing point,
            // and letting one into the state machine would let it "confirm" a pivot.
            if (!isUsable(high) || !isUsable(low)) continue

            if (seekingHigh) {
                if (high > extremePrice) {
                    extremeBar = i
                    extremePrice = high
                } else if (extremePrice - low >= requiredMove(params, extremePrice, atrSeries, trMean, i)
                ) {
                    emitAt(emitted, candles, extremeBar, extremePrice, PivotKind.HIGH, params)
                    seekingHigh = false
                    extremeBar = i
                    extremePrice = low
                }
            } else {
                if (low < extremePrice) {
                    extremeBar = i
                    extremePrice = low
                } else if (high - extremePrice >= requiredMove(params, extremePrice, atrSeries, trMean, i)
                ) {
                    emitAt(emitted, candles, extremeBar, extremePrice, PivotKind.LOW, params)
                    seekingHigh = true
                    extremeBar = i
                    extremePrice = high
                }
            }
        }

        // The leg still in progress at the end of the series: emit it when it clears the same
        // significance test against the previous pivot (or, with none, against bar 0's close), so
        // a flat series still yields zero pivots. It is always unconfirmed (§2.5), which is what
        // lets a detector see the freshest structure while dropping it from formation windows.
        val reference = emitted.lastOrNull()?.price ?: candles[0].close
        val kind = if (seekingHigh) PivotKind.HIGH else PivotKind.LOW
        if (extremeBar > (emitted.lastOrNull()?.barIndex ?: -1) &&
            kotlin.math.abs(extremePrice - reference) >= requiredMove(params, extremePrice, atrSeries, trMean, n - 1)
        ) {
            emitAt(emitted, candles, extremeBar, extremePrice, kind, params)
        }

        return renumber(emitted)
    }

    /**
     * The significance test, `patterns-spec.md` §2.2:
     * `requiredMove = max(depthPct/100 × price, depthAtr × ATR14(bar))`, where `price` is the
     * pivot candidate's own price and the ATR is read at the bar where price moved **away**.
     * A missing ATR (warm-up) falls back to the percentage floor alone, never to zero.
     */
    fun requiredMove(params: ZigzagParams, price: Double, atrSeries: List<Double?>, bar: Int): Double =
        requiredMove(params, price, atrSeries, null, bar)

    /**
     * The significance test, `patterns-spec.md` §2.2:
     * `requiredMove = max(depthPct/100 × price, depthAtr × ATR14(bar))`, where `price` is the
     * pivot candidate's own price and the ATR is read at the bar where price moved **away**.
     *
     * [trMean] supplies the volatility reading for the ATR warm-up window (see [pass]); without it
     * a missing ATR degrades to the percentage floor alone.
     */
    fun requiredMove(
        params: ZigzagParams,
        price: Double,
        atrSeries: List<Double?>,
        trMean: DoubleArray?,
        bar: Int,
    ): Double {
        val pctMove = params.depthPct / 100.0 * price
        val atr = atrSeries.getOrNull(bar)
        val atrMove = when {
            atr != null && !atr.isNaN() && !atr.isInfinite() -> params.atrMultiple * atr
            trMean != null && trMean.getOrNull(bar)?.let { !it.isNaN() && it.isFinite() } == true ->
                params.atrMultiple * trMean[bar]

            else -> 0.0
        }
        return maxOf(pctMove, atrMove)
    }

    /**
     * Running mean true range per bar: `mean(TR[0..i])`. Used as the volatility proxy while `ATR14`
     * is still warming up. `TR[i] = max(high − low, |high − close[i−1]|, |low − close[i−1]|)` —
     * Wilder's own definition, taken from the shared `domain/technical` implementation.
     */
    private fun averageTrueRangeSoFar(candles: List<Candle>): DoubleArray {
        val n = candles.size
        val out = DoubleArray(n)
        var running = 0.0
        for (i in 0 until n) {
            val c = candles[i]
            val tr = if (i == 0) {
                c.high - c.low
            } else {
                val prev = candles[i - 1]
                maxOf(
                    c.high - c.low,
                    kotlin.math.abs(c.high - prev.close),
                    kotlin.math.abs(c.low - prev.close),
                )
            }
            running += if (tr.isFinite() && tr > 0.0) tr else 0.0
            out[i] = running / (i + 1)
        }
        return out
    }

    /**
     * Emits the confirmed candidate, enforcing the two list invariants.
     *
     * **Short legs merge rather than stall.** When the candidate is closer to the previous pivot
     * than [ZigzagParams.minBarsBetween], the previous pivot is *dropped* and the candidate takes
     * its place: two legs squeezed into less than the book's shortest reaction are one swing, and
     * the previous one never had room to be significant. Rejecting the candidate instead would
     * deadlock — the extreme would keep being replaced every bar and the list would never grow.
     */
    private fun emitAt(
        emitted: MutableList<Pivot>,
        candles: List<Candle>,
        barIndex: Int,
        price: Double,
        kind: PivotKind,
        params: ZigzagParams,
    ) {
        val previous = emitted.lastOrNull()
        if (previous != null && barIndex - previous.barIndex < params.minBarsBetween) {
            emitted.removeAt(emitted.lastIndex)
        }
        emit(emitted, Pivot(barIndex, candles[barIndex].date, price, kind, 0))
    }

    /**
     * `patterns-spec.md` §2.3: "emit enforces the alternation invariant … if the candidate's kind
     * equals the previous emitted kind, the **more extreme** of the two is kept and the leg index
     * is not advanced."
     *
     * The state machine makes a same-kind emit unreachable, but the check stays because it is a
     * plan.md §12 property test and a silent regression here would be invisible in the UI.
     */
    private fun emit(emitted: MutableList<Pivot>, candidate: Pivot) {
        val previous = emitted.lastOrNull()
        if (previous != null && previous.kind == candidate.kind) {
            val keepCandidate = when (candidate.kind) {
                PivotKind.HIGH -> candidate.price > previous.price
                PivotKind.LOW -> candidate.price < previous.price
            }
            if (!keepCandidate) return
            emitted[emitted.lastIndex] = candidate.copy(legIndex = emitted.lastIndex)
            return
        }
        emitted.add(candidate.copy(legIndex = emitted.size))
    }

    private fun build(
        pivots: List<Pivot>,
        params: ZigzagParams,
        atrSeries: List<Double?>,
        barCount: Int,
        fallback: Boolean,
    ): PivotSet {
        val bounded = if (pivots.size > params.maxPivots) {
            renumber(pivots.subList(pivots.size - params.maxPivots, pivots.size))
        } else {
            pivots
        }
        // `ATR at pivot`, walking backwards so a pivot inside the ATR warm-up still reports the
        // most recent reading that existed at that time rather than 0.0.
        val atrAtPivot = DoubleArray(bounded.size)
        var lastKnown = 0.0
        for (index in bounded.indices) {
            val bar = bounded[index].barIndex
            var carried = lastKnown
            for (j in bar downTo 0) {
                val a = atrSeries.getOrNull(j)
                if (a != null && !a.isNaN() && !a.isInfinite()) {
                    carried = a
                    break
                }
            }
            atrAtPivot[index] = carried
            lastKnown = carried
        }
        return PivotSet(bounded, params, atrAtPivot.toList(), barCount, fallback)
    }

    /** Keeps the **most recent** pivots when the bound binds, and re-numbers `legIndex`. */
    private fun renumber(pivots: List<Pivot>): List<Pivot> =
        pivots.mapIndexed { index, pivot -> pivot.copy(legIndex = index) }

    private fun isUsable(price: Double): Boolean =
        price > 0.0 && !price.isNaN() && !price.isInfinite()
}