package com.mia.app.domain.pattern

import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.Direction
import com.mia.app.domain.model.PatternDetector
import com.mia.app.domain.model.Timeframe
import com.mia.app.domain.pattern.detectors.chart.AscendingTriangleDetector
import com.mia.app.domain.pattern.detectors.chart.DescendingTriangleDetector
import com.mia.app.domain.pattern.detectors.chart.DoubleBottomDetector
import com.mia.app.domain.pattern.detectors.chart.DoubleTopDetector
import com.mia.app.domain.pattern.detectors.chart.FlagDetector
import com.mia.app.domain.pattern.detectors.chart.HeadAndShouldersDetector
import com.mia.app.domain.pattern.detectors.chart.InverseHeadAndShouldersDetector
import com.mia.app.domain.pattern.detectors.chart.RisingWedgeDetector
import kotlin.math.abs
import kotlin.test.Test

/**
 * **Phase 1 measurement: is a "hit" a trade, or a survivor?**
 *
 * ### Why this is the question now
 * Phase 0 measured that a projected target within 3 % is touched 83 % of the time inside five bars,
 * against a 10 % base rate. That looks like a strong result and it is not yet one.
 *
 * `OutcomeTrackerPolicy.isHit` asks a single question: did price reach the level, in either
 * direction, within the horizon. It does not ask **when**. Price can travel 5 % the wrong way, then
 * reverse and touch the target — and that is recorded identically to a target reached on the first
 * bar. If most of the 83 % were reached after a large adverse move, then the honest reading is
 * "the pattern eventually goes somewhere", which is much weaker than "you could have held this".
 *
 * plan.md §6.1 already says a hit is a chart event and never a profit. This file is the measurement
 * that makes that sentence mean something.
 *
 * ### What it reports
 * 1. **Maximum adverse excursion before the touch**, tight targets against loose ones. If MAE is
 *    large the hit was luck after a drawdown.
 * 2. **A stop sweep.** The projected target against stops of 0.5–5 %, reporting the win rate and
 *    the average outcome *in percent of the entry price* — the only form of that number that is
 *    comparable across candidates. A stop is a **trading parameter the reader chooses**, not a
 *    detector threshold, so sweeping it is legitimate; what is not legitimate is picking one and
 *    quoting it without the curve.
 *
 * Every number is printed. Nothing is asserted, because a threshold chosen here would be the guess
 * this file exists to replace.
 */
class TouchProfitabilityMeasurement {

    private companion object {
        /** The app's own `OutcomeTrackerPolicy.DEFAULT_HORIZON_BARS`. */
        const val HORIZON: Int = 5

        /** Equal to [HORIZON], so no two outcomes share a bar. */
        const val STRIDE: Int = 5

        /** Stop levels swept. Trading parameters the reader chooses, not detector thresholds. */
        val STOPS: List<Double> = listOf(0.5, 1.0, 2.0, 3.0, 5.0)
    }

    private val detectors: List<PatternDetector> = listOf(
        HeadAndShouldersDetector(),
        InverseHeadAndShouldersDetector(),
        DoubleTopDetector(),
        DoubleBottomDetector(),
        AscendingTriangleDetector(),
        DescendingTriangleDetector(),
        FlagDetector(),
        RisingWedgeDetector(),
    )

    /** One candidate's full forward path, so the same walk answers both questions. */
    private class Trade(
        val name: String,
        val score: Int,
        val direction: Direction,
        val entry: Double,
        val target: Double,
        /** Percent from [entry] to [target]. Positive. */
        val targetDistPct: Double,
        /** Bars until the target was touched, or `null` if it never was. */
        val barsToTouch: Int?,
        /** Worst excursion against the trade before the touch, in percent. `0.0` when never touched. */
        val maeBeforeTouchPct: Double,
        /** Worst excursion within the whole horizon, touch or not. */
        val maeWithinHorizonPct: Double,
        /** The bars after the entry, for the stop sweep. */
        val forward: List<Candle>,
    )

    private fun build(symbol: String, i: Int, candles: List<Candle>): List<Trade> {
        val result = PatternEngine.run(
            candles = candles.subList(0, i + 1),
            timeframe = Timeframe.D1,
            detectors = detectors,
            options = PatternScanOptions(symbol = symbol, isCanonicalScan = false),
        )
        val entry = candles[i].close
        val forward = candles.subList(i + 1, minOf(candles.size, i + 1 + HORIZON))
        if (forward.size < HORIZON) return emptyList()

        return result.matches.mapNotNull { match ->
            val move = match.measuredMove ?: return@mapNotNull null
            if (!(move.height > 0.0) || !(move.triggerLevel > 0.0)) return@mapNotNull null
            val up = move.direction == Direction.UP
            val target = if (up) move.projectedLevel else move.projectedLevel
            if (!(target > 0.0)) return@mapNotNull null

            val targetDist = abs(target - entry) / entry * 100.0

            var barsToTouch: Int? = null
            var maeBeforeTouch = 0.0
            var maeWithin = 0.0
            forward.forEachIndexed { k, bar ->
                // Adverse excursion for this bar's extreme.
                val adverse = if (up) {
                    (entry - bar.low) / entry * 100.0
                } else {
                    (bar.high - entry) / entry * 100.0
                }
                val worst = maxOf(adverse, 0.0)
                maeWithin = maxOf(maeWithin, worst)
                val touchedNow = if (up) bar.high >= target else bar.low <= target
                if (touchedNow && barsToTouch == null) {
                    barsToTouch = k + 1
                    maeBeforeTouch = maeWithin
                }
            }

            Trade(
                name = match.name,
                score = match.score,
                direction = move.direction,
                entry = entry,
                target = target,
                targetDistPct = targetDist,
                barsToTouch = barsToTouch,
                maeBeforeTouchPct = maeBeforeTouch,
                maeWithinHorizonPct = maeWithin,
                forward = forward,
            )
        }
    }

    /**
     * The stop sweep.
     *
     * [expectancy] is `sumPct / candidates`, so an **unresolved trade contributes 0**. That is the
     * only honest reading of a 5-bar hold: dropping opens from the denominator makes a wide stop
     * look like a 96 % win rate purely because most trades had not resolved yet.
     *
     * The payoff is asymmetric on purpose — a win pays the projected target, a loss pays the stop —
     * so expectancy is a function of both the win rate and how far away the target was, and it is
     * only comparable between rows that share a filter.
     */
    private class SweepResult(
        val wins: Int,
        val losses: Int,
        val open: Int,
        val sumPct: Double,
        val winRate: Double,
    )

    private fun sweep(trades: List<Trade>, stopPct: Double): SweepResult {
        var wins = 0
        var losses = 0
        var open = 0
        var sumPct = 0.0
        for (t in trades) {
            when (outcome(t, stopPct)) {
                "win" -> { wins++; sumPct += t.targetDistPct }
                "loss" -> { losses++; sumPct -= stopPct }
                else -> open++
            }
        }
        return SweepResult(wins, losses, open, sumPct, wins * 100.0 / trades.size)
    }

    /** Stop sweep: target as projected, stop at [stopPct], first to win within [HORIZON]. */
    private fun outcome(trade: Trade, stopPct: Double): String {
        val up = trade.direction == Direction.UP
        val stop = if (up) trade.entry * (1 - stopPct / 100.0)
        else trade.entry * (1 + stopPct / 100.0)
        trade.forward.forEach { bar ->
            val stopped = if (up) bar.low <= stop else bar.high >= stop
            val touched = if (up) bar.high >= trade.target else bar.low <= trade.target
            // Stop first if both happened in the same bar: pessimistic, because a reader could not
            // know which came first inside a daily bar. That assumption has to be stated rather than
            // chosen silently.
            return when {
                stopped -> "loss"
                touched -> "win"
                else -> "open"
            }
        }
        return "open"
    }

    @Test
    fun `a hit is a survivor or a trade`() {
        val trades = ArrayList<Trade>()
        for (symbol in EodHistory.SYMBOLS) {
            val candles = EodHistory.candles(symbol)
            if (candles.size < 250) continue
            var i = 60
            while (i + HORIZON < candles.size) {
                trades += build(symbol, i, candles)
                i += STRIDE
            }
        }

        val touched = trades.filter { it.barsToTouch != null }
        println("=== IS A HIT A TRADE? ===")
        println("trades=${trades.size}  touched=${touched.size}")

        println()
        println("--- bars until the target was touched (touched only) ---")
        val bars = touched.mapNotNull { it.barsToTouch }.sorted()
        if (bars.isNotEmpty()) {
            val median = bars[bars.size / 2]
            println("  median $median bar(s);  within 1 bar ${bars.count { it == 1 }} (${"%.0f".format(bars.count { it == 1 } * 100.0 / bars.size)}%)")
            for (k in 1..HORIZON) {
                val n = bars.count { it == k }
                println("  bar $k: $n (${"%.0f".format(n * 100.0 / bars.size)}%)")
            }
        }

        println()
        println("--- worst excursion against the trade BEFORE the target was touched ---")
        for ((label, lo, hi) in listOf(
            Triple("target <=2%", 0.0, 2.0),
            Triple("target 2-3%", 2.0, 3.0),
            Triple("target 3-5%", 3.0, 5.0),
            Triple("target >5%", 5.0, 999.0),
        )) {
            val group = touched.filter { it.targetDistPct in lo..hi }
            if (group.isEmpty()) continue
            val mae = group.map { it.maeBeforeTouchPct }.sorted()
            val clean1 = group.count { it.maeBeforeTouchPct < 1.0 }
            val clean2 = group.count { it.maeBeforeTouchPct < 2.0 }
            println(
                "  ${label.padEnd(13)} n=${group.size.toString().padStart(4)}  " +
                    "median MAE ${"%.1f".format(mae[mae.size / 2])}%  " +
                    "MAE<1% ${"%.0f".format(clean1 * 100.0 / group.size)}%  " +
                    "MAE<2% ${"%.0f".format(clean2 * 100.0 / group.size)}%",
            )
        }

        println()
        println("--- stop sweep, target as projected, $HORIZON-bar hold ---")
        println("An unresolved trade counts as ZERO, not as a win. Excluding it would turn a")
        println("wide stop into an apparent 96% win rate when the truth is that most trades were")
        println("still open at the end of the window.")
        println("stop".padEnd(8) + "wins".padStart(6) + "losses".padStart(7) + "open".padStart(6) +
            "".padStart(9) + "win%all".padStart(9) + "expectancy".padStart(12))
        for (stop in STOPS) {
            val r = sweep(trades, stop)
            val expectancy = if (trades.isEmpty()) 0.0 else r.sumPct / trades.size
            println(
                "${"%.1f%%".format(stop).padEnd(8)}" +
                    r.wins.toString().padStart(6) + r.losses.toString().padStart(7) +
                    r.open.toString().padStart(6) +
                    (" ${"%.0f%%".format(r.winRate)}").padStart(9) +
                    (" ${"%.2f".format(expectancy)}%").padStart(12),
            )
        }

        println()
        println("--- same sweep, but ONLY targets within 3% ---")
        val tight = trades.filter { it.targetDistPct <= 3.0 }
        val tightDistances = tight.map { it.targetDistPct }.sorted()
        println(
            "  n=${tight.size} (${"%.0f".format(tight.size * 100.0 / trades.size)}% of trades), " +
                "median target ${"%.1f".format(tightDistances[tightDistances.size / 2])}%",
        )
        for (stop in listOf(0.5, 1.0, 2.0, 3.0)) {
            val r = sweep(tight, stop)
            val expectancy = if (tight.isEmpty()) 0.0 else r.sumPct / tight.size
            println(
                "  stop " + ("%.1f%%".format(stop)).padEnd(7) +
                    r.wins.toString().padStart(4) + "W " +
                    r.losses.toString().padStart(4) + "L " +
                    r.open.toString().padStart(4) + "O  " +
                    ("win% " + "%.1f".format(r.winRate)).padStart(14) +
                    ("  expectancy " + "%.2f".format(expectancy) + "%").padStart(20),
            )
        }

        println()
        println("--- base rate: a random ${"%.1f".format(tight.map { it.targetDistPct }.sorted()[tight.size / 2])}% target with no pattern ---")
        val d = tight.map { it.targetDistPct }.sorted()[tight.size / 2]
        var brWin = 0
        var brLoss = 0
        var brOpen = 0
        for (symbol in EodHistory.SYMBOLS) {
            val candles = EodHistory.candles(symbol)
            if (candles.size < 250) continue
            var i = 60
            while (i + HORIZON < candles.size) {
                val up = (i + symbol.length) % 2 == 0
                val entry = candles[i].close
                val target = entry * (1 + d / 100.0 * if (up) 1.0 else -1.0)
                val stop = entry * (1 - d / 100.0 * if (up) 0.5 else -0.5)
                var done = false
                candles.subList(i + 1, i + 1 + HORIZON).forEach { bar ->
                    if (done) return@forEach
                    val stopped = if (up) bar.low <= stop else bar.high >= stop
                    val touched = if (up) bar.high >= target else bar.low <= target
                    when {
                        stopped -> { brLoss++; done = true }
                        touched -> { brWin++; done = true }
                    }
                }
                if (!done) brOpen++
                i += STRIDE
            }
        }
        println("  random direction, same target size, half-size stop: " +
            "${brWin}W ${brLoss}L ${brOpen}O  win% " +
            "${"%.1f".format(if (brWin + brLoss > 0) brWin * 100.0 / (brWin + brLoss) else 0.0)}")
    }

}
