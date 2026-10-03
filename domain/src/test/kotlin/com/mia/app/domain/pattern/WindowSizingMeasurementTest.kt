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
import com.mia.app.domain.technical.CandleResampler
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * **Phase 0.5 measurement harness** — this is a *reporting* test, not a gate.
 *
 * ### Why this exists
 * The Pattern Explorer is meant to let a reader pick a window and see scores for every pattern in
 * that window. Two numbers were needed to design it, and neither could be chosen by taste:
 *
 * 1. **How small can the window be?** Every detector declares its own `minBars`, but
 *    `MatchScorePolicy.minBars` (60 for D1, 40 for W1) may or may not dominate them.
 * 2. **How large is it useful to go?** Formation-span caps (`hsMaxSpanBars`, `dtMaxSepBars`,
 *    `flagMaxBars`) mean bars beyond some point cannot carry an *additional* live formation — so a
 *    250-bar chip might cost analysis and buy nothing.
 *
 * It also settles four questions that changed the design, recorded in the commit for this file:
 *
 * - `ZigzagPivots.MIN_PIVOTS = 8` is **not** a hard floor. `ZigzagPivots.compute` runs a shallower
 *   fallback pass and keeps whichever found more pivots, flagging it as `usedFallbackDepth`. So a
 *   short window yields *shallower pivots*, not `CANNOT_EVALUATE`. Any design that assumed a pivot
 *   floor would have been wrong.
 * - `PatternScanOptions` has **no** flag suppressing outcome recording, so the Explorer must add
 *   one before a window scan can be run per chip tap.
 * - `FactorAssessment` and `ScoreApplication` keep factor *scores* and *penalties* but discard the
 *   `Measure` objects, so showing a near-miss's weakest-factor measures requires `Match` to carry
 *   them.
 * - Volatility and climax inputs (`VOLATILE_MEDIAN_BARS`, `CLIMAX_DECILE`) clamp their lookback
 *   rather than requiring it, so short windows degrade instead of failing.
 *
 * ### How to read the output
 * Run this in CI or Google AI Studio — `:domain` is the only module that can be run, and the
 * numbers it prints are the input to the chip values. It asserts only invariants that are true by
 * construction; **every quantity that needed measuring is printed, not asserted**, because a
 * threshold this file invented would be exactly the guess this file exists to remove.
 *
 * ### Why the fixture
 * `eod_history.json` is 20 real NSE large caps × 372 real sessions — the same data
 * [PatternDiscriminationGateTest] uses, so the two measurements agree on their inputs.
 */
class WindowSizingMeasurementTest {

    /** The twelve v1 detectors, in plan.md §6.5 order. Identical to the gate test's list. */
    private val detectors: List<PatternDetector> = listOf(
        BullishEngulfingDetector(),
        BearishEngulfingDetector(),
        HammerDetector(),
        ShootingStarDetector(),
        StarDetector(),
        HeadAndShouldersDetector(),
        InverseHeadAndShouldersDetector(),
        DoubleTopDetector(),
        DoubleBottomDetector(),
        AscendingTriangleDetector(),
        DescendingTriangleDetector(),
        FlagDetector(),
    )

    /**
     * Windows to measure, deliberately including sizes **below** the D1 gate.
     *
     * The sub-gate sizes are not there to be offered — they are there to prove they produce
     * `insufficientHistory`, which is the evidence for not offering them.
     */
    private val windows = listOf(30, 40, 60, 90, 120, 160, 200, 250, 372)

    private data class Cell(
        val window: Int,
        val barsFed: Int,
        val pivots: Int,
        val usedFallback: Boolean,
        val kept: Int,
        val suppressed: Int,
        val names: Set<String>,
        val unscoredNotes: Int,
        val gated: Boolean,
    )

    private fun measure(
        symbol: String,
        timeframe: Timeframe,
        window: Int,
    ): Cell {
        val daily = EodHistory.candles(symbol)
        val series = if (timeframe == Timeframe.W1) {
            CandleResampler.resample(daily, Timeframe.W1)
        } else {
            daily
        }
        val slice = series.takeLast(window)
        val result = PatternEngine.run(
            candles = slice,
            timeframe = timeframe,
            detectors = detectors,
            options = PatternScanOptions(symbol = symbol),
        )
        return Cell(
            window = window,
            barsFed = slice.size,
            pivots = result.pivots.size,
            usedFallback = result.pivots.usedFallbackDepth,
            kept = result.matches.size,
            suppressed = result.suppressed.size,
            names = (result.matches.map { it.name } + result.suppressed.map { it.name }).toSet(),
            unscoredNotes = result.notes.count { it.contains("unscored", ignoreCase = true) },
            gated = result.insufficientHistory,
        )
    }

    private fun report(timeframe: Timeframe, symbols: List<String>) {
        val gate = MatchScorePolicy.minBars(timeframe)
        val rows = windows.map { w -> w to symbols.map { measure(it, timeframe, w) } }

        println("")
        println("=== $timeframe  (gate ${gate} bars, ${symbols.size} symbols) ===")
        println(
            "%-7s %-8s %-8s %-9s %-6s %-10s %-9s %-7s %s".format(
                "window", "barsFed", "pivots", "fallback", "kept", "suppressed",
                "namesSeen", "unscored", "gated",
            ),
        )
        for ((w, cells) in rows) {
            val med = cells.map { it.pivots }.sorted()[cells.size / 2]
            val fallback = cells.count { it.usedFallback }
            val kept = cells.sumOf { it.kept }
            val supp = cells.sumOf { it.suppressed }
            val names = cells.flatMap { it.names }.toSet().size
            val uns = cells.count { it.unscoredNotes > 0 }
            val gated = cells.count { it.gated }
            println(
                "%-7d %-8d %-8d %-9s %-6d %-10d %-9d %-7d %d/%d".format(
                    w, cells.first().barsFed, med,
                    "$fallback/${cells.size}", kept, supp, names, uns, gated, cells.size,
                ),
            )
        }

        // The two derived questions, printed rather than asserted.
        val firstUseful = rows.firstOrNull { (_, cells) -> cells.any { it.kept > 0 } }?.first
        println("  smallest window producing a kept match: ${firstUseful ?: "none measured"}")
        val allNames = rows.flatMap { (_, cells) -> cells.flatMap { c -> c.names } }.toSet()
        println("  distinct pattern names seen across all windows: ${allNames.size} of 14")
        println("  names: ${allNames.sorted().joinToString(", ")}")
    }

    @Test
    fun `measure daily window sizing over real data`() {
        val symbols = EodHistory.fullHistorySymbols(count = 20)
        assertTrue(symbols.isNotEmpty(), "the fixture must have full-history symbols")
        report(Timeframe.D1, symbols)
    }

    @Test
    fun `measure weekly window sizing over real data`() {
        val symbols = EodHistory.fullHistorySymbols(count = 20)
        assertTrue(symbols.isNotEmpty(), "the fixture must have full-history symbols")
        report(Timeframe.W1, symbols)
    }

    /**
     * Invariants that hold by construction, asserted so a broken harness fails loudly instead of
     * printing a confident wrong table.
     */
    @Test
    fun `every measurement is internally consistent`() {
        for (timeframe in Timeframe.entries) {
            val gate = MatchScorePolicy.minBars(timeframe)
            for (window in windows) {
                for (symbol in EodHistory.fullHistorySymbols(count = 3)) {
                    val cell = measure(symbol, timeframe, window)
                    assertTrue(
                        cell.barsFed <= window,
                        "$symbol/$timeframe fed ${cell.barsFed} bars for a $window window",
                    )
                    assertTrue(
                        cell.pivots <= cell.barsFed,
                        "pivots cannot exceed bars: ${cell.pivots} > ${cell.barsFed}",
                    )
                    if (cell.barsFed < gate) {
                        assertTrue(
                            cell.gated,
                            "$symbol/$timeframe ${cell.barsFed} bars is under the $gate gate " +
                                "but was not marked insufficientHistory",
                        )
                        assertTrue(
                            cell.kept == 0,
                            "$symbol/$timeframe emitted ${cell.kept} matches below the $gate gate",
                        )
                    }
                }
            }
        }
    }

    /**
     * Determinism: the same slice scanned twice must be identical, or the Explorer cannot present a
     * score the reader can trust to be reproducible (`scoring-spec.md` §9.1).
     */
    @Test
    fun `a window scan is reproducible`() {
        val slice = EodHistory.candles("RELIANCE").takeLast(120)
        val options = PatternScanOptions(symbol = "RELIANCE")
        val first = PatternEngine.run(slice, Timeframe.D1, detectors, options)
        val second = PatternEngine.run(slice, Timeframe.D1, detectors, options)
        assertTrue(
            first.contentEquals(second),
            "two scans of the same slice disagreed, so a displayed score is not reproducible",
        )
    }

    /**
     * Records what the design depends on, so a later change to a constant cannot silently move it.
     *
     * These are the limits Phase 0.5 was run to discover. They are asserted as *documentation* —
     * if one changes, the window design must be revisited, which is precisely when a test should
     * shout about it.
     */
    @Test
    fun `the window design assumptions are still true`() {
        // Every detector's own floor sits below the *smaller* of the two gates, so the gate — not
        // the detector — is always what starves a short window. Compared against W1's 40 because
        // that is the stricter of the two; if any detector ever rose above it, the chip design
        // would have to be revisited and this should shout.
        val tightestGate = minOf(
            MatchScorePolicy.minBars(Timeframe.D1),
            MatchScorePolicy.minBars(Timeframe.W1),
        )
        for (detector in detectors) {
            assertTrue(
                detector.minBars <= tightestGate,
                "${detector.name} needs ${detector.minBars} bars, at or above the " +
                    "$tightestGate-bar weekly gate — the gate no longer dominates every detector",
            )
        }
        // The gate values the chip set is built around (plan.md §6.4).
        assertTrue(MatchScorePolicy.minBars(Timeframe.D1) == 60, "the D1 gate moved")
        assertTrue(MatchScorePolicy.minBars(Timeframe.W1) == 40, "the W1 gate moved")
    }
}