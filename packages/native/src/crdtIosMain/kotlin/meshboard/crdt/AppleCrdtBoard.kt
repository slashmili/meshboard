@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package meshboard.crdt

import kotlinx.cinterop.*
import kotlinx.serialization.json.*
import meshboard.BoardElement
import meshboard.Wire
import meshboard.crdt.ffi.*
import platform.Foundation.NSRecursiveLock

/** Owns one Rust document. Opt-in only; not connected to AppleBoardController yet. */
class AppleCrdtBoard internal constructor(clientId: Long) : CrdtBoard {
    constructor() : this(-1)
    private val lock = NSRecursiveLock()
    private var handle = takeResult(meshboard_crdt_create(clientId)).first

    private fun <T> locked(action: () -> T): T {
        lock.lock()
        try { return action() } finally { lock.unlock() }
    }

    private fun call(operation: Int, input: ByteArray = byteArrayOf()): ByteArray = locked {
        check(handle != 0L) { "CRDT board is closed" }
        require(input.size <= Wire.MAX_MESSAGE) { "CRDT input exceeds 4 MiB" }
        val result = if (input.isEmpty()) meshboard_crdt_call(handle, operation, null, 0uL)
        else input.usePinned { pinned ->
            meshboard_crdt_call(handle, operation, pinned.addressOf(0).reinterpret(), input.size.toULong())
        }
        takeResult(result).second
    }

    override fun put(element: BoardElement) {
        val json = Wire.elementJson(element)
        Wire.element(json)
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
    override fun close() = locked {
        if (handle != 0L) {
            call(7)
            handle = 0L
        }
    }
}

/** Copy before releasing Rust's allocation, including on error. No borrowed pointers escape. */
internal fun takeResult(result: CValue<MeshboardCrdtResult>): Pair<Long, ByteArray> {
    try {
        return result.useContents {
            check(len <= Wire.MAX_MESSAGE.toULong()) { "CRDT output exceeds 4 MiB" }
            val bytes = if (len == 0uL) byteArrayOf() else checkNotNull(data).reinterpret<ByteVar>().readBytes(len.toInt())
            when (status) {
                0 -> handle to bytes
                1 -> throw IllegalArgumentException(bytes.decodeToString())
                else -> throw IllegalStateException(bytes.decodeToString())
            }
        }
    } finally { meshboard_crdt_result_free(result) }
}
