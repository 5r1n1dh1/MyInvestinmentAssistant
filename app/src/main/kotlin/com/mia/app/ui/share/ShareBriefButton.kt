package com.mia.app.ui.share

import android.content.Context
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The share entry point (plan.md P11b).
 *
 * ### Where it goes
 * * **Settings → Share brief as image** is wired today: a symbol field and this button.
 * * The detail-screen header is the intended second entry point. Wire it as
 *   `ShareBriefButton(card = state.briefCard, label = "Share")` beside the app-bar actions, with
 *   the card built from the fields that header already holds. That is a placement decision, not an
 *   API change.
 *
 * ### Why the render happens in a coroutine
 * `renderBriefBitmap` wraps roughly forty lines of text and allocates a 1080 px ARGB bitmap — about
 * 4 MB. plan.md §12 targets 2 GB-RAM devices, so it runs on [Dispatchers.Default], and the
 * `ACTION_SEND` dispatch is brought back to the main thread, where `startActivity` is required.
 *
 * ### Why the button can be pressed again after a failure
 * [ShareOutcome.FAILED] and [ShareOutcome.NO_RECEIVER] set a message rather than throwing, and the
 * button re-enables. A share that silently does nothing is the failure this avoids: the outcome is
 * always stated, on screen, in the caller's own words.
 *
 * @param card the brief to render. `null` disables the button, because an image of an empty card is
 *   a picture with no figures on it.
 * @param onOutcome called with the result, for logging or a snackbar. The button already renders
 *   [ShareOutcome.message] inline, so a caller may ignore it.
 */
@Composable
fun ShareBriefButton(
    card: BriefCard?,
    modifier: Modifier = Modifier,
    label: String = "Share brief as image",
    widthPx: Int = BriefBitmapRenderer.DEFAULT_WIDTH_PX,
    onOutcome: (ShareOutcome) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isRendering by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    OutlinedButton(
        onClick = {
            val brief = card ?: return@OutlinedButton
            isRendering = true
            message = null
            scope.launch {
                val outcome = renderAndShare(
                    context = context,
                    card = brief,
                    widthPx = widthPx,
                )
                isRendering = false
                message = outcome.message
                onOutcome(outcome)
            }
        },
        enabled = card != null && !isRendering,
        modifier = modifier.testTag(SHARE_BUTTON_TAG),
    ) {
        if (isRendering) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp))
        } else {
            Text(text = label)
        }
    }

    message?.let { text ->
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(SHARE_MESSAGE_TAG),
        )
    }
}

/**
 * Render, then share, split out so the button's callback stays one line and the threading lives in
 * one place.
 *
 * The bitmap is recycled in a `finally`, so a failed `startActivity` cannot leak 4 MB on a device
 * that has 2 GB. Recycling is safe because [BriefSharer.share] compresses the bitmap to PNG
 * synchronously, before it returns. The *file* the URI points at is deliberately not deleted: the
 * receiving app reads it asynchronously, and the cache directory is reclaimed by the system.
 */
private suspend fun renderAndShare(
    context: Context,
    card: BriefCard,
    widthPx: Int,
): ShareOutcome {
    val bitmap = withContext(Dispatchers.Default) {
        try {
            BriefBitmapRenderer.renderBriefBitmap(card = card, widthPx = widthPx)
        } catch (failure: Throwable) {
            null
        }
    } ?: return ShareOutcome.FAILED("the brief could not be drawn")

    return try {
        withContext(Dispatchers.Main) {
            BriefSharer.share(context = context, bitmap = bitmap, symbol = card.symbol)
        }
    } finally {
        if (!bitmap.isRecycled) bitmap.recycle()
    }
}

/** Test tag on the share button. */
const val SHARE_BUTTON_TAG: String = "mia_share_brief"

/** Test tag on the outcome line beneath the button. */
const val SHARE_MESSAGE_TAG: String = "mia_share_brief_message"