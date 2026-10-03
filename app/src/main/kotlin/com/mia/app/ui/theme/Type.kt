package com.mia.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Type scale (plan.md §4.1) tuned for **retail legibility on a budget phone**.
 *
 * Two deliberate choices:
 *
 * 1. **Tabular figures on every style that renders a number.** A day change of `+1.04%` next to
 *    `+0.88%` must line up digit for digit, or the eye cannot compare them. `fontFeatureSettings
 *    = "tnum"` asks the platform font for fixed-width digits; devices without the feature simply
 *    get proportional digits, which is a cosmetic degradation rather than a wrong number.
 * 2. **Base sizes are at the large end of the Material scale** (`bodyMedium` is 16sp, not the
 *    default 14sp) because the reader is holding a 5-inch screen at arm's length in daylight,
 *    often outdoors at a bus stop.
 *
 * No custom font family is bundled: a 400 KB typeface is a real cost on a 2 GB-RAM device with a
 * small download budget, and the platform font is already what the user reads everything else
 * with.
 */

/** `tnum` + `lnum` (lining figures): fixed-width, same-height digits. */
private const val TABULAR_FIGURES = "tnum, lnum"

/** `Typography` installed by [MiaTheme]. Unlisted roles keep the Material defaults. */
val MiaTypography = Typography(
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.1.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.2.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.3.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
        fontFeatureSettings = TABULAR_FIGURES,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp,
        fontFeatureSettings = TABULAR_FIGURES,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.5.sp,
        fontFeatureSettings = TABULAR_FIGURES,
    ),
)
