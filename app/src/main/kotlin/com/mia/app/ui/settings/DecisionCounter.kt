package com.mia.app.ui.settings

import com.mia.app.domain.model.MatchScorePolicy
import com.mia.app.domain.pattern.ProjectionGate
import com.mia.app.ui.common.formatDate
import java.time.LocalDate

/**
 * The on-device decision counter, as Settings reads it — gap F3.
 *
 * ### Why this screen owns the *interpretation* and not just the numbers
 * planv2 §W10 asks for "projection gate evaluated N times, fired M". Printing those two integers
 * would be easy and would be nearly useless: the whole point of the counter is to make the next A1
 * visible, and A1 was not a number that looked odd, it was a number that was **exactly zero**. Zero
 * is only alarming in the right circumstances — zero after a thousand evaluations is a dead gate,
 * zero on a device that has never scanned is a fresh install — and telling those apart requires a
 * rule, not a string.
 *
 * ### The rule this file is built to
 * **A counter is only useful if the number is surprising.**
 *
 * Applied literally, every figure below has to answer three questions, or it is not displayed at
 * all:
 * 1. what does this mean when it is **zero**?
 * 2. what does it mean when it is **implausibly high**?
 * 3. what does it look like when it is **merely working**?
 *
 * and the answers to (2) and (3) have to come from a measurement rather than from taste. Two
 * measurements are available and both are quoted below **with their sample sizes**, because a
 * threshold without its `n` is a guess wearing a number's clothes:
 *
 * * **How often the gate can honestly fire.** `ProjectionCalibration`'s walk-forward settled
 *   **875** candidates on this app's own fixture, of which **203 (23 %)** had a measured-move level
 *   inside the 5 % ceiling [ProjectionGate.DISTANCE_MAX] — and that is *before* the regime condition
 *   and the bar-count condition cut the field further. 23 % is therefore a **ceiling**, not a
 *   target: a device firing above it is firing on something the measurement never saw. The alarm
 *   sits at [MAX_PLAUSIBLE_FIRE_PERCENT], above every figure that measurement produced, so a device
 *   behaving exactly like the measured one cannot trip it.
 * * **How often the gate should refuse on distance.** The same 875 left **672** candidates (77 %)
 *   outside the 5 % ceiling. So [ProjectionGate.Blocker.DISTANCE_TOO_FAR] being the commonest
 *   reason is what a *working* gate looks like, and its being rare is the surprising state — which
 *   would mean the levels themselves changed rather than the gate.
 *
 * ### What the gate is counted over, precisely
 * One evaluation per match a canonical scan kept and ranked — per candidate the engine had a verdict
 * about. Candidates the engine suppressed below the render threshold are **not** counted, because
 * they never became a projection decision, and counting them would inflate the denominator with
 * geometry the reader was never shown. Exploratory window re-scans in the Pattern Explorer are also
 * excluded (`PatternScanOptions.isCanonicalScan`), because a denominator a reader can inflate by
 * tapping chips cannot be alarmed by.
 *
 * One consequence is stated on the screen rather than hidden here:
 * [ProjectionGate.Blocker.INSUFFICIENT_HISTORY] is a condition the gate defines but this counter's
 * recording point cannot produce, because `DefaultPatternRepository` refuses a short series before a
 * scan starts. It is displayed anyway, as a **tripwire** — a non-zero count there means the
 * pre-check was bypassed, which is a bug worth catching rather than a statistic worth showing.
 *
 * ### Figures deliberately **not** shown
 * "Average distance from the close" and "scans per day" would both be easy to add and are absent on
 * purpose: neither has a defensible "surprising" state derived from a measurement this repository
 * holds, and the rule at the top of this file says a figure without one is not displayed.
 *
 * @property scans canonical scans that reached the stage where the gate would be asked. The
 *   denominator that separates "the gate says no" from "the gate was never asked".
 * @property evaluated gate evaluations. Equals [fired] plus every blocker count.
 * @property fired how many of them admitted a match.
 * @property blockerCounts how many evaluations each condition stopped. Absent means zero, and zero
 *   is rendered as an explicit `0` rather than as a missing row.
 * @property since the first date anything was recorded, or `null`. Every figure is stated over this
 *   window, because a count with no stated window is a claim about all of time that was never
 *   measured.
 * @property lastSeen the most recent date, or `null`.
 */
data class DecisionCounterReport(
    val scans: Int = 0,
    val evaluated: Int = 0,
    val fired: Int = 0,
    val blockerCounts: Map<ProjectionGate.Blocker, Int> = emptyMap(),
    val since: LocalDate? = null,
    val lastSeen: LocalDate? = null,
) {
    /**
     * Whether a fire rate may be quoted at all.
     *
     * [MatchScorePolicy.MIN_HIT_RATE_SAMPLES] is reused rather than a fresh constant, on the same
     * reasoning the Accuracy tab uses: this app does not publish a rate below 30 cases, and a
     * second, looser floor here would be a second rule for one honesty problem.
     */
    val canQuoteRate: Boolean get() = evaluated >= MIN_RATE_SAMPLES

    /**
     * Fired as a percentage of evaluated, or `null` when [canQuoteRate] is false.
     *
     * `null` rather than a small number, because "3 % from four evaluations" is not a rate and is
     * the sort of figure that gets quoted later without its denominator.
     */
    val firePercent: Int?
        get() = if (!canQuoteRate) {
            null
        } else {
            ((fired.toLong() * 100L) / evaluated.toLong()).toInt()
        }

    /** The state this device is in. Rendered as a word; the tint beside it is only an accent. */
    val verdict: GateVerdict
        get() = when {
            evaluated == 0 && scans == 0 -> GateVerdict.NOT_YET_MEASURED
            evaluated == 0 -> GateVerdict.NEVER_ASKED_OF_A_MATCH
            fired == 0 -> GateVerdict.DEAD_GATE
            (firePercent ?: 0) > MAX_PLAUSIBLE_FIRE_PERCENT -> GateVerdict.TOO_PERMISSIVE
            else -> GateVerdict.WORKING
        }

    /**
     * The headline planv2 §W10 names: "projection gate evaluated N times, fired M".
     *
     * The sample size is inside the sentence rather than beside it, because a ratio and its `n`
     * separated by a line break is a ratio whose denominator gets skimmed. It appears again on the
     * rate line as well, deliberately: plan.md §6.1 requires `n` beside every rate, and saying it
     * twice is cheaper than a layout that can drop one.
     */
    fun headline(): String = "Projection gate evaluated $evaluated times, fired $fired (n=$evaluated)."

    /** The count of canonical scans, as a sentence — the denominator that makes zero legible. */
    fun scanLine(): String =
        if (scans == 0) {
            "No canonical scan has reached the projection gate on this device yet."
        } else {
            "$scans canonical scan(s) reached the projection gate on this device."
        }

    /** The window every figure above is stated over. */
    fun windowLine(): String = when {
        since == null -> "No window: nothing has been recorded on this device yet."
        // A `since` with no `lastSeen` is not reachable through the repository, which takes the
        // max over non-null dates — but the branch is written rather than asserted, because a
        // `requireNotNull` inside a sentence the Settings screen renders is a crash on a malformed
        // row, and "counting since X" is a true statement about one.
        lastSeen == null || since == lastSeen ->
            "Counting since ${formatDate(since)}, all on one day."

        else -> "Counting since ${formatDate(since)}. Last recorded ${formatDate(lastSeen)}."
    }

    /**
     * The one sentence that says what this device's numbers currently mean.
     *
     * Never blank. The worst outcome for a counter is a screen that shows `0 / 0` and leaves the
     * reader to work out whether the app is broken — which is the same ambiguity A1 had, and the
     * reason this counter exists.
     */
    fun verdictLine(): String = when (verdict) {
        GateVerdict.NOT_YET_MEASURED ->
            "Nothing has been measured yet. No scan on this device has reached the stage where the " +
                "gate decides anything, so there is no evidence either way about the projection " +
                "feature. Track a symbol with enough stored history, or wait for the nightly scan."

        GateVerdict.NEVER_ASKED_OF_A_MATCH ->
            "$scans canonical scan(s) ran and none produced a match for the gate to rule on. The " +
                "gate has refused nothing — it has not been asked. That points at detection or at " +
                "history depth rather than at the gate, and each symbol's scan note says which."

        GateVerdict.DEAD_GATE ->
            "The gate refused all $evaluated evaluations and admitted nothing. This is the state " +
                "the counter exists to catch: the app's headline feature has a rule that nothing on " +
                "this device can satisfy. The breakdown below names the condition that did it."

        GateVerdict.TOO_PERMISSIVE ->
            "The gate admitted $fired of $evaluated evaluations ($firePercent %, n=$evaluated). The " +
                "app's own 875-sample walk-forward put 203 of 875 candidates (23 %) inside this " +
                "same 5 % ceiling, so a device admitting more than " +
                "${MAX_PLAUSIBLE_FIRE_PERCENT} % is admitting projections that measurement never " +
                "supported. The ceiling is ${ProjectionGate.DISTANCE_MAX_PERCENT.toInt()} %; moving " +
                "it needs a new measurement, not a preference."

        GateVerdict.WORKING ->
            "The gate admitted $fired of $evaluated evaluations ($firePercent %, n=$evaluated) and " +
                "refused the rest. For scale, the same walk-forward put 203 of 875 candidates " +
                "(23 %) inside this 5 % ceiling, so a fire rate in that region is what the gate is " +
                "calibrated for."
    }

    /**
     * Every condition, with its count and what that count means **here**.
     *
     * Zero-valued blockers are rendered as an explicit `0` rather than omitted: "no candlestick
     * match has reached this device" and "candlestick matches reached it and none were refused"
     * are different facts, and a hidden row cannot tell them apart — which is the same ambiguity
     * this feature exists to remove.
     */
    fun blockerRows(): List<BlockerRow> = listOf(
        BlockerRow(
            name = FIRED_LABEL,
            count = fired,
            meaning = "admitted — a level was projected and its breakout alert could fire",
            reading = firedReading(),
        ),
    ) + ProjectionGate.Blocker.entries.map { blocker ->
        BlockerRow(
            // The enum's own `name`, which is the string the DAO stored. Not a re-spelling: a
            // label invented here would be a second vocabulary for the same four conditions.
            name = blocker.name,
            count = blockerCounts[blocker] ?: 0,
            meaning = blockerMeaning(blocker),
            reading = blockerReading(blocker),
        )
    }

    /** The commonest decline and its count, or `null` when nothing has been declined. */
    fun dominantBlocker(): Pair<ProjectionGate.Blocker, Int>? =
        blockerCounts.entries.maxByOrNull { it.value }?.let { it.key to it.value }

    /** Percentage of evaluations this condition stopped. `0` when nothing has been evaluated. */
    private fun shareOf(count: Int): Int =
        if (evaluated <= 0) 0 else ((count.toLong() * 100L) / evaluated.toLong()).toInt()

    private fun firedReading(): String = when (verdict) {
        GateVerdict.NOT_YET_MEASURED ->
            "Nothing to read yet — the gate has not been asked anything."

        GateVerdict.DEAD_GATE ->
            "Zero, with $evaluated evaluations behind it. The projection feature is not running on " +
                "this device."

        GateVerdict.WORKING ->
            "$fired admissions out of $evaluated evaluations — inside the band the 875-sample " +
                "measurement supports."

        GateVerdict.NEVER_ASKED_OF_A_MATCH, GateVerdict.TOO_PERMISSIVE ->
            "$fired admissions out of $evaluated evaluations."
    }

    private fun blockerReading(blocker: ProjectionGate.Blocker): String {
        val count = blockerCounts[blocker] ?: 0
        val share = shareOf(count)
        return when {
            evaluated == 0 -> "Zero, because the gate has not been asked anything on this device yet."

            count == 0 -> "Zero. Nothing on this device has been stopped by this condition."

            blocker == ProjectionGate.Blocker.INSUFFICIENT_HISTORY ->
                "$count ($share % of evaluations). This is the one condition that should not be " +
                    "able to appear here: a series shorter than the timeframe's minimum is refused " +
                    "before a scan starts, so a non-zero count means that pre-check was bypassed. " +
                    "Read it as a fault, not as a statistic."

            blocker == ProjectionGate.Blocker.DISTANCE_TOO_FAR && share >= COMMON_DECLINE_SHARE_PERCENT ->
                "$count ($share %). The commonest reason, and the expected one: the same walk-forward " +
                    "left 672 of 875 candidates (77 %) outside this ceiling. A gate that rarely " +
                    "refused here would be the surprising state, not this one."

            else -> "$count ($share % of evaluations)."
        }
    }

    companion object {
        /**
         * The floor below which no rate is quoted, taken from `MatchScorePolicy.MIN_HIT_RATE_SAMPLES`
         * (30) rather than invented here.
         *
         * Below it the screen shows the counts and says the rate is withheld. A 100 % fire rate
         * built from four evaluations is a worse lie than no percentage, and this app already has a
         * rule about exactly that on the Accuracy tab.
         */
        const val MIN_RATE_SAMPLES: Int = MatchScorePolicy.MIN_HIT_RATE_SAMPLES

        /**
         * The rate above which the gate is reported as too permissive, in percent.
         *
         * `203 / 875 = 23 %` is the highest fire rate the app's own measurement accounts for, so 25
         * sits above every figure that measurement produced. It is a ceiling check, not a target —
         * nothing in this workstream is trying to make the gate fire more often.
         */
        const val MAX_PLAUSIBLE_FIRE_PERCENT: Int = 25

        /**
         * The share of evaluations at which [ProjectionGate.Blocker.DISTANCE_TOO_FAR] counts as the
         * ordinary case rather than an anomaly, in percent.
         *
         * `672 / 875 = 77 %` of the measured candidates sat outside the 5 % ceiling, so a gate that
         * refuses mostly on distance is the gate working. The constant is the measured share rounded
         * *down* to a whole number, so the "commonest reason" sentence can never claim to describe
         * a device that has in fact refused mostly on regime.
         */
        const val COMMON_DECLINE_SHARE_PERCENT: Int = 60

        /** The label for the admitted row. Never a colour, never a tick. */
        const val FIRED_LABEL: String = "admitted"

        /**
         * Read verbatim under every figure.
         *
         * Stated because a counter on an app that markets itself as serverless would otherwise read
         * as tracking, and the claim has to be checkable rather than reassuring: the numbers behind
         * it are rows in a local Room table, and there is no network call anywhere on the path that
         * writes or reads them.
         */
        const val STORED_ON_THIS_DEVICE: String =
            "These counts live in this app's own database on this phone. Nothing in this section is " +
                "uploaded, and there is no server to upload it to — no account, no analytics service, " +
                "no crash reporting. Clearing the counts deletes them permanently."

        /** A device on which nothing has been evaluated. A real state, not a failure. */
        val EMPTY = DecisionCounterReport()
    }
}

/**
 * The state a device's counter is in.
 *
 * An **enum with a word**, not an enum with a colour: gap F4 requires that colour is never the only
 * carrier of meaning, and the one surface this workstream adds is exactly where a red/green pill
 * would be most tempting. The accent that goes with the word is decoration; the word is the claim.
 */
enum class GateVerdict {
    /** No scan has reached the gate stage. A fresh install, not a fault. */
    NOT_YET_MEASURED,

    /** Scans ran; no candidate ever reached the gate. A detection problem, not a gate problem. */
    NEVER_ASKED_OF_A_MATCH,

    /** Evaluations happened and every one was refused. The A1 shape. */
    DEAD_GATE,

    /** The gate is admitting more often than the measurement can account for. */
    TOO_PERMISSIVE,

    /** Inside the measured band. */
    WORKING,
    ;

    /** The word rendered on screen. Plain, and never the only thing carrying the meaning. */
    val label: String
        get() = when (this) {
            NOT_YET_MEASURED -> "not measured yet"
            NEVER_ASKED_OF_A_MATCH -> "never asked"
            DEAD_GATE -> "not firing"
            TOO_PERMISSIVE -> "firing more than measured"
            WORKING -> "working"
        }
}

/**
 * One line of the breakdown: a condition, how many times it decided, and what that count means
 * **here**.
 *
 * @property reading never blank and never a bare number. A counter row showing `12` with no
 *   sentence is a number, and a number is what A1 was: something to look at that means nothing
 *   unless you already know what to look for.
 */
data class BlockerRow(
    val name: String,
    val count: Int,
    val meaning: String,
    val reading: String,
)

/** One plain sentence per condition, in the reader's terms. */
internal fun blockerMeaning(blocker: ProjectionGate.Blocker): String = when (blocker) {
    ProjectionGate.Blocker.INSUFFICIENT_HISTORY ->
        "refused — fewer bars than the timeframe's minimum"
    ProjectionGate.Blocker.NO_MEASURED_MOVE ->
        "refused — the formation carries no measured move, so there is no level to draw"
    ProjectionGate.Blocker.DISTANCE_TOO_FAR ->
        "refused — the level sits further than ${ProjectionGate.DISTANCE_MAX_PERCENT.toInt()} % " +
            "from the last close"
    ProjectionGate.Blocker.REGIME_NOT_ALIGNED ->
        "refused — the trend regime is not aligned with the formation's direction"
}