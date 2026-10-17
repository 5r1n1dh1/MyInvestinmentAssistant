package com.mia.app.data.fundamentals

import com.mia.app.domain.model.Ratios
import java.time.LocalDate

/**
 * A second source of fundamentals ratios, so plan.md §5.1's merge has two sides to merge.
 *
 * ### Why this interface exists when exactly one implementation ships today
 * `FundamentalsDataSource.divergences()` returns an empty list on every symbol, and
 * `docs/VERIFICATION.md` records that as a **known capability gap** rather than a fact: with one
 * source there is nothing to disagree with, so plan.md §5.1's whole "show both numbers, do not
 * average" rule is dead code — a rule that can never fire is indistinguishable from a rule that
 * works.
 *
 * The cheapest way to make it non-empty **honestly** is to let a person supply the second side. No
 * second scraper is written: that would mean a new network source, new robots.txt compliance work,
 * and a new failure surface, for a feature that only needs *some* second number for *some* fields.
 *
 * ### What an implementation must and must not do
 * * **Must** return a [Ratios] with only the fields it actually has. A field it cannot supply is
 *   `null`, never `0.0` — the same rule every parser here follows, and the reason
 *   `FundamentalsAggregator.merge` can tell "disagreed" from "only one side had it".
 * * **Must** carry its own `asOf`. A stale second number is a legitimate input; a second number
 *   with no date is not, because §5.1's conflict chip shows both figures and the reader has to be
 *   able to judge which is older.
 * * **Must not** override or edit the screener.in side. Precedence lives on `RatioFieldId`, which
 *   is what makes "merge per field, never per source" structural rather than a caller convention.
 * * **Must not** fill a field by inference. "No value" is a legitimate answer and an honest one.
 */
interface FundamentalsSource {

    /** Stable identifier for this source, recorded on every figure it produces. */
    val name: String

    /**
     * The ratios this source has for [symbol], or `null` when it has none.
     *
     * `null` and an all-`null` [Ratios] are both honest answers and the caller may treat them the
     * same way; [name] and the as-of date must still be meaningful so the coverage panel can say
     * what was consulted.
     */
    fun ratiosFor(symbol: String, asOf: LocalDate): Ratios?
}

/**
 * A [FundamentalsSource] the person using the app supplied.
 *
 * ### The honesty contract, stated once for every implementation
 * A value here is **not** measured by this app: nobody checked it, no parser validated it and no
 * second page will agree with it tomorrow. That does not make it useless — a broker's terminal, a
 * paid terminal export or a spreadsheet someone maintains are all real sources a person already has
 * — but it does make three rules non-negotiable:
 *
 * * [Ratios.confidence] is never better than [com.mia.app.domain.model.Confidence.SINGLE]. It is
 *   not `AGREED`, because agreement has to be measured, and it is not `CONFLICT`, because this
 *   source has not been compared against anything yet.
 * * [Ratios.source] is the domain's [com.mia.app.domain.model.Source.SCREENER_IN]-**free**
 *   alternative — there is no such constant, so implementations pass the source the merged result
 *   actually has and the UI says the second figure was imported. See [ImportedRatiosSource].
 * * Every field is `null` or a number. There is no partial credit and no "estimated".
 *
 * ### Why [asOf] is required and defaults to nothing
 * plan.md §5.1 renders both numbers of a conflict and lets the reader judge them, which is only
 * possible with dates. An import with no date cannot participate in a conflict at all, so it is
 * rejected at construction rather than being given today's date — inventing one would make a
 * three-year-old spreadsheet look current.
 */
interface ManualFundamentalsSource : FundamentalsSource {

    /**
     * Import [ratios] for [symbol] as of [asOf].
     *
     * @throws IllegalArgumentException when the symbol is blank or [asOf] is in the future, both
     *   of which are a mistake in the *file*, not a network outcome.
     */
    fun import(symbol: String, asOf: LocalDate, ratios: Ratios)
}