package io.github.eoeo0326.adbmirror

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.withContext
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

/** 저장해 둔 창 위치(dp). 크기는 기억하는 창만 있다. */
data class SavedBounds(val x: Int, val y: Int, val width: Int? = null, val height: Int? = null)

/**
 * 창 위치·크기를 `windows.properties`(설정 파일과 같은 폴더)에 둔다. 키는 창 종류(`list`, `mirror.<serial>`)다.
 * 읽지 못하는 값은 없던 것으로 친다.
 */
class WindowBoundsStore(private val file: File) {
    private val props = Properties().apply {
        if (file.isFile) runCatching { file.reader(Charsets.UTF_8).use { load(it) } }
    }

    @Synchronized
    fun get(key: String): SavedBounds? {
        val x = props.getProperty("$key.x")?.toIntOrNull() ?: return null
        val y = props.getProperty("$key.y")?.toIntOrNull() ?: return null
        return SavedBounds(x, y, props.getProperty("$key.width")?.toIntOrNull(), props.getProperty("$key.height")?.toIntOrNull())
    }

    @Synchronized
    fun put(key: String, bounds: SavedBounds) {
        props["$key.x"] = bounds.x.toString()
        props["$key.y"] = bounds.y.toString()
        bounds.width?.let { props["$key.width"] = it.toString() }
        bounds.height?.let { props["$key.height"] = it.toString() }
        try {
            val dir = file.absoluteFile.parentFile.also { it.mkdirs() }
            val tmp = File.createTempFile("windows", ".tmp", dir)
            try {
                tmp.writer(Charsets.UTF_8).use { props.store(it, "ADB Mirror window bounds") }
                try {
                    Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                tmp.delete()
            }
        } catch (e: Exception) {
            System.err.println("창 위치를 저장하지 못했습니다(${file.path}): ${e.message}")
        }
    }
}

/**
 * 저장된 창의 윗부분(제목 표시줄 쪽 [GRAB_W]×[GRAB_H])이 어느 화면에든 보이면 true.
 * 모니터를 떼어 냈거나 배치가 바뀌어 창을 잡을 수 없는 위치면 false로 기본 위치에 연다.
 */
fun isReachable(bounds: SavedBounds, screens: List<Rectangle>, fallbackWidth: Int): Boolean {
    val w = bounds.width ?: fallbackWidth
    val grab = Rectangle(bounds.x, bounds.y, w, GRAB_H)
    return screens.any { screen -> screen.intersection(grab).let { !it.isEmpty && it.width >= minOf(GRAB_W, w) && it.height >= GRAB_H } }
}

private const val GRAB_W = 100
private const val GRAB_H = 40

private fun screenBounds(): List<Rectangle> =
    runCatching { GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { it.defaultConfiguration.bounds } }.getOrDefault(emptyList())

/** 저장된 위치가 쓸 만하면 그 위치로, 아니면 플랫폼 기본 위치로 창 상태를 만든다. */
fun restoredWindowState(store: WindowBoundsStore, key: String, defaultSize: DpSize, rememberSize: Boolean): WindowState {
    val saved = store.get(key)?.takeIf { isReachable(it, screenBounds(), defaultSize.width.value.toInt()) }
        ?: return WindowState(size = defaultSize, position = WindowPosition.PlatformDefault)
    val size = if (rememberSize && saved.width != null && saved.height != null) DpSize(saved.width.dp, saved.height.dp) else defaultSize
    return WindowState(size = size, position = WindowPosition.Absolute(saved.x.dp, saved.y.dp))
}

/** 창을 옮기거나 크기를 바꾸면 잠시 뒤 저장하고, 창이 닫힐 때 한 번 더 저장한다. */
@OptIn(FlowPreview::class)
@Composable
fun RememberWindowBounds(state: WindowState, store: WindowBoundsStore, key: String, rememberSize: Boolean) {
    fun current(): SavedBounds? {
        val p = state.position as? WindowPosition.Absolute ?: return null
        return SavedBounds(
            p.x.value.toInt(), p.y.value.toInt(),
            state.size.width.value.toInt().takeIf { rememberSize }, state.size.height.value.toInt().takeIf { rememberSize },
        )
    }
    LaunchedEffect(state, key) {
        snapshotFlow { current() }.debounce(500).collect { b -> if (b != null) withContext(Dispatchers.IO) { store.put(key, b) } }
    }
    DisposableEffect(state, key) {
        onDispose { current()?.let { store.put(key, it) } }
    }
}
