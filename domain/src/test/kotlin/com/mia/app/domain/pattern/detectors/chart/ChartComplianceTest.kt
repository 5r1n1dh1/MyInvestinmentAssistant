package com.mia.app.domain.pattern.detectors.chart

import com.mia.app.domain.compliance.AdviceLanguage
import com.mia.app.domain.compliance.Disclosure
import com.mia.app.domain.compliance.HonestyContract
import com.mia.app.domain.model.MatchScoreLabels
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * plan.md §1.4 rule 1, machine-checked, for **this package's** own source.
 *
 * The existing `PatternComplianceTest` scans the pattern engine's top level and deliberately excludes
 * the detector packages, so the seven detectors need their own guard: a penalty reason or a
 * `BreakoutTrigger` description is user-facing text, and "expect it to rise" in a penalty string is the
 * exact failure the product exists to avoid.
 *
 * KDoc is excluded on purpose — a comment has to be able to say "never a price target" in order to
 * forbid it — so only `"…"` literals are tested, which is exactly the set the UI can render.
 */
class ChartComplianceTest {

    private val mainSources = File("src/main/kotlin/com/mia/app/domain/pattern/detectors/chart")

    @Test
    fun no_user_facing_string_literal_in_this_package_uses_forbidden_language() {
        val violations = ArrayList<String>()
        for (file in sourceFiles()) {
            for (literal in stringLiterals(file.readText())) {
                val hits = AdviceLanguage.findForbiddenPhrases(literal)
                if (hits.isNotEmpty()) violations += "${file.name}: \"$literal\" → ${hits.joinToString()}"
            }
        }
        assertTrue(violations.isEmpty(), "advice language found in the chart detectors:\n" + violations.joinToString("\n"))
    }

    @Test
    fun every_user_facing_literal_in_this_package_is_actually_scanned() {
        // If the extractor silently stopped finding literals the test above would pass vacuously.
        val literals = sourceFiles().flatMap { stringLiterals(it.readText()) }
        assertTrue(literals.size > 20, "expected dozens of literals, found ${literals.size}")
        assertTrue(literals.any { it.contains("neckline") }, "expected penalty copy about the neckline")
    }

    @Test
    fun the_penalty_copy_is_scanned_including_the_failure_modes_the_spec_names() {
        val literals = sourceFiles().flatMap { stringLiterals(it.readText()) }.joinToString(" ")
        for (phrase in listOf("neckline", "confirmation level", "channel", "volume")) {
            assertTrue(literals.contains(phrase), "expected the penalty copy to mention '$phrase'")
        }
    }

    @Test
    fun the_measured_move_copy_comes_from_the_single_source_and_is_never_a_target() {
        // `MatchScoreLabels.PROJECTION_LABEL` is the only label the projected level may carry, and
        // `Disclosure.MEASURED_MOVE` is the disclosure that has to sit next to it.
        assertEquals("Pattern geometry", MatchScoreLabels.PROJECTION_LABEL)
        assertTrue(AdviceLanguage.isClean(MatchScoreLabels.PROJECTION_LABEL))
        // `MatchScoreLabels.NOT_A_TARGET_WARNING` and `Disclosure.MEASURED_MOVE` are deliberately **not**
        // put through `isClean`: both have to contain the phrase "price target(s)" in order to *deny*
        // being one, and `findForbiddenPhrases` is a plain substring match that cannot see the negation.
        // What is asserted instead is that the probability-claim guard — whose whole job is refusing
        // to let a number be read as an outcome — passes on every one of them.
        assertTrue(Disclosure.MEASURED_MOVE.contains("not price targets"))
        HonestyContract.requireNotProbabilityClaim(MatchScoreLabels.PROJECTION_LABEL)
        HonestyContract.requireNotProbabilityClaim(MatchScoreLabels.NOT_A_TARGET_WARNING)
        HonestyContract.requireNotProbabilityClaim(Disclosure.MEASURED_MOVE)
    }

    @Test
    fun the_detector_display_names_are_the_canonical_accuracy_table_keys() {
        // plan.md D6: hit rates are reported per stock **and** per pattern, so `Match.name` has to be
        // the canonical key the accuracy table is written against. A rename here silently orphans a
        // stored history.
        val names = listOf(
            HeadAndShouldersDetector().name,
            InverseHeadAndShouldersDetector().name,
            DoubleTopDetector().name,
            DoubleBottomDetector().name,
            AscendingTriangleDetector().name,
            DescendingTriangleDetector().name,
            FlagDetector.BULL_FLAG_NAME,
            FlagDetector.BEAR_FLAG_NAME,
        )
        for (name in names) {
            assertTrue(
                com.mia.app.domain.pattern.PatternIds.isCanonical(name),
                "'$name' is not a canonical pattern name",
            )
        }
        assertEquals(names.toSet(), names.toSet().intersect(PatternIdsCanonicalSet), "names must be canonical")
        // And the ids are distinct, so two patterns can never share an accuracy row.
        assertEquals(names.size, names.map { com.mia.app.domain.pattern.PatternIds.of(it) }.toSet().size)
    }

    private val PatternIdsCanonicalSet: Set<String>
        get() = com.mia.app.domain.pattern.PatternIds.CANONICAL_NAMES.toSet()

    private fun sourceFiles(): List<File> {
        assertTrue(mainSources.isDirectory, "expected ${mainSources.absolutePath} to exist")
        return mainSources.listFiles { file: File -> file.isFile && file.extension == "kt" }
            .orEmpty()
            .sortedBy { it.name }
    }

    /** Every `"…"` literal in [source], with escaped quotes resolved and comments skipped. */
    private fun stringLiterals(source: String): List<String> {
        val literals = ArrayList<String>()
        var i = 0
        while (i < source.length) {
            when {
                source.startsWith("//", i) -> i = endOfLine(source, i)
                source.startsWith("/*", i) -> i = endOfBlockComment(source, i)
                source[i] == '"' -> {
                    val end = endOfStringLiteral(source, i)
                    literals += source.substring(i + 1, end)
                    i = end + 1
                }

                source[i] == '\'' -> i = endOfCharLiteral(source, i)
                else -> i++
            }
        }
        return literals
    }

    private fun endOfLine(source: String, start: Int): Int {
        val next = source.indexOf('\n', start)
        return if (next < 0) source.length else next
    }

    private fun endOfBlockComment(source: String, start: Int): Int {
        var depth = 0
        var i = start
        while (i < source.length) {
            when {
                source.startsWith("/*", i) -> {
                    depth++
                    i += 2
                }

                source.startsWith("*/", i) -> {
                    depth--
                    i += 2
                    if (depth == 0) return i
                }

                else -> i++
            }
        }
        return source.length
    }

    private fun endOfStringLiteral(source: String, start: Int): Int {
        var i = start + 1
        while (i < source.length) {
            when (source[i]) {
                '\\' -> i += 2
                '"' -> return i
                '\n' -> return i
                else -> i++
            }
        }
        return source.length
    }

    private fun endOfCharLiteral(source: String, start: Int): Int {
        var i = start + 1
        while (i < source.length) {
            when (source[i]) {
                '\\' -> i += 2
                '\'' -> return i + 1
                '\n' -> return i
                else -> i++
            }
        }
        return source.length
    }
}