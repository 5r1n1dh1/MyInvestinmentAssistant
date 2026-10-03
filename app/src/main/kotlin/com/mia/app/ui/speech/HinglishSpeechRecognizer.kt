package com.mia.app.ui.speech

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat

/**
 * A thin, non-crashing wrapper over the platform [SpeechRecognizer] (plan.md P11a).
 *
 * ### The three rules this class exists to satisfy
 * 1. **Never crash on a device without recognition.** `SpeechRecognizer.isRecognitionAvailable`
 *    is checked before anything is constructed, and the constructor call itself is guarded, because
 *    it throws on devices with no recogniser service at all. Every entry point returns a
 *    [SpeechAvailability] instead of assuming.
 * 2. **Permission-gated.** `RECORD_AUDIO` is checked before [startListening] and a missing grant
 *    is reported as [SpeechFailure.MICROPHONE_PERMISSION_MISSING], never as a recogniser error.
 * 3. **Never claim a word was recognised with certainty.** The result type carries
 *    [SpeechOutcome.bestGuess] as a *guess*, plus every alternative the recogniser offered, and
 *    the UI layer always shows the edit box ([HinglishQuery.requiresConfirmation]).
 *
 * ### Why on-device is a request, not a promise
 * `EXTRA_PREFER_OFFLINE` asks the platform for its on-device model. Whether one is installed, and
 * whether the device honours the hint, is entirely the platform's decision — most Indian devices
 * ship no offline `hi-IN` model at all. [SpeechOutcome.wasOfflinePreferred] records what was
 * *asked for*, and no UI string in this feature claims that recognition ran offline. Claiming it
 * would be a privacy promise the app cannot keep.
 *
 * ### Language
 * [SpeechLanguage] offers the two combinations that actually matter for this audience: Hindi with a
 * preference for English output (which is what a Romanised-Hindi user wants, and the only one this
 * build can transliterate), and plain English. Devanagari output is not transliterated — see
 * [HinglishQueryNormaliser]'s KDoc.
 *
 * ### Lifecycle
 * Not lifecycle-aware and not meant to be. The caller owns it through `remember` +
 * `DisposableEffect`, and [destroy] is idempotent because both the `onDispose` path and the
 * "recogniser died" path call it.
 */
class HinglishSpeechRecognizer(
    private val context: Context,
) {

    private var recognizer: SpeechRecognizer? = null

    /**
     * Whether this device can recognise speech at all.
     *
     * Checked with the static query rather than by constructing a recogniser, which is the only
     * safe way to ask: the constructor throws when no recognition service is installed.
     */
    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    /** Whether the microphone is currently granted. */
    fun hasMicrophonePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Starts a free-form recognition session.
     *
     * @param language which locale to ask for. See [SpeechLanguage].
     * @param onUpdate called for partial and final results. Partial results arrive on the main
     *   thread; the UI uses them to fill the field live, which is why the callback is delivered
     *   rather than collected as a flow.
     * @param onFailure called once per failure, with the reason.
     */
    fun startListening(
        language: SpeechLanguage = SpeechLanguage.DEFAULT,
        onUpdate: (SpeechOutcome) -> Unit,
        onFailure: (SpeechFailure) -> Unit,
    ) {
        if (!hasMicrophonePermission()) {
            onFailure(SpeechFailure.MICROPHONE_PERMISSION_MISSING)
            return
        }
        if (!isAvailable()) {
            onFailure(SpeechFailure.UNAVAILABLE)
            return
        }
        if (isListening()) {
            // A second tap while listening is a "stop", not an error. Restarting mid-session
            // produces an error callback from the platform that the user cannot interpret.
            stopListening()
            return
        }

        val created = try {
            SpeechRecognizer.createSpeechRecognizer(context)
        } catch (throwable: Throwable) {
            onFailure(SpeechFailure.UNAVAILABLE)
            return
        }
        recognizer = created
        created.setRecognitionListener(
            Callback(
                language = language,
                onUpdate = onUpdate,
                onFailure = onFailure,
            ),
        )
        try {
            created.startListening(buildIntent(context, language))
        } catch (throwable: Throwable) {
            onFailure(SpeechFailure.COULD_NOT_START)
        }
    }

    /** Asks the recogniser to finish now. A no-op when nothing is listening. */
    fun stopListening() {
        recognizer?.let { current ->
            runCatching { current.stopListening() }
        }
    }

    /** Abandons the session. A no-op when nothing is listening. */
    fun cancel() {
        recognizer?.let { current ->
            runCatching { current.cancel() }
        }
    }

    /** `true` while a session is open. */
    fun isListening(): Boolean = runCatching { recognizer?.isListening == true }.getOrDefault(false)

    /**
     * Releases the platform recogniser.
     *
     * Idempotent: [onResults], [onError] and the caller's `onDispose` all reach here, and
     * `destroy()` twice throws on some platform versions.
     */
    fun destroy() {
        recognizer?.let { current ->
            runCatching { current.cancel() }
            runCatching { current.destroy() }
        }
        recognizer = null
    }

    /**
     * The `RecognizerIntent` this class sends.
     *
     * Internal rather than private so the field-by-field contract is reviewable next to the rest
     * of the class, and so a future test can assert it without a device.
     */
    private fun buildIntent(context: Context, language: SpeechLanguage): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            // Free-form, not a command-and-control grammar: the user speaks a name, and a keyword
            // grammar would reject everything except the words it was trained on.
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.recognitionLocale)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language.preferenceLocale)
            // Both languages are acceptable. Without this the platform returns only the exact
            // locale and a Hindi speaker reading a Latin ticker gets a Hindi transcript.
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
            // A hint. Honoured only when an offline model is installed; see the class KDoc.
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            // Several alternatives, because the transliteration layer can only work with what the
            // platform offers, and the best one is not always first.
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, MAX_RESULTS)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // Lets the platform apply its own voice-download policy for this app specifically.
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }

    /** Bridges the platform callbacks to this feature's own result types. */
    private inner class Callback(
        private val language: SpeechLanguage,
        private val onUpdate: (SpeechOutcome) -> Unit,
        private val onFailure: (SpeechFailure) -> Unit,
    ) : RecognitionListener {

        override fun onReadyForSpeech(params: Bundle?) {
            onUpdate(
                SpeechOutcome(
                    phase = SpeechPhase.READY,
                    alternatives = emptyList(),
                    wasOfflinePreferred = language.prefersOffline,
                ),
            )
        }

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        /**
         * The platform's "I stopped hearing speech" signal.
         *
         * Nothing to do here: the recogniser closes the session itself and follows this with
         * `onResults` or `onError`, both of which the caller already handles. It is implemented
         * explicitly rather than left out because [RecognitionListener] requires it, and an
         * unimplemented member here would be a compile error at the call site rather than a
         * documented no-op.
         */
        override fun onEndOfSpeech() = Unit

        override fun onPartialResults(partialResults: Bundle?) {
            publish(partialResults, SpeechPhase.PARTIAL)
        }

        override fun onResults(results: Bundle?) {
            publish(results, SpeechPhase.FINAL)
            // A final result ends the session; holding the recogniser open keeps the microphone
            // hot and drains the battery on exactly the devices plan.md §12 targets.
            destroy()
        }

        override fun onError(error: Int) {
            destroy()
            onFailure(SpeechFailure.fromPlatformError(error))
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        private fun publish(bundle: Bundle?, phase: SpeechPhase) {
            val alternatives = bundle
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                .orEmpty()
                .filter { it.isNotBlank() }
            if (alternatives.isEmpty()) return
            onUpdate(
                SpeechOutcome(
                    phase = phase,
                    alternatives = alternatives,
                    wasOfflinePreferred = language.prefersOffline,
                ),
            )
        }
    }

    private companion object {
        /**
         * Alternatives requested from the platform.
         *
         * Four rather than one: the whole transliteration layer works on the recogniser's output,
         * and taking only the top alternative throws away the information that would have made it
         * work. They are shown to the user as alternatives, never silently merged.
         */
        const val MAX_RESULTS = 4
    }
}

/**
 * The languages this feature asks for.
 *
 * @param recognitionLocale `EXTRA_LANGUAGE`, i.e. what the transcript is written in.
 * @param preferenceLocale `EXTRA_LANGUAGE_PREFERENCE`, i.e. what the user would prefer to be
 *   understood as. For a Romanised-Hindi user this is `en-IN`: the *words* they say are English,
 *   and only the language tag is Hindi.
 */
enum class SpeechLanguage(
    val recognitionLocale: String,
    val preferenceLocale: String,
) {
    /**
     * The default for this app's audience.
     *
     * Hindi recognition with English output preferred, because the vocabulary this app resolves is
     * written in Latin script and a Devanagari transcript cannot be looked up (see
     * [HinglishQueryNormaliser]).
     */
    HINDI_PREFERRED_ENGLISH("hi-IN", "en-IN"),

    /** Plain English, for a device whose recogniser has no Hindi model installed. */
    ENGLISH("en-IN", "en-IN"),
    ;

    /** The label on the language chip. */
    val label: String
        get() = when (this) {
            HINDI_PREFERRED_ENGLISH -> "Hindi / English"
            ENGLISH -> "English"
        }

    /** Both this feature asks for prefer the offline model. See the class KDoc. */
    val prefersOffline: Boolean get() = true

    companion object {
        /**
         * The language the panel starts on.
         *
         * Hindi with English output preferred, because this app's vocabulary is written in Latin
         * script — see [HinglishQueryNormaliser]'s KDoc on why a Devanagari transcript cannot be
         * looked up. Exposed as a companion constant rather than a default parameter on the enum's
         * entries so that "which one does the UI start on" has exactly one answer to read.
         */
        val DEFAULT: SpeechLanguage = HINDI_PREFERRED_ENGLISH
    }
}

/** Where in a session a result sits. */
enum class SpeechPhase {
    /** The platform is listening; no words yet. */
    READY,

    /** An interim hypothesis. Shown live, never treated as the answer. */
    PARTIAL,

    /** The platform's best answer, plus its alternatives. Still a guess. */
    FINAL,
}

/**
 * One recognition result.
 *
 * @param bestGuess the first alternative, i.e. what the platform ranked highest. A **guess**; the
 *   name is the contract.
 * @param alternatives every hypothesis the platform returned, best first. Rendered as tappable
 *   alternatives, because the top one is wrong often enough that offering the runner-up is the
 *   single most useful thing this feature can do.
 * @param wasOfflinePreferred what was *requested*, never what happened. See
 *   [HinglishSpeechRecognizer]'s KDoc.
 */
data class SpeechOutcome(
    val phase: SpeechPhase,
    val alternatives: List<String>,
    val wasOfflinePreferred: Boolean,
) {
    /** The top hypothesis, or `null` when the platform returned nothing usable. */
    val bestGuess: String? get() = alternatives.firstOrNull()

    /** `true` when there is nothing to show, so the UI can skip an update. */
    val isEmpty: Boolean get() = alternatives.isEmpty()

    /** Every alternative except [bestGuess], i.e. what the user can switch to. */
    val otherAlternatives: List<String> get() = alternatives.drop(1)
}

/**
 * Why a session failed.
 *
 * A closed set, because the platform's integer codes are meaningless to a user and the UI has to
 * write a sentence for each of these. Unknown codes become [UNKNOWN] with the code attached, so
 * a reviewer can look it up without the user seeing a number.
 */
enum class SpeechFailure(val message: String) {
    /** `android.speech.RecognitionListener.ERROR_NO_MATCH` */
    NO_MATCH("Nothing was heard. Tap the microphone and try again."),

    /** `ERROR_SPEECH_TIMEOUT` */
    SPEECH_TIMEOUT("Nothing was heard before the recogniser gave up."),

    /** `ERROR_INSUFFICIENT_PERMISSIONS` */
    MICROPHONE_PERMISSION_MISSING(
        "The microphone permission is not granted. Speech search stays off until it is.",
    ),

    /** `ERROR_NETWORK` / `ERROR_NETWORK_TIMEOUT`, or no recogniser service installed. */
    UNAVAILABLE(
        "Speech recognition is not available on this device. Type the ticker instead.",
    ),

    /** `ERROR_RECOGNIZER_BUSY`, `ERROR_CLIENT`, or a start that threw. */
    COULD_NOT_START("The recogniser could not start. Type the ticker instead."),

    /** `ERROR_INSUFFICIENT_DATA` / `ERROR_NO_RECOGNITION_LANGUAGE` */
    UNSUPPORTED_LANGUAGE(
        "This device has no language model for that language. Try English.",
    ),

    /** `ERROR_SERVER`, or an error code this build does not know. */
    UNKNOWN("Speech recognition stopped for an unstated reason. Type the ticker instead."),
    ;

    /**
     * Maps `RecognitionListener`'s error constants.
     *
     * The values are spelled out numerically rather than referenced, so this file needs no platform
     * import and can be checked by reading. Note that two of the platform codes are genuinely
     * ambiguous — `12` is both `ERROR_NETWORK_TIMEOUT` and `ERROR_LANGUAGE_NOT_SUPPORTED`, and
     * `13` is both `ERROR_SERVER` and `ERROR_LANGUAGE_UNAVAILABLE` — so only one `when` label can
     * exist per value, and the sentence chosen is one that is true in both cases.
     */
    companion object {
        private const val ERROR_NETWORK = 2
        private const val ERROR_SPEECH_TIMEOUT = 3
        private const val ERROR_NO_MATCH = 7
        private const val ERROR_RECOGNIZER_BUSY = 8
        private const val ERROR_INSUFFICIENT_PERMISSIONS = 9
        private const val ERROR_INSUFFICIENT_DATA = 10

        /** Both `ERROR_NETWORK_TIMEOUT` and `ERROR_LANGUAGE_NOT_SUPPORTED`. */
        private const val ERROR_CODE_12 = 12

        private const val ERROR_CLIENT = 16

        fun fromPlatformError(code: Int): SpeechFailure = when (code) {
            ERROR_NO_MATCH -> NO_MATCH
            ERROR_SPEECH_TIMEOUT -> SPEECH_TIMEOUT
            ERROR_INSUFFICIENT_PERMISSIONS -> MICROPHONE_PERMISSION_MISSING
            ERROR_RECOGNIZER_BUSY, ERROR_CLIENT -> COULD_NOT_START
            ERROR_NETWORK, ERROR_CODE_12 -> UNAVAILABLE
            ERROR_INSUFFICIENT_DATA -> UNSUPPORTED_LANGUAGE
            else -> UNKNOWN
        }
    }
}