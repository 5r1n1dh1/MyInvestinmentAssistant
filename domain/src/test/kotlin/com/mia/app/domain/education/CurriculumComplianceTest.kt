package com.mia.app.domain.education

import com.mia.app.domain.compliance.AdviceLanguage
import com.mia.app.domain.compliance.HonestyContract
import com.mia.app.domain.pattern.PatternCatalog
import com.mia.app.domain.ratios.RatioId
import com.mia.app.domain.redflag.fundamentals.FundamentalsRedFlagRules
import com.mia.app.domain.redflag.technical.TechnicalRedFlagRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The machine-checked half of the BEGINNER and INTERMEDIATE curriculum.
 *
 * ### Why this test exists at all
 *
 * An education hub is the one surface in this app where a sentence can be both fluent and
 * unlawful. Nothing else in the codebase renders prose a reader is meant to absorb, so nothing
 * else can turn a factual description into advice by accident. Every rule here exists because that
 * accident is cheap to make and expensive to find in a review.
 *
 * ### Why it reads the two module lists directly rather than [Curriculum.modules]
 *
 * The curriculum is composed from three levels and the expert level is authored separately, so a
 * test that walked [Curriculum.modules] would fail whenever a sibling group was mid-flight and
 * would silently skip lessons if it were softened to tolerate that. Reading [BEGINNER_MODULES]
 * and [INTERMEDIATE_MODULES] makes this file's verdict depend only on the lessons it is about:
 * if a lesson in either file breaks the contract, this test fails on its own.
 *
 * ### What each test is guarding, in one line
 *
 * * advice language — no body, no `doesNotEstablish`, no title, no module summary survives
 *   [AdviceLanguage.findForbiddenPhrases];
 * * probability claims — none survives [HonestyContract.requireNotProbabilityClaim];
 * * sample sizes — any text that mentions a hit rate carries `n=` or the word "samples", which is
 *   what [HonestyContract.requireHitRateSampleSize] demands of the real UI labels;
 * * referential integrity — every `relatedRatio`, `relatedPattern` and `relatedFlag` names
 *   something that exists in this repository, resolved against the catalogue and the rule
 *   registries rather than against a hand-copied list;
 * * grounding — the figures the curriculum teaches are pinned here so a document and the code
 *   cannot drift apart silently.
 */
class CurriculumComplianceTest {

    private val modules: List<Module> = BEGINNER_MODULES + INTERMEDIATE_MODULES
    private val lessons: List<Lesson> = modules.flatMap { it.lessons }

    /** Every string in this curriculum that a reader can be shown. */
    private fun everyUserFacingText(): List<Pair<String, String>> = buildList {
        modules.forEach { module ->
            add("${module.id}/summary" to module.summary)
            add("${module.id}/title" to module.title)
            module.lessons.forEach { lesson ->
                add("${lesson.id}/title" to lesson.title)
                add("${lesson.id}/body" to lesson.body)
                add("${lesson.id}/doesNotEstablish" to lesson.doesNotEstablish)
            }
        }
    }

    // ---------------------------------------------------------------------------- compliance

    @Test
    fun `no curriculum string contains advice-shaped language`() {
        val violations = everyUserFacingText().flatMap { (where, text) ->
            AdviceLanguage.findForbiddenPhrases(text).map { "$where -> \"$it\" in: ${text.take(120)}" }
        }
        assertTrue(
            violations.isEmpty(),
            "Advice language in the curriculum:\n" + violations.joinToString("\n"),
        )
    }

    @Test
    fun `no curriculum string is rendered as a percentage of an outcome`() {
        val violations = everyUserFacingText().flatMap { (where, text) ->
            val lower = text.lowercase()
            HonestyContract.PROBABILITY_CLAIMS
                .filter { lower.contains(it) }
                .map { "$where -> \"$it\" in: ${text.take(120)}" }
        }
        assertTrue(
            violations.isEmpty(),
            "Probability claims in the curriculum:\n" + violations.joinToString("\n"),
        )
    }

    @Test
    fun `the production guards agree with the content scan`() {
        // The two tests above report every violation at once; this one proves the production
        // guards actually run over the same strings, so a change to either filter is caught.
        everyUserFacingText().forEach { (where, text) ->
            AdviceLanguage.requireClean(text)
            HonestyContract.requireNotProbabilityClaim(text)
            assertTrue(AdviceLanguage.isClean(text), where)
        }
    }

    @Test
    fun `a hit rate quoted in the curriculum always carries its sample size`() {
        // The real screen renders labels, so the real guard is the one that runs: any sentence that
        // states a hit rate as a percentage must pass HonestyContract.requireHitRateSampleSize,
        // which demands "n=" or "samples". Scoping it to the sentence matters — a lesson that
        // introduces the idea, or quotes a directional accuracy for a different reason, is not the
        // failure this guards against.
        val percentage = Regex("\\d+(\\.\\d+)?\\s?%")
        val sentence = Regex("(?<=[.?])\\s+|\\n")
        val offenders = everyUserFacingText().flatMap { (where, text) ->
            val flagged = sentence.split(text).filter {
                it.contains("hit rate", ignoreCase = true) && percentage.containsMatchIn(it)
            }
            if (flagged.isEmpty() || text.contains("n=") || text.contains("samples")) {
                emptyList()
            } else {
                flagged.map { "$where -> ${it.trim().take(120)}" }
            }
        }

        assertTrue(
            offenders.isEmpty(),
            "a hit rate without its sample size:\n" + offenders.joinToString("\n"),
        )

        // And the production guard agrees with the scan above: it is applied to the whole
        // user-facing string, exactly as the accuracy screen applies it to a rendered label, where
        // the sample size may be carried by the surrounding sentence.
        everyUserFacingText()
            .filter { (_, text) ->
                sentence.split(text).any {
                    it.contains("hit rate", ignoreCase = true) && percentage.containsMatchIn(it)
                }
            }
            .forEach { (_, text) -> HonestyContract.requireHitRateSampleSize(text) }
    }

    // ------------------------------------------------------------------------ referential ids

    @Test
    fun `every related ratio names a real RatioId`() {
        val known = RatioId.entries.map { it.name }.toSet()
        val referenced = lessons.mapNotNull { it.relatedRatio }
        val unknown = referenced.filterNot { it in known }.distinct()

        assertTrue(unknown.isEmpty(), "unknown RatioId(s) referenced by a lesson: $unknown")
        assertTrue(
            referenced.size >= 12,
            "expected the ratio drills and the sector lens to link ratios, found ${referenced.size}",
        )
    }

    @Test
    fun `every related pattern names a real catalogue entry`() {
        val known = PatternCatalog.all.map { it.name }.toSet()
        val referenced = lessons.mapNotNull { it.relatedPattern }
        val unknown = referenced.filterNot { it in known }.distinct()

        assertTrue(unknown.isEmpty(), "unknown pattern name(s) referenced by a lesson: $unknown")
        referenced.forEach { name ->
            assertNotNull(PatternCatalog.byName(name), "catalogue lookup failed for $name")
        }
    }

    @Test
    fun `every related flag names a real red-flag rule`() {
        val known = (
            FundamentalsRedFlagRules.ruleIds + TechnicalRedFlagRules.all().map { it.id }
            ).toSet()
        val referenced = lessons.mapNotNull { it.relatedFlag }
        val unknown = referenced.filterNot { it in known }.distinct()

        assertTrue(unknown.isEmpty(), "unknown flag id(s) referenced by a lesson: $unknown")
        assertTrue(referenced.size >= 6, "expected the red-flag school to link flags, found ${referenced.size}")
    }

    // ------------------------------------------------------------------------------- structure

    @Test
    fun `lesson ids are unique and namespaced to their level`() {
        val ids = lessons.map { it.id }
        assertEquals(ids.size, ids.distinct().size, "duplicate lesson id in: $ids")

        modules.forEach { module ->
            val prefix = module.level.name.lowercase() + "."
            module.lessons.forEach { lesson ->
                assertTrue(
                    lesson.id.startsWith(prefix),
                    "${lesson.id} is in a ${module.level} module but is not namespaced \"$prefix\"",
                )
            }
        }
    }

    @Test
    fun `module ids are unique and each module holds between two and five lessons`() {
        val ids = modules.map { it.id }
        assertEquals(ids.size, ids.distinct().size, "duplicate module id in: $ids")

        modules.forEach { module ->
            assertTrue(module.lessons.size in 2..5, "${module.id} holds ${module.lessons.size} lessons")
            assertTrue(module.title.isNotBlank(), "${module.id} has no title")
            assertTrue(module.summary.length >= 60, "${module.id} has a too-thin summary")
            assertTrue(module.summary.endsWith("."), "${module.id} summary should be a sentence")
        }
    }

    @Test
    fun `every lesson states what it does not establish, in its own words`() {
        lessons.forEach { lesson ->
            assertTrue(lesson.doesNotEstablish.isNotBlank(), "${lesson.id} has no doesNotEstablish")
            assertTrue(
                lesson.doesNotEstablish.length >= 80,
                "${lesson.id} doesNotEstablish is too short to be specific: " +
                    lesson.doesNotEstablish,
            )
            assertTrue(
                lesson.doesNotEstablish.contains("establish"),
                "${lesson.id} doesNotEstablish does not say what it fails to establish",
            )
            assertTrue(lesson.title.isNotBlank(), "${lesson.id} has no title")
        }

        val distinct = lessons.map { it.doesNotEstablish }.distinct()
        assertEquals(
            lessons.size,
            distinct.size,
            "two lessons share a doesNotEstablish, which means one of them did not write its own",
        )
    }

    @Test
    fun `every lesson body is two to four plain paragraphs`() {
        lessons.forEach { lesson ->
            val paragraphs = lesson.body.split("\n\n")
            assertTrue(
                paragraphs.size in 2..4,
                "${lesson.id} body has ${paragraphs.size} paragraphs",
            )
            assertTrue(paragraphs.none { it.isBlank() }, "${lesson.id} body has an empty paragraph")
        }
    }

    // ------------------------------------------------------------------------------- grounding

    @Test
    fun `the base-rate figures the curriculum teaches are the ones measured`() {
        val control = assertNotNull(lessons.firstOrNull { it.id == "beginner.two.numbers.control" })
        val text = control.body

        // docs/VERIFICATION.md §3 and README: the plan.md §6.1 control over 20,780 next-day
        // returns, reproduced on this repository's own 372-session fixture.
        listOf("20,780", "50.10%", "49.90%", "50.24%", "50.17%", "49.81%", "2,723", "5,565", "48.93%")
            .forEach { figure ->
                assertTrue(text.contains(figure), "the control lesson lost the figure $figure")
            }
    }

    @Test
    fun `the hit-rate figures the curriculum teaches carry the sample they came from`() {
        val texts = lessons.map { it.body + "\n" + it.doesNotEstablish }.joinToString("\n")
        listOf("16.45%", "153", "930", "33.3%", "10.0%", "4.9%").forEach { figure ->
            assertTrue(texts.contains(figure), "the measurement curriculum lost the figure $figure")
        }
    }

    @Test
    fun `the four corporate actions are quoted at the figures in the committed fixture`() {
        val text = assertNotNull(lessons.firstOrNull { it.id == "intermediate.corporate.actions.the_four" }).body
        listOf(
            "HDFCBANK", "2025-08-26", "1964.1", "973.4", "50.4%",
            "BAJFINANCE", "2025-06-16", "9331", "938", "89.9%",
            "KOTAKBANK", "2026-01-14", "2132.6", "421", "80.3%",
            "TATAMOTORS", "2025-10-14", "660.75", "395.45", "40.2%",
        ).forEach { figure ->
            assertTrue(text.contains(figure), "the corporate-action lesson lost the figure $figure")
        }
    }

    @Test
    fun `the claim that nine of forty-two catalogue rows are evaluated is still true`() {
        assertEquals(42, PatternCatalog.all.size, "the catalogue grew; update the lesson's count")
        assertEquals(9, PatternCatalog.evaluatedCount, "the evaluated count changed; update the lesson")
        assertEquals(
            0,
            PatternCatalog.all.count { it.family.label == "Candlestick" && it.isEvaluated },
            "a candlestick row became evaluated; the teaching-only claim is now wrong",
        )

        val lesson = assertNotNull(lessons.firstOrNull { it.id == "beginner.pattern.school.vocabulary" })
        assertTrue(lesson.body.contains("42"), "the vocabulary lesson lost the catalogue size")
        assertTrue(lesson.body.contains("Nine"), "the vocabulary lesson lost the evaluated count")
    }

    @Test
    fun `the ratio figures the drills quote are the ones in the golden fixtures`() {
        val text = assertNotNull(lessons.firstOrNull { it.id == "intermediate.ratio.drills.cards" }).body

        // docs/DATA-NOTES.md §4's five-row table, plus the derived figures an existing test pins.
        listOf(
            "63.0%", "51.8%", "14.0", "750,753", "296", "63.5354", "84", "93",
            "10.3%", "8.91%", "21.2", "1,580,194", "668", "8.7489", "88",
            "20.5%", "37.7%", "68.7", "400,898", "177", "1.95", "7.78", "222", "169",
            "0.4457",
        ).forEach { figure ->
            assertTrue(text.contains(figure), "the ratio drill lost the figure $figure")
        }
    }

    @Test
    fun `the tax module states no rate, threshold or exemption`() {
        val tax = modules.filter { it.id == "beginner.tax.starter" }.flatMap { it.lessons }
        assertTrue(tax.isNotEmpty(), "the tax module is empty")

        val percentage = Regex("\\d+(\\.\\d+)?\\s?%")
        tax.forEach { lesson ->
            val texts = listOf(lesson.body, lesson.doesNotEstablish)
            texts.forEach { text ->
                assertTrue(
                    !percentage.containsMatchIn(text),
                    "${lesson.id} states a percentage (${percentage.find(text)?.value}). " +
                        "Indian tax rates change and this app has no measured source for them",
                )
            }
            assertTrue(
                lesson.doesNotEstablish.contains("verify") ||
                    lesson.doesNotEstablish.contains("Income Tax Department") ||
                    lesson.doesNotEstablish.contains("change"),
                "${lesson.id} must tell the reader that the rules change and must be verified",
            )
        }
    }

    @Test
    fun `both curricula are as long as the brief requires`() {
        assertEquals(8, BEGINNER_MODULES.size, "the beginner level lost or gained a module")
        assertEquals(9, INTERMEDIATE_MODULES.size, "the intermediate level lost or gained a module")
        assertEquals(31, BEGINNER_MODULES.sumOf { it.lessons.size }, "beginner lesson count")
        assertEquals(35, INTERMEDIATE_MODULES.sumOf { it.lessons.size }, "intermediate lesson count")
        assertEquals(66, lessons.size, "total lesson count across both levels")
    }
}