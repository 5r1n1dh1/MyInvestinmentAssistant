package com.mia.app.data.android.work

import java.time.LocalDate

/**
 * What happened to one symbol during a nightly scan.
 *
 * The three states are kept apart on purpose. "Scanned and found nothing" is the common,
 * healthy answer; "skipped, not enough history" is a statement plan.md §6.4 requires the app to
 * make; and "failed" is a defect that must never be counted as a clean scan.
 */
enum class SymbolScanStatus {
    /** The engine ran and produced a (possibly empty) result. */
    SCANNED,

    /** plan.md §6.4's hard gate: fewer bars than the timeframe requires. No score was emitted. */
    SKIPPED_NOT_ENOUGH_HISTORY,

    /** The stored series could not be read, or the engine threw. One symbol must not stop the run. */
    FAILED,
}

/**
 * One symbol's row in the nightly scan's log.
 *
 * [note] is pre-rendered and safe to show. It is the reason the run did or did not do something,
 * and it is what `work_state.lastError` carries when the whole pass degraded.
 */
data class SymbolScanOutcome(
    val symbol: String,
    val status: SymbolScanStatus,
    /** Matches the engine returned. Zero is a legitimate result. */
    val matchCount: Int,
    /** Best score, or `-1` when nothing was scored. Never a probability (plan.md §6.1). */
    val topScore: Int,
    val note: String,
)

/**
 * The nightly scan's tally. Pure, so the fan-out's accounting can be asserted without Android.
 */
data class ScanSummary(
    val attempted: Int,
    val scanned: Int,
    val skipped: Int,
    val failed: Int,
    /** Matches at or above the render threshold, across every symbol. */
    val reported: Int,
    /** Breakout alerts actually delivered. Zero is normal without notification permission. */
    val alertsDelivered: Int,
    val asOf: LocalDate,
    /** One line per symbol that was skipped or failed, so nothing disappears silently. */
    val notes: List<String>,
) {
    /** plan.md §12: a failure must be visible, never folded into a clean-looking count. */
    val isDegraded: Boolean get() = failed > 0

    fun headline(): String = buildString {
        append("$scanned of $attempted watched symbol(s) scanned as of $asOf")
        if (reported > 0) append(", $reported pattern(s) reported")
        if (skipped > 0) append(", $skipped skipped for insufficient history")
        if (failed > 0) append(", $failed failed")
        append(", $alertsDelivered alert(s) delivered")
    }
}

/**
 * Turns a per-symbol fan-out into the record `work_state` and the logs need.
 *
 * ### Why the worker does not just count successes
 * plan.md §12 makes silent-empty the most dangerous possible failure, and plan.md §10 requires
 * the last outcome to be visible. A scan that scanned 12 symbols and failed 3 must say so; a
 * summary that reported only "12 scanned, 9 matched" would read as a healthy run. So this folds
 * the three states into an [isDegraded] verdict and collects one note per non-clean symbol.
 *
 * Pure and deterministic: the input list order is preserved in [ScanSummary.notes], and the
 * tally is order-independent.
 */
object ScanCoordinator {

    /**
     * The score above which a match is "reported".
     *
     * Read from the shared policy rather than restated so the nightly log cannot disagree with
     * the pattern screen about what counts as shown.
     */
    const val REPORTED_SCORE_FLOOR: Int = com.mia.app.domain.model.MatchScorePolicy.RENDER_THRESHOLD

    /**
     * Folds [outcomes] into a summary.
     *
     * @param alertsDelivered how many notifications were actually posted. Zero when the user has
     *   not granted `POST_NOTIFICATIONS`, which is a supported v1 state, not a defect — so it is
     *   counted and not treated as a failure.
     */
    fun summarise(
        outcomes: List<SymbolScanOutcome>,
        asOf: LocalDate,
        alertsDelivered: Int = 0,
    ): ScanSummary {
        var scanned = 0
        var skipped = 0
        var failed = 0
        var reported = 0
        val notes = ArrayList<String>()

        for (outcome in outcomes) {
            when (outcome.status) {
                SymbolScanStatus.SCANNED -> {
                    scanned++
                    if (outcome.topScore >= REPORTED_SCORE_FLOOR) reported++
                }

                SymbolScanStatus.SKIPPED_NOT_ENOUGH_HISTORY -> {
                    skipped++
                    notes += "${outcome.symbol}: ${outcome.note}"
                }

                SymbolScanStatus.FAILED -> {
                    failed++
                    notes += "${outcome.symbol}: ${outcome.note}"
                }
            }
        }

        return ScanSummary(
            attempted = outcomes.size,
            scanned = scanned,
            skipped = skipped,
            failed = failed,
            reported = reported,
            alertsDelivered = alertsDelivered,
            asOf = asOf,
            notes = notes,
        )
    }

    /**
     * The one line stored in `work_state.lastError`, or `null` when the pass was clean.
     *
     * Returns `null` rather than an empty string so a clean run does not overwrite a previous
     * failure's explanation with a blank (see `WorkStateDao`).
     */
    fun failureNote(summary: ScanSummary): String? {
        if (!summary.isDegraded) return null
        val head = "${summary.failed} of ${summary.attempted} watched symbol(s) failed as of ${summary.asOf}"
        val detail = summary.notes.take(MAX_NOTES).joinToString("; ")
        return if (detail.isEmpty()) head else "$head — $detail"
    }

    /** Names is already capped by [failureNote]; this bounds the stored string. */
    const val MAX_NOTES: Int = 5
}