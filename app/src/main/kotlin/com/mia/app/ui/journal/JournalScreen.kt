package com.mia.app.ui.journal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mia.app.domain.compliance.Disclosure
import com.mia.app.domain.journal.ExpectationDirection
import com.mia.app.domain.journal.JournalDraft
import com.mia.app.domain.journal.JournalEntry
import com.mia.app.domain.journal.JournalLedger
import com.mia.app.domain.journal.JournalPolicy
import com.mia.app.domain.journal.JournalResolver
import com.mia.app.domain.journal.PersonalRecord
import com.mia.app.domain.model.HitRate
import com.mia.app.ui.common.DisclaimerFooter
import com.mia.app.ui.common.EmptyState
import com.mia.app.ui.common.ErrorState
import com.mia.app.ui.common.HitRateText
import com.mia.app.ui.common.LoadingState
import com.mia.app.ui.common.SectionHeader
import com.mia.app.ui.common.TimeframeToggle
import com.mia.app.ui.common.formatDate
import com.mia.app.ui.common.shortLabel
import com.mia.app.ui.theme.MiaSpacing
import java.time.LocalDate

/**
 * The thesis journal: what the reader believed about a stock, written before the outcome was known
 * (planv2 §W6, gap E5).
 *
 * ### Section order, and why
 * ```
 *  heading + what this is        the premise before the form: write it down before you know
 *  write an entry                the four fields, each with its own job
 *  your record                  the reader's own tally, under the app's own 30-sample gate
 *  the app's own record          beside it, in different words, as a *different* measurement
 *  what the app records          the sentence that keeps the two apart
 *  entries                      each with its own falsification and its measured outcome
 *  data-source footer           plan.md §3
 * ```
 *
 * ### The four things this screen must never do
 * 1. **Let an entry through without a falsification condition.** The save button is disabled while
 *    `JournalDraft.blockingReason()` is non-null, and the reason is printed on the form — so the
 *    requirement is stated rather than merely enforced. See [JournalPolicy].
 * 2. **Print a personal rate below 30 settled entries.** `PersonalRecord.label()` returns the count
 *    and the distance to the gate and **no percentage at all** below the gate; this file never
 *    formats a rate of its own.
 * 3. **Let the two records blur.** They are separate cards with different wording, different
 *    disclosure text and one explicit sentence stating that neither is evidence about the other.
 * 4. **Grade the reader.** Every resolution sentence describes the price and its measured move. No
 *    outcome is called right, wrong, or vindicated, and none of the three `JournalOutcome` labels
 *    mentions the entry.
 *
 * @param onOpenAccuracy opens the Accuracy tab. Offered as a link rather than duplicated: the app's
 *   own record has its own screen, its own tables and its own explanations, and a summary of it here
 *   would inevitably be a worse version of that screen.
 * @param viewModel obtained with `hiltViewModel()`. This route takes no arguments — the journal is
 *   about every symbol the reader has written about, and filtering by one would hide the coverage
 *   line that makes the tally honest about how few entries it rests on.
 */
@Composable
fun JournalScreen(
    onOpenAccuracy: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: JournalViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag(JOURNAL_TAG),
        contentPadding = PaddingValues(
            start = MiaSpacing.lg,
            end = MiaSpacing.lg,
            top = MiaSpacing.lg,
            bottom = MiaSpacing.xxl,
        ),
        verticalArrangement = Arrangement.spacedBy(MiaSpacing.lg),
    ) {
        item(key = HEADING_KEY) { Heading() }

        state.errorReason?.let { reason ->
            item(key = ERROR_KEY) { ErrorState(reason = reason, onRetry = viewModel::onRetry) }
        }

        if (state.isLoading && state.hasNoEntries) {
            item(key = LOADING_KEY) { LoadingState() }
        }

        item(key = COMPOSER_KEY) {
            ComposerSection(state = state, viewModel = viewModel)
        }

        item(key = RECORD_KEY) { YourRecordCard(state.record) }

        item(key = APP_RECORD_KEY) {
            AppRecordCard(state.appHitRate, onOpenAccuracy = onOpenAccuracy)
        }

        item(key = SEPARATION_KEY) { SeparationNote() }

        if (state.hasNoEntries) {
            item(key = EMPTY_KEY) {
                EmptyState(title = EMPTY_TITLE, body = EMPTY_BODY)
            }
        } else {
            if (state.openEntries.isNotEmpty()) {
                item(key = OPEN_HEADING_KEY) {
                    SectionHeader(
                        title = OPEN_ENTRIES_TITLE,
                        subtitle = OPEN_ENTRIES_SUBTITLE,
                    )
                }
                items(
                    count = state.openEntries.size,
                    key = { index -> state.openEntries[index].id },
                ) { index ->
                    EntryCard(
                        entry = state.openEntries[index],
                        openReason = state.openReasons[state.openEntries[index].id],
                        onDelete = { viewModel.onDelete(state.openEntries[index].id) },
                    )
                }
            }
            if (state.resolvedEntries.isNotEmpty()) {
                item(key = RESOLVED_HEADING_KEY) {
                    SectionHeader(
                        title = RESOLVED_ENTRIES_TITLE,
                        subtitle = RESOLVED_ENTRIES_SUBTITLE,
                    )
                }
                items(
                    count = state.resolvedEntries.size,
                    key = { index -> state.resolvedEntries[index].id },
                ) { index ->
                    EntryCard(
                        entry = state.resolvedEntries[index],
                        openReason = null,
                        onDelete = { viewModel.onDelete(state.resolvedEntries[index].id) },
                    )
                }
            }
        }

        state.actionMessage?.let { message ->
            item(key = ACTION_MESSAGE_KEY) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item(key = FOOTER_KEY) { DisclaimerFooter(text = Disclosure.DATA_SOURCE) }
    }
}

// ------------------------------------------------------------------------------ heading

/**
 * The premise, before the form.
 *
 * It is the one sentence that makes the feature make sense, and it is stated before anything can be
 * typed rather than as a hint under the field: an entry written after the outcome is known is not a
 * record of anything, and the reader should know that before they start rather than after.
 */
@Composable
private fun Heading(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MiaSpacing.xs),
    ) {
        Text(
            text = TITLE,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = HEADING_SUBTITLE,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ------------------------------------------------------------------------------ composer

/**
 * The form.
 *
 * ### Why each field is required and how it says so
 * All four text fields are required, and the reason each one matters is printed under it rather than
 * left for the reader to work out. The falsification field is the one that cannot be left blank, and
 * it carries [JournalPolicy.FALSIFICATION_REQUIRED] as its error text from the first keystroke —
 * not as a red banner after a failed save, because the point is that the entry is **not
 * savable**, not that a save failed.
 *
 * ### The review date is four chips, not a date picker
 * "Two weeks, one month, three months, six months" is every choice a reader of this app actually
 * makes, and a full date picker would be a dialog to dismiss before reaching the field that matters.
 * The chosen date is printed above the chips, so what is stored is never a surprise.
 */
@Composable
private fun ComposerSection(
    state: JournalUiState,
    viewModel: JournalViewModel,
    modifier: Modifier = Modifier,
) {
    val draft = state.draft
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MiaSpacing.sm),
    ) {
        SectionHeader(title = SECTION_WRITE, subtitle = WRITE_SUBTITLE)

        if (draft == null) {
            Button(
                onClick = viewModel::onCompose,
                modifier = Modifier.testTag(COMPOSE_BUTTON_TAG),
            ) {
                Text(text = START_ENTRY)
            }
        } else {
            DraftFields(state = state, draft = draft, viewModel = viewModel)
        }
    }
}

/**
 * The fields themselves, once the form is open.
 *
 * Split out of [ComposerSection] so the "form closed" branch is an `else` rather than an early
 * return from inside a composable's content lambda.
 */
@Composable
private fun DraftFields(
    state: JournalUiState,
    draft: JournalDraft,
    viewModel: JournalViewModel,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MiaSpacing.sm),
    ) {
        OutlinedTextField(
            value = draft.symbol,
            onValueChange = viewModel::onDraftSymbol,
            label = { Text(text = LABEL_SYMBOL) },
            placeholder = { Text(text = PLACEHOLDER_SYMBOL) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(DRAFT_SYMBOL_TAG),
        )

        Text(
            text = LABEL_TIMEFRAME,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TimeframeToggle(selected = draft.timeframe, onSelect = viewModel::onDraftTimeframe)

        OutlinedTextField(
            value = draft.thesis,
            onValueChange = viewModel::onDraftThesis,
            label = { Text(text = LABEL_THESIS) },
            supportingText = { Text(text = HINT_THESIS) },
            minLines = 2,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(DRAFT_THESIS_TAG),
        )

        OutlinedTextField(
            value = draft.expectation,
            onValueChange = viewModel::onDraftExpectation,
            label = { Text(text = LABEL_EXPECTATION) },
            supportingText = { Text(text = HINT_EXPECTATION) },
            minLines = 2,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(DRAFT_EXPECTATION_TAG),
        )

        Text(
            text = LABEL_DIRECTION,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ExpectationDirection.entries.forEach { direction ->
            FilterChip(
                selected = draft.direction == direction,
                onClick = {
                    viewModel.onDraftDirection(if (draft.direction == direction) null else direction)
                },
                label = { Text(text = direction.label) },
                modifier = Modifier.testTag(DIRECTION_TAG_PREFIX + direction.name),
            )
        }
        Text(
            text = HINT_DIRECTION,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = draft.falsification,
            onValueChange = viewModel::onDraftFalsification,
            label = { Text(text = LABEL_FALSIFICATION) },
            placeholder = { Text(text = JournalPolicy.FALSIFICATION_HINT) },
            isError = draft.falsification.isBlank(),
            supportingText = {
                Text(
                    text = if (draft.falsification.isBlank()) {
                        JournalPolicy.FALSIFICATION_REQUIRED
                    } else {
                        JournalPolicy.CONDITION_UNIT_NOTE
                    },
                )
            },
            minLines = 2,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(DRAFT_FALSIFICATION_TAG),
        )

        Text(
            text = if (draft.reviewDate == null) {
                LABEL_REVIEW_DATE
            } else {
                "$LABEL_REVIEW_DATE — ${formatDate(draft.reviewDate)}"
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        REVIEW_CHIPS.forEach { option ->
            FilterChip(
                selected = draft.reviewDate == option.dateFrom(state.today),
                onClick = { viewModel.onDraftReviewDate(option.dateFrom(state.today)) },
                label = { Text(text = option.label) },
            )
        }

        state.blockingReason?.let { reason ->
            Text(
                text = reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag(BLOCKING_REASON_TAG),
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(MiaSpacing.sm),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Button(
                onClick = viewModel::onSave,
                // The falsification requirement is enforced here rather than by catching an
                // exception in the ViewModel, so the reader is told *before* trying.
                enabled = draft.isComplete && !state.isLoading,
                modifier = Modifier.testTag(SAVE_ENTRY_TAG),
            ) {
                Text(text = SAVE_ENTRY)
            }
            TextButton(onClick = viewModel::onCancelCompose) { Text(text = CANCEL) }
        }
    }
}

/**
 * A review-date choice.
 *
 * @property months how far ahead this chip puts the review date, in whole months.
 */
private data class ReviewOption(val label: String, val months: Long) {
    fun dateFrom(today: LocalDate): LocalDate = today.plusMonths(months)
}

/**
 * Four horizons, chosen because they are the four a reader actually uses.
 *
 * Two weeks is the "check whether the move I expected has started" answer, and six months is the
 * "was my thesis about the business right" answer — the same entry can legitimately want either, and
 * the difference is the difference between a price claim and a business claim, which is why the
 * thesis and the expectation are separate fields in the first place.
 */
private val REVIEW_CHIPS: List<ReviewOption> = listOf(
    ReviewOption("In 2 weeks", 2),
    ReviewOption("In 1 month", 1),
    ReviewOption("In 3 months", 3),
    ReviewOption("In 6 months", 6),
)

// -------------------------------------------------------------------------- your record

/**
 * The reader's own tally.
 *
 * ### Why this card is worded the way it is
 * It does not say "hit rate", because `Disclosure.HIT_RATE` defines a hit as a pattern's measured-move
 * level being reached and this is a different measurement entirely — reusing the app's vocabulary
 * would make the two cards read as the same kind of number. What it does share with the app's card
 * is the **gate**: [PersonalRecord.minimumSamples] is
 * `MatchScorePolicy.MIN_HIT_RATE_SAMPLES`, the same 30, and below it [PersonalRecord.label] prints
 * the count and no percentage at all.
 *
 * The counts underneath are shown whether or not the rate is, because they are the substance of what
 * is being measured: 7 settled entries is not a small percentage, it is a small number.
 */
@Composable
private fun YourRecordCard(record: PersonalRecord, modifier: Modifier = Modifier) {
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
            verticalArrangement = Arrangement.spacedBy(MiaSpacing.sm),
        ) {
            Text(
                text = YOUR_RECORD_TITLE,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = YOUR_RECORD_SUBTITLE,
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                text = record.label(),
                style = if (record.isReportable) {
                    MaterialTheme.typography.titleSmall
                } else {
                    MaterialTheme.typography.bodyMedium
                },
                color = if (record.isReportable) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.testTag(YOUR_RECORD_LABEL_TAG),
            )
            if (record.isReportable) {
                Text(text = record.withheldNote(), style = MaterialTheme.typography.bodySmall)
            }
            Text(
                text = recordCountsLine(record),
                style = MaterialTheme.typography.bodySmall,
            )
            if (record.unscored > 0) {
                Text(text = JournalLedger.UNSCORED_NOTE, style = MaterialTheme.typography.bodySmall)
            }
            if (!record.isReportable) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(text = JournalLedger.WITHHELD_NOTE, style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(text = JournalLedger.NOT_A_RETURN_NOTE, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** The counts, always shown: `12 entries · 5 settled · 3 agreed · 2 waiting for review · 5 open`. */
private fun recordCountsLine(record: PersonalRecord): String =
    "${record.totalEntries} written · ${record.settled} settled · ${record.agreed} agreed · " +
        "${record.awaitingReview} past review date · ${record.open} not yet due"

// ----------------------------------------------------------------------- the app's record

/**
 * The app's own record, beside the reader's and deliberately not like it.
 *
 * Rendered through [HitRateText], so the wording, the `n=` and the 30-sample gate are the exact
 * code that guards the Accuracy tab — the two cards cannot drift apart because there is only one
 * implementation. What differs is everything else: the heading says whose record it is, and the
 * subheading says what it counts, because a reader who saw two percentages and no difference
 * between them would be right to wonder which one is about them.
 */
@Composable
private fun AppRecordCard(
    hitRate: HitRate,
    onOpenAccuracy: () -> Unit,
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
            verticalArrangement = Arrangement.spacedBy(MiaSpacing.sm),
        ) {
            Text(
                text = APP_RECORD_TITLE,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(text = APP_RECORD_SUBTITLE, style = MaterialTheme.typography.labelMedium)
            HitRateText(hitRate = hitRate, showDisclosure = true)
            OutlinedButton(
                onClick = onOpenAccuracy,
                modifier = Modifier.testTag(OPEN_ACCURACY_TAG),
            ) {
                Text(text = OPEN_ACCURACY)
            }
        }
    }
}

/**
 * The sentence that keeps the two records apart.
 *
 * It is rendered between the cards and the entries rather than inside either one, because the thing
 * it prevents is a *comparison*, and a note attached to one side invites exactly that. A reader who
 * sees "your 8 of 12" and "the app's 34% of 875" and no statement about their relationship will
 * make one up — usually that the app's number is evidence about theirs.
 */
@Composable
private fun SeparationNote(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MiaSpacing.md),
            verticalArrangement = Arrangement.spacedBy(MiaSpacing.xs),
        ) {
            Text(
                text = SEPARATION_HEADING,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(text = SEPARATION_BODY, style = MaterialTheme.typography.bodySmall)
        }
    }
}

// -------------------------------------------------------------------------------- entries

/**
 * One entry, in the reader's own words, with its own falsification shown back.
 *
 * ### Why the falsification is printed on the card
 * It is the only field that makes the entry a testable claim, and a claim the reader cannot see is a
 * claim they cannot check themselves — which defeats the point of writing it down. Printing it also
 * makes the *absence* of a resolved outcome legible: an entry with a condition on screen and no
 * measured move beside it is visibly incomplete, rather than quietly blank.
 *
 * ### What the outcome line says, and what it must never say
 * [JournalEntry.resolutionText] is built in `:domain` and describes the price and the two sessions
 * the move was measured between. This card adds only [JournalResolver.resolutionNote], which names
 * the flat band and the two closes. No word here grades the entry.
 */
@Composable
private fun EntryCard(
    entry: JournalEntry,
    openReason: String?,
    onDelete: () -> Unit,
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
            Text(
                text = "${entry.symbol} · ${entry.timeframe.shortLabel()}",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Written ${formatDate(entry.createdAt)} · review on " +
                    formatDate(entry.reviewDate),
                style = MaterialTheme.typography.labelSmall,
            )
            LabelledField(label = LABEL_THESIS, body = entry.thesis)
            LabelledField(label = LABEL_EXPECTATION, body = entry.expectation)
            LabelledField(label = LABEL_DIRECTION, body = entry.expectationDirection.label)
            LabelledField(label = LABEL_FALSIFICATION, body = entry.falsification)

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            entry.resolutionText()?.let { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.testTag(RESOLUTION_TAG_PREFIX + entry.id),
                )
                val note = JournalResolver.resolutionNote(entry)
                if (note.isNotBlank()) Text(text = note, style = MaterialTheme.typography.labelSmall)
            } ?: openReason?.let { reason ->
                Text(
                    text = "Still open — $reason",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag(OPEN_REASON_TAG_PREFIX + entry.id),
                )
            } ?: Text(
                text = NOT_DUE_YET.format(formatDate(entry.reviewDate)),
                style = MaterialTheme.typography.bodySmall,
            )

            TextButton(
                onClick = onDelete,
                modifier = Modifier.testTag(DELETE_ENTRY_TAG_PREFIX + entry.id),
            ) {
                Text(text = DELETE_ENTRY)
            }
        }
    }
}

/** One labelled line of the reader's own words. */
@Composable
private fun LabelledField(label: String, body: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MiaSpacing.xs),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(text = body, style = MaterialTheme.typography.bodyMedium)
    }
}

// ------------------------------------------------------------------------------ the copy

private const val TITLE = "Thesis journal"
private const val HEADING_SUBTITLE =
    "Write down what you believe about a stock before you know how it turns out, and check " +
        "yourself afterwards. The app records what the price did; it does not tell you what to " +
        "think, and it never grades what you wrote."

private const val SECTION_WRITE = "Write an entry"
private const val WRITE_SUBTITLE =
    "Four fields, because they answer four different questions. The one that cannot be left blank " +
        "is the condition that would prove you wrong."
private const val START_ENTRY = "Write an entry"
private const val CANCEL = "Cancel"
private const val SAVE_ENTRY = "Save entry"

private const val LABEL_SYMBOL = "NSE symbol"
private const val PLACEHOLDER_SYMBOL = "RELIANCE"
private const val LABEL_TIMEFRAME = "Which bars this entry is about"
private const val LABEL_THESIS = "What I believe about the company"
private const val HINT_THESIS =
    "The business claim. What has to be true for this company to be what you think it is."
private const val LABEL_EXPECTATION = "What I expect price to do"
private const val HINT_EXPECTATION =
    "The price claim, in your words. The direction below is what the tally counts."
private const val LABEL_DIRECTION = "The direction I expect"
private const val HINT_DIRECTION =
    "Recorded separately so a tally is possible without the app interpreting your sentence. " +
        "'No particular direction' is allowed and is left out of the tally, and counted."
private const val LABEL_FALSIFICATION = "What would prove this wrong"
private const val LABEL_REVIEW_DATE = "Review on"

private const val EMPTY_TITLE = "No entries yet"
private const val EMPTY_BODY =
    "An entry is worth writing before the outcome is known — afterwards it is a memory, and a " +
        "memory is not evidence. Nothing has been written on this device yet."

private const val OPEN_ENTRIES_TITLE = "Still open"
private const val OPEN_ENTRIES_SUBTITLE =
    "Past their review date, or not yet due. Each one says which of the app's gates is still shut."
private const val RESOLVED_ENTRIES_TITLE = "Resolved"
private const val RESOLVED_ENTRIES_SUBTITLE =
    "Measured from stored closes, once, and never revised afterwards."

private const val DELETE_ENTRY = "Delete this entry"

private const val NOT_DUE_YET = "Not due until %s. Nothing has been measured against it yet."

private const val YOUR_RECORD_TITLE = "Your record"
private const val YOUR_RECORD_SUBTITLE =
    "Counted from the entries above, and only from them. No rate is printed below " +
        "${JournalLedger.MINIMUM_SAMPLES} settled entries."

private const val APP_RECORD_TITLE = "The app's record"
private const val APP_RECORD_SUBTITLE =
    "The engine's own measurement: how often a pattern's measured-move level was reached on " +
        "this device. It is not about you and it is not about your entries."
private const val OPEN_ACCURACY = "Open the full track record"

private const val SEPARATION_HEADING = "These two numbers are not comparable"
private const val SEPARATION_BODY =
    "Yours counts whether the direction you wrote down is the direction the price then moved. " +
        "The app's counts whether a pattern's level was reached. They are different measurements " +
        "over different things, so neither one is evidence about the other, and neither one makes " +
        "the other look better or worse."

// Test tags.
private const val JOURNAL_TAG = "mia_journal"
private const val COMPOSE_BUTTON_TAG = "mia_journal_compose"
private const val SAVE_ENTRY_TAG = "mia_journal_save"
private const val DRAFT_SYMBOL_TAG = "mia_journal_draft_symbol"
private const val DRAFT_THESIS_TAG = "mia_journal_draft_thesis"
private const val DRAFT_EXPECTATION_TAG = "mia_journal_draft_expectation"
private const val DRAFT_FALSIFICATION_TAG = "mia_journal_draft_falsification"
private const val BLOCKING_REASON_TAG = "mia_journal_blocking_reason"
private const val DIRECTION_TAG_PREFIX = "mia_journal_direction_"
private const val YOUR_RECORD_LABEL_TAG = "mia_journal_your_record"
private const val OPEN_ACCURACY_TAG = "mia_journal_open_accuracy"
private const val RESOLUTION_TAG_PREFIX = "mia_journal_resolution_"
private const val OPEN_REASON_TAG_PREFIX = "mia_journal_open_reason_"
private const val DELETE_ENTRY_TAG_PREFIX = "mia_journal_delete_"

// Lazy-list keys.
private const val HEADING_KEY = "journal.heading"
private const val ERROR_KEY = "journal.error"
private const val LOADING_KEY = "journal.loading"
private const val COMPOSER_KEY = "journal.composer"
private const val RECORD_KEY = "journal.record"
private const val APP_RECORD_KEY = "journal.app_record"
private const val SEPARATION_KEY = "journal.separation"
private const val EMPTY_KEY = "journal.empty"
private const val OPEN_HEADING_KEY = "journal.open_heading"
private const val RESOLVED_HEADING_KEY = "journal.resolved_heading"
private const val ACTION_MESSAGE_KEY = "journal.action_message"
private const val FOOTER_KEY = "journal.footer"