@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.cinterop.ExperimentalForeignApi::class)
package meshboard

import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerInputChange
import platform.UIKit.UIEvent
import platform.UIKit.UITouch

/** Compose 1.9.3 forwards raw UITouch.force, not force / maximumPossibleForce. */
fun appleContactPressure(event: PointerEvent, change: PointerInputChange): Float {
    val native = event.nativeEvent as? UIEvent ?: return Float.NaN
    // This is the touch identity used by the pinned Compose UIKit adapter.
    val touch = native.allTouches?.filterIsInstance<UITouch>()
        ?.firstOrNull { it.hashCode().toLong() == change.id.value } ?: return Float.NaN
    return normalizedForce(touch.force, touch.maximumPossibleForce)
}
