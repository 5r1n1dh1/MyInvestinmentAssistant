package com.mia.app.ui.share

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlin.math.max

/**
 * Renders a [BriefCard] to a shareable [Bitmap] (plan.md P11b).
 *
 * ### Purity
 * Reads nothing but its arguments. No clock, no locale, no theme, no density, no `Context`, no IO,
 * no Compose. Every dimension derives from [widthPx], so the same card at the same width produces the
 * same pixels on every device — which is what makes the output reviewable, and what makes it
 * impossible for the image's content to depend on what the user's phone happens to be showing.
 *
 * ### Layout, in one pass
 * ```
 * widthPx ─▶ padding   = widthPx * PADDING_FRACTION
 *         ─▶ textWidth = widthPx - 2 * padding
 *         ─▶ bodyPx    = widthPx * BODY_SIZE_FRACTION      (every size is a factor of this)
 *         ─▶ measure every BriefLine with StaticLayout
 *         ─▶ height    = padding + Σ (line height + gap) + padding
 *         ─▶ draw at those offsets, in the same order
 * ```
 * Measure-then-draw is why there is one loop and not two: the image's height is unknown until every
 * line has been wrapped, and a second pass over a 1080 px bitmap is a second full traversal on a
 * device that plan.md §12 assumes has 2 GB of RAM.
 *
 * ### Why `StaticLayout` and not a Compose `Canvas`
 * A Compose canvas would mean a `Composition`, a `Density` and a configuration read — none of which
 * a share action should need, and all of which would make the output depend on the current
 * configuration. `StaticLayout` wraps text with no configuration at all.
 *
 * ### Threading
 * Not main-thread work. The caller runs it inside a coroutine on a background dispatcher; see
 * `ShareBriefButton`.
 */
object BriefBitmapRenderer {

    /** Width in pixels. 1080 is the narrowest size WhatsApp does not downscale. */
    const val DEFAULT_WIDTH_PX: Int = 1080

    /** Narrowest text column that can render this content legibly. */
    const val MIN_WIDTH_PX: Int = 320

    /**
     * Renders [card] to an opaque [Bitmap].
     *
     * @param widthPx image width; values below [MIN_WIDTH_PX] are raised to it, because a text
     *   column narrower than that wraps every line into single characters.
     * @return a bitmap the caller owns: either hand it to `BriefSharer` or `recycle()` it.
     */
    fun renderBriefBitmap(
        card: BriefCard,
        widthPx: Int = DEFAULT_WIDTH_PX,
    ): Bitmap {
        val width = max(widthPx, MIN_WIDTH_PX)
        val lines = BriefCardContent.lines(card)
        val metrics = Metrics(width)
        val layouts = lines.map { measure(it, metrics) }

        val bitmap = Bitmap.createBitmap(width, metrics.totalHeight(lines, layouts), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(BACKGROUND)

        var y = metrics.padding
        lines.forEachIndexed { index, line ->
            val layout = layouts[index]
            canvas.save()
            canvas.translate(metrics.padding.toFloat(), y.toFloat())
            layout.draw(canvas)
            canvas.restore()
            y += layout.height + metrics.gapAfter(line.role)
        }
        return bitmap
    }

    /**
     * Every dimension, derived once from the width.
     *
     * A value rather than a set of free functions so the measure pass and the draw pass read the
     * *same* numbers. Recomputing them in the draw loop is how an image ends up with its first line
     * at a different size from the rest.
     */
    private class Metrics(width: Int) {
        val padding: Int = (width * PADDING_FRACTION).toInt()
        val textWidth: Int = width - padding * 2
        val bodyPx: Float = width * BODY_SIZE_FRACTION
        val gapPx: Int = (width * GAP_FRACTION).toInt().coerceAtLeast(1)

        // Not `private`: a private member of a nested class is invisible to its outer class, and
        // `measure` — which lives in the outer object — needs it.
        fun sizeFor(role: BriefLineRole): Float = bodyPx * role.sizeFactor

        /** The gap after a line: tight after a figure, loose around a heading or a rule. */
        fun gapAfter(role: BriefLineRole): Int = when (role) {
            BriefLineRole.TITLE -> gapPx * 2
            BriefLineRole.SECTION -> gapPx
            BriefLineRole.RULE -> gapPx * 2
            else -> gapPx / 2
        }

        fun totalHeight(lines: List<BriefLine>, layouts: List<StaticLayout>): Int {
            var content = 0
            lines.indices.forEach { index ->
                content += layouts[index].height + gapAfter(lines[index].role)
            }
            return padding * 2 + content
        }
    }

    /**
     * Wraps one line.
     *
     * `setIncludePad(false)` so the measured height matches the drawn height; the default adds a
     * font's internal leading to both, which makes the last line of every block sit a few pixels
     * too low.
     */
    private fun measure(line: BriefLine, metrics: Metrics): StaticLayout {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = line.role.color
            textSize = metrics.sizeFor(line.role)
            typeface = if (line.role.bold) {
                Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            } else {
                Typeface.DEFAULT
            }
        }
        return StaticLayout.Builder
            .obtain(line.text, 0, line.text.length, paint, metrics.textWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, LINE_SPACING_MULTIPLIER)
            .setIncludePad(false)
            .build()
    }

    /** Gutter as a fraction of the width. 6 % reads as a card rather than as a screenshot. */
    private const val PADDING_FRACTION = 0.06f

    /** Body type size as a fraction of the width, so the image scales with no fixed dpi. */
    private const val BODY_SIZE_FRACTION = 0.038f

    /** Space between consecutive lines, as a fraction of the width. */
    private const val GAP_FRACTION = 0.012f

    /** Line-height multiplier. */
    private const val LINE_SPACING_MULTIPLIER = 1.12f

    /** Opaque white. A shared image is read in a chat client with its own background. */
    private const val BACKGROUND = 0xFFFFFFFF.toInt()
}