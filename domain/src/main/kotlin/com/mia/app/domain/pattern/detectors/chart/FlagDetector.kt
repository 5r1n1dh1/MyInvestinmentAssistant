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
import com.mia.app.domain.pattern.fitBoundary
import com.mia.app.domain.pattern.legSpacingScore
import com.mia.app.domain.pattern.lineThrough
import com.mia.app.domain.pattern.parallelDeviation
import com.mia.app.domain.pattern.slopePerBar
import com.mia.app.domain.pattern.spacingScore
import com.mia.app.domain.model.Measure
import kotlin.math.abs
import kotlin.math.max

/**
 * Detector 12 — `Bull Flag` / `Bear Flag` (`patterns-spec.md` §18), one detector family emitting
 * both polarities, exactly as §10 does for the star pair.
 *
 * **Why one class and not two.** §18.1 P2 defines the role assignment as
 * "`B` is the flagpole base (**pivot opposite in kind to `T`**)": whether a rising pole ends on a
 * `HIGH` and a falling one on a `LOW` is the *only* thing that distinguishes the two. Writing that
 * as one parameter keeps the two polarities provably identical except for their sign.
 *
 * **Roles.** `B, T, F₁, G₁, F₂, G₂, …, F_k, G_k` with `k ≥ 2`:
 *
 * | | `BULL_FLAG_NAME` | `BEAR_FLAG_NAME` |
 * |---|---|---|
 * | `B` pole base | `LOW` | `HIGH` |
 * | `T` pole top | `HIGH` | `LOW` |
 * | `F_i` | flag **lows** (lower channel) | flag **highs** (upper channel) |
 * | `G_i` | flag **highs** (upper channel) | flag **lows** (lower channel) |
 * | counter-trend drift | both channels fall (≤ 0) | both channels rise (≥ 0) |
 * | trigger | close **above** the upper channel | close **below** the lower channel |
 * | measured move | `+ poleHeight` | `− poleHeight` |
 *
 * The measured move is the book's own sentence, verbatim `[Ch.18-5 §Measuring Price Targets]`:
 * "**Flag** — Measure: Flagpole. Project from breakout", with `[Ch.18-4 §Measuring the Target]`'s
 * own numbers: "Flagpole ₹100, Breakout ₹500, Estimated target ₹600". Checked: `500 + 100 = 600`.
 */
class FlagDetector : PatternDetector {

    override val name: String = BULL_FLAG_NAME

    override val category: Category = Category.CONTINUATION

    /**
     * `FLAG_MIN_BARS(W1) = 2` plus the pole's own leg and one bar of §2.5 confirmation lag, rounded
     * up. Deliberately below the plan.md §6.4 gates (`scoring-spec.md` §8.1): a flag is the shortest
     * v1 formation, so its window requirement is the smallest.
     */
    override val minBars: Int = 10

    override fun detect(pivots: List<Pivot>, candles: List<Candle>): List<Match> {
        val context = ChartContext.of(candles)
        if (!context.hasSufficientBars) return emptyList()
        val confirmed = context.confirmed(pivots)
        val out = ArrayList<Match>()
        for (bull in listOf(true, false)) {
            out += buildChartMatches(
                this,
                candles,
                FlagEngine.candidates(
                    name = if (bull) BULL_FLAG_NAME else BEAR_FLAG_NAME,
                    bull = bull,
                    context = context,
                    confirmed = confirmed,
                ),
            )
        }
        return out.sortedWith(
            compareBy<Match> { it.pivots.firstOrNull()?.barIndex ?: Int.MAX_VALUE }.thenBy { it.name },
        )
    }

    companion object {
        /** `PatternIds.CANONICAL_NAMES[12]`. */
        const val BULL_FLAG_NAME: String = "Bull Flag"

        /** `PatternIds.CANONICAL_NAMES[13]`. */
        const val BEAR_FLAG_NAME: String = "Bear Flag"
    }
}

/**
 * Why a candidate window was **not** a flag.
 *
 * A flag is the most selective of the seven detectors (it needs a strong pole, a shallow body, a
 * counter-trend drift, parallel boundaries *and* a duration window), so on real data almost every
 * window dies on a gate. Without a reason code that fact is indistinguishable from a detector that
 * is simply broken, so the gates are named and [FlagEngine.scan] counts them. These are diagnostic
 * labels only — they never become measures, penalties or pattern names (§19 is about penalties, and
 * a rejected window is not a match at all).
 */
internal enum class FlagRejection {
    /** Fewer than two touches on the near-pole channel. */
    NEAR_CHANNEL_TOUCHES,

    /** Fewer than two touches on the breakout channel. */
    BREAK_CHANNEL_TOUCHES,

    /** §18.2 S1 — the flagpole is too short in percent. */
    POLE_PCT,

    /** §18.2 S1 — the flagpole is too short measured in ATR. */
    POLE_ATR,

    /** §18.2 S2 — the correction is too deep (a "deep correction", not a shallow flag). */
    DEPTH_TOO_DEEP,

    /** §18.2 S2 — the correction is too shallow (a straight line, not a flag). */
    DEPTH_TOO_SHALLOW,

    /** §18.2 S3 — the flag drifted the wrong way (with, not against, the pole). */
    DIRECTION,

    /** §18.2 S4 — the boundaries converge: that is a pennant, which is out of scope for v1. */
    PARALLEL,

    /** §18.2 S5 — too few bars in the flag, or too many. */
    DURATION,

    /** The pole is degenerate (no height to measure a move from). */
    DEGENERATE_REF,
}

/**
 * One window's outcome: either a candidate to be scored, or the gate that rejected it.
 *
 * Returning a verdict instead of a nullable candidate is what makes the rejection counts honest —
 * there is no way for a window to vanish without a reason.
 */
internal sealed interface FlagVerdict {
    data class Accepted(val candidate: ChartCandidate) : FlagVerdict
    data class Rejected(val reason: FlagRejection) : FlagVerdict
}

/**
 * The result of one full sweep of the flag family over a series: what survived, what did not and
 * why, and how many windows were looked at in all.
 */
internal data class FlagScan(
    val candidates: List<ChartCandidate>,
    val rejections: Map<FlagRejection, Int>,
    val windowsConsidered: Int,
)

/**
 * The flag family itself, split out so the two polarities can be exercised separately in tests and
 * so `FlagDetector` stays a thin `PatternDetector`.
 */
internal object FlagEngine {

    /**
     * Roles of one flag.
     *
     * The two channel series are named by **where the breakout has to clear them**, which is the
     * only distinction §18.2 S7 draws:
     *
     * | | bull flag | bear flag |
     * |---|---|---|
     * | `F_i` (`nearPoleTopChannel`) | the **lows** — the lower channel | the **highs** — the upper channel |
     * | `G_i` (`breakChannel`) | the **highs** — the upper channel | the **lows** — the lower channel |
     *
     * So the breakout boundary is **always the `G_i` line**: §18.2 S7 "Bull flag: `close >
     * upperChannelAt(breakoutBar)`", "Bear flag: `close < lowerChannelAt(breakoutBar)`", and in each
     * case that boundary is the one built from the `G_i` pivots.
     */
    internal data class Roles(
        val base: Pivot,
        val poleTop: Pivot,
        /** `F_i`: the boundary nearer the pole top. */
        val nearPoleTopChannel: List<Pivot>,
        /** `G_i`: the boundary the breakout must clear. */
        val breakChannel: List<Pivot>,
        /**
         * Every pivot of the window **in its original interleaved order** — `B, T, F₁, G₁, …`.
         * §4 rule 8 requires `Match.pivots` to be "exactly the pivots the detector used, in order",
         * so the channels are views, not a re-ordering.
         */
        val window: List<Pivot>,
    ) {

        /** `poleHeight = |T.price − B.price|` (§18.2 S1). */
        val poleHeight: Double get() = abs(poleTop.price - base.price)

        /** `polePct = poleHeight / B.price × 100` (§18.2 S1). */
        val polePct: Double get() = if (base.price <= 0.0) 0.0 else poleHeight / base.price * 100.0

        /** `flagHeight` over every flag pivot, both channels. */
        val flagHeight: Double
            get() {
                val prices = (nearPoleTopChannel + breakChannel).map { it.price }
                if (prices.isEmpty()) return 0.0
                return prices.max() - prices.min()
            }

        /** §18.2 S2: "The correction is relatively **small** compared to the Flagpole". */
        val depth: Double
            get() {
                val pole = poleHeight
                return if (pole <= 0.0) 0.0 else flagHeight / pole
            }

        /** §3.2 `ref = max(flagpoleHeight, channelHeight)`. */
        val ref: Double get() = max(poleHeight, flagHeight)

        /** §18.2 S7 — the boundary the breakout has to clear, always the `G_i` line. */
        fun triggerLine(): BoundaryLine = lineThrough(breakChannel.first(), breakChannel.last())

        /** The boundary a **failed continuation** breaks instead (§18.3 `I3`), always the `F_i` line. */
        fun oppositeLine(): BoundaryLine =
            lineThrough(nearPoleTopChannel.first(), nearPoleTopChannel.last())
    }

    fun candidates(
        name: String,
        bull: Boolean,
        context: ChartContext,
        confirmed: List<Pivot>,
    ): List<ChartCandidate> = scan(name, bull, context, confirmed).candidates

    /**
     * The sweep behind [candidates], keeping the rejection census.
     *
     * Production calls only ever look at [FlagScan.candidates]; the census exists so that "no flags
     * on this data" can be shown to be the gates saying no rather than the detector saying nothing.
     */
    fun scan(
        name: String,
        bull: Boolean,
        context: ChartContext,
        confirmed: List<Pivot>,
    ): FlagScan {
        if (context.barCount == 0 || !context.hasSufficientBars) {
            return FlagScan(emptyList(), emptyMap(), 0)
        }
        // `B, T` plus `k` `F`/`G` pairs, `k >= 2`, so the window is at least 6 pivots long.
        val minWindow = 2 + 2 * ChartSpec.FLAG_MIN_TOUCH_PAIRS
        val maxWindow = 2 + 2 * ChartSpec.FLAG_MAX_TOUCH_PAIRS
        val poleTopKind = if (bull) PivotKind.HIGH else PivotKind.LOW
        val firstFlagKind = if (bull) PivotKind.LOW else PivotKind.HIGH
        val out = ArrayList<ChartCandidate>()
        val rejections = LinkedHashMap<FlagRejection, Int>()
        var considered = 0
        for (length in minWindow..maxWindow step 2) {
            for (window in confirmed.windowsOf(length)) {
                if (window[1].kind != poleTopKind) continue
                if (window[0].kind == window[1].kind) continue
                if (window[2].kind != firstFlagKind) continue
                considered++
                when (val verdict = assess(name, bull, context, window)) {
                    is FlagVerdict.Accepted -> out += verdict.candidate
                    is FlagVerdict.Rejected -> rejections.merge(verdict.reason, 1, Int::plus)
                }
            }
        }
        return FlagScan(
            candidates = out.sortedWith(
                compareBy<ChartCandidate> { it.isForming }.thenBy { it.formationStartBar },
            ),
            rejections = rejections,
            windowsConsidered = considered,
        )
    }

    private fun assess(
        name: String,
        bull: Boolean,
        context: ChartContext,
        window: List<Pivot>,
    ): FlagVerdict {
        val timeframe = context.timeframe
        val triggerDirection = if (bull) Direction.UP else Direction.DOWN
        val flagPivots = window.drop(2)
        val nearPoleTopChannel = flagPivots.filter { it.kind == if (bull) PivotKind.LOW else PivotKind.HIGH }
        val breakChannel = flagPivots.filter { it.kind == if (bull) PivotKind.HIGH else PivotKind.LOW }
        if (nearPoleTopChannel.size < ChartSpec.FLAG_MIN_TOUCH_PAIRS) {
            return FlagVerdict.Rejected(FlagRejection.NEAR_CHANNEL_TOUCHES)
        }
        if (breakChannel.size < ChartSpec.FLAG_MIN_TOUCH_PAIRS) {
            return FlagVerdict.Rejected(FlagRejection.BREAK_CHANNEL_TOUCHES)
        }

        val roles = Roles(
            base = window[0],
            poleTop = window[1],
            nearPoleTopChannel = nearPoleTopChannel,
            breakChannel = breakChannel,
            window = window,
        )

        // --- §18.2 S1 — a strong flagpole --------------------------------------------------------
        // "Without a strong Flagpole, there is no Flag." [Ch.18-4 §Flagpole]. Two conditions,
        // because 10 % alone is trivial for a low-priced high-volatility stock and ATR alone is
        // trivial for a slow large cap.
        if (roles.polePct < ChartSpec.poleMinPct(timeframe)) {
            return FlagVerdict.Rejected(FlagRejection.POLE_PCT)
        }
        val poleAtr = context.atrAt(roles.base.barIndex)
            ?: return FlagVerdict.Rejected(FlagRejection.POLE_ATR)
        if (roles.poleHeight < ChartSpec.poleMinAtr(timeframe) * poleAtr) {
            return FlagVerdict.Rejected(FlagRejection.POLE_ATR)
        }

        // --- §18.2 S2 — a shallow flag ------------------------------------------------------------
        // `[derived]` 0.05..0.50: "The correction is relatively **small** compared to the Flagpole"
        // and "Mistaking deep corrections for Flags" [Ch.18-4 §Common Beginner Mistakes]. The 0.05
        // floor stops a straight line being labelled a flag.
        if (roles.depth > ChartSpec.FLAG_DEPTH_MAX) return FlagVerdict.Rejected(FlagRejection.DEPTH_TOO_DEEP)
        if (roles.depth < ChartSpec.FLAG_DEPTH_MIN) {
            return FlagVerdict.Rejected(FlagRejection.DEPTH_TOO_SHALLOW)
        }

        // --- §18.2 S3 — the counter-trend direction ------------------------------------------------
        // "After the rally, price moves **slightly downward or sideways**" [Ch.18-4 §The Flag].
        // `[derived]` the `≤ 0` (not `< 0`) form is what permits the sideways case the book allows.
        val lowerSlope = slopePerBar(roles.nearPoleTopChannel.first(), roles.nearPoleTopChannel.last())
        val upperSlope = slopePerBar(roles.breakChannel.first(), roles.breakChannel.last())
        val driftsDown = upperSlope <= 0.0 && lowerSlope <= 0.0
        val driftsUp = upperSlope >= 0.0 && lowerSlope >= 0.0
        if (bull && !driftsDown) return FlagVerdict.Rejected(FlagRejection.DIRECTION)
        if (!bull && !driftsUp) return FlagVerdict.Rejected(FlagRejection.DIRECTION)

        // --- §18.2 S4 — parallel, not converging (a pennant) -------------------------------------------
        // Flag = "**Parallel** boundaries"; Pennant = "**Converging** boundaries"
        // [Ch.18-4 §Difference Between Flag and Pennant]. 0.50 is the half-parallel point and a
        // pennant is out of scope for v1, so it must be **rejected**, not scored.
        val parallelDev = parallelDeviation(upperSlope, lowerSlope)
        if (parallelDev > ChartSpec.FLAG_PARALLEL_MAX) return FlagVerdict.Rejected(FlagRejection.PARALLEL)

        // --- §18.2 S5 — duration -----------------------------------------------------------------------
        // "Flags and Pennants are **short-duration** consolidation patterns"
        // [Ch.18-4 §Key Takeaways]; `[derived]` 3..20 daily, 2..12 weekly.
        val flagBars = window.last().barIndex - roles.poleTop.barIndex
        if (flagBars < ChartSpec.flagMinBars(timeframe) || flagBars > ChartSpec.flagMaxBars(timeframe)) {
            return FlagVerdict.Rejected(FlagRejection.DURATION)
        }

        val ref = roles.ref
        if (ref <= 0.0) return FlagVerdict.Rejected(FlagRejection.DEGENERATE_REF)

        // --- §18.2 S7 — the breakout ---------------------------------------------------------------------
        val trigger = roles.triggerLine()
        val bufferPct = BreakoutEvaluator.bufferPct(timeframe)
        val breakoutBar = context.firstSlopedBreakout(
            line = trigger,
            fromBar = window.last().barIndex + 1,
            direction = triggerDirection,
            bufferPct = bufferPct,
        )
        val triggerBar = breakoutBar ?: (context.barCount - 1)

        // --- §18.2 S6 — volume contraction inside the flag -------------------------------------------------
        val contraction = context.volumeContraction(window[2].barIndex, window.last().barIndex)

        // --- §3.8 divergence -------------------------------------------------------------------------------
        val divergenceGap = context.bestDivergenceGapRsi(
            pairs = roles.nearPoleTopChannel.zipWithNext(),
            want = triggerDirection,
        )

        // --- structure -----------------------------------------------------------------------------------
        val channelPoints = roles.breakChannel
        val residMax = fitBoundary(channelPoints, ref).residMax
        val boundaryR2 = boundaryRSquared(channelPoints)
        val poleLeg = window[1].barIndex - window[0].barIndex
        val flagLegs = window.drop(1).legGaps()
        val spacing = spacingProduct(
            poleLeg = poleLeg,
            flagLegs = flagLegs,
            poleIdeal = ChartSpec.flagPoleIdeal(timeframe),
            poleTolerance = ChartSpec.flagPoleTolerance(timeframe),
            legIdeal = ChartSpec.flagLegIdeal(timeframe),
            legTolerance = ChartSpec.flagLegTolerance(timeframe),
        )
        val spanBars = window.last().barIndex - window.first().barIndex

        val measures = buildList {
            // ---- GEOMETRY (`scoring-spec.md` §3 and §3.1) --------------------------------------------
            // §3's G1 is a symmetry row for detectors 6–11; a flag has no symmetric pair, because the
            // pole and the flag are *deliberately* unequal in size (§18.2 S2).
            add(Measure.notApplicable("G1 symmetry", ScoreFactor.GEOMETRY))
            add(Measure.notApplicable("G2 extremityDominance", ScoreFactor.GEOMETRY))
            // §3.1's flag row: `polePct`, saturating at a 25 % pole — "a full-strength institutional
            // move".
            add(
                Measure.saturating(
                    "G3 polePct", roles.polePct, 20, ScoreFactor.GEOMETRY,
                    PatternScoringPolicy.GEOM_POLE_SAT,
                ),
            )
            add(Measure.notApplicable("G4 timeSymmetry", ScoreFactor.GEOMETRY))
            add(Measure.notApplicable("G5 |necklineTilt|", ScoreFactor.GEOMETRY))
            add(
                Measure.linear(
                    "G6 channelDrift", channelDrift(roles, bull), 15, ScoreFactor.GEOMETRY,
                    PatternScoringPolicy.GEOM_MONO_LO, PatternScoringPolicy.GEOM_MONO_HI,
                ),
            )
            add(
                Measure.decay(
                    "G7 parallelDev", parallelDev, 20, ScoreFactor.GEOMETRY,
                    PatternScoringPolicy.GEOM_PARALLEL_FLOOR, PatternScoringPolicy.GEOM_PARALLEL_MAX,
                ),
            )
            add(
                Measure.linear(
                    "G8 flagDepth", roles.depth, 25, ScoreFactor.GEOMETRY,
                    PatternScoringPolicy.GEOM_FLAGDEPTH_LO, PatternScoringPolicy.GEOM_FLAGDEPTH_HI,
                ),
            )

            // ---- STRUCTURE (`scoring-spec.md` §4) ------------------------------------------------------
            addAll(
                boundaryStructureMeasures(
                    context = context,
                    residMax = residMax,
                    boundaryR2 = boundaryR2,
                    legs = flagLegs,
                    legIdeal = ChartSpec.flagLegIdeal(timeframe),
                    legTolerance = ChartSpec.flagLegTolerance(timeframe),
                    spanBars = spanBars,
                    spanIdeal = ChartSpec.flagSpanIdeal(timeframe),
                    touchCount = null,
                ),
            )

            // ---- CONFIRMATION (`scoring-spec.md` §5.1) --------------------------------------------------
            addAll(
                chartConfirmationMeasures(
                    context = context,
                    boundary = trigger,
                    breakoutBar = breakoutBar,
                    direction = triggerDirection,
                    divergenceGap = divergenceGap,
                    volumeContraction = contraction,
                ),
            )

            // ---- CONTEXT (`scoring-spec.md` §6) ----------------------------------------------------------
            // §18.1 P3: "Prior trend is satisfied **by construction** (the pole *is* the trend)", and
            // §6: "Flags (12) get `X1 = 100` automatically — the pole *is* the prior trend". The
            // remaining rows keep their §6 weights so ADX, DI and the two unknowns still
            // discriminate; §6's "the weight is redistributed across X2–X6" is ambiguous between
            // moving X1's 35 points onto the others and leaving it in place, and this is reported.
            addAll(
                chartContextMeasures(
                    context = context,
                    priorTrendPct = roles.polePct,
                    triggerBar = triggerBar,
                    direction = triggerDirection,
                    reversal = false,
                    priorTrendByConstruction = true,
                ),
            )
        }

        val penalties = buildPenalties(
            name = name,
            bull = bull,
            context = context,
            roles = roles,
            window = window,
            trigger = trigger,
            breakoutBar = breakoutBar,
            triggerBar = triggerBar,
            triggerDirection = triggerDirection,
            contraction = contraction,
            bufferPct = bufferPct,
        )

        return FlagVerdict.Accepted(
            ChartCandidate(
                name = name,
                pivots = roles.window,
                measures = measures,
                penalties = penalties,
                measuredMove = breakoutBar?.let { bar ->
                    MeasuredMoveCalculator.flagpole(
                        direction = triggerDirection,
                        baseBar = roles.base.barIndex,
                        basePrice = roles.base.price,
                        poleTopPrice = roles.poleTop.price,
                        triggerLevel = trigger.at(bar),
                        breakoutBar = bar,
                        breakoutClose = context.candles[bar].close,
                    )
                },
                formationStartBar = window.first().barIndex,
                breakoutBar = breakoutBar ?: -1,
            ),
        )
    }

    /**
     * `G6` for a flag: how consistently both channel boundaries moved the counter-trend way.
     *
     * `scoring-spec.md` §3.6 lists `monoScore` for detector 12, but the shared `monoScore` is a
     * *strict* rising/falling count and §18.2 S3 explicitly allows a **sideways** flag (`≤ 0`, not
     * `< 0`). Using the strict version would score a textbook sideways flag as `0`. This therefore
     * counts "moved the required way **or stayed flat**", which is the same rule as S3 read as a
     * score, and averages the two boundaries.
     */
    private fun channelDrift(roles: Roles, bull: Boolean): Double {
        val upper = driftScore(roles.breakChannel, bull)
        val lower = driftScore(roles.nearPoleTopChannel, bull)
        return (upper + lower) / 2.0
    }

    private fun driftScore(series: List<Pivot>, bull: Boolean): Double {
        if (series.size < 2) return 0.0
        var ok = 0
        for (i in 1 until series.size) {
            val step = series[i].price - series[i - 1].price
            if (bull && step <= 0.0) ok++
            if (!bull && step >= 0.0) ok++
        }
        return ok.toDouble() / (series.size - 1)
    }

    private fun buildPenalties(
        name: String,
        bull: Boolean,
        context: ChartContext,
        roles: Roles,
        window: List<Pivot>,
        trigger: BoundaryLine,
        breakoutBar: Int?,
        triggerBar: Int,
        triggerDirection: Direction,
        contraction: Double?,
        bufferPct: Double,
    ): List<Penalty> {
        val penalties = ArrayList<Penalty>()
        val trapId = if (bull) PenaltyId.BULL_TRAP else PenaltyId.BEAR_TRAP
        val opposite = if (bull) Direction.DOWN else Direction.UP
        val other = roles.oppositeLine()

        if (breakoutBar != null) {
            // §18.3 I2 — "[Ch.18-4 §Role of Volume]".
            if (context.weakBreakoutVolume(breakoutBar)) {
                penalties += Penalty.of(
                    PenaltyId.WEAK_VOLUME,
                    "$name broke out on volume below ${ChartSpec.RVOL_WEAK}x its 20-bar average.",
                )
            }
            val traps = context.trapEvents(trigger, breakoutBar, triggerDirection)
            repeat(minOf(traps, 2)) { event ->
                val reason = "$name closed back inside the channel within " +
                    "${ChartSpec.trapWindow(context.timeframe)} bars of the break (event ${event + 1})."
                penalties += Penalty.repeat(Penalty.of(trapId, reason), reason)
            }
            val tolerance = context.touchToleranceAt(breakoutBar, trigger.at(breakoutBar))
            if (context.isFailedRetest(trigger, breakoutBar, triggerDirection, tolerance)) {
                penalties += Penalty.of(
                    PenaltyId.FAILED_RETEST,
                    "$name returned to the channel boundary and closed back through it.",
                )
            }
            // §18.3 I3 FAILED_CONTINUATION — "Bull Flag → price breaks downward → heavy volume →
            // trend reverses. Failed continuation patterns often produce strong moves in the
            // opposite direction." Heavier than a plain trap because the book says the *opposite*
            // move is the likely one.
            if (context.passedLevel(breakoutBar, other.at(breakoutBar), opposite, ChartSpec.FAILURE_LOOKAHEAD_BARS)) {
                penalties += Penalty.of(
                    PenaltyId.FAILED_CONTINUATION,
                    "$name broke the opposite channel instead of the one it was pointing at.",
                )
            }
        } else {
            // §18.3 I4's cousin in time, §19 STALE_PATTERN — no breakout within 10 bars of the flag.
            if (context.isStale(window.last().barIndex)) {
                penalties += Penalty.of(
                    PenaltyId.STALE_PATTERN,
                    "$name has gone ${context.barCount - 1 - window.last().barIndex} bars without a breakout.",
                )
            }
            if (context.intradaySpikeOnly(trigger, window.last().barIndex + 1, triggerDirection, bufferPct)) {
                penalties += Penalty.of(
                    PenaltyId.INTRADAY_SPIKE_ONLY,
                    "$name was crossed by an intraday print but no close has cleared the channel.",
                )
            }
        }

        // §18.3 I4 — "volume **expanded** through the flag" is the unhealthy case.
        if (contraction != null && contraction > ChartSpec.VOLUME_CONTRACTION_UNHEALTHY) {
            penalties += Penalty.of(
                PenaltyId.VOLUME_CONTRACTION_ABSENT,
                "Volume during the $name was ${"%.2f".format(contraction)}x the volume before the pole; a " +
                    "healthy continuation formation contracts.",
            )
        }

        // §19 EXHAUSTION applies to detectors 5, 10 and 12 — the bullish ones; the bear flag is a
        // mirror of the same "buying exhaustion" idea and gets the inverted test.
        if (context.isExhausted(triggerBar, triggerDirection)) {
            penalties += Penalty.of(
                PenaltyId.EXHAUSTION,
                "The $name triggered ${ChartSpec.exhaustionLookback(context.timeframe)} bars into a run of " +
                    "at least ${(ChartSpec.EXHAUSTION_MULTIPLE * 100).toInt()}%.",
            )
        }

        if (context.isRanging(triggerBar)) {
            penalties += Penalty.of(
                PenaltyId.RANGING_REGIME,
                "ADX at the channel is below ${ChartSpec.ADX_RANGING}; continuation breakouts often fail " +
                    "in a range.",
            )
        }
        return penalties
    }
}

/**
 * `scoring-spec.md` §4.1's `spacingScore` for a flag, which has **two** ideals.
 *
 * The pole is judged against `gIdeal 24 / gTol 16` and each flag leg against `gIdeal 5 / gTol 4`,
 * and §4.1's rule is a *product* over legs, so the two products are multiplied: one badly-spaced leg
 * is enough to zero the whole measure, which is exactly what §4.1's example B relies on.
 */
private fun spacingProduct(
    poleLeg: Int,
    flagLegs: List<Int>,
    poleIdeal: Double,
    poleTolerance: Double,
    legIdeal: Double,
    legTolerance: Double,
): Double {
    val pole = legSpacingScore(poleLeg, poleIdeal, poleTolerance)
    val legs = spacingScore(flagLegs, legIdeal, legTolerance)
    return (pole * legs).coerceIn(0.0, 1.0)
}