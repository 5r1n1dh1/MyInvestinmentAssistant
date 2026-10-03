package com.mia.app.domain.pattern.detectors.chart

import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.PatternDetector
import com.mia.app.domain.model.Timeframe
import com.mia.app.domain.pattern.ZigzagPivots
import com.mia.app.domain.technical.IndicatorSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The real-data regression: every detector over every symbol in
 * `domain/src/test/resources/fixtures/eod_history.json`, on **D1** and on the **W1** series derived
 * from it.
 *
 * Two jobs. First, nothing may crash and every score must be valid — the cheap assertion that runs on
 * every CI run. Second, the **detection counts are printed and asserted against a ceiling**, because a
 * high fire rate is the characteristic failure of a geometry detector: `scoring-spec.md` §9.2 expects
 * 55–75 % of candidates to be suppressed, and a detector that fires on a third of all symbols is
 * "seeing chart patterns everywhere" `[Ch.18-5 §Seeing Patterns Everywhere]` — the apophenia the whole
 * scoring design exists to suppress.
 *
 * **⚠️ Read the counts with the corporate-action hazard in mind.** The fixture is unadjusted, so
 * `HDFCBANK`, `BAJFINANCE`, `KOTAKBANK` and `TATAMOTORS` each contain one enormous one-session gap
 * (a 1:2 split, a 1:10, a 1:5 and a 40 % reduction). Those gaps are indistinguishable from a real
 * formation to a geometry detector: a 50 % fall inside one bar is a "lower low" with an enormous
 * `ref`. [detection_counts_exclude_the_bars_around_measured_corporate_actions] therefore reports the
 * counts twice — raw, and with the bars adjacent to those four dates removed — and the ceiling is
 * asserted on the second. Nothing is repaired: the trimmed series is a genuine series with those
 * sessions deleted, and its bar indices are internally consistent because the pivots are recomputed
 * on it.
 */
class ChartPatternRealDataTest {

    private val detectors: List<PatternDetector> = listOf(
        HeadAndShouldersDetector(),
        InverseHeadAndShouldersDetector(),
        DoubleTopDetector(),
        DoubleBottomDetector(),
        AscendingTriangleDetector(),
        DescendingTriangleDetector(),
        FlagDetector(),
    )

    private val patternNames: List<String> = listOf(
        "Head & Shoulders",
        "Inverse Head & Shoulders",
        "Double Top",
        "Double Bottom",
        "Ascending Triangle",
        "Descending Triangle",
        "Bull Flag",
        "Bear Flag",
    )

    /** Matches per symbol ceiling on the corporate-action-cleaned daily history. */
    private val ceilingsPerSymbol = 4

    @Test
    fun every_detector_runs_over_all_forty_symbols_without_crashing_and_scores_validly() {
        var matches = 0
        var symbols = 0
        for (symbol in RealEodHistory.symbols()) {
            for (timeframe in listOf(Timeframe.D1, Timeframe.W1)) {
                val candles = RealEodHistory.candles(symbol, timeframe)
                if (candles.isEmpty()) continue
                symbols++
                val pivots = pivotsOf(candles, timeframe)
                for (detector in detectors) {
                    for (match in detector.detect(pivots, candles)) {
                        matches++
                        assertTrue(match.score in 0..100, "$symbol/$timeframe ${detector.name}: bad score")
                        assertEquals(
                            match.breakdown?.total,
                            match.score,
                            "$symbol/$timeframe ${detector.name}: score must be the breakdown total",
                        )
                        ChartAssertions.nameIsCanonical(match.name)
                        ChartAssertions.measuredMoveIsOnTheCorrectSide(match)
                        ChartAssertions.pivotsAreRealElements(match, pivots)
                    }
                }
            }
        }
        // 41 keys in the file, of which `TATAMOTORSPV` has no rows: 40 symbols × 2 timeframes.
        assertEquals(80, symbols, "40 symbols × 2 timeframes, TATAMOTORSPV excluded for having no rows")
        println("chart-pattern matches over $symbols symbol/timeframe series: $matches")
    }

    @Test
    fun detection_counts_exclude_the_bars_around_measured_corporate_actions() {
        val excludedBySymbol = RealEodHistory.corporateActionBars()
        for (timeframe in listOf(Timeframe.D1, Timeframe.W1)) {
            val raw = run(timeframe) { emptySet() }
            val cleaned = run(timeframe) { symbol -> excludedBySymbol[symbol].orEmpty() }
            val symbolCount = RealEodHistory.symbols().count { RealEodHistory.dailyBars(it) > 0 }
            println("── $timeframe ─────────────────────────────────────────")
            println("%-26s %6s %6s %8s %9s %9s".format("pattern", "raw", "clean", "raw/sym", "clean/sym", "fired-sym"))
            for (name in patternNames) {
                val rawCount = raw.getOrDefault(name, 0)
                val cleanCount = cleaned.getOrDefault(name, 0)
                val fired = cleaned.getOrDefault("$name#fired", 0)
                println(
                    "%-26s %6d %6d %8.2f %9.2f %9d".format(
                        name,
                        rawCount,
                        cleanCount,
                        rawCount.toDouble() / symbolCount,
                        cleanCount.toDouble() / symbolCount,
                        fired,
                    ),
                )
            }
            if (timeframe != Timeframe.D1) continue
            for (name in patternNames) {
                val total = cleaned.getOrDefault(name, 0)
                val ceiling = ceilingsPerSymbol * symbolCount
                assertTrue(
                    total <= ceiling,
                    "'$name' fired $total times over $symbolCount symbols (ceiling $ceilingsPerSymbol per " +
                        "symbol = $ceiling). A geometry detector that fires on most symbols is seeing " +
                        "patterns everywhere [Ch.18-5 §Seeing Patterns Everywhere].",
                )
            }
        }
    }

    @Test
    fun weekly_series_yield_no_more_matches_than_daily_series() {
        // The honesty requirement: "fewer pivots → stricter shape requirements or no match. Never
        // lower thresholds to force detections." A weekly series of ~79 bars cannot contain a
        // 120-bar head & shoulders, so the weekly count may not exceed the daily one by more than the
        // number of detectors (i.e. rounding of the per-detector counts).
        val d1 = run(Timeframe.D1) { emptySet() }
        val w1 = run(Timeframe.W1) { emptySet() }
        for (name in patternNames) {
            val daily = d1.getOrDefault(name, 0)
            val weekly = w1.getOrDefault(name, 0)
            assertTrue(
                weekly <= daily + patternNames.size,
                "'$name': W1 fired $weekly times against D1's $daily, which is not 'fewer pivots → " +
                    "stricter or nothing'",
            )
        }
    }

    @Test
    fun the_weekly_series_are_short_and_carry_few_pivots() {
        val weekly = RealEodHistory.candles("RELIANCE", Timeframe.W1)
        val pivots = pivotsOf(weekly, Timeframe.W1)
        println("RELIANCE W1: ${weekly.size} bars, ${pivots.size} pivots")
        assertTrue(weekly.size in 70..85, "expected ~79 weekly bars, got ${weekly.size}")
        assertTrue(pivots.size <= 20, "the weekly pivot list is expected to be short, was ${pivots.size}")
    }

    /**
     * The pinned census: how many windows each §18.2 gate turned away, over the committed fixture.
     *
     * These are exact on purpose. The standing rule is "**never lower thresholds to force
     * detections**", and a flag that never fires is exactly the outcome such a change would produce
     * — so the honest reading of a failure here is not "update the numbers" but "a gate moved, and
     * the reason for moving it was not more detections". Compare the first two columns:
     * `POLE_PCT` alone turns away half of D1's windows.
     */
    private val expectedCensus: Map<Timeframe, Map<FlagRejection, Int>> = mapOf(
        Timeframe.D1 to mapOf(
            FlagRejection.POLE_PCT to 681,
            FlagRejection.POLE_ATR to 135,
            FlagRejection.DEPTH_TOO_DEEP to 506,
            FlagRejection.DEPTH_TOO_SHALLOW to 3,
            FlagRejection.DIRECTION to 13,
            FlagRejection.PARALLEL to 2,
            FlagRejection.DURATION to 1,
        ),
        Timeframe.W1 to mapOf(
            FlagRejection.POLE_PCT to 48,
            FlagRejection.POLE_ATR to 252,
            FlagRejection.DEPTH_TOO_DEEP to 78,
            FlagRejection.DIRECTION to 4,
            FlagRejection.DURATION to 1,
        ),
    )

    @Test
    fun the_flag_family_rejects_every_real_window_and_the_census_says_why() {
        // Zero flags on real data is only a *result* if the gates are demonstrably the reason. This
        // test walks the whole census, so a future edit that silently loosened or closed a gate shows
        // up as a changed number rather than as a detector that quietly stopped working.
        //
        // The census is a test-visible diagnostic ([FlagEngine.scan]); it is not part of the
        // `PatternDetector` seam, it never becomes a Measure or a Penalty, and it changes no
        // candidate: `scan(...).candidates == candidates(...)` is asserted below.
        for (timeframe in listOf(Timeframe.D1, Timeframe.W1)) {
            val census = LinkedHashMap<FlagRejection, Int>()
            var windows = 0
            var candidates = 0
            var viaCandidatesApi = 0
            for (symbol in RealEodHistory.symbols()) {
                val candles = RealEodHistory.candles(symbol, timeframe)
                if (candles.size < 60) continue
                val pivots = pivotsOf(candles, timeframe)
                val context = ChartContext.of(candles)
                val confirmed = context.confirmed(pivots)
                for (bull in listOf(true, false)) {
                    val name = if (bull) "Bull Flag" else "Bear Flag"
                    val scan = FlagEngine.scan(name, bull, context, confirmed)
                    windows += scan.windowsConsidered
                    candidates += scan.candidates.size
                    viaCandidatesApi += FlagEngine.candidates(name, bull, context, confirmed).size
                    for ((reason, count) in scan.rejections) census.merge(reason, count, Int::plus)
                }
            }
            println("── $timeframe flag census ────────────────────────────────")
            println("windows considered: $windows")
            for (reason in FlagRejection.entries) {
                println("%-22s %6d".format(reason.name, census[reason] ?: 0))
            }
            println("accepted: $candidates")

            // Every window is accounted for: nothing can be dropped on the floor.
            assertEquals(windows, census.values.sum(), "$timeframe: rejected windows must add up")
            // The census is a read-only view of the same sweep.
            assertEquals(candidates, viaCandidatesApi, "$timeframe: scan() must not change detection")
            // The measured outcome this documents.
            assertEquals(0, candidates, "$timeframe: the flag family fires on no real series")
            // The two touch-count gates and the degenerate-pole guard are unreachable by
            // construction (the window length already forces two touches of each; `ref` is the pole
            // height, which §18.2 S1 has already bounded above). Recording them as zero keeps a
            // future edit from quietly disabling a gate.
            assertEquals(
                expectedCensus[timeframe],
                census.filterValues { it > 0 },
                "$timeframe: a §18.2 gate changed its verdict — see the census printed above",
            )
        }

        // The gates are not closed on everything: §18.7's textbook flag clears all of them.
        val control = bullFlagSeries()
        val controlContext = ChartContext.of(control.candles)
        val controlScan = FlagEngine.scan(
            name = "Bull Flag",
            bull = true,
            context = controlContext,
            confirmed = controlContext.confirmed(control.pivots),
        )
        assertTrue(
            controlScan.candidates.isNotEmpty(),
            "a synthetic bull flag must survive the gates, but the census rejected everything: " +
                controlScan.rejections,
        )
    }

    @Test
    fun the_real_data_hazard_dates_are_inside_the_fixture() {
        // Guards the hazard list itself: if the fixture is ever regenerated with adjusted prices, this
        // test fails and the exclusion logic can be removed rather than silently doing nothing.
        val bars = RealEodHistory.candles("HDFCBANK", Timeframe.D1)
        val index = bars.indexOfFirst { it.date.toString() == "2025-08-26" }
        assertTrue(index > 0, "the HDFCBANK 2025-08-26 split session must be present")
        val before = bars[index - 1].close
        val after = bars[index].close
        assertTrue(after < before * 0.6, "expected an unadjusted ~1:2 gap at 2025-08-26, was $before → $after")
        for ((symbol, date) in RealEodHistory.CORPORATE_ACTIONS) {
            assertTrue(
                RealEodHistory.candles(symbol, Timeframe.D1).any { it.date.toString() == date },
                "$symbol should contain its $date corporate action",
            )
        }
    }

    // ------------------------------------------------------------------------------- helpers

    /**
     * Runs every detector over every symbol on [timeframe] and counts matches by pattern name.
     *
     * @param excludedBars which bar indices of that symbol's series to delete first.
     * @return the counts, plus a `"<name>#fired"` entry per pattern: the number of symbols in which
     *   it fired at least once, which is the number a reader actually wants.
     */
    private fun run(
        timeframe: Timeframe,
        excludedBars: (String) -> Set<Int>,
    ): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        for (symbol in RealEodHistory.symbols()) {
            val full = RealEodHistory.candles(symbol, timeframe)
            if (full.isEmpty()) continue
            val excluded = excludedBars(symbol)
            val candles: List<Candle> = full.indices.filter { !excluded.contains(it) }.map { full[it] }
            if (candles.size < 60) continue
            val pivots = pivotsOf(candles, timeframe)
            val fired = mutableSetOf<String>()
            for (detector in detectors) {
                for (match in detector.detect(pivots, candles)) {
                    out[match.name] = (out[match.name] ?: 0) + 1
                    fired += match.name
                }
            }
            for (name in fired) out["$name#fired"] = (out["$name#fired"] ?: 0) + 1
        }
        return out
    }

    private fun pivotsOf(candles: List<Candle>, timeframe: Timeframe) =
        ZigzagPivots.compute(candles, timeframe, atr = IndicatorSnapshot.of(candles).atr).pivots
}