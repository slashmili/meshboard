package meshboard

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import meshboard.crdt.CrdtBoard
import meshboard.crdt.JvmCrdtBoard
import kotlin.test.*

class DesktopReportingTest {
    private class CountingBoard(private val board: CrdtBoard = JvmCrdtBoard()) : CrdtBoard by board {
        val decodes = AtomicInteger()
        val histories = AtomicInteger()
        override fun elements(): List<BoardElement> { decodes.incrementAndGet(); return board.elements() }
        override fun history(): Pair<Boolean, Boolean> { histories.incrementAndGet(); return board.history() }
    }

    @Test fun connectionReportsReuseTheDecodedBoardAndLocalEditsInvalidateIt() = runBlocking {
        // Exercise the real async report path without libwebrtc or an external service.
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/rtc-config") { request -> request.sendResponseHeaders(503, -1); request.close() }
        server.start()
        val board = CountingBoard()
        try {
            DesktopController(crdtFactory = { board }).use { controller ->
                suspend fun awaitState(predicate: (BoardState) -> Boolean) = withTimeout(5_000) { controller.state.first(predicate) }
                val large = BoardElement("large", "pen", "#293b36", 3.0, List(12_000) { Point(it.toDouble(), 0.0) })
                controller.put(large)
                val before = awaitState { it.elements.size == 1 }
                val decodes = board.decodes.get(); val histories = board.histories.get()
                controller.share("http://127.0.0.1:${server.address.port}")
                val failed = awaitState { it.error != null }
                assertEquals(before.elements, failed.elements)
                assertEquals(decodes, board.decodes.get(), "Connection-only reports must not decode all 12,000 points again")
                assertEquals(histories, board.histories.get(), "Connection-only reports must reuse unchanged history")
                controller.put(large.copy(id = "next"))
                awaitState { it.elements.size == 2 }
                assertEquals(decodes + 1, board.decodes.get())
                assertEquals(histories + 1, board.histories.get())
                controller.undo()
                awaitState { it.elements.size == 1 }
                assertEquals(decodes + 2, board.decodes.get())
                assertEquals(histories + 2, board.histories.get())
            }
        } finally { server.stop(0) }
    }
}
