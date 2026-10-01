@file:OptIn(ExperimentalWasmJsInterop::class)

package io.github.eoeo0326.adbmirror.feature.mirror.video

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.github.eoeo0326.adbmirror.core.domain.model.EncodedPacket
import io.github.eoeo0326.adbmirror.core.domain.model.MirrorSession
import io.github.eoeo0326.adbmirror.core.domain.model.TouchAction
import io.github.eoeo0326.adbmirror.core.domain.model.VideoSize
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement

/**
 * WebCodecs `VideoDecoder`가 H.264를 풀고, 나온 `VideoFrame`을 JS에서 바로 HTML canvas에 그린다(픽셀을 Wasm으로 옮기지 않음).
 * canvas는 Compose 화면 **뒤**에 두고, Compose는 영상 영역만 투명하게 비워(BlendMode.Clear) 그 구멍으로 영상이 보이게 한다.
 * 그래서 Snackbar·메뉴 같은 Compose 요소가 영상 위에 그려지고, 터치도 Compose가 그대로 받는다
 * (HTML 요소를 위에 겹치면 Compose 요소가 그 아래에 깔리고 포인터 이벤트를 가로챈다).
 */
@Composable
actual fun VideoSurface(
    session: MirrorSession,
    videoSize: VideoSize?,
    onTouch: (TouchAction, Int, Int) -> Unit,
    modifier: Modifier,
) {
    var fatal by remember(session) { mutableStateOf<String?>(null) }
    val canvas = remember(session) { createBackgroundCanvas() }
    val renderer = remember(session) { if (webCodecsSupported()) createRenderer(canvas) else null }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    DisposableEffect(canvas, renderer) {
        onDispose {
            renderer?.let(::closeRenderer)
            canvas.remove()
        }
    }
    LaunchedEffect(session, renderer) {
        if (renderer == null) {
            fatal = "이 브라우저는 WebCodecs 영상 디코딩을 지원하지 않습니다"
            return@LaunchedEffect
        }
        val loop = PacketDecodeLoop(
            newDecoder = { size -> WebCodecsH264Decoder(renderer, size) },
            requestKeyFrame = { scope.launch { runCatching { session.requestKeyFrame() } } },
            nowMs = { nowMs().toLong() },
            onError = { println("영상 디코더 오류: ${it.message}") },
        )
        try {
            loop.start()
            session.packets.collect { loop.accept(it) }
        } catch (e: DecoderUnavailableException) {
            fatal = "영상을 디코딩하지 못했습니다: ${e.cause?.message ?: e.message}"
        } finally {
            loop.close()
        }
    }
    val touch by rememberUpdatedState(onTouch)

    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        val area = if (videoSize != null) Modifier.aspectRatio(videoSize.width.toFloat() / videoSize.height) else Modifier.fillMaxSize()
        Box(
            area
                .onGloballyPositioned { coordinates ->
                    // Compose 화면은 페이지 (0, 0)부터 채운다(body margin 0). 픽셀을 CSS 픽셀로 바꿔 canvas를 그 자리에 둔다.
                    val r = coordinates.boundsInWindow()
                    placeCanvas(canvas, r.left / density, r.top / density, r.width / density, r.height / density)
                }
                .drawWithContent {
                    drawRect(Color.Transparent, blendMode = BlendMode.Clear)
                    drawContent()
                },
        ) {
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

/**
 * 캡처 세션 하나의 WebCodecs 디코더. Annex B(시작 코드) 그대로 넣으므로 SPS·PPS(config)를 key frame 앞에 붙인다.
 * 디코딩은 비동기라 [decode]는 큐에 넣기만 하고, 큐가 밀리면(느린 기기) 버리고 false를 돌려 key frame을 다시 받는다.
 */
internal class WebCodecsH264Decoder(private val renderer: JsAny, override val size: VideoSize) : PacketDecoder {
    private var config: ByteArray? = null

    override fun decode(packet: EncodedPacket): Boolean {
        val kind = packet.kind
        if (kind == EncodedPacket.Kind.Config) {
            config = packet.data
            val codec = avcCodecString(packet.data) ?: throw IllegalStateException("SPS를 찾지 못했습니다")
            if (!configureRenderer(renderer, codec)) throw IllegalStateException("WebCodecs가 $codec 디코딩을 지원하지 않습니다")
            return true
        }
        val c = config ?: return false
        if (rendererQueueSize(renderer) > MAX_QUEUE) return false
        val key = kind == EncodedPacket.Kind.KeyFrame
        val data = if (key) c + packet.data else packet.data
        if (!decodeChunk(renderer, key, (packet.ptsUs ?: 0L).toDouble(), data.toUint8Array())) {
            throw IllegalStateException("WebCodecs 디코더가 닫혔습니다")
        }
        return true
    }

    override fun close() = resetRenderer(renderer)

    private companion object {
        const val MAX_QUEUE = 10
    }
}

/** Annex B config에서 첫 SPS(NAL 7)의 profile·constraint·level로 `avc1.PPCCLL`을 만든다. */
internal fun avcCodecString(config: ByteArray): String? {
    var i = 0
    while (i + 3 < config.size) {
        val start = when {
            config[i].toInt() == 0 && config[i + 1].toInt() == 0 && config[i + 2].toInt() == 1 -> i + 3
            i + 4 < config.size && config[i].toInt() == 0 && config[i + 1].toInt() == 0 && config[i + 2].toInt() == 0 && config[i + 3].toInt() == 1 -> i + 4
            else -> -1
        }
        if (start >= 0 && start + 3 < config.size && (config[start].toInt() and 0x1F) == 7) {
            fun hex(b: Byte) = (b.toInt() and 0xFF).toString(16).padStart(2, '0')
            return "avc1." + hex(config[start + 1]) + hex(config[start + 2]) + hex(config[start + 3])
        }
        i++
    }
    return null
}

/** Compose 화면 뒤(z-index -1)에 고정 위치로 둔 영상 canvas. 포인터 이벤트는 받지 않는다. */
@JsFun(
    """() => { const c = document.createElement('canvas');
  c.style.cssText = 'position:fixed;left:0;top:0;width:0;height:0;z-index:-1;pointer-events:none;background:#000';
  document.body.appendChild(c); return c; }""",
)
private external fun createBackgroundCanvas(): HTMLElement

@JsFun("(c, x, y, w, h) => { c.style.left = x + 'px'; c.style.top = y + 'px'; c.style.width = w + 'px'; c.style.height = h + 'px'; }")
private external fun placeCanvas(canvas: HTMLElement, x: Float, y: Float, width: Float, height: Float)

@JsFun("() => typeof VideoDecoder !== 'undefined'")
private external fun webCodecsSupported(): Boolean

@JsFun("() => Date.now()")
private external fun nowMs(): Double

/**
 * canvas 하나에 그리는 디코더 묶음. 출력 프레임마다 canvas 픽셀 크기를 프레임 크기에 맞추고 그린 뒤 바로 닫는다.
 * 오류가 나면 디코더가 닫히고 decode가 false를 돌려준다(Kotlin 쪽이 새로 configure한다).
 */
@JsFun(
    """(canvas) => {
  const ctx = canvas.getContext('2d');
  const r = { decoder: null, codec: null };
  r.make = () => {
    r.decoder = new VideoDecoder({
      output: (frame) => {
        if (canvas.width !== frame.displayWidth) canvas.width = frame.displayWidth;
        if (canvas.height !== frame.displayHeight) canvas.height = frame.displayHeight;
        ctx.drawImage(frame, 0, 0);
        frame.close();
      },
      error: (e) => { console.warn('VideoDecoder', e); },
    });
  };
  return r;
}""",
)
internal external fun createRenderer(canvas: HTMLElement): JsAny

@JsFun(
    """(r, codec) => {
  try {
    if (!r.decoder || r.decoder.state === 'closed') r.make();
    r.decoder.configure({ codec: codec, optimizeForLatency: true });
    r.codec = codec;
    return true;
  } catch (e) { console.warn('configure', e); return false; }
}""",
)
private external fun configureRenderer(renderer: JsAny, codec: String): Boolean

@JsFun("(r) => (r.decoder && r.decoder.state === 'configured') ? r.decoder.decodeQueueSize : 0")
private external fun rendererQueueSize(renderer: JsAny): Int

@JsFun(
    """(r, key, ts, data) => {
  if (!r.decoder || r.decoder.state !== 'configured') return false;
  try { r.decoder.decode(new EncodedVideoChunk({ type: key ? 'key' : 'delta', timestamp: ts, data: data })); return true; }
  catch (e) { console.warn('decode', e); return false; }
}""",
)
private external fun decodeChunk(renderer: JsAny, key: Boolean, timestampUs: Double, data: JsAny): Boolean

/** 회전 등으로 새 크기의 디코더를 만들기 전에 지금 디코더를 닫는다. */
@JsFun("(r) => { try { if (r.decoder && r.decoder.state !== 'closed') r.decoder.close(); } catch (e) {} r.decoder = null; }")
private external fun resetRenderer(renderer: JsAny)

@JsFun("(r) => { try { if (r.decoder && r.decoder.state !== 'closed') r.decoder.close(); } catch (e) {} }")
private external fun closeRenderer(renderer: JsAny)

@JsFun("(n) => new Uint8Array(n)")
private external fun newBytes(length: Int): JsAny

@JsFun("(a, i, v) => { a[i] = v; }")
private external fun setByteAt(array: JsAny, index: Int, value: Int)

private fun ByteArray.toUint8Array(): JsAny = newBytes(size).also { a -> forEachIndexed { i, b -> setByteAt(a, i, b.toInt() and 0xFF) } }
