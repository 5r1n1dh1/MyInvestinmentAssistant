package com.mia.app.data.android.db

import androidx.annotation.RestrictTo
import com.mia.app.data.aggregate.RatioFieldId
import com.mia.app.data.price.BhavcopyRow
import com.mia.app.data.symbols.SymbolNormaliser
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
import com.mia.app.data.symbols.SymbolRecord
import java.time.LocalDate

/**
 * Entity ↔ domain conversion. The only place the two vocabularies meet.
 *
 * ### Rules
 * * **Enums round-trip by `name`,** never by ordinal — an ordinal is renumbered the moment an
 *   enum is reordered, and this database has to survive app updates with its accuracy record
 *   intact (plan.md §6.1). Every reader has a documented fallback for a name this build does not
 *   know, so a database written by a *newer* build degrades to a stated default instead of
 *   throwing at a query site.
 * * **Every unknown-name fallback is written down.** An unrecognised `Source` becomes
 *   `Source.DERIVED` and an unrecognised `Sector` becomes `Sector.OTHER` (plan.md §1.4 rule 8:
 *   default to OTHER, never guess).
 * * Nothing in this file touches Android, so the whole file is unit-testable on a plain JVM —
 *   which is the only reason the round-trip tests can exist at all on this build machine.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
object Mappers {

    // ---------------------------------------------------------------- enum storage keys

    /** `"D1"` / `"W1"`. Unknown text falls back to D1, the only series with an EOD source. */
    fun timeframeKey(timeframe: Timeframe): String = timeframe.name

    fun timeframeOrDefault(key: String): Timeframe =
        Timeframe.fromStorageKey(key) ?: Timeframe.D1

    fun sourceKey(source: Source): String = source.name

    fun sourceOrDefault(key: String): Source =
        Source.entries.firstOrNull { it.name == key } ?: Source.DERIVED

    fun sectorKey(sector: Sector): String = sector.name

    fun sectorOrDefault(key: String): Sector =
        Sector.entries.firstOrNull { it.name == key } ?: Sector.OTHER

    fun confidenceKey(confidence: Confidence): String = confidence.name

    fun confidenceOrDefault(key: String): Confidence =
        Confidence.entries.firstOrNull { it.name == key } ?: Confidence.SINGLE

    fun categoryKey(category: Category): String = category.name

    fun categoryOrDefault(key: String): Category =
        Category.entries.firstOrNull { it.name == key } ?: Category.REVERSAL

    fun directionKey(direction: Direction): String = direction.name

    fun directionOrDefault(key: String): Direction =
        Direction.entries.firstOrNull { it.name == key } ?: Direction.UP

    fun pivotKindKey(kind: PivotKind): String = kind.name

    fun pivotKindOrDefault(key: String): PivotKind =
        PivotKind.entries.firstOrNull { it.name == key } ?: PivotKind.HIGH

    fun verdictKey(verdict: MatchScorePolicy.Verdict): String = verdict.name

    fun verdictOrDefault(key: String): MatchScorePolicy.Verdict =
        MatchScorePolicy.Verdict.entries.firstOrNull { it.name == key } ?: MatchScorePolicy.Verdict.SUPPRESS

    // ---------------------------------------------------------------------------- candles

    fun toCandle(entity: CandleEntity): Candle = Candle(
        symbol = entity.symbol,
        timeframe = timeframeOrDefault(entity.timeframe),
        date = LocalDate.parse(entity.date),
        open = entity.open,
        high = entity.high,
        low = entity.low,
        close = entity.close,
        volume = entity.volume,
        deliveryPercent = entity.deliveryPercent,
        source = sourceOrDefault(entity.source),
    )

    fun fromCandle(candle: Candle): CandleEntity = CandleEntity(
        symbol = candle.symbol,
        timeframe = timeframeKey(candle.timeframe),
        date = candle.date.toString(),
        open = candle.open,
        high = candle.high,
        low = candle.low,
        close = candle.close,
        volume = candle.volume,
        deliveryPercent = candle.deliveryPercent,
        source = sourceKey(candle.source),
    )

    // ---------------------------------------------------------------------------- symbols

    fun toRecord(entity: SymbolEntity): SymbolRecord = SymbolRecord(
        symbol = entity.symbol,
        name = entity.name,
        series = entity.series,
        isin = entity.isin,
        listingDate = entity.listingDate?.takeIf { it.isNotBlank() }?.let(LocalDate::parse),
        paidUpValue = entity.paidUpValue,
        lotSize = entity.lotSize,
        faceValue = entity.faceValue,
        source = sourceOrDefault(entity.source),
    )

    fun fromRecord(record: SymbolRecord): SymbolEntity = SymbolEntity(
        symbol = record.symbol,
        name = record.name,
        // Stored through the same normaliser `:data` resolves with, so `INE002A01018` and
        // `ine002a01018` cannot become two identities (plan.md §9).
        isin = SymbolNormaliser.isinKey(record.isin),
        series = record.series,
        listingDate = record.listingDate?.toString(),
        paidUpValue = record.paidUpValue,
        lotSize = record.lotSize,
        faceValue = record.faceValue,
        source = sourceKey(record.source),
    )

    // ----------------------------------------------------------------------------- ratios

    fun toRatios(entity: RatiosEntity): Ratios = Ratios(
        symbol = entity.symbol,
        sector = sectorOrDefault(entity.sector),
        industry = entity.industry,
        asOf = LocalDate.parse(entity.asOf),
        source = sourceOrDefault(entity.source),
        confidence = confidenceOrDefault(entity.confidence),
        roce = entity.roce,
        roe = entity.roe,
        debtToEquity = entity.debtToEquity,
        interestCoverage = entity.interestCoverage,
        operatingMargin = entity.operatingMargin,
        fcf = entity.fcf,
        pat = entity.pat,
        cfoToOp = entity.cfoToOp,
        revenueCagr3y = entity.revenueCagr3y,
        revenueGrowth = entity.revenueGrowth,
        grossMargin = entity.grossMargin,
        pe = entity.pe,
        pb = entity.pb,
        evToEbitda = entity.evToEbitda,
        dividendYield = entity.dividendYield,
        promoterHolding = entity.promoterHolding,
        marketCap = entity.marketCap,
    )

    fun fromRatios(ratios: Ratios): RatiosEntity = RatiosEntity(
        symbol = ratios.symbol,
        asOf = ratios.asOf.toString(),
        sector = sectorKey(ratios.sector),
        industry = ratios.industry,
        source = sourceKey(ratios.source),
        confidence = confidenceKey(ratios.confidence),
        roce = ratios.roce,
        roe = ratios.roe,
        debtToEquity = ratios.debtToEquity,
        interestCoverage = ratios.interestCoverage,
        operatingMargin = ratios.operatingMargin,
        fcf = ratios.fcf,
        pat = ratios.pat,
        cfoToOp = ratios.cfoToOp,
        revenueCagr3y = ratios.revenueCagr3y,
        revenueGrowth = ratios.revenueGrowth,
        grossMargin = ratios.grossMargin,
        pe = ratios.pe,
        pb = ratios.pb,
        evToEbitda = ratios.evToEbitda,
        dividendYield = ratios.dividendYield,
        promoterHolding = ratios.promoterHolding,
        marketCap = ratios.marketCap,
    )

    // -------------------------------------------------------------------------- quarters

    fun toQuarter(entity: QuarterEntity): QuarterRecord = QuarterRecord(
        year = entity.year,
        quarter = entity.quarter,
        sales = entity.sales,
        expenses = entity.expenses,
        operatingProfit = entity.operatingProfit,
        opmPercent = entity.opmPercent,
        otherIncome = entity.otherIncome,
        interest = entity.interest,
        depreciation = entity.depreciation,
        eps = entity.eps,
    )

    fun fromQuarter(symbol: String, record: QuarterRecord): QuarterEntity = QuarterEntity(
        symbol = symbol,
        year = record.year,
        quarter = record.quarter,
        sales = record.sales,
        expenses = record.expenses,
        operatingProfit = record.operatingProfit,
        opmPercent = record.opmPercent,
        otherIncome = record.otherIncome,
        interest = record.interest,
        depreciation = record.depreciation,
        eps = record.eps,
    )

    // ------------------------------------------------------------- annual statement rows

    /**
     * Statement sections of `FundamentalsSnapshot`, keyed by the **screener.in section id** so a
     * `FundamentalsParseResult.sections` map can be stored by looking the key up directly.
     * See `com.mia.app.data.fundamentals.ScreenerHtml`.
     */
    object Statements {
        const val PROFIT_LOSS: String = "profit-loss"
        const val BALANCE_SHEET: String = "balance-sheet"
        const val CASH_FLOW: String = "cash-flow"
        const val SHAREHOLDING: String = "shareholding"

        val ALL: List<String> = listOf(PROFIT_LOSS, BALANCE_SHEET, CASH_FLOW, SHAREHOLDING)

        /**
         * `Mar 2026` → `2026`. An Indian FY ending 31 March is labelled by its starting year,
         * which is the number the red-flag rules and the derived metrics are keyed on.
         * Delegated to `:data` so the fiscal-year convention has exactly one implementation.
         */
        fun fiscalYearOf(header: String?): Int? =
            com.mia.app.data.fundamentals.ScreenerHtml.yearOf(header)
    }

    /**
     * `AnnualRow` values are positionally aligned to the table's year columns, so the fiscal
     * year comes from the header and is stored with the value rather than guessed from order.
     */
    fun toStatementRows(
        statement: String,
        symbol: String,
        rows: List<AnnualRow>,
        years: List<Int>,
    ): List<StatementRowEntity> = buildList {
        rows.forEachIndexed { ordinal: Int, row: AnnualRow ->
            row.values.forEachIndexed { index: Int, value: Double? ->
                val year = years.getOrNull(index) ?: return@forEachIndexed
                add(
                    StatementRowEntity(
                        symbol = symbol,
                        statement = statement,
                        fiscalYear = year,
                        ordinal = ordinal,
                        label = row.label,
                        value = value,
                    ),
                )
            }
        }
    }

    /** Rebuilds `List<AnnualRow>` from storage, restoring the published order. */
    fun fromStatementRows(rows: List<StatementRowEntity>): List<AnnualRow> {
        val years = rows.map { it.fiscalYear }.distinct().sortedDescending()
        val byOrdinal = rows.groupBy { it.ordinal }
        return byOrdinal.keys.sorted().map { ordinal ->
            val cells = byOrdinal.getValue(ordinal).associateBy { it.fiscalYear }
            AnnualRow(
                label = cells.values.first().label,
                values = years.map { cells[it]?.value },
            )
        }
    }

    // ------------------------------------------------------------------- derived metrics

    /**
     * `CashFlowDerived` is a bag of `year -> value` maps plus two scalars, which is exactly a
     * normalised `(metric, fiscalYear)` row set. The two scalars are stored under
     * [SCALAR_YEAR], which no fiscal year can occupy because a fiscal year is a four-digit
     * Indian year, i.e. always ≥ 1900.
     */
    object DerivedMetrics {
        const val SCALAR_YEAR: Int = 0

        const val CFO: String = "cfo"
        const val PAT: String = "pat"
        const val FCF: String = "fcf"
        const val CFO_TO_OP: String = "cfoToOp"
        const val RECEIVABLES: String = "receivables"
        const val INVENTORY: String = "inventory"
        const val REVENUE: String = "revenue"
        const val OTHER_INCOME: String = "otherIncome"
        const val TOTAL_ASSETS: String = "totalAssets"
        const val GOODWILL: String = "goodwill"
        const val DEBT: String = "debt"
        const val EQUITY: String = "equity"
        const val INTEREST: String = "interest"
        const val EBIT: String = "ebit"
        const val SHARES_OUTSTANDING: String = "sharesOutstanding"
        const val PROMOTOR_PLEDGE: String = "promoterPledgePercent"

        /** Every map on [CashFlowDerived], in declaration order. */
        val MAP_METRICS: List<Pair<String, (CashFlowDerived) -> Map<Int, Double>>> = listOf(
            CFO to { it.cfoByYear },
            PAT to { it.patByYear },
            FCF to { it.fcfByYear },
            CFO_TO_OP to { it.cfoToOpByYear },
            RECEIVABLES to { it.receivablesByYear },
            INVENTORY to { it.inventoryByYear },
            REVENUE to { it.revenueByYear },
            OTHER_INCOME to { it.otherIncomeByYear },
            TOTAL_ASSETS to { it.totalAssetsByYear },
            GOODWILL to { it.goodwillByYear },
            DEBT to { it.debtByYear },
            EQUITY to { it.equityByYear },
            INTEREST to { it.interestByYear },
            EBIT to { it.ebitByYear },
        )

        val ALL_METRICS: List<String> = MAP_METRICS.map { it.first } +
            SHARES_OUTSTANDING + PROMOTOR_PLEDGE
    }

    fun fromDerived(
        symbol: String,
        asOf: LocalDate,
        derived: CashFlowDerived,
    ): List<DerivedMetricEntity> = buildList {
        DerivedMetrics.MAP_METRICS.forEach { (metric, getter) ->
            getter(derived).forEach { (year, value) ->
                add(DerivedMetricEntity(symbol, asOf.toString(), metric, year, value))
            }
        }
        derived.sharesOutstanding?.let {
            add(DerivedMetricEntity(symbol, asOf.toString(), DerivedMetrics.SHARES_OUTSTANDING, DerivedMetrics.SCALAR_YEAR, it))
        }
        derived.promoterPledgePercent?.let {
            add(DerivedMetricEntity(symbol, asOf.toString(), DerivedMetrics.PROMOTOR_PLEDGE, DerivedMetrics.SCALAR_YEAR, it))
        }
    }

    fun toDerived(rows: List<DerivedMetricEntity>): CashFlowDerived {
        val maps = HashMap<String, MutableMap<Int, Double>>()
        var shares: Double? = null
        var pledge: Double? = null
        for (row in rows) {
            when (row.metric) {
                DerivedMetrics.SHARES_OUTSTANDING -> if (row.fiscalYear == DerivedMetrics.SCALAR_YEAR) shares = row.value
                DerivedMetrics.PROMOTOR_PLEDGE -> if (row.fiscalYear == DerivedMetrics.SCALAR_YEAR) pledge = row.value
                else -> maps.getOrPut(row.metric) { LinkedHashMap() }[row.fiscalYear] = row.value
            }
        }
        fun metric(key: String): Map<Int, Double> = maps[key].orEmpty()

        return CashFlowDerived(
            cfoByYear = metric(DerivedMetrics.CFO),
            patByYear = metric(DerivedMetrics.PAT),
            fcfByYear = metric(DerivedMetrics.FCF),
            cfoToOpByYear = metric(DerivedMetrics.CFO_TO_OP),
            receivablesByYear = metric(DerivedMetrics.RECEIVABLES),
            inventoryByYear = metric(DerivedMetrics.INVENTORY),
            revenueByYear = metric(DerivedMetrics.REVENUE),
            otherIncomeByYear = metric(DerivedMetrics.OTHER_INCOME),
            totalAssetsByYear = metric(DerivedMetrics.TOTAL_ASSETS),
            goodwillByYear = metric(DerivedMetrics.GOODWILL),
            debtByYear = metric(DerivedMetrics.DEBT),
            equityByYear = metric(DerivedMetrics.EQUITY),
            interestByYear = metric(DerivedMetrics.INTEREST),
            ebitByYear = metric(DerivedMetrics.EBIT),
            sharesOutstanding = shares,
            promoterPledgePercent = pledge,
        )
    }

    // --------------------------------------------------------------------------- patterns

    fun toPivot(entity: PatternPivotEntity): Pivot = Pivot(
        barIndex = entity.barIndex,
        date = LocalDate.parse(entity.date),
        price = entity.price,
        kind = pivotKindOrDefault(entity.kind),
        legIndex = entity.legIndex,
    )

    fun fromPivot(patternRowId: Long, index: Int, pivot: Pivot): PatternPivotEntity =
        PatternPivotEntity(
            patternId = patternRowId,
            pivotIndex = index,
            price = pivot.price,
            kind = pivotKindKey(pivot.kind),
            barIndex = pivot.barIndex,
            date = pivot.date.toString(),
            legIndex = pivot.legIndex,
        )

    fun toPattern(
        symbol: String,
        match: Match,
        verdict: MatchScorePolicy.Verdict,
        barCount: Int,
        notes: String,
    ): PatternEntity = PatternEntity(
        patternId = match.patternId,
        symbol = symbol,
        timeframe = timeframeKey(match.timeframe),
        name = match.name,
        score = match.score,
        detectedOn = match.detectedOn.toString(),
        category = categoryKey(match.category),
        verdict = verdictKey(verdict),
        geometry = match.breakdown?.geometry,
        structure = match.breakdown?.structure,
        confirmation = match.breakdown?.confirmation,
        context = match.breakdown?.context,
        moveHeight = match.measuredMove?.height,
        moveTrigger = match.measuredMove?.triggerLevel,
        moveProjected = match.measuredMove?.projectedLevel,
        moveDirection = match.measuredMove?.let { directionKey(it.direction) },
        barCount = barCount,
        notes = notes,
    )

    /** Rebuilds a `Match` from storage. [pivots] must be the rows for [row]'s own id. */
    fun toMatch(row: PatternEntity, pivots: List<PatternPivotEntity>): Match = Match(
        patternId = row.patternId,
        name = row.name,
        score = row.score,
        pivots = pivots.map { toPivot(it) },
        measuredMove = measuredMoveOf(row),
        category = categoryOrDefault(row.category),
        timeframe = timeframeOrDefault(row.timeframe),
        detectedOn = LocalDate.parse(row.detectedOn),
        breakdown = breakdownOf(row),
    )

    /**
     * A measured move only exists when **all three** numbers survived. A partial move would
     * render a projected level that was never computed — spec DD-1's "no measured move" case
     * has to stay representable.
     */
    fun measuredMoveOf(row: PatternEntity): MeasuredMove? {
        val height = row.moveHeight ?: return null
        val trigger = row.moveTrigger ?: return null
        val projected = row.moveProjected ?: return null
        val direction = row.moveDirection ?: return null
        return MeasuredMove(
            height = height,
            triggerLevel = trigger,
            direction = directionOrDefault(direction),
            projectedLevel = projected,
        )
    }

    private fun breakdownOf(row: PatternEntity): ScoreBreakdown? {
        val geometry = row.geometry ?: return null
        val structure = row.structure ?: return null
        val confirmation = row.confirmation ?: return null
        val context = row.context ?: return null
        return ScoreBreakdown(geometry, structure, confirmation, context)
    }

    // --------------------------------------------------------------------------- outcomes

    fun toOutcome(entity: OutcomeEntity): PatternOutcome = PatternOutcome(
        patternId = entity.patternId,
        symbol = entity.symbol,
        patternName = entity.patternName,
        timeframe = timeframeOrDefault(entity.timeframe),
        category = categoryOrDefault(entity.category),
        projectedOn = LocalDate.parse(entity.projectedOn),
        score = entity.score,
        direction = directionOrDefault(entity.direction),
        projectedLevel = entity.projectedLevel,
        actualHigh = entity.actualHigh,
        actualLow = entity.actualLow,
        hit = entity.hit,
        resolved = entity.resolved,
        horizonBars = entity.horizonBars,
    )

    fun fromOutcome(patternRowId: Long, outcome: PatternOutcome): OutcomeEntity = OutcomeEntity(
        patternId = patternRowId,
        symbol = outcome.symbol,
        patternName = outcome.patternName,
        timeframe = timeframeKey(outcome.timeframe),
        category = categoryKey(outcome.category),
        projectedOn = outcome.projectedOn.toString(),
        score = outcome.score,
        direction = directionKey(outcome.direction),
        projectedLevel = outcome.projectedLevel,
        actualHigh = outcome.actualHigh,
        actualLow = outcome.actualLow,
        hit = outcome.hit,
        resolved = outcome.resolved,
        horizonBars = outcome.horizonBars,
    )

    // ------------------------------------------------------------------- market presence

    /** Field separator of the `bhavcopy_cache.presence` text encoding. */
    const val PRESENCE_SEPARATOR: Char = '|'

    /**
     * Compact `SYMBOL|ISIN|SERIES` lines for the rolling market cache.
     *
     * Identity only. The OHLCV is in the write-once `candles` table, and duplicating it here
     * would give the cache a second, replaceable copy of price history — the one thing plan.md
     * §5.2's write-once rule exists to prevent.
     */
    fun encodePresence(rows: List<BhavcopyRow>): String =
        rows.joinToString("\n") { row ->
            listOf(
                SymbolNormaliser.tickerKey(row.ticker),
                row.isin?.let(SymbolNormaliser::isinKey).orEmpty(),
                row.series,
            ).joinToString(PRESENCE_SEPARATOR.toString())
        }

    /** Inverse of [encodePresence]. A blank or malformed line is skipped, never fatal. */
    fun decodePresence(payload: String): List<Triple<String, String, String>> {
        if (payload.isBlank()) return emptyList()
        return payload.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val cells = line.split(PRESENCE_SEPARATOR)
                if (cells.size < 3) return@mapNotNull null
                Triple(
                    SymbolNormaliser.tickerKey(cells[0]),
                    cells[1].takeIf { it.isNotBlank() }?.let(SymbolNormaliser::isinKey).orEmpty(),
                    cells[2],
                )
            }
            .toList()
    }

    // ------------------------------------------------------------------------ ratio field

    /**
     * The `ratios` row id used by `:data`'s aggregation when it needs a stable key per field.
     * Kept here so a column rename in `:data` does not silently change a stored key.
     */
    fun ratioFieldKeys(): List<String> = RatioFieldId.ALL.map { it.name }
}