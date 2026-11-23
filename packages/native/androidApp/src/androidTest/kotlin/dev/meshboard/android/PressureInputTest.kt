package dev.meshboard.android

import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

/** Synthetic MotionEvents exercise Android's real Compose adapter, not pen hardware. */
@OptIn(ExperimentalTestApi::class)
class PressureInputTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>(effectContext = StandardTestDispatcher())
    private val controller get() = ViewModelProvider(rule.activity)[BoardViewModel::class.java].controller
    private var downTime = 0L
    private var elapsed = 0L

    private fun send(action: Int, points: List<Triple<Int, Float, Float>>) {
        val bounds = rule.onNodeWithTag("mobile-canvas").fetchSemanticsNode().boundsInRoot
        if (action == MotionEvent.ACTION_DOWN) { downTime = android.os.SystemClock.uptimeMillis(); elapsed = 0 }
        elapsed += 16
        val properties = points.mapIndexed { index, sample ->
            MotionEvent.PointerProperties().apply { id = index; toolType = sample.first }
        }.toTypedArray()
        val coordinates = points.map { sample ->
            MotionEvent.PointerCoords().apply {
                x = bounds.left + sample.second; y = bounds.top + 200f
                pressure = sample.third; size = .1f
            }
        }.toTypedArray()
        rule.runOnUiThread {
            val event = MotionEvent.obtain(downTime, downTime + elapsed, action, points.size,
                properties, coordinates, 0, 0, 1f, 1f, 0, 0,
                InputDevice.SOURCE_TOUCHSCREEN or InputDevice.SOURCE_STYLUS, 0)
            try { rule.activity.dispatchTouchEvent(event) } finally { event.recycle() }
        }
        rule.waitForIdle()
    }

    @Test fun stylusPressureSurvivesPalmContactAndLiftOff() {
        val pen = MotionEvent.TOOL_TYPE_STYLUS
        val finger = MotionEvent.TOOL_TYPE_FINGER
        send(MotionEvent.ACTION_DOWN, listOf(Triple(pen, 400f, .1f)))
        send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            listOf(Triple(pen, 400f, .1f), Triple(finger, 700f, 1f)))
        send(MotionEvent.ACTION_MOVE, listOf(Triple(pen, 500f, .9f), Triple(finger, 700f, 1f)))
        send(MotionEvent.ACTION_POINTER_UP, listOf(Triple(pen, 550f, 0f), Triple(finger, 700f, 1f)))
        send(MotionEvent.ACTION_UP, listOf(Triple(finger, 700f, 0f)))
        rule.waitUntil(5000) { controller.state.value.elements.size == 1 }
        val points = controller.state.value.elements.single().points
        assertEquals(.1, points.first().pressure!!, .00001)
        assertEquals(.9, points.last().pressure!!, .00001)
        assertTrue(points.first().x < points.last().x)
        assertTrue(points.all { it.x <= points.last().x }) // The palm lies beyond the pen endpoint.
    }

    @Test fun constantFingerPressureAndCancelledContactsDoNotCreatePressureOrStrayInk() {
        val finger = MotionEvent.TOOL_TYPE_FINGER
        send(MotionEvent.ACTION_DOWN, listOf(Triple(finger, 400f, 1f)))
        send(MotionEvent.ACTION_MOVE, listOf(Triple(finger, 500f, 1f)))
        send(MotionEvent.ACTION_UP, listOf(Triple(finger, 550f, 0f)))
        rule.waitUntil(5000) { controller.state.value.elements.size == 1 }
        assertTrue(controller.state.value.elements.single().points.all { it.pressure == null })
        send(MotionEvent.ACTION_DOWN, listOf(Triple(finger, 400f, .2f)))
        send(MotionEvent.ACTION_MOVE, listOf(Triple(finger, 500f, .8f)))
        send(MotionEvent.ACTION_CANCEL, listOf(Triple(finger, 500f, 0f)))
        rule.runOnIdle { assertEquals(1, controller.state.value.elements.size) }
    }
}
