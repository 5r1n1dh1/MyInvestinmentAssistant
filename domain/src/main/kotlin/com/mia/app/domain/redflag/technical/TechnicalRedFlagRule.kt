package com.mia.app.domain.redflag.technical

import com.mia.app.domain.redflag.RedFlagContext
import com.mia.app.domain.redflag.RedFlagRule
import com.mia.app.domain.technical.IndicatorSnapshot

/**
 * A technical red-flag rule that can say **why it did not run**.
 *
 * **Why this is additive to the engine seam rather than a replacement for it.**
 * `RedFlagRule.evaluate` returns a list, and an empty list is ambiguous: it means "evaluated, did
 * not trip" *and* "could not evaluate". plan.md §12 is explicit that collapsing those two is the
 * most dangerous possible failure — *"Silent-empty is indistinguishable from 'no red flags'"* — and
 * `redflags-spec.md` §11 rule 8 says a suppressed check must render as *"Not evaluated — <reason>"*
 * rather than as absence. `RedFlagRule` is a locked seam that returns `List<RedFlag>`, so this
 * interface adds the second answer alongside it instead of changing it.
 *
 * The contract is deliberately **separate** from [evaluate]: a rule reports a skip when it had to
 * abstain, and `null` when it ran. A rule that runs and finds nothing trips nothing — it is not
 * "skipped", because it answered the question.
 */
interface TechnicalRedFlagRule : RedFlagRule {

    /**
     * Why this rule could not answer its question, or `null` when it did answer it.
     *
     * The reason is a complete, user-renderable sentence fragment — it names the window and the
     * missing input — because it is what the UI puts next to *"Not evaluated"* (§11 rule 8). Never
     * a generic "no data".
     */
    fun skipReason(context: RedFlagContext): String?
}

/**
 * The indicators a rule reads, computing them from the candle list only when the caller did not
 * supply a snapshot.
 *
 * **Why the fallback exists.** `RedFlagContext.indicators` is nullable, and a rule that returned
 * "cannot evaluate" whenever it was null would be *silently disabled* on any call path that forgot
 * to build the snapshot — a check that vanishes rather than one that reports a gap, which is the
 * failure mode §12 is about. So the rule uses the snapshot when it is there and builds its own when
 * it is not.
 *
 * **Why that is not a performance problem worth optimising away.** `IndicatorSnapshot.of` is a
 * linear fused pass; building one per rule for a 372-bar series is a few tens of thousands of
 * arithmetic operations. The screen should pass the snapshot it already built for the chart (the
 * share-image renderer needs it anyway); the fallback exists for correctness, not for speed.
 */
internal fun RedFlagContext.indicatorsOrCompute(): IndicatorSnapshot? =
    indicators ?: if (candles.isEmpty()) null else IndicatorSnapshot.of(candles)

/** This context with [RedFlagContext.indicators] filled in, so a batch run computes it once. */
internal fun RedFlagContext.withComputedIndicators(): RedFlagContext =
    if (indicators != null || candles.isEmpty()) this else copy(indicators = IndicatorSnapshot.of(candles))

/**
 * The last bar, or `null`. Every technical flag reads its open/close/volume from here, so a caller
 * that supplies indicators without candles gets a *skip* (an unanswerable question), never a flag
 * assembled from half the inputs.
 */
internal fun RedFlagContext.lastCandle() = candles.lastOrNull()