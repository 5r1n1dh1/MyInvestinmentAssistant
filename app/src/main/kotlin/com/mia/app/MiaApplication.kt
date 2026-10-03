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
     * Enqueues a first-run history walk, and a first-run EOD refresh alongside it.
     *
     * ### Why these are two independent enqueues rather than a chain
     * This used to be `beginUniqueWork(EOD).then(HISTORY)`, reasoning that the EOD refresh should
     * store the newest session before the walk started. That chain was the second cause of
     * "Not Enough History" on every stock:
     *
     * `EodRefreshWorker` returns `Result.retry()` whenever today's bhavcopy is not published yet —
     * plan.md §3.1 measures that as "404 until roughly 18:00 IST". A `retry` **fails the entire
     * chain**, so a reader who installed at 10:00 had the history walk never run at all, and every
     * tab reported "Not Enough History" until they happened to launch after 18:00.
     *
     * The chain was also unnecessary: `backfillHistory` walks *backwards from today*, so its first
     * session is the newest published one — it covers today's bar by itself.
     *
     * Independence also fixes a *reporting* bug: one chain yields one status, so a failing EOD
     * refresh made a working history walk look like it had never run. Each now records its own
     * outcome in `work_state`.
     *
     * ### Why network-gated but not battery-gated
     * ~58 MB, once, in the background. The cost of *never* getting the data — every tab permanently
     * empty — is worse than the cost of downloading it, and it is the same download the nightly job
     * performs anyway. plan.md §10's battery rule still governs the nightly schedule via
     * [scheduler.scheduleAll].
     *
     * ### Resumable
     * A pass interrupted by process death resumes rather than restarts: sessions already in the
     * write-once `candles` table are skipped without a request.
     */
    private fun primeFirstRun() {
        val networkRequired = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workManager = WorkManager.getInstance(this)

        // The newest published session. Deliberately NOT chained to the history walk: see the
        // class KDoc. Its failure is recorded in `work_state`, not propagated.
        workManager.enqueueUniqueWork(
            ONE_SHOT_PRIME_EOD,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<EodRefreshWorker>()
                .setConstraints(networkRequired)
                .addTag(RefreshWorkScheduler.TAG_EOD)
                .build(),
        )

        // The history walk, at full depth rather than a token batch.
        //
        // [CandleRepository.DEFAULT_HISTORY_SESSIONS] (280) is the number that clears *both*
        // plan.md §6.4 gates — 280 daily bars against a 60-bar daily gate, and ~56 weekly bars
        // against a 40-bar weekly gate. Priming with a smaller batch is precisely what left the
        // `1W` tab permanently below its own gate.
        workManager.enqueueUniqueWork(
            ONE_SHOT_PRIME_HISTORY,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<HistoryBackfillWorker>()
                .setConstraints(networkRequired)
                .setInputData(
                    workDataOf(
                        HistoryBackfillWorker.KEY_SESSIONS to
                            CandleRepository.DEFAULT_HISTORY_SESSIONS,
                    ),
                )
                .addTag(RefreshWorkScheduler.TAG_BACKFILL)
                .build(),
        )
    }

    private companion object {
        /**
         * Unique names for the two priming one-shots.
         *
         * Both are distinct from `RefreshWorkScheduler.ONE_SHOT_EOD`, so pressing "refresh" in
         * Settings cannot cancel an in-flight history walk: a reader who explicitly asked for
         * history should not lose it because they also asked for a price.
         *
         * They are distinct from *each other* because the two used to share one chain, and a chain
         * yields a single status — so a failing EOD refresh made a *successful* history walk look
         * like it had never run.
         */
        const val ONE_SHOT_PRIME_EOD: String = "mia.first_run_eod"
        const val ONE_SHOT_PRIME_HISTORY: String = "mia.first_run_history"
    }
}