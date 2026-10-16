package com.mia.app.ui.theme.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mia.app.ui.theme.MiaSpacing

/**
 * The numbers the glass design is built from.
 *
 * Every one of them is a named token rather than a literal at the call site. A redesign that
 * changes the blur radius from 20 dp to 16 dp has to change it in exactly one place, and the same
 * has to be true for the press scale and the stagger step, or "interactive" quietly becomes
 * "different on every screen".
 */
object GlassTokens {

    // ------------------------------------------------------------------------------- the blur
    /**
     * The medium blur: **20 dp**.
     *
     * Chosen because it is the smallest radius at which a backdrop stops reading as a flat tint
     * and starts reading as something seen *through* something. Measured against this app's
     * backdrops: below ~8 dp the gradient is still sharp enough that the panel looks like it is
     * painted over a photograph rather than in front of one; past ~32 dp the glows smear into each
     * other, the hairline stops separating the panel from its background, and the extra radius buys
     * nothing a reader can see while still costing a wider kernel on every pass.
     *
     * 20 dp also happens to be the smallest radius at which the effect survives a *scaled-down*
     * screenshot, which is where most of this UI is actually seen.
     */
    val MediumBlur: Dp = 20.dp

    /** 8 dp — [GlassMode.REDUCED]. Enough to read as frosted, a quarter of the kernel width. */
    val ReducedBlur: Dp = 8.dp

    /** 0 dp — [GlassMode.OFF], and the default for every surface inside a lazy list. */
    val NoBlur: Dp = 0.dp

    /** The blur radius [mode] draws with, in dp, including `0.dp` for [GlassMode.OFF]. */
    fun blurFor(mode: GlassMode): Dp = GlassModeResolver.blurDp(mode).dp

    // ------------------------------------------------------------------------------ the fills
    /** 18% — headers, bars, anything that carries the most text. */
    const val FillAlphaStrong: Float = 0.18f

    /** 15% — the default fill. The middle of the 12–18% band the design specifies. */
    const val FillAlphaMedium: Float = 0.15f

    /** 12% — list items, where a stronger fill would start fighting the numbers on it. */
    const val FillAlphaSoft: Float = 0.12f

    /** 1 dp. A hairline, not a border: it separates the panel from its background and nothing else. */
    val HairlineWidth: Dp = 1.dp

    // -------------------------------------------------------------------------- the interactions
    /** How far a pressed surface dips. 3% reads as a press without looking like a broken layout. */
    const val PressScale: Float = 0.97f

    /** One row's entrance. Long enough to be noticed, short enough not to delay the list. */
    const val EntranceMillis: Int = 320

    /** The delay added per row, so the list arrives as a wave rather than as a block. */
    const val StaggerStepMillis: Int = 40

    /**
     * The ceiling on the cumulative stagger.
     *
     * Without it, row 40 of a long watchlist would start 1.6 s after the first frame and the
     * reader would scroll into an empty list. Capped at one [EntranceMillis] so the last row is
     * visible at the same moment the first one has finished.
     */
    const val MaxStaggerMillis: Int = 320

    /** How far a row travels as it arrives. 12 dp: enough to read as motion, not enough to notice. */
    val EntranceRise: Dp = 12.dp
}

/**
 * A frosted panel: a blurred backdrop, a translucent fill over it, and a hairline edge.
 *
 * ```kotlin
 * GlassSurface(shape = MaterialTheme.shapes.large) {
 *     Text("Every quote is sourced and dated.")
 * }
 * ```
 *
 * ### What it draws, in order
 * 1. the `backdrop` slot, blurred — **only** when one is supplied and only when the resolved
 *    [GlassMode] blurs. On API 31+ (guaranteed by `minSdk = 31`) `Modifier.blur(radius,
 *    BlurredEdgeTreatment.Rectangle)` is `RenderEffect.createBlurEffect(radiusPx, radiusPx,
 *    Shader.TileMode.CLAMP)` on the layer's own content, with the dp radius converted to px at draw
 *    time; `BlurredEdgeTreatment.Rectangle` is the bounded path, so the blur is clipped to the
 *    panel's own bounds and pixels sampled past them take the value of the nearest edge pixel
 *    (`CLAMP`) rather than fading to transparent.
 * 2. `tint`, at 12–18% alpha — the fill that *reveals* the blur. Without it the panel is a hole.
 * 3. `border`, a 1 dp hairline — the only thing that tells a reader, outdoors, on a washed-out
 *    screen, that the panel has an edge at all.
 * 4. `content`.
 *
 * ### How the backdrop works, and its one sharp edge
 * A `RenderEffect` blurs **its own layer's content**. It cannot reach through the window to sample
 * what is behind the panel. So "blur the backdrop" means drawing a *second copy* of the backdrop in
 * a sibling, blurring that, and putting the panel's fill and content over the top — which is what
 * the `backdrop` slot is for.
 *
 * Two consequences of `BlurredEdgeTreatment.Rectangle`, both of which are load-bearing here:
 *
 * - The blurred copy is **clipped to the panel's bounds**, so it fills the panel exactly and no
 *   halo spills past the rounded corners. Nothing needs to be drawn outside the panel to make this
 *   work, which is the usual reason to reach for `Unbounded`.
 * - The copy is laid out in the **panel's own coordinate space**, not the screen's, so it starts at
 *   the panel's top-left rather than the screen's. Pass something that tiles or slides smoothly: the
 *   app's [GlassBackdropContent] is a vertical gradient with two soft glows, and the seam where a
 *   header's copy meets the screen's copy is invisible because a gradient has nothing to line up
 *   against. If you pass content with hard edges into this slot, the offset between the two copies
 *   will be visible.
 *
 * ### Which helper to reach for
 * | Use | Why |
 * |---|---|
 * | [GlassBackdrop] | **Once per screen**, behind everything. This is where the blur budget is spent. |
 * | [GlassSurface] | A fixed header or panel that needs its own blurred copy of the backdrop. |
 * | [GlassTopBar] | A full-bleed header. [GlassSurface] plus a bottom hairline and the gloss. |
 * | [GlassCard] | A list item or an inline card. **Never blurs** — see the rules below. |
 *
 * ### PERFORMANCE RULES — these are the whole reason this file is written the way it is
 *
 * 1. **Never blur inside a `LazyColumn`/`LazyRow` item.** A blur is a GPU pass over the size of the
 *    panel. Twenty rows of a watchlist is twenty passes, and the list recycles: scrolling runs the
 *    pass again for whatever scrolled in, every frame it moves. Blur the *fixed* background once
 *    ([GlassBackdrop]) and give the rows a translucent fill over it instead ([GlassCard]). That is
 *    why [GlassCard] has no backdrop slot and no blur parameter at all — the mistake is not
 *    reachable rather than merely discouraged.
 * 2. **A repeated header is a cached layer.** [GlassBackdrop] and a header's blurred backdrop are
 *    static content, so their blurred result is produced once and served from a cached hardware
 *    layer. Anything that changes per frame — a scrolling price list, an animated chart, a live
 *    clock — must not sit behind a blur, or the cache is invalidated every frame and the blur cost
 *    is paid continuously instead of once.
 * 3. **Blur is per-frame expensive on low-end devices.** `ActivityManager.isLowRamDevice` and
 *    `PowerManager.isPowerSaveMode` both switch it off (see [GlassModeResolver]), and
 *    [GlassMode.OFF] costs nothing at all. The expensive case is not "a large blur", it is "many
 *    small blurs" or "a blur that changes every frame"; neither is reachable through this API.
 * 4. **One full-screen blur per screen.** If a second screen also needs a background, it gets its
 *    own single layer — never a nested one inside the first.
 *
 * ### Accessibility
 * The fill is a tint over a *known* backdrop, never over arbitrary content, so body text over a
 * glass surface holds the same contrast as it did on a flat one. Glass is a preference with an
 * off-switch ([GlassMode.OFF] renders a plain surface and keeps every contrast guarantee), so it is
 * never the only way a figure is legible, and it never encodes direction — the bull/bear
 * ▲/▼ rule in `MarketColors.kt` is untouched by anything here.
 *
 * @param blurRadius `null` means "whatever [GlassMode] resolves to": 20 dp for [GlassMode.FULL],
 *   8 dp for [GlassMode.REDUCED], nothing for [GlassMode.OFF]. Pass a value to override — including
 *   `GlassTokens.NoBlur` to force a tinted panel with no blur.
 * @param tint ignored when glass is off; an "off" surface is [GlassPalette.flatFill] so it is an
 *   ordinary Material surface and not a 12%-alpha panel the reader cannot read through.
 * @param border pass `null` for a surface with no edge (a full-bleed header that draws its own).
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.large,
    tint: Color = GlassTheme.palette.tintMedium,
    blurRadius: Dp? = null,
    border: BorderStroke? = BorderStroke(GlassTokens.HairlineWidth, GlassTheme.palette.hairline),
    backdrop: (@Composable () -> Unit)? = null,
    sheen: Boolean = false,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit,
) {
    val palette = GlassTheme.palette
    val mode = rememberGlassMode()
    val blurs = GlassModeResolver.blurs(mode)
    val radius = blurRadius ?: GlassTokens.blurFor(mode)

    Box(
        modifier = modifier.clip(shape),
        contentAlignment = contentAlignment,
    ) {
        if (backdrop != null && blurs && radius > GlassTokens.NoBlur) {
            // The one place the app spends a RenderEffect. `Modifier.blur(radius,
            // BlurredEdgeTreatment.Rectangle)` builds exactly
            // `BlurEffect(radiusPx, radiusPx, TileMode.Clamp)` — i.e.
            // `RenderEffect.createBlurEffect(radiusPx, radiusPx, Shader.TileMode.CLAMP)` — on the
            // layer it is applied to, with the dp radius converted to px at draw time. `Rectangle`
            // selects the bounded path: clip + CLAMP sampling, not DECAL. minSdk is 31, so there is
            // no API-30 path where this silently does nothing.
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .blur(radius, BlurredEdgeTreatment.Rectangle),
            ) {
                backdrop()
            }
        }

        Box(
            modifier = Modifier
                .matchParentSize()
                .background(if (blurs) tint else palette.flatFill, shape),
        )

        if (sheen) {
            Box(modifier = Modifier.matchParentSize().background(palette.sheenBrush(), shape))
        }

        if (border != null) {
            Box(modifier = Modifier.matchParentSize().border(border, shape))
        }

        // The content is a *direct* child and is deliberately not `matchParentSize()`: a child that
        // matches its parent contributes nothing to the parent's measured size, so a surface whose
        // every layer used it would measure 0 × 0 and disappear. The decoration layers use
        // `matchParentSize()` precisely because they must not influence the size; the content — the
        // one thing that decides how big this panel is — is laid out normally and placed by
        // [contentAlignment].
        content()
    }
}

/**
 * A glass list item or inline card. **Never blurs.**
 *
 * A card is almost always a lazy-list item, and the first performance rule in [GlassSurface]
 * forbids a blur there — so [GlassCard] exposes no way to ask for one. What it does have is the
 * fill and the hairline, over a screen that already has a blurred [GlassBackdrop] behind it: the
 * row is a genuine tinted pane, the blur behind it is genuinely blurred, and the total cost for a
 * list of any length is the one backdrop.
 *
 * ```kotlin
 * LazyColumn {
 *     items(rows, key = { it.symbol }) { row ->
 *         GlassCard(modifier = Modifier.fillMaxWidth().clickable { open(row.symbol) }) {
 *             Text(row.symbol)
 *         }
 *     }
 * }
 * ```
 *
 * @param tint the fill, 12% by default: a list item carries the app's numbers, and the fill has to
 *   stay light enough for them to read.
 * @param contentColor the colour for any `Text` inside that does not set its own — the same role
 *   Material's `Card` plays with `CardDefaults.cardColors(contentColor = …)`, and provided the same
 *   way, so replacing a `Card` with a [GlassCard] changes the panel and not the type colour.
 * @param contentPadding defaults to [MiaSpacing.md], the app's in-card padding.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.medium,
    tint: Color = GlassTheme.palette.tintSoft,
    contentColor: Color = LocalContentColor.current,
    border: BorderStroke? = BorderStroke(GlassTokens.HairlineWidth, GlassTheme.palette.hairline),
    arrangement: Arrangement.Vertical = Arrangement.spacedBy(MiaSpacing.xs),
    contentPadding: PaddingValues = PaddingValues(MiaSpacing.md),
    content: @Composable ColumnScope.() -> Unit,
) {
    GlassSurface(
        modifier = modifier,
        shape = shape,
        tint = tint,
        blurRadius = GlassTokens.NoBlur,
        border = border,
        contentAlignment = Alignment.TopStart,
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(contentPadding),
                verticalArrangement = arrangement,
            ) {
                content()
            }
        }
    }
}

/**
 * A full-bleed glass header: [GlassSurface] with the top-edge gloss, a bottom hairline, and a row
 * laid out inside it.
 *
 * ```kotlin
 * GlassTopBar {
 *     Text("Tracked symbols", style = MaterialTheme.typography.titleMedium)
 * }
 * ```
 *
 * Blurs its own copy of [GlassBackdropContent], so it stays legible over a scrolling list without
 * the list having to know anything about it. It is a fixed surface, not a list item, so the blur
 * here is the good kind: static content, blurred once, served from a cached layer.
 */
@Composable
fun GlassTopBar(
    modifier: Modifier = Modifier,
    backdrop: @Composable () -> Unit = { GlassBackdropContent() },
    content: @Composable RowScope.() -> Unit,
) {
    val palette = GlassTheme.palette
    val hairlinePx = with(LocalDensity.current) { GlassTokens.HairlineWidth.toPx() }

    GlassSurface(
        modifier = modifier,
        shape = RectangleShape,
        tint = palette.tintStrong,
        border = null,
        sheen = true,
        backdrop = backdrop,
        contentAlignment = Alignment.CenterStart,
    ) {
        // `fillMaxWidth`, not `matchParentSize`: a full-bleed header has to be as wide as the
        // screen, and it decides its own height from its content.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    // Drawn on the bottom edge only: a hairline on all four sides of a full-bleed
                    // header reads as a box, and the top and side edges are the window's own.
                    val y = size.height - hairlinePx / 2f
                    drawLine(
                        color = palette.hairlineStrong,
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = hairlinePx,
                    )
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MiaSpacing.sm),
            content = content,
        )
    }
}

/**
 * The fixed, blurred background behind a whole screen.
 *
 * **Render it once per screen, as a sibling behind everything, never inside a lazy list:**
 *
 * ```kotlin
 * Box(Modifier.fillMaxSize()) {
 *     GlassBackdrop(Modifier.matchParentSize())
 *     Column(Modifier.fillMaxSize()) { /* … */ }
 * }
 * ```
 *
 * The content is static by construction — a gradient and two glows that never animate — which is
 * what makes it cheap: the blurred layer is rendered once and then cached by the display list, so
 * scrolling costs nothing. This is the whole blur budget for a screen.
 *
 * @param blurRadius `null` follows the resolved [GlassMode]; [GlassMode.OFF] draws the gradient
 *   unblurred, which is a perfectly ordinary flat background and keeps the accent glows.
 */
@Composable
fun GlassBackdrop(
    modifier: Modifier = Modifier,
    blurRadius: Dp? = null,
    content: @Composable BoxScope.() -> Unit = { GlassBackdropContent() },
) {
    val mode = rememberGlassMode()
    val radius = blurRadius ?: GlassTokens.blurFor(mode)
    val blurs = GlassModeResolver.blurs(mode) && radius > GlassTokens.NoBlur

    Box(modifier = if (blurs) modifier.blur(radius, BlurredEdgeTreatment.Rectangle) else modifier) {
        content()
    }
}

// ------------------------------------------------------------------------------- interaction

/**
 * Dips a surface while it is held down.
 *
 * ```kotlin
 * val interactionSource = remember { MutableInteractionSource() }
 * GlassCard(
 *     modifier = Modifier
 *         .glassPressScale(interactionSource)
 *         .clickable(interactionSource = interactionSource, onClick = { open(row.symbol) }),
 * )
 * ```
 *
 * Share one [MutableInteractionSource] between the scale and the `clickable` — that is the whole
 * mechanism: the scale reads the same pressed state the ripple does, so they can never disagree,
 * and there is no extra gesture detector and no extra state to keep in sync.
 *
 * A `graphicsLayer` scale rather than `animateDpAsState` on the size, because a scale is a single
 * matrix on an already-composited layer: no remeasure, no relayout, no second pass. The spring
 * overshoots very slightly, which is what makes it feel like a physical surface rather than a
 * fade.
 *
 * @param pressedScale [GlassTokens.PressScale] by default.
 */
@Composable
fun Modifier.glassPressScale(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = GlassTokens.PressScale,
): Modifier {
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) pressedScale else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "glassPressScale",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * A staggered entrance for list items: each item fades and rises [rise] dp, [index] ×
 * [stepMillis] after the first.
 *
 * ```kotlin
 * LazyColumn {
 *     itemsIndexed(rows, key = { _, row -> row.symbol }) { index, row ->
 *         GlassRow(modifier = Modifier.glassEntrance(index = index))
 *     }
 * }
 * ```
 *
 * ### Interaction budget
 * Each entry is a single [Animatable] driving one `graphicsLayer`, so a row costs one layer
 * transform per frame and nothing after it finishes: no remeasure, no relayout, no recomposition of
 * the row's children. On a low-RAM device this is the *only* thing that still moves — [GlassMode]
 * turns the blur off there precisely so the motion can stay.
 *
 * ### Why it is cheap enough to put in a lazy list
 * One [Animatable] per item, driving a single `graphicsLayer` block: alpha and a translation, both
 * read inside the layer block so a frame invalidates the layer and not the composition. No blur is
 * involved, nothing is measured, and once the animation finishes the item is skipped by the
 * composition entirely — the cost stops rather than merely becoming small. The cumulative delay is
 * capped at [GlassTokens.MaxStaggerMillis] so a long list does not open with rows still invisible.
 *
 * The animation re-runs when an item is recycled by the lazy list, which is the behaviour a reader
 * expects: a row that scrolls back into view arrives rather than popping.
 *
 * @param enabled pass `false` (or the mode) to skip the animation entirely — this is the hook a
 *   reduced-motion or battery-saver setting would use, and it returns the modifier untouched rather
 *   than a zero-length animation.
 */
@Composable
fun Modifier.glassEntrance(
    index: Int,
    enabled: Boolean = true,
    stepMillis: Int = GlassTokens.StaggerStepMillis,
    rise: Dp = GlassTokens.EntranceRise,
): Modifier {
    if (!enabled) return this

    val progress = remember(index) { Animatable(0f) }
    LaunchedEffect(progress, stepMillis) {
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = GlassTokens.EntranceMillis,
                delayMillis = (index * stepMillis).coerceIn(0, GlassTokens.MaxStaggerMillis),
                easing = FastOutSlowInEasing,
            ),
        )
    }
    val risePx = with(LocalDensity.current) { rise.toPx() }

    return graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * risePx
    }
}
