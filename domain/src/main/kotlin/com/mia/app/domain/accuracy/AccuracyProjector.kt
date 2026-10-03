package com.mia.app.domain.accuracy

import com.mia.app.domain.compliance.AdviceLanguage
import com.mia.app.domain.compliance.HonestyContract
import com.mia.app.domain.model.HitRate
import com.mia.app.domain.model.Match
import com.mia.app.domain.model.MatchScorePolicy
import com.mia.app.domain.model.PatternOutcome
import java.time.LocalDate

/**
 * What the app knows about itself before it shows a fresh pattern (plan.md §6.1, phase P9).
 *
 * A track record is only worth displaying if it is used honestly. The only honest use of one is
 * as **past fact**: "on this stock, this formation's measured-move level was reached in 18 of 31
 * previous cases". It is never a forecast — plan.md §6.1's control test found every directional
 * signal sitting on a 50% base rate, so any wording that turns a track record into a likelihood
 * of what happens next would be a lie told by arithmetic.
 *
 * Three deliberate choices follow from that:
 * - The per-stock sample (decision D6: per stock *and* per pattern) is the headline, because a
 *   30-case sample on one stock and this one formation is what the user is about to look at.
 * - Below [MatchScorePolicy.MIN_HIT_RATE_SAMPLES] cases the number is **withheld** and the count
 *   is shown as `(n of 30)` instead. The threshold is read from the shared policy rather than
 *   restated, so it cannot drift.
 * - A pool of the same formation across other stocks is offered as [comparableHitRate] — again
 *   behind the same gate — so a thin per-stock cell can still say something true about geometry
 *   without pretending it describes this stock.
 *
 * Every string this object emits is checked with [AdviceLanguage] and [HonestyContract] before it
 * leaves, so no screen can grow "80% chance"-style copy here.
 */
data class TrackRecordContext(
    /** Resolved past cases of this formation on this stock. */
    val priorHits: Int,

    /** Resolved past cases of this formation on this stock. `priorSamples >= priorHits`. */
    val priorSamples: Int,

    /** Per-stock hit rate in percent, or `null` while below the reporting threshold. */
    val hitRate: Double?,

    /** Resolved cases of this formation across every tracked stock. */
    val comparableOutcomes: Int,

    /** Cross-stock hit rate in percent, or `null` while below the reporting threshold. */
    val comparableHitRate: Double?,

    /** User-facing sentence(s). Never a probability of future success. */
    val evidence: String,
) {
    /** True when [hitRate] clears the reporting gate and may be shown as a percentage. */
    val isReportable: Boolean get() = hitRate != null

    /** `n` for the per-stock figure, shown next to it whenever it is shown at all. */
    val sampleSize: Int get() = priorSamples
}

/**
 * Builds the [TrackRecordContext] shown next to a detected pattern.
 *
 * Reads only from tracked history: no candles, no IO, no clock beyond the injected [asOf].
 */
object AccuracyProjector {

    /** Copy used whenever the sample is too thin to quote a percentage. */
    const val HELD_PREFIX: String = "Not enough tracked cases yet"

    /**
     * Attached to every reported figure. Keeps the sentence from reading as a forecast and
     * reminds the reader what a hit is (plan.md §1.4 rule 1, §6.1).
     */
    const val NOT_A_PREDICTION: String =
        "Past outcomes are chart events, not profits, and do not predict what happens next."

    /**
     * The track record to display for [match] on [symbol], given everything tracked in [history].
     *
     * Only resolved outcomes whose projection date is strictly before [asOf] are counted: an
     * outcome for the current bar is not a *previous* case, and one dated in the future is a
     * look-ahead bug.
     */
    fun context(
        match: Match,
        symbol: String,
        history: List<PatternOutcome>,
        asOf: LocalDate = LocalDate.now(),
    ): TrackRecordContext {
        return context(symbol, match.name, history, asOf)
    }
    fun context(
        symbol: String,
        patternName: String,
        history: List<PatternOutcome>,
        asOf: LocalDate = LocalDate.now(),
    ): TrackRecordContext {
        val previous = previousOutcomes(patternName, history, asOf)
        val onThisStock = previous.filter { it.symbol == symbol }
        val perStock = HitRate(symbol, patternName, onThisStock.count { it.hit == true }, onThisStock.size)
        val comparable = HitRate(null, patternName, previous.count { it.hit == true }, previous.size)
        return TrackRecordContext(
            priorHits = perStock.hits,
            priorSamples = perStock.samples,
            hitRate = perStock.hitRate.takeIf { perStock.isReportable },
            comparableOutcomes = comparable.samples,
            comparableHitRate = comparable.hitRate.takeIf { comparable.isReportable },
            evidence = evidence(symbol, perStock, comparable),
        )
    }

    /**
     * The sentence shown to the user. Same copy path as [context]; public so the accuracy screen
     * can refresh a row without rebuilding the whole context.
     *
     * - ≥ threshold on this stock: quotes the per-stock rate **with `n`**.
     * - thin here but enough across tracked stocks: says so, then quotes the cross-stock rate.
     * - thin everywhere: withholds both and shows `(n of 30)`.
     */
    fun evidence(symbol: String, perStock: HitRate, comparable: HitRate): String {
        val text = when {
            perStock.isReportable ->
                "On $symbol, this pattern's geometry was reached in ${perStock.hits} of " +
                    "${perStock.samples} previous cases (${percent(perStock)}). $NOT_A_PREDICTION"

            comparable.isReportable ->
                "$HELD_PREFIX on $symbol (${perStock.samples} of " +
                    "${MatchScorePolicy.MIN_HIT_RATE_SAMPLES}). Across tracked stocks this pattern's " +
                    "geometry was reached in ${comparable.hits} of ${comparable.samples} cases " +
                    "(${percent(comparable)}). $NOT_A_PREDICTION"

            else ->
                "$HELD_PREFIX (${perStock.samples} of ${MatchScorePolicy.MIN_HIT_RATE_SAMPLES})."
        }
        HonestyContract.requireNotProbabilityClaim(text)
        AdviceLanguage.requireClean(text)
        return text
    }

    /**
     * Resolved, strictly-past outcomes of [patternName].
     *
     * Uses [Match.name] directly rather than a pattern id so the history stays comparable across
     * detectors that are re-run with new ids.
     */
    private fun previousOutcomes(
        patternName: String,
        history: List<PatternOutcome>,
        asOf: LocalDate,
    ): List<PatternOutcome> = history.filter {
        it.resolved && it.hit != null && it.patternName == patternName && it.projectedOn.isBefore(asOf)
    }

    private fun percent(hitRate: HitRate): String {
        val value = hitRate.hitRate
        require(value != null) { "percent() is only called on a reportable hit rate" }
        return String.format("%.1f%%", value)
    }
}