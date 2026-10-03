package com.mia.app.data.android.alert

import com.mia.app.domain.compliance.AdviceLanguage
import com.mia.app.domain.model.Direction
import com.mia.app.domain.model.MatchScorePolicy
import com.mia.app.domain.model.Timeframe
import java.time.LocalDate

/**
 * One breakout alert, as plan.md §6.6 item 3 defines it: a **condition on a close**, which is an
 * event and not a forecast.
 *
 * ### The three things this type deliberately does not carry
 * * **No projected level.** The alert is about the *breakout*, not the measured move. The pattern's
 *   geometry is drawn on the chart and labelled "Pattern geometry"; putting it in a notification
 *   would turn a projected level into what reads as a price target — which plan.md §1.4 forbids
 *   and which `AdviceLanguage.FORBIDDEN` machine-checks for.
 * * **No probability.** [score] is geometric fit to the textbook template (plan.md §6.1) and is
 *   carried only so the app can explain why the alert was raised at all.
 * * **No advice.** [init] runs the composed copy through `AdviceLanguage.requireClean`, so a
 *   future edit that introduces banned wording fails immediately instead of shipping.
 */
data class BreakoutAlert(
    val symbol: String,
    val patternName: String,
    val timeframe: Timeframe,
    val score: Int,
    val direction: Direction,
    /** The boundary that was crossed on a **close**. The observable fact. */
    val level: Double,
    /** The condition in words, e.g. "Close above the pattern boundary". */
    val condition: String,
    /** The session whose close satisfied the condition. */
    val asOf: LocalDate,
) {
    init {
        require(condition.isNotBlank()) {
            "a breakout alert must state the condition it fired on (plan.md §6.6 item 3)"
        }
        AdviceLanguage.requireClean(title(), body())
    }

    /**
     * Whether the app is allowed to raise an alert for this match at all.
     *
     * plan.md §6.4: a projection and an alert require `score >= 70` **and** an aligned regime;
     * anything at or above the render threshold may be shown but must not be alerted on. The
     * threshold is read from the shared policy so this cannot drift from the pattern screen.
     */
    val mayAlert: Boolean get() = score >= MatchScorePolicy.PROJECT_THRESHOLD

    /**
     * Title line: the pattern, the symbol, the timeframe. Nothing else.
     *
     * A lock-screen notification has room for one line, so anything that does not fit belongs in
     * [body].
     */
    fun title(): String = "$patternName on $symbol (${timeframe.name})"

    /**
     * Body line: the observed condition, its date, and the honesty footer.
     *
     * The footer is not decoration. A notification is the one place a user meets this app without
     * having read the in-app disclaimer, and plan.md §6.1 is explicit that a score is not a
     * probability of profit. The wording is checked by [init] against `AdviceLanguage.FORBIDDEN`.
     */
    fun body(): String = "$condition on $asOf. $FOOTER"

    /**
     * Kept here rather than taken from `Disclosure` so the one-line notification version is a
     * single short string. It states both halves of plan.md §1.4's posture — not advice, not a
     * forecast — without using any phrase `AdviceLanguage` bans.
     */
    companion object {
        const val FOOTER: String =
            "Geometric fit to the textbook shape only. Not investment advice, not a forecast."
    }
}

/**
 * Delivers a [BreakoutAlert]. An interface with a no-op default, because the app must work with
 * **zero permissions** in v1 (plan.md §1.4 keeps it permission-light).
 *
 * ### Why the interface exists at all
 * * [NoOpBreakoutAlertDispatcher] — the default. A device that has not granted
 *   `POST_NOTIFICATIONS` gets no notification, and the app is fully functional.
 * * `AndroidBreakoutAlertDispatcher` — the real one, which checks `areNotificationsEnabled()`
 *   before posting and reports that it did not, rather than throwing or silently dropping.
 *
 * Splitting it this way also keeps Android notification code out of `PatternScanWorker`: the
 * worker asks "was this delivered?" and records the answer, which is how "0 alerts delivered"
 * becomes a visible fact rather than a mystery.
 */
interface BreakoutAlertDispatcher {

    /**
     * Attempts delivery. Never throws.
     *
     * @return `true` when a notification was actually posted. `false` means "not delivered", and
     *   the caller must be able to tell the user why rather than leaving them wondering whether
     *   the pattern was ever seen.
     */
    suspend fun notifyBreakout(alert: BreakoutAlert): Boolean

    /** `false` for the no-op implementation, so a caller can state the capability honestly. */
    val isEnabled: Boolean
}

/**
 * The default: alerts are evaluated, tracked and stored, and nothing is shown to the user.
 *
 * ### Why the pattern scan still runs without it
 * plan.md §6.1's whole product is the track record. A user who denies notifications still gets
 * every detection, every projection and every settled outcome; they just do not get a
 * lock-screen ping. Cancelling the run instead would mean no accuracy data either, which would
 * make the permission prompt the price of the app's differentiator.
 */
object NoOpBreakoutAlertDispatcher : BreakoutAlertDispatcher {

    override val isEnabled: Boolean = false

    override suspend fun notifyBreakout(alert: BreakoutAlert): Boolean = false

    /** The line the UI shows when no dispatcher is installed. Safe to render verbatim. */
    const val REASON: String =
        "Breakout alerts are off. Everything else works without notification permission — " +
            "patterns, projections and the accuracy record are unaffected; only the alert is " +
            "suppressed."
}