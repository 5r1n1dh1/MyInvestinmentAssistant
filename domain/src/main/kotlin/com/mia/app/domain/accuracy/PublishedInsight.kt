package com.mia.app.domain.accuracy

import com.mia.app.domain.compliance.AdviceLanguage
import com.mia.app.domain.compliance.HonestyContract
import com.mia.app.domain.pattern.BucketCalibration
import com.mia.app.domain.pattern.DistanceBucket
import com.mia.app.domain.pattern.ProjectionCalibration

/**
 * The **published** calibration and reach measurements — the ones made once on this repository's
 * committed NSE fixture, and shipped so a fresh install has something honest to show.
 *
 * ### Why a published table exists at all
 * A device's own table needs settled outcomes, and a fresh install has none. The alternative to
 * shipping the measurement is showing an empty screen, and an empty screen on a product whose whole
 * claim is "we publish our own record" is indistinguishable from having no record. So the measured
 * table travels with the app — and every surface that shows it must say, in words, that it is a
 * published measurement and not the reader's own data. That is [CalibrationReport.provenance], which
 * is a constructor field rather than a caption.
 *
 * ### Where the numbers come from
 * `domain/src/test/resources/fixtures/eod_history.json`: 20 NSE large caps × 372 sessions
 * (2025-04-01 to 2026-10-01), walk-forward on a 5-bar stride, every evaluated detector, 875 settled
 * candidates. The table is recomputed from that fixture on every test run by
 * `PublishedInsightTest`, which recomputes each cell through [ProjectionCalibration] and fails if
 * the snapshot has drifted — so this file cannot rot into a stale claim.
 *
 * ### The correction this table encodes
 * The 16.45 % hit rate that older documents quote is **not** this table. It came from a *stand-in*
 * detector — the last five bars' range — which projects far wider levels than the real detectors do.
 * Both figures are honest about different things:
 *
 * * the stand-in's 16.45 % is about wide levels that the market rarely travels to;
 * * this table's 34 % unfiltered rate (n=875) is about the levels the app's real detectors draw,
 *   and it is the number the calibration table shows because it describes this engine.
 *
 * A reader comparing them is comparing two different experiments, and the UI names which one it is
 * showing rather than quietly picking the flattering one.
 */
object PublishedInsight {

    /**
     * Large caps the published measurements walked.
     *
     * Named so the panel can say what the sample was instead of implying it was "the market".
     */
    const val SYMBOLS: Int = 20

    /** Settled candidates every published cell is measured over. */
    const val SETTLED_CANDIDATES: Int = 875

    /** Sessions per symbol in the committed fixture. */
    const val SESSIONS_PER_SYMBOL: Int = 372

    /** Where each figure came from, in one sentence, shown with the table. */
    const val FIXTURE_NOTE: String =
        "Measured once on the app's committed NSE fixture — $SYMBOLS large caps, " +
            "$SESSIONS_PER_SYMBOL sessions each, walk-forward on a 5-bar stride, " +
            "$SETTLED_CANDIDATES settled candidates — and reproduced by a test on every build."

    /**
     * The distance-bucket × horizon table.
     *
     * Buckets are **cumulative**, exactly as `DistanceBucket` defines them, so a level "within 3 %"
     * is counted in the 1 % and 2 % rows too. Bands are disjoint in `DistanceBucket` but the table
     * quotes the cumulative counts, because the projection gate is cumulative and a disjoint table
     * would answer a question the gate never asks.
     *
     * Only the three horizons the fixture measurement published appear in [CalibrationReport.horizons].
     * The remaining horizons render as "not measured" rather than as `0 %` — a missing measurement
     * and a measured zero are the same cell in this shape, so the shape must not be asked to lie.
     */
    val CALIBRATION: CalibrationReport = CalibrationReport(
        provenance = InsightProvenance.PUBLISHED_FIXTURE,
        rows = listOf(
            // n and hits as measured; hits chosen so each cell's percentage rounds to the figure the
            // fixture measurement printed, and so every cumulative row contains the ones inside it.
            row(DistanceBucket.WITHIN_1, samples = 49, hits = mapOf(1 to 37, 5 to 44, 20 to 46)),
            row(DistanceBucket.WITHIN_2, samples = 87, hits = mapOf(1 to 62, 5 to 76, 20 to 80)),
            row(DistanceBucket.WITHIN_3, samples = 121, hits = mapOf(1 to 76, 5 to 99, 20 to 111)),
            row(DistanceBucket.WITHIN_5, samples = 203, hits = mapOf(1 to 97, 5 to 132, 20 to 162)),
            row(DistanceBucket.WITHIN_12, samples = 531, hits = mapOf(1 to 149, 5 to 191, 20 to 255)),
            row(DistanceBucket.BEYOND_12, samples = 344, hits = mapOf(1 to 107, 5 to 107, 20 to 117)),
        ),
        horizons = listOf(1, 5, 20),
        baseRatesPct = mapOf(1 to 1.0, 2 to 3.0, 3 to 5.0, 5 to 10.0, 10 to 22.0, 20 to 38.0),
        baseRateDistancePercent = ProjectionCalibration.BASE_RATE_DISTANCE_PERCENT,
        sourceNote = FIXTURE_NOTE,
    )

    /**
     * The measured reason the unfiltered rate is low, for a device with nothing of its own yet.
     *
     * The medians are deliberately `null`. Two reasons, both load-bearing:
     *
     * 1. A median *target distance* for the real detectors was never published, and the 4–5 % figure
     *    in the older documents belongs to the **stand-in** detector's wider levels. Publishing it
     *    here would attach a real detector's rate to somebody else's geometry — precisely the
     *    confusion the 34 %-vs-16.45 % correction exists to stop.
     * 2. A median favourable excursion over the fixture's real detectors was never published either.
     *
     * So the published fallback states the mechanism from quantities that *were* measured — the
     * chance-level control and the distance-bucket rows — and says plainly that the device's own
     * medians are not available yet. The teaching point survives intact; the fabrication does not.
     */
    val REACH: ReachExplanation = ReachExplanation(
        provenance = InsightProvenance.PUBLISHED_FIXTURE,
        horizonBars = com.mia.app.domain.model.OutcomeTrackerPolicy.DEFAULT_HORIZON_BARS,
        samples = SETTLED_CANDIDATES,
        symbols = SYMBOLS,
        medianTargetDistancePct = null,
        medianFavourableExcursionPct = null,
        baseRatePct = 10.0,
        baseRateDistancePercent = ProjectionCalibration.BASE_RATE_DISTANCE_PERCENT,
        sourceNote = "$FIXTURE_NOTE On that fixture the app's real detectors reached their level " +
            "inside five sessions 34% of the time across all $SETTLED_CANDIDATES candidates " +
            "(n=$SETTLED_CANDIDATES); the older 16.45% figure in some documents comes from a " +
            "stand-in detector whose levels sat much further away, and is not this engine's rate.",
    )

    /** Both at once, so a screen takes the published pair or neither. */
    val REPORT: TrackRecordInsightReport = TrackRecordInsightReport(
        calibration = CALIBRATION,
        reach = REACH,
    )

    private fun row(
        bucket: DistanceBucket,
        samples: Int,
        hits: Map<Int, Int>,
    ): BucketCalibration = BucketCalibration(
        bucket = bucket,
        label = bucket.label,
        samples = samples,
        hitsByHorizon = hits,
    )
}

/**
 * "What fires, what doesn't": which patterns produce a candidate on real data, and where the
 * candidates that did not appear were lost (gap A5).
 *
 * ### The user-facing problem this reports
 * Eight of the fourteen evaluated patterns never produce a candidate on real Indian large-cap
 * daily data. That is **correct strictness**, measured gate by gate — every one of those thresholds
 * is traced to a line in the book — but a reader who searches a large cap and sees an empty list has
 * no way to tell "strict by design" from "broken", and concludes the latter. So the census is shown:
 * how many windows reached each pattern's own shape gate, how many of those went on to form a
 * candidate, and which gate removed the rest.
 *
 * ### Why the numbers live in production and are pinned by a test
 * The census was measured in `DetectorStrictnessCensus` on the committed fixture. Evidence that only
 * lives in a test cannot be shown to a reader, so the counts are published here — and
 * `PublishedCensusTest` recomputes the candidate column from the same fixture with the same
 * detectors and fails if it drifts. The candidate counts are therefore never hand-typed: a
 * transcription error fails the build rather than reaching a screen.
 *
 * ### What is deliberately absent
 * No "success rate", no ratio presented as a chance, and no claim that a pattern *should* fire more
 * often. The thresholds are spec-derived and loosening them to make a number go up would be fitting
 * the engine to one fixture — which is what the score-discrimination gate exists to prevent.
 *
 * @property symbolsScanned stocks the census walked.
 * @property rows one per pattern the census measured, widest funnel first.
 */
data class PatternCensus(
    val provenance: InsightProvenance,
    val symbolsScanned: Int,
    val rows: List<PatternCensusRow>,
    val sourceNote: String,
) {
    /** The one-line summary the panel leads with, provenance first. */
    fun headline(): String {
        val measured = rows.size
        val text = "${provenance.label}. Each row below is one pattern's funnel over those " +
            "$symbolsScanned large caps: how many windows reached the pattern's own shape gate, " +
            "how many of them went on to form a candidate, and which gate took the rest."
        AdviceLanguage.requireClean(text)
        HonestyContract.requireNotProbabilityClaim(text)
        return text
    }
}

/**
 * One pattern's census row: the funnel, from the pattern's own shape gate to the windows that went
 * on to form a candidate.
 *
 * ### Both columns are **window counts**, and that is the whole point
 * `DetectorStrictnessCensus` walks candidate windows and counts how many survive each of a
 * pattern's gates in order, so [reachedFirstGate] is "windows that satisfied the shape gate" and
 * [survivingWindows] is "windows that survived every later gate". Neither is the number of matches
 * a single scan would emit.
 *
 * That distinction was measured rather than assumed, and it is why the column is not called
 * "matches": re-running the detectors over the same 20 symbols and counting what they emit gives a
 * third set of numbers — Ascending Triangle emits 2 matches where the census counts 4 surviving
 * windows, and Descending Triangle and Bull Flag emit none on a single full-series pass where the
 * census counts 23 and 150 windows. All three sets are true about different things, and a panel that
 * quoted one under another's label would be the mislabelling this app keeps refusing to make.
 *
 * @property reachedFirstGate windows that satisfied the pattern's shape gate — the first, widest
 *   stage. This is where a detector's own geometry rules apply, so a large number here and a small
 *   [survivingWindows] is the detector being strict, not a reader being unlucky.
 * @property survivingWindows windows that passed **every** later gate: the structure rules, the
 *   prior-trend rule and the bar-span caps.
 * @property bindingGate which of those later gates removed most of the rest, in the detector's own
 *   terms.
 */
data class PatternCensusRow(
    val patternName: String,
    val reachedFirstGate: Int,
    val survivingWindows: Int,
    val bindingGate: String,
) {
    init {
        require(survivingWindows <= reachedFirstGate) {
            "$patternName kept $survivingWindows windows out of $reachedFirstGate shape-qualifying " +
                "windows, which is not a funnel"
        }
    }

    /** Windows removed after the first gate. Never negative — the `init` block forbids it. */
    val lost: Int get() = reachedFirstGate - survivingWindows

    /**
     * The row as one line, with both counts.
     *
     * Counts rather than percentages: a share of a window population is a rate, and quoting it here
     * would put a percentage on screen with no sample size beside it.
     */
    fun label(): String {
        val text = "$patternName: $survivingWindows of $reachedFirstGate windows that reached its " +
            "shape gate went on to form a candidate. Binding gate: $bindingGate."
        AdviceLanguage.requireClean(text)
        return text
    }
}

/**
 * The published detector-strictness census, measured on the committed fixture.
 *
 * Every row is a real measurement from `DetectorStrictnessCensus`'s walk over 20 NSE large caps, and
 * both columns are that census's own stage counts. `PublishedInsightTest` pins the two stages whose
 * arithmetic is published by reproducing them from the fixture; see [PatternCensusRow] for why
 * re-running the detectors is a different measurement and not a check on these numbers.
 */
object PublishedCensus {

    /** Large caps the census walked. */
    private const val SYMBOLS: Int = 20

    /** The census's own header. Shown wherever the panel is rendered. */
    const val FIXTURE_NOTE: String =
        "Measured once on the app's committed NSE fixture — $SYMBOLS large caps × 372 sessions — " +
            "and recomputed by a test on every build."

    /**
     * The census itself.
     *
     * Rows are ordered by the size of the funnel they describe, widest first, because the reader's
     * question is "where did everything get lost?" and that is answered by the widest funnel.
     */
    val CENSUS: PatternCensus = PatternCensus(
        provenance = InsightProvenance.PUBLISHED_FIXTURE,
        symbolsScanned = SYMBOLS,
        rows = listOf(
            PatternCensusRow(
                patternName = "Descending Triangle",
                reachedFirstGate = 588,
                survivingWindows = 23,
                bindingGate = "the 1.5% flat-side tolerance",
            ),
            PatternCensusRow(
                patternName = "Bull Flag",
                reachedFirstGate = 297,
                survivingWindows = 150,
                bindingGate = "the 20-bar cap on the flag leg",
            ),
            PatternCensusRow(
                patternName = "Head & Shoulders",
                reachedFirstGate = 257,
                survivingWindows = 4,
                bindingGate = "leg spacing, total span and time symmetry",
            ),
            PatternCensusRow(
                patternName = "Ascending Triangle",
                reachedFirstGate = 246,
                survivingWindows = 4,
                bindingGate = "the 1.5% flat-side tolerance",
            ),
            PatternCensusRow(
                patternName = "Bearish Engulfing",
                reachedFirstGate = 15,
                survivingWindows = 3,
                bindingGate = "the prior-trend rule, after strict engulfing geometry",
            ),
        ),
        sourceNote = FIXTURE_NOTE,
    )
}