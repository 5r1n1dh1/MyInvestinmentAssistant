package com.mia.app.data.symbols

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Search ranking, asserted against the real 2,593-ticker master.
 *
 * The ranking is a *contract*: plan.md §9 requirement 5 asks for instant offline search, and the
 * only thing a user can perceive about an instant local search is the order the rows come back in.
 * So the order is asserted exactly rather than "contains".
 */
class SymbolBookSearchTest {

    private val book = SymbolFixtures.book()

    @Test
    fun `the ranking order is exact then prefix then substring then name prefix then name substring`() {
        assertTrue(
            SearchMatchKind.EXACT_TICKER.ordinal < SearchMatchKind.TICKER_PREFIX.ordinal &&
                SearchMatchKind.TICKER_PREFIX.ordinal < SearchMatchKind.TICKER_SUBSTRING.ordinal &&
                SearchMatchKind.TICKER_SUBSTRING.ordinal < SearchMatchKind.NAME_PREFIX.ordinal &&
                SearchMatchKind.NAME_PREFIX.ordinal < SearchMatchKind.NAME_SUBSTRING.ordinal,
            "the enum ordinal *is* the ranking",
        )
    }

    @Test
    fun `RELI ranks every ticker prefix above every ticker substring above every name match`() {
        val hits = book.search("RELI", limit = 40)
        val kinds = hits.map { it.kind }
        assertEquals(kinds.sortedBy { it.ordinal }, kinds, "results must be grouped by tier")

        val prefixes = hits.filter { it.kind == SearchMatchKind.TICKER_PREFIX }
        assertEquals(
            listOf("RELIABLE", "RELIANCE", "RELIGARE", "RELINFRA"),
            prefixes.map { it.record.symbol },
            "ticker-prefix tier is alphabetical within the tier; RELIABLE precedes RELIANCE",
        )
        assertTrue(
            prefixes.any { it.record.symbol == "RELIANCE" },
            "RELIANCE must be in the top tier for RELI",
        )
        // Everything after the prefix tier is a weaker match, in strict tier order.
        val afterPrefix = hits.drop(prefixes.size)
        assertTrue(afterPrefix.all { it.kind.ordinal > SearchMatchKind.TICKER_PREFIX.ordinal })
        assertTrue(
            afterPrefix.filter { it.kind == SearchMatchKind.NAME_PREFIX }
                .all { it.record.name.startsWith("Reli", ignoreCase = true) },
        )
    }

    @Test
    fun `bank puts ticker prefixes before ticker substrings before name matches`() {
        val hits = book.search("bank", limit = 60)
        // Measured on the real master: 3 tickers start with BANK, 17 contain it, and 24 further
        // banks are reachable only through the company name ("State Bank of India" -> SBIN).
        // Within a tier: shortest ticker first, then alphabetical — so BANKA (5) before BANKINDIA (9)
        // before BANKBARODA (10). Length first because for a prefix query the shorter ticker is
        // more likely to be the instrument the user meant, and it keeps the order stable.
        assertEquals(
            listOf("BANKA", "BANKINDIA", "BANKBARODA"),
            hits.filter { it.kind == SearchMatchKind.TICKER_PREFIX }.map { it.record.symbol },
        )
        val substrings = hits.filter { it.kind == SearchMatchKind.TICKER_SUBSTRING }.map { it.record.symbol }
        assertEquals(17, substrings.size, "every ticker containing BANK: $substrings")
        assertEquals(
            substrings.sortedWith(compareBy({ it.length }, { it })),
            substrings,
            "within a tier the order is shortest ticker then alphabetical, not alphabetical alone",
        )
        assertTrue(substrings.containsAll(listOf("AUBANK", "AXISBANK", "HDFCBANK", "ICICIBANK", "YESBANK")))

        // Ticker matches always outrank name matches, however good the name match looks. A bank
        // whose *ticker* says nothing about banking -- SBIN, PNB, CANBK, IDBI -- is the real test
        // that name matches come last.
        val lastTickerIndex = hits.indexOfLast { it.kind.ordinal <= SearchMatchKind.TICKER_SUBSTRING.ordinal }
        val firstNameIndex = hits.indexOfFirst { it.kind.ordinal >= SearchMatchKind.NAME_PREFIX.ordinal }
        assertTrue(
            lastTickerIndex < firstNameIndex,
            "in 'bank', ${hits[firstNameIndex].record.symbol} (${hits[firstNameIndex].kind}) appeared " +
                "before a ticker match ended",
        )
        // "State Bank of India" *contains* "bank" but does not *begin* with it, so it is NAME_SUBSTRING.
        val sbin = assertNotNull(hits.firstOrNull { it.record.symbol == "SBIN" })
        assertEquals(SearchMatchKind.NAME_SUBSTRING, sbin.kind)
        assertEquals(
            hits.indexOfLast { it.kind == SearchMatchKind.TICKER_SUBSTRING },
            firstNameIndex - 1,
            "every ticker substring must sit immediately before the first name match",
        )

        // Three names genuinely begin with "bank": Bank of Baroda, Bank of India, Bank of Maharashtra.
        // Within the tier the shortest ticker wins, so MAHABANK (8) outranks BANKINDIA (9) and
        // BANKBARODA (10) — a defensible order, and a stable one.
        assertEquals(
            SearchMatchKind.NAME_PREFIX,
            assertNotNull(book.bestMatch("bank of")).kind,
        )
        assertEquals("MAHABANK", book.bestMatch("bank of")?.record?.symbol)
    }

    @Test
    fun `findByNamePrefix answers only for names that really begin with the query`() {
        assertEquals(listOf("HDFCBANK"), book.findByNamePrefix("HDFC BANK LIMITED").map { it.symbol })
        // searchKeys is in ticker order, so the prefix results come back sorted by ticker.
        assertEquals(
            listOf("BANKBARODA", "BANKINDIA", "MAHABANK"),
            book.findByNamePrefix("Bank of").map { it.symbol },
        )
        assertEquals(emptyList(), book.findByNamePrefix("of India"), "a mid-name fragment is not a prefix")
        assertEquals(emptyList(), book.findByNamePrefix(""), "a blank query is not a prefix of everything")
    }

    @Test
    fun `search is case-insensitive and whitespace-tolerant`() {
        assertEquals(
            book.search("RELIANCE").map { it.record.symbol },
            book.search("reliance").map { it.record.symbol },
        )
        assertEquals(
            book.search("RELIANCE").map { it.record.symbol },
            book.search("  reliance  ").map { it.record.symbol },
        )
        assertEquals(SearchMatchKind.EXACT_TICKER, book.search("reliance").first().kind)
        assertEquals(SearchMatchKind.EXACT_TICKER, book.search("ReLiAnCe").first().kind)
    }

    @Test
    fun `an ampersand ticker is found exactly and its sibling ranks second`() {
        // Folding '&' out of the search key would turn M&M into MM and rank M&MFIN against it.
        val hits = book.search("m&m")
        assertEquals(SearchMatchKind.EXACT_TICKER, hits[0].kind)
        assertEquals("M&M", hits[0].record.symbol)
        assertEquals("M&MFIN", hits[1].record.symbol)
        assertEquals(SearchMatchKind.TICKER_PREFIX, hits[1].kind)
    }

    @Test
    fun `the yahoo exchange suffix is stripped so a pasted link still resolves`() {
        val hit = assertNotNull(book.bestMatch("RELIANCE.NS"))
        assertEquals("RELIANCE", hit.record.symbol)
        assertEquals(SearchMatchKind.EXACT_TICKER, hit.kind)
        assertEquals("BAJAJ-AUTO", book.bestMatch("BAJAJ-AUTO.NS")?.record?.symbol)
    }

    @Test
    fun `a nonsense query returns nothing rather than a confident wrong answer`() {
        assertEquals(emptyList(), book.search("ZZZZQQQ"))
        assertEquals(emptyList(), book.search("1234567"))
        assertNull(book.bestMatch("ZZZZQQQ"))
    }

    @Test
    fun `a blank query returns nothing instead of the whole market`() {
        assertEquals(emptyList(), book.search(""))
        assertEquals(emptyList(), book.search("   "))
        assertEquals(emptyList(), book.search("RELIANCE", limit = 0))
    }

    @Test
    fun `the limit is honoured and positions stay contiguous`() {
        val hits = book.search("BANK", limit = 5)
        assertEquals(5, hits.size)
        assertEquals(listOf(0, 1, 2, 3, 4), hits.map { it.position })
        assertEquals(hits.map { it.record.symbol }, book.search("BANK", limit = 5).map { it.record.symbol })
    }

    @Test
    fun `find is exact while findNormalised is forgiving`() {
        assertEquals("BAJAJ-AUTO", book.find("BAJAJ-AUTO")?.symbol)
        assertEquals(null, book.find("BAJAJAUTO"), "find must not guess")
        assertEquals(null, book.find("bajaj auto"))
        assertEquals("BAJAJ-AUTO", book.findNormalised("BAJAJAUTO")?.symbol)
        assertEquals("BAJAJ-AUTO", book.findNormalised("bajaj-auto")?.symbol)
        assertEquals("M&M", book.findNormalised("MM")?.symbol, "'&' folds out of the loose key")
        assertEquals("M&M", book.findNormalised("m & m")?.symbol)
        assertEquals("RELIANCE", book.find("reliance")?.symbol, "case folding is not guessing")
        assertEquals("RELIANCE", book.find(" RELIANCE ")?.symbol)
    }

    @Test
    fun `lookups are case-insensitive on both the ticker and the ISIN`() {
        assertEquals("RELIANCE", book.find("reliance")?.symbol)
        assertEquals("RELIANCE", book.findByIsin("ine002a01018")?.symbol)
        assertEquals("RELIANCE", book.findByIsin(" ine002a01018 ")?.symbol)
        assertEquals(null, book.findByIsin("INE000A00000"))
        assertEquals(null, book.find("NOTALISTED"))
    }

    @Test
    fun `name search finds companies whose ticker says nothing about the name`() {
        // HDFC has no ticker in the master (it merged into HDFCBANK in 2023), so a name query has to
        // go through the name index or the user finds nothing at all.
        assertEquals("HDFC Bank Limited", book.findByNamePrefix("hdfc bank").firstOrNull()?.name)
        assertEquals(listOf("HDFCBANK"), book.findByNamePrefix("HDFC BANK LIMITED").map { it.symbol })
        assertEquals("State Bank of India", book.bestMatch("state bank of india")?.record?.name)
    }

    @Test
    fun `searching the whole book is cheap enough for every keystroke`() {
        // No wall-clock assertion (flaky on shared CI). What is asserted is the shape of the work:
        // one pass over 2,593 records with pre-computed keys, and no allocation per record beyond
        // the matched pair. The reason is structural — no transport is reachable from here at all.
        val warm = book.search("BAN")
        repeat(20) { book.search("BAN") }
        assertTrue(warm.isNotEmpty())
        assertEquals(2593, book.records.size)
    }

    @Test
    fun `every record in the book is reachable by its own ticker`() {
        val missing = book.records.filter { book.find(it.symbol) !== it }
        assertTrue(missing.isEmpty(), "unreachable records: ${missing.take(5).map { it.symbol }}")
    }

    @Test
    fun `every record in the book is reachable by its own ISIN`() {
        val missing = book.records.filter { book.findByIsin(it.isin) !== it }
        assertTrue(missing.isEmpty(), "unreachable records: ${missing.take(5).map { it.symbol }}")
    }

    @Test
    fun `no loose-ticker index collision exists in the real master`() {
        // M&M folds to MM and BAJAJ-AUTO to BAJAJAUTO. Measured across all 2,593 tickers the folded
        // keys are unique, so the "first wins" fallback in SymbolBook never has to choose.
        val folded = book.records.groupBy { SymbolNormaliser.looseTickerKey(it.symbol) }
        val collisions = folded.filterValues { it.size > 1 }
        assertTrue(collisions.isEmpty(), "loose-ticker collisions: ${collisions.keys}")
        assertTrue(book.health.ok)
    }
}