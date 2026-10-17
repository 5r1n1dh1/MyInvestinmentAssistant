package com.mia.app.data.aggregate

import com.mia.app.data.fundamentals.ImportedRatiosSource
import com.mia.app.data.fundamentals.ScreenerFixtures
import com.mia.app.domain.model.Confidence
import com.mia.app.domain.model.Ratios
import com.mia.app.domain.model.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * plan.md §5.1's merge, run for the first time.
 *
 * ### Why this file is the point of the imported-source work
 * `FundamentalsAggregator` has been complete and unit-tested since it was written, and it has never
 * executed on real data, because there was only ever one side to merge. docs/VERIFICATION.md lists
 * `divergences()` being permanently empty as a *known capability gap* — which is the honest
 * framing, and also an admission that a whole feature is unreachable. These tests drive it with two
 * sides so the conflict path is exercised against a **real** screener.in page rather than only
 * against synthetic `Ratios`.
 *
 * ### What is and is not asserted
 * The merge arithmetic, precedence and 5% tolerance are `FundamentalsAggregator`'s own contract and
 * are asserted here against the live RELIANCE fixture so a change to the aggregator cannot quietly
 * re-break the rule §5.1 states. What is **not** asserted is that any imported number is correct:
 * nobody checked it, which is exactly why [ImportedRatiosSource] stamps it `Confidence.SINGLE`.
 */
class ImportedSourceMergeTest {

    private val asOf = ScreenerFixtures.AS_OF

    private fun screenIn(): Ratios = ScreenerFixtures.parse("RELIANCE").snapshot.ratios

    // -------------------------------------------------------------------------- the seam

    @Test
    fun `an import with no file leaves the second side empty and says so`() {
        val source = ImportedRatiosSource()
        assertNull(source.ratiosFor("RELIANCE", asOf))
        assertTrue(source.symbols().isEmpty())
        assertTrue(
            source.coverageNote().contains("nothing to disagree with"),
            source.coverageNote(),
        )
    }

    /**
     * An import makes `divergences()` reachable, which is the whole reason this exists.
     *
     * The imported P/E is set 8% away from the page's, past §5.1's 5% tolerance, so the merge must
     * classify it as a conflict and carry the alternate — the state `RealFundamentalsDataSource`
     * could not produce for any symbol before.
     */
    @Test
    fun `an import past the tolerance produces a conflict the UI can render`() {
        val source = ImportedRatiosSource()
        source.importFrom(
            """
            symbol,asOf,pe,roe
            RELIANCE,2026-10-02,22.9,8.91
            """.trimIndent(),
        )
        val merge = FundamentalsAggregator.merge(screenIn(), assertNotNull(source.ratiosFor("RELIANCE", asOf)), asOf)

        val pe = assertNotNull(merge.field(RatioFieldId.PE))
        assertEquals(Confidence.CONFLICT, pe.confidence, "8% apart is past §5.1's 5% tolerance")
        assertTrue(pe.hasConflict, "the field must carry the disagreement, not just a number")
        assertEquals(22.9, pe.other, "the alternate must survive to the UI")
        assertTrue(pe.divergencePercent!! > 5.0, "the delta the merge computed: ${pe.divergencePercent}")

        // plan.md §5.1: the primary is screener.in's own figure and the two are never averaged.
        assertEquals(21.2, merge.ratios.pe, "the primary must be the page's, not the midpoint")
        val conflict = assertNotNull(merge.conflicts.firstOrNull { it.field == "PE" })
        assertEquals(21.2, conflict.shown)
        assertEquals(22.9, conflict.alternate)
        assertTrue(
            conflict.chip().contains("vs") && conflict.chip().contains("apart"),
            "§5.1's chip renders both numbers and the gap: ${conflict.chip()}",
        )
    }

    /**
     * Inside the tolerance it is agreement, not a conflict chip.
     *
     * The negative case matters as much as the positive one: a UI that put a conflict chip on
     * agreeing numbers would train the reader to ignore it, which is worse than never having it.
     */
    @Test
    fun `an import inside the tolerance agrees and raises no conflict`() {
        val source = ImportedRatiosSource()
        source.importFrom("symbol,asOf,pe\nRELIANCE,2026-10-02,21.5\n")
        val merge = FundamentalsAggregator.merge(screenIn(), assertNotNull(source.ratiosFor("RELIANCE", asOf)), asOf)
        assertTrue(merge.conflicts.isEmpty(), "unexpected conflicts: ${merge.conflicts}")
        val pe = assertNotNull(merge.field(RatioFieldId.PE))
        assertEquals(Confidence.AGREED, pe.confidence)
        assertFalse(pe.hasConflict)
        assertEquals(21.2, merge.ratios.pe)
    }

    // ------------------------------------------------------------------- honesty rules

    /**
     * `AGREED` is a claim about a comparison that was actually performed.
     *
     * The import has been compared against nothing until the merge runs, so its own confidence is
     * `SINGLE` — which is what stops a second source from arriving pre-agreed with the first.
     */
    @Test
    fun `an imported snapshot is SINGLE and never AGREED or CONFLICT`() {
        val source = ImportedRatiosSource()
        val imported = source.importFrom("symbol,asOf,pe\nRELIANCE,2026-10-02,22.9\n")
        assertEquals(Confidence.SINGLE, imported.ratios.confidence)
        assertEquals(Source.SCREENER_IN, imported.ratios.source, "the merged side's own provenance")
        assertEquals("RELIANCE", imported.ratios.symbol)
        assertEquals(asOf, imported.ratios.asOf)
    }

    /**
     * A blank cell is missing data, and a zero is a value.
     *
     * The file carries `dividendYield` as `-`, the same placeholder screener.in prints. Coercing it
     * to `0.0` would report that the company pays no dividend, and downstream a zero denominator is
     * a division by zero rather than an absence.
     */
    @Test
    fun `a placeholder cell is absent and never zero`() {
        val source = ImportedRatiosSource()
        val imported = source.importFrom(
            "symbol,asOf,pe,dividendYield,roe\nRELIANCE,2026-10-02,22.9,-,8.91\n",
        )
        assertNull(imported.ratios.dividendYield)
        assertEquals(22.9, imported.ratios.pe)
        assertTrue(imported.fieldErrors.isEmpty(), "a placeholder is not an error: ${imported.fieldErrors}")
    }

    /**
     * A column that does not parse is **reported**, not silently dropped.
     *
     * A null and a typo look identical in the merged output, and only one of them is a bug in the
     * file. The [ImportedRatiosSource.Imported.fieldErrors] list is what tells them apart, which is
     * why it exists rather than the value being quietly dropped.
     */
    @Test
    fun `an unparseable number is reported as an error and the field stays absent`() {
        val source = ImportedRatiosSource()
        val imported = source.importFrom("symbol,asOf,pe,roe\nRELIANCE,2026-10-02,twenty-two,8.91\n")
        assertNull(imported.ratios.pe)
        assertEquals(8.91, imported.ratios.roe)
        assertEquals(1, imported.fieldErrors.size, "${imported.fieldErrors}")
        assertTrue(imported.fieldErrors.single().contains("not a number"), imported.fieldErrors.single())
    }

    /**
     * No date, no import.
     *
     * §5.1 shows both numbers of a conflict and lets the reader judge them, which needs dates.
     * Defaulting to today would make a three-year-old spreadsheet look current — plan.md §10's
     * "fastest way to lose trust".
     */
    @Test
    fun `a file with no date is rejected rather than defaulted to today`() {
        val source = ImportedRatiosSource()
        val failure = assertFailsWith<IllegalArgumentException> {
            source.importFrom("symbol,pe\nRELIANCE,22.9\n")
        }
        assertTrue(failure.message!!.contains("asOf"), failure.message!!)
        assertTrue(source.symbols().isEmpty(), "a rejected file must not be stored")
    }

    @Test
    fun `a file with no symbol is rejected`() {
        val source = ImportedRatiosSource()
        val failure = assertFailsWith<IllegalArgumentException> {
            source.importFrom("asOf,pe\n2026-10-02,22.9\n")
        }
        assertTrue(failure.message!!.contains("symbol"), failure.message!!)
    }

    @Test
    fun `a future date is rejected because a snapshot is not a forecast`() {
        val source = ImportedRatiosSource()
        assertFailsWith<IllegalArgumentException> {
            source.import("RELIANCE", ScreenerFixtures.AS_OF.plusYears(1), Ratios(
                symbol = "RELIANCE",
                sector = com.mia.app.domain.model.Sector.OTHER,
                asOf = ScreenerFixtures.AS_OF,
                source = Source.SCREENER_IN,
                confidence = Confidence.SINGLE,
            ))
        }
    }

    /** An imported file must not claim a sector the app never resolved (plan.md §7.1). */
    @Test
    fun `an imported snapshot carries an unresolved sector`() {
        val source = ImportedRatiosSource()
        val imported = source.importFrom("symbol,asOf,pe\nRELIANCE,2026-10-02,22.9\n")
        assertEquals(
            com.mia.app.domain.model.Sector.OTHER,
            imported.ratios.sector,
            "plan.md §7.1: default to OTHER and never guess",
        )
    }

    // ------------------------------------------------------------------------ formats

    @Test
    fun `json and csv produce the same snapshot`() {
        val csv = ImportedRatiosSource().importFrom(
            "symbol,asOf,pe,roe,roce,pb,dividendYield,marketCap,operatingMargin\n" +
                "RELIANCE,2026-10-02,22.9,8.91,10.3,1.75,0.35,1580194.0,15.4\n",
        )
        val json = ImportedRatiosSource().importFrom(
            """
            {"symbol": "RELIANCE", "asOf": "2026-10-02", "pe": 22.9, "roe": 8.91, "roce": 10.3,
             "pb": 1.75, "dividendYield": 0.35, "marketCap": 1580194.0, "operatingMargin": 15.4}
            """.trimIndent(),
        )
        assertEquals(csv.ratios, json.ratios)
    }

    /** Indian-format numbers are how a spreadsheet actually holds them. */
    @Test
    fun `indian thousands separators are accepted in both formats`() {
        val csv = ImportedRatiosSource().importFrom("symbol,asOf,marketCap\nRELIANCE,2026-10-02,\"15,80,194\"\n")
        assertEquals(1580194.0, csv.ratios.marketCap)
        val json = ImportedRatiosSource().importFrom(
            "{\"symbol\":\"RELIANCE\",\"asOf\":\"2026-10-02\",\"marketCap\":\"15,80,194\"}",
        )
        assertEquals(1580194.0, json.ratios.marketCap)
    }

    /**
     * A nested JSON value is rejected rather than half-read.
     *
     * A flat snapshot is the entire documented shape, and silently dropping a nested object would
     * leave the reader believing a figure was imported when it was not.
     */
    @Test
    fun `a nested json value is rejected with the reason`() {
        val source = ImportedRatiosSource()
        val failure = assertFailsWith<IllegalArgumentException> {
            source.importFrom("""{"symbol":"RELIANCE","asOf":"2026-10-02","ratios":{"pe":22.9}}""")
        }
        assertTrue(failure.message!!.contains("flat object"), failure.message!!)
    }

    @Test
    fun `comments and blank lines are ignored`() {
        val imported = ImportedRatiosSource().importFrom(
            """
            # exported from a spreadsheet
            symbol,asOf,pe

            RELIANCE,2026-10-02,22.9
            """.trimIndent(),
        )
        assertEquals(22.9, imported.ratios.pe)
    }

    // --------------------------------------------------------------------- the note

    /**
     * The coverage note says the figures were not checked and that the import is session-only.
     *
     * plan.md §12's coverage panel exists to name what the app did not do. An imported number is the
     * largest such thing on the tab, so the note states it rather than leaving the reader to assume
     * every number on screen came from a source the app fetched.
     */
    @Test
    fun `the coverage note names the scope and the un-verified nature`() {
        val source = ImportedRatiosSource()
        source.importFrom("symbol,asOf,pe\nRELIANCE,2026-10-02,22.9\n")
        val note = source.coverageNote()
        assertTrue(note.contains("RELIANCE"), note)
        assertTrue(note.contains("not checked by this app"), note)
        assertTrue(note.contains("neither is"), "§5.1's no-averaging rule must be stated: $note")
        assertTrue(note.contains("session"), "an in-memory import must not look durable: $note")
    }

    /**
     * The merge still reports the fields only one side had.
     *
     * An import carrying two fields must not make the other sixteen look agreed; §5.1's per-field
     * merge is the whole reason the seam exists.
     */
    @Test
    fun `fields only the page had are reported as single-sourced`() {
        val source = ImportedRatiosSource()
        source.importFrom("symbol,asOf,pe\nRELIANCE,2026-10-02,22.9\n")
        val merge = FundamentalsAggregator.merge(screenIn(), assertNotNull(source.ratiosFor("RELIANCE", asOf)), asOf)
        assertTrue(
            merge.singleSourced.contains(RatioFieldId.MARKET_CAP),
            "the import carried no market cap: ${merge.singleSourced}",
        )
        assertTrue(merge.singleSourced.contains(RatioFieldId.ROCE), "${merge.singleSourced}")
        // And the page's own figures survive untouched.
        assertEquals(1_580_194.0, merge.ratios.marketCap)
    }

    /**
     * A file carrying one field must not acquire the other sixteen.
     *
     * Every `Ratios` field not named by the file stays `null` — never `0.0`, never derived from
     * something else in the row. This is the same rule the scraper path follows and the reason the
     * two sides of a merge are comparable at all.
     */
    @Test
    fun `no field is invented from a field the file did not carry`() {
        val imported = ImportedRatiosSource().importFrom("symbol,asOf,pe\nRELIANCE,2026-10-02,22.9\n")
        assertEquals(22.9, imported.ratios.pe)
        assertNull(imported.ratios.roe)
        assertNull(imported.ratios.roce)
        assertNull(imported.ratios.pb)
        assertNull(imported.ratios.pat)
        assertNull(imported.ratios.fcf)
        assertNull(imported.ratios.cfoToOp)
        assertNull(imported.ratios.grossMargin)
        assertNull(imported.ratios.evToEbitda)
        assertNull(imported.ratios.marketCap)
    }
}