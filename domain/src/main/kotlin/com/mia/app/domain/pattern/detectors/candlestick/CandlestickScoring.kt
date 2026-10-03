package com.mia.app.domain.pattern.detectors.candlestick

import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.MatchScorePolicy
import com.mia.app.domain.pattern.PatternScoringPolicy
import com.mia.app.domain.pattern.ScoreFactor
import com.mia.app.domain.pattern.coefficientOfVariation
import com.mia.app.domain.model.Measure
import com.mia.app.domain.model.MeasureBand
import kotlin.math.max

/**
 * The candlestick measure set of `scoring-spec.md` — §3.1 (G3), §4.3 (S1–S6), §5.2 (K1–K5) and
 * §6 (X1–X6) — built once so all five detectors emit **the same shape** of evidence.
 *
 * **The rule this file exists to enforce.** A detector does not compute a score; it emits
 * weighted `Measure`s and `FactorScorer` compiles them (`docs/CONTRACTS.md`). `Measure.weight`
 * is relative *within* its factor and the scorer renormalises by the sum, so the weights below
 * are the spec's own (`0.25 / 0.20 / 0.15 …` turned into integers `25 / 20 / 15`) and a detector
 * never rescales by hand.
 *
 * **Why every detector shares these builders.** §4.3 replaces the whole pivot-based STRUCTURE
 * row for detectors 1–5 with candle-based slots, and §5.2 replaces the chart-pattern
 * CONFIRMATION rows with `K1…K5`. If each detector wrote its own, a hammer's "body
 * significance" and a star's would silently use different anchors and the P7 discrimination gate
 * (which compares all 12 detectors) would be measuring nothing.
 *
 * **Inapplicable slots are emitted with weight 0** rather than omitted
 * (`scoring-spec.md` §6.1), so `FactorScorer` redistributes their share *and* the UI can say
 * "this pattern has no symmetry measure" instead of silently showing a shorter breakdown.
 */
internal object CandlestickScoring {

    // ------------------------------------------------------------------- GEOMETRY (§3, §3.1)

    /**
     * The geometry slots that exist for **every** pattern (`scoring-spec.md` §3) and therefore
     * never for a candlestick: they are all about lines drawn *through pivots*, and §4.3 says a
     * candlestick has none.
     */
    private val NON_CANDLE_GEOMETRY: List<String> = listOf(
        "Symmetry",
        "Extremity dominance",
        "Time symmetry",
        "Line tilt",
        "Boundary flatness",
        "Channel conformity",
        "Pole/formation proportion",
    )

    /** The inapplicable geometry placeholders, so §6.1 redistributes their weight. */
    internal fun geometryPlaceholders(): List<Measure> =
        NON_CANDLE_GEOMETRY.map { Measure.notApplicable(it, ScoreFactor.GEOMETRY) }

    /**
     * **G3 — proportionality**, the one geometry measure each candlestick has
     * (`scoring-spec.md` §3.1, per-detector table).
     *
     * | Detector | raw `v` | normalisation |
     * |---|---|---|
     * | 1 Bullish Engulfing | `engulf = (close[n] − open[n−1]) / body[n−1]` | `RATIO_SATURATING(v, 1.0)` |
     * | 2 Bearish Engulfing | mirror | `RATIO_SATURATING(v, 1.0)` |
     * | 3 Hammer | `wickRatio = lowerWick / max(body, 1e-3·low)` | `RATIO_SATURATING(v, 4.0)` |
     * | 4 Shooting Star | mirror | `RATIO_SATURATING(v, 4.0)` |
     * | 5 Star family | `starRatio` and `pen` | decay / linear |
     */
    internal fun saturationGeometry(name: String, value: Double, k: Double): Measure =
        Measure.saturating(name, value, GEOMETRY_PRIMARY_WEIGHT, ScoreFactor.GEOMETRY, k)

    /**
     * A geometry measure the spec names but `scoring-spec.md` §3 has no slot for — the §6.2
     * **S5** gap bonus, the §10.2 **S6** `bodyRatio` balance and the §8.5 **C7** climax.
     *
     * They are emitted at [SUPPLEMENTAL_WEIGHT] rather than folded into a named measure, because
     * inventing a slot for them at full weight would quietly redefine the spec's factor; a small
     * weight means they can *only* ever add a few points, and the spec's own wording ("treated as
     * a **bonus**, never a gate") is exactly that.
     */
    internal fun supplementalGeometry(name: String, value: Double, k: Double): Measure =
        Measure.saturating(name, value, SUPPLEMENTAL_WEIGHT, ScoreFactor.GEOMETRY, k)

    /** `LIN_DECAY` geometry (`scoring-spec.md` §3.1, star family `starRatio`). */
    internal fun decayGeometry(name: String, value: Double, perfect: Double, floor: Double): Measure =
        Measure.decay(
            name = name,
            value = value,
            weight = GEOMETRY_PRIMARY_WEIGHT,
            factor = ScoreFactor.GEOMETRY,
            perfect = perfect,
            floor = floor,
        )

    /** `LIN_LINEAR` geometry (`scoring-spec.md` §3.1, star family `pen`). */
    internal fun linearGeometry(name: String, value: Double, lo: Double, hi: Double): Measure =
        Measure.linear(name, value, GEOMETRY_PRIMARY_WEIGHT, ScoreFactor.GEOMETRY, lo, hi)

    /** A supplemental geometry reading on a `1.0 = perfect, 0.5 = unacceptable` ratio. */
    internal fun ratioEqualGeometry(name: String, value: Double, target: Double, tolerance: Double): Measure =
        Measure.ratioEqual(name, value, SUPPLEMENTAL_WEIGHT, ScoreFactor.GEOMETRY, target, tolerance)

    // ------------------------------------------------------------------ STRUCTURE (§4, §4.3)

    /**
     * `scoring-spec.md` §4.3, verbatim: *candlestick patterns have no pivots and therefore no
     * `residMax` / spacing / duration / touch measures*, so slots S1, S3, S4, S5 and S6 are
     * **replaced** with candle readings and the weights are kept identical.
     */
    internal data class StructureInputs(
        /** §4.3 **S1**: `bodyFrac[signalBar]` — "body significance". */
        val signalBodyFrac: Double,
        /** §4.3 **S3**: `max(upperFrac, lowerFrac) − bodyFrac` at the signal bar. */
        val wickMinusBodyFrac: Double,
        /** §4.3 **S4**: `1 − stdev/mean` of the preceding context-window closes. */
        val contextCleanliness: Double,
        /** §4.3 **S5**: `range[signalBar] / ATR14` — a 4-ATR bar is a "massive battle". */
        val rangeOverAtr: Double,
        /** §4.3 **S6**: the signal bar(s) sit at the extreme of the window, `1` or `0`. */
        val atWindowExtreme: Boolean,
        /** §4.3 **S2**: `IndicatorSnapshot.trendlineFitRSquared` (closes, 30 bars). */
        val trendlineRSquared: Double,
    )

    internal fun structureMeasures(inputs: StructureInputs): List<Measure> = listOf(
        Measure.linear(
            name = "Body significance",
            value = inputs.signalBodyFrac,
            weight = 25,
            factor = ScoreFactor.STRUCTURE,
            lo = 0.10,
            hi = 0.60,
        ),
        Measure.linear(
            name = "Trendline fit R²",
            value = inputs.trendlineRSquared,
            weight = 20,
            factor = ScoreFactor.STRUCTURE,
            lo = PatternScoringPolicy.STRUCT_R2_LO,
            hi = PatternScoringPolicy.STRUCT_R2_HI,
        ),
        Measure.linear(
            name = "Wick/body separation",
            value = inputs.wickMinusBodyFrac,
            weight = 15,
            factor = ScoreFactor.STRUCTURE,
            lo = 0.10,
            hi = 0.55,
        ),
        Measure.linear(
            name = "Context window cleanliness",
            value = inputs.contextCleanliness,
            weight = 15,
            factor = ScoreFactor.STRUCTURE,
            lo = PatternScoringPolicy.STRUCT_CV_LO,
            hi = PatternScoringPolicy.STRUCT_CV_HI,
        ),
        Measure.decay(
            name = "Signal bar range vs ATR",
            value = inputs.rangeOverAtr,
            weight = 15,
            factor = ScoreFactor.STRUCTURE,
            perfect = PatternScoringPolicy.STRUCT_RANGEAT_FLOOR,
            floor = PatternScoringPolicy.STRUCT_RANGEAT_PERFECT,
        ),
        Measure.binary(
            name = "Two/three-bar consistency",
            hit = inputs.atWindowExtreme,
            weight = 10,
            factor = ScoreFactor.STRUCTURE,
        ),
    )

    // --------------------------------------------------------------- CONFIRMATION (§5, §5.2)

    /**
     * `scoring-spec.md` §5.2, `K1…K5`.
     *
     * @param rvolOnSignal `rvol[signalBar]`; `null` before the 20-bar average exists.
     * @param nextBarConfirmed the book's own next-bar rule (§8.3 `I1`, §6.3 `C2`): `true` it
     *   held, `false` it did not. `null` when the signal bar *is* the last bar, i.e. the
     *   confirmation has not had a session in which to happen yet.
     * @param structureConfirmed §6.5 `C3` / §10.5 `C2`: a **close** beyond the swing extreme
     *   that preceded the pattern, within [CandlestickPolicy.STRUCTURE_LOOKAHEAD_BARS] bars.
     * @param rsiOnSignal RSI at the signal bar, `null` inside the warm-up.
     * @param divergence `0.0 / 0.5 / 1.0` from [`CandlestickSignals.divergenceScore`].
     * @param deliveryVsAverage `deliveryVsAvg(signalBar)`; `null` when delivery data is absent.
     */
    internal data class ConfirmationInputs(
        val rvolOnSignal: Double?,
        val nextBarConfirmed: Boolean?,
        val structureConfirmed: Boolean,
        val rsiOnSignal: Double?,
        val divergence: Double,
        val deliveryVsAverage: Double?,
    )

    internal fun confirmationMeasures(
        inputs: ConfirmationInputs,
        bullish: Boolean,
    ): List<Measure> {
        val measures = ArrayList<Measure>(7)

        // K1 — the book's own number: "If third candle volume is **twice** the average, buyers are
        // demonstrating conviction" [Ch.17-3 §Role of Volume]. The 3× anchor is `scoring-spec.md`
        // §5.2's; an absent reading scores 0 rather than being treated as average.
        measures += Measure.saturating(
            name = "Volume on the signal bar",
            value = inputs.rvolOnSignal ?: 0.0,
            weight = 35,
            factor = ScoreFactor.CONFIRMATION,
            k = PatternScoringPolicy.CONF_RVOL_SAT,
        )

        // K2 — no next bar yet is "not yet", which the spec's 0/1 reading cannot express and a
        // penalty would wrongly punish; see each detector's KDoc.
        measures += Measure.binary(
            name = "Next-bar confirmation",
            hit = inputs.nextBarConfirmed == true,
            weight = 25,
            factor = ScoreFactor.CONFIRMATION,
        )

        measures += Measure.binary(
            name = "Structure confirmation",
            hit = inputs.structureConfirmed,
            weight = 15,
            factor = ScoreFactor.CONFIRMATION,
        )

        // K4 — "`rsi[signalBar]` against the appropriate band, **or** a divergence". The band's
        // inverted anchors are `scoring-spec.md` §5.2's: oversold side `LIN_LINEAR(v, 42, 26)`,
        // overbought side `LIN_LINEAR(v, 70, 88)`. The book offers the band *or* the divergence,
        // so the better of the two is what the match is scored on.
        measures += Measure.identity(
            name = if (bullish) "RSI (oversold side)" else "RSI (overbought side)",
            value = rsiReading(inputs.rsiOnSignal, bullish, inputs.divergence),
            weight = 15,
            factor = ScoreFactor.CONFIRMATION,
        )

        // K5 — 0 when delivery is unavailable, never "average" (§5.2 bullet 2).
        measures += Measure.saturating(
            name = "Delivery % vs average",
            value = inputs.deliveryVsAverage ?: 0.0,
            weight = 10,
            factor = ScoreFactor.CONFIRMATION,
            k = PatternScoringPolicy.CONF_DELIVERY_SAT,
        )

        return measures
    }

    /**
     * `max(band reading, divergence reading)` — see K4 above.
     *
     * `scoring-spec.md` §5.2 writes the oversold band as `LIN_LINEAR(v, 42, 26)`, which is a
     * *descending* interval; `MeasureBand.Linear` requires `hi > lo`, so it is expressed as the
     * same maths with the band endpoints ordered and `invert = true`. Both give `100` at RSI 26
     * and `0` at RSI 42.
     */
    private fun rsiReading(rsiOnSignal: Double?, bullish: Boolean, divergence: Double): Double {
        val band = when {
            rsiOnSignal == null -> 0.0
            bullish -> MeasureBand.Linear(invert = true, lo = 26.0, hi = 42.0).normalise(rsiOnSignal)
            else -> MeasureBand.Linear(invert = false, lo = 70.0, hi = 88.0).normalise(rsiOnSignal)
        } / 100.0
        return max(band, divergence)
    }

    // --------------------------------------------------------------------- CONTEXT (§6)

    /**
     * `scoring-spec.md` §6, `X1…X6`.
     *
     * @param priorTrendMagnitude `|priorTrendPct|` measured in the pattern's direction.
     * @param adxValue `adx[signalBar]`, `null` inside the ADX warm-up.
     * @param diAligned `+DI > −DI` in the pattern's direction at the signal bar.
     */
    internal data class ContextInputs(
        val priorTrendMagnitude: Double,
        val adxValue: Double?,
        val diAligned: Boolean,
    )

    internal fun contextMeasures(inputs: ContextInputs): List<Measure> = listOf(
        Measure.linear(
            name = "Prior trend alignment",
            value = inputs.priorTrendMagnitude,
            weight = 35,
            factor = ScoreFactor.CONTEXT,
            lo = PatternScoringPolicy.CTX_PRIOR_LO,
            hi = PatternScoringPolicy.CTX_PRIOR_HI,
        ),
        // Every candlestick is reversal-like (`PatternEngine.regimeAligned` treats CANDLESTICK as
        // such), so §6's *reversal* asymmetry applies: a reversal is a failure of trend, and
        // `[Ch.20 §Regime 3]`/`[Ch.17-4 §Ranging Markets]` make a ranging market the good case.
        // `LIN_LINEAR(40 − v, 0, 20)` written with ordered anchors.
        Measure.linear(
            name = "ADX regime (reversal side)",
            value = inputs.adxValue ?: 0.0,
            weight = 25,
            factor = ScoreFactor.CONTEXT,
            lo = 0.0,
            hi = 20.0,
            invert = true,
        ),
        Measure.binary(
            name = "DI alignment",
            hit = inputs.diAligned,
            weight = 10,
            factor = ScoreFactor.CONTEXT,
        ),
        // X4: `relativeStrength` needs a benchmark index series and `PatternDetector.detect` has no
        // parameter to carry one, so the reading is permanently the spec's documented neutral
        // (§6 bullet: "when no benchmark is loaded … `X4 = 0.5` (neutral)").
        Measure.identity("Relative strength (no benchmark)", 0.5, 15, ScoreFactor.CONTEXT),
        // X5: the other timeframe's regime is likewise unreachable from `detect`; the spec's own
        // "unknown" reading is 0.5, not 1.
        Measure.identity("Higher-timeframe agreement (unknown)", 0.5, 10, ScoreFactor.CONTEXT),
        // X6 `marketWeak` needs the benchmark index (patterns-spec.md §19.2). Asserting "the
        // market is not weak" from one symbol's candles would be fabrication, so the slot is
        // emitted as inapplicable and §6.1 redistributes its 5 %.
        Measure.notApplicable("Market breadth (no benchmark)", ScoreFactor.CONTEXT),
    )

    // --------------------------------------------------------------------------- utilities

    /**
     * `scoring-spec.md` §4.3 **S4**: `1 − stdev/mean` of the context-window closes, i.e. the
     * coefficient of variation inverted. "Candlestick patterns become less reliable" in noisy,
     * high-CV conditions `[Ch.17-4 §Highly Volatile Markets]`.
     */
    internal fun contextCleanliness(closes: List<Double>): Double {
        if (closes.size < 2) return 0.0
        return max(0.0, 1.0 - coefficientOfVariation(closes))
    }

    /** `scoring-spec.md` §4.3 **S5**: `range[signalBar] / ATR14[signalBar]`. */
    internal fun rangeOverAtr(candles: List<Candle>, bar: Int, atrAtBar: Double?): Double {
        val candle = candles.getOrNull(bar) ?: return 0.0
        val atr = atrAtBar ?: return 0.0
        if (atr <= 0.0) return 0.0
        return candle.range / atr
    }

    /** `scoring-spec.md` §4.3 **S3**: `max(upperFrac, lowerFrac) − bodyFrac`, never negative. */
    internal fun wickMinusBodyFrac(shape: CandleShape): Double =
        max(0.0, max(shape.upperFrac, shape.lowerFrac) - shape.bodyFrac)

    // Formerly `renderable(score)`, a per-detector copy of the render gate. Removed deliberately:
    //
    // patterns-spec.md §4 rule 4 says no match below the suppress threshold may be *returned*, and
    // that is still guaranteed — but by `PatternEngine`, not by each detector. The engine scores
    // every candidate and routes the sub-threshold ones into `PatternScanResult.suppressed` with a
    // reason, so `matches` still never contains one and the UI still never renders one.
    //
    // Having the gate in both places meant it also *destroyed* the candidate before the engine
    // could see it: a Hammer scoring 47 was written nowhere at all. That is strictly worse than the
    // spec required, because it made "detected but not drawn" unanswerable for exactly the patterns
    // that most often fall short. `CandlestickScoreInvariantTest` now asserts the engine-level
    // invariant, which is the one the spec is actually protecting.

    private const val GEOMETRY_PRIMARY_WEIGHT: Int = 20

    /**
     * Weight of a spec-named measure that `scoring-spec.md` §3 has no slot for. 20 % of one
     * G3-sized slot: enough for the book's "bonus, never a gate" to be visible, small enough that
     * it can never move a match by a tier on its own.
     */
    private const val SUPPLEMENTAL_WEIGHT: Int = 10
}