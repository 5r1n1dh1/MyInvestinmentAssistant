package com.mia.app.data.android.repo

import com.mia.app.domain.model.BreakoutTrigger
import com.mia.app.domain.model.Category
import com.mia.app.domain.model.Direction
import com.mia.app.domain.model.MeasuredMove
import com.mia.app.domain.model.Match
import com.mia.app.domain.model.MatchScorePolicy
import com.mia.app.domain.model.Pivot
import com.mia.app.domain.model.ProjectedPath
import com.mia.app.domain.model.Timeframe
import com.mia.app.domain.pattern.ProjectionGate
import java.time.LocalDate

/**
 * One scan's outcome, in the two halves the UI actually renders.
 *
 * ### What a score is and is not
 * [matches] carry `Match.score`, which measures **geometric fit to the textbook template** and
 * nothing else. plan.md §6.1's control test over 20,780 next-day returns found every signal at
 * ~50 %; any wording that turned the score into a likelihood would be a lie told by
 * arithmetic. The wording lives in `com.mia.app.domain.model.MatchScoreLabels`, which is the
 * single source both this module and `:app` read.
 *
 * ### Why [suppressed] is returned rather than discarded
 * Showing the user *why* a detected pattern was not drawn is how they learn the app is not
 * quietly manufacturing signals (plan.md §1.4). It is also the only honest answer to "why did
 * nothing appear for this stock?".
 */
data class PatternScan(
    val symbol: String,
    val timeframe: Timeframe,
    val matches: List<Match>,
    /** Candidates that scored or gated below the render threshold, each with a reason. */
    val suppressed: List<com.mia.app.domain.pattern.SuppressedMatch>,
    /** Gate outcome for the top-ranked match. */
    val verdict: MatchScorePolicy.Verdict,
    val barCount: Int,
    val minBarsRequired: Int,
    /** True when [barCount] < [minBarsRequired]; then no score was emitted at all. */
    val insufficientHistory: Boolean,
    /** The shared pivot list, so a re-score needs no re-detection (plan.md §5.2). */
    val pivots: List<Pivot>,
    val notes: List<String>,
) {
    val isEmpty: Boolean get() = matches.isEmpty()

    /** The best-scored match, or `null`. */
    fun topMatch(): Match? = matches.firstOrNull()

    fun summary(): String = buildString {
        append(if (matches.isEmpty()) "No pattern" else "${matches.size} pattern(s)")
        append(" on $symbol (${timeframe.name}), ")
        append("$barCount bar(s)")
        if (insufficientHistory) {
            append(" — below plan.md §6.4's gate of $minBarsRequired, so no score was emitted")
        } else {
            append(" — verdict ${verdict.name.lowercase().replace('_', ' ')}")
        }
    }
}

/**
 * One pattern's three projection outputs, kept apart exactly as plan.md §6.6 keeps them
 * (decision D2: both, never conflated).
 *
 * | Output | Field | What it is |
 * |---|---|---|
 * | measured move | [measuredMove] | the formation's height projected from the breakout — geometry |
 * | directional path | [path] | a 1–5 bar dashed path, directional only, no level attached |
 * | breakout alert | [trigger] | a condition on a close — an event, not a forecast |
 *
 * Conflating them is how an app ends up labelling a forecast as a price level. The three fields
 * have three types, three labels and three schedules, and this class never converts one into
 * another.
 *
 * ### Which of the three may actually be shown
 * [path] is `null` whenever [projectionBlocker] is not `null`: a directional path is a projection,
 * and a projection the gate declined is not built in the first place, so nothing downstream can
 * draw one by forgetting to ask. [measuredMove] and [trigger] are **not** withheld — the level is
 * a measurement of the chart and the trigger is a condition on a close — but whether a
 * notification is sent for it is [mayAlert], which asks the same gate.
 *
 * The gate's inputs are carried on this type because `PatternScanWorker` has no candles left to
 * ask, and the rule itself is read from the one object that owns it.
 */
data class PatternProjection(
    val symbol: String,
    val patternId: Long,
    val patternName: String,
    val timeframe: Timeframe,
    val category: Category,
    val score: Int,
    val detectedOn: LocalDate,
    /** Null for a candlestick formation: it has no height to project (spec DD-1). */
    val measuredMove: MeasuredMove?,
    val path: ProjectedPath?,
    val trigger: BreakoutTrigger?,

    // ----------------------------------------------------------------- the projection gate
    //
    // The gate needs four things `ProjectionGate` cannot recover on its own, and the only place
    // all four exist is `DefaultPatternRepository.projectionOf`, where the candles still do. They
    // are **carried** rather than recomputed downstream because by the time a projection reaches
    // `PatternScanWorker` the bars are gone; a gate re-asked without them would have to guess, and
    // a guessed gate is how an alert fires for a formation that never earned one.
    //
    // Defaults are the fail-closed ones: no bars, no history, no regime. `ProjectionGate.blocker`
    // answers `INSUFFICIENT_HISTORY` for a `barCount` of 0 before it looks at anything else, so a
    // projection built without them cannot project or alert. Silence beats a wrong alert.

    /**
     * The breakout close the level is measured from — the newest stored bar of the series the
     * detection was made on, exactly as `PatternEngine.run` takes it. `0.0` when no bars are
     * stored, which the gate reads as "no close to measure from", never as "0 % away".
     */
    val lastClose: Double = 0.0,

    /** Bars in the window this detection was scanned on. `0` when none are stored. */
    val barCount: Int = 0,

    /**
     * The direction-aware ADX / relative-strength gate, resolved where the bars were.
     *
     * `null` means **undecided**, which is not alignment — an unknown regime may render and never
     * project, exactly as it could not when the gate was the score.
     */
    val regimeAligned: Boolean? = null,
) {
    /** The level a `Direction.UP` formation projects; `null` when there is no measured move. */
    val projectedLevel: Double? get() = measuredMove?.projectedLevel

    val direction: Direction? get() = measuredMove?.direction

    /**
     * The first unmet condition of `com.mia.app.domain.pattern.ProjectionGate`, or `null` when this
     * projection may be drawn **and** alerted on.
     *
     * The blocker is carried rather than a boolean so a screen can say *why* there is no projected
     * level — "the level sits 9.4 % from the last close" is an answer and `false` is not. The rule
     * itself is read from the one object that owns it, so this property cannot drift from the
     * engine's own decision.
     */
    val projectionBlocker: ProjectionGate.Blocker?
        get() = ProjectionGate.blocker(measuredMove, lastClose, timeframe, barCount, regimeAligned)

    /** Whether a projected level and its directional path may be drawn for this detection. */
    val mayProject: Boolean get() = projectionBlocker == null

    /**
     * Whether the app is allowed to raise a breakout alert for this detection.
     *
     * The same gate, deliberately: plan.md §6.4 makes projection and alerting one decision, so a
     * notification is never raised for a level the chart refused to draw.
     *
     * The **score is not consulted**, and that is the whole point of W1. `MatchScorePolicy`'s
     * `PROJECT_THRESHOLD` was unreachable (the highest score in 875 settled candidates was 68) and
     * ran backwards against outcomes, so it gated nothing; projection is decided by how far the
     * level sits from the close, which is the quantity a reader would have to be right about.
     */
    val mayAlert: Boolean get() = mayProject

    /**
     * `|projectedLevel − lastClose| / lastClose`, in percent, or `null` when there is no level to
     * measure. The number a reader needs to judge a `DISTANCE_TOO_FAR` blocker for themselves.
     */
    val levelDistancePercent: Double?
        get() = ProjectionGate.distance(measuredMove, lastClose)?.times(100.0)
}

/**
 * The only way `:app` reaches pattern detections.
 *
 * ### Cost, and why the nightly scan is the watchlist
 * plan.md §6.2 computes `ZigzagPivots` and the indicator snapshot **once** per
 * `(symbol, timeframe)` and shares them across all twelve detectors, precisely so the scan fits
 * on a 2 GB phone. Even so, a scan is milliseconds per symbol: 3,051 symbols a night is not a
 * background job, it is a battery complaint. Decision OQ-4 therefore makes the nightly scan
 * **watchlist only**, with everything else on demand, and this repository never triggers a
 * market-wide sweep.
 */
interface PatternRepository {

    /**
     * Detects on the stored series and persists the result (including normalised pivots).
     *
     * Returns a typed failure with [DataFailureReason.NOT_ENOUGH_HISTORY] below plan.md §6.4's
     * gate rather than a scan with a suppressed-everything match list — "not enough history" is
     * a statement the UI must make, and burying it in [PatternScan.suppressed] is how it gets
     * lost.
     */
    suspend fun scan(
        symbol: String,
        timeframe: Timeframe,
    ): DataResult<PatternScan>

    /** Detections already stored for `(symbol, timeframe)`, newest first. No detection run. */
    suspend fun storedMatches(symbol: String, timeframe: Timeframe): DataResult<List<PatternProjection>>

    /**
     * The projections and alerts for a stored symbol, including the breakout state evaluated
     * against the newest bar. This is what the pattern screen renders.
     */
    suspend fun projections(symbol: String, timeframe: Timeframe): DataResult<List<PatternProjection>>

    /** How many detections this device holds. Diagnostics, and the accuracy screen's context. */
    suspend fun storedCount(): Int

    companion object {
        /**
         * plan.md §3.2's documented shapes, reused as the "is the backfill complete?" test:
         * `needsBackfill` in `:data` answers false above them.
         */
        const val BACKFILL_DAILY_ROWS: Int = 500
        const val BACKFILL_WEEKLY_ROWS: Int = 261
    }
}