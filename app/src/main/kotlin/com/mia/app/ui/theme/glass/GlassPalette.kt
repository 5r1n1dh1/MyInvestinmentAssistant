package com.mia.app.ui.theme.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import com.mia.app.ui.theme.MiaMarketColors
import com.mia.app.ui.theme.MiaTheme

/**
 * The glass tokens (planv2 W7): a dark-first palette for frosted surfaces, with a light frosted
 * variant.
 *
 * ### Why a palette of its own rather than Material roles
 * A translucent surface cannot be expressed with a `ColorScheme` role. Material's `surfaceVariant`
 * is an opaque colour: over a blurred backdrop it either covers the blur completely (no glass) or
 * has to be given an alpha by hand at every call site (and drifts). The fill of a glass surface is
 * therefore a *token with its own alpha*, and the alpha is the whole point — it is what lets the
 * blur behind show through at all.
 *
 * ### Why dark-first
 * Glassmorphism is the art of making an opaque backdrop *look* like it is behind a pane. There is
 * nothing behind a surface to distort on a white background, so a light glass panel has almost no
 * blur to show and degrades to a flat tint. The dark scheme is where the effect exists: a deep
 * indigo gradient with two soft accent glows behind every card. The light variant is a deliberately
 * quieter frosted variant (see [lightGlassPalette]).
 *
 * ### Where the accents come from
 * The two backdrop glows are derived from [MiaMarketColors.measuredMove] and
 * [MiaMarketColors.breakoutTrigger] — the violet and the teal, already documented as the two hues
 * chosen to survive deuteranopia and a washed-out screen. Glass does not invent a palette; it
 * re-uses the one the app has.
 *
 * ### Not every tint is white
 * A dark-mode glass fill has to *lighten* what is behind it or the panel reads as a hole punched in
 * the background rather than as a pane of glass. The dark fills are therefore a cool off-white at
 * 12–18%; the light fills are a deep navy, the same logic with the sign flipped.
 */
@Immutable
data class GlassPalette(
    // -------------------------------------------------------------- the backdrop behind the glass
    /** Top of the fixed background gradient. */
    val backdropTop: Color,
    /** Middle of the fixed background gradient, where the accent glows sit. */
    val backdropMiddle: Color,
    /** Bottom of the fixed background gradient. */
    val backdropBottom: Color,
    /** The primary accent glow, derived from [MiaMarketColors.measuredMove]. */
    val glowPrimary: Color,
    /** The secondary accent glow, derived from [MiaMarketColors.breakoutTrigger]. */
    val glowSecondary: Color,

    // ---------------------------------------------------------------------------------- the fills
    /** 18% fill — headers and bars, the surfaces that carry the most content. */
    val tintStrong: Color,
    /** 15% fill — the default for a [GlassSurface]. */
    val tintMedium: Color,
    /** 12% fill — list items, where a stronger fill would fight the numbers on it. */
    val tintSoft: Color,
    /** Fill used instead of a tint when glass is off, so an "off" surface is a normal surface. */
    val flatFill: Color,

    // ----------------------------------------------------------------------------- the hairlines
    /** 1 dp border, the edge that tells the reader the panel has a surface at all. */
    val hairline: Color,
    /** A brighter 1 dp border, for the one edge that needs to read as an edge (a top bar's base). */
    val hairlineStrong: Color,

    // ------------------------------------------------------------------------ elevation overlays
    /** The top-edge gloss: a short vertical fade that reads as light catching a pane. */
    val sheen: Color,
    /** The inner-shadow stand-in, for a panel that must read as raised over another panel. */
    val elevationOverlay: Color,
)

/** Relative luminance at or below which the background counts as dark. */
private const val DARK_BACKGROUND_LUMINANCE = 0.5f

/**
 * The dark palette — the one the design was drawn for.
 *
 * [market] supplies the two accent glows. Taking the live [MiaMarketColors] rather than a
 * hard-coded pair is what keeps the glass accents and the chart accents the same colour.
 */
fun darkGlassPalette(market: MiaMarketColors): GlassPalette = GlassPalette(
    backdropTop = Color(0xFF171C2E),
    backdropMiddle = Color(0xFF0F1119),
    backdropBottom = Color(0xFF090A0F),
    glowPrimary = market.measuredMove.copy(alpha = 0.30f),
    glowSecondary = market.breakoutTrigger.copy(alpha = 0.20f),
    tintStrong = Color(0xFFE8EDFF).copy(alpha = GlassTokens.FillAlphaStrong),
    tintMedium = Color(0xFFDCE4FF).copy(alpha = GlassTokens.FillAlphaMedium),
    tintSoft = Color(0xFFD6DEFF).copy(alpha = GlassTokens.FillAlphaSoft),
    flatFill = Color(0xFF14161C),
    hairline = Color(0xFFFFFFFF).copy(alpha = 0.22f),
    hairlineStrong = Color(0xFFFFFFFF).copy(alpha = 0.38f),
    sheen = Color(0xFFFFFFFF).copy(alpha = 0.10f),
    elevationOverlay = Color(0xFF000000).copy(alpha = 0.28f),
)

/**
 * The light frosted variant.
 *
 * Weaker glows and a deeper fill than the dark palette, because a pale backdrop has no contrast
 * budget to spare: the same 18% white-on-white fill that reads as glass on indigo reads as a
 * rendering bug on `#EDF3FB`.
 */
fun lightGlassPalette(market: MiaMarketColors): GlassPalette = GlassPalette(
    backdropTop = Color(0xFFDCE7F7),
    backdropMiddle = Color(0xFFEDF3FB),
    backdropBottom = Color(0xFFC9D9EE),
    glowPrimary = market.measuredMove.copy(alpha = 0.14f),
    glowSecondary = market.breakoutTrigger.copy(alpha = 0.10f),
    tintStrong = Color(0xFF1B2A44).copy(alpha = GlassTokens.FillAlphaStrong),
    tintMedium = Color(0xFF1B2A44).copy(alpha = GlassTokens.FillAlphaMedium),
    tintSoft = Color(0xFF1B2A44).copy(alpha = GlassTokens.FillAlphaSoft),
    flatFill = Color(0xFFF7FAFE),
    hairline = Color(0xFF0B1220).copy(alpha = 0.14f),
    hairlineStrong = Color(0xFF0B1220).copy(alpha = 0.26f),
    sheen = Color(0xFFFFFFFF).copy(alpha = 0.55f),
    elevationOverlay = Color(0xFF0B1220).copy(alpha = 0.10f),
)

/**
 * The gloss across the top of a glass panel.
 *
 * A drop shadow is the Material way of saying "this is above that", and it does not work on glass:
 * a shadow under a translucent panel is drawn against a blurred backdrop and reads as dirt. This
 * vertical fade does the same job as light catching the top edge of a pane, for one brush and no
 * extra draw pass.
 */
fun GlassPalette.sheenBrush(): Brush = Brush.verticalGradient(
    0f to sheen,
    1f to Color.Transparent,
)

/**
 * A per-screen override of [GlassTheme.palette], installed by [ProvideGlassPalette].
 *
 * `null` — the default — means "derive the palette from the current Material scheme and market
 * palette", which is what every screen in the app wants. A screen only provides one when its glass
 * has to be tinted towards something specific.
 *
 * `staticCompositionLocalOf` is correct here and `compositionLocalOf` would be wrong: the default is
 * a constant `null`, and a screen that does provide a palette never changes it, so there is nothing
 * to re-read on recomposition.
 */
val LocalGlassPalette = staticCompositionLocalOf<GlassPalette?> { null }

/**
 * Installs [palette] as the glass palette for [content].
 *
 * Nothing in the app calls this today — [MiaTheme] derives the palette — and it exists so a screen
 * can opt into a specific glass without editing `MiaTheme.kt`, which is owned by another workstream.
 */
@Composable
fun ProvideGlassPalette(palette: GlassPalette, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalGlassPalette provides palette, content = content)
}

/**
 * Access to the glass palette.
 *
 * ```kotlin
 * Box(Modifier.background(GlassTheme.palette.tintSoft))
 * ```
 *
 * Dark or light is decided by the luminance of the Material background rather than by
 * `isSystemInDarkTheme()`, so the glass can never disagree with the theme it is drawn in — a
 * `MiaTheme(darkTheme = …)` preview or a forced-light test gets light glass automatically.
 */
object GlassTheme {
    val palette: GlassPalette
        @Composable
        @ReadOnlyComposable
        get() = LocalGlassPalette.current ?: if (
            MiaTheme.colors.background.luminance() < DARK_BACKGROUND_LUMINANCE
        ) {
            darkGlassPalette(MiaTheme.marketColors)
        } else {
            lightGlassPalette(MiaTheme.marketColors)
        }
}

/**
 * The fixed background gradient, without the blur.
 *
 * Kept separate from [GlassBackdrop] because a [GlassSurface]'s `backdrop` slot and the
 * screen-level background must render *the same* gradient, or the seam where a blurred header
 * meets the screen behind it is visible.
 *
 * The three layers are a vertical gradient plus two soft accent glows offset outside the top-right
 * and bottom-left corners in dp, so the composition of the backdrop is identical on a 2x and a 3x
 * panel. Nothing here animates: the whole point of putting the blur on a single fixed layer is
 * that it is drawn once and then served from a cached layer.
 */
@Composable
fun GlassBackdropContent(modifier: Modifier = Modifier) {
    val palette = GlassTheme.palette
    val fade = Color.Transparent

    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0f to palette.backdropTop,
                        0.60f to palette.backdropMiddle,
                        1f to palette.backdropBottom,
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = 56.dp, y = (-72).dp)
                .size(320.dp)
                .background(Brush.radialGradient(0f to palette.glowPrimary, 1f to fade)),
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .offset(x = (-48).dp, y = 64.dp)
                .size(280.dp)
                .background(Brush.radialGradient(0f to palette.glowSecondary, 1f to fade)),
        )
    }
}
