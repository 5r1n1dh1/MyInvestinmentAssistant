package com.mia.app.domain.ratios

import com.mia.app.domain.compliance.AdviceLanguage
import com.mia.app.domain.compliance.HonestyContract
import com.mia.app.domain.model.Sector
import com.mia.app.domain.redflag.fundamentals.FundamentalsFlagIds
import com.mia.app.domain.redflag.fundamentals.FundamentalsRedFlagRules
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `VerificationSources` is only a deliverable if it is **complete** — a "where to verify" line
 * attached to four of the five abstaining checks is worse than none, because the one that is
 * missing reads as "nobody knows where this is published".
 *
 * These tests are the completeness contract:
 *
 * 1. every `RatioId` the app can ever leave blank has a named destination;
 * 2. every fundamentals red-flag id has one, and the keys are real ids (a typo would compile and
 *    ship a lookup that never fires);
 * 3. the bank ratios name the RBI Financial Stability Report, which is the specific thing
 *    docs/LIVE-PROBE.md §3 says must replace the rejected promotion;
 * 4. nothing reads as advice.
 */
class VerificationSourcesTest {

    // --------------------------------------------------------------------- ratios

    /**
     * Every ratio that can carry `NOT_SOURCED` or `MISSING` has a destination.
     *
     * The two families are covered separately so a future `RatioId` cannot slip in through a gap
     * between them: a §7.3-only slot and a never-sourced ratio are the same user problem even
     * though they get their state from different code.
     */
    @Test
    fun `every never-sourced and every sector-specific ratio names where to verify it`() {
        val covered = VerificationSources.forEveryRatio()
        val must = RatioId.entries.filter { it.isNeverSourced || it.isSectorSpecific }.toSet()
        assertEquals(20, must.size, "3 never-sourced + 17 §7.3-only")
        for (id in must) {
            val sources = assertNotNull(covered[id], "$id has no verification source")
            assertTrue(sources.isNotEmpty(), id.name)
            for (source in sources) assertWellFormed(id.name, source)
        }
    }

    /**
     * The supplementary ownership ratios are **not** in the table, and that is correct.
     *
     * They are sourced on all five fixtures, so a "where to verify" line beside them would be
     * advice to go and re-check something the app has. [forRatio] returning `null` for them in a
     * sourced state is what keeps that honest.
     */
    @Test
    fun `a sourced or stale ratio has nothing to go and verify`() {
        for (id in RatioId.entries) {
            assertNull(VerificationSources.forRatio(id, RatioState.AVAILABLE, Sector.OTHER), id.name)
            assertNull(VerificationSources.forRatio(id, RatioState.STALE, Sector.OTHER), id.name)
        }
    }

    /**
     * A sector suppression is not a gap, so it gets no destination.
     *
     * §7.3 removing a bank's D/E is a decision about the *measure*, not a hole in the disclosure.
     * Pointing a reader at the annual report would imply they should go and override the app's
     * judgement, which is the opposite of what the override is for.
     */
    @Test
    fun `a sector suppression has no verification source because nothing is missing`() {
        val suppressed = RatioPriority.sectorOverrides(Sector.BANKING).suppressed.keys +
            RatioPriority.sectorOverrides(Sector.IT).suppressed.keys
        assertTrue(suppressed.isNotEmpty())
        for (id in suppressed) {
            assertNull(
                VerificationSources.forRatio(id, RatioState.SUPPRESSED_FOR_SECTOR, Sector.BANKING),
                "${id.name}: a suppressed ratio is not a gap",
            )
        }
    }

    /**
     * A `MISSING` ratio still has somewhere to look.
     *
     * "This page did not carry it" is a per-page accident; the disclosure exists and the annual
     * report carries more of it than the summary page does.
     */
    @Test
    fun `a ratio missing from this page still names the annual report`() {
        for (id in listOf(RatioId.ROCE, RatioId.ROE, RatioId.PB, RatioId.PE_VS_SECTOR_MEDIAN)) {
            val sources = assertNotNull(
                VerificationSources.forRatio(id, RatioState.MISSING, Sector.OTHER),
                id.name,
            )
            assertTrue(
                sources.any { it.title.contains("annual report") },
                "${id.name} has no annual-report destination",
            )
        }
    }

    // ------------------------------------------------ the probe-rejected bank ratios

    /**
     * The specific substitute docs/LIVE-PROBE.md §3 asks for.
     *
     * The probe found a bank's NIM / GNPA / NNPA / CASA / PCR **labels** on screener.in behind 60
     * empty `highlight-cell` elements and the standalone rows carrying no numbers at all, and
     * rejected promoting them. "Rejected" has to mean *something* rather than nothing, and this
     * asserts it: every bank ratio names the company's own asset-quality disclosure **and** the
     * RBI's system-wide report.
     */
    @Test
    fun `bank asset quality ratios name the annual report and the RBI Financial Stability Report`() {
        for (id in listOf(
            RatioId.NET_INTEREST_MARGIN,
            RatioId.GNPA_NNPA,
            RatioId.CASA_RATIO,
            RatioId.PROVISION_COVERAGE_RATIO,
        )) {
            val sources = assertNotNull(
                VerificationSources.forRatio(id, RatioState.NOT_SOURCED, Sector.BANKING),
                id.name,
            )
            val text = sources.joinToString(" ") { it.asSentence() }
            assertTrue(text.contains("annual report"), "${id.name}: no annual report")
            assertTrue(text.contains("Financial Stability Report"), "${id.name}: no RBI FSR")
        }
    }

    /**
     * NBFC ratios point at the disclosure the RBI **requires**, not at an optional note.
     *
     * Stage-2 and provisioning are mandatory disclosures under the Master Direction, so a reader
     * can rely on finding them; saying so is more useful than naming a document that may not have
     * the figure.
     */
    @Test
    fun `NBFC ratios point at the RBI Master Direction disclosure`() {
        for (id in listOf(
            RatioId.ASSET_QUALITY,
            RatioId.STAGE_2_GNPA,
            RatioId.COST_OF_FUNDS,
        )) {
            val sources = assertNotNull(
                VerificationSources.forRatio(id, RatioState.NOT_SOURCED, Sector.NBFC),
                id.name,
            )
            assertTrue(
                sources.any { it.title.contains("Master Direction") },
                "${id.name}: no Master Direction destination",
            )
        }
    }

    // --------------------------------------------------------------------- red flags

    /**
     * Every fundamentals red-flag id has a destination, and every key is a real id.
     *
     * Both directions are asserted: a key that is not in `FundamentalsFlagIds` would compile, ship
     * and never fire, which is the failure mode a string-keyed map always has.
     */
    @Test
    fun `every fundamentals red flag id names where to verify it`() {
        // Read off the rule registry rather than a hand-written list, so a rule added later is
        // covered without anyone remembering to add it here — and cannot be added *without* a
        // destination without failing this test.
        val declared = FundamentalsRedFlagRules.ruleIds
        assertEquals(10, declared.size)
        val covered = VerificationSources.rulesWithSources()
        for (id in declared) {
            val sources = assertNotNull(covered[id], "$id has no verification source")
            assertTrue(sources.isNotEmpty(), id)
            for (source in sources) assertWellFormed(id, source)
        }
        assertEquals(
            emptySet(),
            covered.keys - declared.toSet(),
            "a verification source exists for an id no rule uses",
        )
    }

    /**
     * The two permanently-unsourceable checks name the documents that do carry their inputs.
     *
     * Goodwill is a note on intangible assets; a pledge is a **separate filing** from the
     * shareholding pattern, and the note has to say so — a reader who opens the shareholding
     * pattern will find holding, not encumbrance, and would conclude the promoter has pledged
     * nothing.
     */
    @Test
    fun `goodwill points at the intangibles note and pledging at a separate filing`() {
        val goodwill = assertNotNull(VerificationSources.forRule(FundamentalsFlagIds.GOODWILL_ABOVE_20PCT_ASSETS))
        val goodwillText = goodwill.joinToString(" ") { it.asSentence() }
        assertTrue(goodwillText.contains("intangible"), goodwillText)
        assertTrue(goodwillText.contains("business-combination"), goodwillText)

        val pledging = assertNotNull(VerificationSources.forRule(FundamentalsFlagIds.PROMOTER_PLEDGING))
        assertTrue(pledging.size >= 2, "pledge needs both the pattern filing and the pledge filing")
        val pledgingText = pledging.joinToString(" ") { it.asSentence() }
        assertTrue(pledgingText.contains("holding is not pledge"), pledgingText)
        assertTrue(pledgingText.contains("Disclosure of Pledge"), pledgingText)
    }

    // --------------------------------------------------------------------- compliance

    /**
     * plan.md §1.4 rule 1 over the produced strings and the source literals.
     *
     * The **source** scan matters as much as the output one: these are hand-written strings the UI
     * will print verbatim, so a literal is the unit a reviewer can break, and several of them name
     * documents ("the FDA's warning-letter database") that are exactly the place advice-shaped
     * wording creeps in.
     */
    @Test
    fun `no verification string reads as advice`() {
        val texts = buildList {
            VerificationSources.forEveryRatio().forEach { (id, sources) ->
                sources.forEach { add(it.asSentence()); add(it.title); add(it.where); add(it.whatItAnswers) }
                assertTrue(id.isNeverSourced || id.isSectorSpecific, "$id should not need a source")
            }
            VerificationSources.rulesWithSources().forEach { (ruleId, sources) ->
                sources.forEach { add(it.asSentence()); add(it.title); add(it.where); add(it.whatItAnswers) }
                assertTrue(ruleId.isNotBlank())
            }
        }
        assertTrue(texts.size > 100, "expected every field of every source; got ${texts.size}")
        for (text in texts) {
            assertTrue(text.isNotBlank(), "blank user-facing string")
            assertTrue(AdviceLanguage.findForbiddenPhrases(text).isEmpty(), text)
            HonestyContract.requireNotProbabilityClaim(text)
        }
        for (literal in literalsOf(sourceFile())) {
            assertTrue(AdviceLanguage.findForbiddenPhrases(literal).isEmpty(), literal)
        }
    }

    /**
     * The wording states a fact about a document, never a verdict about a company.
     *
     * "This ratio is high" is a judgement; "this figure is in the asset-quality note" is a
     * location. The second is all this file is allowed to say, and the banned list is the spec's
     * own (§11 rule 2), which is wider than the machine list.
     */
    @Test
    fun `no verification string judges the company`() {
        for (source in allSources()) {
            val text = source.asSentence().lowercase()
            for (word in listOf("good", "bad", "risky", "safe", "cheap", "expensive", "overvalued", "buy", "sell")) {
                assertTrue(
                    !Regex("(?<![a-z])${Regex.escape(word)}(?![a-z])").containsMatchIn(text),
                    "${source.title}: \"$word\" in \"${source.asSentence()}\"",
                )
            }
        }
    }

    /** No source may promise the app can fetch it — LIVE-PROBE measured those endpoints 404ing. */
    @Test
    fun `no source claims the app can open the document itself`() {
        for (source in allSources()) {
            val text = source.asSentence().lowercase()
            assertTrue(
                !text.contains("tap") && !text.contains("in-app") && !text.contains("we fetch"),
                "a source must be a place the reader goes, not a button: ${source.asSentence()}",
            )
        }
    }

    // ----------------------------------------------------------------------- helpers

    private fun allSources(): List<VerificationSource> = buildList {
        VerificationSources.forEveryRatio().values.forEach { addAll(it) }
        VerificationSources.rulesWithSources().values.forEach { addAll(it) }
    }

    private fun assertWellFormed(what: String, source: VerificationSource) {
        assertTrue(source.title.isNotBlank(), "$what: blank title")
        assertTrue(source.where.length > 20, "$what: 'where' must name a section, not a document")
        assertTrue(source.whatItAnswers.isNotBlank(), "$what: blank whatItAnswers")
    }

    private fun sourceFile(): File {
        // `ratios/VerificationSources.kt`, located relative to the module root the test runs in.
        val direct = File("src/main/kotlin/com/mia/app/domain/ratios/VerificationSources.kt")
        if (direct.exists()) return direct
        return File("domain/src/main/kotlin/com/mia/app/domain/ratios/VerificationSources.kt")
    }

    private fun literalsOf(file: File): List<String> =
        if (!file.exists()) emptyList() else LITERALS.findAll(file.readText()).map { it.groupValues[1] }.toList()

    private companion object {
        val LITERALS = Regex("\"([^\"\\\\]*(?:\\\\.[^\"\\\\]*)*)\"")
    }
}