package meshboard

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Local-only checkpoint controller. Call on the UI thread; stores nothing on disk. */
class LocalBoardController(private val createId: () -> String) : DrawingController {
    private val document = BoardDocument()
    private val mutable = MutableStateFlow(BoardState())
    override val state = mutable.asStateFlow()
    override fun newId() = createId()
    override fun put(element: BoardElement) = update {
        Wire.element(Wire.elementJson(element))
        document.apply(BoardMessage("put", element = element))
    }
    override fun remove(ids: List<String>) = update { document.apply(BoardMessage("remove", ids = ids)) }
    override fun preview(element: BoardElement?) { /* Drafts live in the canvas until committed. */ }
    fun discard() { document.reset(); mutable.value = BoardState() }
    private fun update(action: () -> Unit) {
        try { action(); mutable.value = BoardState(elements = document.elements) }
        catch (_: IllegalArgumentException) { mutable.value = mutable.value.copy(error = "This drawing reached the prototype limits. Clear the board to start again.") }
    }
}
