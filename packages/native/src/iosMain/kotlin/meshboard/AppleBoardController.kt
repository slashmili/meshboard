package meshboard

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.io.encoding.Base64
import meshboard.crdt.CrdtBoard
import platform.Foundation.NSDate
import platform.Foundation.NSUUID
import platform.Foundation.timeIntervalSince1970

/** Implemented by the Swift host. All calls and callbacks run on the main thread. */
interface AppleNetwork {
    fun listen(events: AppleNetworkEvents?)
    fun normalizeOrigin(value: String): String?
    fun start(origin: String, room: String)
    fun stop()
    fun send(peer: String, frame: String): Boolean
    fun reject(peer: String)
    fun qrPng(value: String): String
    fun previewAllowed(origin: String): Boolean = false
    fun peerId(): String = ""
    fun fragments(peer: String): Boolean = false
    fun sendBinary(peer: String, base64: String): Boolean = false
}
interface AppleNetworkEvents {
    fun signaling(active: Boolean, error: String?)
    fun opened(peer: String)
    fun closed(peer: String)
    fun route(peer: String, relayed: Boolean)
    fun received(peer: String, frame: String)
    fun receivedBinary(peer: String, base64: String)
}

/** Shared wire codec/document, with Apple WebRTC and URLSession owned by Swift. */
class AppleBoardController(private val network: AppleNetwork, private val crdtFactory: (() -> CrdtBoard)? = appleCrdtFactory()) : BoardController, AppleNetworkEvents {
    private val document = BoardDocument()
    private var crdt = crdtFactory?.invoke()
    private val elements get() = crdt?.elements() ?: document.elements
    private fun resetDocument() { document.reset(); crdt?.close(); crdt = crdtFactory?.invoke() }
    private val mutable = MutableStateFlow(BoardState())
    override val state = mutable.asStateFlow()
    private val receivers = mutableMapOf<String, FrameReceiver>()
    private val yReceivers = mutableMapOf<String, YWire.Receiver>()
    private var awareness = YAwareness()
    private var awarenessJob: Job? = null
    private val previews = mutableMapOf<String, BoardElement>()
    private val relayed = mutableSetOf<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var previewJob: Job? = null
    private var pending: BoardElement? = null
    private var origin = ""
    private var room = ""
    init { network.listen(this) }
    override fun newId() = NSUUID().UUIDString.lowercase()
    private fun report(error: String? = mutable.value.error) {
        mutable.value = mutable.value.copy(elements = elements, previews = previews.values.toList(),
            connected = receivers.size, relayed = relayed.size, error = error)
    }
    private fun attempt(block: () -> Unit) {
        try { block() } catch (e: Exception) { report(e.message ?: "The connection could not complete.") }
    }
    override fun put(element: BoardElement) = attempt {
        Wire.element(Wire.elementJson(element)); local(BoardMessage("put", element = element))
    }
    override fun remove(ids: List<String>) = attempt { if (ids.isNotEmpty()) local(BoardMessage("remove", ids = ids)) }
    private fun local(message: BoardMessage) {
        val board = crdt
        if (board == null) { document.apply(message); report(); broadcast(message); return }
        val before = board.stateVector()
        when (message.type) {
            "put" -> board.put(requireNotNull(message.element))
            "remove" -> board.removeAll(message.ids)
            else -> error("Unsupported local CRDT operation")
        }
        report(); broadcastY(YWire.sync(2, board.update(before)))
    }
    private fun broadcastPreview(element: BoardElement?) {
        if (crdt == null) broadcast(BoardMessage("preview", element = element))
        else broadcastY(awareness.local(network.peerId(), element))
    }
    override fun preview(element: BoardElement?) {
        pending = element
        if (element == null) { previewJob?.cancel(); previewJob = null; broadcastPreview(null) }
        else if (previewJob == null) previewJob = scope.launch {
            delay(50); previewJob = null; broadcastPreview(pending)
        }
    }
    override fun share(origin: String) = attempt {
        if (room.isEmpty()) begin(requireNotNull(network.normalizeOrigin(origin)) { "Enter an HTTPS app address (HTTP is allowed only for localhost)." }, newId(), true)
    }
    override fun join(invite: String) = attempt {
        require(invite.length <= 2048) { "The invite is too long." }
        val base = requireNotNull(network.normalizeOrigin(invite)) { "Enter a valid HTTPS board invite." }
        val fragment = invite.trim().substringAfter('#', "")
        val key = if (crdt != null) "crdt" else "room"
        if (fragment.startsWith("room=") || fragment.startsWith("crdt=")) {
            require(fragment.startsWith("$key=")) { "This invite belongs to another protocol. Use matching preview builds." }
        }
        require(fragment.startsWith("$key=") && '&' !in fragment) { "Paste the full invite, including #$key=…" }
        val id = fragment.removePrefix("$key=")
        require(uuidPattern.matches(id)) { "This invite has an invalid room ID." }
        begin(base, id, false)
    }
    override fun retry() { if (room.isNotEmpty()) begin(origin, room, true) }
    private fun begin(base: String, id: String, keep: Boolean) {
        require(crdt == null || network.previewAllowed(base)) { "CRDT preview is local-only and requires the iPad simulator." }
        disconnect(); if (!keep) resetDocument()
        origin = base; room = id
        mutable.value = BoardState(elements = elements, invite = if (crdt != null) "$base/?crdt=1#crdt=$id" else "$base/#room=$id")
        network.start(base, id)
        if (crdt != null) {
            awareness = YAwareness(); awareness.local(network.peerId(), null)
            awarenessJob = scope.launch {
                while (isActive) {
                    delay(10_000)
                    awareness.expire()?.let(::broadcastY)
                    previews.clear(); previews.putAll(awareness.previews()); report()
                    broadcastY(awareness.local(network.peerId(), pending))
                }
            }
        }
    }
    override fun leave() { disconnect(); room = ""; resetDocument(); mutable.value = BoardState() }
    private fun disconnect() {
        previewJob?.cancel(); previewJob = null; pending = null
        awarenessJob?.cancel(); awarenessJob = null
        network.stop(); receivers.clear(); yReceivers.clear(); previews.clear(); relayed.clear()
    }
    fun close() { disconnect(); crdt?.close(); network.listen(null); scope.cancel() }
    override fun signaling(active: Boolean, error: String?) {
        mutable.value = mutable.value.copy(signaling = active); report(error)
    }
    override fun opened(peer: String) {
        if (peer in receivers) return
        receivers[peer] = FrameReceiver { (NSDate().timeIntervalSince1970 * 1000).toLong() }
        val board = crdt
        if (board == null) send(peer, document.snapshot()) else {
            yReceivers[peer] = YWire.Receiver()
            sendY(peer, YWire.sync(0, board.stateVector()))
            sendY(peer, awareness.all()); sendY(peer, byteArrayOf(3))
        }
        report(null)
    }
    override fun closed(peer: String) {
        receivers.remove(peer); yReceivers.remove(peer); previews.remove(peer); relayed.remove(peer)
        if (crdt != null) {
            awareness.expire(peer)?.let(::broadcastY)
            previews.clear(); previews.putAll(awareness.previews())
        }
        report()
    }
    override fun route(peer: String, relayed: Boolean) {
        if (peer !in receivers) return
        if (relayed) this.relayed.add(peer) else this.relayed.remove(peer)
        report()
    }
    override fun received(peer: String, frame: String) {
        try {
            require(crdt == null)
            val message = receivers[peer]?.accept(frame) ?: return
            if (message.type == "preview") {
                val element = message.element
                if (element == null) previews.remove(peer) else previews[peer] = element
            } else { previews.remove(peer); document.apply(message) }
            report()
        } catch (_: Exception) {
            network.reject(peer); closed(peer)
            report("A peer sent invalid or oversized drawing data. Its connection was closed.")
        }
    }
    override fun receivedBinary(peer: String, base64: String) {
        try {
            val board = requireNotNull(crdt)
            val receiver = yReceivers[peer] ?: return
            require(base64.length <= (YWire.MAX_MESSAGE + 2) / 3 * 4)
            val bytes = Base64.decode(base64)
            val message = if (network.fragments(peer)) receiver.accept(bytes) ?: return else bytes
            val reader = YWire.Reader(message)
            when (reader.uint()) {
                0L -> {
                    val step = reader.uint(); val update = reader.bytes(); reader.end()
                    when (step) {
                        0L -> sendY(peer, YWire.sync(1, board.update(update)))
                        1L, 2L -> { board.apply(update); report() }
                        else -> error("Invalid Yjs sync step")
                    }
                }
                1L -> {
                    val update = reader.bytes(); reader.end()
                    awareness.receive(update)?.let(::broadcastY)
                    previews.clear(); previews.putAll(awareness.previews()); report()
                }
                3L -> { reader.end(); sendY(peer, awareness.all()) }
                else -> error("Unsupported y-webrtc message")
            }
        } catch (_: Exception) {
            network.reject(peer); closed(peer)
            report("A peer sent invalid or oversized drawing data. Its connection was closed.")
        }
    }
    private fun broadcastY(bytes: ByteArray) { yReceivers.keys.toList().forEach { sendY(it, bytes) } }
    private fun sendY(peer: String, bytes: ByteArray) {
        try {
            require(bytes.size <= YWire.MAX_MESSAGE)
            val frames = if (network.fragments(peer)) YWire.frames(bytes) else listOf(bytes)
            for (frame in frames) check(network.sendBinary(peer, Base64.encode(frame)))
        } catch (_: Exception) { network.reject(peer); closed(peer); report("A peer could not keep up. Retry the connection.") }
    }
    private fun broadcast(message: BoardMessage) { receivers.keys.toList().forEach { send(it, message) } }
    private fun send(peer: String, message: BoardMessage) {
        try {
            for (frame in Wire.frames(message, newId())) check(network.send(peer, frame))
        } catch (_: Exception) { network.reject(peer); closed(peer); report("A peer could not keep up. Retry the connection.") }
    }
}
