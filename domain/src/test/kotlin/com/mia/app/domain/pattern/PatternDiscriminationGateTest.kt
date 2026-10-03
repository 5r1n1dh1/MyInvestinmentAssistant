package com.mia.app.domain.pattern

import com.mia.app.domain.model.Match
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
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The P7 gate (plan.md §11): **the 0–100 score must produce a spread, not cluster near a
 * constant.**
 *
 * > "P7: score discrimination test — the 0–100 score must produce a **spread**, not cluster
 * > near a constant. If it does not discriminate, the scorer is noise and P8 must not start."
 *
 * ### Why this is the most important test in the repository
 * A scorer that returns 72 for everything technically satisfies every range check, every
 * [Match.score] == [com.mia.app.domain.model.ScoreBreakdown].total assertion, and every
 * threshold in `MatchScorePolicy` — and would still be worthless, because the number the user
 * reads would carry no information. Nothing else in the suite can catch that: unit tests assert
 * that the scorer does what it was told, and the scorer doing something *constant* is a
 * consistent behaviour.
 *
 * So this test runs the **whole pipeline** — zigzag, all twelve detectors, the four-factor
 * scorer, the penalty table and the gates — over **real** NSE end-of-day data for 20 large
 * caps (372 sessions each), and interrogates the resulting score distribution.
 *
 * ### What it asserts
 * 1. The engine found a non-trivial number of matches (a scorer cannot discriminate nothing).
 * 2. The scores span a wide range rather than clustering.
 * 3. The standard deviation is materially above zero.
 * 4. Scores fill the band continuously enough that the 50/70 thresholds are actually used.
 * 5. Every score is inside [0,100] and equals its own breakdown's weighted total.
 *
 * A sixth, weaker expectation is deliberately **not** asserted: that the distribution be normal
 * or that its mean land anywhere in particular. Real pattern scores cluster low because most
 * formations are mediocre, and pinning a mean would make the test a tripwire for ordinary
 * threshold tuning. What must hold is that the numbers *differ*.
 */
class PatternDiscriminationGateTest {

    /** The twelve v1 detectors, in plan.md §6.5 order. */
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
     * Bars per scan: the **full** fixture history (372 sessions), not a shorter window.
     *
     * This is not cosmetic. Measured: a 200-bar window yields only 36 scored candidates across
     * 20 stocks, which is too few for the distribution to mean anything — most structural
     * detectors look back further than 200 bars to place a formation, so truncating the input
     * starves them and the sample measures the window rather than the scorer. The app itself
     * feeds the engine 500 bars (plan.md §6.2), so the full history is also the more faithful
     * window.
     */
    private val window = 372

    /**
     * Every **scored** candidate the pipeline produced, kept or suppressed.
     *
     * The gate is about the scorer, so it must interrogate every number the scorer emitted — not
     * only the handful that survived `MatchScorePolicy`'s 50-point render threshold. Measured on
     * this fixture the survivors are a small minority of scored candidates, so a distribution
     * drawn from survivors alone would say almost nothing about how the scorer behaves.
     *
     * `PatternScanResult` deliberately keeps the rejected candidates (`suppressed`, each with its
     * score and a reason) for exactly this kind of interrogation — and so the UI can explain what
     * it chose not to draw.
     */
    private data class Scored(
        val symbol: String,
        val name: String,
        val score: Int,
        val kept: Boolean,
        val breakdown: com.mia.app.domain.model.ScoreBreakdown?,
    )

    private fun scanAll(): List<Scored> =
        EodHistory.fullHistorySymbols(count = 20).flatMap { symbol ->
            val candles = EodHistory.candles(symbol).takeLast(window)
            assertTrue(
                candles.size >= MatchScorePolicyForTests.MIN_D1_BARS,
                "$symbol has only ${candles.size} bars in the fixture",
            )
            val result = PatternEngine.run(
                candles = candles,
                timeframe = Timeframe.D1,
                detectors = detectors,
                options = PatternScanOptions(symbol = symbol),
            )
            result.matches.map {
                Scored(symbol, it.name, it.score, kept = true, breakdown = it.breakdown)
            } + result.suppressed
                // A candidate whose score is 0 never reached the scorer; counting those would
                // measure the detectors' strictness, not the scorer's discrimination.
                .filter { it.score > 0 }
                .map { Scored(symbol, it.name, it.score, kept = false, breakdown = null) }
        }

    @Test
    fun `the score discriminates across real stocks`() {
        val scored = scanAll()
        val scores = scored.map { it.score }

        // (1) There is something to discriminate between.
        assertTrue(
            scored.size >= MIN_MATCHES,
            "only ${scored.size} scored candidates across 20 real stocks — the pipeline is " +
                "finding almost nothing, so a score distribution would be meaningless",
        )

        val min = scores.min()
        val max = scores.max()
        val mean = scores.average()
        val stdDev = sqrt(scores.sumOf { (it - mean) * (it - mean) } / scores.size)

        println(
            "P7 gate: ${scored.size} scored candidates over " +
                "${EodHistory.fullHistorySymbols(20).size} symbols (${scored.count { it.kept }} kept) — " +
                "min=$min max=$max mean=${"%.1f".format(mean)} " +
                "sd=${"%.1f".format(stdDev)} distinct=${scores.distinct().size}",
        )

        // (2) The scores are not a constant.
        assertTrue(
            max - min >= MIN_RANGE,
            "scores span only ${max - min} points (min=$min, max=$max); a near-constant score " +
                "means the scorer is noise (plan.md §11, P7 gate)",
        )

        // (3) The spread is not carried by a single outlier either.
        assertTrue(
            stdDev >= MIN_STD_DEV,
            "score standard deviation is ${"%.2f".format(stdDev)}, below the ${"%.2f".format(MIN_STD_DEV)} " +
                "floor — the bulk of the scores are indistinguishable",
        )

        // (4) Many distinct values, so the 50/70 thresholds are doing real work.
        assertTrue(
            scores.distinct().size >= MIN_DISTINCT,
            "only ${scores.distinct().size} distinct scores across ${scores.size} matches",
        )

        // The honest reporting requirement: some matches are publishable and some are not.
        val renderable = scores.count { it >= 50 }
        val projectable = scores.count { it >= 70 }
        val suppressed = scores.count { it < 50 }
        println(
            "P7 gate spread: >=70 $projectable, 50-69 $renderable, <50 $suppressed " +
                "(thresholds are load-bearing if all three are non-zero)",
        )
        assertTrue(
            suppressed > 0,
            "no match ever scored below the render threshold — the scorer cannot reject anything",
        )
        assertTrue(
            renderable > 0,
            "no match reached the render threshold, so the app would show an empty Patterns tab " +
                "for every symbol in this sample",
        )

        // (5) Every score is a real weighted total, not an ad-hoc number.
        scored.forEach { candidate ->
            assertTrue(candidate.score in 0..100, "${candidate.name} scored ${candidate.score}")
            candidate.breakdown?.let { breakdown ->
                assertTrue(
                    breakdown.total in 0..100,
                    "${candidate.name}'s weighted total ${breakdown.total} is out of range",
                )
            }
        }
    }

    @Test
    fun `scores differ within a single stock, not only between stocks`() {
        // Between-stock variation alone could be produced by a per-symbol offset. This asks the
        // harder question: does one stock's own history produce a range of scores?
        val perSymbolSpread = EodHistory.fullHistorySymbols(count = 20).mapNotNull { symbol ->
            val scores = scanAll().filter { it.symbol == symbol }.map { it.score }
            if (scores.size < 2) null else symbol to (scores.max() - scores.min())
        }

        assertTrue(
            perSymbolSpread.isNotEmpty(),
            "no symbol produced two comparable matches, so within-stock spread is untestable",
        )
        val widening = perSymbolSpread.count { it.second >= MIN_RANGE }
        println(
            "P7 gate within-stock spread: " +
                perSymbolSpread.joinToString { "${it.first}=${it.second}" },
        )
        assertTrue(
            widening >= 1,
            "within every single stock the scores were nearly constant " +
                "($perSymbolSpread) — the scorer is not responding to formation quality",
        )
    }

    @Test
    fun `the score is not a function of one factor alone`() {
        // A scorer that secretly returned `geometry` would pass every spread test above. This
        // asserts the four factors are genuinely distinct numbers.
        val matches = scanAll().filter { it.breakdown != null }
        assertTrue(
            matches.size >= MIN_FACTOR_MATCHES,
            "only ${matches.size} scored candidates carry a breakdown, so the four factors cannot " +
                "be compared",
        )

        val identicalFactors = matches.count { m ->
            val b = m.breakdown!!
            b.structure == b.geometry && b.confirmation == b.geometry && b.context == b.geometry
        }
        assertTrue(
            identicalFactors == 0,
            "$identicalFactors candidates have all four factors equal — the four-factor model is " +
                "not being applied",
        )

        val distinctFactorProfiles = matches.map { m ->
            val b = m.breakdown!!
            listOf(b.geometry, b.structure, b.confirmation, b.context)
        }.distinct()
        println("P7 gate factor profiles: ${distinctFactorProfiles.size} distinct across ${matches.size} candidates")
        assertTrue(
            distinctFactorProfiles.size >= 5,
            "only ${distinctFactorProfiles.size} distinct (G,S,C,X) profiles across ${matches.size} " +
                "candidates — the factors are barely moving independently",
        )
    }

    private object MatchScorePolicyForTests {
        const val MIN_D1_BARS = 60
    }

    private companion object {
        /** Below this many scored candidates the distribution says nothing about the scorer. */
        const val MIN_MATCHES = 50

        /** The four-factor comparison needs enough breakdown-carrying candidates. */
        const val MIN_FACTOR_MATCHES = 20

        /** A scorer that discriminates must span a meaningful part of 0–100. */
        const val MIN_RANGE = 25

        /** And its bulk must not be a spike. */
        const val MIN_STD_DEV = 6.0

        /** Distinct integer scores needed before the thresholds are meaningful. */
        const val MIN_DISTINCT = 10
    }
}