package com.mia.app.domain.education

import com.mia.app.domain.pattern.PatternCatalog
import com.mia.app.domain.ratios.RatioId
import com.mia.app.domain.redflag.fundamentals.FundamentalsFlagIds
import com.mia.app.domain.redflag.technical.TechnicalFlagIds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The curriculum **schema's** invariants — the rules a lesson file has to obey before its prose is
 * even looked at.
 *
 * ### Why this test owns synthetic fixtures rather than the real curriculum
 * The lesson files and this test land in different workstreams. If the assertions ran only over
 * whatever the content files had registered, the schema would be unenforced until they merged, and
 * then a schema failure would look like a content failure. Registering known fixtures here keeps
 * the contract testable on its own — and, because the fixtures are registered through the public
 * [Curriculum.register] path, it also tests the registration contract itself.
 *
 * ### What a violation costs
 * Every rule below has a real failure behind it: a blank [Lesson.doesNotEstablish] ships a lesson
 * that teaches without the sentence that stops it being read as advice (plan.md §1.4 rule 3); a
 * duplicated lesson id makes a deep link resolve to the wrong lesson; a misspelled
 * [Lesson.relatedPattern] renders a dead cross-reference; and a reordered registry teaches the hub
 * out of sequence.
 */
class CurriculumStructureTest {

    // ------------------------------------------------------------------ the fixtures

    /**
     * Three modules, declared **expert first**, so anything that fails to sort by level fails here.
     *
     * The ids are namespaced under `schema-fixture.` for the reason rule 3 of the registration
     * contract gives: the registry is process-wide, and a fixture id must not collide with a real
     * module's.
     */
    private val fixtures: List<Module> = listOf(
        Module(
            id = "schema-fixture.expert",
            level = Level.EXPERT,
            title = "Synthetic expert module",
            summary = "Fixture module: exists to be measured, not to be read.",
            lessons = listOf(
                Lesson(
                    id = "expert.regime.death-cross",
                    title = "What a death cross measures",
                    body = "A death cross is two averages crossing.\n\n" +
                        "The fixture body is two paragraphs, so the \\n\\n convention is exercised.",
                    doesNotEstablish = "It says the averages crossed; it does not say what the " +
                        "stock does next, and the app never renders it as a timing signal.",
                    relatedFlag = TechnicalFlagIds.DEATH_CROSS,
                    relatedPattern = "Head & Shoulders",
                ),
            ),
        ),
        Module(
            id = "schema-fixture.beginner",
            level = Level.BEGINNER,
            title = "Synthetic beginner module",
            summary = "Fixture module: exists to be measured, not to be read.",
            lessons = listOf(
                Lesson(
                    id = "beginner.pattern.double-top",
                    title = "Reading a double top",
                    body = "Two peaks at similar heights make a double top.",
                    doesNotEstablish = "It names a shape that has already printed; it establishes " +
                        "no level, no duration and no outcome.",
                    relatedPattern = "Double Top",
                ),
                Lesson(
                    id = "beginner.redflag.coverage",
                    title = "Why coverage below two is flagged",
                    body = "EBIT divided by interest is the coverage ratio.\n\nBelow two, the " +
                        "flag fires — that is all it says.",
                    doesNotEstablish = "A fired flag says the ratio was measured and found below " +
                        "two. It does not say the company will default.",
                    relatedFlag = FundamentalsFlagIds.INTEREST_COVERAGE_BELOW_2,
                    relatedRatio = "INTEREST_COVERAGE",
                ),
            ),
        ),
        Module(
            id = "schema-fixture.intermediate",
            level = Level.INTERMEDIATE,
            title = "Synthetic intermediate module",
            summary = "Fixture module: exists to be measured, not to be read.",
            lessons = listOf(
                Lesson(
                    id = "intermediate.ratios.pe-vs-sector",
                    title = "Reading P/E against the sector median",
                    body = "One company's P/E is a number; against its sector it is a comparison.",
                    doesNotEstablish = "A low P/E does not establish that the stock is cheap. A " +
                        "high multiple does not establish that it is expensive.",
                    relatedRatio = "PE_VS_SECTOR_MEDIAN",
                ),
            ),
        ),
    )

    /**
     * Installs the fixtures. Called by every test rather than once in `@BeforeTest` so a single
     * test can be run in isolation, and safe to repeat because registration replaces by id.
     */
    private fun install(): List<Module> {
        Curriculum.register(fixtures)
        return Curriculum.modules.filter { it.id.startsWith(FIXTURE_PREFIX) }
    }

    // ------------------------------------------------------------------ lesson content

    @Test
    fun `every lesson states a title, a body and what it does not establish`() {
        // The three fields that cannot be defaulted. [Lesson.doesNotEstablish] is the one that
        // matters: it is what keeps a well-explained lesson from reading as a conclusion, so a
        // lesson that ships without one is a compliance failure, not a missing nicety.
        val installed = install()
        assertTrue(installed.isNotEmpty(), "the fixtures did not register")
        for (module in installed) {
            assertTrue(module.title.isNotBlank(), "${module.id} has a blank title")
            assertTrue(module.summary.isNotBlank(), "${module.id} has a blank summary")
            assertTrue(module.lessons.isNotEmpty(), "${module.id} has no lessons")
            for (lesson in module.lessons) {
                assertTrue(lesson.title.isNotBlank(), "${lesson.id} has a blank title")
                assertTrue(lesson.body.isNotBlank(), "${lesson.id} has a blank body")
                assertTrue(
                    lesson.doesNotEstablish.isNotBlank(),
                    "${lesson.id} does not say what it does not establish (plan.md §1.4 rule 3)",
                )
                assertTrue(
                    lesson.doesNotEstablish.length > 30,
                    "${lesson.id}'s doesNotEstablish is a token " +
                        "\"${lesson.doesNotEstablish}\" rather than a sentence",
                )
            }
        }
    }

    // ------------------------------------------------------------------------ lesson ids

    @Test
    fun `lesson ids are unique across every registered module`() {
        install()
        val byId = Curriculum.modules.flatMap { it.lessons }.groupBy { it.id }
        val duplicates = byId.filterValues { it.size > 1 }.keys
        assertTrue(duplicates.isEmpty(), "duplicate lesson ids: $duplicates")
    }

    @Test
    fun `lesson ids are dotted and start with the module's level`() {
        // The first segment has to be a level name so a lesson id is readable on its own — the
        // only identifier a deep link, a test failure message or a bookmark will ever show. The
        // second half of the rule is that it must agree with the module the lesson sits in, or a
        // lesson could be filed under one level and named for another.
        val installed = install()
        for (module in installed) {
            for (lesson in module.lessons) {
                val segments = lesson.id.split(".")
                if (segments.size < 3) {
                    fail("${lesson.id} is not dotted as level.topic.term")
                }
                assertTrue(
                    segments.all { it.isNotBlank() },
                    "${lesson.id} has an empty segment",
                )
                val level = segments.first()
                val expected = module.level.name.lowercase()
                assertEquals(
                    expected,
                    level,
                    "${lesson.id} is filed under ${module.level} but names level segment \"$level\"",
                )
                assertNotNull(
                    Level.entries.firstOrNull { it.name.lowercase() == level },
                    "${lesson.id} starts with \"$level\", which is not a Level",
                )
            }
        }
    }

    // ------------------------------------------------------------- cross-references

    @Test
    fun `relatedPattern must name a row the catalogue actually has`() {
        // The reference is a string so `:domain`'s education package stays free of an import cycle
        // with the pattern package. That only works if the string is checked, because a typo would
        // otherwise render a cross-reference that resolves to nothing.
        val installed = install()
        val referenced = installed.flatMap { it.lessons }.mapNotNull { it.relatedPattern }
        assertTrue(referenced.isNotEmpty(), "no fixture exercises relatedPattern")
        for (name in referenced) {
            assertNotNull(
                PatternCatalog.byName(name),
                "\"$name\" is not a pattern catalogue name",
            )
        }
        // …and the check has teeth: the obvious typos must fail it.
        assertNull(PatternCatalog.byName("Double Topp"))
        assertNull(PatternCatalog.byName("head and shoulders 2"))
    }

    @Test
    fun `relatedRatio must name a real RatioId`() {
        val installed = install()
        val referenced = installed.flatMap { it.lessons }.mapNotNull { it.relatedRatio }
        assertTrue(referenced.isNotEmpty(), "no fixture exercises relatedRatio")
        for (name in referenced) {
            assertNotNull(
                RatioId.entries.firstOrNull { it.name == name },
                "\"$name\" is not a RatioId; the app renders ratio names from the enum, not from " +
                    "free text",
            )
        }
    }

    @Test
    fun `relatedFlag must name a fundamentals or technical flag id`() {
        // Two id vocabularies on purpose: the fundamentals tab and the technicals tab own separate
        // id sets, and a lesson about a chart measure must be able to point at either. They are
        // collected by reflection rather than written out as a literal list so a new flag id is
        // accepted the moment its constant ships, with no edit to this test.
        val installed = install()
        val referenced = installed.flatMap { it.lessons }.mapNotNull { it.relatedFlag }
        assertTrue(referenced.isNotEmpty(), "no fixture exercises relatedFlag")

        val known = buildSet {
            FundamentalsFlagIds::class.java.declaredFields
                .filter { it.type == String::class.java }
                .forEach { add(it.get(null) as String) }
            TechnicalFlagIds::class.java.declaredFields
                .filter { it.type == String::class.java }
                .forEach { add(it.get(null) as String) }
        }
        assertTrue(known.isNotEmpty(), "no flag ids were discovered by reflection")

        for (id in referenced) {
            assertTrue(
                id in known,
                "\"$id\" is not a FundamentalsFlagIds or TechnicalFlagIds constant",
            )
        }
    }

    // ------------------------------------------------------------------- registration

    @Test
    fun `modules are ordered by level, beginner first`() {
        // The fixtures are declared expert-first precisely so a registry that kept declaration
        // order would fail here rather than pass by luck.
        val installed = install()
        assertEquals(
            listOf(Level.BEGINNER, Level.INTERMEDIATE, Level.EXPERT),
            installed.map { it.level },
            "modules must be sorted BEGINNER → INTERMEDIATE → EXPERT",
        )
    }

    @Test
    fun `modulesFor returns one level in the same order as modules`() {
        val installed = install()
        for (level in Level.entries) {
            assertEquals(
                Curriculum.modules.filter { it.level == level },
                Curriculum.modulesFor(level),
                "modulesFor($level) disagrees with modules",
            )
        }
    }

    @Test
    fun `registering the same modules twice neither duplicates nor reorders them`() {
        // The idempotence half of the registration contract. Without it, a content object's
        // `init` plus a test's own `register` call would leave two copies of every module and the
        // curriculum would teach the same lesson twice.
        val first = install()
        val second = install()

        assertEquals(first.map { it.id }, second.map { it.id }, "re-registration changed the order")
        assertEquals(first.size, second.size, "re-registration duplicated modules")
        assertEquals(
            Curriculum.modules.map { it.id }.distinct().size,
            Curriculum.modules.size,
            "the registry holds the same module id twice",
        )
    }

    @Test
    fun `lesson lookup resolves a registered id and misses cleanly`() {
        install()
        val found = assertNotNull(
            Curriculum.lesson("beginner.pattern.double-top"),
            "a registered lesson id must resolve",
        )
        assertEquals("Reading a double top", found.title)
        assertNull(Curriculum.lesson("beginner.pattern.double-topp"), "a typo must not resolve")
        assertNull(Curriculum.lesson(""), "a blank id must not resolve")
    }

    private companion object {
        /** Every fixture id starts with this, so no fixture can claim a real module's id. */
        const val FIXTURE_PREFIX = "schema-fixture."
    }
}