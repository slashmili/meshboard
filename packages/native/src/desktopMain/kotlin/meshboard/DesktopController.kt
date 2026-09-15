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
import java.util.concurrent.*

/** All state and JNI operations run on one executor, never on libwebrtc callback threads. */
class DesktopController(private val forceRelay: Boolean = false) : BoardController, AutoCloseable {
    private val executor = ScheduledThreadPoolExecutor(1) { task -> Thread(task, "meshboard-session").apply { isDaemon = true } }
    // The app's HTTP/WebSocket server does not negotiate cleartext HTTP/2 (h2c).
    private val http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(8)).build()
    private val mutable = MutableStateFlow(BoardState())
    override val state = mutable.asStateFlow()
    private val document = BoardDocument()
    private val previews = mutableMapOf<String, BoardElement>()
    private var audio: HeadlessAudioDeviceModule? = null
    private var factory: PeerConnectionFactory? = null
    private var config = RTCConfiguration()
    private var socket: WebSocket? = null
    private var sends = CompletableFuture.completedFuture<Void>(null)
    private var generation = 0
    private var self = ""
    private var room = ""
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
        var remoteSet = false
        var relayed = false
        var timeout: ScheduledFuture<*>? = null
        val candidates = mutableListOf<RTCIceCandidate>()
        val receiver = FrameReceiver(System::currentTimeMillis)
        val queue = ArrayDeque<String>()
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
        mutable.value = mutable.value.copy(elements = document.elements, previews = previews.values.toList(),
            connected = open.size, connecting = peers.size - open.size, relayed = open.count { it.relayed }, error = error)
    }
    override fun newId() = "${System.currentTimeMillis().toString(36)}-${UUID.randomUUID()}"
    override fun put(element: BoardElement) = post {
        Wire.element(Wire.elementJson(element))
        local(BoardMessage("put", element = element))
    }
    override fun remove(ids: List<String>) = post { if (ids.isNotEmpty()) local(BoardMessage("remove", ids = ids)) }
    private fun local(message: BoardMessage) { document.apply(message); report(); broadcast(message) }
    override fun preview(element: BoardElement?) = post {
        pendingPreview = element
        if (element == null) {
            previewTask?.cancel(false); previewTask = null
            broadcast(BoardMessage("preview"))
        } else if (previewTask == null) previewTask = later(50) {
            previewTask = null
            broadcast(BoardMessage("preview", element = pendingPreview))
        }
    }
    override fun share(origin: String) = post {
        if (room.isNotEmpty()) return@post
        val base = validateOrigin(origin)
        begin(base, UUID.randomUUID().toString(), keepDrawing = true)
    }
    override fun join(invite: String) = post {
        val parsed = parseInvite(invite)
        begin(parsed.first, parsed.second, keepDrawing = false)
    }
    override fun retry() = post { if (room.isNotEmpty()) begin(origin, room, keepDrawing = true) }
    override fun leave() = post {
        disconnect(); room = ""; document.reset(); mutable.value = BoardState()
    }
    private fun begin(base: String, id: String, keepDrawing: Boolean) {
        disconnect()
        if (!keepDrawing) document.reset()
        origin = base; room = id
        mutable.value = BoardState(elements = document.elements, invite = "$origin/#room=$room")
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
        val url = URI(origin.replaceFirst("https:", "wss:").replaceFirst("http:", "ws:") + "/signal")
        http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(8)).buildAsync(url, object : WebSocket.Listener {
            val text = StringBuilder()
            override fun onOpen(ws: WebSocket) {
                ws.request(1)
                post {
                    if (current != generation) { ws.abort(); return@post }
                    socket = ws; sends = CompletableFuture.completedFuture(null)
                    sendJson(buildJsonObject { put("v", 1); put("type", "join"); put("room", room) })
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
    private fun signal(id: String, payload: JsonObject) = sendJson(buildJsonObject { put("v", 1); put("type", "signal"); put("to", id); put("payload", payload) })
    private fun receiveSignal(raw: String) {
        val o = Json.parseToJsonElement(raw).jsonObject; o.version()
        when (o.text("type")) {
            "welcome" -> {
                o.exact("v", "type", "self", "peers")
                val id = o.text("self"); require(uuidPattern.matches(id))
                if (self.isNotEmpty() && self != id) peers.keys.toList().forEach(::drop)
                self = id; attempts.clear(); desired.clear()
                val ids = o.getValue("peers").jsonArray.map { it.jsonPrimitive.content }
                require(ids.size <= 7 && ids.all(uuidPattern::matches))
                desired.addAll(ids); reconnectDelay = 1
                mutable.value = mutable.value.copy(signaling = true); report(null)
                ids.forEach(::ensurePeer)
            }
            "peer-joined" -> { o.exact("v", "type", "peer"); val id = o.text("peer"); require(uuidPattern.matches(id)); desired.add(id); ensurePeer(id) }
            "peer-left" -> {
                o.exact("v", "type", "peer"); val id = o.text("peer"); desired.remove(id)
                val p = peers[id] ?: return
                p.timeout?.cancel(false)
                p.timeout = later(1000) { if (socket != null && id !in desired && peers[id] === p) drop(id) }
            }
            "signal" -> {
                o.exact("v", "type", "from", "payload")
                val id = o.text("from"); if (id !in desired) return
                val p = ensurePeer(id) ?: return
                val payload = o.getValue("payload").jsonObject
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
            "error" -> { o.exact("v", "type", "code"); report(if (o.text("code") == "room-full") "This board already has 8 participants." else "The connection service declined this session.") }
            else -> error("Unsupported signaling message")
        }
    }
    private fun ensurePeer(id: String): Peer? {
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
        if (self < id) { attach(id, peer, pc.createDataChannel("meshboard.v1", RTCDataChannelInit())); makeDescription(id, peer, true) }
        report(); return peer
    }
    private fun makeDescription(id: String, p: Peer, offer: Boolean) {
        val observer = object : CreateSessionDescriptionObserver {
            override fun onSuccess(d: RTCSessionDescription) = post {
                if (peers[id] !== p) return@post
                p.pc.setLocalDescription(d, object : SetSessionDescriptionObserver {
                    override fun onSuccess() = post {
                        if (peers[id] === p) signal(id, buildJsonObject { putJsonObject("description") { put("type", if (offer) "offer" else "answer"); put("sdp", d.sdp) } })
                    }
                    override fun onFailure(error: String) = post { fail(id, p) }
                })
            }
            override fun onFailure(error: String) = post { fail(id, p) }
        }
        if (offer) p.pc.createOffer(RTCOfferOptions(), observer) else p.pc.createAnswer(RTCAnswerOptions(), observer)
    }
    private fun attach(id: String, p: Peer, channel: RTCDataChannel) {
        if (p.channel != null || channel.label != "meshboard.v1" || !channel.isOrdered || !channel.isReliable) { channel.close(); channel.dispose(); return }
        p.channel = channel
        channel.registerObserver(object : RTCDataChannelObserver {
            override fun onBufferedAmountChange(sent: Long) = post { if (peers[id] === p) flush(id, p) }
            override fun onStateChange() = post { if (peers[id] === p) channelState(id, p) }
            override fun onMessage(buffer: RTCDataChannelBuffer) {
                // JNI owns this buffer; copy it before returning from the callback.
                if (buffer.binary || buffer.data.remaining() > Wire.MAX_FRAME) { post { rejectPeer(id) }; return }
                val bytes = ByteArray(buffer.data.remaining()); buffer.data.get(bytes)
                post {
                    if (peers[id] !== p) return@post
                    try {
                        val message = p.receiver.accept(bytes.decodeToString(throwOnInvalidSequence = true)) ?: return@post
                        if (message.type == "preview") {
                            if (message.element == null) previews.remove(id) else previews[id] = message.element
                        } else { previews.remove(id); document.apply(message) }
                        report()
                    } catch (e: Exception) { rejectPeer(id) }
                }
            }
        })
        channelState(id, p)
    }
    private fun channelState(id: String, p: Peer) {
        when (p.channel?.state) {
            RTCDataChannelState.OPEN -> if (!p.open) {
                p.open = true; p.timeout?.cancel(false); attempts[id] = 0
                send(id, p, document.snapshot()); updateRoute(id, p); report(null)
            }
            RTCDataChannelState.CLOSED -> fail(id, p)
            else -> Unit
        }
    }
    private fun broadcast(message: BoardMessage) { peers.toMap().forEach { (id, p) -> if (p.open) send(id, p, message) } }
    private fun send(id: String, p: Peer, message: BoardMessage) {
        try {
            val frames = Wire.frames(message, UUID.randomUUID().toString())
            val bytes = frames.sumOf { it.encodeToByteArray().size }
            require(p.queueBytes + bytes <= 8 * 1024 * 1024)
            p.queue.addAll(frames); p.queueBytes += bytes; flush(id, p)
        } catch (e: Exception) { fail(id, p) }
    }
    private fun flush(id: String, p: Peer) {
        val channel = p.channel ?: return
        if (!p.open) return
        try {
            while (p.queue.isNotEmpty() && channel.bufferedAmount < 256 * 1024) {
                val bytes = p.queue.first().encodeToByteArray()
                channel.send(RTCDataChannelBuffer(ByteBuffer.wrap(bytes), false))
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
    private fun rejectPeer(id: String) { desired.remove(id); drop(id); report("A peer sent invalid or oversized drawing data. Its connection was closed.") }
    private fun fail(id: String, p: Peer) {
        if (peers[id] !== p) return
        drop(id)
        if (id in desired && socket != null) later(1500) { ensurePeer(id) }
    }
    private fun drop(id: String) {
        val p = peers.remove(id) ?: return
        p.timeout?.cancel(false)
        p.channel?.let { it.unregisterObserver(); it.close(); it.dispose() }
        p.pc.close(); previews.remove(id); report()
    }
    private fun disconnect() {
        generation++; reconnect?.cancel(false); previewTask?.cancel(false); previewTask = null
        desired.clear(); attempts.clear(); peers.keys.toList().forEach(::drop)
        socket?.abort(); socket = null; self = ""; previews.clear()
    }
    override fun close() {
        if (disposed) return
        executor.submit {
            disconnect(); document.reset(); factory?.dispose(); factory = null; audio?.dispose(); audio = null
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
        fun parseInvite(value: String): Pair<String, String> {
            val uri = URI(value.trim()); val base = validateOrigin(value)
            val params = uri.rawFragment?.split('&')?.associate { entry ->
                val pair = entry.split('=', limit = 2)
                require(pair.size == 2) { "The invite must include #room=…" }
                pair[0] to URLDecoder.decode(pair[1], Charsets.UTF_8)
            } ?: error("Paste the full invite link, including #room=…")
            require(params.keys == setOf("room") && uuidPattern.matches(params.getValue("room"))) { "This invite is not supported by the connection prototype." }
            return base to params.getValue("room")
        }
    }
}
