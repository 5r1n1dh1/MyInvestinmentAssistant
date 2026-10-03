package com.mia.app.data.android.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Enqueues the two periodic jobs of plan.md §10.
 *
 * ```
 * 15:30 IST   market close
 * ~18:00 IST  bhavcopy published
 * 19:00 IST   EodRefreshWorker: fetch bhavcopy → parse → replace market cache
 * 19:05 IST   PatternScanWorker: rescan the user's tracked symbols → generate alerts
 * ```
 *
 * ### Constraints (plan.md §10, verbatim)
 * `NetworkType.CONNECTED` and `requiresBatteryNotLow`. Charging is deliberately **not** required:
 * a 208 KB download plus a parse on a charging-only constraint would skip the refresh for most
 * users, and the whole-market fetch is precisely the shape of work that is cheap on battery.
 *
 * ### The initial delay
 * Computed by [RefreshSchedule] against the device's own zone, which for this app's audience is
 * IST (UTC+05:30, no DST). See [RefreshSchedule]'s class KDoc for the arithmetic and for the
 * "already past 19:00" case.
 *
 * ### Backoff (plan.md §10)
 * `BackoffPolicy.EXPONENTIAL` with a **30 minute** minimum — WorkManager's floor for exponential
 * backoff is 10 s, so 30 minutes is a deliberate choice, not the minimum the API allows: a
 * bhavcopy that 404s because NSE has not published it yet must not be retried every ten seconds.
 *
 * ### `UPDATE`, not `KEEP`
 * `ExistingPeriodicWorkPolicy.UPDATE` (WorkManager 2.8+) replaces an already-enqueued request
 * with the new one, so a build that changes its constraints or its target time takes effect
 * without the user clearing app data. `KEEP` would strand the old schedule forever.
 */
@Singleton
class RefreshWorkScheduler @Inject constructor(
    private val workManager: WorkManager,
) {

    /** Schedules (or re-schedules) both jobs. Safe to call on every cold start. */
    fun scheduleAll(now: ZonedDateTime = ZonedDateTime.now()) {
        scheduleEodRefresh(now)
        schedulePatternScan(now)
    }

    fun scheduleEodRefresh(now: ZonedDateTime = ZonedDateTime.now()) {
        val delay = RefreshSchedule.eodInitialDelay(now)
        val request = PeriodicWorkRequestBuilder<EodRefreshWorker>(
            RefreshSchedule.PERIOD.toHours(),
            TimeUnit.HOURS,
        )
            .setInitialDelay(delay.toMinutes(), TimeUnit.MINUTES)
            .setConstraints(constraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, RefreshSchedule.MIN_BACKOFF.toMinutes(), TimeUnit.MINUTES)
            .addTag(TAG_EOD)
            .build()

        workManager.enqueueUniquePeriodicWork(
            UNIQUE_EOD,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun schedulePatternScan(now: ZonedDateTime = ZonedDateTime.now()) {
        val delay = RefreshSchedule.patternScanInitialDelay(now)
        val request = PeriodicWorkRequestBuilder<PatternScanWorker>(
            RefreshSchedule.PERIOD.toHours(),
            TimeUnit.HOURS,
        )
            .setInitialDelay(delay.toMinutes(), TimeUnit.MINUTES)
            .setConstraints(constraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, RefreshSchedule.MIN_BACKOFF.toMinutes(), TimeUnit.MINUTES)
            .addTag(TAG_SCAN)
            .build()

        workManager.enqueueUniquePeriodicWork(
            UNIQUE_SCAN,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    /** Cancels both jobs. `:app` calls this from Settings → "stop automatic refresh". */
    fun cancelAll() {
        workManager.cancelUniqueWork(UNIQUE_EOD)
        workManager.cancelUniqueWork(UNIQUE_SCAN)
    }

    /** One sentence per job describing when it will next run, for the settings screen. */
    fun describeSchedule(now: ZonedDateTime = ZonedDateTime.now()): Map<String, String> = mapOf(
        UNIQUE_EOD to RefreshSchedule.describe(now, RefreshSchedule.eodTarget(now)),
        UNIQUE_SCAN to RefreshSchedule.describe(now, RefreshSchedule.patternScanTarget(now)),
    )

    private fun constraints(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true)
        .build()

    companion object {
        /** Unique work names. Doubles as the `work_state.taskId` keys. */
        const val UNIQUE_EOD: String = "mia.eod_refresh"
        const val UNIQUE_SCAN: String = "mia.pattern_scan"

        /** Tags, for `:app` to observe or cancel by category. */
        const val TAG_EOD: String = "mia:eod"
        const val TAG_SCAN: String = "mia:pattern_scan"
    }
}

/**
 * Provides the singleton [WorkManager].
 *
 * `:app` supplies a `Configuration` carrying the [com.mia.app.data.android.di.DatabaseModule]-
 * provided `HiltWorkerFactory` through `Configuration.Provider`, which requires removing
 * WorkManager's default initialiser from `:app`'s manifest. Until `:app` does that,
 * `WorkManager.getInstance(context)` throws, and this is the only place that would.
 */
@Module
@InstallIn(SingletonComponent::class)
object WorkModule {

    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager =
        WorkManager.getInstance(context)
}