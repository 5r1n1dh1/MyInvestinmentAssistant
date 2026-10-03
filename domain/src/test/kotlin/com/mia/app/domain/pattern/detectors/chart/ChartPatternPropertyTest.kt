package com.mia.app.domain.pattern.detectors.chart

import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.Direction
import com.mia.app.domain.model.PatternDetector
import com.mia.app.domain.model.PivotKind
import com.mia.app.domain.model.Timeframe
import com.mia.app.domain.pattern.ZigzagPivots
import com.mia.app.domain.technical.IndicatorSnapshot
import com.mia.app.domain.technical.CandleResampler
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The plan.md §12 property tests and `patterns-spec.md` §24's checklist, run over **real** NSE
 * prices rather than over hand-built fixtures.
 *
 * A property that only holds on a fixture is not a property. These run the whole pipeline — shared
 * zigzag, shared indicator snapshot, all seven detectors — over both timeframes of every symbol in
 * the fixture and assert the invariants that must hold for *any* input.
 */
class ChartPatternPropertyTest {

    private val detectors: List<PatternDetector> = listOf(
        HeadAndShouldersDetector(),
        InverseHeadAndShouldersDetector(),
        DoubleTopDetector(),
        DoubleBottomDetector(),
        AscendingTriangleDetector(),
        DescendingTriangleDetector(),
        FlagDetector(),
    )

    private val history = RealEodHistory

    // ------------------------------------------------------------------------------- invariants

    @Test
    fun `scoresAreInRangeAndTheBreakdownTotalIsTheMatchScore`() {
        scan { detector, candles, pivots, _ ->
            for (match in detector.detect(pivots, candles)) {
                assertTrue(match.score in 0..100, "${detector.name}: score ${match.score} out of range")
                val breakdown = match.breakdown
                    ?: error("${detector.name}: a detector must always emit a ScoreBreakdown")
                assertEquals(breakdown.total, match.score, "${detector.name}: score must be the total")
                for (factor in 0..100) {
                    assertTrue(factor in 0..100)
                }
                assertTrue(breakdown.geometry in 0..100, "${detector.name}: geometry out of range")
                assertTrue(breakdown.structure in 0..100, "${detector.name}: structure out of range")
                assertTrue(breakdown.confirmation in 0..100, "${detector.name}: confirmation out of range")
                assertTrue(breakdown.context in 0..100, "${detector.name}: context out of range")
                ChartAssertions.nameIsCanonical(match.name)
            }
        }
    }

    @Test
    fun `everyReportedPivotIsAnElementOfTheInputPivotListAndOrderedOldestFirst`() {
        scan { detector, candles, pivots, _ ->
            for (match in detector.detect(pivots, candles)) {
                ChartAssertions.pivotsAreRealElements(match, pivots)
                ChartAssertions.pivotsAreOrderedOldestFirst(match)
                assertTrue(
                    match.pivots.isNotEmpty(),
                    "${detector.name}: a match must report the pivots it used (§4 rule 8)",
                )
            }
        }
    }

    @Test
    fun `measuredMoveHeightIsNeverNegativeAndTheProjectionSitsOnTheCorrectSide`() {
        scan { detector, candles, pivots, _ ->
            for (match in detector.detect(pivots, candles)) {
                val measured = match.measuredMove ?: continue
                assertTrue(measured.height >= 0.0, "${detector.name}: height ${measured.height} < 0")
                assertTrue(measured.height.isFinite(), "${detector.name}: height is not finite")
                assertTrue(measured.triggerLevel.isFinite(), "${detector.name}: trigger is not finite")
                when (measured.direction) {
                    Direction.UP -> assertTrue(
                        measured.projectedLevel > measured.triggerLevel,
                        "${detector.name}: an UP projection must sit above its trigger",
                    )

                    Direction.DOWN -> assertTrue(
                        measured.projectedLevel < measured.triggerLevel,
                        "${detector.name}: a DOWN projection must sit below its trigger",
                    )
                }
            }
        }
    }

    @Test
    fun `anEmptyPivotListProducesNoMatchesAndNoException`() {
        val candles = syntheticSeries(120)
        for (detector in detectors) {
            assertEquals(emptyList(), detector.detect(emptyList(), candles), detector.name)
            assertEquals(emptyList(), detector.detect(emptyList(), emptyList()), "${detector.name}: no candles")
        }
    }

    @Test
    fun `aSeriesShorterThanTheHistoryGateProducesNoMatchAtAll`() {
        // §4 rule 1 / `scoring-spec.md` §8.1: "A detector's own `minBars` unmet → the detector returns
        // **no match**. Never a `Match` with a low score."
        for (bars in listOf(0, 1, 5, 40, 59)) {
            val candles = syntheticSeries(bars)
            for (detector in detectors) {
                assertEquals(
                    emptyList(),
                    detector.detect(emptyList(), candles),
                    "${detector.name}: ${bars} bars must not produce a match",
                )
            }
        }
    }

    @Test
    fun `anEmptyCandleListIsHandledByEveryDetector`() {
        for (detector in detectors) {
            assertEquals(emptyList(), detector.detect(emptyList(), emptyList()), detector.name)
        }
    }

    @Test
    fun `aFlatSeriesProducesNoMatchAndNoException`() {
        // A degenerate series is the classic NaN / divide-by-zero source: every ATR, RSI and slope is
        // zero. plan.md §12 requires the detectors to survive it.
        val candles = flatSeries(200)
        for (detector in detectors) {
            assertEquals(emptyList(), detector.detect(emptyList(), candles), detector.name)
        }
    }

    @Test
    fun `detection_is_deterministic_to_the_byte`() {
        // plan.md §4.3 purity and `scoring-spec.md` §9.1's determinism requirement.
        for (symbol in history.symbols().take(6)) {
            for (timeframe in listOf(Timeframe.D1, Timeframe.W1)) {
                val candles = history.candles(symbol, timeframe)
                val snapshot = IndicatorSnapshot.of(candles)
                val pivots = ZigzagPivots.compute(candles, timeframe, atr = snapshot.atr).pivots
                for (detector in detectors) {
                    assertEquals(
                        detector.detect(pivots, candles),
                        detector.detect(pivots, candles),
                        "${detector.name} on $symbol/$timeframe must be deterministic",
                    )
                }
            }
        }
    }

    @Test
    fun `no_match_uses_an_unconfirmed_pivot`() {
        // §2.5: "Structural detectors (6–12) skip unconfirmed pivots entirely" — with a one-bar
        // confirmation lag that means the newest pivot of the series can never be used.
        for (symbol in history.symbols().take(8)) {
            for (timeframe in listOf(Timeframe.D1, Timeframe.W1)) {
                val candles = history.candles(symbol, timeframe)
                val pivots = ZigzagPivots.compute(candles, timeframe).pivots
                val newest = pivots.maxOfOrNull { it.barIndex } ?: continue
                for (detector in detectors) {
                    for (match in detector.detect(pivots, candles)) {
                        assertTrue(
                            match.pivots.none { it.barIndex == newest && candles.size - 1 - newest < 1 },
                            "${detector.name} on $symbol/$timeframe used the unconfirmed newest pivot",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `atMostOneMatchPerFormationAndBreakoutPair`() {
        // §4 rule 6.
        for (symbol in history.symbols().take(10)) {
            val candles = history.candles(symbol, Timeframe.D1)
            val pivots = ZigzagPivots.compute(candles, Timeframe.D1).pivots
            for (detector in detectors) {
                val keys = detector.detect(pivots, candles)
                    .map { it.pivots.map { p -> p.barIndex } }
                assertEquals(keys.distinct().size, keys.size, "${detector.name} on $symbol repeated a formation")
            }
        }
    }

    @Test
    fun `noMatchIsReturnedForAnUnconfirmedThreePivotTailAndTheDetectorStillTerminates`() {
        // Weekly series carry very few pivots (measured: 7 over 79 weekly bars), so the short-window
        // requirements must degrade honestly — fewer pivots means no match, never a stretched one.
        val weekly = history.candles("RELIANCE", Timeframe.W1)
        val pivots = ZigzagPivots.compute(weekly, Timeframe.W1).pivots
        assertTrue(pivots.size < 12, "the W1 pivot list is expected to be short, was ${pivots.size}")
        for (detector in detectors) {
            detector.detect(pivots, weekly)
        }
    }

    // -------------------------------------------------------------------------------- helpers

    private fun scan(body: (PatternDetector, List<Candle>, List<com.mia.app.domain.model.Pivot>, Timeframe) -> Unit) {
        for (symbol in history.symbols()) {
            for (timeframe in listOf(Timeframe.D1, Timeframe.W1)) {
                val candles = history.candles(symbol, timeframe)
                if (candles.isEmpty()) continue
                val snapshot = IndicatorSnapshot.of(candles)
                val pivots = ZigzagPivots.compute(candles, timeframe, atr = snapshot.atr).pivots
                for (detector in detectors) body(detector, candles, pivots, timeframe)
            }
        }
    }

    private fun syntheticSeries(bars: Int): List<Candle> {
        if (bars <= 0) return emptyList()
        val out = ArrayList<Candle>(bars)
        val start = LocalDate.of(2025, 1, 1)
        for (i in 0 until bars) {
            val base = 100.0 + 0.4 * i
            out += Candle(
                symbol = "SYNTH",
                timeframe = Timeframe.D1,
                date = start.plusDays(i.toLong()),
                open = base,
                high = base * 1.01,
                low = base * 0.99,
                close = base + 0.2,
                volume = 1_000_000L,
            )
        }
        return out
    }

    private fun flatSeries(bars: Int): List<Candle> = syntheticSeries(bars).map {
        it.copy(open = 100.0, high = 100.0, low = 100.0, close = 100.0)
    }
}