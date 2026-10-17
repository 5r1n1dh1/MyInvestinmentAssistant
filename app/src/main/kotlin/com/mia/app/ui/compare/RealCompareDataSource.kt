package com.mia.app.ui.compare

import com.mia.app.domain.model.CashFlowDerived
import com.mia.app.domain.model.Sector
import com.mia.app.domain.model.Source
import com.mia.app.domain.ratios.FreshnessKind
import com.mia.app.domain.ratios.FreshnessPolicy
import com.mia.app.domain.ratios.RatioComputation
import com.mia.app.domain.ratios.RatioId
import com.mia.app.domain.ratios.ShareholdingMetrics
import com.mia.app.ui.fundamentals.FundamentalsDataSource
import com.mia.app.ui.fundamentals.FundamentalsPage
import com.mia.app.ui.fundamentals.FundamentalsUnavailableException
import com.mia.app.work.MiaBackgroundDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [CompareDataSource] over the Fundamentals screen's own data source.
 *
 * ### Why it reads `FundamentalsDataSource` and not a repository
 * A comparison is a *view* of the fundamentals this app already has, not a second fetch path. Going
 * through the same interface the Fundamentals tab uses means the compare screen cannot show a figure
 * the Fundamentals tab would not, cannot resolve a sector differently, and cannot invent a value for
 * a row the tab reports as unsourced. The one thing it adds is the sector the page resolved to and
 * whether that resolution succeeded — both already on `FundamentalsPage`, because plan.md §7.1
 * requires the *reason* to travel with the answer.
 *
 * ### One failed symbol never fails the comparison
 * A ticker whose page has never been fetched, or whose fetch failed, still produces a
 * [CompareCompany] with [CompareCompany.unavailableReason] set. That is deliberate and it is the
 * whole difference between a comparison and a wall: a column that is missing for a stated reason
 * tells the reader something true, while an absent column tells them nothing and a thrown error
 * would throw away the two columns that did load.
 *
 * ### Where the values come from
 * [readingsOf] is a projection over the *same* `:domain` functions the Fundamentals tab computes
 * from — `RatioComputation` and `ShareholdingMetrics` — so it is a second *presentation* of one
 * calculation rather than a second calculation. Its KDoc says what it deliberately does not do with
 * §7.3: nothing here applies a sector rule, because [CompareAlignment] must apply it **per cell**.
 */
@Singleton
class RealCompareDataSource @Inject constructor(
    private val fundamentals: FundamentalsDataSource,
    @MiaBackgroundDispatcher private val backgroundDispatcher: CoroutineDispatcher,
) : CompareDataSource {

    override suspend fun compare(symbols: List<String>): CompareComparison =
        withContext(backgroundDispatcher) {
            val today = FreshnessPolicy.todayInMarket()
            val companies = symbols.map { symbol -> companyFor(symbol, today) }
            CompareComparison(companies = companies, rows = CompareAlignment.rows(companies))
        }

    /**
     * One company, or one company with the reason it has nothing.
     *
     * Only `FundamentalsUnavailableException` is caught: it is the typed "this page could not be
     * produced" signal, and its `reason` is already a user-facing sentence. Anything else escaping
     * is a defect and is allowed to propagate rather than being dressed up as an absence — the same
     * rule `RealFundamentalsDataSource` follows.
     */
    private suspend fun companyFor(symbol: String, today: LocalDate): CompareCompany =
        try {
            val page = fundamentals.fundamentals(symbol)
            from(page, today)
        } catch (unavailable: FundamentalsUnavailableException) {
            CompareCompany(
                symbol = symbol,
                name = symbol,
                sector = Sector.OTHER,
                sectorResolved = false,
                asOf = fundamentals.asOf(symbol),
                source = Source.SCREENER_IN,
                isStale = false,
                unavailableReason = unavailable.reason,
            )
        }

    /** The happy path: the page's own values, its own sector, its own as-of date. */
    private fun from(page: FundamentalsPage, today: LocalDate): CompareCompany {
        val ratios = page.snapshot.ratios
        val freshness = FreshnessPolicy.inspect(
            asOf = ratios.asOf,
            today = today,
            kind = FreshnessKind.FUNDAMENTALS_SNAPSHOT,
        )
        return CompareCompany(
            symbol = page.snapshot.symbol,
            name = page.snapshot.name,
            sector = page.sector,
            sectorResolved = page.sectorResolution.resolved,
            asOf = ratios.asOf,
            source = ratios.source,
            isStale = freshness.isStale,
            readings = readingsOf(page),
        )
    }
}

/**
 * Every `RatioId` this page could answer, as a raw value plus the reason it is absent.
 *
 * ### Why this mapping exists at all, given the Fundamentals tab already has one
 * `FundamentalsViewModel.valueOf` is private to that screen and returns a screen-shaped
 * [com.mia.app.ui.fundamentals.RatioCard] list for one symbol with its trends and divergences
 * attached. A comparison needs a *total* function from `RatioId` to a value for a page it has just
 * read, with nothing screen-shaped in the way, and it needs to be callable for three pages at once.
 *
 * So this is a second **projection** of the same numbers, not a second calculation: every ratio below
 * comes from [Ratios][com.mia.app.domain.model.Ratios] or from the same `:domain` computation the
 * Fundamentals tab calls, and the notes are those functions' own notes — "no fiscal year publishes
 * both 'Borrowings' and 'Equity Capital + Reserves'" is a far better absence than a bare null,
 * because it names the missing input.
 *
 * ### What it deliberately does not do
 * * **It does not apply §7.3.** No sector rule is consulted here, because a sector rule is a
 *   per-*company* decision and [CompareAlignment] has to be able to answer it for every cell
 *   separately. Applying it here would bake one company's sector into another company's cell.
 * * **It does not fetch.** A page this device has not got is an absent column with a reason, not a
 *   request — plan.md §13's one-fetch-a-day budget is not this screen's to spend.
 * * **It does not fill.** No default, no carry-forward, no "last known". A ratio the page does not
 *   publish is missing here and says so.
 */
private fun readingsOf(page: FundamentalsPage): Map<RatioId, CompareReading> {
    val snapshot = page.snapshot
    val ratios = snapshot.ratios
    val derived: CashFlowDerived = snapshot.cashFlowDerived
    val sector = page.sector
    // Resolved once, so the three ownership figures cannot disagree about which quarter is the
    // latest — the failure mode of reading a quarterly card three times independently.
    val ownership = ShareholdingMetrics.of(page.shareholdingPeriods, snapshot.shareholding)

    // Each `RatioComputation` call is made once and destructured, because the Fundamentals tab does
    // the same: calling it twice for the value and then for the note would walk the annual columns
    // twice and could in principle observe two different states.
    val debtToEquity = RatioComputation.debtToEquity(derived, sector)
    val interestCoverage = RatioComputation.interestCoverage(derived, sector)
    val operatingMargin = RatioComputation.operatingMarginTrend(
        page.profitLossPeriods,
        snapshot.profitLoss,
        RatioComputation.TREND_YEARS,
    )
    val revenueCagr = RatioComputation.revenueCagr(derived, RatioComputation.CAGR_SHORT_YEARS)
    val promoterHolding = RatioComputation.promoterHolding(
        page.shareholdingPeriods,
        snapshot.shareholding,
    )
    val dividendPayout = RatioComputation.dividendPayout(page.profitLossPeriods, snapshot.profitLoss)
    val fcfToPat = RatioComputation.fcfToPat(derived)
    val fiiHolding = ShareholdingMetrics.fiiHolding(ownership)
    val diiHolding = ShareholdingMetrics.diiHolding(ownership)
    val fiiChange = ShareholdingMetrics.fiiHoldingQuarterlyChange(ownership)

    fun reading(value: Double?, note: String? = null) = CompareReading(value = value, note = note)

    return mapOf(
        RatioId.ROCE to reading(ratios.roce),
        RatioId.ROE to reading(ratios.roe),
        RatioId.DEBT_TO_EQUITY to reading(ratios.debtToEquity ?: debtToEquity.value, debtToEquity.note),
        RatioId.INTEREST_COVERAGE to reading(
            ratios.interestCoverage ?: interestCoverage.value,
            interestCoverage.note,
        ),
        // The trend's own latest point is the better figure: it comes from the annual statement
        // rather than from the `top-ratios` card.
        RatioId.OPERATING_MARGIN to reading(
            operatingMargin.value?.latest?.value ?: ratios.operatingMargin,
            operatingMargin.note,
        ),
        RatioId.GROSS_MARGIN to reading(ratios.grossMargin),
        RatioId.FCF_VS_PAT to reading(fcfToPat.value, fcfToPat.note),
        RatioId.REVENUE_CAGR to reading(ratios.revenueCagr3y ?: revenueCagr.value, revenueCagr.note),
        RatioId.PE_VS_SECTOR_MEDIAN to reading(ratios.pe),
        RatioId.PB to reading(ratios.pb),
        RatioId.EV_TO_EBITDA to reading(ratios.evToEbitda),
        RatioId.PROMOTER_HOLDING to reading(
            ratios.promoterHolding ?: promoterHolding.value,
            promoterHolding.note,
        ),
        RatioId.DIVIDEND_YIELD to reading(ratios.dividendYield),
        RatioId.DIVIDEND_PAYOUT to reading(dividendPayout.value, dividendPayout.note),
        RatioId.FII_HOLDING to reading(fiiHolding.value, fiiHolding.note),
        RatioId.DII_HOLDING to reading(diiHolding.value, diiHolding.note),
        RatioId.FII_TREND to reading(fiiChange.value, fiiChange.note),
    )
}