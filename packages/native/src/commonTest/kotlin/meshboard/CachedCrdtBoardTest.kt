package meshboard

import kotlin.test.*
import meshboard.crdt.CachedCrdtBoard
import meshboard.crdt.CrdtBoard

class CachedCrdtBoardTest {
    private class Counting : CrdtBoard {
        val stored = mutableMapOf<String, BoardElement>()
        var decodes = 0; var histories = 0; var closed = false
        var failNext = false
        private fun change(block: () -> Unit) { block(); if (failNext) { failNext = false; error("partial failure") } }
        override fun put(element: BoardElement) = change { stored[element.id] = element }
        override fun remove(id: String) = change { stored.remove(id) }
        override fun move(ids: List<String>, delta: Point) = change {
            ids.forEach { id -> stored[id]?.let { e -> stored[id] = e.copy(points = e.points.map { Point(it.x + delta.x, it.y + delta.y) }) } }
        }
        override fun undo() = change { stored.remove(stored.keys.last()) }
        override fun redo() = change {}
        override fun history(): Pair<Boolean, Boolean> { histories++; return stored.isNotEmpty() to false }
        override fun elements(): List<BoardElement> { decodes++; return stored.values.sortedBy { it.id } }
        override fun apply(update: ByteArray) = change { stored["remote-${update.size}"] = stroke("remote-${update.size}") }
        override fun stateVector() = byteArrayOf(stored.size.toByte())
        override fun update(targetStateVector: ByteArray) = byteArrayOf()
        override fun close() { closed = true }
    }

    @Test fun unchangedDocumentIsDecodedOnce() {
        val inner = Counting(); val board = CachedCrdtBoard(inner)
        board.put(stroke("a"))
        val first = board.elements()
        repeat(1_000) { assertSame(first, board.elements()); assertEquals(true to false, board.history()) }
        // Reads that do not change the document keep the decoded board.
        board.stateVector(); board.update(byteArrayOf(0))
        assertSame(first, board.elements())
        assertEquals(1, inner.decodes); assertEquals(1, inner.histories)
    }

    @Test fun everyMutationPublishesFreshElementsAndHistory() {
        val inner = Counting(); val board = CachedCrdtBoard(inner)
        val changes = listOf<Pair<() -> Unit, List<String>>>(
            { board.put(stroke("a")) } to listOf("a"),
            { board.put(stroke("b")) } to listOf("a", "b"),
            { board.apply(ByteArray(3)) } to listOf("a", "b", "remote-3"),
            { board.remove("a") } to listOf("b", "remote-3"),
            { board.removeAll(listOf("b")) } to listOf("remote-3"),
            { board.undo() } to emptyList(),
            { board.redo() } to emptyList(),
        )
        assertEquals(false to false, board.history())
        for ((change, expected) in changes) {
            change()
            assertEquals(expected, board.elements().map { it.id })
            assertEquals(expected.isNotEmpty() to false, board.history())
        }
        board.put(stroke("moved")); board.elements()
        board.move(listOf("moved"), Point(5.0, -5.0))
        assertEquals(listOf(Point(6.0, -3.0)), board.elements().single().points)
        board.close(); assertTrue(inner.closed)
    }

    @Test fun failedMutationStillInvalidates() {
        val inner = Counting(); val board = CachedCrdtBoard(inner)
        assertTrue(board.elements().isEmpty())
        inner.failNext = true
        assertFailsWith<IllegalStateException> { board.put(stroke("partial")) }
        assertEquals(listOf("partial"), board.elements().map { it.id })
    }

    private companion object {
        fun stroke(id: String) = BoardElement(id, "pen", "#293b36", 3.0, listOf(Point(1.0, 2.0)))
    }
}
