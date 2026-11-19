package meshboard

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import meshboard.crdt.JvmCrdtBoard

/** UI test adapter using the same CRDT document as desktop sessions. */
class LocalBoardController(private val createId: () -> String) : DrawingController, AutoCloseable {
    private var document = JvmCrdtBoard()
    private val mutable = MutableStateFlow(BoardState())
    override val state = mutable.asStateFlow()
    override fun close() = document.close()
    override fun newId() = createId()
    override fun put(element: BoardElement) = update { document.put(element) }
    override fun remove(ids: List<String>) = update { document.removeAll(ids) }
    override fun move(ids: List<String>, delta: Point) = update { document.move(ids, delta) }
    override fun preview(element: BoardElement?) { }
    fun discard() { document.close(); document = JvmCrdtBoard(); mutable.value = BoardState() }
    override fun undo() = update { document.undo() }
    override fun redo() = update { document.redo() }
    private fun update(action: () -> Unit) {
        try {
            action()
            val history = document.history()
            mutable.value = BoardState(elements = document.elements(), canUndo = history.first, canRedo = history.second)
        } catch (_: IllegalArgumentException) {
            mutable.value = mutable.value.copy(error = "That change exceeds the session limits or is invalid.")
        }
    }
}
