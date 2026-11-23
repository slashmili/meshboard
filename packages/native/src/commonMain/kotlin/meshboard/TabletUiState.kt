package meshboard

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Size

/** In-memory viewport state. The Android ViewModel retains it across activity recreation.
 * It is never serialized; process death still discards the session normally.
 */
class TabletUiState {
    internal val view = mutableStateOf(View())
    internal val selection = mutableStateOf(Selection())
    internal val canvasSize = mutableStateOf(Size.Zero)
}
