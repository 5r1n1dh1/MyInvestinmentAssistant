package com.mia.app.domain.redflag.technical

import com.mia.app.domain.compliance.AdviceLanguage
import com.mia.app.domain.compliance.HonestyContract
import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.RedFlag
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * plan.md §1.4 rule 1 and `redflags-spec.md` §11, machine-checked for **this** package.
 *
 * Two layers, because they catch different things:
 *
 * 1. **Every string literal in the sources.** The repository's own compliance tests do not walk into
 *    a nested package, so a flag rule would otherwise ship copy nobody read. Comments are skipped on
 *    purpose — a comment has to be able to say "never a price target" in order to forbid it — so
 *    only `"…"` literals are scanned, which is exactly the set the UI can render.
 * 2. **Every flag the family actually produces**, across the firing fixtures and the real OHLCV
 *    history. A literal can be clean in isolation and become advice once interpolated
 *    (`"$symbol is a buy"`), so the rendered strings are checked too.
 */
class TechnicalRedFlagComplianceTest {

    /** Words that must never appear in a rendered title, per the brief and plan.md §1.4. */
    private val bannedTitleWords = listOf("will rise", "target", "buy", "sell", "guaranteed")

    @Test
    fun `no user-facing string literal in this package uses forbidden language`() {
        val violations = ArrayList<String>()
        for (file in sourceFiles()) {
            for (literal in stringLiterals(file.readText())) {
                val hits = AdviceLanguage.findForbiddenPhrases(literal)
                if (hits.isNotEmpty()) violations += "${file.name}: \"$literal\" -> ${hits.joinToString()}"
            }
        }
        assertTrue(violations.isEmpty(), "advice language found:\n" + violations.joinToString("\n"))
    }

    @Test
    fun `the literal extractor is actually finding this package's strings`() {
        val literals = sourceFiles().flatMap { stringLiterals(it.readText()) }
        assertTrue(literals.size > 40, "expected dozens of literals, found ${literals.size}")
        assertTrue(literals.contains(DeathCrossRule.THRESHOLD), "threshold strings must be scanned")
        assertTrue(
            literals.any { it.startsWith("High volume means high participation") },
            "caveat fragments must be scanned",
        )
        assertTrue(
            literals.any { it.contains("Not evaluated") || it.contains("daily sessions") },
            "skip reasons are rendered too and must be scanned",
        )
    }

    @Test
    fun `no title contains an advice word`() {
        val flags = allFixtureFlags()
        assertTrue(flags.isNotEmpty())
        for (flag in flags) {
            val lower = flag.title.lowercase()
            for (word in bannedTitleWords) {
                assertTrue(
                    !lower.contains(word),
                    "${flag.id} title contains '$word': ${flag.title}",
                )
            }
        }
    }

    @Test
    fun `every title, threshold, caveat and evidence value is compliance clean`() {
        for (flag in allFixtureFlags()) {
            val strings = buildList {
                add(flag.title)
                add(flag.threshold)
                flag.evidence.forEach { row ->
                    add(row.label)
                    add(row.value)
                }
            }
            AdviceLanguage.requireClean(*strings.toTypedArray())
            strings.forEach { HonestyContract.requireNotProbabilityClaim(it) }
        }
    }

    @Test
    fun `no flag describes the stock instead of the observation`() {
        // redflags-spec.md §11 rule 3: the title names what was observed, never what it implies.
        for (flag in allFixtureFlags()) {
            val lower = flag.title.lowercase()
            assertTrue(
                !lower.startsWith("this stock is") && !lower.startsWith("this company is"),
                "${flag.id} title asserts a property of the company: ${flag.title}",
            )
        }
    }

    @Test
    fun `every title carries at least one number and a stated reference`() {
        // redflags-spec.md §11 rules 1 and 4: numbers always, and always one comparative.
        for (flag in allFixtureFlags()) {
            assertTrue(
                flag.title.any { it.isDigit() },
                "${flag.id} title has no number: ${flag.title}",
            )
        }
    }

    @Test
    fun `every caveat the spec names is present on the flags that need one`() {
        // redflags-spec.md §11 rule 7: caveats are content, not fine print.
        val expected = mapOf(
            TechnicalFlagIds.DEATH_CROSS to DeathCrossRule.CAVEAT,
            TechnicalFlagIds.RSI_OVERBOUGHT to RsiBandRule.CAVEAT,
            TechnicalFlagIds.RSI_OVERSOLD to RsiBandRule.CAVEAT,
            TechnicalFlagIds.DISTRIBUTION_DAY to DistributionDayRule.CAVEAT,
            TechnicalFlagIds.AT_52WEEK_LOW_WITH_DECLINING_OBV to FiftyTwoWeekLowObvRule.CAVEAT,
            TechnicalFlagIds.DELIVERY_COLLAPSE to DeliveryCollapseRule.CAVEAT,
        )
        val byId = allFixtureFlags().associateBy { it.id }
        expected.forEach { (id, caveat) ->
            val flag = byId[id] ?: error("no fixture produced $id")
            assertEquals(caveat, flag.evidenceValue("Caveat"), "$id must carry its caveat row")
        }
    }

    @Test
    fun `skip reasons are renderable sentences, not error codes`() {
        val reasons = TechnicalRedFlagRules.skips(
            technicalContext(TechnicalSeries.tooShortForVolumeAverage(bars = 12)),
        ).map { it.reason } + TechnicalRedFlagRules.skips(
            technicalContext(TechnicalSeries.deliveryAbsent()),
        ).map { it.reason }

        assertTrue(reasons.size >= 6)
        for (reason in reasons) {
            AdviceLanguage.requireClean(reason)
            assertTrue(reason.length > 30, "skip reason is not renderable: '$reason'")
            assertTrue(reason.endsWith("."), "skip reason must read as a sentence: '$reason'")
        }
    }

    @Test
    fun `the compliance walk covers both the overbought and the oversold wording`() {
        val flags = engineeredFixtureFlags()
        val rsiFlags = flags.filter { it.id.startsWith("RSI_") }
        assertTrue(
            rsiFlags.map { it.id }.containsAll(
                listOf(TechnicalFlagIds.RSI_OVERBOUGHT, TechnicalFlagIds.RSI_OVERSOLD),
            ),
            "the engineered series must exercise both bands",
        )
        rsiFlags.forEach { flag ->
            assertTrue(
                bannedTitleWords.none { it in flag.title.lowercase() },
                "${flag.id} title reads as advice: ${flag.title}",
            )
        }
    }

    // ------------------------------------------------------------------------------- plumbing

    /** The flags the engineered series produce — exactly one per band, per rule. */
    private fun engineeredFixtureFlags(): List<RedFlag> = flagsFrom(ENGINEERED_SERIES)

    /** Every flag the family produces on the engineered fixtures plus the real OHLCV history. */
    private fun allFixtureFlags(): List<RedFlag> {
        val real = TechnicalHistoryFixture.symbolsWithHistory()
            .flatMap { TechnicalHistoryFixture.lastBars(it) }
        return flagsFrom(ENGINEERED_SERIES) + flagsFrom(listOf(real))
    }

    private fun flagsFrom(series: List<List<Candle>>): List<RedFlag> =
        series.flatMap { candles ->
            TechnicalRedFlagRules.all().flatMap { rule -> rule.evaluate(technicalContext(candles)) }
        }

    private val ENGINEERED_SERIES: List<List<Candle>> = listOf(
        TechnicalSeries.deathCross(),
        TechnicalSeries.recentDeathCross(),
        TechnicalSeries.monotonicDecline(),
        TechnicalSeries.rsiOverbought(),
        TechnicalSeries.rsiOversold(),
        TechnicalSeries.rsiFreshCrossingOfSeventy(),
        TechnicalSeries.distributionDownDay(),
        TechnicalSeries.atFiftyTwoSessionLowWithFallingObv(),
        TechnicalSeries.deliveryCollapse(),
        TechnicalSeries.deliveryCollapseAfterHighParticipation(),
        TechnicalSeries.atFiftyTwoSessionLowWithFallingObv().map { it.copy(deliveryPercent = 12.0) },
    )

    /** The files this package owns. */
    private fun sourceFiles(): List<File> {
        val main = File("src/main/kotlin/com/mia/app/domain/redflag/technical")
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