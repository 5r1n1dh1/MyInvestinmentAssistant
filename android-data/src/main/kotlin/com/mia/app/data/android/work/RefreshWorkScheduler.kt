package com.mia.app.data.android.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.mia.app.data.android.repo.CandleRepository
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

    /**
     * Runs an EOD refresh **now**, once, instead of waiting for the next 19:00 IST slot.
     *
     * ### Why this exists
     * The periodic schedule alone left the app unusable for most of a fresh install's first day:
     * [RefreshSchedule.eodInitialDelay] targets 19:00 IST, so a user who installs at 10:00 sees
     * every tab report "no bars stored" for the next nine hours with no way to change that.
     * Settings' refresh button previously re-scheduled the *periodic* work, which does not make it
     * run any sooner.
     *
     * `REPLACE` rather than `KEEP`: the point of the button is "run it now", so a second tap must
     * re-run rather than be swallowed by an already-enqueued request.
     *
     * @param force also deepen history, for when the newest session is stored but the series is
     *   still too short to clear plan.md §6.4's 60-bar gate.
     */
    fun refreshNow(force: Boolean = false) {
        val builder = OneTimeWorkRequestBuilder<EodRefreshWorker>()
            .setConstraints(manualConstraints())
            .addTag(TAG_EOD)

        if (force) {
            builder.setInputData(workDataOf(EodRefreshWorker.KEY_FORCE_BACKFILL to true))
        }

        workManager.enqueueUniqueWork(
            ONE_SHOT_EOD,
            ExistingWorkPolicy.REPLACE,
            builder.build(),
        )
    }

    /**
     * Runs one history-deepening pass **now** — the walk backwards through the NSE archive.
     *
     * Separate from [refreshNow] because they answer different questions at very different cost:
     * one whole-market file (~208 KB) versus up to
     * [CandleRepository.HISTORY_BATCH_LIMIT] of them. A reader who pressed refresh once on an empty
     * app now has a current price; pattern *detection* additionally needs history, and the UI has
     * to be able to say which of the two it is asking for.
     *
     * The pass is resumable — every already-stored session is skipped without a request — so a
     * second tap continues the walk rather than restarting it.
     */
    fun backfillHistoryNow(sessions: Int = CandleRepository.DEFAULT_HISTORY_SESSIONS) {
        val request = OneTimeWorkRequestBuilder<HistoryBackfillWorker>()
            .setInputData(workDataOf(HistoryBackfillWorker.KEY_SESSIONS to sessions))
            .setConstraints(manualConstraints())
            .addTag(TAG_BACKFILL)
            .build()

        workManager.enqueueUniqueWork(
            ONE_SHOT_BACKFILL,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    /**
     * Constraints for a user-initiated run.
     *
     * **No initial delay, and a laxer battery rule than the nightly job.** The nightly refresh may
     * wait for its schedule to line up; a person standing in front of the app pressing a button is
     * telling us to run it now. Delaying that is precisely the failure this method exists to fix, so
     * this is deliberately *not* [constraints].
     */
    private fun manualConstraints(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

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

        /**
         * Unique names for the user-initiated one-shots.
         *
         * Deliberately **distinct from** [UNIQUE_EOD]: the periodic job and the manual run are
         * different work with different lifetimes. Sharing a name would make a manual refresh
         * replace the nightly schedule (losing the 19:00 IST slot) or be replaced by it (losing
         * the run the reader asked for).
         */
        const val ONE_SHOT_EOD: String = "mia.eod_refresh_now"
        const val ONE_SHOT_BACKFILL: String = "mia.history_backfill"

        /** Tags, for `:app` to observe or cancel by category. */
        const val TAG_EOD: String = "mia:eod"
        const val TAG_SCAN: String = "mia:pattern_scan"
        const val TAG_BACKFILL: String = "mia:backfill"
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