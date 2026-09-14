package meshboard

import kotlin.test.*

class BoardTest {
    private val stroke = BoardElement("stroke-1", "pen", "#293b36", 3.0, listOf(Point(0.0, 0.0), Point(100.0, 100.0)))
    @Test fun deletionsSurviveLateSnapshots() {
        val a = BoardDocument(); val b = BoardDocument()
        a.apply(BoardMessage("put", element = stroke))
        b.apply(a.snapshot())
        a.apply(BoardMessage("remove", ids = listOf(stroke.id)))
        a.apply(b.snapshot()); b.apply(a.snapshot())
        assertTrue(a.elements.isEmpty()); assertEquals(a.snapshot(), b.snapshot())
    }
    @Test fun concurrentObjectsMergeAndPreviewsStayEphemeral() {
        val a = BoardDocument(); val b = BoardDocument()
        a.apply(BoardMessage("put", element = stroke)); b.apply(BoardMessage("put", element = stroke.copy(id = "stroke-2")))
        a.apply(b.snapshot()); b.apply(a.snapshot())
        assertEquals(2, a.elements.size); assertEquals(a.snapshot(), b.snapshot())
        a.apply(BoardMessage("preview", element = stroke.copy(id = "preview")))
        assertEquals(2, a.elements.size)
        a.reset(); assertTrue(a.snapshot().removed.isEmpty()); assertTrue(a.elements.isEmpty())
    }
    @Test fun zoomPreservesAnchorAndClamps() {
        val view = View(20.0, -30.0, 2.0); val anchor = Point(400.0, 200.0)
        assertEquals(view.world(anchor), view.zoomAt(anchor, 3.0).world(anchor))
        assertEquals(4.0, view.zoomAt(anchor, 100.0).zoom)
        assertEquals(.25, view.zoomAt(anchor, 0.0).zoom)
    }
    @Test fun eraserSweepsAcrossOutlinesNotShapeInteriors() {
        assertTrue(hits(stroke, Point(0.0, 100.0), Point(100.0, 0.0), 1.0))
        val rect = stroke.copy(type = "rectangle")
        assertFalse(hits(rect, Point(50.0, 50.0), Point(50.0, 50.0), 1.0))
        assertTrue(hits(rect, Point(-20.0, 50.0), Point(120.0, 50.0), 1.0))
        assertEquals(Point(-100.0, 100.0), constrained(Point(0.0, 0.0), Point(-50.0, 100.0), "rectangle"))
    }
    @Test fun largeMessageFramesRoundTripAndRejectBadSequences() {
        val big = BoardMessage("put", element = stroke.copy(points = (0 until 12000).map { Point(it.toDouble(), 1.0) }))
        val frames = Wire.frames(big, "123e4567-e89b-42d3-a456-426614174000")
        assertTrue(frames.size > 1); assertTrue(frames.all { it.encodeToByteArray().size <= Wire.MAX_FRAME })
        val receiver = FrameReceiver { 0 }
        frames.dropLast(1).forEach { assertNull(receiver.accept(it)) }
        assertEquals(big, receiver.accept(frames.last()))
        assertFails { FrameReceiver { 0 }.accept(frames[1]) }
        var now = 0L; val expired = FrameReceiver { now }; expired.accept(frames[0]); now = 30001
        assertFails { expired.accept(frames[1]) }
    }
}
