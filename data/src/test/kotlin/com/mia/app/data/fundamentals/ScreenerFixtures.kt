package com.mia.app.data.fundamentals

/**
 * Real screener.in pages, captured 2026-10-02 from
 * `https://www.screener.in/company/{SYMBOL}/consolidated/` with
 * `Referer: https://www.screener.in/` and a browser `User-Agent` (plan.md §3.3).
 *
 * ~218–242 KB each. These are the golden files: no hand-written HTML stands in for the real
 * page, because the failure being guarded (plan.md §13, "HTML parser brittleness") is exactly
 * the gap between a plausible fake and the real markup — the class-name landmine, the trailing
 * `TTM` column, the quarterly `shareholding` headers, and HDFCBANK's bank-shaped P&L all live
 * only in the real bytes.
 */
internal object ScreenerFixtures {

    /** Fixtures captured 2026-10-02; pinned so assertions never drift with the wall clock. */
    val AS_OF: java.time.LocalDate = java.time.LocalDate.of(2026, 10, 2)

    val symbols: List<String> = listOf("RELIANCE", "TCS", "HDFCBANK", "INFY", "TITAN")

    fun html(symbol: String): String = text("$symbol.html")

    fun text(name: String): String =
        bytes(name).toString(Charsets.UTF_8)

    fun bytes(name: String): ByteArray =
        stream(name)?.use { it.readBytes() } ?: error("missing fixture /fixtures/$name")

    private fun stream(name: String) =
        ScreenerFixtures::class.java.getResourceAsStream("/fixtures/$name")

    /** Parses one fixture through the production facade with a fixed "as of" date. */
    fun parse(symbol: String): FundamentalsParseResult =
        FundamentalsParserFacade.parse(html(symbol), symbol, AS_OF)
}
