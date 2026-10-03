package com.mia.app.domain.pattern

import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.Category
import com.mia.app.domain.model.Direction
import com.mia.app.domain.model.Match
import com.mia.app.domain.model.MatchScorePolicy
import com.mia.app.domain.model.Measure
import com.mia.app.domain.model.MeasuredMove
import com.mia.app.domain.model.Pivot
import com.mia.app.domain.model.PatternDetector
import com.mia.app.domain.model.PivotKind
import com.mia.app.domain.model.ScoreBreakdown
import com.mia.app.domain.model.Source
import com.mia.app.domain.model.Timeframe
import com.mia.app.domain.technical.IndicatorSnapshot
import com.mia.app.domain.technical.TrendRegime
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * plan.md §6.4 gating, `docs/CONTRACTS.md`'s direction-aware regime rule, ranking, and the
 * "computed once" performance requirement.
 */
class PatternEngineTest {

    // ------------------------------------------------------------------ not enough history

    @Test
    fun `dailyHistoryBelowSixtyBarsSuppressesEverythingWithAReason`() {
        val candles = rising(59)
        val result = PatternEngine.run(candles, Timeframe.D1, listOf(detectorWith(75)))

        assertTrue(result.insufficientHistory)
        assertEquals(MatchScorePolicy.Verdict.NOT_ENOUGH_HISTORY, result.verdict)
        assertEquals(60, result.minBarsRequired)
        assertEquals(59, result.barCount)
        assertTrue(result.matches.isEmpty(), "no score may be emitted without enough history")
        assertEquals(1, result.suppressed.size)
        val reason = result.suppressed.single().reason
        assertTrue(reason.contains("Not enough history"), reason)
        assertTrue(reason.contains("60 daily bars"), reason)
        assertTrue(result.notes.any { it.startsWith("Not enough history") }, result.notes.toString())
    }

    @Test
    fun `weeklyHistoryBelowFortyBarsSuppressesEverythingWithAReason`() {
        val candles = rising(39, Timeframe.W1)
        val result = PatternEngine.run(candles, Timeframe.W1, listOf(detectorWith(75)))

        assertTrue(result.insufficientHistory)
        assertEquals(40, result.minBarsRequired)
        assertEquals(MatchScorePolicy.Verdict.NOT_ENOUGH_HISTORY, result.verdict)
        assertTrue(result.suppressed.single().reason.contains("40 weekly bars"), result.suppressed.single().reason)
    }

    @Test
    fun `exactlySixtyDailyBarsIsSufficient`() {
        val result = PatternEngine.run(
            rising(60),
            Timeframe.D1,
            listOf(detectorWith(75)),
            PatternScanOptions(regimeOverride = TrendRegime.TRENDING_DOWN),
        )
        assertTrue(!result.insufficientHistory)
        assertEquals(1, result.matches.size)
    }

    // ----------------------------------------------------------------------------- scoring gate

    @Test
    fun `scoreSeventyFiveWithAnAlignedRegimeProjects`() {
        val result = PatternEngine.run(
            rising(500),
            Timeframe.D1,
            listOf(detectorWith(75, direction = Direction.DOWN)),
            PatternScanOptions(regimeOverride = TrendRegime.TRENDING_DOWN),
        )
        assertEquals(MatchScorePolicy.Verdict.PROJECT, result.verdict)
        assertEquals(1, result.matches.size)
        assertTrue(result.notes.any { it.contains("trend regime aligned") }, result.notes.toString())
    }

    @Test
    fun `scoreSeventyFiveWithAnUnalignedRegimeOnlyRenders`() {
        val result = PatternEngine.run(
            rising(500),
            Timeframe.D1,
            listOf(detectorWith(75, direction = Direction.DOWN)),
            PatternScanOptions(regimeOverride = TrendRegime.TRENDING_UP),
        )
        assertEquals(MatchScorePolicy.Verdict.RENDER, result.verdict)
        assertEquals(1, result.matches.size, "an unaligned regime must not hide the match, only its projection")
        assertTrue(result.notes.any { it.contains("trend regime is not aligned") }, result.notes.toString())
    }

    @Test
    fun `scoreSixtyRendersAndScoreFortyIsSuppressedWithItsReason`() {
        val render = PatternEngine.run(
            rising(500),
            Timeframe.D1,
            listOf(detectorWith(60, direction = Direction.DOWN)),
            PatternScanOptions(regimeOverride = TrendRegime.TRENDING_DOWN),
        )
        assertEquals(MatchScorePolicy.Verdict.RENDER, render.verdict)
        assertEquals(1, render.matches.size)
        assertTrue(render.suppressed.isEmpty())

        val suppress = PatternEngine.run(
            rising(500),
            Timeframe.D1,
            listOf(detectorWith(40, direction = Direction.DOWN)),
            PatternScanOptions(regimeOverride = TrendRegime.TRENDING_DOWN),
        )
        assertEquals(MatchScorePolicy.Verdict.SUPPRESS, suppress.verdict)
        assertTrue(suppress.matches.isEmpty())
        val suppressed = suppress.suppressed.single()
        assertEquals(40, suppressed.score)
        assertTrue(suppressed.reason.contains("below the 50 render threshold"), suppressed.reason)
        assertTrue(suppressed.reason.contains("Geometric fit only"), suppressed.reason)
    }

    // --------------------------------------------------------------- direction-aware regime

    @Test
    fun `rangingCountsAsAlignedForAReversalButNotForAContinuation`() {
        for (category in listOf(Category.REVERSAL, Category.CANDLESTICK)) {
            assertEquals(
                true,
                PatternEngine.regimeAligned(TrendRegime.RANGING, Direction.DOWN, category),
                "$category at a range boundary is the case reversals exist for",
            )
        }
        assertEquals(
            true,
            PatternEngine.regimeAligned(TrendRegime.RANGING, Direction.UP, Category.REVERSAL),
        )
        assertEquals(
            false,
            PatternEngine.regimeAligned(TrendRegime.RANGING, Direction.UP, Category.CONTINUATION),
            "a continuation pattern in a range is the book: \"breakouts fail frequently\"",
        )
        assertEquals(
            false,
            PatternEngine.regimeAligned(TrendRegime.RANGING, Direction.DOWN, Category.CONTINUATION),
        )
    }

    @Test
    fun `theRegimeGateOnlyAlignsWithThePatternsOwnDirection`() {
        assertEquals(true, PatternEngine.regimeAligned(TrendRegime.TRENDING_UP, Direction.UP, Category.CONTINUATION))
        assertEquals(false, PatternEngine.regimeAligned(TrendRegime.TRENDING_UP, Direction.DOWN, Category.REVERSAL))
        assertEquals(true, PatternEngine.regimeAligned(TrendRegime.TRENDING_DOWN, Direction.DOWN, Category.REVERSAL))
        assertEquals(false, PatternEngine.regimeAligned(TrendRegime.TRENDING_DOWN, Direction.UP, Category.CONTINUATION))
    }

    @Test
    fun `anUnknownRegimeIsNeverAlignedSoAMatchCanNeverProject`() {
        for (category in Category.entries) {
            for (direction in listOf(Direction.UP, Direction.DOWN)) {
                assertEquals(
                    null,
                    PatternEngine.regimeAligned(TrendRegime.UNKNOWN, direction, category),
                    "an undecided market may render, never project",
                )
            }
        }
        // And an unknown direction likewise.
        assertEquals(null, PatternEngine.regimeAligned(TrendRegime.TRENDING_UP, null, Category.REVERSAL))
    }

    @Test
    fun `anUnknownRegimeDowngradesAHighScoreToRender`() {
        val result = PatternEngine.run(
            rising(500),
            Timeframe.D1,
            listOf(detectorWith(75, direction = Direction.DOWN)),
            PatternScanOptions(regimeOverride = TrendRegime.UNKNOWN),
        )
        assertEquals(MatchScorePolicy.Verdict.RENDER, result.verdict)
        assertTrue(result.notes.any { it.contains("undecided") }, result.notes.toString())
    }

    @Test
    fun `aMatchWithNoDirectionCanRenderButNeverProject`() {
        // No measured move and no pivots ⇒ the direction is genuinely unknown, and an unknown
        // direction may RENDER but never PROJECT.
        val undirected = match("undirected", 75, direction = null, withPivot = false)
        val result = PatternEngine.run(
            rising(500),
            Timeframe.D1,
            listOf(fixedDetector("probe", listOf(undirected))),
            PatternScanOptions(regimeOverride = TrendRegime.TRENDING_DOWN),
        )
        assertEquals(MatchScorePolicy.Verdict.RENDER, result.verdict)
    }

    // ------------------------------------------------------------------------------- ranking

    @Test
    fun `matchesAreRankedByScoreThenByMostRecentPivot`() {
        val older = match(name = "Older", score = 60, pivotDate = LocalDate.of(2026, 1, 1))
        val newer = match(name = "Newer", score = 60, pivotDate = LocalDate.of(2026, 2, 1))
        val best = match(name = "Best", score = 75, pivotDate = LocalDate.of(2026, 1, 15))

        val result = PatternEngine.run(
            rising(500),
            Timeframe.D1,
            listOf(fixedDetector("ranked", listOf(older, newer, best))),
            PatternScanOptions(regimeOverride = TrendRegime.TRENDING_DOWN),
        )
        assertEquals(listOf("Best", "Newer", "Older"), result.matches.map { it.name })
    }

    // -------------------------------------------------------------------------------- plumbing

    @Test
    fun `theIndicatorSnapshotIsComputedOnceForTheWholeScan`() {
        var builds = 0
        val options = PatternScanOptions(
            regimeOverride = TrendRegime.TRENDING_DOWN,
            snapshotFactory = { candles, benchmark ->
                builds++
                IndicatorSnapshot.of(candles, benchmark)
            },
        )
        val detectors = (1..12).map { fixedDetector("detector $it", listOf(match("m$it", 60))) }
        val result = PatternEngine.run(rising(500), Timeframe.D1, detectors, options)

        assertEquals(1, builds, "one snapshot for twelve detectors (plan.md §6.2)")
        assertEquals(12, result.matches.size)
    }

    @Test
    fun `thePivotSetIsComputedOnceAndHandedToEveryDetector`() {
        val seen = HashMap<String, List<Pivot>>()
        val detectors = (1..12).map { index ->
            object : PatternDetector {
                override val name: String = "detector $index"
                override val category: Category = Category.REVERSAL
                override val minBars: Int = 5
                override fun detect(pivots: List<Pivot>, candles: List<Candle>): List<Match> {
                    seen[name] = pivots
                    return emptyList()
                }
            }
        }
        PatternEngine.run(rising(500), Timeframe.D1, detectors)
        assertEquals(12, seen.size)
        val distinct = seen.values.map { it.map { pivot -> pivot.barIndex to pivot.kind } }.distinct()
        assertEquals(1, distinct.size, "all detectors must see the identical pivot list")
        assertTrue(distinct.first().isNotEmpty())
    }

    @Test
    fun `aDetectorWhoseOwnWindowIsUnmetIsSkippedWithANote`() {
        val result = PatternEngine.run(
            rising(200),
            Timeframe.D1,
            listOf(detectorWith(75, minBars = 400)),
            PatternScanOptions(regimeOverride = TrendRegime.TRENDING_DOWN),
        )
        assertTrue(result.matches.isEmpty())
        assertTrue(result.notes.any { it.contains("needs 400 bars") }, result.notes.toString())
    }

    @Test
    fun `diagnosticsRecordOneRowPerDetector`() {
        val result = PatternEngine.run(
            rising(500),
            Timeframe.D1,
            listOf(
                fixedDetector("a", listOf(match("m", 60))),
                fixedDetector("b", emptyList()),
            ),
            PatternScanOptions(collectDiagnostics = true, regimeOverride = TrendRegime.TRENDING_DOWN),
        )
        assertEquals(listOf("a", "b"), result.diagnostics.map { it.detector })
        assertEquals(1, result.diagnostics[0].matchCount)
        assertEquals(0, result.diagnostics[1].matchCount)
        assertTrue(result.diagnostics.all { it.nanos >= 0 })
    }

    @Test
    fun `twoRunsOfTheSameInputProduceIdenticalContent`() {
        val candles = rising(500)
        val detectors = listOf(fixedDetector("a", listOf(match("m1", 75), match("m2", 40))))
        val options = PatternScanOptions(regimeOverride = TrendRegime.TRENDING_DOWN)
        val first = PatternEngine.run(candles, Timeframe.D1, detectors, options)
        val second = PatternEngine.run(candles, Timeframe.D1, detectors, options)
        assertTrue(first.contentEquals(second), "the pipeline must be pure (plan.md §4.3)")
        assertEquals(first.pivots.pivots, second.pivots.pivots)
    }

    @Test
    fun `notesDeclareWhatCouldNotBeScored`() {
        val result = PatternEngine.run(rising(500), Timeframe.D1, listOf(detectorWith(75)))
        assertTrue(result.notes.any { it.contains("Delivery % unavailable") }, result.notes.toString())
        assertTrue(result.notes.any { it.contains("No index benchmark") }, result.notes.toString())
    }

    @Test
    fun `aBenchmarkMakesTheRelativeStrengthNoteDisappear`() {
        val benchmark = rising(500, symbol = "NIFTY")
        val result = PatternEngine.run(
            rising(500),
            Timeframe.D1,
            listOf(detectorWith(75)),
            PatternScanOptions(benchmarkCandles = benchmark, regimeOverride = TrendRegime.TRENDING_DOWN),
        )
        assertTrue(result.notes.none { it.contains("No index benchmark") }, result.notes.toString())
    }

    @Test
    fun `anUnscoredFactorIsReportedForTheUi`() {
        // A detector that emitted geometry and structure only: confirmation and context are 0, and
        // the notes must say so rather than let the UI present them as real readings.
        val partial = object : PatternDetector {
            override val name: String = "Partial"
            override val category: Category = Category.REVERSAL
            override val minBars: Int = 5
            override fun detect(pivots: List<Pivot>, candles: List<Candle>): List<Match> =
                listOf(
                    matchOf(
                        detector = this,
                        candles = candles,
                        pivots = pivots,
                        measures = listOf(
                            Measure.linear("sym", 0.95, 20, ScoreFactor.GEOMETRY, 0.8, 1.0),
                            Measure.linear("residMax", 0.0, 25, ScoreFactor.STRUCTURE, 0.0, 0.15, invert = true),
                        ),
                    ),
                )
        }
        val result = PatternEngine.run(
            rising(500),
            Timeframe.D1,
            listOf(partial),
            PatternScanOptions(regimeOverride = TrendRegime.TRENDING_DOWN),
        )
        assertEquals(1, result.matches.size, "geometry + structure alone reaches 65")
        assertTrue(result.notes.any { it.contains("Partial") && it.contains("Confirmation unscored") }, result.notes.toString())
        assertTrue(result.notes.any { it.contains("Partial") && it.contains("Context unscored") }, result.notes.toString())
        assertTrue(result.notes.none { it.contains("Geometry unscored") }, result.notes.toString())
    }

    @Test
    fun `aMatchThatScoresBelowTheThresholdIsSuppressedRatherThanRendered`() {
        val geometryOnly = object : PatternDetector {
            override val name: String = "Geometry only"
            override val category: Category = Category.REVERSAL
            override val minBars: Int = 5
            override fun detect(pivots: List<Pivot>, candles: List<Candle>): List<Match> =
                listOf(
                    matchOf(
                        detector = this,
                        candles = candles,
                        pivots = pivots,
                        measures = listOf(Measure.linear("sym", 0.95, 20, ScoreFactor.GEOMETRY, 0.8, 1.0)),
                    ),
                )
        }
        val result = PatternEngine.run(
            rising(500),
            Timeframe.D1,
            listOf(geometryOnly),
            PatternScanOptions(regimeOverride = TrendRegime.TRENDING_DOWN),
        )
        assertTrue(result.matches.isEmpty())
        val suppressed = result.suppressed.single()
        assertEquals("Geometry only", suppressed.name)
        assertEquals(30, suppressed.score, "geometry 75 alone is 0.40 × 75")
        assertTrue(suppressed.reason.contains("below the 50 render threshold"), suppressed.reason)
    }

    @Test
    fun `theEngineRunsOverRealHistoryWithoutThrowing`() {
        val candles = EodHistory.candles("RELIANCE")
        val result = PatternEngine.run(candles, Timeframe.D1, listOf(fixedDetector("probe", emptyList())))
        assertEquals(372, result.barCount)
        assertTrue(!result.insufficientHistory)
        assertTrue(result.pivots.size >= 8, "real data must yield usable pivots: ${result.pivots.size}")
        assertEquals(MatchScorePolicy.Verdict.SUPPRESS, result.verdict)
    }

    // ----------------------------------------------------------------------------- helpers

    /**
     * Breakdowns chosen so `ScoreBreakdown.total` lands on the gate thresholds exactly:
     * `(100,100,50,0)` → 75, `(100,80,0,0)` → 60, `(100,0,0,0)` → 40.
     */
    private fun breakdownFor(score: Int): ScoreBreakdown = when (score) {
        75 -> ScoreBreakdown(100, 100, 50, 0)
        60 -> ScoreBreakdown(100, 80, 0, 0)
        40 -> ScoreBreakdown(100, 0, 0, 0)
        else -> error("add a breakdown for score $score")
    }

    private fun match(
        name: String,
        score: Int,
        direction: Direction? = Direction.DOWN,
        category: Category = Category.REVERSAL,
        pivotDate: LocalDate = LocalDate.of(2026, 1, 1),
        barIndex: Int = 100,
        kind: PivotKind = PivotKind.HIGH,
        withPivot: Boolean = true,
    ): Match {
        val measured = direction?.let {
            MeasuredMove(height = 50.0, triggerLevel = 900.0, direction = it, projectedLevel = 850.0)
        }
        return Match(
            patternId = PatternIds.of(name),
            name = name,
            score = score,
            pivots = if (withPivot) {
                listOf(Pivot(barIndex, pivotDate, 1_000.0, kind, 0))
            } else {
                emptyList()
            },
            measuredMove = measured,
            category = category,
            timeframe = Timeframe.D1,
            detectedOn = pivotDate,
            breakdown = breakdownFor(score),
        )
    }

    private fun fixedDetector(
        name: String,
        matches: List<Match>,
        minBars: Int = 5,
    ): PatternDetector =
        object : PatternDetector {
            override val name: String = name
            override val category: Category = Category.REVERSAL
            override val minBars: Int = minBars
            override fun detect(pivots: List<Pivot>, candles: List<Candle>): List<Match> = matches
        }

    private fun detectorWith(
        score: Int,
        direction: Direction? = Direction.DOWN,
        minBars: Int = 5,
    ): PatternDetector = fixedDetector("probe-$score", listOf(match("probe", score, direction)), minBars)

    private fun rising(
        count: Int,
        timeframe: Timeframe = Timeframe.D1,
        symbol: String = "TEST",
    ): List<Candle> = List(count) { i ->
        val close = 100.0 + i
        Candle(
            symbol = symbol,
            timeframe = timeframe,
            date = LocalDate.of(2025, 1, 1).plusDays(i.toLong()),
            open = close - 0.5,
            high = close + 0.5,
            low = close - 0.5,
            close = close,
            volume = 1_000_000L,
            source = Source.NSE_BHAVCOPY,
        )
    }
}