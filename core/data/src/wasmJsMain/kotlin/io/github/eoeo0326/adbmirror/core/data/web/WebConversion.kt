@file:OptIn(ExperimentalWasmJsInterop::class)

package io.github.eoeo0326.adbmirror.core.data.web

import io.github.eoeo0326.adbmirror.core.data.conversion.RgbaFrame
import io.github.eoeo0326.adbmirror.core.data.conversion.VideoFrameSource
import io.github.eoeo0326.adbmirror.core.data.conversion.WebpFrameEncoder
import io.github.eoeo0326.adbmirror.core.data.recording.Mp4Demuxer
import io.github.eoeo0326.adbmirror.core.domain.model.VideoInfo
import kotlinx.coroutines.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import kotlin.js.Promise

/**
 * 프레임을 [width]×[height] ARGB(Int32Array)로 줄여 쌓아 두는 WebCodecs 디코더. Kotlin 쪽이 [takeFrame]으로 꺼낸다.
 * 디코딩 결과·오류·큐가 줄 때마다 [waitEvent]의 대기를 깨운다.
 */
@JsFun(
    """(codec, description, width, height) => {
  const canvas = new OffscreenCanvas(width, height);
  const ctx = canvas.getContext('2d', { willReadFrequently: true });
  const st = { frames: [], error: null, waiter: null };
  const wake = () => { const w = st.waiter; st.waiter = null; if (w) w(); };
  st.decoder = new VideoDecoder({
    output: (frame) => {
      try {
        ctx.drawImage(frame, 0, 0, width, height);
        const rgba = ctx.getImageData(0, 0, width, height).data;
        const argb = new Int32Array(width * height);
        for (let i = 0, j = 0; i < argb.length; i++, j += 4) argb[i] = (rgba[j + 3] << 24) | (rgba[j] << 16) | (rgba[j + 1] << 8) | rgba[j + 2];
        st.frames.push({ ts: frame.timestamp, argb: argb });
      } finally {
        frame.close();
      }
      wake();
    },
    error: (e) => { st.error = String((e && e.message) || e); wake(); },
  });
  st.decoder.addEventListener('dequeue', wake);
  st.decoder.configure({ codec: codec, description: description });
  return st;
}""",
)
private external fun newFrameDecoder(codec: String, description: JsAny, width: Int, height: Int): JsAny

@JsFun(
    """(st, file, offset, size, ts, key) => st.decoder.decode(new EncodedVideoChunk({
  type: key ? 'key' : 'delta', timestamp: ts, data: file.subarray(offset, offset + size) }))""",
)
private external fun decodeSample(state: JsAny, file: JsAny, offset: Double, size: Int, timestampUs: Double, key: Boolean)

@JsFun("(st) => st.decoder.decodeQueueSize")
private external fun queueSize(state: JsAny): Int

@JsFun("(st) => new Promise((r) => { if (st.frames.length || st.error) r(); else st.waiter = r; })")
private external fun waitEvent(state: JsAny): Promise<JsAny?>

@JsFun("(st) => st.decoder.flush().catch((e) => { st.error = String((e && e.message) || e); })")
private external fun flushDecoder(state: JsAny): Promise<JsAny?>

@JsFun("(st) => { if (st.decoder.state !== 'closed') st.decoder.close(); st.frames = []; }")
private external fun closeDecoder(state: JsAny)

@JsFun("(st) => st.frames.length")
private external fun frameCount(state: JsAny): Int

@JsFun("(st) => st.frames.shift()")
private external fun takeFrame(state: JsAny): JsAny

@JsFun("(f) => f.ts")
private external fun frameTimestamp(frame: JsAny): Double

@JsFun("(f) => f.argb")
private external fun frameArgb(frame: JsAny): JsAny

@JsFun("(st) => st.error")
private external fun decoderError(state: JsAny): String?

/** WebCodecs H.264 디코딩과 OffscreenCanvas가 있으면 true(Chromium). */
@JsFun("() => typeof VideoDecoder !== 'undefined' && typeof EncodedVideoChunk !== 'undefined' && typeof OffscreenCanvas !== 'undefined'")
internal external fun webDecodingAvailable(): Boolean

/**
 * 웹 녹화 파일(OPFS의 fragmented MP4) → 프레임. [Mp4Demuxer]로 샘플 위치를 읽고 WebCodecs로 디코딩한다.
 * [openFile]은 파일 전체를 JS Uint8Array로 돌려준다. 샘플 데이터는 JS 쪽에서 잘라 디코더에 바로 넣고,
 * Kotlin으로는 줄인 프레임 픽셀만 옮긴다. 같은 파일을 info·decode가 이어 부르므로 마지막 파일 하나를 기억해 둔다.
 */
internal class WebVideoFrameSource(private val openFile: suspend (String) -> JsAny) : VideoFrameSource {
    private class Loaded(val name: String, val bytes: JsAny, val track: Mp4Demuxer.Track)

    private val lock = Mutex()
    private var cache: Loaded? = null

    private suspend fun load(file: String): Loaded = lock.withLock {
        cache?.takeIf { it.name == file } ?: run {
            val bytes = openFile(file)
            val track = Mp4Demuxer.read(byteLengthOf(bytes).toLong()) { offset, length ->
                subarrayOf(bytes, offset.toInt(), offset.toInt() + length).toByteArray()
            }
            Loaded(file, bytes, track).also { cache = it }
        }
    }

    override suspend fun info(file: String): VideoInfo = load(file).track.info

    override suspend fun decode(file: String, startMs: Long, width: Int, height: Int, onFrame: suspend (ptsMs: Long, frame: RgbaFrame) -> Boolean) {
        val loaded = load(file)
        val samples = loaded.track.samples
        val first = samples.indexOfLast { it.key && it.ptsUs <= startMs * 1000 }.coerceAtLeast(0)
        val state = newFrameDecoder(loaded.track.codec, loaded.track.avcC.toUint8Array(), width, height)

        /** 쌓인 프레임을 넘긴다. [onFrame]이 멈추라고 하면 false. */
        suspend fun drain(): Boolean {
            while (frameCount(state) > 0) {
                val frame = takeFrame(state)
                val ptsMs = (frameTimestamp(frame) / 1000).toLong()
                if (!onFrame(ptsMs, RgbaFrame(width, height, frameArgb(frame).toIntArray()))) return false
                yield() // 변환이 길어도 화면(진행률)이 멈추지 않게
            }
            decoderError(state)?.let { error("영상을 디코딩하지 못했습니다: $it") }
            return true
        }

        try {
            for (i in first until samples.size) {
                val s = samples[i]
                decodeSample(state, loaded.bytes, s.offset.toDouble(), s.size, s.ptsUs.toDouble(), s.key)
                while (queueSize(state) >= MAX_QUEUE) {
                    waitEvent(state).await<JsAny?>()
                    if (!drain()) return
                }
                if (!drain()) return
            }
            flushDecoder(state).await<JsAny?>()
            drain()
        } finally {
            closeDecoder(state)
        }
    }

    private companion object {
        /** 디코더에 한꺼번에 넣어 둘 샘플 수. 넘으면 결과가 나올 때까지 기다린다. */
        const val MAX_QUEUE = 4
    }
}

/** ARGB(little-endian Int32 바이트)를 canvas에 그려 WebP로. 브라우저가 WebP로 못 만들면 null. */
@JsFun(
    """(bytes, width, height, quality) => {
  const argb = new Int32Array(bytes.buffer, bytes.byteOffset, width * height);
  const canvas = new OffscreenCanvas(width, height);
  const ctx = canvas.getContext('2d');
  const img = ctx.createImageData(width, height);
  const d = img.data;
  for (let i = 0, j = 0; i < argb.length; i++, j += 4) { const v = argb[i]; d[j] = (v >> 16) & 255; d[j + 1] = (v >> 8) & 255; d[j + 2] = v & 255; d[j + 3] = (v >>> 24) & 255; }
  ctx.putImageData(img, 0, 0);
  return canvas.convertToBlob({ type: 'image/webp', quality: quality / 100 })
    .then((b) => b.type === 'image/webp' ? b.arrayBuffer().then((a) => new Uint8Array(a)) : null);
}""",
)
private external fun encodeWebp(argb: JsAny, width: Int, height: Int, quality: Int): Promise<JsAny?>

/** 이 브라우저의 canvas가 WebP로 인코딩하는지(Chromium은 한다). 2D 컨텍스트를 만든 canvas여야 인코딩된다. */
@JsFun(
    """() => {
  if (typeof OffscreenCanvas === 'undefined') return Promise.resolve(false);
  const canvas = new OffscreenCanvas(2, 2);
  canvas.getContext('2d').fillRect(0, 0, 2, 2);
  return canvas.convertToBlob({ type: 'image/webp' }).then((b) => b.type === 'image/webp').catch(() => false);
}""",
)
internal external fun webpEncodingSupported(): Promise<JsBoolean>

/** canvas `convertToBlob('image/webp')`로 한 장씩 인코딩한다. */
internal object CanvasWebpFrameEncoder : WebpFrameEncoder {
    override suspend fun encode(frame: RgbaFrame, quality: Int): ByteArray {
        val webp = encodeWebp(frame.pixels.toUint8Array(), frame.width, frame.height, quality).await<JsAny?>()
            ?: error("이 브라우저는 WebP로 저장하지 못합니다")
        return webp.toByteArray()
    }
}
