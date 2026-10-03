package com.mia.app.data.fundamentals

import com.mia.app.domain.model.Confidence
import com.mia.app.domain.model.Sector
import com.mia.app.domain.model.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Golden-file assertions over the five real screener.in pages captured 2026-10-02.
 *
 * Every number below was read out of the fixture bytes, not out of plan.md. Where the plan's
 * evidence log (§14) stated a value this test reports both and flags the difference — see
 * [relianceRatiosMatchThePlanEvidenceLog] and the class KDoc on `TopRatiosParser`.
 *
 * The tolerances are not slack: **every published figure is asserted exactly**. Only the four
 * computed ratios (P/B, interest coverage, revenue CAGR, revenue growth) carry a tolerance,
 * because they are divisions of published figures and floating point is not exact.
 */
class ScreenerGoldenFileTest {

    private val tolerance = 1e-9

    // ------------------------------------------------------------------ headline ratios

    @Test
    fun `reliance headline ratios are exactly what the page publishes`() {
        val top = topRatios("RELIANCE")
        assertEquals(
            listOf(
                "marketcap", "currentprice", "highlow", "stockpe", "bookvalue",
                "dividendyield", "roce", "roe", "facevalue",
            ),
            top.keys,
        )
        assertEquals(1_580_194.0, top.marketCapCr)
        assertEquals(1_168.0, top.currentPrice)
        assertEquals(1_612.0, top.high52Week)
        assertEquals(1_161.0, top.low52Week)
        assertEquals(21.2, top.stockPe)
        assertEquals(668.0, top.bookValue)
        assertEquals(0.51, top.dividendYieldPercent)
        assertEquals(10.3, top.rocePercent)
        assertEquals(8.91, top.roePercent)
        assertEquals(10.0, top.faceValue)
        // Raw text is kept so the UI can show the page's own wording (plan.md §1.4 rule 3).
        assertEquals("15,80,194", top.fields.getValue("marketcap").rawText)
        assertEquals("₹ 1,612 / 1,161", top.fields.getValue("highlow").rawText)
    }

    @Test
    fun `tcs headline ratios are exactly what the page publishes`() {
        val top = topRatios("TCS")
        assertEquals(750_753.0, top.marketCapCr)
        assertEquals(2_075.0, top.currentPrice)
        assertEquals(3_350.0, top.high52Week)
        assertEquals(1_976.0, top.low52Week)
        assertEquals(14.0, top.stockPe)
        assertEquals(296.0, top.bookValue)
        assertEquals(3.08, top.dividendYieldPercent)
        assertEquals(63.0, top.rocePercent)
        assertEquals(51.8, top.roePercent)
        assertEquals(1.0, top.faceValue)
    }

    @Test
    fun `hdfcbank headline ratios are exactly what the page publishes`() {
        val top = topRatios("HDFCBANK")
        assertEquals(1_111_989.0, top.marketCapCr)
        assertEquals(721.0, top.currentPrice)
        assertEquals(1_020.0, top.high52Week)
        assertEquals(682.0, top.low52Week)
        assertEquals(14.1, top.stockPe)
        assertEquals(390.0, top.bookValue)
        assertEquals(1.8, top.dividendYieldPercent)
        assertEquals(7.02, top.rocePercent)
        assertEquals(13.6, top.roePercent)
        assertEquals(1.0, top.faceValue)
    }

    @Test
    fun `infy headline ratios are exactly what the page publishes`() {
        val top = topRatios("INFY")
        assertEquals(420_032.0, top.marketCapCr)
        assertEquals(1_035.0, top.currentPrice)
        assertEquals(1_728.0, top.high52Week)
        assertEquals(980.0, top.low52Week)
        assertEquals(13.5, top.stockPe)
        assertEquals(225.0, top.bookValue)
        assertEquals(4.64, top.dividendYieldPercent)
        assertEquals(40.0, top.rocePercent)
        assertEquals(31.9, top.roePercent)
        assertEquals(5.0, top.faceValue)
    }

    @Test
    fun `titan headline ratios are exactly what the page publishes`() {
        val top = topRatios("TITAN")
        assertEquals(400_898.0, top.marketCapCr)
        assertEquals(4_516.0, top.currentPrice)
        assertEquals(5_187.0, top.high52Week)
        assertEquals(3_378.0, top.low52Week)
        assertEquals(68.7, top.stockPe)
        assertEquals(177.0, top.bookValue)
        assertEquals(0.33, top.dividendYieldPercent)
        assertEquals(20.5, top.rocePercent)
        assertEquals(37.7, top.roePercent)
        assertEquals(1.0, top.faceValue)
    }

    @Test
    fun `relianceRatiosMatchThePlanEvidenceLog`() {
        // plan.md §14 recorded "RELIANCE ROCE 10.3%, ROE 8.91%, P/E 21.2, BV ₹668" — all four
        // still hold against today's fixture. The brief's "Market Cap ~₹3.96 lakh Cr" does NOT:
        // the page says ₹15,80,194 Cr. 15,80,194 Cr / ₹1,168 per share is 13.53 bn shares, which
        // is RELIANCE's actual share count, so the page is right and the earlier figure was not
        // this page's. Asserted here so the divergence is on the record rather than in prose.
        val ratios = ScreenerFixtures.parse("RELIANCE").snapshot.ratios
        assertEquals(10.3, ratios.roce)
        assertEquals(8.91, ratios.roe)
        assertEquals(21.2, ratios.pe)
        assertEquals(668.0, topRatios("RELIANCE").bookValue)
        assertEquals(1_580_194.0, ratios.marketCap)
    }

    // ------------------------------------------------------------------------- Ratios

    @Test
    fun `reliance ratios carry provenance, a null debt to equity and a resolved sector`() {
        val snapshot = ScreenerFixtures.parse("RELIANCE").snapshot
        val r = snapshot.ratios
        assertEquals("RELIANCE", r.symbol)
        assertEquals(ScreenerFixtures.AS_OF, r.asOf)
        assertEquals(Source.SCREENER_IN, r.source)
        assertEquals(Confidence.SINGLE, r.confidence)

        // plan.md §1.4 rule 8 / §7.1: never guess a sector — but the sector is now *read*, not
        // guessed, because the page has always carried screener.in's own classification and
        // `SectorResolver` is its single decision function. RELIANCE's page says
        // `Energy → Oil, Gas & Consumable Fuels → Petroleum Products → Refineries & Marketing`,
        // and `ratios.sector` agrees with `snapshot.sector`, which is the pairing that matters:
        // `DefaultRedFlagRepository` reads `ratios.sector` to build `RedFlagContext`, so when the
        // two disagreed the red-flag engine and the screen would have shown different answers.
        assertEquals(Sector.OIL_GAS, snapshot.sector)
        assertEquals(Sector.OIL_GAS, r.sector)
        assertEquals(true, snapshot.sectorResolved)
        assertEquals(
            "Energy, Oil, Gas & Consumable Fuels, Petroleum Products, Refineries & Marketing",
            snapshot.industry,
            "the industry chain is kept so the evidence can name the source, not just a keyword",
        )

        assertEquals(10.3, r.roce)
        assertEquals(8.91, r.roe)
        assertNull(r.debtToEquity, "Debt/Equity is not published by this page — null, never 0")
        assertEquals(17.0, r.operatingMargin, "annual OPM % for Mar 2026, as published")
        assertEquals(70_023.0, r.fcf)
        assertEquals(95_754.0, r.pat)
        assertEquals(1.13, r.cfoToOp, "page prints '113%'; stored as the ratio 1.13")
        assertEquals(21.2, r.pe)
        assertEquals(0.51, r.dividendYield)
        assertEquals(50.48, r.promoterHolding, "Jun 2026 quarter, the most recent published")
        assertEquals(1_580_194.0, r.marketCap)
        assertNull(r.grossMargin, "no cost-of-goods line on the page")
        assertNull(r.evToEbitda, "no enterprise value and no EBITDA on the page")

        // Computed, so tolerance rather than equality.
        assertClose(1_168.0 / 668.0, assertNotNull(r.pb))
        assertClose(236_753.0 / 27_061.0, assertNotNull(r.interestCoverage))
        assertClose(6.4039330737, assertNotNull(r.revenueCagr3y), 1e-8)
        assertClose(9.6549718535, assertNotNull(r.revenueGrowth), 1e-8)
    }

    @Test
    fun `tcs ratios`() {
        val r = ScreenerFixtures.parse("TCS").snapshot.ratios
        assertEquals(63.0, r.roce)
        assertEquals(51.8, r.roe)
        assertNull(r.debtToEquity)
        assertEquals(27.0, r.operatingMargin)
        assertEquals(48_013.0, r.fcf)
        assertEquals(49_454.0, r.pat)
        assertEquals(0.92, r.cfoToOp)
        assertEquals(14.0, r.pe)
        assertEquals(3.08, r.dividendYield)
        assertEquals(71.77, r.promoterHolding)
        assertEquals(750_753.0, r.marketCap)
        assertClose(2_075.0 / 296.0, assertNotNull(r.pb))
        assertClose(77_958.0 / 1_227.0, assertNotNull(r.interestCoverage))
        assertClose(5.8018486194, assertNotNull(r.revenueCagr3y), 1e-8)
        assertClose(4.5812379565, assertNotNull(r.revenueGrowth), 1e-8)
    }

    @Test
    fun `hdfcbank ratios leave debt to equity null and explain why`() {
        val result = ScreenerFixtures.parse("HDFCBANK")
        val r = result.snapshot.ratios
        assertEquals(7.02, r.roce)
        assertEquals(13.6, r.roe)
        assertNull(r.debtToEquity, "meaningless for banks (plan.md §7.3)")
        assertNull(r.operatingMargin, "the page publishes 'Financing Margin %', not 'OPM %'")
        assertNull(r.interestCoverage, "no Operating Profit row, so no EBIT")
        assertNull(r.promoterHolding, "HDFCBANK's shareholding card publishes no Promoters row")
        assertEquals(110_144.0, r.fcf)
        assertEquals(79_219.0, r.pat)
        assertEquals(0.94, r.cfoToOp)
        assertEquals(14.1, r.pe)
        assertEquals(1.8, r.dividendYield)
        assertEquals(1_111_989.0, r.marketCap)
        assertClose(721.0 / 390.0, assertNotNull(r.pb))
        assertClose(26.8601034556, assertNotNull(r.revenueCagr3y), 1e-8)
        assertClose(3.6412608847, assertNotNull(r.revenueGrowth), 1e-8)

        // The absence is stated, not merely encoded as null.
        val note = result.health.notes.firstOrNull { it.contains("Debt / Equity") }
        assertNotNull(note, "the parser must report D/E as absent from the page")
        assertTrue(note.contains("never 0"), note)
        assertTrue(note.contains("§7.3"), note)
    }

    @Test
    fun `infy ratios`() {
        val r = ScreenerFixtures.parse("INFY").snapshot.ratios
        assertEquals(40.0, r.roce)
        assertEquals(31.9, r.roe)
        assertNull(r.debtToEquity)
        assertEquals(24.0, r.operatingMargin)
        assertEquals(31_259.0, r.fcf)
        assertEquals(29_474.0, r.pat)
        assertEquals(1.01, r.cfoToOp)
        assertEquals(13.5, r.pe)
        assertEquals(4.64, r.dividendYield)
        assertEquals(13.82, r.promoterHolding, "Jun 2026, not Mar 2026")
        assertEquals(420_032.0, r.marketCap)
        assertClose(1_035.0 / 225.0, assertNotNull(r.pb))
        assertClose(47_182.0 / 416.0, assertNotNull(r.interestCoverage))
        assertClose(6.7722024242, assertNotNull(r.revenueCagr3y), 1e-8)
        assertClose(9.6079514081, assertNotNull(r.revenueGrowth), 1e-8)
    }

    @Test
    fun `titan ratios`() {
        val r = ScreenerFixtures.parse("TITAN").snapshot.ratios
        assertEquals(20.5, r.roce)
        assertEquals(37.7, r.roe)
        assertNull(r.debtToEquity)
        assertEquals(10.0, r.operatingMargin)
        assertEquals(4_713.0, r.fcf)
        assertEquals(5_073.0, r.pat)
        assertEquals(0.88, r.cfoToOp)
        assertEquals(68.7, r.pe)
        assertEquals(0.33, r.dividendYield)
        assertEquals(52.9, r.promoterHolding)
        assertEquals(400_898.0, r.marketCap)
        assertClose(4_516.0 / 177.0, assertNotNull(r.pb))
        assertClose(9_183.0 / 1_180.0, assertNotNull(r.interestCoverage))
        assertClose(29.2375597372, assertNotNull(r.revenueCagr3y), 1e-8)
        assertClose(44.8723038243, assertNotNull(r.revenueGrowth), 1e-8)
    }

    // ----------------------------------------------------------------- company identity

    @Test
    fun `company names come from h1`() {
        assertEquals("Reliance Industries Ltd", ScreenerFixtures.parse("RELIANCE").snapshot.name)
        assertEquals("Tata Consultancy Services Ltd", ScreenerFixtures.parse("TCS").snapshot.name)
        assertEquals("HDFC Bank Ltd", ScreenerFixtures.parse("HDFCBANK").snapshot.name)
        assertEquals("Infosys Ltd", ScreenerFixtures.parse("INFY").snapshot.name)
        assertEquals("Titan Company Ltd", ScreenerFixtures.parse("TITAN").snapshot.name)
    }

    // ------------------------------------------------------------------------- health

    @Test
    fun `every fixture parses all seven sections ok`() {
        // Seven, not the plan.md §3.3 six: `RatiosCardParser` now reads the page's `ratios` card,
        // so it is accounted for like any other section. Asserted in full because a section that
        // quietly stops being reported is the plan.md §12 failure this whole accounting exists
        // to prevent.
        assertEquals(
            listOf(
                "top-ratios", "quarters", "profit-loss", "balance-sheet", "cash-flow",
                "ratios", "shareholding",
            ),
            FundamentalsParserFacade.SECTION_IDS,
        )
        for (symbol in ScreenerFixtures.symbols) {
            val result = ScreenerFixtures.parse(symbol)
            assertTrue(result.health.ok, "$symbol: ${result.health.notes}")
            assertEquals(
                FundamentalsParserFacade.SECTION_IDS,
                result.health.sectionsFound,
                symbol,
            )
            assertEquals(emptyList(), result.health.sectionsMissing, symbol)
            assertEquals(result.health, result.snapshot.health, "$symbol must not disagree with itself")
        }
    }

    @Test
    fun `every fixture reports an as of date so staleness is visible`() {
        for (symbol in ScreenerFixtures.symbols) {
            val health = ScreenerFixtures.parse(symbol).health
            val note = health.notes.firstOrNull { it.startsWith("as of ") }
            assertNotNull(note, "$symbol must state its as-of date (plan.md §10)")
            assertTrue(note.contains("latest annual period Mar 2026"), note)
            assertTrue(note.contains("latest quarter 2Q2026"), note)
        }
    }

    @Test
    fun `notes are deduplicated`() {
        for (symbol in ScreenerFixtures.symbols) {
            val notes = ScreenerFixtures.parse(symbol).health.notes
            assertEquals(notes.size, notes.distinct().size, "$symbol repeated a note: $notes")
        }
    }

    // ------------------------------------------------------------------------- helpers

    private fun topRatios(symbol: String): TopRatiosSnapshot =
        TopRatiosParser.parse(ScreenerHtml.document(ScreenerFixtures.html(symbol)))

    private fun assertClose(expected: Double, actual: Double, delta: Double = tolerance) {
        assertTrue(
            kotlin.math.abs(expected - actual) <= delta,
            "expected $expected but was $actual (delta $delta)",
        )
    }
}
