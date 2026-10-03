package com.mia.app.domain.redflag.technical

import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.Evidence
import com.mia.app.domain.model.FlagGroup
import com.mia.app.domain.model.FlagSeverity
import com.mia.app.domain.model.FlagTab
import com.mia.app.domain.model.RedFlag
import com.mia.app.domain.model.Source
import com.mia.app.domain.model.Timeframe
import com.mia.app.domain.redflag.RedFlagContext
import com.mia.app.domain.technical.DeliveryCollapseEvidence
import com.mia.app.domain.technical.TechnicalPolicy
import com.mia.app.domain.technical.deliveryAveragePercent
import java.time.LocalDate

/**
 * `RF-T-5` — `DELIVERY_COLLAPSE` (plan.md §8.2; `redflags-spec.md` §7.5).
 *
 * **Condition.** The latest [TechnicalPolicy.DELIVERY_COLLAPSE_CONSECUTIVE_BARS] sessions each
 * delivered at most [TechnicalPolicy.DELIVERY_COLLAPSE_RATIO] of their **own** 20-session average,
 * with at least [TechnicalPolicy.DELIVERY_COVERAGE_REQUIREMENT] of the window populated. The
 * condition itself comes from the locked seam (`IndicatorSnapshot.isDeliveryCollapsingFlag`); this
 * rule owns the evidence, the severity and the three suppressions.
 *
 * ### The delivery-data caveat, handled three ways
 *
 * `Candle.deliveryPercent` exists **only** in the legacy NSE `sec_bhavdata` file (plan.md §3.5, whose
 * availability is `UNVERIFIED`) and is `null` on every bar of the shared OHLCV fixture. A flag built
 * on it therefore has to do three separate things, and this rule does all three:
 *
 * 1. **Never fire on absent data.** With no delivery reading there is no average, so
 *    [com.mia.app.domain.technical.isDeliveryCollapsing] is `false` — and absence of evidence is not
 *    evidence of collapse, so the flag is `null` rather than a quiet pass.
 * 2. **Report the skip.** Below the coverage floor the rule returns nothing *and* names the gap
 *    through [skipReason], so the UI can render *"Not evaluated — …"* instead of implying the stock
 *    is clean (§11 rule 8, plan.md §12).
 * 3. **Withhold, and say so, below a liquidity floor.** Ch.21 §21.20: *"In small caps volume can be
 *    manipulated."* The spec requires suppression for thin stocks *with a stated reason*, so the
 *    reason is a skip, never silence.
 *
 * **D1 only.** The legacy feed is a daily file, so a weekly view has no delivery reading at all; on
 * W1 the rule abstains rather than aggregating (`redflags-spec.md` §7.5 "Additional constraint").
 *
 * **One average series, read once.** The 20-session delivery average is the most expensive thing
 * this rule needs and three different decisions depend on it (does it fire, how many sessions in a
 * row, was participation high beforehand). Deriving it once and passing it to all three is not just
 * cheaper — it means those three answers cannot disagree with each other.
 */
object DeliveryCollapseRule : TechnicalRedFlagRule {

    override val id: String = TechnicalFlagIds.DELIVERY_COLLAPSE

    override val tab: FlagTab = FlagTab.TECHNICALS

    /** `redflags-spec.md` §7.5 threshold string. */
    const val THRESHOLD: String =
        "Delivery percentage at or below 40% of its own 20-session average for 2 consecutive sessions"

    /** Book caveat, Ch.21-6: *"a low delivery percentage is not necessarily bearish."* */
    const val CAVEAT: String =
        "Delivery data should not be interpreted in isolation. The book notes a low delivery " +
            "percentage is not necessarily bearish, and that delivery must be read alongside price " +
            "action and broader market context."

    /** D1-only skip, `redflags-spec.md` §7.5. */
    const val WEEKLY_SKIP: String =
        "Delivery percentage is published only for daily sessions, so this check does not run on a " +
            "weekly view."

    override fun evaluate(context: RedFlagContext): List<RedFlag> {
        if (context.timeframe != Timeframe.D1) return emptyList()
        val candles = context.candles
        if (candles.size < TechnicalPolicy.DELIVERY_SMA_PERIOD) return emptyList()

        val window = candles.takeLast(TechnicalPolicy.DELIVERY_SMA_PERIOD)
        val covered = window.count { it.deliveryPercent != null }
        if (covered < coverageFloor()) return emptyList()
        if (averageTurnover(candles) < TechnicalRedFlagPolicy.MIN_LIQUIDITY_SHARES_PER_SESSION) {
            return emptyList()
        }

        val snapshot = context.indicatorsOrCompute() ?: return emptyList()
        if (!snapshot.isDeliveryCollapsingFlag) return emptyList()

        val last = context.lastCandle() ?: return emptyList()
        val deliveryPercent = last.deliveryPercent ?: return emptyList()

        // One average series, three questions.
        val averages = deliveryAveragePercent(candles)
        val numbers = numbersFor(candles, averages) ?: return emptyList()
        val consecutive = consecutiveCollapsing(candles, averages)
        if (consecutive < TechnicalPolicy.DELIVERY_COLLAPSE_CONSECUTIVE_BARS) return emptyList()

        val asOf: LocalDate = last.date
        val deliveryQuantity = deliveryQuantity(last)
        val coveragePercent = 100.0 * covered / TechnicalPolicy.DELIVERY_SMA_PERIOD

        val evidence = buildList {
            add(
                Evidence(
                    label = "Delivery % (last bar)",
                    value = TechnicalFlagFormat.percent(deliveryPercent),
                    source = Source.NSE_BHAVCOPY,
                    asOf = asOf,
                ),
            )
            add(
                Evidence(
                    label = "20-session average delivery %",
                    value = TechnicalFlagFormat.percent(numbers.averagePercent),
                    source = Source.DERIVED,
                    asOf = asOf,
                ),
            )
            add(
                Evidence(
                    label = "Delivery % against its own average",
                    value = TechnicalFlagFormat.ratio(numbers.ratio),
                    source = Source.DERIVED,
                    asOf = asOf,
                ),
            )
            add(
                Evidence(
                    label = "Collapse threshold ratio",
                    value = TechnicalFlagFormat.ratio(TechnicalPolicy.DELIVERY_COLLAPSE_RATIO),
                    source = Source.DERIVED,
                    asOf = asOf,
                ),
            )
            add(
                Evidence(
                    label = "Consecutive collapsing sessions",
                    value = "$consecutive",
                    source = Source.DERIVED,
                    asOf = asOf,
                ),
            )
            add(
                Evidence(
                    label = "Traded quantity (last bar)",
                    value = TechnicalFlagFormat.shares(last.volume),
                    source = Source.NSE_BHAVCOPY,
                    asOf = asOf,
                ),
            )
            add(
                Evidence(
                    label = "Delivery quantity (last bar)",
                    value = TechnicalFlagFormat.shares(deliveryQuantity),
                    source = Source.DERIVED,
                    asOf = asOf,
                ),
            )
            add(
                Evidence(
                    label = "Window coverage",
                    value = "$covered of ${TechnicalPolicy.DELIVERY_SMA_PERIOD} sessions " +
                        "(${TechnicalFlagFormat.percent(coveragePercent, 0)})",
                    source = Source.DERIVED,
                    asOf = asOf,
                ),
            )
            add(Evidence(label = "Caveat", value = CAVEAT, source = Source.DERIVED))
            add(Evidence(label = "Threshold", value = THRESHOLD, source = Source.DERIVED))
        }

        return listOf(
            RedFlag(
                id = id,
                group = FlagGroup.TECHNICAL,
                severity = severityFor(candles, averages, consecutive),
                tab = tab,
                title = "Delivery was " +
                    "${TechnicalFlagFormat.percent(deliveryPercent)} on the last $consecutive " +
                    "sessions, against a 20-session average of " +
                    "${TechnicalFlagFormat.percent(numbers.averagePercent)} — " +
                    "${TechnicalFlagFormat.decimal(numbers.ratio * 100.0, 0)}% of its own average.",
                evidence = evidence,
                threshold = THRESHOLD,
            ),
        )
    }

    override fun skipReason(context: RedFlagContext): String? {
        if (context.timeframe != Timeframe.D1) return WEEKLY_SKIP
        if (context.candles.isEmpty()) return "No price history was supplied."
        if (context.candles.size < TechnicalPolicy.DELIVERY_SMA_PERIOD) {
            return "The 20-session delivery window needs ${TechnicalPolicy.DELIVERY_SMA_PERIOD} " +
                "daily sessions; this series has ${context.candles.size}."
        }

        val covered = context.candles.takeLast(TechnicalPolicy.DELIVERY_SMA_PERIOD)
            .count { it.deliveryPercent != null }
        if (covered == 0) {
            return "Delivery percentage is absent from all of the last " +
                "${TechnicalPolicy.DELIVERY_SMA_PERIOD} sessions; our sources did not publish it."
        }
        if (covered < coverageFloor()) {
            return "Delivery percentage is available for only $covered of the last " +
                "${TechnicalPolicy.DELIVERY_SMA_PERIOD} sessions; the check needs at least " +
                "${coverageFloor()} of the window."
        }

        val turnover = averageTurnover(context.candles)
        if (turnover < TechnicalRedFlagPolicy.MIN_LIQUIDITY_SHARES_PER_SESSION) {
            return "Delivery percentage is not evaluated below " +
                "${TechnicalFlagFormat.shares(TechnicalRedFlagPolicy.MIN_LIQUIDITY_SHARES_PER_SESSION.toLong())} " +
                "of average daily turnover, because the book warns that volume in small caps can " +
                "be manipulated; this stock's 20-session average is " +
                "${TechnicalFlagFormat.shares(turnover)}."
        }
        return null
    }

    /**
     * `TechnicalPolicy.DELIVERY_COVERAGE_REQUIREMENT` expressed in whole sessions.
     *
     * `ceil`, not `round`: the requirement is "at least 75 % of the window", so 14.9 of 20 sessions
     * must not pass a check that asks for 15.
     */
    private fun coverageFloor(): Int = Math.ceil(
        TechnicalPolicy.DELIVERY_COVERAGE_REQUIREMENT * TechnicalPolicy.DELIVERY_SMA_PERIOD,
    ).toInt()

    /** The two numbers behind the flag, built from the shared average series. */
    private fun numbersFor(candles: List<Candle>, averages: List<Double?>): DeliveryCollapseEvidence? {
        val i = candles.lastIndex
        if (i < 0) return null
        val latest = candles[i].deliveryPercent ?: return null
        val average = averages[i] ?: return null
        if (average <= 0.0) return null
        return DeliveryCollapseEvidence(
            latestPercent = latest,
            averagePercent = average,
            ratio = latest / average,
            date = candles[i].date,
        )
    }

    /**
     * Trailing sessions whose delivery % is at or below [TechnicalPolicy.DELIVERY_COLLAPSE_RATIO] of
     * their own 20-session average, counting back from the latest bar.
     */
    private fun consecutiveCollapsing(candles: List<Candle>, averages: List<Double?>): Int {
        var n = 0
        for (i in candles.indices.reversed()) {
            val latest = candles[i].deliveryPercent ?: break
            val average = averages[i] ?: break
            if (average <= 0.0 || latest > average * TechnicalPolicy.DELIVERY_COLLAPSE_RATIO) break
            n++
        }
        return n
    }

    /**
     * `HIGH` when delivery was **above** its own 20-session average on each of the
     * [TechnicalRedFlagPolicy.DELIVERY_HIGH_PRIOR_BARS] sessions before the collapse **and** price
     * fell over that stretch (`redflags-spec.md` §7.5). Participation leaving after a run of high
     * delivery is a different statement from participation that was never there.
     *
     * `MEDIUM` whenever any part of that history is missing: an escalation that cannot be evidenced
     * is not asserted.
     */
    private fun severityFor(
        candles: List<Candle>,
        averages: List<Double?>,
        runLength: Int,
    ): FlagSeverity {
        val lookback = TechnicalRedFlagPolicy.DELIVERY_HIGH_PRIOR_BARS
        val end = candles.size - runLength - 1
        val start = end - lookback + 1
        if (start < 0 || end < 0 || end >= candles.size) return FlagSeverity.MEDIUM

        for (i in start..end) {
            val delivery = candles[i].deliveryPercent ?: return FlagSeverity.MEDIUM
            val average = averages[i] ?: return FlagSeverity.MEDIUM
            if (delivery <= average) return FlagSeverity.MEDIUM
        }
        val last = candles.last()
        return if (last.close < candles[end].close) FlagSeverity.HIGH else FlagSeverity.MEDIUM
    }

    /** Delivery quantity implied by a delivery percentage — the share of the bar that changed hands. */
    private fun deliveryQuantity(bar: Candle): Long {
        val percent = bar.deliveryPercent ?: return 0L
        return if (percent <= 0.0) 0L else Math.round(bar.volume * percent / 100.0)
    }

    /**
     * Mean traded volume over the delivery window — the liquidity measure the book warns about.
     *
     * The same number feeds [evaluate]'s gate and [skipReason]'s explanation, so the flag and the
     * "Not evaluated" line can never disagree about whether the floor was crossed.
     */
    private fun averageTurnover(candles: List<Candle>): Double =
        candles.takeLast(TechnicalPolicy.DELIVERY_SMA_PERIOD)
            .map { it.volume.toDouble() }
            .average()
}