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
 * Low-data / expiring-soon push notifications for the user's purchased
 * services, checked each time the account is refreshed (see
 * CubeVpnApp.refreshAccountServices in MainActivity.kt). Uses plain
 * NotificationManager — no WorkManager/Glance — after the widget's
 * WorkManager dependency crashed release builds under R8.
 */
object ServiceAlerts {

    private const val CHANNEL_ID = "cube_service_alerts"
    private const val PREFS = "cube_service_alerts"
    private const val KEY_WARNED = "warned_keys"

    private const val LOW_DATA_FRACTION = 0.1
    private const val LOW_DATA_CLEAR_FRACTION = 0.2
    private const val EXPIRE_WARN_DAYS = 3L
    private const val EXPIRE_CLEAR_DAYS = 5L

    fun checkAndNotify(context: Context, store: ConfigStore, services: List<AccountService>) {
        if (services.isEmpty()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val warned = prefs.getStringSet(KEY_WARNED, emptySet())?.toMutableSet() ?: mutableSetOf()
        val before = warned.toSet()
        ensureChannel(nm)

        val lang = store.lang.value

        for (svc in services) {
            val name = svc.name.ifBlank { "Service" }

            val lowKey = "low_${svc.id}"
            if (svc.totalBytes > 0) {
                val fraction = (svc.totalBytes - svc.usedBytes).coerceAtLeast(0).toDouble() / svc.totalBytes
                when {
                    fraction <= LOW_DATA_FRACTION && lowKey !in warned -> {
                        notify(context, nm, lang, notifId(lowKey), "data_low_title", "data_low_body", name)
                        warned += lowKey
                    }
                    fraction > LOW_DATA_CLEAR_FRACTION -> warned -= lowKey
                }
            } else {
                warned -= lowKey
            }

            val expKey = "exp_${svc.id}"
            if (svc.expire > 0) {
                val daysLeft = (svc.expire * 1000 - System.currentTimeMillis()) / 86_400_000L
                when {
                    daysLeft in 0..EXPIRE_WARN_DAYS && expKey !in warned -> {
                        notify(context, nm, lang, notifId(expKey), "expiring_soon_title", "expiring_soon_body", name, daysLeft)
                        warned += expKey
                    }
                    daysLeft > EXPIRE_CLEAR_DAYS || daysLeft < 0 -> warned -= expKey
                }
            } else {
                warned -= expKey
            }
        }

        if (warned != before) {
            prefs.edit().putStringSet(KEY_WARNED, warned).apply()
        }
    }

    private fun notifId(key: String): Int = (key.hashCode() and 0x7fffffff) % 90_000 + 5_000

    private fun ensureChannel(nm: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, Brand.appName, NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
    }

    private fun notify(
        context: Context,
        nm: NotificationManager,
        lang: Lang,
        id: Int,
        titleKey: String,
        bodyKey: String,
        serviceName: String,
        daysLeft: Long = 0
    ) {
        val title = Strings.get(lang, titleKey)
        val body = Strings.get(lang, bodyKey).format(serviceName, localizeDigits("$daysLeft", lang))
        val pi = PendingIntent.getActivity(
            context, id, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(id, notification)
    }
}
