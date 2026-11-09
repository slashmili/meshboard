package meshboard

import kotlinx.serialization.json.*

/** Test adapter only. StateFlow retains its immutable state instance until a change. */
internal class InteropSnapshot(
    private val awaitReceipt: Boolean = false,
    private val encode: (BoardState) -> String = ::encodeInteropState,
) {
    private var previous: BoardState? = null
    // Receipt comes from the consumer after parsing, not merely from a socket send.
    // While blocked, leave previous untouched and serialize only the latest state next time.
    private var pending = false

    fun received() { pending = false }

    fun changed(state: BoardState): String? {
        // Do not traverse/serialize a large board just to discover it is unchanged.
        if (pending || state === previous) return null
        val result = encode(state)
        previous = state
        pending = awaitReceipt
        return result
    }
}

internal fun encodeInteropState(state: BoardState): String = buildJsonObject {
    put("connected", state.connected); put("relayed", state.relayed)
    put("invite", state.invite); put("signaling", state.signaling)
    put("error", state.error?.let(::JsonPrimitive) ?: JsonNull)
    put("elements", JsonArray(state.elements.map(Wire::elementJson)))
    put("previews", JsonArray(state.previews.map(Wire::elementJson)))
}.toString()
