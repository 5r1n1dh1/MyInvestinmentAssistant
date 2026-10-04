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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Explorer's mapping, over the **real** fixture.
 *
 * ### The property that matters
 * A pattern score is only meaningful if it means exactly what the engine decided. This mapping
 * re-labels results, so the risk is not that it scores badly — it is that it silently disagrees
 * with the Patterns tab. Everything below is therefore about *fidelity* and *completeness*
 * rather than about which patterns happen to be found on this data.
 */
class PatternExplorerTest {

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

    // ------------------------------------------------------------- fidelity: nothing invented

    @Test
    fun `a matched outcome carries the engine's own score unchanged`() {
        // Which symbols keep a match at full depth is a property of the *data*, not of this mapping,
        // so the fixture is swept for one rather than a symbol being assumed to. Measured across
        // the 20-symbol fixture, kept-match counts at 372 bars range from 0 to 3, so hard-coding a
        // symbol here would have made this test fail on a data change for the wrong reason.
        var sourceResult: PatternScanResult? = null
        var found: PatternEvaluation? = null
        for (symbol in EodHistory.fullHistorySymbols(count = 20)) {
            val candles = EodHistory.candles(symbol)
            val result = PatternEngine.run(candles, Timeframe.D1, detectors, PatternScanOptions())
            val candidate =
                PatternExplorer.evaluate(result, symbol, Timeframe.D1, candles.size)
            if (candidate.matched().isNotEmpty()) {
                sourceResult = result
                found = candidate
                break
            }
        }
        val evaluation = assertNotNull(
            found,
            "no symbol in the fixture kept a single match at full depth, so there is nothing " +
                "to check fidelity against",
        )
        val result = assertNotNull(sourceResult, "the source result must be retained")

        val matched = evaluation.matched()
        assertEquals(
            result.matches.size,
            matched.size,
            "match count differs from the engine's",
        )

        // Compared against the result the evaluation was built FROM, deliberately not a fresh
        // scan: two runs over the same bars can rank equal-scoring candidates differently, because
        // PatternEngine breaks ties by latest pivot, so re-running would compare two different
        // scans and report a phantom disagreement.
        //
        // Matched as a multiset of scores, not name-by-name. One pattern name can appear more than
        // once in a scan, because a chart detector reports every qualifying formation in the
        // window rather than only the best. Measured on this fixture: TCS returns Double Top at 60
        // and 56, LT at 67 and 62, ASIANPAINT three times at 68/56/55. Pairing by name therefore
        // compared TCS's 60 against the outcome built from its 56.
        assertEquals(
            result.matches.map { it.score }.sortedDescending(),
            matched.map { it.score },
            "the matched scores differ from the engine's",
        )
        // And each outcome is one of the engine's own matches, unchanged.
        for (outcome in matched) {
            val sameName = result.matches.filter { it.name == outcome.name }
            assertTrue(
                sameName.any { it.score == outcome.score },
                "${outcome.name} scored ${outcome.score}, which is not among the engine's scores " +
                    "for it: ${sameName.map { it.score }}",
            )
            assertTrue(
                sameName.any { it == outcome.match },
                "${outcome.name}: the Match itself was altered",
            )
        }
    }

    @Test
    fun `a near-miss score is the engine's, and is genuinely below the gate`() {
        val candles = EodHistory.candles("RELIANCE")
        val result = PatternEngine.run(candles, Timeframe.D1, detectors, PatternScanOptions())
        val evaluation = PatternExplorer.evaluate(result, "RELIANCE", Timeframe.D1, candles.size)

        for (outcome in result.suppressed) {
            if (outcome.breakdown == null) continue
            val mapped = evaluation.outcomeFor(outcome.name) as? EvaluationOutcome.NearMiss
            assertNotNull(mapped, "${outcome.name} was suppressed but not mapped to a near-miss")
            assertEquals(outcome.score, mapped.score, "${outcome.name}: score was altered")
            assertTrue(
                mapped.score < MatchScorePolicy.SUPPRESS_THRESHOLD,
                "${outcome.name} scored ${mapped.score}, which is not below the gate",
            )
        }
    }

    @Test
    fun `every match and every suppressed candidate appears in the outcome list`() {
        // The failure this guards against is a pattern silently vanishing from the Explorer while
        // being present on the Patterns tab.
        val candles = EodHistory.candles("TCS")
        val result = PatternEngine.run(candles, Timeframe.D1, detectors, PatternScanOptions())
        val evaluation = PatternExplorer.evaluate(result, "TCS", Timeframe.D1, candles.size)

        val reported = evaluation.outcomes.map { it.name }.toSet()
        for (name in result.matches.map { it.name } + result.suppressed.map { it.name }) {
            assertTrue(name in reported, "$name is in the scan result but not in the outcomes")
        }
    }

    @Test
    fun `a suppressed candidate with no breakdown is never dressed up as a scored near-miss`() {
        // The bar gate suppresses candidates that were never scored. Reporting a fabricated score
        // for one would be worse than saying nothing.
        val candles = EodHistory.candles("TCS").takeLast(30)
        val result = PatternEngine.run(candles, Timeframe.D1, detectors, PatternScanOptions())
        assertTrue(result.insufficientHistory, "a 30-bar window should be under the gate")

        val evaluation = PatternExplorer.evaluate(result, "TCS", Timeframe.D1, 30)
        for (outcome in evaluation.outcomes) {
            if (outcome is EvaluationOutcome.NearMiss) {
                assertNotNull(
                    outcome.breakdown,
                    "${outcome.name} is a near-miss with no breakdown, so its score is fabricated",
                )
            }
        }
    }

    // ------------------------------------------------------- completeness: every row answered

    @Test
    fun `every catalogue pattern gets exactly one outcome`() {
        val candles = EodHistory.candles("HDFCBANK")
        val evaluation = PatternExplorer.scanWindow(
            candles = candles,
            timeframe = Timeframe.D1,
            detectors = detectors,
            symbol = "HDFCBANK",
        )

        val names = evaluation.outcomes.map { it.name }
        assertEquals(
            names.size,
            names.toSet().size,
            "a pattern appears more than once in the outcome list",
        )
        for (spec in PatternCatalog.all) {
            assertTrue(spec.name in names, "${spec.name} has no outcome at all")
        }
    }

    @Test
    fun `patterns the app does not evaluate are reported as not evaluated, not as absent`() {
        val evaluation = PatternExplorer.scanWindow(
            candles = EodHistory.candles("INFY"),
            timeframe = Timeframe.D1,
            detectors = detectors,
            symbol = "INFY",
        )

        for (spec in PatternCatalog.notEvaluated) {
            val outcome = evaluation.outcomeFor(spec.name)
            assertTrue(
                outcome is EvaluationOutcome.NotEvaluated,
                "${spec.name} is not evaluated by the app but reported as $outcome",
            )
        }
    }

    // --------------------------------------------------------- the barrier sentences help

    @Test
    fun `a blocked pattern names both the requirement and what was available`() {
        val evaluation = PatternExplorer.scanWindow(
            candles = EodHistory.candles("INFY").takeLast(40),
            timeframe = Timeframe.D1,
            detectors = detectors,
            symbol = "INFY",
            windowBars = 40,
        )

        val blocked = evaluation.outcomes.filterIsInstance<EvaluationOutcome.CannotEvaluate>()
        assertTrue(blocked.isNotEmpty(), "a 40-bar window should block the evaluated patterns")
        for (outcome in blocked) {
            assertTrue(
                outcome.reason.contains("40") && outcome.reason.contains("60"),
                "${outcome.name}'s blocker does not state both numbers: ${outcome.reason}",
            )
        }
    }

    @Test
    fun `the empty state sentence explains itself when nothing matched`() {
        // Measured: no symbol produced a kept match at 60 bars across 20 large caps except one, so
        // a short window is the realistic empty case and its sentence has to be useful.
        val evaluation = PatternExplorer.scanWindow(
            candles = EodHistory.candles("SBIN").takeLast(60),
            timeframe = Timeframe.D1,
            detectors = detectors,
            symbol = "SBIN",
            windowBars = 60,
        )
        if (evaluation.matched().isNotEmpty()) return

        val sentence = evaluation.emptyStateSentence()
        assertNotNull(sentence, "an empty result must still explain itself")
        assertTrue(
            sentence.contains("${MatchScorePolicy.RENDER_THRESHOLD}"),
            "the empty sentence must name the gate: $sentence",
        )
        assertTrue(
            sentence.contains("60-bar"),
            "the empty sentence must name the window: $sentence",
        )
    }

    // -------------------------------------------------------------- window honesty

    @Test
    fun `a window scan reports whether it fell back to shallower pivots`() {
        val candles = EodHistory.candles("ITC")
        val wide = PatternExplorer.scanWindow(
            candles = candles, timeframe = Timeframe.D1, detectors = detectors,
            symbol = "ITC", windowBars = candles.size,
        )
        // The whole series, with `windowBars` selecting the slice — the signature that feeds the
        // engine warm-up bars. Pre-slicing here would test the old, un-warmed behaviour.
        val narrow = PatternExplorer.scanWindow(
            candles = candles, timeframe = Timeframe.D1, detectors = detectors,
            symbol = "ITC", windowBars = 60,
        )

        // Measured over the 20-symbol fixture, warm-up included: 13 of 20 use the fallback at 60
        // bars, 5 of 20 at 120, and 0 at 372. The 60- and 120-bar figures were 17 and 13 before
        // `scanWindow` fed warm-up bars — the fallback is rarer now because the pivot pass sees
        // more context, which is the improvement this number exists to track. If it reaches 0 at
        // 60 the UI's warning has become false and should be removed rather than reworded.
        assertFalse(
            wide.usedFallbackPivots,
            "a whole-series window was measured as never needing the shallow fallback",
        )

        var fallbackAt60 = 0
        var symbols = 0
        for (symbol in EodHistory.fullHistorySymbols(count = 20)) {
            val series = EodHistory.candles(symbol)
            if (series.size < 60) continue
            symbols++
            if (PatternExplorer.scanWindow(
                    series, Timeframe.D1, detectors, symbol, windowBars = 60,
                ).usedFallbackPivots
            ) {
                fallbackAt60++
            }
        }
        println("warm-up: fallback at 60 bars on $fallbackAt60 of $symbols symbols (was 17 of 20)")
        assertTrue(
            fallbackAt60 in 1 until symbols,
            "a 60-bar window is still measured as needing the shallow fallback on some symbols; " +
                "if that ever reaches 0 the UI warning is false and must go, " +
                "if it reaches $symbols the warm-up is not reaching the pivot pass",
        )
    }

    @Test
    fun `a window scan is never canonical`() {
        // Guards the accuracy-table hazard: PatternIds is name-based, so a recorded window scan
        // would overwrite the canonical projection for the same pattern.
        val candles = EodHistory.candles("ITC")
        val result = PatternEngine.run(
            candles = candles.takeLast(120),
            timeframe = Timeframe.D1,
            detectors = detectors,
            options = PatternScanOptions(symbol = "ITC", isCanonicalScan = false),
        )
        // Same scan, and the flag must be metadata only — already asserted in
        // CanonicalScanGuardTest, so here just confirm the window path reports its own bar count.
        val evaluation = PatternExplorer.evaluate(result, "ITC", Timeframe.D1, 120)
        assertEquals(120, evaluation.windowBars)
    }

    @Test
    fun `the same window is reproducible through the explorer`() {
        val candles = EodHistory.candles("ITC").takeLast(120)
        val first = PatternExplorer.scanWindow(candles, Timeframe.D1, detectors, "ITC", 120)
        val second = PatternExplorer.scanWindow(candles, Timeframe.D1, detectors, "ITC", 120)
        assertEquals(
            first.outcomes.map { it.name to it::class.simpleName },
            second.outcomes.map { it.name to it::class.simpleName },
            "two identical window scans disagreed on which patterns were reported",
        )
        assertEquals(
            first.matched().map { it.score },
            second.matched().map { it.score },
            "two identical window scans disagreed on scores",
        )
    }

    // -------------------------------------------------------------- window warm-up

    @Test
    fun `a narrow window is analysed with warm-up bars before it, not from scratch`() {
        // The point of the warm-up is that indicators at the window's first bar have a real
        // reading. A window computed from exactly its own bars has none: the longest look-back any
        // detector touches is the 20-bar candlestick context, so the first ~20 bars of the window
        // would score on `null` indicators. That is a silent scoring penalty for choosing a narrow
        // window, which is the bug.
        val candles = EodHistory.candles("ITC")
        val windowBars = 120
        assertTrue(
            candles.size > windowBars + PatternExplorer.WARM_UP_BARS,
            "the fixture must be deep enough for this test to be meaningful",
        )

        // Fed to the engine with warm-up, versus the same window computed from scratch.
        val warmed = PatternExplorer.scanWindow(
            candles = candles, timeframe = Timeframe.D1, detectors = detectors,
            symbol = "ITC", windowBars = windowBars,
        )
        val coldResult = PatternEngine.run(
            candles = candles.takeLast(windowBars),
            timeframe = Timeframe.D1,
            detectors = detectors,
            options = PatternScanOptions(symbol = "ITC", isCanonicalScan = false),
        )
        val cold = PatternExplorer.evaluate(coldResult, "ITC", Timeframe.D1, windowBars)

        // The reported window is what the reader asked for, in both cases — the warm-up is never
        // counted as reported bars, or the window label would lie.
        assertEquals(windowBars, warmed.windowBars)
        assertEquals(windowBars, cold.windowBars)

        // The pivot pass sees more bars, so the shallow fallback is less likely — which is the
        // measurable consequence of the warm-up and the reason it exists.
        val symbols = EodHistory.fullHistorySymbols(count = 20)
        var warmedFallback = 0
        var coldFallback = 0
        for (symbol in symbols) {
            val series = EodHistory.candles(symbol)
            if (PatternExplorer.scanWindow(
                    series, Timeframe.D1, detectors, symbol, windowBars,
                ).usedFallbackPivots
            ) {
                warmedFallback++
            }
            val coldScan = PatternEngine.run(
                candles = series.takeLast(windowBars),
                timeframe = Timeframe.D1,
                detectors = detectors,
                options = PatternScanOptions(symbol = symbol, isCanonicalScan = false),
            )
            if (coldScan.pivots.usedFallbackDepth) coldFallback++
        }
        println(
            "warm-up census at $windowBars bars over ${symbols.size} symbols: " +
                "fallback used $warmedFallback warmed vs $coldFallback cold",
        )
        assertTrue(
            warmedFallback <= coldFallback,
            "warm-up bars can only add pivot context, so the shallow fallback cannot get more " +
                "likely: $warmedFallback warmed vs $coldFallback cold",
        )
    }

    @Test
    fun `reported candidates are the ones inside the window`() {
        // A candidate formed entirely in the warm-up region is not a finding about the window the
        // reader selected. Reported, it would also be drawn on a chart that is not showing those
        // bars.
        val candles = EodHistory.candles("ITC")
        val windowBars = 120
        val evaluation = PatternExplorer.scanWindow(
            candles = candles, timeframe = Timeframe.D1, detectors = detectors,
            symbol = "ITC", windowBars = windowBars,
        )
        // Re-derive the boundary the same way `scanWindow` does: fed = window + warm-up, so the
        // window starts at `fed.size - windowBars` in the engine's bar-index space.
        val windowStart = minOf(candles.size, windowBars + PatternExplorer.WARM_UP_BARS) - windowBars
        assertTrue(windowStart > 0, "this test needs warm-up bars to exist")

        val outside = evaluation.matched().filter { outcome ->
            outcome.match.pivots.isNotEmpty() && outcome.match.pivots.none { it.barIndex >= windowStart }
        }
        assertTrue(
            outside.isEmpty(),
            "matches entirely before the window leaked in: " +
                outside.joinToString { "${it.name} pivots=${it.match.pivots.map { p -> p.barIndex }}" },
        )
    }

    @Test
    fun `a whole-series window needs no warm-up and is unchanged by it`() {
        // The `All` chip is the control: with no bars before the window there is nothing to warm up,
        // so it must behave exactly like a canonical scan of the stored series.
        val candles = EodHistory.candles("ITC")
        val evaluation = PatternExplorer.scanWindow(
            candles = candles, timeframe = Timeframe.D1, detectors = detectors,
            symbol = "ITC", windowBars = candles.size,
        )
        assertEquals(candles.size, evaluation.windowBars)
        assertFalse(
            evaluation.usedFallbackPivots,
            "a whole-series scan was measured as never needing the fallback",
        )
    }

    @Test
    fun `a window scan cannot report more bars than are stored`() {
        val candles = EodHistory.candles("ITC")
        val evaluation = PatternExplorer.scanWindow(
            candles = candles, timeframe = Timeframe.D1, detectors = detectors,
            symbol = "ITC", windowBars = candles.size + 500,
        )
        assertEquals(
            candles.size,
            evaluation.windowBars,
            "a window wider than the stored series must be clamped, not invented",
        )
    }
}
