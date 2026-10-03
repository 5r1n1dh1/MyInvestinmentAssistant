package com.mia.app.ui.accuracy

import com.mia.app.domain.accuracy.TrackRecordContext
import com.mia.app.domain.model.AccuracyReport
import com.mia.app.domain.model.HitRate
import com.mia.app.domain.model.MatchScorePolicy
import com.mia.app.domain.model.PatternOutcome
import com.mia.app.domain.model.Timeframe
import java.time.LocalDate

/**
 * The Accuracy tab's entire view of the outside world (plan.md §6.1, decision D6).
 *
 * ### Why this screen earns its tab
 * It is the product's differentiator: the app publishes **its own** track record, per stock **and**
 * per pattern, and withholds any rate below [MatchScorePolicy.MIN_HIT_RATE_SAMPLES] samples. That
 * only means something if the screen cannot be bent, so the interface is deliberately shaped around
 * three facts the screen must not be able to hide:
 *
 * * [report] is grouped three ways by `HitRateCalculator`, so the screen renders tables rather than
 *   computing anything itself.
 * * [unresolved] is a **separate** call. A projection whose evaluation window is still open is not a
 *   sample and not a miss; folding it into a rate is how a denominator shrinks itself.
 * * [lastSettledOn] is separate again, because a track record with no stated freshness reads as
 *   continuously maintained. It is not: verdicts settle in trading sessions.
 *
 * ### Contract, as for every screen's data source
 * * `suspend`, already off the main thread.
 * * `:domain` types only.
 * * `null` / empty means **"there is nothing"**, not "we failed". A function that fails throws
 *   [AccuracyUnavailableException].
 */
interface AccuracyDataSource {

    /**
     * The full report, grouped per stock+pattern, per pattern and per symbol.
     *
     * An empty report ([AccuracyReport.EMPTY]) is a real, expected state — a fresh install has
     * tracked nothing — and the screen renders it as an empty state rather than as a 0 % table.
     */
    suspend fun report(): AccuracyReport

    /**
     * Outcomes whose evaluation window has not closed yet.
     *
     * Reported separately and **never averaged into** a rate. Each is labelled with how many bars of
     * its horizon have elapsed, so "still open" is visibly different from "stale".
     */
    suspend fun unresolved(): List<PatternOutcome>

    /**
     * Every symbol with at least one tracked outcome, sorted. Used for the coverage summary and to
     * say plainly how few symbols the record covers.
     */
    suspend fun trackedSymbols(): List<String>

    /**
     * The date of the most recent settled verdict, or `null` when none has ever settled.
     *
     * The only honest statement about the record's freshness. "Updated nightly" would be false:
     * a verdict cannot settle until the horizon's bars exist.
     */
    suspend fun lastSettledOn(): LocalDate?

    /**
     * The track record for one symbol and pattern, for a drill-down row.
     *
     * Returns a context even when the rate is withheld, because `TrackRecordContext.evidence`
     * already distinguishes "too few cases here, here is the cross-stock figure" from "too few
     * cases anywhere".
     */
    suspend fun trackRecord(symbol: String, patternName: String): TrackRecordContext

    /**
     * The timeframes the tracked outcomes span.
     *
     * Shown as a coverage line. A record built only from daily bars is a different statement from
     * one that includes weekly, and the screen must not imply the record covers a timeframe it does
     * not.
     */
    suspend fun trackedTimeframes(): List<Timeframe> = emptyList()
}

/**
 * The exception every implementation throws on a failed read.
 *
 * Separate from `TechnicalsUnavailableException` on purpose: the two screens have genuinely
 * different failure shapes. A missing bhavcopy on the Technicals tab means "today's data is not
 * there yet", which is expected and nearly harmless. A failure here means the app cannot state its
 * own record at all, which is the one thing this tab exists to do — so it is an error, and the
 * screen offers a retry.
 */
class AccuracyUnavailableException(
    /** Plain language, shown verbatim. Never an exception class name. */
    val reason: String,
    val isTransient: Boolean = true,
) : Exception(reason)

/**
 * Everything the tab loaded, assembled off the main thread.
 *
 * Grouped into one value so there is no intermediate state in which the report has arrived but the
 * unresolved list has not, which would briefly show an unresolved count of zero.
 */
internal data class AccuracyLoad(
    val report: AccuracyReport,
    val unresolved: List<PatternOutcome>,
    val symbols: List<String>,
    val timeframes: List<Timeframe>,
    val lastSettledOn: LocalDate?,
) {
    companion object {
        /** The state of a device that has tracked nothing yet. */
        val EMPTY = AccuracyLoad(
            report = AccuracyReport.EMPTY,
            unresolved = emptyList(),
            symbols = emptyList(),
            timeframes = emptyList(),
            lastSettledOn = null,
        )
    }
}

/**
 * A row in one of the three tables.
 *
 * @param hitRate the domain value, rendered through `HitRate.label()` so the withholding rule and
 *   the sample size cannot be bypassed.
 * @param kind which of the three groupings the row came from, so a screen reader announces the
 *   scope without the table's heading being read twice.
 */
data class AccuracyRow(
    val heading: String,
    val hitRate: HitRate,
    val kind: AccuracyRowKind,
)

/** Which grouping an [AccuracyRow] belongs to. */
enum class AccuracyRowKind(val label: String) {
    /** Per stock **and** per pattern (decision D6). The strictest grouping. */
    STOCK_AND_PATTERN("Per stock and pattern"),

    /** Per formation, across every tracked stock. */
    PATTERN("Per pattern"),

    /** Per stock, across every tracked formation. */
    SYMBOL("Per symbol"),
}

/** The sample size below which every rate on this screen is withheld. Read from the policy. */
internal val REPORTING_THRESHOLD: Int = MatchScorePolicy.MIN_HIT_RATE_SAMPLES