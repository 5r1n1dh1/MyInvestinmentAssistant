package com.mia.app

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
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
 * Nothing else happens in this class on purpose. plan.md §12 targets 2 GB-RAM devices, and an
 * `Application` that does work on startup is work the reader pays for before seeing a pixel; the
 * EOD data path is owned by `:android-data`'s `EodRefreshWorker`.
 */
@HiltAndroidApp
class MiaApplication : Application(), Configuration.Provider {

    /**
     * Why the app opts out of WorkManager's automatic initialisation.
     *
     * `androidx.startup`'s default provider creates WorkManager before Hilt's graph exists, which
     * is exactly the situation `@HiltWorker` cannot survive: the worker factory that knows how to
     * inject a worker is not available yet. On-demand initialisation, requested through the
     * manifest (`androidx.work.WorkManagerInitializer` removed under
     * `androidx.startup`), hands control to [getWorkManagerConfiguration] instead, and the
     * configuration below supplies the Hilt worker factory.
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
        // Enqueue the EOD refresh and the watchlist pattern scan (plan.md §10). Both are periodic
        // work with an initial delay that lands the run near 19:00 IST, computed by
        // `RefreshSchedule` from the device time zone — so this is two schedule writes, not work
        // on the main thread.
        scheduler.scheduleAll()
    }
}
