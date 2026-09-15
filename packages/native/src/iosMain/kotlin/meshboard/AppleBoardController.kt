package meshboard

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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
}
interface AppleNetworkEvents {
    fun signaling(active: Boolean, error: String?)
    fun opened(peer: String)
    fun closed(peer: String)
    fun route(peer: String, relayed: Boolean)
    fun received(peer: String, frame: String)
}

/** Shared wire codec/document, with Apple WebRTC and URLSession owned by Swift. */
class AppleBoardController(private val network: AppleNetwork) : BoardController, AppleNetworkEvents {
    private val document = BoardDocument()
    private val mutable = MutableStateFlow(BoardState())
    override val state = mutable.asStateFlow()
    private val receivers = mutableMapOf<String, FrameReceiver>()
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
        mutable.value = mutable.value.copy(elements = document.elements, previews = previews.values.toList(),
            connected = receivers.size, relayed = relayed.size, error = error)
    }
    private fun attempt(block: () -> Unit) {
        try { block() } catch (e: Exception) { report(e.message ?: "The connection could not complete.") }
    }
    override fun put(element: BoardElement) = attempt {
        Wire.element(Wire.elementJson(element)); local(BoardMessage("put", element = element))
    }
    override fun remove(ids: List<String>) = attempt { if (ids.isNotEmpty()) local(BoardMessage("remove", ids = ids)) }
    private fun local(message: BoardMessage) { document.apply(message); report(); broadcast(message) }
    override fun preview(element: BoardElement?) {
        pending = element
        if (element == null) { previewJob?.cancel(); previewJob = null; broadcast(BoardMessage("preview")) }
        else if (previewJob == null) previewJob = scope.launch {
            delay(50); previewJob = null; broadcast(BoardMessage("preview", element = pending))
        }
    }
    override fun share(origin: String) = attempt {
        if (room.isEmpty()) begin(requireNotNull(network.normalizeOrigin(origin)) { "Enter an HTTPS app address (HTTP is allowed only for localhost)." }, newId(), true)
    }
    override fun join(invite: String) = attempt {
        require(invite.length <= 2048) { "The invite is too long." }
        val base = requireNotNull(network.normalizeOrigin(invite)) { "Enter a valid HTTPS board invite." }
        val fragment = invite.trim().substringAfter('#', "")
        require(fragment.startsWith("room=") && '&' !in fragment) { "Paste the full invite, including #room=…" }
        val id = fragment.removePrefix("room=")
        require(uuidPattern.matches(id)) { "This invite has an invalid room ID." }
        begin(base, id, false)
    }
    override fun retry() { if (room.isNotEmpty()) begin(origin, room, true) }
    private fun begin(base: String, id: String, keep: Boolean) {
        disconnect(); if (!keep) document.reset()
        origin = base; room = id
        mutable.value = BoardState(elements = document.elements, invite = "$base/#room=$id")
        network.start(base, id)
    }
    override fun leave() { disconnect(); room = ""; document.reset(); mutable.value = BoardState() }
    private fun disconnect() {
        previewJob?.cancel(); previewJob = null; pending = null
        network.stop(); receivers.clear(); previews.clear(); relayed.clear()
    }
    fun close() { disconnect(); network.listen(null); scope.cancel() }
    override fun signaling(active: Boolean, error: String?) {
        mutable.value = mutable.value.copy(signaling = active); report(error)
    }
    override fun opened(peer: String) {
        if (peer in receivers) return
        receivers[peer] = FrameReceiver { (NSDate().timeIntervalSince1970 * 1000).toLong() }
        send(peer, document.snapshot()); report(null)
    }
    override fun closed(peer: String) { receivers.remove(peer); previews.remove(peer); relayed.remove(peer); report() }
    override fun route(peer: String, relayed: Boolean) {
        if (peer !in receivers) return
        if (relayed) this.relayed.add(peer) else this.relayed.remove(peer)
        report()
    }
    override fun received(peer: String, frame: String) {
        try {
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
    private fun broadcast(message: BoardMessage) { receivers.keys.toList().forEach { send(it, message) } }
    private fun send(peer: String, message: BoardMessage) {
        try {
            for (frame in Wire.frames(message, newId())) check(network.send(peer, frame))
        } catch (_: Exception) { network.reject(peer); closed(peer); report("A peer could not keep up. Retry the connection.") }
    }
}
