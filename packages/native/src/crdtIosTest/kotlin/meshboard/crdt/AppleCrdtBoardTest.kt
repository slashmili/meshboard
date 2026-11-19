@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package meshboard.crdt

import kotlin.test.*
import meshboard.BoardElement
import meshboard.Point
import meshboard.Wire
import meshboard.crdt.ffi.*

class AppleCrdtBoardTest {
    private val stroke = BoardElement("stroke", "pen", "#123456", 3.0, listOf(Point(-1.25, 2.5)))

    @Test fun atomicMovesPreservePressureAndIgnoreDeletedIds() {
        val board = AppleCrdtBoard()
        try {
            board.put(stroke.copy(points = listOf(Point(1.0,2.0,0.5))))
            board.put(stroke.copy(id = "deleted")); board.remove("deleted")
            val before = board.elements()
            assertFails { board.move(listOf(stroke.id), Point(1e8,0.0)) }
            assertEquals(before, board.elements())
            board.move(listOf(stroke.id,"deleted"),Point(10.0,-3.0))
            assertEquals(listOf(Point(11.0,-1.0,0.5)),board.elements().single().points)
        } finally { board.close() }
    }
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

    @Test fun undoRedoBindingsPreserveRemoteObjects() {
        val a = AppleCrdtBoard(); val b = AppleCrdtBoard()
        try {
            a.put(stroke); b.apply(a.update())
            assertEquals(false to false, b.history())
            b.put(stroke.copy(id = "remote")); a.apply(b.update(a.stateVector()))
            a.undo(); b.apply(a.update(b.stateVector()))
            assertEquals(listOf("remote"), b.elements().map { it.id })
            assertEquals(false to true, a.history())
            a.redo(); b.apply(a.update(b.stateVector()))
            assertEquals(a.elements(), b.elements())
        } finally { a.close(); b.close() }
    }

    @Test fun embeddedImageSurvivesAppleBindingsAndUndo() {
        val a = AppleCrdtBoard(); val b = AppleCrdtBoard()
        try {
            val element = stroke.copy(type = "image", points = listOf(Point(0.0,0.0),Point(40.0,30.0)), image = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAACgAAAAeAQMAAABkE86eAAAAIGNIUk0AAHomAACAhAAA+gAAAIDoAAB1MAAA6mAAADqYAAAXcJy6UTwAAAADUExURdweKMIdZqsAAAAHdElNRQfqCwgWKxJUDdhZAAAAJXRFWHRkYXRlOmNyZWF0ZQAyMDI2LTExLTA4VDIyOjQzOjE4KzAwOjAwmmLvvQAAACV0RVh0ZGF0ZTptb2RpZnkAMjAyNi0xMS0wOFQyMjo0MzoxOCswMDowMOs/VwEAAAAodEVYdGRhdGU6dGltZXN0YW1wADIwMjYtMTEtMDhUMjI6NDM6MTgrMDA6MDC8KnbeAAAADElEQVQI12NgGHoAAAC0AAFecZC/AAAAAElFTkSuQmCC")
            a.put(element); b.apply(a.update())
            assertEquals(listOf(element),b.elements())
            a.undo(); assertTrue(a.elements().isEmpty())
            a.redo(); assertEquals(listOf(element),a.elements())
        } finally { a.close(); b.close() }
    }

    @Test fun attachedArrowRoundTripsAndKeepsItsAnchorDuringMove() {
        val a = AppleCrdtBoard(); val b = AppleCrdtBoard()
        try {
            val arrow = stroke.copy(type = "arrow", points = listOf(Point(0.0,0.0),Point(40.0,30.0)), startAttachment = meshboard.Attachment("target",1.0,0.5))
            a.put(arrow); a.move(listOf(arrow.id),Point(20.0,10.0)); b.apply(a.update())
            assertEquals(arrow.startAttachment,b.elements().single().startAttachment)
            assertEquals(listOf(Point(0.0,0.0),Point(60.0,40.0)),b.elements().single().points)
            a.undo(); assertEquals(listOf(arrow),a.elements())
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
