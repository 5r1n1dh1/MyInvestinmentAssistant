package com.mia.app.domain.pattern

import com.mia.app.domain.compliance.Disclosure
import com.mia.app.domain.model.BreakoutTrigger
import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.Direction
import com.mia.app.domain.model.MatchScoreLabels
import com.mia.app.domain.model.MeasuredMove
import com.mia.app.domain.model.ProjectedPath
import com.mia.app.domain.model.Timeframe
import com.mia.app.domain.technical.atr
import kotlin.math.abs

/**
 * The three projection outputs of plan.md §6.6, kept deliberately apart (decision D2: both, never
 * conflated).
 *
 * | Output | Type | What it is |
 * |--------|------|------------|
 * | measured move | [MeasuredMove] | the **height of the formation** projected from the breakout — chart geometry |
 * | directional path | [ProjectedPath] | a **1–5 bar dashed path**, directional only, no level attached |
 * | breakout alert | [BreakoutTrigger] | a **condition on a close**, an event, not a forecast |
 *
 * Conflating them is how an app ends up labelling a forecast as a price level. They have three
 * different types, three different labels and three different schedules, and this file never
 * converts one into another.
 *
 * **Every string here comes from `MatchScoreLabels` / `Disclosure`** (plan.md §1.4 rule 1). The
 * projected level is pattern geometry — never a price target — and [ProjectionLabels] is the
 * single place that says so.
 */
object ProjectionLabels {
    /** Label for a measured move on the chart. */
    const val MEASURED_MOVE_LABEL: String = MatchScoreLabels.PROJECTION_LABEL

    /** The line the app must attach to a projected level. */
    const val NOT_A_TARGET: String = MatchScoreLabels.NOT_A_TARGET_WARNING

    /** Footer for the whole projection surface. */
    const val FOOTER: String = Disclosure.PROJECTION_FOOTER

    /** What a score is, for the panel that shows the projections. */
    const val SCORE_MEANING: String = Disclosure.SCORE_MEANING

    /**
     * Label for the dashed 1–5 bar path. Deliberately identical to
     * [ProjectedPath.label]'s default; a test asserts they match, because the path's own type is
     * the only place this string can appear.
     */
    const val PATH_LABEL: String = "Projected path (directional, not a forecast of price)"

    /** Human name of a boundary, used to describe a breakout condition in plain words. */
    fun breakoutDescription(subject: String, direction: Direction): String =
        if (direction == Direction.UP) "Close above the $subject" else "Close below the $subject"
}

/**
 * The book's measured-move constructions (`patterns-spec.md` §12.4, §13, §14.4, §16.4, §18.4).
 *
 * **Why primitives rather than one function.** The book gives a *procedure* — "measure the
 * formation, project it from the breakout" `[Ch.18-5 §Measuring Price Targets]` — and then a
 * different specific height for each family: head to neckline (H&S), peak to trough (double top),
 * flat side to opposite extreme (triangles, **DD-4**, the one rule the book does not state), and
 * the pole itself (flag). Each detector therefore builds its own [MeasuredMove] from these
 * primitives, so the height can never be quietly substituted for the one the book gives.
 *
 * **The invariant** (`patterns-spec.md` §24 check 4): `height >= 0` always. Every factory routes
 * through [heightAbove]/[heightBelow], which are absolute differences, so a detector that passes
 * its arguments in the wrong order produces a level the chart shows as a geometry, never a
 * negative height that renders as an upside-down arrow.
 */
object MeasuredMoveCalculator {

    /** `|extreme − level|` — the height of a formation whose extreme sits **above** its boundary. */
    fun heightAbove(level: Double, extreme: Double): Double = abs(sanitise(extreme) - sanitise(level))

    /** `|level − extreme|` — the mirror, for a formation whose extreme sits **below** its boundary. */
    fun heightBelow(level: Double, extreme: Double): Double = heightAbove(level, extreme)

    /** The height of an extreme measured against a *sloping* boundary at the extreme's own bar. */
    fun heightAt(boundary: BoundaryLine, extremeBar: Int, extremePrice: Double): Double =
        heightAbove(boundary.at(extremeBar), extremePrice)

    /**
     * The universal construction: `triggerLevel` is the boundary at the breakout bar and the
     * projected level is the breakout **close** ± the height.
     *
     * This is the book's two steps verbatim for H&S `[Ch.18-2 §Measuring the Price Target]`:
     * "Measure the distance: Head ↓ Neckline … Difference ₹200. **Step 2** Subtract this distance
     * from the breakout point."
     *
     * Note the arithmetic is measured **from the breakout close**, not from the boundary, so
     * `projectedLevel != 2·boundary − extreme` unless the breakout happened exactly at the
     * boundary. That is deliberate — `patterns-spec.md` §23 C7 says follow the book literally and
     * show the breakout close in the evidence, so the rendered line and a charting platform's line
     * can be reconciled visually.
     */
    fun fromBoundary(
        direction: Direction,
        boundary: BoundaryLine,
        extremeBar: Int,
        extremePrice: Double,
        breakoutBar: Int,
        breakoutClose: Double,
    ): MeasuredMove {
        val height = heightAt(boundary, extremeBar, extremePrice)
        val trigger = boundary.at(breakoutBar)
        val projected = when (direction) {
            Direction.UP -> sanitise(breakoutClose) + height
            Direction.DOWN -> sanitise(breakoutClose) - height
        }
        return MeasuredMove(
            height = height,
            triggerLevel = trigger,
            direction = direction,
            projectedLevel = projected,
        )
    }

    /** [fromBoundary] against a horizontal boundary: a double top's confirmation level, §14.4. */
    fun fromHorizontal(
        direction: Direction,
        level: Double,
        extremePrice: Double,
        breakoutBar: Int,
        breakoutClose: Double,
    ): MeasuredMove = fromBoundary(
        direction = direction,
        boundary = BoundaryLine.horizontal(level),
        extremeBar = breakoutBar,
        extremePrice = extremePrice,
        breakoutBar = breakoutBar,
        breakoutClose = breakoutClose,
    )

    /**
     * Flag measured move, §18.4: **"Flag — Measure: Flagpole. Project from breakout."**
     * (`[Ch.18-5]`; the book's own numbers: pole ₹100, breakout ₹500 ⇒ 600).
     *
     * The trigger is the channel boundary the breakout cleared, not the pole top.
     */
    fun flagpole(
        direction: Direction,
        baseBar: Int,
        basePrice: Double,
        poleTopPrice: Double,
        triggerLevel: Double,
        breakoutBar: Int,
        breakoutClose: Double,
    ): MeasuredMove {
        val height = heightAbove(basePrice, poleTopPrice)
        // `baseBar` participates only so the signature matches the other factories; the pole's
        // height does not depend on where the base sits.
        require(baseBar >= 0) { "baseBar must be a real bar index" }
        val projected = when (direction) {
            Direction.UP -> sanitise(breakoutClose) + height
            Direction.DOWN -> sanitise(breakoutClose) - height
        }
        return MeasuredMove(height, sanitise(triggerLevel), direction, projected)
    }

    /**
     * Triangle measured move, `patterns-spec.md` §16.4 / **DD-4** — the only v1 measured move the
     * book does not state verbatim: the book's universal construction applied to the triangle's
     * defining height, `flat side − opposite extreme`.
     */
    fun triangle(
        direction: Direction,
        flatSide: Double,
        oppositeExtreme: Double,
        breakoutClose: Double,
    ): MeasuredMove {
        val height = heightAbove(flatSide, oppositeExtreme)
        val projected = when (direction) {
            Direction.UP -> sanitise(breakoutClose) + height
            Direction.DOWN -> sanitise(breakoutClose) - height
        }
        return MeasuredMove(height, sanitise(flatSide), direction, projected)
    }

    private fun sanitise(value: Double): Double = if (value.isFinite()) value else 0.0
}

/**
 * The 1–5 bar directional path (plan.md §6.6 item 2).
 *
 * **Semantically distinct from the measured move and deliberately so.** The path has *no level*
 * in it: it is where the direction points over the next few sessions, drawn dashed, and its label
 * says it is not a forecast of price. The measured move is a level derived from the formation's
 * geometry. A user who reads one as the other has been told something the app never claimed.
 *
 * The step per bar is the symbol's own `ATR14` — the book's own yardstick for "how much price
 * normally moves" `[Ch.22 §22.26]` — so the path scales with volatility and with the stock
 * instead of being an invented percentage.
 */
object ProjectionBuilder {

    /** plan.md §6.6 item 2: "the next 1–5 bars". */
    const val MIN_HORIZON_BARS: Int = 1
    const val MAX_HORIZON_BARS: Int = 5

    /**
     * Builds the dashed path.
     *
     * @param horizonBars clamped into `[1, 5]`.
     * @param stepPerBar the per-bar move; defaults to `ATR14` at the last bar, falling back to the
     *   last bar's own range and then to a small fraction of the close when both are zero.
     * @param atrSeries pre-computed `ATR14` so the caller does not recompute it per match.
     */
    fun directionalPath(
        candles: List<Candle>,
        direction: Direction,
        horizonBars: Int = MAX_HORIZON_BARS,
        stepPerBar: Double? = null,
        timeframe: Timeframe = candles.firstOrNull()?.timeframe ?: Timeframe.D1,
        atrSeries: List<Double?>? = null,
    ): ProjectedPath {
        val bars = horizonBars.coerceIn(MIN_HORIZON_BARS, MAX_HORIZON_BARS)
        val last = candles.lastOrNull()
            ?: return ProjectedPath(emptyList(), bars, ProjectionLabels.PATH_LABEL)
        val step = stepPerBar ?: defaultStep(candles, last, atrSeries)
        val points = (1..bars).map { k ->
            val offset = when (direction) {
                Direction.UP -> k * step
                Direction.DOWN -> -k * step
            }
            last.date.plusDays(calendarStep(timeframe).toLong() * k) to (last.close + offset)
        }
        return ProjectedPath(points, bars, ProjectionLabels.PATH_LABEL)
    }

    /** `ATR14` at the last bar, else the last bar's range, else `0.1 %` of the close. */
    private fun defaultStep(
        candles: List<Candle>,
        last: Candle,
        atrSeries: List<Double?>?,
    ): Double {
        val fromSeries = atrSeries?.getOrNull(candles.lastIndex)
        val atr = fromSeries?.takeIf { it.isFinite() && it > 0.0 }
        if (atr != null) return atr
        if (atrSeries == null) {
            val computed = atr(candles).getOrNull(candles.lastIndex)
            if (computed != null && computed.isFinite() && computed > 0.0) return computed
        }
        val range = last.high - last.low
        if (range > 0.0) return range
        return last.close * 0.001
    }

    private fun calendarStep(timeframe: Timeframe): Int = when (timeframe) {
        Timeframe.D1 -> 1
        Timeframe.W1 -> 7
    }
}

/** The live state of a pattern's breakout condition (plan.md §6.6 item 3). */
data class BreakoutState(
    /** `true` when the **latest close** satisfies the condition. */
    val fired: Boolean,
    val direction: Direction,
    val level: Double,
    /** Plain-language description of the condition, e.g. "Close below the neckline". */
    val description: String,
    /** Bars elapsed since the first close that satisfied it; `-1` when it never has. */
    val barsSinceFired: Int,
)

/**
 * The breakout alert condition (plan.md §6.6 item 3) — an **event**, never merged into the
 * projection.
 *
 * **A close-based trigger is mandatory.** `patterns-spec.md` §3.7: "Professional analysts place
 * much greater importance on **closing prices** than on temporary intraday movements"
 * `[Ch.18-5 §Breakout vs Intraday Spike]`. An intraday `high` crossing the neckline is not a
 * breakout, which is why nothing here reads `high`/`low`; a formation that only poked through
 * carries the `INTRADAY_SPIKE_ONLY` penalty instead.
 *
 * The [BreakoutEvaluator.bufferPct] is `patterns-spec.md` §11.2's `BREAKOUT_BUFFER_PCT` — a close
 * must be *decisively* beyond the level ("price trades at ₹810 is not a breakout, price closes at
 * ₹835 is" `[Ch.18-5 §Breakout vs Intraday Spike]`).
 */
object BreakoutEvaluator {

    /** `patterns-spec.md` §11.2: 0.50 % daily / 0.75 % weekly, `[derived]`. */
    fun bufferPct(timeframe: Timeframe): Double = when (timeframe) {
        Timeframe.D1 -> 0.50
        Timeframe.W1 -> 0.75
    }

    /** `true` when [close] clears [level] decisively in [direction]. */
    fun isBreakoutClose(
        close: Double,
        level: Double,
        direction: Direction,
        bufferPct: Double,
    ): Boolean {
        if (!close.isFinite() || !level.isFinite()) return false
        val needed = when (direction) {
            Direction.UP -> level * (1.0 + bufferPct / 100.0)
            Direction.DOWN -> level * (1.0 - bufferPct / 100.0)
        }
        return when (direction) {
            Direction.UP -> close > needed
            Direction.DOWN -> close < needed
        }
    }

    /**
     * The first bar at or after [fromBar] whose close clears the level, or `null`.
     *
     * `patterns-spec.md` §3.7 `isBreakout`: "for the **first** `j >= from`" — the trigger is the
     * moment the condition became true, not any later re-touch.
     */
    fun firstBreakoutBar(
        candles: List<Candle>,
        fromBar: Int,
        level: Double,
        direction: Direction,
        bufferPct: Double,
    ): Int? {
        for (j in fromBar.coerceAtLeast(0) until candles.size) {
            if (isBreakoutClose(candles[j].close, level, direction, bufferPct)) return j
        }
        return null
    }

    /**
     * Evaluates the condition against the **last** candle.
     *
     * @param subject what the level is, for the description: `"neckline"`, `"confirmation level"`,
     *   `"resistance"`, `"channel boundary"`.
     */
    fun evaluate(
        candles: List<Candle>,
        level: Double,
        direction: Direction,
        subject: String,
        timeframe: Timeframe = candles.firstOrNull()?.timeframe ?: Timeframe.D1,
        bufferPct: Double = bufferPct(timeframe),
    ): BreakoutState {
        val description = ProjectionLabels.breakoutDescription(subject, direction)
        if (candles.isEmpty()) {
            return BreakoutState(false, direction, level, description, -1)
        }
        val lastIndex = candles.lastIndex
        val firedNow = isBreakoutClose(candles[lastIndex].close, level, direction, bufferPct)
        val firstBar = firstBreakoutBar(candles, 0, level, direction, bufferPct)
        val barsSince = if (firstBar == null) -1 else lastIndex - firstBar
        return BreakoutState(
            fired = firedNow,
            direction = direction,
            level = level,
            description = description,
            barsSinceFired = barsSince,
        )
    }

    /** The [BreakoutTrigger] form, so a `Match`'s alert can be rendered or scheduled directly. */
    fun trigger(
        candles: List<Candle>,
        level: Double,
        direction: Direction,
        subject: String,
        timeframe: Timeframe = candles.firstOrNull()?.timeframe ?: Timeframe.D1,
        bufferPct: Double = bufferPct(timeframe),
    ): BreakoutTrigger {
        val state = evaluate(candles, level, direction, subject, timeframe, bufferPct)
        return BreakoutTrigger(
            description = state.description,
            level = state.level,
            direction = state.direction,
            fired = state.fired,
        )
    }
}