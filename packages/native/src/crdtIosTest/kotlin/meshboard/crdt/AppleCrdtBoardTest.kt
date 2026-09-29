@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package meshboard.crdt

import kotlin.test.*
import meshboard.BoardElement
import meshboard.Point
import meshboard.Wire
import meshboard.crdt.ffi.*

class AppleCrdtBoardTest {
    private val stroke = BoardElement("stroke", "pen", "#123456", 3.0, listOf(Point(-1.25, 2.5)))

    @Test fun independentBoardsExchangeSnapshotsDeltasAndDeletionOnlyUpdates() {
        val a = AppleCrdtBoard()
        val b = AppleCrdtBoard()
        try {
            a.put(stroke)
            assertTrue(b.elements().isEmpty())
            val snapshot = a.update()
            b.apply(snapshot)
            assertEquals(listOf(stroke), b.elements())
            val vector = b.stateVector()
            a.remove(stroke.id)
            assertContentEquals(vector, a.stateVector())
            b.apply(a.update(vector))
            b.apply(snapshot)
            assertTrue(b.elements().isEmpty())
        } finally { a.close(); b.close() }
    }

    @Test fun closeIsIdempotentAndUseAfterCloseFails() {
        val board = AppleCrdtBoard()
        board.close()
        board.close()
        assertFailsWith<IllegalStateException> { board.stateVector() }
        assertFailsWith<IllegalStateException> { board.put(stroke) }
    }

    @Test fun boundaryRejectsStaleHandlesInvalidIdsAndUnknownOperations() {
        assertFailsWith<IllegalArgumentException> { takeResult(meshboard_crdt_create(0)) }
        val handle = takeResult(meshboard_crdt_create(-1)).first
        try {
            assertFailsWith<IllegalArgumentException> { takeResult(meshboard_crdt_call(handle, 999, null, 0uL)) }
            assertFailsWith<IllegalArgumentException> { takeResult(meshboard_crdt_call(handle, 1, null, 1uL)) }
            assertFailsWith<IllegalArgumentException> {
                takeResult(meshboard_crdt_call(handle, 1, null, (Wire.MAX_MESSAGE + 1).toULong()))
            }
        } finally { takeResult(meshboard_crdt_call(handle, 7, null, 0uL)) }
        assertFailsWith<IllegalArgumentException> { takeResult(meshboard_crdt_call(handle, 4, null, 0uL)) }
    }

    @Test fun malformedAndOversizedInputsLeaveDocumentIntact() {
        val board = AppleCrdtBoard()
        try {
            board.put(stroke)
            repeat(20) {
                assertFailsWith<IllegalArgumentException> { board.apply(byteArrayOf(-1)) }
                assertFailsWith<IllegalArgumentException> { board.update(byteArrayOf(-1)) }
            }
            assertFailsWith<IllegalArgumentException> { board.apply(ByteArray(Wire.MAX_MESSAGE + 1)) }
            assertFailsWith<IllegalArgumentException> { board.put(stroke.copy(width = -1.0)) }
            assertFailsWith<IllegalArgumentException> { board.remove("bad_id") }
            assertEquals(listOf(stroke), board.elements())
        } finally { board.close() }
    }

    @Test fun largeSnapshotAndBatchRemovalRoundTrip() {
        val a = AppleCrdtBoard()
        val b = AppleCrdtBoard()
        try {
            val large = stroke.copy(points = (0 until 12000).map { Point(it / 8.0, -it / 4.0) })
            a.put(large)
            a.put(stroke.copy(id = "second"))
            val snapshot = a.update()
            b.apply(snapshot)
            assertEquals(a.elements(), b.elements())
            a.removeAll(listOf("stroke", "second"))
            b.apply(a.update(b.stateVector()))
            b.apply(snapshot)
            assertTrue(b.elements().isEmpty())
        } finally { a.close(); b.close() }
    }
}
