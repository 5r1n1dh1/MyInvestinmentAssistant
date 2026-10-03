package com.mia.app.ui.search

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mia.app.ui.common.DataSourceFooter
import com.mia.app.ui.common.EmptyState
import com.mia.app.ui.common.ErrorState
import com.mia.app.ui.common.LoadingState
import com.mia.app.ui.common.SectionHeader
import com.mia.app.ui.common.SourceBadge
import com.mia.app.ui.theme.MiaSpacing

/**
 * The app's front door (plan.md §1.1: *open app → search a stock*).
 *
 * ### What makes this screen different from every other one
 * It is **entirely offline and entirely local**. plan.md §3.6 forbids screener.in's `?q=` search
 * endpoint and §9 requirement 5 requires local search over the bundled `EQUITY_L.csv`; nothing in
 * this file issues a request, and the data source is documented as never touching the network. That
 * is also why the screen is instant rather than merely fast, and why it works on a plane.
 *
 * ### The resolved-solution path is the point of the screen
 * plan.md §9 requirement 4 — *"Never return zero results silently"* — is not a nicety here. The plan
 * measured that 1 of 30 large caps (`TATAMOTORS`) returns **zero** rows because it demerged, and an
 * app that answers that with an empty list teaches the reader that the app is unreliable. So:
 *
 * * hits → the ranked list, exact ticker first;
 * * no hits but the resolver found a successor → [ResolvedSymbolCard] with the resolver's own
 *   sentence and a button that opens the successor;
 * * no hits and no successor → the near misses plus a sentence saying it could not match, never a
 *   bare "0 results".
 *
 * ### Section order
 * ```
 *  search field + voice   pinned OUTSIDE the scroll: a query box that scrolls away is unusable
 *  notice line            one line, transient — "RELIANCE added to your tracked symbols"
 *  error                  whole-screen, with a retry
 *  list                   master header · resolved card · near misses · hits
 *  data-source footer     the master file is a source, and plan.md §1.4 rule 3 is about provenance
 * ```
 *
 * @param onOpenStock invoked with a ticker when the reader picks a result. The screen never
 *   navigates itself: `Screen`/`NavHost` belong to another agent, so the route is built here and
 *   called there. Always opens `Timeframe.D1`, because plan.md decision D1 makes daily the default
 *   granularity and the detail screen's own toggle changes it from there.
 * @param viewModel obtained with `hiltViewModel()`. The Search route has no arguments, so there is
 *   no `SavedStateHandle` to read; see `SearchViewModel` for why the typed query deliberately does
 *   not survive process death.
 */
@Composable
fun SearchScreen(
    onOpenStock: (symbol: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // The voice recogniser lives in `ui/speech` and is owned by another agent. The event is emitted
    // here, from `SearchViewModel.onVoiceRequested`, so wiring it is one line here and one line in
    // that agent's module — the recogniser never has to know a composable exists.
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                // TODO(voice agent): hand `SearchEvent.RequestVoiceSearch` to the Hinglish
                // recogniser, then report back with `viewModel.onVoiceResult(text)` or
                // `viewModel.onVoiceFailure(reason)`. Both hooks already exist and both drive the
                // `listening` / `transcribing` / `error` states rendered below.
                SearchEvent.RequestVoiceSearch -> Unit
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag(SEARCH_TAG),
    ) {
        SearchField(
            query = state.query,
            voice = state.voice,
            onQueryChanged = viewModel::onQueryChanged,
            onClear = { viewModel.onQueryChanged("") },
            onVoiceClick = viewModel::onVoiceRequested,
            onVoiceDismissed = viewModel::onVoiceDismissed,
        )

        state.notice?.let { notice ->
            Text(
                text = notice,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = MiaSpacing.lg, vertical = MiaSpacing.xs),
            )
        }

        state.errorReason?.let { reason ->
            ErrorState(
                reason = reason,
                onRetry = viewModel::onRetry,
                modifier = Modifier.padding(horizontal = MiaSpacing.lg, vertical = MiaSpacing.sm),
            )
        }

        if (state.isLoadingBook && state.results.isEmpty()) {
            LoadingState(label = LOADING_BOOK_LABEL)
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = MiaSpacing.lg,
                    end = MiaSpacing.lg,
                    top = MiaSpacing.sm,
                    bottom = MiaSpacing.xxl,
                ),
                verticalArrangement = Arrangement.spacedBy(MiaSpacing.md),
            ) {
                item(key = HEADER_KEY) { MasterHeader(state = state) }

                state.resolution
                    ?.takeIf { it.summary != null }
                    ?.let { resolution ->
                        item(key = RESOLVED_KEY) {
                            ResolvedSymbolCard(
                                resolution = resolution,
                                trackedSymbols = state.trackedSymbols,
                                onOpenStock = onOpenStock,
                                onAddToTracked = viewModel::onAddToTracked,
                            )
                        }
                    }

                state.resolution
                    ?.takeIf { it.summary == null && it.alternatives.isNotEmpty() }
                    ?.let { resolution ->
                        item(key = UNRESOLVED_KEY) { UnresolvedHeader(resolution = resolution) }
                        items(
                            items = resolution.alternatives,
                            key = { "alt.${it.symbol}" },
                        ) { summary ->
                            SymbolRow(
                                summary = summary,
                                trackedSymbols = state.trackedSymbols,
                                onOpenStock = onOpenStock,
                                onAddToTracked = viewModel::onAddToTracked,
                            )
                        }
                    }

                items(
                    items = state.results,
                    key = { "hit.${it.summary.symbol}" },
                ) { match ->
                    SymbolRow(
                        summary = match.summary,
                        trackedSymbols = state.trackedSymbols,
                        onOpenStock = onOpenStock,
                        onAddToTracked = viewModel::onAddToTracked,
                    )
                }

                if (state.isTrulyEmpty) {
                    item(key = EMPTY_KEY) {
                        EmptyState(
                            title = "No instrument in the bundled NSE master matches that text",
                            body = "The bundled symbol master holds every instrument NSE publishes " +
                                "and is searched entirely offline, so nothing here needs a network. " +
                                "Check the ticker spelling, or search part of the company name.",
                        )
                    }
                }

                item(key = FOOTER_KEY) { DataSourceFooter() }
            }
        }
    }
}

// --------------------------------------------------------------------------- the field

/**
 * The search box and the voice affordance, pinned above the list.
 *
 * ### Why the box is above the scroll and not a list item
 * A query field that scrolls off the top of its own results is unusable on a 5-inch screen with a
 * keyboard open. It is laid out outside the `LazyColumn` so it cannot move.
 *
 * ### Why no trailing clear icon when the field is empty
 * A disabled affordance that still occupies a touch target invites taps that do nothing. The
 * trailing slot is only composed when there is something to clear.
 */
@Composable
private fun SearchField(
    query: String,
    voice: VoiceSearchUiState,
    onQueryChanged: (String) -> Unit,
    onClear: () -> Unit,
    onVoiceClick: () -> Unit,
    onVoiceDismissed: () -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MiaSpacing.lg, vertical = MiaSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MiaSpacing.sm),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChanged,
                modifier = Modifier
                    .weight(1f)
                    .testTag(SEARCH_FIELD_TAG),
                label = { Text(text = SEARCH_FIELD_LABEL) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        // The field is debounced, so the keyboard's Search key only has to dismiss
                        // itself; the results arrive from the same debounced path a keystroke uses.
                        keyboard?.hide()
                    },
                ),
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        TextButton(
                            onClick = onClear,
                            modifier = Modifier.testTag(SEARCH_CLEAR_TAG),
                        ) {
                            Text(text = CLEAR_LABEL)
                        }
                    }
                },
            )
            VoiceSearchButton(
                state = voice,
                onClick = onVoiceClick,
                modifier = Modifier.testTag(VOICE_BUTTON_TAG),
            )
        }

        VoiceStatusLine(
            state = voice,
            onDismissed = onVoiceDismissed,
        )
    }
}

/**
 * The voice affordance.
 *
 * ### Why the label is a word and not a microphone glyph
 * `material-icons-core`'s availability has moved between Compose versions (see the comment in
 * `MainActivity`'s app bar), and an emoji microphone renders as a colour glyph that does not match
 * the rest of the app. A word is unambiguous, needs no icon artifact, and is what TalkBack reads
 * most reliably.
 *
 * ### Why the labels change with the state
 * A button that always says "Voice" leaves the reader unable to tell whether the app heard them.
 * `Stop` while listening is both the correct action and the correct status, which is one thing to
 * read instead of two.
 *
 * ### What this composable deliberately does not own
 * The recogniser. It emits `onClick`; `SearchViewModel` turns that into
 * [SearchEvent.RequestVoiceSearch]; the other agent's code performs the recognition and reports back.
 * Nothing here imports `ui/speech`, so the two can land in either order.
 */
@Composable
fun VoiceSearchButton(
    state: VoiceSearchUiState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isTranscribing = state == VoiceSearchUiState.Transcribing
    OutlinedButton(
        onClick = onClick,
        enabled = !isTranscribing,
        modifier = modifier.semantics {
            contentDescription = when (state) {
                VoiceSearchUiState.Idle -> "Search by voice"
                VoiceSearchUiState.Listening -> "Stop listening"
                VoiceSearchUiState.Transcribing -> "Transcribing your speech"
                is VoiceSearchUiState.Failed -> "Retry voice search"
            }
        },
    ) {
        Text(text = voiceButtonLabel(state))
    }
}

/** `Voice` / `Stop` / `Transcribing`. The label is also the action, which is why it changes. */
private fun voiceButtonLabel(state: VoiceSearchUiState): String = when (state) {
    VoiceSearchUiState.Idle -> VOICE_LABEL
    VoiceSearchUiState.Listening -> VOICE_STOP_LABEL
    VoiceSearchUiState.Transcribing -> VOICE_TRANSCRIBING_LABEL
    is VoiceSearchUiState.Failed -> VOICE_LABEL
}

/**
 * What the recogniser is doing, in words.
 *
 * Renders **nothing** when idle. A permanent "Ready to listen" line is a third piece of chrome on a
 * screen whose only job is one text field, and it trains the eye to ignore the line that appears
 * when the microphone is actually open.
 */
@Composable
private fun VoiceStatusLine(
    state: VoiceSearchUiState,
    onDismissed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        VoiceSearchUiState.Idle -> Unit

        VoiceSearchUiState.Listening -> StatusLine(
            text = VOICE_LISTENING_LABEL,
            isProblem = false,
            showActivity = true,
            modifier = modifier,
        )

        VoiceSearchUiState.Transcribing -> StatusLine(
            text = VOICE_TRANSCRIBING_LINE,
            isProblem = false,
            modifier = modifier,
        )

        is VoiceSearchUiState.Failed -> Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = MiaSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(MiaSpacing.xs),
        ) {
            StatusLine(text = state.reason, isProblem = true)
            TextButton(
                onClick = onDismissed,
                modifier = Modifier.testTag(VOICE_DISMISS_TAG),
            ) {
                Text(text = VOICE_DISMISS_LABEL)
            }
        }
    }
}

/**
 * One status line plus, while listening, a thin activity bar.
 *
 * The bar is the only motion on the screen and it is an *indeterminate* `LinearProgressIndicator`,
 * which is honest: nothing knows how long a dictated phrase is, so any determinate animation would
 * be a fabricated countdown.
 */
@Composable
private fun StatusLine(
    text: String,
    isProblem: Boolean,
    modifier: Modifier = Modifier,
    showActivity: Boolean = false,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MiaSpacing.xs),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = if (isProblem) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.testTag(VOICE_STATUS_TAG),
        )
        if (showActivity) {
            LinearProgressIndicator(modifier = Modifier.size(width = 160.dp, height = 2.dp))
        }
    }
}

// ------------------------------------------------------------------------ the list parts

/**
 * How big the bundled master is, and where it came from.
 *
 * plan.md §3.4 and §1.4 rule 3: the symbol master is a **source**, and a search box that finds
 * nothing is much easier to trust when the reader can see how many instruments it searched and
 * which file they came from.
 */
@Composable
private fun MasterHeader(state: SearchUiState, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MiaSpacing.xs),
    ) {
        SectionHeader(
            title = SEARCH_HEADER_TITLE,
            subtitle = if (state.bookSize > 0) {
                "${state.bookSize} instruments in the bundled NSE symbol master · searched on " +
                    "device, no network"
            } else {
                "Offline search over the bundled NSE symbol master."
            },
        )
        SourceBadge(source = SYMBOL_BOOK_SOURCE)
    }
}

/**
 * plan.md §9 requirement 4, rendered.
 *
 * ### Why the explanation is shown verbatim
 * The resolver wrote [SymbolResolution.explanation] to be read by a person — "TATAMOTORS changed
 * after the demerger; showing TMCV" — and paraphrasing it here would be the one place the app could
 * quietly lose the *why*. The card is therefore two sentences: what the app is showing, and the
 * resolver's own sentence, unedited.
 *
 * ### Why this is not an error
 * A retired ticker is a normal fact about a listed company, not a failure. So the card uses the
 * secondary container rather than the error container, and the copy never uses the word "invalid".
 */
@Composable
private fun ResolvedSymbolCard(
    resolution: SymbolResolution,
    trackedSymbols: Set<String>,
    onOpenStock: (String) -> Unit,
    onAddToTracked: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val summary = resolution.summary ?: return
    val tracked = summary.symbol in trackedSymbols

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag(RESOLVED_CARD_TAG),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MiaSpacing.md),
            verticalArrangement = Arrangement.spacedBy(MiaSpacing.sm),
        ) {
            Text(
                text = RESOLVED_TITLE,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = "You searched for ${resolution.query} · showing ${summary.symbol}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = resolution.explanation,
                style = MaterialTheme.typography.bodySmall,
            )
            resolution.corporateActionDetail?.let { detail ->
                Text(text = detail, style = MaterialTheme.typography.bodySmall)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MiaSpacing.sm),
            ) {
                OutlinedButton(
                    onClick = { onOpenStock(summary.symbol) },
                    modifier = Modifier.testTag(RESOLVED_OPEN_TAG),
                ) {
                    Text(text = "Open ${summary.symbol}")
                }
                if (tracked) {
                    Text(
                        text = TRACKED_LABEL,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                } else {
                    TextButton(onClick = { onAddToTracked(summary.symbol) }) {
                        Text(text = TRACK_ACTION_LABEL)
                    }
                }
            }
        }
    }
}

/**
 * The "I could not match that, here are the closest" state.
 *
 * The near misses follow as ordinary rows, so a reader who wanted one of them taps it and moves on
 * without the screen ever having claimed it found their query.
 */
@Composable
private fun UnresolvedHeader(resolution: SymbolResolution, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MiaSpacing.xs),
    ) {
        Text(
            text = UNRESOLVED_TITLE,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = resolution.explanation,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (resolution.alternatives.isNotEmpty()) {
            Text(
                text = CLOSEST_LABEL,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * One instrument: ticker, company name, series, and the add-to-tracked affordance.
 *
 * ### Why the series code is shown at all
 * plan.md §3.1 keeps only `EQ` and `SM` for pricing, and the master carries `BE`, `ST`, `GB`, `GS`
 * as well. A reader who lands on a book-entry row and then finds no price anywhere in the app needs
 * to know the series is why. The badge therefore renders every code, not only the priceable ones.
 *
 * ### Why the row is not tinted
 * No green/red here. A ticker has no direction; a coloured row would imply one and would fight the
 * `ChangePercentText` colours on the detail screen.
 */
@Composable
private fun SymbolRow(
    summary: SymbolSummary,
    trackedSymbols: Set<String>,
    onOpenStock: (String) -> Unit,
    onAddToTracked: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tracked = summary.symbol in trackedSymbols
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable { onOpenStock(summary.symbol) }
            .padding(vertical = MiaSpacing.sm)
            .testTag(SYMBOL_ROW_TAG_PREFIX + summary.symbol),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MiaSpacing.sm),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = summary.symbol,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = summary.name,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        SeriesBadge(series = summary.series, isPriceable = summary.isPriceable)
        if (tracked) {
            Text(
                text = TRACKED_LABEL,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            TextButton(
                onClick = { onAddToTracked(summary.symbol) },
                modifier = Modifier.testTag(TRACK_BUTTON_TAG_PREFIX + summary.symbol),
            ) {
                Text(text = TRACK_ACTION_LABEL)
            }
        }
    }
}

/**
 * The NSE series code, in a bordered label.
 *
 * The border is the encoding: it distinguishes the badge from the ticker text without a colour, so
 * it survives a washed-out screen and a colour-vision deficiency (the same rule as `SourceBadge`).
 * A non-priceable series is stated in words underneath, because the code alone (`BE`) means nothing
 * to a first-time reader.
 */
@Composable
private fun SeriesBadge(series: String, isPriceable: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.semantics {
            contentDescription = seriesLabel(series, isPriceable)
        },
        horizontalAlignment = Alignment.End,
    ) {
        Text(
            text = series,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clip(MaterialTheme.shapes.extraSmall)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    shape = MaterialTheme.shapes.extraSmall,
                )
                .padding(horizontal = MiaSpacing.sm, vertical = 2.dp),
        )
        if (!isPriceable) {
            Text(
                text = NOT_PRICEABLE_LABEL,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** Spoken form of the badge: the code plus whether the app can price it. */
private fun seriesLabel(series: String, isPriceable: Boolean): String = when {
    isPriceable -> "series $series, priced from the daily bhavcopy"
    else -> "series $series, not priced by this app because NSE publishes no end-of-day row for it"
}

// --------------------------------------------------------------------------- copy tokens

private const val SEARCH_TAG: String = "mia_search"
private const val SEARCH_FIELD_TAG: String = "mia_search_field"
private const val SEARCH_CLEAR_TAG: String = "mia_search_clear"
private const val VOICE_BUTTON_TAG: String = "mia_voice_button"
private const val VOICE_STATUS_TAG: String = "mia_voice_status"
private const val VOICE_DISMISS_TAG: String = "mia_voice_dismiss"
private const val RESOLVED_CARD_TAG: String = "mia_search_resolved"
private const val RESOLVED_OPEN_TAG: String = "mia_search_resolved_open"
private const val TRACK_BUTTON_TAG_PREFIX: String = "mia_search_track_"

private const val SYMBOL_ROW_TAG_PREFIX: String = "mia_search_row_"

private const val SEARCH_FIELD_LABEL: String = "Ticker or company name"
private const val CLEAR_LABEL: String = "Clear"
private const val VOICE_LABEL: String = "Voice"
private const val VOICE_STOP_LABEL: String = "Stop"
private const val VOICE_TRANSCRIBING_LABEL: String = "Transcribing"
private const val VOICE_LISTENING_LABEL: String = "Listening. Say a ticker or a company name."
private const val VOICE_TRANSCRIBING_LINE: String = "Turning what you said into a search."
private const val VOICE_DISMISS_LABEL: String = "Dismiss"
private const val LOADING_BOOK_LABEL: String = "Reading the bundled NSE symbol master"
private const val SEARCH_HEADER_TITLE: String = "Search"
private const val RESOLVED_TITLE: String = "That ticker has changed"
private const val UNRESOLVED_TITLE: String = "No close match for that text"
private const val CLOSEST_LABEL: String = "Closest instruments in the master:"
private const val TRACK_ACTION_LABEL: String = "Track"
private const val TRACKED_LABEL: String = "Tracked"
private const val NOT_PRICEABLE_LABEL: String = "not priced"

private const val HEADER_KEY: String = "search.header"
private const val RESOLVED_KEY: String = "search.resolved"
private const val UNRESOLVED_KEY: String = "search.unresolved"
private const val EMPTY_KEY: String = "search.empty"
private const val FOOTER_KEY: String = "search.footer"