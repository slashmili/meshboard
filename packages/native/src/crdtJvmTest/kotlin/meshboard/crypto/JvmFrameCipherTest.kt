package meshboard.crypto

import kotlin.test.*

class JvmFrameCipherTest {
    private val secret = ByteArray(32) { 7 }
    private val salt = ByteArray(32) { 9 }
    private val context = "meshboard/phase3/vector/sender->receiver"
    private val clear = byteArrayOf(0, 0, 1, 0)
    private fun make(encrypt: Boolean, info: String = context, key: ByteArray = secret, nonce: ByteArray = salt) = JvmFrameCipher.create(key, nonce, info, encrypt)

    @Test fun matchesWebCryptoVectors() {
        val vectors = listOf("4d424501000000000000000001b1593506cf39475764eccdb4115385127f7844", "4d42450100000000000000017a1534afd35753a2733f173cc06ed3eecbc319e4")
        make(true).use { sender -> make(false).use { receiver ->
            vectors.forEach { vector ->
                val encrypted = sender.process(clear)
                assertEquals(vector, encrypted.joinToString("") { "%02x".format(it) })
                assertContentEquals(clear, receiver.process(encrypted))
            }
        } }
    }
    @Test fun authenticationRejectsWrongContextAndKeys() {
        val frame = make(true).use { it.process(clear) }
        listOf(make(false, key = ByteArray(32)), make(false, nonce = ByteArray(32)), make(false, info = "$context/other-room"), make(false, info = "meshboard/phase3/vector/receiver->sender"), make(false, info = "$context/other-fingerprint")).forEach { receiver ->
            receiver.use { assertFailsWith<IllegalArgumentException> { it.process(frame) } }
        }
    }
    @Test fun everyTamperedByteFailsAndClosesTheCipher() {
        val frame = make(true).use { it.process(clear) }
        frame.indices.forEach { index ->
            val changed = frame.copyOf(); changed[index] = (changed[index].toInt() xor 1).toByte()
            make(false).use { receiver ->
                assertFailsWith<IllegalArgumentException> { receiver.process(changed) }
                assertFailsWith<IllegalArgumentException> { receiver.process(frame) }
            }
        }
    }
    @Test fun rejectsReplayGapsPlaintextAndInvalidSizes() {
        make(true).use { sender ->
            val first = sender.process(clear); val second = sender.process(clear)
            make(false).use { receiver ->
                receiver.process(first)
                assertFailsWith<IllegalArgumentException> { receiver.process(first) }
            }
            listOf(second, clear, first.copyOf(first.size - 1), ByteArray(JvmFrameCipher.MAX_CLEAR_FRAME + 29)).forEach { frame ->
                make(false).use { receiver -> assertFailsWith<IllegalArgumentException> { receiver.process(frame) } }
            }
        }
        listOf(ByteArray(0), ByteArray(JvmFrameCipher.MAX_CLEAR_FRAME + 1)).forEach { frame ->
            make(true).use { sender -> assertFailsWith<IllegalArgumentException> { sender.process(frame) } }
        }
        val maximum = ByteArray(JvmFrameCipher.MAX_CLEAR_FRAME) { 42 }
        make(true).use { sender -> make(false).use { receiver -> assertContentEquals(maximum, receiver.process(sender.process(maximum))) } }
    }
    @Test fun disposalAndInvalidDerivationInputsFailClosed() {
        val sender = make(true); sender.close()
        assertFailsWith<IllegalArgumentException> { sender.process(clear) }
        assertFailsWith<IllegalArgumentException> { make(true, key = ByteArray(31)) }
        assertFailsWith<IllegalArgumentException> { make(true, nonce = ByteArray(31)) }
        assertFailsWith<IllegalArgumentException> { make(true, info = "") }
        assertFailsWith<IllegalArgumentException> { make(true, info = "x".repeat(4097)) }
    }
    @Test fun exhaustsConnectionBudgetWithoutReusingANonce() {
        make(true).use { sender -> make(false).use { receiver ->
            val field = JvmFrameCipher::class.java.getDeclaredField("sequence").also { it.isAccessible = true }
            field.setLong(sender, JvmFrameCipher.MAX_FRAMES - 1)
            field.setLong(receiver, JvmFrameCipher.MAX_FRAMES - 1)
            val last = sender.process(clear)
            assertContentEquals(clear, receiver.process(last))
            assertFailsWith<IllegalArgumentException> { sender.process(clear) }
            assertFailsWith<IllegalArgumentException> { receiver.process(last) }
        } }
    }
    @Test fun sessionKeysAreCanonicalAnd256Bits() {
        val keys = List(20) { JvmFrameCipher.newSessionKey() }
        assertEquals(20, keys.toSet().size)
        keys.forEach { assertEquals(32, JvmFrameCipher.decodeSessionKey(it).size) }
        listOf("", "a".repeat(42), "A".repeat(42) + "B", "A".repeat(43) + "=", "+".repeat(43)).forEach { key ->
            assertFailsWith<IllegalArgumentException> { JvmFrameCipher.decodeSessionKey(key) }
        }
    }
}
