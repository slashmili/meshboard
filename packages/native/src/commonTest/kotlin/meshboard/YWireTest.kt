package meshboard

import kotlin.test.*

class YWireTest {
    @Test fun syncMatchesUpstreamFixtureAndVarintsAreBounded() {
        assertContentEquals(byteArrayOf(0, 0, 1, 0), YWire.sync(0, byteArrayOf(0)))
        for (value in listOf(0L, 127L, 128L, 0xffff_ffffL, 9_007_199_254_740_991L)) {
            val reader = YWire.Reader(YWire.Writer().uint(value).finish())
            assertEquals(value, reader.uint()); reader.end()
        }
        assertFails { YWire.Reader(byteArrayOf(128.toByte())).uint() }
        assertFails { YWire.Reader(ByteArray(9) { 255.toByte() }).uint() }
        assertFails { YWire.Reader(byteArrayOf(3, 1)).bytes() }
        assertFails { YWire.Reader(byteArrayOf(0, 0)).apply { uint(); end() } }
    }
    @Test fun fragmentsRoundTripAndRejectInvalidSequences() {
        val bytes = ByteArray(500_000) { 42 }; bytes[0] = 0
        assertContentEquals(byteArrayOf(127, 0, 7, -95, 32), YWire.frames(bytes).first().copyOfRange(0, 5))
        val receiver = YWire.Receiver()
        val messages = YWire.frames(bytes).mapNotNull(receiver::accept)
        assertEquals(1, messages.size); assertContentEquals(bytes, messages.single())
        assertFails { YWire.frames(ByteArray(YWire.MAX_MESSAGE + 1)) }
        assertFails { YWire.Receiver().accept(byteArrayOf(127, 127, -1, -1, -1, 0)) }
        var now = 0L
        val expired = YWire.Receiver { now }; val frames = YWire.frames(bytes)
        expired.accept(frames.first()); now = 30_001
        assertFails { expired.accept(frames[1]) }
        val interleaved = YWire.Receiver(); interleaved.accept(frames.first())
        assertFails { interleaved.accept(byteArrayOf(3)) }
    }
    @Test fun awarenessMatchesUpstreamAndHandlesClocksRemovalExpiry() {
        var now = 0L
        val awareness = YAwareness { now }
        val upstream = byteArrayOf(1, 42, 1, 2, 123, 125)
        assertContentEquals(YWire.awareness(upstream), awareness.receive(upstream))
        assertNull(awareness.receive(upstream))
        fun update(clock: Long, json: String) = YWire.Writer().uint(1).uint(42).uint(clock).string(json).finish()
        val shape = BoardElement("test", "pen", "#123456", 3.0, listOf(Point(1.0, 2.0)))
        val state = "{\"meshboard\":{\"peerId\":\"test-peer\",\"preview\":${Wire.elementJson(shape)}}}"
        awareness.receive(update(2, state)); assertEquals(listOf(shape), awareness.previews().values.toList())
        assertNull(awareness.receive(update(1, "null"))); assertEquals(1, awareness.previews().size)
        awareness.receive(update(2, "null")); assertTrue(awareness.previews().isEmpty())
        assertNull(awareness.receive(update(2, state)))
        awareness.receive(update(3, state)); now = 30_001
        assertNotNull(awareness.expire()); assertTrue(awareness.previews().isEmpty())
        assertFails { awareness.receive(update(4, "[]")) }
        assertFails { awareness.receive(byteArrayOf(65)) }
    }
}
