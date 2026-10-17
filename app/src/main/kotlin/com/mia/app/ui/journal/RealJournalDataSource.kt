package com.mia.app.ui.journal

import com.mia.app.data.android.repo.JournalRepository
import com.mia.app.domain.journal.JournalDraft
import com.mia.app.domain.journal.JournalEntry
import com.mia.app.domain.journal.JournalLedger
import com.mia.app.domain.journal.PersonalRecord
import com.mia.app.domain.model.HitRate
import com.mia.app.domain.ratios.FreshnessPolicy
import com.mia.app.ui.di.DataFailureException
import com.mia.app.ui.di.isWorthRetrying
import com.mia.app.ui.di.orDataFailure
import com.mia.app.work.MiaBackgroundDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [JournalDataSource] over `:android-data`'s `JournalRepository`.
 *
 * ### Why this lives here and not in `ui/di`
 * Every other `Real…DataSource` sits in `ui/di/` because that package owns the adapters. This wave
 * does not own `ui/di/UiDataSourceModule.kt` — another workstream does — and adding a `@Binds` line
 * there would put a merge conflict on the branch for no functional gain. So the adapter sits beside
 * the screen it serves and `JournalDataSourceModule.kt` binds it; a follow-up that owns `ui/di/` can
 * move both files with no change to any caller, because nothing outside this package names the
 * concrete class.
 *
 * ### What this adapter computes, and what it deliberately does not
 * It runs the resolution pass first, so the tally a reader sees counts outcomes recorded on the
 * session they just opened rather than on whatever the last nightly run happened to see. It then
 * hands `JournalLedger`'s own [PersonalRecord] through untouched: **the gate is applied in
 * `:domain`, once.** Recomputing `settled` or `agreed` here would be a second implementation of the
 * 30-sample rule, and the 30-sample rule is the one thing in this app that must not exist twice.
 *
 * The app's own record is likewise handed over as a [HitRate] rather than as text, so this screen
 * and the Accuracy tab share `HitRate.label()`'s wording, its `n=` and its withholding behaviour.
 *
 * ### A failure to resolve is not a failure to read
 * [JournalRepository.resolveDue] writes, and a write that fails must not blank the screen: the
 * reader's own words are still readable, and an entry shown as "still open" with the reason that
 * closed nothing is a **more** honest state than a red banner over a record the app could read. So
 * the pass is run with [runCatching] and its failure surfaces as an open reason, never as an error.
 */
@Singleton
class RealJournalDataSource @Inject constructor(
    private val journalRepository: JournalRepository,
    @MiaBackgroundDispatcher private val backgroundDispatcher: CoroutineDispatcher,
) : JournalDataSource {

    override suspend fun load(): JournalLoad = translating {
        val today = todayInMarket()
        // Ordered: resolve first, so the tally counts what this visit could close.
        runCatching { journalRepository.resolveDue(today) }

        val entries = journalRepository.entries(today).orDataFailure()
        JournalLoad(
            entries = entries,
            record = JournalLedger.record(entries, today),
            appHitRate = journalRepository.appTrackRecord(today).orDataFailure().hitRate,
            openReasons = JournalLedger.openReasons(
                entries = entries,
                // Bars are not re-read here: the resolution pass already had them, and an entry
                // still open after it has run can only be open because of a gate that is not about
                // data. The reason that pass produced is the accurate one, and re-reading 500 daily
                // bars per symbol on every screen entry to re-derive it would be waste.
                candlesBySymbol = emptyMap(),
                settledSymbols = journalRepository.settledOutcomeSymbols(today).orDataFailure(),
                today = today,
            ),
            today = today,
        )
    }

    override suspend fun save(draft: JournalDraft, today: LocalDate): JournalEntry = translating {
        // `toEntry` is the last gate: it re-checks completeness — so a caller that skipped the form
        // cannot store a blank falsification — and normalises the symbol before it is written.
        journalRepository.record(draft.toEntry(today), today).orDataFailure()
    }

    override suspend fun delete(id: Long): Unit = translating {
        journalRepository.delete(id).orDataFailure()
    }

    // --------------------------------------------------------------------------- internals

    /**
     * Runs [block] off the main thread and turns a storage failure into this screen's own exception.
     *
     * `orDataFailure` is the shared mapping: it throws only for failures that are *not* an honest
     * absence, so "this device holds no entries" stays a value and "the database is unreachable"
     * becomes an error with the reason attached.
     */
    private suspend fun <T> translating(block: suspend () -> T): T = try {
        withContext(backgroundDispatcher) { block() }
    } catch (failure: DataFailureException) {
        throw JournalUnavailableException(
            reason = failure.failure.message,
            isTransient = failure.failure.isWorthRetrying(),
        )
    }

    /**
     * Today in market time.
     *
     * [FreshnessPolicy] rather than `LocalDate.now()`: review dates are session dates, and the
     * 19:00 IST bhavcopy lands after midnight in some other zones, so the same instant must not
     * produce two different answers to "is this entry due".
     */
    private fun todayInMarket(): LocalDate = FreshnessPolicy.todayInMarket()
}