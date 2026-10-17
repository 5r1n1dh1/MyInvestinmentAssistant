package com.mia.app.domain.accuracy

import com.mia.app.domain.compliance.AdviceLanguage
import com.mia.app.domain.compliance.HonestyContract
import com.mia.app.domain.model.AccuracyReport
import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.Direction
import com.mia.app.domain.model.HitRate
import com.mia.app.domain.model.MatchScorePolicy
import com.mia.app.domain.model.OutcomeTrackerPolicy
import com.mia.app.domain.model.PatternOutcome
import com.mia.app.domain.pattern.BucketCalibration
import com.mia.app.domain.pattern.DistanceBucket
import com.mia.app.domain.pattern.ProjectionCalibration
import kotlin.math.abs

/**
 * Where a number on the Accuracy tab came from. **This is the single most important field in the
 * package**: a reader who cannot tell a published research figure from their own track record will
 * read one as the other, and that is the one mistake this app cannot make (plan.md §1.4).
 *
 * The distinction is enforced by the type rather than by a caption: a [CalibrationReport] or a
 * [ReachExplanation] **cannot be constructed without one**, so there is no code path that renders a
 * figure without declaring where it came from.
 */
enum class InsightProvenance(
    /** The line shown above the figure. Short, factual, and impossible to miss. */
    val label: String,
) {
    /** Computed from outcomes this device stored itself. The reader's own record. */
    DEVICE_STORED("Computed on this device from its own stored outcomes"),

    /**
     * Measured once on the repository's committed NSE fixture and shipped as the fallback.
     *
     * Not the reader's data, and never presented as such: it is a published measurement of the
     * engine on 20 large caps, which is evidence about the engine and evidence about nothing at all
     * about the reader's own outcomes.
     */
    PUBLISHED_FIXTURE(
        "Published measurement — the app's own 20-stock NSE fixture, not this device's record",
    ),
    ;

    /** `true` for the published fallback, so a screen can badge it. */
    val isPublished: Boolean get() = this == PUBLISHED_FIXTURE
}

/**
 * The distance-bucket calibration table the Accuracy tab renders.
 *
 * ### What it is for
 * A hit rate on its own is uninterpretable, because a hit is a level being *reached* and how often
 * that happens is dominated by how far the level sits from price (plan.md §6.1; measured in
 * `ProjectionCalibrationTest`). This table is the control: it shows the rate at every distance band,
 * each with its own `n`, next to the chance-level touch rate for a level of the same size.
 *
 * ### Why it is built from `BucketCalibration` rather than its own row type
 * `BucketCalibration` already carries `samples`, `hitsByHorizon` and `isReportable`, so plan.md
 * §6.1's "every rate carries `n`, and nothing below [MatchScorePolicy.MIN_HIT_RATE_SAMPLES] is
 * reportable" cannot be bypassed by a caller that forgets it: the withholding rule lives in the row,
 * not in the screen. A new row type here would be a second place for that rule to live.
 *
 * @property provenance declared, never defaulted. See [InsightProvenance].
 * @property rows one per [DistanceBucket], **cumulative and tightest first** — a level inside 3 %
 *   is counted in the 1 % and 2 % bands too. Same convention as the published table and as
 *   `ProjectionCalibration.calibrate`, so the two are read side by side without translation. The
 *   closed bands therefore nest; `BEYOND_12` is the open-ended remainder, so the last row is the
 *   complement of `WITHIN_12` rather than a superset of it. `PublishedInsightTest` pins both, because
 *   a disjoint-banded table is the reading that makes a 4 % band look better than a 5 % band when
 *   nothing of the sort happened.
 * @property horizons the horizons with a measurement. A horizon absent from this list was never
 *   measured and renders as "not measured" — never as `0 %`.
 * @property baseRatesPct chance-level touch rate **at the same level size**, per horizon. Partial by
 *   design: only the sizes that were actually measured appear.
 * @property sourceNote one sentence naming the data, shown under the table.
 */
data class CalibrationReport(
    val provenance: InsightProvenance,
    val rows: List<BucketCalibration>,
    val horizons: List<Int>,
    val baseRatesPct: Map<Int, Double>,
    val baseRateDistancePercent: Double,
    val sourceNote: String,
) {
    /** The row for [bucket], or `null` when this report does not measure it. */
    fun row(bucket: DistanceBucket): BucketCalibration? = rows.firstOrNull { it.bucket == bucket }

    /** The chance-level rate at [horizonBars] and the stated level size, or `null` if unmeasured. */
    fun baseRatePct(horizonBars: Int): Double? = baseRatesPct[horizonBars]

    /** `true` when at least one row clears the reporting gate. */
    val hasReportableCell: Boolean get() = rows.any { it.isReportable }

    /**
     * Why no cell may be quoted yet, or `null` when one may be.
     *
     * A permanent part of the type rather than a UI concern: a table whose every cell is below the
     * gate has to say so, because an empty table and a hidden table are the same thing to a reader.
     */
    val withheldReason: String?
        get() = if (hasReportableCell) {
            null
        } else {
            "Every distance band is below the ${MatchScorePolicy.MIN_HIT_RATE_SAMPLES}-sample " +
                "reporting gate, so no cell of this table is quoted. The counts are shown instead."
        }

    /**
     * One line stating where these numbers come from, for the reader and for the honesty contract.
     *
     * Runs every sentence through [AdviceLanguage] and [HonestyContract] before returning, so a
     * screen cannot grow advice-like or probability-like copy by adding a caption here.
     */
    fun provenanceLine(): String {
        val text = "${provenance.label}. $sourceNote"
        AdviceLanguage.requireClean(text)
        HonestyContract.requireNotProbabilityClaim(text)
        return text
    }

    /** The plain-text table, `n` in every cell. Cheap enough to print from any run. */
    fun renderTable(): List<String> {
        val header = buildString {
            append("level distance".padEnd(18))
            horizons.forEach { append("h$it".padStart(9)) }
            append("n".padStart(8))
        }
        val body = rows.map { row ->
            buildString {
                append(row.label.padEnd(18))
                horizons.forEach { h ->
                    val cell = if (!horizons.contains(h) || row.samples <= 0) {
                        "not measured"
                    } else {
                        "%.0f%%".format(row.hitRatePct(h) ?: Double.NaN)
                    }
                    append(" $cell".padStart(12))
                }
                append(row.samples.toString().padStart(8))
            }
        }
        val base = buildString {
            append("chance level".padEnd(18))
            horizons.forEach { h ->
                val rate = baseRatePct(h)
                append(" ${if (rate == null) "not measured" else "%.0f%%".format(rate)}".padStart(12))
            }
            append(" $baseRateDistancePercent% away".padStart(8))
        }
        return listOf(header) + body + listOf(base)
    }
}

/**
 * Why a hit rate is the size it is — the measured reason, with the sample size attached (plan.md
 * §6.1; gap A6).
 *
 * ### The claim this type exists to make explainable
 * A hit is a chart event: the measured-move level was reached inside the horizon. Nothing more. So
 * the rate is a function of **where the level sits** — and the app's levels sit a long way from
 * price, because a textbook formation is a *large* formation and a large formation projects a far
 * level. Measured on the fixture: within 3 % of the close, 82 % (n=121); beyond 12 %, 31 % (n=344);
 * and with **no pattern at all** a level 5 % away is touched 10 % of the time inside five sessions.
 * A reader shown only the unfiltered rate would read it as incompetence rather than as geometry.
 *
 * ### Why the medians are `Double?`
 * Because they are frequently unmeasurable. The device's own medians need the breakout close, which
 * is only recoverable from stored candles; and the fixture's median target distance was never
 * published as a figure. `null` renders as "not measured", which is the truth, where a plausible
 * number would be a fabrication.
 */
data class ReachExplanation(
    val provenance: InsightProvenance,
    /** Bars each measurement is taken over. `OutcomeTrackerPolicy.DEFAULT_HORIZON_BARS` in practice. */
    val horizonBars: Int,
    /** Settled outcomes the medians are computed over. `0` when nothing is measurable yet. */
    val samples: Int,
    /** Symbols those outcomes span. */
    val symbols: Int,
    /** Median `|level − breakout close| / close`, in percent, over [samples]. */
    val medianTargetDistancePct: Double?,
    /**
     * Median favourable excursion over [horizonBars] bars, in percent, in each outcome's own
     * direction. The quantity a level has to beat to be "reached".
     */
    val medianFavourableExcursionPct: Double?,
    /**
     * Chance-level touch rate at [ReachExplanation.baseRateDistancePercent] and [horizonBars], with
     * no pattern involved, over the same bars. `null` when it was not computed.
     */
    val baseRatePct: Double?,
    /** The level size [baseRatePct] was measured at. */
    val baseRateDistancePercent: Double,
    val sourceNote: String,
) {
    /**
     * `true` when this device's own record can explain the rate numerically.
     *
     * When `false` the screen must fall back to the published table rather than implying it has a
     * measurement — which is the whole distinction between the two provenances.
     */
    val hasDeviceMeasurement: Boolean
        get() = provenance == InsightProvenance.DEVICE_STORED &&
            medianTargetDistancePct != null &&
            medianFavourableExcursionPct != null &&
            samples > 0

    /**
     * The reader-facing sentences, in order: where the numbers come from, the measured distances,
     * and the chance control they have to be read against.
     *
     * Every sentence that quotes a rate carries `n`, and none of them advises. The wording is
     * descriptive on purpose — it names a mechanism and stops there, because a sentence that went on
     * to say what the reader should do would be advice (plan.md §1.4).
     */
    fun sentences(): List<String> {
        val out = mutableListOf<String>()
        out += "${provenance.label}."
        val distance = medianTargetDistancePct
        val excursion = medianFavourableExcursionPct
        if (distance != null && excursion != null) {
            out += "The ${samples} levels this device has settled sat a median " +
                "${fmt(distance)}% from the close. Over the same $horizonBars sessions, the " +
                "furthest the price travelled in the projected direction was a median " +
                "${fmt(excursion)}%."
            out += "A level is only counted as reached if price got all the way to it, so a level " +
                "further away than the market usually travels is textbook-correct and still " +
                "unreached. That is geometry, not a score."
        } else {
            out += "This device has not settled enough projections with stored bars to measure " +
                "that distance yet, so no median is quoted here."
        }
        val control = baseRatePct
        if (control != null) {
            out += "For scale: with no pattern involved at all, a level " +
                "${fmt(baseRateDistancePercent)}% from the close is touched " +
                "${"%.0f".format(control)}% of the time within $horizonBars sessions."
        }
        out += sourceNote
        val text = out.joinToString(" ")
        AdviceLanguage.requireClean(text)
        HonestyContract.requireNotProbabilityClaim(text)
        return out
    }

    private fun fmt(value: Double): String = "%.1f".format(value)
}

/**
 * Builds both insights from **stored outcomes and stored candles**.
 *
 * ### Why the device's own numbers are computed here rather than shipped
 * A published table is evidence about the engine on 20 large caps. The reader's question is about
 * their own stock, and only their own settled outcomes can answer it. So the rule is production
 * code and the *history* is injected — exactly the arrangement `ProjectionCalibration` uses for the
 * fixture walk-forward, so the two can be compared like for like instead of by eye.
 *
 * ### The one derived quantity everything rests on
 * [settledSamples] locates the bar a projection was made on, by date, symbol and timeframe. From its
 * close come the target distance and the favourable excursion, which is why both figures are
 * unavailable (rather than approximate) when that bar is not stored: an outcome whose projection bar
 * has been pruned is counted as unmeasured and left out, never guessed at.
 */
object TrackRecordInsight {

    /** The app's own horizon. Every published finding in the docs uses this one. */
    const val HORIZON_BARS: Int = OutcomeTrackerPolicy.DEFAULT_HORIZON_BARS

    /**
     * The calibration table for this device's own settled outcomes.
     *
     * Returns `null` — meaning "nothing to report yet", never a zero-filled table — when no settled
     * outcome has both a level and a stored projection bar. The screen then shows the published
     * table with its provenance label.
     */
    fun calibrationFromOutcomes(
        outcomes: List<PatternOutcome>,
        candlesBySymbol: Map<String, List<Candle>>,
        horizonBars: Int = HORIZON_BARS,
    ): CalibrationReport? {
        val settled = settledSamples(outcomes, candlesBySymbol, horizonBars)
        if (settled.isEmpty()) return null
        return calibrationOf(settled, candlesBySymbol)
    }

    /**
     * The measured reason this device's rate is what it is.
     *
     * `null` when nothing is measurable yet. A published fallback exists ([PublishedInsight.REACH])
     * and is labelled as published, so the screen never has to distinguish "no measurement" from
     * "a measurement on this device".
     */
    fun reachFromOutcomes(
        outcomes: List<PatternOutcome>,
        candlesBySymbol: Map<String, List<Candle>>,
        horizonBars: Int = HORIZON_BARS,
    ): ReachExplanation? {
        val settled = settledSamples(outcomes, candlesBySymbol, horizonBars)
        if (settled.isEmpty()) return null
        return reachOf(settled, candlesBySymbol)
    }

    /** Both insights at once, so a screen takes one pass over the stored bars. */
    fun fromOutcomes(
        outcomes: List<PatternOutcome>,
        candlesBySymbol: Map<String, List<Candle>>,
        horizonBars: Int = HORIZON_BARS,
    ): TrackRecordInsightReport? {
        val settled = settledSamples(outcomes, candlesBySymbol, horizonBars)
        if (settled.isEmpty()) return null
        return TrackRecordInsightReport(
            calibration = calibrationOf(settled, candlesBySymbol),
            reach = reachOf(settled, candlesBySymbol),
        )
    }

    private fun calibrationOf(
        settled: List<SettledSample>,
        candlesBySymbol: Map<String, List<Candle>>,
    ): CalibrationReport {
        val horizons = settled.map { it.horizonBars }.distinct().sorted()
        // Cumulative, exactly as the published table and `ProjectionCalibration.calibrate` build
        // theirs: a level inside 3 % is also counted in the 1 % and 2 % bands. Two reasons, and the
        // first is the reader's. A reader comparing two bands is comparing two populations, and
        // disjoint bands invite exactly the false reading that a 4 % band does better than a 5 %
        // band — when what actually happened is that 252 levels sat in the 4 % band and 165 in the
        // 5 % one. The second is comparability: one shape for both provenances, so the device's own
        // table and the published one can be read side by side without translation.
        val rows = DistanceBucket.entries.map { bucket ->
            val inBucket = settled.filter { candidate -> bucket.contains(candidate.distanceFraction) }
            BucketCalibration(
                bucket = bucket,
                label = bucket.label,
                samples = inBucket.size,
                hitsByHorizon = horizons.associateWith { h ->
                    inBucket.count { it.horizonBars == h && it.hit }
                },
            )
        }
        val distancePct = medianOf(settled.map { it.distanceFraction * 100.0 })
        return CalibrationReport(
            provenance = InsightProvenance.DEVICE_STORED,
            rows = rows,
            horizons = horizons,
            // The control is redrawn at the distance this device's own levels actually sit at,
            // because a base rate is a function of the level's size (see ProjectionCalibration's
            // KDoc) — quoting the 5 % control beside a 2 % median would flatter or damn the figure
            // for the wrong reason.
            baseRatesPct = distancePct?.let { distance ->
                ProjectionCalibration.baseRatesPct(
                    candlesBySymbol = candlesBySymbol,
                    horizons = horizons,
                    distancePercent = distance,
                )
            }.orEmpty(),
            baseRateDistancePercent = distancePct ?: ProjectionCalibration.BASE_RATE_DISTANCE_PERCENT,
            sourceNote = deviceSourceNote(settled),
        )
    }

    private fun reachOf(
        settled: List<SettledSample>,
        candlesBySymbol: Map<String, List<Candle>>,
    ): ReachExplanation? {
        val horizonBars = settled.first().horizonBars
        val medianDistance = medianOf(settled.map { it.distanceFraction * 100.0 }) ?: return null
        val baseRate = ProjectionCalibration.baseRatesPct(
            candlesBySymbol = candlesBySymbol,
            horizons = listOf(horizonBars),
            distancePercent = medianDistance,
        )[horizonBars]
        return ReachExplanation(
            provenance = InsightProvenance.DEVICE_STORED,
            horizonBars = horizonBars,
            samples = settled.size,
            symbols = settled.map { it.symbol }.distinct().size,
            medianTargetDistancePct = medianDistance,
            medianFavourableExcursionPct = medianOf(settled.mapNotNull { it.favourableExcursionPct }),
            baseRatePct = baseRate,
            baseRateDistancePercent = medianDistance,
            sourceNote = deviceSourceNote(settled),
        )
    }

    /**
     * How many more settled outcomes a rate needs before it may be quoted at all.
     *
     * plan.md §6.1's gate is a floor on *evidence*, so the reader is entitled to know how far away
     * the floor is. Returns `0` for a row that already clears it — "0 more" is the answer, and
     * suppressing it would be the one reading that suggests something is still missing.
     */
    fun samplesUntilReportable(samples: Int): Int =
        (MatchScorePolicy.MIN_HIT_RATE_SAMPLES - samples).coerceAtLeast(0)

    /** [samplesUntilReportable] for a reported cell. */
    fun samplesUntilReportable(hitRate: HitRate): Int = samplesUntilReportable(hitRate.samples)

    /**
     * The cell closest to becoming reportable — fewest further outcomes needed, among the cells that
     * are not there yet.
     *
     * Ordered per stock **and** pattern first, because that is the strictest grouping and therefore
     * the one furthest from the gate; a reader told "6 more" should be told it about the number they
     * most want. Ties are broken by scope then by sample count so the sentence never wobbles between
     * renders.
     */
    fun nearestToReportable(report: AccuracyReport): HitRate? =
        report.perSymbolAndPattern.plus(report.perPattern).plus(report.perSymbol)
            .filterNot { it.isReportable }
            .minWithOrNull(
                compareBy<HitRate> { samplesUntilReportable(it.samples) }
                    .thenBy { it.symbol ?: it.patternName ?: "" }
                    .thenByDescending { it.samples },
            )

    /** `"6 more settled outcomes would make this reportable (24 of 30 now)."` */
    fun samplesUntilReportableText(hitRate: HitRate): String {
        val needed = samplesUntilReportable(hitRate)
        val text = if (needed == 0) {
            "Reportable now: ${hitRate.samples} settled outcomes, at or above the " +
                "${MatchScorePolicy.MIN_HIT_RATE_SAMPLES}-sample gate."
        } else {
            "$needed more settled ${if (needed == 1) "outcome" else "outcomes"} would make this " +
                "reportable (${hitRate.samples} of ${MatchScorePolicy.MIN_HIT_RATE_SAMPLES} now)."
        }
        AdviceLanguage.requireClean(text)
        HonestyContract.requireNotProbabilityClaim(text)
        return text
    }

    // ----------------------------------------------------------------------- internals

    /**
     * One settled outcome reduced to the two quantities the insights need.
     *
     * @property distanceFraction `|level − close| / close`: how far the reader would have to be right.
     * @property favourableExcursionPct how far price actually travelled in the projected direction
     *   over the horizon, so the two are always the same quantity in the same direction.
     */
    private data class SettledSample(
        val symbol: String,
        val distanceFraction: Double,
        val favourableExcursionPct: Double?,
        val horizonBars: Int,
        val hit: Boolean,
    )

    /**
     * Every resolved outcome that can be measured, or `null` from this list when its projection bar
     * is not stored.
     *
     * `hit` is required: an outcome that resolved to `false` is as much a measurement as one that
     * resolved to `true`, and dropping the misses would produce a table describing a perfect engine.
     */
    private fun settledSamples(
        outcomes: List<PatternOutcome>,
        candlesBySymbol: Map<String, List<Candle>>,
        horizonBars: Int,
    ): List<SettledSample> = outcomes.mapNotNull { outcome ->
        if (!outcome.resolved || outcome.hit == null) return@mapNotNull null
        if (outcome.horizonBars != horizonBars) return@mapNotNull null
        val candles = candlesBySymbol[outcome.symbol].orEmpty()
        val index = candles.indexOfFirst { it.date == outcome.projectedOn }
        if (index < 0) return@mapNotNull null
        val close = candles[index].close
        if (!close.isFinite() || close <= 0.0) return@mapNotNull null
        val distance = abs(outcome.projectedLevel - close) / close
        if (!distance.isFinite()) return@mapNotNull null
        val window = candles.drop(index + 1).take(horizonBars)
        SettledSample(
            symbol = outcome.symbol,
            distanceFraction = distance,
            favourableExcursionPct = excursionPct(outcome, window, close),
            horizonBars = horizonBars,
            hit = outcome.hit == true,
        )
    }

    /**
     * How far price travelled in the outcome's own direction over the window, in percent.
     *
     * Direction-aware because "the market moved 2 %" is not the same claim as "the market moved 2 %
     * the way this projection needed it to". Clamped at zero so a window that moved against the
     * projection reads as "it did not get there" rather than as a negative reach.
     */
    private fun excursionPct(
        outcome: PatternOutcome,
        window: List<Candle>,
        close: Double,
    ): Double? {
        if (window.isEmpty()) return null
        return when (outcome.direction) {
            Direction.UP -> (window.maxOf { it.high } / close - 1.0).coerceAtLeast(0.0) * 100.0
            Direction.DOWN -> (1.0 - window.minOf { it.low } / close).coerceAtLeast(0.0) * 100.0
        }
    }

    /** Median of [values], or `null` for an empty list. Midpoint of the middle pair when even. */
    private fun medianOf(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
    }

    private fun deviceSourceNote(samples: List<SettledSample>): String =
        "Measured on this device over ${samples.size} settled " +
            "${if (samples.size == 1) "outcome" else "outcomes"} across " +
            "${samples.map { it.symbol }.distinct().size} " +
            "${if (samples.map { it.symbol }.distinct().size == 1) "stock" else "stocks"}, each " +
            "judged only after its full window of sessions had elapsed."
}

/** Both insights in one value, so a screen takes one pass over the stored bars. */
data class TrackRecordInsightReport(
    val calibration: CalibrationReport?,
    val reach: ReachExplanation?,
)