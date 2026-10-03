package com.mia.app

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.mia.app.data.android.repo.CandleRepository
import com.mia.app.data.android.work.EodRefreshWorker
import com.mia.app.data.android.work.HistoryBackfillWorker
import com.mia.app.data.android.work.RefreshWorkScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * The Hilt root (plan.md P0).
 *
 * ### Why the app enables Hilt in `Application` and not in `MainActivity`
 * The DI graph has to outlive the activity: `WorkManager`'s EOD refresh workers
 * (plan.md §10, 19:00 IST) are injected by Hilt from the **application** context, and a worker that
 * launches while the app is closed cannot reach an activity-scoped graph. So the singleton component
 * is installed here and every activity and ViewModel reads from it.
 *
 * ### Why startup also enqueues one-shot work
 * The periodic schedule alone left a fresh install unusable. [RefreshWorkScheduler] targets 19:00
 * IST, so a user who installed at 10:00 saw every tab report "no bars stored" for the next nine
 * hours, with nothing on screen able to change it — the read path had no write path behind it.
 * [primeFirstRun] asks for the data immediately, in the background, and returns.
 *
 * Startup still does no work on the main thread: it enqueues two small `Data` objects and returns.
 * plan.md §12 targets 2 GB-RAM devices, and an `Application` that blocks is a cost the reader pays
 * before seeing a pixel.
 */
@HiltAndroidApp
class MiaApplication : Application(), Configuration.Provider {

    /**
     * Why the app opts out of WorkManager's automatic initialisation.
     *
     * `androidx.startup`'s default provider creates WorkManager from the application context before
     * Hilt's graph exists, which is exactly what `@HiltWorker` cannot survive: the factory that
     * knows how to inject a worker is not available yet. On-demand initialisation — requested by
     * removing only the WorkManager initializer from the manifest — hands control to
     * [workManagerConfiguration] instead.
     */
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var scheduler: RefreshWorkScheduler

    // `buildConfig = false` in this module, so `BuildConfig.DEBUG` does not exist here and the log
    // level is a plain constant rather than a build-type switch.
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(Log.WARN)
            .build()

    override fun onCreate() {
        super.onCreate()
        scheduler.scheduleAll()
        primeFirstRun()
    }

    /**
     * Enqueues the newest session, then a bounded history walk behind it.
     *
     * ### Why a chain rather than two independent requests
     * The history walk traverses the archive **backwards from today**. Two independently enqueued
     * one-shots may run concurrently, in which case the long job can be the only thing that stored
     * today — so a reader watching the app populate would see history appear with no current price.
     * `beginUniqueWork(…).then(…)` makes that ordering a guarantee rather than a hope, and gives
     * the reader one work item representing the whole outcome instead of two to correlate.
     *
     * ### Why network-gated but not battery-gated
     * Both downloads together are on the order of tens of megabytes, taken once, in the background.
     * The cost of *never* getting the data — every tab permanently empty — is worse than the cost of
     * downloading it, and it is the same download the nightly job performs anyway. plan.md §10's
     * battery rule still governs the nightly schedule via [scheduler.scheduleAll].
     *
     * ### Resumable
     * A pass interrupted by process death resumes rather than restarts: sessions already in the
     * write-once `candles` table are skipped without a request.
     */
    private fun primeFirstRun() {
        val networkRequired = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        WorkManager.getInstance(this)
            .beginUniqueWork(
                ONE_SHOT_PRIME,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<EodRefreshWorker>()
                    .setConstraints(networkRequired)
                    .addTag(RefreshWorkScheduler.TAG_EOD)
                    .build(),
            )
            .then(
                OneTimeWorkRequestBuilder<HistoryBackfillWorker>()
                    .setConstraints(networkRequired)
                    // One bounded batch, not the full 280-session pass: this is priming, not the
                    // deep walk. A reader who wants depth taps Settings → Refresh history, which
                    // resumes from wherever this stopped.
                    .setInputData(
                        workDataOf(
                            HistoryBackfillWorker.KEY_SESSIONS to CandleRepository.HISTORY_BATCH_LIMIT,
                        ),
                    )
                    .addTag(RefreshWorkScheduler.TAG_BACKFILL)
                    .build(),
            )
            .enqueue()
    }

    private companion object {
        /**
         * The chained priming work name.
         *
         * Distinct from `RefreshWorkScheduler.ONE_SHOT_EOD`, so pressing "refresh" in Settings
         * cannot cancel an in-flight history walk: a reader who explicitly asked for history should
         * not lose it because they also asked for a price.
         */
        const val ONE_SHOT_PRIME: String = "mia.first_run_prime"
    }
}