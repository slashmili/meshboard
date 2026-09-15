package meshboard

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.*

private val MobileInk = Color(0xff293b36)
private val MobileGreen = Color(0xff254d3c)
private val MobilePaper = Color(0xfff8f9f6)
private val MobileMuted = Color(0xff78866e)
private val MobileBorder = Color(0xffe0e5dc)
private val MobilePalette = listOf("Ink" to "#293b36", "Fern" to "#387c59", "Blue" to "#4878c8", "Violet" to "#9563be", "Coral" to "#d76857", "Amber" to "#c49226")
private fun mobilePaint(hex: String) = Color(0xff000000 or hex.drop(1).toLong(16))

/** Touch layout shared by mobile targets; transport is deliberately independent. */
@Composable
fun MobileBoardApp(controller: BoardController, defaultOrigin: String = "https://", qrImage: (String) -> ImageBitmap) {
    val state by controller.state.collectAsState()
    val latest by rememberUpdatedState(state)
    var toolName by rememberSaveable { mutableStateOf(Tool.Pen.name) }
    val tool = Tool.valueOf(toolName)
    var color by rememberSaveable { mutableStateOf("#293b36") }
    var width by rememberSaveable { mutableStateOf(3.0) }
    var view by remember { mutableStateOf(View()) }
    var draft by remember { mutableStateOf<BoardElement?>(null) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var widthMenu by remember { mutableStateOf(false) }
    var cancelled by remember { mutableIntStateOf(0) }
    var canvasSize by remember { mutableStateOf(Size.Zero) }
    var origin by remember { mutableStateOf(defaultOrigin) }
    var inviteInput by remember { mutableStateOf("") }
    var copied by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val density = LocalDensity.current.density.toDouble()
    fun cancel() { draft = null; cancelled++; controller.preview(null) }
    fun zoom(factor: Double) { cancel(); view = view.zoomAt(Point(canvasSize.width / density / 2, canvasSize.height / density / 2), view.zoom * factor) }

    MaterialTheme(colorScheme = lightColorScheme(primary = MobileGreen, onPrimary = Color.White, surface = Color.White, onSurface = MobileInk, background = MobilePaper, outline = MobileBorder)) {
        Column(Modifier.fillMaxSize().background(MobilePaper)) {
            Row(Modifier.fillMaxWidth().height(60.dp).background(Color.White).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("meshboard.", color = MobileGreen, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { cancel(); dialog = "clear" }, enabled = state.elements.isNotEmpty(), modifier = Modifier.testTag("mobile-clear")) { Text("Clear") }
                TextButton(onClick = { cancel(); dialog = "help" }, modifier = Modifier.testTag("mobile-help")) { Text("Help") }
            }
            HorizontalDivider(color = MobileBorder)
            Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(state.connectionLabel, Modifier.weight(1f).testTag("mobile-connection-status"), fontSize = 12.sp, color = MobileGreen)
                TextButton(onClick = { cancel(); dialog = "join" }, modifier = Modifier.testTag("mobile-join")) { Text("Join") }
                TextButton(onClick = { cancel(); copied = false; dialog = "share" }, modifier = Modifier.testTag("mobile-share")) { Text("Share") }
            }
            Row(Modifier.fillMaxWidth().background(Color.White).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Tool.entries.forEach { item ->
                    val label = when (item) { Tool.Rectangle -> "Rect"; Tool.Ellipse -> "Oval"; else -> item.label }
                    TextButton(onClick = { cancel(); toolName = item.name }, modifier = Modifier.width(56.dp).height(48.dp).testTag("mobile-tool-${item.wire}").semantics { contentDescription = item.label; selected = tool == item },
                        contentPadding = PaddingValues(0.dp), shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.textButtonColors(containerColor = if (tool == item) Color(0xffe5efd8) else Color.Transparent)) { Text(label, fontSize = 12.sp) }
                }
            }
            Row(Modifier.fillMaxWidth().background(Color.White).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                MobilePalette.forEach { (name, hex) ->
                    Box(Modifier.size(48.dp).semantics { contentDescription = "$name stroke"; selected = color == hex }.clickable { cancel(); color = hex }.padding(9.dp)
                        .border(1.dp, if (color == hex) mobilePaint(hex) else Color.Transparent, CircleShape).padding(4.dp).background(mobilePaint(hex), CircleShape))
                }
                Box {
                    TextButton(onClick = { cancel(); widthMenu = true }, modifier = Modifier.testTag("mobile-width")) { Text("${width.toInt()} px", fontSize = 12.sp) }
                    DropdownMenu(expanded = widthMenu, onDismissRequest = { widthMenu = false }) {
                        listOf(2.0, 3.0, 6.0).forEach { value -> DropdownMenuItem(text = { Text("${value.toInt()} px stroke") }, onClick = { width = value; widthMenu = false }) }
                    }
                }
            }
            HorizontalDivider(color = MobileBorder)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Canvas(Modifier.fillMaxSize().testTag("mobile-canvas").semantics {
                    contentDescription = "Drawing canvas"
                    stateDescription = "${state.elements.size} objects"
                }.onSizeChanged { canvasSize = Size(it.width.toFloat(), it.height.toFloat()) }
                    .pointerInput(tool, color, width, density, cancelled) {
                        try {
                            awaitPointerEventScope {
                                var pointer: PointerId? = null
                                var start: Point? = null
                                var last: Point? = null
                                var navigating = false
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val pressed = event.changes.filter { it.pressed }
                                    if (pressed.size >= 2) {
                                        draft = null; pointer = null; navigating = true; controller.preview(null)
                                        val pair = pressed.take(2)
                                        if (pair.all { it.previousPressed }) {
                                            fun center(previous: Boolean): Point = Point(
                                                pair.sumOf { (if (previous) it.previousPosition.x else it.position.x).toDouble() } / (2 * density),
                                                pair.sumOf { (if (previous) it.previousPosition.y else it.position.y).toDouble() } / (2 * density))
                                            val old = center(true); val next = center(false)
                                            val oldSpan = (pair[0].previousPosition - pair[1].previousPosition).getDistance().toDouble()
                                            val newSpan = (pair[0].position - pair[1].position).getDistance().toDouble()
                                            val ratio = if (oldSpan > 1) newSpan / oldSpan else 1.0
                                            val scaled = view.zoomAt(old, view.zoom * ratio)
                                            view = scaled.copy(x = scaled.x + next.x - old.x, y = scaled.y + next.y - old.y)
                                        }
                                        event.changes.forEach { it.consume() }; continue
                                    }
                                    // After a pinch, wait for every finger to lift before drawing again.
                                    if (navigating) {
                                        if (pressed.isEmpty()) navigating = false
                                        event.changes.forEach { it.consume() }; continue
                                    }
                                    val change = event.changes.firstOrNull { it.id == pointer }
                                        ?: event.changes.firstOrNull { it.pressed && !it.previousPressed } ?: continue
                                    val point = Point(change.position.x / density, change.position.y / density)
                                    val world = view.world(point)
                                    if (pointer == null && change.pressed) {
                                        pointer = change.id; start = world; last = point
                                        if (tool != Tool.Hand && tool != Tool.Eraser) {
                                            draft = BoardElement(controller.newId(), tool.wire, color, width, if (tool == Tool.Pen) listOf(world) else listOf(world, world))
                                        }
                                    }
                                    if (pointer != change.id) continue
                                    when (tool) {
                                        Tool.Hand -> { val previous = last!!; view = view.copy(x = view.x + point.x - previous.x, y = view.y + point.y - previous.y) }
                                        Tool.Eraser -> {
                                            val from = last?.let(view::world) ?: world
                                            val ids = latest.elements.filter { hits(it, from, world, 12 / view.zoom) }.map { it.id }
                                            if (ids.isNotEmpty()) controller.remove(ids)
                                        }
                                        else -> draft?.let { element ->
                                            if (tool == Tool.Pen && element.points.last() != world) {
                                                if (element.points.size == Wire.MAX_POINTS) {
                                                    controller.put(element)
                                                    draft = element.copy(id = controller.newId(), points = listOf(element.points.last(), world))
                                                } else draft = element.copy(points = element.points + world)
                                            } else if (tool != Tool.Pen) draft = element.copy(points = listOf(start!!, world))
                                            controller.preview(draft)
                                        }
                                    }
                                    last = point
                                    if (!change.pressed) {
                                        draft?.let { element ->
                                            val a = element.points.first(); val b = element.points.last()
                                            if (element.type == "pen" || hypot(b.x - a.x, b.y - a.y) > 1 / view.zoom) controller.put(element)
                                        }
                                        draft = null; pointer = null; controller.preview(null)
                                    }
                                    change.consume()
                                }
                            }
                        } finally { draft = null; controller.preview(null) }
                    }) {
                    val d = density.toFloat()
                    val spacing = (24 * view.zoom * density).toFloat()
                    var x = ((view.x * density % spacing + spacing) % spacing).toFloat()
                    while (x < size.width) {
                        var y = ((view.y * density % spacing + spacing) % spacing).toFloat()
                        while (y < size.height) { drawCircle(Color(0xffcbd3ca), .8f * d, Offset(x, y)); y += spacing }
                        x += spacing
                    }
                    withTransform({ scale(d, d, Offset.Zero); translate(view.x.toFloat(), view.y.toFloat()); scale(view.zoom.toFloat(), view.zoom.toFloat(), Offset.Zero) }) {
                        state.elements.forEach { mobileElement(it) }
                        state.previews.forEach { mobileElement(it, .65f) }
                        draft?.let { mobileElement(it) }
                    }
                }
                if (state.elements.isEmpty() && draft == null) Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("A SPACE FOR IDEAS", fontSize = 10.sp, letterSpacing = 1.5.sp, color = MobileMuted)
                    Spacer(Modifier.height(14.dp))
                    Text("What’s on your mind?", fontSize = 26.sp, color = MobileInk)
                    Spacer(Modifier.height(10.dp))
                    Text("Draw with one finger. Pan and pinch with two.", fontSize = 12.sp, color = MobileMuted)
                }
                state.error?.let { Text(it, Modifier.align(Alignment.TopCenter).background(Color(0xfffff2d9)).padding(12.dp), fontSize = 12.sp) }
            }
            HorizontalDivider(color = MobileBorder)
            Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { zoom(1 / 1.2) }, enabled = view.zoom > .25, modifier = Modifier.testTag("mobile-zoom-out")) { Text("−") }
                Text("${(view.zoom * 100).roundToInt()}%", fontSize = 12.sp, modifier = Modifier.testTag("mobile-zoom"))
                TextButton(onClick = { zoom(1.2) }, enabled = view.zoom < 4, modifier = Modifier.testTag("mobile-zoom-in")) { Text("+") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { cancel(); view = View() }) { Text("Reset view", fontSize = 12.sp) }
            }
            Text(if (state.invite.isEmpty()) "Session-only canvas · not saved" else if (state.relayed > 0) "VIA RELAY · prototype / unverified peers" else "WebRTC · prototype / unverified peers",
                Modifier.fillMaxWidth().background(Color.White).padding(start = 16.dp, bottom = 8.dp).testTag("mobile-connection-route"), color = MobileMuted, fontSize = 10.sp)
        }
        if (dialog == "clear") AlertDialog(onDismissRequest = { dialog = null }, title = { Text("Clear this board?") }, text = { Text("Remove every object${if (state.invite.isNotEmpty()) " for everyone" else ""}? There’s no undo in this version.") },
            confirmButton = { Button(onClick = { controller.remove(state.elements.map { it.id }); dialog = null }, modifier = Modifier.testTag("mobile-confirm-clear")) { Text("Clear board") } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } })
        if (dialog == "help") AlertDialog(onDismissRequest = { dialog = null }, title = { Text("A little space for ideas") }, text = {
            Text("Choose a tool, then drag with one finger or a stylus. The eraser removes whole objects.\n\nUse two fingers to pan and pinch to zoom. Adding a second finger cancels the unfinished stroke. Reset view returns to the starting position.\n\nShare this board or paste a web/desktop invite to join. Rotating keeps your board, but leaving or the system ending the app discards it. Export and saving are not available yet.\n\nConnection prototype: WebRTC transport encryption only. Invites and peers are not authenticated; application encryption comes later. Don’t use for sensitive content.")
        }, confirmButton = { TextButton(onClick = { dialog = null }) { Text("Back to the board") } })
        if (dialog == "join") AlertDialog(onDismissRequest = { dialog = null }, title = { Text("Join a board") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Joining replaces your current canvas. Paste the full invite, including #room=…")
                OutlinedTextField(inviteInput, { inviteInput = it }, label = { Text("Board invite") }, modifier = Modifier.testTag("mobile-invite-input"))
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = { Button(enabled = inviteInput.isNotBlank(), onClick = { controller.join(inviteInput); view = View(); dialog = null }, modifier = Modifier.testTag("mobile-confirm-join")) { Text("Join board") } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } })
        if (dialog == "share") AlertDialog(onDismissRequest = { dialog = null }, title = { Text(if (state.invite.isEmpty()) "Share this board" else "Invite someone") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.invite.isEmpty()) {
                    Text("Your drawing stays on this board. Use the address of your running Meshboard web app.")
                    OutlinedTextField(origin, { origin = it }, label = { Text("App address") }, modifier = Modifier.testTag("mobile-app-address"))
                    Text("In the local emulator, the default address connects to this computer.", fontSize = 12.sp)
                    Button(onClick = { controller.share(origin) }, modifier = Modifier.testTag("mobile-create-invite")) { Text("Create invite") }
                } else {
                    val qr = remember(state.invite) { qrImage(state.invite) }
                    Image(qr, "Board invite QR code", Modifier.size(180.dp))
                    OutlinedTextField(state.invite, {}, readOnly = true, label = { Text("Board invite") }, modifier = Modifier.testTag("mobile-board-invite"))
                    Button(onClick = { clipboard.setText(AnnotatedString(state.invite)); copied = true }) { Text(if (copied) "Copied" else "Copy invite") }
                    Text(state.connectionLabel, fontSize = 12.sp)
                    Row {
                        TextButton(onClick = { controller.retry() }) { Text("Retry") }
                        TextButton(onClick = { dialog = "leave" }) { Text("Leave board") }
                    }
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text("Prototype: transport encryption only; peers are not authenticated. Don’t share sensitive content.", fontSize = 12.sp)
            }
        }, confirmButton = { TextButton(onClick = { dialog = null }) { Text("Done") } })
        if (dialog == "leave") AlertDialog(onDismissRequest = { dialog = null }, title = { Text("Leave this board?") }, text = { Text("Your local copy will be discarded. Other connected participants can keep drawing.") },
            confirmButton = { Button(onClick = { controller.leave(); view = View(); dialog = null }) { Text("Leave board") } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } })
    }
}

private fun DrawScope.mobileElement(element: BoardElement, alpha: Float = 1f) {
    val color = mobilePaint(element.color).copy(alpha = alpha)
    val a = element.points.first(); val b = element.points.last()
    if (element.points.size == 1) { drawCircle(color, element.width.toFloat() / 2, Offset(a.x.toFloat(), a.y.toFloat())); return }
    val style = Stroke(element.width.toFloat(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    if (element.type == "rectangle" || element.type == "ellipse") {
        val top = Offset(min(a.x, b.x).toFloat(), min(a.y, b.y).toFloat())
        val size = Size(abs(b.x - a.x).toFloat(), abs(b.y - a.y).toFloat())
        if (element.type == "rectangle") drawRect(color, top, size, style = style) else drawOval(color, top, size, style = style)
    } else {
        val path = Path().apply { moveTo(a.x.toFloat(), a.y.toFloat()); element.points.drop(1).forEach { lineTo(it.x.toFloat(), it.y.toFloat()) } }
        drawPath(path, color, style = style)
    }
}
