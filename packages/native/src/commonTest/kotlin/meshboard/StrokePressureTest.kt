package meshboard

import androidx.compose.ui.input.pointer.PointerType
import kotlin.test.*

class StrokePressureTest {
    @Test fun appleForceUsesTheDeviceMaximumBeforeClamping() {
        assertEquals(.25f, normalizedForce(1.0, 4.0))
        assertEquals(1f, normalizedForce(8.0, 4.0))
        assertEquals(0f, normalizedForce(-1.0, 4.0))
        for (maximum in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) assertTrue(normalizedForce(1.0, maximum).isNaN())
        assertTrue(normalizedForce(Double.NaN, 4.0).isNaN())
    }

    @Test fun placeholdersAndMissingPressureStayAtBaseWidth() {
        for (type in listOf(PointerType.Mouse, PointerType.Touch, PointerType.Stylus)) {
            for (p in listOf(0f, .5f, 1f, Float.NaN, Float.POSITIVE_INFINITY)) {
                val input = StrokePressure(type)
                input.add(Point(0.0, 0.0), p)
                assertTrue(input.add(Point(1.0, 0.0), p).all { it.pressure == null })
                assertTrue(input.add(Point(2.0, 0.0), 0f, false).all { it.pressure == null })
            }
        }
    }
    @Test fun fingerVariationPreservesEarlySamplesAndRelease() {
        val input = StrokePressure(PointerType.Touch)
        assertNull(input.add(Point(0.0, 0.0), .25f).first().pressure)
        assertEquals(listOf(.25, .75), input.add(Point(1.0, 0.0), .75f).map { it.pressure })
        assertEquals(listOf(.25, .75, .75), input.add(Point(2.0, 0.0), 0f, false).map { it.pressure })
        input.retainLast()
        assertEquals(listOf(.75, .75), input.add(Point(3.0, 0.0), .75f).map { it.pressure })
        assertNull(StrokePressure(PointerType.Touch).add(Point(0.0, 0.0), .75f).first().pressure)
    }
    @Test fun penClampsAndPreservesMissingEndpointsWithoutFullPressureBlob() {
        val input = StrokePressure(PointerType.Stylus)
        input.add(Point(0.0, 0.0), -1f)
        assertEquals(listOf(0.0, .25), input.add(Point(0.0, 0.0), .25f).map { it.pressure })
        assertEquals(1.0, input.add(Point(1.0, 0.0), 2f).last().pressure)
        assertNull(input.add(Point(2.0, 0.0), Float.NaN).last().pressure)
        assertNull(input.add(Point(3.0, 0.0), 0f, false).last().pressure)
    }
}
