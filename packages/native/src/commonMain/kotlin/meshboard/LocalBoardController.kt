package meshboard

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Local-only checkpoint controller. Call on the UI thread; stores nothing on disk. */
class LocalBoardController(private val createId: () -> String) : DrawingController {
    private val document = BoardDocument()
    private val undo = ArrayDeque<Pair<List<BoardElement>, List<BoardElement>>>()
    private val redo = ArrayDeque<Pair<List<BoardElement>, List<BoardElement>>>()
    private val mutable = MutableStateFlow(BoardState())
    override val state = mutable.asStateFlow()
    override fun newId() = createId()
    override fun put(element: BoardElement) = update {
        Wire.element(Wire.elementJson(element))
        document.apply(BoardMessage("put", element = element))
    }
    override fun remove(ids: List<String>) = update { document.apply(BoardMessage("remove", ids = ids)) }
    override fun move(ids: List<String>, delta: Point) = update {
        document.apply(BoardMessage("snapshot", elements = translated(document.elements, ids, delta)))
    }
    override fun preview(element: BoardElement?) { /* Drafts live in the canvas until committed. */ }
    fun discard() { undo.clear(); redo.clear(); document.reset(); mutable.value = BoardState() }
    override fun undo() { restore(undo, redo, true) }
    override fun redo() { restore(redo, undo, false) }
    private fun restore(source: ArrayDeque<Pair<List<BoardElement>, List<BoardElement>>>, target: ArrayDeque<Pair<List<BoardElement>, List<BoardElement>>>, backwards: Boolean) {
        val action = source.lastOrNull() ?: return
        val elements = if (backwards) action.first else action.second
        document.reset(); document.apply(BoardMessage("snapshot", elements = elements))
        source.removeLast(); target.addLast(action); report()
    }
    private fun report() { mutable.value = BoardState(elements = document.elements, canUndo = undo.isNotEmpty(), canRedo = redo.isNotEmpty()) }
    private fun update(action: () -> Unit) {
        try {
            val before = document.elements
            action()
            val after = document.elements
            if (before != after) {
                redo.clear(); undo.addLast(before to after)
                fun bytes() = undo.sumOf { (a,b) -> listOf(a,b).sumOf { Wire.encode(BoardMessage("snapshot", elements = it)).encodeToByteArray().size.toLong() } }
                while (undo.size > 100 || bytes() > 8 * 1024 * 1024) undo.removeFirst()
            }
            report()
        }
        catch (_: IllegalArgumentException) { mutable.value = mutable.value.copy(error = "This drawing reached the prototype limits. Clear the board to start again.") }
    }
}
