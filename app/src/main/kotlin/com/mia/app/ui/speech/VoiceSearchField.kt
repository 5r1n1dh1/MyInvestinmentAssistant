package com.mia.app.ui.speech

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.mia.app.ui.theme.MiaSpacing
import kotlinx.coroutines.delay

/**
 * The Hinglish voice-search panel (plan.md P11a).
 *
 * ### The rule this composable exists to enforce
 * **Recognition never navigates.** The recogniser's output is typed into an editable
 * [OutlinedTextField], and only a user action submits it. There is no code path from a recognition
 * callback to a stock screen, because a recogniser that renders "reliyanse" as "reliance" would
 * otherwise open a fully-populated, entirely convincing screen about a company the user never
 * asked for — the one failure this feature is not allowed to have.
 * [HinglishQuery.requiresConfirmation] is unconditionally `true` and the field is always present.
 *
 * ### Why the transcript is only ever a *guess*
 * The platform returns a ranked hypothesis, not a transcript. It is typed into the field as a
 * starting point and captioned as something the recogniser thinks it heard
 * ([HinglishQuery.banner]). On a mid-range Indian device with no offline `hi-IN` model the top
 * hypothesis is frequently wrong, and the alternative list the platform returns is frequently
 * right — which is why every alternative is offered instead of being discarded.
 *
 * ### No icon dependency
 * The microphone control is a labelled tonal button rather than an `IconButton`. The
 * `material-icons-extended` artifact is not a dependency of this module, so `Icons.Filled.Mic`
 * would not resolve; a labelled button needs no new artifact and reads better with a screen reader
 * than an unlabelled glyph.
 *
 * ### Integration
 * Drop this next to an existing search field, or use it *as* the field: it owns no navigation and
 * no ViewModel, so the search screen keeps its own symbol resolver and its own results list. The
 * only contract is [onSubmit], which the caller routes wherever its own search does.
 *
 * @param value the current search text, owned by the caller so it survives recomposition and
 *   rotation without this composable owning any state for it.
 * @param onValueChange called on every keystroke **and** on every recognition result.
 * @param onSubmit called when the user presses the keyboard's search action. Never called by this
 *   composable itself.
 */
@Composable
fun VoiceSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search a symbol, e.g. RELIANCE",
    label: String = "Symbol",
) {
    val context = LocalContext.current

    val recognizer = remember { HinglishSpeechRecognizer(context) }
    DisposableEffect(recognizer) {
        onDispose { recognizer.destroy() }
    }

    var isAvailable by remember { mutableStateOf(recognizer.isAvailable()) }
    var isListening by remember { mutableStateOf(false) }
    var language by remember { mutableStateOf(SpeechLanguage.DEFAULT) }
    var failure by rememberSaveable { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf<HinglishQuery?>(null) }
    var alternatives by remember { mutableStateOf<List<String>>(emptyList()) }
    var listeningSince by remember { mutableStateOf(0L) }

    /**
     * Opens one session and folds its callbacks into the field.
     *
     * A local function rather than two copies of the block at the permission and the button: the
     * two paths must behave identically, and duplicated callback bodies are how a "partial results
     * also updates the field" fix ends up applied to only one of them.
     */
    fun startSession() {
        failure = null
        recognizer.startListening(
            language = language,
            onUpdate = { outcome ->
                if (outcome.isEmpty) return@startListening
                val best = outcome.bestGuess ?: return@startListening
                val normalised = HinglishQueryNormaliser.normalise(best)
                query = normalised
                alternatives = outcome.otherAlternatives
                // The field takes the cleaned text. The UI panel is where the candidates and the
                // alternatives are shown, so recognition alone can never choose a stock.
                onValueChange(normalised.searchText)
                if (outcome.phase == SpeechPhase.FINAL) isListening = false
            },
            onFailure = { error ->
                isListening = false
                failure = error.message
            },
        )
        isListening = recognizer.isListening()
        listeningSince = System.currentTimeMillis()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        isAvailable = recognizer.isAvailable()
        failure = if (granted) {
            null
        } else {
            SpeechFailure.MICROPHONE_PERMISSION_MISSING.message
        }
        if (granted) startSession()
    }

    // A hard ceiling on one session. Without it a device that never delivers `onResults` holds the
    // microphone open indefinitely, which is the battery complaint plan.md §12 exists to avoid.
    LaunchedEffect(isListening, listeningSince) {
        if (!isListening) return@LaunchedEffect
        delay(MAX_LISTENING_MILLIS)
        recognizer.stopListening()
        isListening = false
        failure = LISTENING_TIMED_OUT
    }

    val toggleVoice: () -> Unit = {
        when {
            !isAvailable -> failure = SpeechFailure.UNAVAILABLE.message

            isListening -> {
                recognizer.stopListening()
                isListening = false
            }

            recognizer.hasMicrophonePermission() -> startSession()

            else -> permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MiaSpacing.sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MiaSpacing.sm),
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = "$label search box" },
                label = { Text(text = label) },
                placeholder = { Text(text = placeholder) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit(value) }),
            )
            VoiceButton(
                isAvailable = isAvailable,
                isListening = isListening,
                onClick = toggleVoice,
            )
        }

        LanguageChips(
            selected = language,
            enabled = !isListening,
            onSelect = { language = it },
        )

        failure?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        query?.let { recognised ->
            RecognitionCard(query = recognised, alternatives = alternatives)
        }
    }
}

/**
 * The card under the field: what the recogniser thinks it heard, the honest caveat, the tickers
 * that might match, and every other hypothesis the platform offered.
 */
@Composable
private fun RecognitionCard(
    query: HinglishQuery,
    alternatives: List<String>,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MiaSpacing.md),
            verticalArrangement = Arrangement.spacedBy(MiaSpacing.xs),
        ) {
            Text(text = query.banner, style = MaterialTheme.typography.bodySmall)
            query.candidates.forEach { candidate ->
                Text(
                    text = candidate.displayLabel + " — " + candidate.explanation,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            alternatives.forEach { alternative ->
                Text(
                    text = "Also heard: \"$alternative\"",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

/**
 * The voice button: three states, each carrying its own spoken reason.
 *
 * Disabled only while listening (so a second tap stops). An *unavailable* recogniser still
 * responds, because the tap is what produces the explanation — a permanently disabled control with
 * no explanation is the least helpful thing a screen can do with a missing feature.
 */
@Composable
private fun VoiceButton(
    isAvailable: Boolean,
    isListening: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = when {
        isListening -> "Stop"
        !isAvailable -> "Voice unavailable"
        else -> "Voice"
    }
    FilledTonalButton(
        onClick = onClick,
        enabled = !isListening,
        modifier = modifier.semantics {
            contentDescription = when {
                isListening -> "Stop listening"
                !isAvailable -> "Voice search is unavailable on this device. Tap for details."
                else -> "Search by voice"
            }
        },
    ) {
        if (isListening) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp))
        } else {
            Text(text = label)
        }
    }
}

/** The two language chips, and only two — same reason the timeframe toggle has two. */
@Composable
private fun LanguageChips(
    selected: SpeechLanguage,
    enabled: Boolean,
    onSelect: (SpeechLanguage) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(MiaSpacing.sm),
    ) {
        SpeechLanguage.entries.forEach { language ->
            AssistChip(
                onClick = { onSelect(language) },
                enabled = enabled,
                label = { Text(text = language.label) },
                modifier = Modifier.semantics {
                    contentDescription = language.label +
                        if (language == selected) ", selected" else ""
                },
            )
        }
    }
}

/**
 * Hard ceiling on one listening session.
 *
 * The platform's own silence timeout is roughly ten seconds, which is generous for a name and long
 * enough to hold the microphone open on a 2 GB phone. Four seconds is enough to say one ticker.
 */
private const val MAX_LISTENING_MILLIS = 4_000L

/** Shown when the ceiling is reached without a final result. */
private const val LISTENING_TIMED_OUT =
    "Stopped listening without hearing a complete name. Type the ticker instead."