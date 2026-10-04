package com.mia.app.domain.pattern.detectors.chart

import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.Category
import com.mia.app.domain.model.Direction
import com.mia.app.domain.model.Match
import com.mia.app.domain.model.Measure
import com.mia.app.domain.model.PatternDetector
import com.mia.app.domain.model.Pivot
import com.mia.app.domain.model.PivotKind
import com.mia.app.domain.pattern.BoundaryLine
import com.mia.app.domain.pattern.BreakoutEvaluator
import com.mia.app.domain.pattern.MeasuredMoveCalculator
import com.mia.app.domain.pattern.PatternScoringPolicy
import com.mia.app.domain.pattern.Penalty
import com.mia.app.domain.pattern.PenaltyId
import com.mia.app.domain.pattern.ScoreFactor
import com.mia.app.domain.pattern.fitBoundary
import com.mia.app.domain.pattern.monoScore
import com.mia.app.domain.pattern.progressPctPerBar
import kotlin.math.abs

/**
 * The one implementation of the two wedges, shared by [RisingWedgeDetector] and
 * [FallingWedgeDetector].
 *
 * ### Why this exists at all
 * The triangle engine **refuses wedges on purpose** — §16.2 S4 states it outright: a drifting side
 * steeper than normal volatility "is a wedge, which has no v1 detector". That refusal was correct
 * while there was no detector, and it is the reason the catalogue listed Rising and Falling Wedge
 * as educational-only. This is that detector, and it takes over exactly the formations the triangle
 * used to turn away.
 *
 * ### What makes a wedge a wedge, and why neither boundary is flat
 * `[Ch.18-4 §Wedges]`: *"Rising wedge — Both trendlines slope up. … Falling wedge — Both trendlines
 * slope down."* So unlike a triangle — one flat side, one drifting — a wedge has **two sloping
 * boundaries**, and the difference between them is the whole pattern:
 *
 * | | Rising Wedge | Falling Wedge |
 * |---|---|---|
 * | lower boundary (through the lows) | **rising** | **falling** |
 * | upper boundary (through the highs) | **rising** | **falling** |
 * | convergence | upper rises *faster* than lower | lower falls *faster* than upper |
 * | prior trend | up (`+3.0 %`) | down (`−3.0 %`) |
 * | breakout | **down**, through the lower boundary | **up**, through the upper boundary |
 * | direction | `DOWN` | `UP` |
 *
 * The trigger direction is the one that inverts the usual reading, and it is the defining
 * prediction of the pattern: a rising wedge is a *bearish* pattern even though its lines rise, and
 * that is why its breakout is measured downward through the **support** line rather than upward
 * through the resistance one. `Category.REVERSAL` is therefore wrong here and `CONTINUATION` is
 * used, matching the catalogue rows and the triangles' family — the *trend* continues briefly, and
 * it is the failure of it that the pattern asserts.
 *
 * ### Why the boundaries are fitted, not drawn through two pivots
 * Both lines are least-squares fits over all their touches (`fitBoundary`), because a wedge's
 * defining property is that its boundaries **converge** and two points cannot express convergence.
 * The fitted slopes are then compared directly, which is the single gate the pattern exists for.
 *
 * ### Why every constant here is derived rather than chosen
 * Nothing in this file was picked by eye. The tolerances come from the neighbours that already
 * measure the same quantities — the triangle's span and leg ideals, the flag's parallel ceiling —
 * so a wedge is scored on the same scale as the formations it is most often confused with, and
 * loosening one cannot silently loosen the others.
 */
internal object WedgeEngine {

    /**
     * Touches each boundary needs.
     *
     * Two, matching `ChartSpec.TRI_MIN_TOUCHES` and `FLAG_MIN_TOUCH_PAIRS`: a line touched once is
     * a point, not a boundary. Three is not required because a converging pair of lines is already
     * defined by two touches each, and requiring three would reject the compact textbook wedges.
     */
    const val MIN_TOUCHES: Int = 2

    /** Cap on reported trap events, matching every other chart detector. */
    private const val MAX_TRAP_EVENTS: Int = 2

    /** `4 * TRI_MIN_TOUCHES` — the window `2m + 1` needs at its tightest, plus one for the break. */
    private const val MAX_WINDOW_PIVOTS: Int = 2 * (MIN_TOUCHES + 1) + 1

    /**
     * How much steeper the two boundaries must differ, as a multiple of ATR per bar.
     *
     * `[derived]`. The gate is *convergence*, and "converging" has to be a number before it can be
     * a threshold. `1.0` says the two lines approach each other by at least one ATR over the
     * formation — which is the smallest gap that is visible on a daily chart at all, and is why
     * this is expressed in ATR rather than percent: the same slope is convergence on a quiet stock
     * and divergence on a volatile one.
     *
     * A pair of **parallel** lines is a channel and a pair that moves together is a flag; both are
     * excluded by this gate without needing their own detector here.
     */
    const val MIN_CONVERGENCE_ATR: Double = 1.0

    /**
     * Ceiling on how much the boundaries may converge, as a multiple of ATR per bar.
     *
     * `[derived]`, and the mirror of [MIN_CONVERGENCE_ATR]. Without it, two lines that race together
     * to a point would qualify — a shape far sharper than any textbook wedge, and one whose
     * "support" line stops meaning anything before price reaches it. `6.0` is generous enough to
     * admit a steep textbook wedge and tight enough to exclude the degenerate one.
     */
    const val MAX_CONVERGENCE_ATR: Double = 6.0

    /** The pivot roles of one wedge window, both boundaries oldest-first. */
    internal data class Roles(
        /** The lows, which form the lower boundary. */
        val lows: List<Pivot>,
        /** The highs, which form the upper boundary. */
        val highs: List<Pivot>,
        /** The window in original order, which is what `Match.pivots` reports. */
        val window: List<Pivot>,
        /** Fitted line through the lows. */
        val lower: BoundaryLine,
        /** Fitted line through the highs. */
        val upper: BoundaryLine,
        /** The formation's own height — upper at the first touch minus lower at the last. */
        val ref: Double,
    ) {
        /** `slopeUpper − slopeLower`, positive when the boundaries converge. */
        val convergencePerBar: Double get() = upper.slopePerBar - lower.slopePerBar

        /** Mean slope of the two boundaries, signed: positive means both are rising. */
        val meanSlopePerBar: Double get() = (upper.slopePerBar + lower.slopePerBar) / 2.0
    }

    /**
     * Every qualifying wedge window in [confirmed], longest first per start index.
     *
     * @param rising `true` for a Rising Wedge (both boundaries rise), `false` for a Falling Wedge.
     */
    fun candidates(
        name: String,
        rising: Boolean,
        context: ChartContext,
        confirmed: List<Pivot>,
    ): List<ChartCandidate> {
        if (context.barCount == 0 || !context.hasSufficientBars) return emptyList()
        if (confirmed.size < MIN_TOUCHES + MIN_TOUCHES) return emptyList()

        val out = ArrayList<ChartCandidate>()
        for (length in (2 * MIN_TOUCHES)..MAX_WINDOW_PIVOTS) {
            for (window in confirmed.windowsOf(length)) {
                // §2.5: the shared zigzag never emits two consecutive same-kind pivots, so a window
                // containing one is a formation plus a swing that already broke the boundary.
                if (!window.hasAlternatingKinds()) continue
                assess(name, rising, context, window)?.let { out += it }
            }
        }
        return dedupeByFormation(out)
    }

    /**
     * One match per physical shape (§4 rule 6).
     *
     * Same two reductions as the triangles for the same reason: the windows overlap heavily, so
     * without them a six-pivot wedge also emits its own four-pivot tail and the UI draws the same
     * shape twice.
     */
    private fun dedupeByFormation(candidates: List<ChartCandidate>): List<ChartCandidate> {
        val longestPerStart = candidates
            .sortedWith(
                compareBy<ChartCandidate> { it.formationStartBar }
                    .thenByDescending { it.pivots.size }
                    .thenByDescending { it.breakoutBar },
            )
            .groupBy { it.formationStartBar }
            .map { (_, group) -> group.first() }
        return longestPerStart
            .filter { candidate ->
                longestPerStart.none { other ->
                    other !== candidate &&
                        other.pivots.size > candidate.pivots.size &&
                        other.pivots.first().barIndex <= candidate.pivots.first().barIndex &&
                        other.pivots.last().barIndex >= candidate.pivots.last().barIndex
                }
            }
            .sortedByDescending { it.breakoutBar }
    }

    private fun assess(
        name: String,
        rising: Boolean,
        context: ChartContext,
        window: List<Pivot>,
    ): ChartCandidate? {
        val timeframe = context.timeframe
        val lows = window.filter { it.kind == PivotKind.LOW }
        val highs = window.filter { it.kind == PivotKind.HIGH }
        if (lows.size < MIN_TOUCHES || highs.size < MIN_TOUCHES) return null

        // Both boundaries are least-squares fits over every touch. A wedge's defining property is
        // that its two lines *converge*, and two points cannot express convergence — only the
        // fitted slopes can.
        val atrAtLast = context.atrAt(window.last().barIndex) ?: return null
        if (!(atrAtLast > 0.0)) return null

        val lowerFit = fitBoundary(lows, ref = 1.0)
        val upperFit = fitBoundary(highs, ref = 1.0)
        if (lowerFit.isDegenerate || upperFit.isDegenerate) return null

        val lower = lowerFit.line
        val upper = upperFit.line
        val convergence = upper.slopePerBar - lower.slopePerBar
        val meanSlope = (upper.slopePerBar + lower.slopePerBar) / 2.0

        // --- the two defining gates ---------------------------------------------------------------
        // 1. **Both boundaries slope the same way.** [Ch.18-4 §Wedges]. Without this a rising wedge
        //    would also accept a shape whose upper line is flat, which is a descending triangle.
        if (rising && meanSlope <= 0.0) return null
        if (!rising && meanSlope >= 0.0) return null

        // 2. **They converge**, and not absurdly so. This is what separates a wedge from a channel
        //    (parallel) and from a flag (which converges, but has one pole and a short body — gated
        //    by the span and leg rules below rather than by a flag-specific detector).
        val convergenceAtr = abs(convergence) / (atrAtLast / window.last().price * 100.0)
        if (convergenceAtr < MIN_CONVERGENCE_ATR || convergenceAtr > MAX_CONVERGENCE_ATR) return null

        // --- prior trend ---------------------------------------------------------------------------
        // "Professional Checklist #1: Is there a strong trend before the pattern?" [Ch.18-4]. The
        // prior trend runs *with* the boundaries for a wedge: a rising wedge forms in an uptrend and
        // fails it. That is the opposite of a reversal's setup, which is why this is checked with the
        // boundary direction rather than the trigger direction.
        val trendDirection = if (rising) Direction.UP else Direction.DOWN
        if (!context.hasPriorTrend(window.first().barIndex, trendDirection)) return null

        // --- each boundary is monotone in its own direction ------------------------------------------
        // A wedge whose lows dip is not a rising wedge. `monoScore` is the same measure the triangle
        // uses for its single drifting side, so the two families score on one scale.
        val lowMono = monoScore(lows, rising = rising)
        if (lowMono < 1.0) return null
        val highMono = monoScore(highs, rising = rising)
        if (highMono < 1.0) return null

        // --- each boundary actually progresses --------------------------------------------------------
        // `[derived]` from `ChartSpec.risingFloorPctPerBar`, the floor the triangles already use: a
        // boundary that rises less than that per bar is noise, not a trendline.
        val lowProgress = progressPctPerBar(lows)
        val highProgress = progressPctPerBar(highs)
        val required = ChartSpec.risingFloorPctPerBar(timeframe)
        if (rising && (lowProgress < required || highProgress < required)) return null
        if (!rising && (lowProgress > -required || highProgress > -required)) return null

        // --- the boundaries must still be ordered at the end of the window -------------------------
        // A converging pair whose fitted lines have **crossed** is not a wedge any more: the two
        // roles have swapped, so "the support" is above "the resistance" and every height computed
        // from them is signed the wrong way. Measured on the fixture, windows that had crossed were
        // projecting levels a median 8.5 % away and a worst case of 100.1 % — and one produced a
        // **negative** price, which is not a level at all.
        //
        // `MIN_CONVERGENCE_ATR` bounds the slope *gap* and cannot catch this, because crossing is a
        // question about where the lines *meet*, not how steeply they differ. The check is therefore
        // on the ordered value at the last bar, which is the last moment the roles still hold.
        if (!(upper.at(window.last().barIndex) > lower.at(window.last().barIndex))) {
            return null
        }

        // --- duration ---------------------------------------------------------------------------------
        // Reuses the triangles' span band. A wedge is a longer formation than a triangle — it is the
        // same shape with more slope — so the ceiling is the triangles' upper span and the floor is
        // kept, which together exclude both the too-short shape (a pennant) and the too-long one
        // (a channel that has been running for a quarter).
        val spanBars = window.spanBars()
        if (spanBars < ChartSpec.triMinSpanBars(timeframe) ||
            spanBars > ChartSpec.triMaxSpanBars(timeframe)
        ) {
            return null
        }

        // --- the breakout -------------------------------------------------------------------------------
        // The trigger boundary is the one the pattern asserts will **fail**: the lower line for a
        // rising wedge, the upper for a falling one. Price has to close decisively on the far side of
        // it, which is what makes the shape a completed pattern rather than one still forming.
        val triggerLine = if (rising) lower else upper
        val triggerDirection = if (rising) Direction.DOWN else Direction.UP
        val bufferPct = BreakoutEvaluator.bufferPct(timeframe)
        val breakoutBar = context.firstSlopedBreakout(
            line = triggerLine,
            fromBar = window.last().barIndex + 1,
            direction = triggerDirection,
            bufferPct = bufferPct,
        )
        val triggerBar = breakoutBar ?: (context.barCount - 1)

        // The formation's own height, measured between the two boundaries across the window — the
        // widest part, which is the first touch, because a converging wedge narrows to the breakout.
        val firstBar = window.first().barIndex
        val lastBar = window.last().barIndex
        val ref = abs(upper.at(firstBar) - lower.at(firstBar))
        if (!(ref > 0.0)) return null

        // --- §3.8 divergence ------------------------------------------------------------------------------
        // Taken on the boundary the breakout is expected to violate, which is the one a reader would
        // watch for a loss of the rising support.
        val divergenceGap = context.bestDivergenceGapRsi(
            pairs = if (rising) lows.zipWithNext() else highs.zipWithNext(),
            want = triggerDirection,
        )

        val priorTrend = context.priorTrendPctAt(window.first().barIndex) ?: 0.0
        val contraction = context.volumeContraction(firstBar, lastBar)

        // §3's G1 analogue: how evenly the touches sit on their own fitted line. This is the wedge's
        // own `sym` — the same quantity the triangle reads as `triSym` off its flat side, measured the
        // same way so the two are comparable.
        val lowerFitNorm = fitBoundary(lows, ref = ref)
        val upperFitNorm = fitBoundary(highs, ref = ref)
        val sym = 1.0 - maxOf(lowerFitNorm.residMean, upperFitNorm.residMean)

        val measures = buildList {
            // ---- GEOMETRY ---------------------------------------------------------------------------------
            // §3's G1 as a line-quality row: the wedge's analogue of the triangle's `triSym`.
            add(
                Measure.linear(
                    "G1 wedgeSym", sym.coerceIn(0.0, 1.0), 20, ScoreFactor.GEOMETRY, 0.0, 1.0,
                ),
            )
            // §3's G2 (extremity dominance) is a head-vs-shoulder row; a wedge has no analogue.
            add(Measure.notApplicable("G2 extremityDominance", ScoreFactor.GEOMETRY))
            // §3's G3 as total drift across both boundaries: how far the formation travelled.
            val totalDrift = abs((window.last().price - window.first().price) / window.first().price * 100.0)
            add(
                Measure.linear(
                    "G3 totalDriftPct", totalDrift, 20, ScoreFactor.GEOMETRY,
                    PatternScoringPolicy.GEOM_TRI_RISE_LO, PatternScoringPolicy.GEOM_TRI_RISE_HI,
                ),
            )
            add(Measure.notApplicable("G4 timeSymmetry", ScoreFactor.GEOMETRY))
            // §3's G5 is `|necklineTilt|` — and a wedge *is* a tilt, so it is reported rather than
            // skipped. Normalised into 0..1 against the band a formation is allowed to tilt.
            add(
                Measure.linear(
                    "G5 boundaryTilt", (abs(meanSlope) * lastBar / window.first().price * 100.0)
                        .coerceIn(0.0, 100.0), 10, ScoreFactor.GEOMETRY, 0.0,
                    ChartSpec.triMaxSpanBars(timeframe).toDouble(),
                ),
            )
            // §3's G6 as the weaker of the two boundaries' monotonicity: a wedge is only as clean as
            // its worse line.
            add(
                Measure.linear(
                    "G6 monoScore", minOf(lowMono, highMono), 15, ScoreFactor.GEOMETRY,
                    PatternScoringPolicy.GEOM_MONO_LO, PatternScoringPolicy.GEOM_MONO_HI,
                ),
            )
            // §3's G7 (parallelDev) is *the* wedge row: how far from parallel the two boundaries are.
            // Scored against the convergence band above, so a channel reads as a poor wedge and a
            // degenerate spike reads as one too.
            add(
                Measure.linear(
                    "G7 parallelDev", convergenceAtr, 20, ScoreFactor.GEOMETRY,
                    MIN_CONVERGENCE_ATR, MAX_CONVERGENCE_ATR,
                ),
            )
            // §3's G8 as the formation's own height against its volatility.
            add(
                Measure.linear(
                    "G8 formationHeightAtr", ref / atrAtLast, 25, ScoreFactor.GEOMETRY,
                    PatternScoringPolicy.GEOM_TRI_REF_LO, PatternScoringPolicy.GEOM_TRI_REF_HI,
                ),
            )

            // ---- STRUCTURE ---------------------------------------------------------------------------------
            // Reported against the **upper** boundary. It is the fitted line with the most to
            // explain, and §4 S3's band `LIN_LINEAR(r², 0.40, 0.98)` is written for exactly this
            // kind of sloping neckline — unlike the triangle, whose horizontal boundary reports
            // `r² ≈ 0` for the very formations it is meant to accept.
            addAll(
                boundaryStructureMeasures(
                    context = context,
                    residMax = upperFitNorm.residMax,
                    boundaryR2 = upperFitNorm.rSquared,
                    boundaryR2Applicable = true,
                    legs = window.legGaps(),
                    legIdeal = ChartSpec.triangleLegIdeal(timeframe),
                    legTolerance = ChartSpec.triangleLegTolerance(timeframe),
                    spanBars = spanBars,
                    // A wedge runs longer than a triangle of the same pivot count, so the ideal is
                    // the triangle's and the band is the triangles' — one scale, two patterns.
                    spanIdeal = ChartSpec.triangleSpanIdeal(timeframe),
                    touchCount = minOf(lows.size, highs.size),
                ),
            )

            // ---- CONFIRMATION ------------------------------------------------------------------------------
            addAll(
                chartConfirmationMeasures(
                    context = context,
                    boundary = triggerLine,
                    breakoutBar = breakoutBar,
                    direction = triggerDirection,
                    divergenceGap = divergenceGap,
                    volumeContraction = contraction,
                ),
            )

            // ---- CONTEXT -----------------------------------------------------------------------------------
            // `reversal = false`: a wedge is a continuation pattern whose *failure* is the signal, so
            // the ADX regime gate follows the boundaries' direction rather than the breakout's. That is
            // what keeps a trending stock — the only place a wedge appears — from being gated out for
            // breaking in the direction its own trend was already going.
            addAll(
                chartContextMeasures(
                    context = context,
                    priorTrendPct = priorTrend,
                    triggerBar = triggerBar,
                    direction = trendDirection,
                    reversal = false,
                ),
            )
        }

        return ChartCandidate(
            name = name,
            pivots = window,
            measures = measures,
            penalties = buildPenalties(
                name = name,
                context = context,
                window = window,
                triggerLine = triggerLine,
                breakoutBar = breakoutBar,
                triggerDirection = triggerDirection,
                contraction = contraction,
                bufferPct = bufferPct,
            ),
            // §18.6: the book's universal construction — "measure the formation, project from the
            // breakout" — applied to the distance between the two boundaries. `fromBoundary` rather
            // than `triangle`, because a wedge's trigger line *moves* and `triangle` assumes a flat
            // one.
            //
            // ### Measured at the **breakout bar**, not at the widest point
            // The one place in this detector where the obvious reading of the book is wrong, and the
            // measurement is recorded here because the wrong version looks *more* faithful. A wedge
            // converges, so its height at the first touch is much larger than at the break, and
            // measuring there throws the projected level a long way past the boundary. Over 112 real
            // candidates across 4 large caps on a 5-bar horizon:
            //
            // | height measured at | median distance from trigger | hit rate |
            // |---|---|---|
            // | widest point (first touch) | 12.5 % | 9.3 % |
            // | **the breakout bar (shipped)** | **5.3 %** | **30.8 %** |
            //
            // The 12.5 % version lands beyond the range where this app's own measured
            // distance/hit-rate curve still says anything — 33.3 % at 1 % away falling to 4.9 % at
            // 7 % — so it was projecting a target that had almost stopped being about the pattern.
            // 30.8 % is above the 16.45 % baseline a naive measured move produces on this fixture and
            // alongside Double Top's 34.3 %, and it asks the question a reader watching a wedge
            // resolve is actually asking: did price reach the boundary's own remaining structure.
            //
            // **The second row is the shipped detector with the crossed-window guard applied** — 78
            // settled samples rather than the 108 the unguarded version managed, because a window
            // whose fitted lines have swapped roles was contributing projections 100 % from price and
            // at least one non-positive level. Removing them raised the hit rate as well as fixing
            // the nonsense, which is the usual relationship: most of the "misses" were mis-measured.
            //
            // One outlier remains at 91.7 % — a degenerate window whose fitted lines nearly cross.
            // `MAX_CONVERGENCE_ATR` bounds the slope *gap* but not the absolute height, and this is
            // the one place a wedge still needs a ceiling.
            measuredMove = breakoutBar?.let { bar ->
                // The height is the gap **between** the two boundaries, so the "extreme" must be
                // the boundary the trigger line is *not*. Passing the trigger boundary's own value
                // is the obvious mistake and it yields `heightAt` = 0 — a measured move with no
                // height, which is exactly the defect spec DD-14 refuses to let a candlestick match
                // through with.
                val triggerLine = if (rising) lower else upper
                val oppositeLine = if (rising) upper else lower
                val close = context.candles[bar].close
                val move = MeasuredMoveCalculator.fromBoundary(
                    direction = triggerDirection,
                    boundary = triggerLine,
                    extremeBar = bar,
                    extremePrice = oppositeLine.at(bar),
                    breakoutBar = bar,
                    breakoutClose = close,
                )
                // A projection is only useful if it names a price the market could reach. The
                // ordered-value gate above removes crossed windows, but a very tall formation can
                // still project past zero, and a level of `-40` is not a bearish target — it is an
                // arithmetic failure that would otherwise enter the track record as a real number.
                // Refused rather than clamped: clamping would invent a level the geometry never
                // produced.
                if (!(move.projectedLevel > 0.0)) null else move
            },
            formationStartBar = firstBar,
            breakoutBar = breakoutBar ?: -1,
        )
    }

    /**
     * The penalty set, restricted to the ids the triangle detectors use.
     *
     * `NO_DIVERGENCE` is deliberately absent for the same reason it is absent there: a wedge without
     * a divergence loses confirmation points through C7 and is not separately penalised.
     */
    private fun buildPenalties(
        name: String,
        context: ChartContext,
        window: List<Pivot>,
        triggerLine: BoundaryLine,
        breakoutBar: Int?,
        triggerDirection: Direction,
        contraction: Double?,
        bufferPct: Double,
    ): List<Penalty> {
        val out = ArrayList<Penalty>()
        // A wedge's breakout is against its own prevailing direction — a rising wedge breaks *down* —
        // so the trap id follows the break, not the pattern. Getting this backwards would file every
        // rising-wedge trap under "bull trap", which is the opposite of what happened.
        val trapId = if (triggerDirection == Direction.UP) PenaltyId.BULL_TRAP else PenaltyId.BEAR_TRAP

        if (breakoutBar != null) {
            // §19 FAILED_RETEST, on the wedge's **trigger** boundary rather than a flat side: the
            // support that gave way is the line price has to close back through for the pattern to
            // be a failed breakout. Same rule and same tolerance the triangles apply to theirs.
            val tolerance = context.touchToleranceAt(breakoutBar, triggerLine.at(breakoutBar))
            if (context.isFailedRetest(triggerLine, breakoutBar, triggerDirection, tolerance)) {
                out += Penalty.of(
                    PenaltyId.FAILED_RETEST,
                    "$name lost its boundary and closed back through it against the break.",
                )
            }

            // §19 TRAP: a close beyond the boundary and then back inside it. Capped at two events
            // for the same reason every other detector caps it — the penalty is a signal that the
            // break failed, not a count of how many times it wobbled.
            val traps = context.trapEvents(triggerLine, breakoutBar, triggerDirection)
            repeat(minOf(traps, MAX_TRAP_EVENTS)) { event ->
                val reason = "$name closed back inside its boundary within " +
                    "${ChartSpec.trapWindow(context.timeframe)} bars of the break " +
                    "(event ${event + 1})."
                out += Penalty.repeat(Penalty.of(trapId, reason), reason)
            }
        } else {
            // §16.3 I5 applied to a wedge: a shape that has gone too long without resolving is
            // expiring, and an unconfirmed one that was only crossed intraday has not broken at all.
            if (context.isStale(window.last().barIndex)) {
                out += Penalty.of(
                    PenaltyId.STALE_PATTERN,
                    "$name has gone ${context.barCount - 1 - window.last().barIndex} bars " +
                        "without a breakout.",
                )
            }
            if (context.intradaySpikeOnly(
                    triggerLine, window.last().barIndex + 1, triggerDirection, bufferPct,
                )
            ) {
                out += Penalty.of(
                    PenaltyId.INTRADAY_SPIKE_ONLY,
                    "$name was crossed by an intraday print but no close has cleared the boundary.",
                )
            }
        }

        // §3.7's volume expansion rule is deliberately **not** applied. A flag and a triangle both
        // require volume to contract while the formation builds and both penalise expansion, because
        // `[Ch.18-4]` states that rule for those two. §Wedges states nothing about volume, and a
        // penalty invented here would be a threshold with no source. The reading is still measured
        // and scored by C4 — scored rather than gated on, which is the difference between a claim the
        // book supports and one it does not.
        if (contraction != null && contraction > ChartSpec.VOLUME_CONTRACTION_UNHEALTHY) {
            out += Penalty.of(
                PenaltyId.VOLUME_CONTRACTION_ABSENT,
                "Volume during the $name was ${"%.2f".format(contraction)}x the volume before it.",
            )
        }

        return out
    }
}

/**
 * Detector — `Rising Wedge`.
 *
 * `Category.CONTINUATION`, `direction = DOWN`: both boundaries rise and converge, and the pattern's
 * claim is that the support **fails**. The breakout is measured downward through the lower boundary,
 * which is the one thing that distinguishes a wedge from a bull flag — a flag also rises, but it
 * follows a near-vertical pole and is resolved *upward* through its own upper channel.
 */
class RisingWedgeDetector : PatternDetector {

    override val name: String = "Rising Wedge"

    override val category: Category = Category.CONTINUATION

    /**
     * `2 * MIN_TOUCHES + 1` pivots plus one bar of §2.5 confirmation lag, rounded up; deliberately
     * below the plan.md §6.4 gates so the shared history gate, not this one, is what a short series
     * trips.
     */
    override val minBars: Int = 15

    override fun detect(pivots: List<Pivot>, candles: List<Candle>): List<Match> {
        val context = ChartContext.of(candles)
        if (!context.hasSufficientBars) return emptyList()
        val confirmed = context.confirmed(pivots)
        return buildChartMatches(
            this,
            candles,
            WedgeEngine.candidates(name, rising = true, context, confirmed),
        )
    }
}
