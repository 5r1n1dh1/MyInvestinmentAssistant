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

/** Which side of the RSI bands a reading sits on. */
enum class RsiBand(val label: String) {
    OVERBOUGHT("Overbought (above 70)"),
    OVERSOLD("Oversold (below 30)"),
    ;

    companion object {
        /**
         * The band a reading is in, or `null` when it is between the two.
         *
         * The bounds are read from [TechnicalPolicy] rather than repeated, so the flag and the
         * chart's band lines can never drift apart.
         */
        fun of(rsi: Double?): RsiBand? {
            val v = rsi ?: return null
            return when {
                v > TechnicalPolicy.RSI_OVERBOUGHT -> OVERBOUGHT
                v < TechnicalPolicy.RSI_OVERSOLD -> OVERSOLD
                else -> null
            }
        }
    }
}

/**
 * `RF-T-2` — `RSI_OVERBOUGHT` and `RSI_OVERSOLD` (plan.md §8.2; `redflags-spec.md` §7.2).
 *
 * **Why these are two rules and not one.** A single rule whose emitted flag id depended on the
 * reading would have an id that changes between sessions, and `RedFlagRule.id` is documented as the
 * stable key for stored outcomes. Two ids, two rules, one shared builder — the overbought and
 * oversold bands are mutually exclusive on one reading anyway, so nothing is lost.
 *
 * **The wording is the flag.** `redflags-spec.md` §7.2 flags this as *"The strongest [false
 * positive] in this document"* because the book directly contradicts the naive reading of an
 * overbought reading: *"A strong stock can remain RSI > 70 for months. This is not automatically
 * bearish."* So the title describes **the reading**, never an implication, and the caveat is a
 * first-class evidence row (§11 rule 7).
 *
 * **Severity** is `LOW` by default and `MEDIUM` once the reading has held for
 * [TechnicalRedFlagPolicy.RSI_PERSISTENCE_BARS] consecutive sessions — a one-session excursion in
 * an oscillator is not a state.
 */
class RsiBandRule(
    private val band: RsiBand,
) : TechnicalRedFlagRule {

    override val id: String = when (band) {
        RsiBand.OVERBOUGHT -> TechnicalFlagIds.RSI_OVERBOUGHT
        RsiBand.OVERSOLD -> TechnicalFlagIds.RSI_OVERSOLD
    }

    override val tab: FlagTab = FlagTab.TECHNICALS

    /** The number in the title is the same number the comparison used — one decimal, always. */
    private val titleTemplate: String = when (band) {
        RsiBand.OVERBOUGHT ->
            "RSI(14) is %s, in the overbought band above %s. The book notes a strong stock can stay " +
                "in this band for months; this is a momentum reading, not a direction call."

        RsiBand.OVERSOLD ->
            "RSI(14) is %s, in the oversold band below %s. Oversold describes momentum, not a " +
                "turn in the price, and a low reading is not by itself a reason to act."
    }

    private val thresholdLabel: String = when (band) {
        RsiBand.OVERBOUGHT -> "Consecutive sessions above 70"
        RsiBand.OVERSOLD -> "Consecutive sessions below 30"
    }

    override fun evaluate(context: RedFlagContext): List<RedFlag> {
        val snapshot = context.indicatorsOrCompute() ?: return emptyList()
        val value = snapshot.rsi.value ?: return emptyList()
        if (RsiBand.of(value) != band) return emptyList()

        val asOf: LocalDate? = snapshot.asOf
        val bound = if (band == RsiBand.OVERBOUGHT) {
            TechnicalPolicy.RSI_OVERBOUGHT
        } else {
            TechnicalPolicy.RSI_OVERSOLD
        }
        val consecutive = consecutiveSessions(snapshot.rsi.rsi)
        val severity =
            if (consecutive >= TechnicalRedFlagPolicy.RSI_PERSISTENCE_BARS) FlagSeverity.MEDIUM else FlagSeverity.LOW

        val evidence = buildList {
            add(
                Evidence(
                    label = "RSI (14)",
                    value = TechnicalFlagFormat.decimal(value, 1),
                    source = Source.DERIVED,
                    asOf = asOf,
                ),
            )
            add(
                Evidence(
                    label = "Band boundary",
                    value = TechnicalFlagFormat.decimal(bound, 1),
                    source = Source.DERIVED,
                    asOf = asOf,
                ),
            )
            add(
                Evidence(
                    label = "Band",
                    value = band.label,
                    source = Source.DERIVED,
                ),
            )
            add(
                Evidence(
                    label = thresholdLabel,
                    value = "$consecutive",
                    source = Source.DERIVED,
                    asOf = asOf,
                ),
            )
            snapshot.fiftyTwoWeekHigh?.let { high ->
                add(
                    Evidence(
                        label = "52-week high (last 52 sessions)",
                        value = TechnicalFlagFormat.rupee(high),
                        source = Source.DERIVED,
                        asOf = asOf,
                    ),
                )
            }
            snapshot.lastClose?.let { close ->
                add(
                    Evidence(
                        label = "Close (last bar)",
                        value = TechnicalFlagFormat.rupee(close),
                        source = Source.NSE_BHAVCOPY,
                        asOf = asOf,
                    ),
                )
            }
            add(
                Evidence(
                    label = "Caveat",
                    value = CAVEAT,
                    source = Source.DERIVED,
                ),
            )
            add(Evidence(label = "Threshold", value = THRESHOLD, source = Source.DERIVED))
        }

        val reading = TechnicalFlagFormat.decimal(value, 1)
        return listOf(
            RedFlag(
                id = id,
                group = FlagGroup.TECHNICAL,
                severity = severity,
                tab = tab,
                title = titleTemplate.format(reading, TechnicalFlagFormat.decimal(bound, 0)),
                evidence = evidence,
                threshold = THRESHOLD,
            ),
        )
    }

    override fun skipReason(context: RedFlagContext): String? {
        val snapshot = context.indicatorsOrCompute() ?: return "No price history was supplied."
        if (snapshot.rsi.value == null) {
            return "RSI(14) needs ${TechnicalPolicy.RSI_PERIOD + 1} daily sessions; " +
                "this series has ${snapshot.barCount}."
        }
        return null
    }

    /** Sessions in a row, counting back from the latest bar, on this rule's side of the band. */
    private fun consecutiveSessions(series: List<Double?>): Int {
        var n = 0
        for (i in series.indices.reversed()) {
            if (RsiBand.of(series[i]) == band) n++ else break
        }
        return n
    }

    companion object {
        /** Book caveat, `redflags-spec.md` §7.2 / Ch.22 §22.16 and §22.35 Mistake 1. */
        const val CAVEAT: String =
            "RSI is a momentum reading, not a reversal signal, and a strong stock can remain in " +
                "this band for months. The book lists treating a single oscillator reading as a " +
                "decision in itself as a classic mistake. Neither band is evidence of what " +
                "happens next."

        /** `redflags-spec.md` §7.2 threshold string. */
        const val THRESHOLD: String =
            "RSI(14) above 70 (overbought) or below 30 (oversold)"
    }
}