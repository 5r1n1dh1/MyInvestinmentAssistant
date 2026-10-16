package com.mia.app.domain.pattern

import com.mia.app.domain.model.Match
import com.mia.app.domain.model.MatchScorePolicy
import com.mia.app.domain.model.MeasuredMove
import com.mia.app.domain.model.Timeframe
import kotlin.math.abs

/**
 * Whether a match may **project**: draw a measured-move level and schedule a breakout alert.
 *
 * ### Why the gate is about distance and not about the score
 * The gate used to be `MatchScorePolicy.PROJECT_THRESHOLD = 70`, and it was **unreachable**.
 * Measured over 875 settled walk-forward candidates on this app's own fixture
 * (`domain/src/test/resources/fixtures/eod_history.json`, 17 large caps, 5-bar stride, every
 * evaluated detector), the highest score ever produced was **68**. The app's headline feature — a
 * projected level and a notification — had therefore never executed once, and no test had noticed,
 * because a gate nothing can cross is indistinguishable from a gate that works.
 *
 * Worse, the score was not merely useless as a gate, it pointed the wrong way. Hit rate **fell** as
 * the score rose, monotonically across all six horizons:
 *
 * ```
 * bucket      h1    h2    h3    h5   h10   h20     n
 * 50-59      30%   32%   33%   35%   39%   45%   670
 * 60-69      28%   28%   28%   30%   32%   34%   205
 * ```
 *
 * The mechanism is not noise: the score is a proxy for how far away the projected level sits. A
 * more textbook-ideal formation is a *bigger* formation, so it projects a *further* level, so it is
 * harder to reach. Median target distance was 9.7 % for the 50–59 bucket against 14.1 % for
 * 60–69. The score was sorting **against** the outcome.
 *
 * ### What the same measurement found instead
 * Bucketing by the distance from the breakout close to the projected level — the quantity a reader
 * would have to be right about — inverts the result. Reproduced on this fixture by
 * `ProjectionCalibrationTest` (20 large caps, 875 settled candidates, 5-bar stride, reach inside
 * five bars, against a no-pattern base rate drawn at the same level size):
 *
 * ```
 * level within 1 %   90 %   n=49
 * level within 2 %   87 %   n=87
 * level within 3 %   82 %   n=121
 * level within 4 %   73 %   n=169
 * level within 5 %   65 %   n=203
 * beyond 12 %        31 %   n=344
 * base rate, no pattern, 5 % away:  10 %   (1 % 3 % 5 % 10 % 22 % 38 % at h1…h20)
 * ```
 *
 * A level within 3 % is touched 82 % of the time inside five bars, against a 10 % no-pattern base
 * rate — and 87 % of those touches land on the very next bar. So the signal exists, it is
 * concentrated in tight levels, and the score was filtering exactly the wrong end of it.
 *
 * **[DISTANCE_MAX] is therefore the largest distance the calibration still supports**, not a score:
 * it is the edge of the last bucket whose rate stays several times above a same-size base rate.
 *
 * ### What this object does *not* claim
 * A hit is a chart event and never a profit (plan.md §6.1); touching a level says nothing about
 * whether anyone was ever in profit at that point, and nothing here is advice (plan.md §1.4). Every
 * rate this gate is calibrated on is printed with its sample size by
 * [ProjectionCalibration], and a rate below [MatchScorePolicy.MIN_HIT_RATE_SAMPLES] is never
 * reportable at all.
 */
object ProjectionGate {

    /**
     * The calibrated ceiling on `|projectedLevel − lastClose| / lastClose`, as a fraction.
     *
     * `0.05` = the 5 % row of the distance table above: reached 65 % of the time inside five bars
     * (n=203) against a 10 % no-pattern base rate at the same level size. Chosen from the
     * measurement, not from taste — and deliberately *not* re-tuned per pattern, because a
     * per-pattern number chosen after seeing the outcomes is the guess the measurement exists to
     * remove.
     */
    const val DISTANCE_MAX: Double = 0.05

    /** [DISTANCE_MAX] in percent, for the reader-facing note. */
    const val DISTANCE_MAX_PERCENT: Double = DISTANCE_MAX * 100.0

    /** Which condition stopped a match from projecting. Reported so the UI can say *why*. */
    enum class Blocker {
        /** No measured move, so there is no level to project (a candlestick formation, spec DD-14). */
        NO_MEASURED_MOVE,

        /** The level exists but sits further from the close than [DISTANCE_MAX]. */
        DISTANCE_TOO_FAR,

        /** Below [MatchScorePolicy.minBars] for the timeframe (plan.md §6.4). */
        INSUFFICIENT_HISTORY,

        /** The direction-aware ADX / relative-strength gate said no, or is undecided. */
        REGIME_NOT_ALIGNED,
    }

    /**
     * `|projectedLevel − lastClose| / lastClose`, or `null` when there is no level to measure.
     *
     * `null` rather than `0.0` is load-bearing: a candlestick match has no measured move at all,
     * which must never read as "0 % away, comfortably inside the gate".
     *
     * [lastClose] is the **breakout close** — the last bar of the window the pattern was detected
     * on. It is the same price [MeasuredMoveCalculator] projects the height from, so this is the
     * distance between the line the reader sees and the price under it, not an approximation of it.
     */
    fun distance(measuredMove: MeasuredMove?, lastClose: Double): Double? {
        val move = measuredMove ?: return null
        if (!lastClose.isFinite() || lastClose <= 0.0) return null
        val level = move.projectedLevel
        if (!level.isFinite()) return null
        return abs(level - lastClose) / lastClose
    }

    /** [distance] for [match], or `null` when the match carries no level. */
    fun distance(match: Match, lastClose: Double): Double? = distance(match.measuredMove, lastClose)

    /**
     * Whether a projected level sits within [max] of [lastClose].
     *
     * `false` when there is no level at all — a candlestick formation can never project, and that
     * has been true since `OutcomeTracker` refused to track one for exactly this reason.
     */
    fun withinDistance(measuredMove: MeasuredMove?, lastClose: Double, max: Double = DISTANCE_MAX): Boolean {
        val d = distance(measuredMove, lastClose) ?: return false
        return d <= max
    }

    /**
     * The whole gate: a level exists, it is within [max] of [lastClose], the timeframe's bar
     * minimum is met, and the regime gate is satisfied.
     *
     * `regimeAligned` is the direction-aware boolean from
     * [PatternEngine.regimeAligned]: `null` (undecided regime, unknown direction) is **not**
     * alignment, exactly as it was when the score was the gate.
     */
    fun admits(
        measuredMove: MeasuredMove?,
        lastClose: Double,
        timeframe: Timeframe,
        barCount: Int,
        regimeAligned: Boolean?,
        max: Double = DISTANCE_MAX,
    ): Boolean = blocker(measuredMove, lastClose, timeframe, barCount, regimeAligned, max) == null

    /** [admits] for a [Match], which is what the engine holds. */
    fun admits(
        match: Match,
        lastClose: Double,
        timeframe: Timeframe,
        barCount: Int,
        regimeAligned: Boolean?,
        max: Double = DISTANCE_MAX,
    ): Boolean = admits(match.measuredMove, lastClose, timeframe, barCount, regimeAligned, max)

    /**
     * The first unmet condition, or `null` when the match may project.
     *
     * Order is the order a reader would want them explained in: not enough history is a hard stop
     * and is reported before anything softer; a missing level is reported before a distance,
     * because there is nothing to measure; and the regime gate comes last because it is the only
     * condition the reader can act on by waiting.
     */
    fun blocker(
        measuredMove: MeasuredMove?,
        lastClose: Double,
        timeframe: Timeframe,
        barCount: Int,
        regimeAligned: Boolean?,
        max: Double = DISTANCE_MAX,
    ): Blocker? {
        if (!MatchScorePolicy.isSufficientBars(timeframe, barCount)) return Blocker.INSUFFICIENT_HISTORY
        if (measuredMove == null) return Blocker.NO_MEASURED_MOVE
        if (!withinDistance(measuredMove, lastClose, max)) return Blocker.DISTANCE_TOO_FAR
        if (regimeAligned != true) return Blocker.REGIME_NOT_ALIGNED
        return null
    }

    /** [blocker] for a [Match]. */
    fun blocker(
        match: Match,
        lastClose: Double,
        timeframe: Timeframe,
        barCount: Int,
        regimeAligned: Boolean?,
        max: Double = DISTANCE_MAX,
    ): Blocker? = blocker(match.measuredMove, lastClose, timeframe, barCount, regimeAligned, max)
}