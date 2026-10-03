package com.mia.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/**
 * The app's theme root.
 *
 * ```kotlin
 * setContent {
 *     MiaTheme {
 *         MiaNavHost()
 *     }
 * }
 * ```
 *
 * `dynamicColor` is deliberately **not** offered. See [MiaLightColorScheme] for why: the bull/bear
 * pair carries meaning, so it must not be re-hued from the device wallpaper.
 *
 * @param darkTheme follows the system setting. There is no app-level override in v1: plan.md §1.4
 *   keeps the app free of user-tunable presentation, and a theme switch is a preference, not a
 *   compliance surface.
 */
@Composable
fun MiaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) MiaDarkColorScheme else MiaLightColorScheme
    val marketColors = if (darkTheme) MiaDarkMarketColors else MiaLightMarketColors

    ProvideMiaMarketColors(marketColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = MiaTypography,
            shapes = MiaShapes,
            content = content,
        )
    }
}
