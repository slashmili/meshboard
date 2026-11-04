package meshboard.crypto

import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** One direction of a Phase 3 connection, not a complete authentication protocol.
 * The caller must provide a fresh connection salt and authenticated context. */
class JvmFrameCipher private constructor(private var key: ByteArray?, private val encrypt: Boolean, private val context: ByteArray) : AutoCloseable {
    private var sequence = 0L

    @Synchronized fun process(input: ByteArray): ByteArray {
        try {
            val currentKey = checkNotNull(key)
            require(sequence < MAX_FRAMES)
            val header = ByteBuffer.allocate(12).put(byteArrayOf(0x4d, 0x42, 0x45, 1)).putLong(sequence).array()
            val nonce = ByteBuffer.allocate(12).putInt(0).putLong(sequence).array()
            if (encrypt) require(input.size in 1..MAX_CLEAR_FRAME)
            else require(input.size in 29..MAX_CLEAR_FRAME + 28 && input.copyOfRange(0, 12).contentEquals(header))
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, SecretKeySpec(currentKey, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(header + context)
            val result = if (encrypt) header + cipher.doFinal(input) else cipher.doFinal(input, 12, input.size - 12)
            sequence++
            return result
        } catch (_: Exception) {
            close()
            throw IllegalArgumentException("Encrypted frame rejected")
        }
    }

    @Synchronized override fun close() { key?.fill(0); key = null }

    companion object {
        const val MAX_CLEAR_FRAME = 16 * 1024
        const val MAX_FRAMES = 1_048_576L
        fun newSessionKey(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
        fun decodeSessionKey(value: String): ByteArray {
            require(Regex("^[A-Za-z0-9_-]{43}$").matches(value)) { "Invalid session key" }
            val result = Base64.getUrlDecoder().decode(value)
            require(Base64.getUrlEncoder().withoutPadding().encodeToString(result) == value) { "Invalid session key" }
            return result
        }
        fun create(secret: ByteArray, salt: ByteArray, context: String, encrypt: Boolean): JvmFrameCipher {
            val info = context.toByteArray(Charsets.UTF_8)
            require(secret.size == 32 && salt.size == 32 && info.size in 1..4096) { "Invalid key context" }
            // RFC 5869 extract then one expand block (SHA-256 output = AES-256 key size).
            val hmac = Mac.getInstance("HmacSHA256")
            hmac.init(SecretKeySpec(salt, "HmacSHA256"))
            val prk = hmac.doFinal(secret)
            try {
                hmac.init(SecretKeySpec(prk, "HmacSHA256"))
                return JvmFrameCipher(hmac.doFinal(info + byteArrayOf(1)), encrypt, info)
            } finally { prk.fill(0) }
        }
    }
}
