package io.github.eoeo0326.adbmirror.core.data.conversion

import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import io.github.eoeo0326.adbmirror.core.domain.model.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * MediaExtractor·MediaCodec(하드웨어 디코더)로 녹화 파일(MP4)을 프레임으로 푼다. 호출마다 파일을 새로 연다.
 * 디코더 출력(YUV 4:2:0)을 바로 목표 크기로 줄이며 ARGB로 바꾼다. 밝기는 영역 평균, 색은 영역 가운데 값을 쓴다.
 */
class MediaCodecVideoFrameSource : VideoFrameSource {
    override suspend fun info(file: String): VideoInfo = withContext(Dispatchers.IO) {
        openVideo(file) { extractor, format, _ ->
            VideoInfo(durationMs(extractor, format), format.getInteger(MediaFormat.KEY_WIDTH), format.getInteger(MediaFormat.KEY_HEIGHT))
        }
    }

    override suspend fun decode(
        file: String,
        startMs: Long,
        width: Int,
        height: Int,
        onFrame: suspend (ptsMs: Long, frame: RgbaFrame) -> Boolean,
    ) {
        // 디스패처를 바꾸지 않는다. onFrame이 호출한 쪽의 Flow에 진행률을 내보내므로 같은 컨텍스트여야 한다.
        openVideo(file) { extractor, format, origin ->
            // 시작 지점 앞의 key frame부터 읽는다. 그 앞 프레임은 FrameSampler가 거른다.
            if (startMs > 0) extractor.seekTo(origin + startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            try {
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                codec.configure(format, null, null, 0)
                codec.start()
                val scaler = YuvScaler(width, height)
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                while (true) {
                    if (!inputDone) {
                        val i = codec.dequeueInputBuffer(TIMEOUT_US)
                        if (i >= 0) {
                            val size = extractor.readSampleData(codec.getInputBuffer(i)!!, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(i, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                    if (o < 0) continue
                    val end = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    // 출력 버퍼는 바로 돌려준다(onFrame이 오래 걸려도 디코더를 붙잡지 않게).
                    val frame = if (info.size > 0) codec.getOutputImage(o)?.use(scaler::toArgb) else null
                    val ptsUs = info.presentationTimeUs
                    codec.releaseOutputBuffer(o, false)
                    if (frame != null && !onFrame((ptsUs - origin) / 1000, frame)) break
                    if (end) break
                }
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
        }
    }

    /** 영상 트랙을 고른 [MediaExtractor]와 그 형식, 첫 샘플 시각(µs)을 넘긴다. */
    private inline fun <R> openVideo(file: String, block: (MediaExtractor, MediaFormat, Long) -> R): R {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: error("영상 트랙이 없습니다")
            extractor.selectTrack(track)
            return block(extractor, extractor.getTrackFormat(track), extractor.sampleTime.coerceAtLeast(0))
        } finally {
            extractor.release()
        }
    }

    /**
     * 길이(ms). fragmented MP4는 컨테이너에 길이가 없을 수 있다. 그러면 샘플 시각만 끝까지 훑어(디코딩 없이)
     * 마지막 시각 + 그 앞 간격으로 잰다.
     */
    private fun durationMs(extractor: MediaExtractor, format: MediaFormat): Long {
        if (format.containsKey(MediaFormat.KEY_DURATION)) {
            val us = format.getLong(MediaFormat.KEY_DURATION)
            if (us > 0) return us / 1000
        }
        val origin = extractor.sampleTime.coerceAtLeast(0)
        var last = origin
        var gap = 0L
        while (extractor.sampleTime >= 0) {
            val t = extractor.sampleTime
            if (t > last) gap = t - last
            last = maxOf(last, t)
            extractor.advance()
        }
        return (last + gap - origin) / 1000
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
    }
}

/** YUV_420_888 [Image]를 [width]×[height] ARGB로 줄인다(BT.601 limited range, Desktop swscale 기본과 같음). */
internal class YuvScaler(private val width: Int, private val height: Int) {
    private var y = ByteArray(0)
    private var u = ByteArray(0)
    private var v = ByteArray(0)

    fun toArgb(image: Image): RgbaFrame {
        val crop = image.cropRect
        val planes = image.planes
        y = planes[0].copyTo(y)
        u = planes[1].copyTo(u)
        v = planes[2].copyTo(v)
        val yStride = planes[0].rowStride
        val yPixel = planes[0].pixelStride
        val cStride = planes[1].rowStride
        val cPixel = planes[1].pixelStride
        val vStride = planes[2].rowStride
        val vPixel = planes[2].pixelStride
        val sw = crop.width()
        val sh = crop.height()
        val xs = IntArray(width + 1) { crop.left + (it.toLong() * sw / width).toInt() }
        val ys = IntArray(height + 1) { crop.top + (it.toLong() * sh / height).toInt() }
        val px = IntArray(width * height)
        for (dy in 0 until height) {
            val y0 = ys[dy]
            val y1 = maxOf(ys[dy + 1], y0 + 1)
            val cy = (y0 + y1 - 1) / 2 / 2
            for (dx in 0 until width) {
                val x0 = xs[dx]
                val x1 = maxOf(xs[dx + 1], x0 + 1)
                var sum = 0
                for (sy in y0 until y1) {
                    var p = sy * yStride + x0 * yPixel
                    for (sx in x0 until x1) {
                        sum += y[p].toInt() and 0xFF
                        p += yPixel
                    }
                }
                val luma = sum / ((y1 - y0) * (x1 - x0))
                val cx = (x0 + x1 - 1) / 2 / 2
                val cb = (u[cy * cStride + cx * cPixel].toInt() and 0xFF) - 128
                val cr = (v[cy * vStride + cx * vPixel].toInt() and 0xFF) - 128
                val c = (luma - 16) * 298
                val r = (c + 409 * cr + 128) shr 8
                val g = (c - 100 * cb - 208 * cr + 128) shr 8
                val b = (c + 516 * cb + 128) shr 8
                px[dy * width + dx] = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
            }
        }
        return RgbaFrame(width, height, px)
    }

    private fun Image.Plane.copyTo(reuse: ByteArray): ByteArray {
        val buffer = buffer.duplicate()
        val out = if (reuse.size == buffer.remaining()) reuse else ByteArray(buffer.remaining())
        buffer.get(out)
        return out
    }
}
