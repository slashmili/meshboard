package meshboard

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import kotlin.test.*

class InteropSnapshotTest {
    private val large = BoardElement("large", "pen", "#387c59", 3.0,
        List(12_000) { Point(it.toDouble(), -it.toDouble()) })

    @Test fun unchangedLargeBoardIsSerializedOnlyOnce() {
        var serializations = 0
        val snapshots = InteropSnapshot { serializations++; encodeInteropState(it) }
        val state = MutableStateFlow(BoardState(elements = listOf(large)))
        val first = Json.parseToJsonElement(assertNotNull(snapshots.changed(state.value))).jsonObject
        assertEquals(12_000, first.getValue("elements").jsonArray.single().jsonObject.getValue("points").jsonArray.size)
        repeat(1_000) { assertNull(snapshots.changed(state.value)) }
        // An equal report does not replace StateFlow's instance either.
        state.value = state.value.copy()
        assertNull(snapshots.changed(state.value))
        assertEquals(1, serializations)
    }

    @Test fun metadataPreviewsEditsDeletesAndSessionResetStillPublish() {
        val snapshots = InteropSnapshot()
        var state = BoardState(elements = listOf(large))
        assertNotNull(snapshots.changed(state))
        val changes = listOf<(BoardState) -> BoardState>(
            { it.copy(signaling = true, invite = "test-invite") },
            { it.copy(connected = 3) }, { it.copy(relayed = 1) },
            { it.copy(previews = listOf(large.copy(id = "draft"))) },
            { it.copy(previews = emptyList()) },
            { it.copy(elements = listOf(large.copy(color = "#ffffff"))) },
            { it.copy(elements = emptyList()) }, { it.copy(error = "test error") },
            { BoardState() },
        )
        for (change in changes) {
            state = change(state)
            assertEquals(encodeInteropState(state), snapshots.changed(state))
            assertNull(snapshots.changed(state))
        }
    }

    @Test fun failedSerializationDoesNotConsumeTheChange() {
        var fail = true
        val snapshots = InteropSnapshot { check(!fail); encodeInteropState(it) }
        val state = BoardState()
        assertFailsWith<IllegalStateException> { snapshots.changed(state) }
        fail = false
        assertNotNull(snapshots.changed(state))
        assertNull(snapshots.changed(state))
    }
}
