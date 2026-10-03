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
import com.mia.app.domain.pattern.Measure
import com.mia.app.domain.pattern.MeasuredMoveCalculator
import com.mia.app.domain.pattern.Penalty
import com.mia.app.domain.pattern.PenaltyId
import com.mia.app.domain.pattern.PatternScoringPolicy
import com.mia.app.domain.pattern.ScoreFactor
import com.mia.app.domain.pattern.boundaryRSquared
import com.mia.app.domain.pattern.fitBoundary
import com.mia.app.domain.pattern.gap
import com.mia.app.domain.pattern.lineThrough
import com.mia.app.domain.pattern.monoScore
import com.mia.app.domain.pattern.slopeOverSpan
import com.mia.app.domain.pattern.symmetryRatio
import com.mia.app.domain.pattern.touches
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The one implementation of the shoulder patterns, shared by [HeadAndShouldersDetector] (§12) and
 * [InverseHeadAndShouldersDetector] (§13).
 *
 * **Why it is shared and not copy-pasted.** §13 is defined as "Exact mirror of §12" and every
 * number in §12 has a stated inverse in §13's table. Two hand-written copies of a five-role window,
 * a sloping neckline, ten shape rules and eight penalties is exactly where the two families quietly
 * drift apart — and a drifted mirror is invisible, because both still produce plausible matches. The
 * polarity is therefore a parameter and every rule below is written once against it.
 *
 * **The polarity, stated the way the book states it.**
 *
 * | | `top = true` (§12 H&S) | `top = false` (§13 inverse H&S) |
 * |---|---|---|
 * | role kinds | `HIGH LOW HIGH LOW HIGH` | `LOW HIGH LOW HIGH LOW` |
 * | roles | `LS, T1, H, T2, RS` | `LS, Q1, H, Q2, RS` |
 * | head | the **highest** pivot | the **lowest** pivot |
 * | `ref` (§3.2) | `head − neckline.at(headBar)` | `neckline.at(headBar) − head` |
 * | prior trend (§12.1 P3) | `priorTrendPct(LS) ≥ +3.0 %` | `priorTrendPct(LS) ≤ −3.0 %` |
 * | trigger | close **below** the neckline | close **above** the neckline |
 * | divergence (§3.8) | bearish at `LS→H` or `H→RS` | bullish at `LS→H` or `H→RS` |
 * | direction | `DOWN` | `UP` |
 */
internal object ShouldersEngine {

    /** Roles in the order the window lists them, named as §12.1 P2 and §13 name them. */
    internal data class Roles(
        val leftShoulder: Pivot,
        val necklinePoint1: Pivot,
        val head: Pivot,
        val necklinePoint2: Pivot,
        val rightShoulder: Pivot,
    ) {
        val window: List<Pivot>
            get() = listOf(leftShoulder, necklinePoint1, head, necklinePoint2, rightShoulder)
    }

    private val TOP_KINDS = listOf(
        PivotKind.HIGH, PivotKind.LOW, PivotKind.HIGH, PivotKind.LOW, PivotKind.HIGH,
    )
    private val BOTTOM_KINDS = listOf(
        PivotKind.LOW, PivotKind.HIGH, PivotKind.LOW, PivotKind.HIGH, PivotKind.LOW,
    )

    /**
     * Every qualifying shoulder formation in [confirmed], oldest formation first.
     *
     * @param top `true` for `Head & Shoulders` (§12), `false` for the inverse (§13).
     */
    fun candidates(
        name: String,
        top: Boolean,
        context: ChartContext,
        confirmed: List<Pivot>,
    ): List<ChartCandidate> {
        if (context.barCount == 0 || !context.hasSufficientBars) return emptyList()
        if (confirmed.size < 5) return emptyList()
        val kinds = if (top) TOP_KINDS else BOTTOM_KINDS
        val out = ArrayList<ChartCandidate>()
        for (window in confirmed.windowsOf(5)) {
            if (!window.hasKindSequence(kinds)) continue
            assess(name, top, context, window, confirmed)?.let { out += it }
        }
        return out
    }

    /**
     * One formation. Returns `null` the moment a gate fails — "Never fabricate a match: unmet rules
     * ⇒ `emptyList()`" — so a rejected window leaves no trace in the returned list.
     */
    private fun assess(
        name: String,
        top: Boolean,
        context: ChartContext,
        window: List<Pivot>,
        confirmed: List<Pivot>,
    ): ChartCandidate? {
        val timeframe = context.timeframe
        val roles = Roles(window[0], window[1], window[2], window[3], window[4])
        val trendDirection = if (top) Direction.UP else Direction.DOWN
        val triggerDirection = if (top) Direction.DOWN else Direction.UP

        // --- §12.1 P3 / §13 P3 — prior trend ---------------------------------------------
        // "Head & Shoulders: Requires a clear **uptrend** before the pattern"
        // [Ch.18-2 §The Role of Trend]; "It can only reverse something that already exists. If a
        // stock has been moving sideways for months, a three-peak structure has little
        // significance."
        if (!context.hasPriorTrend(roles.leftShoulder.barIndex, trendDirection)) return null

        // --- §12.2 S5 / §13 S5 — the neckline ----------------------------------------------
        val neckline = lineThrough(roles.necklinePoint1, roles.necklinePoint2)
        val necklineAtHead = neckline.at(roles.head.barIndex)

        // --- §3.2 — the pattern scale `ref`, the book's own measured-move height -------------
        val ref = if (top) roles.head.price - necklineAtHead else necklineAtHead - roles.head.price
        if (ref <= 0.0) return null

        // --- §12.2 S1 / §13 S1 — the head is the extreme -----------------------------------
        // §12 Stage 3: "buyers push price to a new high. This creates the Head". Stage 5: "they
        // fail to reach the previous high. Instead, price forms a **Lower High**." §13 mirrors it:
        // "Sellers attempt another decline. They **fail to make a new low**."
        val shoulderHigh = max(roles.leftShoulder.price, roles.rightShoulder.price)
        val shoulderLow = min(roles.leftShoulder.price, roles.rightShoulder.price)
        if (top && !(roles.head.price > shoulderHigh)) return null
        if (!top && !(roles.head.price < shoulderLow)) return null

        // --- §12.2 S2 / §13 S2 — head dominance ---------------------------------------------
        // `[derived]` 0.10: "without a floor, a 'head' 0.05 % above a shoulder is an artefact of one
        // bar's wick, not 'the final major buying effort'" [Ch.18-2 §Why the Head Matters].
        val headDominance = (if (top) roles.head.price - shoulderHigh else shoulderLow - roles.head.price) / ref
        if (headDominance < ChartSpec.HS_HEAD_DOMINANCE) return null

        // --- §12.2 S3 / §13 S3 — shoulder symmetry -------------------------------------------
        // `[derived]` 0.90: "Uneven shoulders" is a normal variation and "Perfection is
        // unnecessary" [Ch.18-2 §Common Variations], yet the same chapter warns against calling every
        // three-peak formation a Head & Shoulders. 0.90 tolerates a 10 % shoulder difference.
        val symShoulder = symmetryRatio(roles.leftShoulder.price, roles.rightShoulder.price)
        if (symShoulder < ChartSpec.HS_SHOULDER_SYM) return null

        // --- §12.2 S6 / §13 S6 — neckline tilt ------------------------------------------------
        // "The neckline may be horizontal, slightly upward sloping, slightly downward sloping. All
        // three are valid. The exact angle is less important than the market psychology"
        // [Ch.18-2 §The Neckline]. `[derived]` 6.0 %, measured across the **whole formation** so the
        // test is timeframe-independent.
        val spanBars = roles.rightShoulder.barIndex - roles.leftShoulder.barIndex
        val necklineTilt = abs(slopeOverSpan(roles.necklinePoint1, roles.necklinePoint2, spanBars))
        if (necklineTilt > ChartSpec.HS_NECKLINE_TILT_PCT) return null

        // --- §12.2 S7 / §13 S7 — bar spacing --------------------------------------------------
        // `[derived]` 3/40 daily, 2/20 weekly: "3 daily bars is the book's 'reaction' rather than
        // noise; 40 daily bars ≈ 2 months, past which the formation is not something a next-day
        // investor acts on."
        val legs = window.legGaps()
        val minLeg = ChartSpec.hsMinLegBars(timeframe)
        val maxLeg = ChartSpec.hsMaxLegBars(timeframe)
        if (legs.any { it < minLeg || it > maxLeg }) return null

        // --- §12.2 S8 / §13 S8 — total duration -------------------------------------------------
        if (spanBars < ChartSpec.hsMinSpanBars(timeframe) || spanBars > ChartSpec.hsMaxSpanBars(timeframe)) return null

        // --- §12.2 S9 / §13 S9 — time symmetry ---------------------------------------------------
        // `[derived]` 0.50 rather than the book's "no numbers": a formation whose left swing takes
        // 3 bars and whose right swing takes 25 is a different shape even if the prices line up.
        val g1 = gap(roles.leftShoulder, roles.necklinePoint1)
        val g2 = gap(roles.necklinePoint1, roles.head)
        val g3 = gap(roles.head, roles.necklinePoint2)
        val g4 = gap(roles.necklinePoint2, roles.rightShoulder)
        val timeSymmetry = symmetryRatio(g1.toDouble(), g4.toDouble())
        if (timeSymmetry < ChartSpec.HS_TIME_SYM) return null
        if (symmetryRatio(g2.toDouble(), g3.toDouble()) < ChartSpec.HS_TIME_SYM) return null

        // --- §11.1 / §12.5 — the breakout ----------------------------------------------------------
        val bufferPct = BreakoutEvaluator.bufferPct(timeframe)
        val breakoutBar = context.firstSlopedBreakout(
            line = neckline,
            fromBar = roles.rightShoulder.barIndex + 1,
            direction = triggerDirection,
            bufferPct = bufferPct,
        )
        val triggerBar = breakoutBar ?: (context.barCount - 1)

        // --- §12.2 S5 — the third touch ------------------------------------------------------------
        // "Two swing points create a trendline. The **third touch validates it**" [Ch.16-5 §Rule 2].
        // The third point is a post-shoulder pivot of the neckline points' own kind that comes back
        // to the line before the break — the classic throwback low (top) / high (bottom). With only
        // two points `residMax` is 0 by construction and `r²` is DD-7's neutral 0.60, never 1.
        val exclude = roles.window.mapTo(mutableSetOf()) { it.barIndex }
        val third = confirmed.firstOrNull { candidate ->
            candidate.barIndex > roles.rightShoulder.barIndex &&
                (breakoutBar == null || candidate.barIndex <= breakoutBar) &&
                candidate.kind == roles.necklinePoint1.kind &&
                touches(candidate, neckline, context.touchToleranceAt(candidate))
        }
        val neckPoints = listOfNotNull(roles.necklinePoint1, roles.necklinePoint2, third)
        val residMax = fitBoundary(neckPoints, ref).residMax
        val boundaryR2 = boundaryRSquared(neckPoints)

        // --- §12.2 S4 / DD-6 — the right shoulder against the *pre-trend* extreme ------------------
        // The book only ever compares the right shoulder to the head (Stage 5: "they fail to reach
        // the previous high", where "the previous high" is the head), so the pre-trend clause is
        // **scored**, never a gate — DD-6.
        val preTrendExtreme = priorExtreme(confirmed, roles.leftShoulder, top)
        val rightShoulderBeyondPriorHigh = when {
            preTrendExtreme == null -> true
            top -> roles.rightShoulder.price < preTrendExtreme
            else -> roles.rightShoulder.price > preTrendExtreme
        }

        // --- §12.2 S10 — volume shape (scored, not gated) -------------------------------------------
        // "healthy buying volume → volume may decline → volume often decreases further"
        // [Ch.18-2 §Importance of Volume]; the inverse mirrors it.
        val volumeShape = volumeShapeHolds(context, roles, top)

        // --- §3.8 divergence -----------------------------------------------------------------------
        val divergenceGap = context.bestDivergenceGapRsi(
            pairs = listOf(roles.leftShoulder to roles.head, roles.head to roles.rightShoulder),
            want = triggerDirection,
        )

        val priorTrend = context.priorTrendPctAt(roles.leftShoulder.barIndex) ?: 0.0

        val measures = buildList {
            // ---- GEOMETRY (`scoring-spec.md` §3) --------------------------------------------------
            add(
                Measure.linear(
                    "G1 symShoulder", symShoulder, 20, ScoreFactor.GEOMETRY,
                    PatternScoringPolicy.GEOM_SYM_LO, PatternScoringPolicy.GEOM_SYM_HI,
                ),
            )
            add(
                Measure.linear(
                    "G2 headDominance", headDominance, 15, ScoreFactor.GEOMETRY,
                    PatternScoringPolicy.GEOM_DOM_LO, PatternScoringPolicy.GEOM_DOM_HI,
                ),
            )
            add(
                Measure.linear(
                    "G3 headDominance", headDominance, 20, ScoreFactor.GEOMETRY,
                    PatternScoringPolicy.GEOM_DOM3_LO, PatternScoringPolicy.GEOM_DOM3_HI,
                ),
            )
            add(
                Measure.linear(
                    "G4 timeSymmetry", timeSymmetry, 10, ScoreFactor.GEOMETRY,
                    PatternScoringPolicy.GEOM_TIMESYM_LO, PatternScoringPolicy.GEOM_TIMESYM_HI,
                ),
            )
            add(
                Measure.decay(
                    "G5 |necklineTilt|", necklineTilt, 10, ScoreFactor.GEOMETRY,
                    PatternScoringPolicy.GEOM_TILT_PERFECT, PatternScoringPolicy.GEOM_TILT_FLOOR,
                ),
            )
            add(
                Measure.linear(
                    "G6 monoScore", necklineMonotonicity(roles), 15, ScoreFactor.GEOMETRY,
                    PatternScoringPolicy.GEOM_MONO_LO, PatternScoringPolicy.GEOM_MONO_HI,
                ),
            )
            add(
                Measure.binary(
                    "rsBelowPriorHigh (DD-6)", rightShoulderBeyondPriorHigh, 15, ScoreFactor.GEOMETRY,
                ),
            )
            add(Measure.binary("volumeDecline (S10)", volumeShape, 10, ScoreFactor.GEOMETRY))

            // ---- STRUCTURE (`scoring-spec.md` §4) -------------------------------------------------
            addAll(
                boundaryStructureMeasures(
                    context = context,
                    residMax = residMax,
                    boundaryR2 = boundaryR2,
                    legs = legs,
                    legIdeal = ChartSpec.shoulderLegIdeal(timeframe),
                    legTolerance = ChartSpec.shoulderLegTolerance(timeframe),
                    spanBars = spanBars,
                    spanIdeal = ChartSpec.shouldersSpanIdeal(timeframe),
                    touchCount = null,
                ),
            )

            // ---- CONFIRMATION (`scoring-spec.md` §5.1) --------------------------------------------
            addAll(
                chartConfirmationMeasures(
                    context = context,
                    boundary = neckline,
                    breakoutBar = breakoutBar,
                    direction = triggerDirection,
                    divergenceGap = divergenceGap,
                ),
            )

            // ---- CONTEXT (`scoring-spec.md` §6) ---------------------------------------------------
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
            neckline = neckline,
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
            // §12.4 / §13 verbatim: "**Step 2** Subtract this distance from the breakout point."
            // A `FORMING` match has no breakout point, so §11.1 requires `measuredMove == null`.
            measuredMove = breakoutBar?.let { bar ->
                MeasuredMoveCalculator.fromBoundary(
                    direction = triggerDirection,
                    boundary = neckline,
                    extremeBar = roles.head.barIndex,
                    extremePrice = roles.head.price,
                    breakoutBar = bar,
                    breakoutClose = context.candles[bar].close,
                )
            },
            formationStartBar = roles.leftShoulder.barIndex,
            breakoutBar = breakoutBar ?: -1,
        )
    }

    /**
     * `G6 monoScore` for a shoulder pattern: the two neckline points, read as "the second reaction
     * continued the first".
     *
     * `scoring-spec.md` §3.2 evaluates example A's `monoScore` as exactly `1.0` for the §12.7 fixture
     * (`T1 = 1000 → T2 = 1020`), which is what this reproduces. Reading it over the *whole* window
     * instead — `LS → T1 → H → T2 → RS` — would score every well-formed shoulder as `0.5`, because a
     * head is by construction a rise out of the first trough, making the measure an artefact of the
     * shape rather than a reading of it.
     */
    private fun necklineMonotonicity(roles: Roles): Double =
        monoScore(listOf(roles.necklinePoint1, roles.necklinePoint2), rising = true)

    /** DD-6: the head-relative extreme that came **before** the left shoulder, if any. */
    private fun priorExtreme(confirmed: List<Pivot>, leftShoulder: Pivot, top: Boolean): Double? {
        val earlier = confirmed.filter { it.barIndex < leftShoulder.barIndex }
        if (earlier.isEmpty()) return null
        return if (top) earlier.maxOf { it.price } else earlier.minOf { it.price }
    }

    /** §12.2 S10 and the §13 mirror: the book's healthy three-bar volume sequence. */
    private fun volumeShapeHolds(context: ChartContext, roles: Roles, top: Boolean): Boolean {
        val left = context.candles.getOrNull(roles.leftShoulder.barIndex)?.volume ?: return false
        val head = context.candles.getOrNull(roles.head.barIndex)?.volume ?: return false
        val right = context.candles.getOrNull(roles.rightShoulder.barIndex)?.volume ?: return false
        return if (top) {
            // Healthy buying volume → volume may decline → volume often decreases further.
            head <= left && right <= head
        } else {
            // The mirror: the low of the decline carries the heaviest volume.
            head >= left && right >= head
        }
    }

    /** The §12.3 / §13 penalty set, restricted to the ids §19.1 lists for detectors 6 and 7. */
    private fun buildPenalties(
        name: String,
        top: Boolean,
        context: ChartContext,
        roles: Roles,
        neckline: BoundaryLine,
        breakoutBar: Int?,
        triggerBar: Int,
        triggerDirection: Direction,
        divergenceGap: Double,
        bufferPct: Double,
    ): List<Penalty> {
        val penalties = ArrayList<Penalty>()
        val trapId = if (top) PenaltyId.BULL_TRAP else PenaltyId.BEAR_TRAP

        if (breakoutBar != null) {
            // §12.3 I2 / §13 I2 — "A neckline break on **weak volume** deserves caution".
            if (context.weakBreakoutVolume(breakoutBar)) {
                penalties += Penalty.of(
                    PenaltyId.WEAK_VOLUME,
                    "$name broke the neckline on volume below ${ChartSpec.RVOL_WEAK}x its 20-bar average.",
                )
            }
            // §12.3 I3 / §13 I3 — a close beyond the neckline then back inside, inside TRAP_WINDOW.
            val traps = context.trapEvents(neckline, breakoutBar, triggerDirection)
            repeat(minOf(traps, 2)) { event ->
                val reason = "$name closed back inside the neckline within " +
                    "${ChartSpec.trapWindow(context.timeframe)} bars of the break (event ${event + 1})."
                penalties += Penalty.repeat(Penalty.of(trapId, reason), reason)
            }
            // §12.3 I5 / §13 I5 — a throwback that closes back through the neckline the wrong way.
            val tolerance = context.touchToleranceAt(breakoutBar, neckline.at(breakoutBar))
            if (context.isFailedRetest(neckline, breakoutBar, triggerDirection, tolerance)) {
                penalties += Penalty.of(
                    PenaltyId.FAILED_RETEST,
                    "$name returned to the neckline and closed back through it against the break.",
                )
            }
            // §12.3 I4 / §13 I4 — the "failed Head & Shoulders" that re-takes its extreme.
            val opposite = if (top) Direction.UP else Direction.DOWN
            if (context.passedLevel(breakoutBar, roles.head.price, opposite, ChartSpec.FAILURE_LOOKAHEAD_BARS)) {
                penalties += Penalty.of(
                    PenaltyId.FAILED_PATTERN,
                    "$name re-took its head extreme within ${ChartSpec.FAILURE_LOOKAHEAD_BARS} bars " +
                        "of the break.",
                )
            }
        } else {
            val opposite = if (top) Direction.UP else Direction.DOWN
            // §12.3 I6 / §13 I6 — the trigger is exceeded before it fires.
            if (context.passedLevel(roles.rightShoulder.barIndex + 1, roles.head.price, opposite, ChartSpec.FAILURE_LOOKAHEAD_BARS)) {
                penalties += Penalty.of(
                    PenaltyId.STRUCTURE_CONTRADICTS,
                    "$name is still forming and price has already passed its extreme.",
                )
            }
            // §19 INTRADAY_SPIKE_ONLY — poked by an intraday print, never closed through.
            if (context.intradaySpikeOnly(neckline, roles.rightShoulder.barIndex + 1, triggerDirection, bufferPct)) {
                penalties += Penalty.of(
                    PenaltyId.INTRADAY_SPIKE_ONLY,
                    "$name was crossed by an intraday print but no close has cleared the neckline.",
                )
            }
        }

        // §12.3 I7 / §13 I7 — the book names lower RSI at the head as a hallmark.
        if (divergenceGap <= 0.0) {
            penalties += Penalty.of(
                PenaltyId.NO_DIVERGENCE,
                "$name has no qualifying RSI divergence on its shoulder-to-head or head-to-shoulder leg.",
            )
        }

        // §12.6 / §19 — "Range-bound market … breakouts fail frequently" [Ch.20 §Regime 3].
        if (context.isRanging(triggerBar)) {
            penalties += Penalty.of(
                PenaltyId.RANGING_REGIME,
                "ADX at the neckline is below ${ChartSpec.ADX_RANGING}; breakouts often fail in a range.",
            )
        }
        return penalties
    }
}

/**
 * Detector 6 — `Head & Shoulders` (`patterns-spec.md` §12).
 *
 * `Category.REVERSAL`, `direction = DOWN`, roles `HIGH LOW HIGH LOW HIGH` at five consecutive leg
 * indices (§12.1 P2): `LS` left shoulder, `T1`/`T2` the two neckline reaction lows, `H` the head,
 * `RS` the right shoulder.
 *
 * The measured move is the book's own two steps verbatim `[Ch.18-2 §Measuring the Price Target]`:
 * "**Step 1** Measure the distance: Head ↓ Neckline … Difference ₹200. **Step 2** Subtract this
 * distance from the breakout point." Verified on the book's own numbers: `1200 − 1000 = 200`;
 * `1000 − 200 = 800`.
 */
class HeadAndShouldersDetector : PatternDetector {

    override val name: String = "Head & Shoulders"

    override val category: Category = Category.REVERSAL

    /**
     * The smallest window a head & shoulders can occupy on **either** timeframe:
     * `HS_MIN_SPAN_BARS(W1) = 8` plus four minimum legs and one bar of §2.5 confirmation lag, rounded
     * up. Deliberately below the plan.md §6.4 gates (60 daily / 40 weekly) so that *history*, not
     * this property, decides whether a series is scorable (`scoring-spec.md` §8.1).
     */
    override val minBars: Int = 20

    override fun detect(pivots: List<Pivot>, candles: List<Candle>): List<Match> {
        val context = ChartContext.of(candles)
        if (!context.hasSufficientBars) return emptyList()
        val confirmed = context.confirmed(pivots)
        return buildChartMatches(this, candles, ShouldersEngine.candidates(name, top = true, context, confirmed))
    }
}

/**
 * Detector 7 — `Inverse Head & Shoulders` (`patterns-spec.md` §13).
 *
 * `Category.REVERSAL`, `direction = UP`, roles `LOW HIGH LOW HIGH LOW`: `LS`, `Q1`/`Q2` the two
 * neckline **peak** pivots, `H`, `RS`.
 *
 * The measured move is given by the book for the inverse explicitly
 * `[Ch.18-2 §Measuring the Upside Target]`: "Measure: Neckline ↓ Head. Suppose Head ₹500, Neckline
 * ₹620, Difference ₹120. Breakout ₹620, Target ₹740." Checked: `620 − 500 = 120`; `620 + 120 = 740`.
 */
class InverseHeadAndShouldersDetector : PatternDetector {

    override val name: String = "Inverse Head & Shoulders"

    override val category: Category = Category.REVERSAL

    /** See [HeadAndShouldersDetector.minBars] — the window requirement is identical. */
    override val minBars: Int = 20

    override fun detect(pivots: List<Pivot>, candles: List<Candle>): List<Match> {
        val context = ChartContext.of(candles)
        if (!context.hasSufficientBars) return emptyList()
        val confirmed = context.confirmed(pivots)
        return buildChartMatches(
            this,
            candles,
            ShouldersEngine.candidates(name, top = false, context, confirmed),
        )
    }
}