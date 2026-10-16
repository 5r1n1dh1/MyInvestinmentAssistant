package com.mia.app.domain.pattern

import com.mia.app.domain.accuracy.OutcomeTracker
import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.Match
import com.mia.app.domain.model.MatchScorePolicy
import com.mia.app.domain.model.OutcomeTrackerPolicy
import com.mia.app.domain.model.PatternDetector
import com.mia.app.domain.model.Timeframe
import com.mia.app.domain.technical.IndicatorSnapshot
import com.mia.app.domain.technical.TrendRegime
import java.time.LocalDate

/**
 * One measured-move candidate, exactly as the walk-forward found it — **before** any outcome has
 * been asked of it.
 *
 * The split matters. Collecting candidates and scoring outcomes are two different experiments, and
 * conflating them is how a measurement ends up reporting its own sampling decisions. A candidate
 * knows only what was true on its projection bar; [hit] is asked separately, per horizon.
 *
 * @property verdict what the engine's own gate said about **this** candidate, so a caller can ask
 *   "could the gate be satisfied?" without re-deriving the rule.
 * @property scanVerdict what the engine reported for the **window** the candidate came from, which
 *   is the top-ranked candidate's verdict. Zero means the headline path never fired.
 */
data class ProjectionCandidate(
    val symbol: String,
    val match: Match,
    /** The breakout close — the last bar of the window the pattern was detected on. */
    val lastClose: Double,
    /** That bar's date: what an outcome would be recorded against. */
    val projectedOn: LocalDate,
    /** Bars strictly **after** the projection bar. The projection bar never counts. */
    val future: List<Candle>,
    val verdict: MatchScorePolicy.Verdict,
    val scanVerdict: MatchScorePolicy.Verdict,
) {
    /** `|projectedLevel − lastClose| / lastClose`, or `null` when there is no level to measure. */
    val distance: Double? get() = ProjectionGate.distance(match, lastClose)

    /** The level as a percentage of the close, for the reader-facing calibration table. */
    val distancePercent: Double? get() = distance?.times(100.0)

    /**
     * Whether the measured-move level was touched inside [horizonBars] bars, or `null` when the
     * window has not elapsed in full.
     *
     * `null` rather than `false` for an unfinished window: counting a half-elapsed window as a miss
     * is precisely the inflation `OutcomeTracker` rule 5 exists to forbid (plan.md §6.1).
     */
    fun hit(horizonBars: Int): Boolean? {
        if (horizonBars < 1 || future.size < horizonBars) return null
        val outcome = OutcomeTracker.record(match, symbol, projectedOn, horizonBars)
        return OutcomeTrackerPolicy.isHit(outcome, future, horizonBars)
    }
}

/**
 * A distance bucket. **Cumulative, not exclusive**: `WITHIN_3` holds every candidate whose level
 * sits within 3 % of the close, including the ones inside 1 % and 2 %.
 *
 * Cumulative because the gate is cumulative — it admits "within 5 %" — so a table of disjoint
 * bands would not answer the question the gate actually asks.
 */
enum class DistanceBucket(
    /** Upper edge in percent, or `null` for the open-ended [BEYOND_12]. */
    val maxPercent: Double?,
    val label: String,
) {
    WITHIN_1(1.0, "within 1%"),
    WITHIN_2(2.0, "within 2%"),
    WITHIN_3(3.0, "within 3%"),
    WITHIN_4(4.0, "within 4%"),
    WITHIN_5(5.0, "within 5%"),
    WITHIN_7(7.0, "within 7%"),
    WITHIN_12(12.0, "within 12%"),
    BEYOND_12(null, "beyond 12%"),
    ;

    fun contains(distanceFraction: Double): Boolean {
        val pct = distanceFraction * 100.0
        val edge = maxPercent ?: return pct > (WITHIN_12.maxPercent ?: 12.0)
        return pct <= edge
    }

    companion object {
        /** The tightest bucket containing [distanceFraction]. */
        fun of(distanceFraction: Double): DistanceBucket = entries.first { it.contains(distanceFraction) }
    }
}

/**
 * One row of the calibration table: a bucket (or the whole sample), how many candidates fell in it,
 * and how many of them were reached at each horizon.
 *
 * `hits` and `samples` are inseparable by construction — there is no way to read a rate from this
 * type without its denominator, which is plan.md §6.1's "every hit rate carries its sample size n"
 * rule enforced by the shape rather than by a reviewer's memory.
 */
data class BucketCalibration(
    /** `null` for the all-candidates row, which belongs to no bucket. */
    val bucket: DistanceBucket?,
    val label: String,
    val samples: Int,
    val hitsByHorizon: Map<Int, Int>,
) {
    fun hits(horizonBars: Int): Int = hitsByHorizon[horizonBars] ?: 0

    /** Percentage reached within [horizonBars], or `null` when the row holds no candidates. */
    fun hitRatePct(horizonBars: Int): Double? =
        if (samples <= 0) null else hits(horizonBars) * 100.0 / samples

    /**
     * Below [MatchScorePolicy.MIN_HIT_RATE_SAMPLES] a rate is withheld rather than shown: a 100 %
     * rate built from three candidates is a worse lie than no number at all.
     */
    val isReportable: Boolean get() = samples >= MatchScorePolicy.MIN_HIT_RATE_SAMPLES

    /** `"within 3%: 83% at h5 (n=119)"` — the rate, the horizon and the sample size, always together. */
    fun labelAt(horizonBars: Int): String {
        val rate = hitRatePct(horizonBars) ?: return "$label: no candidates"
        return "$label: ${"%.0f".format(rate)}% at h$horizonBars (n=$samples)"
    }
}

/**
 * The distance-bucket × horizon hit table, computed from real history rather than transcribed.
 *
 * ### Why this lives in production code
 * The table is the evidence [ProjectionGate.DISTANCE_MAX] rests on, and evidence that lives only in
 * a test is evidence that cannot be reused: the accuracy screen (a later wave) is meant to show
 * the device's own version of this table. So the *rule* — how a candidate is bucketed, how a hit is
 * asked, how a rate is withheld — is production code, and only the **history** is injected. The
 * walk-forward feeds it this repository's committed NSE fixture; nothing in this file knows where
 * that fixture came from, which is why the same code can be pointed at stored outcomes later.
 *
 * ### There are no result constants here, deliberately
 * Every rate in this file is computed. A transcribed table is a snapshot that silently rots: the
 * fixture changes, or a detector's geometry is re-tuned, and the published number keeps claiming
 * something the code no longer does. The reproduction test recomputes the table and asserts the
 * findings hold — so drift fails the build instead of reaching a reader.
 *
 * Named [CalibrationTable] rather than [ProjectionCalibration] because the walker below owns that
 * name: one is the data, one is the code that produces it, and a reader should be able to tell them
 * apart at a call site.
 */
data class CalibrationTable(
    val horizons: List<Int>,
    /** Cumulative distance buckets, tightest first. */
    val byBucket: List<BucketCalibration>,
    /** Every candidate at once, i.e. the unfiltered pattern hit rate. */
    val overall: BucketCalibration,
    /**
     * How often a level [baseRateDistancePercent] from the close is touched at each horizon with
     * **no pattern involved** — the control every rate in [byBucket] has to be read against.
     */
    val baseRatesPct: Map<Int, Double>,
    /** Candidate windows visited, including those the detectors found nothing in. */
    val windowsVisited: Int,
    /** Windows whose only candidates were still forming or had no measured move. */
    val incomplete: Int,
    val baseRateDistancePercent: Double,
) {
    val totalSamples: Int get() = overall.samples

    fun bucket(distanceBucket: DistanceBucket): BucketCalibration =
        byBucket.first { it.bucket == distanceBucket }

    /** The no-pattern base rate at [horizonBars], in percent. */
    fun baseRatePct(horizonBars: Int): Double? = baseRatesPct[horizonBars]

    /**
     * The plain-text table, rate and sample size in every cell.
     *
     * Exists so the numbers can be printed or shown without a formatter being written twice, and
     * so no rendering path can drop the `n`.
     */
    fun renderTable(): List<String> {
        val header = buildString {
            append("distance".padEnd(14))
            horizons.forEach { append("h$it".padStart(12)) }
            append("n".padStart(8))
        }
        val rows = (byBucket + overall).map { row ->
            buildString {
                append(row.label.padEnd(14))
                horizons.forEach { h ->
                    val rate = row.hitRatePct(h)
                    val cell = if (rate == null) "-" else "%.0f%%".format(rate)
                    append(" $cell".padStart(11))
                }
                append(" ".repeat(1) + row.samples.toString().padStart(7))
            }
        }
        val base = buildString {
            append("base rate".padEnd(14))
            horizons.forEach { h ->
                val rate = baseRatePct(h)
                append(" ${"%.0f%%".format(rate ?: Double.NaN)}".padStart(11))
            }
            append(" ".repeat(1) + "(no pattern)".padStart(7))
        }
        return listOf(header) + rows + listOf(base)
    }
}

/**
 * The walk-forward that produces [ProjectionCandidate]s and aggregates them into a
 * [CalibrationTable].
 *
 * ### Method, inherited from the score-discrimination measurement
 * - **Walk-forward, never in-sample.** The engine is run on `candles[0..i]` and judged only on bars
 *   *after* `i`. A detector that is allowed to see the future will always look good.
 * - **5-bar stride**, so no two candidates share an outcome bar and the sample is not inflated by
 *   neighbouring windows describing the same move.
 * - **Every horizon is asked of the same candidate** — `isHit` recomputed at 1, 2, 3, 5, 10 and 20
 *   bars — because "83 % within five bars" is a claim about five bars, and quoting it for twenty
 *   would be a different experiment.
 * - **The base rate is computed, not assumed.** It is the same touch test with no pattern and no
 *   distance filter, over the same bars, so the two are comparable.
 */
object ProjectionCalibration {

    /**
     * The horizons the app reasons about: its own four chips (1–5 bars) plus 10 and 20, to see
     * where the curve stops meaning anything.
     */
    val HORIZONS: List<Int> = listOf(1, 2, 3, 5, 10, 20)

    /**
     * The distance the base-rate control is drawn at.
     *
     * A base rate has no meaning without a stated distance — "touched 10 % of the time" is a claim
     * about *some* level, and the claim changes completely with the level. 5 % is the median
     * distance the app's own detectors produce, so the control is drawn at the distance a reader
     * actually sees, not at a convenient round number.
     */
    const val BASE_RATE_DISTANCE_PERCENT: Double = 5.0

    /** Warm-up bars before the first walk-forward window, matching the score measurement. */
    const val WARMUP_BARS: Int = 60

    /** Bars between windows, so no two candidates share an outcome bar. */
    const val STRIDE_BARS: Int = 5

    /** Symbols shorter than this are skipped: too little history to place a formation at all. */
    const val MIN_HISTORY_BARS: Int = 250

    /**
     * Runs the engine window by window and returns every candidate that carries a measurable level.
     *
     * Candidates are **not** filtered on the gate — the table has to include the far ones, or the
     * distance curve would be describing a filter that was already applied.
     *
     * @param detectors the detector list, injected so this object stays free of the detector
     *   packages (`docs/CONTRACTS.md`: the engine never imports them).
     */
    fun walkForward(
        candlesBySymbol: Map<String, List<Candle>>,
        detectors: List<PatternDetector>,
        timeframe: Timeframe = Timeframe.D1,
        warmupBars: Int = WARMUP_BARS,
        strideBars: Int = STRIDE_BARS,
        minHistoryBars: Int = MIN_HISTORY_BARS,
    ): WalkForwardResult {
        val candidates = ArrayList<ProjectionCandidate>()
        var windows = 0
        var incomplete = 0

        for ((symbol, series) in candlesBySymbol.sortedBy { it.key }) {
            if (series.size < minHistoryBars) continue
            var i = warmupBars
            while (i < series.size) {
                val window = series.subList(0, i + 1)
                // The engine computes the indicator snapshot once per scan; capturing it through
                // the documented seam is cheaper than recomputing it here, and guarantees the
                // regime used is the regime the gate actually saw.
                var snapshot: IndicatorSnapshot? = null
                val options = PatternScanOptions(
                    symbol = symbol,
                    isCanonicalScan = false,
                    snapshotFactory = { candles, benchmark ->
                        IndicatorSnapshot.of(candles, benchmark).also { snapshot = it }
                    },
                )
                val result = PatternEngine.run(window, timeframe, detectors, options)
                val regime = options.regimeOverride ?: snapshot?.trendRegime ?: TrendRegime.UNKNOWN
                val lastClose = window.last().close
                windows++

                for (match in result.matches) {
                    val move = match.measuredMove
                    if (move == null || !(move.height > 0.0) || !(move.triggerLevel > 0.0)) {
                        // Still forming, or a candlestick formation with no height to project.
                        // Counted so the census can say how much of the walk these are.
                        incomplete++
                        continue
                    }
                    candidates += ProjectionCandidate(
                        symbol = symbol,
                        match = match,
                        lastClose = lastClose,
                        projectedOn = window.last().date,
                        future = series.subList(i + 1, series.size),
                        // The engine's own gate, asked per candidate: the per-match verdict and the
                        // window-level verdict are the same rule applied to different matches.
                        verdict = PatternEngine.verdictFor(match, timeframe, window.size, lastClose, regime, options),
                        scanVerdict = result.verdict,
                    )
                }
                i += strideBars
            }
        }
        return WalkForwardResult(candidates, windows, incomplete)
    }

    /** What [walkForward] collected, plus the census of what it could not use. */
    data class WalkForwardResult(
        val candidates: List<ProjectionCandidate>,
        val windowsVisited: Int,
        val incomplete: Int,
    )

    /**
     * Buckets [candidates] by measured-move distance and asks each one whether its level was
     * reached at every horizon.
     *
     * **One sample set for every horizon.** A candidate whose longest horizon has not fully
     * elapsed is dropped from the table entirely rather than counted as a miss at the horizons it
     * did survive — otherwise the h20 column would be measured on a different, shorter sample than
     * h1 and the apparent decay with horizon would partly be an artifact of the sample shrinking.
     * `ProjectionCandidate.hit` still answers `null` for an unfinished window; this is where the
     * table chooses to require all of them.
     */
    fun calibrate(
        candidates: List<ProjectionCandidate>,
        horizons: List<Int> = HORIZONS,
        baseRatesPct: Map<Int, Double> = emptyMap(),
        windowsVisited: Int = 0,
        incomplete: Int = 0,
        baseRateDistancePercent: Double = BASE_RATE_DISTANCE_PERCENT,
    ): CalibrationTable {
        val longest = horizons.maxOrNull() ?: 0
        val eligible = candidates.filter { it.future.size >= longest }
        val rows = DistanceBucket.entries.map { bucket ->
            val inBucket = eligible.filter { candidate ->
                candidate.distance?.let { bucket.contains(it) } == true
            }
            bucketRow(bucket.label, bucket, inBucket, horizons)
        }
        return CalibrationTable(
            horizons = horizons,
            byBucket = rows,
            overall = bucketRow("all", null, eligible, horizons),
            baseRatesPct = baseRatesPct,
            windowsVisited = windowsVisited,
            incomplete = incomplete,
            baseRateDistancePercent = baseRateDistancePercent,
        )
    }

    /**
     * How often a level [distancePercent] from the close is touched within each horizon, with **no
     * pattern involved**.
     *
     * Both directions are counted, because "no pattern" does not mean "no direction": a reader
     * asking what a level of this size is worth is asking about an arbitrary side of the market,
     * and a control that quietly assumed the pattern's own direction would flatter the pattern.
     */
    fun baseRatesPct(
        candlesBySymbol: Map<String, List<Candle>>,
        horizons: List<Int> = HORIZONS,
        distancePercent: Double = BASE_RATE_DISTANCE_PERCENT,
        warmupBars: Int = WARMUP_BARS,
        strideBars: Int = STRIDE_BARS,
        minHistoryBars: Int = MIN_HISTORY_BARS,
    ): Map<Int, Double> {
        val maxHorizon = horizons.maxOrNull() ?: return emptyMap()
        val touched = HashMap<Int, Int>()
        val total = HashMap<Int, Int>()
        for (h in horizons) {
            touched[h] = 0
            total[h] = 0
        }

        for (entry in candlesBySymbol.sortedBy { it.key }) {
            val series = entry.value
            if (series.size < minHistoryBars) continue
            var i = warmupBars
            while (i + maxHorizon < series.size) {
                val close = series[i].close
                val future = series.subList(i + 1, i + 1 + maxHorizon)
                for (directionSign in listOf(1.0, -1.0)) {
                    val level = close * (1.0 + distancePercent / 100.0 * directionSign)
                    for (h in horizons) {
                        total[h] = total.getValue(h) + 1
                        val reached = future.take(h).any { bar ->
                            if (directionSign > 0) bar.high >= level else bar.low <= level
                        }
                        if (reached) touched[h] = touched.getValue(h) + 1
                    }
                }
                i += strideBars
            }
        }
        return horizons.associateWith { h ->
            val n = total.getValue(h)
            if (n == 0) 0.0 else touched.getValue(h) * 100.0 / n
        }
    }

    /** Convenience: walk forward, then calibrate, in one call. */
    fun measure(
        candlesBySymbol: Map<String, List<Candle>>,
        detectors: List<PatternDetector>,
        timeframe: Timeframe = Timeframe.D1,
        horizons: List<Int> = HORIZONS,
    ): CalibrationTable {
        val walk = walkForward(candlesBySymbol, detectors, timeframe)
        return calibrate(
            candidates = walk.candidates,
            horizons = horizons,
            baseRatesPct = baseRatesPct(candlesBySymbol, horizons),
            windowsVisited = walk.windowsVisited,
            incomplete = walk.incomplete,
        )
    }

    private fun bucketRow(
        label: String,
        bucket: DistanceBucket?,
        candidates: List<ProjectionCandidate>,
        horizons: List<Int>,
    ): BucketCalibration = BucketCalibration(
        bucket = bucket,
        label = label,
        samples = candidates.size,
        hitsByHorizon = horizons.associateWith { h -> candidates.count { it.hit(h) == true } },
    )
}