package com.mia.app.ui.education

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mia.app.domain.education.Curriculum
import com.mia.app.domain.education.Level
import com.mia.app.domain.education.Lesson
import com.mia.app.domain.education.Module
import com.mia.app.ui.common.DisclaimerFooter
import com.mia.app.ui.theme.MiaSpacing
import com.mia.app.ui.theme.glass.GlassBackdrop
import com.mia.app.ui.theme.glass.GlassCard
import com.mia.app.ui.theme.glass.GlassTopBar

/**
 * The education hub — gaps E1/E2, planv2 §W6.
 *
 * ### Why this screen exists at all
 *
 * Nineteen modules and 108 lessons were merged into `:domain` before **any** screen referenced
 * `Curriculum`. The content was real, tested and completely invisible: a reader of this app could
 * not reach a single lesson. This screen is the specific thing that closes that gap, which is why
 * it is a top-level route rather than a section folded into another screen — a curriculum a reader
 * has to find is a curriculum most readers will not.
 *
 * ### Why level is a filter and not a gate
 *
 * The three levels are peer tabs, not a Beginner → Intermediate → Expert progression the reader is
 * walked through. Someone who already knows P/E and installs this app mid-career has no use for a
 * gatekeeper, and gating reference material behind "start at the beginning" is how a library
 * becomes unfindable. All three are reachable from the first frame.
 *
 * ### Why there is no progress tracking
 *
 * Not yet, deliberately. A "you are 3 lessons in" marker needs a store, a notion of completion, and
 * a decision about whether finishing a lesson means anything. Marking a lesson complete without
 * ever testing whether the reader understood it would be a badge for opening a screen.
 *
 * ### No back affordance
 *
 * Deliberate, and it matches every other screen in this app: none of them draw one. Navigation
 * back is the platform's job and the system gesture does it. A hand-rolled back arrow here would be
 * the only such control in the build.
 */
@Composable
fun EducationScreen(
    onOpenLesson: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Registration rides on the property initialisers in the four content files, so touching
    // `Curriculum.modules` is what installs them. `remember` then holds the snapshot for the life of
    // the screen: the corpus is immutable at runtime, and re-reading it per frame would rebuild a
    // 108-lesson index on every recomposition.
    val modules = remember { Curriculum.modules }
    var level by rememberSaveable { mutableStateOf(Level.BEGINNER) }
    val visible = remember(modules, level) { modules.filter { it.level == level } }

    Column(modifier = modifier.fillMaxSize().testTag(EDUCATION_TAG)) {
        // The screen's one blurred layer: fixed, static, blurred once. See `Glass.kt`'s performance
        // rules before adding a second one — never inside the LazyColumn below.
        GlassBackdrop(modifier = Modifier.fillMaxSize())

        Column {
            GlassTopBar(backdrop = {}) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Education",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        text = "${modules.size} modules · ${modules.sumOf { it.lessons.size }} lessons",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            LevelTabs(
                selected = level,
                counts = Level.entries.associateWith { l -> modules.count { it.level == l } },
                onSelect = { level = it },
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = MiaSpacing.lg,
                end = MiaSpacing.lg,
                top = MiaSpacing.sm,
                bottom = MiaSpacing.xl,
            ),
            verticalArrangement = Arrangement.spacedBy(MiaSpacing.md),
        ) {
            items(visible, key = { it.id }) { module ->
                ModuleCard(module = module, onOpenLesson = onOpenLesson)
            }

            item(key = "footer") { DisclaimerFooter() }
        }
    }
}

/**
 * One module, expandable to its lessons.
 *
 * Collapsed by default: nineteen expanded modules is a wall, and a reader looking for "RSI" should
 * see the module titles first and decide where to look. Expansion is keyed on the module id, so it
 * survives recomposition and configuration change without needing a store.
 */
@Composable
private fun ModuleCard(module: Module, onOpenLesson: (String) -> Unit) {
    var expanded by rememberSaveable(module.id) { mutableStateOf(false) }

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .semantics {
                    contentDescription = buildString {
                        append(module.title)
                        append(", ${module.lessons.size} lessons")
                        append(if (expanded) ", expanded" else ", collapsed")
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = module.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = module.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // One chevron, rotated. A second icon for the expanded state would need a glyph this
            // build does not have on the classpath — only material3 is a dependency, not
            // material-icons-extended — so rotation is the honest way to get two states from one.
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp).rotate(if (expanded) 90f else 0f),
            )
        }

        if (expanded) {
            module.lessons.forEach { lesson ->
                LessonRow(lesson = lesson, onOpen = { onOpenLesson(lesson.id) })
            }
        }
    }
}

/**
 * A lesson's title, and what it does not establish.
 *
 * The `doesNotEstablish` line is here rather than only inside the lesson because it is the part
 * that decides whether to keep reading. A row showing only a title has taught the reader nothing
 * about the app's central claim, and the whole point of the schema making that field mandatory is
 * that it reaches the reader at every entry point, not just at the bottom of a long body.
 */
@Composable
private fun LessonRow(lesson: Lesson, onOpen: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .testTag(educationLessonTag(lesson.id))
            .padding(vertical = MiaSpacing.xs),
    ) {
        Text(text = lesson.title, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = "Does not establish: ${lesson.doesNotEstablish}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LevelTabs(
    selected: Level,
    counts: Map<Level, Int>,
    onSelect: (Level) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MiaSpacing.lg, vertical = MiaSpacing.sm),
        horizontalArrangement = Arrangement.spacedBy(MiaSpacing.sm),
    ) {
        Level.entries.forEach { level ->
            val active = level == selected
            GlassCard(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onSelect(level) }
                    .semantics {
                        contentDescription = "${level.label}, ${counts[level] ?: 0} modules, " +
                            if (active) "selected" else "not selected"
                    },
                contentPadding = PaddingValues(
                    horizontal = MiaSpacing.sm,
                    vertical = MiaSpacing.xs,
                ),
            ) {
                Text(
                    text = level.label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    color = if (active) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Text(
                    text = "${counts[level] ?: 0} modules",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Test tag for the hub, so a UI test can assert the curriculum is reachable at all. */
const val EDUCATION_TAG: String = "education-hub"

/** Test tag for one lesson row, parameterised by lesson id. */
fun educationLessonTag(lessonId: String): String = "education-lesson-$lessonId"