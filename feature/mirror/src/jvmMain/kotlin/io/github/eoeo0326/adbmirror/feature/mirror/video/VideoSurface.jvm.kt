package io.github.eoeo0326.adbmirror.feature.mirror.video

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import io.github.eoeo0326.adbmirror.core.domain.model.MirrorSession
import io.github.eoeo0326.adbmirror.core.domain.model.TouchAction
import io.github.eoeo0326.adbmirror.core.domain.model.VideoSize
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import java.util.concurrent.Executors
import kotlin.math.roundToInt

@Composable
actual fun VideoSurface(
    session: MirrorSession,
    videoSize: VideoSize?,
    onTouch: (TouchAction, Int, Int) -> Unit,
    modifier: Modifier,
) {
    val store = remember(session) { FrameStore() }
    val version by store.version.collectAsState()
    val touch by rememberUpdatedState(onTouch)

    // FFmpeg 디코더는 한 스레드에서만 쓴다. 세션이 바뀌거나 화면을 떠나면 닫는다.
    val decoderThread = remember(session) { Executors.newSingleThreadExecutor { Thread(it, "ffmpeg-decoder").apply { isDaemon = true } } }
    DisposableEffect(session) { onDispose { decoderThread.shutdownNow() } }
    LaunchedEffect(session) {
        withContext(decoderThread.asCoroutineDispatcher()) {
            FfmpegH264Decoder().use { decoder ->
                val stats = if (System.getenv("ADB_MIRROR_STATS") == "1") FpsLogger() else null
                session.packets.collect { packet ->
                    decoder.decode(packet.data) { w, h, bgra ->
                        store.publish(w, h, bgra)
                        stats?.frame()
                    }
                }
            }
        }
    }

    Canvas(
        modifier.background(Color.Black).pointerInput(videoSize) {
            val video = videoSize ?: return@pointerInput
            awaitPointerEventScope {
                var pressed = false
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull() ?: continue
                    val (w, h) = size.width.toFloat() to size.height.toFloat()
                    when (event.type) {
                        PointerEventType.Press -> videoPoint(change.position.x, change.position.y, w, h, video, clamp = false)?.let { (x, y) ->
                            pressed = true
                            touch(TouchAction.Down, x, y)
                        }
                        PointerEventType.Move -> if (pressed) {
                            videoPoint(change.position.x, change.position.y, w, h, video, clamp = true)?.let { (x, y) -> touch(TouchAction.Move, x, y) }
                        }
                        PointerEventType.Release -> if (pressed) {
                            pressed = false
                            videoPoint(change.position.x, change.position.y, w, h, video, clamp = true)?.let { (x, y) -> touch(TouchAction.Up, x, y) }
                        }
                    }
                }
            }
        },
    ) {
        if (version == 0L) return@Canvas
        val bitmap = store.latestBitmap() ?: return@Canvas
        val r = fitRect(size.width, size.height, VideoSize(bitmap.width, bitmap.height))
        drawImage(
            bitmap.asComposeImageBitmap(),
            dstOffset = IntOffset(r.left.roundToInt(), r.top.roundToInt()),
            dstSize = IntSize(r.width.roundToInt(), r.height.roundToInt()),
        )
    }
}

/** 디코더 스레드가 쓴 최신 프레임을 UI가 가져가는 곳. 늦게 그리면 중간 프레임은 건너뛴다. */
private class FrameStore {
    private val lock = Any()
    private var pending = ByteArray(0)
    private var width = 0
    private var height = 0
    private var dirty = false
    private var bitmap: Bitmap? = null

    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version

    fun publish(w: Int, h: Int, bgra: ByteArray) {
        synchronized(lock) {
            if (pending.size != bgra.size) pending = ByteArray(bgra.size)
            bgra.copyInto(pending)
            width = w
            height = h
            dirty = true
        }
        _version.value++
    }

    /** UI 스레드에서만 부른다. 새 프레임이 있으면 비트맵에 올린다. */
    fun latestBitmap(): Bitmap? = synchronized(lock) {
        if (dirty) {
            val info = ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)
            val b = bitmap?.takeIf { it.width == width && it.height == height } ?: Bitmap().also {
                bitmap?.close()
                bitmap = it
            }
            b.installPixels(info, pending, width * 4)
            dirty = false
        }
        bitmap
    }
}

private class FpsLogger {
    private var count = 0
    private var windowStart = System.nanoTime()

    fun frame() {
        count++
        val now = System.nanoTime()
        if (now - windowStart >= 1_000_000_000L) {
            println("decode fps=$count")
            count = 0
            windowStart = now
        }
    }
}
