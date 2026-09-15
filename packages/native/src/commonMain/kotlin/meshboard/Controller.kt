package meshboard

import kotlinx.coroutines.flow.StateFlow

data class BoardState(
    val elements: List<BoardElement> = emptyList(), val previews: List<BoardElement> = emptyList(),
    val invite: String = "", val connected: Int = 0, val connecting: Int = 0,
    val signaling: Boolean = false, val relayed: Int = 0, val error: String? = null,
) {
    val connectionLabel get() = when {
        invite.isEmpty() -> "Local only"
        connected > 0 -> "$connected peer${if (connected == 1) "" else "s"} connected"
        connecting > 0 -> "Connecting…"
        signaling -> "Waiting for a peer"
        else -> "Joining…"
    }
}

interface DrawingController {
    val state: StateFlow<BoardState>
    fun newId(): String
    fun put(element: BoardElement)
    fun remove(ids: List<String>)
    fun preview(element: BoardElement?)
}

interface BoardController : DrawingController {
    fun share(origin: String)
    fun join(invite: String)
    fun retry()
    fun leave()
}
