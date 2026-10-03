package com.mia.app.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import com.mia.app.domain.model.Direction
import com.mia.app.ui.theme.MiaTheme
import com.mia.app.ui.theme.MoveDirection
import com.mia.app.ui.theme.glyph

/**
 * A signed figure whose direction is carried by a **glyph and a word**, never by colour alone.
 *
 * plan.md §1.4 and the accessibility reality behind it: this app is read outdoors, on cheap LCDs
 * with washed-out colours, by readers of whom roughly 1 in 12 men has a colour-vision deficiency.
 * A red/green-only encoding fails all three. So [ChangeText] always renders the triangle and
 * always replaces the merged semantics node with a spoken sentence — a TalkBack user hears "up
 * 1.24 percent", not "black triangle 1.24 percent".
 *
 * Use this for every number that can go up or down: change, day change, an indicator delta, a
 * distance from a moving average.
 */
@Composable
fun ChangeText(
    text: String,
    direction: MoveDirection,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.titleMedium,
) {
    val marketColors = MiaTheme.marketColors
    val color = when (direction) {
        MoveDirection.UP -> marketColors.bull
        MoveDirection.DOWN -> marketColors.bear
        MoveDirection.FLAT -> marketColors.flat
    }
    val spoken = when (direction) {
        MoveDirection.UP -> "up"
        MoveDirection.DOWN -> "down"
        MoveDirection.FLAT -> "unchanged"
    }
    Text(
        text = direction.glyph + " " + text,
        style = style,
        color = color,
        modifier = modifier.clearAndSetSemantics { contentDescription = "$spoken $text" },
    )
}

/**
 * [ChangeText] for the day's change, which is always a percentage against the previous close.
 *
 * @param changePercent already in percent units (a `0.74` rise renders as `▲ +0.74%`).
 */
@Composable
fun ChangePercentText(
    changePercent: Double?,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.titleMedium,
) {
    ChangeText(
        text = formatSignedPercent(changePercent),
        direction = changePercent?.let { MoveDirection.ofChange(it) } ?: MoveDirection.FLAT,
        modifier = modifier,
        style = style,
    )
}

/**
 * Where a price sits relative to a level — always stated as a measurement plus a direction word,
 * because "distance from the 200-DMA" is a fact and "distance from" plus a red number is not.
 *
 * @param value the level being measured against.
 * @param actual the observed price.
 */
@Composable
fun ValueAgainstLevel(
    label: String,
    value: Double?,
    actual: Double?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (value == null || actual == null) {
            Text(
                text = UiStrings.unavailable("not enough history to compute it"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val delta = actual - value
            ChangeText(
                text = formatSigned(delta) + "  (" + formatPrice(value) + ")",
                direction = MoveDirection.ofChange(delta),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** Maps pattern geometry direction onto the screen's move vocabulary. */
fun Direction.toMoveDirection(): MoveDirection = when (this) {
    Direction.UP -> MoveDirection.UP
    Direction.DOWN -> MoveDirection.DOWN
}
