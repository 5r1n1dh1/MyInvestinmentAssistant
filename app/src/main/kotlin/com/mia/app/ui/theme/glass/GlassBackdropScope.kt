package com.mia.app.ui.theme.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/**
 * Whether an ancestor has already drawn this screen's blurred background.
 *
 * `false` — the default, and therefore the case for every screen that has one entry point and can
 * simply call [GlassBackdrop] itself. `true` — an ancestor has already spent this screen's blur
 * budget, so a nested screen must **not** draw another one.
 *
 * `staticCompositionLocalOf` is correct here for the reason it is correct in
 * [LocalGlassPalette]: the default is a constant, and a screen that provides a value always provides
 * the same one, so there is nothing for a recomposition to re-read. A `compositionLocalOf` would put
 * a comparison on a path that runs for every composable in the subtree, for a value that never changes.
 */
val LocalGlassBackdropProvided = staticCompositionLocalOf { false }

/**
 * The one blurred background for a screen, and the marker that lets a nested screen know it is
 * already drawn.
 *
 * ### Why this exists rather than "every screen calls `GlassBackdrop`"
 * `Glass.kt`'s fourth performance rule is *"one full-screen blur per screen … never a nested one
 * inside the first"*, and it is unenforceable if every screen assumes it is the root: a composable
 * that is also usable as a slot inside another screen has no way to tell. The app has exactly that
 * case — [com.mia.app.ui.detail.StockDetailScreen] hosts the Fundamentals, Technicals and Patterns
 * screens in its tab slot, **and** each of those is registered as a standalone nav destination of its
 * own (see `MiaNavHost`). Without this marker, opening a symbol would put two full-screen blurs on
 * screen at once — the frame's and the tab's — paying for the effect twice and showing the reader a
 * seam where the header's background stops and the slot's begins.
 *
 * So a screen declares *"I am a screen frame"* by wrapping its content in this, and every screen
 * inside it — the tab, the empty state, a future bottom sheet — inherits the answer instead of
 * guessing it. A standalone route has no such ancestor, so the same composable draws its own
 * backdrop and the standalone entry point looks identical to the hosted one.
 *
 * ```kotlin
 * // the frame
 * GlassScreenBackdrop(modifier = Modifier.fillMaxSize()) {
 *     Column(Modifier.fillMaxSize()) { /* … */ }
 * }
 *
 * // anything nested inside it draws no backdrop of its own, with no argument to pass
 * GlassScreenBackdrop(modifier = Modifier.fillMaxSize()) { /* … */ }
 * ```
 *
 * ### The layout rule it exists to make unmissable
 * The backdrop is a **sibling behind** [content], inside a `Box`, and the content is a *direct*
 * child laid out normally. Writing the backdrop as the first child of a `Column` instead — the shape
 * this replaces — is the bug that renders an empty screen: `fillMaxSize()` in a `Column` claims the
 * full height, the header and the list are left with nothing to draw in, and it compiles without a
 * word.
 *
 * @param modifier the screen's own modifier. Pass the constraints the frame fills; the backdrop is
 *   sized from the resulting box, so it needs no modifier of its own.
 * @param content the screen. Typed `@Composable () -> Unit` rather than `BoxScope` so a caller can
 *   pass a `Column` without the receiver leaking in and a `matchParentSize()` at this level
 *   silently matching the wrong thing.
 */
@Composable
fun GlassScreenBackdrop(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    // Read once, before the provider below installs `true` for the subtree: the answer that matters
    // is the one *this* screen inherits, not the one it hands down.
    val alreadyProvided = LocalGlassBackdropProvided.current

    Box(modifier = modifier) {
        if (!alreadyProvided) {
            // The entire blur budget for this screen: one fixed, static, blurred layer behind
            // everything. `matchParentSize` rather than `fillMaxSize` because the box is already the
            // screen, and a backdrop must never be the thing that decides the screen's size.
            GlassBackdrop(modifier = Modifier.matchParentSize())
        }

        CompositionLocalProvider(LocalGlassBackdropProvided provides true) {
            content()
        }
    }
}