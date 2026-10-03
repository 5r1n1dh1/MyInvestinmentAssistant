package com.mia.app.domain.redflag.fundamentals

import com.mia.app.domain.model.Evidence
import com.mia.app.domain.model.Sector
import com.mia.app.domain.model.Source
import com.mia.app.domain.ratios.FreshnessPolicy
import com.mia.app.domain.ratios.SectorResolver
import java.time.LocalDate
import java.util.Locale

/**
 * Number formatting and evidence-row builders shared by the ten fundamentals flags.
 *
 * ### Why one formatter
 * plan.md §1.4 rule 3 requires every fundamentals figure to show a source and an as-of date,
 * and `RedFlag.init` already refuses a flag with no evidence. What it does not check is that a
 * figure is *readable*: `"8.748859"` next to `"8.75×"` next to `"₹402962 Cr"` across five rules
 * is how a user stops trusting the evidence list. One formatter also means one place to prove
 * locale-independence — a device set to a locale that groups digits differently must not print
 * `"8,75"` — which is why every format here pins [Locale.ROOT], the same reason
 * `RatioComputation.trim` does.
 *
 * ### The rule about as-of dates
 * `Evidence.asOf` is the date the **datum** belongs to, not the date the rule ran:
 *
 * * a fiscal-year figure → 31 March of that year (Indian fiscal years end in March, and the page
 *   labels the column `Mar 2026`);
 * * `Stock P/E` and `Market Cap` → the **scrape date** (`Ratios.asOf`), because `top-ratios` is a
 *   live value with no publication stamp and presenting it under a March date would imply the
 *   multiple was struck at the fiscal year end (spec §11 rule 9);
 * * a threshold or a definition row → the as-of date of the window it was applied to, because
 *   the reader is asking "which data was this rule applied to", not "when was this constant
 *   written".
 */
internal object FundamentalsEvidence {

    /** `FY2026` — how a fiscal year is named in every label. */
    fun fyLabel(year: Int): String = "FY$year"

    /** `2026-03-31` for a fiscal year's end, the as-of date of every statement figure. */
    fun fiscalYearEnd(year: Int): LocalDate = FundamentalsPolicy.fiscalYearEnd(year)

    /** `31 Mar 2026`, the form a user reads. */
    fun formatDate(date: LocalDate): String = FreshnessPolicy.format(date)

    /** `₹192,113 Cr`, with the sign inside the amount as the page prints it (`₹-12,400 Cr`). */
    fun crore(value: Double, decimals: Int = 0): String = "₹${number(value, decimals)} Cr"

    /** `₹22.40` — a per-share rupee figure. Never a percent and never a multiple. */
    fun rupees(value: Double, decimals: Int = 2): String = "₹${number(value, decimals)}"

    /** `30.1 %`. A percent sign is separated by a space, as the page prints it (`113 %`). */
    fun percent(value: Double, decimals: Int = 1): String = "${number(value, decimals)} %"

    /** `8.75×` — a multiple, never a percent (a coverage ratio is not "855 %"). */
    fun multiple(value: Double, decimals: Int = 2): String = "${number(value, decimals)}×"

    /** `20 days`. */
    fun days(value: Double): String = "${number(value, 0)} days"

    /** `+13` / `-5` — a change, always signed so the direction is unambiguous. */
    fun signed(value: Double, decimals: Int = 0): String {
        val body = number(kotlin.math.abs(value), decimals)
        return if (value < 0) "-$body" else "+$body"
    }

    /**
     * A locale-independent grouped number.
     *
     * `%.0f` with [Locale.ROOT] gives `"192,113"`, and `-12400.0` gives `"-12,400"`, so the sign
     * lands where a reader expects it and no locale can move it.
     */
    fun number(value: Double, decimals: Int = 0): String =
        String.format(Locale.ROOT, "%,.${decimals}f", value)

    /** A statement row: a published figure, so [Source.SCREENER_IN] and a fiscal-year date. */
    fun statement(label: String, value: String, year: Int): Evidence =
        Evidence(label, value, Source.SCREENER_IN, fiscalYearEnd(year))

    /** A computed ratio: [Source.DERIVED], dated by the window it was computed over. */
    fun derived(label: String, value: String, asOf: LocalDate): Evidence =
        Evidence(label, value, Source.DERIVED, asOf)

    /** The threshold row every flag carries, dated by the window it was applied to. */
    fun threshold(label: String, value: String, asOf: LocalDate): Evidence =
        Evidence(label, value, Source.DERIVED, asOf)

    /**
     * A row for something our sources do not publish.
     *
     * spec §1 rule 4 permits a "not available" row **only** where the flag is inherently limited
     * — never as a stand-in for a measurement. Both current uses are inherent: debt maturities
     * (spec §4.3 — *"we cannot see maturities, so the evidence must say so"*) and the goodwill
     * and pledge gaps of spec §2.3.
     */
    fun notAvailable(label: String, detail: String, asOf: LocalDate): Evidence =
        Evidence(label, detail, Source.DERIVED, asOf)

    /** The sector row spec §6.2 requires on every flag that survives with an unresolved sector. */
    fun sectorRow(sector: Sector, asOf: LocalDate): Evidence? =
        FundamentalsSectors.unresolvedSectorEvidence(sector)?.let {
            Evidence("Sector", it, Source.DERIVED, asOf)
        }

    /** `Banking`, `Information technology`, … — never an enum constant rendered raw. */
    fun sectorName(sector: Sector): String = SectorResolver.displayNameOf(sector)
}