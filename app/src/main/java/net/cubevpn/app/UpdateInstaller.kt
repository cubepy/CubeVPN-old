package net.cubevpn.app

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

/**
 * Downloads a release APK via Android's DownloadManager — a background, OS-managed download
 * instead of handing the .apk link to the browser — then prompts the system installer once it
 * lands. Android still requires an explicit user tap to actually install a package; there's no
 * way for a non-system app to skip that step, however the download happens.
 */
object UpdateInstaller {

    fun downloadAndInstall(context: Context, url: String, version: String) {
        // Lands in the user's Downloads under this name, so it wears the brand they installed
        // rather than the one that wrote the code. Whitespace would survive into the path, so
        // a name like "OG VPN" becomes OGVPN-v1.4.3.apk.
        val fileName = "${Brand.appName.filter { !it.isWhitespace() }}-v$version.apk"
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return

        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        val existing = dir?.let { File(it, fileName) }
        if (existing?.exists() == true) existing.delete()

        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(fileName)
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        val appContext = context.applicationContext
        val downloadId = dm.enqueue(request)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) != downloadId) return
                runCatching { appContext.unregisterReceiver(this) }
                val file = File(dir, fileName)
                if (!file.exists()) return
                val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", file)
                val install = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                runCatching { appContext.startActivity(install) }
            }
        }
        ContextCompat.registerReceiver(
            appContext, receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED
        )
    }
}
