package com.mia.app.domain.education

/**
 * The three depths the education hub teaches at.
 *
 * ### Why a level is data rather than a route
 * The curriculum is stored in `:domain` as plain Kotlin precisely so it is JVM-testable, and a
 * route name is an `:app` concept. Keeping [label] here means the screen header, the module list
 * and the compliance scan all read one string, so a level cannot be spelled "Intermediate" on
 * one surface and "Intermediates" on another.
 *
 * The declaration order **is** the teaching order — see [Curriculum.modules] — so a level must
 * not be re-sorted later in the pipeline.
 */
enum class Level(val label: String) {
    BEGINNER("Beginner"), INTERMEDIATE("Intermediate"), EXPERT("Expert")
}

/**
 * One teaching unit: what the reader knows before and after it, and — mandatory — what it does
 * not establish.
 *
 * ### Why [doesNotEstablish] has no default
 * The single honest answer to "what does this lesson prove?" is "nothing about your next trade",
 * and every lesson needs to say its **own** version of that. A default would let a lesson ship
 * without the sentence that stops a well-explained idea being read as a conclusion, which is the
 * failure plan.md §1.4 rule 3 exists to prevent. So it is a required constructor argument: a
 * lesson cannot be written without it.
 *
 * ### Why the cross-references are strings and not typed ids
 * [relatedPattern], [relatedRatio] and [relatedFlag] join this module to three vocabularies that
 * live in other packages (`PatternCatalog`, `RatioId`, the red-flag id constants) and are all
 * *names*, joined by normalised name. Referencing them by name keeps `:domain`'s education data
 * free of an import cycle with the pattern and red-flag packages, and `CurriculumStructureTest`
 * turns the string back into the real thing — a typo fails a test rather than rendering a dead
 * link. Each is nullable because most lessons teach a concept with no single ratio, pattern or
 * flag attached.
 */
data class Lesson(
    val id: String,                        // stable, e.g. "beginner.jargon.pe"
    val title: String,
    val body: String,                      // plain paragraphs; \n\n between
    val doesNotEstablish: String,          // MANDATORY: what this lesson does not prove
    val relatedPattern: String? = null,    // exact PatternCatalog name, e.g. "Head & Shoulders"
    val relatedRatio: String? = null,      // RatioId name, e.g. "ROCE"
    val relatedFlag: String? = null,       // flag id, e.g. "INTEREST_COVERAGE_BELOW_2"
)

/**
 * A named group of [lessons] at one [level].
 *
 * A module is the unit the UI opens, and the unit a lesson is reachable from, so its [id] is a
 * stable key like a lesson id's: renaming a title must not orphan a deep link or a bookmark.
 */
data class Module(
    val id: String,
    val level: Level,
    val title: String,
    val summary: String,
    val lessons: List<Lesson>,
)

/**
 * The registry the curriculum's content files plug into, and the only way to read it back.
 *
 * ### Why registration exists at all
 * The curriculum is content, and content grows in files written at different times by different
 * people. A single hard-coded `listOf(...)` in this object would mean every new lesson file edits
 * this one, which is a merge conflict waiting to happen and gives the schema owner a veto over
 * content. So the content files own their modules and **register** them here instead, and this
 * file stays the stable contract they are written against.
 *
 * ### The registration contract
 * A content file declares its modules and registers them from its own object's `init` block, so
 * the file is self-installing and this file is never edited:
 *
 * ```kotlin
 * internal object BeginnerLessons {
 *     val MODULES: List<Module> = listOf(Module("beginner.tour", Level.BEGINNER, "…", "…", lessons))
 *     init { Curriculum.register(MODULES) }
 * }
 * ```
 *
 * Rules that follow from [register] and that the tests enforce:
 *
 * 1. **Order.** [modules] is the registered modules sorted by [Level] — BEGINNER, INTERMEDIATE,
 *    EXPERT — and, within one level, in the order they were registered. Sorting is by level only,
 *    so a content file that is touched first cannot reorder the hub.
 * 2. **Replace, never duplicate.** [register] keys on [Module.id]: a module id already present is
 *    **replaced in place**, keeping its original position. Re-registering the same modules
 *    therefore cannot duplicate a row or move it, so a double registration in a test leaves the
 *    registry exactly as it found it. A module id is still claimed by whoever registers it first,
 *    so ids must be namespaced by file.
 * 3. **Process-wide.** The registry is a process-wide mutable singleton, so a test must not assert
 *    an exact `modules.size` without counting only the ids it registered itself.
 *
 * ### Why nothing is registered here
 * An empty registry with a documented registration path is honest about what this file is: the
 * schema. If this object also carried the content, the two would be inseparable and a content
 * edit would be a schema edit.
 */
object Curriculum {

    /**
     * Guards [registered] and [positionById]. Registration happens from whichever content object a
     * caller touches first, which is not guaranteed to be the main thread, so the registry is
     * synchronised rather than relying on that being true.
     */
    private val lock = Any()

    /** Declaration order, preserved across registrations. Never read outside the lock. */
    private val registered = ArrayList<Module>()

    /** Module id → index in [registered], so a repeat registration replaces in place. */
    private val positionById = HashMap<String, Int>()

    /**
     * Adds [groups] to the registry, flattening the groups in the order given.
     *
     * Per the contract above: a module whose id is already registered is replaced at its existing
     * position rather than appended, so registering twice is a no-op in both content and order.
     * That is what makes this safe to call from a content object's `init` and again from a test.
     */
    fun register(vararg groups: List<Module>) {
        synchronized(lock) {
            for (group in groups) {
                for (module in group) {
                    require(module.id.isNotBlank()) { "a module must have an id" }
                    val at = positionById[module.id]
                    if (at == null) {
                        positionById[module.id] = registered.size
                        registered += module
                    } else {
                        registered[at] = module
                    }
                }
            }
        }
    }

    /**
     * Every registered module, ordered by [Level] and then by declaration order within a level.
     *
     * The sort is by level ordinal alone and Kotlin's sort is stable, which is what keeps
     * declaration order inside a level — `CurriculumStructureTest` pins both halves rather than
     * trusting that a future refactor keeps them.
     */
    val modules: List<Module>
        get() = synchronized(lock) {
            registered.toList().sortedBy { it.level.ordinal }
        }

    /** The modules at [level], in the same order as [modules]. Empty for a level nothing registered. */
    fun modulesFor(level: Level): List<Module> = modules.filter { it.level == level }

    /** The lesson with this exact [id], or `null`. Ids are unique across the whole curriculum. */
    fun lesson(id: String): Lesson? =
        modules.asSequence().flatMap { it.lessons.asSequence() }.firstOrNull { it.id == id }
}