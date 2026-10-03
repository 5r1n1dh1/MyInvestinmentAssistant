package com.mia.app.domain.redflag.technical

import com.mia.app.domain.model.Evidence
import com.mia.app.domain.model.FlagGroup
import com.mia.app.domain.model.FlagSeverity
import com.mia.app.domain.model.FlagTab
import com.mia.app.domain.model.RedFlag
import com.mia.app.domain.model.Source
import com.mia.app.domain.redflag.RedFlagContext
import com.mia.app.domain.technical.TechnicalPolicy
import java.time.LocalDate

/**
 * `RF-T-3` — `DISTRIBUTION_DAY` (plan.md §8.2; `redflags-spec.md` §7.3).
 *
 * **Condition.** `close < open` **on the same bar** and `volume / volumeSma20 >= 2.5`. The two
 * halves are read from one bar, which is the whole point: a volume spike measured on one session
 * and a down close measured on another is not distribution, it is two unrelated facts. The seam's
 * `IndicatorSnapshot.isDistributionDay` already enforces the conjunction; this rule uses it and
 * keeps the evidence in one place.
 *
 * **What it deliberately does not do.** `redflags-spec.md` §7.3: *"do not extend this to 'high
 * volume on an up day = accumulation'. The book says direction requires context."* So there is no
 * mirror rule here, and the up-day case is a quiet case.
 *
 * **The caveat is load-bearing.** Ch.21 §21.27 Mistake 1: *"High volume means: high participation.
 * Direction requires context."* An ex-results or index-rebalance session can print a 5× volume on a
 * down day legitimately, so the title never asserts distribution — it reports the ratio, the
 * average, and the bar's own direction.
 */
object DistributionDayRule : TechnicalRedFlagRule {

    override val id: String = TechnicalFlagIds.DISTRIBUTION_DAY

    override val tab: FlagTab = FlagTab.TECHNICALS

    /** `redflags-spec.md` §7.3 threshold string. */
    const val THRESHOLD: String =
        "Volume at or above 2.5x the 20-bar average on a bar that closed lower"

    /** Book caveat, Ch.21 §21.27 Mistake 1 and §21.21 VSA. */
    const val CAVEAT: String =
        "High volume means high participation; direction requires context. A single session can " +
            "also be an earnings, corporate-action or index-rebalance event, which our sources do " +
            "not carry."

    override fun evaluate(context: RedFlagContext): List<RedFlag> {
        val snapshot = context.indicatorsOrCompute() ?: return emptyList()
        val last = context.lastCandle() ?: return emptyList()
        if (last.isBullish) return emptyList()
        if (!snapshot.isDistributionDay(context.candles)) return emptyList()

        val ratio = snapshot.latestVolumeRatio ?: return emptyList()
        val average = snapshot.volumeAverage ?: return emptyList()
        if (average <= 0.0) return emptyList()

        val severity = if (ratio >= TechnicalRedFlagPolicy.DISTRIBUTION_HIGH_MULTIPLE) {
            FlagSeverity.HIGH
        } else {
            FlagSeverity.MEDIUM
        }

        val asOf: LocalDate = last.date
        val changePercent = if (last.open == 0.0) null else (last.close - last.open) / last.open * 100.0

        val evidence = buildList {
            add(
                Evidence(
                    label = "Volume (last bar)",
                    value = TechnicalFlagFormat.shares(last.volume),
                    source = Source.NSE_BHAVCOPY,
                    asOf = asOf,
                ),
            )
            add(
                Evidence(
                    label = "20-bar average volume",
                    value = TechnicalFlagFormat.shares(average),
                    source = Source.DERIVED,
                    asOf = asOf,
                ),
            )
            add(
                Evidence(
                    label = "Volume ratio",
                    value = TechnicalFlagFormat.multiple(ratio),
                    source = Source.DERIVED,
                    asOf = asOf,
                ),
            )
            add(
                Evidence(
                    label = "Open",
                    value = TechnicalFlagFormat.rupee(last.open),
                    source = Source.NSE_BHAVCOPY,
                    asOf = asOf,
                ),
            )
            add(
                Evidence(
                    label = "Close",
                    value = TechnicalFlagFormat.rupee(last.close),
                    source = Source.NSE_BHAVCOPY,
                    asOf = asOf,
                ),
            )
            add(
                Evidence(
                    label = "Change across the bar",
                    value = TechnicalFlagFormat.percent(changePercent ?: Double.NaN, 2),
                    source = Source.DERIVED,
                    asOf = asOf,
                ),
            )
            snapshot.atrValue?.takeIf { it > 0.0 }?.let { atr ->
                add(
                    Evidence(
                        label = "Bar range against ATR(14)",
                        value = TechnicalFlagFormat.multiple(last.range / atr) + " (" +
                            TechnicalFlagFormat.rupee(atr) + " ATR)",
                        source = Source.DERIVED,
                        asOf = asOf,
                    ),
                )
            }
            add(Evidence(label = "Caveat", value = CAVEAT, source = Source.DERIVED))
            add(Evidence(label = "Threshold", value = THRESHOLD, source = Source.DERIVED))
        }

        return listOf(
            RedFlag(
                id = id,
                group = FlagGroup.TECHNICAL,
                severity = severity,
                tab = tab,
                title = "Volume was " +
                    "${TechnicalFlagFormat.multiple(ratio, 1)} the 20-bar average of " +
                    "${TechnicalFlagFormat.shares(average)} on a bar that closed lower " +
                    "(${TechnicalFlagFormat.rupee(last.open)} open, " +
                    "${TechnicalFlagFormat.rupee(last.close)} close). " +
                    "High volume means high participation; direction requires context.",
                evidence = evidence,
                threshold = THRESHOLD,
            ),
        )
    }

    override fun skipReason(context: RedFlagContext): String? {
        if (context.candles.isEmpty()) return "No price history was supplied."
        val snapshot = context.indicatorsOrCompute() ?: return "No price history was supplied."
        if (snapshot.volumeAverage == null || snapshot.latestVolumeRatio == null) {
            return "The 20-bar average volume needs ${TechnicalPolicy.VOLUME_SMA_PERIOD} daily " +
                "sessions; this series has ${snapshot.barCount}."
        }
        return null
    }
}