package meshboard.crdt

import meshboard.BoardElement

/**
 * Reuses the decoded elements and history of an unchanged document. Presence
 * and connection reports must not re-decode a large board on every message.
 * Not thread-safe: use from the controller's single thread only.
 */
class CachedCrdtBoard(private val board: CrdtBoard) : CrdtBoard by board {
    private var elements: List<BoardElement>? = null
    private var history: Pair<Boolean, Boolean>? = null

    // Invalidate even when the change throws: a failed call may have applied part of it.
    private inline fun changing(change: () -> Unit) {
        try { change() } finally { elements = null; history = null }
    }

    override fun put(element: BoardElement) = changing { board.put(element) }
    override fun remove(id: String) = changing { board.remove(id) }
    override fun removeAll(ids: List<String>) = changing { board.removeAll(ids) }
    override fun move(ids: List<String>, delta: meshboard.Point) = changing { board.move(ids, delta) }
    override fun undo() = changing { board.undo() }
    override fun redo() = changing { board.redo() }
    override fun apply(update: ByteArray) = changing { board.apply(update) }
    override fun close() = changing { board.close() }
    override fun elements(): List<BoardElement> = elements ?: board.elements().also { elements = it }
    override fun history(): Pair<Boolean, Boolean> = history ?: board.history().also { history = it }
}
