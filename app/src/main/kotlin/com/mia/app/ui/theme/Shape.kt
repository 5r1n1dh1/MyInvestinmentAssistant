package com.mia.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Corner radii (plan.md §4.1).
 *
 * Chips and small surfaces use 8dp, cards 12dp, sheets/dialogs 16dp. Nothing is fully round:
 * a pill-shaped card on a 5-inch screen wastes the horizontal space the numbers need.
 */
val MiaShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)
