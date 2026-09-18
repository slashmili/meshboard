package meshboard

import kotlin.random.Random
import kotlin.time.Clock
import kotlinx.serialization.json.*

/** Shared native lib0 framing for y-webrtc / y-protocols. */
object YWire {
    const val FRAGMENT_CHANNEL = "meshboard.y-webrtc.fragments-v1"
    const val FRAGMENT_SDP = "a=meshboard-fragments:1"
    const val MAX_MESSAGE = 2 * 1024 * 1024 + 1024
    private const val CHUNK = 16 * 1024 - 5
    class Writer {
        private var out = ByteArray(64)
        private var size = 0
        private fun write(value: Int) {
            if (size == out.size) out = out.copyOf(out.size * 2)
            out[size++] = value.toByte()
        }
        fun uint(value: Long): Writer {
            require(value in 0..9_007_199_254_740_991L)
            var n = value
            while (n > 127) { write(((n and 127) or 128).toInt()); n = n ushr 7 }
            write(n.toInt()); return this
        }
        fun bytes(value: ByteArray): Writer {
            require(value.size <= MAX_MESSAGE)
            uint(value.size.toLong())
            if (size + value.size > out.size) out = out.copyOf(maxOf(out.size * 2, size + value.size))
            value.copyInto(out, size); size += value.size
            return this
        }
        fun string(value: String) = bytes(value.encodeToByteArray())
        fun finish() = out.copyOf(size)
    }
    class Reader(private val data: ByteArray) {
        private var offset = 0
        init { require(data.size <= MAX_MESSAGE) }
        fun uint(): Long {
            var result = 0L
            for (shift in 0..49 step 7) {
                require(offset < data.size)
                val byte = data[offset++].toInt() and 255
                result = result or ((byte and 127).toLong() shl shift)
                require(result <= 9_007_199_254_740_991L)
                if (byte < 128) return result
            }
            error("Varuint exceeds safe integer range")
        }
        fun bytes(): ByteArray {
            val size = uint(); require(size <= data.size - offset)
            return data.copyOfRange(offset, offset + size.toInt()).also { offset += size.toInt() }
        }
        fun string(): String = bytes().decodeToString(throwOnInvalidSequence = true)
        fun end() { require(offset == data.size) { "Trailing protocol data" } }
    }
    fun sync(step: Int, bytes: ByteArray) = Writer().uint(0).uint(step.toLong()).bytes(bytes).finish()
    fun awareness(bytes: ByteArray) = Writer().uint(1).bytes(bytes).finish()
    fun frames(bytes: ByteArray): List<ByteArray> {
        require(bytes.isNotEmpty() && bytes.size <= MAX_MESSAGE)
        if (bytes.size <= CHUNK) return listOf(bytes)
        return (bytes.indices step CHUNK).map { offset ->
            val length = minOf(CHUNK, bytes.size - offset)
            ByteArray(length + 5).also { frame ->
                frame[0] = 127
                for (i in 0..3) frame[i + 1] = (bytes.size ushr (24 - i * 8)).toByte()
                bytes.copyInto(frame, 5, offset, offset + length)
            }
        }
    }
    class Receiver(private val now: () -> Long = ::protocolMillis) {
        private var pending: ByteArray? = null
        private var offset = 0
        private var started = 0L
        fun accept(bytes: ByteArray): ByteArray? {
            require(bytes.isNotEmpty() && bytes.size <= MAX_MESSAGE)
            if (bytes[0] != 127.toByte()) { require(pending == null); return bytes }
            require(bytes.size in 6..CHUNK + 5)
            var total = 0
            for (i in 1..4) total = (total shl 8) or (bytes[i].toInt() and 255)
            require(total in CHUNK + 1..MAX_MESSAGE)
            if (pending == null) { pending = ByteArray(total); offset = 0; started = now() }
            val target = pending!!
            require(total == target.size && now() - started <= 30_000 && offset + bytes.size - 5 <= total)
            bytes.copyInto(target, offset, 5); offset += bytes.size - 5
            if (offset < total) return null
            pending = null; require(target[0] != 127.toByte()); return target
        }
    }
}

/** Bounded y-protocols awareness state, with clocks, removal and 30-second expiry. */
class YAwareness(private val now: () -> Long = ::protocolMillis) {
    private data class Entry(val clock: Long, val state: JsonObject?, val seen: Long)
    val clientId = Random.nextLong(1, 0x1_0000_0000L)
    private val entries = mutableMapOf<Long, Entry>()
    private var clock = 0L
    fun local(peer: String, preview: BoardElement?): ByteArray {
        val element = preview?.let(Wire::elementJson)?.takeIf { it.toString().encodeToByteArray().size <= 48 * 1024 }
        val state = buildJsonObject { putJsonObject("meshboard") { put("peerId", peer); put("preview", element ?: JsonNull) } }
        entries[clientId] = Entry(++clock, state, now())
        return encode(listOf(clientId))
    }
    fun all() = encode(entries.filterValues { it.state != null }.keys.toList())
    private fun encode(ids: List<Long>): ByteArray {
        val writer = YWire.Writer().uint(ids.size.toLong())
        for (id in ids) { val entry = entries.getValue(id); writer.uint(id).uint(entry.clock).string(entry.state?.toString() ?: "null") }
        return YWire.awareness(writer.finish())
    }
    fun receive(bytes: ByteArray): ByteArray? {
        val reader = YWire.Reader(bytes); val count = reader.uint(); require(count <= 64)
        val incoming = mutableMapOf<Long, Entry>()
        repeat(count.toInt()) {
            val id = reader.uint(); val clock = reader.uint(); val text = reader.string()
            require(text.encodeToByteArray().size <= 64 * 1024)
            val json = Json.parseToJsonElement(text)
            val state = if (json == JsonNull) null else json.jsonObject
            state?.get("meshboard")?.jsonObject?.get("preview")?.takeUnless { it == JsonNull }?.let(Wire::element)
            require(!incoming.containsKey(id)); incoming[id] = Entry(clock, state, now())
        }
        reader.end()
        require((entries.keys + incoming.keys).size <= 64)
        val changed = mutableListOf<Long>()
        for ((id, entry) in incoming) {
            if (id == clientId) continue
            val old = entries[id]
            if (old == null || entry.clock > old.clock || (entry.clock == old.clock && entry.state == null && old.state != null)) {
                entries[id] = entry; changed.add(id)
            }
        }
        return changed.takeIf { it.isNotEmpty() }?.let(::encode)
    }
    fun expire(peer: String? = null): ByteArray? {
        val removed = entries.filter { (id, entry) -> id != clientId && entry.state != null &&
            (now() - entry.seen > 30_000 || (peer != null && entry.state["meshboard"]?.jsonObject?.get("peerId")?.jsonPrimitive?.contentOrNull == peer)) }.keys.toList()
        for (id in removed) entries[id] = entries.getValue(id).copy(state = null)
        return removed.takeIf { it.isNotEmpty() }?.let(::encode)
    }
    fun previews(): Map<String, BoardElement> = entries.filterKeys { it != clientId }.mapNotNull { (id, entry) ->
        entry.state?.get("meshboard")?.jsonObject?.get("preview")?.takeUnless { it == JsonNull }?.let { id.toString() to Wire.element(it) }
    }.toMap()
}

@OptIn(kotlin.time.ExperimentalTime::class)
private fun protocolMillis() = Clock.System.now().toEpochMilliseconds()
