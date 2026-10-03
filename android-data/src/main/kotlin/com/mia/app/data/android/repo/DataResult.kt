package com.mia.app.data.android.repo

import com.mia.app.domain.model.ParserHealth
import com.mia.app.domain.model.Source
import java.time.LocalDate

/**
 * Why a read produced no value. **Never** "there is nothing to report".
 *
 * plan.md §12: *"A parser must return a health flag, not an empty list. Silent-empty is
 * indistinguishable from 'no red flags' — the most dangerous possible failure."* Every
 * repository therefore returns a [DataResult], and [DataResult.init] makes it structurally
 * impossible to hand back a null value with no explanation.
 */
enum class DataFailureReason {
    /** plan.md §3.1: every NSE host answered 404/410 — today's file is not out yet. Expected. */
    NOT_PUBLISHED,

    /** No host answered 2xx and none answered 404: DNS, TLS, 403/429/5xx. */
    SOURCE_UNAVAILABLE,

    /** Bytes arrived, the parser rejected them. A source changed shape (plan.md §13). */
    UNPARSEABLE,

    /** Nothing cached locally and no fetch was attempted or allowed. */
    NO_DATA_CACHED,

    /**
     * Nothing is cached **because no fetch has ever been attempted on this device**.
     *
     * Distinct from [NO_DATA_CACHED] because the two call for different words on screen. Before
     * the first successful refresh the honest sentence is "nothing has been downloaded yet —
     * fetch now", not "no data is stored on this device", which reads as a permanent condition
     * the user cannot act on. A missing first run was indistinguishable from a dead source; this
     * value is what lets the UI tell them apart and offer the fetch.
     */
    NOT_ATTEMPTED,

    /**
     * A fetch succeeded but the published files contained **no bars for this symbol**.
     *
     * Also distinct on purpose: this is a fact about the symbol (delisted, newly listed, series
     * filtered out by plan.md §3.1's `EQ`/`SM` rule), and retrying will not change it. Rendering
     * it as "not stored" would send the user to a refresh button that cannot help.
     */
    NO_ROWS_FOR_SYMBOL,

    /** The symbol master is not bundled, so search and resolution cannot run. */
    SYMBOL_BOOK_UNAVAILABLE,

    /**
     * A fetch succeeded and bars were stored, but the series is still shorter than plan.md §6.4's
     * hard gate (1D ≥ 60, 1W ≥ 40), so no pattern may be scored.
     *
     * A stated refusal, not an error. Kept separate from [NOT_ENOUGH_HISTORY] so the tab can say
     * *how many* bars it has and *why* there are not more — the answer is nearly always "the
     * archive we can reach only goes back N sessions", which the user can act on by understanding
     * it rather than by tapping refresh again.
     */
    NOT_ENOUGH_HISTORY,

    /** History could not be deepened: every host unreachable, or the archive has no more days. */
    HISTORY_UNAVAILABLE,

    /** Today is not a trading day (plan.md §10's guard), so there is nothing new to fetch. */
    NOT_TRADING_DAY,

    /** The user has not granted POST_NOTIFICATIONS, so the alert was not delivered. */
    PERMISSION_DENIED,

    /** The caller's own precondition failed — an unresolvable symbol, an empty watchlist. */
    NOTHING_TO_DO,

    /** Something the caller must treat as a bug; the message carries the detail. */
    UNEXPECTED,
}

/**
 * One failure, with a sentence the UI can show.
 *
 * [message] is pre-rendered rather than built at the call site because plan.md §8.3 requires
 * factual wording and there is exactly one place in this module that is allowed to write it.
 */
data class DataFailure(
    val reason: DataFailureReason,
    val message: String,
    /** HTTP status or exception class, when there was one. Diagnostics only. */
    val detail: String? = null,
) {
    init {
        require(message.isNotBlank()) {
            "a failure must explain itself — an unexplained empty result is what plan.md §12 forbids"
        }
    }
}

/**
 * Provenance attached to every repository result — plan.md §1.4 rule 3 and plan.md §10.
 *
 * ### Why this is not optional
 * *"Every fundamentals figure shows source + 'as of' date"* and *"staleness must be visible in
 * the UI"* are the two rules that decide whether a user trusts the app. A repository that
 * returned a bare `Ratios` would push that obligation onto every call site, and one call site
 * would eventually forget. Carrying it in the result makes forgetting impossible.
 *
 * [parserHealth] is present whenever a parser was involved — a fetched value with no health
 * attached is exactly the state in which "the page changed" is indistinguishable from "this
 * company is clean".
 */
data class Provenance(
    /** Which source the value came from. Never hidden. */
    val source: Source,
    /** The data's own date — the newest bar or the latest statement period. */
    val asOf: LocalDate?,
    /** When this device read the data, in market time. */
    val fetchedOn: LocalDate,
    /** Non-null whenever a parser produced the value. */
    val parserHealth: ParserHealth? = null,
    /** Transparency notes: bars below a gate, a fallback host, a missing delivery column. */
    val notes: List<String> = emptyList(),
) {
    val isHealthy: Boolean get() = parserHealth?.ok ?: true
}

/**
 * The result of one repository read: a value plus its provenance, or a typed failure.
 *
 * ### The invariant
 * ```kotlin
 * require(value != null || failure != null)   // never an unexplained empty
 * require(value == null || failure == null)   // never a value that hides a problem
 * ```
 * A repository that has something real to say and nothing wrong with it returns `Ok`. A
 * repository that cannot answer returns [unavailable]. There is no third state in which a caller
 * receives `null` and has to guess — which is the guessing that plan.md §12 is about.
 */
data class DataResult<T>(
    val value: T?,
    val provenance: Provenance,
    val failure: DataFailure? = null,
) {
    init {
        require(value != null || failure != null) {
            "a DataResult must carry either a value or a typed failure; a null value with no " +
                "explanation is the silent-empty failure of plan.md §12"
        }
        require(value == null || failure == null) {
            "a DataResult that carries a value must not also carry a failure"
        }
    }

    val isOk: Boolean get() = failure == null

    /** True when the value exists but the data behind it is incomplete. */
    val isDegraded: Boolean
        get() = isOk && parserHealth?.ok == false

    val parserHealth: ParserHealth? get() = provenance.parserHealth

    val asOf: LocalDate? get() = provenance.asOf

    val source: Source get() = provenance.source

    /**
     * The value, or a thrown [DataUnavailableException]. For the rare call site that genuinely
     * cannot proceed — as opposed to one that must render the failure.
     */
    fun orThrow(): T = value ?: throw DataUnavailableException(failure ?: DataFailure(
        reason = DataFailureReason.UNEXPECTED,
        message = "DataResult carried neither a value nor a failure",
    ))

    /** The value, or `null`. Deliberately named so the `?` is visible at every call site. */
    fun valueOrNull(): T? = value

    companion object {

        /** A successful read. [health] is `null` only when no parser was involved. */
        fun <T> ok(
            value: T,
            source: Source,
            fetchedOn: LocalDate,
            asOf: LocalDate? = null,
            health: ParserHealth? = null,
            notes: List<String> = emptyList(),
        ): DataResult<T> = DataResult(
            value = value,
            provenance = Provenance(
                source = source,
                asOf = asOf,
                fetchedOn = fetchedOn,
                parserHealth = health,
                notes = notes,
            ),
        )

        /** A typed failure. The UI renders [DataFailure.message] rather than an empty screen. */
        fun <T> unavailable(
            failure: DataFailure,
            source: Source = Source.DERIVED,
            fetchedOn: LocalDate,
            asOf: LocalDate? = null,
            health: ParserHealth? = null,
            notes: List<String> = emptyList(),
        ): DataResult<T> = DataResult(
            value = null,
            provenance = Provenance(
                source = source,
                asOf = asOf,
                fetchedOn = fetchedOn,
                parserHealth = health,
                notes = notes,
            ),
            failure = failure,
        )

        /**
         * The tricky one: an **empty collection** that is a real answer.
         *
         * "No red flags", "no corporate actions today" and "the watchlist is empty" are true
         * facts, and turning them into a failure would train users to ignore the failure state.
         * So an empty list is only ever an `Ok` when the caller can say *why* it is empty —
         * which is what [Provenance.parserHealth] plus [Provenance.notes] carry — and the call
         * sites below are written so that a health that is not ok, or a note that says something
         * could not be checked, downgrades the result to a failure instead.
         */
        fun <T> emptyIsFailure(
            emptyMessage: String,
            reason: DataFailureReason,
            source: Source,
            fetchedOn: LocalDate,
            asOf: LocalDate? = null,
            health: ParserHealth? = null,
            notes: List<String> = emptyList(),
        ): DataResult<T> = unavailable(
            failure = DataFailure(reason = reason, message = emptyMessage),
            source = source,
            fetchedOn = fetchedOn,
            asOf = asOf,
            health = health,
            notes = notes,
        )
    }
}

/**
 * Thrown by [DataResult.orThrow] only. Nothing in this module throws it implicitly; it exists so
 * a caller who has genuinely decided it cannot proceed without a value says so at the call site.
 */
class DataUnavailableException(val failure: DataFailure) :
    Exception(failure.message)

/**
 * "Today" for the app, in market time.
 *
 * plan.md §10's whole point is that a session has a date, and the bhavcopy lands at ~18:00 IST
 * — after midnight in some other zones. Reading the clock in one place means the same instant
 * can never produce two different "as of" dates.
 */
fun todayInMarket(): LocalDate = LocalDate.now(com.mia.app.domain.ratios.FreshnessPolicy.MARKET_ZONE)

/**
 * What an on-demand freshness check did, and what the caller should show.
 *
 * ### Why this is its own type and not a [DataResult]
 * [DataResult] models *a read*, and its contract is "a value, or a typed failure". A freshness
 * check is neither: it always has an honest answer — "the cache is current", "a fetch ran and
 * wrote 3,051 bars", "the fetch failed but your existing bars are untouched" — and that answer
 * must stay reportable **even when the fetch failed**. Reusing [DataResult] would force one of
 * those situations to be expressed as a failure, which is precisely the conflation this type
 * exists to prevent.
 */
data class EnsureResult(
    val symbol: String,
    /** Did this call perform a network fetch? `false` means the cache was served as-is. */
    val fetched: Boolean,
    /** Bars this call appended to the symbol's existing series. */
    val barsAdded: Int = 0,
    /** Newest stored bar's date after the check — the "as of" the tab should show. */
    val asOf: LocalDate? = null,
    /** Why no fetch was made, or why the fetch could not help. Never blank. */
    val note: String,
    /** Set when a fetch was attempted and failed. The cached series is still intact. */
    val failure: DataFailure? = null,
) {
    /** True when the cache already covered the newest expected session, so nothing was fetched. */
    val wasFresh: Boolean get() = !fetched

    companion object {
        /**
         * The cache already holds the newest expected session, so no request was made.
         *
         * This is the common path and the reason the check is cheap: a user who opens ten stocks
         * from a cache fetched an hour ago pays zero requests.
         */
        fun fresh(
            symbol: String,
            asOf: LocalDate?,
            barCount: Int,
            expectedThrough: LocalDate?,
        ) = EnsureResult(
            symbol = symbol,
            fetched = false,
            asOf = asOf,
            note = buildString {
                append("no download needed: the newest stored session is ")
                append(asOf ?: "unknown")
                if (expectedThrough != null) {
                    append(", which covers the latest expected session ($expectedThrough)")
                }
                append("; ").append(barCount).append(" bar(s) served from the on-device cache")
            },
        )

        /** A fetch ran and completed. [barsAdded] is this symbol's gain, not the market's. */
        fun fetched(
            symbol: String,
            asOf: LocalDate?,
            barsAdded: Int,
            note: String,
        ) = EnsureResult(
            symbol = symbol,
            fetched = true,
            barsAdded = barsAdded,
            asOf = asOf,
            note = note,
        )

        /** A fetch was attempted and did not succeed. The cached series is untouched. */
        fun failed(
            symbol: String,
            asOf: LocalDate?,
            barCount: Int,
            failure: DataFailure,
        ) = EnsureResult(
            symbol = symbol,
            fetched = true,
            asOf = asOf,
            note = "the refresh did not succeed, so the ${barCount} already-stored bar(s) are " +
                "being shown unchanged rather than replaced with nothing",
            failure = failure,
        )
    }
}