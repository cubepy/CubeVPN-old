package net.cubevpn.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * Parses the "JSON subscription" format — an array of complete Xray configs, one per server,
 * each carrying its display name in `remarks`. v2rayN/v2rayNG accept it, so panels serve it to
 * any client that looks modern enough, and which format a panel picks is not stable: the same
 * URL served plain links to `v2rayNG/1.8.23` one evening and JSON to that exact agent the next,
 * after a panel update. Parsing every format the panel might send is the only way off that
 * treadmill — [SubscriptionFetcher] falls back to this when no line parsed as a link.
 *
 * This is the inverse of [ConfigBuilder]'s outbound/stream writers; keep the two in step.
 */
object JsonSubscription {

    fun parse(text: String, source: ConfigSource = ConfigSource.PERSONAL): List<ProxyConfig> {
        val trimmed = text.trim()
        if (!trimmed.startsWith("[") && !trimmed.startsWith("{")) return emptyList()
        val entries = try {
            if (trimmed.startsWith("[")) JSONArray(trimmed)
            else JSONArray().put(JSONObject(trimmed))
        } catch (e: Exception) {
            return emptyList()
        }

        val out = ArrayList<ProxyConfig>(entries.length())
        for (i in 0 until entries.length()) {
            val entry = entries.optJSONObject(i) ?: continue
            val name = entry.optString("remarks").ifBlank { "Server ${i + 1}" }
            val outbound = pickProxyOutbound(entry.optJSONArray("outbounds")) ?: continue
            runCatching { fromOutbound(outbound, name, source) }.getOrNull()?.let { out.add(it) }
        }
        return out
    }

    /** The tunnel outbound, by convention tagged "proxy" — never the direct/block ones. */
    private fun pickProxyOutbound(outbounds: JSONArray?): JSONObject? {
        outbounds ?: return null
        var fallback: JSONObject? = null
        for (i in 0 until outbounds.length()) {
            val ob = outbounds.optJSONObject(i) ?: continue
            if (ob.optString("protocol") !in SUPPORTED) continue
            if (ob.optString("tag") == "proxy") return ob
            if (fallback == null) fallback = ob
        }
        return fallback
    }

    private fun fromOutbound(ob: JSONObject, name: String, source: ConfigSource): ProxyConfig? {
        val protocol = ob.optString("protocol")
        val settings = ob.optJSONObject("settings") ?: JSONObject()
        val stream = ob.optJSONObject("streamSettings") ?: JSONObject()

        var address = ""
        var port = 0
        var uuid = ""
        var password = ""
        var method = ""
        var alterId = 0
        var encryption = "none"
        var flow = ""

        when (protocol) {
            "vless", "vmess" -> {
                val vnext = settings.optJSONArray("vnext")?.optJSONObject(0) ?: return null
                address = vnext.optString("address")
                port = vnext.optInt("port")
                val user = vnext.optJSONArray("users")?.optJSONObject(0) ?: return null
                uuid = user.optString("id")
                flow = user.optString("flow")
                encryption = if (protocol == "vless") {
                    user.optString("encryption").ifBlank { "none" }
                } else {
                    alterId = user.optInt("alterId", 0)
                    user.optString("security").ifBlank { "auto" }
                }
            }
            "trojan", "shadowsocks" -> {
                val server = settings.optJSONArray("servers")?.optJSONObject(0) ?: return null
                address = server.optString("address")
                port = server.optInt("port")
                password = server.optString("password")
                flow = server.optString("flow")
                if (protocol == "shadowsocks") method = server.optString("method")
            }
            else -> return null
        }
        if (address.isBlank() || port <= 0) return null

        val network = stream.optString("network").ifBlank { "tcp" }
        val security = stream.optString("security").ifBlank { "none" }

        var sni = ""
        var publicKey = ""
        var shortId = ""
        var fingerprint = ""
        var alpn = ""
        when (security) {
            "reality" -> stream.optJSONObject("realitySettings")?.let {
                sni = it.optString("serverName")
                publicKey = it.optString("publicKey")
                shortId = it.optString("shortId")
                fingerprint = it.optString("fingerprint")
            }
            "tls" -> stream.optJSONObject("tlsSettings")?.let {
                sni = it.optString("serverName")
                fingerprint = it.optString("fingerprint")
                alpn = it.optJSONArray("alpn").toCsv()
            }
        }

        var path = ""
        var host = ""
        var serviceName = ""
        var mode = ""
        var headerType = ""
        when (network) {
            "ws" -> stream.optJSONObject("wsSettings")?.let {
                path = it.optString("path")
                host = it.optString("host").ifBlank { it.optJSONObject("headers")?.optString("Host") ?: "" }
            }
            "httpupgrade" -> stream.optJSONObject("httpupgradeSettings")?.let {
                path = it.optString("path")
                host = it.optString("host")
            }
            "xhttp", "splithttp" -> stream.optJSONObject("xhttpSettings")?.let {
                path = it.optString("path")
                host = it.optString("host")
                mode = it.optString("mode")
            }
            "grpc" -> stream.optJSONObject("grpcSettings")?.let {
                serviceName = it.optString("serviceName")
                if (it.optBoolean("multiMode")) mode = "multi"
            }
            "http", "h2" -> stream.optJSONObject("httpSettings")?.let {
                path = it.optString("path")
                host = it.optJSONArray("host").toCsv()
            }
            "kcp" -> stream.optJSONObject("kcpSettings")?.let {
                headerType = it.optJSONObject("header")?.optString("type") ?: ""
                path = it.optString("seed")
            }
            "tcp" -> stream.optJSONObject("tcpSettings")?.optJSONObject("header")?.let { header ->
                headerType = header.optString("type")
                header.optJSONObject("request")?.let { req ->
                    path = req.optJSONArray("path").toCsv()
                    host = req.optJSONObject("headers")?.optJSONArray("Host").toCsv()
                }
            }
        }

        return ProxyConfig(
            name = name,
            protocol = protocol,
            address = address,
            port = port,
            uuid = uuid,
            password = password,
            method = method,
            alterId = alterId,
            encryption = encryption,
            flow = flow,
            network = network,
            security = security,
            sni = sni,
            publicKey = publicKey,
            shortId = shortId,
            fingerprint = fingerprint.ifBlank { "chrome" },
            path = path,
            host = host,
            serviceName = serviceName,
            mode = mode,
            alpn = alpn,
            headerType = headerType,
            source = source
        )
    }

    private fun JSONArray?.toCsv(): String {
        this ?: return ""
        return (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotBlank() } }
            .joinToString(",")
    }

    private val SUPPORTED = setOf("vless", "vmess", "trojan", "shadowsocks")
}
