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
) {
    /** The level a `Direction.UP` formation projects; `null` when there is no measured move. */
    val projectedLevel: Double? get() = measuredMove?.projectedLevel

    val direction: Direction? get() = measuredMove?.direction

    /**
     * Whether a projection and an alert are allowed at all.
     *
     * plan.md §6.4: `PROJECT` needs `score >= 70` **and** an aligned regime; anything below
     * that may render but must not project. `MatchScorePolicy.PROJECT_THRESHOLD` is read rather
     * than restated so the threshold cannot drift.
     */
    val mayAlert: Boolean get() = score >= MatchScorePolicy.PROJECT_THRESHOLD
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