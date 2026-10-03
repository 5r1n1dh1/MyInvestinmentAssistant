package com.mia.app.domain.redflag.technical

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Number formatting for the technical red flags' evidence rows.
 *
 * **Why formatting is a domain concern and not a UI concern here.** plan.md §8.3 requires that
 * tapping a flag show *the numbers behind the title*, and `redflags-spec.md` §1 rule 2 requires
 * `Evidence.value` to be **the number actually used in the comparison** — never a rounded or
 * re-derived variant. If the UI formatted these values itself, the number on screen could disagree
 * with the number the rule compared against, which is the one failure mode the evidence list exists
 * to prevent. So each rule formats its own rows here, from the same `Double` it tested.
 *
 * Three deliberate choices:
 *
 * 1. **ASCII hyphen, always.** `redflags-spec.md` typesets negatives with U+2212 MINUS SIGN; a
 *    formatter that emits one codepoint in one place and another elsewhere makes string comparison
 *    in the UI (and in the tests) quietly fragile. Everything here is U+002D.
 * 2. **`Locale.ROOT` always.** Thousands separators must be `16,771,221` for every device. Under a
 *    locale such as `de-DE` the default formatter would emit `16.771.221`, which is not a number any
 *    Indian user reads.
 * 3. **No negative zero.** `String.format("%.1f", -0.04)` prints `-0.0`, and a percentage change of
 *    minus nothing displayed as "-0.0 %" reads as a fabricated precision. Values that round to zero
 *    are printed unsigned.
 */
internal object TechnicalFlagFormat {

    private val POWERS = doubleArrayOf(
        1.0, 10.0, 100.0, 1_000.0, 10_000.0, 100_000.0, 1_000_000.0, 10_000_000.0,
    )

    /** Rendered when a row exists but has no number behind it — never a fabricated zero. */
    const val UNAVAILABLE: String = "not available from our sources"

    /** `1167.7` → `"₹1,167.70"`. */
    fun rupee(value: Double): String = "₹" + decimal(value, 2)

    /** `2.61` → `"2.61×"`. */
    fun multiple(value: Double, decimals: Int = 2): String = decimal(value, decimals) + "×"

    /** `48.66` → `"48.7 %"`. */
    fun percent(value: Double, decimals: Int = 1): String = decimal(value, decimals) + " %"

    /** `0.22` → `"0.22"`. A ratio to a stated reference, no unit. */
    fun ratio(value: Double, decimals: Int = 2): String = decimal(value, decimals)

    /** `16771221` → `"16,771,221 shares"`. */
    fun shares(value: Long): String =
        String.format(Locale.ROOT, "%,d", value) + " shares"

    /**
     * A 20-bar **mean** traded quantity, rendered as a whole number of shares.
     *
     * Shares are whole numbers but a mean is not, so the mean is rounded rather than truncated —
     * flooring would make every printed average read as systematically low next to the session
     * volumes printed beside it.
     */
    fun shares(mean: Double): String =
        if (mean.isNaN() || mean.isInfinite() || mean <= 0.0) {
            shares(0L)
        } else {
            shares(Math.round(mean))
        }

    /** `-0.0834` → `"-0.083 (signed ÷ gross volume)"` — the normalised OBV slope. */
    fun obvSlope(value: Double): String =
        decimal(value, 3) + " (signed ÷ gross volume)"

    /** Fixed-decimal number in the root locale, with negative zero normalised away. */
    fun decimal(value: Double, decimals: Int): String {
        if (value.isNaN() || value.isInfinite()) return UNAVAILABLE
        val safe = decimals.coerceIn(0, POWERS.size - 1)
        val scale = POWERS[safe]
        var rounded = (value * scale).roundToLong() / scale
        if (rounded == 0.0) rounded = abs(rounded)
        return String.format(Locale.ROOT, "%.${safe}f", rounded)
    }
}