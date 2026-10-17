package com.mia.app.ui.fundamentals

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mia.app.domain.compliance.Disclosure
import com.mia.app.domain.model.Confidence
import com.mia.app.domain.model.RedFlag
import com.mia.app.domain.ratios.QuarterlyPoint
import com.mia.app.domain.ratios.RatioState
import com.mia.app.domain.ratios.RatioUnit
import com.mia.app.domain.ratios.Trend
import com.mia.app.domain.ratios.VerificationSource
import com.mia.app.domain.ratios.VerificationSources
import com.mia.app.domain.ratios.YearValue
import com.mia.app.ui.common.AsOfChip
import com.mia.app.ui.common.DisclaimerFooter
import com.mia.app.ui.common.DivergenceChip
import com.mia.app.ui.common.EmptyState
import com.mia.app.ui.common.ErrorState
import com.mia.app.ui.common.EvidenceDialog
import com.mia.app.ui.common.LoadingState
import com.mia.app.ui.common.NOT_AVAILABLE
import com.mia.app.ui.common.RedFlagAccess
import com.mia.app.ui.common.RUPEE
import com.mia.app.ui.common.SectionHeader
import com.mia.app.ui.common.StaleBadge
import com.mia.app.ui.common.UiStrings
import com.mia.app.ui.common.displayName
import com.mia.app.ui.common.formatFixed
import com.mia.app.ui.common.formatPercent
import com.mia.app.ui.theme.MiaSpacing
import kotlin.math.abs

/**
 * The Fundamentals tab (plan.md §7).
 *
 * ### Section order, and why it is not alphabetical or numeric
 * ```
 *  red-flag banner     pinned, OUTSIDE the scroll — plan.md §8.3
 *  not-fetched note    "nothing cached yet" is not an error
 *  sector panel        the sector, its evidence, and §7.3's override statement
 *  ratio overview      how many rows are figures, suppressions, unsourced and stale
 *  ratio cards         in the EXACT order of RatioPriority for the resolved sector
 *  coverage            parser sections missing + rules that abstained — plan.md §12
 *  disclosure footer   plan.md §1.4 rule 3 / §3
 * ```
 *
 * ### The three absences are three different renderings
 * This is the whole design of the tab, and the reason it is built around `RatioAvailability` rather
 * than around `Double?`. plan.md §13 lists *"misleading blank ratios → user distrusts app"* as a
 * product risk and §7.3 is the fix, so:
 *
 * | state | container | tag | sentence in the value slot |
 * |---|---|---|---|
 * | `AVAILABLE` | the card's own fill | — | the figure |
 * | `STALE` | the card's own fill + a red note | — | the figure, marked old with its age |
 * | `SUPPRESSED_FOR_SECTOR` | a `tertiary`-bordered box | **Not applicable** | *"Not applicable for Banking"* + §7.3's own reason |
 * | `NOT_SOURCED` | no container | **Not sourced** | *"Not available from our sources"* + why |
 * | `MISSING` | no container | **Not published** | *"Not published this time"* + why |
 *
 * The two §7.3 states differ in **container**, **tag** and **sentence**, so they cannot be mistaken
 * for each other; and because the value slot always holds a sentence rather than an empty cell, none
 * of them can be mistaken for a number. `RatioAvailability`'s own `init` blocks a suppressed,
 * unsourced or missing ratio from carrying a value at all, so `0.00` for a bank's debt/equity is not
 * reachable from this screen.
 *
 * ### Sector is never guessed
 * plan.md §7.1 and §1.4 rule 8: an unresolved sector keeps `Sector.OTHER`, the generic §7.2 order is
 * used unchanged, and [SectorPanel] prints the resolver's own reason for not resolving. A wrong
 * sector is not cosmetic — §7.3 lets it decide which ratios are displayed at all.
 *
 * @param viewModel obtained with `hiltViewModel()`. The symbol arrives through the route's
 *   `SavedStateHandle` (argument name `symbol`, see `Screen.Fundamentals`), so the tab is restorable
 *   after process death without a field on the ViewModel.
 */
@Composable
fun FundamentalsScreen(
    modifier: Modifier = Modifier,
    viewModel: FundamentalsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var evidenceFlag by remember { mutableStateOf<RedFlag?>(null) }

    evidenceFlag?.let { flag ->
        EvidenceDialog(flag = flag, onDismissRequest = { evidenceFlag = null })
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag(FUNDAMENTALS_TAG),
    ) {
        // plan.md §8.3: at the top of the tab, and outside the scroll so a flag cannot scroll away
        // before it has been read.
        RedFlagAccess(
            flags = state.redFlags,
            // The Fundamentals tab never rendered a coverage section — `flagCoverageIncomplete`
            // was computed and then unused — so the gap is stated in the row itself.
            skippedRules = state.skippedRules,
            ruleFailures = state.ruleFailures,
            onFlagClick = { evidenceFlag = it },
            modifier = Modifier.padding(horizontal = MiaSpacing.lg, vertical = MiaSpacing.sm),
        )

        state.notFetchedNote?.let { note ->
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(horizontal = MiaSpacing.lg)
                    .testTag(NOT_FETCHED_TAG),
            )
        }

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
            state.errorReason?.let { reason ->
                item(key = ERROR_KEY) {
                    ErrorState(reason = reason, onRetry = viewModel::onRetry)
                }
            }

            item(key = SECTOR_KEY) { SectorPanel(state = state) }

            if (state.isLoading) {
                item(key = LOADING_KEY) { LoadingState(label = LOADING_LABEL) }
            }

            if (state.cards.isNotEmpty()) {
                item(key = OVERVIEW_KEY) { RatioOverview(state = state) }
            }

            state.cards.forEach { card ->
                item(key = cardKey(card)) { RatioCardView(card = card, state = state) }
            }

            if (state.needsQuarterlySection) {
                item(key = QUARTERLY_KEY) { QuarterlySection(rows = state.quarterly, state = state) }
                state.quarterly.forEach { row ->
                    item(key = quarterlyKey(row)) { QuarterlyCard(row = row, state = state) }
                }
            }

            if (state.needsCoverageSection) {
                item(key = COVERAGE_KEY) { CoverageSection(state = state) }
            }

            item(key = FOOTER_KEY) { DisclaimerFooter(text = Disclosure.DATA_SOURCE) }
        }
    }
}

// --------------------------------------------------------------------------- sector panel

/**
 * The sector, how it was decided, and what §7.3 did about it.
 *
 * ### Why the evidence sentence is shown verbatim
 * `SectorResolution.evidence` is written for a person — *"matched `bank` in the published sector
 * text"* — and it is the only thing that makes a classification auditable. plan.md §7.1 forbids
 * guessing, and a classification the reader cannot check is indistinguishable from a guess.
 *
 * ### Why the unresolved case is stated, not hidden
 * An unresolved sector is the **correct** outcome more often than not (`LARSEN & TOUBRO` carries no
 * industry word, and `SectorResolver` returns `OTHER` for it deliberately). Saying so, with the
 * resolver's reason, is what keeps the generic list below from reading as the app's opinion about
 * the company.
 */
@Composable
private fun SectorPanel(state: FundamentalsUiState, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MiaSpacing.xs),
    ) {
        SectionHeader(title = SECTOR_TITLE, subtitle = SECTOR_SUBTITLE)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MiaSpacing.sm),
        ) {
            Text(
                text = sectorLabel(state),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.testTag(SECTOR_VALUE_TAG),
            )
            state.freshness?.let { freshness ->
                StaleBadge(freshness = freshness)
            }
        }
        Text(
            text = state.sectorResolution?.evidence ?: SECTOR_NOT_LOADED,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.overrideNote?.let { note ->
            Text(
                text = OVERRIDE_PREFIX + note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag(OVERRIDE_TAG),
            )
        }
    }
}

/**
 * The sector's display name, or the explicit "could not resolve" sentence.
 *
 * The unresolved wording is deliberately different in kind from a sector name: it is a statement
 * about the app's knowledge rather than about the company, and it must not look like a value.
 */
private fun sectorLabel(state: FundamentalsUiState): String =
    if (state.isSectorUnresolved) {
        SECTOR_UNRESOLVED_LABEL
    } else {
        state.sector.displayNameForUser()
    }

// ------------------------------------------------------------------------- ratio overview

/**
 * The count of each kind of row, before any of them is scrolled to.
 *
 * ### Why this is the second thing on the tab
 * plan.md §7.3 exists because *"a single global list misleads on banks"*. A reader who cannot see
 * that three of the cards say "not applicable for Banking" until they scroll into them has learned
 * nothing about why the list looks odd. One line, up front, says it: which figures are figures, which
 * the sector removed, which we cannot source, and how many are stale.
 */
@Composable
private fun RatioOverview(state: FundamentalsUiState, modifier: Modifier = Modifier) {
    val figures = state.cards.sumOf { card -> card.readings.count { it.hasValue } }
    val total = state.cards.sumOf { card -> card.readings.size }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MiaSpacing.xs),
    ) {
        SectionHeader(
            title = RATIOS_TITLE,
            subtitle = RATIOS_SUBTITLE,
        )
        Text(
            text = OVERVIEW_COUNTS(
                figures = figures,
                total = total,
                suppressed = state.suppressedCount,
                notSourced = state.notSourcedCount,
                stale = state.staleCount,
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(OVERVIEW_TAG),
        )
    }
}

// ------------------------------------------------------------------------------ ratio card

/**
 * One plan.md §7.2 card, in the plan's order.
 *
 * @param state the whole tab state, needed for the page's provenance: every figure on a
 *   screener.in page shares one publication date, so one `AsOfChip` per card is honest rather than a
 *   shortcut.
 */
@Composable
private fun RatioCardView(
    card: RatioCard,
    state: FundamentalsUiState,
    modifier: Modifier = Modifier,
) {
    val anyStale = card.readings.any { it.availability.state == RatioState.STALE }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag(CARD_TAG_PREFIX + card.slot.id.name),
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
            CardHeader(card = card)
            card.readings.forEach { reading ->
                ReadingRow(reading = reading, state = state)
            }
            if (card.wantsTrend) {
                TrendBlock(
                    trend = card.trend,
                    note = card.trendNote,
                    unit = card.readings.firstOrNull()?.unit ?: RatioUnit.PERCENT,
                )
            }
            if (anyStale) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    text = CARD_STALE_LINE,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * The card's title, its plan rank, and the reason the plan puts it there.
 *
 * ### Why the rank is rendered
 * plan.md §7.2 ranks by *"how much the ratio changes your conclusion about the business"*. Showing
 * the number makes the ordering legible: a reader who wonders why ROCE is above ROE is owed the
 * answer, and §7.2's own rationale for that row — *"duplicated leverage"* — is exactly the answer.
 *
 * A §7.3-only card shows `Sector` instead of a number, because it has no §7.2 rank; inventing one
 * would assert a position in the plan's order that the plan does not assign.
 */
@Composable
private fun CardHeader(card: RatioCard, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MiaSpacing.xs),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MiaSpacing.sm),
        ) {
            Text(
                text = card.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() },
            )
            RankBadge(
                rank = card.planRank,
                isSectorSpecific = card.isSectorSpecific,
                isSupplementary = card.isSupplementary,
            )
        }
        Text(
            text = card.slot.id.rationale,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * `1` … `12` for a §7.2 row, `Sector` for a §7.3-only one, `Ownership` for the unranked card.
 *
 * The third label exists because printing "0" beside an FII holding would claim §7.2 ranked it
 * somewhere — including *after* everything, which is a claim too. Bordered, so colour is never the
 * encoding.
 */
@Composable
private fun RankBadge(
    rank: Int,
    isSectorSpecific: Boolean,
    isSupplementary: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Text(
        text = when {
            isSupplementary -> OWNERSHIP_BADGE_LABEL
            isSectorSpecific -> SECTOR_BADGE_LABEL
            else -> rank.toString()
        },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = MaterialTheme.shapes.extraSmall,
            )
            .padding(horizontal = MiaSpacing.sm, vertical = 2.dp)
            .semantics {
                contentDescription = when {
                    isSupplementary ->
                        "published by our source but not ranked by plan.md section 7.2"
                    isSectorSpecific -> "added for this sector by plan.md section 7.3"
                    else -> "priority $rank of 12 in plan.md section 7.2"
                }
            },
    )
}

/**
 * One ratio: the figure, or one of the four honest absences, with its provenance.
 *
 * ### Why the divergence path does not use `MergedValueRow`
 * `MergedValueRow` renders the plain value when `Merged.hasConflict` is false, and `hasConflict`
 * requires `Confidence.CONFLICT`. A field that is both stale **and** divergent carries
 * `Confidence.STALE` — `FreshnessPolicy.confidenceFor` prefers staleness deliberately — and
 * `MergedValueRow` would then hide the second number, which plan.md §5.1 requires to be shown. So
 * [DivergenceChip] is called directly here; it is the component that renders both numbers, and this
 * file has no averaging path at all.
 */
@Composable
private fun ReadingRow(
    reading: RatioReading,
    state: FundamentalsUiState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MiaSpacing.xs),
    ) {
        Text(
            text = reading.id.title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        when (reading.availability.state) {
            RatioState.AVAILABLE, RatioState.STALE -> ValueRow(reading = reading, state = state)

            RatioState.SUPPRESSED_FOR_SECTOR -> AbsentRow(
                tag = TAG_NOT_APPLICABLE,
                sentence = NOT_APPLICABLE_SENTENCE_PREFIX + state.sector.displayNameForUser(),
                note = reading.availability.note,
                emphasised = true,
            )

            RatioState.NOT_SOURCED -> AbsentRow(
                tag = TAG_NOT_SOURCED,
                sentence = NOT_SOURCED_SENTENCE,
                note = reading.availability.note,
                emphasised = false,
                verifyWith = VerificationSources.forRatio(
                    reading.id,
                    reading.availability.state,
                    state.sector,
                ).orEmpty(),
            )

            RatioState.MISSING -> AbsentRow(
                tag = TAG_NOT_PUBLISHED,
                sentence = MISSING_SENTENCE,
                note = reading.availability.note,
                emphasised = false,
                verifyWith = VerificationSources.forRatio(
                    reading.id,
                    reading.availability.state,
                    state.sector,
                ).orEmpty(),
            )
        }
    }
}

/**
 * A figure, with the divergence chip when a second source disagreed.
 *
 * ### Why the divergence line names the delta
 * plan.md §5.1 measured a 7.18% P/E disagreement on TCS and never established which figure is
 * correct. `UiStrings.conflictExplanation` states the divergence, says both are shown and says
 * neither is averaged — that is the whole content of the rule, so it is rendered verbatim rather than
 * paraphrased into "sources differ".
 */
@Composable
private fun ValueRow(
    reading: RatioReading,
    state: FundamentalsUiState,
    modifier: Modifier = Modifier,
) {
    val value = reading.valueOrNull
    val divergence = reading.divergence
    val merged = reading.mergedOrNull()

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MiaSpacing.xs),
    ) {
        if (merged != null && divergence != null) {
            DivergenceChip(
                primary = formatReading(value, reading.unit),
                alternate = formatReading(divergence.alternateValue, reading.unit) +
                    SEPARATOR + divergence.alternateSource.displayName(),
                confidence = reading.confidence,
            )
            Text(
                text = UiStrings.conflictExplanation(divergence.deltaPercent),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(DIVERGENCE_EXPLANATION_TAG),
            )
        } else {
            Text(
                text = formatReading(value, reading.unit),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics {
                    contentDescription = spokenReading(reading, value)
                },
            )
            // A snapshot-level conflict with no attributable second figure. plan.md §5.1 says both
            // numbers are shown and neither is averaged; when the second number does not exist for
            // this field, saying so is the only honest rendering left — silently dropping the conflict
            // would understate a disagreement the merge did find.
            if (reading.confidence == Confidence.CONFLICT) {
                Text(
                    text = CONFLICT_WITHOUT_SECOND_SOURCE,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        ReadingProvenance(reading = reading, state = state)

        if (reading.availability.state == RatioState.STALE) {
            reading.availability.note?.let { note ->
                Text(
                    text = note,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * Source + as-of for one figure. plan.md §1.4 rule 3 — mandatory, on every single number.
 *
 * One [AsOfChip] per card rather than one per reading: every figure on a screener.in page shares one
 * publication date, and repeating the same chip down a two-metric card would be noise that trains
 * the eye to skip the chip that matters.
 */
@Composable
private fun ReadingProvenance(
    reading: RatioReading,
    state: FundamentalsUiState,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MiaSpacing.sm),
    ) {
        state.freshness?.let { freshness ->
            AsOfChip(
                source = reading.source,
                asOf = reading.asOf,
                freshness = freshness,
            )
        }
        Text(
            text = unitLabel(reading.unit),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One of the three absences, rendered as a **sentence** in the value slot.
 *
 * @param emphasised `true` only for `SUPPRESSED_FOR_SECTOR`. The sector's removal is a decision the
 *   app made and the plan cares about, so it earns a bordered box in the tertiary colour; the two
 *   data gaps are facts about a source, so they stay quiet. Both still carry a tag and a full
 *   sentence, so emphasis is never the only difference.
 */
@Composable
private fun AbsentRow(
    tag: String,
    sentence: String,
    note: String?,
    emphasised: Boolean,
    verifyWith: List<VerificationSource> = emptyList(),
    modifier: Modifier = Modifier,
) {
    val accent: Color = if (emphasised) {
        MaterialTheme.colorScheme.tertiary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }
    val rowModifier = modifier
        .fillMaxWidth()
        .testTag(ABSENT_ROW_TAG)

    if (emphasised) {
        Box(
            modifier = rowModifier
                .clip(RoundedCornerShape(8.dp))
                .border(width = 1.dp, color = accent, shape = RoundedCornerShape(8.dp))
                .padding(MiaSpacing.sm),
        ) {
            AbsentRowContent(
                tag = tag,
                sentence = sentence,
                note = note,
                emphasised = true,
                verifyWith = verifyWith,
            )
        }
    } else {
        Box(modifier = rowModifier) {
            AbsentRowContent(
                tag = tag,
                sentence = sentence,
                note = note,
                emphasised = false,
                verifyWith = verifyWith,
            )
        }
    }
}

/**
 * The tag, the sentence, the reason — and, for a data gap, **where the figure actually is**.
 *
 * ### Why this is the deliverable that replaced the rejected bank-ratio work
 * docs/LIVE-PROBE.md §3 fetched HDFCBANK's page and found the bank-ratio *labels* present with
 * their **values** behind a login paywall: 60 `highlight-cell` elements rendering nothing. The
 * promotion was rejected because recording a figure the app cannot see is fabrication. That left
 * "we cannot show you this" with no second half — and the honest second half is *"we cannot show
 * you this, and it is published here"*. A blank the reader cannot act on is the failure mode
 * plan.md §13 lists as "misleading blank ratios → user distrusts app".
 *
 * ### Why the sources are named but not linked
 * The NSE corporate-announcement and shareholding-pattern endpoints **404 from this network**
 * (LIVE-PROBE §4), so an in-app "open this" button would be a promise the build cannot keep. These
 * are places a person goes, and they are printed as places a person goes.
 */
@Composable
private fun AbsentRowContent(
    tag: String,
    sentence: String,
    note: String?,
    emphasised: Boolean,
    verifyWith: List<VerificationSource> = emptyList(),
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MiaSpacing.xs),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MiaSpacing.sm),
        ) {
            Text(
                text = tag,
                style = MaterialTheme.typography.labelSmall,
                color = if (emphasised) {
                    MaterialTheme.colorScheme.tertiary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier
                    .clip(MaterialTheme.shapes.extraSmall)
                    .border(
                        width = 1.dp,
                        color = if (emphasised) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                        shape = MaterialTheme.shapes.extraSmall,
                    )
                    .padding(horizontal = MiaSpacing.sm, vertical = 2.dp),
            )
            Text(
                text = sentence,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        note?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (verifyWith.isNotEmpty()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                text = WHERE_TO_VERIFY_LABEL,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            verifyWith.forEach { source ->
                Text(
                    text = "· " + source.asSentence(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(VERIFY_WITH_TAG),
                )
            }
        }
    }
}

// ------------------------------------------------------------------------- trend affordance

/**
 * The 5-year trend plan.md §7.2 asks for on the two margin ratios — **and the honest absence of it**.
 *
 * ### Why a bar sparkline and not a line chart
 * `ui/chart` belongs to another agent, this module cannot be compiled here, and a five-point annual
 * series is a bar chart. Bars carry the value as a height *and* the fiscal year is printed beneath,
 * so nothing depends on reading a slope against an axis. plan.md §12's low-RAM target also rules out
 * a chart library on this path.
 *
 * ### Why the empty case says what was missing
 * `RatioComputation` returns a `Computation<Trend>` whose `note` on a miss is a sentence naming the
 * absent input — *"gross margin needs cost of goods sold; screener.in's consolidated page publishes no
 * such line"*. Printing that sentence instead of an empty frame is the difference between *"there is
 * no trend"* and *"the app did not try"*, which plan.md §12 names as the failure to avoid.
 */
@Composable
private fun TrendBlock(
    trend: Trend?,
    note: String?,
    unit: RatioUnit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MiaSpacing.xs),
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            text = TREND_LABEL,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (trend == null) {
            Text(
                text = TREND_ABSENT_PREFIX + (note ?: TREND_ABSENT_FALLBACK),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(TREND_ABSENT_TAG),
            )
            return
        }
        Sparkline(
            points = trend.points,
            unit = unit,
            modifier = Modifier.testTag(TREND_TAG),
        )
        Text(
            text = buildString {
                append(trendSummary(trend = trend, unit = unit))
                trend.note?.let { append(SEPARATOR).append(it) }
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One bar per fiscal year, height normalised to the largest absolute value in the series.
 *
 * ### Why the bars are not green or red
 * A trend is a series, not a price. Colouring it by sign would attach a bull/bear meaning to "the
 * operating margin improved", and plan.md §1.4's market-colour discipline reserves those two colours
 * for a figure measured against another figure. Here the bar height *is* the encoding and the exact
 * number is in the spoken description.
 *
 * `Arrangement.Bottom` inside each column and `Alignment.Bottom` on the row is what keeps every year
 * label on one baseline: a top-aligned column would float each label to the top of its own bar and
 * produce a staircase of dates.
 */
@Composable
private fun Sparkline(
    points: List<YearValue>,
    unit: RatioUnit,
    modifier: Modifier = Modifier,
) {
    val peak = points.maxOfOrNull { abs(it.value) } ?: 1.0
    val scale = if (peak > 0.0) peak else 1.0
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = sparklineSpoken(points = points, unit = unit) },
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(MiaSpacing.xs),
    ) {
        points.forEach { point ->
            val fraction = (abs(point.value) / scale).toFloat().coerceIn(MIN_BAR_FRACTION, 1f)
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height((SPARKLINE_BAR_MAX * fraction).dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)),
                )
                Text(
                    text = point.year.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = MiaSpacing.xs),
                )
            }
        }
    }
}

// ------------------------------------------------------------------------ quarterly results

/**
 * The header for the published `quarters` card.
 *
 * ### Why it says what it covers before showing anything
 * The next three cards are Sales, Operating profit and EPS. A reader who does not know that will
 * read three numbers and assume they are the whole quarter; the subtitle names the table and the
 * fact that it is thirteen quarters deep, which is the difference between "this app picked three
 * things" and "this is what the source publishes".
 */
@Composable
private fun QuarterlySection(
    rows: List<QuarterlyCardRow>,
    state: FundamentalsUiState,
    modifier: Modifier = Modifier,
) {
    val readable = rows.count { !it.isAbsent }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MiaSpacing.xs),
    ) {
        SectionHeader(title = QUARTERLY_TITLE, subtitle = QUARTERLY_SUBTITLE)
        Text(
            text = QUARTERLY_COUNTS(
                readable = readable,
                total = rows.size,
                quarters = rows.firstOrNull()?.comparison?.series?.size ?: 0,
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(QUARTERLY_OVERVIEW_TAG),
        )
        if (readable < rows.size) {
            // Absent lines are named here rather than left as an unexplained gap in the sequence.
            Text(
                text = QUARTERLY_ABSENT_PREFIX +
                    rows.filter { it.isAbsent }.joinToString(", ") { it.title },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(QUARTERLY_ABSENT_TAG),
            )
        }
    }
}

/**
 * One quarterly line: the latest figure, both comparisons, and a sparkline of the series.
 *
 * ### Why the two comparisons are separate rows and not one "growth" figure
 * An Indian festival quarter is routinely 25–30% above the one before it and flat year on year.
 * Printing whichever is larger would make a seasonal business look like it is accelerating every
 * quarter, and printing an average of the two would be a number the source never published. Both
 * are shown, each against the quarter it is actually compared with, and each carries its endpoints.
 *
 * ### Why an absent line renders a sentence
 * HDFCBANK publishes no `Operating Profit` row. The card says which row it looked for and which row
 * the page prints instead, because "Financing Profit" under a heading called "Operating profit"
 * would be a confident wrong number on the one sector plan.md §7.3 exists to protect.
 */
@Composable
private fun QuarterlyCard(
    row: QuarterlyCardRow,
    state: FundamentalsUiState,
    modifier: Modifier = Modifier,
) {
    val comparison = row.comparison
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag(QUARTERLY_CARD_TAG_PREFIX + row.metric.name),
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
                text = row.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = row.metric.rationale,
                style = MaterialTheme.typography.bodySmall,
            )

            if (row.isAbsent) {
                Text(
                    text = comparison.notes.joinToString(SEPARATOR),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(QUARTERLY_ABSENT_NOTE_TAG),
                )
                return@Column
            }

            val latest = comparison.latest
            if (latest != null) {
                Text(
                    text = formatReading(latest.value, row.metric.unit),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.semantics {
                        contentDescription = "${row.title} ${formatReading(latest.value, row.metric.unit)}" +
                            " in ${latest.period}, ${unitLabel(row.metric.unit)}"
                    },
                )
                Text(
                    text = LATEST_PERIOD_PREFIX + latest.period,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            ComparisonRow(
                label = QUARTERLY_ON_QUARTER_LABEL,
                change = comparison.quarterOverQuarterPercent,
                now = comparison.latest,
                before = comparison.previousQuarter,
            )
            ComparisonRow(
                label = QUARTERLY_YEAR_ON_YEAR_LABEL,
                change = comparison.yearOnYearPercent,
                now = comparison.latest,
                before = comparison.sameQuarterPriorYear,
            )
            if (!row.hasAnyComparison) {
                Text(
                    text = NO_COMPARISON_PREFIX + comparison.notes.joinToString(SEPARATOR),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(QUARTERLY_NO_COMPARISON_TAG),
                )
            } else if (comparison.notes.isNotEmpty()) {
                comparison.notes.forEach { note ->
                    Text(
                        text = note,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            QuarterlySparkline(
                series = comparison.series,
                unit = row.metric.unit,
                modifier = Modifier.testTag(QUARTERLY_SPARKLINE_TAG_PREFIX + row.metric.name),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MiaSpacing.sm),
            ) {
                state.freshness?.let { freshness ->
                    AsOfChip(source = row.source, asOf = row.asOf, freshness = freshness)
                }
                Text(
                    text = unitLabel(row.metric.unit),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * One comparison, or the reason it could not be made.
 *
 * A `null` change is rendered as a sentence rather than an empty cell, and the sentence names the
 * quarter that was wanted — the same rule the quarterly domain notes follow, because an absent
 * comparison and a comparison of zero must never look alike.
 */
@Composable
private fun ComparisonRow(
    label: String,
    change: Double?,
    now: QuarterlyPoint?,
    before: QuarterlyPoint?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MiaSpacing.xs),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (change == null) {
            Text(
                text = NOT_COMPARABLE,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        val rendered = formatPercent(change, 1)
        Text(
            text = rendered,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            // Up/down is never signalled by colour alone (plan.md §1.4): the glyph and the sentence
            // both carry it.
            modifier = Modifier.semantics {
                contentDescription = "$label ${if (change >= 0) "up" else "down"} " +
                    "${formatFixed(kotlin.math.abs(change), 1)} percent"
            },
        )
        if (now != null && before != null) {
            Text(
                text = "$label ${COMPARED_AGAINST} ${before.period}" +
                    if (now.period != before.period) " · ${now.period}" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * One bar per published quarter, oldest at the left.
 *
 * ### Why the labels are quarter abbreviations rather than the full period
 * Thirteen full `"Sep 2023"` labels do not fit a 360 dp phone at `labelSmall`, and truncating them
 * to a year would lose the only part that distinguishes the columns. `S3`/`D3`/`J4`/`J6` keeps all
 * three parts and still reads, which is why the axis is quarterly rather than annual.
 *
 * ### Why it is not coloured by sign
 * Same reason as the ratio sparkline: a quarterly operating-profit series is a series, not a price,
 * and colouring it green/red would attach a market meaning to a number that has none.
 */
@Composable
private fun QuarterlySparkline(
    series: List<QuarterlyPoint>,
    unit: RatioUnit,
    modifier: Modifier = Modifier,
) {
    if (series.isEmpty()) return
    val peak = series.maxOfOrNull { kotlin.math.abs(it.value) } ?: 1.0
    val scale = if (peak > 0.0) peak else 1.0
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = quarterlySparklineSpoken(series, unit) },
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        series.forEach { point ->
            val fraction = (kotlin.math.abs(point.value) / scale).toFloat().coerceIn(MIN_BAR_FRACTION, 1f)
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height((SPARKLINE_BAR_MAX * fraction).dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)),
                )
                Text(
                    text = shortPeriod(point.period),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** `"Sep 2023"` → `"S3"`: the month, its first letter, and the year's last digit. */
private fun shortPeriod(period: String): String {
    val parts = period.trim().split(' ', ' ')
    val month = parts.getOrNull(0) ?: return period
    val year = parts.getOrNull(1) ?: return period
    val letter = month.firstOrNull()?.uppercase() ?: return period
    return "$letter${year.takeLast(2)}"
}

/** Spoken form: the values, because a bar's height is not an announced quantity. */
private fun quarterlySparklineSpoken(series: List<QuarterlyPoint>, unit: RatioUnit): String =
    series.joinToString(", ") { "${it.period}: ${formatFixed(it.value, 1)} ${unitLabel(unit)}" }

// ------------------------------------------------------------------------ coverage section

/**
 * plan.md §12: what the parse and the red-flag engine could **not** do.
 *
 * Without this an absent ratio reads as a gap in the data rather than a gap in our ability to read
 * the data, and an absent red-flag check reads as "no flags" — which plan.md §12 calls the most
 * dangerous possible failure in the product.
 */
@Composable
private fun CoverageSection(state: FundamentalsUiState, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MiaSpacing.xs),
    ) {
        SectionHeader(title = COVERAGE_TITLE, subtitle = COVERAGE_SUBTITLE)
        if (state.cards.isEmpty()) {
            EmptyState(title = NO_RATIOS_TITLE, body = NO_RATIOS_BODY)
        }
        state.missingSections.forEach { section ->
            Text(
                text = "Not parsed — " + section,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.parserNotes.forEach { note ->
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.skippedRules.forEach { skip ->
            Text(
                text = "Red flag not evaluated — " + skip.ruleId + ": " + skip.reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Every abstaining check names where its inputs are published. Without this the panel
            // would say only "not evaluated", which reads as "we looked and found nothing" — the one
            // reading plan.md §12 forbids.
            VerificationSources.forRule(skip.ruleId).orEmpty().forEach { source ->
                Text(
                    text = VERIFY_RULE_PREFIX + source.asSentence(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(VERIFY_RULE_TAG),
                )
            }
        }
        state.ruleFailures.forEach { (ruleId, message) ->
            Text(
                text = "Red flag check failed — " + ruleId + ": " + message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

// ---------------------------------------------------------------------------- formatting

/**
 * A figure in its own unit.
 *
 * `RatioUnit` is what stops a formatter printing `₹21.20` for a P/E. Three of the units share the
 * glyph `×` and mean different things: `RATIO` is a bare ratio (`0.45×`), `MULTIPLE` is a
 * valuation/coverage multiple (`8.75×`), and only `PERCENT` is a percentage. The unit is *also*
 * printed in words beside the chip, so the glyph never has to carry the distinction alone.
 */
private fun formatReading(value: Double?, unit: RatioUnit): String = when (unit) {
    RatioUnit.PERCENT -> formatPercent(value, 1)
    RatioUnit.RATIO -> value?.let { formatFixed(it, 2) + TIMES } ?: NOT_AVAILABLE
    RatioUnit.MULTIPLE -> value?.let { formatFixed(it, 2) + TIMES } ?: NOT_AVAILABLE
    RatioUnit.CRORE -> value?.let { RUPEE + formatFixed(it, 2) + CRORE_SUFFIX } ?: NOT_AVAILABLE
    RatioUnit.DAYS -> value?.let { formatFixed(it, 0) + DAYS_SUFFIX } ?: NOT_AVAILABLE
    // `pp`, never `%`: an FII share that falls 18.67 → 17.19 moved 1.48 *points*. Rendering that
    // as "-1.48%" describes a 7.9% relative fall under the wrong label, which is precisely the
    // substitution a percentage-point unit exists to prevent.
    RatioUnit.PERCENTAGE_POINTS -> value?.let {
        (if (it >= 0) "+" else "") + formatFixed(it, 2) + POINTS_SUFFIX
    } ?: NOT_AVAILABLE
    RatioUnit.RUPEE_PER_SHARE -> value?.let { RUPEE + formatFixed(it, 2) } ?: NOT_AVAILABLE
}

/** The unit named in words, beside the figure. Never inferred from the number's shape. */
private fun unitLabel(unit: RatioUnit): String = when (unit) {
    RatioUnit.PERCENT -> UNIT_PERCENT
    RatioUnit.RATIO -> UNIT_RATIO
    RatioUnit.MULTIPLE -> UNIT_MULTIPLE
    RatioUnit.CRORE -> UNIT_CRORE
    RatioUnit.DAYS -> UNIT_DAYS
    RatioUnit.PERCENTAGE_POINTS -> UNIT_POINTS
    RatioUnit.RUPEE_PER_SHARE -> UNIT_RUPEE_PER_SHARE
}

/** The spoken form of a figure, so TalkBack never reads a bare glyph. */
private fun spokenReading(reading: RatioReading, value: Double?): String {
    val rendered = formatReading(value, reading.unit)
    return when (reading.availability.state) {
        RatioState.AVAILABLE -> "$rendered, ${unitLabel(reading.unit)}"
        RatioState.STALE -> "$rendered, stale. " + (reading.availability.note ?: "")
        else -> reading.availability.note ?: rendered
    }
}

/** One line describing a trend: direction as a word, plus the per-year change. */
private fun trendSummary(trend: Trend, unit: RatioUnit): String {
    val from = trend.oldest
        ?.let { formatFixed(it.value, 1) + " in " + it.year }
        ?: NOT_AVAILABLE
    val to = trend.latest
        ?.let { formatFixed(it.value, 1) + " in " + it.year }
        ?: NOT_AVAILABLE
    val perYear = trend.changePerYear
        ?.let { change ->
            SEPARATOR + (if (change > 0.0) "+" else "") + formatFixed(change, 2) + " " +
                unitLabel(unit) + PER_YEAR
        }
        .orEmpty()
    val direction = when {
        trend.isRising -> "rising"
        trend.isFalling -> "falling"
        else -> "flat across the window"
    }
    return "${trend.yearsSpanned} year(s)" + SEPARATOR + direction + SEPARATOR + from +
        ARROW + to + perYear
}

/** Spoken form of the sparkline: the values, because a bar's height is not an announced quantity. */
private fun sparklineSpoken(points: List<YearValue>, unit: RatioUnit): String =
    points.joinToString(SEPARATOR_TRIMMED) { point ->
        point.year.toString() + ": " + formatFixed(point.value, 1) + " " + unitLabel(unit)
    }

/** The one-line count of every kind of row, up front. */
private fun OVERVIEW_COUNTS(
    figures: Int,
    total: Int,
    suppressed: Int,
    notSourced: Int,
    stale: Int,
): String = buildString {
    append("$figures of $total figures shown")
    if (suppressed > 0) append(SEPARATOR).append(suppressed).append(" not applicable for this sector")
    if (notSourced > 0) append(SEPARATOR).append(notSourced).append(" not available from our sources")
    if (stale > 0) append(SEPARATOR).append(stale).append(" stale")
}

// ---------------------------------------------------------------------------- copy tokens

private const val FUNDAMENTALS_TAG: String = "mia_fundamentals"
private const val SECTOR_VALUE_TAG: String = "mia_fundamentals_sector"
private const val OVERRIDE_TAG: String = "mia_fundamentals_override"
private const val OVERVIEW_TAG: String = "mia_fundamentals_overview"
private const val NOT_FETCHED_TAG: String = "mia_fundamentals_not_fetched"
private const val CARD_TAG_PREFIX: String = "mia_fundamentals_card_"
private const val ABSENT_ROW_TAG: String = "mia_fundamentals_absent"
private const val VERIFY_WITH_TAG: String = "mia_fundamentals_verify_with"
private const val VERIFY_RULE_TAG: String = "mia_fundamentals_verify_rule"
private const val QUARTERLY_OVERVIEW_TAG: String = "mia_fundamentals_quarterly_overview"
private const val QUARTERLY_ABSENT_TAG: String = "mia_fundamentals_quarterly_absent"
private const val QUARTERLY_CARD_TAG_PREFIX: String = "mia_fundamentals_quarterly_card_"
private const val QUARTERLY_ABSENT_NOTE_TAG: String = "mia_fundamentals_quarterly_absent_note"
private const val QUARTERLY_NO_COMPARISON_TAG: String = "mia_fundamentals_quarterly_no_comparison"
private const val QUARTERLY_SPARKLINE_TAG_PREFIX: String = "mia_fundamentals_quarterly_spark_"
private const val QUARTERLY_KEY: String = "fundamentals.quarterly"
private const val OWNERSHIP_BADGE_LABEL: String = "Ownership"
private const val TREND_TAG: String = "mia_fundamentals_trend"
private const val TREND_ABSENT_TAG: String = "mia_fundamentals_trend_absent"
private const val DIVERGENCE_EXPLANATION_TAG: String = "mia_fundamentals_divergence"

private const val SECTOR_KEY: String = "fundamentals.sector"
private const val LOADING_KEY: String = "fundamentals.loading"
private const val ERROR_KEY: String = "fundamentals.error"
private const val OVERVIEW_KEY: String = "fundamentals.overview"
private const val COVERAGE_KEY: String = "fundamentals.coverage"
private const val FOOTER_KEY: String = "fundamentals.footer"

/** Stable per card: `RatioPriority.compose` asserts slot ids are unique within a sector's list. */
private fun cardKey(card: RatioCard): String = "fundamentals.card." + card.slot.id.name

private fun quarterlyKey(row: QuarterlyCardRow): String = "fundamentals.quarterly." + row.metric.name

private const val QUARTERLY_TITLE: String = "Quarterly results"
private const val QUARTERLY_SUBTITLE: String =
    "What the source publishes for the last thirteen quarters, and how the latest one compares " +
        "with the quarter before and with the same quarter a year earlier."

private const val LATEST_PERIOD_PREFIX: String = "Latest published quarter: "
private const val QUARTERLY_ON_QUARTER_LABEL: String = "Quarter on quarter"
private const val QUARTERLY_YEAR_ON_YEAR_LABEL: String = "Year on year"
private const val NOT_COMPARABLE: String =
    "Not comparable — the reason is printed below with the other notes."
private const val NO_COMPARISON_PREFIX: String = "Neither comparison could be made: "
private const val COMPARED_AGAINST: String = "compared with"

/** `QUARTERLY_COUNTS` built as a function so the numbers are formatted in one place. */
private fun QUARTERLY_COUNTS(readable: Int, total: Int, quarters: Int): String = buildString {
    append("$readable of $total published lines")
    if (readable < total) append(SEPARATOR).append(total - readable).append(" not published by this page")
    append(SEPARATOR).append(quarters).append(" quarters of history")
}

private const val QUARTERLY_ABSENT_PREFIX: String =
    "Not published on this page, so not shown: "

/** Prefix for a source line attached to a ratio row. */
private const val WHERE_TO_VERIFY_LABEL: String = "Where to verify this yourself"

/** Prefix for a source line attached to an abstaining red-flag check. */
private const val VERIFY_RULE_PREFIX: String = "Where to verify this check: "

private const val LOADING_LABEL: String = "Reading the cached fundamentals page"

private const val SECTOR_TITLE: String = "Sector"
private const val SECTOR_SUBTITLE: String =
    "The sector decides which ratios matter. It is resolved from published text and is never " +
        "guessed."
private const val SECTOR_UNRESOLVED_LABEL: String = "Could not be resolved"
private const val SECTOR_NOT_LOADED: String = "No sector evidence is available yet."
private const val SECTOR_BADGE_LABEL: String = "Sector"
private const val OVERRIDE_PREFIX: String = "Sector override — "

private const val RATIOS_TITLE: String = "Ratios"
private const val RATIOS_SUBTITLE: String =
    "In plan.md's order of how much each ratio changes your conclusion, amended for this sector."

private const val TAG_NOT_APPLICABLE: String = "Not applicable"
private const val TAG_NOT_SOURCED: String = "Not sourced"
private const val TAG_NOT_PUBLISHED: String = "Not published"

private const val NOT_APPLICABLE_SENTENCE_PREFIX: String = "Not applicable for "
private const val NOT_SOURCED_SENTENCE: String = "Not available from our sources"
private const val MISSING_SENTENCE: String = "Not published this time"

private const val TREND_LABEL: String = "5-year trend"
private const val TREND_ABSENT_PREFIX: String = "No trend available — "
private const val TREND_ABSENT_FALLBACK: String =
    "the source published no annual series for this ratio"
private const val CARD_STALE_LINE: String =
    "Some figures on this card are older than the window for fundamentals. Each is marked above."
private const val CONFLICT_WITHOUT_SECOND_SOURCE: String =
    "The sources disagreed on this figure, and the second figure is not stored, so only one number " +
        "can be shown here. Neither source's value has been averaged into the other."

private const val COVERAGE_TITLE: String = "What was not checked"
private const val COVERAGE_SUBTITLE: String =
    "A ratio or a rule that could not be evaluated is listed here. An absent check is not a check " +
        "that found nothing."
private const val NO_RATIOS_TITLE: String = "No fundamentals page is stored for this symbol"
private const val NO_RATIOS_BODY: String =
    "Fundamentals come from a single free source, cached on the device. Nothing here is derived " +
        "from the price data, and no figure is shown without its source and as-of date."

private const val UNIT_PERCENT: String = "percent"
private const val UNIT_RATIO: String = "ratio, shown as a multiple"
private const val UNIT_MULTIPLE: String = "multiple"
private const val UNIT_CRORE: String = "₹ crore"
private const val UNIT_DAYS: String = "days"
private const val UNIT_POINTS: String = "percentage points"
private const val UNIT_RUPEE_PER_SHARE: String = "₹ per share"

/** Suffix for a change between two percentages. Never `%`. */
private const val POINTS_SUFFIX: String = " pp"

private const val TIMES: String = "×"
private const val ARROW: String = " → "
private const val CRORE_SUFFIX: String = " Cr"
private const val DAYS_SUFFIX: String = " days"
private const val PER_YEAR: String = "/year"

/** ` · ` — the app's separator for "and here is more about the same thing". */
private const val SEPARATOR: String = " · "

/** The same separator without the spaces, for `joinToString`. */
private const val SEPARATOR_TRIMMED: String = ", "

/** A bar is never zero-height: a margin of exactly 0% would otherwise render as nothing at all. */
private const val MIN_BAR_FRACTION: Float = 0.08f

/** Tallest sparkline bar in dp. Small enough that the year labels stay on screen below it. */
private const val SPARKLINE_BAR_MAX: Int = 36