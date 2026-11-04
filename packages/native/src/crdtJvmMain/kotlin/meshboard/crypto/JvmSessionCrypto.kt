package meshboard.crypto

import meshboard.RecordCipher
import meshboard.SessionCrypto
import java.security.SecureRandom

object JvmSessionCrypto : SessionCrypto {
    private val random = SecureRandom()
    override fun random32() = ByteArray(32).also(random::nextBytes)
    override fun cipher(secret: ByteArray, salt: ByteArray, context: String, encrypt: Boolean): RecordCipher {
        val cipher = JvmFrameCipher.create(secret, salt, context, encrypt)
        return object : RecordCipher {
            override fun process(input: ByteArray) = cipher.process(input)
            override fun close() = cipher.close()
        }
    }
}
