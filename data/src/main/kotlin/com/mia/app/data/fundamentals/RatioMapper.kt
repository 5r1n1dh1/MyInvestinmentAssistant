package com.mia.app.data.fundamentals

import com.mia.app.data.fundamentals.ScreenerHtml.ParsedTable
import com.mia.app.domain.model.CashFlowDerived
import com.mia.app.domain.model.Confidence
import com.mia.app.domain.model.Ratios
import com.mia.app.domain.model.Sector
import com.mia.app.domain.model.Source
import java.time.LocalDate
import kotlin.math.pow

/**
 * Fills a domain [Ratios] **as far as the published page allows** — nothing more.
 *
 * ### Sector is never guessed here
 * plan.md §1.4 rule 8 and §7.1: default to [Sector.OTHER] and never guess. This mapper makes no
 * classification decision of its own — it copies the [sector] its caller resolved through the
 * domain's `SectorResolver`, and defaults to [Sector.OTHER] when it was given nothing.
 *
 * It used to *always* write [Sector.OTHER] and report `sectorResolved = false`, on the reasoning
 * that mapping text to a sector is a classification decision with a wrong-answer-is-worse-than-
 * blank failure mode. That reasoning was right and the implementation over-applied it: the
 * persisted `OTHER` was then read back by `DefaultRedFlagRepository` as the sector for
 * `RedFlagContext`, so **every** sector-conditioned red flag was evaluated against `OTHER` for
 * every stock — the flag engine could never apply a single sector suppression. Delegating to
 * `SectorResolver` keeps the decision in one place (the domain) while making the stored value
 * mean something.
 *
 * ### Field-by-field provenance and units
 *
 * | Field | Source | Unit |
 * |---|---|---|
 * | `roce`, `roe`, `dividendYield`, `operatingMargin`, `revenueCagr3y`, `revenueGrowth` | published / computed | **percent** |
 * | `pe` | published `Stock P/E` | multiple |
 * | `pb` | `Current Price ÷ Book Value` | multiple |
 * | `cfoToOp` | `CashFlowDerived.cfoToOpByYear` | **ratio** (`1.09`, not `109`) |
 * | `interestCoverage` | `EBIT ÷ Interest` | multiple |
 * | `fcf`, `pat`, `marketCap` | published | **₹ crore** |
 * | `promoterHolding` | latest `shareholding` Promoters % | percent |
 * | `debtToEquity` | **never published by this page → `null`** | — |
 * | `grossMargin` | **no cost-of-goods line → `null`** | — |
 * | `evToEbitda` | **no enterprise value, no EBITDA → `null`** | — |
 *
 * `debtToEquity` is deliberately **not** derived here even though
 * `Borrowings ÷ (Equity Capital + Reserves)` is available from the page. plan.md §7.3 makes
 * D/E meaningless for banks, and only the domain's `SectorResolver` knows the sector. Fabricating
 * a number here would hand a bank a D/E it must then be trusted to discard — and would make
 * HDFCBANK's snapshot claim a figure plan.md §7.3 explicitly says should read "not meaningful".
 * The derivation belongs in `domain/ratios/RatioComputation`, behind the sector override.
 *
 * Confidence is [Confidence.SINGLE]: one source, no cross-check performed in this layer
 * (plan.md §5.1 merges per field and this is the screener.in side of that merge).
 */
object RatioMapper {

    private const val KEY_OPM = "opm"

    /**
     * @param asOf the date this page was read. screener.in publishes no "as of" stamp for its
     *   headline ratios, so the honest answer is the fetch date — and plan.md §10 requires that
     *   staleness is visible, which is what the facade's notes are for.
     */
    fun map(
        symbol: String,
        asOf: LocalDate,
        topRatios: TopRatiosSnapshot,
        derived: CashFlowDerived,
        profitLoss: ParsedTable?,
        shareholding: ParsedTable?,
        sector: Sector = Sector.OTHER,
    ): Ratios {
        val years = derived.revenueByYear.keys + derived.patByYear.keys
        val latest = years.maxOrNull()
        val previous = latest?.let { it - 1 }
        val threeYearsAgo = latest?.let { it - 3 }

        val price = topRatios.currentPrice
        val bookValue = topRatios.bookValue
        val ebit = latest?.let { derived.ebitByYear[it] }
        val interest = latest?.let { derived.interestByYear[it] }

        return Ratios(
            symbol = symbol,
            sector = sector,
            asOf = asOf,
            source = Source.SCREENER_IN,
            confidence = Confidence.SINGLE,
            roce = topRatios.rocePercent,
            roe = topRatios.roePercent,
            // Absent from the page; see the class KDoc.
            debtToEquity = null,
            interestCoverage = ratio(ebit, interest),
            operatingMargin = latest?.let { StatementParsers.valueAt(profitLoss, indexOfYear(profitLoss, it), KEY_OPM) },
            fcf = latest?.let { derived.fcfByYear[it] },
            pat = latest?.let { derived.patByYear[it] },
            cfoToOp = latest?.let { derived.cfoToOpByYear[it] },
            revenueCagr3y = cagr(derived.revenueByYear, latest, threeYearsAgo),
            revenueGrowth = growth(derived.revenueByYear, latest, previous),
            grossMargin = null,
            pe = topRatios.stockPe,
            pb = ratio(price, bookValue),
            evToEbitda = null,
            dividendYield = topRatios.dividendYieldPercent,
            promoterHolding = DerivedMetrics.latestPromoterPercent(shareholding),
            marketCap = topRatios.marketCapCr,
        )
    }

    private fun indexOfYear(table: ParsedTable?, year: Int): Int = ScreenerHtml.yearIndexOf(table, year) ?: -1

    /** `a ÷ b`, and `null` unless both are present and the divisor is non-zero. */
    private fun ratio(a: Double?, b: Double?): Double? {
        if (a == null || b == null || b == 0.0) return null
        return a / b
    }

    /** Compound annual growth rate over the three years ending at [latest], in percent. */
    private fun cagr(series: Map<Int, Double>, latest: Int?, start: Int?): Double? {
        val end = latest ?: return null
        val begin = start ?: return null
        val a = series[end] ?: return null
        val b = series[begin] ?: return null
        if (b <= 0.0 || a <= 0.0) return null
        return ((a / b).pow(1.0 / 3.0) - 1.0) * 100.0
    }

    /** Year-on-year growth at [latest] versus [start], in percent. */
    private fun growth(series: Map<Int, Double>, latest: Int?, start: Int?): Double? {
        val end = latest ?: return null
        val begin = start ?: return null
        val a = series[end] ?: return null
        val b = series[begin] ?: return null
        if (b <= 0.0) return null
        return (a / b - 1.0) * 100.0
    }
}
