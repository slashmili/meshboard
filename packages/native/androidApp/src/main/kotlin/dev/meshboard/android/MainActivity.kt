package dev.meshboard.android

import android.os.Bundle
import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import meshboard.MobileBoardApp
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

class BoardViewModel(application: Application) : AndroidViewModel(application) {
    val controller = AndroidController(application)
    override fun onCleared() { controller.close() }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val board: BoardViewModel = viewModel()
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                MobileBoardApp(board.controller, defaultOrigin = if (BuildConfig.DEBUG) "http://127.0.0.1:5173" else "https://", qrImage = { invite ->
                    val matrix = QRCodeWriter().encode(invite, BarcodeFormat.QR_CODE, 512, 512)
                    val pixels = IntArray(512 * 512) { i -> if (matrix[i % 512, i / 512]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
                    Bitmap.createBitmap(pixels, 512, 512, Bitmap.Config.ARGB_8888).asImageBitmap()
                })
            }
        }
    }
}
