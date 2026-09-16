package meshboard.crdt

import meshboard.BoardElement

/** Phase 2 document API, used by the opt-in desktop preview; mobile bindings pending. */
interface CrdtBoard {
    fun put(element: BoardElement)
    fun remove(id: String)
    fun removeAll(ids: List<String>) { ids.forEach(::remove) }
    fun elements(): List<BoardElement>
    fun apply(update: ByteArray)
    fun stateVector(): ByteArray
    fun update(targetStateVector: ByteArray = byteArrayOf(0)): ByteArray
    fun close()
}
