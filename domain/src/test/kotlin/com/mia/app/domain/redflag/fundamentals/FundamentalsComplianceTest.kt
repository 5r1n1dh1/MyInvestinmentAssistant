package com.mia.app.domain.redflag.fundamentals

import com.mia.app.domain.compliance.AdviceLanguage
import com.mia.app.domain.compliance.HonestyContract
import com.mia.app.domain.model.CashFlowDerived
import com.mia.app.domain.model.QuarterRecord
import com.mia.app.domain.model.RedFlag
import com.mia.app.domain.model.Sector
import com.mia.app.domain.redflag.RedFlagContext
import java.io.File
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * plan.md §1.4 rule 1, machine-checked for this package: no string literal may read as advice.
 *
 * ### Why the source is scanned rather than only the output
 * A rule builds its copy out of literals, so a literal is the unit a reviewer can break. Scanning
 * the **source** catches a forbidden phrase in a skip reason or a threshold that no fixture
 * happens to reach, which an output-only scan would miss until a user hit it. KDoc and line
 * comments are excluded deliberately — a comment has to be able to say "never a price target" in
 * order to forbid it — so only `"…"` literals are tested, which is exactly the set the UI can
 * render.
 *
 * ### The second scan is the one that matters most
 * [everyProducedStringIsClean] runs the *actual output* of every rule — titles, thresholds,
 * evidence labels and values, and skip reasons — through the same filters, because the output is
 * what a user reads and interpolation can assemble a phrase no author wrote.
 */
class FundamentalsComplianceTest {

    /**
     * spec §11 rule 2's banned list, wider than [AdviceLanguage.FORBIDDEN].
     *
     * `AdviceLanguage` is the enforcement point for advice claims; this list is the spec's own
     * enumeration of words that turn a measurement into a verdict or a recommendation, and it
     * includes "risk", "safe", "cheap" and "overvalued", which the machine list does not carry
     * because they are judgement words rather than advice phrases. Both are applied.
     */
    private val BANNED_WORDS = listOf(
        "buy", "sell", "avoid", "exit", "entry", "target", "guarantee", "guaranteed", "will",
        "should", "risk", "risky", "safe", "safely", "dangerous", "overvalued", "cheap",
        "recommend", "recommendation", "profitable", "unprofitable", "worthless",
    )

    @Test
    fun `noUserFacingStringLiteralInThisPackageUsesForbiddenLanguage`() {
        val violations = mutableListOf<String>()
        for (file in sourceFiles()) {
            for (literal in stringLiterals(file.readText())) {
                val hits = AdviceLanguage.findForbiddenPhrases(literal)
                if (hits.isNotEmpty()) {
                    violations += "${file.name}: \"$literal\" -> ${hits.joinToString()}"
                }
            }
        }
        assertTrue(violations.isEmpty(), "advice language in the fundamentals rules:\n" + violations.joinToString("\n"))
    }

    @Test
    fun `everyUserFacingLiteralInThisPackageIsScanned`() {
        // If the extractor silently stopped finding literals, the test above would pass vacuously.
        val literals = sourceFiles().flatMap { stringLiterals(it.readText()) }
        assertTrue(literals.size > 80, "expected dozens of literals, found ${literals.size}")
        assertTrue(literals.any { it.contains("Trailing EPS fell from") })
        assertTrue(literals.any { it.contains("Days sales outstanding") })
        assertTrue(literals.any { it.contains("Promoter holding is not pledge") })
        assertTrue(literals.any { it.contains("no Goodwill line") })
    }

    @Test
    fun `noTitleContainsABannedWordOrANAdvicePhrase`() {
        for (flag in everyFlagEverProduced()) {
            val lower = flag.title.lowercase()
            for (word in BANNED_WORDS) {
                assertTrue(
                    !Regex("(?<![a-z])${Regex.escape(word)}(?![a-z])").containsMatchIn(lower),
                    "${flag.id}: title contains \"$word\" -> ${flag.title}",
                )
            }
            assertTrue(AdviceLanguage.findForbiddenPhrases(flag.title).isEmpty(), flag.title)
            HonestyContract.requireNotProbabilityClaim(flag.title)
            assertTrue(!lower.contains("will rise"), flag.title)
            assertTrue(!lower.contains("target"), flag.title)
            assertTrue(!lower.contains("buy"), flag.title)
        }
    }

    @Test
    fun `everyProducedStringIsCleanIncludingEvidenceThresholdsAndSkipReasons`() {
        for (flag in everyFlagEverProduced()) {
            val texts = buildList {
                add(flag.title)
                add(flag.threshold)
                add(flag.id)
                flag.evidence.forEach { row ->
                    add(row.label)
                    add(row.value)
                }
            }
            for (text in texts) {
                assertTrue(text.isNotBlank(), "${flag.id}: blank user-facing string")
                assertTrue(
                    AdviceLanguage.findForbiddenPhrases(text).isEmpty(),
                    "${flag.id}: \"$text\"",
                )
                HonestyContract.requireNotProbabilityClaim(text)
            }
        }
    }

    @Test
    fun `everySkipReasonIsCleanNonBlankAndSaysWhyItWasNotEvaluated`() {
        val engine = FundamentalsRedFlagEngine()
        val reasons = listOf(relianceContext(), tcsContext(), hdfcBankContext(), titanContext())
            .flatMap { engine.skipsFor(it) }

        assertTrue(reasons.size >= 10, "expected a skip reason per unevaluable rule, got ${reasons.size}")
        for (skip in reasons) {
            assertTrue(skip.reason.isNotBlank(), skip.ruleId)
            assertTrue(
                AdviceLanguage.findForbiddenPhrases(skip.reason).isEmpty(),
                "${skip.ruleId}: ${skip.reason}",
            )
            assertTrue(
                !skip.reason.startsWith("This company"),
                "${skip.ruleId}: ${skip.reason}",
            )
        }
    }

    @Test
    fun `anEmptyReportNeverReadsAsAllClearForAContextThatCouldNotBeChecked`() {
        val engine = FundamentalsRedFlagEngine()
        val report = engine.evaluate(relianceContext())

        assertEquals(1, report.flags.size, "RELIANCE's one documented flag")
        assertTrue(report.skipped.isNotEmpty(), "spec §11 rule 8: suppressed is not clean")
        assertTrue(
            report.headerText(com.mia.app.domain.model.FlagTab.FUNDAMENTALS).isNotBlank(),
        )
    }

    // ------------------------------------------------------------------ fixtures

    /** Every flag the ten rules can produce, across the golden and synthetic contexts. */
    private fun everyFlagEverProduced(): List<RedFlag> {
        val contexts = listOf<Pair<RedFlagContext, RatiosCardSeries?>>(
            relianceContext() to Golden.relianceCard,
            tcsContext() to Golden.tcsCard,
            hdfcBankContext() to Golden.hdfcBankCard,
            titanContext() to Golden.titanCard,
            distressedContext() to RatiosCardSeries(
                debtorDays = mapOf(2025 to 20.0, 2026 to 70.0),
                inventoryDays = mapOf(2025 to 20.0, 2026 to 70.0),
                daysPayable = mapOf(2025 to 10.0, 2026 to 10.0),
                rocePercent = mapOf(2024 to 40.0, 2025 to 30.0, 2026 to 20.0),
            ),
            distressedContext() to null,
        )
        return contexts.flatMap { (context, card) ->
            FundamentalsRedFlagRules.all(card).flatMap { it.evaluate(context) }
        }
    }

    /** Figures chosen to trip every check at once, in a sector where every check is meaningful. */
    private fun distressedContext() = testContext(
        symbol = "STRESSED",
        sector = Sector.CEMENT,
        ratios = Golden.titanRatios.copy(symbol = "STRESSED", sector = Sector.CEMENT, pe = 9.0),
        derived = CashFlowDerived(
            cfoByYear = mapOf(2024 to -100.0, 2025 to -200.0, 2026 to -300.0),
            patByYear = mapOf(2024 to 100.0, 2025 to 200.0, 2026 to 300.0),
            debtByYear = mapOf(2025 to 30_000.0, 2026 to 40_000.0),
            equityByYear = mapOf(2025 to 10_000.0, 2026 to 10_000.0),
            ebitByYear = mapOf(2026 to 500.0),
            interestByYear = mapOf(2026 to 1_000.0),
            otherIncomeByYear = mapOf(2026 to 900.0),
            goodwillByYear = mapOf(2026 to 50_000.0),
            totalAssetsByYear = mapOf(2026 to 100_000.0),
            promoterPledgePercent = 30.0,
            revenueByYear = mapOf(2025 to 100.0, 2026 to 120.0),
        ),
        quarters = (0 until 9).map { index ->
            QuarterRecord(2024 + index / 4, index % 4 + 1, eps = if (index < 5) 6.0 else 7.2 - (index - 4) * 1.2)
        },
        candles = listOf(
            dailyCandle(LocalDate.of(2026, 3, 31), 190.0),
            dailyCandle(LocalDate.of(2025, 3, 31), 264.0),
        ),
    )

    // ------------------------------------------------------------ source scanner

    /** This package's own sources, and only this package's. */
    private fun sourceFiles(): List<File> {
        val main = File("src/main/kotlin/com/mia/app/domain/redflag/fundamentals")
        assertTrue(main.isDirectory, "expected ${main.absolutePath} to exist")
        val files = main.listFiles { file: File -> file.isFile && file.extension == "kt" }.orEmpty()
            .sortedBy { it.name }
        assertTrue(files.isNotEmpty(), "no fundamentals rule sources found in ${main.absolutePath}")
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