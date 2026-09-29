package meshboard

import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Test-source-only access to real channels; no production fault API or document reset. */
fun closeInteropChannels(controller: Any) {
    fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
    val executor = field(controller, "executor") as ExecutorService
    executor.submit {
        check(field(controller, "crdt") != null) { "Network faults require the CRDT test build" }
        val peers = field(controller, "peers") as Map<*, *>
        check(peers.isNotEmpty()) { "No peers to disconnect" }
        for (peer in peers.values.toList()) {
            val channel = field(requireNotNull(peer), "channel") ?: error("Missing data channel")
            channel.javaClass.getMethod("close").invoke(channel)
        }
    }.get(5, TimeUnit.SECONDS)
}
