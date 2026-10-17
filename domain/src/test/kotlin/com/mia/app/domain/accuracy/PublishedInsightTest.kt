package com.mia.app.domain.accuracy

import com.mia.app.domain.compliance.AdviceLanguage
import com.mia.app.domain.compliance.HonestyContract
import com.mia.app.domain.model.HitRate
import com.mia.app.domain.model.MatchScorePolicy
import com.mia.app.domain.model.PivotKind
import com.mia.app.domain.model.Timeframe
import com.mia.app.domain.pattern.DistanceBucket
import com.mia.app.domain.pattern.EodHistory
import com.mia.app.domain.pattern.PatternIds
import com.mia.app.domain.pattern.ProjectionCalibration
import com.mia.app.domain.pattern.ProjectionFixture
import com.mia.app.domain.pattern.ZigzagPivots
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
import com.mia.app.domain.technical.IndicatorSnapshot
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Pins the numbers the Accuracy tab ships, and proves the ones it computes.
 *
 * ### The problem this file exists for
 * Two different kinds of number reach that screen, and they must not be confused:
 *
 * * a **published measurement**, made once on this repository's committed NSE fixture and committed
 *   here so a fresh install has something honest to show;
 * * a **device measurement**, computed on the reader's own stored outcomes.
 *
 * A published number that is merely *recorded* is a photograph, and photographs go stale silently.
 * So every cell of [PublishedInsight.CALIBRATION] is recomputed below through
 * [ProjectionCalibration] on the same fixture and compared; the counts must match exactly and the
 * rates must agree within [RATE_TOLERANCE] points. A detector re-tuned, a fixture changed or a gate
 * moved fails here instead of reaching a reader.
 *
 * ### Why the census is pinned loosely and deliberately
 * `PatternCensusRow.reachedFirstGate` is `DetectorStrictnessCensus`'s own stage count for a
 * detector-specific gate, so recomputing it means re-deriving five different detectors' internal
 * pre-checks. What is asserted exactly instead is the part that decides whether the panel is
 * telling the truth about *this* build: every pattern the panel names must still produce at least
 * one candidate on the fixture, and the two stage columns whose arithmetic is published
 * ([FLAG_POLARITY], [HEAD_AND_SHOULDERS_WINDOWS]) must reproduce exactly. A panel claiming a pattern
 * fires when it does not is the failure that matters.
 */
class PublishedInsightTest {

    private val published = PublishedInsight.CALIBRATION
    private val recomputed by lazy { ProjectionFixture.calibration }

    // ------------------------------------------------------- the calibration table

    @Test
    fun `the published calibration table still matches the fixture it was measured on`() {
        published.horizons.forEach { horizon ->
            assertTrue(
                horizon in recomputed.horizons,
                "h$horizon is published but the measurement no longer reports it",
            )
        }
        assertTrue(published.rows.isNotEmpty())

        published.rows.forEach { row ->
            val live = assertNotNull(
                recomputed.byBucket.firstOrNull { it.bucket == row.bucket },
                "${row.label} is published but no longer measured",
            )
            // Counts exactly: an integer cannot be "nearly" right, and a changed count is the first
            // thing a re-tuned gate would show.
            assertEquals(
                live.samples,
                row.samples,
                "${row.label} now holds ${live.samples} candidates, not ${row.samples}",
            )
            published.horizons.forEach { horizon ->
                val mine = row.hitRatePct(horizon)
                val theirs = live.hitRatePct(horizon)
                if (mine == null || theirs == null) return@forEach
                assertTrue(
                    abs(mine - theirs) <= RATE_TOLERANCE,
                    "${row.label} at h$horizon is ${"%.1f".format(mine)}% but the fixture now " +
                        "measures ${"%.1f".format(theirs)}% (n=${live.samples})",
                )
            }
        }
    }

    @Test
    fun `the published chance-level control still matches the fixture`() {
        published.baseRatesPct.forEach { (horizon, rate) ->
            val live = ProjectionCalibration.baseRatesPct(
                candlesBySymbol = ProjectionFixture.candlesBySymbol,
                horizons = listOf(horizon),
                distancePercent = published.baseRateDistancePercent,
            )[horizon] ?: 0.0
            assertTrue(
                abs(rate - live) <= RATE_TOLERANCE,
                "a level ${published.baseRateDistancePercent}% away is touched " +
                    "${"%.1f".format(rate)}% at h$horizon in the published table and " +
                    "${"%.1f".format(live)}% now",
            )
        }
    }

    @Test
    fun `the published table still describes an engine whose rate falls with distance`() {
        // The claim the projection gate rests on, checked against the *published* cells a reader
        // will see rather than only against a live recomputation.
        val atFive = published.rows.mapNotNull { it.hitRatePct(H5) }
        assertTrue(atFive.size >= 3, "not enough published cells to call the curve monotone")
        atFive.zipWithNext().forEach { (tighter, wider) ->
            assertTrue(
                wider <= tighter,
                "a wider distance band was reached more often than a tighter one: $atFive",
            )
        }
        val gateEdge = assertNotNull(
            published.row(DistanceBucket.WITHIN_5)?.hitRatePct(H5),
            "the gate's own edge must be published",
        )
        val control = assertNotNull(published.baseRatePct(H5), "no chance-level control at h5")
        assertTrue(
            gateEdge >= control * 3,
            "the ${DistanceBucket.WITHIN_5.label} band is ${"%.0f".format(gateEdge)}% against a " +
                "${"%.0f".format(control)}% control — the gate's outer edge is no longer where the " +
                "pattern adds anything",
        )
        assertNull(published.withheldReason, "the published table must have at least one reportable cell")
    }

    @Test
    fun `no published cell is one the app would withhold`() {
        published.rows.forEach { row ->
            assertTrue(
                row.isReportable,
                "${row.label} is published with n=${row.samples}, below the reporting gate — a " +
                    "published cell must never be one HitRate would withhold",
            )
            val label = row.labelAt(H5)
            HonestyContract.requireHitRateSampleSize(label)
            assertTrue(label.contains("h5"), label)
            assertTrue(label.contains("n=${row.samples}"), label)
        }
    }

    @Test
    fun `the published rate is the corrected one, and says which figure it is not`() {
        // docs/VERIFICATION.md §2: the unfiltered rate of the *real* detectors is ~34%. The 16.45%
        // in older documents came from a stand-in detector whose levels sit much further away.
        val overall = assertNotNull(recomputed.overall.hitRatePct(H5), "no unfiltered rate was measured")
        assertTrue(
            abs(overall - PUBLISHED_UNFILTERED_PCT) <= RATE_TOLERANCE,
            "the fixture's unfiltered rate is ${"%.1f".format(overall)}%, so the published " +
                "$PUBLISHED_UNFILTERED_PCT% no longer describes this engine",
        )
        assertTrue(overall > 20.0, "the unfiltered rate fell to ${"%.1f".format(overall)}%")
        assertTrue(
            PublishedInsight.REACH.sourceNote.contains("n=${PublishedInsight.SETTLED_CANDIDATES}"),
            "the unfiltered rate must carry its sample size in prose too",
        )
    }

    // --------------------------------------------------------- provenance labelling

    @Test
    fun `a published figure is never presented as this device's own record`() {
        assertEquals(InsightProvenance.PUBLISHED_FIXTURE, published.provenance)
        assertTrue(published.provenance.isPublished)
        val line = published.provenanceLine()
        assertTrue(line.contains("not this device"), "provenance line was \"$line\"")
        assertTrue(line.contains("fixture"), line)
        AdviceLanguage.requireClean(line)
        HonestyContract.requireNotProbabilityClaim(line)

        val reach = PublishedInsight.REACH
        assertEquals(InsightProvenance.PUBLISHED_FIXTURE, reach.provenance)
        assertTrue(
            !reach.hasDeviceMeasurement,
            "the published fallback must not claim a device measurement it does not have",
        )
        // The medians are deliberately absent, and the sentences must say so rather than invent them.
        assertNull(reach.medianTargetDistancePct)
        assertNull(reach.medianFavourableExcursionPct)
        val text = reach.sentences().joinToString(" ")
        assertTrue(text.contains("not settled enough projections"), text)
        assertTrue(text.contains("10%"), "the chance control must still be stated: $text")
    }

    // ----------------------------------------------------------------- device path

    @Test
    fun `the device path computes the medians from the device's own settled outcomes`() {
        val simulation = RealHistorySimulation.run()
        val insights = assertNotNull(
            TrackRecordInsight.fromOutcomes(simulation.outcomes, simulation.candlesBySymbol),
            "the real simulated history produced no device insight",
        )
        val reach = assertNotNull(insights.reach, "no reach explanation")
        assertEquals(InsightProvenance.DEVICE_STORED, reach.provenance)
        assertTrue(!reach.provenance.isPublished)
        assertTrue(reach.hasDeviceMeasurement)
        assertEquals(simulation.resolved.size, reach.samples)
        assertEquals(RealHistorySimulation.SYMBOLS.size, reach.symbols)

        // The measured reason the rate is low: the level sits further from price than the market
        // usually travels in the same window. docs/VERIFICATION.md §3 measured the simulated moves
        // at 4-5% of price against a median 5-bar favourable excursion of ~1.8%.
        val distance = assertNotNull(reach.medianTargetDistancePct)
        val excursion = assertNotNull(reach.medianFavourableExcursionPct)
        assertTrue(distance in 3.0..6.5, "median target distance was ${"%.1f".format(distance)}%")
        assertTrue(excursion in 0.5..4.0, "median 5-bar excursion was ${"%.1f".format(excursion)}%")
        assertTrue(
            distance > excursion,
            "the level sat ${"%.1f".format(distance)}% away but price only travelled " +
                "${"%.1f".format(excursion)}%: if that is no longer true the card is explaining " +
                "something that is not happening",
        )
        // The control is redrawn at the device's own median distance, not at a convenient 5 %.
        val control = assertNotNull(reach.baseRatePct, "no chance-level control was computed")
        assertTrue(
            abs(reach.baseRateDistancePercent - distance) < 0.001,
            "the control must be measured at the median distance, not at " +
                "${reach.baseRateDistancePercent}%",
        )
        assertTrue(control in 0.0..60.0, "a chance-level touch rate of $control% is not a rate")
    }

    @Test
    fun `the device calibration table buckets this device's own outcomes`() {
        val simulation = RealHistorySimulation.run()
        val table = assertNotNull(
            TrackRecordInsight.calibrationFromOutcomes(simulation.outcomes, simulation.candlesBySymbol),
            "the real simulated history produced no calibration table",
        )
        assertEquals(InsightProvenance.DEVICE_STORED, table.provenance)
        assertEquals(DistanceBucket.entries.size, table.rows.size)
        // Bands are cumulative, as the published table and the screen's copy both say: a level inside
        // 3 % is counted in the 1 % and 2 % bands too. So the closed bands nest, tighter never
        // exceeds wider. `BEYOND_12` is the open-ended remainder rather than a superset, so the last
        // pair is checked by the identity below instead.
        table.rows.filter { it.bucket?.maxPercent != null }.zipWithNext().forEach { (tighter, wider) ->
            assertTrue(
                tighter.samples <= wider.samples,
                "${tighter.label} holds ${tighter.samples} samples, more than the wider " +
                    "${wider.label}'s ${wider.samples} — the bands are cumulative and must nest",
            )
        }
        val withinTwelve = assertNotNull(table.row(DistanceBucket.WITHIN_12))
        val beyond = assertNotNull(table.row(DistanceBucket.BEYOND_12))
        assertEquals(
            simulation.resolved.size,
            withinTwelve.samples + beyond.samples,
            "every settled outcome is either inside 12 % or beyond it",
        )
        // The stand-in detector's levels sit 4-5 % from price, so the band the app's own record
        // actually populates is the one inside the gate, and it is reportable.
        val populated = assertNotNull(table.row(DistanceBucket.WITHIN_5))
        assertTrue(populated.samples > 0, "no settled outcome fell within 5 % of the close")
        assertTrue(populated.isReportable, "${populated.samples} samples in one band is reportable")
        assertTrue(table.hasReportableCell)
        assertNull(table.withheldReason)
    }

    @Test
    fun `an outcome whose projection bar is not stored is left out, never guessed at`() {
        val simulation = RealHistorySimulation.run()
        // Bars that do not contain any projection date: no measurement is possible, and a zeroed
        // table would read as "the device tracked a lot of nothing".
        assertNull(
            TrackRecordInsight.fromOutcomes(simulation.outcomes, emptyMap()),
            "with no stored bars there is nothing to measure, and the published table must be used",
        )
    }

    // ------------------------------------------------ samples until reportable

    @Test
    fun `samples until reportable counts down to the gate and stops at zero`() {
        val gate = MatchScorePolicy.MIN_HIT_RATE_SAMPLES
        assertEquals(gate, TrackRecordInsight.samplesUntilReportable(0))
        assertEquals(1, TrackRecordInsight.samplesUntilReportable(gate - 1))
        assertEquals(0, TrackRecordInsight.samplesUntilReportable(gate))
        assertEquals(0, TrackRecordInsight.samplesUntilReportable(62))
        assertEquals(
            0,
            TrackRecordInsight.samplesUntilReportable(gate + 100),
            "a cell well past the gate is not waiting for anything",
        )

        val report = HitRateCalculator.report(RealHistorySimulation.run().outcomes)
        val nearest = assertNotNull(
            TrackRecordInsight.nearestToReportable(report),
            "every cell of a 930-outcome record is below the gate on the strict grouping",
        )
        val needed = TrackRecordInsight.samplesUntilReportable(nearest)
        assertTrue(needed > 0, "a cell that clears the gate is not the nearest one below it")
        val smallestGap = report.perSymbolAndPattern
            .plus(report.perPattern)
            .plus(report.perSymbol)
            .filterNot { it.isReportable }
            .minOf { TrackRecordInsight.samplesUntilReportable(it) }
        assertEquals(smallestGap, needed, "nearestToReportable must report the smallest gap")

        val text = TrackRecordInsight.samplesUntilReportableText(nearest)
        assertTrue(text.contains("${nearest.samples} of $gate"), text)
        AdviceLanguage.requireClean(text)
        HonestyContract.requireNotProbabilityClaim(text)
    }

    @Test
    fun `a cell that already clears the gate says so rather than implying something is missing`() {
        val text = TrackRecordInsight.samplesUntilReportableText(HitRate("RELIANCE", null, 4, 62))
        assertTrue(text.contains("Reportable now"), text)
        assertTrue(text.contains("62"), text)
        AdviceLanguage.requireClean(text)
    }

    // ------------------------------------------------------------------- census

    @Test
    fun `a single scan is a different measurement from the census, and the panel says windows`() {
        // Measured over the same 20 large caps the census walked. A detector pass emits matches; the
        // census counts windows per gate stage. They are not the same number and never were — so the
        // published column is worded "windows", and this test exists to stop a future edit from
        // relabelling it as matches.
        val counts = candidateCountsByPattern()
        assertEquals(
            SCAN_MATCHES_ON_FIXTURE,
            counts.values.sum(),
            "the detectors now emit ${counts.values.sum()} matches over the census's own symbols, " +
                "so the census's window counts need re-measuring before this panel is trusted",
        )
        PublishedCensus.CENSUS.rows.forEach { row ->
            assertTrue(
                row.label().contains("windows"),
                "the census row must be worded in windows, was \"${row.label()}\"",
            )
            val emitted = counts[row.patternName] ?: 0
            assertTrue(
                emitted <= row.survivingWindows,
                "${row.patternName} emitted $emitted matches from ${row.survivingWindows} surviving " +
                    "windows, which cannot be right",
            )
        }
    }

    @Test
    fun `the census funnels are ordered widest first and lose candidates at their binding gate`() {
        val rows = PublishedCensus.CENSUS.rows
        assertTrue(rows.size >= 5, "the census should name at least five patterns, found ${rows.size}")
        rows.zipWithNext().forEach { (wider, narrower) ->
            assertTrue(
                wider.reachedFirstGate >= narrower.reachedFirstGate,
                "rows must be ordered widest funnel first: ${wider.patternName} then " +
                    "${narrower.patternName}",
            )
        }
        rows.forEach { row ->
            assertTrue(row.survivingWindows > 0, "${row.patternName} kept nothing, so it has no row")
            assertTrue(row.lost >= 0, row.label())
            assertTrue(row.bindingGate.isNotBlank(), "${row.patternName} must name its binding gate")
            AdviceLanguage.requireClean(row.label())
        }
    }

    @Test
    fun `the two published funnel stages reproduce exactly`() {
        val symbols = EodHistory.fullHistorySymbols(count = PublishedInsight.SYMBOLS)
        assertEquals(PublishedInsight.SYMBOLS, symbols.size)
        var flagTriples = 0
        var hsWindows = 0
        val topKinds = listOf(PivotKind.HIGH, PivotKind.LOW, PivotKind.HIGH, PivotKind.LOW, PivotKind.HIGH)
        val bottomKinds = topKinds.map { kind ->
            if (kind == PivotKind.HIGH) PivotKind.LOW else PivotKind.HIGH
        }
        for (symbol in symbols) {
            val candles = EodHistory.candles(symbol)
            val snapshot = IndicatorSnapshot.of(candles, null)
            val pivots = ZigzagPivots.compute(
                candles = candles,
                timeframe = Timeframe.D1,
                atr = snapshot.atr,
                barCount = candles.size,
            ).pivots
            val confirmed = com.mia.app.domain.pattern.detectors.chart.ChartContext.of(candles).confirmed(pivots)
            for (i in 0..(confirmed.size - 3)) {
                val window = confirmed.subList(i, i + 3)
                val kinds = window.map { it.kind }
                if (kinds == listOf(PivotKind.LOW, PivotKind.HIGH, PivotKind.LOW) ||
                    kinds == listOf(PivotKind.HIGH, PivotKind.LOW, PivotKind.HIGH)
                ) {
                    flagTriples++
                }
            }
            for (i in 0..(confirmed.size - 5)) {
                val kinds = confirmed.subList(i, i + 5).map { it.kind }
                if (kinds == topKinds || kinds == bottomKinds) hsWindows++
            }
        }
        assertEquals(
            FLAG_POLARITY,
            flagTriples,
            "the bull/bear flag pole-shaped triple count moved to $flagTriples",
        )
        assertEquals(
            HEAD_AND_SHOULDERS_WINDOWS,
            hsWindows,
            "the five-pivot head-and-shoulders window count moved to $hsWindows",
        )
    }

    // ------------------------------------------------------------- copy + compliance

    @Test
    fun `every string this package can put on screen is clean and carries its sample size`() {
        val texts = buildList {
            add(published.provenanceLine())
            add(published.sourceNote)
            add(published.withheldReason ?: "")
            addAll(published.renderTable())
            add(PublishedInsight.REACH.sentences().joinToString(" "))
            add(PublishedCensus.CENSUS.headline())
            add(PublishedCensus.FIXTURE_NOTE)
            PublishedCensus.CENSUS.rows.forEach { add(it.label()) }
            val simulation = RealHistorySimulation.run()
            TrackRecordInsight.fromOutcomes(simulation.outcomes, simulation.candlesBySymbol)?.let { report ->
                report.reach?.let { addAll(it.sentences()) }
                report.calibration?.let { table ->
                    add(table.provenanceLine())
                    addAll(table.renderTable())
                    table.rows.filter { it.samples > 0 }.forEach { add(it.labelAt(H5)) }
                }
            }
        }
        assertTrue(texts.isNotEmpty())
        texts.forEach { text ->
            if (text.isBlank()) return@forEach
            val hits = AdviceLanguage.findForbiddenPhrases(text)
            if (hits.isNotEmpty()) fail("advice-like copy \"$text\": $hits")
            HonestyContract.requireNotProbabilityClaim(text)
        }
        // Every published cell that quotes a percentage quotes its denominator beside it.
        published.rows.forEach { row ->
            HonestyContract.requireHitRateSampleSize(row.labelAt(H5))
        }
    }

    @Test
    fun `the census names patterns the app actually evaluates`() {
        // A row for a pattern the catalogue has retired would be a row about nothing.
        PublishedCensus.CENSUS.rows.forEach { row ->
            assertTrue(
                row.patternName in PatternIds.EVALUATED_CANONICAL_NAMES ||
                    row.patternName in PatternIds.CANONICAL_NAMES,
                "${row.patternName} is not a pattern the app knows about",
            )
        }
    }

    private fun candidateCountsByPattern(): Map<String, Int> {
        val detectors = listOf(
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
        val counts = HashMap<String, Int>()
        for (symbol in EodHistory.fullHistorySymbols(count = PublishedInsight.SYMBOLS)) {
            val candles = EodHistory.candles(symbol)
            val snapshot = IndicatorSnapshot.of(candles, null)
            val pivots = ZigzagPivots.compute(
                candles = candles,
                timeframe = Timeframe.D1,
                atr = snapshot.atr,
                barCount = candles.size,
            ).pivots
            for (detector in detectors) {
                detector.detect(pivots, candles).forEach { match ->
                    counts[match.name] = (counts[match.name] ?: 0) + 1
                }
            }
        }
        return counts
    }

    private companion object {
        /**
         * The app's own horizon; every published finding is measured at it.
         *
         * `OutcomeTrackerPolicy.DEFAULT_HORIZON_BARS`, written out because the two packages are
         * independently compiled and a typo here would silently compare the wrong horizon.
         */
        const val H5 = 5

        /**
         * Points of disagreement tolerated between a published rate and a live recomputation.
         *
         * Tight on purpose. The fixture and the walk-forward are deterministic, so a correct snapshot
         * agrees exactly; the tolerance exists only because the published figures are stored rounded
         * to whole percentage points, which is how they were read off the measurement.
         */
        const val RATE_TOLERANCE = 1.5

        /** docs/VERIFICATION.md §2: the unfiltered rate of the app's real detectors. */
        const val PUBLISHED_UNFILTERED_PCT = 34.0

        /** `DetectorStrictnessCensus`: pole-shaped triples across the 20 large caps. */
        const val FLAG_POLARITY = 297

        /** `DetectorStrictnessCensus`: five-pivot head-and-shoulders-shaped windows. */
        const val HEAD_AND_SHOULDERS_WINDOWS = 257

        /**
         * Matches the thirteen evaluated detectors emit over the census's own 20 symbols in one
         * pass, measured on the fixture: Double Top 34, Double Bottom 29, Ascending Triangle 2,
         * Head & Shoulders 1, Bullish Engulfing 1, Hammer 4, Rising Wedge 9.
         *
         * Recorded because the census's window counts are a *different* measurement and a reader of
         * this file will otherwise assume they are the same one.
         */
        const val SCAN_MATCHES_ON_FIXTURE = 80
    }
}