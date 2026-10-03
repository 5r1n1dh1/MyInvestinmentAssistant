package com.mia.app.data.android.db

import com.mia.app.data.price.BhavcopyRow
import com.mia.app.data.symbols.SymbolRecord
import com.mia.app.domain.model.AnnualRow
import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.CashFlowDerived
import com.mia.app.domain.model.Category
import com.mia.app.domain.model.Confidence
import com.mia.app.domain.model.Direction
import com.mia.app.domain.model.MeasuredMove
import com.mia.app.domain.model.Match
import com.mia.app.domain.model.MatchScorePolicy
import com.mia.app.domain.model.PatternOutcome
import com.mia.app.domain.model.Pivot
import com.mia.app.domain.model.PivotKind
import com.mia.app.domain.model.QuarterRecord
import com.mia.app.domain.model.Ratios
import com.mia.app.domain.model.ScoreBreakdown
import com.mia.app.domain.model.Sector
import com.mia.app.domain.model.Source
import com.mia.app.domain.model.Timeframe
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Round-trip tests for [Mappers].
 *
 * ### What these are actually protecting
 * * **Nulls survive.** plan.md §3.3/§7.3: a blank in the source must never become `0.0`, because a
 *   fabricated zero manufactures a red flag (plan.md §8.2). A mapper that coalesced a null would
 *   break the most safety-critical rule in the app.
 * * **Enums round-trip by name, not ordinal.** An ordinal is renumbered the moment an enum is
 *   reordered, and this database has to survive app updates with its accuracy record intact.
 * * **An unknown enum name degrades to a stated default** rather than throwing at a query site, so
 *   a database written by a *newer* build is still readable.
 * * **The presence payload is lossless enough for the tracker** to see a corporate action.
 *
 * Everything here is pure Kotlin, so it runs without an Android runtime.
 */
class MappersTest {

    private val session: LocalDate = LocalDate.parse("2026-10-01")

    // ------------------------------------------------------------------------------ candle

    private fun candle(
        delivery: Double? = null,
        source: Source = Source.NSE_BHAVCOPY,
        timeframe: Timeframe = Timeframe.D1,
    ) = Candle(
        symbol = "RELIANCE",
        timeframe = timeframe,
        date = session,
        open = 1160.0,
        high = 1172.35,
        low = 1158.1,
        close = 1167.70,
        volume = 16_771_221L,
        deliveryPercent = delivery,
        source = source,
    )

    @Test
    fun `a candle without delivery round-trips with its null intact`() {
        val original = candle(delivery = null)
        val restored = Mappers.toCandle(Mappers.fromCandle(original))

        assertEquals(original, restored)
        assertNull(restored.deliveryPercent, "a missing DELIV_PER must stay null, never 0.0 (plan.md §8.2)")
    }

    @Test
    fun `a candle with delivery round-trips the published percentage`() {
        // plan.md §14's captured RELIANCE volume is 16,771,221; the delivery figure here is
        // illustrative and only the round-trip matters.
        val original = candle(delivery = 42.5)
        val restored = Mappers.toCandle(Mappers.fromCandle(original))

        assertEquals(original, restored)
        assertEquals(42.5, restored.deliveryPercent)
    }

    @Test
    fun `both timeframes round-trip and the date is stored as sortable ISO text`() {
        val weekly = candle(timeframe = Timeframe.W1, source = Source.YAHOO)
        val entity = Mappers.fromCandle(weekly)

        assertEquals("W1", entity.timeframe)
        assertEquals("2026-10-01", entity.date)
        assertEquals(weekly, Mappers.toCandle(entity))
    }

    // ----------------------------------------------------------------------------- symbol

    @Test
    fun `a symbol record round-trips including its nullable listing date`() {
        val record = SymbolRecord(
            symbol = "RELIANCE",
            name = "Reliance Industries Limited",
            series = "EQ",
            isin = "INE002A01018",
            listingDate = LocalDate.parse("1995-11-29"),
            paidUpValue = 1234.5,
            lotSize = 500L,
            faceValue = 10.0,
            source = Source.NSE_EQUITY_MASTER,
        )
        val restored = Mappers.toRecord(Mappers.fromRecord(record))

        assertEquals(record, restored)
    }

    @Test
    fun `a symbol record with no listing date round-trips`() {
        val record = SymbolRecord(
            symbol = "TMCV",
            name = "Tata Motors Limited",
            series = "EQ",
            isin = "INE1TAE01010",
            listingDate = null,
            paidUpValue = null,
            lotSize = null,
            faceValue = null,
        )
        val restored = Mappers.toRecord(Mappers.fromRecord(record))

        assertEquals(record, restored)
        assertNull(restored.listingDate)
    }

    @Test
    fun `an ISIN is stored upper-cased so case cannot fork one identity into two`() {
        val record = SymbolRecord(
            symbol = "RELIANCE",
            name = "Reliance Industries Limited",
            series = "EQ",
            isin = "ine002a01018",
        )
        val entity = Mappers.fromRecord(record)

        assertEquals("INE002A01018", entity.isin)
        assertEquals("INE002A01018", Mappers.fromRecord(Mappers.toRecord(entity)).isin)
    }

    // ------------------------------------------------------------------------------ ratios

    @Test
    fun `a fully populated ratios row round-trips every nullable field`() {
        val ratios = Ratios(
            symbol = "TCS",
            sector = Sector.IT,
            asOf = session,
            source = Source.SCREENER_IN,
            confidence = Confidence.AGREED,
            roce = 44.5,
            roe = 51.8,
            debtToEquity = null,
            interestCoverage = 24.1,
            operatingMargin = 27.3,
            fcf = 18_000.0,
            pat = 24_500.0,
            cfoToOp = 1.12,
            revenueCagr3y = 14.3,
            revenueGrowth = 11.1,
            grossMargin = null,
            pe = 14.0,
            pb = 8.1,
            evToEbitda = null,
            dividendYield = 2.4,
            promoterHolding = 31.2,
            marketCap = 1_400_000.0,
        )
        val restored = Mappers.toRatios(Mappers.fromRatios(ratios))

        assertEquals(ratios, restored)
        // plan.md §3.3: screener.in publishes no cost of goods and no EV, so those are absent.
        assertNull(restored.grossMargin)
        assertNull(restored.evToEbitda)
        // plan.md §7.3: a bank's D/E is absent, not zero.
        assertNull(restored.debtToEquity)
    }

    @Test
    fun `an all-null ratios row round-trips rather than becoming zeros`() {
        val ratios = Ratios(
            symbol = "UNKNOWN",
            sector = Sector.OTHER,
            asOf = session,
            source = Source.SCREENER_IN,
            confidence = Confidence.SINGLE,
        )
        val restored = Mappers.toRatios(Mappers.fromRatios(ratios))

        assertEquals(ratios, restored)
        assertNull(restored.pe)
        assertNull(restored.marketCap)
        assertEquals(Sector.OTHER, restored.sector)
    }

    @Test
    fun `an unrecognised sector degrades to OTHER and never to a guess`() {
        // plan.md §1.4 rule 8: default to OTHER, never guess.
        val entity = Mappers.fromRatios(
            Ratios(
                symbol = "X",
                sector = Sector.BANKING,
                asOf = session,
                source = Source.SCREENER_IN,
                confidence = Confidence.SINGLE,
            ),
        ).copy(sector = "CRYPTO")

        assertEquals(Sector.OTHER, Mappers.toRatios(entity).sector)
    }

    @Test
    fun `an unrecognised source degrades to DERIVED`() {
        val entity = Mappers.fromRatios(
            Ratios(
                symbol = "X",
                sector = Sector.IT,
                asOf = session,
                source = Source.YAHOO,
                confidence = Confidence.SINGLE,
            ),
        ).copy(source = "SOME_FUTURE_VENDOR")

        assertEquals(Source.DERIVED, Mappers.toRatios(entity).source)
    }

    // -------------------------------------------------------------------------- quarters

    @Test
    fun `a quarter row round-trips every nullable metric`() {
        val record = QuarterRecord(
            year = 2026,
            quarter = 2,
            sales = 24_500.0,
            expenses = 18_000.0,
            operatingProfit = 6_500.0,
            opmPercent = 26.5,
            otherIncome = null,
            interest = 120.0,
            depreciation = 900.0,
            eps = null,
        )
        val restored = Mappers.toQuarter(Mappers.fromQuarter("TCS", record))

        assertEquals(record, restored)
        assertNull(restored.eps)
        assertNull(restored.otherIncome)
    }

    // ------------------------------------------------------------- statement row storage

    @Test
    fun `annual statement rows round-trip through the normalised table`() {
        val rows = listOf(
            AnnualRow("Sales", listOf(40_000.0, 45_000.0)),
            AnnualRow("Expenses", listOf(null, 33_000.0)),
            AnnualRow("Operating Profit", listOf(12_000.0, null)),
        )
        val years = listOf(2026, 2025)

        val entities = Mappers.toStatementRows(Mappers.Statements.PROFIT_LOSS, "TCS", rows, years)
        val restored = Mappers.fromStatementRows(entities)

        assertEquals(rows, restored)
        // A missing year/value stays null rather than becoming 0.0.
        assertNull(restored[1].values[0])
        assertNull(restored[2].values[1])
    }

    @Test
    fun `statement rows with more values than fiscal years keep the known years only`() {
        val rows = listOf(AnnualRow("Sales", listOf(1.0, 2.0, 3.0)))
        val entities = Mappers.toStatementRows(Mappers.Statements.CASH_FLOW, "X", rows, listOf(2026))

        // One header was available, so one cell is stored — the other two are not guessable.
        assertEquals(1, entities.size)
        assertEquals(2026, entities.single().fiscalYear)
    }

    // ------------------------------------------------------------------- derived metrics

    @Test
    fun `the cash-flow derived maps round-trip through the normalised table`() {
        val derived = CashFlowDerived(
            cfoByYear = mapOf(2026 to 20_000.0, 2025 to 18_500.0),
            patByYear = mapOf(2026 to 12_000.0),
            fcfByYear = mapOf(2026 to 9_000.0),
            cfoToOpByYear = mapOf(2026 to 1.4),
            receivablesByYear = mapOf(2026 to 4_200.0),
            inventoryByYear = mapOf(2026 to 3_100.0),
            revenueByYear = mapOf(2026 to 40_000.0),
            otherIncomeByYear = emptyMap(),
            totalAssetsByYear = mapOf(2026 to 90_000.0),
            goodwillByYear = emptyMap(),
            debtByYear = mapOf(2026 to 22_000.0),
            equityByYear = mapOf(2026 to 51_000.0),
            interestByYear = mapOf(2026 to 700.0),
            ebitByYear = mapOf(2026 to 13_000.0),
            sharesOutstanding = 3_600_000_000.0,
            promoterPledgePercent = null,
        )

        val restored = Mappers.toDerived(Mappers.fromDerived("TCS", session, derived))

        assertEquals(derived, restored)
        assertNull(restored.promoterPledgePercent, "a source with no pledge figure must stay null")
        assertEquals(0, Mappers.DerivedMetrics.SCALAR_YEAR, "the scalar year can never be a fiscal year")
    }

    @Test
    fun `an empty derived set round-trips to the empty object`() {
        val restored = Mappers.toDerived(emptyList())

        assertEquals(CashFlowDerived(), restored)
        assertNull(restored.sharesOutstanding)
    }

    @Test
    fun `every declared derived metric has a stable key`() {
        // plan.md §8.1's evidence view renders these numbers, so the keys are part of the schema.
        assertEquals(16, Mappers.DerivedMetrics.ALL_METRICS.size)
        assertEquals(16, Mappers.DerivedMetrics.ALL_METRICS.distinct().size)
    }

    // ---------------------------------------------------------------------------- pattern

    private fun match(
        score: Int = 78,
        measuredMove: MeasuredMove? = MeasuredMove(
            height = 60.0,
            triggerLevel = 900.0,
            direction = Direction.UP,
            projectedLevel = 960.0,
        ),
        pivots: List<Pivot> = listOf(
            Pivot(barIndex = 40, date = session.minusDays(20), price = 900.0, kind = PivotKind.LOW, legIndex = 0),
            Pivot(barIndex = 55, date = session.minusDays(5), price = 960.0, kind = PivotKind.HIGH, legIndex = 1),
        ),
    ) = Match(
        patternId = 7L,
        name = "Head & Shoulders",
        score = score,
        pivots = pivots,
        measuredMove = measuredMove,
        category = Category.REVERSAL,
        timeframe = Timeframe.D1,
        detectedOn = session,
        breakdown = ScoreBreakdown(80, 75, 70, 65),
    )

    @Test
    fun `a pattern row round-trips through the entity with its score breakdown`() {
        val original = match()
        val entity = Mappers.toPattern(
            symbol = "RELIANCE",
            match = original,
            verdict = MatchScorePolicy.Verdict.PROJECT,
            barCount = 300,
            notes = "note",
        )

        assertEquals("Head & Shoulders", entity.name)
        assertEquals(7L, entity.patternId)
        assertEquals("2026-10-01", entity.detectedOn)
        assertEquals(MatchScorePolicy.Verdict.PROJECT.name, entity.verdict)

        val restored = Mappers.toMatch(
            entity,
            original.pivots.mapIndexed { index, pivot -> Mappers.fromPivot(0L, index, pivot) },
        )
        assertEquals(original, restored)
    }

    @Test
    fun `a candlestick match with no measured move keeps all three move fields null`() {
        // spec DD-1: a candlestick formation has no height to project.
        val original = match(measuredMove = null, pivots = emptyList())
        val entity = Mappers.toPattern("X", original, MatchScorePolicy.Verdict.RENDER, 300, "")

        assertNull(entity.moveHeight)
        assertNull(entity.moveTrigger)
        assertNull(entity.moveProjected)
        assertNull(entity.moveDirection)
        assertNull(Mappers.measuredMoveOf(entity), "a partial move must not be reconstructed")
    }

    @Test
    fun `a partially stored measured move is treated as absent rather than half-drawn`() {
        val original = match()
        val truncated = Mappers.toPattern("X", original, MatchScorePolicy.Verdict.RENDER, 300, "")
            .copy(moveProjected = null)

        assertNull(Mappers.measuredMoveOf(truncated))
    }

    @Test
    fun `a score breakdown missing one factor becomes null rather than a fabricated zero`() {
        val entity = Mappers.toPattern("X", match(), MatchScorePolicy.Verdict.RENDER, 300, "")
            .copy(context = null)
        val restored = Mappers.toMatch(entity, emptyList())

        assertNull(restored.breakdown, "an incomplete breakdown must not read as four factors")
    }

    @Test
    fun `pivots round-trip with their bar index, leg index and kind`() {
        val original = match()
        val restored = Mappers.toMatch(
            Mappers.toPattern("X", original, MatchScorePolicy.Verdict.RENDER, 300, ""),
            original.pivots.mapIndexed { index, pivot -> Mappers.fromPivot(0L, index, pivot) },
        )

        assertEquals(original.pivots, restored.pivots)
        assertEquals(PivotKind.LOW, restored.pivots[0].kind)
        assertEquals(40, restored.pivots[0].barIndex)
        assertEquals(1, restored.pivots[1].legIndex)
    }

    // -------------------------------------------------------------------------- outcomes

    @Test
    fun `a resolved outcome round-trips every field including the verdict`() {
        val outcome = PatternOutcome(
            patternId = 12L,
            symbol = "RELIANCE",
            patternName = "Double Top",
            timeframe = Timeframe.D1,
            category = Category.REVERSAL,
            projectedOn = session,
            score = 74,
            direction = Direction.DOWN,
            projectedLevel = 950.0,
            actualHigh = 962.0,
            actualLow = 941.0,
            hit = true,
            resolved = true,
            horizonBars = 5,
        )
        val restored = Mappers.toOutcome(Mappers.fromOutcome(outcome.patternId, outcome))

        assertEquals(outcome, restored)
        assertTrue(restored.resolved)
        assertEquals(true, restored.hit)
    }

    @Test
    fun `an open outcome keeps hit null so a half-elapsed window is never counted`() {
        // plan.md §6.1 rule 5: a provisional hit would be counted as a fact.
        val outcome = PatternOutcome(
            patternId = 12L,
            symbol = "RELIANCE",
            patternName = "Double Top",
            timeframe = Timeframe.D1,
            category = Category.REVERSAL,
            projectedOn = session,
            score = 74,
            direction = Direction.DOWN,
            projectedLevel = 950.0,
            actualHigh = 962.0,
            actualLow = null,
            hit = null,
            resolved = false,
        )
        val restored = Mappers.toOutcome(Mappers.fromOutcome(outcome.patternId, outcome))

        assertEquals(outcome, restored)
        assertNull(restored.hit)
        assertTrue(!restored.resolved)
        assertNull(restored.actualLow)
    }

    // -------------------------------------------------------------- market presence blob

    @Test
    fun `the presence payload round-trips through its text encoding`() {
        val original = listOf(
            bhavcopyRow("RELIANCE", "INE002A01018", "EQ"),
            bhavcopyRow("TCS", "INE467B01029", "EQ"),
            // A row with no published ISIN encodes as empty and decodes as empty, never as a
            // fabricated key: the corporate-action signal must come from a real identity change.
            bhavcopyRow("SMALL", null, "SM"),
        )

        val payload = Mappers.encodePresence(original)
        val restored = Mappers.decodePresence(payload)

        assertEquals(3, restored.size)
        assertEquals(Triple("RELIANCE", "INE002A01018", "EQ"), restored[0])
        assertEquals(Triple("TCS", "INE467B01029", "EQ"), restored[1])
        assertEquals(Triple("SMALL", "", "SM"), restored[2])
    }

    @Test
    fun `a blank or malformed presence payload decodes to nothing instead of throwing`() {
        val nothing: List<Triple<String, String, String>> = emptyList()

        assertEquals(nothing, Mappers.decodePresence(""))
        assertEquals(nothing, Mappers.decodePresence("   \n  \n"))
        // A truncated line (no series column) is skipped, not turned into a partial identity.
        assertEquals(nothing, Mappers.decodePresence("RELIANCE|INE002A01018"))
    }

    @Test
    fun `presence tickers and ISINs are normalised so reconciliation cannot fork on case`() {
        val payload = Mappers.encodePresence(
            listOf(bhavcopyRow("reliance ", "ine002a01018", "EQ")),
        )
        val decoded = Mappers.decodePresence(payload).single()

        assertEquals("RELIANCE", decoded.first)
        assertEquals("INE002A01018", decoded.second)
    }

    private fun bhavcopyRow(
        ticker: String,
        isin: String?,
        series: String,
    ) = BhavcopyRow(
        tradDt = session,
        isin = isin,
        ticker = ticker,
        series = series,
        open = 1160.0,
        high = 1172.35,
        low = 1158.1,
        close = 1167.70,
        volume = 16_771_221L,
    )

    // ------------------------------------------------------------------------------ misc

    @Test
    fun `the ratio field keys are the canonical field names`() {
        val keys = Mappers.ratioFieldKeys()

        assertTrue(keys.contains("ROCE"), "plan.md §5.1's ratio set must be addressable by name")
        assertTrue(keys.contains("MARKET_CAP"))
        assertEquals(keys.size, keys.distinct().size)
    }

    @Test
    fun `every storage key function round-trips its enum`() {
        assertEquals(Timeframe.W1, Mappers.timeframeOrDefault(Mappers.timeframeKey(Timeframe.W1)))
        assertEquals(Source.SCREENER_IN, Mappers.sourceOrDefault(Mappers.sourceKey(Source.SCREENER_IN)))
        assertEquals(Sector.PHARMA, Mappers.sectorOrDefault(Mappers.sectorKey(Sector.PHARMA)))
        assertEquals(Confidence.CONFLICT, Mappers.confidenceOrDefault(Mappers.confidenceKey(Confidence.CONFLICT)))
        assertEquals(Category.CONTINUATION, Mappers.categoryOrDefault(Mappers.categoryKey(Category.CONTINUATION)))
        assertEquals(Direction.DOWN, Mappers.directionOrDefault(Mappers.directionKey(Direction.DOWN)))
        assertEquals(PivotKind.HIGH, Mappers.pivotKindOrDefault(Mappers.pivotKindKey(PivotKind.HIGH)))
        assertEquals(
            MatchScorePolicy.Verdict.SUPPRESS,
            Mappers.verdictOrDefault(Mappers.verdictKey(MatchScorePolicy.Verdict.SUPPRESS)),
        )
    }

    @Test
    fun `an unknown timeframe degrades to D1 the only series with an EOD source`() {
        assertEquals(Timeframe.D1, Mappers.timeframeOrDefault("D5"))
        assertEquals(Timeframe.D1, Mappers.timeframeOrDefault(""))
    }

    @Test
    fun `the statement section ids are the screener section ids`() {
        // They are stored in the `statement` column, and a lookup against a
        // `FundamentalsParseResult.sections` map must find them by the same key.
        assertEquals(
            listOf("profit-loss", "balance-sheet", "cash-flow", "shareholding"),
            Mappers.Statements.ALL,
        )
        assertNotNull(Mappers.Statements.fiscalYearOf("Mar 2026"))
        assertEquals(2026, Mappers.Statements.fiscalYearOf("Mar 2026"))
        assertNull(Mappers.Statements.fiscalYearOf("TTM"))
    }
}