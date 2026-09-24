package meshboard

import kotlinx.serialization.json.*

/** Test adapter only. StateFlow retains its immutable state instance until a change. */
internal class InteropSnapshot(private val encode: (BoardState) -> String = ::encodeInteropState) {
    private var previous: BoardState? = null

    fun changed(state: BoardState): String? {
        // Do not traverse/serialize a large board just to discover it is unchanged.
        if (state === previous) return null
        val result = encode(state)
        previous = state
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
