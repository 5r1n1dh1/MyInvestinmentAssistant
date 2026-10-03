package com.mia.app.ui.di

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import com.mia.app.ui.search.SearchEvent
import com.mia.app.ui.search.SearchScreen
import com.mia.app.ui.search.SearchViewModel
import com.mia.app.ui.speech.HinglishQueryNormaliser
import com.mia.app.ui.speech.HinglishSpeechRecognizer
import com.mia.app.ui.speech.SpeechFailure
import com.mia.app.ui.speech.SpeechLanguage
import com.mia.app.ui.speech.SpeechPhase

/**
 * The Search route, composed the way `MiaNavHost` needs it: the real screen **plus** the voice
 * recogniser that screen's event channel was written for.
 *
 * ### Why a wrapper rather than an edit to `SearchScreen.kt`
 * `SearchScreen.kt` is not this change's to edit, and it already leaves a marked seam:
 * ```
 * // TODO(voice agent): hand `SearchEvent.RequestVoiceSearch` to the Hinglish recogniser …
 * SearchEvent.RequestVoiceSearch -> Unit
 * ```
 * Both hooks it needs already exist — `SearchViewModel.onVoiceResult(text)` and
 * `SearchViewModel.onVoiceFailure(reason)` — and it already renders a `VoiceSearchButton` whose label
 * tracks `VoiceSearchUiState`. So the missing piece is *one collector*, and a collector is something
 * that can be composed from outside.
 *
 * `SearchScreen`'s own `hiltViewModel()` and the one above resolve to the **same** `SearchViewModel`:
 * `hiltViewModel()` keys off `LocalHiltViewModelStoreOwner`, which for a composable inside a
 * `NavHost` destination is that destination's `NavBackStackEntry`, and the default key is the class.
 * So collecting `viewModel.events` here observes exactly the flow the screen emits into.
 *
 * ### The consequence, and what it looks like on screen
 * With this wrapper, tapping **Voice** on the Search screen starts a recognition session, the button
 * label becomes `Stop` while listening, a final transcript is cleaned by
 * [HinglishQueryNormaliser] and handed to `onVoiceResult` — which fills the field through the same
 * debounced path a keystroke uses, so a spoken query and a typed one cannot disagree — and any failure
 * is reported through `onVoiceFailure` with the sentence the user reads.
 *
 * Without it (i.e. if this wrapper is bypassed) the button remains exactly as it is today: it moves the
 * UI state to `Listening` and nothing listens. That is a safe degradation, not a crash.
 *
 * ### Why recognition never opens a stock
 * [HinglishQueryNormaliser]'s output goes into the **search field**, never into navigation. The
 * recogniser's best guess is editable text, and the reader submits it. `VoiceSearchField` enforces the
 * same rule for the same reason (`HinglishQuery.requiresConfirmation`), and plan.md's P11a rule is
 * that a recogniser which renders "reliyanse" as "reliance" must never be one keystroke away from a
 * fully-populated screen about a company nobody asked for.
 */
@Composable
fun SearchRoute(
    onOpenStock: (symbol: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: SearchViewModel = hiltViewModel()
    HinglishVoiceSearchBridge(viewModel)
    SearchScreen(onOpenStock = onOpenStock, modifier = modifier, viewModel = viewModel)
}

/**
 * Owns one [HinglishSpeechRecognizer] for as long as the Search route is composed, and turns
 * [SearchEvent.RequestVoiceSearch] into a session whose result lands back in the ViewModel.
 *
 * ### Lifecycle
 * The recogniser is `remember`ed on the context and released by a [DisposableEffect], because
 * `HinglishSpeechRecognizer` holds a platform recogniser that holds the microphone. `destroy()` is
 * idempotent, so the "session ended" path and the "left the screen" path can both call it.
 *
 * ### The permission is asked here, at the moment of the tap
 * A second tap while listening is a **stop**, not a second request — the recogniser documents that
 * restarting mid-session produces a platform error the user cannot interpret, and
 * `SearchViewModel.onVoiceRequested` has already moved the state to `Idle` for that tap, so the
 * `Listening` state is only ever seen while a session is genuinely open.
 *
 * The launcher's own failure message is [SpeechFailure.MICROPHONE_PERMISSION_MISSING]'s, so a denial
 * produces the same sentence whether the recogniser or the permission dialog reported it.
 *
 * @param viewModel the Search screen's ViewModel, obtained by the caller from the same
 *   `NavBackStackEntry` the screen uses.
 */
@Composable
fun HinglishVoiceSearchBridge(viewModel: SearchViewModel) {
    val context = LocalContext.current
    val recognizer = remember { HinglishSpeechRecognizer(context) }
    DisposableEffect(recognizer) { onDispose { recognizer.destroy() } }

    // `false` until the user taps, so the permission dialog is never shown on the way in.
    var permissionRequested by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionRequested = true
        if (granted) {
            recognizer.startListening(
                language = SpeechLanguage.DEFAULT,
                onUpdate = { outcome -> onOutcome(viewModel, outcome.phase, outcome.bestGuess) },
                onFailure = { failure -> viewModel.onVoiceFailure(failure.message) },
            )
        } else {
            viewModel.onVoiceFailure(SpeechFailure.MICROPHONE_PERMISSION_MISSING.message)
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                SearchEvent.RequestVoiceSearch -> {
                    when {
                        recognizer.isListening() -> recognizer.stopListening()

                        !recognizer.isAvailable() ->
                            viewModel.onVoiceFailure(SpeechFailure.UNAVAILABLE.message)

                        recognizer.hasMicrophonePermission() -> recognizer.startListening(
                            language = SpeechLanguage.DEFAULT,
                            onUpdate = { outcome ->
                                onOutcome(viewModel, outcome.phase, outcome.bestGuess)
                            },
                            onFailure = { failure -> viewModel.onVoiceFailure(failure.message) },
                        )

                        permissionRequested -> viewModel.onVoiceFailure(
                            // Android will not ask again after a refusal, so another tap cannot help
                            // and saying so is more useful than re-prompting into a dead dialog.
                            SpeechFailure.MICROPHONE_PERMISSION_MISSING.message +
                                PERMISSION_REFUSED_TAIL,
                        )

                        else -> permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            }
        }
    }
}

/**
 * Folds one recognition update into the ViewModel.
 *
 * A **partial** result is ignored on purpose. The field is filled only from a final transcript, so the
 * query box never flickers through a half-word and the debounced search is not fired five times per
 * utterance. The recogniser's alternatives are discarded rather than merged: the platform ranks them,
 * and merging hypotheses would be inventing text the app did not hear. `VoiceSearchField` shows them
 * as tappable alternatives instead, which is the honest treatment.
 *
 * An empty final result (the platform returned nothing usable) is reported as
 * [SpeechFailure.NO_MATCH] rather than silently clearing the field, because a tap that appeared to do
 * nothing is the failure this feature is not allowed to have.
 */
private fun onOutcome(viewModel: SearchViewModel, phase: SpeechPhase, bestGuess: String?) {
    if (phase != SpeechPhase.FINAL) return
    val text = bestGuess?.takeIf { it.isNotBlank() }
    if (text == null) {
        viewModel.onVoiceFailure(SpeechFailure.NO_MATCH.message)
        return
    }
    viewModel.onVoiceResult(HinglishQueryNormaliser.normalise(text).searchText)
}

/** Appended to the permission message when the dialog has already been refused once. */
private const val PERMISSION_REFUSED_TAIL: String =
    " It was declined, so it can only be changed in system settings."