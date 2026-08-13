package net.cubevpn.app

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * Shapes below are trimmed from a real panel's JSON-subscription response (credentials replaced)
 * — the format that imported zero servers before JsonSubscription existed.
 */
class JsonSubscriptionTest {

    private val realityXhttp = """
    [
      {
        "log": { "loglevel": "warning" },
        "inbounds": [ { "tag": "socks", "port": 10808, "protocol": "socks" } ],
        "outbounds": [
          {
            "tag": "proxy",
            "protocol": "vless",
            "settings": { "vnext": [ {
              "address": "fi.6.linuxbase.ir",
              "port": 8443,
              "users": [ { "id": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", "encryption": "none" } ]
            } ] },
            "streamSettings": {
              "network": "xhttp",
              "security": "reality",
              "realitySettings": {
                "serverName": "player.example.com",
                "fingerprint": "chrome",
                "publicKey": "PUBKEY123",
                "shortId": "33bc",
                "spiderX": "/"
              },
              "xhttpSettings": { "mode": "auto", "path": "/" }
            }
          },
          { "tag": "DIRECT", "protocol": "freedom" },
          { "tag": "BLOCK", "protocol": "blackhole" }
        ],
        "remarks": "🇫🇮 Helsinki | Sauna"
      },
      {
        "outbounds": [
          {
            "tag": "proxy",
            "protocol": "vless",
            "settings": { "vnext": [ {
              "address": "www.speedtest.net",
              "port": 8443,
              "users": [ { "id": "11111111-2222-3333-4444-555555555555", "encryption": "none" } ]
            } ] },
            "streamSettings": {
              "network": "ws",
              "security": "tls",
              "tlsSettings": { "serverName": "ch.example.test", "fingerprint": "safari" },
              "wsSettings": { "path": "/graph/api/", "host": "ch.example.test" }
            }
          }
        ],
        "remarks": "🇨🇭 Bern | Alps"
      },
      {
        "outbounds": [
          {
            "tag": "proxy",
            "protocol": "shadowsocks",
            "settings": { "servers": [ {
              "address": "1.2.3.4", "port": 990,
              "method": "chacha20-ietf-poly1305", "password": "secretpw"
            } ] },
            "streamSettings": { "network": "tcp" }
          }
        ],
        "remarks": "🇩🇪 Tunnel"
      }
    ]
    """.trimIndent()

    @Test
    fun parsesEveryServerWithItsName() {
        val configs = JsonSubscription.parse(realityXhttp)
        assertEquals(3, configs.size)
        assertEquals(listOf("🇫🇮 Helsinki | Sauna", "🇨🇭 Bern | Alps", "🇩🇪 Tunnel"), configs.map { it.name })
    }

    @Test
    fun mapsRealityStreamSettings() {
        val c = JsonSubscription.parse(realityXhttp).first()
        assertEquals("vless", c.protocol)
        assertEquals("fi.6.linuxbase.ir", c.address)
        assertEquals(8443, c.port)
        assertEquals("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", c.uuid)
        assertEquals("xhttp", c.network)
        assertEquals("reality", c.security)
        assertEquals("player.example.com", c.sni)
        assertEquals("PUBKEY123", c.publicKey)
        assertEquals("33bc", c.shortId)
        assertEquals("chrome", c.fingerprint)
        assertEquals("auto", c.mode)
        assertEquals("/", c.path)
    }

    @Test
    fun mapsTlsWebsocketStreamSettings() {
        val c = JsonSubscription.parse(realityXhttp)[1]
        assertEquals("ws", c.network)
        assertEquals("tls", c.security)
        assertEquals("ch.example.test", c.sni)
        assertEquals("safari", c.fingerprint)
        assertEquals("/graph/api/", c.path)
        assertEquals("ch.example.test", c.host)
    }

    @Test
    fun mapsShadowsocksServer() {
        val c = JsonSubscription.parse(realityXhttp)[2]
        assertEquals("shadowsocks", c.protocol)
        assertEquals("1.2.3.4", c.address)
        assertEquals(990, c.port)
        assertEquals("chacha20-ietf-poly1305", c.method)
        assertEquals("secretpw", c.password)
    }

    /** The direct/block outbounds every entry carries must never become servers. */
    @Test
    fun ignoresFreedomAndBlackholeOutbounds() {
        val configs = JsonSubscription.parse(realityXhttp)
        assertTrue(configs.none { it.protocol == "freedom" || it.protocol == "blackhole" })
    }

    @Test
    fun returnsEmptyForLinkListsAndGarbage() {
        assertTrue(JsonSubscription.parse("vless://uuid@host:443#Name").isEmpty())
        assertTrue(JsonSubscription.parse("").isEmpty())
        assertTrue(JsonSubscription.parse("not json at all").isEmpty())
        assertTrue(JsonSubscription.parse("[{\"remarks\":\"broken\"}]").isEmpty())
    }
}
