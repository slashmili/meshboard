package meshboard

import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.github.vinceglb.filekit.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.core.PickerType
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

@Composable
fun InsertImageButton(controller: DrawingController, center: () -> Point, cancel: () -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val latestController by rememberUpdatedState(controller)
    val latestCenter by rememberUpdatedState(center)
    val launcher = rememberFilePickerLauncher(type = PickerType.File(extensions = listOf("png","jpg","jpeg"))) { file ->
        if (file != null) scope.launch {
            busy = true
            try {
                val bytes = readImageFile(file)
                decodeBoardImage(bytes) // A valid header alone is insufficient for a local import.
                latestController.put(imageElement(latestController.newId(),bytes,latestCenter()))
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = IMAGE_HELP }
            finally { busy = false }
        }
    }
    TextButton(enabled = !busy, onClick = { cancel(); launcher.launch() }, modifier = modifier.testTag("insert-image")) { Text(if (busy) "Loading…" else "Image") }
    if (error != null) AlertDialog(onDismissRequest = { error = null }, title = { Text("Cannot insert image") }, text = { Text(error!!) }, confirmButton = { TextButton(onClick = { error = null }) { Text("OK") } })
}

/** Providers may omit file size; never trust metadata to bound the read. */
suspend fun readImageFile(file: io.github.vinceglb.filekit.core.PlatformFile): ByteArray {
    val stream = file.getStream()
    try {
        val result = ByteArray(MAX_IMAGE_BYTES + 1)
        val chunk = ByteArray(8192)
        var size = 0
        while (size < result.size) {
            val count = stream.readInto(chunk, minOf(chunk.size, result.size - size))
            if (count <= 0) break
            chunk.copyInto(result, size, 0, count); size += count
        }
        require(size in 1..MAX_IMAGE_BYTES) { IMAGE_HELP }
        return result.copyOf(size)
    } finally { stream.close() }
}
