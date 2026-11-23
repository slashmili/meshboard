package meshboard

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.*

private val MobileInk = Color(0xff293b36)
private val MobilePaper = Color(0xfff8f9f6)
private val MobileMuted = Color(0xff617059)
private val MobilePalette = listOf("Ink" to "#293b36", "Fern" to "#387c59", "Blue" to "#4878c8", "Violet" to "#9563be", "Coral" to "#d76857", "Amber" to "#c49226")
private fun mobilePaint(hex: String) = Color(0xff000000 or hex.drop(1).toLong(16))

/** Touch layout shared by mobile targets; transport is deliberately independent. */
@Composable
fun MobileBoardApp(
    controller: DrawingController,
    defaultOrigin: String = "https://",
    boardTitle: String = "Meshboard",
    uiState: TabletUiState? = null,
    shareInvite: ((String) -> Unit)? = null,
    backHandler: (@Composable (Boolean, () -> Unit) -> Unit)? = null,
    qrImage: ((String) -> ImageBitmap)? = null,
) {
    val sharing = controller as? BoardController
    val state by controller.state.collectAsState()
    val images = rememberBoardImages(state.elements)
    val latest by rememberUpdatedState(state)
    var toolName by rememberSaveable { mutableStateOf(Tool.Pen.name) }
    val tool = Tool.valueOf(toolName)
    var color by rememberSaveable { mutableStateOf("#293b36") }
    var width by rememberSaveable { mutableStateOf(3.0) }
    val localUiState = remember { TabletUiState() }
    val tabletUi = uiState ?: localUiState
    var view by tabletUi.view
    val textMeasurer = rememberTextMeasurer()
    var textEdit by remember { mutableStateOf<BoardElement?>(null) }
    var editingExisting by remember { mutableStateOf(false) }
    var selection by tabletUi.selection
    var draft by remember { mutableStateOf<BoardElement?>(null) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var drawWithFinger by rememberSaveable { mutableStateOf(true) }
    var dockRight by rememberSaveable { mutableStateOf(false) }
    var spacePan by remember { mutableStateOf(false) }
    val controls = remember { mutableStateMapOf<String, Rect>() }
    var canvasBounds by remember { mutableStateOf(Rect.Zero) }
    val focusRequester = remember { FocusRequester() }
    var feedback by remember { mutableStateOf("") }
    var cancelled by remember { mutableIntStateOf(0) }
    var canvasSize by tabletUi.canvasSize
    var errorSize by remember { mutableStateOf(Size.Zero) }
    var origin by remember { mutableStateOf(defaultOrigin) }
    var inviteInput by remember { mutableStateOf("") }
    var copied by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val density = LocalDensity.current.density.toDouble()
    fun cancel() { selection = Selection(selection.ids); draft = null; spacePan = false; cancelled++; controller.preview(null) }
    fun choose(item: Tool) { cancel(); selection = Selection(); toolName = item.name; dialog = null }
    fun open(name: String) { cancel(); dialog = name }
    fun zoom(factor: Double) { cancel(); view = view.zoomAt(Point(canvasSize.width / density / 2, canvasSize.height / density / 2), view.zoom * factor) }

    val inputBlocked = dialog != null || textEdit != null
    backHandler?.invoke(selection.ids.isNotEmpty() && !inputBlocked) { cancel(); selection = Selection() }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    var wasWindowFocused by remember { mutableStateOf(windowFocused) }
    DisposableEffect(windowFocused) {
        if (wasWindowFocused && !windowFocused) { cancel(); spacePan = false }
        wasWindowFocused = windowFocused
        onDispose { }
    }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    LaunchedEffect(state.elements) { selection = selection.copy(ids = selection.ids.filter { id -> state.elements.any { it.id == id } }) }
    val activeTool = if (spacePan) Tool.Hand else tool

    var appearance by rememberAppearance()
    val colors = appearanceColors(appearance)
    BoardTheme(colors) {
        BoxWithConstraints(Modifier.fillMaxSize().background(MobilePaper).focusRequester(focusRequester).onPreviewKeyEvent { event ->
            if (inputBlocked) return@onPreviewKeyEvent false
            if (event.key == Key.Spacebar) { spacePan = event.type == KeyEventType.KeyDown; return@onPreviewKeyEvent true }
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when {
                event.key == Key.Escape -> { cancel(); selection = Selection(); true }
                (event.isCtrlPressed || event.isMetaPressed) && event.key == Key.Z -> {
                    cancel(); if (event.isShiftPressed) controller.redo() else controller.undo(); true
                }
                event.key == Key.Delete || event.key == Key.Backspace -> {
                    if (selection.ids.isNotEmpty()) { controller.remove(selection.ids); selection = Selection(); true } else false
                }
                !event.isCtrlPressed && !event.isMetaPressed && !event.isAltPressed -> {
                    val next = when(event.key) { Key.V -> Tool.Select; Key.P -> Tool.Pen; Key.E -> Tool.Eraser; Key.R -> Tool.Rectangle; Key.O -> Tool.Ellipse; Key.A -> Tool.Arrow; Key.L -> Tool.Line; Key.T -> Tool.Text; Key.N -> Tool.Sticky; Key.H -> Tool.Hand; else -> null }
                    if (next != null) { choose(next); true } else false
                }
                else -> false
            }
        }.focusable()) {
            val layout = tabletLayout(maxWidth.value / LocalDensity.current.fontScale, maxHeight.value)
            Column(Modifier.fillMaxSize()) {
                TabletHeader(boardTitle, state.connectionLabel, layout == TabletLayout.Wide, sharing != null && qrImage != null,
                    onSession = { open("session") }, onShare = { copied = false; open("share") }, onMenu = { open("board") })
                HorizontalDivider(color = colors.outline.copy(alpha = .5f))
                Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                Canvas(Modifier.fillMaxSize().clipToBounds().testTag("mobile-canvas").semantics {
                    contentDescription = "Drawing canvas"
                    traversalIndex = 3f
                    stateDescription = "${state.elements.size} objects"
                }.onGloballyPositioned { canvasBounds = it.boundsInRoot() }.onSizeChanged {
                    val next = Size(it.width.toFloat(), it.height.toFloat())
                    if (canvasSize != Size.Zero && next != canvasSize) {
                        // Preserve the same world point at the viewport center through tablet reflow.
                        view = view.copy(x = view.x + (next.width - canvasSize.width) / (2 * density), y = view.y + (next.height - canvasSize.height) / (2 * density))
                        cancel()
                    }
                    canvasSize = next
                }
                    .pointerInput(activeTool, drawWithFinger, color, width, density, cancelled, inputBlocked) {
                        try {
                            awaitPointerEventScope {
                                var pointer: PointerId? = null
                                var start: Point? = null
                                var arrowEndpoint = 1
                                var editingArrow = false
                                var last: Point? = null
                                var navigating = false
                                var suppressed = false
                                var gestureTool = activeTool
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val pressed = event.changes.filter { it.pressed }
                                    // Captured pointers continue arriving outside the canvas. Cancel
                                    // before applying coordinates to any tool, and drain the gesture
                                    // so re-entry cannot start another stroke or trigger a text edit.
                                    val outside = event.changes.any {
                                        (it.pressed || it.previousPressed) &&
                                            (it.position.x < 0 || it.position.y < 0 ||
                                                it.position.x >= size.width || it.position.y >= size.height)
                                    }
                                    val overControl = event.changes.any { change ->
                                        val at = change.position + canvasBounds.topLeft
                                        controls.values.any { it.contains(at) }
                                    }
                                    val overError = latest.error != null && event.changes.any {
                                        val left = (size.width - errorSize.width) / 2
                                        it.position.x >= left && it.position.x < left + errorSize.width &&
                                            it.position.y >= 0 && it.position.y < errorSize.height
                                    }
                                    if (inputBlocked || outside || overError || overControl || event.changes.any { it.isConsumed }) suppressed = true
                                    if (suppressed) {
                                        selection = Selection(selection.ids); draft = null; pointer = null
                                        navigating = false; controller.preview(null)
                                        suppressed = pressed.isNotEmpty()
                                        event.changes.forEach { it.consume() }; continue
                                    }
                                    if (pressed.size >= 2) {
                                        selection = Selection(selection.ids); draft = null; pointer = null; navigating = true; controller.preview(null)
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
                                        ?: event.changes.firstOrNull { it.changedToDown() } ?: continue
                                    val point = Point(change.position.x / density, change.position.y / density)
                                    if (pointer == null && change.pressed) gestureTool = if (!drawWithFinger && change.type == PointerType.Touch) Tool.Hand else activeTool
                                    val tool = gestureTool
                                    val world = if (tool == Tool.Pen) penSample(view.world(point), change.type, if (change.pressed) change.pressure else (draft?.points?.lastOrNull()?.pressure ?: 1.0).toFloat()) else view.world(point)
                                    if (pointer == null && change.pressed) {
                                        pointer = change.id; start = world; last = point
                                        if (tool == Tool.Select) selection = selection.begin(resolveConnectors(latest.elements), world, 6 / view.zoom)
                                        if (tool != Tool.Select && tool != Tool.Hand && tool != Tool.Eraser && tool != Tool.Text && tool != Tool.Sticky) {
                                            draft = BoardElement(controller.newId(), tool.wire, color, width, if (tool == Tool.Pen) listOf(world) else listOf(world, world))
                                            if (tool == Tool.Arrow) { val arrow = beginArrow(latest.elements, world, 10 / view.zoom, draft!!); draft = arrow.element; arrowEndpoint = arrow.index; editingArrow = arrow.existing } else editingArrow = false
                                        }
                                    }
                                    if (pointer != change.id) continue
                                    when (tool) {
                                        Tool.Select -> selection = selection.copy(end = world)
                                        Tool.Hand -> { val previous = last!!; view = view.copy(x = view.x + point.x - previous.x, y = view.y + point.y - previous.y) }
                                        Tool.Eraser -> {
                                            val from = last?.let(view::world) ?: world
                                            val ids = resolveConnectors(latest.elements).filter { hits(it, from, world, 12 / view.zoom) }.map { it.id }
                                            if (ids.isNotEmpty()) controller.remove(ids)
                                        }
                                        else -> draft?.let { element ->
                                            if (tool == Tool.Arrow) draft = dragArrow(element,arrowEndpoint,world,latest.elements,10/view.zoom)
                                            else if (tool == Tool.Pen && element.points.last() != world) {
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
                                        if (tool == Tool.Select) {
                                            if (selection.moving) controller.move(selection.ids, selection.delta())
                                            selection = selection.finish(resolveConnectors(latest.elements))
                                        }
                                        if ((tool == Tool.Text || tool == Tool.Sticky) && start != null && hypot(world.x - start!!.x, world.y - start!!.y) * view.zoom < 10) {
                                            val existing = latest.elements.lastOrNull { it.text != null && hits(it, world, world, 0.0) }
                                            editingExisting = existing != null
                                            textEdit = existing ?: textElement(controller.newId(), tool.wire, world, color, "")
                                        }
                                        draft?.let { element ->
                                            val a = element.points.first(); val b = element.points.last()
                                            if (element.type == "pen" || hypot(b.x - a.x, b.y - a.y) > 1 / view.zoom) { if (!editingArrow || latest.elements.any { it.id == element.id }) controller.put(element) }
                                        }
                                        draft = null; pointer = null; controller.preview(null)
                                    }
                                    change.consume()
                                }
                            }
                        } finally { selection = Selection(selection.ids); draft = null; controller.preview(null) }
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
                        val displayState = selection.display(state.elements)
                        val displayed = resolveConnectors(displayState).filter { it.id != draft?.id }
                        displayed.forEach { mobileElement(it, textMeasurer, images) }
                        if (tool == Tool.Arrow) displayed.filter { it.type == "arrow" }.flatMap { it.points }.forEach { drawCircle(Color.White, (4/view.zoom).toFloat(), Offset(it.x.toFloat(),it.y.toFloat())); drawCircle(Color(0xff387c59), (4/view.zoom).toFloat(), Offset(it.x.toFloat(),it.y.toFloat()), style=Stroke((1/view.zoom).toFloat())) }
                        if (tool == Tool.Select) drawSelection(selection, displayed, view.zoom)
                        state.previews.forEach { mobileElement(it, textMeasurer, images, .65f) }
                        draft?.let { resolveConnector(it,displayState) }?.let { mobileElement(it, textMeasurer, images) }
                    }
                }
                if (state.elements.isEmpty() && draft == null) Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("A SPACE FOR IDEAS", fontSize = 10.sp, letterSpacing = 1.5.sp, color = MobileMuted)
                    Spacer(Modifier.height(14.dp))
                    Text("What’s on your mind?", fontSize = 26.sp, color = MobileInk)
                    Spacer(Modifier.height(10.dp))
                    Text(if (drawWithFinger) "Draw with one finger. Pan and pinch with two." else "Draw with a stylus. Pan with your fingers.", fontSize = 12.sp, color = MobileMuted)
                }
                state.error?.let { Text(it, Modifier.align(Alignment.TopCenter).onSizeChanged { errorSize = Size(it.width.toFloat(), it.height.toFloat()) }.pointerInput(Unit) {
                    awaitPointerEventScope { while (true) { awaitPointerEvent().changes.forEach { it.consume() } } }
                }.background(Color(0xfffff2d9)).padding(12.dp), fontSize = 12.sp, color = MobileInk) }
                    val wide = layout == TabletLayout.Wide
                    val bottomPadding = 20.dp
                    val dockAlignment = if (dockRight) Alignment.TopEnd else Alignment.TopStart
                    if (wide) {
                        CanvasControl("dock", controls, Modifier.align(dockAlignment).padding(top = 48.dp, start = 16.dp, end = 16.dp)) {
                            TabletDock(layout, tool, ::choose, { open(it) })
                        }
                        CanvasControl("history", controls, Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = bottomPadding)) {
                            TabletHistory(state.canUndo, state.canRedo, { cancel(); controller.undo(); feedback = "Undone" }, { cancel(); controller.redo(); feedback = "Redone" })
                        }
                    } else {
                        CanvasControl("bottom", controls, Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = bottomPadding)) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (layout != TabletLayout.Minimal) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    TabletHistory(state.canUndo, state.canRedo, { cancel(); controller.undo(); feedback = "Undone" }, { cancel(); controller.redo(); feedback = "Redone" })
                                }
                                TabletDock(layout, tool, ::choose, { open(it) })
                            }
                        }
                    }
                    CanvasControl("view", controls, Modifier.align(if (wide) Alignment.BottomEnd else Alignment.TopEnd).padding(16.dp)) {
                        TabletZoom(view.zoom, { zoom(1 / 1.2) }, { open("view") }, { zoom(1.2) })
                    }
                    if (feedback.isNotEmpty()) CanvasControl("feedback", controls, Modifier.align(Alignment.TopCenter).padding(top = 8.dp)) { Text(feedback, Modifier.semantics { liveRegion = LiveRegionMode.Polite }.background(colors.surface, RoundedCornerShape(6.dp)).padding(6.dp), color = colors.onSurface, fontSize = 12.sp) }
                    if (dialog in listOf("board", "settings", "session", "properties", "shapes", "insert", "tools", "objects", "view")) {
                        TabletSheet(dialog!!, wide, { dialog = null }) {
                            when (dialog) {
                                "board" -> {
                                    Text("Board", style = MaterialTheme.typography.titleMedium)
                                    TabletMenuAction("Tool properties", "mobile-tool-properties") { open("properties") }
                                    if (tool == Tool.Select) TabletMenuAction("Objects", "mobile-objects") { open("objects") }
                                    InsertImageButton(controller, { view.world(Point(canvasSize.width / density / 2, canvasSize.height / density / 2)) }, { cancel() }, onComplete = { dialog = null }, label = "Insert image", modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp))
                                    if (sharing != null) TabletMenuAction("Join another board", "mobile-join") { open("join") }
                                    TabletMenuAction("Settings") { open("settings") }
                                    TabletMenuAction("Help", "mobile-help") { open("help") }
                                    TabletMenuAction("Clear board", "mobile-clear", state.elements.isNotEmpty()) { open("clear") }
                                    if (sharing != null && state.invite.isNotEmpty()) TabletMenuAction("Leave board") { open("leave") }
                                }
                                "settings" -> {
                                    Text("Settings", style = MaterialTheme.typography.titleMedium)
                                    Text("Appearance")
                                    AppearanceOptions(appearance) { appearance = it }
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Text("Draw with finger", Modifier.weight(1f))
                                        Switch(drawWithFinger, { drawWithFinger = it }, Modifier.testTag("mobile-finger-drawing"))
                                    }
                                    Text("When off, fingers navigate and a stylus draws. Two fingers always pan and zoom.", style = MaterialTheme.typography.bodySmall)
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Text("Dock on right", Modifier.weight(1f))
                                        Switch(dockRight, { dockRight = it }, Modifier.testTag("mobile-dock-right"))
                                    }
                                }
                                "session" -> {
                                    Text("Session details", style = MaterialTheme.typography.titleMedium)
                                    Text(state.connectionLabel)
                                    Text(if (state.invite.isEmpty()) "Session-only canvas · not saved" else if (state.relayed > 0) "VIA RELAY · encrypted" else "WebRTC · encrypted", Modifier.testTag("mobile-connection-route"))
                                    state.error?.let { Text(it, color = colors.error) }
                                    if (sharing != null) {
                                        TabletMenuAction("Retry connection") { sharing.retry() }
                                        TabletMenuAction("Join another board", "mobile-join") { open("join") }
                                        if (state.invite.isNotEmpty()) TabletMenuAction("Leave board") { open("leave") }
                                    }
                                }
                                "shapes", "insert", "tools" -> {
                                    Text(if (dialog == "shapes") "Shapes" else if (dialog == "insert") "Insert" else "Tools", style = MaterialTheme.typography.titleMedium)
                                    val choices = if (dialog == "shapes") listOf(Tool.Rectangle, Tool.Ellipse, Tool.Line) + if (layout in listOf(TabletLayout.Compact, TabletLayout.Minimal)) listOf(Tool.Arrow) else emptyList()
                                        else if (dialog == "insert") listOf(Tool.Text, Tool.Sticky) else Tool.entries
                                    choices.forEach { item -> TabletMenuAction(item.label, "mobile-tool-${item.wire}", selected = tool == item) { choose(item) } }
                                    if (dialog != "shapes") InsertImageButton(controller, { view.world(Point(canvasSize.width / density / 2, canvasSize.height / density / 2)) }, { cancel() }, onComplete = { dialog = null }, label = "Insert image")
                                    if (dialog == "tools") {
                                        TabletHistory(state.canUndo, state.canRedo, { controller.undo() }, { controller.redo() })
                                        TabletMenuAction("Tool properties") { open("properties") }
                                    }
                                }
                                "properties" -> {
                                    Text(tool.label, style = MaterialTheme.typography.titleMedium)
                                    when (tool) {
                                        Tool.Select -> {
                                            Text("${selection.ids.size} selected", Modifier.testTag("mobile-selection-count"))
                                            TabletMenuAction("Objects") { open("objects") }
                                            TabletMenuAction("Deselect", enabled = selection.ids.isNotEmpty()) { selection = Selection(); dialog = null }
                                            TabletMenuAction("Delete selection", "mobile-delete-selection", selection.ids.isNotEmpty()) { controller.remove(selection.ids); selection = Selection(); dialog = null }
                                            val editable = state.elements.singleOrNull { it.id in selection.ids && it.text != null }
                                            if (selection.ids.size == 1 && editable != null) TabletMenuAction("Edit text") { dialog = null; editingExisting = true; textEdit = editable }
                                        }
                                        Tool.Eraser -> Text("Removes whole objects. Use Undo to restore an erased object.")
                                        Tool.Hand -> { Text("Drag with one finger to pan. Pinch with two fingers to zoom."); TabletMenuAction("View controls") { open("view") } }
                                        else -> {
                                            Text("Color for new marks")
                                            MobilePalette.forEach { (name, hex) ->
                                                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(color == hex, role = Role.RadioButton, onClick = { color = hex }).semantics { contentDescription = "$name stroke" }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                                    Box(Modifier.size(24.dp).background(mobilePaint(hex), CircleShape).border(if (color == hex) 3.dp else 1.dp, colors.onSurface, CircleShape))
                                                    Text(name, Modifier.weight(1f)); if (color == hex) Text("Selected", style = MaterialTheme.typography.labelSmall)
                                                }
                                            }
                                            if (tool != Tool.Text && tool != Tool.Sticky) {
                                                Text("Stroke width")
                                                listOf(2.0, 3.0, 6.0).forEach { value -> TabletMenuAction("${value.toInt()} px stroke", selected = width == value) { width = value } }
                                            } else TabletMenuAction("Add ${if (tool == Tool.Text) "text" else "note"} at center") { dialog = null; editingExisting = false; textEdit = textElement(controller.newId(), tool.wire, view.world(Point(canvasSize.width / density / 2, canvasSize.height / density / 2)), color, "") }
                                        }
                                    }
                                }
                                "objects" -> {
                                    Text("Objects · ${state.elements.size}", style = MaterialTheme.typography.titleMedium)
                                    if (state.elements.isEmpty()) Text("No objects yet")
                                    state.elements.forEachIndexed { index, element ->
                                        val label = "${index + 1}. ${element.type}${element.text?.let { ": ${it.take(50)}" } ?: ""}"
                                        TabletMenuAction(label, selected = element.id in selection.ids) { selection = Selection(if (element.id in selection.ids) selection.ids - element.id else selection.ids + element.id); toolName = Tool.Select.name }
                                    }
                                    Text("${selection.ids.size} selected", Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                                    listOf("Move left" to Point(-10.0, 0.0), "Move right" to Point(10.0, 0.0), "Move up" to Point(0.0, -10.0), "Move down" to Point(0.0, 10.0)).forEach { (label, delta) -> TabletMenuAction(label, enabled = selection.ids.isNotEmpty()) { controller.move(selection.ids, delta); feedback = label } }
                                    TabletMenuAction("Delete selection", "mobile-delete-selection", selection.ids.isNotEmpty()) { controller.remove(selection.ids); selection = Selection() }
                                    TabletMenuAction("Deselect", enabled = selection.ids.isNotEmpty()) { selection = Selection() }
                                }
                                "view" -> {
                                    Text("View", style = MaterialTheme.typography.titleMedium)
                                    TabletMenuAction("Reset view") { cancel(); view = View(); dialog = null }
                                    TabletMenuAction("Fit content", enabled = state.elements.isNotEmpty()) { cancel(); view = fitTabletContent(state.elements, canvasSize.width / density, canvasSize.height / density); dialog = null }
                                }
                            }
                        }
                    }
                }
            }
        }
        textEdit?.let { editing -> TextEditor(editing, deleted = editingExisting && state.elements.none { it.id == editing.id }, onSave = { controller.put(it); textEdit = null; feedback = "Text saved" }, onDismiss = { textEdit = null }) }
        if (dialog == "clear") AlertDialog(onDismissRequest = { dialog = null }, title = { Text("Clear this board?") }, text = { Text("Remove every object${if (state.invite.isNotEmpty()) " for everyone" else ""}? You can undo this action while this session is open.") },
            confirmButton = { Button(onClick = { controller.remove(state.elements.map { it.id }); dialog = null; feedback = "Board cleared" }, modifier = Modifier.testTag("mobile-confirm-clear")) { Text("Clear board") } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } })
        if (dialog == "help") AlertDialog(onDismissRequest = { dialog = null }, title = { Text("A little space for ideas") }, text = {
            Text("Choose a tool, then draw with a finger or stylus. Two fingers pan and pinch to zoom; a second finger cancels the unfinished stroke. Eraser removes whole objects. Select by tapping or dragging a box, then drag to move. Use Objects for accessible selection and step movement. Arrow endpoints attach to objects.\n\nKeyboard: V/P/E/R/O/A/L/T/N/H choose tools. Space-drag pans. Cmd/Ctrl-Z undoes, Shift-Z redoes; Escape dismisses or deselects.\n\nThis board stays in memory only. Leaving or closing the final participant loses it. Invites authenticate peers; board traffic is end-to-end encrypted. Anyone with the link can read, edit and invite others.")
        }, confirmButton = { TextButton(onClick = { dialog = null }) { Text("Back to the board") } })
        if (dialog == "join" && sharing != null) AlertDialog(onDismissRequest = { dialog = null }, title = { Text("Join another board?") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Joining discards your current local copy. Other participants keep drawing. If you are the last participant, this board is lost. Paste the full invite.")
                OutlinedTextField(inviteInput, { inviteInput = it }, label = { Text("Board invite") }, modifier = Modifier.testTag("mobile-invite-input"))
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = { Button(enabled = inviteInput.isNotBlank(), onClick = { sharing.join(inviteInput); selection = Selection(); view = View(); dialog = null }, modifier = Modifier.testTag("mobile-confirm-join")) { Text("Join board") } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } })
        if (dialog == "share" && sharing != null && qrImage != null) AlertDialog(onDismissRequest = { dialog = null }, title = { Text(if (state.invite.isEmpty()) "Share this board" else "Invite someone") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.invite.isEmpty()) {
                    Text("Your drawing stays on this board. Use the address of your running Meshboard web app.")
                    OutlinedTextField(origin, { origin = it }, label = { Text("App address") }, modifier = Modifier.testTag("mobile-app-address"))
                    Text("Everyone needs to be able to reach this address.", fontSize = 12.sp)
                    Button(onClick = { sharing.share(origin) }, modifier = Modifier.testTag("mobile-create-invite")) { Text("Create invite") }
                } else {
                    val qr = remember(state.invite) { qrImage(state.invite) }
                    Image(qr, "Board invite QR code", Modifier.size(180.dp))
                    OutlinedTextField(state.invite, {}, readOnly = true, label = { Text("Board invite") }, modifier = Modifier.testTag("mobile-board-invite"))
                    Button(onClick = { clipboard.setText(AnnotatedString(state.invite)); copied = true }) { Text(if (copied) "Copied" else "Copy invite") }
                    if (shareInvite != null) TextButton(onClick = { shareInvite(state.invite) }, modifier = Modifier.testTag("mobile-system-share")) { Text("Share link…") }
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text("Board traffic is end-to-end encrypted. Anyone with the link can read, edit, and invite others. Keep it private.", fontSize = 12.sp)
            }
        }, confirmButton = { TextButton(onClick = { dialog = null }) { Text("Done") } })
        if (dialog == "leave" && sharing != null) AlertDialog(onDismissRequest = { dialog = null }, title = { Text("Leave this board?") }, text = { Text("Your local copy will be discarded. Other connected participants can keep drawing. If you are the last participant, this board is lost.") },
            confirmButton = { Button(onClick = { sharing.leave(); selection = Selection(); view = View(); dialog = null }) { Text("Leave board") } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } })
    }
}

private fun DrawScope.mobileElement(element: BoardElement, textMeasurer: TextMeasurer, images: Map<String, ImageBitmap?>, alpha: Float = 1f) {
    if (element.image != null) { drawImageElement(element, images, alpha); return }
    if (element.text != null) { drawTextElement(element, textMeasurer, alpha); return }
    val color = mobilePaint(element.color).copy(alpha = alpha)
    if (element.type == "pen" && element.points.any { it.pressure != null }) { drawPressurePen(element, color); return }
    val a = element.points.first(); val b = element.points.last()
    if (element.points.size == 1) { drawCircle(color, element.width.toFloat() / 2, Offset(a.x.toFloat(), a.y.toFloat())); return }
    val style = Stroke(element.width.toFloat(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    if (element.type == "rectangle" || element.type == "ellipse") {
        val top = Offset(min(a.x, b.x).toFloat(), min(a.y, b.y).toFloat())
        val size = Size(abs(b.x - a.x).toFloat(), abs(b.y - a.y).toFloat())
        if (element.type == "rectangle") drawRect(color, top, size, style = style) else drawOval(color, top, size, style = style)
    } else {
        val path = Path().apply { moveTo(a.x.toFloat(), a.y.toFloat()); outline(element).drop(1).forEach { lineTo(it.x.toFloat(), it.y.toFloat()) } }
        drawPath(path, color, style = style)
    }
}
