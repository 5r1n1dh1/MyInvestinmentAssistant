package com.mia.app.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Spacing tokens (plan.md §4.1).
 *
 * A 4 dp base scale, 8 dp for anything a thumb reaches and 16/24 dp for the screen gutter. Every
 * screen in the app uses these instead of literals so that padding stays consistent across tabs
 * that were written by different agents — a 12 dp gutter on one tab and 16 dp on the next reads
 * as a bug even when both are "fine".
 */
object MiaSpacing {
    /** 4 dp — inside a chip, between two labels on one line. */
    val xs: Dp = 4.dp

    /** 8 dp — between related rows. */
    val sm: Dp = 8.dp

    /** 12 dp — between blocks inside a card. */
    val md: Dp = 12.dp

    /** 16 dp — the screen gutter, and between cards. */
    val lg: Dp = 16.dp

    /** 24 dp — between top-level sections. */
    val xl: Dp = 24.dp

    /** 32 dp — above a page-level heading. */
    val xxl: Dp = 32.dp
}
