package meshboard.crdt

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*
import meshboard.BoardElement
import meshboard.Point
import meshboard.Wire

class JvmCrdtBoardTest {
    private val stroke = BoardElement("stroke", "pen", "#123456", 3.0, listOf(Point(-1.25, 2.5)))

    @Test fun independentBoardsExchangeBinaryUpdatesAndDeletions() {
        JvmCrdtBoard().use { a -> JvmCrdtBoard().use { b ->
            a.put(stroke)
            assertTrue(b.elements().isEmpty())
            val snapshot = a.update()
            b.apply(snapshot)
            assertEquals(listOf(stroke), b.elements())
            a.remove(stroke.id)
            b.apply(a.update(b.stateVector()))
            b.apply(snapshot)
            assertTrue(b.elements().isEmpty())
        } }
    }

    @Test fun closingIsIdempotentAndUseAfterCloseFails() {
        val board = JvmCrdtBoard()
        board.close()
        board.close()
        assertFailsWith<IllegalStateException> { board.stateVector() }
        assertFailsWith<IllegalStateException> { board.put(stroke) }
    }

    @Test fun jniRejectsStaleHandlesAndUnknownOperationsWithoutCrashing() {
        val handle = CrdtJni.create(-1)
        try {
            assertFailsWith<IllegalArgumentException> { CrdtJni.call(handle, 999, byteArrayOf()) }
        } finally { CrdtJni.call(handle, 7, byteArrayOf()) }
        assertFailsWith<IllegalArgumentException> { CrdtJni.call(handle, 4, byteArrayOf()) }
        assertFailsWith<IllegalArgumentException> { CrdtJni.call(handle, 7, byteArrayOf()) }
        assertFailsWith<IllegalArgumentException> { CrdtJni.create(0) }
    }

    @Test fun invalidBinaryAndOversizedInputAreRejected() {
        JvmCrdtBoard().use { board ->
            assertFailsWith<IllegalArgumentException> { board.apply(byteArrayOf(-1)) }
            assertFailsWith<IllegalArgumentException> { board.update(byteArrayOf(-1)) }
            assertFailsWith<IllegalArgumentException> { board.apply(ByteArray(Wire.MAX_MESSAGE + 1)) }
            board.put(stroke)
            assertEquals(listOf(stroke), board.elements())
        }
        val handle = CrdtJni.create(-1)
        try {
            assertFailsWith<IllegalArgumentException> { CrdtJni.call(handle, 1, ByteArray(Wire.MAX_MESSAGE + 1)) }
        } finally { CrdtJni.call(handle, 7, byteArrayOf()) }
    }

    @Test fun existingElementValidationAppliesBeforeCrossingJni() {
        JvmCrdtBoard().use { board ->
            assertFailsWith<IllegalArgumentException> { board.put(stroke.copy(width = -1.0)) }
            assertFailsWith<IllegalArgumentException> { board.remove("bad_id") }
            assertTrue(board.elements().isEmpty())
        }
    }

    @Test fun concurrentCallersDoNotLoseWrites() {
        JvmCrdtBoard().use { board ->
            val executor = Executors.newFixedThreadPool(4)
            try {
                val writes = (1..40).map { i -> executor.submit { board.put(stroke.copy(id = "stroke-$i")) } }
                writes.forEach { it.get(10, TimeUnit.SECONDS) }
                assertEquals(40, board.elements().size)
                board.removeAll((1..40).map { "stroke-$it" })
                assertTrue(board.elements().isEmpty())
            } finally { executor.shutdownNow() }
        }
    }
}
