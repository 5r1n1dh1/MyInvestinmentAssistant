package com.mia.app.domain.pattern.detectors.candlestick

import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.Category
import com.mia.app.domain.model.Match
import com.mia.app.domain.model.MatchScorePolicy
import com.mia.app.domain.model.PatternDetector
import com.mia.app.domain.model.Pivot
import com.mia.app.domain.pattern.Penalty
import com.mia.app.domain.pattern.PenaltyId
import com.mia.app.domain.pattern.matchOf
import com.mia.app.domain.model.Measure
import kotlin.math.abs
import kotlin.math.min

/**
 * Detector 1 — **Bullish Engulfing** (`patterns-spec.md` §6, `[Ch.17-2 §Bullish Engulfing Pattern]`).
 *
 * The book: *"Day 1: Bearish candle. Day 2: Bullish candle. **The body completely engulfs the
 * previous candle's body.**"* `[Ch.17-2 §Structure]`
 *
 * ### Precondition, penalty, bonus — the distinctions §6 makes and why they matter
 *
 * * **P3 (the prior decline) is a hard gate** — §22 **DD-2**: "Bullish Engulfing patterns become
 *   more meaningful when they appear after a sustained decline … **Much less useful: during
 *   sideways markets, near major resistance, in overextended rallies**"
 *   `[Ch.17-2 §Poor Locations]`. A score-range penalty cannot rescue a pattern the book calls
 *   "much less useful".
 * * **Location is a penalty, not a gate** — §5.3 Layer 1 puts location in the five-layer check with
 *   `CTX_MISMATCH`, weighted **double** for this family (`scoring-spec.md` §7.1). §6 defines no
 *   location precondition for this detector, so none is invented.
 * * **S5 (the gap-down open) is a bonus, never a gate** — §6.2 S5: "Some classic candlestick
 *   definitions emphasize price gaps … Modern traders generally focus more on the shift in buying
 *   and selling pressure than on strict textbook gap requirements" `[Ch.17-2 §Gap Openings]`.
 *
 * ### Why there is no measured move
 *
 * §6.4 and §22 **DD-1**: `measuredMove = null`. `[Ch.17-2]` defines no projection for any
 * candlestick pattern and a two-bar formation has no height to measure, so any level would be an
 * invention — precisely what plan.md §1.4 rule 2 forbids.
 *
 * ### Why `pivots` is ignored
 *
 * §4 rules 7 and 8: candlestick detectors read `candles` directly and carry **no** pivots, because
 * a two-bar formation has no swing structure to draw. *Note for the orchestrator*: with no pivots
 * and no measured move, `PatternEngine.resolveDirection` returns `null` for this match unless the
 * caller passes a `directionResolver`, so the match may render but never project.
 */
class BullishEngulfingDetector : PatternDetector {

    /** Canonical `PatternIds` name — id `1`, an accuracy-table key (decision D6). */
    override val name: String = CandlestickNames.BULLISH_ENGULFING

    override val category: Category = Category.CANDLESTICK

    /**
     * The detector's own minimum window, smaller than `MatchScorePolicy.minBars(timeframe)` = 60
     * (`patterns-spec.md` §20): the 20-bar context look-back plus its two formation bars.
     */
    override val minBars: Int = CandlestickPolicy.MIN_REQUIRED_BARS

    override fun detect(pivots: List<Pivot>, candles: List<Candle>): List<Match> {
        val timeframe = candles.firstOrNull()?.timeframe ?: return emptyList()
        // --- P1 — the global history gate is a precondition, never a low score (§24 check 1) ---
        if (!MatchScorePolicy.isSufficientBars(timeframe, candles.size)) return emptyList()
        if (candles.size < minBars) return emptyList()

        val signals = CandlestickSignals(candles)
        val lookback = CandlestickPolicy.contextLookback(timeframe)
        val locationBand = 1.0 + CandlestickPolicy.LOCATION_BAND_PCT / 100.0

        // §4 rule 6: newest qualifying window first, at most one match per invocation.
        for (bar in CandlestickSupport.candidateSignalBars(candles, FORMATION_BARS)) {
            val previousBar = bar - 1
            val contextFrom = bar - lookback
            if (previousBar - 1 < contextFrom) continue

            val previous = candles[previousBar]
            val signal = candles[bar]
            val previousShape = shapeOf(previous)
            val signalShape = shapeOf(signal)
            if (previousShape.isFlat || signalShape.isFlat) continue

            // --- S1 — "Day 1: Bearish candle. Day 2: Bullish candle." [Ch.17-2 §Structure] ---
            if (!previousShape.isBear || !signalShape.isBull) continue

            // --- S2 — "The body completely engulfs the previous candle's body." ----------------
            // Strict inequalities, so a doji pair can never qualify (§6.2 S2).
            if (signal.open >= previous.close || signal.close <= previous.open) continue

            // --- S4 — engulfment depth, the primary geometry measure (§6.2 S4) ----------------
            val engulf = (signal.close - previous.open) / previousShape.body
            if (!(engulf > 0.0)) continue

            // --- S3 — "The second body is significantly larger than the first" (1.20, [derived])
            if (signalShape.body < CandlestickPolicy.ENGULF_BODY_RATIO_MIN * previousShape.body) continue

            // --- P3 — "after a sustained decline", a gate (DD-2) -------------------------------
            // The trend is read at `n−2`, the last bar *outside* the pair, exactly as §6.1 P3
            // writes it — and exactly as DD-11 requires, so the pattern's own bars can never
            // supply the decline that is supposed to make it meaningful.
            val priorTrend = signals.priorTrendPctAt(previousBar - 1) ?: continue
            if (priorTrend > -CandlestickPolicy.declineThresholdPct(timeframe)) continue

            val contextLow = lowestLow(candles, contextFrom, previousBar) ?: continue
            val contextHigh = highestHigh(candles, contextFrom, previousBar) ?: continue
            val pairLow = min(previous.low, signal.low)

            val penalties = ArrayList<Penalty>(6)

            // --- §5.3 Layer 1 — location, the only CTX_MISMATCH source for this detector --------
            if (pairLow > contextLow * locationBand) {
                penalties += CandlestickSupport.contextMismatchPenalty(
                    "The pair did not form within ${CandlestickPolicy.LOCATION_BAND_PCT}% of the " +
                        "lowest low of the preceding $lookback bars — location matters more than " +
                        "the candle itself [Ch.17-4 §Layer 1].",
                )
            }

            val next = candles.getOrNull(bar + 1)

            // --- §6.3 I1 — the whole engulfing body retraced within one bar -------------------
            if (next != null && next.close <= previous.open) {
                penalties += Penalty.of(
                    PenaltyId.NO_FOLLOW_THROUGH,
                    "The next session closed back inside the first candle's body — no " +
                        "follow-through [Ch.17-2 §Confirmation].",
                )
            }

            // --- §6.3 I2 — bull trap [Ch.18-5 §Bull Trap] ------------------------------------
            if (next != null && next.high > signal.close && next.close < signal.close) {
                penalties += Penalty.of(
                    PenaltyId.BULL_TRAP,
                    "Price traded above the reversal close and closed back below it in the same " +
                        "session — a bull trap [Ch.18-5 §Bull Trap].",
                )
            }

            // --- §6.3 I3 — "the broader downtrend remains intact" -----------------------------
            if (CandlestickSupport.structureContradicts(candles, bar, signal.low, bullish = true)) {
                penalties += CandlestickSupport.structureContradictsPenalty(
                    "A lower low printed in the next few sessions — the pattern may be only a " +
                        "temporary bounce [Ch.17-2 §Combining with Market Structure].",
                )
            }

            // --- §6.3 I4 — a strong close above the prior high on thin volume ------------------
            val rvol = signals.rvolAt(bar)
            if (signal.close > previous.high && CandlestickSupport.isWeakVolume(rvol)) {
                penalties += CandlestickSupport.weakVolumePenalty(
                    "The reversal closed above the prior candle's high on volume below its own " +
                        "20-session average [Ch.17-2 §The Importance of Volume].",
                )
            }

            // --- §6.5 C6 — big volume with no ownership transfer ------------------------------
            CandlestickSupport.deliveryChurnPenalty(signals, candles, bar)?.let { penalties += it }

            // --- §6.6 — a highly volatile market ---------------------------------------------
            CandlestickSupport.volatileMarketPenalty(signals, bar)?.let { penalties += it }

            // --- measures ---------------------------------------------------------------------
            val measures = ArrayList<Measure>(24)
            measures += CandlestickScoring.geometryPlaceholders()
            // §3.1 G3: "Deeper engulfment ⇒ a truer 'complete engulfment'".
            measures += CandlestickScoring.saturationGeometry("Engulfment", engulf, 1.0)
            // §6.2 S5 — the gap-down open bonus, explicitly never a gate.
            measures += CandlestickScoring.saturationGeometry(
                "Gap-down open",
                if (signal.open <= previous.close) 1.0 else 0.0,
                1.0,
            )
            measures += CandlestickScoring.structureMeasures(
                CandlestickScoring.StructureInputs(
                    signalBodyFrac = signalShape.bodyFrac,
                    wickMinusBodyFrac = CandlestickScoring.wickMinusBodyFrac(signalShape),
                    // DD-11: the context window never contains the pattern's own bars.
                    contextCleanliness = CandlestickScoring.contextCleanliness(
                        closesIn(candles, contextFrom, previousBar),
                    ),
                    rangeOverAtr = CandlestickScoring.rangeOverAtr(candles, bar, signals.atrAt(bar)),
                    atWindowExtreme = pairLow <= contextLow,
                    trendlineRSquared = signals.trendlineRSquared,
                ),
            )
            measures += CandlestickScoring.confirmationMeasures(
                CandlestickScoring.ConfirmationInputs(
                    rvolOnSignal = rvol,
                    // §6.3 C2: "`close[n+1] > close[n]` for 1–2 sessions"; `null` = no next bar yet.
                    nextBarConfirmed = next?.let { it.close > signal.close },
                    // §6.5 C3: "a close above the swing high that preceded the pattern, within
                    // 5 bars" — "Break Above Lower High".
                    structureConfirmed = CandlestickSupport.structureConfirmed(
                        candles = candles,
                        signalBar = bar,
                        precedingExtreme = contextHigh,
                        bullish = true,
                    ),
                    rsiOnSignal = signals.rsiAt(bar),
                    // §6.5 C4: "bullish RSI divergence … or `rsi[n] < 30`".
                    divergence = signals.divergenceScore(
                        candles = candles,
                        from = contextFrom,
                        signalBar = bar,
                        bullish = true,
                    ),
                    deliveryVsAverage = signals.deliveryVsAverageAt(candles, bar),
                ),
                bullish = true,
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
                // §4 rule 7 — candlestick detectors consume no pivots.
                pivots = emptyList(),
                measures = measures,
                // §6.4 / DD-1 — the book defines no measured move for a candlestick pattern.
                measuredMove = null,
                penalties = penalties,
            )
            // §4 rule 4: a match below the suppress threshold is never returned. The most recent
            // qualifying instance is reported; an older one is not promoted in its place.
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
        /** A bullish engulfing occupies two bars: the bearish day and the engulfing day. */
        const val FORMATION_BARS: Int = 2
    }
}