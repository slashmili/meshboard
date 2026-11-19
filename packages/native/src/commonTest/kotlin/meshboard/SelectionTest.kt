package meshboard
import kotlin.test.*

class SelectionTest {
    private val pen = BoardElement("pen", "pen", "#293b36", 3.0, listOf(Point(0.0,0.0),Point(100.0,100.0),Point(20.0,20.0)))
    @Test fun marqueeIncludesEveryPointAndRequiresFullEnclosure() {
        assertEquals(SelectionBox(0.0,0.0,100.0,100.0), selectionBounds(pen))
        assertTrue(Selection(start=Point(-1.0,-1.0), end=Point(30.0,30.0)).finish(listOf(pen)).ids.isEmpty())
        assertEquals(listOf("pen"), Selection(start=Point(101.0,101.0),end=Point(-1.0,-1.0)).finish(listOf(pen)).ids)
    }
    @Test fun togglingOffADotDoesNotReSelectIt() {
        val dot = pen.copy(points=listOf(Point(0.0,0.0)))
        assertTrue(Selection(listOf("pen")).begin(listOf(dot),Point(0.0,0.0),2.0,true).finish(listOf(dot)).ids.isEmpty())
    }
    @Test fun movingPreservesDataAndRejectsOutOfBoundsGroups() {
        val pressure = pen.copy(points=listOf(Point(0.0,0.0,0.5)))
        assertEquals(Point(5.0,-2.0,0.5), translated(listOf(pressure),listOf("pen","deleted"),Point(5.0,-2.0)).single().points.single())
        assertFails { translated(listOf(pen),listOf("pen"),Point(1e8,0.0)) }
    }
}
