package com.mia.app.ui.di

import com.mia.app.data.android.repo.DataFailure
import com.mia.app.data.android.repo.DataFailureReason
import com.mia.app.data.android.repo.DataResult

/**
 * The one place `DataResult` is turned into what a screen can render.
 *
 * ### Why a shared file rather than nine private copies
 * plan.md §12 makes a *silent empty* the most dangerous failure in the product, and
 * `DataResult` is the type that exists to prevent it: a repository can never hand back a `null`
 * with no explanation. Turning that into a UI decision — "is this an honest absence, or is this a
 * failure the reader must be told about?" — is the same decision on all nine adapters, so it is
 * decided once here. Nine private copies is nine chances for one of them to answer "empty" where
 * the others answer "throw".
 *
 * ### The three outcomes, and nothing else
 * | `DataFailureReason` | UI decision | Why |
 * |---|---|---|
 * | `NOT_PUBLISHED`, `NOT_TRADING_DAY` | `isNotPublished = true` — **not an error** | plan.md §3.1: today's file is simply not out yet. The tab keeps the last successful session with its as-of date and adds a note. A red banner for a file that has not been published is the wrong answer. |
 * | `NO_DATA_CACHED`, `NOT_ENOUGH_HISTORY` | **an honest absence**, not a throw | "Nothing has been fetched yet" is a real state the screens are built to render (`TechnicalsUiState.hasEnoughHistory`, `AccuracyUiState.hasNoOutcomes`). Returning an empty list *or* throwing would each misdescribe it: an empty chart reads as "this stock has no history", a red banner reads as "the read failed". |
 * | everything else | **throw** | an unreachable source, an unparseable payload, a missing bundled asset. The screen's `ErrorState` with a retry is the truth. |
 *
 * ### `isTransient` is only ever a hint about the retry button
 * `true` when a second attempt could plausibly succeed (network, a parse the source may fix, an
 * unexpected error), `false` when it could not (the asset is missing, the caller's precondition
 * failed, a permission was denied). It never decides *whether* to throw — only the reason string
 * and the `isNotPublished` flag do.
 */

/**
 * `true` when this failure means "there is genuinely nothing here yet".
 *
 * All four reasons are absences the screens already have a designed state for, so they must never
 * reach `ErrorState`:
 * * [DataFailureReason.NOT_ATTEMPTED] — nothing has ever been fetched on this device. The tab shows
 *   "no data downloaded yet" and offers the fetch, which is the whole reason this is a distinct
 *   value rather than a flavour of `NO_DATA_CACHED`.
 * * [DataFailureReason.NO_DATA_CACHED] — a fetch has happened but the newest expected session is
 *   still missing.
 * * [DataFailureReason.NOT_ENOUGH_HISTORY] — plan.md §6.4's hard gate is unmet, so no score may be
 *   emitted. The engine and the screens both render that as a stated refusal.
 * * [DataFailureReason.NO_ROWS_FOR_SYMBOL] — the published files contain nothing for this ticker.
 *   Rendering it as an empty chart would assert the stock has no history, which is a different and
 *   much stronger claim.
 */
fun DataFailure.isNothingStored(): Boolean = when (reason) {
    DataFailureReason.NOT_ATTEMPTED,
    DataFailureReason.NO_DATA_CACHED,
    DataFailureReason.NOT_ENOUGH_HISTORY,
    DataFailureReason.NO_ROWS_FOR_SYMBOL,
    -> true

    else -> false
}

/**
 * `true` when the reader is looking at a device that has never downloaded anything.
 *
 * The distinction from [DataFailure.isNothingStored] is actionable: this is the *only* one of the
 * four absences that a single tap can fix, so it is what the Technicals tab's empty state keys its
 * "fetch now" affordance on. Rendering the same button for a delisted ticker or an unmet bar gate
 * teaches the reader that the button does nothing.
 */
fun DataFailure.neverAttempted(): Boolean = reason == DataFailureReason.NOT_ATTEMPTED

/**
 * `true` when the symbols *are* right but the series is too short — so the reader needs more
 * history, not a different symbol.
 */
fun DataFailure.needsMoreHistory(): Boolean =
    reason == DataFailureReason.NOT_ENOUGH_HISTORY ||
        reason == DataFailureReason.HISTORY_UNAVAILABLE

/**
 * `true` when this failure is plan.md §3.1's "the file is not out yet".
 *
 * A **not-yet-published** condition is not an error at all, and the three screens that render it
 * (`Technicals`, `Patterns`, `StockDetail`) each keep the last good session on screen and add a
 * note instead of a banner. `NOT_TRADING_DAY` belongs here for the same reason: on a Saturday
 * there is no file to fetch and nothing has gone wrong.
 */
fun DataFailure.isNotPublishedYet(): Boolean = when (reason) {
    DataFailureReason.NOT_PUBLISHED,
    DataFailureReason.NOT_TRADING_DAY,
    -> true

    else -> false
}

/**
 * Whether a retry button is worth offering for this failure.
 *
 * Read from the reason rather than guessed from the message: `SOURCE_UNAVAILABLE` and
 * `UNPARSEABLE` are both worth another attempt (plan.md §10's backoff, and plan.md §13's
 * "the page changed" case), while a missing bundled asset, an unresolved symbol and a denied
 * permission will fail identically forever, and a button that can never work is worse than none.
 */
fun DataFailure.isWorthRetrying(): Boolean = when (reason) {
    DataFailureReason.SOURCE_UNAVAILABLE,
    DataFailureReason.UNPARSEABLE,
    DataFailureReason.UNEXPECTED,
    DataFailureReason.PERMISSION_DENIED,
    // Worth retrying because the reader can fix it with a tap: nothing has been downloaded yet, so
    // the whole point is to offer the fetch.
    DataFailureReason.NOT_ATTEMPTED,
    // The history walk is resumable and its failures are usually the network, so another pass is
    // the documented remedy rather than a dead end.
    DataFailureReason.HISTORY_UNAVAILABLE,
    -> true

    DataFailureReason.NOT_PUBLISHED,
    DataFailureReason.NOT_TRADING_DAY,
    DataFailureReason.NO_DATA_CACHED,
    DataFailureReason.SYMBOL_BOOK_UNAVAILABLE,
    DataFailureReason.NOT_ENOUGH_HISTORY,
    // Deliberately **not** worth retrying: the published files contain no rows for this ticker, and
    // re-downloading the same files will not invent any. A retry button here would be a lie.
    DataFailureReason.NO_ROWS_FOR_SYMBOL,
    DataFailureReason.NOTHING_TO_DO,
    -> false
}

/**
 * [DataFailure.message], plus the diagnostic detail when there is one.
 *
 * `DataFailure.detail` is documented as diagnostics only — an HTTP status, an exception class, a
 * bar count — so it is appended in parentheses rather than promoted into the sentence. It is
 * appended at all because a bare "the source is unavailable" gives a reader nothing to report,
 * and one exact number (`httpCode=429`) is the difference between a useful bug report and a
 * useless one. Never a class name on its own: [DataFailure] already guarantees the message is
 * non-blank, so the fallback below can only be reached for a failure built by hand.
 */
fun DataFailure.userFacingReason(): String {
    val detail = detail?.trim().orEmpty()
    if (detail.isEmpty()) return message
    return "$message ($detail)"
}

/**
 * The typed failure, or `null` when the read succeeded.
 *
 * Never `null` in practice on the failure path: `DataResult.init` forbids `value == null` with
 * `failure == null`, so a caller that has already established there is no value always gets a real
 * [DataFailure] here. The `?: DataFailure(UNEXPECTED, …)` arm is the belt to that braces — a defect in
 * the storage layer would otherwise surface as a `NullPointerException` with no reason at all, which
 * is exactly the "unexplained empty" plan.md §12 forbids.
 *
 * Returning the [DataFailure] rather than a translated screen exception is deliberate: each adapter
 * owns its own exception type, and doing the translation here would hide that mapping in a file that
 * cannot see the screens.
 */
fun <T : Any> DataResult<T>.failureOrNull(): DataFailure? =
    if (isOk) null else failure ?: DataFailure(
        reason = DataFailureReason.UNEXPECTED,
        message = "the data layer reported a failure with no reason",
    )

/**
 * The list, or nothing — but only for a reason that genuinely means "nothing is stored".
 *
 * The guard is the whole point. A caller that wrote `valueOrNull() ?: emptyList()` would turn
 * `SOURCE_UNAVAILABLE` into an empty screen, which is plan.md §12's worst failure, and the compiler
 * would not have stopped them. Here an absent value that is *not* an honest absence throws, so the
 * mistake is impossible to make by accident.
 */
fun <T : Any> DataResult<List<T>>.valueOrEmptyList(): List<T> {
    val value = valueOrNull()
    if (value != null) return value
    val failure = failureOrNull()
    if (failure != null && failure.isNothingStored()) return emptyList()
    throw DataFailureException(failure ?: unexpectedFailure())
}

/** The value, or `null` — only when the absence is an honest one. See [valueOrEmptyList]. */
fun <T : Any> DataResult<T>.orNullWhenNothingStored(): T? {
    val value = valueOrNull()
    if (value != null) return value
    val failure = failureOrNull()
    if (failure != null && failure.isNothingStored()) return null
    throw DataFailureException(failure ?: unexpectedFailure())
}

/**
 * The value, or a throw carrying the typed failure.
 *
 * Used by the reads where "nothing stored" is *not* a designed state — a missing fundamentals page
 * and an unreachable symbol master both have to be shown as a failure, because a screen that renders
 * "nothing to report" for either would be making a statement about the app's coverage rather than
 * about one symbol.
 */
fun <T : Any> DataResult<T>.orDataFailure(): T =
    valueOrNull() ?: throw DataFailureException(failureOrNull() ?: unexpectedFailure())

/** The belt to `DataResult.init`'s braces. Never user-facing; always a defect report. */
private fun unexpectedFailure(): DataFailure = DataFailure(
    reason = DataFailureReason.UNEXPECTED,
    message = "the data layer reported a failure with no reason",
)

/**
 * A repository failure, carried unchanged to the adapter that has to translate it.
 *
 * Not user-facing and never rendered: an adapter catches it and builds its screen's own exception
 * type from [DataFailure]. It exists so the mapping from `DataFailureReason` to "throw / not
 * published / honest absence" is a single `when`-free call rather than a hand-rolled `?:` chain at
 * every repository call site.
 */
class DataFailureException(val failure: DataFailure) :
    Exception(failure.message)