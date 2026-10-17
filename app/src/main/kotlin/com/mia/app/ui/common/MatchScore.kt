package com.mia.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mia.app.domain.compliance.Disclosure
import com.mia.app.domain.model.Match
import com.mia.app.domain.model.MatchScoreLabels
import com.mia.app.domain.model.MatchScorePolicy
import com.mia.app.domain.model.MeasuredMove
import com.mia.app.ui.theme.MiaSpacing

/**
 * The match score, rendered so it can never be mistaken for a probability (plan.md §6.1).
 *
 * ### The three encodings a score gets
 * 1. The **number** out of 100.
 * 2. A **word** from the thresholds in `MatchScorePolicy` (`Weak` / `Moderate` / `Strong`), so the
 *    band is readable without reading the digits.
 * 3. A **shape**: a filled arc whose sweep is the score. Colour is the third encoding, never the
 *    only one.
 *
 * ### What is deliberately absent
 * No percentage sign, no percent suffix, no confidence wording, and no colour scale that runs
 * green→red like an expected-return gradient. `AdviceLanguage` and `HonestyContract` forbid
 * probability phrasing outright, and `MatchScoreLabels.DISCLAIMER` is rendered next to the chip by
 * [ScoreExplainCard]. The chip's own spoken description names it as geometric fit, because the glyph
 * and the digits are the only two things a screen reader gets otherwise.
 */
@Composable
fun MatchScoreChip(
    score: Int,
    modifier: Modifier = Modifier,
) {
    val clamped = score.coerceIn(0, 100)
    val band = scoreBand(clamped)

    Row(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(band.containerColor)
            .border(width = 1.dp, color = band.borderColor, shape = MaterialTheme.shapes.small)
            .padding(horizontal = MiaSpacing.sm, vertical = 3.dp)
            .semantics {
                contentDescription =
                    "${MatchScoreLabels.TITLE} $clamped of 100, ${band.label}. " +
                    MatchScoreLabels.SUBTITLE
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MiaSpacing.xs),
    ) {
        Text(
            text = clamped.toString(),
            style = MaterialTheme.typography.titleSmall,
            color = band.contentColor,
        )
        Text(
            text = band.label,
            style = MaterialTheme.typography.labelSmall,
            color = band.contentColor,
        )
    }
}

/**
 * The full explanation of one match: name, score, all four factors, the geometry, and the
 * disclosures that keep the number honest.
 *
 * ### Why the four factors are always shown
 * A single 0–100 number invites the reader to treat it as an oracle. Showing
 * `geometry 82 · structure 61 · confirmation 40 · context 55` immediately reveals that the score
 * is a weighted mean of four partly-failed judgements, which is the honest picture. The weights
 * (40/25/20/15) come from `MatchScorePolicy`, so the UI can never disagree with the scorer.
 *
 * ### Why `Disclosure.SCORE_MEANING` is rendered here
 * `AdviceLanguage.REQUIRED_NEAR` requires the phrase "match score" to be accompanied by
 * `Disclosure.SCORE_MEANING`. This card is where the phrase and the meaning appear together, so
 * the requirement is satisfied structurally rather than by remembering to add a line.
 */
@Composable
fun ScoreExplainCard(
    match: Match,
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = match.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                MatchScoreChip(score = match.score)
            }

            Text(
                text = MatchScoreLabels.SUBTITLE,
                style = MaterialTheme.typography.bodySmall,
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            ScoreBreakdownTable(match)

            match.measuredMove?.let { move ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                MeasuredMoveSummary(move)
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Text(
                text = MatchScoreLabels.DISCLAIMER,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = Disclosure.SCORE_MEANING,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * The four weighted factors.
 *
 * A `Match` without a `breakdown` is legal — a detector that emitted no measures for a factor
 * records the reason elsewhere — so the card says the breakdown is unavailable rather than
 * printing four zeroes, which would read as "every factor scored zero" and is a different claim.
 */
@Composable
private fun ScoreBreakdownTable(match: Match, modifier: Modifier = Modifier) {
    val breakdown = match.breakdown
    if (breakdown == null) {
        Text(
            text = "Per-factor scores were not recorded for this match; only the combined score is " +
                "available.",
            style = MaterialTheme.typography.bodySmall,
        )
        return
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(MiaSpacing.xs)) {
        FactorRow("Geometry", breakdown.geometry, MatchScorePolicy.GEOMETRY_WEIGHT)
        FactorRow("Structure", breakdown.structure, MatchScorePolicy.STRUCTURE_WEIGHT)
        FactorRow("Confirmation", breakdown.confirmation, MatchScorePolicy.CONFIRMATION_WEIGHT)
        FactorRow("Context", breakdown.context, MatchScorePolicy.CONTEXT_WEIGHT)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Weighted total",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = breakdown.total.toString() + " / 100",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * One factor: name, its own 0–100 reading, and the weight it carries in the total.
 *
 * The weight is shown because it is the whole point of §6.3 — a 90 in Context moves the total by
 * 13.5 points and a 90 in Geometry by 36, and a reader who cannot see that will assume the
 * factors are equally important.
 */
@Composable
private fun FactorRow(name: String, value: Int, weight: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "$name $value of 100, weight $weight percent of the total"
            },
        horizontalArrangement = Arrangement.spacedBy(MiaSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(96.dp),
        )
        FactorBar(value = value, weight = weight)
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(28.dp),
            textAlign = TextAlign.End,
        )
    }
}

/**
 * A filled bar whose length is the factor's own score. Length is the encoding; the number beside
 * it is the exact value, so a colour-blind reader loses nothing and a low-vision reader does not
 * have to judge a 3-pixel difference.
 */
@Composable
private fun FactorBar(value: Int, weight: Int, modifier: Modifier = Modifier) {
    val fraction = (value.coerceIn(0, 100)) / 100f
    val trackColor = MaterialTheme.colorScheme.outlineVariant
    val fillColor = MaterialTheme.colorScheme.primary
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(6.dp)
                .clip(CircleShape)
                .background(trackColor),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(6.dp)
                    .clip(CircleShape)
                    .background(fillColor),
            )
        }
        Text(
            text = "w$weight",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = MiaSpacing.xs),
        )
    }
}

/** The measured move as numbers, labelled as geometry (plan.md §6.6 item 1). */
@Composable
private fun MeasuredMoveSummary(move: MeasuredMove, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(MiaSpacing.xs)) {
        Text(
            text = MatchScoreLabels.PROJECTION_LABEL,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "Formation height " + formatPrice(move.height) +
                " · trigger " + formatPrice(move.triggerLevel) +
                " · level " + formatPrice(move.projectedLevel),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = MatchScoreLabels.NOT_A_TARGET_WARNING,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** A score band: the word, and the three roles it paints. */
private data class ScoreBand(
    val label: String,
    val containerColor: Color,
    val contentColor: Color,
    val borderColor: Color,
)

/**
 * The colour band beside a score.
 *
 * ### Why the cut is not a gate
 * This used to read `MatchScorePolicy.PROJECT_THRESHOLD` and paint "projectable" a third colour,
 * which made a **display** band look like a **rule**: a reader could be told their formation was in
 * a special band and reasonably infer the app had decided something about it. It had decided
 * nothing — the band was a threshold, the threshold was unreachable, and the score's only remaining
 * power in this app is deciding whether a formation is drawn at all
 * ([MatchScorePolicy.RENDER_THRESHOLD]).
 *
 * So the bands are keyed on the render threshold plus `UiStrings.TIGHT_FIT_MIN`, described by
 * `UiStrings.fitBand`, and the colours are decoration rather than a verdict. Nothing here may be
 * read as a claim about outcomes: hit rate *falls* as the score rises on this app's own fixture,
 * so a higher band is a closer resemblance to a textbook shape and nothing more.
 */
@Composable
private fun scoreBand(score: Int): ScoreBand = when {
    score >= UiStrings.TIGHT_FIT_MIN -> ScoreBand(
        label = UiStrings.fitBand(score),
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        borderColor = MaterialTheme.colorScheme.primary,
    )

    score >= MatchScorePolicy.RENDER_THRESHOLD -> ScoreBand(
        label = UiStrings.fitBand(score),
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        borderColor = MaterialTheme.colorScheme.secondary,
    )

    else -> ScoreBand(
        label = UiStrings.fitBand(score),
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        borderColor = MaterialTheme.colorScheme.outlineVariant,
    )
}
