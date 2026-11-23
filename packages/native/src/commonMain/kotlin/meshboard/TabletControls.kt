package meshboard

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Window classes concern available tablet space, including multitasking and text scaling. */
internal enum class TabletLayout { Wide, Medium, Compact, Minimal }
internal fun tabletLayout(width: Float, height: Float) = when {
    width < 400 || height < 420 -> TabletLayout.Minimal
    width >= 1000 && height >= 600 -> TabletLayout.Wide
    width >= 700 -> TabletLayout.Medium
    else -> TabletLayout.Compact
}

internal fun fitTabletContent(elements: List<BoardElement>, width: Double, height: Double): View {
    if (elements.isEmpty() || width <= 0 || height <= 0) return View()
    val boxes = resolveConnectors(elements).map(::selectionBounds)
    if (boxes.isEmpty()) return View()
    val left = boxes.minOf { it.x }; val top = boxes.minOf { it.y }
    val right = boxes.maxOf { it.x + it.width }; val bottom = boxes.maxOf { it.y + it.height }
    // Reserve space for floating controls on each edge, including the bottom dock.
    val zoom = minOf((width - 192).coerceAtLeast(48.0) / (right - left).coerceAtLeast(1.0),
        (height - 256).coerceAtLeast(48.0) / (bottom - top).coerceAtLeast(1.0)).coerceIn(.25, 4.0)
    return View(width / 2 - (left + right) * zoom / 2, (height - 96) / 2 - (top + bottom) * zoom / 2, zoom)
}

@Composable
private fun Modifier.tabletFocus(): Modifier {
    var focused by remember { mutableStateOf(false) }
    return onFocusChanged { focused = it.isFocused }.then(if (focused) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)) else Modifier)
}

@Composable
internal fun CanvasControl(name: String, bounds: MutableMap<String, Rect>, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    DisposableEffect(name) { onDispose { bounds.remove(name) } }
    Box(modifier) {
        Box(Modifier.onGloballyPositioned { bounds[name] = it.boundsInRoot() }.pointerInput(Unit) {
            // Standard tap detection lets nested buttons/scrolling own their gestures.
            // Blank chrome captures its down too, so it cannot start canvas input.
            detectTapGestures(onTap = {})
        }) { content() }
    }
}

@Composable
private fun TabletSurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(modifier, shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = .55f)), shadowElevation = 3.dp, content = content)
}

@Composable
internal fun TabletHeader(title: String, status: String, wide: Boolean, sharing: Boolean, onSession: () -> Unit, onShare: () -> Unit, onMenu: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).background(colors.surface).padding(horizontal = 12.dp).testTag("mobile-header").semantics { isTraversalGroup = true; traversalIndex = 0f },
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        MeshboardLogo(Modifier.size(28.dp))
        Column(Modifier.weight(1f).heightIn(min = 48.dp).clickable(onClickLabel = "Session details", onClick = onSession).padding(vertical = 6.dp)) {
            Text(title, color = colors.primary, fontSize = 15.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(status, Modifier.testTag("mobile-connection-status"), color = colors.onSurfaceVariant, fontSize = 10.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (wide) HeaderAction("Session details", false, "mobile-session", onSession)
        if (sharing) HeaderAction("Share", true, "mobile-share", onShare)
        IconButton(onMenu, Modifier.tabletFocus().size(48.dp).testTag("mobile-more").semantics { contentDescription = "Board options" }) { TabletIcon("menu", colors.onSurface) }
    }
}

@Composable
private fun HeaderAction(label: String, primary: Boolean, tag: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    // Visual surfaces remain compact; the independent 48-unit button owns touch/focus.
    TextButton(onClick, Modifier.tabletFocus().heightIn(min = 48.dp).testTag(tag), contentPadding = PaddingValues(horizontal = if (primary) 4.dp else 2.dp), shape = RoundedCornerShape(6.dp)) {
        Box(Modifier.widthIn(min = if (primary) 70.dp else 0.dp).heightIn(min = 32.dp).background(if (primary) colors.primaryContainer else colors.surfaceVariant.copy(alpha = .3f), RoundedCornerShape(6.dp))
            .then(if (primary) Modifier else Modifier.border(1.dp, colors.outline, RoundedCornerShape(6.dp))).padding(horizontal = 12.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
            Text(label, fontSize = 12.sp, color = if (primary) colors.onPrimaryContainer else colors.onSurface, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
internal fun TabletDock(layout: TabletLayout, tool: Tool, choose: (Tool) -> Unit, open: (String) -> Unit) {
    val wide = layout == TabletLayout.Wide
    val compact = layout == TabletLayout.Compact
    val minimal = layout == TabletLayout.Minimal
    val shapes = tool in listOf(Tool.Rectangle, Tool.Ellipse, Tool.Line) || (compact && tool == Tool.Arrow)
    val insert = tool == Tool.Text || tool == Tool.Sticky
    val slots: @Composable () -> Unit = {
        DockButton("select", "Select", tool == Tool.Select, "mobile-tool-select") { choose(Tool.Select) }
        if (minimal) {
            val current = if (tool == Tool.Hand || tool == Tool.Select) Tool.Pen else tool
            DockButton(current.wire, current.label, tool == current, "mobile-tool-${current.wire}") { choose(current) }
            DockButton("shapes", "Tools", false, "mobile-tools") { open("tools") }
        } else {
            DockButton("pen", "Pen", tool == Tool.Pen, "mobile-tool-pen") { choose(Tool.Pen) }
            DockButton("eraser", "Whole-object eraser", tool == Tool.Eraser, "mobile-tool-eraser") { choose(Tool.Eraser) }
            DockButton(if (shapes) tool.wire else "shapes", if (shapes) "Shapes: ${tool.label}" else "Shapes", shapes, "mobile-shapes") { open("shapes") }
            if (compact) DockButton(if (insert) tool.wire else "insert", if (insert) "Insert: ${tool.label}" else "Insert", insert, "mobile-insert") { open("insert") }
            else {
                DockButton("arrow", "Arrow", tool == Tool.Arrow, "mobile-tool-arrow") { choose(Tool.Arrow) }
                DockButton("text", "Text", tool == Tool.Text, "mobile-tool-text") { choose(Tool.Text) }
                DockButton("sticky", "Sticky note", tool == Tool.Sticky, "mobile-tool-sticky") { choose(Tool.Sticky) }
            }
        }
        DockButton("hand", "Pan", tool == Tool.Hand, "mobile-tool-hand") { choose(Tool.Hand) }
    }
    TabletSurface(Modifier.testTag(if (wide) "tablet-side-dock" else "tablet-bottom-dock").semantics { isTraversalGroup = true; traversalIndex = 1f }) {
        if (wide) Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { slots() }
        else Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { slots() }
    }
}

@Composable
private fun DockButton(icon: String, label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    TextButton(onClick, Modifier.tabletFocus().size(48.dp).testTag(tag).semantics { contentDescription = label; this.selected = selected }, shape = RoundedCornerShape(10.dp), contentPadding = PaddingValues(0.dp)) {
        Box(Modifier.fillMaxSize().background(if (selected) colors.primaryContainer else Color.Transparent, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            TabletIcon(icon, if (selected) colors.onPrimaryContainer else colors.onSurface)
        }
    }
}

@Composable
internal fun TabletHistory(canUndo: Boolean, canRedo: Boolean, undo: () -> Unit, redo: () -> Unit) {
    TabletSurface {
        Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(undo, Modifier.tabletFocus().size(48.dp).testTag("mobile-undo").semantics { contentDescription = "Undo" }, enabled = canUndo) { TabletIcon("undo", LocalContentColor.current) }
            IconButton(redo, Modifier.tabletFocus().size(48.dp).testTag("mobile-redo").semantics { contentDescription = "Redo" }, enabled = canRedo) { TabletIcon("redo", LocalContentColor.current) }
        }
    }
}

@Composable
internal fun TabletZoom(zoom: Double, out: () -> Unit, menu: () -> Unit, into: () -> Unit) {
    TabletSurface(Modifier.semantics { isTraversalGroup = true; traversalIndex = 4f }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(out, Modifier.tabletFocus().size(48.dp).testTag("mobile-zoom-out").semantics { contentDescription = "Zoom out" }, enabled = zoom > .25) { Text("−", fontSize = 20.sp) }
            TextButton(menu, Modifier.tabletFocus().sizeIn(minWidth = 56.dp, minHeight = 48.dp).testTag("mobile-zoom").semantics { contentDescription = "Zoom ${(zoom * 100).toInt()} percent, view options" }) { Text("${kotlin.math.round(zoom * 100).toInt()}%", fontSize = 12.sp) }
            IconButton(into, Modifier.tabletFocus().size(48.dp).testTag("mobile-zoom-in").semantics { contentDescription = "Zoom in" }, enabled = zoom < 4) { Text("+", fontSize = 20.sp) }
        }
    }
}

@Composable
internal fun TabletMenuAction(label: String, tag: String = "", enabled: Boolean = true, selected: Boolean = false, action: () -> Unit) {
    TextButton(action, Modifier.tabletFocus().fillMaxWidth().heightIn(min = 48.dp).then(if (tag.isEmpty()) Modifier else Modifier.testTag(tag)).semantics { this.selected = selected }, enabled = enabled, contentPadding = PaddingValues(horizontal = 12.dp)) {
        Text(label, Modifier.weight(1f), color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = .5f))
        if (selected) Text("✓", Modifier.semantics { contentDescription = "Selected" })
    }
}

/** Modal, scrollable tools/settings surface; Dialog provides focus confinement and Back/Escape. */
@Composable
internal fun TabletSheet(name: String, wide: Boolean, dismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .25f)).clickable(onClickLabel = "Close panel", onClick = dismiss).testTag("tablet-scrim")) {
            Surface(Modifier.align(if (wide) Alignment.Center else Alignment.BottomCenter).padding(16.dp).widthIn(max = if (wide) 320.dp else 680.dp).fillMaxWidth().heightIn(max = maxHeight * .6f)
                .pointerInput(Unit) { detectTapGestures(onTap = {}) }.testTag("tablet-panel-$name"),
                color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
                    TextButton(dismiss, Modifier.align(Alignment.End).heightIn(min = 48.dp)) { Text("Done") }
                }
            }
        }
    }
}

@Composable
private fun TabletIcon(name: String, color: Color) {
    Canvas(Modifier.size(24.dp)) {
        val scale = size.width / 48
        fun line(vararg points: Float) {
            val path = Path().apply { moveTo(points[0] * scale, points[1] * scale); var i = 2; while (i < points.size) { lineTo(points[i] * scale, points[i+1] * scale); i += 2 } }
            drawPath(path, color, style = Stroke(2 * scale, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        when(name) {
            "select" -> line(15f,11f,15f,34f,21f,28f,27f,37f,32f,34f,26f,25f,35f,25f,15f,11f)
            "pen" -> { line(13f,35f,16f,26f,32f,10f,39f,17f,23f,33f,13f,35f); line(16f,26f,23f,33f) }
            "eraser" -> { line(11f,28f,27f,12f,39f,24f,25f,38f,20f,38f,11f,28f); line(19f,20f,31f,32f); line(25f,38f,40f,38f) }
            "rectangle", "shapes" -> { line(11f,11f,35f,11f,35f,35f,11f,35f,11f,11f); if (name == "shapes") drawCircle(color, 8 * scale, Offset(34 * scale,34 * scale), style = Stroke(2 * scale)) }
            "ellipse" -> drawOval(color, Offset(9 * scale, 13 * scale), androidx.compose.ui.geometry.Size(30 * scale, 22 * scale), style = Stroke(2 * scale))
            "arrow" -> { line(12f,36f,36f,12f); line(22f,12f,36f,12f,36f,26f) }
            "line" -> line(12f,36f,36f,12f)
            "text" -> { line(12f,13f,36f,13f); line(24f,13f,24f,37f); line(19f,37f,29f,37f) }
            "sticky" -> { line(12f,11f,37f,11f,37f,29f,28f,38f,12f,38f,12f,11f); line(28f,38f,28f,29f,37f,29f) }
            "hand" -> line(14f,25f,14f,14f,20f,14f,20f,23f,20f,9f,26f,9f,26f,23f,26f,12f,32f,12f,32f,25f,38f,21f,39f,25f,35f,35f,30f,39f,21f,37f,10f,28f,10f,24f,14f,25f)
            "insert" -> { line(10f,24f,38f,24f); line(24f,10f,24f,38f) }
            "undo" -> { line(14f,14f,14f,26f,26f,26f); line(14f,26f,24f,17f,34f,18f,39f,28f) }
            "redo" -> { line(34f,14f,34f,26f,22f,26f); line(34f,26f,24f,17f,14f,18f,9f,28f) }
            else -> listOf(14f,24f,34f).forEach { drawCircle(color, 2 * scale, Offset(it * scale, 24 * scale)) }
        }
    }
}
