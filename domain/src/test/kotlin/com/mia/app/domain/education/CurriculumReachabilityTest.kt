package com.mia.app.domain.education

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Every module this repository **declares** is reachable through [Curriculum].
 *
 * ### Why this test exists
 *
 * The expert content (ten modules, 42 lessons) and the base-rate module (one module, 3 lessons)
 * were merged **declared but never registered**. `EXPERT_MODULES` and `BASE_RATE_MODULES` were
 * perfectly good top-level `val`s that nothing ever called `Curriculum.register` on, so they never
 * entered `Curriculum.modules` — and any screen reading the curriculum would have shown a
 * beginner-and-intermediate hub with the entire expert level silently missing.
 *
 * Nothing caught it, and nothing could have. The existing tests were each scoped to their own
 * file's list: `ExpertComplianceTest` walked `EXPERT_MODULES` directly, so it passed; the
 * structure test used synthetic fixtures; the compliance test read the two files that *did*
 * register. Every test was green and every one of them would have stayed green with the expert
 * level unreachable.
 *
 * That is the shape of the bug: **content that is declared is not content that is reachable**, and
 * the two properties are only distinguishable by asking about both. So this test is deliberately
 * blunt — it names the declared lists, and it asks the registry for them back.
 */
@DisplayName("declared curriculum content is reachable through the registry")
class CurriculumReachabilityTest {

    /**
     * The declared corpus, named explicitly.
     *
     * A hand-written list on purpose. Deriving it by reflection over the package would pass
     * vacuously if a file stopped declaring anything, and the whole failure mode here is a file
     * quietly not being wired. Naming the four lists means adding a fifth content file without
     * registering it fails this test.
     */
    private val declared: Map<String, List<Module>> = mapOf(
        "BeginnerLessons" to BEGINNER_MODULES,
        "IntermediateLessons" to INTERMEDIATE_MODULES,
        "ExpertLessons" to EXPERT_MODULES,
        "BaseRateLessons" to BASE_RATE_MODULES,
    )

    @Test
    fun `every declared module appears in the registry`() {
        // Reading each value first: registration rides on the property initialiser
        // (`.also { Curriculum.register(it) }`), so a list nobody has touched is a list nobody has
        // registered. Touching it here is what makes the assertion below meaningful.
        declared.values.forEach { modules -> modules.size }
        assertTrue(
            declared.values.none { it.isEmpty() },
            "a content file declares no modules — has its content been deleted?",
        )

        val registered = Curriculum.modules.associateBy { it.id }
        val missing = declared.flatMap { (name, modules) ->
            modules.filter { it.id !in registered }.map { "$name → ${it.id}" }
        }
        assertTrue(
            missing.isEmpty(),
            "declared but never registered, so a reader could never reach them: $missing",
        )
    }

    @Test
    fun `every declared lesson is reachable by id`() {
        declared.values.flatten().forEach { it.lessons.size } // force registration via initialiser
        val unreachable = declared.values.flatten()
            .flatMap { module -> module.lessons.map { module.id to it } }
            .filter { (_, lesson) -> Curriculum.lesson(lesson.id) == null }
            .map { (_, lesson) -> lesson.id }
        assertTrue(
            unreachable.isEmpty(),
            "lessons whose id does not resolve through Curriculum.lesson(): $unreachable",
        )
    }

    @Test
    fun `the registry holds the whole declared corpus and nothing else`() {
        declared.values.flatten().forEach { it.lessons.size }
        val declaredIds = declared.values.flatten().map { it.id }.toSet()
        val registeredIds = Curriculum.modules.map { it.id }.toSet()

        // Equality both ways, on purpose. "Everything declared is registered" catches the bug this
        // test was written for; "nothing is registered that was not declared" catches the opposite
        // one — a stale module left in the registry by a test that registered a fixture and never
        // cleaned up, which would surface to a user as a module with no content behind it.
        assertEquals(
            declaredIds,
            registeredIds,
            "the registry and the declared corpus have diverged in both directions",
        )
    }

    @Test
    fun `every level carries modules and every module carries lessons`() {
        declared.values.flatten().forEach { it.lessons.size }
        for (level in Level.entries) {
            val atLevel = Curriculum.modulesFor(level)
            assertTrue(atLevel.isNotEmpty(), "no modules registered for ${level.label}")
            val empty = atLevel.filter { it.lessons.isEmpty() }.map { it.id }
            assertTrue(empty.isEmpty(), "modules with no lessons would render as dead rows: $empty")
        }
    }

    @Test
    fun `module order follows the level order, not registration order`() {
        declared.values.flatten().forEach { it.lessons.size }
        val ranks = Curriculum.modules.map { Level.entries.indexOf(it.level) }
        assertEquals(
            ranks.sorted(),
            ranks,
            "modules are out of level order; a reader would see Expert before Beginner " +
                "because of which file happened to initialise first",
        )
        assertNotNull(
            Curriculum.modulesFor(Level.BEGINNER).firstOrNull(),
            "the beginner level must come first — it is the only one guaranteed to be populated",
        )
    }
}