package net.cubevpn.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Posts a system notification when a newer CubeVPN release is found, alongside the in-app
 * dialog shown on the next app open — so an update doesn't go unnoticed if the user dismisses
 * or never sees that dialog. Uses plain NotificationManager, no WorkManager (see ServiceAlerts.kt
 * for why: WorkManager crashed release builds under R8 for the now-reverted home-screen widget).
 */
object UpdateNotifier {

    private const val CHANNEL_ID = "cube_update_alerts"
    private const val PREFS = "cube_update_alerts"
    private const val KEY_NOTIFIED_VERSION = "notified_version"
    private const val NOTIF_ID = 42_001

    /** Notifies at most once per version, so the daily check doesn't re-spam the same update. */
    fun notifyIfNeeded(context: Context, lang: Lang, version: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_NOTIFIED_VERSION, null) == version) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, Brand.appName, NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val title = Strings.get(lang, "update_available").format(version)
        val body = Strings.get(lang, "update_notif_body")
        val pi = PendingIntent.getActivity(
            context, NOTIF_ID, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIF_ID, notification)
        prefs.edit().putString(KEY_NOTIFIED_VERSION, version).apply()
    }
}
