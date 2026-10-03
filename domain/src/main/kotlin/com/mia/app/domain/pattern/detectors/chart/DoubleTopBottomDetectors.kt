package com.mia.app.domain.pattern.detectors.chart

import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.Category
import com.mia.app.domain.model.Direction
import com.mia.app.domain.model.Match
import com.mia.app.domain.model.PatternDetector
import com.mia.app.domain.model.Pivot
import com.mia.app.domain.model.PivotKind
import com.mia.app.domain.pattern.BoundaryLine
import com.mia.app.domain.pattern.BreakoutEvaluator
import com.mia.app.domain.pattern.MeasuredMoveCalculator
import com.mia.app.domain.pattern.Penalty
import com.mia.app.domain.pattern.PenaltyId
import com.mia.app.domain.pattern.PatternScoringPolicy
import com.mia.app.domain.pattern.ScoreFactor
import com.mia.app.domain.pattern.boundaryRSquared
import com.mia.app.domain.pattern.gap
import com.mia.app.domain.pattern.peakEquality
import com.mia.app.domain.model.Measure
import kotlin.math.max
import kotlin.math.min

/**
 * The one implementation of the two-formation reversal patterns, shared by
 * [DoubleTopDetector] (§14) and [DoubleBottomDetector] (§15).
 *
 * **Why shared.** §15 is "Mirror of §14" and prints the inverse of every one of §14's eight rules.
 * Two copies of a three-role window and a horizontal confirmation level is where they drift, and a
 * drifted mirror still returns plausible matches.
 *
 * | | `top = true` (§14 Double Top) | `top = false` (§15 Double Bottom) |
 * |---|---|---|
 * | role kinds | `HIGH LOW HIGH` | `LOW HIGH LOW` |
 * | roles | `P1, V, P2` first peak / trough / second peak | `B1, R, B2` first bottom / peak / second bottom |
 * | peak equality | `sym(P1, P2) ≥ 0.97` | `sym(B1, B2) ≥ 0.97` |
 * | meaningful middle | `max(P1,P2) − V ≥ 2 × ATR14(V)` | `R − min(B1,B2) ≥ 2 × ATR14(R)` |
 * | `ref` (§3.2) | `max(P1,P2) − V` | `R − min(B1,B2)` |
 * | confirmation level | `V.price` | `R.price` |
 * | prior trend (§14.1 P3) | `priorTrendPct(P1) ≥ +3.0 %` | `priorTrendPct(B1) ≤ −3.0 %` |
 * | trigger | close **below** the level | close **above** the level |
 * | direction | `DOWN` | `UP` |
 */
internal object DoublePatternEngine {

    /** Roles of a two-formation reversal, in window order. */
    internal data class Roles(
        val firstExtremity: Pivot,
        val middle: Pivot,
        val secondExtremity: Pivot,
    ) {
        val window: List<Pivot> get() = listOf(firstExtremity, middle, secondExtremity)
    }

    private val TOP_KINDS = listOf(PivotKind.HIGH, PivotKind.LOW, PivotKind.HIGH)
    private val BOTTOM_KINDS = listOf(PivotKind.LOW, PivotKind.HIGH, PivotKind.LOW)

    fun candidates(
        name: String,
        top: Boolean,
        context: ChartContext,
        confirmed: List<Pivot>,
    ): List<ChartCandidate> {
        if (context.barCount == 0 || !context.hasSufficientBars) return emptyList()
        if (confirmed.size < 3) return emptyList()
        val kinds = if (top) TOP_KINDS else BOTTOM_KINDS
        val out = ArrayList<ChartCandidate>()
        for (window in confirmed.windowsOf(3)) {
            if (!window.hasKindSequence(kinds)) continue
            assess(name, top, context, window)?.let { out += it }
        }
        return out
    }

    private fun assess(
        name: String,
        top: Boolean,
        context: ChartContext,
        window: List<Pivot>,
    ): ChartCandidate? {
        val timeframe = context.timeframe
        val roles = Roles(window[0], window[1], window[2])
        val trendDirection = if (top) Direction.UP else Direction.DOWN
        val triggerDirection = if (top) Direction.DOWN else Direction.UP
        val level = BoundaryLine.horizontal(roles.middle.price)

        // --- §14.1 P3 / §15 P3 — prior trend -------------------------------------------------
        // §14.1: "Stage 1 – Strong Uptrend … Higher Highs, Lower Highs" [Ch.18-3]; §18-1: "Without
        // a prior decline [a double bottom] is simply two similar lows."
        if (!context.hasPriorTrend(roles.firstExtremity.barIndex, trendDirection)) return null

        // --- §14.2 S1 / §15 S1 — peak equality ------------------------------------------------
        // "Second peak **near** the first" [Ch.18-3 §Structure]; the book's own numeric
        // illustration is "Resistance ₹1,000. Price reaches ₹995 … returns to ₹1,005" = ±0.5 %
        // [Ch.18-3 §False Double Tops]. `[derived]` 0.97 keeps that inside while rejecting two peaks
        // 5 % apart, which the book would call a range: "Confusing ranges with reversal patterns".
        val symPeaks = peakEquality(roles.firstExtremity.price, roles.secondExtremity.price)
        if (symPeaks < ChartSpec.DT_PEAK_SYM) return null

        // --- §3.2 — the pattern scale `ref` ----------------------------------------------------
        val extremeHigh = max(roles.firstExtremity.price, roles.secondExtremity.price)
        val extremeLow = min(roles.firstExtremity.price, roles.secondExtremity.price)
        val ref = if (top) extremeHigh - roles.middle.price else roles.middle.price - extremeLow
        if (ref <= 0.0) return null

        // --- §14.2 S6 — the middle really is the local extreme -----------------------------------
        // Guaranteed by the zigzag's role pattern; asserted anyway because the whole rule rests on
        // it and a shared pivot list is a contract other detectors also read.
        if (top && !(roles.middle.price < roles.firstExtremity.price && roles.middle.price < roles.secondExtremity.price)) {
            return null
        }
        if (!top && !(roles.middle.price > roles.firstExtremity.price && roles.middle.price > roles.secondExtremity.price)) {
            return null
        }

        // --- §14.2 S2 / §15 S2 — a meaningful middle -------------------------------------------
        // `[derived]` 2 × ATR: "buyers were given **two opportunities** to overcome resistance"
        // [Ch.18-3 §Why This Matters]; two peaks 0.3 ATR apart are one peak, not two.
        val atr = context.atrAt(roles.middle.barIndex) ?: return null
        if (ref < ChartSpec.DT_MIN_TROUGH_ATR * atr) return null

        // --- §14.2 S3 / §15 S3 — peak separation -------------------------------------------------
        // `[derived]` 5/80 daily, 3/40 weekly: "5 daily bars ≈ one week, the shortest span in which
        // two distinct 'attempts' can be seen".
        val separation = gap(roles.firstExtremity, roles.secondExtremity)
        if (separation < ChartSpec.dtMinSepBars(timeframe) || separation > ChartSpec.dtMaxSepBars(timeframe)) return null

        // --- §14.3 I1 / §15 — the breakout --------------------------------------------------------
        val bufferPct = BreakoutEvaluator.bufferPct(timeframe)
        val breakoutBar = context.firstSlopedBreakout(
            line = level,
            fromBar = roles.secondExtremity.barIndex + 1,
            direction = triggerDirection,
            bufferPct = bufferPct,
        )
        val triggerBar = breakoutBar ?: (context.barCount - 1)

        // --- §14.2 S5 — volume behaviour (scored) -------------------------------------------------
        // "Second peak: price reaches similar levels. Volume often declines. Buying enthusiasm
        // weakens" [Ch.18-3 §Volume Behavior]; the double bottom's mirror is "first low high selling
        // volume, second low lower selling volume" [Ch.18-3 §Volume Analysis].
        val volumeShape = volumeShapeHolds(context, roles)

        // --- §3.8 divergence --------------------------------------------------------------------
        val divergenceGap = context.divergenceGapRsi(roles.firstExtremity, roles.secondExtremity, triggerDirection)

        val priorTrend = context.priorTrendPctAt(roles.firstExtremity.barIndex) ?: 0.0

        val measures = buildList {
            // ---- GEOMETRY (`scoring-spec.md` §3 and §3.1) ----------------------------------------
            add(
                Measure.linear(
                    "G1 symPeaks", symPeaks, 20, ScoreFactor.GEOMETRY,
                    PatternScoringPolicy.GEOM_SYM_LO, PatternScoringPolicy.GEOM_SYM_HI,
                ),
            )
            // §3.1's double-top row: `troughDepthAtr = (max peak − trough) / (2·ATR)`.
            add(
                Measure.linear(
                    "G2 troughDepthAtr", ref / (2.0 * atr), 15, ScoreFactor.GEOMETRY,
                    PatternScoringPolicy.GEOM_DOM_DT_LO, PatternScoringPolicy.GEOM_DOM_DT_HI,
                ),
            )
            add(
                Measure.linear(
                    "G3 peakSym", symPeaks, 20, ScoreFactor.GEOMETRY, 0.94, 1.00,
                ),
            )
            // §3 lists G4 (time symmetry) and G5 (line tilt) for detectors 6 and 7 only: a double
            // top has three pivots and a *horizontal* confirmation level, so neither has a meaning
            // here. §6.1 redistributes their weight rather than filling it with a neutral.
            add(Measure.notApplicable("G4 timeSymmetry", ScoreFactor.GEOMETRY))
            add(Measure.notApplicable("G5 |necklineTilt|", ScoreFactor.GEOMETRY))
            // §3's G6 row lists `monoScore` for detectors 6, 7, 10, 11 and 12 and not for 8 and 9;
            // §10.3 nevertheless evaluates one for detector 8. A three-pivot formation has no
            // same-role sequence longer than two, where "monotonicity" would only restate `G1`, so
            // this follows §3's applicability column and leaves G6 inapplicable.
            add(Measure.notApplicable("G6 monoScore", ScoreFactor.GEOMETRY))
            add(Measure.binary("volumeShrink (S5)", volumeShape, 10, ScoreFactor.GEOMETRY))

            // ---- STRUCTURE (`scoring-spec.md` §4) -----------------------------------------------
            // §4 S1's `residMax` is "over the pivots that define the boundary". A double top's
            // boundary is horizontal through **one** pivot (`V`), so there is no fit to measure and
            // the residual is 0 by construction — stated here rather than dressed up as a fit.
            addAll(
                boundaryStructureMeasures(
                    context = context,
                    residMax = 0.0,
                    // DD-7: one boundary point cannot produce an r², so the neutral 0.60 is used.
                    boundaryR2 = boundaryRSquared(listOf(roles.middle)),
                    legs = listOf(separation),
                    legIdeal = ChartSpec.doubleTopSpacingIdeal(timeframe),
                    legTolerance = ChartSpec.doubleTopSpacingTolerance(timeframe),
                    spanBars = separation,
                    spanIdeal = ChartSpec.doubleTopSpanIdeal(timeframe),
                    touchCount = null,
                ),
            )

            // ---- CONFIRMATION (`scoring-spec.md` §5.1) -------------------------------------------
            addAll(
                chartConfirmationMeasures(
                    context = context,
                    boundary = level,
                    breakoutBar = breakoutBar,
                    direction = triggerDirection,
                    divergenceGap = divergenceGap,
                ),
            )

            // ---- CONTEXT (`scoring-spec.md` §6) --------------------------------------------------
            addAll(
                chartContextMeasures(
                    context = context,
                    priorTrendPct = priorTrend,
                    triggerBar = triggerBar,
                    direction = triggerDirection,
                    reversal = true,
                ),
            )
        }

        val penalties = buildPenalties(
            name = name,
            top = top,
            context = context,
            roles = roles,
            level = level,
            breakoutBar = breakoutBar,
            triggerBar = triggerBar,
            triggerDirection = triggerDirection,
            divergenceGap = divergenceGap,
            bufferPct = bufferPct,
        )

        return ChartCandidate(
            name = name,
            pivots = roles.window,
            measures = measures,
            penalties = penalties,
            measuredMove = breakoutBar?.let { bar ->
                MeasuredMoveCalculator.fromHorizontal(
                    direction = triggerDirection,
                    level = roles.middle.price,
                    extremePrice = if (top) extremeHigh else extremeLow,
                    breakoutBar = bar,
                    breakoutClose = context.candles[bar].close,
                )
            },
            formationStartBar = roles.firstExtremity.barIndex,
            breakoutBar = breakoutBar ?: -1,
        )
    }

    /**
     * §14.2 S5 / §15 S5: the second attempt carries no more volume than the first.
     *
     * The rule is worded the same way in both chapters — "Second peak … Volume often declines"
     * `[Ch.18-3 §Volume Behavior]` and "second low lower selling volume" `[Ch.18-3 §Volume Analysis]`
     * — so one expression covers the pair.
     */
    private fun volumeShapeHolds(context: ChartContext, roles: Roles): Boolean {
        val first = context.candles.getOrNull(roles.firstExtremity.barIndex)?.volume ?: return false
        val second = context.candles.getOrNull(roles.secondExtremity.barIndex)?.volume ?: return false
        return second <= first
    }

    /**
     * §14.3 / §15's penalty set, restricted to the ids §19.1 lists for detectors 8 and 9.
     *
     * `CTX_MISMATCH` and `NO_PRIOR_TREND` are listed for these detectors but unreachable: the prior
     * trend is a **gate** (`patterns-spec.md` §14.1 P3, §12.3 I8 says the same for 6 and 7), so a
     * formation without one never becomes a candidate and there is nothing to penalise. `HTF`
     * cannot be computed from `PatternDetector.detect(pivots, candles)` — see the report.
     */
    private fun buildPenalties(
        name: String,
        top: Boolean,
        context: ChartContext,
        roles: Roles,
        level: BoundaryLine,
        breakoutBar: Int?,
        triggerBar: Int,
        triggerDirection: Direction,
        divergenceGap: Double,
        bufferPct: Double,
    ): List<Penalty> {
        val penalties = ArrayList<Penalty>()
        val trapId = if (top) PenaltyId.BEAR_TRAP else PenaltyId.BULL_TRAP
        val opposite = if (top) Direction.UP else Direction.DOWN
        val confirmationLevel = roles.middle.price

        if (breakoutBar != null) {
            // §14.3 I2 — "Selling volume ideally increases sharply" [Ch.18-3 §Volume Behavior].
            if (context.weakBreakoutVolume(breakoutBar)) {
                penalties += Penalty.of(
                    PenaltyId.WEAK_VOLUME,
                    "$name broke the confirmation level on volume below " +
                        "${ChartSpec.RVOL_WEAK}x its 20-bar average.",
                )
            }
            // §14.3 I3 — a close below the level then back above it inside TRAP_WINDOW bars.
            val traps = context.trapEvents(level, breakoutBar, triggerDirection)
            repeat(minOf(traps, 2)) { event ->
                val reason = "$name closed back above the confirmation level within " +
                    "${ChartSpec.trapWindow(context.timeframe)} bars of the breakdown (event ${event + 1})."
                penalties += Penalty.repeat(Penalty.of(trapId, reason), reason)
            }
            // §14.3 I6 — the pullback to the level that closed back above it: old support became
            // new resistance and held [Ch.18-5 §Pullback After Breakdown].
            val tolerance = context.touchToleranceAt(breakoutBar, level.at(breakoutBar))
            if (context.isFailedRetest(level, breakoutBar, triggerDirection, tolerance)) {
                penalties += Penalty.of(
                    PenaltyId.FAILED_RETEST,
                    "$name pulled back to the confirmation level and closed back through it.",
                )
            }
        } else {
            // §14.3 I4 — the "false Double Top": price closes above both peaks before any breakdown.
            // "**Without confirmation, there is no completed pattern.**" [Ch.18-3 §False Double Tops]
            if (context.passedLevel(roles.secondExtremity.barIndex + 1, confirmationLevel, opposite, ChartSpec.FAILURE_LOOKAHEAD_BARS)) {
                penalties += Penalty.of(
                    PenaltyId.STRUCTURE_CONTRADICTS,
                    "$name was never confirmed — price passed the extreme instead of the level.",
                )
            }
            if (context.intradaySpikeOnly(level, roles.secondExtremity.barIndex + 1, triggerDirection, bufferPct)) {
                penalties += Penalty.of(
                    PenaltyId.INTRADAY_SPIKE_ONLY,
                    "$name was crossed by an intraday print but no close has cleared the confirmation level.",
                )
            }
        }

        // §14.3 I5 — "no bearish RSI divergence between the two peaks" [Ch.22 §22.17].
        if (divergenceGap <= 0.0) {
            penalties += Penalty.of(
                PenaltyId.NO_DIVERGENCE,
                "$name has no qualifying RSI divergence between its two extremities.",
            )
        }

        // §14.6 — `RANGING_REGIME` when `adx < 20` [Ch.20 Regime 3].
        if (context.isRanging(triggerBar)) {
            penalties += Penalty.of(
                PenaltyId.RANGING_REGIME,
                "ADX at the confirmation level is below ${ChartSpec.ADX_RANGING}; breakouts often fail " +
                    "in a range.",
            )
        }
        return penalties
    }
}

/**
 * Detector 8 — `Double Top` (`patterns-spec.md` §14).
 *
 * `Category.REVERSAL`, `direction = DOWN`, roles `HIGH LOW HIGH` at three consecutive leg indices
 * (§14.1 P2): `P1` first peak, `V` the intervening trough — "the support formed between the two
 * peaks", which the book names the **confirmation level** — and `P2` the second peak.
 *
 * The measured move is the book's own worked example, verbatim `[Ch.18-3 §Measuring the Target]`:
 * "Measure: Peak ↓ Support. Peak ₹1,200, Support ₹1,050, Difference ₹150, Breakdown ₹1,050,
 * Estimated target ₹900." Checked: `1200 − 1050 = 150`; `1050 − 150 = 900`. The spec also records
 * the book's own caveat — "this represents a **minimum objective**, not a guarantee" — which is why
 * the app renders it through `MatchScoreLabels.PROJECTION_LABEL`, never as a target.
 */
class DoubleTopDetector : PatternDetector {

    override val name: String = "Double Top"

    override val category: Category = Category.REVERSAL

    /**
     * `DT_MIN_SEP_BARS(W1) = 3` plus the separation's own window and one bar of §2.5 confirmation
     * lag, rounded up; deliberately below the plan.md §6.4 gates (`scoring-spec.md` §8.1).
     */
    override val minBars: Int = 15

    override fun detect(pivots: List<Pivot>, candles: List<Candle>): List<Match> {
        val context = ChartContext.of(candles)
        if (!context.hasSufficientBars) return emptyList()
        val confirmed = context.confirmed(pivots)
        return buildChartMatches(this, candles, DoublePatternEngine.candidates(name, top = true, context, confirmed))
    }
}

/**
 * Detector 9 — `Double Bottom` (`patterns-spec.md` §15).
 *
 * `Category.REVERSAL`, `direction = UP`, roles `LOW HIGH LOW`: `B1`, `R` the intervening peak
 * ("Stage 4 – Buyers finally overcome resistance" `[Ch.18-3]`), `B2`.
 *
 * Measured move from `[Ch.18-5 §Measuring Price Targets]` verbatim: "Double Bottom — Measure:
 * Resistance ↓ Bottom. Project upward." The book gives no worked numbers here; the double top's are
 * the arithmetic mirror and are the ones verified.
 */
class DoubleBottomDetector : PatternDetector {

    override val name: String = "Double Bottom"

    override val category: Category = Category.REVERSAL

    /** See [DoubleTopDetector.minBars] — the window requirement is identical. */
    override val minBars: Int = 15

    override fun detect(pivots: List<Pivot>, candles: List<Candle>): List<Match> {
        val context = ChartContext.of(candles)
        if (!context.hasSufficientBars) return emptyList()
        val confirmed = context.confirmed(pivots)
        return buildChartMatches(
            this,
            candles,
            DoublePatternEngine.candidates(name, top = false, context, confirmed),
        )
    }
}