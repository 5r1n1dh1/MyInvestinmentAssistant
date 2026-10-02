package com.mia.app.data.fundamentals

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * robots.txt compliance (plan.md §3.6, binding).
 *
 * screener.in disallows `?q=` (search), `?page=` (pagination), `?sort=`, `?limit=`, `/user/` and
 * `/company/source/quarter/`. This suite asserts three separate things, because any one of them
 * alone could be satisfied by accident:
 *
 * 1. The URL builder produces exactly one shape, and only for a safe symbol.
 * 2. [ScreenerInUrls.isPermitted] refuses search, pagination and any query string at all.
 * 3. A real fetch, against a recording transport, **only ever sends permitted URLs** — this is
 *    the assertion that would catch a future change adding a second request behind the builder.
 */
class ScreenerRobotsComplianceTest {

    @Test
    fun `the builder produces exactly one url shape`() {
        assertEquals(
            "https://www.screener.in/company/RELIANCE/consolidated/",
            ScreenerInUrls.companyPage("RELIANCE"),
        )
        assertEquals(
            "https://www.screener.in/company/TCS/consolidated/",
            ScreenerInUrls.companyPage("TCS"),
        )
        // Lower case is normalised, because NSE symbols are upper case and a stray lower-case
        // symbol from search must not become a 404 URL.
        assertEquals(
            "https://www.screener.in/company/RELIANCE/consolidated/",
            ScreenerInUrls.companyPage("reliance"),
        )
        assertEquals(
            "https://www.screener.in/company/RELIANCE/consolidated/",
            ScreenerInUrls.companyPage("  RELIANCE  "),
        )
    }

    @Test
    fun `real NSE symbols that need punctuation are accepted`() {
        for (symbol in listOf("M&M", "L&T", "BAJAJ-AUTO", "NIFTYBEES", "INDIAETF", "TCS", "M100")) {
            val url = ScreenerInUrls.companyPage(symbol)
            assertTrue(
                ScreenerInUrls.isPermitted(url),
                "$symbol produced a non-permitted URL: $url",
            )
            assertEquals(
                "https://www.screener.in/company/${symbol.uppercase()}/consolidated/",
                url,
            )
        }
    }

    @Test
    fun `no symbol can inject a path a query string or another host`() {
        val hostile = listOf(
            "RELIANCE?q=RELIANCE",
            "RELIANCE?page=2",
            "RELIANCE&page=2",
            "../../company/TCS/consolidated/",
            "/etc/passwd",
            "RELIANCE#x",
            "RELIANCE%2f..%2f",
            "RE LIANCE",
            "RELIANCE\nX-Injected: 1",
            "RELIANCE\r\nHost: evil.example",
            "https://evil.example/company/RELIANCE/consolidated/",
            "evil.example",
            ".",
            "..",
            "-RELIANCE",
            ".RELIANCE",
            "RELIANCE/",
            "",
            "   ",
            "A".repeat(ScreenerInUrls.MAX_SYMBOL_LENGTH + 1),
        )
        for (symbol in hostile) {
            assertFailsWith<IllegalArgumentException>("symbol '$symbol' must be rejected") {
                ScreenerInUrls.companyPage(symbol)
            }
            assertFalse(ScreenerInUrls.isValidSymbol(symbol), "'$symbol' must be invalid")
        }
    }

    @Test
    fun `the permitted set is exactly the consolidated company page`() {
        assertTrue(ScreenerInUrls.isPermitted("https://www.screener.in/company/RELIANCE/consolidated/"))
        assertTrue(ScreenerInUrls.isPermitted("https://www.screener.in/company/M&M/consolidated/"))
    }

    @Test
    fun `search pagination and every other query string are refused`() {
        val forbidden = listOf(
            // Disallow: /[*]?q=
            "https://www.screener.in/api/company/search/?q=RELIANCE",
            "https://www.screener.in/company/RELIANCE/consolidated/?q=RELIANCE",
            "https://www.screener.in/?q=RELIANCE",
            // Disallow: /[*]?page=
            "https://www.screener.in/company/RELIANCE/consolidated/?page=2",
            "https://www.screener.in/company/RELIANCE/consolidated/?page=1",
            // Disallow: /[*]?sort= and /[*]?limit=
            "https://www.screener.in/company/RELIANCE/consolidated/?sort=name",
            "https://www.screener.in/company/RELIANCE/consolidated/?limit=100",
            // Disallow: /user/ and /company/source/quarter/
            "https://www.screener.in/user/quick_ratios/?next=/company/RELIANCE/consolidated/",
            "https://www.screener.in/company/source/quarter/2726/6/2023/",
            // Standalone is a different page, and this parser only ever claims consolidated.
            "https://www.screener.in/company/RELIANCE/",
            // Other hosts, fragments, and no trailing slash.
            "https://www.screener.in/company/RELIANCE/consolidated",
            "https://www.screener.in/company/RELIANCE/consolidated/#x",
            "http://www.screener.in/company/RELIANCE/consolidated/",
            "https://screener.in/company/RELIANCE/consolidated/",
            "https://evil.example/company/RELIANCE/consolidated/",
            "https://www.screener.in.evil.example/company/RELIANCE/consolidated/",
        )
        for (url in forbidden) {
            assertFalse(ScreenerInUrls.isPermitted(url), "must refuse $url")
        }
    }

    @Test
    fun `a real fetch only ever sends one permitted url shape`() = runTest {
        val sleeper = RecordingSleeper()
        val transport = FakeScreenerTransport {
            FakeScreenerTransport.html(ScreenerFixtures.html("RELIANCE"))
        }
        val source = ScreenerInSource(
            transport,
            ScreenerInSource.Policy(minIntervalMillis = 0, maxAttempts = 1),
            sleeper.sleeper,
        )

        for (symbol in listOf("RELIANCE", "TCS", "HDFCBANK", "M&M", "BAJAJ-AUTO")) {
            source.fetch(symbol, ScreenerFixtures.AS_OF)
        }

        assertEquals(5, transport.count, "one request per symbol and nothing else")
        for (url in transport.urls) {
            assertTrue(ScreenerInUrls.isPermitted(url), "fetched a forbidden URL: $url")
            assertFalse(url.contains("?q="), url)
            assertFalse(url.contains("?page="), url)
            assertFalse(url.contains("?sort="), url)
            assertFalse(url.contains("?limit="), url)
            assertFalse(url.contains("?") , url)
            assertFalse(url.contains("/user/"), url)
            assertFalse(url.contains("/company/source/"), url)
        }
        assertEquals(
            listOf(
                "https://www.screener.in/company/RELIANCE/consolidated/",
                "https://www.screener.in/company/TCS/consolidated/",
                "https://www.screener.in/company/HDFCBANK/consolidated/",
                "https://www.screener.in/company/M&M/consolidated/",
                "https://www.screener.in/company/BAJAJ-AUTO/consolidated/",
            ),
            transport.urls,
        )
    }

    @Test
    fun `a hostile symbol never reaches the network at all`() = runTest {
        val sleeper = RecordingSleeper()
        val transport = FakeScreenerTransport { FakeScreenerTransport.status(200) }
        val source = ScreenerInSource(transport, ScreenerInSource.Policy(minIntervalMillis = 0), sleeper.sleeper)

        val result = source.fetch("RELIANCE/consolidated/?q=x", ScreenerFixtures.AS_OF)

        assertEquals(0, transport.count, "an invalid symbol must not be interpolated into a request")
        assertFalse(result.health.ok)
        assertTrue(result.health.notes.any { it.contains("rejected symbol") })
    }

    @Test
    fun `the Raw PDF sub-schedule links are never requested`() = runTest {
        val sleeper = RecordingSleeper()
        val html = ScreenerFixtures.html("RELIANCE")
        assertTrue(html.contains("/company/source/quarter/"), "the fixture does contain the link")
        val transport = FakeScreenerTransport { FakeScreenerTransport.html(html) }
        val source = ScreenerInSource(transport, ScreenerInSource.Policy(minIntervalMillis = 0), sleeper.sleeper)
        source.fetch("RELIANCE", ScreenerFixtures.AS_OF)
        assertTrue(
            transport.urls.none { it.contains("/company/source/") },
            "the parser must not follow the Raw PDF links (plan.md §3.6)",
        )
    }
}
