package dev.meshboard.android

import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*
import meshboard.*
import okhttp3.*
import okio.ByteString
import org.webrtc.*
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.*

/** JNI callbacks only copy buffers/enqueue work; document and WebRTC calls use one executor. */
class AndroidController(context: Context, private val forceRelay: Boolean = false) : BoardController, AutoCloseable {
    private val appContext = context.applicationContext
    private val emulator = BuildConfig.DEBUG && (Build.FINGERPRINT.startsWith("generic") || Build.MODEL.contains("sdk_gphone") || Build.HARDWARE in listOf("ranchu", "goldfish"))
    private val executor = ScheduledThreadPoolExecutor(1) { r -> Thread(r, "meshboard-session").apply { isDaemon = true } }
    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).followRedirects(false).build()
    private val mutable = MutableStateFlow(BoardState())
    override val state = mutable.asStateFlow()
    private val document = BoardDocument()
    private val previews = mutableMapOf<String, BoardElement>()
    private var factory: PeerConnectionFactory? = null
    private var config = PeerConnection.RTCConfiguration(emptyList())
    private var socket: WebSocket? = null
    private var generation = 0
    private var self = ""
    private var origin = ""
    private var room = ""
    @Volatile private var disposed = false
    private val peers = mutableMapOf<String, Peer>()
    private val desired = mutableSetOf<String>()
    private val attempts = mutableMapOf<String, Int>()
    private var reconnect: ScheduledFuture<*>? = null
    private var reconnectDelay = 1L
    private var previewTask: ScheduledFuture<*>? = null
    private var pendingPreview: BoardElement? = null
    private class Peer(val pc: PeerConnection) {
        var channel: DataChannel? = null
        var open = false
        var remoteSet = false
        var relayed = false
        var timeout: ScheduledFuture<*>? = null
        val candidates = mutableListOf<IceCandidate>()
        val receiver = FrameReceiver(System::currentTimeMillis)
        val queue = ArrayDeque<String>()
        var queueBytes = 0
    }
    private fun post(action: () -> Unit) {
        if (disposed) return
        try { executor.execute { if (!disposed) try { action() } catch (e: Exception) { report(e.message ?: "The connection could not complete.") } } }
        catch (_: RejectedExecutionException) { /* Closing raced a native callback. */ }
    }
    private fun later(delay: Long, action: () -> Unit) = executor.schedule({ post(action) }, delay, TimeUnit.MILLISECONDS)
    private fun report(error: String? = mutable.value.error) {
        val open = peers.values.filter { it.open }
        mutable.value = mutable.value.copy(elements = document.elements, previews = previews.values.toList(), connected = open.size,
            connecting = peers.size - open.size, relayed = open.count { it.relayed }, error = error)
    }
    override fun newId() = "${System.currentTimeMillis().toString(36)}-${UUID.randomUUID()}"
    override fun put(element: BoardElement) = post { Wire.element(Wire.elementJson(element)); local(BoardMessage("put", element = element)) }
    override fun remove(ids: List<String>) = post { if (ids.isNotEmpty()) local(BoardMessage("remove", ids = ids)) }
    private fun local(message: BoardMessage) { document.apply(message); report(); broadcast(message) }
    override fun preview(element: BoardElement?) = post {
        pendingPreview = element
        if (element == null) { previewTask?.cancel(false); previewTask = null; broadcast(BoardMessage("preview")) }
        else if (previewTask == null) previewTask = later(50) { previewTask = null; broadcast(BoardMessage("preview", element = pendingPreview)) }
    }
    override fun share(origin: String) = post { if (room.isEmpty()) begin(validateOrigin(origin), UUID.randomUUID().toString(), true) }
    override fun join(invite: String) = post { val (base, id) = parseInvite(invite); begin(base, id, false) }
    override fun retry() = post { if (room.isNotEmpty()) begin(origin, room, true) }
    override fun leave() = post { disconnect(); room = ""; document.reset(); mutable.value = BoardState() }

    // Keep the canonical invite unchanged. Only debug emulator network destinations use its host alias.
    private fun destination(url: String): String {
        if (!emulator) return url
        val uri = URI(url)
        return if (uri.host in listOf("127.0.0.1", "localhost")) URI(uri.scheme, null, "10.0.2.2", uri.port, uri.path, uri.query, null).toString() else url
    }
    private fun begin(base: String, id: String, keepDrawing: Boolean) {
        disconnect(); if (!keepDrawing) document.reset()
        origin = base; room = id
        mutable.value = BoardState(elements = document.elements, invite = "$origin/#room=$room")
        val current = generation
        http.newCall(Request.Builder().url(destination("$base/api/rtc-config")).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = post { if (current == generation) report("Could not load connection settings. Check the app address and retry.") }
            override fun onResponse(call: Call, response: Response) {
                val raw = try { response.use { if (it.isSuccessful) it.peekBody(16_385).string() else null } }
                catch (e: IOException) { onFailure(call, e); return }
                post {
                    if (current != generation) return@post
                    require(raw != null && raw.length <= 16_384) { "Could not load connection settings. Check the app address and retry." }
                    config = readConfig(raw)
                    if (factory == null) {
                        initialize(appContext)
                        factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
                    }
                    connectSocket(current)
                }
            }
        })
    }
    private fun readConfig(raw: String): PeerConnection.RTCConfiguration {
        val o = Json.parseToJsonElement(raw).jsonObject
        o.exact("iceServers", "iceTransportPolicy", "localDevelopment")
        val policy = o.text("iceTransportPolicy"); require(policy in listOf("all", "relay"))
        val local = o.getValue("localDevelopment").jsonPrimitive.boolean
        val servers = o.getValue("iceServers").jsonArray; require(servers.size <= 8)
        val ice = servers.map { item ->
            val server = item.jsonObject; require(server.keys.all { it in setOf("urls", "username", "credential") })
            val urls = server.getValue("urls").jsonArray.map { require(it.jsonPrimitive.isString); it.jsonPrimitive.content }
            require(urls.size in 1..8 && urls.all { Regex("^(stun|stuns|turn|turns):[^\\s]+$").matches(it) })
            val mapped = urls.map { if (emulator && local) it.replace("turn:127.0.0.1:", "turn:10.0.2.2:") else it }
            PeerConnection.IceServer.builder(mapped).setUsername(server["username"]?.jsonPrimitive?.content ?: "")
                .setPassword(server["credential"]?.jsonPrimitive?.content ?: "").createIceServer()
        }
        return PeerConnection.RTCConfiguration(ice).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            iceTransportsType = if (forceRelay || policy == "relay") PeerConnection.IceTransportsType.RELAY else PeerConnection.IceTransportsType.ALL
        }
    }
    private fun connectSocket(current: Int) {
        val url = destination(origin).replaceFirst("https:", "wss:").replaceFirst("http:", "ws:") + "/signal"
        socket = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) = post {
                if (current != generation || socket !== ws) { ws.cancel(); return@post }
                sendJson(buildJsonObject { put("v", 1); put("type", "join"); put("room", room) })
            }
            override fun onMessage(ws: WebSocket, text: String) = post {
                if (current != generation || socket !== ws) return@post
                if (text.encodeToByteArray().size > 32_768) { ws.close(1008, "invalid-message"); return@post }
                try { receiveSignal(text) } catch (_: Exception) { report("The connection service sent an unsupported message."); ws.close(1008, "invalid-message") }
            }
            override fun onMessage(ws: WebSocket, bytes: ByteString) { ws.close(1008, "invalid-message") }
            override fun onClosing(ws: WebSocket, code: Int, reason: String) { ws.close(code, null) }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) = post { if (current == generation && socket === ws) socketLost(current, code == 1008) }
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) = post { if (current == generation && socket === ws) socketLost(current, false) }
        })
    }
    private fun socketLost(current: Int, rejected: Boolean) {
        socket = null; mutable.value = mutable.value.copy(signaling = false)
        if (rejected) { report(); return }
        report("Reconnecting to the connection service…")
        reconnect?.cancel(false); reconnect = later(reconnectDelay * 1000) { if (current == generation) connectSocket(current) }
        reconnectDelay = (reconnectDelay * 2).coerceAtMost(8)
    }
    private fun sendJson(message: JsonObject) {
        val ws = socket ?: return; val raw = message.toString()
        require(raw.length <= 32_768 && ws.queueSize() < 256 * 1024 && ws.send(raw)) { "The connection service is unavailable. Retry the connection." }
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
                require(ids.size <= 7 && ids.all(uuidPattern::matches) && self !in ids)
                desired.addAll(ids); reconnectDelay = 1; mutable.value = mutable.value.copy(signaling = true); report(null)
                ids.forEach(::ensurePeer)
            }
            "peer-joined" -> { o.exact("v", "type", "peer"); val id = o.text("peer"); require(uuidPattern.matches(id) && id != self && desired.size < 7); desired.add(id); ensurePeer(id) }
            "peer-left" -> {
                o.exact("v", "type", "peer"); val id = o.text("peer"); desired.remove(id)
                val peer = peers[id] ?: return; peer.timeout?.cancel(false)
                peer.timeout = later(1000) { if (socket != null && id !in desired && peers[id] === peer) drop(id) }
            }
            "signal" -> {
                o.exact("v", "type", "from", "payload"); val id = o.text("from"); if (id !in desired) return
                val p = ensurePeer(id) ?: return; val payload = o.getValue("payload").jsonObject
                if ("description" in payload) {
                    payload.exact("description"); val d = payload.getValue("description").jsonObject; d.exact("type", "sdp")
                    val type = d.text("type"); val sdp = d.text("sdp")
                    require(type in listOf("offer", "answer") && sdp.startsWith("v=0") && sdp.length <= 24_000)
                    p.pc.setRemoteDescription(observer(id, p, set = {
                        p.remoteSet = true; p.candidates.forEach(p.pc::addIceCandidate); p.candidates.clear()
                        if (type == "offer") makeDescription(id, p, false)
                    }), SessionDescription(SessionDescription.Type.fromCanonicalForm(type), sdp))
                } else {
                    payload.exact("candidate"); val c = payload.getValue("candidate").jsonObject
                    require(c.keys.all { it in setOf("candidate", "sdpMid", "sdpMLineIndex", "usernameFragment") })
                    val sdp = c.text("candidate"); require(sdp.length <= 2048); if (sdp.isEmpty()) return
                    val mid = c["sdpMid"]?.jsonPrimitive?.contentOrNull
                    val index = c["sdpMLineIndex"]?.jsonPrimitive?.intOrNull ?: 0
                    require(index in 0..16 && (mid == null || mid.length <= 64))
                    val candidate = IceCandidate(mid, index, sdp)
                    if (p.remoteSet) p.pc.addIceCandidate(candidate) else { require(p.candidates.size < 128); p.candidates.add(candidate) }
                }
            }
            "error" -> { o.exact("v", "type", "code"); report(if (o.text("code") == "room-full") "This board already has 8 participants." else "The connection service declined this session.") }
            else -> error("Unsupported signaling message")
        }
    }
    private fun ensurePeer(id: String): Peer? {
        peers[id]?.let { return it }; if (id !in desired) return null
        val count = attempts[id] ?: 0
        if (count >= 3) { report("A peer could not connect. Check the network and retry."); return null }
        attempts[id] = count + 1
        lateinit var peer: Peer
        val pc = requireNotNull(factory!!.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onIceCandidate(c: IceCandidate) = post {
                if (peers[id] === peer) signal(id, buildJsonObject { putJsonObject("candidate") { put("candidate", c.sdp); put("sdpMid", c.sdpMid); put("sdpMLineIndex", c.sdpMLineIndex) } })
            }
            override fun onDataChannel(channel: DataChannel) = post { if (peers[id] === peer) attach(id, peer, channel) else { channel.close(); channel.dispose() } }
            override fun onConnectionChange(state: PeerConnection.PeerConnectionState) = post {
                if (peers[id] !== peer) return@post
                if (state == PeerConnection.PeerConnectionState.FAILED) fail(id, peer)
                if (state == PeerConnection.PeerConnectionState.CONNECTED) updateRoute(id, peer)
            }
            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onRenegotiationNeeded() {}
        }))
        peer = Peer(pc); peers[id] = peer; peer.timeout = later(20_000) { fail(id, peer) }
        if (self < id) { attach(id, peer, pc.createDataChannel("meshboard.v1", DataChannel.Init())); makeDescription(id, peer, true) }
        report(); return peer
    }
    private fun observer(id: String, p: Peer, create: (SessionDescription) -> Unit = {}, set: () -> Unit = {}) = object : SdpObserver {
        override fun onCreateSuccess(d: SessionDescription) = post { if (peers[id] === p) create(d) }
        override fun onSetSuccess() = post { if (peers[id] === p) set() }
        override fun onCreateFailure(error: String) = post { fail(id, p) }
        override fun onSetFailure(error: String) = post { fail(id, p) }
    }
    private fun makeDescription(id: String, p: Peer, offer: Boolean) {
        val listener = observer(id, p, create = { d ->
            p.pc.setLocalDescription(observer(id, p, set = {
                signal(id, buildJsonObject { putJsonObject("description") { put("type", d.type.canonicalForm()); put("sdp", d.description) } })
            }), d)
        })
        if (offer) p.pc.createOffer(listener, MediaConstraints()) else p.pc.createAnswer(listener, MediaConstraints())
    }
    private fun attach(id: String, p: Peer, channel: DataChannel) {
        if (p.channel != null || channel.label() != "meshboard.v1") { channel.close(); channel.dispose(); return }
        p.channel = channel
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = post { if (peers[id] === p) flush(id, p) }
            override fun onStateChange() = post { if (peers[id] === p) channelState(id, p) }
            override fun onMessage(buffer: DataChannel.Buffer) {
                if (buffer.binary || buffer.data.remaining() > Wire.MAX_FRAME) { post { if (peers[id] === p) reject(id) }; return }
                val bytes = ByteArray(buffer.data.remaining()); buffer.data.get(bytes)
                post {
                    if (peers[id] !== p) return@post
                    try {
                        val message = p.receiver.accept(bytes.decodeToString(throwOnInvalidSequence = true)) ?: return@post
                        if (message.type == "preview") { val element = message.element; if (element == null) previews.remove(id) else previews[id] = element }
                        else { previews.remove(id); document.apply(message) }
                        report()
                    } catch (_: Exception) { reject(id) }
                }
            }
        })
        channelState(id, p)
    }
    private fun channelState(id: String, p: Peer) {
        when (p.channel?.state()) {
            DataChannel.State.OPEN -> if (!p.open) {
                p.open = true; p.timeout?.cancel(false); attempts[id] = 0
                send(id, p, document.snapshot()); updateRoute(id, p); report(null)
            }
            DataChannel.State.CLOSED -> fail(id, p)
            else -> Unit
        }
    }
    private fun broadcast(message: BoardMessage) { peers.toMap().forEach { (id, p) -> if (p.open) send(id, p, message) } }
    private fun send(id: String, p: Peer, message: BoardMessage) {
        try {
            val frames = Wire.frames(message, UUID.randomUUID().toString()); val bytes = frames.sumOf { it.encodeToByteArray().size }
            require(p.queueBytes + bytes <= 8 * 1024 * 1024)
            p.queue.addAll(frames); p.queueBytes += bytes; flush(id, p)
        } catch (_: Exception) { fail(id, p) }
    }
    private fun flush(id: String, p: Peer) {
        val channel = p.channel ?: return; if (!p.open) return
        try {
            while (p.queue.isNotEmpty() && channel.bufferedAmount() < 256 * 1024) {
                val bytes = p.queue.first().encodeToByteArray()
                check(channel.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), false)))
                p.queue.removeFirst(); p.queueBytes -= bytes.size
            }
        } catch (_: Exception) { fail(id, p) }
    }
    private fun updateRoute(id: String, p: Peer) = p.pc.getStats { stats -> post {
        if (peers[id] !== p) return@post
        stats.statsMap.values.filter { it.type == "transport" }.forEach { transport ->
            val pair = stats.statsMap[transport.members["selectedCandidatePairId"]] ?: return@forEach
            p.relayed = listOf("localCandidateId", "remoteCandidateId").any { key -> stats.statsMap[pair.members[key]]?.members?.get("candidateType") == "relay" }
        }
        report()
    } }
    private fun reject(id: String) { desired.remove(id); drop(id); report("A peer sent invalid or oversized drawing data. Its connection was closed.") }
    private fun fail(id: String, p: Peer) {
        if (peers[id] !== p) return; drop(id)
        val current = generation
        if (id in desired && socket != null) later(1500) { if (current == generation) ensurePeer(id) }
    }
    private fun drop(id: String) {
        val p = peers.remove(id) ?: return; p.timeout?.cancel(false)
        p.channel?.let { it.unregisterObserver(); it.close(); it.dispose() }; p.pc.close(); p.pc.dispose()
        previews.remove(id); report()
    }
    private fun disconnect() {
        generation++; reconnect?.cancel(false); previewTask?.cancel(false); previewTask = null
        desired.clear(); attempts.clear(); peers.keys.toList().forEach(::drop)
        socket?.cancel(); socket = null; self = ""; previews.clear(); http.dispatcher.cancelAll()
    }
    override fun close() {
        if (disposed) return
        disposed = true
        executor.execute {
            disconnect(); document.reset(); factory?.dispose(); factory = null
            http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown(); executor.shutdown()
        }
    }
    companion object {
        private var initialized = false
        @Synchronized private fun initialize(context: Context) {
            if (!initialized) {
                PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).setEnableInternalTracer(false).createInitializationOptions())
                Logging.enableLogToDebugOutput(Logging.Severity.LS_NONE)
                initialized = true
            }
        }
        fun validateOrigin(value: String): String {
            require(value.length <= 2048) { "The app address is too long." }
            val uri = URI(value.trim())
            require(uri.scheme in listOf("http", "https") && uri.host != null && uri.userInfo == null && uri.port in -1..65535) { "Enter an HTTPS app address." }
            require(uri.scheme == "https" || (BuildConfig.DEBUG && uri.host in listOf("localhost", "127.0.0.1", "10.0.2.2"))) { "Use HTTPS for an app on another computer." }
            return "${uri.scheme}://${uri.rawAuthority}"
        }
        fun parseInvite(value: String): Pair<String, String> {
            require(value.length <= 2048) { "The invite is too long." }
            val uri = URI(value.trim()); val base = validateOrigin(value)
            val entries = uri.rawFragment?.split('&') ?: error("Paste the full invite, including #room=…")
            require(entries.size == 1) { "This invite is not supported by the connection prototype." }
            val pair = entries.single().split('=', limit = 2)
            require(pair.size == 2 && pair[0] == "room") { "The invite must include #room=…" }
            val room = URLDecoder.decode(pair[1], "UTF-8"); require(uuidPattern.matches(room)) { "This invite has an invalid room ID." }
            return base to room
        }
    }
}
