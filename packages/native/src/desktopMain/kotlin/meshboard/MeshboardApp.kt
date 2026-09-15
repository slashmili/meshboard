@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
package meshboard

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.*

private val Ink = Color(0xff293b36)
private val Green = Color(0xff254d3c)
private val Muted = Color(0xff78866e)
private val Paper = Color(0xfff8f9f6)
private val Border = Color(0xffe0e5dc)
private val Palette = listOf("Ink" to "#293b36", "Fern" to "#387c59", "Blue" to "#4878c8", "Violet" to "#9563be", "Coral" to "#d76857", "Amber" to "#c49226")
private fun paint(hex: String) = Color(0xff000000 or hex.drop(1).toLong(16))
private fun Point.offset() = Offset(x.toFloat(), y.toFloat())

@Composable
fun MeshboardApp(controller: BoardController, defaultOrigin: String = "http://127.0.0.1:5173", qrImage: (String) -> ImageBitmap? = { null }) {
    val state by controller.state.collectAsState()
    val currentState by rememberUpdatedState(state)
    var tool by remember { mutableStateOf(Tool.Pen) }
    var color by remember { mutableStateOf(Palette.first().second) }
    var width by remember { mutableStateOf(3.0) }
    var view by remember { mutableStateOf(View()) }
    var draft by remember { mutableStateOf<BoardElement?>(null) }
    var space by remember { mutableStateOf(false) }
    var cancelled by remember { mutableIntStateOf(0) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var origin by remember { mutableStateOf(defaultOrigin) }
    var joinLink by remember { mutableStateOf("") }
    var copied by remember { mutableStateOf(false) }
    var canvasSize by remember { mutableStateOf(Size.Zero) }
    val focus = remember { FocusRequester() }
    val density = LocalDensity.current.density
    val clipboard = LocalClipboardManager.current
    fun cancel() { draft = null; cancelled++; controller.preview(null) }
    fun zoom(factor: Double) { cancel(); view = view.zoomAt(Point(canvasSize.width / density / 2.0, canvasSize.height / density / 2.0), view.zoom * factor) }

    MaterialTheme(colorScheme = lightColorScheme(primary = Green, onPrimary = Color.White, background = Paper, surface = Color.White, onSurface = Ink, outline = Border)) {
        Column(Modifier.fillMaxSize().background(Paper).onPreviewKeyEvent { event ->
            if (dialog != null || event.isCtrlPressed || event.isMetaPressed || event.isAltPressed) return@onPreviewKeyEvent false
            if (event.key == Key.Spacebar) { space = event.type == KeyEventType.KeyDown; return@onPreviewKeyEvent true }
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            if (event.key == Key.Escape) { cancel(); return@onPreviewKeyEvent true }
            val next = when (event.key) { Key.P -> Tool.Pen; Key.E -> Tool.Eraser; Key.R -> Tool.Rectangle; Key.O -> Tool.Ellipse; Key.L -> Tool.Line; Key.H -> Tool.Hand; else -> null }
            if (next != null) { cancel(); tool = next; true } else false
        }) {
            Row(Modifier.fillMaxWidth().height(76.dp).background(Color.White).padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.size(36.dp).background(Green, RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) { Text("M", color = Color(0xffd8f29a), fontSize = 23.sp, fontWeight = FontWeight.Medium) }
                Text("meshboard.", color = Green, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                VerticalDivider(Modifier.height(28.dp))
                Column { Text("Untitled board", fontSize = 13.sp, fontWeight = FontWeight.Medium); Text("Desktop · a space for ideas", color = Muted, fontSize = 10.sp) }
                Spacer(Modifier.weight(1f))
                Surface(shape = RoundedCornerShape(30.dp), color = Color(0xfff0f4e9), border = BorderStroke(1.dp, Border)) { Text(state.connectionLabel, Modifier.padding(10.dp, 7.dp).testTag("connection-status"), fontSize = 11.sp, color = Green) }
                TextButton(onClick = { cancel(); dialog = "join" }) { Text("Join", fontSize = 12.sp) }
                Button(onClick = { cancel(); copied = false; dialog = "share" }, shape = RoundedCornerShape(8.dp)) { Text("Share", fontSize = 12.sp) }
                TextButton(onClick = { cancel(); dialog = "clear" }, enabled = state.elements.isNotEmpty()) { Text("Clear", fontSize = 12.sp) }
            }
            HorizontalDivider(color = Border)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Canvas(Modifier.fillMaxSize().testTag("drawing-canvas").semantics { contentDescription = "Drawing canvas" }
                    .onSizeChanged { canvasSize = Size(it.width.toFloat(), it.height.toFloat()) }
                    .focusRequester(focus).focusable()
                    .pointerHoverIcon(if (tool == Tool.Hand || space) PointerIcon.Hand else PointerIcon.Crosshair)
                    .onPointerEvent(PointerEventType.Scroll) { event ->
                        if (draft != null) return@onPointerEvent
                        val change = event.changes.first()
                        val p = Point(change.position.x / density.toDouble(), change.position.y / density.toDouble())
                        if (event.keyboardModifiers.isCtrlPressed || event.keyboardModifiers.isMetaPressed) view = view.zoomAt(p, view.zoom * exp(-change.scrollDelta.y * .12))
                        else view = view.copy(x = view.x - change.scrollDelta.x * 32, y = view.y - change.scrollDelta.y * 32)
                        change.consume()
                    }
                    .pointerInput(tool, color, width, cancelled) {
                        try {
                            awaitPointerEventScope {
                                var active: Tool? = null
                                var last: Point? = null
                                var start: Point? = null
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull() ?: continue
                                    val p = Point(change.position.x / density.toDouble(), change.position.y / density.toDouble())
                                    val world = view.world(p)
                                    if (change.pressed && !change.previousPressed) {
                                        focus.requestFocus()
                                        active = if (space || event.buttons.isTertiaryPressed) Tool.Hand else tool
                                        last = p; start = world
                                        if (active != Tool.Hand && active != Tool.Eraser) {
                                            draft = BoardElement(controller.newId(), active!!.wire, color, width, if (active == Tool.Pen) listOf(world) else listOf(world, world))
                                            controller.preview(draft)
                                        }
                                    }
                                    if (active != null) {
                                        when (active) {
                                            Tool.Hand -> if (change.previousPressed) { val previous = last!!; view = view.copy(x = view.x + p.x - previous.x, y = view.y + p.y - previous.y) }
                                            Tool.Eraser -> {
                                                val from = last?.let(view::world) ?: world
                                                val ids = currentState.elements.filter { hits(it, from, world, 9 / view.zoom) }.map { it.id }
                                                if (ids.isNotEmpty()) controller.remove(ids)
                                            }
                                            else -> if (change.previousPressed) {
                                                val element = draft
                                                if (element != null) {
                                                    if (active == Tool.Pen) {
                                                        if (element.points.last() != world) {
                                                            if (element.points.size == Wire.MAX_POINTS) {
                                                                controller.put(element)
                                                                draft = element.copy(id = controller.newId(), points = listOf(element.points.last(), world))
                                                            } else draft = element.copy(points = element.points + world)
                                                        }
                                                    } else draft = element.copy(points = listOf(start!!, if (event.keyboardModifiers.isShiftPressed) constrained(start!!, world, element.type) else world))
                                                    controller.preview(draft)
                                                }
                                            }
                                        }
                                        last = p
                                        if (!change.pressed) {
                                            draft?.let { element ->
                                                val first = element.points.first(); val end = element.points.last()
                                                if (element.type == "pen" || hypot(end.x - first.x, end.y - first.y) > 1 / view.zoom) controller.put(element)
                                            }
                                            draft = null; active = null; controller.preview(null)
                                        }
                                        change.consume()
                                    }
                                }
                            }
                        } finally { draft = null; controller.preview(null) }
                    }) {
                    val spacing = (24 * view.zoom * density).toFloat()
                    val ox = ((view.x * density % spacing + spacing) % spacing).toFloat()
                    val oy = ((view.y * density % spacing + spacing) % spacing).toFloat()
                    var x = ox
                    while (x < size.width) { var y = oy; while (y < size.height) { drawCircle(Color(0xffcbd3ca), .8f * density, Offset(x, y)); y += spacing }; x += spacing }
                    withTransform({
                        scale(density, density, Offset.Zero)
                        translate(view.x.toFloat(), view.y.toFloat())
                        scale(view.zoom.toFloat(), view.zoom.toFloat(), Offset.Zero)
                    }) {
                        state.elements.forEach { drawElement(it) }
                        state.previews.filter { preview -> state.elements.none { it.id == preview.id } }.forEach { drawElement(it, .65f) }
                        draft?.let { drawElement(it) }
                    }
                }
                if (state.elements.isEmpty() && draft == null && state.previews.isEmpty()) Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("A LITTLE SPACE. ENDLESS POSSIBILITIES.", fontSize = 10.sp, letterSpacing = 1.5.sp, color = Muted)
                    Spacer(Modifier.height(18.dp)); Text("What’s on your mind?", fontSize = 34.sp, color = Ink)
                    Spacer(Modifier.height(12.dp)); Text("A rough sketch. A big idea. A place to start.", fontSize = 13.sp, color = Muted)
                    Spacer(Modifier.height(6.dp)); Text("Pick a tool and make it yours.", fontSize = 13.sp, color = Muted)
                }
                Column(Modifier.align(Alignment.TopStart).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(shape = RoundedCornerShape(14.dp), shadowElevation = 3.dp, border = BorderStroke(1.dp, Border)) {
                        Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Tool.entries.forEach { item ->
                                TextButton(onClick = { cancel(); tool = item }, modifier = Modifier.width(125.dp).height(42.dp).testTag("tool-${item.wire}"), shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.textButtonColors(containerColor = if (tool == item) Color(0xffe5efd8) else Color.Transparent, contentColor = if (tool == item) Green else Muted)) {
                                    Text(item.shortcut, fontSize = 11.sp, modifier = Modifier.width(24.dp)); Text(item.label, fontSize = 12.sp, modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                    Surface(shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Border)) {
                        Column(Modifier.width(137.dp).padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            Text("STROKE", fontSize = 9.sp, letterSpacing = 1.sp, color = Muted)
                            Palette.chunked(3).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) { row.forEach { (label, hex) ->
                                Box(Modifier.size(29.dp).border(1.dp, if (color == hex) paint(hex) else Color.Transparent, CircleShape).padding(4.dp).background(paint(hex), CircleShape).clickable { color = hex }.semantics { contentDescription = label })
                            } } }
                            HorizontalDivider(color = Border)
                            Row { listOf(2.0, 3.0, 6.0).forEach { value ->
                                Box(Modifier.size(34.dp, 29.dp).background(if (width == value) Color(0xffeef3e7) else Color.Transparent, RoundedCornerShape(5.dp)).clickable { width = value }.semantics { contentDescription = "Stroke width ${value.toInt()}" }, contentAlignment = Alignment.Center) { Box(Modifier.size(17.dp, value.dp).background(Muted, CircleShape)) }
                            } }
                        }
                    }
                }
                Column(Modifier.align(Alignment.TopEnd).padding(25.dp), horizontalAlignment = Alignment.End) {
                    Text(if (state.invite.isEmpty()) "Just this window, just for now." else "Shared live. Never saved.", color = Muted, fontSize = 11.sp)
                    Text(if (state.invite.isEmpty()) "Closing discards your board." else "Gone when everyone leaves.", color = Muted, fontSize = 11.sp)
                }
                Row(Modifier.align(Alignment.BottomStart).padding(24.dp).background(Color.White, RoundedCornerShape(10.dp)).border(1.dp, Border, RoundedCornerShape(10.dp)).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { zoom(1 / 1.2) }, enabled = view.zoom > .25, modifier = Modifier.width(40.dp)) { Text("−") }
                    Text("${(view.zoom * 100).roundToInt()}%", fontSize = 11.sp, modifier = Modifier.testTag("zoom"))
                    TextButton(onClick = { zoom(1.2) }, enabled = view.zoom < 4, modifier = Modifier.width(40.dp)) { Text("+") }
                    TextButton(onClick = { cancel(); view = View() }) { Text("Reset view", fontSize = 11.sp) }
                }
                Text("Scroll to pan · Ctrl/⌘ + scroll to zoom", Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp), color = Muted, fontSize = 10.sp)
                TextButton(onClick = { cancel(); dialog = "help" }, modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp)) { Text("Help") }
                state.error?.let { error -> Surface(Modifier.align(Alignment.TopCenter).padding(top = 8.dp).widthIn(max = 480.dp), color = Color(0xfffff9ee), shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, Color(0xffe0c89e))) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Text(error, Modifier.weight(1f), fontSize = 12.sp); if (state.invite.isNotEmpty()) TextButton(onClick = controller::retry) { Text("Retry") } }
                } }
            }
            HorizontalDivider(color = Border)
            Row(Modifier.fillMaxWidth().height(34.dp).background(Color.White).padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (space) "Pan" else tool.label, color = Green, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(16.dp)); Text("P pen · E eraser · R rectangle · O ellipse · L line · H pan · Shift constrains shapes", color = Muted, fontSize = 10.sp)
                Spacer(Modifier.weight(1f)); Text(if (state.connected == 0) "WEB ↔ DESKTOP / 03" else if (state.relayed > 0) "VIA RELAY / 03" else "DIRECT CONNECTION / 03", fontSize = 9.sp, color = Muted, modifier = Modifier.testTag("connection-route"))
            }
        }
        when (dialog) {
            "share" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text("Make room for a friend.") }, text = {
                Column(Modifier.width(370.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (state.invite.isEmpty()) {
                        Text("Create a shared board from this window. Your existing drawing will be included.", fontSize = 13.sp)
                        OutlinedTextField(origin, { origin = it }, label = { Text("App address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Text("Use the address where the web app and signaling service run.", fontSize = 11.sp, color = Muted)
                    } else {
                        Text("Open this invite in the web app or another desktop window.", fontSize = 13.sp)
                        val image = remember(state.invite) { qrImage(state.invite) }
                        if (image != null) Image(image, "QR code for this board", Modifier.size(192.dp).align(Alignment.CenterHorizontally))
                        OutlinedTextField(state.invite, {}, readOnly = true, label = { Text("Board invite") }, modifier = Modifier.fillMaxWidth().testTag("invite"))
                        Text(if (copied) "Link copied." else "The localhost address works on this computer only.", fontSize = 11.sp, color = Muted)
                    }
                    Text("Connection prototype: invites are not authenticated and application-layer encryption is not implemented. Use test drawings.", fontSize = 11.sp, color = Muted)
                }
            }, confirmButton = { Button(onClick = { if (state.invite.isEmpty()) controller.share(origin) else { clipboard.setText(AnnotatedString(state.invite)); copied = true } }) { Text(if (state.invite.isEmpty()) "Create invite" else "Copy invite") } }, dismissButton = { Row { if (state.invite.isNotEmpty()) TextButton(onClick = { dialog = "leave" }) { Text("Leave board") }; TextButton(onClick = { dialog = null }) { Text("Done") } } })
            "join" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text("Join a board") }, text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Paste the full invite link from the web or desktop app.", fontSize = 13.sp)
                OutlinedTextField(joinLink, { joinLink = it }, label = { Text("Invite link") }, modifier = Modifier.width(380.dp).testTag("join-link"))
                if (state.elements.isNotEmpty() || state.invite.isNotEmpty()) Text("Joining discards this window’s current copy. Other connected peers keep theirs.", fontSize = 12.sp, color = Muted)
            } }, confirmButton = { Button(onClick = { controller.join(joinLink); view = View(); dialog = null }, enabled = joinLink.isNotBlank()) { Text("Join board") } }, dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } })
            "clear", "leave" -> {
                val leaving = dialog == "leave"
                AlertDialog(onDismissRequest = { dialog = null }, title = { Text(if (leaving) "Leave this board?" else "Clear this board?") }, text = {
                    Text(if (leaving) "Your copy will be discarded. Others can keep drawing; if you’re the last participant, the board is gone." else "Remove the objects you currently see for everyone on the board? There’s no undo in this version.")
                }, confirmButton = { Button(onClick = { cancel(); if (leaving) { controller.leave(); view = View() } else controller.remove(state.elements.map { it.id }); dialog = null }) { Text(if (leaving) "Leave" else "Clear board") } }, dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } })
            }
            "help" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text("A few handy shortcuts") }, text = { Text("P pen · E eraser · R rectangle · O ellipse · L line · H pan\n\nDrag to draw. The eraser removes whole objects. Hold Shift for squares, circles, or snapped lines. Hold Space and drag to pan; scroll to pan, Ctrl/⌘ + scroll to zoom. Escape cancels a draft.\n\nUse Share to create an invite or Join to paste one. Drawing lives only in participant memory. No export, undo, or application-layer encryption yet.", fontSize = 13.sp) }, confirmButton = { TextButton(onClick = { dialog = null }) { Text("Back to the board") } })
        }
    }
}

private fun DrawScope.drawElement(element: BoardElement, alpha: Float = 1f) {
    val color = paint(element.color).copy(alpha = alpha)
    val points = element.points
    if (points.size == 1) { drawCircle(color, element.width.toFloat() / 2, points.first().offset()); return }
    val path = Path().apply { val start = points.first(); moveTo(start.x.toFloat(), start.y.toFloat()); outline(element).drop(1).forEach { lineTo(it.x.toFloat(), it.y.toFloat()) } }
    if (element.type == "rectangle" || element.type == "ellipse") {
        val a = points.first(); val b = points.last()
        val top = Offset(min(a.x, b.x).toFloat(), min(a.y, b.y).toFloat())
        val size = Size(abs(a.x - b.x).toFloat(), abs(a.y - b.y).toFloat())
        if (element.type == "rectangle") drawRect(color, top, size, style = Stroke(element.width.toFloat()))
        else drawOval(color, top, size, style = Stroke(element.width.toFloat()))
    } else drawPath(path, color, style = Stroke(element.width.toFloat(), cap = StrokeCap.Round, join = StrokeJoin.Round))
}
