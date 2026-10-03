package com.mia.app.domain.pattern

/**
 * The catalogue of chart patterns this app can talk about, and which of them it can look for.
 *
 * ### The question this exists to answer
 * *"Why did nothing match?"* has three very different answers — the pattern scored below the
 * gate, the pattern could not be evaluated on this data, or **the app does not look for this
 * pattern at all** — and a reader shown only the patterns that matched can never tell them apart.
 *
 * So the catalogue is deliberately larger than the detector set. Every textbook pattern gets a row
 * carrying [PatternSpec.detectionStatus], and a `NOT_IMPLEMENTED` row says so in words rather than
 * being omitted. Omission would read as "checked, found none", which is a claim about the data
 * rather than a fact about the software.
 *
 * ### Why a name is the join key
 * [PatternSpec.name] is the canonical display name and matches `Match.name` exactly for every
 * evaluated pattern. No new id type was introduced because `PatternIds.of` already keys on the
 * normalised name, so a catalogue row and a scan result join with no mapping table to keep in sync
 * — and a rename that desynchronised the two would fail `PatternCatalogTest` rather than silently
 * produce an empty page.
 */
object PatternCatalog {

    /** Every row, evaluated patterns first. */
    val all: List<PatternSpec> = PatternCatalogData.ALL

    /** Rows for the patterns this app actually evaluates on real data. */
    val evaluated: List<PatternSpec> = all.filter { it.isEvaluated }

    /** Rows for the patterns this app names but does not yet look for. */
    val notEvaluated: List<PatternSpec> = all.filter { !it.isEvaluated }

    /** Rows carrying an authored idealised shape, which today is every evaluated row. */
    val withShape: List<PatternSpec> = all.filter { it.hasShape }

    private val byName: Map<String, PatternSpec> =
        all.associateBy { PatternIds.normalise(it.name) }

    /**
     * The row for [name], or `null`.
     *
     * Matching reuses [PatternIds.normalise] — lowercase, alphanumerics only — so a lookup succeeds
     * on the same terms a scan result joins on. That is what makes `Head & Shoulders`,
     * `head and shoulders` and `HEAD&SHOULDERS` the same row, which is required rather than
     * cosmetic: `PatternIds` treats them as one pattern, so the catalogue must too.
     */
    fun byName(name: String): PatternSpec? = byName[PatternIds.normalise(name.trim())]

    /** [byName] but for a name that is known to be evaluable. `null` when absent or not evaluated. */
    fun evaluatedByName(name: String): PatternSpec? = byName(name)?.takeIf { it.isEvaluated }

    /** Rows for [family], in catalogue order. */
    fun byFamily(family: PatternFamily): List<PatternSpec> = all.filter { it.family == family }

    /**
     * How many of the 14 detectable names this catalogue knows about.
     *
     * Asserted against `PatternIds.CANONICAL_NAMES` by test: the catalogue and the accuracy table
     * must describe the same fourteen, or a scan result would join to nothing.
     */
    val evaluatedCount: Int get() = evaluated.size
}