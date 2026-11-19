package meshboard.crypto

import meshboard.*
import kotlin.test.*

class SecureChannelTest {
    private val room = "12345678-1234-4234-8234-123456789abc"
    private val alice = "12345678-1234-4234-8234-123456789abd"
    private val bob = "12345678-1234-4234-8234-123456789abe"
    private val key = "A".repeat(43)
    private fun sdp(byte: String) = "a=fingerprint:sha-256 ${List(32) { byte }.joinToString(":")}\r\nm=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n"
    private fun crypto(nonce: Int) = object : SessionCrypto {
        override fun random32() = ByteArray(32) { nonce.toByte() }
        override fun cipher(secret: ByteArray, salt: ByteArray, context: String, encrypt: Boolean) = JvmSessionCrypto.cipher(secret, salt, context, encrypt)
    }
    private fun pair(otherKey: String = key, remote: String = sdp("22")) =
        SecureChannel(crypto(1), key, room, alice, bob, sdp("11"), remote) to SecureChannel(crypto(2), otherKey, room, bob, alice, sdp("22"), sdp("11"))
    @Test fun matchesIndependentConfirmationVectorAndEncryptsBothDirections() {
        val (a, b) = pair()
        assertFails { a.encrypt(byteArrayOf(1)) }
        val aProof = a.receive(b.hello).response!!; val bProof = b.receive(a.hello).response!!
        assertEquals("4d42450100000000000000004dbdefb9a050490bd5dcc0a7d1f9e958e8732708", aProof.toHexString())
        assertTrue(a.receive(bProof).admitted); assertTrue(b.receive(aProof).admitted)
        assertContentEquals(byteArrayOf(0, 1, 2), b.receive(a.encrypt(byteArrayOf(0, 1, 2))).data)
        assertContentEquals(byteArrayOf(3), a.receive(b.encrypt(byteArrayOf(3))).data)
    }
    @Test fun rejectsWrongKeysAndFingerprintSubstitution() {
        listOf(pair(SecureWire.key(ByteArray(32) { 3 })), pair(remote = sdp("33"))).forEach { (a, b) ->
            a.receive(b.hello)
            assertFails { a.receive(b.receive(a.hello).response!!) }; assertFalse(a.authenticated)
        }
    }
    @Test fun rejectsReflectionPlaintextAndReconnectReplay() {
        val (a, b) = pair(); assertFails { a.receive(a.hello) }; assertFails { b.receive(byteArrayOf(0, 0, 1, 0)) }
        val (c, d) = pair(); c.receive(d.hello); assertFails { c.receive(d.hello) }
        val (e, f) = pair(); val oldProof = f.receive(e.hello).response!!
        val fresh = SecureChannel(crypto(3), key, room, alice, bob, sdp("11"), sdp("22"))
        fresh.receive(f.hello); assertFails { fresh.receive(oldProof) }
    }
    @Test fun bindsRoomAndPeerIdentities() {
        listOf(SecureChannel(crypto(2), key, bob, bob, alice, sdp("22"), sdp("11")), SecureChannel(crypto(2), key, room, bob, room, sdp("22"), sdp("11"))).forEach { other ->
            val (a, _) = pair(); a.receive(other.hello); assertFails { a.receive(other.receive(a.hello).response!!) }
        }
    }
    @Test fun rejectsAmbiguousOrUnsupportedFingerprintsAndBadIdentities() {
        assertEquals("ab".repeat(32), SecureWire.fingerprint(sdp("AB")))
        listOf("", sdp("11").replace("sha-256", "sha-1"), sdp("11") + sdp("22"), sdp("GG")).forEach { value -> assertFails { SecureWire.fingerprint(value) } }
        assertFails { SecureChannel(crypto(1), key, room, alice, alice, sdp("11"), sdp("22")) }
    }
}
