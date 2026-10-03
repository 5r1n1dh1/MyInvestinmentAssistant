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
 * `RF-T-4` — `AT_52WEEK_LOW_WITH_DECLINING_OBV` (plan.md §8.2; `redflags-spec.md` §7.4).
 *
 * **Condition.** `close <= lowest close of the last 52 sessions` **and** the 20-bar OBV slope is
 * negative. Both halves come from the locked seam
 * (`IndicatorSnapshot.isAtFiftyTwoWeekLowWithDecliningObv`).
 *
 * **Why a slope and not an OBV level.** OBV is a cumulative signed total, so its absolute value
 * scales with a stock's own turnover and is meaningless across symbols. `obvSlope` normalises the
 * signed volume by the gross volume over the same window, which lands in `[-1, 1]`. The evidence row
 * says so explicitly, because a raw number of "−184,500" next to a 52-week low reads as a claim
 * about the share, not about participation.
 *
 * **Severity.** `HIGH` when the close is within [TechnicalRedFlagPolicy.NEAR_LOW_TOLERANCE] of the
 * low of the 52 sessions *before* the latest bar — see that constant for why the window excludes the
 * latest bar, which the condition's own window necessarily includes.
 *
 * **Window naming.** `redflags-spec.md` §10 R9 asks that the visible label say *"last 52 sessions"*
 * rather than *"52 weeks"*, because the app only stores exchange sessions and a user comparing
 * against a broker's terminal would otherwise count the wrong period. The flag id keeps the spec's
 * name; every rendered label uses sessions.
 */
object FiftyTwoWeekLowObvRule : TechnicalRedFlagRule {

    override val id: String = TechnicalFlagIds.AT_52WEEK_LOW_WITH_DECLINING_OBV

    override val tab: FlagTab = FlagTab.TECHNICALS

    /** `redflags-spec.md` §7.4 threshold string. */
    const val THRESHOLD: String =
        "Close at the 52-week low (last 52 sessions) with a declining 20-bar on-balance-volume slope"

    /** Book caveat, Ch.21 §21.27 Mistake 3 — a falling OBV can be profit-taking, not distribution. */
    const val CAVEAT: String =
        "A falling on-balance-volume slope during a range can reflect profit-taking rather than " +
            "distribution; the slope shows direction, not intent."

    /** Evidence label for the low of the 52 sessions *before* the latest bar. */
    const val PRIOR_LOW_LABEL: String = "Low of the 52 sessions before the latest bar"

    override fun evaluate(context: RedFlagContext): List<RedFlag> {
        val snapshot = context.indicatorsOrCompute() ?: return emptyList()
        if (context.candles.size < TechnicalPolicy.WEEKS_PER_YEAR) return emptyList()
        val close = snapshot.lastClose ?: return emptyList()
        val low = snapshot.fiftyTwoWeekLow ?: return emptyList()
        val slope = snapshot.obvSlope ?: return emptyList()
        if (close > low) return emptyList()
        if (slope >= 0.0) return emptyList()

        val candles = context.candles
        val priorLow = candles.dropLast(1)
            .takeLast(TechnicalPolicy.WEEKS_PER_YEAR)
            .minOfOrNull { it.close }
        val depthBelowPrior = if (priorLow == null || priorLow <= 0.0) {
            null
        } else {
            (priorLow - close) / priorLow * 100.0
        }
        val severity = if (depthBelowPrior != null && depthBelowPrior <= TechnicalRedFlagPolicy.NEAR_LOW_TOLERANCE * 100.0) {
            FlagSeverity.HIGH
        } else {
            FlagSeverity.MEDIUM
        }

        val asOf: LocalDate? = snapshot.asOf
        val evidence = buildList {
            add(
                Evidence(
                    label = "Close (last bar)",
                    value = TechnicalFlagFormat.rupee(close),
                    source = Source.NSE_BHAVCOPY,
                    asOf = asOf,
                ),
            )
            add(
                Evidence(
                    label = "52-week low (last 52 sessions)",
                    value = TechnicalFlagFormat.rupee(low),
                    source = Source.DERIVED,
                    asOf = snapshot.fiftyTwoWeekRange?.lowDate ?: asOf,
                ),
            )
            snapshot.fiftyTwoWeekHigh?.let { high ->
                add(
                    Evidence(
                        label = "52-week high (last 52 sessions)",
                        value = TechnicalFlagFormat.rupee(high),
                        source = Source.DERIVED,
                        asOf = snapshot.fiftyTwoWeekRange?.highDate ?: asOf,
                    ),
                )
            }
            snapshot.fiftyTwoWeekRange?.let { range ->
                add(
                    Evidence(
                        label = "Position in the 52-session range",
                        value = TechnicalFlagFormat.percent(range.currentPositionPercent),
                        source = Source.DERIVED,
                        asOf = asOf,
                    ),
                )
            }
            add(
                Evidence(
                    label = PRIOR_LOW_LABEL,
                    value = TechnicalFlagFormat.rupee(priorLow ?: Double.NaN),
                    source = Source.DERIVED,
                    asOf = asOf,
                ),
            )
            add(
                Evidence(
                    label = "Distance below that prior low",
                    value = TechnicalFlagFormat.percent(depthBelowPrior ?: Double.NaN, 2),
                    source = Source.DERIVED,
                    asOf = asOf,
                ),
            )
            add(
                Evidence(
                    label = "On-balance-volume slope (20 sessions)",
                    value = TechnicalFlagFormat.obvSlope(slope),
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
                severity = severity,
                tab = tab,
                title = "The last close of ${TechnicalFlagFormat.rupee(close)} is at the 52-session " +
                    "low of ${TechnicalFlagFormat.rupee(low)}, and the 20-session on-balance-volume " +
                    "slope is ${TechnicalFlagFormat.decimal(slope, 3)} (declining).",
                evidence = evidence,
                threshold = THRESHOLD,
            ),
        )
    }

    override fun skipReason(context: RedFlagContext): String? {
        val snapshot = context.indicatorsOrCompute() ?: return "No price history was supplied."
        if (snapshot.lastClose == null) return "No closing price was available on the latest session."
        if (context.candles.size < TechnicalPolicy.WEEKS_PER_YEAR ||
            snapshot.fiftyTwoWeekLow == null ||
            snapshot.fiftyTwoWeekHigh == null
        ) {
            return "The 52-session high/low window needs ${TechnicalPolicy.WEEKS_PER_YEAR} daily " +
                "sessions; this series has ${context.candles.size}. Calling the lowest close of a " +
                "shorter window a 52-session low would be a claim the data does not support."
        }
        if (snapshot.obvSlope == null) {
            return "The on-balance-volume slope needs ${TechnicalPolicy.SMA_FAST + 1} daily " +
                "sessions; this series has ${snapshot.barCount}."
        }
        return null
    }
}