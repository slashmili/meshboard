package meshboard

import kotlin.io.encoding.Base64

interface RecordCipher {
    @Throws(Exception::class)
    fun process(input: ByteArray): ByteArray
    fun close()
}
interface SessionCrypto {
    @Throws(Exception::class)
    fun random32(): ByteArray
    @Throws(Exception::class)
    fun cipher(secret: ByteArray, salt: ByteArray, context: String, encrypt: Boolean): RecordCipher
}
object SecureWire {
    const val CHANNEL = "meshboard.secure-v1"
    private val uuid = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
    fun validId(value: String) = uuid.matches(value)
    fun key(bytes: ByteArray): String { require(bytes.size == 32); return Base64.UrlSafe.encode(bytes).trimEnd('=') }
    fun decodeKey(value: String): ByteArray {
        require(Regex("^[A-Za-z0-9_-]{43}$").matches(value)) { "Invalid session key" }
        return Base64.UrlSafe.decode("$value=").also { require(key(it) == value) { "Invalid session key" } }
    }
    fun fingerprint(sdp: String): String {
        require(sdp.length <= 65536 && "m=application " in sdp)
        val lines = sdp.lineSequence().map { it.trimEnd('\r') }.filter { it.startsWith("a=fingerprint:") }.toList()
        require(lines.isNotEmpty() && lines.all { Regex("^a=fingerprint:sha-256 ([0-9a-f]{2}:){31}[0-9a-f]{2}$", RegexOption.IGNORE_CASE).matches(it) }) { "Invalid DTLS fingerprint" }
        val values = lines.map { it.substringAfter(' ').replace(":", "").lowercase() }.distinct()
        require(values.size == 1) { "Ambiguous DTLS fingerprint" }
        return values.single()
    }
}

/** All methods run serially on the platform session executor. */
class SecureChannel(private val crypto: SessionCrypto, key: String, private val room: String, private val self: String, private val peer: String, localSdp: String, remoteSdp: String) {
    private val secret = SecureWire.decodeKey(key)
    private val challenge = crypto.random32().also { require(it.size == 32) }
    val hello = byteArrayOf(77, 66, 72, 1) + challenge
    var authenticated = false; private set
    private val localFingerprint = SecureWire.fingerprint(localSdp)
    private val remoteFingerprint = SecureWire.fingerprint(remoteSdp)
    private var sender: RecordCipher? = null
    private var receiver: RecordCipher? = null
    private var remoteHello = false
    private var closed = false
    init { require(listOf(room, self, peer).all(SecureWire::validId) && self != peer) }
    data class Result(val response: ByteArray? = null, val data: ByteArray? = null, val admitted: Boolean = false)
    private fun context(sender: String, receiver: String, senderFp: String, receiverFp: String, senderNonce: ByteArray, receiverNonce: ByteArray): String =
        listOf(SecureWire.CHANNEL, room, sender, receiver, senderFp, receiverFp, senderNonce.toHexString(), receiverNonce.toHexString()).joinToString("\n")
    @Throws(Exception::class)
    fun receive(bytes: ByteArray): Result {
        try {
            check(!closed)
            if (!remoteHello) {
                require(bytes.size == 36 && bytes.copyOfRange(0, 4).contentEquals(byteArrayOf(77, 66, 72, 1)))
                val remote = bytes.copyOfRange(4, 36); require(!remote.contentEquals(challenge)); remoteHello = true
                sender = crypto.cipher(secret, ByteArray(32), context(self, peer, localFingerprint, remoteFingerprint, challenge, remote), true)
                receiver = crypto.cipher(secret, ByteArray(32), context(peer, self, remoteFingerprint, localFingerprint, remote, challenge), false)
                secret.fill(0)
                return Result(response = sender!!.process(byteArrayOf(77, 66, 65, 1)))
            }
            val data = receiver!!.process(bytes)
            if (!authenticated) {
                require(data.contentEquals(byteArrayOf(77, 66, 65, 1)))
                authenticated = true; return Result(admitted = true)
            }
            return Result(data = data)
        } catch (_: Exception) { close(); throw IllegalArgumentException("Peer authentication failed") }
    }
    @Throws(Exception::class)
    fun encrypt(bytes: ByteArray): ByteArray { check(authenticated && !closed); return sender!!.process(bytes) }
    fun close() { closed = true; authenticated = false; secret.fill(0); sender?.close(); receiver?.close() }
}

/** Stable Swift-facing factory; construction failures cross the NSError boundary. */
object SecureChannels {
    @Throws(Exception::class)
    fun open(crypto: SessionCrypto, key: String, room: String, localId: String, remoteId: String, localSdp: String, remoteSdp: String) =
        SecureChannel(crypto, key, room, localId, remoteId, localSdp, remoteSdp)
}
