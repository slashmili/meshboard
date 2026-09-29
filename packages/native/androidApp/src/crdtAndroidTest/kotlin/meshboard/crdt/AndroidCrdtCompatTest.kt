package meshboard.crdt

import androidx.test.platform.app.InstrumentationRegistry
import android.os.Bundle
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket

/** Test APK only. adb forwards this loopback adapter to the existing Yjs test driver. */
class AndroidCrdtCompatTest {
    @Test fun compatibility() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("meshboard.crdtCompat") == "true")
        ServerSocket(18767, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 60_000
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("stream", "MESHBOARD_CRDT_READY\n")
            })
            while (true) {
                server.accept().use { socket ->
                    socket.soTimeout = 30_000
                    val input = socket.getInputStream().bufferedReader()
                    val line = input.readLine() ?: return@use // Readiness probe.
                    if (line == "close") return
                    require(line.length <= 16 * 1024 * 1024)
                    val response = crdtCompat(Json.parseToJsonElement(line).jsonObject)
                    socket.getOutputStream().bufferedWriter().apply {
                        write(response.toString()); newLine(); flush()
                    }
                }
            }
        }
    }
}
