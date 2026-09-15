package meshboard

import kotlin.test.*

class AppleControllerTest {
    private class Network : AppleNetwork {
        var events: AppleNetworkEvents? = null
        val sent = mutableListOf<Pair<String, String>>()
        val rejected = mutableListOf<String>()
        var starts = 0
        override fun listen(events: AppleNetworkEvents?) { this.events = events }
        override fun normalizeOrigin(value: String) = if (value.startsWith("https://board.test")) "https://board.test" else null
        override fun start(origin: String, room: String) { starts++ }
        override fun stop() {}
        override fun send(peer: String, frame: String): Boolean { sent.add(peer to frame); return true }
        override fun reject(peer: String) { rejected.add(peer) }
        override fun qrPng(value: String) = ""
    }
    private val stroke = BoardElement("stroke", "pen", "#293b36", 3.0, listOf(Point(1.0, 2.0)))
    @Test fun invalidInviteDoesNotDiscardExistingDrawing() {
        val net = Network(); val controller = AppleBoardController(net)
        try {
            controller.put(stroke)
            controller.join("https://board.test/#room=invalid")
            assertEquals(listOf(stroke), controller.state.value.elements)
            assertEquals(0, net.starts)
            assertNotNull(controller.state.value.error)
        } finally { controller.close() }
    }
    @Test fun lateJoinSnapshotRetainsDeletions() {
        val net = Network(); val controller = AppleBoardController(net)
        try {
            controller.put(stroke); controller.remove(listOf(stroke.id))
            controller.share("https://board.test")
            controller.opened("peer")
            val receiver = FrameReceiver { 0L }
            val snapshot = net.sent.mapNotNull { receiver.accept(it.second) }.single()
            assertEquals("snapshot", snapshot.type)
            assertTrue(snapshot.elements.isEmpty())
            assertEquals(listOf("stroke"), snapshot.removed)
        } finally { controller.close() }
    }
    @Test fun malformedPeerFrameClosesOnlyThatPeer() {
        val net = Network(); val controller = AppleBoardController(net)
        try {
            controller.opened("bad"); controller.opened("good")
            controller.received("bad", "not json")
            assertEquals(listOf("bad"), net.rejected)
            assertEquals(1, controller.state.value.connected)
            val message = BoardMessage("put", element = stroke)
            Wire.frames(message, "123e4567-e89b-42d3-a456-426614174000").forEach { controller.received("good", it) }
            assertEquals(listOf(stroke), controller.state.value.elements)
        } finally { controller.close() }
    }
}
