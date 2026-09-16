package meshboard.crdt

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import meshboard.BoardElement
import meshboard.Wire

/** Loaded only in the opt-in CRDT checkpoint; not included in app distributions. */
internal object CrdtJni {
    init {
        System.load(requireNotNull(System.getProperty("meshboard.crdt.library")) {
            "Run the CRDT Gradle task to build and locate the native library"
        })
    }
    external fun create(clientId: Long): Long
    external fun call(handle: Long, operation: Int, input: ByteArray): ByteArray
}

class JvmCrdtBoard internal constructor(clientId: Long) : CrdtBoard, AutoCloseable {
    constructor() : this(-1)
    private var handle = CrdtJni.create(clientId)

    // JVM calls are serialized with close; Rust also validates IDs under a mutex.
    @Synchronized private fun call(operation: Int, input: ByteArray = byteArrayOf()): ByteArray {
        check(handle != 0L) { "CRDT board is closed" }
        require(input.size <= Wire.MAX_MESSAGE) { "CRDT input exceeds 4 MiB" }
        return CrdtJni.call(handle, operation, input)
    }
    override fun put(element: BoardElement) {
        val json = Wire.elementJson(element)
        Wire.element(json) // Reuse current shape/coordinate/point-count validation.
        call(2, json.toString().encodeToByteArray())
    }
    override fun remove(id: String) {
        require(Regex("^[a-zA-Z0-9-]{1,80}$").matches(id))
        call(3, id.encodeToByteArray())
    }
    override fun removeAll(ids: List<String>) {
        require(ids.size <= Wire.MAX_REMOVED && ids.all { Regex("^[a-zA-Z0-9-]{1,80}$").matches(it) })
        call(8, JsonArray(ids.map(::JsonPrimitive)).toString().encodeToByteArray())
    }
    override fun elements(): List<BoardElement> {
        val values = Json.parseToJsonElement(call(6).decodeToString()).jsonObject
        require(values.size <= Wire.MAX_ELEMENTS)
        return values.map { (id, value) -> Wire.element(value).also { require(it.id == id) } }.sortedBy { it.id }
    }
    override fun apply(update: ByteArray) { call(1, update) }
    override fun stateVector(): ByteArray = call(4)
    override fun update(targetStateVector: ByteArray): ByteArray = call(5, targetStateVector)
    @Synchronized override fun close() {
        if (handle != 0L) {
            CrdtJni.call(handle, 7, byteArrayOf())
            handle = 0
        }
    }
}
