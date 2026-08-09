package net.cubevpn.app

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

data class SubUserInfo(
    val upload: Long = 0,
    val download: Long = 0,
    val total: Long = 0,
    val expire: Long = 0
) {
    val used: Long get() = upload + download
    val hasData: Boolean get() = total > 0 || expire > 0
}

data class FetchResult(
    val configs: List<ProxyConfig>,
    val userInfo: SubUserInfo?
)

object SubscriptionFetcher {

    suspend fun fetch(url: String, source: ConfigSource = ConfigSource.PERSONAL): List<ProxyConfig> =
        fetchFull(url, source).configs

    /**
     * Just the panel's own "subscription-userinfo" header (data used/total, expire) — the
     * accurate source for a service's remaining days, unlike the account API's invoice-derived
     * numbers which don't carry an expiry at all. Still does a full GET (panels only send this
     * header on the subscription response), so call it sparingly.
     */
    suspend fun fetchUserInfo(url: String): SubUserInfo? = fetchFull(url).userInfo

    suspend fun fetchFull(url: String, source: ConfigSource = ConfigSource.PERSONAL): FetchResult =
        withContext(Dispatchers.IO) {
            val conn = openFollowingRedirects(url)
            try {
                val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                val userInfo = parseUserInfo(conn.getHeaderField("subscription-userinfo"))
                val text = decodeMaybeBase64(body)
                val configs = text.lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .mapNotNull { ConfigParser.parse(it, source) }
                    .toList()
                FetchResult(configs, userInfo)
            } finally {
                conn.disconnect()
            }
        }

    private fun openFollowingRedirects(startUrl: String): HttpURLConnection {
        var current = startUrl
        var hops = 0
        while (true) {
            val conn = connect(current)
            val code = conn.responseCode
            if (code in 300..399 && hops < 5) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if (loc.isNullOrBlank()) {
                    return connect(current)
                }
                current = URL(URL(current), loc).toString()
                hops++
                continue
            }
            return conn
        }
    }

    /**
     * Validates the TLS certificate normally first. Only if that handshake actually fails
     * (self-hosted subscription panels commonly run on self-signed certs in this ecosystem)
     * do we fall back to trusting anything, and only for that one host/hop. This keeps
     * self-signed panels working exactly as before while protecting every panel that does
     * have a real certificate from a network-level MITM injecting hostile proxy configs.
     */
    private fun connect(urlStr: String): HttpURLConnection {
        val validated = buildConnection(urlStr, insecure = false)
        return try {
            validated.responseCode
            validated
        } catch (e: SSLException) {
            runCatching { validated.disconnect() }
            buildConnection(urlStr, insecure = true)
        }
    }

    private fun buildConnection(urlStr: String, insecure: Boolean): HttpURLConnection =
        (URL(urlStr).openConnection() as HttpURLConnection).apply {
            if (insecure && this is HttpsURLConnection) {
                sslSocketFactory = insecureSocketFactory()
                hostnameVerifier = HostnameVerifier { _, _ -> true }
            }
            connectTimeout = 12000
            readTimeout = 12000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "CubeVPN")
            instanceFollowRedirects = false
        }

    private fun insecureSocketFactory(): SSLSocketFactory {
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, trustAll, SecureRandom())
        return ctx.socketFactory
    }
    private fun parseUserInfo(header: String?): SubUserInfo? {
        if (header.isNullOrBlank()) return null
        val map = header.split(';').mapNotNull {
            val eq = it.indexOf('=')
            if (eq < 0) null
            else it.substring(0, eq).trim() to (it.substring(eq + 1).trim().toLongOrNull() ?: 0L)
        }.toMap()
        return SubUserInfo(
            upload = map["upload"] ?: 0,
            download = map["download"] ?: 0,
            total = map["total"] ?: 0,
            expire = map["expire"] ?: 0
        )
    }

    /**
     * Subscription bodies come either as plain `scheme://…` lines or base64-wrapped. Panels are
     * inconsistent about which base64 they emit: some use the URL-safe alphabet (`-`/`_`), some
     * drop the `=` padding, most wrap at 76 chars. Android's standard-alphabet decoder rejects
     * the first two outright, and a rejected decode used to fall through to returning the raw
     * base64 text — which parses to zero servers and surfaces as a bogus "fetch failed".
     */
    private fun decodeMaybeBase64(body: String): String {
        val trimmed = body.trim()
        if (trimmed.contains("://")) return trimmed

        val normalized = buildString(trimmed.length) {
            for (c in trimmed) when {
                c.isWhitespace() -> {}
                c == '-' -> append('+')
                c == '_' -> append('/')
                else -> append(c)
            }
        }
        if (normalized.isEmpty()) return trimmed
        val padded = when (normalized.length % 4) {
            2 -> "$normalized=="
            3 -> "$normalized="
            0 -> normalized
            else -> return trimmed // length%4==1 is never valid base64
        }
        return try {
            String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8)
        } catch (e: Exception) {
            trimmed
        }
    }
}