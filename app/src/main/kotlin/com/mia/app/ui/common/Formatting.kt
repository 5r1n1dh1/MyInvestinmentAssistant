package com.mia.app.ui.common

import com.mia.app.domain.model.Source
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs

/**
 * Number and date formatting for the whole app.
 *
 * ### Why `Locale.ROOT` and not the device locale
 * A comma is a decimal separator in much of the Indian subcontinent. `String.format` with the
 * default locale would render `₹1,234.50` as `₹1.234,50` on a German phone and `₹1,234.50` on an
 * American one — the same screen, two different numbers. Every formatter here is pinned to
 * [Locale.ROOT], and the digit grouping is done by hand so it is Indian (`1,16,770.00`) on every
 * device, matching the NSE bhavcopy the user may be looking at next to the phone.
 *
 * ### Why these are plain functions and not `NumberFormat.getInstance()`
 * `NumberFormat` resolves currency symbols and grouping from the default locale, which is exactly
 * the drift above. Hand-rolled grouping is nine lines, has no locale input, and cannot change
 * behaviour on a device update.
 */

/** `1,16,770.00` — the Indian grouping, two digits then threes. */
fun groupIndianDigits(value: String): String {
    val negative = value.startsWith("-")
    val body = if (negative) value.substring(1) else value
    val dot = body.indexOf('.')
    val integerPart = if (dot < 0) body else body.substring(0, dot)
    val fractionPart = if (dot < 0) "" else body.substring(dot)

    if (integerPart.length <= 3) {
        return (if (negative) "-" else "") + integerPart + fractionPart
    }
    val lastThree = integerPart.takeLast(3)
    val rest = integerPart.dropLast(3)
    val groupedRest = rest.reversed().chunked(2).joinToString(",").reversed()
    return (if (negative) "-" else "") + "$groupedRest,$lastThree$fractionPart"
}

/** Locale-independent fixed-point rendering. `decimals` is clamped into `0..6`. */
fun formatFixed(value: Double, decimals: Int = 2): String {
    if (value.isNaN()) return NOT_AVAILABLE
    if (value.isInfinite()) return if (value > 0) INFINITY_UPPER else INFINITY_LOWER
    val places = decimals.coerceIn(0, 6)
    val raw = String.format(Locale.ROOT, "%.${places}f", value)
    return if (places == 0) raw else groupIndianDigits(raw)
}

/**
 * A price in rupees. Two decimals, because an EOD close is quoted to the paisa and rounding it
 * to a rupee would make a chart's last bar disagree with the table under it.
 */
fun formatPrice(value: Double?): String =
    if (value == null) NOT_AVAILABLE else RUPEE + formatFixed(value, 2)

/** A percentage, already expressed in percent (so `0.74` renders as `0.74%`, not `74.00%`). */
fun formatPercent(value: Double?, decimals: Int = 2): String =
    if (value == null) NOT_AVAILABLE else formatFixed(value, decimals) + "%"

/** A signed percentage with an explicit `+` on a gain, so the sign is never inferred from colour. */
fun formatSignedPercent(value: Double?, decimals: Int = 2): String {
    if (value == null) return NOT_AVAILABLE
    val sign = if (value > 0.0) "+" else ""
    return sign + formatFixed(value, decimals) + "%"
}

/** A signed absolute change. */
fun formatSigned(value: Double?, decimals: Int = 2): String {
    if (value == null) return NOT_AVAILABLE
    val sign = if (value > 0.0) "+" else ""
    return sign + formatFixed(value, decimals)
}

/**
 * Volume in Indian market units, because `16,771,221` is not a number a retail reader parses.
 *
 * `1.00 L` = lakh (1e5), `1.00 Cr` = crore (1e7). Below a lakh the raw count is shown, grouped.
 * One decimal is enough to distinguish `1.2 Cr` from `1.25 Cr` at a glance, which is the
 * distinction that matters for a volume-spike read (plan.md §8.2).
 */
fun formatVolume(volume: Long?): String {
    if (volume == null) return NOT_AVAILABLE
    val value = volume.toDouble()
    val sign = if (value < 0) "-" else ""
    val magnitude = abs(value)
    return when {
        magnitude >= CRORE -> sign + formatFixed(magnitude / CRORE, 2) + " Cr"
        magnitude >= LAKH -> sign + formatFixed(magnitude / LAKH, 2) + " L"
        else -> sign + groupIndianDigits(magnitude.toLong().toString())
    }
}

private const val LAKH = 100_000.0
private const val CRORE = 10_000_000.0

/** `₹` — the rupee sign. U+20B9 is present on every Android font from API 21 onward. */
const val RUPEE: String = "₹"

/** Rendered wherever a value genuinely does not exist. Never `0`, never an empty cell. */
const val NOT_AVAILABLE: String = "—"

private const val INFINITY_UPPER = "∞"
private const val INFINITY_LOWER = "-∞"

/**
 * The user-facing name of a source, from the enum's own name.
 *
 * Deliberately mechanical: the mapping is data provenance the user can check against
 * `Disclosure.DATA_SOURCE`, so inventing friendlier names per screen would let the same source be
 * called two things on two tabs.
 */
fun Source.displayName(): String = when (this) {
    Source.NSE_BHAVCOPY -> "NSE EOD bhavcopy"
    Source.YAHOO -> "Yahoo Finance"
    Source.SCREENER_IN -> "screener.in"
    Source.NSE_EQUITY_MASTER -> "NSE symbol master"
    Source.DERIVED -> "Derived on device"
}

/**
 * A date in the one format this app ever uses: `dd MMM yyyy`.
 *
 * Delegates to `FreshnessPolicy.format` rather than repeating it, because that function is
 * already the one the domain's own tests assert on — two formatters would be two chances to
 * disagree about what "as of" means.
 */
fun formatDate(date: LocalDate): String = com.mia.app.domain.ratios.FreshnessPolicy.format(date)

/** `as of 02 Oct 2026`, using the regulated template from `Disclosure`. */
fun formatAsOf(date: LocalDate): String =
    String.format(Locale.ROOT, com.mia.app.domain.compliance.Disclosure.AS_OF_TEMPLATE, formatDate(date))
