package meshboard

/** Scanner implementations decode locally and deliver callbacks on the UI thread. */
interface QrScanEvents {
    fun found(value: String)
    fun cancelled()
    fun failed(message: String)
}

/** Swift hosts its camera UI; stop must also invalidate pending permission callbacks. */
interface QrScanner {
    fun start(events: QrScanEvents)
    fun stop()
}

/** A camera may report the same code repeatedly, including after dismissal. */
class QrScanAttempt(private val events: QrScanEvents) : QrScanEvents {
    private var active = true
    fun close() { active = false }
    private inline fun finish(action: () -> Unit) {
        if (!active) return
        active = false
        action()
    }
    override fun found(value: String) = finish { events.found(value) }
    override fun cancelled() = finish { events.cancelled() }
    override fun failed(message: String) = finish { events.failed(message) }
}

/** Common encrypted fragment rules, with each platform's existing URL policy. */
fun parseEncryptedInvite(value: String, normalizeOrigin: (String) -> String): Triple<String, String, String> {
    require(value.length <= 2048) { "The invite is too long." }
    val base = normalizeOrigin(value)
    val entries = value.trim().substringAfter('#', "").split('&')
    require(entries.size == 2) { "Paste the full encrypted invite, including its key." }
    val params = entries.associate { entry ->
        val pair = entry.split('=', limit = 2)
        require(pair.size == 2) { "Invalid encrypted invite." }
        // The canonical QR/link format uses unescaped UUID and base64url values.
        // Accept percent-encoded unreserved values, as the JVM parsers did before.
        val decoded = Regex("%([0-9a-fA-F]{2})").replace(pair[1]) { it.groupValues[1].toInt(16).toChar().toString() }
        pair[0] to decoded
    }
    require(params.keys == setOf("crdt", "key")) { "Invalid encrypted invite." }
    val room = params.getValue("crdt").lowercase()
    require(SecureWire.validId(room)) { "Invalid encrypted invite." }
    val key = params.getValue("key")
    SecureWire.decodeKey(key)
    return Triple(base, room, key)
}
