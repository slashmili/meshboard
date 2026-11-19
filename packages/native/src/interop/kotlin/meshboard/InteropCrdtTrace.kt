package meshboard

import kotlin.io.encoding.Base64
import kotlin.time.TimeSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import meshboard.crdt.CrdtBoard

/** Test-only observations at the actual document boundary; no board content or invite secrets. */
internal class InteropCrdtTrace(private val board: CrdtBoard) : CrdtBoard by board {
    private val started = TimeSource.Monotonic.markNow()
    private var operations = 0
    private val latest = MutableStateFlow<JsonObject>(buildJsonObject {})
    fun snapshot(): JsonObject = latest.value

    private fun record(kind: String, bytes: Int = 0, change: () -> Unit) {
        val start = TimeSource.Monotonic.markNow()
        try {
            change()
        } finally {
            latest.value = buildJsonObject {
                put("operation", kind); put("operations", ++operations)
                put("inputBytes", bytes)
                put("completedMs", started.elapsedNow().inWholeMilliseconds)
                put("durationMs", start.elapsedNow().inWholeMilliseconds)
                put("stateVector", Base64.encode(board.stateVector()))
            }
        }
    }
    override fun apply(update: ByteArray) = record("apply", update.size) { board.apply(update) }
    override fun put(element: BoardElement) = record("put") { board.put(element) }
    override fun remove(id: String) = record("remove") { board.remove(id) }
    override fun removeAll(ids: List<String>) = record("removeAll") { board.removeAll(ids) }
    override fun move(ids: List<String>, delta: Point) = record("move") { board.move(ids, delta) }
    override fun undo() = record("undo") { board.undo() }
    override fun redo() = record("redo") { board.redo() }
}
