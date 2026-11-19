package meshboard

import kotlin.test.*
import kotlin.io.encoding.Base64
import meshboard.crdt.AppleCrdtBoard

class AppleControllerTest {
    private class Network : AppleNetwork {
        var events: AppleNetworkEvents? = null
        val sent = mutableListOf<Pair<String, String>>()
        val rejected = mutableListOf<String>()
        var starts = 0
        override fun listen(events: AppleNetworkEvents?) { this.events = events }
        override fun normalizeOrigin(value: String) = if (value.startsWith("https://board.test")) "https://board.test" else null
        override fun start(origin: String, room: String, key: String) { starts++ }
        override fun newKey() = "A".repeat(43)
        override fun stop() {}
        override fun sendBinary(peer: String, base64: String): Boolean { sent.add(peer to base64); return true }
        override fun reject(peer: String) { rejected.add(peer) }
        override fun qrPng(value: String) = ""
    }
    private inline fun <T> AppleCrdtBoard.useBoard(block: (AppleCrdtBoard) -> T): T = try { block(this) } finally { close() }
    private val stroke = BoardElement("stroke", "pen", "#293b36", 3.0, listOf(Point(1.0, 2.0)))
    @Test fun invalidInviteDoesNotDiscardExistingDrawing() {
        val net = Network(); val controller = AppleBoardController(net)
        try {
            controller.put(stroke)
            controller.join("https://board.test/#crdt=invalid")
            assertEquals(listOf(stroke), controller.state.value.elements)
            assertEquals(0, net.starts)
            assertNotNull(controller.state.value.error)
            controller.join("https://board.test/#room=12345678-1234-4234-8234-123456789abc")
            assertTrue(controller.state.value.error!!.contains("another protocol"))
            assertEquals(listOf(stroke), controller.state.value.elements)
            assertEquals(0, net.starts)
        } finally { controller.close() }
    }
    @Test fun lateJoinSnapshotRetainsDeletions() {
        val net = Network(); val controller = AppleBoardController(net)
        try {
            controller.put(stroke)
            controller.share("https://board.test")
            controller.opened("peer")
            AppleCrdtBoard().useBoard { remote ->
                controller.receivedBinary("peer", Base64.encode(YWire.sync(0, remote.stateVector())))
                val initial = YWire.Reader(Base64.decode(net.sent.last().second))
                assertEquals(0L, initial.uint()); assertEquals(1L, initial.uint())
                remote.apply(initial.bytes())
                assertEquals(listOf(stroke), remote.elements())
                controller.remove(listOf(stroke.id))
                controller.receivedBinary("peer", Base64.encode(YWire.sync(0, remote.stateVector())))
                val reader = YWire.Reader(Base64.decode(net.sent.last().second))
                assertEquals(0L, reader.uint()); assertEquals(1L, reader.uint())
                remote.apply(reader.bytes())
                assertTrue(remote.elements().isEmpty())
            }
        } finally { controller.close() }
    }
    @Test fun malformedPeerFrameClosesOnlyThatPeer() {
        val net = Network(); val controller = AppleBoardController(net)
        try {
            controller.opened("bad"); controller.opened("good")
            controller.receivedBinary("bad", "not base64!")
            assertEquals(listOf("bad"), net.rejected)
            assertEquals(1, controller.state.value.connected)
            AppleCrdtBoard().useBoard { remote ->
                remote.put(stroke)
                controller.receivedBinary("good", Base64.encode(YWire.sync(2, remote.update())))
            }
            assertEquals(listOf(stroke), controller.state.value.elements)
        } finally { controller.close() }
    }
}
