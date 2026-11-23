package meshboard

import kotlin.test.*

class BoardTest {
    @Test fun pressureWidthsHitTestsAndInputFallbackAgree() {
        assertEquals(2.0, penWidth(8.0, 0.0)); assertEquals(8.0, penWidth(8.0, 1.0))
        assertEquals(8.0, penWidth(8.0, null))
        val thin = BoardElement("thin", "pen", "#293b36", 8.0, listOf(Point(0.0,0.0,0.0), Point(100.0,0.0,0.0)))
        assertFalse(hits(thin, Point(50.0,2.0), Point(50.0,2.0), 0.0))
        assertTrue(hits(thin, Point(50.0,1.0), Point(50.0,1.0), 0.0))
    }

    @Test fun arrowGeometryIncludesErasableWingsAndHandlesShortOrZeroLengths() {
        val a = Point(0.0, 0.0); val b = Point(100.0, 0.0)
        val arrow = BoardElement("arrow", "arrow", "#293b36", 3.0, listOf(a, b))
        assertEquals(listOf(a, b, Point(88.0, 6.0), b, Point(88.0, -6.0)), outline(arrow))
        assertEquals(Point(12.0, -6.0), outline(arrow.copy(points = listOf(b, a)))[2])
        assertEquals(listOf(a, a), outline(arrow.copy(points = listOf(a, a))))
        assertEquals(Point(2.0, 1.0), outline(arrow.copy(points = listOf(a, Point(4.0, 0.0))))[2])
        assertTrue(hits(arrow, Point(88.0, 6.0), Point(88.0, 6.0), 0.0))
        assertFalse(hits(arrow, Point(88.0, 10.0), Point(88.0, 10.0), 0.0))
        val snapped = constrained(a, Point(100.0, 90.0), "arrow")
        assertEquals(snapped.x, snapped.y, 0.000001)
    }

    private val stroke = BoardElement("stroke-1", "pen", "#293b36", 3.0, listOf(Point(0.0, 0.0), Point(100.0, 100.0)))
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
}
