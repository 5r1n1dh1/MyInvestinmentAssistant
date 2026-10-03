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
import com.mia.app.domain.pattern.fitBoundary
import com.mia.app.domain.pattern.monoScore
import com.mia.app.domain.pattern.progressPctPerBar
import com.mia.app.domain.model.Measure
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The one implementation of the two triangles, shared by [AscendingTriangleDetector] (§16) and
 * [DescendingTriangleDetector] (§17).
 *
 * **Why shared.** §17 is "Exact mirror of §16" and prints the inverse of every rule; two copies of
 * a sliding window over a pivot list is where they drift.
 *
 * **The two window shapes, and why they differ.** §16.1 P2 describes the ascending triangle as
 * "a window of `2m + 1` consecutive pivots **starting and ending at a LOW**", and its §16.7 fixture
 * is 7 pivots (`L₀ H₁ L₁ H₂ L₂ H₃ L₃`) with `m = 3` highs and 4 lows. §17's prose says "Window ends
 * at a `HIGH`" but its own enumeration (`H₀, L₁, H₁, L₂, …, L_m`) **and** its §17.7 fixture (6 pivots
 * `H₀ L₁ H₁ L₂ H₂ L₃`, ending at a LOW) say otherwise. Both readings are accepted here — an even
 * window `2m` starting at a HIGH and an odd window `2m + 1` starting at a HIGH — so neither reading
 * can silently lose a formation. For each starting index the **longest** qualifying window wins,
 * because the most-developed formation is the one the user is looking at and §4 rule 6 forbids two
 * matches for one formation.
 *
 * | | `ascending = true` (§16) | `ascending = false` (§17) |
 * |---|---|---|
 * | flat side | the `m` **highs** → `resistance = mean` | the `m` **lows** → `support = mean` |
 * | drifting side | the `m + 1` lows, strictly **rising** | the highs, strictly **falling** |
 * | `ref` (§3.2) | `resistance − lowestLow` | `highestHigh − support` |
 * | prior trend (§16.1 P3) | `priorTrendPct(L₀) ≥ +3.0 %` | `priorTrendPct(H₀) ≤ −3.0 %` |
 * | trigger | close **above** the flat side | close **below** the flat side |
 * | direction | `UP` | `DOWN` |
 */
internal object TriangleEngine {

    private const val MAX_WINDOW_PIVOTS = 2 * ChartSpec.TRI_MAX_TOUCHES + 1

    /** The pivot roles of one triangle window. */
    internal data class Roles(
        /** The drifting boundary, oldest first: the lows for an ascending triangle. */
        val drift: List<Pivot>,
        /** The flat boundary, oldest first: the highs for an ascending triangle. */
        val flat: List<Pivot>,
        /** The window in original order, which is what `Match.pivots` reports. */
        val window: List<Pivot>,
    ) {
        /** §3.2 `ref`: the flat side minus the opposite extreme. */
        val ref: Double
            get() {
                val flatLevel = flat.map { it.price }.average()
                val opposite = if (drift.size > flat.size) drift.map { it.price }.min()
                else drift.map { it.price }.max()
                return if (opposite < flatLevel) flatLevel - opposite else opposite - flatLevel
            }

        /** The flat boundary as a price level (its `mean`, per §16.1 P2). */
        val flatLevel: Double get() = flat.map { it.price }.average()

        /** The flat side's own spread as a % of the level — §16.2 S1's `resTolPct`. */
        val flatTolerancePct: Double
            get() {
                val level = flatLevel
                if (level <= 0.0) return 0.0
                return flat.maxOf { abs(it.price - level) } / level * 100.0
            }

        /** `m`: the number of pivots touching the flat side. */
        val touches: Int get() = flat.size
    }

    fun candidates(
        name: String,
        ascending: Boolean,
        context: ChartContext,
        confirmed: List<Pivot>,
    ): List<ChartCandidate> {
        if (context.barCount == 0 || !context.hasSufficientBars) return emptyList()
        if (confirmed.size < 3) return emptyList()
        val startKind = if (ascending) PivotKind.LOW else PivotKind.HIGH
        val out = ArrayList<ChartCandidate>()
        // Longest window first so the first qualifying one for a start index is also the most
        // developed formation at that index.
        for (length in (2 * ChartSpec.TRI_MIN_TOUCHES)..MAX_WINDOW_PIVOTS) {
            for (window in confirmed.windowsOf(length)) {
                if (window.first().kind != startKind) continue
                // `patterns-spec.md` §2.5: the shared zigzag never emits two consecutive same-kind
                // pivots, and a window that contains such a pair is not a formation — it is a
                // formation plus a swing that has already broken the boundary. Rejecting it is also
                // what stops a triangle from reporting a breakout bar that is one of its own pivots.
                if (!window.hasAlternatingKinds()) continue
                // §16.1 P2 is explicit for the ascending triangle: the window starts **and ends** at a
                // LOW. Without the closing half of that rule a window whose last pivot is the *break*
                // (a HIGH beyond the flat side) is admitted, which both folds the break into the
                // resistance mean and reports a breakout bar that is one of its own pivots. §17's
                // prose and §17's fixture disagree about which end the descending window closes on,
                // so both ends are accepted there — see the class KDoc.
                if (ascending && window.last().kind != startKind) continue
                assess(name, ascending, context, window)?.let { out += it }
            }
        }
        return dedupeByFormation(out)
    }

    /**
     * One match per physical shape, not one per window (§4 rule 6).
     *
     * Two reductions, both of which exist because the spec's windows overlap heavily: for each
     * starting index the **longest** qualifying window wins (the most developed formation is the one
     * the user is looking at), and a window whose pivots are all contained in a longer accepted
     * window is dropped. Without the second reduction a seven-pivot ascending triangle also emits
     * its own five-pivot tail, and the UI would draw the same shape twice.
     */
    private fun dedupeByFormation(candidates: List<ChartCandidate>): List<ChartCandidate> {
        val longestPerStart = candidates
            .sortedWith(
                compareBy<ChartCandidate> { it.formationStartBar }
                    .thenByDescending { it.pivots.size }
                    .thenByDescending { it.breakoutBar },
            )
            .distinctBy { it.formationStartBar }
        val accepted = longestPerStart.toMutableList()
        val longest = longestPerStart.maxOfOrNull { it.pivots.size } ?: 0
        accepted.removeAll { candidate ->
            candidate.pivots.size < longest &&
                longestPerStart.any { other ->
                    other !== candidate &&
                        other.pivots.size > candidate.pivots.size &&
                        candidate.pivots.all { p -> other.pivots.any { it.barIndex == p.barIndex } }
                }
        }
        return accepted.sortedWith(compareBy<ChartCandidate> { it.isForming }.thenBy { it.formationStartBar })
    }

    /**
     * One formation, with the window **truncated at the break**.
     *
     * **The truncation is not cosmetic.** The bar that breaks the flat side is very often itself a
     * zigzag pivot, and a pivot beyond the boundary is the *break*, not a touch of it. Folding it
     * into the flat side would corrupt `resistance` (its own mean) and make a formation look flatter
     * than it is — and it would find a "breakout" on a bar that is itself one of the reported pivots.
     * So the assessment runs on the window as given; if the resulting formation reports pivots at or
     * after its own breakout bar, it is re-assessed on the window truncated just before that bar.
     */
    private fun assess(
        name: String,
        ascending: Boolean,
        context: ChartContext,
        window: List<Pivot>,
    ): ChartCandidate? {
        var current = window
        repeat(MAX_TRUNCATIONS + 1) { attempt ->
            val candidate = assessOnce(name, ascending, context, current)
            if (candidate == null || candidate.isForming) return candidate
            val firstAfterBreak = candidate.pivots.firstOrNull { it.barIndex >= candidate.breakoutBar }
                ?: return candidate
            val cut = current.indexOfFirst { it.barIndex == firstAfterBreak.barIndex }
            if (attempt == MAX_TRUNCATIONS || cut <= 0) return candidate
            current = current.subList(0, cut)
        }
        return null
    }

    /** Two truncations is enough: one for the break bar, one if the retest bar is also a pivot. */
    private const val MAX_TRUNCATIONS: Int = 2

    private fun assessOnce(
        name: String,
        ascending: Boolean,
        context: ChartContext,
        window: List<Pivot>,
    ): ChartCandidate? {
        val timeframe = context.timeframe
        val lows = window.filter { it.kind == PivotKind.LOW }
        val highs = window.filter { it.kind == PivotKind.HIGH }
        val drift = if (ascending) lows else highs
        val flat = if (ascending) highs else lows
        // The flat side needs at least §16.1 P2's `m >= 2` touches, and the drifting side needs one
        // more pivot than the flat side so that the formation starts *and* ends on it.
        if (flat.size < ChartSpec.TRI_MIN_TOUCHES || drift.size < ChartSpec.TRI_MIN_TOUCHES + 1) return null

        val roles = Roles(drift = drift, flat = flat, window = window)
        val trendDirection = if (ascending) Direction.UP else Direction.DOWN
        val triggerDirection = if (ascending) Direction.UP else Direction.DOWN

        // --- §16.1 P3 / §17 P3 — prior trend ---------------------------------------------------
        // "Professional Checklist #1: Is there a strong trend before the pattern?"
        // [Ch.18-4 §Professional Checklist]; "a chart pattern must be interpreted relative to the
        // preceding trend" [Ch.18-1].
        if (!context.hasPriorTrend(drift.first().barIndex, trendDirection)) return null

        // --- §16.2 S1 / §17 S1 — the flat side ---------------------------------------------------
        // `[derived]` FLAT_BAND_PCT = `DEPTH_PCT` per timeframe: the flat side must sit *inside
        // noise, which is exactly what "significant swing points" means in [Ch.16-1]. Tighter would
        // reject real formations; looser would swallow pennants.
        val flatTolerancePct = roles.flatTolerancePct
        if (flatTolerancePct > ChartSpec.flatBandPct(timeframe)) return null

        // --- §16.2 S2 / §17 S2 — the drifting side is monotone -------------------------------------
        // "Higher Lows. Each correction ends higher. Demand increases." [Ch.18-4 §Structure/§Psychology]
        val mono = monoScore(drift, rising = ascending)
        if (mono < 1.0) return null

        // --- §16.2 S3 / §17 S3 — the drifting side actually progresses --------------------------------
        // `[derived]` 0.05 %/bar daily, 0.10 weekly: over a 25-bar daily triangle that is +1.25 %.
        val progress = progressPctPerBar(drift)
        val required = ChartSpec.risingFloorPctPerBar(timeframe)
        if (ascending && progress < required) return null
        if (!ascending && progress > -required) return null

        // --- §16.2 S4 / §17 S4 — not a wedge ----------------------------------------------------------
        // `[Ch.18-4 §Wedges]` defines a wedge as "both trendlines slope in the same direction", so a
        // drifting side steeper than normal volatility is a wedge, which has no v1 detector.
        val lastDrift = drift.last()
        val atr = context.atrAt(lastDrift.barIndex) ?: return null
        val wedgeCap = ChartSpec.TRI_WEDGE_ATR_MULTIPLE * atr / lastDrift.price * 100.0
        if (abs(progress) > wedgeCap) return null

        // --- §16.2 S5 / §17 S5 — duration -----------------------------------------------------------------
        val spanBars = window.spanBars()
        if (spanBars < ChartSpec.triMinSpanBars(timeframe) || spanBars > ChartSpec.triMaxSpanBars(timeframe)) return null

        // --- §16.2 S6 / §17 S6 — the breakout ---------------------------------------------------------------
        val level = BoundaryLine.horizontal(roles.flatLevel)
        val bufferPct = BreakoutEvaluator.bufferPct(timeframe)
        val breakoutBar = context.firstSlopedBreakout(
            line = level,
            fromBar = window.last().barIndex + 1,
            direction = triggerDirection,
            bufferPct = bufferPct,
        )
        val triggerBar = breakoutBar ?: (context.barCount - 1)

        val ref = roles.ref
        if (ref <= 0.0) return null

        // --- §16.3 I4 / §18 S6 — volume through the formation ---------------------------------------------
        // "During consolidation … volume naturally **contracts**" [Ch.18-4 §Volume During Continuation
        // Patterns] — and for a continuation pattern contraction is a **requirement**, not a bonus.
        val contraction = context.volumeContraction(window.first().barIndex, window.last().barIndex)

        // --- §3.8 divergence ----------------------------------------------------------------------------
        val divergenceGap = context.bestDivergenceGapRsi(
            pairs = drift.zipWithNext(),
            want = triggerDirection,
        )

        val priorTrend = context.priorTrendPctAt(drift.first().barIndex) ?: 0.0
        // §3 G1's triangle row: "G1 uses `sym` of the first and last touch on the flat side,
        // normalised against the tolerance band": `triSym = 1 − resTolPct / FLAT_BAND_PCT`.
        val triSym = 1.0 - flatTolerancePct / ChartSpec.flatBandPct(timeframe)
        // §3.1's triangle row: `risePct` / `fallPct` over the whole drifting side.
        val risePct = abs((drift.last().price - drift.first().price) / drift.first().price * 100.0)

        val measures = buildList {
            // ---- GEOMETRY (`scoring-spec.md` §3 and §3.1) ------------------------------------------------
            add(
                Measure.linear(
                    "G1 triSym", triSym.coerceIn(0.0, 1.0), 20, ScoreFactor.GEOMETRY, 0.0, 1.0,
                ),
            )
            // §3's G2 (extremity dominance) is a head-vs-shoulder row and has no triangle analogue.
            add(Measure.notApplicable("G2 extremityDominance", ScoreFactor.GEOMETRY))
            add(
                Measure.linear(
                    "G3 risePct", risePct, 20, ScoreFactor.GEOMETRY,
                    PatternScoringPolicy.GEOM_TRI_RISE_LO, PatternScoringPolicy.GEOM_TRI_RISE_HI,
                ),
            )
            add(Measure.notApplicable("G4 timeSymmetry", ScoreFactor.GEOMETRY))
            add(Measure.notApplicable("G5 |necklineTilt|", ScoreFactor.GEOMETRY))
            add(
                Measure.linear(
                    "G6 monoScore", mono, 15, ScoreFactor.GEOMETRY,
                    PatternScoringPolicy.GEOM_MONO_LO, PatternScoringPolicy.GEOM_MONO_HI,
                ),
            )
            // §3's G7 (channel conformity) is the flag-vs-pennant row; a triangle's flat side is the
            // opposite of a converging boundary, so it does not apply.
            add(Measure.notApplicable("G7 parallelDev", ScoreFactor.GEOMETRY))
            // §3's G8 triangle row: `ref / ATR` — "how tall the formation is against its own volatility".
            add(
                Measure.linear(
                    "G8 formationHeightAtr", ref / atr, 25, ScoreFactor.GEOMETRY,
                    PatternScoringPolicy.GEOM_TRI_REF_LO, PatternScoringPolicy.GEOM_TRI_REF_HI,
                ),
            )

            // ---- STRUCTURE (`scoring-spec.md` §4) ------------------------------------------------------------
            // §4 S1's `residMax` is meaningful here: every flat-side touch is a residual against the
            // flat line, which is precisely what S1 measures.
            addAll(
                boundaryStructureMeasures(
                    context = context,
                    residMax = fitBoundary(flat, ref).residMax,
                    // §4 S3's band `LIN_LINEAR(r², 0.40, 0.98)` is a **neckline/channel** row. A
                    // triangle's defining boundary is horizontal, so its own regression reports
                    // `r² ≈ 0` for exactly the formations §16.2 S1 accepts — reusing the band would
                    // score a textbook triangle as a bad fit. Marked inapplicable (and therefore
                    // recorded in `FactorAssessment.inapplicableMeasures`, which is how
                    // `scoring-spec.md` §8.1 says an unavailable measure must be surfaced).
                    boundaryR2 = PatternScoringPolicy.NEUTRAL_BOUNDARY_R2,
                    boundaryR2Applicable = false,
                    legs = window.legGaps(),
                    legIdeal = ChartSpec.triangleLegIdeal(timeframe),
                    legTolerance = ChartSpec.triangleLegTolerance(timeframe),
                    spanBars = spanBars,
                    spanIdeal = ChartSpec.triangleSpanIdeal(timeframe),
                    touchCount = roles.touches,
                ),
            )

            // ---- CONFIRMATION (`scoring-spec.md` §5.1) ---------------------------------------------------------
            addAll(
                chartConfirmationMeasures(
                    context = context,
                    boundary = level,
                    breakoutBar = breakoutBar,
                    direction = triggerDirection,
                    divergenceGap = divergenceGap,
                    volumeContraction = contraction,
                ),
            )

            // ---- CONTEXT (`scoring-spec.md` §6) ----------------------------------------------------------------
            addAll(
                chartContextMeasures(
                    context = context,
                    priorTrendPct = priorTrend,
                    triggerBar = triggerBar,
                    direction = triggerDirection,
                    reversal = false,
                ),
            )
        }

        val penalties = buildPenalties(
            name = name,
            context = context,
            window = window,
            level = level,
            breakoutBar = breakoutBar,
            triggerBar = triggerBar,
            triggerDirection = triggerDirection,
            contraction = contraction,
            bufferPct = bufferPct,
        )

        return ChartCandidate(
            name = name,
            pivots = roles.window,
            measures = measures,
            penalties = penalties,
            // §16.4 / §17 — DD-4: the book's universal construction applied to the triangle's
            // defining height. The one v1 measured move the book does not state verbatim.
            measuredMove = breakoutBar?.let { bar ->
                MeasuredMoveCalculator.triangle(
                    direction = triggerDirection,
                    flatSide = roles.flatLevel,
                    oppositeExtreme = if (ascending) drift.minOf { it.price } else drift.maxOf { it.price },
                    breakoutClose = context.candles[bar].close,
                )
            },
            formationStartBar = window.first().barIndex,
            breakoutBar = breakoutBar ?: -1,
        )
    }

    /**
     * §16.3 / §17's penalty set, restricted to the ids §19.1 lists for detectors 10 and 11.
     *
     * `NO_DIVERGENCE` is **not** listed for these detectors in §19.1, so a triangle without a
     * divergence loses confirmation points (C7) but is never penalised for it.
     */
    private fun buildPenalties(
        name: String,
        context: ChartContext,
        window: List<Pivot>,
        level: BoundaryLine,
        breakoutBar: Int?,
        triggerBar: Int,
        triggerDirection: Direction,
        contraction: Double?,
        bufferPct: Double,
    ): List<Penalty> {
        val penalties = ArrayList<Penalty>()
        val trapId = if (triggerDirection == Direction.UP) PenaltyId.BULL_TRAP else PenaltyId.BEAR_TRAP

        if (breakoutBar != null) {
            // §16.3 I2 — "Volume typically declines during formation, expands during breakout".
            if (context.weakBreakoutVolume(breakoutBar)) {
                penalties += Penalty.of(
                    PenaltyId.WEAK_VOLUME,
                    "$name broke out on volume below ${ChartSpec.RVOL_WEAK}x its 20-bar average.",
                )
            }
            // §16.3 I3 — a close beyond the flat side then back inside it.
            val traps = context.trapEvents(level, breakoutBar, triggerDirection)
            repeat(minOf(traps, 2)) { event ->
                val reason = "$name closed back inside the flat side within " +
                    "${ChartSpec.trapWindow(context.timeframe)} bars of the break (event ${event + 1})."
                penalties += Penalty.repeat(Penalty.of(trapId, reason), reason)
            }
            // §19 FAILED_RETEST.
            val tolerance = context.touchToleranceAt(breakoutBar, level.at(breakoutBar))
            if (context.isFailedRetest(level, breakoutBar, triggerDirection, tolerance)) {
                penalties += Penalty.of(
                    PenaltyId.FAILED_RETEST,
                    "$name returned to the flat side and closed back through it against the break.",
                )
            }
        } else {
            // §16.3 I5 — "no breakout within 10 bars of the last touch → triangle is expiring".
            if (context.isStale(window.last().barIndex)) {
                penalties += Penalty.of(
                    PenaltyId.STALE_PATTERN,
                    "$name has gone ${context.barCount - 1 - window.last().barIndex} bars without a breakout.",
                )
            }
            if (context.intradaySpikeOnly(level, window.last().barIndex + 1, triggerDirection, bufferPct)) {
                penalties += Penalty.of(
                    PenaltyId.INTRADAY_SPIKE_ONLY,
                    "$name was crossed by an intraday print but no close has cleared the flat side.",
                )
            }
        }

        // §16.3 I4 — "volume **expanded** during the formation" is the unhealthy case:
        // "high volume during correction … may indicate institutional selling"
        // [Ch.18-4 §Role of Volume].
        if (contraction != null && contraction > ChartSpec.VOLUME_CONTRACTION_UNHEALTHY) {
            penalties += Penalty.of(
                PenaltyId.VOLUME_CONTRACTION_ABSENT,
                "Volume during the $name was ${"%.2f".format(contraction)}x the volume before it; a " +
                    "healthy continuation formation contracts.",
            )
        }

        // §19 EXHAUSTION — §19.1 applies this to detectors 5, 10 and 12, i.e. the *bullish*
        // continuation families; a descending triangle is not in the matrix and gets none.
        if (triggerDirection == Direction.UP && context.isExhausted(triggerBar, Direction.UP)) {
            penalties += Penalty.of(
                PenaltyId.EXHAUSTION,
                "The $name triggered ${ChartSpec.exhaustionLookback(context.timeframe)} bars into a " +
                    "run of at least ${(ChartSpec.EXHAUSTION_MULTIPLE * 100).toInt()}%.",
            )
        }

        // §19 RANGING_REGIME.
        if (context.isRanging(triggerBar)) {
            penalties += Penalty.of(
                PenaltyId.RANGING_REGIME,
                "ADX at the flat side is below ${ChartSpec.ADX_RANGING}; continuation breakouts often fail " +
                    "in a range.",
            )
        }
        return penalties
    }
}

/**
 * Detector 10 — `Ascending Triangle` (`patterns-spec.md` §16).
 *
 * `Category.CONTINUATION`, `direction = UP`. Roles: a window of `2m + 1` consecutive confirmed pivots
 * starting **and** ending at a `LOW` — `L₀, H₁, L₁, …, H_m, L_m` with `m ≥ 2` — where
 * `resistance = mean(H₁…H_m)` is flat and the lows rise strictly.
 *
 * Measured move is **DD-4**, the one v1 measured move not taken verbatim from the book: the book's
 * universal construction `[Ch.18-5 §Measuring Price Targets]` ("measure the formation, project it
 * from the breakout") applied to the triangle's defining height, `resistance − lowestLow`.
 */
class AscendingTriangleDetector : PatternDetector {

    override val name: String = "Ascending Triangle"

    override val category: Category = Category.CONTINUATION

    /**
     * `TRI_MIN_SPAN_BARS(W1) = 6` plus the five pivots a two-touch window needs and one bar of §2.5
     * confirmation lag, rounded up; deliberately below the plan.md §6.4 gates.
     */
    override val minBars: Int = 15

    override fun detect(pivots: List<Pivot>, candles: List<Candle>): List<Match> {
        val context = ChartContext.of(candles)
        if (!context.hasSufficientBars) return emptyList()
        val confirmed = context.confirmed(pivots)
        return buildChartMatches(
            this,
            candles,
            TriangleEngine.candidates(name, ascending = true, context, confirmed),
        )
    }
}

/**
 * Detector 11 — `Descending Triangle` (`patterns-spec.md` §17).
 *
 * `Category.CONTINUATION`, `direction = DOWN`. Roles: a window of `m` highs and `m` (or `m + 1`) lows
 * where `support = mean(L₁…L_m)` is flat and the highs fall strictly.
 *
 * Measured move is DD-4, the mirror: `height = highestHigh − support`,
 * `projectedLevel = breakoutClose − height`.
 */
class DescendingTriangleDetector : PatternDetector {

    override val name: String = "Descending Triangle"

    override val category: Category = Category.CONTINUATION

    /** See [AscendingTriangleDetector.minBars] — the window requirement is identical. */
    override val minBars: Int = 15

    override fun detect(pivots: List<Pivot>, candles: List<Candle>): List<Match> {
        val context = ChartContext.of(candles)
        if (!context.hasSufficientBars) return emptyList()
        val confirmed = context.confirmed(pivots)
        return buildChartMatches(
            this,
            candles,
            TriangleEngine.candidates(name, ascending = false, context, confirmed),
        )
    }
}