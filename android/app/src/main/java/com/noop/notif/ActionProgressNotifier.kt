package com.noop.notif

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.noop.R
import com.noop.ui.appLaunchIntent

/**
 * System-notification half of the long-running-action feedback (import, export, full Charge rescore).
 *
 * These actions run for a minute or more on a background coroutine, so the user is free to leave the
 * screen — or the app — while one is in flight. The in-app banner (AppViewModel.backgroundActions)
 * covers the in-app case and is the RELIABLE one: it needs no permission and always shows. This adds
 * the out-of-app case, which is exactly when a Toast was useless.
 *
 * Deliberately NOT a foreground service: the work is a coroutine on the ViewModel scope, and promoting
 * it to a service would keep the process alive for a job the user can simply re-trigger. If Android
 * kills the process mid-pass the notification goes with it, which is honest — the pass died too.
 *
 * Notification behaviour, mirroring the banner:
 *  - running  → ongoing, indeterminate progress bar, not swipeable away
 *  - finished → dismissible, auto-cancels on tap (the banner keeps the result until dismissed in-app)
 *
 * Every action gets its own notification id derived from its action id, so two actions in flight don't
 * overwrite each other's line. Silently no-ops when notifications are off — the banner still reports.
 */
object ActionProgressNotifier {
    private const val CHANNEL_ID = "noop_action_progress"

    /** 4201 = ongoing connection, 4202 = illness, 4203 = inactivity — start clear of those. */
    private const val NOTIF_ID_BASE = 4300

    private fun notifId(actionId: String): Int =
        NOTIF_ID_BASE + (actionId.hashCode().and(0x7fffffff) % 50)

    @SuppressLint("MissingPermission") // guarded by areNotificationsEnabled() + runCatching
    fun onRunning(context: Context, actionId: String, label: String) {
        post(context, actionId) {
            it.setContentTitle(label)
                .setContentText("Running… you can leave this screen.")
                .setProgress(0, 0, true)
                .setOngoing(true)
                .setAutoCancel(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
        }
    }

    @SuppressLint("MissingPermission")
    fun onFinished(context: Context, actionId: String, label: String, ok: Boolean, detail: String?) {
        val body = detail ?: if (ok) "Finished." else "Didn't finish — nothing was changed."
        post(context, actionId) {
            it.setContentTitle(label)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setProgress(0, 0, false)
                .setOngoing(false)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        }
    }

    /** Drop the system line when the user dismisses the in-app banner, so the two stay in step. */
    fun cancel(context: Context, actionId: String) {
        runCatching { NotificationManagerCompat.from(context).cancel(notifId(actionId)) }
    }

    @SuppressLint("MissingPermission")
    private fun post(
        context: Context,
        actionId: String,
        configure: (NotificationCompat.Builder) -> NotificationCompat.Builder,
    ) {
        // Defensive: never let a notify() throw (revoked POST_NOTIFICATIONS, OEM quirk) fail the action
        // itself — the work matters more than its progress line.
        runCatching {
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
            ensureChannel(context)
            val openApp = PendingIntent.getActivity(
                context, 7,
                appLaunchIntent(context),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = configure(
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_stat_heart)
                    .setContentIntent(openApp)
                    .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                    .setOnlyAlertOnce(true),
            ).build()
            NotificationManagerCompat.from(context).notify(notifId(actionId), n)
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, "Background tasks",
                    // LOW: a rescore or import is something you asked for, not something to be buzzed about.
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Progress and result of imports, exports and full recalculations."
                },
            )
        }
    }
}
