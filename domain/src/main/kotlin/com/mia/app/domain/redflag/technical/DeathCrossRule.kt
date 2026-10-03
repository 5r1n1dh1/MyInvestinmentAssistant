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
 * `RF-T-1` — `DEATH_CROSS` (plan.md §8.2; `redflags-spec.md` §7.1).
 *
 * **Condition.** `SMA50 < SMA200` **and** the latest close is below `SMA200`. Both halves come from
 * the locked seam (`IndicatorSnapshot.isDeathCrossCondition`), so the chart and the flag cannot
 * disagree about whether the cross is in place.
 *
 * **Severity.** `HIGH` when the cross printed within
 * [TechnicalRedFlagPolicy.DEATH_CROSS_RECENT_BARS] sessions of the latest bar, `MEDIUM` otherwise.
 * "When did it happen?" is the question a user actually has, so the cross *date* is evidence, not a
 * footnote — and a cross from six months ago is a very different statement from one printed today.
 *
 * **The book's caveat is a row, not fine print** (`redflags-spec.md` §11 rule 7):
 * *"Lagging indicators. They react after price movement."* Without it the flag reads as a prediction.
 */
object DeathCrossRule : TechnicalRedFlagRule {

    override val id: String = TechnicalFlagIds.DEATH_CROSS

    override val tab: FlagTab = FlagTab.TECHNICALS

    /** Shown verbatim as evidence. `redflags-spec.md` §7.1 / Ch.22 §22.10. */
    const val CAVEAT: String =
        "Moving averages are lagging; they react after price moves, so this is a description of " +
            "where price already is, not an expectation about where it goes next."

    /** `redflags-spec.md` §7.1 threshold string, rendered on the flag. */
    const val THRESHOLD: String = "Close below the 200-day average and the 50-day average below the 200-day average"

    /** What the evidence shows when the cross bar index is known. */
    const val CROSS_DATE_LABEL: String = "Cross date (50-day average crossed below the 200-day average)"

    /** What the evidence shows when no ABOVE → BELOW transition exists inside the loaded window. */
    const val CROSS_NOT_IN_WINDOW: String =
        "No crossover inside the loaded window: the 50-day average has been below the 200-day " +
            "average on every bar available."

    override fun evaluate(context: RedFlagContext): List<RedFlag> {
        val snapshot = context.indicatorsOrCompute() ?: return emptyList()
        val close = snapshot.lastClose ?: return emptyList()
        val sma50 = snapshot.sma50Value ?: return emptyList()
        val sma200 = snapshot.sma200Value ?: return emptyList()
        if (!snapshot.isDeathCrossCondition()) return emptyList()

        val candles = context.candles
        val crossIndex = snapshot.deathCrossBarIndex
        val crossDate = crossIndex?.let { candles.getOrNull(it)?.date }
        val barsSinceCross = crossIndex?.let { candles.lastIndex - it }
        val severity = if (barsSinceCross != null && barsSinceCross <= TechnicalRedFlagPolicy.DEATH_CROSS_RECENT_BARS) {
            FlagSeverity.HIGH
        } else {
            FlagSeverity.MEDIUM
        }

        val asOf: LocalDate? = snapshot.asOf
        val gapPercent = if (sma200 == 0.0) null else (close - sma200) / sma200 * 100.0

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
                    label = "50-day average",
                    value = TechnicalFlagFormat.rupee(sma50),
                    source = Source.DERIVED,
                    asOf = asOf,
                ),
            )
            add(
                Evidence(
                    label = "200-day average",
                    value = TechnicalFlagFormat.rupee(sma200),
                    source = Source.DERIVED,
                    asOf = asOf,
                ),
            )
            add(
                Evidence(
                    label = "Close against the 200-day average",
                    value = TechnicalFlagFormat.percent(gapPercent ?: Double.NaN),
                    source = Source.DERIVED,
                    asOf = asOf,
                ),
            )
            add(
                Evidence(
                    label = CROSS_DATE_LABEL,
                    value = crossDate?.toString() ?: CROSS_NOT_IN_WINDOW,
                    source = Source.DERIVED,
                    // As-of is the *window's* last bar either way: a statement about what did not
                    // cross inside the window is still a statement about that window.
                    asOf = crossDate ?: asOf,
                ),
            )
            add(
                Evidence(
                    label = "Sessions since the cross",
                    value = barsSinceCross?.let { "$it session(s)" }
                        ?: "Not observable inside the loaded window",
                    source = Source.DERIVED,
                    asOf = asOf,
                ),
            )
            add(
                Evidence(
                    label = "Bars in the loaded window",
                    value = "${candles.size} daily bar(s)",
                    source = Source.NSE_BHAVCOPY,
                    asOf = asOf,
                ),
            )
            add(
                Evidence(
                    label = "Caveat",
                    value = CAVEAT,
                    source = Source.DERIVED,
                ),
            )
            add(Evidence(label = "Threshold", value = THRESHOLD, source = Source.DERIVED))
        }

        return listOf(
            RedFlag(
                id = id,
                group = FlagGroup.TECHNICAL,
                severity = severity,
                tab = tab,
                title = "The 50-day average is below the 200-day average, and the last close of " +
                    "${TechnicalFlagFormat.rupee(close)} is below the 200-day average of " +
                    "${TechnicalFlagFormat.rupee(sma200)}.",
                evidence = evidence,
                threshold = THRESHOLD,
            ),
        )
    }

    override fun skipReason(context: RedFlagContext): String? {
        val snapshot = context.indicatorsOrCompute()
            ?: return "No price history was supplied for ${context.symbol}."
        if (snapshot.sma200Value == null) {
            return "The 200-day average needs ${TechnicalPolicy.SMA_LONG} daily sessions; " +
                "this series has ${snapshot.barCount}."
        }
        if (snapshot.sma50Value == null) {
            return "The 50-day average needs ${TechnicalPolicy.SMA_MID} daily sessions; " +
                "this series has ${snapshot.barCount}."
        }
        if (snapshot.lastClose == null) {
            return "No closing price was available on the latest session."
        }
        return null
    }
}