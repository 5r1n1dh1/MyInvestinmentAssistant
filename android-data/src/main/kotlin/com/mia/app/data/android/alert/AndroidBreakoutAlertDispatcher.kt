package com.mia.app.data.android.alert

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.mia.app.domain.compliance.Disclosure
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts breakout alerts as Android notifications, and never assumes it is allowed to.
 *
 * ### The permission story, in full
 * * This module **does not** declare `POST_NOTIFICATIONS`. `:app` owns that decision, per the
 *   brief: plan.md §1.4 keeps v1 permission-light and the app is fully usable without the alert.
 * * `areNotificationsEnabled()` is checked **before** posting, so a denied permission produces a
 *   clean `false` rather than a `SecurityException` on API 33+.
 * * A `SecurityException` is caught anyway and reported as "not delivered", because a dispatcher
 *   that throws would abort the nightly scan — and losing a run over one alert would also lose
 *   every detection, projection and settled outcome in it.
 *
 * ### Channel
 * One channel, `IMPORTANCE_DEFAULT`, created idempotently. Android 8.0+ (the module's `minSdk`
 * is 26) requires a channel before `notify`, and re-creating one with the same id is a no-op.
 *
 * ### Small icon
 * A framework drawable, because a library module has no `R.mipmap` of its own. `:app` should
 * override [smallIconRes] with its launcher icon.
 */
@Singleton
class AndroidBreakoutAlertDispatcher @Inject constructor(
    @ApplicationContext context: Context,
) : BreakoutAlertDispatcher {

    private val appContext: Context = context.applicationContext

    private val manager: NotificationManager =
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override val isEnabled: Boolean
        get() = manager.areNotificationsEnabled()

    override suspend fun notifyBreakout(alert: BreakoutAlert): Boolean {
        if (!alert.mayAlert) return false
        if (!manager.areNotificationsEnabled()) return false
        return try {
            ensureChannel()
            manager.notify(notificationId(alert), build(alert))
            true
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS was revoked between the check and the post, or the channel's
            // importance was lowered. Reported, not thrown.
            false
        }
    }

    private fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        channel.description = CHANNEL_DESCRIPTION
        manager.createNotificationChannel(channel)
    }

    private fun build(alert: BreakoutAlert): Notification =
        NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(resolveSmallIcon())
            .setContentTitle(alert.title())
            .setContentText(alert.body())
            .setStyle(NotificationCompat.BigTextStyle().bigText(alert.body()))
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            // No auto-cancel: the user may want to read it later, and there is no deep link from
            // a library module to `:app`'s navigation graph.
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setContentIntent(null)
            .build()

    /**
     * Notification ids must be stable per (symbol, pattern, session) so a re-run of the nightly
     * worker replaces its own notification instead of stacking duplicates. plan.md §10's
     * exponential backoff means a retry is expected, not exceptional.
     */
    private fun notificationId(alert: BreakoutAlert): Int {
        val key = "${alert.symbol}|${alert.patternName}|${alert.timeframe.name}|${alert.asOf}"
        // `hashCode()` is stable within a process and across processes for a String on Android's
        // JVM; the `and` keeps it positive so it cannot collide with `NotificationManager`'s
        // reserved ids.
        return key.hashCode() and Int.MAX_VALUE
    }

    /** `:app`'s icon when it set one, otherwise a framework drawable that always exists. */
    private fun resolveSmallIcon(): Int =
        if (smallIconRes > 0) smallIconRes else DEFAULT_SMALL_ICON

    companion object {
        const val CHANNEL_ID: String = "mia_pattern_breakouts"
        const val CHANNEL_NAME: String = "Pattern breakouts"
        const val CHANNEL_DESCRIPTION: String =
            "A pattern's breakout condition was met on a closing price. " + Disclosure.SCORE_MEANING

        /**
         * Override from `:app`. `0` means "use the framework default", which is what a library
         * module has to ship with.
         */
        @Volatile
        @JvmStatic
        var smallIconRes: Int = 0

        /** `android.R.drawable.stat_notify_sync` — always present, always valid as a small icon. */
        const val DEFAULT_SMALL_ICON: Int = android.R.drawable.stat_notify_sync
    }
}