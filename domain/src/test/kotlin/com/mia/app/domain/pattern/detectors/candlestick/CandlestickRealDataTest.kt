package com.mia.app.domain.pattern.detectors.candlestick

import com.mia.app.domain.model.Match
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The regression the brief asks for: every detector over **every** symbol of
 * `fixtures/eod_history.json`, on the last 100 daily bars *and* on the full 372.
 *
 * ### What is asserted
 *
 * * no crash, no exception, a score in range for every match (the deep property tests live in
 *   [CandlestickScoreInvariantTest]);
 * * **the detection rate per detector is reported**, in the test output, because a detector that
 *   fires on most symbols is the classic apophenia failure `[Ch.18-5 §Seeing Patterns Everywhere]`
 *   and must be visible rather than discovered.
 *
 * ### Why a rate is the right thing to watch
 *
 * The book's own expectation is that most raw candidate formations must die: "Many beginners
 * identify every three-peak formation as a Head & Shoulders" `[Ch.18-2]`, and `scoring-spec.md`
 * §9.2 puts the whole score population at 55–75 % below the render threshold. A candlestick
 * detector that matches on most symbols would therefore be wrong by construction — the
 * `CONTEXT_DECLINE_PCT` gate alone rejects anything that is not preceded by a real decline, and
 * `LOCATION_BAND_PCT` rejects anything not at a level.
 *
 * The per-detector rates are also asserted to stay **below 60 %**, the threshold the brief calls
 * "suspicious". That is a deliberately loose anti-apophenia guard, not a tuning target: a rate
 * above it means a gate is not doing its job, and the detector needs fixing rather than the
 * fixture.
 */
class CandlestickRealDataTest {

    private data class Sweep(
        val detector: String,
        val window: String,
        val bars: Int,
        val symbols: Int,
        val matches: List<Match>,
    ) {
        val rate: Double get() = if (symbols == 0) 0.0 else 100.0 * matches.size / symbols
    }

    @Test
    fun `everyDetectorRunsOverEveryRealSymbolAndReportsItsDetectionRate`() {
        val sweeps = ArrayList<Sweep>()
        val windows = listOf(
            "last ${RealHistory.LAST_N_BARS} bars" to { symbol: String -> RealHistory.lastBars(symbol) },
            "full history" to { symbol: String -> RealHistory.candles(symbol) },
        )

        for ((label, pick) in windows) {
            for (detector in allCandlestickDetectors()) {
                val matches = ArrayList<Match>()
                for (symbol in RealHistory.SYMBOLS) {
                    val candles = pick(symbol)
                    assertTrue(candles.size >= 60, "$symbol must carry a full daily history")
                    for (hit in detectOn(detector, candles)) {
                        assertTrue(hit.score in 0..100, "${detector.name}/$symbol scored ${hit.score}")
                        assertTrue(hit.measuredMove == null, "DD-1")
                        matches += hit
                    }
                }
                sweeps += Sweep(
                    detector = detector.name,
                    window = label,
                    bars = RealHistory.candles(RealHistory.SYMBOLS.first()).size,
                    symbols = RealHistory.SYMBOLS.size,
                    matches = matches,
                )
            }
        }

        // The report the brief asks to be able to read off the test output.
        println("--- candlestick detection rate over ${RealHistory.SYMBOLS.size} NSE large caps ---")
        for (sweep in sweeps) {
            val scores = sweep.matches.map { it.score }
            val mean = if (scores.isEmpty()) 0.0 else scores.sum().toDouble() / scores.size
            val max = scores.maxOrNull() ?: 0
            val min = scores.minOrNull() ?: 0
            println(
                "%-18s %-16s bars=%d  matches=%2d/%2d (%5.1f%%)  score min=%d mean=%.1f max=%d"
                    .format(
                        sweep.detector,
                        sweep.window,
                        sweep.bars,
                        sweep.matches.size,
                        sweep.symbols,
                        sweep.rate,
                        min,
                        mean,
                        max,
                    ),
            )
        }
        println("--- end detection rate report ---")

        // Nothing in the family may claim a match on more than 60 % of the universe.
        for (sweep in sweeps) {
            assertTrue(
                sweep.rate <= 60.0,
                "${sweep.detector} matched ${sweep.rate}% of symbols on ${sweep.window}; " +
                    "that is apophenia, not detection [Ch.18-5 §Seeing Patterns Everywhere]",
            )
        }
    }

    @Test
    fun `theFullHistoryWindowIsLongEnoughForTheFamilysOwnGates`() {
        // 372 bars > 60 (history gate) + 20 (context) + 3 (signal window) with room to spare, so
        // the full-history sweep is a real test and not a "not enough history" no-op.
        val bars = RealHistory.candles(RealHistory.SYMBOLS.first()).size
        assertEquals(372, bars)
        assertTrue(bars > MatchScorePolicyBars.TOTAL_FLOOR)
    }

    private object MatchScorePolicyBars {
        /** 60-bar gate + 20-bar context + 3-bar signal window + the formation itself. */
        const val TOTAL_FLOOR: Int = 60 + 20 + 3 + 3
    }
}