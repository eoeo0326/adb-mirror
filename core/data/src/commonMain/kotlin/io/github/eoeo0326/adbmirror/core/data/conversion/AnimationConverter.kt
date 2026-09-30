package io.github.eoeo0326.adbmirror.core.data.conversion

import io.github.eoeo0326.adbmirror.core.domain.model.AnimatedFormat
import io.github.eoeo0326.adbmirror.core.domain.model.ConversionOptions
import io.github.eoeo0326.adbmirror.core.domain.model.VideoInfo
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** 녹화 파일을 프레임으로 푸는 쪽(플랫폼 디코더). */
interface VideoFrameSource {
    suspend fun info(file: String): VideoInfo

    /**
     * [startMs] 앞의 key frame부터 PTS 순서로 디코딩해 [width]×[height] ARGB로 줄여 [onFrame]에 넘긴다.
     * [onFrame]이 false를 돌려주면 멈춘다.
     */
    suspend fun decode(file: String, startMs: Long, width: Int, height: Int, onFrame: suspend (ptsMs: Long, frame: RgbaFrame) -> Boolean)
}

/** 한 장짜리 WebP 인코더(Desktop FFmpeg libwebp 등). 결과는 RIFF 전체. */
fun interface WebpFrameEncoder {
    fun encode(frame: RgbaFrame, quality: Int): ByteArray
}

/** 녹화 파일 → 애니메이션 GIF/WebP 바이트. 인코딩은 이 모듈의 [GifEncoder]·[AnimatedWebpMuxer]가 한다. */
class AnimationConverter(private val source: VideoFrameSource, private val webp: WebpFrameEncoder?) {
    val supportedFormats: Set<AnimatedFormat> =
        if (webp != null) AnimatedFormat.entries.toSet() else setOf(AnimatedFormat.Gif)

    suspend fun info(file: String): VideoInfo = source.info(file)

    suspend fun convert(file: String, options: ConversionOptions, onProgress: suspend (Float) -> Unit): ByteArray {
        require(options.format in supportedFormats) { "이 플랫폼에서는 ${options.format}로 변환할 수 없습니다" }
        val info = source.info(file)
        val endMs = (options.endMs ?: info.durationMs).coerceAtMost(info.durationMs)
        require(endMs > options.startMs) { "변환할 구간이 없습니다" }
        val (w, h) = options.outputSize(info)
        val sampler = FrameSampler<RgbaFrame>(options.startMs, endMs, options.fps)
        val sink = when (options.format) {
            AnimatedFormat.Gif -> GifEncoder(w, h, options.loopCount, options.dither).let { gif ->
                Sink({ f, d -> gif.addFrame(f, d) }, gif::finish)
            }
            AnimatedFormat.WebP -> AnimatedWebpMuxer(w, h, options.loopCount).let { mux ->
                val encoder = webp!!
                Sink({ f, d -> mux.addFrame(encoder.encode(f, options.quality), d) }, mux::finish)
            }
        }
        var lastReported = -1
        source.decode(file, options.startMs, w, h) { ptsMs, frame ->
            currentCoroutineContext().ensureActive()
            sampler.offer(ptsMs, frame).forEach { (f, d) -> sink.add(f, d) }
            val percent = (sampler.progress * 100).toInt()
            if (percent != lastReported) {
                lastReported = percent
                onProgress(sampler.progress)
            }
            !sampler.done
        }
        sampler.finish().forEach { (f, d) -> sink.add(f, d) }
        onProgress(1f)
        return sink.finish()
    }

    private class Sink(val add: (RgbaFrame, Int) -> Unit, val finish: () -> ByteArray)
}
