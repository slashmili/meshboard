package dev.meshboard.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import meshboard.LocalBoardController
import meshboard.MobileBoardApp
import java.util.UUID

class BoardViewModel : ViewModel() {
    val controller = LocalBoardController { "${System.currentTimeMillis().toString(36)}-${UUID.randomUUID()}" }
    override fun onCleared() { controller.discard() }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val board: BoardViewModel = viewModel()
            Box(Modifier.fillMaxSize().safeDrawingPadding()) { MobileBoardApp(board.controller) }
        }
    }
}
