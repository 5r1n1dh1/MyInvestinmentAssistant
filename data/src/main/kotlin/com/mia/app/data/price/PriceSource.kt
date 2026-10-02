package com.mia.app.data.price

import java.time.LocalDate

/**
 * The price layer's single seam (plan.md §3.7 roadmap note, §13 risk table).
 *
 * The NSE bhavcopy is **undocumented and unversioned**. plan.md's mitigation for "format or
 * URL changes silently → total price-layer failure" is to keep it behind an interface so the
 * blast radius is one file: a future `YahooPriceSource` or a renamed-column shim drops in
 * here, and nothing above this line — the EOD worker, Room, the pattern engine — changes.
 *
 * Implementations return a [BhavcopyParseResult], never a bare `List<Candle>`, so the
 * never-silently-empty rule of plan.md §12 cannot be bypassed by a caller.
 */
interface PriceSource {

    /**
     * Daily candles for the session of [date].
     *
     * A date whose file NSE has not published yet yields an empty [BhavcopyParseResult] with
     * [BhavcopyParseResult.fetchFailure] `[FetchFailure.NOT_PUBLISHED]` — an expected state,
     * not an error.
     */
    suspend fun daily(date: LocalDate): BhavcopyParseResult
}
