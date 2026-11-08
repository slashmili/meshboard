package meshboard.crdt

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import meshboard.BoardElement
import meshboard.Wire

/** Shared JNI binding, packaged with every desktop and Android app. */
internal object CrdtJni {
    init {
        val desktopLibrary = System.getProperty("meshboard.crdt.library")
        if (desktopLibrary != null) System.load(desktopLibrary)
        else {
            val name = System.mapLibraryName("meshboard_crdt_core")
            val resource = CrdtJni::class.java.getResourceAsStream("/meshboard/native/$name")
            if (resource == null) System.loadLibrary("meshboard_crdt_core") // Android APK ABI directory.
            else resource.use { input ->
                val directory = java.nio.file.Files.createTempDirectory("meshboard-crdt-").toFile()
                directory.deleteOnExit()
                val library = java.io.File(directory, name)
                library.deleteOnExit()
                library.outputStream().use { input.copyTo(it) }
                System.load(library.absolutePath)
            }
        }
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
    override fun move(ids: List<String>, delta: meshboard.Point) {
        require(ids.size <= Wire.MAX_ELEMENTS && delta.x.isFinite() && delta.y.isFinite())
        val request = kotlinx.serialization.json.buildJsonObject {
            put("ids", JsonArray(ids.map(::JsonPrimitive)))
            put("dx", JsonPrimitive(delta.x)); put("dy", JsonPrimitive(delta.y))
        }
        call(9, request.toString().encodeToByteArray())
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
