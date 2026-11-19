package meshboard.crdt

import meshboard.BoardElement

/** Shared CRDT document API for native platform bindings. */
interface CrdtBoard {
    fun put(element: BoardElement)
    fun remove(id: String)
    fun removeAll(ids: List<String>) { ids.forEach(::remove) }
    fun move(ids: List<String>, delta: meshboard.Point)
    fun undo()
    fun redo()
    fun history(): Pair<Boolean, Boolean>
    fun elements(): List<BoardElement>
    fun apply(update: ByteArray)
    fun stateVector(): ByteArray
    fun update(targetStateVector: ByteArray = byteArrayOf(0)): ByteArray
    fun close()
}
