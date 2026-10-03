package com.mia.app.domain.pattern.detectors.candlestick

import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.Category
import com.mia.app.domain.pattern.Penalty
import com.mia.app.domain.pattern.PenaltyId

/**
 * The parts of §5–§10 that every candlestick detector shares, factored out so a rule can only be
 * written once.
 *
 * The alternative — five private copies of "is the volume on the signal bar weak?" — is how two
 * detectors end up disagreeing about the same bar and the user is shown two different verdicts for
 * one candle.
 */
internal object CandlestickSupport {

    /**
     * The bars a detector may treat as its **signal bar**, newest first.
     *
     * `patterns-spec.md` §6.1 **P2** describes an offline scan of *every* `n`; this is that scan
     * clipped to the only bars a candlestick can legitimately be current at. A formation is
     * defined over its last one to three bars `[Ch.17-2, Ch.17-3]`, so the oldest allowed signal
     * bar is [CandlestickPolicy.MAX_SIGNAL_AGE_BARS] back from the end of the series — the window
     * over which "the next session" still exists for the book's own follow-through rule and the
     * alert the app schedules (plan.md §6.6 item 3).
     *
     * Newest first, because §4 rule 6 resolves two qualifying windows toward the **more recent**
     * formation, and because a caller asked "what is happening now".
     *
     * @param formationBars how many bars the pattern itself occupies (2 engulfing, 1 hammer,
     *   3 star); used only to keep the signal bar from running off the start of the list.
     */
    fun candidateSignalBars(candles: List<Candle>, formationBars: Int): List<Int> {
        val last = candles.lastIndex
        val oldest = maxOf(last - CandlestickPolicy.MAX_SIGNAL_AGE_BARS, formationBars - 1)
        return (last downTo oldest).toList()
    }

    /**
     * §5.3 Layer 3 / §6.3 **I4** / §8.3 **I4** / §9 **I4**: `rvol[signalBar] < 1.5`.
     *
     * "A breakout without volume is suspicious" `[Ch.21 §21.13]`; for the family as a whole,
     * "Volume: 3× average" is the layer the book puts third `[Ch.17-4 §The Five Layers]`.
     */
    fun isWeakVolume(rvolOnSignal: Double?): Boolean =
        rvolOnSignal == null || rvolOnSignal < CandlestickPolicy.RVOL_WEAK

    fun weakVolumePenalty(reason: String): Penalty = Penalty.of(PenaltyId.WEAK_VOLUME, reason)

    /**
     * §6.6 `VOLATILE_MARKET` — "Candlestick patterns become less reliable. Large price swings can
     * distort short-term signals" `[Ch.17-4 §Highly Volatile Markets]`.
     *
     * Applies to detectors 1–5 only (`patterns-spec.md` §19.1).
     */
    fun volatileMarketPenalty(signals: CandlestickSignals, bar: Int): Penalty? =
        if (signals.isVolatileMarket(bar)) {
            Penalty.of(
                PenaltyId.VOLATILE_MARKET,
                "Volatility on the signal bar is more than twice its own trailing median — " +
                    "candlestick signals are less reliable in a highly volatile market.",
            )
        } else {
            null
        }

    /**
     * §5.3 **CTX_MISMATCH**, the location layer: "This is usually more important than the pattern
     * itself" `[Ch.17-4 §Layer 1]`.
     *
     * Weighted **double** for the candlestick family (`scoring-spec.md` §7.1), which is what
     * `PenaltyId.of(id, Category.CANDLESTICK, reason)` produces.
     */
    fun contextMismatchPenalty(reason: String): Penalty =
        PenaltyId.of(PenaltyId.CTX_MISMATCH, Category.CANDLESTICK, reason)

    /**
     * §6.5 **C3** / §10.5 **C2**, the market-structure confirmation: a **close** beyond the swing
     * extreme that preceded the pattern, within
     * [CandlestickPolicy.STRUCTURE_LOOKAHEAD_BARS] bars — "Break Above Lower High"
     * `[Ch.17-2 §Combining with Market Structure]`, `[Ch.17-3 §Importance of Confirmation]`.
     *
     * A `close`-based test is mandatory (`patterns-spec.md` §3.7: "Professional analysts place much
     * greater importance on **closing prices** than on temporary intraday movements"), so an
     * intraday poke through is not a confirmation.
     *
     * @param precedingExtreme the swing high (bullish) or swing low (bearish) of the context
     *   window — the level market structure says has to give way.
     */
    fun structureConfirmed(
        candles: List<Candle>,
        signalBar: Int,
        precedingExtreme: Double,
        bullish: Boolean,
    ): Boolean {
        val end = minOf(
            signalBar + CandlestickPolicy.STRUCTURE_LOOKAHEAD_BARS,
            candles.lastIndex,
        )
        for (j in (signalBar + 1)..end) {
            val close = candles[j].close
            if (bullish && close > precedingExtreme) return true
            if (!bullish && close < precedingExtreme) return true
        }
        return false
    }

    /**
     * §5.3 **STRUCTURE_CONTRADICTS** shared predicate: does the market structure in the next
     * [CandlestickPolicy.STRUCTURE_LOOKAHEAD_BARS] bars still disagree with the pattern?
     *
     * §6.3 **I3** (bullish: "bars `[n+1, n+5]` still print a lower low than `low[n]`"), §7
     * (bearish mirror), §8.3/§9 **I3** and §10.3 **I4** ("bars `[n+1, n+5]` continue the prior
     * trend") are all this one question.
     *
     * @param extremePrice the signal bar's own low (a bullish pattern) or high (a bearish one).
     */
    fun structureContradicts(
        candles: List<Candle>,
        signalBar: Int,
        extremePrice: Double,
        bullish: Boolean,
    ): Boolean {
        val end = minOf(
            signalBar + CandlestickPolicy.STRUCTURE_LOOKAHEAD_BARS,
            candles.lastIndex,
        )
        for (j in (signalBar + 1)..end) {
            if (bullish && candles[j].low < extremePrice) return true
            if (!bullish && candles[j].high > extremePrice) return true
        }
        return false
    }

    fun structureContradictsPenalty(reason: String): Penalty =
        Penalty.of(PenaltyId.STRUCTURE_CONTRADICTS, reason)

    /**
     * §6.5 **C6**: `rvol >= 2.0` while `deliveryVsAvg < 0.70` — "a high delivery percentage …
     * does not automatically imply accumulation"; the mirror case is intraday churn
     * `[Ch.21-6 §Delivery Volume]`.
     *
     * The rule is named in `patterns-spec.md` §6.5 but **no penalty id is**, so `WEAK_VOLUME` is
     * used: it is the catalogue's only participation-quality id, it is in §19.1's applicability
     * row for detectors 1 and 2, and its `−6` is proportionate to a caveat rather than a failure.
     * It can never double up with the `rvol < 1.5` weak-volume rule, because the two conditions
     * are mutually exclusive — so `scoring-spec.md` §7.2's "a penalty fires at most ONCE per
     * match" holds.
     */
    fun deliveryChurnPenalty(signals: CandlestickSignals, candles: List<Candle>, bar: Int): Penalty? {
        val rvol = signals.rvolAt(bar) ?: return null
        val delivery = signals.deliveryVsAverageAt(candles, bar) ?: return null
        if (rvol >= CandlestickPolicy.RVOL_CONFIRM && delivery < CandlestickPolicy.DELIVERY_CHURN_RATIO) {
            return Penalty.of(
                PenaltyId.WEAK_VOLUME,
                "Volume expanded but delivery % is below 70 % of its own average — intraday churn " +
                    "rather than a transfer of ownership.",
            )
        }
        return null
    }
}