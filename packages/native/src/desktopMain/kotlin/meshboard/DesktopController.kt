package meshboard
import dev.onvoid.webrtc.*
import dev.onvoid.webrtc.media.audio.HeadlessAudioDeviceModule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.time.Duration
import java.util.UUID
import meshboard.crypto.JvmSessionCrypto
import meshboard.crdt.CrdtBoard
import meshboard.crdt.JvmCrdtBoard
import java.util.concurrent.*
/** All state and JNI operations run on one executor, never on libwebrtc callback threads. */
class DesktopController(private val forceRelay: Boolean = false, private val crdtFactory: () -> CrdtBoard = { JvmCrdtBoard() }) : BoardController, AutoCloseable {
    private val executor = ScheduledThreadPoolExecutor(1) { task -> Thread(task, "meshboard-session").apply { isDaemon = true } }
    // The app's HTTP/WebSocket server does not negotiate cleartext HTTP/2 (h2c).
    private val http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(8)).build()
    private val mutable = MutableStateFlow(BoardState())
    override val state = mutable.asStateFlow()
    private var crdt = crdtFactory()
    private val elements get() = crdt.elements()
    private val channelLabel get() = SecureWire.CHANNEL
    private fun resetDocument() { crdt.close(); crdt = crdtFactory() }
    private var awareness = YAwareness()
    private var awarenessTask: ScheduledFuture<*>? = null
    private val previews = mutableMapOf<String, BoardElement>()
    private var audio: HeadlessAudioDeviceModule? = null
    private var factory: PeerConnectionFactory? = null
    private var config = RTCConfiguration()
    private var socket: WebSocket? = null
    private var sends = CompletableFuture.completedFuture<Void>(null)
    private var generation = 0
    private var self = ""
    private var room = ""
    private var sessionKey = ""
    private var origin = ""
    @Volatile private var disposed = false
    private val peers = mutableMapOf<String, Peer>()
    private val desired = mutableSetOf<String>()
    private val attempts = mutableMapOf<String, Int>()
    private var reconnect: ScheduledFuture<*>? = null
    private var previewTask: ScheduledFuture<*>? = null
    private var pendingPreview: BoardElement? = null
    private var reconnectDelay = 1L
    private class Peer(val pc: RTCPeerConnection) {
        var channel: RTCDataChannel? = null
        var open = false
        var secure: SecureChannel? = null
        val incomingBytes = java.util.concurrent.atomic.AtomicInteger()
        val incomingFrames = java.util.concurrent.atomic.AtomicInteger()
        val invalidQueued = java.util.concurrent.atomic.AtomicBoolean()
        var remoteSet = false
        var relayed = false
        var timeout: ScheduledFuture<*>? = null
        val candidates = mutableListOf<RTCIceCandidate>()
        val yReceiver = YWire.Receiver()
        var token: Double? = null
        val queue = ArrayDeque<ByteArray>()
        var queueBytes = 0
    }
    private fun post(action: () -> Unit) {
        if (disposed) return
        executor.execute {
            if (!disposed) try { action() } catch (e: Exception) { report(error = e.message ?: "The connection could not complete.") }
        }
    }
    private fun later(delay: Long, action: () -> Unit) = executor.schedule({ if (!disposed) post(action) }, delay, TimeUnit.MILLISECONDS)
    private fun report(error: String? = mutable.value.error) {
        val open = peers.values.filter { it.open }
        mutable.value = mutable.value.copy(elements = elements, previews = previews.values.toList(),
        connected = open.size, connecting = peers.size - open.size, relayed = open.count { it.relayed }, error = error)
    }
    override fun newId() = "${System.currentTimeMillis().toString(36)}-${UUID.randomUUID()}"
    override fun put(element: BoardElement) = post {
        Wire.element(Wire.elementJson(element))
        local(BoardMessage("put", element = element))
    }
    override fun remove(ids: List<String>) = post { if (ids.isNotEmpty()) local(BoardMessage("remove", ids = ids)) }
    private fun local(message: BoardMessage) {
        val board = crdt
        val before = board.stateVector()
        when (message.type) {
            "put" -> board.put(requireNotNull(message.element))
            "remove" -> board.removeAll(message.ids)
            else -> error("Invalid local CRDT operation")
        }
        report()
        broadcastY(YWire.sync(2, board.update(before)))
    }
    override fun preview(element: BoardElement?) = post {
        pendingPreview = element
        if (element == null) {
            previewTask?.cancel(false); previewTask = null
            broadcastY(awareness.local(self, null))
        } else if (previewTask == null) previewTask = later(50) {
            previewTask = null
            broadcastY(awareness.local(self, pendingPreview))
        }
    }
    override fun share(origin: String) = post {
        if (room.isNotEmpty()) return@post
        val base = validateOrigin(origin)
        sessionKey = SecureWire.key(JvmSessionCrypto.random32())
        begin(base, UUID.randomUUID().toString(), keepDrawing = true)
    }
    override fun join(invite: String) = post {
        val parsed = parseInvite(invite)
        sessionKey = parsed.third
        begin(parsed.first, parsed.second, keepDrawing = false)
    }
    override fun retry() = post { if (room.isNotEmpty()) begin(origin, room, keepDrawing = true) }
    override fun leave() = post {
        disconnect(); room = ""; sessionKey = ""; resetDocument(); mutable.value = BoardState()
    }
    private fun begin(base: String, id: String, keepDrawing: Boolean) {
        disconnect()
        if (!keepDrawing) resetDocument()
        origin = base; room = id
        self = UUID.randomUUID().toString(); awareness = YAwareness(); awareness.local(self, null)
        fun tick() {
            awareness.expire()?.let(::broadcastY)
            previews.clear(); previews.putAll(awareness.previews()); report()
            broadcastY(awareness.local(self, pendingPreview))
            awarenessTask = later(10_000) { tick() }
        }
        awarenessTask = later(10_000) { tick() }
        mutable.value = BoardState(elements = elements, invite = "$origin/#crdt=$room&key=$sessionKey")
        val current = generation
        val request = HttpRequest.newBuilder(URI("$origin/api/rtc-config")).timeout(Duration.ofSeconds(8)).GET().build()
        http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).whenComplete { response, error -> post {
                if (current != generation) return@post
                if (error != null || response.statusCode() != 200) { report("Could not load connection settings. Check the app address and retry."); return@post }
                require(response.body().length <= 16_384)
                config = readConfig(response.body())
                if (factory == null) {
                    try {
                        // Data only: do not open audio hardware or access microphone/camera.
                        audio = HeadlessAudioDeviceModule()
                        factory = PeerConnectionFactory(audio)
                    } catch (e: LinkageError) { report("Native WebRTC could not load. Check the platform requirements in the native README."); return@post }
                }
                connectSocket(current)
        } }
    }
    private fun readConfig(raw: String): RTCConfiguration {
        val o = Json.parseToJsonElement(raw).jsonObject
        o.exact("iceServers", "iceTransportPolicy", "localDevelopment")
        val policy = o.text("iceTransportPolicy"); require(policy == "all" || policy == "relay")
        val servers = o.getValue("iceServers").jsonArray; require(servers.size <= 8)
        return RTCConfiguration().apply {
            iceTransportPolicy = if (forceRelay || policy == "relay") RTCIceTransportPolicy.RELAY else RTCIceTransportPolicy.ALL
            servers.forEach { item ->
                val server = item.jsonObject; require(server.keys.all { it in setOf("urls", "username", "credential") })
                val urls = server.getValue("urls").jsonArray.map { it.jsonPrimitive.content }
                require(urls.size in 1..8 && urls.all { Regex("^(stun|stuns|turn|turns):[^\\s]+$").matches(it) })
                iceServers.add(RTCIceServer().apply { this.urls.addAll(urls); username = server["username"]?.jsonPrimitive?.content ?: ""; password = server["credential"]?.jsonPrimitive?.content ?: "" })
            }
        }
    }
    private fun connectSocket(current: Int) {
        val url = URI(origin.replaceFirst("https:", "wss:").replaceFirst("http:", "ws:") + "/signal-y-webrtc")
        http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(8)).buildAsync(url, object : WebSocket.Listener {
            val text = StringBuilder()
            override fun onOpen(ws: WebSocket) {
                ws.request(1)
                post {
                    if (current != generation) { ws.abort(); return@post }
                    socket = ws; sends = CompletableFuture.completedFuture(null)
                    run {
                        sendJson(buildJsonObject { put("type", "subscribe"); putJsonArray("topics") { add("meshboard-crdt:$room") } })
                        announceY(); reconnectDelay = 1
                        mutable.value = mutable.value.copy(signaling = true); report(null)
                    }
                }
            }
            override fun onText(ws: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                text.append(data)
                if (text.length > 32_768) { ws.abort(); return null }
                if (last) {
                    val message = text.toString(); text.clear()
                    post { if (current == generation && socket === ws) receiveSignal(message) }
                }
                ws.request(1); return null
            }
            override fun onBinary(ws: WebSocket, data: ByteBuffer, last: Boolean): CompletionStage<*>? { ws.abort(); return null }
            override fun onClose(ws: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
                post { if (current == generation && socket === ws) socketLost(current, statusCode == 1008) }; return null
            }
            override fun onError(ws: WebSocket, error: Throwable) { post { if (current == generation) socketLost(current, false) } }
        }).whenComplete { _, error -> if (error != null) post { if (current == generation) socketLost(current, false) } }
    }
    private fun socketLost(current: Int, rejected: Boolean) {
        socket = null
        mutable.value = mutable.value.copy(signaling = false)
        if (rejected) { report(); return }
        report("Reconnecting to the connection service…")
        reconnect?.cancel(false)
        reconnect = later(reconnectDelay * 1000) { if (current == generation) connectSocket(current) }
        reconnectDelay = (reconnectDelay * 2).coerceAtMost(8)
    }
    private fun sendJson(message: JsonObject) {
        val ws = socket ?: return
        val raw = message.toString(); require(raw.encodeToByteArray().size <= 32_768)
        sends = sends.thenCompose { ws.sendText(raw, true).thenApply<Void> { null } }
    }
    private fun signal(id: String, payload: JsonObject) {
        val p = peers[id] ?: return
        val token = p.token ?: (System.currentTimeMillis().toDouble() + ThreadLocalRandom.current().nextDouble()).also { p.token = it }
        publishY(buildJsonObject {
            put("type", "signal"); put("from", self); put("to", id); put("token", token)
            put("signal", payload["description"] ?: buildJsonObject { put("type", "candidate"); put("candidate", payload.getValue("candidate")) })
        })
    }
    private fun publishY(data: JsonObject) = sendJson(buildJsonObject { put("type", "publish"); put("topic", "meshboard-crdt:$room"); put("data", data) })
    private fun announceY() = publishY(buildJsonObject { put("type", "announce"); put("from", self) })
    private fun receiveSignal(raw: String) {
        receiveYSignal(Json.parseToJsonElement(raw).jsonObject)
    }
    private fun receiveYSignal(message: JsonObject) {
        if (message.text("type") == "pong") return
        if (message.text("type") == "error") {
            report(if (message.text("code") == "room-full") "This board already has 8 participants." else "The connection service declined this session.")
            socket?.sendClose(1000, "policy"); socket = null
            mutable.value = mutable.value.copy(signaling = false); return
        }
        require(message.text("type") == "publish" && message.text("topic") == "meshboard-crdt:$room")
        val data = message.getValue("data").jsonObject
        val id = data.text("from"); require(uuidPattern.matches(id))
        if (id == self || ("to" in data && data.text("to") != self)) return
        if (peers.size >= 7 && id !in peers) return
        desired.add(id)
        if (data.text("type") == "announce") { ensurePeer(id, true); return }
        require(data.text("type") == "signal")
        val signal = data.getValue("signal").jsonObject
        val type = signal.text("type")
        if (type == "offer") {
            val existing = peers[id]
            if (existing?.open == true) return
            val remote = data.number("token")
            if (existing?.token != null) {
                if (existing.token!! > remote) return
                // libwebrtc-java does not expose simple-peer's implicit rollback.
                // Recreate the losing offerer's connection before answering.
                drop(id)
            }
        }
        val p = ensurePeer(id, false) ?: return
        if (type == "answer") p.token = null
        val payload = when (type) {
            "offer", "answer" -> buildJsonObject { put("description", signal) }
            "candidate" -> buildJsonObject { put("candidate", signal.getValue("candidate")) }
            else -> error("Unsupported WebRTC signal")
        }
        applySignal(id, p, payload)
    }
    private fun applySignal(id: String, p: Peer, payload: JsonObject) {
        if ("description" in payload) {
            payload.exact("description")
            val d = payload.getValue("description").jsonObject; d.exact("type", "sdp")
            val type = d.text("type"); require(type == "offer" || type == "answer")
            val sdp = d.text("sdp"); require(sdp.startsWith("v=0") && sdp.length <= 24_000)
            val description = RTCSessionDescription(if (type == "offer") RTCSdpType.OFFER else RTCSdpType.ANSWER, sdp)
            p.pc.setRemoteDescription(description, object : SetSessionDescriptionObserver {
                override fun onSuccess() = post {
                    if (peers[id] !== p) return@post
                    p.remoteSet = true; p.candidates.forEach(p.pc::addIceCandidate); p.candidates.clear()
                    if (type == "offer") makeDescription(id, p, false)
                }
                override fun onFailure(error: String) = post { fail(id, p) }
            })
        } else {
            payload.exact("candidate")
            val c = payload.getValue("candidate").jsonObject
            require(c.keys.all { it in setOf("candidate", "sdpMid", "sdpMLineIndex", "usernameFragment") })
            val sdp = c.text("candidate"); require(sdp.length <= 2048)
            if (sdp.isEmpty()) return
            val mid = c["sdpMid"]?.jsonPrimitive?.contentOrNull
            val index = c["sdpMLineIndex"]?.jsonPrimitive?.intOrNull ?: 0
            require(index in 0..16 && (mid == null || mid.length <= 64))
            val candidate = RTCIceCandidate(mid, index, sdp)
            if (p.remoteSet) p.pc.addIceCandidate(candidate) else { require(p.candidates.size < 128); p.candidates.add(candidate) }
        }
    }
    private fun ensurePeer(id: String, initiator: Boolean = false): Peer? {
        peers[id]?.let { return it }
        if (id !in desired) return null
        val count = attempts[id] ?: 0
        if (count >= 3) { report("A peer could not connect. Check the network and retry."); return null }
        attempts[id] = count + 1
        lateinit var peer: Peer
        val pc = factory!!.createPeerConnection(config, object : PeerConnectionObserver {
            override fun onIceCandidate(c: RTCIceCandidate) = post {
                if (peers[id] !== peer) return@post
                signal(id, buildJsonObject { putJsonObject("candidate") { put("candidate", c.sdp); put("sdpMid", c.sdpMid); put("sdpMLineIndex", c.sdpMLineIndex) } })
            }
            override fun onDataChannel(channel: RTCDataChannel) = post {
                if (peers[id] === peer) attach(id, peer, channel) else { channel.close(); channel.dispose() }
            }
            override fun onConnectionChange(state: RTCPeerConnectionState) = post {
                if (peers[id] !== peer) return@post
                if (state == RTCPeerConnectionState.FAILED) fail(id, peer)
                if (state == RTCPeerConnectionState.CONNECTED) updateRoute(id, peer)
            }
        })
        peer = Peer(pc); peers[id] = peer
        peer.timeout = later(20_000) { fail(id, peer) }
        if (initiator) {
            peer.token = System.currentTimeMillis().toDouble() + ThreadLocalRandom.current().nextDouble()
            attach(id, peer, pc.createDataChannel(channelLabel, RTCDataChannelInit())); makeDescription(id, peer, true)
        }
        report(); return peer
    }
    private fun makeDescription(id: String, p: Peer, offer: Boolean) {
        val observer = object : CreateSessionDescriptionObserver {
            override fun onSuccess(d: RTCSessionDescription) = post {
                if (peers[id] !== p) return@post
                p.pc.setLocalDescription(d, object : SetSessionDescriptionObserver {
                    override fun onSuccess() = post {
                        if (peers[id] === p) signal(id, buildJsonObject { putJsonObject("description") {
                                put("type", if (offer) "offer" else "answer")
                                put("sdp", d.sdp)
                        } })
                    }
                    override fun onFailure(error: String) = post { fail(id, p) }
                })
            }
            override fun onFailure(error: String) = post { fail(id, p) }
        }
        if (offer) p.pc.createOffer(RTCOfferOptions(), observer) else p.pc.createAnswer(RTCAnswerOptions(), observer)
    }
    private fun attach(id: String, p: Peer, channel: RTCDataChannel) {
        if (p.channel != null || !channel.isOrdered || !channel.isReliable) { channel.close(); channel.dispose(); return }
        p.channel = channel
        channel.registerObserver(object : RTCDataChannelObserver {
            override fun onBufferedAmountChange(sent: Long) = post { if (peers[id] === p) flush(id, p) }
            override fun onStateChange() = post { if (peers[id] === p) channelState(id, p) }
            override fun onMessage(buffer: RTCDataChannelBuffer) {
                fun invalid() { if (p.invalidQueued.compareAndSet(false, true)) post { if (peers[id] === p) rejectPeer(id) } }
                if (p.invalidQueued.get()) return
                val size = buffer.data.remaining()
                if (!buffer.binary || size !in 29..16 * 1024 + 28) { invalid(); return }
                if (p.incomingBytes.addAndGet(size) > 8 * 1024 * 1024 || p.incomingFrames.incrementAndGet() > 512) { invalid(); return }
                val bytes = ByteArray(size); buffer.data.get(bytes)
                post {
                    p.incomingBytes.addAndGet(-bytes.size); p.incomingFrames.decrementAndGet()
                    if (peers[id] !== p) return@post
                    try {
                        val result = checkNotNull(p.secure).receive(bytes)
                        result.response?.let { enqueue(id, p, listOf(it)) }
                        if (result.admitted) {
                            p.open = true; p.timeout?.cancel(false); attempts[id] = 0
                            sendY(id, p, YWire.sync(0, crdt.stateVector()))
                            sendY(id, p, awareness.all()); sendY(id, p, byteArrayOf(3))
                            updateRoute(id, p); report(null)
                        }
                        result.data?.let { clear -> p.yReceiver.accept(clear)?.let { receiveYData(id, p, it) } }
                    } catch (e: Exception) { rejectPeer(id) }
                }
            }
        })
        channelState(id, p)
    }
    private fun channelState(id: String, p: Peer) {
        when (p.channel?.state) {
            RTCDataChannelState.OPEN -> if (p.secure == null) {
                try {
                    require(p.channel!!.label == SecureWire.CHANNEL)
                    p.secure = SecureChannel(JvmSessionCrypto, sessionKey, room, self, id, p.pc.localDescription.sdp, p.pc.remoteDescription.sdp)
                    enqueue(id, p, listOf(p.secure!!.hello))
                } catch (_: Exception) { rejectPeer(id) }
            }
            RTCDataChannelState.CLOSED -> fail(id, p)
            else -> Unit
        }
    }
    private fun receiveYData(id: String, p: Peer, bytes: ByteArray) {
        val board = crdt
        val reader = YWire.Reader(bytes)
        when (reader.uint()) {
            0L -> {
                val step = reader.uint(); val update = reader.bytes(); reader.end()
                when (step) {
                    0L -> sendY(id, p, YWire.sync(1, board.update(update)))
                    1L, 2L -> { board.apply(update); report() }
                    else -> error("Invalid Yjs sync step")
                }
            }
            1L -> {
                val update = reader.bytes(); reader.end()
                awareness.receive(update)?.let(::broadcastY)
                previews.clear(); previews.putAll(awareness.previews()); report()
            }
            3L -> { reader.end(); sendY(id, p, awareness.all()) }
            else -> error("Unsupported y-webrtc message")
        }
    }
    private fun broadcastY(bytes: ByteArray) { peers.toMap().forEach { (id, p) -> if (p.open) sendY(id, p, bytes) } }
    private fun sendY(id: String, p: Peer, bytes: ByteArray) {
        try {
            require(bytes.size <= YWire.MAX_MESSAGE)
            val frames = YWire.frames(bytes).map { checkNotNull(p.secure).encrypt(it) }
            enqueue(id, p, frames)
        } catch (e: Exception) { fail(id, p) }
    }
    private fun enqueue(id: String, p: Peer, frames: List<ByteArray>) {
        val bytes = frames.sumOf { it.size }
        require(p.queueBytes + bytes <= 8 * 1024 * 1024)
        p.queue.addAll(frames); p.queueBytes += bytes; flush(id, p)
    }
    private fun flush(id: String, p: Peer) {
        val channel = p.channel ?: return
        if (channel.state != RTCDataChannelState.OPEN) return
        try {
            while (p.queue.isNotEmpty() && channel.bufferedAmount < 256 * 1024) {
                val bytes = p.queue.first()
                channel.send(RTCDataChannelBuffer(ByteBuffer.wrap(bytes), true))
                p.queue.removeFirst(); p.queueBytes -= bytes.size
            }
        } catch (e: Exception) { fail(id, p) }
    }
    private fun updateRoute(id: String, p: Peer) = p.pc.getStats { report -> post {
            if (peers[id] !== p) return@post
            val stats = report.stats
            stats.values.filter { it.type == RTCStatsType.TRANSPORT }.forEach { transport ->
                val pair = stats[transport.attributes["selectedCandidatePairId"]] ?: return@forEach
                p.relayed = listOf("localCandidateId", "remoteCandidateId").any { key -> stats[pair.attributes[key]]?.attributes?.get("candidateType") == "relay" }
            }
            report()
    } }
    private fun rejectPeer(id: String) { desired.remove(id); drop(id); report("A peer could not authenticate or sent invalid encrypted data.") }
    private fun fail(id: String, p: Peer) {
        if (peers[id] !== p) return
        drop(id)
        if (id in desired && socket != null) {
            desired.remove(id); later(1500) { if (socket != null && peers.size < 7) announceY() }
        }
    }
    private fun drop(id: String) {
        val p = peers.remove(id) ?: return
        p.timeout?.cancel(false); p.secure?.close()
        p.channel?.let { it.unregisterObserver(); it.close(); it.dispose() }
        p.pc.close(); previews.remove(id); report()
        awareness.expire(id)?.let(::broadcastY); previews.clear(); previews.putAll(awareness.previews()); report()
    }
    private fun disconnect() {
        generation++; reconnect?.cancel(false); previewTask?.cancel(false); previewTask = null
        awarenessTask?.cancel(false); awarenessTask = null; pendingPreview = null
        desired.clear(); attempts.clear(); peers.keys.toList().forEach(::drop)
        socket?.abort(); socket = null; self = ""; previews.clear()
    }
    override fun close() {
        if (disposed) return
        executor.submit {
            disconnect(); crdt.close(); factory?.dispose(); factory = null; audio?.dispose(); audio = null
            disposed = true
        }.get(10, TimeUnit.SECONDS)
        executor.shutdownNow(); http.shutdownNow()
    }
    companion object {
        fun validateOrigin(value: String): String {
            val uri = URI(value.trim())
            require(uri.scheme in listOf("http", "https") && uri.host != null && uri.userInfo == null && uri.port in -1..65535) { "Enter an HTTP or HTTPS app address." }
            require(uri.scheme == "https" || uri.host in listOf("localhost", "127.0.0.1", "[::1]")) { "Use HTTPS for an app on another computer." }
            return "${uri.scheme}://${uri.rawAuthority}"
        }
        fun parseInvite(value: String): Triple<String, String, String> {
            val uri = URI(value.trim()); val base = validateOrigin(value)
            require(uri.rawFragment?.split('&')?.size == 2) { "Paste the full encrypted invite, including its key." }
            val params = uri.rawFragment?.split('&')?.associate { entry ->
                val pair = entry.split('=', limit = 2)
                require(pair.size == 2) { "The invite must include #crdt=…" }
                pair[0] to URLDecoder.decode(pair[1], Charsets.UTF_8)
            } ?: error("Paste the full invite link, including #crdt=…")
            val key = "crdt"
            require(params.size == 2 && params.keys == setOf(key, "key") && uuidPattern.matches(params.getValue(key))) { "This invite belongs to another protocol or is invalid. Create a new board and share its invite." }
            val secret = params.getValue("key"); SecureWire.decodeKey(secret)
            return Triple(base, params.getValue(key).lowercase(), secret)
        }
    }
}
