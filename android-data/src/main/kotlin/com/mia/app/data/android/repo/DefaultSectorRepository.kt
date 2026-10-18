package com.mia.app.data.android.repo

import com.mia.app.data.android.db.FundamentalsDao
import com.mia.app.data.android.db.Mappers
import com.mia.app.data.android.db.SymbolDao
import com.mia.app.data.android.di.IoDispatcher
import com.mia.app.domain.model.Ratios
import com.mia.app.domain.ratios.FreshnessKind
import com.mia.app.domain.ratios.FreshnessPolicy
import com.mia.app.domain.ratios.RatioId
import com.mia.app.domain.ratios.SectorResolver
import com.mia.app.domain.sectors.SectorCompany
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [SectorRepository] over the `ratios` and `symbols` tables.
 *
 * ### Why this reads two tables
 * `ratios` carries the figures and screener.in's industry sentence. It does **not** carry the
 * company's display name — and `SectorResolver`'s inputs are exactly those two texts. Reading the
 * name from `symbols` means the sector a company is filed under here is resolved from the same
 * inputs, in the same precedence, as the sector the Fundamentals tab resolved for it. A peer set
 * built from a *different* classification than the subject's would put a bank and an FMCG company
 * in one median, which is plan.md §7.3's exact failure mode arriving through a different door.
 *
 * ### Why the sector is re-resolved rather than read from the `sector` column
 * The column is written by the same resolver, but it may have been written by an **older** build —
 * before `ratios.industry` existed, when resolution fell back to matching words in the name
 * (see `DatabaseMigrations` v3). Re-resolving from the current inputs gives one classification rule
 * for every stored row, and the direction it can drift is the safe one: a row that no longer
 * resolves drops **out** of the peer group and shrinks a stated `n`, rather than quietly joining a
 * sector it does not belong to.
 *
 * ### Why only the latest row per symbol
 * `ratios` is `PK(symbol, asOf)`, so a company fetched twice holds two dated snapshots. A peer set
 * containing both would double-count that company and put two different dates into one median. The
 * latest row is the one the reader would see if they opened that peer, which makes it the only
 * defensible peer.
 *
 * ### What is deliberately not read
 * The annual statement tables. Ratios whose values come from a per-year alignment — FCF/PAT, the
 * dividend payout ratio, the FII/DII ownership rows — are **not** computed for peers here, and the
 * consequence is that those rows show no sector median rather than one built from a different
 * fiscal year than the subject's. See `SECTOR_COVERAGE_NOTE` for the sentence the UI prints, and
 * the class KDoc on `SectorBenchmark` for why the floor matters more than the coverage.
 */
@Singleton
class DefaultSectorRepository @Inject constructor(
    private val fundamentalsDao: FundamentalsDao,
    private val symbolDao: SymbolDao,
    @IoDispatcher private val dispatcher: CoroutineDispatcher,
) : SectorRepository {

    override suspend fun cachedCompanies(today: LocalDate): DataResult<List<SectorCompany>> =
        withContext(dispatcher) {
            val rows = fundamentalsDao.allRatios()
            if (rows.isEmpty()) {
                return@withContext DataResult.unavailable(
                    failure = DataFailure(
                        reason = DataFailureReason.NOT_ATTEMPTED,
                        message = NOTHING_CACHED,
                    ),
                    fetchedOn = today,
                )
            }

            // The names are fetched once for the whole table rather than per row: the peer set is
            // built from every row, and a lookup per company would be a query storm for a read that
            // is already pulling the whole table.
            val names = symbolDao.all().associate { it.symbol to it.name }

            val latest = rows
                .groupBy { it.symbol }
                .mapNotNull { (_, perSymbol) -> perSymbol.maxByOrNull { it.asOf } }
                .map { entity -> Mappers.toRatios(entity) }
                .sortedBy { it.symbol }

            val companies = latest.map { ratios -> toCompany(ratios, names[ratios.symbol], today) }

            DataResult.ok(
                value = companies,
                source = SectorRepository.SOURCE,
                fetchedOn = today,
                asOf = companies.maxOfOrNull { it.asOf ?: LocalDate.MIN },
                notes = listOf(SECTOR_COVERAGE_NOTE),
            )
        }

    /**
     * One stored snapshot as the sector view sees it.
     *
     * @param name the display name from `symbols`, or `null` when the company is not in the master —
     *   which is legal, and the resolver then has only the industry sentence to go on. A missing
     *   name is **not** silently replaced by the symbol: passing `"RELIANCE"` as a name would let
     *   the ticker itself match a keyword, and a ticker is not a classification.
     */
    private fun toCompany(ratios: Ratios, name: String?, today: LocalDate): SectorCompany {
        val resolution = SectorResolver.resolve(name = name, about = ratios.industry)
        val freshness = FreshnessPolicy.inspect(
            asOf = ratios.asOf,
            today = today,
            kind = FreshnessKind.FUNDAMENTALS_SNAPSHOT,
        )
        return SectorCompany(
            symbol = ratios.symbol,
            name = name ?: ratios.symbol,
            resolution = resolution,
            asOf = ratios.asOf,
            isStale = freshness.isStale,
            readings = readingsOf(ratios),
        )
    }

    /**
     * The columns a stored `ratios` row carries, mapped to `RatioId`.
     *
     * ### Why `PE_VS_SECTOR_MEDIAN` holds the plain P/E
     * That is the plan's §7.2 rank 8 title read literally — the slot exists to compare a P/E against
     * a sector's, so the *stock's* side of it is its own P/E, and the *sector's* side is the median
     * P/E of the peers. Putting the already-divided "P/E vs sector median" number in the subject's
     * column and then taking its median across peers would produce a median of a comparison.
     *
     * ### Why the keys are present even when the value is null
     * An absent key and a null value mean the same thing to a median — no figure for that peer — so
     * the map's shape is not load-bearing. It is spelled out because it is the honest record of
     * what the `ratios` table has columns for, and what it is missing (FCF/PAT, dividend payout,
     * the ownership rows) is exactly what [SECTOR_COVERAGE_NOTE] tells the reader.
     *
     * Nothing here is derived. Every value is a column read straight off the row, so a peer median
     * and the peer's own Fundamentals tab are reading the same number rather than two computations
     * that happen to agree.
     */
    private fun readingsOf(ratios: Ratios): Map<RatioId, Double?> = mapOf(
        RatioId.ROCE to ratios.roce,
        RatioId.ROE to ratios.roe,
        RatioId.DEBT_TO_EQUITY to ratios.debtToEquity,
        RatioId.INTEREST_COVERAGE to ratios.interestCoverage,
        RatioId.OPERATING_MARGIN to ratios.operatingMargin,
        RatioId.GROSS_MARGIN to ratios.grossMargin,
        RatioId.REVENUE_CAGR to ratios.revenueCagr3y,
        RatioId.PE_VS_SECTOR_MEDIAN to ratios.pe,
        RatioId.PB to ratios.pb,
        RatioId.EV_TO_EBITDA to ratios.evToEbitda,
        RatioId.PROMOTER_HOLDING to ratios.promoterHolding,
        RatioId.DIVIDEND_YIELD to ratios.dividendYield,
    )

    private companion object {
        /**
         * Shown as the empty state when nothing has ever been fetched.
         *
         * [DataFailureReason.NOT_ATTEMPTED] rather than `NO_DATA_CACHED`, because the two call for
         * different sentences: nothing downloaded yet is something the reader can act on, and
         * "nothing stored" reads as a condition they cannot change.
         */
        const val NOTHING_CACHED =
            "No sector comparison yet: this device has not stored a single company's " +
                "fundamentals page. Open a company's Fundamentals tab and this fills in — and " +
                "sector medians need several peers in the same sector, not just the one."

        /**
         * Why some rows carry no median even on a well-populated device.
         *
         * Printed once, above the rows, so the reader learns it once rather than meeting it again on
         * each absent cell. It names the cause — these figures need the annual statements, and a
         * median over peers whose latest fiscal years differ would be a comparison between different
         * periods wearing the word median.
         */
        const val SECTOR_COVERAGE_NOTE =
            "A median is shown only for figures this device stores per company. Ratios built from " +
                "the annual statements — free cash flow vs PAT, dividend payout, and the " +
                "institutional ownership rows — are read for the company you opened but not for " +
                "its peers, because a median across peers whose latest fiscal years differ is a " +
                "comparison between periods, not between companies."
    }
}
