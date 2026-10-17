package com.mia.app.ui.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mia.app.domain.compliance.Disclosure
import com.mia.app.domain.education.Curriculum
import com.mia.app.ui.common.DisclaimerFooter
import com.mia.app.ui.common.SectionHeader
import com.mia.app.ui.common.formatDate
import com.mia.app.ui.share.ShareBriefButton
import com.mia.app.ui.theme.MiaSpacing
import com.mia.app.ui.theme.glass.GlassMode
import com.mia.app.ui.theme.glass.GlassModeController
import com.mia.app.ui.theme.glass.GlassModeStore
import com.mia.app.ui.theme.glass.SharedPreferencesGlassModeStorage
import com.mia.app.work.RefreshRunState
import com.mia.app.work.RefreshStatus
import com.mia.app.work.RefreshTask

/**
 * Settings (plan.md §1.4 rule 4, §3, §10; phase P11's share entry point).
 *
 * ### Section order, and why
 * ```
 *  app name + version        what you are looking at
 *  the SEBI disclaimer       verbatim — plan.md §1.4 rule 4. First, and unmissable.
 *  what each number means    score / geometry / hit rate, verbatim from Disclosure
 *  data coverage             what the app can measure, and — the part that matters — what it cannot
 *  notifications             the one permission, requested here and nowhere else
 *  glass effects             how much blur is drawn, and the device conditions behind the answer
 *  voice search              what it does, what it needs, and that it guesses
 *  background refresh        plan.md §10's last success / last failure, plus "refresh now"
 *  clear cache               with its consequences stated before it is pressed
 *  share brief as image      P11b's entry point
 * ```
 *
 * ### The permission rule
 * `POST_NOTIFICATIONS` is requested **here** and nowhere else. plan.md's manifest comment is explicit
 * that the user must be able to read a chart without being asked for notifications, and that the
 * alert surface is the only consumer. A settings row the user visits deliberately is the one place a
 * permission prompt is not a surprise.
 *
 * ### Why the disclosures are rendered verbatim and repeatedly
 * [Disclosure.SETTINGS] is the SEBI statement and plan.md §1.4 rule 4 requires it in Settings, so it
 * is the first thing on the screen and is not summarised, reworded or truncated. The other three
 * appear in their own section with a heading each, because a reader who wants to know what the score
 * means should not have to scroll past the disclaimer to find out.
 *
 * @param viewModel obtained with `hiltViewModel()`. This route takes no arguments.
 */
@Composable
fun SettingsScreen(
    onOpenEducation: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val versionName = rememberAppVersion(context)

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag(SETTINGS_TAG),
        contentPadding = PaddingValues(
            start = MiaSpacing.lg,
            end = MiaSpacing.lg,
            top = MiaSpacing.lg,
            bottom = MiaSpacing.xxl,
        ),
        verticalArrangement = Arrangement.spacedBy(MiaSpacing.xl),
    ) {
        item(key = ABOUT_KEY) {
            SectionHeader(
                title = Disclosure.APP_NAME,
                subtitle = "$VERSION_LABEL $versionName",
            )
        }

        // plan.md §1.4 rule 4. Verbatim, first, and not collapsed.
        item(key = DISCLAIMER_KEY) {
            Card(
                modifier = Modifier.fillMaxWidth(),
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
                        text = SECTION_DISCLAIMER,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        text = Disclosure.SETTINGS,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        item(key = MEANING_KEY) { MeaningSection(onOpenEducation = onOpenEducation) }

        item(key = COVERAGE_KEY) { CoverageSection(state, viewModel::onRetry) }

        item(key = NOTIFICATIONS_KEY) { NotificationsSection() }

        item(key = GLASS_KEY) { GlassSection() }

        item(key = SPEECH_KEY) { SpeechSection(state) }

        item(key = REFRESH_KEY) {
            RefreshSection(
                statuses = state.refreshStatuses,
                isActionRunning = state.isActionRunning,
                onRefreshNow = viewModel::onRefreshNow,
            )
        }

        item(key = CACHE_KEY) {
            CacheSection(
                isActionRunning = state.isActionRunning,
                onClearCache = viewModel::onClearCache,
            )
        }

        item(key = SHARE_KEY) { ShareSection(state, viewModel) }

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

// ------------------------------------------------------------------------------ meaning

/**
 * What each number on every other screen means.
 *
 * The three statements are the app's own, rendered verbatim and each under its own heading, because
 * they answer three different questions a reader may have about the same screen: what is `72 of
 * 100`, what is the level on my chart, and what is the hit rate under the pattern.
 */
@Composable
private fun MeaningSection(onOpenEducation: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MiaSpacing.sm),
    ) {
        SectionHeader(title = SECTION_MEANING, subtitle = MEANING_SUBTITLE)
        MeaningCard(heading = MEANING_SCORE_HEADING, body = Disclosure.SCORE_MEANING)
        MeaningCard(heading = MEANING_GEOMETRY_HEADING, body = Disclosure.MEASURED_MOVE)
        MeaningCard(heading = MEANING_HIT_RATE_HEADING, body = Disclosure.HIT_RATE)
        MeaningCard(heading = MEANING_SOURCE_HEADING, body = Disclosure.DATA_SOURCE)

        // The route into the curriculum, and the reason it lives here rather than behind a
        // toolbar icon nobody looks for. The four cards above define the app's vocabulary; this
        // section is where a reader goes to learn the ideas behind those words, so the link
        // belongs with the definitions rather than in a nav drawer.
        EducationEntryCard(onOpen = onOpenEducation)
    }
}

/**
 * The way into the three-level curriculum.
 *
 * States its size rather than just its name. A reader deciding whether this is worth opening cannot
 * tell "Education" from a glossary of six terms, and "19 modules · 108 lessons" is the difference
 * between a link they click and a link they skip.
 */
@Composable
private fun EducationEntryCard(onOpen: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .testTag(EDUCATION_ENTRY_TAG)
            .semantics {
                contentDescription = "Education, ${Curriculum.modules.size} modules, open the curriculum"
            },
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
                text = EDUCATION_HEADING,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = EDUCATION_SUBTITLE,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun MeaningCard(heading: String, body: String, modifier: Modifier = Modifier) {
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
                text = heading,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(text = body, style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ---------------------------------------------------------------------------- coverage

/**
 * What the app can measure on this device, and what it cannot.
 *
 * The counts are the first half; the **declared gaps** are the second, and are the part users never
 * see anywhere else. A gap is rendered as a sentence with its own stated consequence, because a gap
 * without a consequence is a disclaimer, and disclaimers get scrolled past.
 *
 * A coverage read that failed shows its reason **inside this card** and leaves the rest of the screen
 * intact — the SEBI disclaimer must render whether or not the database is reachable.
 */
@Composable
private fun CoverageSection(state: SettingsUiState, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MiaSpacing.sm),
    ) {
        SectionHeader(title = SECTION_COVERAGE, subtitle = COVERAGE_SUBTITLE)

        state.coverageErrorReason?.let { reason ->
            Text(
                text = "$COVERAGE_UNREADABLE $reason",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            OutlinedButton(onClick = onRetry) { Text(text = RETRY_LABEL) }
        }

        val coverage = state.coverage
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MiaSpacing.sm),
        ) {
            CoverageTile(
                label = "tracked",
                value = coverage.trackedSymbolCount.toString(),
                modifier = Modifier.weight(1f),
            )
            CoverageTile(
                label = "with daily bars",
                value = coverage.symbolsWithDailyBars.toString(),
                modifier = Modifier.weight(1f),
            )
            CoverageTile(
                label = "with outcomes",
                value = coverage.symbolsWithOutcomes.toString(),
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            text = coverageDateLine(coverage),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (coverage.hasAnythingToReport) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                text = COVERAGE_GAPS_HEADING,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            coverage.declaredGaps.forEach { gap -> GapRow(gap) }
            coverage.sourceNotes.forEach { note ->
                Text(
                    text = "$BULLET $note",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** `Newest stored bar: 02 Oct 2026 · newest fundamentals: —`. Never blank, never invented. */
private fun coverageDateLine(coverage: SettingsCoverage): String {
    val bars = coverage.latestBarDate?.let { formatDate(it) } ?: "—"
    val fundamentals = coverage.latestFundamentalsDate?.let { formatDate(it) } ?: "—"
    return "Newest stored bar: $bars · newest fundamentals: $fundamentals"
}

/** One gap: the area, the gap, and what the reader therefore cannot conclude. */
@Composable
private fun GapRow(gap: CoverageGap, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = MiaSpacing.xs),
        verticalArrangement = Arrangement.spacedBy(MiaSpacing.xs),
    ) {
        Text(
            text = gap.area,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = gap.gap,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "So: ${gap.consequence}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A count over a short label. Three across; a fourth would truncate on a 360 dp screen. */
@Composable
private fun CoverageTile(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MiaSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(MiaSpacing.xs),
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(text = label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

// ----------------------------------------------------------------------- notifications

/**
 * The one runtime permission this app requests, and the only place it requests it.
 *
 * ### Why here and not in a tab
 * plan.md's manifest comment is explicit: the user must be able to read a chart without being asked
 * for notifications. A settings row the user navigated to deliberately is the one context in which a
 * permission prompt is not an ambush.
 *
 * ### Why the button disappears below API 33
 * `POST_NOTIFICATIONS` is an install-time permission below API 33 and cannot be requested at runtime,
 * so a button there would either crash or silently do nothing. Below the threshold the section says
 * the permission is granted by installation.
 */
@Composable
private fun NotificationsSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(notificationPermissionGranted(context)) }
    var wasAsked by rememberSaveable { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { result ->
        granted = result
        wasAsked = true
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MiaSpacing.sm),
    ) {
        SectionHeader(title = SECTION_NOTIFICATIONS, subtitle = NOTIFICATIONS_SUBTITLE)
        if (granted) {
            Text(
                text = NOTIFICATIONS_GRANTED,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        } else {
            Text(
                text = if (wasAsked) NOTIFICATIONS_DENIED else NOTIFICATIONS_NOT_GRANTED,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Button(
                    onClick = { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                    modifier = Modifier.testTag(NOTIFICATION_BUTTON_TAG),
                ) {
                    Text(text = NOTIFICATIONS_ALLOW)
                }
            } else {
                Text(
                    text = NOTIFICATIONS_PRE_33,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = NOTIFICATIONS_ONLY_FOR,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** `POST_NOTIFICATIONS` state. Below API 33 the platform grants it at install, so this reads true. */
private fun notificationPermissionGranted(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    } else {
        true
    }

// ------------------------------------------------------------------------------ glass mode

/**
 * How much glass the app draws (planv2 W7).
 *
 * ### Why this is here and not in a theme switch
 * It changes no number, no disclosure and nothing the reader is asked to act on — [GlassMode.FULL]
 * and [GlassMode.OFF] render the same text with the same contrast, differing only in whether the
 * pane behind the text is blurred. It is the "reduce motion" of the glass design, which is why it
 * defaults to letting the device decide instead of asking the reader to decide at first launch.
 *
 * ### Why the resolved mode is shown as its own line
 * `Auto` is not one thing: it resolves to Full on a healthy device and to Off on a device the
 * platform reports as low on memory, or while battery saver is on. A row that silently ignored the
 * reader's tap would look broken, so the resolution is stated in words under the chips.
 *
 * ### Why it is written straight through here
 * [GlassModeController] persists to `SharedPreferences` and holds one snapshot state, so this
 * section is self-contained: no ViewModel, no data source, no Hilt binding. It follows the pattern
 * `NotificationsSection` already uses for a setting that is a platform fact rather than app state.
 */
@Composable
private fun GlassSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    // Re-read on entry, so the row reports the device's conditions as they are *now*: battery saver
    // can be switched on from the notification shade while the app is in the background.
    DisposableEffect(context) {
        GlassModeController.refresh(context)
        onDispose { }
    }

    // Local state, not the controller's: the controller holds the *resolved* mode, which under
    // Auto is not the reader's choice. The chip has to show what was chosen, or tapping Auto on a
    // low-memory device appears to do nothing.
    var stored by remember(context) {
        mutableStateOf(GlassModeStore(SharedPreferencesGlassModeStorage(context)).stored())
    }
    val effective = GlassModeController.effective.value

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MiaSpacing.sm),
    ) {
        SectionHeader(title = SECTION_GLASS, subtitle = GLASS_SUBTITLE)

        // Two rows of two rather than four across: 360 dp is the narrowest screen this app runs on,
        // and four chips with their check icons and padding do not fit in it without truncating
        // "Reduced".
        GlassMode.entries.chunked(2).forEach { pair ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MiaSpacing.sm),
            ) {
                pair.forEach { mode ->
                    FilterChip(
                        selected = stored == mode,
                        onClick = {
                            stored = mode
                            GlassModeController.select(context, mode)
                        },
                        label = { Text(text = glassModeLabel(mode)) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag(GLASS_TAG_PREFIX + mode.name.lowercase()),
                    )
                }
                // Keeps the last odd chip at half width instead of full width, so a one-item row
                // cannot render wider than the two-item row above it.
                if (pair.size == 1) Spacer(modifier = Modifier.weight(1f))
            }
        }

        Text(
            text = glassModeSummary(effective),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = GLASS_CHANGES_NOTHING_ELSE,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** `Auto` / `Full` / `Reduced` / `Off`. Short enough for a chip on a 360 dp screen. */
private fun glassModeLabel(mode: GlassMode): String = when (mode) {
    GlassMode.AUTO -> "Auto"
    GlassMode.FULL -> "Full"
    GlassMode.REDUCED -> "Reduced"
    GlassMode.OFF -> "Off"
}

/**
 * What the app is drawing right now.
 *
 * Stated in words because `Auto` resolves to two different answers depending on the device, and a
 * chip that appears not to respond is worse than no chip at all.
 */
private fun glassModeSummary(effective: GlassMode): String = when (effective) {
    GlassMode.FULL -> GLASS_NOW_FULL
    GlassMode.REDUCED -> GLASS_NOW_REDUCED
    GlassMode.OFF -> GLASS_NOW_OFF
    // Unreachable in practice — `GlassModeController` resolves before it hands anything out — but a
    // blank line under a preference would read as a rendering fault, so it names the fallback.
    GlassMode.AUTO -> GLASS_NOW_AUTO
}

// ------------------------------------------------------------------------------- speech

/**
 * What voice search needs and what it will do.
 *
 * Two things a reader must be told before they grant a microphone: **that it guesses**, and **that it
 * asks the device's own speech service to work offline without being able to promise it will**. The
 * second is deliberately not a privacy promise, because the app does not control whether an offline
 * model is installed.
 */
@Composable
private fun SpeechSection(state: SettingsUiState, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MiaSpacing.sm),
    ) {
        SectionHeader(title = SECTION_SPEECH, subtitle = SPEECH_SUBTITLE)
        Text(
            text = state.speech.description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = SPEECH_NEVER_CERTAIN,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// -------------------------------------------------------------------------- background

/**
 * plan.md §10: the background refresh's last success and last failure, with a "refresh now".
 *
 * Every status line is a statement about what is **known**. [RefreshStatus.headline] is never "up to
 * date": a job that has never run is unknown, and this screen must not imply otherwise. A job that
 * last failed says so in the error colour with the recorded reason attached, because a green
 * "refreshed" line over three days-old data is the failure plan.md §10 exists to prevent.
 */
@Composable
private fun RefreshSection(
    statuses: Map<RefreshTask, RefreshStatus>,
    isActionRunning: Boolean,
    onRefreshNow: (RefreshTask) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MiaSpacing.md),
    ) {
        SectionHeader(title = SECTION_REFRESH, subtitle = REFRESH_SUBTITLE)
        RefreshTask.entries.forEach { task ->
            RefreshTaskCard(
                task = task,
                status = statuses[task],
                isActionRunning = isActionRunning,
                onRefreshNow = { onRefreshNow(task) },
            )
        }
    }
}

@Composable
private fun RefreshTaskCard(
    task: RefreshTask,
    status: RefreshStatus?,
    isActionRunning: Boolean,
    onRefreshNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val headline = status?.headline ?: RefreshStatus.neverRun(task).headline
    val detail = status?.detail ?: UNKNOWN_STATUS
    val isFailure = status?.state == RefreshRunState.FAILED

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isFailure) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            contentColor = if (isFailure) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MiaSpacing.md),
            verticalArrangement = Arrangement.spacedBy(MiaSpacing.xs),
        ) {
            Text(
                text = task.label,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(text = task.description, style = MaterialTheme.typography.bodySmall)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                text = headline,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isFailure) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.onSurface,
            )
            Text(text = detail, style = MaterialTheme.typography.labelSmall)

            // The pattern scan is derived work: it reads bars the price refresh stores, so a button
            // on it could only re-read the existing cache. It keeps its status card — a reader needs
            // to see when the last scan ran — but is not given an action that cannot do anything.
            if (task != RefreshTask.PATTERN_SCAN) {
                OutlinedButton(
                    onClick = onRefreshNow,
                    enabled = !isActionRunning && status?.isBusy != true,
                    modifier = Modifier.testTag("refresh_now_${task.name}"),
                ) {
                    Text(
                        text = if (task == RefreshTask.HISTORY_BACKFILL) REFRESH_HISTORY else REFRESH_NOW,
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------- cache

/**
 * Clear the cached market data and history.
 *
 * The consequences are stated **above** the button: the tracked-symbol list is kept, the stored bars
 * and fundamentals are removed, and the app will fetch them again on the next refresh. A destructive
 * button whose effect is discovered afterwards is the kind of thing a trust-focused app should not
 * ship, and there is no confirmation dialog because nothing irreversible is removed — but the reader
 * is told first anyway.
 */
@Composable
private fun CacheSection(
    isActionRunning: Boolean,
    onClearCache: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MiaSpacing.sm),
    ) {
        SectionHeader(title = SECTION_CACHE, subtitle = CACHE_SUBTITLE)
        Text(text = CACHE_WHATS_KEPT, style = MaterialTheme.typography.bodySmall)
        Text(text = CACHE_WHATS_GONE, style = MaterialTheme.typography.bodySmall)
        OutlinedButton(
            onClick = onClearCache,
            enabled = !isActionRunning,
            modifier = Modifier.testTag(CLEAR_CACHE_TAG),
        ) {
            Text(text = CLEAR_CACHE)
        }
    }
}

// ------------------------------------------------------------------------------- share

/**
 * P11b's entry point: build a brief for a symbol and share it as an image.
 *
 * A symbol field rather than a stock picker, because Settings takes no route argument and a picker
 * would pull the whole watchlist into a screen whose job is disclosures and coverage. The field is
 * upper-cased and trimmed on every keystroke, so the ticker that reaches the data source is in the
 * form the symbol master uses.
 *
 * The share button is disabled until a brief exists, and the reason it could not be built is shown
 * above it rather than being swallowed — an image of an empty card is worse than no image.
 */
@Composable
private fun ShareSection(state: SettingsUiState, viewModel: SettingsViewModel, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MiaSpacing.sm),
    ) {
        SectionHeader(title = SECTION_SHARE, subtitle = SHARE_SUBTITLE)
        OutlinedTextField(
            value = state.shareSymbol,
            onValueChange = viewModel::onShareSymbolChanged,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(text = SHARE_SYMBOL_LABEL) },
            placeholder = { Text(text = SHARE_SYMBOL_PLACEHOLDER) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { viewModel.onShareBriefRequested() }),
        )
        Button(
            onClick = viewModel::onShareBriefRequested,
            enabled = !state.isBuildingBrief && state.shareSymbol.isNotBlank(),
            modifier = Modifier.testTag(BUILD_BRIEF_TAG),
        ) {
            Text(text = if (state.isBuildingBrief) BUILDING_BRIEF else BUILD_BRIEF)
        }
        state.shareUnavailableReason?.let { reason ->
            Text(
                text = reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ShareBriefButton(
            card = state.shareCard,
            modifier = Modifier.testTag(SHARE_ENTRY_TAG),
            label = SHARE_ACTION_LABEL,
        )
        Text(
            text = SHARE_SAFETY_NOTE,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ------------------------------------------------------------------------------- helpers

/**
 * The app's version name, read once.
 *
 * Read through [PackageManager] rather than `BuildConfig`, which this module deliberately does not
 * generate (`buildConfig = false` in `app/build.gradle.kts`) — the version the user sees should be the
 * version the package manager installed, not a constant that can drift from it.
 */
@Composable
@Suppress("DEPRECATION")
private fun rememberAppVersion(context: Context): String = remember(context) {
    runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: VERSION_UNKNOWN
}

// ------------------------------------------------------------------------------ the copy

private const val VERSION_LABEL = "Version"
private const val VERSION_UNKNOWN = "unknown"

private const val SECTION_DISCLAIMER = "What this app is"
private const val SECTION_MEANING = "What each number means"
private const val MEANING_SUBTITLE =
    "The same four statements appear wherever those numbers are shown. They are here so you can read " +
        "them without opening a chart."
private const val MEANING_SCORE_HEADING = "Match score (0–100)"
private const val MEANING_GEOMETRY_HEADING = "A level drawn on the chart"
private const val MEANING_HIT_RATE_HEADING = "Hit rate"
private const val MEANING_SOURCE_HEADING = "Where the data comes from"

/** Heading and subtitle for the link into the curriculum. See [EducationEntryCard]. */
private const val EDUCATION_HEADING = "Education"

private const val EDUCATION_SUBTITLE =
    "Learn the ideas behind these terms, from the first look at a stock through to how the "
        + "app measures itself."

/** Test tag for the curriculum link, so its presence is assertable rather than assumed. */
private const val EDUCATION_ENTRY_TAG = "education-entry"

private const val SECTION_COVERAGE = "Data coverage"
private const val COVERAGE_SUBTITLE =
    "What this device has stored, and — below that — what this app cannot measure at all. Both are " +
        "listed, because a reader who knows a gap can use the tool around it."
private const val COVERAGE_UNREADABLE = "The stored-data counts could not be read:"
private const val COVERAGE_GAPS_HEADING = "What this app does not measure"

private const val SECTION_NOTIFICATIONS = "Breakout alerts"
private const val NOTIFICATIONS_SUBTITLE =
    "The only notification this app sends is a breakout condition being met on a stored end-of-day " +
        "bar. Nothing else."
private const val NOTIFICATIONS_GRANTED =
    "Notifications are on. A breakout condition being met produces one alert."
private const val NOTIFICATIONS_NOT_GRANTED =
    "Notifications are off. A breakout condition being met produces no alert, and nothing else " +
        "changes."
private const val NOTIFICATIONS_DENIED =
    "The permission was declined. Android will not ask again, so this can only be changed in system " +
        "settings. A breakout condition being met produces no alert."
private const val NOTIFICATIONS_ALLOW = "Allow notifications"
private const val NOTIFICATIONS_PRE_33 =
    "On this Android version the permission is granted when the app is installed."
private const val NOTIFICATIONS_ONLY_FOR =
    "The permission is used for that alert and nothing else. No other app feature touches it."

private const val SECTION_GLASS = "Glass effects"
private const val GLASS_SUBTITLE =
    "Blurred panels behind the app's cards. How much of it is drawn depends on this device, and on " +
        "nothing else."
private const val GLASS_NOW_FULL = "Right now the app is drawing full glass effects."
private const val GLASS_NOW_REDUCED = "Right now the app is drawing reduced glass effects."
private const val GLASS_NOW_OFF = "Right now the app is drawing plain surfaces, with no glass."
private const val GLASS_NOW_AUTO = "Right now the app is choosing for itself."
private const val GLASS_CHANGES_NOTHING_ELSE =
    "This changes how the app looks and nothing else. Every figure, source and disclosure you see " +
        "is the same in all four settings."

private const val SECTION_SPEECH = "Voice search"
private const val SPEECH_SUBTITLE =
    "Say a company name instead of typing it. Romanised Hindi and English are both understood."
private const val SPEECH_NEVER_CERTAIN =
    "Recognition reads the words it thinks it heard and is often wrong about Indian company names, " +
        "so the words always stay editable and are never turned into a stock without you. The app " +
        "asks the device's speech service to work offline, but cannot promise it will, and it " +
        "recognises nothing until you grant the microphone."

private const val SECTION_REFRESH = "Background refresh"
private const val REFRESH_SUBTITLE =
    "What the nightly jobs last did. A job that has never run says so; it is not reported as current."

/**
 * The button on the price-refresh card.
 *
 * "Refresh now" because it queues a run immediately rather than rescheduling the nightly job —
 * a button that silently only re-plans would leave a reader on an empty app still waiting for
 * 19:00 IST.
 */
private const val REFRESH_NOW = "Refresh now"

/**
 * The button on the history card.
 *
 * Named for what it actually does, because it costs tens of megabytes of download against one
 * ~208 KB session file, and a reader deserves to know which button they are about to press.
 */
private const val REFRESH_HISTORY = "Fetch more history"
private const val UNKNOWN_STATUS =
    "No status has been read for this job on this device yet."

private const val SECTION_CACHE = "Storage"
private const val CACHE_SUBTITLE = "What clearing removes, and what it keeps."
private const val CACHE_WHATS_KEPT =
    "Kept: the symbols you track, and the track record of their patterns."
private const val CACHE_WHATS_GONE =
    "Removed: stored daily and weekly bars, stored fundamentals, and stored pattern detections. " +
        "They are fetched again on the next refresh."
private const val CLEAR_CACHE = "Clear cached data"

private const val SECTION_SHARE = "Share brief as image"
private const val SHARE_SUBTITLE =
    "Renders one symbol's brief to a picture and hands it to any app on the device. Every figure on " +
        "the image carries its source and as-of date, the score is labelled as shape fit, and the " +
        "disclosures are printed in the image's own footer."
private const val SHARE_SYMBOL_LABEL = "NSE symbol"
private const val SHARE_SYMBOL_PLACEHOLDER = "RELIANCE"
private const val BUILD_BRIEF = "Build the brief"
private const val BUILDING_BRIEF = "Building the brief…"
private const val SHARE_ACTION_LABEL = "Share brief as image"
private const val SHARE_SAFETY_NOTE =
    "The image contains no verdict, no forecast and no target level. If a share target lets you crop " +
        "it, the disclosures at the bottom can be cropped away too — prefer sending the whole image."

private const val RETRY_LABEL = "Try again"
private const val BULLET = "•"

// Test tags.
private const val NOTIFICATION_BUTTON_TAG = "mia_settings_notifications"
private const val CLEAR_CACHE_TAG = "mia_settings_clear_cache"
private const val BUILD_BRIEF_TAG = "mia_settings_build_brief"
private const val SHARE_ENTRY_TAG = "mia_settings_share_entry"
private const val GLASS_TAG_PREFIX = "mia_settings_glass_"

// Lazy-list keys.
private const val ABOUT_KEY = "settings.about"
private const val DISCLAIMER_KEY = "settings.disclaimer"
private const val MEANING_KEY = "settings.meaning"
private const val COVERAGE_KEY = "settings.coverage"
private const val NOTIFICATIONS_KEY = "settings.notifications"
private const val GLASS_KEY = "settings.glass"
private const val SPEECH_KEY = "settings.speech"
private const val REFRESH_KEY = "settings.refresh"
private const val CACHE_KEY = "settings.cache"
private const val SHARE_KEY = "settings.share"
private const val ACTION_MESSAGE_KEY = "settings.action_message"
private const val FOOTER_KEY = "settings.footer"