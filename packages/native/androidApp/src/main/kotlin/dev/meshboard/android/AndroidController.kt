package dev.meshboard.android
import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*
import meshboard.*
import meshboard.crypto.JvmSessionCrypto
import meshboard.crdt.CrdtBoard
import meshboard.crdt.JvmCrdtBoard
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
    // Reflection keeps the optional wrapper/library out of normal and release APKs.
    private fun newCrdt(): CrdtBoard = JvmCrdtBoard()
    private var crdt = newCrdt()
    private val elements get() = crdt.elements()
    private val channelLabel get() = SecureWire.CHANNEL
    private fun resetDocument() { crdt.close(); crdt = newCrdt() }
    private var awareness = YAwareness()
    private var awarenessTask: ScheduledFuture<*>? = null
    private val previews = mutableMapOf<String, BoardElement>()
    private var factory: PeerConnectionFactory? = null
    private var config = PeerConnection.RTCConfiguration(emptyList())
    private var socket: WebSocket? = null
    private var generation = 0
    private var self = ""
    private var origin = ""
    private var room = ""
    private var sessionKey = ""
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
        var secure: SecureChannel? = null
        val incomingBytes = java.util.concurrent.atomic.AtomicInteger()
        val incomingFrames = java.util.concurrent.atomic.AtomicInteger()
        val invalidQueued = java.util.concurrent.atomic.AtomicBoolean()
        var remoteSet = false
        var relayed = false
        var timeout: ScheduledFuture<*>? = null
        val candidates = mutableListOf<IceCandidate>()
        val yReceiver = YWire.Receiver()
        var token: Double? = null
        val queue = ArrayDeque<ByteArray>()
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
        mutable.value = mutable.value.copy(elements = elements, previews = previews.values.toList(), connected = open.size,
        connecting = peers.size - open.size, relayed = open.count { it.relayed }, error = error)
    }
    override fun newId() = "${System.currentTimeMillis().toString(36)}-${UUID.randomUUID()}"
    override fun put(element: BoardElement) = post { Wire.element(Wire.elementJson(element)); local(BoardMessage("put", element = element)) }
    override fun remove(ids: List<String>) = post { if (ids.isNotEmpty()) local(BoardMessage("remove", ids = ids)) }
    private fun local(message: BoardMessage) {
        val board = crdt
        val before = board.stateVector()
        when (message.type) {
            "put" -> board.put(requireNotNull(message.element))
            "remove" -> board.removeAll(message.ids)
            else -> error("Invalid local CRDT operation")
        }
        report(); broadcastY(YWire.sync(2, board.update(before)))
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
    override fun share(origin: String) = post { if (room.isEmpty()) { sessionKey = SecureWire.key(JvmSessionCrypto.random32()); begin(validateOrigin(origin), UUID.randomUUID().toString(), true) } }
    override fun join(invite: String) = post { val (base, id, key) = parseInvite(invite); sessionKey = key; begin(base, id, false) }
    override fun retry() = post { if (room.isNotEmpty()) begin(origin, room, true) }
    override fun leave() = post { disconnect(); room = ""; sessionKey = ""; resetDocument(); mutable.value = BoardState() }
    // Keep the canonical invite unchanged. Only debug emulator network destinations use its host alias.
    private fun destination(url: String): String {
        if (!emulator) return url
        val uri = URI(url)
        return if (uri.host in listOf("127.0.0.1", "localhost")) URI(uri.scheme, null, "10.0.2.2", uri.port, uri.path, uri.query, null).toString() else url
    }
    private fun begin(base: String, id: String, keepDrawing: Boolean) {
        disconnect(); if (!keepDrawing) resetDocument()
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
        val url = destination(origin).replaceFirst("https:", "wss:").replaceFirst("http:", "ws:") + "/signal-y-webrtc"
        socket = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) = post {
                if (current != generation || socket !== ws) { ws.cancel(); return@post }
                run {
                    sendJson(buildJsonObject { put("type", "subscribe"); putJsonArray("topics") { add("meshboard-crdt:$room") } })
                    announceY(); reconnectDelay = 1
                    mutable.value = mutable.value.copy(signaling = true); report(null)
                }
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
            socket?.close(1000, "policy"); socket = null
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
                // Recreate the losing offerer, matching simple-peer's glare policy.
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
    private fun ensurePeer(id: String, initiator: Boolean = false): Peer? {
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
        if (initiator) {
            peer.token = System.currentTimeMillis().toDouble() + ThreadLocalRandom.current().nextDouble()
            attach(id, peer, pc.createDataChannel(channelLabel, DataChannel.Init())); makeDescription(id, peer, true)
        }
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
                signal(id, buildJsonObject { putJsonObject("description") {
                        put("type", d.type.canonicalForm())
                        put("sdp", d.description)
                } })
            }), d)
        })
        if (offer) p.pc.createOffer(listener, MediaConstraints()) else p.pc.createAnswer(listener, MediaConstraints())
    }
    private fun attach(id: String, p: Peer, channel: DataChannel) {
        if (p.channel != null) { channel.close(); channel.dispose(); return }
        p.channel = channel
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = post { if (peers[id] === p) flush(id, p) }
            override fun onStateChange() = post { if (peers[id] === p) channelState(id, p) }
            override fun onMessage(buffer: DataChannel.Buffer) {
                fun invalid() { if (p.invalidQueued.compareAndSet(false, true)) post { if (peers[id] === p) reject(id) } }
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
                    } catch (_: Exception) { reject(id) }
                }
            }
        })
        channelState(id, p)
    }
    private fun channelState(id: String, p: Peer) {
        when (p.channel?.state()) {
            DataChannel.State.OPEN -> if (p.secure == null) {
                try {
                    require(p.channel!!.label() == SecureWire.CHANNEL)
                    p.secure = SecureChannel(JvmSessionCrypto, sessionKey, room, self, id, p.pc.localDescription.description, p.pc.remoteDescription.description)
                    enqueue(id, p, listOf(p.secure!!.hello))
                } catch (_: Exception) { reject(id) }
            }
            DataChannel.State.CLOSED -> fail(id, p)
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
            enqueue(id, p, YWire.frames(bytes).map { checkNotNull(p.secure).encrypt(it) })
        } catch (_: Exception) { fail(id, p) }
    }
    private fun enqueue(id: String, p: Peer, frames: List<ByteArray>) {
        val bytes = frames.sumOf { it.size }
        require(p.queueBytes + bytes <= 8 * 1024 * 1024)
        p.queue.addAll(frames); p.queueBytes += bytes; flush(id, p)
    }
    private fun flush(id: String, p: Peer) {
        val channel = p.channel ?: return; if (channel.state() != DataChannel.State.OPEN) return
        try {
            while (p.queue.isNotEmpty() && channel.bufferedAmount() < 256 * 1024) {
                val bytes = p.queue.first()
                check(channel.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), true)))
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
    private fun reject(id: String) { desired.remove(id); drop(id); report("A peer could not authenticate or sent invalid encrypted data.") }
    private fun fail(id: String, p: Peer) {
        if (peers[id] !== p) return; drop(id)
        val current = generation
        if (id in desired && socket != null) {
            desired.remove(id)
            later(1500) { if (current == generation && socket != null && peers.size < 7) announceY() }
        }
    }
    private fun drop(id: String) {
        val p = peers.remove(id) ?: return; p.timeout?.cancel(false); p.secure?.close()
        p.channel?.let { it.unregisterObserver(); it.close(); it.dispose() }; p.pc.close(); p.pc.dispose()
        previews.remove(id); report()
        awareness.expire(id)?.let(::broadcastY); previews.clear(); previews.putAll(awareness.previews()); report()
    }
    private fun disconnect() {
        generation++; reconnect?.cancel(false); previewTask?.cancel(false); previewTask = null
        awarenessTask?.cancel(false); awarenessTask = null; pendingPreview = null
        desired.clear(); attempts.clear(); peers.keys.toList().forEach(::drop)
        socket?.cancel(); socket = null; self = ""; previews.clear(); http.dispatcher.cancelAll()
    }
    override fun close() {
        if (disposed) return
        disposed = true
        executor.execute {
            disconnect(); crdt.close(); factory?.dispose(); factory = null
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
        fun parseInvite(value: String): Triple<String, String, String> {
            require(value.length <= 2048) { "The invite is too long." }
            val uri = URI(value.trim()); val base = validateOrigin(value)
            val entries = uri.rawFragment?.split('&') ?: error("Paste the full encrypted invite.")
            require(entries.size == 2) { "Paste the full encrypted invite, including its key." }
            val params = entries.associate { entry ->
                val pair = entry.split('=', limit = 2); require(pair.size == 2)
                pair[0] to URLDecoder.decode(pair[1], "UTF-8")
            }
            require(params.keys == setOf("crdt", "key")) { "Invalid encrypted invite." }
            val room = params.getValue("crdt").lowercase(); require(SecureWire.validId(room))
            val key = params.getValue("key"); SecureWire.decodeKey(key)
            return Triple(base, room, key)
        }
    }
}
