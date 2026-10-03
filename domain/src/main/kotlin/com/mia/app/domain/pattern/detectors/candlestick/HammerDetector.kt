package com.mia.app.domain.pattern.detectors.candlestick

import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.Category
import com.mia.app.domain.model.Match
import com.mia.app.domain.model.MatchScorePolicy
import com.mia.app.domain.model.PatternDetector
import com.mia.app.domain.model.Pivot
import com.mia.app.domain.pattern.Measure
import com.mia.app.domain.pattern.Penalty
import com.mia.app.domain.pattern.PenaltyId
import com.mia.app.domain.pattern.ScoreFactor
import com.mia.app.domain.pattern.matchOf
import kotlin.math.abs
import kotlin.math.max

/**
 * Detector 3 — **Hammer** (`patterns-spec.md` §8, `[Ch.17-1 §Hammer]`).
 *
 * The book gives three adjectives and no numbers: *"small body, long lower wick, little upper
 * wick"* `[Ch.17-1 §Hammer]`. Every threshold below is therefore `[derived]`, with the
 * reasoning carried in the KDoc of [CandlestickPolicy].
 *
 * ### The two gates §8 makes, and the one it does not
 *
 * * **P2 (after a decline) is a gate** — §22 **DD-2**: "A Hammer is meaningful primarily: **after
 *   a decline**. If it appears randomly in the middle of a sideways market, its significance is
 *   limited" `[Ch.17-1 §Important Rule]`.
 * * **P3 (at support) is a gate too** — §8.1 lists it under *Preconditions*, and §8.7's own
 *   near-miss fixture is rejected on it: "so it is suppressed as 'not at support'".
 * * **DD-3 (wrong context) is a suppression, not a penalty** — §8.3 **I2**: the identical shape
 *   inside an uptrend is a **Hanging Man**, "a potential warning, not a hammer"
 *   `[Ch.17-1 §Hanging Man]`, where "the shape is identical. Context changes the interpretation."
 *   Scoring it low would still show the user a "Hammer".
 * * **Body colour is irrelevant** (§8.2 **S5**) — the shape is the pattern; only location decides
 *   whether it is a hammer or a hanging man. So neither `isBull` nor `isBear` is tested.
 *
 * ### No measured move
 *
 * §8.4 / §22 **DD-1**: `measuredMove = null` — `[Ch.17-1]` defines none.
 *
 * ### No pivots
 *
 * §4 rules 7 and 8: `Match.pivots` is empty.
 */
class HammerDetector : PatternDetector {

    /** Canonical `PatternIds` name — id `3`, an accuracy-table key (decision D6). */
    override val name: String = CandlestickNames.HAMMER

    override val category: Category = Category.CANDLESTICK

    /** See [BullishEngulfingDetector.minBars]. */
    override val minBars: Int = CandlestickPolicy.MIN_REQUIRED_BARS

    override fun detect(pivots: List<Pivot>, candles: List<Candle>): List<Match> {
        val timeframe = candles.firstOrNull()?.timeframe ?: return emptyList()
        // --- P1 — the global history gate is a precondition (§24 check 1) ----------------------
        if (!MatchScorePolicy.isSufficientBars(timeframe, candles.size)) return emptyList()
        if (candles.size < minBars) return emptyList()

        val signals = CandlestickSignals(candles)
        val lookback = CandlestickPolicy.contextLookback(timeframe)
        val decline = -CandlestickPolicy.declineThresholdPct(timeframe)
        val rise = CandlestickPolicy.riseThresholdPct(timeframe)
        // §8.1 P3 — the hammer forms at the context window's own low, within the location band.
        val locationBand = 1.0 + CandlestickPolicy.LOCATION_BAND_PCT / 100.0

        for (bar in CandlestickSupport.candidateSignalBars(candles, FORMATION_BARS)) {
            val contextFrom = bar - lookback
            if (bar - 1 < contextFrom) continue

            val signal = candles[bar]
            val shape = shapeOf(signal)
            if (shape.isFlat) continue

            // --- S1 — "long lower wick" (≥ half the bar's range) ------------------------------
            if (shape.lowerFrac < CandlestickPolicy.LONG_WICK_FRAC_MIN) continue
            // --- S2 — "little upper wick" ---------------------------------------------------
            if (shape.upperFrac > CandlestickPolicy.OPPOSITE_WICK_FRAC_MAX) continue
            // --- S3 — "small body" ----------------------------------------------------------
            if (shape.bodyFrac > CandlestickPolicy.SMALL_BODY_FRAC_MAX) continue
            // --- S4 — the wick is long *relative to the body* --------------------------------
            if (shape.lowerFrac < CandlestickPolicy.WICK_VS_BODY_MIN * shape.bodyFrac) continue

            // --- P2 — "after a decline" (a gate, DD-2) ---------------------------------------
            val priorTrend = signals.priorTrendPctAt(bar) ?: continue
            if (priorTrend > decline) continue

            // --- I2 / DD-3 — the same shape in an uptrend is a Hanging Man: suppress ---------
            if (priorTrend >= rise) continue

            // --- P3 — "forms exactly at a long-term support zone" (a precondition) ----------
            val contextLow = lowestLow(candles, contextFrom, bar - 1) ?: continue
            if (signal.low > contextLow * locationBand) continue

            // --- S6 — the primary geometry measure ------------------------------------------
            val wickRatio = shape.lowerWickRatio(signal.low)
            // The swing high that preceded the pattern — §6.5 C3's confirmation level.
            val precedingHigh = highestHigh(candles, contextFrom, bar - 1) ?: continue

            val penalties = ArrayList<Penalty>(6)
            val next = candles.getOrNull(bar + 1)

            // --- §8.3 I1 — "the book requires the next session to close above the hammer high"
            if (next != null && next.close <= signal.high) {
                penalties += Penalty.of(
                    PenaltyId.NO_CONFIRMATION,
                    "The next session did not close above the hammer's high — the book's own " +
                        "confirmation rule did not happen [Ch.17-4 §Candlesticks at Support and " +
                        "Resistance §Example 1].",
                )
            }

            // --- §8.3 I3 — structure contradicts ----------------------------------------------
            if (CandlestickSupport.structureContradicts(candles, bar, signal.low, bullish = true)) {
                penalties += CandlestickSupport.structureContradictsPenalty(
                    "A lower low printed in the next few sessions — market structure has not " +
                        "turned [Ch.17-4 §Layer 4].",
                )
            }

            // --- §8.3 I4 — volume --------------------------------------------------------
            val rvol = signals.rvolAt(bar)
            if (CandlestickSupport.isWeakVolume(rvol)) {
                penalties += CandlestickSupport.weakVolumePenalty(
                    "Volume on the hammer was below its own 20-session average [Ch.17-4 §Layer 3].",
                )
            }

            // --- §6.6 — a highly volatile market -------------------------------------------
            CandlestickSupport.volatileMarketPenalty(signals, bar)?.let { penalties += it }

            val measures = ArrayList<Measure>(24)
            measures += CandlestickScoring.geometryPlaceholders()
            // §3.1 G3 — "A 4:1 shadow-to-body is the book's 'long lower wick, small body' fully
            // realised".
            measures += CandlestickScoring.saturationGeometry("Lower wick / body", wickRatio, 4.0)
            measures += CandlestickScoring.structureMeasures(
                CandlestickScoring.StructureInputs(
                    signalBodyFrac = shape.bodyFrac,
                    wickMinusBodyFrac = CandlestickScoring.wickMinusBodyFrac(shape),
                    // §8.7: the context window is `[n−LOOKBACK, n−1]` — the hammer is one bar, so
                    // DD-11 excludes only bar `n` itself.
                    contextCleanliness = CandlestickScoring.contextCleanliness(
                        closesIn(candles, contextFrom, bar - 1),
                    ),
                    rangeOverAtr = CandlestickScoring.rangeOverAtr(candles, bar, signals.atrAt(bar)),
                    atWindowExtreme = signal.low <= contextLow,
                    trendlineRSquared = signals.trendlineRSquared,
                ),
            )
            measures += CandlestickScoring.confirmationMeasures(
                CandlestickScoring.ConfirmationInputs(
                    rvolOnSignal = rvol,
                    // §8.5 C1 — "`close[n+1] > high[n]`, the book's exact professional rule".
                    nextBarConfirmed = next?.let { it.close > signal.high },
                    // §5.3 market-structure layer: a close above the swing high that preceded
                    // the pattern, within 5 bars.
                    structureConfirmed = CandlestickSupport.structureConfirmed(
                        candles = candles,
                        signalBar = bar,
                        precedingExtreme = precedingHigh,
                        bullish = true,
                    ),
                    rsiOnSignal = signals.rsiAt(bar),
                    // §8.5 C4 — bullish RSI divergence over the last 20 bars.
                    divergence = signals.divergenceScore(
                        candles = candles,
                        from = contextFrom,
                        signalBar = bar,
                        bullish = true,
                    ),
                    // §8.5 C6 — "High delivery volume".
                    deliveryVsAverage = signals.deliveryVsAverageAt(candles, bar),
                ),
                bullish = true,
            )
            // §8.5 C7 — a selling climax `[Ch.21 §21.22]` is the one named confirmation input with
            // no slot in `scoring-spec.md` §5.2, so it enters at a supplemental weight.
            val climax = signals.isClimax(candles, bar, bullish = true)
            measures += Measure.binary(
                name = "Selling climax",
                hit = climax,
                weight = SUPPLEMENTAL_CONFIRMATION_WEIGHT,
                factor = ScoreFactor.CONFIRMATION,
            )
            measures += CandlestickScoring.contextMeasures(
                CandlestickScoring.ContextInputs(
                    priorTrendMagnitude = abs(priorTrend),
                    adxValue = signals.adxAt(bar),
                    diAligned = signals.diAlignedAt(bar, bullish = true),
                ),
            )

            val match = matchOf(
                detector = this,
                candles = candles,
                pivots = emptyList(),
                // §8.4 / DD-1 — `[Ch.17-1]` defines no projection for the hammer.
                measuredMove = null,
                measures = measures,
                penalties = penalties,
            )
            return if (CandlestickScoring.renderable(match.score)) listOf(match) else emptyList()
        }
        return emptyList()
    }

    private companion object {
        /** A hammer is a single bar; the next bar is its confirmation, not part of the pattern. */
        const val FORMATION_BARS: Int = 1

        /**
         * §8.5 **C7**'s weight: 10 % of the confirmation factor, matching the supplemental
         * geometry weight, so a named-but-unslotted confirmation input can never move a match by
         * a tier on its own.
         */
        const val SUPPLEMENTAL_CONFIRMATION_WEIGHT: Int = 10
    }
}