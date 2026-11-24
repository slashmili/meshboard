package meshboard

import kotlin.test.*

class QrScannerTest {
    private val room = "123e4567-e89b-42d3-a456-426614174000"
    private val key = "A".repeat(43)
    private val invite = "https://board.example/#crdt=$room&key=$key"
    private fun parse(value: String) = parseEncryptedInvite(value) { "https://board.example" }

    @Test fun encryptedFragmentPreservesTheEntireSecret() {
        assertEquals(Triple("https://board.example", room, key), parse(invite))
        assertEquals(parse(invite), parse("https://board.example/#key=$key&crdt=${room.uppercase()}"))
        assertEquals(parse(invite), parse(invite.replace("crdt=1", "crdt=%31")))
    }
    @Test fun incompleteLegacyAndMalformedFragmentsAreRejected() {
        for (value in listOf("", "hello", "#room=$room", "#crdt=$room", "#key=$key", "#crdt=$room&key=bad",
            "#crdt=$room&key=${"B".repeat(43)}", "#crdt=bad&key=$key", "#crdt=$room&crdt=$room",
            "#crdt=$room&key=$key&extra=1", "#crdt=$room&key=$key&key=$key", "x".repeat(2049))) {
            assertFailsWith<IllegalArgumentException> { parse(value) }
        }
    }
    @Test fun oldInvitesAskForTheCompleteEncryptedLink() {
        val error = assertFailsWith<IllegalArgumentException> {
            parse("https://board.example/#room=$room")
        }
        assertEquals("Paste the full encrypted invite, including its key.", error.message)
    }
    @Test fun resultGateIgnoresDuplicatesAndLateCallbacksAfterCancelOrDispose() {
        val results = mutableListOf<String>()
        val events = object : QrScanEvents {
            override fun found(value: String) { results += "found" }
            override fun cancelled() { results += "cancelled" }
            override fun failed(message: String) { results += "failed" }
        }
        val first = QrScanAttempt(events)
        first.found(invite); first.found(invite); first.failed("late"); first.cancelled()
        assertEquals(listOf("found"), results)
        val cancelled = QrScanAttempt(events)
        cancelled.cancelled(); cancelled.found(invite)
        val disposed = QrScanAttempt(events)
        disposed.close(); disposed.found(invite); disposed.failed("late")
        assertEquals(listOf("found", "cancelled"), results)
        val failed = QrScanAttempt(events)
        failed.failed("camera"); failed.found(invite)
        assertEquals(listOf("found", "cancelled", "failed"), results)
    }
}
