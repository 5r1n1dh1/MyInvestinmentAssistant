package com.mia.app.domain.ratios

import com.mia.app.domain.model.Confidence
import com.mia.app.domain.model.Ratios
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * The kind of datum being judged, with the age at which it stops being trustworthy.
 *
 * ### Why each window is what it is
 * plan.md §10: *"Staleness must be visible in the UI. Fundamentals can lag a quarter. A stale
 * P/E shown as live is the fastest way to lose trust."* The windows are not one number for
 * everything, because the sources have genuinely different rhythms:
 *
 * | Kind | Window | Why |
 * |---|---|---|
 * | [PRICE] | 1 day | plan.md §3.1: the bhavcopy is a **daily** end-of-day file, published ~19:00 IST. Yesterday's close is the newest price that exists. |
 * | [DAILY_CANDLE] | 1 day | Same file, same cadence. A chart bar two sessions old is not "today's chart". |
 * | [FUNDAMENTALS_SNAPSHOT] | 7 days | A screener.in page has no publication stamp; it changes when the company files. A week covers a normal refresh and still catches a forgotten refresh after a network change. |
 * | [ANNUAL_STATEMENT] | 200 days | An Indian FY ends 31 March; results are due within 60 days, so a full cycle is ~240 days. 200 keeps a March-year company fresh through the filing season while flagging a year-end gap. |
 * | [QUARTERLY] | 120 days | One quarter plus a margin for a delayed filing. |
 *
 * The boundary is **inclusive**: a value exactly [windowDays] old is [FreshnessState.FRESH].
 * Every one of these numbers is in calendar days, not sessions — the app stores one EOD bar per
 * trading day but has no trading calendar of its own beyond a holiday list
 * (plan.md §3.4), and inventing a session count here would make the window wrong for every
 * Diwali.
 */
enum class FreshnessKind(val windowDays: Int, val label: String) {
    /** A price: the EOD bhavcopy close. */
    PRICE(1, "price"),

    /** One daily OHLCV bar. */
    DAILY_CANDLE(1, "daily candle"),

    /** A whole fundamentals page: ratios plus the statements on it. */
    FUNDAMENTALS_SNAPSHOT(7, "fundamentals"),

    /** One annual statement table. */
    ANNUAL_STATEMENT(200, "annual statements"),

    /** One quarterly results table. */
    QUARTERLY(120, "quarterly results"),
}

/** Whether a value can still be presented as current. */
enum class FreshnessState {
    /** Inside the window for its kind. */
    FRESH,

    /** Outside the window: still true, but no longer current. Must be labelled as old. */
    STALE,

    /** There is no value at all. */
    MISSING,
}

/**
 * A freshness verdict with the arithmetic that produced it.
 *
 * [ageDays] is kept rather than discarded so the UI can render "as of 02 Oct 2026 (214 days
 * old)" without re-deriving the date difference — and so a test can assert the number rather
 * than the string.
 */
data class Freshness(
    val kind: FreshnessKind,
    val state: FreshnessState,
    /** `null` when there is nothing to judge, which is [FreshnessState.MISSING] by definition. */
    val asOf: LocalDate?,
    val today: LocalDate,
    /** Whole days from [asOf] to [today]; `null` when there is no [asOf]. */
    val ageDays: Long?,
    val note: String? = null,
) {
    val isFresh: Boolean get() = state == FreshnessState.FRESH
    val isStale: Boolean get() = state == FreshnessState.STALE
    val isMissing: Boolean get() = state == FreshnessState.MISSING

    /** User-facing copy. plan.md §10 and §1.4 rule 3: the as-of date is never optional. */
    val description: String get() = FreshnessPolicy.describe(this)
}

/**
 * plan.md §10's staleness policy, as pure functions with an injectable clock.
 *
 * ### `LocalDate.now()` is a parameter, always
 * Every function takes `today: LocalDate` with a default. That is not a convenience: a
 * freshness policy is exactly the kind of code that is untestable when it reaches for the
 * system clock, and a boundary bug here ("is 7 days stale on the 7th day?") is invisible until
 * a user sees a wrong label. The default is [LocalDate.now] in **Asia/Kolkata**, because
 * "today" for a market is the market's day — the 19:00 IST bhavcopy lands after midnight in
 * some other zones, and the same instant must not produce two different ages.
 *
 * ### The one rule that matters
 * An old value is [Confidence.STALE], not silently [Confidence.SINGLE].
 * [Confidence.SINGLE] means "one source said this and nothing contradicted it", which is a
 * statement about *provenance*. A stale figure is a statement about *time*. Reporting a
 * 9-month-old fundamentals page as `SINGLE` would claim a confidence the app has not earned,
 * and plan.md §5.1's per-field merge would carry that claim into every consumer.
 */
object FreshnessPolicy {

    /** Indian market time. "Today" for anything the app prices or parses. */
    val MARKET_ZONE: ZoneId = ZoneId.of("Asia/Kolkata")

    /** `today` in market time. The injectable-clock default for every function below. */
    fun todayInMarket(): LocalDate = LocalDate.now(MARKET_ZONE)

    /**
     * The verdict for a value of kind [kind] read on [today].
     *
     * * `asOf == null` → [FreshnessState.MISSING].
     * * `0 ≤ age ≤ windowDays` → [FreshnessState.FRESH]. **Exactly the window is fresh.**
     * * `age > windowDays` → [FreshnessState.STALE].
     * * `age < 0` → [FreshnessState.FRESH] with a note: an as-of date in the future is a clock
     *   or timezone mistake, not fresh data, and silently accepting it would let a bad row
     *   look permanent.
     */
    fun evaluate(
        asOf: LocalDate?,
        today: LocalDate = LocalDate.now(MARKET_ZONE),
        kind: FreshnessKind = FreshnessKind.FUNDAMENTALS_SNAPSHOT,
    ): FreshnessState = inspect(asOf, today, kind).state

    /** [evaluate] with the date arithmetic retained. */
    fun inspect(
        asOf: LocalDate?,
        today: LocalDate = LocalDate.now(MARKET_ZONE),
        kind: FreshnessKind = FreshnessKind.FUNDAMENTALS_SNAPSHOT,
    ): Freshness {
        if (asOf == null) {
            return Freshness(
                kind = kind,
                state = FreshnessState.MISSING,
                asOf = null,
                today = today,
                ageDays = null,
                note = "no ${kind.label} data has been fetched for this symbol yet",
            )
        }
        val age = ChronoUnit.DAYS.between(asOf, today)
        return when {
            age < 0 -> Freshness(
                kind = kind,
                state = FreshnessState.FRESH,
                asOf = asOf,
                today = today,
                ageDays = age,
                note = "as-of date $asOf is after today ($today): a clock or timezone mistake, " +
                    "not fresh data",
            )
            age <= kind.windowDays -> Freshness(
                kind = kind,
                state = FreshnessState.FRESH,
                asOf = asOf,
                today = today,
                ageDays = age,
            )
            else -> Freshness(
                kind = kind,
                state = FreshnessState.STALE,
                asOf = asOf,
                today = today,
                ageDays = age,
                note = "older than the ${kind.windowDays}-day window for ${kind.label}",
            )
        }
    }

    /**
     * The string a user reads under a figure.
     *
     * plan.md §1.4 rule 3 makes the source and the as-of date mandatory, and §10 makes
     * staleness visible, so both live in the same sentence:
     *
     * ```
     * as of 02 Oct 2026 (7 days old)
     * as of 02 Oct 2026 (today)
     * as of 02 Oct 2026 (214 days old, older than the 200-day window for annual statements)
     * no annual statements have been fetched for this symbol yet
     * ```
     *
     * A stale line always names the window it exceeded, because "214 days old" on its own
     * invites the reader to guess whether that is bad; against a 200-day statement window it
     * is not a judgement call.
     */
    fun describe(
        asOf: LocalDate?,
        today: LocalDate = LocalDate.now(MARKET_ZONE),
        kind: FreshnessKind = FreshnessKind.FUNDAMENTALS_SNAPSHOT,
    ): String = describe(inspect(asOf, today, kind))

    fun describe(freshness: Freshness): String {
        val asOf = freshness.asOf
            ?: return "no ${freshness.kind.label} data has been fetched for this symbol yet"
        val age = freshness.ageDays ?: 0L
        if (age < 0) {
            return "as of ${format(asOf)} (this date is after today, ${format(freshness.today)})"
        }
        val ageText = when (age) {
            0L -> "today"
            1L -> "1 day old"
            else -> "$age days old"
        }
        return when (freshness.state) {
            FreshnessState.STALE -> "as of ${format(asOf)} ($ageText, older than the " +
                "${freshness.kind.windowDays}-day window for ${freshness.kind.label})"
            // FRESH: the as-of date and its age are the whole message.
            FreshnessState.FRESH, FreshnessState.MISSING -> "as of ${format(asOf)} ($ageText)"
        }
    }

    /**
     * [Confidence] for a value of [kind] read on [today], given the provenance [parsed].
     *
     * Staleness overrides provenance: `SINGLE`/`AGREED`/`CONFLICT` all become
     * [Confidence.STALE] once the window is exceeded, because all three are claims about
     * cross-source agreement and none of them says "and it was current". A conflict between
     * two stale sources is not a conflict worth showing — it is two old numbers; staleness is
     * the more important fact.
     */
    fun confidenceFor(
        asOf: LocalDate?,
        today: LocalDate = LocalDate.now(MARKET_ZONE),
        kind: FreshnessKind = FreshnessKind.FUNDAMENTALS_SNAPSHOT,
        parsed: Confidence = Confidence.SINGLE,
    ): Confidence = when (evaluate(asOf, today, kind)) {
        FreshnessState.STALE -> Confidence.STALE
        else -> parsed
    }

    /** `dd MMM yyyy`, e.g. `02 Oct 2026`. Locale-independent. */
    fun format(date: LocalDate): String {
        val month = MONTH_ABBREVIATIONS[date.monthValue - 1]
        return String.format(Locale.ROOT, "%02d %s %04d", date.dayOfMonth, month, date.year)
    }

    private val MONTH_ABBREVIATIONS = listOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
    )
}

/**
 * [Ratios] with [Confidence.STALE] applied when the page is older than
 * [FreshnessKind.FUNDAMENTALS_SNAPSHOT]'s window.
 *
 * A `Ratios` snapshot has no way to say "these figures are nine months old" — [Confidence] is its
 * only provenance channel — so this is the single place that fact can reach the UI, the
 * share-image and the red-flag engine. It returns `this` unchanged when the snapshot is fresh,
 * so it is cheap and safe to call on every render:
 *
 * ```kotlin
 * val shown = snapshot.ratios.withFreshness()          // market clock by default
 * val tested = snapshot.ratios.withFreshness(today)    // injected clock
 * ```
 *
 * Declared at file scope rather than inside [FreshnessPolicy] because a Kotlin member extension
 * cannot be called without a `with(FreshnessPolicy)` scope, and every caller in this app would
 * otherwise have to write one.
 */
fun Ratios.withFreshness(
    today: LocalDate = LocalDate.now(FreshnessPolicy.MARKET_ZONE),
): Ratios {
    val resolved = FreshnessPolicy.confidenceFor(
        asOf = asOf,
        today = today,
        kind = FreshnessKind.FUNDAMENTALS_SNAPSHOT,
        parsed = confidence,
    )
    return if (resolved == confidence) this else copy(confidence = resolved)
}