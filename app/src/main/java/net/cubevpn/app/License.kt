package net.cubevpn.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Whether this branded build is still licensed to run.
 *
 * A reseller who sells through their own panel sends us none of their traffic — their customers'
 * configs come from their own servers, and nothing in the app has to ask us anything. So if they
 * stop paying, there is no natural moment at which anything of ours notices. This check is the
 * only lever, and it exists solely for that case: a build with no LICENSE_URL never makes a
 * request, which is every build of CubeVPN itself and every reseller who is on our panel.
 *
 * Three rules decide what an answer means, and each of them is a decision about who gets hurt
 * when something goes wrong:
 *
 *   A network error changes nothing. Our server being unreachable is not evidence that anyone
 *   stopped paying, and treating it as such would black out every paying customer of every brand
 *   the moment our box hiccuped.
 *
 *   Only "expired" closes the app, and only from a reply we actually received and parsed. A
 *   truncated body, a captive portal's login page, an HTML error — none of those are an answer.
 *
 *   Silence eventually counts. Without that, blocking one domain defeats the whole thing, and a
 *   reseller who stops paying keeps shipping our app forever. So a build that has not reached us
 *   in [GRACE_DAYS] days closes. That window is the trade: long enough that our own week of
 *   downtime does not strand anyone, short enough that evasion costs them their app.
 *
 * The answer is cached, so the decision at startup is instant and offline; the network check runs
 * afterwards and only ever changes what the *next* start decides.
 */
object License {

    private const val PREFS = "cube_license"
    private const val KEY_STATUS = "status"          // last explicit answer: "active" or "expired"
    private const val KEY_LAST_OK = "last_ok_millis" // when we last got either of those
    private const val KEY_FIRST_RUN = "first_run"    // so a fresh install is not instantly out of grace

    private const val GRACE_DAYS = 7L
    private const val CHECK_INTERVAL_HOURS = 20L
    private const val KEY_LAST_CHECK = "last_check_millis"

    private val URL_STR = BuildConfig.LICENSE_URL
    private val KEY = BuildConfig.BRAND_KEY

    /** A build nobody licenses is a build that never asks. */
    val enabled: Boolean get() = URL_STR.isNotBlank() && KEY.isNotBlank()

    /**
     * Whether the app should refuse to run right now. Reads only the cache, so it is safe to call
     * on the main thread and gives the same answer with no network at all.
     */
    fun isBlocked(context: Context): Boolean {
        if (!enabled) return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        if (prefs.getString(KEY_STATUS, null) == "expired") return true

        // A first run has never had a chance to check, and starting the grace clock at zero would
        // block an app the moment it is installed on a phone that happens to be offline.
        val firstRun = prefs.getLong(KEY_FIRST_RUN, 0L).let {
            if (it == 0L) System.currentTimeMillis().also { now -> prefs.edit().putLong(KEY_FIRST_RUN, now).apply() } else it
        }
        val lastOk = prefs.getLong(KEY_LAST_OK, 0L)
        val since = System.currentTimeMillis() - (if (lastOk > 0L) lastOk else firstRun)

        return since > GRACE_DAYS * 24 * 60 * 60 * 1000
    }

    /**
     * Ask the server, at most once a day. Never throws; a failure simply leaves the cache alone,
     * which is what "a network error changes nothing" means in practice.
     */
    suspend fun refresh(context: Context) = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        val sinceCheck = System.currentTimeMillis() - prefs.getLong(KEY_LAST_CHECK, 0L)
        if (sinceCheck < CHECK_INTERVAL_HOURS * 60 * 60 * 1000) return@withContext

        val status = fetchStatus() ?: return@withContext
        if (status != "active" && status != "expired") {
            // "unknown" is the server saying it cannot answer — a key it does not recognise, or
            // its own database being down. Deliberately indistinguishable from each other, and
            // deliberately not an answer: it must not close anything.
            prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
            return@withContext
        }

        prefs.edit()
            .putString(KEY_STATUS, status)
            .putLong(KEY_LAST_OK, System.currentTimeMillis())
            .putLong(KEY_LAST_CHECK, System.currentTimeMillis())
            .apply()
    }

    private fun fetchStatus(): String? = try {
        val url = URL(URL_STR.trimEnd('/') + "?key=" + KEY)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10000
            readTimeout = 10000
            setRequestProperty("Accept", "application/json")
        }
        val code = conn.responseCode
        val text = if (code in 200..299) conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) } else null
        conn.disconnect()
        // Anything that is not a 2xx JSON body with a status field is treated as no answer —
        // a captive portal's login page parses as neither "active" nor "expired", and must not
        // be read as either.
        text?.let { runCatching { JSONObject(it).optString("status") }.getOrNull() }?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }
}
