package com.mia.app.data.symbols

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every rung of the escalation ladder, asserted individually so a break in one cannot hide behind
 * the others (plan.md §9 requirements 1–5).
 */
class SymbolResolverLadderTest {

    private val resolver = SymbolFixtures.resolver()

    // -------------------------------------------------------------------------------------
    // Rung 1 — exact ticker
    // -------------------------------------------------------------------------------------

    @Test
    fun `rung 1 an exact ticker resolves exactly and says so`() {
        val resolution = resolver.resolve("RELIANCE")
        assertEquals(ResolutionStatus.EXACT, resolution.status)
        assertEquals(ResolutionStep.EXACT_TICKER, resolution.step)
        assertEquals("RELIANCE", resolution.symbol)
        assertEquals(ResolutionStatus.EXACT, resolution.status)
        assertTrue(!resolution.isAdjusted)
        assertTrue(resolution.explanation.contains("RELIANCE"))
        assertNull(resolution.corporateAction)
    }

    @Test
    fun `rung 1 an empty query is unresolved with a message not a crash`() {
        val resolution = resolver.resolve("   ")
        assertEquals(ResolutionStatus.UNRESOLVED, resolution.status)
        assertNull(resolution.record)
        assertTrue(resolution.explanation.isNotBlank())
    }

    // -------------------------------------------------------------------------------------
    // Rung 2 — normalisation
    // -------------------------------------------------------------------------------------

    @Test
    fun `rung 2 surrounding whitespace is normalised because EXACT means byte-identical`() {
        // EXACT is the signal that nothing was adjusted. Reporting "RELIANCE is an exact match for
        // 'RELIANCE  '" would misdescribe what the app did, so the padded form is NORMALISED.
        val padded = resolver.resolve("RELIANCE  ")
        assertEquals(ResolutionStatus.NORMALISED, padded.status)
        assertEquals(ResolutionStep.NORMALISED_TICKER, padded.step)
        assertEquals("RELIANCE", padded.symbol)
        assertTrue(padded.isAdjusted)
        assertTrue(padded.explanation.contains("spacing"), padded.explanation)
    }

    @Test
    fun `rung 2 an inner space has to be folded which rung 1 cannot do`() {
        val spaced = resolver.resolve("BAJAJ AUTO")
        assertEquals(ResolutionStatus.NORMALISED, spaced.status)
        assertEquals(ResolutionStep.NORMALISED_TICKER, spaced.step)
        assertEquals("BAJAJ-AUTO", spaced.symbol)
        assertTrue(spaced.isAdjusted)
        assertTrue(spaced.explanation.contains("BAJAJ AUTO"), spaced.explanation)
        assertTrue(spaced.explanation.contains("BAJAJ-AUTO"), spaced.explanation)
    }

    @Test
    fun `rung 2 lowercase resolves`() {
        assertEquals("TCS", resolver.resolve("tcs").symbol)
        assertEquals("TCS", resolver.resolve("tCs").symbol)
        assertEquals("TCS", resolver.resolve(" Tcs\n").symbol)
    }

    @Test
    fun `rung 2 a dash-folded ticker resolves to the real hyphenated one`() {
        val resolution = resolver.resolve("BAJAJAUTO")
        assertEquals(ResolutionStatus.NORMALISED, resolution.status)
        assertEquals("BAJAJ-AUTO", resolution.symbol)
        assertEquals("Bajaj Auto Limited", resolution.record?.name)
    }

    @Test
    fun `rung 2 ampersand and space folding resolves to the real ampersand ticker`() {
        assertEquals("M&M", resolver.resolve("MM").symbol)
        assertEquals("M&M", resolver.resolve("m & m").symbol)
        assertEquals("M&MFIN", resolver.resolve("m&mfin").symbol)
        assertEquals("GVT&D", resolver.resolve("GVTD").symbol)
    }

    @Test
    fun `rung 2 a yahoo exchange suffix is normalised and named as such`() {
        val resolution = resolver.resolve("TCS.NS")
        assertEquals(ResolutionStatus.NORMALISED, resolution.status)
        assertEquals("TCS", resolution.symbol)
        assertTrue(
            resolution.explanation.contains("exchange suffix"),
            "the explanation must name the fold that happened: ${resolution.explanation}",
        )
    }

    @Test
    fun `rung 2 the explanation names only the folds that actually happened`() {
        assertTrue(resolver.resolve("reliance").explanation.contains("case"))
        assertTrue(!resolver.resolve("reliance").explanation.contains("exchange suffix"))
    }

    // -------------------------------------------------------------------------------------
    // Rung 3 — known corporate-action alias
    // -------------------------------------------------------------------------------------

    @Test
    fun `rung 3 TATAMOTORS resolves to its live successor with the event named`() {
        val resolution = resolver.resolve("TATAMOTORS")
        assertEquals(ResolutionStatus.ALIAS, resolution.status)
        assertEquals(ResolutionStep.ALIAS, resolution.step)
        assertEquals("TMCV", resolution.symbol)
        assertEquals("Tata Motors Limited", resolution.record?.name)
        assertEquals("INE1TAE01010", resolution.record?.isin)

        val explanation = resolution.explanation
        assertTrue(explanation.contains("TATAMOTORS"), explanation)
        assertTrue(explanation.contains("TMCV"), explanation)
        assertTrue(explanation.contains("demerger"), explanation)
        assertTrue(explanation.contains("2025-11-12"), "the new listing date must be shown: $explanation")

        val alias = assertNotNull(resolution.corporateAction)
        assertEquals("TATAMOTORS", alias.fromSymbol)
        assertEquals("TMCV", alias.toSymbol)
        assertEquals("DEMERGER", alias.event)
        // The other leg of the demerger is offered, not hidden: the user may have meant it.
        assertEquals(listOf("TMPV"), resolution.alternatives.map { it.symbol })
    }

    @Test
    fun `rung 3 the alias is case-insensitive and survives the yahoo suffix`() {
        assertEquals("TMCV", resolver.resolve("tatamotors").symbol)
        assertEquals("TMCV", resolver.resolve("  TataMotors  ").symbol)
        assertEquals("TMCV", resolver.resolve("TATAMOTORS.NS").symbol)
        assertEquals(ResolutionStatus.ALIAS, resolver.resolve("tatamotors").status)
    }

    @Test
    fun `rung 3 an alias whose successor is not in the book falls through instead of inventing one`() {
        // An alias is a claim about a *specific* ticker. If the successor is missing, the bundled
        // master is stale relative to the alias table, and substituting a near match would send the
        // user to a different company with full confidence — worse than saying nothing matched.
        val book = SymbolBook.of(
            listOf(
                SymbolFixtures.record("TCS", isin = "INE467B01029"),
                SymbolFixtures.record("AABBCC", isin = "INE999A01001"),
            ),
        )
        val local = SymbolResolver(
            book,
            aliases = listOf(CorporateActionAlias("ZZZZQQQ", "MISSINGSUC", "DEMERGER", detail = "synthetic")),
        )
        val resolution = local.resolve("ZZZZQQQ")
        assertEquals(ResolutionStatus.UNRESOLVED, resolution.status)
        assertNull(resolution.record)
        assertTrue(resolution.explanation.contains("ZZZZQQQ"), resolution.explanation)
        // The alias is still reported, so the caller can surface a stale-master problem.
        assertNotNull(local.aliasFor("ZZZZQQQ"))
    }

    // -------------------------------------------------------------------------------------
    // Rung 4 — ISIN lookup
    // -------------------------------------------------------------------------------------

    @Test
    fun `rung 4 an ISIN from a previous bhavcopy resolves to the same company`() {
        val resolution = resolver.resolve("INE002A01018")
        assertEquals(ResolutionStep.ISIN_LOOKUP, resolution.step)
        assertEquals(ResolutionStatus.EXACT, resolution.status, "an ISIN match is an exact identity match")
        assertEquals("RELIANCE", resolution.symbol)
        assertTrue(resolution.explanation.contains("stable identity key"))
    }

    @Test
    fun `rung 4 the demerger legs are found by ISIN including the retired one`() {
        // INE155A01022 is the ISIN the retired TATAMOTORS carried and that TMPV inherited. Looking
        // it up must land on a real, live company rather than on the dead ticker.
        assertEquals("TMPV", resolver.resolve("INE155A01022").symbol)
        assertEquals("TMCV", resolver.resolve("INE1TAE01010").symbol)
    }

    @Test
    fun `rung 4 an ISIN-shaped string that is unknown does not resolve to a guess`() {
        val resolution = resolver.resolve("INE000A00000")
        assertNull(resolution.record)
        assertEquals(ResolutionStatus.UNRESOLVED, resolution.status)
        assertTrue(resolution.explanation.isNotBlank())
    }

    // -------------------------------------------------------------------------------------
    // Rung 5 — fuzzy
    // -------------------------------------------------------------------------------------

    @Test
    fun `rung 5a a one-character typo in the ticker resolves as a labelled guess`() {
        val resolution = resolver.resolve("RELIANC")
        assertEquals(ResolutionStatus.FUZZY, resolution.status)
        assertEquals(ResolutionStep.FUZZY_TICKER, resolution.step)
        assertEquals("RELIANCE", resolution.symbol)
        assertTrue(resolution.isAdjusted, "a guess must be visibly a guess")
        assertTrue(resolution.explanation.contains("No symbol matches"), resolution.explanation)
        assertTrue(resolution.explanation.contains("1 character"), resolution.explanation)
    }

    @Test
    fun `rung 5a the edit distance ceiling rejects a rewrite`() {
        // 6 characters changed is a different company, not a typo.
        val resolution = resolver.resolve("RELIANCEZZZZZZ")
        assertNull(resolution.record)
        assertEquals(ResolutionStatus.UNRESOLVED, resolution.status)
    }

    @Test
    fun `rung 5a a two-character query is never guessed from`() {
        val resolution = resolver.resolve("TC")
        assertNull(resolution.record)
        assertEquals(ResolutionStatus.UNRESOLVED, resolution.status)
        assertTrue(resolution.alternatives.isNotEmpty(), "but near misses are still offered")
    }

    @Test
    fun `rung 5b a company name with no ticker counterpart resolves through token overlap`() {
        val resolution = resolver.resolve("state bank of india")
        assertEquals(ResolutionStatus.FUZZY, resolution.status)
        assertEquals(ResolutionStep.FUZZY_NAME, resolution.step)
        assertEquals("SBIN", resolution.symbol)
        assertEquals("State Bank of India", resolution.record?.name)
        assertTrue(resolution.explanation.contains("name"), resolution.explanation)
    }

    @Test
    fun `rung 5b a partial company name resolves through token overlap`() {
        val resolution = resolver.resolve("reliance industries")
        assertEquals(ResolutionStep.FUZZY_NAME, resolution.step)
        assertEquals("RELIANCE", resolution.symbol)
    }

    // -------------------------------------------------------------------------------------
    // Rung 6 — explicit unresolved
    // -------------------------------------------------------------------------------------

    @Test
    fun `rung 6 a nonsense query is explicitly unresolved never an empty silent result`() {
        val resolution = resolver.resolve("ZZZZQQQ")
        assertEquals(ResolutionStatus.UNRESOLVED, resolution.status)
        assertEquals(ResolutionStep.UNRESOLVED, resolution.step)
        assertNull(resolution.record)
        assertNull(resolution.symbol)
        assertTrue(!resolution.isResolved)
        assertTrue(resolution.explanation.contains("ZZZZQQQ"), resolution.explanation)
        assertTrue(resolution.explanation.contains("2593"), "the search size must be reported: ${resolution.explanation}")
    }

    @Test
    fun `rung 6 an unresolved single-character query still offers near misses`() {
        val resolution = resolver.resolve("R")
        assertEquals(ResolutionStatus.UNRESOLVED, resolution.status)
        assertTrue(resolution.alternatives.isNotEmpty())
        assertTrue(resolution.explanation.contains("Closest"), resolution.explanation)
    }

    @Test
    fun `an empty book reports its own emptiness instead of claiming no symbol matched`() {
        // A blank search box and a broken asset look identical to the user unless they are told apart.
        val resolution = SymbolResolver(SymbolBook.of(emptyList())).resolve("RELIANCE")
        assertEquals(ResolutionStatus.UNRESOLVED, resolution.status)
        assertTrue(resolution.explanation.contains("symbol master is empty"), resolution.explanation)
    }

    // -------------------------------------------------------------------------------------
    // Cross-cutting invariants
    // -------------------------------------------------------------------------------------

    @Test
    fun `no query of any shape ever yields a blank explanation`() {
        val queries = listOf(
            "", " ", "\t", "R", "RE", "RELIANCE", "reliance", "  RELIANCE  ", "RELIANCE.NS",
            "BAJAJAUTO", "BAJAJ-AUTO", "MM", "M&M", "TATAMOTORS", "TATAMOTORS.NS", "INE002A01018",
            "ZZZZQQQ", "1234567", "@@@@", "state bank of india", "reliance industries", "आरईएफसी",
        )
        for (query in queries) {
            val resolution = resolver.resolve(query)
            assertTrue(
                resolution.explanation.isNotBlank(),
                "blank explanation for '$query'",
            )
            assertEquals(resolution.query, query, "the original query must be echoed verbatim")
            if (resolution.status == ResolutionStatus.UNRESOLVED) {
                assertNull(resolution.record, "'$query' is UNRESOLVED but carries a record")
            } else {
                assertNotNull(resolution.record, "'$query' resolved without a record")
            }
        }
    }

    @Test
    fun `resolution of any ticker-shaped query returns a record that is in the book`() {
        for (symbol in listOf("RELIANCE", "TCS", "HDFCBANK", "M&M", "BAJAJ-AUTO", "TMCV", "TMPV", "SBIN")) {
            val resolution = resolver.resolve(symbol)
            assertEquals(SymbolFixtures.book().find(symbol), resolution.record, "resolve($symbol)")
        }
    }

    @Test
    fun `resolve never returns a dead ticker when a live one exists`() {
        // The property plan.md §9 is actually about: whatever the user types, the answer is either
        // null-with-a-reason or a symbol the current book says trades.
        val live = SymbolFixtures.book().records.map { it.symbol }.toSet()
        for (symbol in listOf("RELIANC", "TCSN", "TCSSS", "HDFC", "HDFFCBANK", "tata motors", "TATAMOTORS")) {
            val resolved = resolver.resolve(symbol).symbol
            if (resolved != null) assertTrue(resolved in live, "$symbol resolved to $resolved which is not listed")
        }
    }

    @Test
    fun `levenshtein matches the reference distances`() {
        assertEquals(0, SymbolResolver.levenshtein("RELIANCE", "RELIANCE"))
        assertEquals(1, SymbolResolver.levenshtein("RELIANCE", "RELIANC"))
        assertEquals(1, SymbolResolver.levenshtein("RELIANCE", "RELIANCES"))
        assertEquals(1, SymbolResolver.levenshtein("RELIANCE", "RELIANCC"))
        // RELIANCE -> TCS: 7 edits. Verified against a reference implementation in Python
// (`editdistance("RELIANCE","TCS") == 7`), not against my own arithmetic.
        assertEquals(7, SymbolResolver.levenshtein("RELIANCE", "TCS"))
        assertEquals(3, SymbolResolver.levenshtein("kitten", "sitting"))
        assertEquals(3, SymbolResolver.levenshtein("", "abc"))
        assertEquals(0, SymbolResolver.levenshtein("", ""))
        assertEquals(1, SymbolResolver.levenshtein("TCS", "TCSS"))
        assertEquals(1, SymbolResolver.levenshtein("BAJAJ-AUTO", "BAJAJAUTO"))
        // The ceiling aborts and reports "further than the ceiling" instead of the real distance:
        // RELIANCE→TCS is 8, but a caller that only asks "is it within 2?" must get a clear no.
        assertEquals(3, SymbolResolver.levenshtein("RELIANCE", "TCS", maxEditDistance = 2))
    }

    @Test
    fun `the resolver exposes the same ranking as the book so there is one implementation`() {
        assertEquals(
            SymbolFixtures.book().search("BANK", limit = 8).map { it.record.symbol },
            resolver.search("BANK", limit = 8).map { it.record.symbol },
        )
    }

    @Test
    fun `book resolve is the default resolver so a caller cannot get a different answer`() {
        assertEquals(
            resolver.resolve("TATAMOTORS").symbol,
            SymbolFixtures.book().resolve("TATAMOTORS").symbol,
        )
    }
}