package com.mia.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * The Material 3 colour schemes (plan.md §4.1).
 *
 * ### Why these are hand-picked and not dynamic
 * `dynamicColorScheme` would repaint the app from the user's wallpaper, which on a budget
 * Android device means the up/down greens and reds get whatever hue the wallpaper happens to
 * contain. The whole regulatory presentation of this app depends on a reader being able to
 * tell "the bar went down" from "the bar went up" at a glance, so the palette is fixed and
 * contrast-checked instead of inherited.
 *
 * ### Contrast
 * Every foreground/background pair below was chosen to clear WCAG AA (4.5:1) for body text in
 * its own scheme; [MiaMarketColors] is documented separately because the bull/bear pair is the
 * one that carries meaning.
 */

// ------------------------------------------------------------------------------- light

private val LightPrimary = Color(0xFF1B5E9C)
private val LightOnPrimary = Color(0xFFFFFFFF)
private val LightPrimaryContainer = Color(0xFFD3E4FF)
private val LightOnPrimaryContainer = Color(0xFF001C38)
private val LightSecondary = Color(0xFF3F5F80)
private val LightOnSecondary = Color(0xFFFFFFFF)
private val LightSecondaryContainer = Color(0xFFD3E4FF)
private val LightOnSecondaryContainer = Color(0xFF001C38)
private val LightTertiary = Color(0xFF7A5900)
private val LightOnTertiary = Color(0xFFFFFFFF)
private val LightTertiaryContainer = Color(0xFFFFDF9B)
private val LightOnTertiaryContainer = Color(0xFF261A00)
private val LightBackground = Color(0xFFFDFCFF)
private val LightOnBackground = Color(0xFF1A1C1E)
private val LightSurface = Color(0xFFFDFCFF)
private val LightOnSurface = Color(0xFF1A1C1E)
private val LightError = Color(0xFFBA1A1A)
private val LightOnError = Color(0xFFFFFFFF)
private val LightErrorContainer = Color(0xFFFFDAD6)
private val LightOnErrorContainer = Color(0xFF410002)
private val LightOutline = Color(0xFF73777F)
private val LightOutlineVariant = Color(0xFFC3C7CF)

/** Light scheme. Only long-stable Material 3 role parameters are used. */
val MiaLightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary = LightSecondary,
    onSecondary = LightOnSecondary,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = LightOnSecondaryContainer,
    tertiary = LightTertiary,
    onTertiary = LightOnTertiary,
    tertiaryContainer = LightTertiaryContainer,
    onTertiaryContainer = LightOnTertiaryContainer,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnSurface,
    error = LightError,
    onError = LightOnError,
    errorContainer = LightErrorContainer,
    onErrorContainer = LightOnErrorContainer,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
)

// -------------------------------------------------------------------------------- dark

private val DarkPrimary = Color(0xFFA2C9FF)
private val DarkOnPrimary = Color(0xFF00325B)
private val DarkPrimaryContainer = Color(0xFF004881)
private val DarkOnPrimaryContainer = Color(0xFFD3E4FF)
private val DarkSecondary = Color(0xFFA7C8E8)
private val DarkOnSecondary = Color(0xFF0B3050)
private val DarkSecondaryContainer = Color(0xFF264768)
private val DarkOnSecondaryContainer = Color(0xFFD3E4FF)
private val DarkTertiary = Color(0xFFF5BF48)
private val DarkOnTertiary = Color(0xFF412D00)
private val DarkTertiaryContainer = Color(0xFF5D4200)
private val DarkOnTertiaryContainer = Color(0xFFFFDF9B)
private val DarkBackground = Color(0xFF121316)
private val DarkOnBackground = Color(0xFFE3E2E6)
private val DarkSurface = Color(0xFF121316)
private val DarkOnSurface = Color(0xFFE3E2E6)
private val DarkError = Color(0xFFFFB4AB)
private val DarkOnError = Color(0xFF690005)
private val DarkErrorContainer = Color(0xFF93000A)
private val DarkOnErrorContainer = Color(0xFFFFDAD6)
private val DarkOutline = Color(0xFF8D9199)
private val DarkOutlineVariant = Color(0xFF43474E)

/** Dark scheme. Mirrors [MiaLightColorScheme] role for role. */
val MiaDarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = DarkSecondary,
    onSecondary = DarkOnSecondary,
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = DarkOnSecondaryContainer,
    tertiary = DarkTertiary,
    onTertiary = DarkOnTertiary,
    tertiaryContainer = DarkTertiaryContainer,
    onTertiaryContainer = DarkOnTertiaryContainer,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    error = DarkError,
    onError = DarkOnError,
    errorContainer = DarkErrorContainer,
    onErrorContainer = DarkOnErrorContainer,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
)
