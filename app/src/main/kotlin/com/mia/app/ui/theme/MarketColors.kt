package com.mia.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Colours that are **not** Material 3 roles: the market semantics and the chart's own palette.
 *
 * ### Why they are separate from [androidx.compose.material3.ColorScheme]
 * `primary`/`error` are UI affordances — "this is a button", "this failed". Market direction is
 * data, and reusing `error` for "the stock fell" would make a -3% bar indistinguishable from a
 * failed download. So direction, grid, crosshair and the three projection layers get their own
 * tokens, exposed through a composition local rather than through `ColorScheme`.
 *
 * ### Why direction is never colour alone
 * plan.md §12 targets 2 GB-RAM budget devices and the reader is often outdoors. Roughly 1 in 12
 * men has a colour-vision deficiency, and a low-contrast screen washes green and red into the
 * same grey. So every place these two colours are used must also render [UP_GLYPH] / [DOWN_GLYPH]
 * / [FLAT_GLYPH] — see [MoveGlyph] and `ChangeLabel`, which do that in one place so no screen
 * can forget.
 */
@Immutable
data class MiaMarketColors(
    /** An up bar / a rising figure. Always paired with [UP_GLYPH]. */
    val bull: Color,
    /** A down bar / a falling figure. Always paired with [DOWN_GLYPH]. */
    val bear: Color,
    /** Unchanged. Always paired with [FLAT_GLYPH]. */
    val flat: Color,
    /** Fill of a bullish candle body. */
    val bullBody: Color,
    /** Fill of a bearish candle body. */
    val bearBody: Color,
    /** Fill of a bullish volume bar. */
    val bullVolume: Color,
    /** Fill of a bearish volume bar. */
    val bearVolume: Color,
    /** Horizontal price grid lines and the axis gutter rule. */
    val grid: Color,
    /** The touch crosshair's vertical and horizontal rules. */
    val crosshair: Color,
    /** Secondary text: axis labels, units, captions. */
    val mutedText: Color,
    /** Filled marker for a swing high. */
    val pivotHigh: Color,
    /** Hollow marker for a swing low. */
    val pivotLow: Color,
    /** The measured-move geometry level (plan.md §6.6 item 1). */
    val measuredMove: Color,
    /** The 1–5 bar dashed projected path (plan.md §6.6 item 2). */
    val projectedPath: Color,
    /** The breakout trigger level (plan.md §6.6 item 3). */
    val breakoutTrigger: Color,
)

/** Light-mode market palette. Bull/bear clear 4.5:1 on `MiaLightColorScheme.background`. */
val MiaLightMarketColors = MiaMarketColors(
    bull = Color(0xFF0B6E3F),
    bear = Color(0xFFB3261E),
    flat = Color(0xFF5F6368),
    bullBody = Color(0xFF128A4E),
    bearBody = Color(0xFFC62828),
    bullVolume = Color(0x66128A4E),
    bearVolume = Color(0x66C62828),
    grid = Color(0x1F1A1C1E),
    crosshair = Color(0x991A1C1E),
    mutedText = Color(0xFF5A5F66),
    pivotHigh = Color(0xFF3F5F80),
    pivotLow = Color(0xFF3F5F80),
    // Violet / amber / teal: three hues that survive both deuteranopia and a washed-out screen,
    // and are not confusable with the bull/bear pair.
    measuredMove = Color(0xFF5B3FD6),
    projectedPath = Color(0xFF8A5A00),
    breakoutTrigger = Color(0xFF00696E),
)

/** Dark-mode market palette. Bull/bear clear 4.5:1 on `MiaDarkColorScheme.background`. */
val MiaDarkMarketColors = MiaMarketColors(
    bull = Color(0xFF6BD98F),
    bear = Color(0xFFFF8A80),
    flat = Color(0xFFBFC1C4),
    bullBody = Color(0xFF57C77C),
    bearBody = Color(0xFFE97067),
    bullVolume = Color(0x666BD98F),
    bearVolume = Color(0x66FF8A80),
    grid = Color(0x2EFFFFFF),
    crosshair = Color(0xB3FFFFFF),
    mutedText = Color(0xFFC4C6C9),
    pivotHigh = Color(0xFFA7C8E8),
    pivotLow = Color(0xFFA7C8E8),
    measuredMove = Color(0xFFB9A6FF),
    projectedPath = Color(0xFFFFC46B),
    breakoutTrigger = Color(0xFF64D8DE),
)

private val LocalMiaMarketColors = staticCompositionLocalOf { MiaLightMarketColors }

/**
 * Installs [colors] for [content]. Only [MiaTheme] calls this; screens read [MiaTheme.marketColors].
 */
@Composable
internal fun ProvideMiaMarketColors(
    colors: MiaMarketColors,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalMiaMarketColors provides colors, content = content)
}

/**
 * Access to the market palette.
 *
 * ```kotlin
 * val up = MiaTheme.marketColors.bull
 * ```
 */
object MiaTheme {
    val marketColors: MiaMarketColors
        @Composable
        @ReadOnlyComposable
        get() = LocalMiaMarketColors.current

    /** Shorthand for `MaterialTheme.colorScheme`. */
    val colors: androidx.compose.material3.ColorScheme
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.colorScheme
}

/**
 * The three states a figure can be in.
 *
 * A separate enum from `com.mia.app.domain.model.Direction` (which is only `UP`/`DOWN` and is
 * about pattern geometry) because a *screen* also has to render "unchanged", and because a price
 * table needs this without a pattern in sight.
 */
enum class MoveDirection {
    UP,
    DOWN,
    FLAT,
    ;

    companion object {
        /** Classifies a signed difference. Exactly `0.0` is [FLAT]; never a guess. */
        fun ofChange(change: Double): MoveDirection = when {
            change > 0.0 -> UP
            change < 0.0 -> DOWN
            else -> FLAT
        }
    }
}

/** ▲ — a rising figure. */
const val UP_GLYPH: String = "▲"

/** ▼ — a falling figure. */
const val DOWN_GLYPH: String = "▼"

/** — — an unchanged figure. */
const val FLAT_GLYPH: String = "—"

/** The glyph that must accompany [MiaMarketColors.bull] / `bear` / `flat`. */
val MoveDirection.glyph: String
    get() = when (this) {
        MoveDirection.UP -> UP_GLYPH
        MoveDirection.DOWN -> DOWN_GLYPH
        MoveDirection.FLAT -> FLAT_GLYPH
    }
