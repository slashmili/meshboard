package meshboard.crdt

import kotlin.io.encoding.Base64
import kotlin.test.*
import meshboard.*

class AppleCrdtControllerTest {
    private class Network(private val fragmented: Boolean = true) : AppleNetwork {
        val sent = mutableListOf<String>()
        val rejected = mutableListOf<String>()
        var starts = 0
        override fun listen(events: AppleNetworkEvents?) {}
        override fun normalizeOrigin(value: String): String? = when {
            value.startsWith("http://127.0.0.1:5174") -> "http://127.0.0.1:5174"
            value.startsWith("https://example.invalid") -> "https://example.invalid"
            else -> null
        }
        override fun previewAllowed(origin: String) = origin == "http://127.0.0.1:5174"
        override fun peerId() = "local-peer"
        override fun fragments(peer: String) = fragmented
        override fun sendBinary(peer: String, base64: String): Boolean { sent.add(base64); return true }
        override fun start(origin: String, room: String) { starts++ }
        override fun stop() {}
        override fun send(peer: String, frame: String): Boolean = error("CRDT must send binary data")
        override fun reject(peer: String) { rejected.add(peer) }
        override fun qrPng(value: String) = ""
    }
    private val stroke = BoardElement("stroke", "pen", "#123456", 3.0, listOf(Point(1.0, 2.0)))
    private fun controller(network: Network) = AppleBoardController(network) { AppleCrdtBoard() }
    private fun deliver(network: Network, receiver: AppleBoardController) {
        val frames = network.sent.toList(); network.sent.clear()
        frames.forEach { receiver.receivedBinary("peer", it) }
    }

    @Test fun fragmentedLargeSnapshotAndDeleteOnlyUpdatesConverge() {
        val na = Network(); val nb = Network()
        val a = controller(na); val b = controller(nb)
        try {
            val large = stroke.copy(points = (0 until 12000).map { Point(it / 8.0, -it / 4.0) })
            a.put(large)
            a.opened("peer"); b.opened("peer")
            repeat(4) { deliver(na, b); deliver(nb, a) }
            assertEquals(listOf(large), b.state.value.elements)
            assertNull(a.state.value.error); assertNull(b.state.value.error)
            a.remove(listOf("stroke")); deliver(na, b)
            assertTrue(b.state.value.elements.isEmpty())
            assertTrue(na.rejected.isEmpty()); assertTrue(nb.rejected.isEmpty())
        } finally { a.close(); b.close() }
    }

    @Test fun unfragmentedUpstreamFramesAndAwarenessWork() {
        val net = Network(fragmented = false); val board = controller(net)
        val remote = AppleCrdtBoard()
        try {
            board.opened("peer")
            remote.put(stroke)
            board.receivedBinary("peer", Base64.encode(YWire.sync(2, remote.update())))
            assertEquals(listOf(stroke), board.state.value.elements)
            val awareness = YAwareness()
            board.receivedBinary("peer", Base64.encode(awareness.local("peer", stroke)))
            assertEquals(listOf(stroke), board.state.value.previews)
            board.closed("peer")
            assertTrue(board.state.value.previews.isEmpty())
            assertNull(board.state.value.error)
        } finally { board.close(); remote.close() }
    }

    @Test fun invalidBinaryRejectsOnlyThatPeerAndKeepsValidatedState() {
        val net = Network(); val board = controller(net)
        try {
            board.put(stroke); board.opened("peer"); board.opened("healthy")
            board.receivedBinary("peer", Base64.encode(YWire.sync(2, byteArrayOf(-1))))
            assertEquals(listOf("peer"), net.rejected)
            assertEquals(1, board.state.value.connected)
            assertEquals(listOf(stroke), board.state.value.elements)
            assertTrue(board.state.value.error!!.contains("invalid or oversized"))
        } finally { board.close() }
    }

    @Test fun protocolAndOriginGuardsDoNotDiscardDrawing() {
        val net = Network(); val board = controller(net)
        try {
            board.put(stroke)
            board.join("http://127.0.0.1:5174/#room=12345678-1234-4234-8234-123456789abc")
            assertTrue(board.state.value.error!!.contains("another protocol"))
            board.share("https://example.invalid")
            assertTrue(board.state.value.error!!.contains("local-only"))
            assertEquals(listOf(stroke), board.state.value.elements)
            assertEquals(0, net.starts)
        } finally { board.close() }
    }
}
