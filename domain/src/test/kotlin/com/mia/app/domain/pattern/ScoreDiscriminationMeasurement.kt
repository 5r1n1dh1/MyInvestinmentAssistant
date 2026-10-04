package com.mia.app.domain.pattern

import com.mia.app.domain.model.MatchScorePolicy
import com.mia.app.domain.model.PatternDetector
import com.mia.app.domain.model.Timeframe
import com.mia.app.domain.pattern.detectors.candlestick.BearishEngulfingDetector
import com.mia.app.domain.pattern.detectors.candlestick.BullishEngulfingDetector
import com.mia.app.domain.pattern.detectors.candlestick.HammerDetector
import com.mia.app.domain.pattern.detectors.candlestick.ShootingStarDetector
import com.mia.app.domain.pattern.detectors.candlestick.StarDetector
import com.mia.app.domain.pattern.detectors.chart.AscendingTriangleDetector
import com.mia.app.domain.pattern.detectors.chart.DescendingTriangleDetector
import com.mia.app.domain.pattern.detectors.chart.DoubleBottomDetector
import com.mia.app.domain.pattern.detectors.chart.DoubleTopDetector
import com.mia.app.domain.pattern.detectors.chart.FlagDetector
import com.mia.app.domain.pattern.detectors.chart.HeadAndShouldersDetector
import com.mia.app.domain.pattern.detectors.chart.InverseHeadAndShouldersDetector
import com.mia.app.domain.pattern.detectors.chart.RisingWedgeDetector
import kotlin.test.Test

/**
 * **Phase 0 measurement: does the match score discriminate at all?**
 *
 * ### Why this is measured before anything else
 * `MatchScorePolicy.PROJECT_THRESHOLD = 70` gates alerts, and `RENDER_THRESHOLD = 50` gates display.
 * Those numbers are used to decide what a reader sees, so the score is already making a claim: a 72
 * should behave differently from a 52.
 *
 * Nothing in the codebase has ever checked that. If the score is decorative, then every search for
 * "edge" through a score threshold is filtering on noise, and that has to be known before another
 * detector is written rather than after.
 *
 * ### How it is measured
 * Walk-forward over the real fixture, 5-bar stride so no two outcomes share a bar, all nine
 * evaluated detectors. For every candidate that is complete (has a measured move) and has a full
 * horizon, the hit is recomputed at each horizon in [HORIZONS] rather than only the app's 5.
 *
 * Two numbers matter and they must be read together:
 *
 * - **hit rate by score bucket** — does it rise with the score?
 * - **the base rate at that horizon** — how often a level is touched at all, from a projection drawn
 *   with no pattern involved. This rises steeply with the horizon, because a level 5 % away is
 *   reached by almost anything given 20 sessions. A 90 % "hit rate" at 20 bars is not a signal.
 *
 * ### Reporting, not gating
 * Every quantity is printed. A threshold invented here would be exactly the guess this file exists
 * to remove, so it asserts only that the sweep ran.
 */
class ScoreDiscriminationMeasurement {

    private val detectors: List<PatternDetector> = listOf(
        HeadAndShouldersDetector(),
        InverseHeadAndShouldersDetector(),
        DoubleTopDetector(),
        DoubleBottomDetector(),
        AscendingTriangleDetector(),
        DescendingTriangleDetector(),
        FlagDetector(),
        RisingWedgeDetector(),
        // Candlestick is still registered and still scored, even though the catalogue no longer
        // claims to evaluate it. Included here deliberately: excluding the patterns the app does
        // not market would bias the score distribution, since a three-bar shape and a double top do
        // not reach the same scores for the same reason of quality.
        BullishEngulfingDetector(),
        BearishEngulfingDetector(),
        HammerDetector(),
        ShootingStarDetector(),
        StarDetector(),
    )

    private data class Sample(
        val name: String,
        val score: Int,
        /** Percent from the trigger level to the projected level, at each horizon. */
        val distanceAt: Map<Int, Double>,
        val hitAt: Map<Int, Boolean>,
    )

    @Test
    fun `measure hit rate by score bucket and horizon`() {
        val samples = ArrayList<Sample>()
        var forming = 0

        for (symbol in EodHistory.SYMBOLS) {
            val candles = EodHistory.candles(symbol)
            if (candles.size < 250) continue
            var i = 60
            while (i + MAX_HORIZON < candles.size) {
                val result = PatternEngine.run(
                    candles = candles.subList(0, i + 1),
                    timeframe = Timeframe.D1,
                    detectors = detectors,
                    options = PatternScanOptions(symbol = symbol, isCanonicalScan = false),
                )
                for (match in result.matches) {
                    val move = match.measuredMove
                    if (move == null || !(move.height > 0.0) || !(move.triggerLevel > 0.0)) {
                        forming++
                        continue
                    }
                    val future = candles.subList(i + 1, candles.size)
                    if (future.size < MAX_HORIZON) {
                        forming++
                        continue
                    }
                    // `isHit` is the app's own rule, asked once per horizon. Recomputing the
                    // projection per horizon would be a different experiment.
                    samples += Sample(
                        name = match.name,
                        score = match.score,
                        distanceAt = HORIZONS.associateWith { h ->
                            // How far the projected level sits from the breakout close, in percent,
                            // always positive. That is the distance a reader would have to be right
                            // about, so it is the one the geometry control is bucketed on.
                            val up = move.direction == com.mia.app.domain.model.Direction.UP
                            val delta = if (up) {
                                move.projectedLevel - candles[i].close
                            } else {
                                candles[i].close - move.projectedLevel
                            }
                            val base = if (up) candles[i].close else move.projectedLevel
                            if (base > 0.0) kotlin.math.abs(delta) / base * 100.0 else 0.0
                        },
                        hitAt = HORIZONS.associateWith { h ->
                            val outcome = com.mia.app.domain.accuracy.OutcomeTracker.record(
                                match, symbol, candles[i].date, h,
                            )
                            com.mia.app.domain.model.OutcomeTrackerPolicy.isHit(
                                outcome, future.subList(0, h), h,
                            )
                        },
                    )
                }
                i += STRIDE
            }
        }

        println("=== SCORE DISCRIMINATION ===")
        println("samples=${samples.size}  forming/incomplete=$forming")
        println("score range ${samples.minOf { it.score }}..${samples.maxOf { it.score }}")
        println("distinct patterns: ${samples.map { it.name }.distinct().sorted()}")

        println()
        println("--- hit rate by score bucket ---")
        println("bucket".padEnd(12) + HORIZONS.joinToString("") { "h$it".padStart(9) } + "   n")
        for ((label, lo, hi) in BUCKETS) {
            val bucket = samples.filter { it.score in lo..hi }
            if (bucket.isEmpty()) {
                println(label.padEnd(12) + "(none)".padStart(9))
                continue
            }
            val cells = HORIZONS.joinToString("") { h ->
                val hits = bucket.count { it.hitAt.getValue(h) }
                val pct = hits * 100.0 / bucket.size
                " ${"%.0f%%".format(pct)}".padStart(9)
            }
            println(label.padEnd(12) + cells + "   ${bucket.size}")
        }

        println()
        println("--- overall, and the no-pattern base rate at each horizon ---")
        val allCells = HORIZONS.joinToString("") { h ->
            val pct = samples.count { it.hitAt.getValue(h) } * 100.0 / samples.size
            " ${"%.0f%%".format(pct)}".padStart(9)
        }
        println("all".padEnd(12) + allCells + "   ${samples.size}")
        println("base rate".padEnd(12) + HORIZONS.joinToString("") { h ->
            " ${"%.0f%%".format(baseRatePct(h))}".padStart(9)
        })

        println()
        println("--- per pattern at the app's own ${MatchScorePolicy.RENDER_THRESHOLD}/${OutcomeHorizon}-bar horizon ---")
        samples.groupBy { it.name }.entries.sortedByDescending { it.value.size }.forEach { (name, group) ->
            val hits = group.count { it.hitAt.getValue(OutcomeHorizon) }
            println(
                "  ${name.padEnd(22)} $hits/${group.size} = " +
                    "${"%.1f".format(hits * 100.0 / group.size)}%  " +
                    "median score ${group.map { it.score }.sorted()[group.size / 2]}",
            )
        }

        println()
        println("--- is the score a proxy for how far away the target is? ---")
        // The likely explanation for the inversion above, and the difference between "the score is
        // noise" and "the score measures the wrong thing". A more textbook-ideal formation is a
        // *bigger* formation, which projects a *further* level, which is harder to reach. If the
        // mean distance rises with the score, the score is not measuring quality at the outcome —
        // it is measuring how much the reader would have to want the trade.
        println("bucket".padEnd(12) + "median target dist%".padStart(20) + "median score".padStart(14))
        for ((label, lo, hi) in BUCKETS) {
            val bucket = samples.filter { it.score in lo..hi }
            if (bucket.isEmpty()) continue
            val d = bucket.map { sample -> sample.distanceAt.getValue(5) }.sorted()
            val sc = bucket.map { it.score }.sorted()
            println(
                label.padEnd(12) +
                    " ${"%.1f".format(d[d.size / 2])}".padStart(19) +
                    " ${sc[sc.size / 2]}".padStart(13),
            )
        }

        println()
        println("--- hit rate by target distance at h5 (the geometry control) ---")
        samples.groupBy { it.distanceAt.getValue(5).toInt().coerceIn(1, 30) }
            .filterKeys { it in 1..12 }
            .toSortedMap()
            .forEach { (bucketPct, group) ->
                val hits = group.count { it.hitAt.getValue(5) }
                println(
                    "  ${bucketPct.toString().padStart(2)}% away  $hits/${group.size} = " +
                        "${"%.0f".format(hits * 100.0 / group.size)}%",
                )
            }

        println()
        println("--- the filter the score does NOT apply: target distance alone ---")
        for (cut in listOf(2.0, 3.0, 4.0, 5.0)) {
            val group = samples.filter { it.distanceAt.getValue(5) <= cut }
            if (group.isEmpty()) continue
            val cells = HORIZONS.joinToString("") { h ->
                " ${"%.0f%%".format(group.count { it.hitAt.getValue(h) } * 100.0 / group.size)}".padStart(8)
            }
            val share = group.size * 100.0 / samples.size
            println(
                "  target within ${cut}%: ${cells}   n=${group.size} (${"%.0f".format(share)}% " +
                    "of samples)  h5=${"%.0f".format(group.count { it.hitAt.getValue(5) } * 100.0 / group.size)}%",
            )
        }
        println("  (base rate at the same horizons, no pattern: 1% 3% 5% 10% 22% 38%)")

        println()
        println("--- PROJECT_THRESHOLD reachability ---")
        println("  PROJECT_THRESHOLD = ${MatchScorePolicy.PROJECT_THRESHOLD}")
        println("  highest score ever seen: ${samples.maxOf { it.score }}")
        println("  samples at or above it: ${samples.count { it.score >= MatchScorePolicy.PROJECT_THRESHOLD }}")
        println("  => the project-and-alert path is ${if (samples.none { it.score >= MatchScorePolicy.PROJECT_THRESHOLD }) "UNREACHABLE" else "reachable"}")

        println()
        println("--- score distribution ---")
        for ((label, lo, hi) in BUCKETS) {
            val n = samples.count { it.score in lo..hi }
            println("  ${label.padEnd(12)} $n")
        }
    }

    /**
     * How often a level 5 % from the close is touched, from a projection drawn with no pattern.
     *
     * Computed from the fixture's own daily ranges rather than assumed, because this is the number
     * that decides whether any measured hit rate means anything.
     */
    private fun baseRatePct(horizon: Int): Double {
        var touched = 0
        var total = 0
        for (symbol in EodHistory.SYMBOLS) {
            val candles = EodHistory.candles(symbol)
            if (candles.size < 250) continue
            var i = 60
            while (i + MAX_HORIZON < candles.size) {
                val close = candles[i].close
                val future = candles.subList(i + 1, i + 1 + horizon)
                // A level 5 % away, the median projected distance the app actually produces.
                for (direction in listOf(1.0, -1.0)) {
                    val level = close * (1.0 + 0.05 * direction)
                    total++
                    if (future.any { c -> if (direction > 0) c.high >= level else c.low <= level }) {
                        touched++
                    }
                }
                i += STRIDE
            }
        }
        return touched * 100.0 / total
    }

    private companion object {
        /** The app's own `OutcomeTrackerPolicy.DEFAULT_HORIZON_BARS`. */
        const val OutcomeHorizon: Int = 5
        const val MAX_HORIZON: Int = 20
        const val STRIDE: Int = 5

        /** The app's four chips, plus 20 to see where the curve stops meaning anything. */
        val HORIZONS: List<Int> = listOf(1, 2, 3, 5, 10, 20)

        /** Score buckets spanning the gate, the render threshold and the project threshold. */
        val BUCKETS: List<Triple<String, Int, Int>> = listOf(
            Triple("below 50", 0, 49),
            Triple("50-59", 50, 59),
            Triple("60-69", 60, 69),
            Triple("70-79", 70, 79),
            Triple("80-100", 80, 100),
        )
    }
}
