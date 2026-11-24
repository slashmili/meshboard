package dev.meshboard.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.CameraPreview
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import meshboard.QrScanEvents

/** Uses no capture Activity, image files, network decoder, or media tracks. */
@Composable
fun AndroidQrScanner(events: QrScanEvents) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    fun granted() = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    var permission by remember { mutableStateOf(granted()) }
    var requested by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var camera by remember { mutableStateOf<DecoratedBarcodeView?>(null) }
    val latestEvents by rememberUpdatedState(events)
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permission = it
        if (!it) error = "Camera access is off. Allow it in Settings, or cancel and paste the invite."
    }
    LaunchedEffect(Unit) {
        if (!permission && !requested) { requested = true; request.launch(Manifest.permission.CAMERA) }
    }
    fun resume() {
        if (permission && error == null && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) camera?.resume()
    }
    DisposableEffect(lifecycle, camera, permission, error) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    val nowGranted = granted()
                    if (permission != nowGranted) { permission = nowGranted; if (nowGranted) error = null }
                    resume()
                }
                Lifecycle.Event.ON_PAUSE -> camera?.pause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        resume()
        onDispose { lifecycle.removeObserver(observer); camera?.pause() }
    }
    AlertDialog(onDismissRequest = { events.cancelled() }, title = { Text("Scan a board QR code") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Point the camera at a Meshboard invite. Images are processed only on this device.")
            if (permission && error == null) AndroidView(
                factory = { viewContext ->
                    DecoratedBarcodeView(viewContext).apply {
                        statusView.text = "Looking for a board QR code…"
                        barcodeView.decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
                        barcodeView.addStateListener(object : CameraPreview.StateListener {
                            override fun previewSized() {}
                            override fun previewStarted() {}
                            override fun previewStopped() {}
                            override fun cameraClosed() {}
                            override fun cameraError(e: Exception) {
                                post { error = "The camera is unavailable. Close other camera apps and try again, or paste the invite." }
                            }
                        })
                        decodeContinuous(object : BarcodeCallback {
                            override fun barcodeResult(result: BarcodeResult) {
                                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                                    pause()
                                    latestEvents.found(result.text)
                                }
                            }
                        })
                        camera = this
                    }
                }, modifier = Modifier.fillMaxWidth().height(240.dp)
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (!permission && requested) TextButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
            }) { Text("Open Settings") }
            if (permission && error != null) TextButton(onClick = { error = null }) { Text("Try camera again") }
        }
    }, confirmButton = { TextButton(onClick = { events.cancelled() }) { Text("Cancel · Paste invite") } })
}
