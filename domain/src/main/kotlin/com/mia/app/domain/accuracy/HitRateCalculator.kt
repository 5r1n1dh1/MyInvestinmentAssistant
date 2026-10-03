package com.mia.app.domain.accuracy

import com.mia.app.domain.compliance.AdviceLanguage
import com.mia.app.domain.compliance.HonestyContract
import com.mia.app.domain.model.AccuracyReport
import com.mia.app.domain.model.HitRate
import com.mia.app.domain.model.PatternOutcome
import java.time.LocalDate

/**
 * Aggregation of tracked outcomes into the hit rates the accuracy screen shows (plan.md §6.1,
 * decision D6, phase P9).
 *
 * The single most important rule here is that **only resolved outcomes count**. A projection
 * whose window is still open is not a sample and is not a miss: it belongs in
 * [AccuracyReport.unresolved]. Counting open windows would let the app shrink its own
 * denominator as patterns went unresolved, and a hit rate computed over a moving denominator is
 * not a measurement.
 *
 * The reporting gate itself is not duplicated here. [HitRate.isReportable] and [HitRate.label]
 * already encode "≥ [com.mia.app.domain.model.MatchScorePolicy.MIN_HIT_RATE_SAMPLES] samples,
 * always with `n`", and every string this object can emit is built on them.
 *
 * Accuracy is reported three ways, because plan.md §6.1 asks for per stock *and* per pattern
 * (D6) and a per-symbol total is what a user asks for first:
 * - [perStockAndPattern] — the cell the user reads next to a pattern (the strictest grouping).
 * - [perPattern] — how a formation behaves across every stock the app has tracked.
 * - [perSymbol] — how one stock behaves across every formation tracked on it.
 */
object HitRateCalculator {

    /**
     * Resolved outcomes only: the ones eligible to be a hit or a miss.
     *
     * A resolved outcome always carries a non-null `hit` (see [OutcomeTracker.evaluate]), so a
     * resolved outcome with no verdict is treated as unresolved rather than counted as a sample.
     */
    fun resolved(outcomes: List<PatternOutcome>): List<PatternOutcome> =
        outcomes.filter { it.resolved && it.hit != null }

    /** Outcomes with no verdict yet. Reported, never averaged in. */
    fun unresolved(outcomes: List<PatternOutcome>): List<PatternOutcome> =
        outcomes.filterNot { it.resolved && it.hit != null }

    /** How many outcomes are still open. */
    fun unresolvedCount(outcomes: List<PatternOutcome>): Int = unresolved(outcomes).size

    /**
     * Per stock *and* per pattern (decision D6): the strictest grouping, and the one shown next
     * to a detected pattern.
     *
     * Sorted by symbol then pattern name so the screen never reshuffles between renders.
     */
    fun perStockAndPattern(outcomes: List<PatternOutcome>): List<HitRate> =
        aggregate(outcomes) { outcome ->
            val key = OutcomeTracker.keyOf(outcome)
            key.symbol to key.patternName
        }.map { group ->
            HitRate(
                symbol = group.primary,
                patternName = group.secondary,
                hits = group.hits,
                samples = group.samples,
            )
        }

    /** Per formation, across every tracked stock. `symbol` is null: this is not stock-specific. */
    fun perPattern(outcomes: List<PatternOutcome>): List<HitRate> =
        aggregate(outcomes) { outcome -> outcome.patternName to null }
            .map { group ->
                HitRate(
                    symbol = null,
                    patternName = group.primary,
                    hits = group.hits,
                    samples = group.samples,
                )
            }

    /** Per stock, across every tracked formation on it. `patternName` is null. */
    fun perSymbol(outcomes: List<PatternOutcome>): List<HitRate> =
        aggregate(outcomes) { outcome -> outcome.symbol to null }
            .map { group ->
                HitRate(
                    symbol = group.primary,
                    patternName = null,
                    hits = group.hits,
                    samples = group.samples,
                )
            }

    /**
     * Everything the accuracy screen needs, in one value.
     *
     * @param today the reporting date. Used as a look-ahead guard: an outcome dated after
     * [today] cannot have happened yet, so aggregating one is a data bug worth failing on
     * rather than silently dropping.
     */
    fun report(
        outcomes: List<PatternOutcome>,
        today: LocalDate = LocalDate.now(),
    ): AccuracyReport {
        require(outcomes.none { it.projectedOn.isAfter(today) }) {
            "cannot report outcomes projected after the reporting date " +
                "($today): the track record must not contain future projections"
        }
        return AccuracyReport(
            perSymbolAndPattern = perStockAndPattern(outcomes),
            perPattern = perPattern(outcomes),
            perSymbol = perSymbol(outcomes),
            totalOutcomes = outcomes.size,
            unresolved = unresolvedCount(outcomes),
        )
    }

    /**
     * Copy for a table row: the scope, then [HitRate.label].
     *
     * Safe by construction. [HitRate.label] withholds anything below
     * [com.mia.app.domain.model.MatchScorePolicy.MIN_HIT_RATE_SAMPLES] samples and always
     * carries `n`; this asserts that rather than trusting it, and runs the result through
     * [AdviceLanguage] and [HonestyContract] before returning, so no screen can grow advice-like
     * or probability-like copy by accident.
     */
    fun summaryText(hitRate: HitRate): String {
        val scope = when {
            hitRate.symbol != null && hitRate.patternName != null ->
                "${hitRate.patternName} on ${hitRate.symbol}"
            hitRate.patternName != null ->
                "${hitRate.patternName} on every tracked stock"
            hitRate.symbol != null ->
                "${hitRate.symbol} across every tracked pattern"
            else ->
                "Every tracked pattern on every tracked stock"
        }
        val label = hitRate.label()
        HonestyContract.requireHitRateSampleSize(label)
        val text = "$scope — $label."
        HonestyContract.requireNotProbabilityClaim(text)
        AdviceLanguage.requireClean(text)
        return text
    }

    /** [summaryText] for a whole table, one entry per row, in the order given. */
    fun summaryTable(hitRates: List<HitRate>): List<String> = hitRates.map { summaryText(it) }

    /**
     * Folds resolved outcomes into `(group, hits, samples)` triples, sorted by group key.
     *
     * Groups with no resolved outcome yet are kept with `samples = 0` rather than dropped: "we
     * track this but have no settled cases" is a fact the accuracy screen should be able to
     * show, and [HitRate.label] already renders it as withheld.
     */
    private fun aggregate(
        outcomes: List<PatternOutcome>,
        group: (PatternOutcome) -> Pair<String, String?>,
    ): List<Group> {
        val hitsByKey = HashMap<Pair<String, String?>, Int>()
        val samplesByKey = HashMap<Pair<String, String?>, Int>()
        outcomes.forEach { outcome ->
            val key = group(outcome)
            // Register the group even when nothing has resolved, so it is visible as withheld.
            hitsByKey.getOrPut(key) { 0 }
            samplesByKey.getOrPut(key) { 0 }
            if (!outcome.resolved || outcome.hit == null) return@forEach
            samplesByKey[key] = samplesByKey.getValue(key) + 1
            if (outcome.hit) hitsByKey[key] = hitsByKey.getValue(key) + 1
        }
        // Key is `(symbol, patternName)` for the strict grouping and `(name, null)` for the two
        // aggregates, so ordering by first then second is symbol-then-pattern in both cases.
        return samplesByKey.keys
            .sortedWith(compareBy({ it.first }, { it.second ?: "" }))
            .map { key ->
                Group(
                    primary = key.first,
                    secondary = key.second,
                    hits = hitsByKey.getValue(key),
                    samples = samplesByKey.getValue(key),
                )
            }
    }

    private data class Group(
        val primary: String,
        val secondary: String?,
        val hits: Int,
        val samples: Int,
    )
}