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
import com.mia.app.domain.pattern.matchOf
import kotlin.math.abs
import kotlin.math.max

/**
 * Detector 2 — **Bearish Engulfing** (`patterns-spec.md` §7, `[Ch.17-2 §Bearish Engulfing Pattern]`).
 *
 * "Exact mirror of §6 with every sign flipped and the context precondition inverted" — the book:
 *"Day 1: Bullish candle. Day 2: Bearish candle. **The body completely engulfs the previous
 * candle's body.**"* `[Ch.17-2 §Structure]`
 *
 * ### The one place the mirror is *not* symmetric
 *
 * §7's confirmation list ends with a statement that has no bullish twin and that the measure
 * handles naturally: `deliveryVsAvg >= 1.00` **confirms** this pattern, because "high delivery +
 * falling price = selling pressure" `[Ch.21 §21.19]`. High delivery is evidence of *ownership
 * changing hands*; on a down bar that is distribution, on an up bar it is accumulation. Both
 * directions read the same number and reach opposite conclusions, which is why K5 is a bare
 * reading rather than a signed one.
 *
 * ### No measured move
 *
 * §22 **DD-1** / §7: `measuredMove = null` — `[Ch.17-2]` defines no projection for any candlestick
 * pattern, so any level would be invented.
 *
 * ### No pivots
 *
 * §4 rules 7 and 8: candlestick detectors read `candles` directly; `Match.pivots` is empty.
 */
class BearishEngulfingDetector : PatternDetector {

    /** Canonical `PatternIds` name — id `2`, an accuracy-table key (decision D6). */
    override val name: String = CandlestickNames.BEARISH_ENGULFING

    override val category: Category = Category.CANDLESTICK

    /** See [BullishEngulfingDetector.minBars]; the family shares one window. */
    override val minBars: Int = CandlestickPolicy.MIN_REQUIRED_BARS

    override fun detect(pivots: List<Pivot>, candles: List<Candle>): List<Match> {
        val timeframe = candles.firstOrNull()?.timeframe ?: return emptyList()
        // --- P1 — the global history gate is a precondition, never a low score (§24 check 1) ---
        if (!MatchScorePolicy.isSufficientBars(timeframe, candles.size)) return emptyList()
        if (candles.size < minBars) return emptyList()

        val signals = CandlestickSignals(candles)
        val lookback = CandlestickPolicy.contextLookback(timeframe)
        val locationBand = 1.0 - CandlestickPolicy.LOCATION_BAND_PCT / 100.0

        for (bar in CandlestickSupport.candidateSignalBars(candles, FORMATION_BARS)) {
            val previousBar = bar - 1
            val contextFrom = bar - lookback
            if (previousBar - 1 < contextFrom) continue

            val previous = candles[previousBar]
            val signal = candles[bar]
            val previousShape = shapeOf(previous)
            val signalShape = shapeOf(signal)
            if (previousShape.isFlat || signalShape.isFlat) continue

            // --- S1 — isBull(n−1), isBear(n) --------------------------------------------------
            if (!previousShape.isBull || !signalShape.isBear) continue

            // --- S2 — "The body completely engulfs the previous candle's body", strict ---------
            if (signal.open <= previous.close || signal.close >= previous.open) continue

            // --- S4 — engulfment depth, `engulf = (open[n−1] − close[n]) / body[n−1]` ----------
            val engulf = (previous.open - signal.close) / previousShape.body
            if (!(engulf > 0.0)) continue

            // --- S3 — "significantly larger", 1.20 [derived] -----------------------------------
            if (signalShape.body < CandlestickPolicy.ENGULF_BODY_RATIO_MIN * previousShape.body) continue

            // --- P3 — "Most meaningful: after prolonged rallies" — a gate (§7, DD-2) ------------
            // Read at `n−2`, the last bar outside the pair, as §7's mirror of §6.1 P3 says and as
            // DD-11 requires.
            val priorTrend = signals.priorTrendPctAt(previousBar - 1) ?: continue
            if (priorTrend < CandlestickPolicy.riseThresholdPct(timeframe)) continue

            val contextLow = lowestLow(candles, contextFrom, previousBar) ?: continue
            val contextHigh = highestHigh(candles, contextFrom, previousBar) ?: continue
            val pairHigh = max(previous.high, signal.high)

            val penalties = ArrayList<Penalty>(6)

            // --- §5.3 Layer 1 — location -------------------------------------------------------
            if (pairHigh < contextHigh * locationBand) {
                penalties += CandlestickSupport.contextMismatchPenalty(
                    "The pair did not form within ${CandlestickPolicy.LOCATION_BAND_PCT}% of the " +
                        "highest high of the preceding $lookback bars — location matters more than " +
                        "the candle itself [Ch.17-4 §Layer 1].",
                )
            }

            val next = candles.getOrNull(bar + 1)

            // --- §7 I1 — the whole engulfing body retraced within one bar ---------------------
            if (next != null && next.close >= previous.open) {
                penalties += Penalty.of(
                    PenaltyId.NO_FOLLOW_THROUGH,
                    "The next session closed back inside the first candle's body — no " +
                        "follow-through [Ch.17-2 §Confirmation].",
                )
            }

            // --- §7 I2 — bear trap [Ch.18-5 §Bear Trap] --------------------------------------
            if (next != null && next.low < signal.close && next.close > signal.close) {
                penalties += Penalty.of(
                    PenaltyId.BEAR_TRAP,
                    "Price traded below the reversal close and closed back above it in the same " +
                        "session — a bear trap [Ch.18-5 §Bear Trap].",
                )
            }

            // --- §7 I3 — the broader uptrend remains intact ----------------------------------
            if (CandlestickSupport.structureContradicts(candles, bar, signal.high, bullish = false)) {
                penalties += CandlestickSupport.structureContradictsPenalty(
                    "A higher high printed in the next few sessions — the pattern may be only a " +
                        "temporary pullback [Ch.17-2 §Combining with Market Structure].",
                )
            }

            // --- §7 I4 — a strong close below the prior low on thin volume ---------------------
            val rvol = signals.rvolAt(bar)
            if (signal.close < previous.low && CandlestickSupport.isWeakVolume(rvol)) {
                penalties += CandlestickSupport.weakVolumePenalty(
                    "The reversal closed below the prior candle's low on volume below its own " +
                        "20-session average [Ch.17-2 §The Importance of Volume].",
                )
            }

            // --- §6.6 — a highly volatile market ---------------------------------------------
            CandlestickSupport.volatileMarketPenalty(signals, bar)?.let { penalties += it }

            val measures = ArrayList<Measure>(24)
            measures += CandlestickScoring.geometryPlaceholders()
            // §3.1 G3 — the mirror of detector 1.
            measures += CandlestickScoring.saturationGeometry("Engulfment", engulf, 1.0)
            // §7 S5 — the gap-up open bonus, never a gate.
            measures += CandlestickScoring.saturationGeometry(
                "Gap-up open",
                if (signal.open >= previous.close) 1.0 else 0.0,
                1.0,
            )
            measures += CandlestickScoring.structureMeasures(
                CandlestickScoring.StructureInputs(
                    signalBodyFrac = signalShape.bodyFrac,
                    wickMinusBodyFrac = CandlestickScoring.wickMinusBodyFrac(signalShape),
                    contextCleanliness = CandlestickScoring.contextCleanliness(
                        closesIn(candles, contextFrom, previousBar),
                    ),
                    rangeOverAtr = CandlestickScoring.rangeOverAtr(candles, bar, signals.atrAt(bar)),
                    atWindowExtreme = pairHigh >= contextHigh,
                    trendlineRSquared = signals.trendlineRSquared,
                ),
            )
            measures += CandlestickScoring.confirmationMeasures(
                CandlestickScoring.ConfirmationInputs(
                    rvolOnSignal = rvol,
                    nextBarConfirmed = next?.let { it.close < signal.close },
                    // §7: "close below the preceding swing low" within 5 bars.
                    structureConfirmed = CandlestickSupport.structureConfirmed(
                        candles = candles,
                        signalBar = bar,
                        precedingExtreme = contextLow,
                        bullish = false,
                    ),
                    rsiOnSignal = signals.rsiAt(bar),
                    // §7: "bearish RSI divergence".
                    divergence = signals.divergenceScore(
                        candles = candles,
                        from = contextFrom,
                        signalBar = bar,
                        bullish = false,
                    ),
                    // §7: "high delivery + falling price = selling pressure [Ch.21 §21.19], which
                    // **confirms** this bearish pattern".
                    deliveryVsAverage = signals.deliveryVsAverageAt(candles, bar),
                ),
                bullish = false,
            )
            measures += CandlestickScoring.contextMeasures(
                CandlestickScoring.ContextInputs(
                    priorTrendMagnitude = abs(priorTrend),
                    adxValue = signals.adxAt(bar),
                    diAligned = signals.diAlignedAt(bar, bullish = false),
                ),
            )

            val match = matchOf(
                detector = this,
                candles = candles,
                pivots = emptyList(),
                // §7 / DD-1 — no measured move for a candlestick pattern.
                measuredMove = null,
                measures = measures,
                penalties = penalties,
            )
            // The render gate is NOT applied here. A sub-threshold candidate is returned and the
            // engine decides its fate: `PatternEngine` routes anything under
            // `MatchScorePolicy.SUPPRESS_THRESHOLD` into `suppressed`, never into `matches`.
            //
            // Filtering here as well was a second, redundant copy of that gate, and it destroyed
            // the candidate: a Hammer scoring 47 reached neither `matches` nor `suppressed`, so it
            // was recorded nowhere and the UI could not explain its absence. Measured over 20 real
            // large caps x 372 sessions, the candlestick detectors that never appeared at any
            // window depth were Shooting Star, Morning Star and Evening Star — invisible rather
            // than absent.
            return listOf(match)
        }
        return emptyList()
    }

    private companion object {
        /** A bearish engulfing occupies two bars. */
        const val FORMATION_BARS: Int = 2
    }
}