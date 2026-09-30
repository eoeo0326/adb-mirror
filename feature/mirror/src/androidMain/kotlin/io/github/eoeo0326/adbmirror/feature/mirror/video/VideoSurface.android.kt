package io.github.eoeo0326.adbmirror.feature.mirror.video

import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.viewinterop.AndroidView
import io.github.eoeo0326.adbmirror.core.domain.model.MirrorSession
import io.github.eoeo0326.adbmirror.core.domain.model.TouchAction
import io.github.eoeo0326.adbmirror.core.domain.model.VideoSize

/**
 * MediaCodec이 SurfaceView에 바로 그린다(CPU 색 변환·복사 없음). SurfaceView는 영상 비율로 가운데에 두고,
 * 그 위에 겹친 투명 레이어가 터치를 받아 영상 좌표로 바꾼다.
 */
@Composable
actual fun VideoSurface(
    session: MirrorSession,
    videoSize: VideoSize?,
    onTouch: (TouchAction, Int, Int) -> Unit,
    modifier: Modifier,
) {
    // `adb shell setprop log.tag.adb-mirror DEBUG`이면 초당 디코딩 프레임을 로그로 남긴다.
    var fatal by remember(session) { mutableStateOf<String?>(null) }
    val controller = remember(session) {
        SurfaceDecodeController(session).apply {
            logFps = Log.isLoggable("adb-mirror", Log.DEBUG)
            onFatal = { fatal = it }
        }
    }
    DisposableEffect(controller) { onDispose { controller.close() } }
    val touch by rememberUpdatedState(onTouch)

    // Surface는 크기를 알기 전에도 만들어 패킷을 받기 시작한다(세션은 첫 패킷을 받을 구독자가 있어야 흘려보낸다).
    // 크기를 알면 비율에 맞춰 가운데에 둔다.
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        val area = if (videoSize != null) Modifier.aspectRatio(videoSize.width.toFloat() / videoSize.height) else Modifier.fillMaxSize()
        Box(area) {
            AndroidView(
                factory = { context ->
                    SurfaceView(context).apply {
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) = controller.attach(holder.surface)
                            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                            // 돌아가기 전에 코덱이 Surface를 놓아야 한다.
                            override fun surfaceDestroyed(holder: SurfaceHolder) = controller.detach()
                        })
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            fatal?.let { Text(it, color = Color.White, modifier = Modifier.align(Alignment.Center).padding(16.dp)) }
            Box(
                Modifier.matchParentSize().pointerInput(videoSize) {
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
                            change.consume()
                        }
                    }
                },
            )
        }
    }
}
