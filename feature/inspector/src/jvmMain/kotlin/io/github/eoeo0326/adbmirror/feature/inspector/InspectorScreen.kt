package io.github.eoeo0326.adbmirror.feature.inspector

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.eoeo0326.adbmirror.core.domain.model.InspectorFailure
import io.github.eoeo0326.adbmirror.core.domain.model.InspectorProperty
import io.github.eoeo0326.adbmirror.core.domain.model.InspectorStep
import io.github.eoeo0326.adbmirror.core.domain.model.LayoutNode
import java.awt.Cursor
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

@Composable
fun InspectorRoute(viewModel: InspectorViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsState()
    val screenshots by viewModel.screenshots.collectAsState()
    InspectorScreen(state, screenshots, viewModel::onIntent, modifier)
}

/** 화면 캔버스의 확대·이동. 툴바(화면 맞춤·1:1)와 캔버스가 함께 쓴다. */
class ScreenViewport {
    var scale by mutableFloatStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)

    /** 늘 때마다 캔버스가 화면 맞춤을 다시 한다. */
    var fitRequest by mutableIntStateOf(1)
    var actualSizeRequest by mutableIntStateOf(0)
}

@Composable
fun InspectorScreen(state: InspectorState, screenshots: Map<Long, ImageBitmap>, onIntent: (InspectorIntent) -> Unit, modifier: Modifier = Modifier) {
    val viewport = remember { ScreenViewport() }
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            Toolbar(state, viewport, onIntent)
            HorizontalDivider()
            state.composeNotice?.takeIf { state.phase is InspectorPhase.Inspecting }?.let { ComposeBanner(it) }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (val phase = state.phase) {
                    is InspectorPhase.Preparing -> Progress(phase.step?.label() ?: "기기를 준비하는 중")
                    is InspectorPhase.Attaching -> Progress(phase.step?.label() ?: "앱에 연결하는 중")
                    InspectorPhase.ChoosingProcess -> ProcessList(state, onIntent)
                    is InspectorPhase.Failed -> Failure(phase, onIntent)
                    InspectorPhase.Inspecting -> Panes(state, screenshots, viewport, onIntent)
                }
            }
            HorizontalDivider()
            StatusBar(state)
        }
    }
}

// ───────────── 툴바 ─────────────

@Composable
private fun Toolbar(state: InspectorState, viewport: ScreenViewport, onIntent: (InspectorIntent) -> Unit) {
    val inspecting = state.phase is InspectorPhase.Inspecting
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ProcessPicker(state, onIntent)
        FilterChip(selected = state.live, onClick = { onIntent(InspectorIntent.ToggleLive) }, enabled = inspecting, label = { Text(if (state.live) "● 실시간" else "○ 실시간") })
        TextButton(onClick = { onIntent(InspectorIntent.Refresh) }, enabled = inspecting && !state.live) { Text("⟳ 새로 고침") }
        TextButton(onClick = { onIntent(InspectorIntent.ResetCounts) }, enabled = inspecting && state.composeAvailable) { Text("Recomposition 초기화") }
        CheckLabel("시스템 컴포저블", state.showSystem, enabled = inspecting && state.composeAvailable) { onIntent(InspectorIntent.ToggleSystem) }
        CheckLabel("Recomposition 열", state.showRecompositionColumns, enabled = true) { onIntent(InspectorIntent.ToggleRecompositionColumns) }
        TextButton(onClick = { viewport.fitRequest++ }, enabled = inspecting) { Text("화면 맞춤") }
        TextButton(onClick = { viewport.actualSizeRequest++ }, enabled = inspecting) { Text("1:1") }
        Spacer(Modifier.weight(1f))
        SearchField(state.search) { onIntent(InspectorIntent.Search(it)) }
    }
}

@Composable
private fun ProcessPicker(state: InspectorState, onIntent: (InspectorIntent) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true; onIntent(InspectorIntent.ReloadProcesses) }, enabled = state.processes.isNotEmpty() || state.selectedProcess != null) {
            val p = state.selectedProcess
            Text(if (p == null) "프로세스 ▾" else "${p.name} (${p.pid}) ▾", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(220.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (state.processes.isEmpty()) DropdownMenuItem(text = { Text("검사할 수 있는 앱이 없습니다") }, onClick = { open = false }, enabled = false)
            state.processes.forEach { p ->
                DropdownMenuItem(
                    text = { Text("${p.name} (${p.pid})" + if (p.foreground) "  · 포그라운드" else "") },
                    onClick = {
                        open = false
                        if (p != state.selectedProcess) onIntent(InspectorIntent.SelectProcess(p))
                    },
                )
            }
        }
    }
}

@Composable
private fun CheckLabel(label: String, checked: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable(enabled = enabled, onClick = onToggle)) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() }, enabled = enabled)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("검색 ", style = MaterialTheme.typography.bodySmall)
        Box(Modifier.width(180.dp).background(colors.surfaceVariant, MaterialTheme.shapes.small).padding(horizontal = 8.dp, vertical = 6.dp)) {
            BasicTextField(query, onChange, singleLine = true, textStyle = MaterialTheme.typography.bodySmall.copy(color = colors.onSurface), modifier = Modifier.fillMaxWidth())
        }
        TextButton(onClick = { onChange("") }, enabled = query.isNotEmpty()) { Text("지우기") }
    }
}

@Composable
private fun ComposeBanner(reason: String) {
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
        Text("Compose 정보를 볼 수 없습니다: $reason", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
    }
}

// ───────────── 진행·선택·실패 ─────────────

@Composable
private fun Progress(text: String) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        CircularProgressIndicator()
        Spacer(Modifier.height(12.dp))
        Text(text)
    }
}

@Composable
private fun ProcessList(state: InspectorState, onIntent: (InspectorIntent) -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        state.processNotice?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state.processes.isEmpty()) {
            Text("검사할 수 있는 앱이 없습니다. debug 빌드를 실행하세요")
        } else {
            Text("검사할 앱을 고르세요", style = MaterialTheme.typography.titleMedium)
            LazyColumn(Modifier.width(420.dp).weight(1f, fill = false)) {
                items(state.processes, key = { it.pid }) { p ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onIntent(InspectorIntent.SelectProcess(p)) }.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(p.name, modifier = Modifier.weight(1f))
                        if (p.foreground) Text("포그라운드  ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        Text("${p.pid}", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        OutlinedButton(onClick = { onIntent(InspectorIntent.ReloadProcesses) }) { Text("다시 찾기") }
    }
}

@Composable
private fun Failure(phase: InspectorPhase.Failed, onIntent: (InspectorIntent) -> Unit) {
    val (title, showDetail) = when (phase.failure) {
        InspectorFailure.StudioNotFound -> "Android Studio가 필요합니다. 설정 > Android Studio에서 위치를 지정하세요" to false
        InspectorFailure.ApiTooLow -> "Layout Inspector는 Android 10(API 29) 이상에서 동작합니다" to false
        InspectorFailure.AttachFailed -> "앱에 연결하지 못했습니다" to true
        InspectorFailure.ProcessDied -> "앱이 종료됐습니다" to false
        InspectorFailure.Disconnected -> "기기 연결이 끊겼습니다" to true
        InspectorFailure.InspectorFailed -> "Layout Inspector를 시작하지 못했습니다" to true
    }
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (showDetail) phase.detail?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (phase.failure == InspectorFailure.StudioNotFound) Button(onClick = { onIntent(InspectorIntent.OpenSettings) }) { Text("설정 열기") }
            if (phase.failure != InspectorFailure.ApiTooLow) OutlinedButton(onClick = { onIntent(InspectorIntent.Retry) }) { Text("다시 시도") }
        }
    }
}

// ───────────── 세 칸(트리 · 화면 · 속성) ─────────────

@Composable
private fun Panes(state: InspectorState, screenshots: Map<Long, ImageBitmap>, viewport: ScreenViewport, onIntent: (InspectorIntent) -> Unit) {
    var treeWidth by remember { mutableStateOf(400.dp) }
    var propertiesWidth by remember { mutableStateOf(340.dp) }
    Row(Modifier.fillMaxSize()) {
        ComponentTree(state, onIntent, Modifier.width(treeWidth).fillMaxHeight())
        Splitter { treeWidth = (treeWidth + it).coerceIn(200.dp, 640.dp) }
        ScreenCanvas(state, screenshots, viewport, onIntent, Modifier.weight(1f).fillMaxHeight())
        Splitter { propertiesWidth = (propertiesWidth - it).coerceIn(220.dp, 640.dp) }
        PropertiesPanel(state, Modifier.width(propertiesWidth).fillMaxHeight())
    }
}

@Composable
private fun Splitter(onDrag: (Dp) -> Unit) {
    val density = LocalDensity.current
    Box(
        Modifier.width(5.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant)
            .pointerHoverIcon(PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR)))
            .pointerInput(Unit) { detectHorizontalDragGestures { change, amount -> change.consume(); onDrag(with(density) { amount.toDp() }) } },
    )
}

private val COUNT_WIDTH = 52.dp
private val INDENT = 8.dp

/** 깊은 행의 이름이 보이도록 남겨 두는 너비. */
private val NAME_ROOM = 120.dp

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ComponentTree(state: InspectorState, onIntent: (InspectorIntent) -> Unit, modifier: Modifier) {
    val rows = remember(state.roots, state.collapsed, state.search) { TreeRows.visible(state.roots, state.collapsed, state.search) }
    val listState = rememberLazyListState()
    // 화면에서 고른 노드가 보이도록 스크롤한다.
    LaunchedEffect(state.selectedNodeId) {
        val index = rows.indexOfFirst { it.node.id == state.selectedNodeId }
        if (index >= 0 && listState.layoutInfo.visibleItemsInfo.none { it.index == index }) listState.animateScrollToItem(index)
    }
    val showCounts = state.showRecompositionColumns && state.composeAvailable
    // 깊은 트리는 들여쓰기만으로 이름이 패널 밖으로 밀린다. 지금 보이는 행 중 가장 얕은 행이 왼쪽에 오도록
    // 이름 영역을 자동으로 밀고(Recomp·Skip 열은 고정), 가로 스크롤(트랙패드·Shift+휠)로 더 밀거나 당긴다.
    val density = LocalDensity.current
    val visibleDepths by remember(rows) {
        derivedStateOf {
            val depths = listState.layoutInfo.visibleItemsInfo.mapNotNull { rows.getOrNull(it.index)?.depth }
            (depths.minOrNull() ?: 0) to (depths.maxOrNull() ?: 0)
        }
    }
    var nameAreaPx by remember { mutableIntStateOf(0) }
    var manual by remember { mutableFloatStateOf(0f) }
    // 보이는 가장 깊은 행의 이름에 NAME_ROOM을 남기도록 밀되, 보이는 가장 얕은 행의 들여쓰기보다 더 밀지는 않는다.
    val auto = with(density) {
        val (shallowest, deepest) = visibleDepths
        val need = (INDENT * deepest + NAME_ROOM).toPx() - nameAreaPx
        need.coerceIn(0f, (INDENT * shallowest).toPx())
    }
    val maxOffset = with(density) { (INDENT * (rows.maxOfOrNull { it.depth } ?: 0)).toPx() }
    val hOffset = (auto + manual).coerceIn(0f, maxOffset)
    Column(modifier.onPointerEvent(PointerEventType.Scroll) { e ->
        val dx = e.changes.firstOrNull()?.scrollDelta?.x ?: 0f
        if (dx != 0f) manual = (manual + dx * 40f).coerceIn(-auto, maxOffset - auto)
    }) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text("이름", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
            if (showCounts) {
                Text("Recomp", style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.End, modifier = Modifier.width(COUNT_WIDTH))
                Text("Skip", style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.End, modifier = Modifier.width(COUNT_WIDTH))
            }
        }
        HorizontalDivider()
        val countsWidth = if (showCounts) COUNT_WIDTH * 2 else 0.dp
        LazyColumn(Modifier.fillMaxSize().onSizeChanged { nameAreaPx = it.width - with(density) { (countsWidth + 16.dp).roundToPx() } }, state = listState) {
            items(rows, key = { it.node.rootId.toString() + ":" + it.node.id }) { row ->
                TreeRowItem(
                    row = row,
                    hOffset = hOffset,
                    selected = row.node.id == state.selectedNodeId,
                    hovered = row.node.id == state.hoveredNodeId,
                    highlightedAt = state.recentlyRecomposed[row.node.id],
                    showCounts = showCounts,
                    onIntent = onIntent,
                )
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun TreeRowItem(row: TreeRow, hOffset: Float, selected: Boolean, hovered: Boolean, highlightedAt: Long?, showCounts: Boolean, onIntent: (InspectorIntent) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val node = row.node
    // Recomposition이 늘어난 행은 배경을 칠했다가 1초 동안 지운다.
    val flash = remember { Animatable(0f) }
    LaunchedEffect(highlightedAt) {
        if (highlightedAt != null) {
            flash.snapTo(1f)
            flash.animateTo(0f, tween(InspectorReducer.HIGHLIGHT_MS.toInt()))
        }
    }
    val base = when {
        selected -> colors.primaryContainer
        hovered -> colors.surfaceVariant
        else -> Color.Transparent
    }
    Row(
        Modifier.fillMaxWidth()
            .background(base)
            .background(colors.tertiary.copy(alpha = 0.45f * flash.value))
            .clickable { onIntent(InspectorIntent.SelectNode(node.id)) }
            .onPointerEvent(PointerEventType.Enter) { onIntent(InspectorIntent.HoverNode(node.id)) }
            .onPointerEvent(PointerEventType.Exit) { onIntent(InspectorIntent.HoverNode(null)) }
            .padding(end = 8.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val faded = if (node.systemCreated) colors.onSurfaceVariant else colors.onSurface
        Box(Modifier.weight(1f).clipToBounds()) {
            Row(
                Modifier.wrapContentWidth(Alignment.Start, unbounded = true)
                    .offset { IntOffset(-hOffset.roundToInt(), 0) }
                    .padding(start = 8.dp + INDENT * row.depth),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (!row.hasChildren) "  " else if (row.expanded) "▾" else "▸",
                    modifier = Modifier.width(14.dp).clickable(enabled = row.hasChildren) { onIntent(InspectorIntent.ToggleExpanded(node.id)) },
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(node.name, style = MaterialTheme.typography.bodySmall, color = faded, maxLines = 1, softWrap = false)
                node.textValue?.let {
                    Text("  \"${it.take(40)}\"", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, softWrap = false)
                }
            }
        }
        if (showCounts) {
            val isCompose = node.kind == LayoutNode.Kind.Composable
            CountCell(if (isCompose) node.recomposeCount else null)
            CountCell(if (isCompose) node.recomposeSkips else null)
        }
    }
}

@Composable
private fun CountCell(value: Int?) {
    Text(value?.toString().orEmpty(), style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), textAlign = TextAlign.End, modifier = Modifier.width(COUNT_WIDTH))
}

// ───────────── 화면 캔버스 ─────────────

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ScreenCanvas(
    state: InspectorState,
    screenshots: Map<Long, ImageBitmap>,
    viewport: ScreenViewport,
    onIntent: (InspectorIntent) -> Unit,
    modifier: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    var panel by remember { mutableStateOf(IntSize.Zero) }
    val content = remember(state.roots) { contentBounds(state.roots) }
    val currentRoots by rememberUpdatedState(state.roots)
    val fit = { if (panel.width > 0 && content.width > 0) fit(viewport, panel, content) }

    LaunchedEffect(viewport.fitRequest, panel, content.width > 0) { fit() }
    LaunchedEffect(viewport.actualSizeRequest) {
        if (viewport.actualSizeRequest > 0) {
            viewport.scale = 1f
            viewport.offset = Offset((panel.width - content.width) / 2f - content.left, 16f - content.top)
        }
    }

    Box(modifier.background(colors.surfaceVariant.copy(alpha = 0.4f)).onSizeChanged { panel = it }) {
        Canvas(
            Modifier.fillMaxSize()
                // 클릭 = 가장 깊은 노드 선택, 드래그 = 이동, 휠 = 커서 기준 확대, 움직임 = hover.
                .pointerInput(viewport) { detectTapGestures { p -> onIntent(InspectorIntent.SelectNode(viewport.hit(currentRoots, p))) } }
                .pointerInput(viewport) { detectDragGestures { change, drag -> change.consume(); viewport.offset += drag } }
                .onPointerEvent(PointerEventType.Move) { e -> e.changes.firstOrNull()?.let { onIntent(InspectorIntent.HoverNode(viewport.hit(currentRoots, it.position))) } }
                .onPointerEvent(PointerEventType.Exit) { onIntent(InspectorIntent.HoverNode(null)) }
                .onPointerEvent(PointerEventType.Scroll) { e -> e.changes.firstOrNull()?.let { viewport.zoom(it.scrollDelta.y, it.position) } },
        ) {
            withTransform({
                translate(viewport.offset.x, viewport.offset.y)
                scale(viewport.scale, viewport.scale, pivot = Offset.Zero)
            }) {
                val stroke = 1f / viewport.scale
                state.roots.forEach { root ->
                    val b = root.bounds
                    screenshots[root.id]?.let { image ->
                        drawImage(image, dstOffset = IntOffset(b.x, b.y), dstSize = IntSize(b.width, b.height))
                    }
                    root.flatten().forEach { n ->
                        drawRect(colors.outline.copy(alpha = 0.35f), Offset(n.bounds.x.toFloat(), n.bounds.y.toFloat()), Size(n.bounds.width.toFloat(), n.bounds.height.toFloat()), style = Stroke(stroke))
                    }
                }
                state.hoveredNodeId?.let { id ->
                    state.roots.firstNotNullOfOrNull { it.find(id) }?.let { n ->
                        drawRect(colors.secondary, Offset(n.bounds.x.toFloat(), n.bounds.y.toFloat()), Size(n.bounds.width.toFloat(), n.bounds.height.toFloat()), style = Stroke(2.5f * stroke))
                    }
                }
                state.selectedNode?.let { n ->
                    val topLeft = Offset(n.bounds.x.toFloat(), n.bounds.y.toFloat())
                    drawRect(colors.primary, topLeft, Size(n.bounds.width.toFloat(), n.bounds.height.toFloat()), style = Stroke(3f * stroke))
                    val label = measurer.measure(n.name, TextStyle(fontSize = (12f / viewport.scale).coerceAtLeast(1f).sp, color = colors.onPrimary))
                    val labelTop = Offset(topLeft.x, (topLeft.y - label.size.height).coerceAtLeast(0f))
                    drawRect(colors.primary, labelTop, Size(label.size.width.toFloat() + 8 * stroke, label.size.height.toFloat()))
                    drawText(label, topLeft = labelTop + Offset(4 * stroke, 0f))
                }
            }
        }
        val drawable = state.roots.any { it.bounds.width > 0 && it.bounds.height > 0 }
        when {
            state.lastUpdatedMs == null -> Text("화면을 받는 중…", modifier = Modifier.align(Alignment.Center), color = colors.onSurfaceVariant)
            !drawable -> Text(
                "보이는 창이 없습니다. 앱이 백그라운드에 있으면 기기에서 앱을 앞으로 가져오세요",
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private class ContentBounds(val left: Float, val top: Float, val width: Float, val height: Float)

private fun contentBounds(roots: List<LayoutNode>): ContentBounds {
    if (roots.isEmpty()) return ContentBounds(0f, 0f, 0f, 0f)
    val left = roots.minOf { it.bounds.x }
    val top = roots.minOf { it.bounds.y }
    val right = roots.maxOf { it.bounds.x + it.bounds.width }
    val bottom = roots.maxOf { it.bounds.y + it.bounds.height }
    return ContentBounds(left.toFloat(), top.toFloat(), (right - left).toFloat(), (bottom - top).toFloat())
}

private fun fit(viewport: ScreenViewport, panel: IntSize, content: ContentBounds) {
    val margin = 16f
    val scale = minOf((panel.width - 2 * margin) / content.width, (panel.height - 2 * margin) / content.height).coerceIn(MIN_SCALE, MAX_SCALE)
    viewport.scale = scale
    viewport.offset = Offset(
        (panel.width - content.width * scale) / 2f - content.left * scale,
        (panel.height - content.height * scale) / 2f - content.top * scale,
    )
}

private const val MIN_SCALE = 0.05f
private const val MAX_SCALE = 8f

/** 캔버스 좌표 → 기기 좌표에서 가장 깊은 노드. */
private fun ScreenViewport.hit(roots: List<LayoutNode>, p: Offset): Long? =
    NodeHitTest.hit(roots, ((p.x - offset.x) / scale).toInt(), ((p.y - offset.y) / scale).toInt())

/** 커서 아래 점이 그대로 있도록 확대·축소한다. */
private fun ScreenViewport.zoom(wheel: Float, cursor: Offset) {
    if (wheel == 0f) return
    val newScale = (scale * if (wheel > 0) 0.9f else 1.1f).coerceIn(MIN_SCALE, MAX_SCALE)
    val anchor = Offset((cursor.x - offset.x) / scale, (cursor.y - offset.y) / scale)
    scale = newScale
    offset = Offset(cursor.x - anchor.x * newScale, cursor.y - anchor.y * newScale)
}

// ───────────── 속성 ─────────────

@Composable
private fun PropertiesPanel(state: InspectorState, modifier: Modifier) {
    val node = state.selectedNode
    Column(modifier.padding(8.dp)) {
        if (node == null) {
            Text("트리나 화면에서 노드를 고르세요", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        Text(node.name, style = MaterialTheme.typography.titleSmall)
        val source = node.sourceFile?.let { "$it:${node.sourceLine ?: "?"}" } ?: node.qualifiedName
        source?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (node.kind == LayoutNode.Kind.Composable && node.recomposeCount != null) {
            Text("Recomposition ${node.recomposeCount} / Skip ${node.recomposeSkips ?: 0}", style = MaterialTheme.typography.bodySmall)
        }
        Text("${node.bounds.x}, ${node.bounds.y}  ${node.bounds.width}×${node.bounds.height}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        when (val p = state.properties) {
            is PropertiesState.Loading, PropertiesState.None -> Text("불러오는 중…", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            is PropertiesState.Failed -> Text("속성을 받지 못했습니다: ${p.message}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            is PropertiesState.Loaded -> PropertyGroups(p.nodeId, p.properties)
        }
    }
}

@Composable
private fun PropertyGroups(nodeId: Long, properties: List<InspectorProperty>) {
    val groups = remember(properties) { properties.groupBy { it.group } }
    var closedGroups by remember(nodeId) { mutableStateOf(emptySet<String>()) }
    var openItems by remember(nodeId) { mutableStateOf(emptySet<String>()) }
    LazyColumn(Modifier.fillMaxSize()) {
        groups.forEach { (group, items) ->
            item(key = "g:$group") {
                val open = group !in closedGroups
                Text(
                    (if (open) "▾ " else "▸ ") + group,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.fillMaxWidth().clickable { closedGroups = if (open) closedGroups + group else closedGroups - group }.padding(vertical = 6.dp),
                )
            }
            if (group !in closedGroups) {
                val rows = ArrayList<Pair<String, Pair<InspectorProperty, Int>>>()
                fun add(p: InspectorProperty, path: String, depth: Int) {
                    rows += path to (p to depth)
                    if (path in openItems) p.children.forEachIndexed { i, c -> add(c, "$path/$i", depth + 1) }
                }
                items.forEachIndexed { i, p -> add(p, "$group/$i", 0) }
                items(rows, key = { it.first }) { (path, pair) ->
                    val (p, depth) = pair
                    val expandable = p.children.isNotEmpty()
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable(enabled = expandable) { openItems = if (path in openItems) openItems - path else openItems + path }
                            .padding(start = (depth * 12).dp, top = 2.dp, bottom = 2.dp),
                    ) {
                        Text(
                            (if (!expandable) "" else if (path in openItems) "▾ " else "▸ ") + p.name,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(0.45f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(p.value, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), modifier = Modifier.weight(0.55f))
                    }
                }
            }
        }
    }
}

// ───────────── 상태줄 ─────────────

private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

@Composable
private fun StatusBar(state: InspectorState) {
    val parts = buildList {
        add(
            when (val phase = state.phase) {
                InspectorPhase.Inspecting -> "연결됨"
                is InspectorPhase.Preparing -> phase.step?.label() ?: "준비 중"
                is InspectorPhase.Attaching -> phase.step?.label() ?: "연결 중"
                InspectorPhase.ChoosingProcess -> "프로세스 선택"
                is InspectorPhase.Failed -> "연결 안 됨"
            },
        )
        state.studioLabel?.let(::add)
        state.composeVersion?.let { add("Compose inspector $it") }
        state.lastUpdatedMs?.let { add("마지막 갱신 ${TIME.format(Instant.ofEpochMilli(it))}") }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(parts.joinToString(" · "), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f), maxLines = 1)
        state.deviceError?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        }
    }
}

internal fun InspectorStep.label(): String = when (this) {
    InspectorStep.LocatingStudio -> "Android Studio를 찾는 중"
    InspectorStep.PushingFiles -> "기기에 파일을 올리는 중"
    InspectorStep.StartingDaemon -> "transport 데몬을 시작하는 중"
    InspectorStep.Attaching -> "앱에 에이전트를 붙이는 중"
    InspectorStep.LoadingInspectors -> "inspector를 불러오는 중"
    is InspectorStep.DownloadingCompose -> "Compose inspector $version 을 내려받는 중"
}
