package com.mia.app.data.symbols

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The bundled NSE index cohorts, checked against the shipped files rather than a fixture.
 *
 * ### Why the shipped asset and not inline CSV
 * A hand-written CSV would only prove the parser reads the shape the test author believed NSE
 * publishes. What actually breaks is the file that is *not* what the author believed: a reordered
 * column, a quoted company name, a truncated download. So the completeness tests read the bundled
 * files - the same bytes the app ships - and assert against the numbers NSE publishes.
 *
 * The parser's refusals are tested against inline CSV instead, because each one needs a
 * deliberately broken input that no real file would contain.
 */
class IndexCohortParserTest {

    private fun load(cohort: IndexCohort): IndexCohortTable {
        val path = "/" + IndexCohort.DIRECTORY + cohort.assetName
        val stream = checkNotNull(javaClass.getResourceAsStream(path)) {
            "$path is not bundled; run tools/fetch-nse-index-cohorts.sh"
        }
        return IndexCohortParser.parse(cohort, stream)
    }

    private fun tables(): List<IndexCohortTable> = IndexCohort.entries.map { cohort -> load(cohort) }

    // ------------------------------------------------------------------ the shipped files

    @Test
    fun `every bundled list parses completely`() {
        for (cohort in IndexCohort.entries) {
            val table: IndexCohortTable = load(cohort)
            assertTrue(
                table.health.ok,
                "${cohort.assetName} did not parse: ${table.health.notes}",
            )
            assertTrue(
                table.isComplete,
                "${cohort.assetName} is incomplete: ${table.constituents.size} row(s), " +
                    "expected ${cohort.expectedSize}, ${table.rejectedCount} rejected. " +
                    "Notes: ${table.health.notes}",
            )
            assertEquals(
                emptyList(),
                table.health.notes,
                "${cohort.assetName} parsed cleanly, so it must carry no notes",
            )
        }
    }

    @Test
    fun `the three lists are the sizes NSE publishes`() {
        // Asserted per cohort, not as a total: a total cannot tell you which list went short.
        assertEquals(50, load(IndexCohort.NIFTY_50).constituents.size)
        assertEquals(150, load(IndexCohort.MIDCAP_150).constituents.size)
        assertEquals(251, load(IndexCohort.SMALLCAP_250).constituents.size)
    }

    @Test
    fun `the cohorts do not overlap`() {
        // NSE maintains the three as a partition. An overlap means either the files were fetched on
        // different dates or the partition assumption is wrong, and either would double-count the
        // same formation in a per-cohort hit rate with nothing on screen to show it.
        val byCohort: Map<IndexCohort, Set<String>> = tables().associate { table ->
            table.cohort to table.constituents.map { row -> row.symbol }.toSet()
        }
        val cohorts = byCohort.keys.toList()
        for (i in cohorts.indices) {
            for (j in i + 1 until cohorts.size) {
                val shared = byCohort.getValue(cohorts[i]).intersect(byCohort.getValue(cohorts[j]))
                assertTrue(
                    shared.isEmpty(),
                    "${cohorts[i]} and ${cohorts[j]} share ${shared.size} symbol(s): " +
                        shared.take(6),
                )
            }
        }
        assertEquals(451, byCohort.values.sumOf { it.size }, "total unique constituents")
    }

    @Test
    fun `a known symbol resolves to the cohort NSE puts it in`() {
        val reliance = load(IndexCohort.NIFTY_50).constituent("RELIANCE")
        assertNotNull(reliance, "RELIANCE is a NIFTY 50 constituent and must be found")
        assertEquals("INE002A01018", reliance.isin)
        assertEquals("EQ", reliance.series)
        assertTrue(
            reliance.industry.isNotBlank(),
            "the industry column is the only published classification the device receives, so a " +
                "blank one means the column was misread rather than genuinely empty",
        )
    }

    @Test
    fun `lookup is tolerant of case and surrounding space`() {
        val table = load(IndexCohort.NIFTY_50)
        assertTrue(table.contains("reliance"))
        assertTrue(table.contains("  RELIANCE  "))
        assertTrue(table.contains("ReLiAnCe"))
        assertNull(table.constituent("NOT_A_LISTED_SYMBOL"))
    }

    @Test
    fun `a symbol resolves to exactly one cohort or to none`() {
        val all = tables()

        assertEquals(
            IndexCohort.NIFTY_50,
            load(IndexCohort.NIFTY_50).cohortOf("RELIANCE", all),
        )

        // A mid-cap name taken from the real file rather than assumed.
        val midcapSymbol = load(IndexCohort.MIDCAP_150).constituents.first().symbol
        assertEquals(
            IndexCohort.MIDCAP_150,
            load(IndexCohort.MIDCAP_150).cohortOf(midcapSymbol, all),
        )

        assertNull(
            load(IndexCohort.NIFTY_50).cohortOf("A_SYMBOL_IN_NO_INDEX", all),
            "an unlisted symbol must resolve to nothing, never to a default cohort",
        )
    }

    @Test
    fun `the bundled vintage is a real date and is not in the future`() {
        // The whole point of the vintage. If this ever drifted to `now`, "large cap" would claim to
        // be current regardless of how old the file on disk is.
        assertEquals(LocalDate.parse("2026-10-04"), IndexCohort.BUNDLED_AS_OF)
        assertTrue(
            IndexCohort.BUNDLED_AS_OF <= LocalDate.now(),
            "a fetch date in the future means the constant was set by hand, not by the script",
        )
    }

    // ------------------------------------------------------------------ the parser's refusals

    @Test
    fun `a column reorder parses rather than silently shifting fields`() {
        // The failure this guards: NSE has reordered these columns before, and indexing by position
        // would read a company's industry as its name and its ISIN as its ticker - producing 451
        // plausible, entirely wrong rows.
        val reordered = """
            Symbol,Company Name,Series,Industry,ISIN Code
            TESTCORP,Test Corporation Ltd.,EQ,Capital Goods,INE999A01001
        """.trimIndent()

        val row = IndexCohortParser.parse(IndexCohort.NIFTY_50, reordered)
            .constituent("TESTCORP")

        assertNotNull(row, "a reordered header must still parse")
        assertEquals("Test Corporation Ltd.", row.companyName)
        assertEquals("INE999A01001", row.isin)
        assertEquals("Capital Goods", row.industry)
    }

    @Test
    fun `a quoted company name does not shift the columns`() {
        // RFC-4180 quoting, the reason the splitter is shared with the bhavcopy parser. NSE has
        // shipped names containing commas.
        val quoted = """
            Company Name,Industry,Symbol,Series,ISIN Code
            "Foo, Bar and Baz Ltd.",Capital Goods,FOOBAZ,EQ,INE888A01002
        """.trimIndent()

        val row = IndexCohortParser.parse(IndexCohort.NIFTY_50, quoted).constituent("FOOBAZ")

        assertNotNull(row)
        assertEquals("Foo, Bar and Baz Ltd.", row.companyName)
        assertEquals("INE888A01002", row.isin)
        assertEquals("Capital Goods", row.industry)
    }

    @Test
    fun `a row with no symbol or no ISIN is rejected and counted`() {
        val mixed = """
            Company Name,Industry,Symbol,Series,ISIN Code
            Good Ltd.,Capital Goods,GOODCO,EQ,INE777A01003
            ,Capital Goods,,EQ,INE777A01004
            No Isin Ltd.,Capital Goods,NOISIN,EQ,
        """.trimIndent()

        val table = IndexCohortParser.parse(IndexCohort.NIFTY_50, mixed)

        assertEquals(1, table.constituents.size, "only the complete row may be kept")
        assertEquals(2, table.rejectedCount, "both incomplete rows must be counted, not dropped")
        assertTrue(
            table.health.notes.any { note -> note.contains("2 row(s) rejected") },
            "the rejection must be stated rather than silent: ${table.health.notes}",
        )
        assertTrue(!table.isComplete, "a short table is never complete")
    }

    @Test
    fun `a non-EQ series is kept and its series recorded`() {
        // The rule this replaced was `Series == EQ`, and it dropped **six real constituents** from
        // the shipped files: three REITs under `RR` and three listed companies under `BE`. A cohort
        // table is a statement about index membership; whether the app can *score* a given
        // instrument is the consumer's question, and a consumer can answer it and report the gap.
        // A parser that drops rows answers it silently, which is how Embassy REIT went missing.
        val mixed = """
            Company Name,Industry,Symbol,Series,ISIN Code
            Real Ltd.,Capital Goods,REALLTD,EQ,INE666A01005
            Embassy Office Parks REIT,Realty,EMBASSY,RR,INE041025011
            HFCL Ltd.,Telecommunication,HFCL,BE,INE475A01015
        """.trimIndent()

        val table = IndexCohortParser.parse(IndexCohort.NIFTY_50, mixed)

        assertEquals(3, table.constituents.size, "every row is a real constituent")
        assertEquals(0, table.rejectedCount)
        assertEquals("RR", table.constituent("EMBASSY")?.series)
        assertEquals("BE", table.constituent("HFCL")?.series)
    }

    @Test
    fun `the shipped lists contain only series NSE is known to publish`() {
        // Keeps [IndexCohortParser.KNOWN_SERIES] honest. A new code appearing in a future fetch
        // would show up here as a failure rather than being quietly absorbed or quietly dropped.
        val seen = mutableSetOf<String>()
        for (cohort in IndexCohort.entries) {
            load(cohort).constituents.forEach { row -> seen += row.series }
        }
        assertEquals(emptySet<String>(), seen - IndexCohortParser.KNOWN_SERIES.toSet())
        assertEquals(setOf("EQ", "RR", "BE"), seen, "the three codes measured on 2026-10-04")
    }

    @Test
    fun `a truncated download is reported rather than read as a smaller index`() {
        // The failure that matters most: a partial file otherwise looks like a legitimately smaller
        // index, and every per-cohort number built on it is wrong with nothing on screen to reveal it.
        val truncated = """
            Company Name,Industry,Symbol,Series,ISIN Code
            Alpha Ltd.,Metals,ALPHA,EQ,INE555A01007
            Beta Ltd.,Metals,BETA,EQ,INE555A01008
        """.trimIndent()

        val table = IndexCohortParser.parse(IndexCohort.NIFTY_50, truncated)

        assertTrue(!table.isComplete, "2 rows must not read as a complete NIFTY 50")
        assertTrue(
            table.health.notes.any { note ->
                note.contains("parsed 2 row(s), NSE publishes 50")
            },
            "the note must name both the parsed count and the published count: ${table.health.notes}",
        )
    }

    @Test
    fun `an html error page is a failure, not an empty cohort`() {
        // NSE serves an HTML page with a 200 when a request does not look like a browser it likes.
        val html = "<html><head><title>404</title></head><body>Not Found</body></html>"

        val table = IndexCohortParser.parse(IndexCohort.NIFTY_50, html)

        assertTrue(!table.health.ok, "an HTML page must not parse as a cohort")
        assertTrue(table.constituents.isEmpty(), "no row may come out of an HTML page")
        // The label, not the enum: `sectionsMissing` is what a reader is shown, and asserting the
        // enum here would pass while the on-screen text were wrong.
        assertEquals(listOf(IndexCohort.NIFTY_50.label), table.health.sectionsMissing)
    }

    @Test
    fun `a header missing a required column says which one`() {
        val wrong = """
            Symbol,Company Name,Series,Industry
            NOISINCO,No Isin Co.,EQ,Metals
        """.trimIndent()

        val table = IndexCohortParser.parse(IndexCohort.NIFTY_50, wrong)

        assertTrue(!table.health.ok)
        assertTrue(
            table.health.notes.first().contains(IndexCohortParser.Columns.ISIN),
            "the failure must name the missing column: ${table.health.notes}",
        )
    }

    @Test
    fun `a null stream is a typed failure`() {
        val table = IndexCohortParser.parse(IndexCohort.NIFTY_50, null)
        assertTrue(!table.health.ok)
        assertTrue(table.constituents.isEmpty(), "a null stream yields no rows, not a crash")
        assertTrue(table.health.notes.first().contains("null"), table.health.notes.toString())
    }

    @Test
    fun `every cohort asset name is distinct and resolvable`() {
        val names = IndexCohort.entries.map { cohort -> cohort.assetName }
        assertEquals(names.size, names.toSet().size, "two cohorts share an asset: $names")
        for (cohort in IndexCohort.entries) {
            assertEquals(cohort, IndexCohort.byAsset(cohort.assetName))
        }
        assertNull(IndexCohort.byAsset("nonexistent.csv"))
    }

    @Test
    fun `the completeness check compares against the published size`() {
        // A guard on the guard: if `expectedSize` were derived from the file rather than stated, the
        // completeness test above could never fail and would be decoration.
        for (cohort in IndexCohort.entries) {
            assertTrue(
                cohort.expectedSize in 1..600,
                "${cohort.assetName} has an implausible expected size ${cohort.expectedSize}",
            )
        }
        assertEquals(
            451,
            IndexCohort.entries.sumOf { cohort -> cohort.expectedSize },
            "the three published sizes must sum to the total membership",
        )
    }

    @Test
    fun `a health note names the cohort it belongs to`() {
        val table = IndexCohortParser.parse(IndexCohort.SMALLCAP_250, "Symbol,Series\nX,EQ\n")
        assertTrue(
            table.health.notes.any { note -> note.contains(IndexCohort.SMALLCAP_250.label) },
            "a failure must name its cohort: ${table.health.notes}",
        )
        assertEquals(emptyList<IndexConstituent>(), table.constituents)
    }
}
