package meshboard

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import meshboard.resources.Res
import meshboard.resources.meshboard_icon
import org.jetbrains.compose.resources.painterResource

@Composable
fun MeshboardLogo(modifier: Modifier = Modifier) {
    Image(painterResource(Res.drawable.meshboard_icon), "Meshboard logo", modifier)
}
