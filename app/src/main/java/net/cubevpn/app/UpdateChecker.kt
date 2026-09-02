package net.cubevpn.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object UpdateChecker {

    // Set UPDATE_REPO=owner/repo in secrets.properties to point this at your own release feed.
    private val REPO = BuildConfig.UPDATE_REPO
    private val API get() = "https://api.github.com/repos/$REPO/releases/latest"
    private val RELEASES get() = "https://github.com/$REPO/releases/latest"

    sealed interface Result {
        /** [downloadUrl] is the .apk asset matching this device's ABI when found, otherwise the release page. */
        data class Available(val version: String, val downloadUrl: String) : Result
        data object UpToDate : Result
        data object Failed : Result
    }

    suspend fun check(currentVersion: String): Result = withContext(Dispatchers.IO) {
        if (REPO.isBlank()) return@withContext Result.Failed
        try {
            val conn = (URL(API).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 10000
                requestMethod = "GET"
                setRequestProperty("User-Agent", Brand.appName)
                setRequestProperty("Accept", "application/vnd.github+json")
            }
            val body = try {
                conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            } finally {
                conn.disconnect()
            }
            val o = JSONObject(body)
            val tag = o.optString("tag_name").removePrefix("v").removePrefix("V").trim()
            val pageUrl = o.optString("html_url").ifEmpty { RELEASES }
            when {
                tag.isEmpty() -> Result.Failed
                isNewer(tag, currentVersion) -> Result.Available(tag, apkAssetUrl(o) ?: pageUrl)
                else -> Result.UpToDate
            }
        } catch (e: Exception) {
            Result.Failed
        }
    }

    /** Picks the release's .apk asset matching this device's ABI (e.g. arm64-v8a), if any. */
    private fun apkAssetUrl(release: JSONObject): String? {
        val assets = release.optJSONArray("assets") ?: return null
        val apks = (0 until assets.length()).mapNotNull { i ->
            val a = assets.optJSONObject(i) ?: return@mapNotNull null
            val name = a.optString("name")
            val url = a.optString("browser_download_url")
            if (name.endsWith(".apk") && url.isNotEmpty()) name to url else null
        }
        if (apks.isEmpty()) return null
        android.os.Build.SUPPORTED_ABIS.forEach { abi ->
            apks.firstOrNull { it.first.contains(abi) }?.let { return it.second }
        }
        return apks.first().second
    }

    private fun isNewer(remote: String, local: String): Boolean {
        val r = parts(remote)
        val l = parts(local)
        val n = maxOf(r.size, l.size)
        for (i in 0 until n) {
            val rv = r.getOrElse(i) { 0 }
            val lv = l.getOrElse(i) { 0 }
            if (rv != lv) return rv > lv
        }
        return false
    }

    private fun parts(v: String): List<Int> =
        v.split('.').map { seg -> seg.filter { it.isDigit() }.toIntOrNull() ?: 0 }
}