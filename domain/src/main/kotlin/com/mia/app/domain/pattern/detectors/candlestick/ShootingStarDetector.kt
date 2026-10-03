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

/**
 * Detector 4 — **Shooting Star** (`patterns-spec.md` §9, `[Ch.17-1 §Shooting Star]`).
 *
 * "Mirror of §8" — the book gives the same three adjectives as the hammer with the wick on the
 * other side: *"small body, long upper shadow, little lower shadow"* `[Ch.17-1 §Shooting Star]`.
 * Every threshold is `[derived]` exactly as in §8.
 *
 * ### Why this detector has the sharpest context gates in the family
 *
 * `[Ch.17-1 §Pattern vs Context]` is unusually explicit for a candlestick: *"Hammer after a long
 * rally may be meaningless … Shooting Star at resistance after extended rally → stronger
 * bearish implication"*. So:
 *
 * * **P2 (after a rally) is a gate** — §22 **DD-2**.
 * * **P3 (at resistance) is a gate** — §9 lists it alongside P2 as a precondition.
 * * **DD-3 (wrong context) is a suppression** — §9's **I2**: the identical shape in a *downtrend*
 *   is an **Inverted Hammer**, "potentially bullish", so it must not be shown as a Shooting Star.
 *
 * ### No measured move, no pivots
 *
 * §22 **DD-1** (`measuredMove = null`, §9 gives none) and §4 rules 7–8 (`Match.pivots` is empty).
 */
class ShootingStarDetector : PatternDetector {

    /** Canonical `PatternIds` name — id `4`, an accuracy-table key (decision D6). */
    override val name: String = CandlestickNames.SHOOTING_STAR

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
        val rise = CandlestickPolicy.riseThresholdPct(timeframe)
        val decline = -CandlestickPolicy.declineThresholdPct(timeframe)
        val locationBand = 1.0 - CandlestickPolicy.LOCATION_BAND_PCT / 100.0

        for (bar in CandlestickSupport.candidateSignalBars(candles, FORMATION_BARS)) {
            val contextFrom = bar - lookback
            if (bar - 1 < contextFrom) continue

            val signal = candles[bar]
            val shape = shapeOf(signal)
            if (shape.isFlat) continue

            // --- S1 — "long upper wick" --------------------------------------------------------
            if (shape.upperFrac < CandlestickPolicy.LONG_WICK_FRAC_MIN) continue
            // --- S2 — "little lower wick" -----------------------------------------------------
            if (shape.lowerFrac > CandlestickPolicy.OPPOSITE_WICK_FRAC_MAX) continue
            // --- S3 — "small body" ------------------------------------------------------------
            if (shape.bodyFrac > CandlestickPolicy.SMALL_BODY_FRAC_MAX) continue
            // --- S4 — long *relative to the body* ---------------------------------------------
            if (shape.upperFrac < CandlestickPolicy.WICK_VS_BODY_MIN * shape.bodyFrac) continue
            // Body colour is irrelevant (§8.2 S5, the same shape as the hammer).

            // --- P2 — "after an extended rally" (a gate, DD-2) --------------------------------
            val priorTrend = signals.priorTrendPctAt(bar) ?: continue
            if (priorTrend < rise) continue

            // --- I2 / DD-3 — the same shape in a downtrend is an Inverted Hammer: suppress -----
            if (priorTrend <= decline) continue

            // --- P3 — "Shooting Star at resistance" (a precondition) ---------------------------
            val contextHigh = highestHigh(candles, contextFrom, bar - 1) ?: continue
            if (signal.high < contextHigh * locationBand) continue

            // --- S6 — the primary geometry measure --------------------------------------------
            val wickRatio = shape.upperWickRatio(signal.high)
            // §9 C2's mirror of §6.5 C3: the swing low that preceded the pattern.
            val precedingLow = lowestLow(candles, contextFrom, bar - 1) ?: continue

            val penalties = ArrayList<Penalty>(6)
            val next = candles.getOrNull(bar + 1)

            // --- §9 I1 — "Next day: Bearish confirmation" [Ch.17-4 §Example 2] ----------------
            if (next != null && next.close >= signal.low) {
                penalties += Penalty.of(
                    PenaltyId.NO_CONFIRMATION,
                    "The next session did not close below the shooting star's low — the book's own " +
                        "confirmation rule did not happen [Ch.17-4 §Example 2].",
                )
            }

            // --- §8.3 I3, mirrored — structure contradicts -------------------------------------
            if (CandlestickSupport.structureContradicts(candles, bar, signal.high, bullish = false)) {
                penalties += CandlestickSupport.structureContradictsPenalty(
                    "A higher high printed in the next few sessions — market structure has not " +
                        "turned [Ch.17-4 §Layer 4].",
                )
            }

            // --- §8.3 I4 — volume --------------------------------------------------------------
            val rvol = signals.rvolAt(bar)
            if (CandlestickSupport.isWeakVolume(rvol)) {
                penalties += CandlestickSupport.weakVolumePenalty(
                    "Volume on the shooting star was below its own 20-session average " +
                        "[Ch.17-4 §Layer 3].",
                )
            }

            // --- §6.6 — a highly volatile market ----------------------------------------------
            CandlestickSupport.volatileMarketPenalty(signals, bar)?.let { penalties += it }

            val measures = ArrayList<Measure>(24)
            measures += CandlestickScoring.geometryPlaceholders()
            // §3.1 G3 — the mirror of the hammer's wick ratio.
            measures += CandlestickScoring.saturationGeometry("Upper wick / body", wickRatio, 4.0)
            measures += CandlestickScoring.structureMeasures(
                CandlestickScoring.StructureInputs(
                    signalBodyFrac = shape.bodyFrac,
                    wickMinusBodyFrac = CandlestickScoring.wickMinusBodyFrac(shape),
                    contextCleanliness = CandlestickScoring.contextCleanliness(
                        closesIn(candles, contextFrom, bar - 1),
                    ),
                    rangeOverAtr = CandlestickScoring.rangeOverAtr(candles, bar, signals.atrAt(bar)),
                    atWindowExtreme = signal.high >= contextHigh,
                    trendlineRSquared = signals.trendlineRSquared,
                ),
            )
            measures += CandlestickScoring.confirmationMeasures(
                CandlestickScoring.ConfirmationInputs(
                    rvolOnSignal = rvol,
                    nextBarConfirmed = next?.let { it.close < signal.low },
                    structureConfirmed = CandlestickSupport.structureConfirmed(
                        candles = candles,
                        signalBar = bar,
                        precedingExtreme = precedingLow,
                        bullish = false,
                    ),
                    rsiOnSignal = signals.rsiAt(bar),
                    // §9 — "bearish RSI divergence".
                    divergence = signals.divergenceScore(
                        candles = candles,
                        from = contextFrom,
                        signalBar = bar,
                        bullish = false,
                    ),
                    deliveryVsAverage = signals.deliveryVsAverageAt(candles, bar),
                ),
                bullish = false,
            )
            // §9's buying-climax confirmation, `[Ch.21 §21.22]` — the mirror of §8.5 C7.
            measures += Measure.binary(
                name = "Buying climax",
                hit = signals.isClimax(candles, bar, bullish = false),
                weight = SUPPLEMENTAL_CONFIRMATION_WEIGHT,
                factor = ScoreFactor.CONFIRMATION,
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
                // §9 / DD-1 — no measured move for a candlestick pattern.
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
        /** A shooting star is a single bar; the next bar is its confirmation. */
        const val FORMATION_BARS: Int = 1

        /** §9's buying-climax weight — the mirror of [HammerDetector]'s selling-climax weight. */
        const val SUPPLEMENTAL_CONFIRMATION_WEIGHT: Int = 10
    }
}