package com.mia.app.domain.pattern

import com.mia.app.domain.model.Candle
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Reproduces the **distance-bucket × horizon hit table** on this repository's committed NSE
 * fixture, and asserts the findings the projection gate is built on.
 *
 * ### Why the table is computed here rather than pasted in
 * A hand-typed table is a photograph of a measurement, and photographs go stale silently: the
 * fixture changes, a detector's geometry is re-tuned, and the published number keeps asserting
 * something the code no longer does. So [ProjectionCalibration] recomputes every cell from
 * `domain/src/test/resources/fixtures/eod_history.json` on each run, and this file asserts only
 * what must remain true for [ProjectionGate] to be honest:
 *
 * 1. **A tight level beats the base rate.** Within 3 % the level is reached far more often than a
 *    level of the same size with no pattern involved — the whole reason the gate exists.
 * 2. **The curve falls with distance.** Every reportable bucket is at or below the one inside it,
 *    so the gate is cutting in the direction the data points.
 * 3. **The unfiltered rate is far below a coin flip.** Roughly one pattern in six is reached inside
 *    five bars. Publishing anything stronger than that is the failure mode plan.md §1.4 is about.
 *
 * ### Every number carries its sample size
 * Cells are printed with `n` and a bucket below [MatchScorePolicy.MIN_HIT_RATE_SAMPLES] is never
 * compared, because a rate from a handful of candidates is not a rate. That is the same rule the
 * accuracy screen applies, enforced here so the table cannot drift away from it.
 */
class ProjectionCalibrationTest {

    @Test
    fun `a level within three percent is reached far more often than the no-pattern base rate`() {
        val table = ProjectionFixture.calibration
        println(ProjectionFixture.report())
        val tight = table.bucket(DistanceBucket.WITHIN_3)
        val rate = assertNotNull(tight.hitRatePct(H5), "the ≤3 % bucket has no candidates")
        val base = assertNotNull(table.baseRatePct(H5), "no base rate was computed")

        assertTrue(tight.samples >= 100, "only ${tight.samples} candidates within 3 % — too few to claim anything")
        assertTrue(
            rate > 60.0,
            "a level within 3 % was reached only ${"%.1f".format(rate)} % of the time (n=${tight.samples}); " +
                "the measurement this gate rests on found 83 %",
        )
        assertTrue(
            base < 20.0,
            "the no-pattern base rate at five bars is ${"%.1f".format(base)} % — a base rate that high " +
                "would leave no room for the pattern to add anything",
        )
        assertTrue(
            rate >= base * 3,
            "the lift over the base rate is only ${"%.1f".format(rate - base)} points (${"%.1f".format(rate)} % " +
                "against a ${"%.1f".format(base)} % base rate)",
        )
    }

    @Test
    fun `the rate never rises as the level moves further away`() {
        val table = ProjectionFixture.calibration
        // Only buckets with enough candidates to mean something are comparable at all; below the
        // sample floor a rate is withheld rather than read (plan.md §6.1).
        val reportable = table.byBucket.filter { it.isReportable }

        assertTrue(
            reportable.size >= 3,
            "only ${reportable.size} buckets reached ${MatchScorePolicy.MIN_HIT_RATE_SAMPLES} samples, " +
                "so the curve cannot be called monotone: " + reportable.joinToString { it.labelAt(H5) },
        )
        reportable.zipWithNext().forEach { (tighter, wider) ->
            val a = assertNotNull(tighter.hitRatePct(H5))
            val b = assertNotNull(wider.hitRatePct(H5))
            assertTrue(
                b <= a,
                "${wider.label} was reached ${"%.1f".format(b)} % of the time, above the " +
                    "${"%.1f".format(a)} % of the tighter ${tighter.label} — the distance curve " +
                    "must fall, or the gate is cutting the wrong way",
            )
        }
        // And the open-ended bucket, which every other bucket sits inside.
        val beyond = assertNotNull(table.bucket(DistanceBucket.BEYOND_12).hitRatePct(H5))
        val withinTwelve = assertNotNull(table.bucket(DistanceBucket.WITHIN_12).hitRatePct(H5))
        assertTrue(beyond <= withinTwelve, "beyond 12 % ($beyond) beat within 12 % ($withinTwelve)")
    }

    @Test
    fun `the unfiltered pattern hit rate is far below a coin flip`() {
        val overall = assertNotNull(ProjectionFixture.calibration.overall.hitRatePct(H5))
        assertTrue(
            overall < 50.0,
            "patterns reach their measured-move level ${"%.1f".format(overall)} % of the time with no " +
                "distance filter — a reader shown that would be reading a coin flip as a signal",
        )
        assertTrue(
            overall > 5.0,
            "the pipeline produced a ${"%.1f".format(overall)} % overall rate; that is too low to be a " +
                "measurement of this engine and more likely a broken walk-forward",
        )
        assertTrue(
            ProjectionFixture.calibration.overall.samples >= 500,
            "only ${ProjectionFixture.calibration.overall.samples} settled candidates — the published " +
                "measurement had 875, so the walk-forward is finding materially less",
        )
    }

    @Test
    fun `theGateBoundaryIsWhereTheLiftOverTheBaseRateEnds`() {
        val table = ProjectionFixture.calibration
        val base = assertNotNull(table.baseRatePct(H5))
        val insideGate = assertNotNull(table.bucket(DistanceBucket.WITHIN_5).hitRatePct(H5))

        // DISTANCE_MAX = 5 % is the edge the gate draws. Inside it the level is reached several
        // times more often than chance; past it the curve decays back toward the base rate, which is
        // why the gate stops there.
        assertTrue(
            insideGate >= base * 2,
            "the bucket the gate admits (within 5 %) was reached ${"%.1f".format(insideGate)} % of the " +
                "time against a ${"%.1f".format(base)} % base rate — the gate would be admitting noise",
        )
        val far = assertNotNull(table.bucket(DistanceBucket.BEYOND_12).hitRatePct(H5))
        assertTrue(
            far <= base * 2,
            "a level more than 12 % away was reached ${"%.1f".format(far)} % of the time against a " +
                "${"%.1f".format(base)} % base rate, so the distance curve has not decayed where the " +
                "gate says it has",
        )
    }

    @Test
    fun `everyPublishedRateCarriesItsSampleSize`() {
        val table = ProjectionFixture.calibration
        // The rendering the accuracy screen will reuse: rate, horizon and n in one string, always.
        val labelled = table.byBucket.filter { it.samples > 0 }.map { it.labelAt(H5) }
        assertTrue(labelled.isNotEmpty())
        labelled.forEach { label ->
            assertTrue(label.contains("n="), "a hit rate without its sample size: $label")
            assertTrue(label.contains("h5"), "a hit rate without its horizon: $label")
        }
        table.renderTable().forEach { line -> assertTrue(line.isNotBlank()) }

        // A bucket below the sample floor says so instead of quoting a percentage.
        val withheld = table.byBucket.firstOrNull { it.samples in 1 until MatchScorePolicy.MIN_HIT_RATE_SAMPLES }
        withheld?.let { assertTrue(!it.isReportable) }
    }

    private companion object {
        /** `OutcomeTrackerPolicy.DEFAULT_HORIZON_BARS` — the horizon every published finding uses. */
        const val H5 = 5
    }
}

/**
 * The walk-forward this repository's two projection tests share.
 *
 * ### Why it is computed once and shared
 * A walk-forward over every large cap in the fixture runs the full pipeline a thousand times. The
 * calibration table and the satisfiability census ask different questions of the **same** walk, so
 * running it twice would double the suite's cost to answer neither question better. `by lazy`
 * makes the cost a single computation for whichever test runs first.
 *
 * ### Why this is test code and not production code
 * The *rule* lives in production ([ProjectionCalibration], [ProjectionGate]); the *history* does
 * not, because the fixture is a test resource and `:domain`'s main source set must not reach into
 * one. This object is the seam: real bars in, a real table out.
 */
internal object ProjectionFixture {

    /**
     * The evaluated detectors, in the same order and the same set as the score-discrimination
     * measurement — a second, different list would make the two tables incomparable.
     *
     * The candlestick family is included deliberately even though the catalogue no longer markets
     * it: excluding the patterns the app does not advertise would bias the sample, because a
     * three-bar shape and a double top do not fail for the same reasons.
     */
    val detectors: List<PatternDetector> = listOf(
        HeadAndShouldersDetector(),
        InverseHeadAndShouldersDetector(),
        DoubleTopDetector(),
        DoubleBottomDetector(),
        AscendingTriangleDetector(),
        DescendingTriangleDetector(),
        FlagDetector(),
        RisingWedgeDetector(),
        BullishEngulfingDetector(),
        BearishEngulfingDetector(),
        HammerDetector(),
        ShootingStarDetector(),
        StarDetector(),
    )

    /** Every symbol in the fixture with enough history to place a formation at all. */
    val candlesBySymbol: Map<String, List<Candle>> = EodHistory.SYMBOLS
        .map { it to EodHistory.candles(it) }
        .filter { it.second.size >= ProjectionCalibration.MIN_HISTORY_BARS }
        .toMap()

    val walk: ProjectionCalibration.WalkForwardResult by lazy {
        ProjectionCalibration.walkForward(candlesBySymbol, detectors, Timeframe.D1)
    }

    val calibration: CalibrationTable by lazy {
        val result = walk
        ProjectionCalibration.calibrate(
            candidates = result.candidates,
            baseRatesPct = ProjectionCalibration.baseRatesPct(candlesBySymbol, ProjectionCalibration.HORIZONS),
            windowsVisited = result.windowsVisited,
            incomplete = result.incomplete,
        )
    }

    /** The table, printed. Cheap enough to keep in the log of any run that touches it. */
    fun report(): String {
        val table = calibration
        val header = buildString {
            append("symbols=${candlesBySymbol.size} windows=${table.windowsVisited} ")
            append("candidates=${table.totalSamples} incomplete=${table.incomplete}")
        }
        return (listOf("=== PROJECTION CALIBRATION ===", header) + table.renderTable()).joinToString("\n")
    }
}