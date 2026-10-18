package com.mia.app.ui.sectors

import com.mia.app.data.android.repo.DataFailure
import com.mia.app.data.android.repo.DataFailureReason
import com.mia.app.data.android.repo.DataResult
import com.mia.app.data.android.repo.SectorRepository
import com.mia.app.domain.sectors.SectorComparison
import com.mia.app.domain.sectors.SectorComparisonBuilder
import com.mia.app.work.MiaBackgroundDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [SectorDataSource] over [SectorRepository].
 *
 * ### What it does and does not do
 * It fetches nothing. It resolves no sector. It computes no median. It reads the list of companies
 * this device has stored, finds the subject in it, and hands both to
 * [SectorComparisonBuilder] — which is the single place every refusal this screen makes is
 * enforced. Keeping it this thin is deliberate: the temptation on a feature like this is to
 * "helpfully" widen the peer set, drop a stale row, or fill a missing ratio from another sector,
 * and each of those would be a one-line change here that nobody would notice was a change.
 */
@Singleton
class RealSectorDataSource @Inject constructor(
    private val repository: SectorRepository,
    @MiaBackgroundDispatcher private val backgroundDispatcher: CoroutineDispatcher,
) : SectorDataSource {

    override suspend fun sectorView(symbol: String, today: LocalDate): DataResult<SectorComparison> =
        withContext(backgroundDispatcher) {
            val cached = repository.cachedCompanies(today)
            val companies = cached.valueOrNull()
                ?: return@withContext DataResult.unavailable(
                    failure = cached.failure ?: DataFailure(
                        reason = DataFailureReason.UNEXPECTED,
                        message = "the stored company list could not be read",
                    ),
                    fetchedOn = today,
                )

            val subject = companies.firstOrNull { it.symbol == symbol }
                ?: return@withContext DataResult.unavailable(
                    failure = DataFailure(
                        reason = DataFailureReason.NO_ROWS_FOR_SYMBOL,
                        message = "No sector comparison for $symbol: this device has never stored " +
                            "a fundamentals page for it, and the sector median is built only from " +
                            "pages already stored. Open $symbol's Fundamentals tab first — then " +
                            "look up a few more companies in its sector and the comparison " +
                            "fills in.",
                    ),
                    fetchedOn = today,
                )

            DataResult.ok(
                value = SectorComparisonBuilder.build(subject = subject, cached = companies),
                source = SectorRepository.SOURCE,
                fetchedOn = today,
                asOf = subject.asOf,
                notes = cached.provenance.notes,
            )
        }
}
