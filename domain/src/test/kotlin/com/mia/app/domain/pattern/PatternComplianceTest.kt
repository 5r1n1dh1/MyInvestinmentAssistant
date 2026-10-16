package com.mia.app.domain.pattern

import com.mia.app.domain.compliance.AdviceLanguage
import com.mia.app.domain.compliance.Disclosure
import com.mia.app.domain.compliance.HonestyContract
import com.mia.app.domain.model.MatchScoreLabels
import com.mia.app.domain.model.ProjectedPath
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * plan.md §1.4 rule 1, machine-checked: no string literal in the pattern engine's own source may
 * read as advice.
 *
 * The scan reads this package's **top-level** files from disk and runs every string literal
 * through [AdviceLanguage.findForbiddenPhrases]. KDoc is excluded on purpose — a comment has to be
 * able to say "never a price target" in order to forbid it — so only `"…"` literals are tested,
 * which is exactly the set the UI can ever render.
 */
class PatternComplianceTest {

    @Test
    fun `noUserFacingStringLiteralInThisPackageUsesForbiddenLanguage`() {
        val violations = mutableListOf<String>()
        for (file in sourceFiles()) {
            for (literal in stringLiterals(file.readText())) {
                val hits = AdviceLanguage.findForbiddenPhrases(literal)
                if (hits.isNotEmpty()) violations += "${file.name}: \"$literal\" → ${hits.joinToString()}"
            }
        }
        assertTrue(violations.isEmpty(), "advice language found in the pattern engine:\n" + violations.joinToString("\n"))
    }

    @Test
    fun `everyUserFacingLiteralInThisPackageIsScanned`() {
        // If the extractor silently stopped finding literals the test above would pass vacuously.
        val literals = sourceFiles().flatMap { stringLiterals(it.readText()) }
        assertTrue(literals.size > 40, "expected dozens of literals, found ${literals.size}")
        assertTrue(literals.any { it.contains("Not enough history") })
        assertTrue(literals.any { it.contains("Delivery % unavailable") })
        assertTrue(literals.any { it.contains("No index benchmark") })
    }

    @Test
    fun `theProjectionLabelsComeFromTheSingleSourceAndAreNeverATarget`() {
        assertEquals(MatchScoreLabels.PROJECTION_LABEL, ProjectionLabels.MEASURED_MOVE_LABEL)
        assertEquals(MatchScoreLabels.NOT_A_TARGET_WARNING, ProjectionLabels.NOT_A_TARGET)
        assertEquals(Disclosure.PROJECTION_FOOTER, ProjectionLabels.FOOTER)
        assertEquals(Disclosure.SCORE_MEANING, ProjectionLabels.SCORE_MEANING)
        assertEquals(ProjectedPath(emptyList(), 0).label, ProjectionLabels.PATH_LABEL)

        // And the disclosure the app must show next to a projected level actually says so.
        assertTrue(Disclosure.MEASURED_MOVE.contains("not price targets"))
        HonestyContract.requireNotProbabilityClaim(ProjectionLabels.PATH_LABEL)
        HonestyContract.requireNotProbabilityClaim(ProjectionLabels.FOOTER)
    }

    @Test
    fun `theGatesAndSuppressionCopyAreComplianceClean`() {
        val texts = listOf(
            "Not enough history — need 60 daily bars, have 42.",
            "Head & Shoulders not shown — match score 41 is below the 50 render threshold. Geometric fit only.",
            "Head & Shoulders scored 74 with the trend regime aligned — its geometry is projected and its breakout alert is scheduled.",
            "Head & Shoulders scored 74 — shown without a projection: the measured-move level sits 18.2 % from the last close, beyond the 5 % the measured calibration supports.",
            "Head & Shoulders scored 74 — shown without a projection: it carries no measured move, so there is no level to project.",
            "Close above the neckline",
            "Close below the confirmation level",
            "Delivery % unavailable — confirmation partially unscored.",
            "No index benchmark — relative strength unscored.",
            "ADX warm-up incomplete (daily) — context partially unscored.",
            "No confirmed swing points in this window — no structural pattern can match.",
            "Head & Shoulders: CONTEXT unscored — no measures emitted.",
        )
        AdviceLanguage.requireClean(*texts.toTypedArray())
        texts.forEach { HonestyContract.requireNotProbabilityClaim(it) }
    }

    /** The files this agent owns: the pattern engine's top level, never the detector packages. */
    private fun sourceFiles(): List<File> {
        val main = File("src/main/kotlin/com/mia/app/domain/pattern")
        assertTrue(main.isDirectory, "expected ${main.absolutePath} to exist")
        val files = main.listFiles { file: File -> file.isFile && file.extension == "kt" }.orEmpty().sortedBy { it.name }
        assertTrue(files.isNotEmpty(), "no pattern engine sources found in ${main.absolutePath}")
        return files
    }

    /** Every `"…"` literal in [source], with escaped quotes resolved. */
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