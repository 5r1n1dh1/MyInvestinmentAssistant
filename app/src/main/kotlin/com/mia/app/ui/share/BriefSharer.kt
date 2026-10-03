package com.mia.app.ui.share

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import com.mia.app.domain.compliance.Disclosure
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Shares a rendered brief through `ACTION_SEND` (plan.md P11b).
 *
 * ### Why `FileProvider` and not a `content://` URI built by hand
 * Sharing an `Intent` with another app needs a URI that app is allowed to read. Since API 24 that
 * has to be a `content://` URI from a `FileProvider`, and the provider's grant must be attached to
 * the `Intent` — a file path in `EXTRA_STREAM` is ignored on modern Android, and writing into a
 * shared directory either throws on API 29+ or exposes the app's cache to every installed app.
 *
 * **The manifest entry this needs, which `:app` must declare** (this module cannot add it):
 * ```xml
 * <provider
 *     android:name="androidx.core.content.FileProvider"
 *     android:authorities="${applicationId}.fileprovider"
 *     android:exported="false"
 *     android:grantUriPermissions="true">
 *     <meta-data
 *         android:name="android.support.FILE_PROVIDER_PATHS"
 *         android:resource="@xml/file_paths" />
 * </provider>
 * ```
 * with `res/xml/file_paths.xml` declaring `<cache-path name="shared_briefs" path="shared_briefs/" />`.
 * The authority and the cache-path name are both named as constants below so the manifest and this
 * file cannot disagree about them silently.
 */
object BriefSharer {

    /**
     * The authority the provider must be declared under.
     *
     * `"${context.packageName}.fileprovider"`, spelled as a function because the package name is
     * only known at runtime.
     */
    fun authorityFor(context: Context): String = context.packageName + ".fileprovider"

    /** The sub-directory of the cache the brief is written to. Must match `file_paths.xml`. */
    const val SHARE_DIR_NAME: String = "shared_briefs"

    /**
     * Writes [bitmap] to the share directory and returns the URI to hand out.
     *
     * The file is PNG, named with the symbol and a timestamp so two shares of the same stock do not
     * overwrite each other, and left in place: the receiving app reads it asynchronously and
     * deleting it immediately would race that read. It lives in the cache directory, so the system
     * reclaims it.
     *
     * @throws IOException when the cache directory or the file cannot be created. The caller turns
     *   this into [ShareOutcome.FAILED] rather than letting it escape.
     */
    fun writeToCache(context: Context, bitmap: Bitmap, symbol: String): Uri {
        val directory = File(context.cacheDir, SHARE_DIR_NAME)
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("could not create the share directory at ${directory.absolutePath}")
        }
        val file = File(directory, fileName(symbol))
        FileOutputStream(file).use { stream ->
            // PNG rather than JPEG: the image is flat colour and text, and JPEG's ringing around
            // glyph edges makes a ₹ sign look damaged.
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, stream)) {
                throw IOException("the brief could not be encoded as a PNG")
            }
            stream.flush()
        }
        return FileProvider.getUriForFile(context, authorityFor(context), file)
    }

    /**
     * Builds the chooser intent for [uri].
     *
     * @param symbol only used in the chooser title, so the sheet reads "Share RELIANCE brief".
     * @return an intent ready for `startActivity`. Grant flags are set here, not by the caller, so
     *   no caller can forget them.
     */
    fun buildChooser(uri: Uri, symbol: String): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = MIME_PNG
            putExtra(Intent.EXTRA_STREAM, uri)
            // The text carries the same disclosures as the image footer, because a receiving app
            // may drop the attachment and keep the message.
            putExtra(Intent.EXTRA_TEXT, shareMessage(symbol))
            putExtra(Intent.EXTRA_SUBJECT, "$symbol — end-of-day brief")
            // `clipData` alongside the flag: the flag alone does not grant on every OEM build, and
            // the `clipData` alone is ignored on API 24+.
            clipData = ClipData.newRawUri(SHARE_CLIP_LABEL, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // `createChooser` builds a fresh intent, so the grant is repeated on the chooser itself.
        return Intent.createChooser(send, "Share $symbol brief").apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * Writes, builds and starts the share, reporting what happened.
     *
     * Never throws: every failure becomes a [ShareOutcome] carrying a sentence, because the caller
     * is a composable that has nowhere to put an exception.
     */
    fun share(context: Context, bitmap: Bitmap, symbol: String): ShareOutcome {
        val cleanSymbol = symbol.trim().uppercase().ifEmpty { "brief" }
        return try {
            val uri = writeToCache(context, bitmap, cleanSymbol)
            val chooser = buildChooser(uri, cleanSymbol)
            context.startActivity(chooser)
            ShareOutcome.SHARED
        } catch (noHandler: ActivityNotFoundException) {
            // No app on the device accepts `ACTION_SEND`, which is rare but real on a stripped
            // build. A chooser with nothing in it would look like the app hanging.
            ShareOutcome.NO_RECEIVER
        } catch (failure: Throwable) {
            ShareOutcome.FAILED(failure.message ?: "the brief could not be prepared for sharing")
        }
    }

    /**
     * The message body.
     *
     * The two disclosures that matter if the attachment is dropped, plus the data sources. Written
     * out here rather than assembled from a constant so the exact text is reviewable in one place,
     * and it deliberately does not mention the score, the geometry or the hit rate: a text-only
     * share must not become a decontextualised number.
     */
    fun shareMessage(symbol: String): String = buildString {
        append(Disclosure.APP_NAME)
        append(" — ")
        append(symbol)
        append('\n')
        append(Disclosure.SETTINGS)
        append('\n')
        append(Disclosure.DATA_SOURCE)
    }

    /** `RELIANCE-brief-1788326400000.png`. The millis keep two shares of one stock apart. */
    private fun fileName(symbol: String): String =
        symbol.replace(Regex("[^A-Za-z0-9._-]"), "_") + "-brief-" + System.currentTimeMillis() + ".png"

    /** PNG is lossless, so the "quality" argument is ignored; 100 keeps the intent obvious. */
    private const val PNG_QUALITY = 100

    private const val MIME_PNG = "image/png"

    /** Clip label for the shared image. Shown by some receivers in a "via" line. */
    private const val SHARE_CLIP_LABEL = "stock brief"
}

/** What a share attempt did. */
sealed interface ShareOutcome {

    /** The chooser is open. */
    data object SHARED : ShareOutcome

    /** No installed app accepts a shared image. */
    data object NO_RECEIVER : ShareOutcome

    /** The brief could not be prepared. [reason] is already plain language. */
    data class FAILED(val reason: String) : ShareOutcome

    /** One sentence for a snackbar or an inline message. */
    val message: String
        get() = when (this) {
            SHARED -> "Brief ready to share."
            NO_RECEIVER -> "No app on this device can receive a shared image."
            is FAILED -> "The brief could not be shared — $reason"
        }

    /** `true` when something went wrong, so the caller can keep the failure visible. */
    val isFailure: Boolean get() = this !is ShareOutcome.SHARED
}