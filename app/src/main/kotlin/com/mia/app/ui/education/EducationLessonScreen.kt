package com.mia.app.ui.education

import com.mia.app.domain.compliance.Disclosure
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import com.mia.app.domain.education.Curriculum
import com.mia.app.domain.education.Lesson
import com.mia.app.ui.common.DisclaimerFooter
import com.mia.app.ui.common.EmptyState
import com.mia.app.ui.theme.MiaSpacing
import com.mia.app.ui.theme.glass.GlassBackdrop
import com.mia.app.ui.theme.glass.GlassCard
import com.mia.app.ui.theme.glass.GlassTopBar

/**
 * One lesson, read in full — gaps E1/E3, planv2 §W6.
 *
 * ### Why the "does not establish" panel is the first thing after the body
 *
 * Because a reader who skims should still leave with the right belief. The lesson explains a
 * concept; the panel states what that explanation is *not* evidence for. Putting it last would mean
 * the skimming reader — who is most likely to over-read a lesson — leaves with the lesson's
 * confident tone and none of its caveat. Putting it above the related-links keeps the caveat inside
 * the part of the screen that is always visible on a phone.
 *
 * ### Why an unknown id renders an empty state rather than throwing
 *
 * Lesson ids are in a nav route, which means a stale link, a restored back stack, or a curriculum
 * edit between launches can all produce an id that no longer resolves. That is a navigation state,
 * not a programming error, and it must not take the app down on the way in.
 */
@Composable
fun EducationLessonScreen(
    lessonId: String,
    modifier: Modifier = Modifier,
) {
    // `remember` keyed on the id, so navigating between two lessons does not show the previous one
    // while the corpus lookup re-runs.
    val lesson = remember(lessonId) { Curriculum.lesson(lessonId) }

    Box(modifier = modifier.fillMaxSize().testTag(EDUCATION_LESSON_TAG)) {
        // Sibling behind the content, never the first child of the column — `fillMaxSize()` inside a
        // `Column` claims the full height and leaves the bar and the body nothing. Compiles cleanly,
        // renders an empty screen.
        GlassBackdrop(modifier = Modifier.fillMaxSize())

        Column(modifier = Modifier.fillMaxSize()) {
            GlassTopBar(backdrop = {}) {
                Text(
                    text = lesson?.title ?: "Lesson",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
            }

            if (lesson == null) {
                EmptyState(
                    title = "That lesson is not available",
                    body = "The curriculum may have changed since this link was made. Open Education " +
                        "to see the current modules.",
                    modifier = Modifier.padding(MiaSpacing.lg),
                )
            } else {
                LessonBody(lesson = lesson)
            }
        }
    }
}

/**
 * The reading surface: body paragraphs, the caveat panel, then the cross-links.
 *
 * Split out from the screen chrome so the layout can be reasoned about — and tested — without the
 * navigation frame around it.
 */
@Composable
private fun LessonBody(lesson: Lesson, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = MiaSpacing.lg,
            end = MiaSpacing.lg,
            top = MiaSpacing.sm,
            bottom = MiaSpacing.xl,
        ),
        verticalArrangement = Arrangement.spacedBy(MiaSpacing.md),
    ) {
        item(key = "body") {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                lesson.body.split("\n\n").filter { it.isNotBlank() }.forEach { paragraph ->
                    Text(
                        text = paragraph.trim(),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }

        item(key = "does-not-establish") {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "What this does not establish",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(text = lesson.doesNotEstablish, style = MaterialTheme.typography.bodyMedium)
            }
        }

        item(key = "related") {
            // Only rendered when at least one cross-link exists. The schema makes all three
            // optional, and a panel reading "related: none" teaches a reader nothing — the absence
            // of a link is the honest statement.
            val related = relatedLines(lesson)
            if (related.isNotEmpty()) {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Related in this app",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    related.forEach { Text(text = it, style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }

        item(key = "footer") { DisclaimerFooter(text = Disclosure.DATA_SOURCE) }
    }
}

/**
 * The cross-link lines for a lesson, in the order pattern → ratio → flag.
 *
 * Plain text, not navigation, and that is a deliberate limitation worth naming. The curriculum
 * schema carries these ids precisely so a lesson can point at the screen that shows the thing, and
 * wiring them to navigation means choosing *which* screen for a ratio — the fundamentals tab or a
 * pattern's glossary row — which is a real decision about information architecture rather than a
 * formatting one. So they render as named references now, which is already more than the app
 * offered, and the ids are in the data whenever that decision is made.
 */
private fun relatedLines(lesson: Lesson): List<String> = buildList {
    lesson.relatedPattern?.let { add("Pattern — $it") }
    lesson.relatedRatio?.let { add("Ratio — $it") }
    lesson.relatedFlag?.let { add("Red flag — $it") }
}

/** Test tag for the lesson reader. */
const val EDUCATION_LESSON_TAG: String = "education-lesson"