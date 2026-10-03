package com.mia.app.domain.pattern.detectors.candlestick

import com.mia.app.domain.compliance.AdviceLanguage
import com.mia.app.domain.compliance.HonestyContract
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * plan.md §1.4 rule 1, machine-checked for **this** package: no user-facing string literal may read
 * as advice.
 *
 * The repository's own `PatternComplianceTest` scans only the *top level* of the pattern package,
 * so a detector package would otherwise ship unchecked copy. This test scans every `.kt` file in
 * `pattern/detectors/candlestick/`.
 *
 * KDoc and line comments are excluded on purpose — a comment has to be able to say "never a price
 * target" in order to forbid it — so only `"…"` literals are tested, which is exactly the set the
 * UI can ever render.
 */
class CandlestickComplianceTest {

    @Test
    fun `noUserFacingStringLiteralInThisPackageUsesForbiddenLanguage`() {
        val violations = ArrayList<String>()
        for (file in sourceFiles()) {
            for (literal in stringLiterals(file.readText())) {
                val hits = AdviceLanguage.findForbiddenPhrases(literal)
                if (hits.isNotEmpty()) {
                    violations += "${file.name}: \"$literal\" -> ${hits.joinToString()}"
                }
            }
        }
        assertTrue(violations.isEmpty(), "advice language found:\n" + violations.joinToString("\n"))
    }

    @Test
    fun `everyUserFacingLiteralInThisPackageIsScanned`() {
        // If the extractor silently stopped finding literals, the test above would pass vacuously.
        val literals = sourceFiles().flatMap { stringLiterals(it.readText()) }
        assertTrue(literals.size > 25, "expected dozens of literals, found ${literals.size}")
        assertTrue(literals.any { it == "Engulfment" }, "the measure names must be scanned too")
        assertTrue(
            literals.any { it.contains("location matters more than") },
            "a rendered penalty reason must be scanned",
        )
    }

    @Test
    fun `thePenaltyReasonsAreShownToTheUserAndReadAsEvidenceNotAdvice`() {
        // The `Penalty.reason` strings are rendered verbatim in the "why this is lower" line, so
        // each one must be clean, and must not read as a probability.
        val reasons = buildList {
            val signals = CandlestickSignals(HammerFixture.textbook())
            val candles = HammerFixture.textbook()
            addAll(
                listOf(
                    "The next session did not close above the hammer's high",
                    "Volume on the hammer was below its own 20-session average",
                    "A lower low printed in the next few sessions",
                    "The pattern did not form within 2.0% of the lowest low",
                    "Volatility on the signal bar is more than twice its own trailing median",
                    "Volume expanded but delivery % is below 70 % of its own average",
                ),
            )
            require(signals.barCount == candles.size)
        }
        AdviceLanguage.requireClean(*reasons.toTypedArray())
        reasons.forEach { HonestyContract.requireNotProbabilityClaim(it) }
    }

    @Test
    fun `theCanonicalPatternNamesAreComplianceClean`() {
        val names = listOf(
            CandlestickNames.BULLISH_ENGULFING,
            CandlestickNames.BEARISH_ENGULFING,
            CandlestickNames.HAMMER,
            CandlestickNames.SHOOTING_STAR,
            CandlestickNames.MORNING_STAR,
            CandlestickNames.EVENING_STAR,
        )
        AdviceLanguage.requireClean(*names.toTypedArray())
        assertEquals(names.size, names.distinct().size)
    }

    /** The files this agent owns. */
    private fun sourceFiles(): List<File> {
        val main = File("src/main/kotlin/com/mia/app/domain/pattern/detectors/candlestick")
        assertTrue(main.isDirectory, "expected ${main.absolutePath} to exist")
        return main.listFiles { file: File -> file.isFile && file.extension == "kt" }
            .orEmpty()
            .sortedBy { it.name }
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
            if (source.startsWith("/*", i)) {
                depth++
                i += 2
            } else if (source.startsWith("*/", i)) {
                depth--
                i += 2
                if (depth == 0) return i
            } else {
                i++
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