package meshboard

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

@Composable
fun TextEditor(element: BoardElement, deleted: Boolean, onSave: (BoardElement) -> Unit, onDismiss: () -> Unit) {
    var text by remember(element) { mutableStateOf(element.text ?: "") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (element.type == "sticky") "Sticky note" else "Text box") }, text = {
        Column {
            OutlinedTextField(text, { if (it.length <= 500) text = it }, label = { Text("Text") }, minLines = 3, maxLines = 8, modifier = Modifier.testTag("text-editor"))
            Text(if (deleted) "This object was deleted by another participant. Cancel to close." else "${text.length}/500")
        }
    }, confirmButton = { TextButton(enabled = text.isNotBlank() && !deleted, onClick = { onSave(textElement(element.id, element.type, element.points.first(), element.color, text)) }) { Text("Save text") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
