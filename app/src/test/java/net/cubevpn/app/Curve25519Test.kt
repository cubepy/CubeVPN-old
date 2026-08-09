package net.cubevpn.app

import org.junit.Test
import org.junit.Assert.assertEquals

private fun hexToBytes(s: String): ByteArray =
    ByteArray(s.length / 2) { i -> ((Character.digit(s[i * 2], 16) shl 4) + Character.digit(s[i * 2 + 1], 16)).toByte() }

private fun bytesToHex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

/** RFC 7748 section 5.2 test vectors for X25519. */
class Curve25519Test {
    @Test
    fun scalarmultBaseMatchesRfc7748VectorOne() {
        val method = Curve25519::class.java.getDeclaredMethod("scalarmultBase", ByteArray::class.java)
        method.isAccessible = true
        val alicePriv = hexToBytes("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val alicePub = method.invoke(Curve25519, alicePriv) as ByteArray
        assertEquals("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a", bytesToHex(alicePub))
    }

    @Test
    fun scalarmultBaseMatchesRfc7748VectorTwo() {
        val method = Curve25519::class.java.getDeclaredMethod("scalarmultBase", ByteArray::class.java)
        method.isAccessible = true
        val bobPriv = hexToBytes("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
        val bobPub = method.invoke(Curve25519, bobPriv) as ByteArray
        assertEquals("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f", bytesToHex(bobPub))
    }
}
