package com.mia.app.domain.pattern.detectors.candlestick

import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.Category
import com.mia.app.domain.model.Match
import com.mia.app.domain.model.MatchScorePolicy
import com.mia.app.domain.model.PatternDetector
import com.mia.app.domain.model.Pivot
import com.mia.app.domain.pattern.Measure
import com.mia.app.domain.pattern.PatternScoringPolicy
import com.mia.app.domain.pattern.Penalty
import com.mia.app.domain.pattern.PenaltyId
import com.mia.app.domain.pattern.matchOf
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Detector 5 — the **star family**: `Match.name` = "Morning Star" (bullish) or "Evening Star"
 * (bearish). `patterns-spec.md` §10, `[Ch.17-3 §Morning Star]`, `[Ch.17-3 §Evening Star]`.
 *
 * §10 says explicitly: "One class, `StarDetector`, emitting both directions. `Match.name` =
 * `"Morning Star"` or `"Evening Star"`; `Category` = `CANDLESTICK`." Each direction owns its own
 * `PatternIds` constant — 5 and 6 — because the accuracy table is keyed per pattern name
 * (decision D6); the class registers itself under the morning-star name so
 * `PatternIds.of(detector.name)` is canonical.
 *
 * ### The middle candle's colour is irrelevant — deliberately
 *
 * §10.2 **S2**: "Small body. Can be: small bullish, small bearish, **doji**." A middle candle with
 * `bodyFrac > 0.30` is not a star. Only its *size* is tested, which is also what makes the
 * `starRatio` measure meaningful ("smaller middle body ⇒ a purer 'hesitation' candle").
 *
 * ### The penetration rule the spec had to be corrected twice to state (§10.7)
 *
 * "Closes **well into** the body of Candle 1" `[Ch.17-3]` is
 *
 * ```
 * Morning Star:  pen = (close[n] − open[n−2]) / body[n−2]  ≥ 0.50
 * Evening Star:  pen = (open[n−2] − close[n]) / body[n−2]  ≥ 0.50
 * ```
 *
 * measured from the **first candle's open**, not its close: recovering only to the first candle's
 * close has recovered *none* of its body.
 *
 * ### No measured move, no pivots
 *
 * §10.4 / §22 **DD-1** (`measuredMove = null`; `[Ch.17-3]` defines none) and §4 rules 7–8
 * (`Match.pivots` is empty).
 */
class StarDetector : PatternDetector {

    /**
     * The registered detector name — the morning-star variant, `PatternIds` id `5`.
     *
     * The bearish variant carries its own name on the `Match` ([CandlestickNames.EVENING_STAR]),
     * so both directions keep their own accuracy-table rows.
     */
    override val name: String = CandlestickNames.MORNING_STAR

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
        val locationBandUp = 1.0 + CandlestickPolicy.LOCATION_BAND_PCT / 100.0
        val locationBandDown = 1.0 - CandlestickPolicy.LOCATION_BAND_PCT / 100.0
        val exhaustionLookback = CandlestickPolicy.exhaustionLookback(timeframe)

        for (bar in CandlestickSupport.candidateSignalBars(candles, FORMATION_BARS)) {
            val firstBar = bar - 2
            val contextFrom = bar - lookback
            // --- P2 / DD-11 — the context window ends at `n−3`, never inside the triple -------
            if (firstBar - 1 < contextFrom) continue

            val first = candles[firstBar]
            val middle = candles[firstBar + 1]
            val third = candles[bar]
            val firstShape = shapeOf(first)
            val middleShape = shapeOf(middle)
            val thirdShape = shapeOf(third)
            if (firstShape.isFlat || middleShape.isFlat || thirdShape.isFlat) continue

            // --- S1 — "Large … candle" ---------------------------------------------------------
            if (firstShape.bodyFrac < CandlestickPolicy.STAR_LARGE_BODY_FRAC_MIN) continue
            // --- S2 — "Small body. Can be: small bullish, small bearish, doji" -----------------
            if (middleShape.bodyFrac > CandlestickPolicy.STAR_SMALL_BODY_FRAC_MAX) continue
            // --- S3 — "Large … candle" of the opposite colour to candle 1 ----------------------
            if (thirdShape.bodyFrac < CandlestickPolicy.STAR_LARGE_BODY_FRAC_MIN) continue

            val morningStar = firstShape.isBear && thirdShape.isBull
            val eveningStar = firstShape.isBull && thirdShape.isBear
            if (!morningStar && !eveningStar) continue
            val bullish = morningStar

            // --- S4 — penetration into candle 1's body, from candle 1's OPEN (§10.7) ---------
            val pen = if (morningStar) {
                (third.close - first.open) / firstShape.body
            } else {
                (first.open - third.close) / firstShape.body
            }
            if (pen < CandlestickPolicy.STAR_PENETRATION_MIN) continue

            // --- P3 — "Most meaningful: after prolonged declines / rallies" (a gate, DD-2) ----
            // Read at `n−3`, the last bar outside the triple, exactly as §10.1 P3 writes it and as
            // DD-11 requires.
            val priorTrend = signals.priorTrendPctAt(firstBar - 1) ?: continue
            val threshold = if (bullish) {
                -CandlestickPolicy.declineThresholdPct(timeframe)
            } else {
                CandlestickPolicy.riseThresholdPct(timeframe)
            }
            if (if (bullish) priorTrend > threshold else priorTrend < threshold) continue

            // --- P4 — "near strong support / at demand zones" (a precondition) --------------
            val contextLow = lowestLow(candles, contextFrom, firstBar - 1) ?: continue
            val contextHigh = highestHigh(candles, contextFrom, firstBar - 1) ?: continue
            if (bullish && first.low > contextLow * locationBandUp) continue
            if (!bullish && first.high < contextHigh * locationBandDown) continue

            // --- S6 — the two geometry readings ---------------------------------------------
            val smallestLargeBody = min(firstShape.body, thirdShape.body)
            val starRatio = if (smallestLargeBody > 0.0) middleShape.body / smallestLargeBody else 0.0
            val bodyRatio = if (thirdShape.body > 0.0) firstShape.body / thirdShape.body else 0.0

            val penalties = ArrayList<Penalty>(6)
            val next = candles.getOrNull(bar + 1)

            // --- §10.3 I1 — "the third candle has very low volume" --------------------------
            val rvol = signals.rvolAt(bar)
            val firstRvol = signals.rvolAt(firstBar)
            if (
                CandlestickSupport.isWeakVolume(rvol) &&
                firstRvol != null && firstRvol >= CandlestickPolicy.RVOL_CONFIRM
            ) {
                penalties += CandlestickSupport.weakVolumePenalty(
                    "The first candle expanded volume but the third candle did not — the reversal " +
                        "may lack broad participation [Ch.17-3 §Role of Volume].",
                )
            }

            // --- §10.3 I2 — follow-through ------------------------------------------------
            if (next != null) {
                val failed = if (bullish) next.close <= first.open else next.close >= first.open
                if (failed) {
                    penalties += Penalty.of(
                        PenaltyId.NO_FOLLOW_THROUGH,
                        "The next session closed back through candle 1's open — the star's " +
                            "follow-through did not happen [Ch.17-3 §Importance of Confirmation].",
                    )
                }
            }

            // --- §10.3 I3 — a close back inside candle 1's body within TRAP_WINDOW ---------
            trapPenalty(candles, bar, first, bullish)?.let { penalties += it }

            // --- §10.3 I4 — "bars [n+1, n+5] continue the prior trend" ---------------------
            if (CandlestickSupport.structureContradicts(candles, bar, if (bullish) third.low else third.high, bullish)) {
                penalties += CandlestickSupport.structureContradictsPenalty(
                    "Price continued the prior trend in the next few sessions — the star did not " +
                        "reverse it [Ch.17-3 §Combining with Market Structure].",
                )
            }

            // --- §10.3 I5 — "buying exhaustion" after a 40 % run-up ------------------------
            val exhaustionBase = candles.getOrNull(bar - exhaustionLookback)?.close
            if (exhaustionBase != null && exhaustionBase > 0.0 &&
                third.close >= CandlestickPolicy.EXHAUSTION_MULTIPLE * exhaustionBase
            ) {
                penalties += Penalty.of(
                    PenaltyId.EXHAUSTION,
                    "The star completed after a ${CandlestickPolicy.EXHAUSTION_MULTIPLE}× run-up " +
                        "over the last $exhaustionLookback bars — it may indicate buying " +
                        "exhaustion [Ch.17-3 §When to Be Careful].",
                )
            }

            // --- §6.6 — a highly volatile market -----------------------------------------
            CandlestickSupport.volatileMarketPenalty(signals, bar)?.let { penalties += it }

            val measures = ArrayList<Measure>(26)
            measures += CandlestickScoring.geometryPlaceholders()
            // §3.1 G3 — "Smaller middle body ⇒ a purer 'hesitation' candle" [Ch.17-3 §Candle 2].
            measures += CandlestickScoring.decayGeometry(
                "Star ratio",
                starRatio,
                PatternScoringPolicy.GEOM_STARRATIO_PERFECT,
                PatternScoringPolicy.GEOM_STARRATIO_FLOOR,
            )
            // §3.1 G3 — "Closes well into the body of Candle 1".
            measures += CandlestickScoring.linearGeometry(
                "Penetration of candle 1",
                max(0.0, pen),
                PatternScoringPolicy.GEOM_PEN_LO,
                PatternScoringPolicy.GEOM_PEN_HI,
            )
            // §10.2 S6 — "bodyRatio … the balance of the two large candles", a measure §3.1 has no
            // slot for, so it enters at the supplemental weight.
            measures += CandlestickScoring.ratioEqualGeometry(
                "Balance of the two large candles",
                bodyRatio,
                target = 1.0,
                tolerance = 1.0,
            )
            // §10.2 S5 — the indented/gapped "star" candle, explicitly a bonus only
            // ("Indian daily gaps are common but modern practice emphasises the shift in
            // control, not the gap").
            measures += CandlestickScoring.saturationGeometry(
                "Star candle indented",
                if (bullish && middle.low < first.low) 1.0 else 0.0,
                1.0,
            )
            measures += CandlestickScoring.structureMeasures(
                CandlestickScoring.StructureInputs(
                    signalBodyFrac = thirdShape.bodyFrac,
                    wickMinusBodyFrac = CandlestickScoring.wickMinusBodyFrac(thirdShape),
                    contextCleanliness = CandlestickScoring.contextCleanliness(
                        closesIn(candles, contextFrom, firstBar - 1),
                    ),
                    rangeOverAtr = CandlestickScoring.rangeOverAtr(candles, bar, signals.atrAt(bar)),
                    // §4.3 S6 — "the signal bar(s) sit at the extreme of the context window"; for a
                    // three-bar formation the whole triple is the signal.
                    atWindowExtreme = if (bullish) {
                        (lowestLow(candles, firstBar, bar) ?: 0.0) <= contextLow
                    } else {
                        (highestHigh(candles, firstBar, bar) ?: 0.0) >= contextHigh
                    },
                    trendlineRSquared = signals.trendlineRSquared,
                ),
            )
            measures += CandlestickScoring.confirmationMeasures(
                CandlestickScoring.ConfirmationInputs(
                    rvolOnSignal = rvol,
                    // §10.3 I2's positive form — the same close-based test, read as a measure.
                    nextBarConfirmed = next?.let {
                        if (bullish) it.close > third.close else it.close < third.close
                    },
                    // §10.5 C2 — a close beyond the swing extreme that preceded the triple.
                    structureConfirmed = CandlestickSupport.structureConfirmed(
                        candles = candles,
                        signalBar = bar,
                        precedingExtreme = if (bullish) contextHigh else contextLow,
                        bullish = bullish,
                    ),
                    rsiOnSignal = signals.rsiAt(bar),
                    // §10.5 C3 — "`rsi[n] < 30` (morning) / `> 70` (evening), or a divergence".
                    divergence = signals.divergenceScore(
                        candles = candles,
                        from = contextFrom,
                        signalBar = bar,
                        bullish = bullish,
                    ),
                    deliveryVsAverage = signals.deliveryVsAverageAt(candles, bar),
                ),
                bullish = bullish,
            )
            measures += CandlestickScoring.contextMeasures(
                CandlestickScoring.ContextInputs(
                    priorTrendMagnitude = abs(priorTrend),
                    adxValue = signals.adxAt(bar),
                    diAligned = signals.diAlignedAt(bar, bullish = bullish),
                ),
            )

            val match = matchOf(
                detector = this,
                candles = candles,
                pivots = emptyList(),
                // §10.4 / DD-1 — no measured move for a candlestick pattern.
                measuredMove = null,
                measures = measures,
                penalties = penalties,
                name = if (bullish) CandlestickNames.MORNING_STAR else CandlestickNames.EVENING_STAR,
            )
            return if (CandlestickScoring.renderable(match.score)) listOf(match) else emptyList()
        }
        return emptyList()
    }

    /**
     * §10.3 **I3**: "within `TRAP_WINDOW` bars after the third candle a close back inside bar
     * `n−2`'s body" ⇒ `BULL_TRAP` / `BEAR_TRAP` `[Ch.18-5]`.
     *
     * *Inside the body* means between candle 1's open and close, inclusive — a close back at
     * candle 1's own close has indeed given back the whole star.
     */
    private fun trapPenalty(
        candles: List<Candle>,
        signalBar: Int,
        first: Candle,
        bullish: Boolean,
    ): Penalty? {
        val window = CandlestickPolicy.trapWindow(candles.firstOrNull()?.timeframe ?: candles.first().timeframe)
        val end = min(signalBar + window, candles.lastIndex)
        for (j in (signalBar + 1)..end) {
            val close = candles[j].close
            val inside = close >= min(first.open, first.close) && close <= max(first.open, first.close)
            if (inside) {
                return Penalty.of(
                    if (bullish) PenaltyId.BULL_TRAP else PenaltyId.BEAR_TRAP,
                    "A close inside candle 1's body within the trap window gave the whole star " +
                        "back [Ch.18-5 §Bull Trap].",
                )
            }
        }
        return null
    }

    private companion object {
        /** The star family occupies three bars. */
        const val FORMATION_BARS: Int = 3
    }
}