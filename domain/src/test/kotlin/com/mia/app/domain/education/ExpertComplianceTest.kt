package com.mia.app.domain.education

import com.mia.app.domain.compliance.AdviceLanguage
import com.mia.app.domain.compliance.HonestyContract
import com.mia.app.domain.pattern.PatternCatalog
import com.mia.app.domain.ratios.RatioId
import com.mia.app.domain.redflag.fundamentals.FundamentalsFlagIds
import com.mia.app.domain.redflag.technical.TechnicalFlagIds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The machine-checked half of the curriculum's regulatory posture (plan.md §1.4 rule 1).
 *
 * This file walks **every string a reader can be shown** from the expert level and the
 * base-rate module — title, summary, body, and the mandatory `doesNotEstablish` — through both
 * guards. It is deliberately written against the module lists rather than the `Curriculum`
 * object, so the education content stays independently compilable and independently testable.
 *
 * It also pins the *grounding* of the content, which is the failure mode that actually
 * happens: a lesson whose `relatedPattern` names a pattern the catalogue does not have, or a
 * `relatedFlag` that no rule emits, looks perfectly valid in review and renders as a dead link.
 */
class ExpertComplianceTest {

    private val allModules: List<Module> = EXPERT_MODULES + BASE_RATE_MODULES

    private val allLessons: List<Lesson> = allModules.flatMap { it.lessons }

    // ---- the regulatory scan -----------------------------------------------------------------

    @Test
    fun `no lesson body or disclosure contains advice language`() {
        val failures = mutableListOf<String>()

        for (module in allModules) {
            val moduleTexts = listOf(module.title, module.summary)
            for (text in moduleTexts) {
                val hits = AdviceLanguage.findForbiddenPhrases(text)
                if (hits.isNotEmpty()) failures += "${module.id} module copy \"$text\": $hits"
            }
            for (lesson in module.lessons) {
                // Title + body + doesNotEstablish: everything a lesson screen can render.
                for (text in listOf(lesson.title, lesson.body, lesson.doesNotEstablish)) {
                    val hits = AdviceLanguage.findForbiddenPhrases(text)
                    if (hits.isNotEmpty()) failures += "${lesson.id}: \"$text\": $hits"
                }
            }
        }

        if (failures.isNotEmpty()) {
            fail("Regulatory violation in education copy:\n" + failures.joinToString("\n"))
        }
    }

    @Test
    fun `no lesson renders a score or a measurement as a probability claim`() {
        for (lesson in allLessons) {
            for (text in listOf(lesson.title, lesson.body, lesson.doesNotEstablish)) {
                try {
                    HonestyContract.requireNotProbabilityClaim(text)
                } catch (e: IllegalArgumentException) {
                    fail("${lesson.id}: ${e.message}")
                }
            }
        }
    }

    @Test
    fun `the guards are live over this content, not vacuous`() {
        // If the scanner ever silently stopped matching, every test above would pass for the
        // wrong reason. Prove it is still live with a string this file does not ship.
        val hostile = "This is a guaranteed price target with a 80% chance of profit."
        assertTrue(
            AdviceLanguage.findForbiddenPhrases(hostile).isNotEmpty(),
            "AdviceLanguage must still flag a hostile string",
        )
        assertTrue(
            runCatching { HonestyContract.requireNotProbabilityClaim(hostile) }.isFailure,
            "HonestyContract must still reject a hostile string",
        )

        // …and prove the curriculum is on the right side of both.
        val lessonsChecked = allLessons.size
        assertTrue(lessonsChecked >= 40, "expected the full curriculum, walked $lessonsChecked lessons")
        allLessons.forEach { lesson ->
            assertTrue(AdviceLanguage.isClean(lesson.body), lesson.id)
        }
    }

    // ---- structural guarantees the schema depends on -----------------------------------------

    @Test
    fun `every lesson states what it does not establish`() {
        for (lesson in allLessons) {
            assertTrue(
                lesson.doesNotEstablish.isNotBlank(),
                "${lesson.id} has a blank doesNotEstablish — the field is mandatory",
            )
            assertTrue(
                lesson.doesNotEstablish.length >= 40,
                "${lesson.id}: doesNotEstablish is too short to be a real disclaimer " +
                    "(${lesson.doesNotEstablish.length} chars)",
            )
            assertTrue(
                lesson.doesNotEstablish.trim().startsWith("It does not establish"),
                "${lesson.id}: doesNotEstablish must be phrased as a negated claim",
            )
        }
    }

    @Test
    fun `every lesson body is real prose with paragraphs`() {
        for (lesson in allLessons) {
            assertTrue(lesson.id.isNotBlank(), "a lesson with a blank id cannot be linked to")
            assertTrue(lesson.title.isNotBlank(), "${lesson.id} has a blank title")
            assertTrue(lesson.body.length >= 300, "${lesson.id}: body is a stub (${lesson.body.length} chars)")
            assertTrue(
                "\n\n" in lesson.body,
                "${lesson.id}: body should separate paragraphs with a blank line",
            )
        }
    }

    @Test
    fun `lesson and module ids are unique`() {
        val lessonIds = allLessons.map { it.id }
        assertEquals(lessonIds.size, lessonIds.toSet().size, "duplicate lesson id in the curriculum")

        val moduleIds = allModules.map { it.id }
        assertEquals(moduleIds.size, moduleIds.toSet().size, "duplicate module id in the curriculum")
    }

    @Test
    fun `expert modules are expert and the base rate module is the foundation`() {
        assertEquals(
            EXPERT_MODULES.size,
            EXPERT_MODULES.count { it.level == Level.EXPERT },
            "everything in ExpertLessons.kt is expert level",
        )
        assertTrue(
            BASE_RATE_MODULES.all { it.level == Level.BEGINNER },
            "the base-rate module is the foundation lesson and is not expert content",
        )
        assertTrue(
            EXPERT_MODULES.all { it.lessons.size in 2..5 },
            "each expert module carries 2-5 lessons: " +
                EXPERT_MODULES.joinToString { "${it.id}=${it.lessons.size}" },
        )
        assertTrue(
            EXPERT_MODULES.all { it.summary.isNotBlank() },
            "a module with a blank summary renders as a bare title",
        )
    }

    // ---- grounding: the cross-references must resolve ----------------------------------------

    @Test
    fun `lesson ids are dotted and name the level they sit in`() {
        // The schema agent's convention (CurriculumStructureTest): a lesson id is the only
        // identifier a deep link or a failure message ever shows, so it must read on its own as
        // `level.topic.term` and must agree with the module it is filed under.
        for (module in allModules) {
            for (lesson in module.lessons) {
                val segments = lesson.id.split(".")
                assertTrue(segments.size >= 3, "${lesson.id} is not dotted as level.topic.term")
                assertTrue(segments.all { it.isNotBlank() }, "${lesson.id} has an empty segment")
                assertEquals(
                    module.level.name.lowercase(),
                    segments.first(),
                    "${lesson.id} is filed under ${module.level} but names level segment " +
                        "\"${segments.first()}\"",
                )
            }
        }
    }

    @Test
    fun `every relatedPattern is a name the pattern catalogue knows`() {
        val referenced = allLessons.mapNotNull { it.relatedPattern }.distinct().sorted()
        assertTrue(referenced.isNotEmpty(), "expected the expert level to cross-reference patterns")
        for (name in referenced) {
            assertTrue(
                PatternCatalog.byName(name) != null,
                "\"$name\" is not a PatternCatalog name; the catalogue holds: " +
                    PatternCatalog.all.joinToString { it.name },
            )
        }
    }

    @Test
    fun `every relatedRatio is a RatioId name`() {
        val referenced = allLessons.mapNotNull { it.relatedRatio }.distinct().sorted()
        assertTrue(referenced.isNotEmpty(), "expected the expert level to cross-reference ratios")
        val known = RatioId.entries.map { it.name }.toSet()
        for (name in referenced) {
            assertTrue(name in known, "\"$name\" is not a RatioId; known: ${known.sorted()}")
        }
    }

    @Test
    fun `every relatedFlag is an id a rule can actually emit`() {
        val referenced = allLessons.mapNotNull { it.relatedFlag }.distinct().sorted()
        assertTrue(referenced.isNotEmpty(), "expected the expert level to cross-reference flags")

        val technical = listOf(
            TechnicalFlagIds.DEATH_CROSS,
            TechnicalFlagIds.RSI_OVERBOUGHT,
            TechnicalFlagIds.RSI_OVERSOLD,
            TechnicalFlagIds.DISTRIBUTION_DAY,
            TechnicalFlagIds.AT_52WEEK_LOW_WITH_DECLINING_OBV,
            TechnicalFlagIds.DELIVERY_COLLAPSE,
        )
        val fundamentals = listOf(
            FundamentalsFlagIds.CFO_NEGATIVE_WHILE_PAT_POSITIVE,
            FundamentalsFlagIds.RECEIVABLES_GROWING_FASTER_THAN_REVENUE,
            FundamentalsFlagIds.INVENTORY_DAYS_RISING,
            FundamentalsFlagIds.OTHER_INCOME_AS_PCT_OF_PAT,
            FundamentalsFlagIds.DEBT_TO_EQUITY_SPIKE,
            FundamentalsFlagIds.INTEREST_COVERAGE_BELOW_2,
            FundamentalsFlagIds.GOODWILL_ABOVE_20PCT_ASSETS,
            FundamentalsFlagIds.PROMOTER_PLEDGING,
            FundamentalsFlagIds.LOW_PE_WITH_FALLING_RETURNS,
            FundamentalsFlagIds.PE_RISING_WHILE_EARNINGS_FALL,
        )
        val known = (technical + fundamentals).toSet()
        for (id in referenced) {
            assertTrue(id in known, "\"$id\" is not a technical or fundamentals flag id")
        }
    }
}