package com.mia.app.domain.technical

import com.mia.app.domain.model.Candle
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs

/**
 * One suspected corporate action inside a stored price series.
 *
 * @property date the session **after** the discontinuity — the bar on which the new, lower price
 *   first appears, which is the date a reader would notice the chart has a cliff in it.
 * @property previousClose the last close before the event.
 * @property currentClose the first close after it.
 * @property changePercent `(current − previous) / previous × 100`. Negative for the usual
 *   bonus/split, but a demerger can go either way.
 * @property impliedRatio the divisor the price appears to have been divided by
 *   (`previous / current`). 2.0 ⇒ a 1:2 split; 10.0 ⇒ a 1:10 split.
 */
data class SuspectedSplit(
    val date: LocalDate,
    val previousClose: Double,
    val currentClose: Double,
    val changePercent: Double,
    val impliedRatio: Double,
)

/**
 * Detects corporate actions inside an **unadjusted** price series.
 *
 * ### Why this exists
 * plan.md §5.1 makes the NSE bhavcopy canonical, and plan.md §3.2's Yahoo source is also stored
 * unadjusted. plan.md §3.1's retention gives ~18 months, which is comfortably long enough to
 * contain several real corporate actions. Measured in this repository's own committed fixture
 * (`eod_history.json`, 372 real sessions × 41 NSE large caps):
 *
 * | Symbol | Date | Move |
 * |---|---|---|
 * | HDFCBANK | 2025-08-26 | −50.4% |
 * | BAJFINANCE | 2025-06-16 | −89.9% |
 * | KOTAKBANK | 2026-01-14 | −80.3% |
 * | TATAMOTORS | 2025-10-14 | −40.2% |
 *
 * A bar like that is not a market event, and it silently corrupts anything computed over a window
 * that spans it: a 200-day average, an RSI, a regression slope, and every price level either side
 * of it. The app cannot *fix* this without adjusted prices (see the note below), but it can and
 * should **say so**, which is what plan.md §1.4 rule 3 and §8.3 already require of every other
 * number on screen.
 *
 * ### What it deliberately does not do
 * It does **not** adjust, rescale or drop the affected bars. Rewriting stored history to make a
 * chart look continuous would be fabricating data, and plan.md §5.1's write-once guarantee means a
 * later corrected series could never replace it. The output is a *flag for the reader*, plus the
 * fact that a specific window contains a discontinuity.
 *
 * ### The threshold, and why it is not 10%
 * Indian daily moves of ±10% are ordinary — a weekly move alone can be that large. A threshold low
 * enough to catch a 1:1.5 consolidation would fire constantly on real volatility and train the
 * reader to ignore it. `SPLIT_CHANGE_THRESHOLD_PCT = 25` sits above routine volatility and below
 * the smallest split ratio in common use (1:4 = 75%, 1:5 = 80%, 1:10 = 90%; and 1:2 = 50%), so a
 * genuine split clears it comfortably while an ordinary bad day never approaches it.
 *
 * Note the detector is symmetric in sign: a *rise* of the same size is reported too, because a
 * reverse-split or a demerger can genuinely re-rate a price upward and silently breaking the series
 * is just as misleading as a drop.
 *
 * ### How this differs from `CorporateActionTracker`
 * `:data` has a `CorporateActionTracker` that answers a different question at a different layer:
 * it diffs the *symbol universe* across sessions to decide whether a ticker appeared, vanished,
 * changed ISIN or changed series. This asks whether two adjacent *prices* in an already-stored
 * series are implausibly far apart, which is a statement about the bars rather than about identity.
 *
 * The two are complementary and neither substitutes for the other. A demerger is often *both* — a
 * price cliff on one side and an identity event on the other (TATAMOTORS → `TMCV`, a new ISIN
 * listed 2025-11-12, is exactly that shape). Neither can be inferred from the other: many symbols
 * change identity without a price cliff (a series-code correction), and a price cliff can occur with
 * no identity change visible in the universe at all. This detector stays in `:domain` because it
 * reads candles and nothing else, which is what lets it be a plain JVM test.
 */
object CorporateActionDetector {

    /**
     * Single-bar close-to-close move that counts as a suspected corporate action.
     *
     * 25%, chosen as described in the class KDoc: above routine Indian daily volatility, below the
     * smallest split ratio in common use.
     */
    const val SPLIT_CHANGE_THRESHOLD_PCT: Double = 25.0

    /**
     * Suspected corporate actions in [candles], ascending by date.
     *
     * [candles] must be **ascending by date** — the same ordering every indicator expects — and the
     * method does not sort, because silently reordering input would hide a caller bug.
     *
     * Consecutive-day pairs only: a gap spanning several sessions is not evidence of anything,
     * since the intervening sessions' prices are unknown to this function.
     */
    fun detect(candles: List<Candle>): List<SuspectedSplit> {
        if (candles.size < 2) return emptyList()
        val out = mutableListOf<SuspectedSplit>()
        for (i in 1 until candles.size) {
            val previous = candles[i - 1]
            val current = candles[i]
            // Only near-adjacent sessions. A price change across a long gap carries no information
            // about what happened in between, so it is not attributable to a single event.
            val gap = ChronoUnit.DAYS.between(previous.date, current.date)
            if (gap < 1 || gap > MAX_SESSION_GAP_DAYS) continue
            if (previous.close <= 0.0 || current.close <= 0.0) continue

            val changePercent = (current.close - previous.close) / previous.close * 100.0
            if (abs(changePercent) < SPLIT_CHANGE_THRESHOLD_PCT) continue

            out += SuspectedSplit(
                date = current.date,
                previousClose = previous.close,
                currentClose = current.close,
                changePercent = changePercent,
                impliedRatio = previous.close / current.close,
            )
        }
        return out
    }

    /**
     * The widest calendar gap still treated as "the next session".
     *
     * The app has no exchange holiday calendar on device, so this accepts one to
     * [MAX_SESSION_GAP_DAYS] days and treats "a few days apart, including a weekend" as
     * consecutive. That is a deliberate over-acceptance, and it is *required* rather than merely
     * convenient: Indian markets are shut Saturday and Sunday, so **every** real split straddles a
     * weekend, and a detector demanding literally adjacent calendar dates would find nothing at
     * all. Over-acceptance is safe here because the 25% threshold is what rejects a routine move.
     */
    const val MAX_SESSION_GAP_DAYS: Long = 4

    /**
     * A sentence naming the suspected events in [candles], or `null` when there are none.
     *
     * Returns `null` rather than an empty string so a caller can write
     * `notes += noteFor(candles)?.let { ... }` and get nothing added when there is nothing to say.
     *
     * Factual wording only, per plan.md §8.3: it states the dates and the sizes, and never claims
     * to know the cause. "Suspected" is the honest word — the app has no corporate-action feed, so
     * it is inferring from a price cliff, and plan.md §1.4 rule 3 requires it to say so.
     */
    fun noteFor(candles: List<Candle>): String? {
        val splits = detect(candles)
        if (splits.isEmpty()) return null
        val detail = splits.joinToString("; ") { split ->
            "${split.date}: ${formatSigned(split.changePercent)}% " +
                "(approx 1:${trimRatio(split.impliedRatio)})"
        }
        val prefix = if (splits.size == 1) "1 suspected corporate action" else
            "${splits.size} suspected corporate actions"
        return "$prefix in this series — $detail. Prices are UNADJUSTED, so levels before and " +
            "after each date are not directly comparable, and indicators spanning one are affected."
    }

    private fun formatSigned(value: Double): String {
        val rounded = Math.round(value * 10.0) / 10.0
        return if (rounded > 0) "+$rounded" else "$rounded"
    }

    /** `2.0057` → `"2"`, so the copy reads "approx 1:2" rather than "approx 1:2.0057". */
    private fun trimRatio(ratio: Double): String {
        val nearest = Math.round(ratio * 100.0) / 100.0
        return if (nearest == Math.floor(nearest)) nearest.toLong().toString()
        // `Locale.ROOT`: under a comma-decimal locale this would render "9,95" and the copy would
        // read "approx 1:9,95" — a ratio written with a decimal comma, in a sentence the rest of the
        // app spells with periods.
        else String.format(Locale.ROOT, "%.2f", nearest)
    }
}