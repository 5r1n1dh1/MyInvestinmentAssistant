package com.mia.app.domain.model

import java.time.LocalDate

/**
 * Accuracy tracking (plan.md §6.1, decision D6).
 *
 * This is the product's differentiator: the app shows its own track record. Accuracy is
 * tracked per stock AND per pattern, and is only ever shown alongside `n` — never as a
 * bare percentage.
 */

/** A pattern that was projected on a given date, and what actually happened. */
data class PatternOutcome(
    val patternId: Long,
    val symbol: String,
    val patternName: String,
    val timeframe: Timeframe,
    val category: Category,
    val projectedOn: LocalDate,
    val score: Int,
    val direction: Direction,
    /** Measured-move geometry level recorded at projection time. */
    val projectedLevel: Double,
    /** Actual highs/lows observed after the projection window. */
    val actualHigh: Double? = null,
    val actualLow: Double? = null,
    /** True when the measured-move level was reached within the evaluation window. */
    val hit: Boolean? = null,
    /** True once the evaluation window has elapsed and `hit` is final. */
    val resolved: Boolean = false,
    /** Bars we allowed the outcome to play out over. */
    val horizonBars: Int = 5,
)

/** Aggregate accuracy, always carried together with its sample size. */
data class HitRate(
    val symbol: String?,
    val patternName: String?,
    val hits: Int,
    val samples: Int,
) {
    val hitRate: Double? get() = if (samples <= 0) null else hits.toDouble() / samples * 100.0

    /**
     * Below [MatchScorePolicy.MIN_HIT_RATE_SAMPLES] the number is withheld (plan.md §6.1).
     * This is the guard against a 100% hit rate built from 3 samples.
     */
    val isReportable: Boolean get() = samples >= MatchScorePolicy.MIN_HIT_RATE_SAMPLES

    /** Copy for the UI: always includes `n`. */
    fun label(): String = if (isReportable) {
        "Hit rate ${fmt(hitRate!!)}% (n=$samples)"
    } else {
        "Hit rate withheld — $samples/${MatchScorePolicy.MIN_HIT_RATE_SAMPLES} samples"
    }

    private fun fmt(v: Double): String = String.format("%.1f", v)
}

/** Key used to group outcomes: per stock AND per pattern (decision D6). */
data class AccuracyKey(
    val symbol: String,
    val patternName: String,
)

/** What the accuracy screen shows. */
data class AccuracyReport(
    val perSymbolAndPattern: List<HitRate>,
    val perPattern: List<HitRate>,
    val perSymbol: List<HitRate>,
    val totalOutcomes: Int,
    val unresolved: Int,
) {
    val isEmpty: Boolean get() = totalOutcomes == 0

    companion object {
        val EMPTY = AccuracyReport(emptyList(), emptyList(), emptyList(), 0, 0)
    }
}

/**
 * Rules for turning projections into outcomes (plan.md §6.1, §9 D6).
 *
 * "Hit" means the measured-move level was touched inside the horizon — it is a statement
 * about chart geometry, never about profit.
 */
object OutcomeTrackerPolicy {
    /** Bars allowed for the projection to play out. */
    const val DEFAULT_HORIZON_BARS: Int = 5

    /**
     * A long projection is a hit if the high reaches [projectedLevel]; a short projection
     * is a hit if the low falls to it. Evaluated over [horizonBars] bars after
     * [patternOutcome]'s projection date.
     */
    fun isHit(
        patternOutcome: PatternOutcome,
        futureCandles: List<Candle>,
        horizonBars: Int = patternOutcome.horizonBars,
    ): Boolean {
        val window = futureCandles.take(horizonBars)
        if (window.isEmpty()) return false
        return when (patternOutcome.direction) {
            Direction.UP -> window.any { it.high >= patternOutcome.projectedLevel }
            Direction.DOWN -> window.any { it.low <= patternOutcome.projectedLevel }
        }
    }

    fun isResolved(patternOutcome: PatternOutcome, now: LocalDate): Boolean =
        patternOutcome.resolved || patternOutcome.hit != null
}