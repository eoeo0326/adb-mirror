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

    /**
     * [files]를 차례로 이어 하나의 애니메이션으로 만든다(회전으로 나뉜 part). 캔버스는 첫 part를 옵션 너비로 줄인 크기다.
     * 다른 방향 part는 비율을 지켜 캔버스 가운데에 맞추고 남는 곳은 검정으로 채운다. 구간은 이어 붙인 시간 기준이다.
     */
    suspend fun convert(files: List<String>, options: ConversionOptions, onProgress: suspend (Float) -> Unit): ByteArray {
        require(files.isNotEmpty()) { "변환할 파일이 없습니다" }
        require(options.format in supportedFormats) { "이 플랫폼에서는 ${options.format}로 변환할 수 없습니다" }
        val parts = files.map { source.info(it) }
        val joined = VideoInfo.joined(parts)
        val endMs = (options.endMs ?: joined.durationMs).coerceAtMost(joined.durationMs)
        require(endMs > options.startMs) { "변환할 구간이 없습니다" }
        val (w, h) = options.outputSize(joined)
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
        var offsetMs = 0L
        for ((file, info) in files.zip(parts)) {
            val partStart = offsetMs
            offsetMs += info.durationMs
            if (offsetMs <= options.startMs) continue // 구간 앞의 part
            if (partStart >= endMs || sampler.done) break
            val (fw, fh) = fitInside(info.width, info.height, w, h)
            source.decode(file, (options.startMs - partStart).coerceAtLeast(0), fw, fh) { ptsMs, frame ->
                currentCoroutineContext().ensureActive()
                val onCanvas = if (fw == w && fh == h) frame else letterbox(frame, w, h)
                sampler.offer(partStart + ptsMs, onCanvas).forEach { (f, d) -> sink.add(f, d) }
                val percent = (sampler.progress * 100).toInt()
                if (percent != lastReported) {
                    lastReported = percent
                    onProgress(sampler.progress)
                }
                !sampler.done
            }
        }
        sampler.finish().forEach { (f, d) -> sink.add(f, d) }
        onProgress(1f)
        return sink.finish()
    }

    private class Sink(val add: (RgbaFrame, Int) -> Unit, val finish: () -> ByteArray)
}

/**
 * [w]×[h]를 비율을 지켜 [maxW]×[maxH] 안에 가장 크게 넣은 크기. 최소 1px.
 * 캔버스와 비율이 같은 part(첫 part와 같은 방향)는 반올림 차이(1px)로 테두리가 생기지 않게 캔버스 크기를 그대로 쓴다.
 */
internal fun fitInside(w: Int, h: Int, maxW: Int, maxH: Int): Pair<Int, Int> {
    val fitted = if (w.toLong() * maxH <= h.toLong() * maxW) {
        // 세로가 먼저 닿는다
        maxOf(1, (w.toLong() * maxH / h).toInt()) to maxH
    } else {
        maxW to maxOf(1, (h.toLong() * maxW / w).toInt())
    }
    return if (maxW - fitted.first <= 1 && maxH - fitted.second <= 1) maxW to maxH else fitted
}

/** [frame]을 [w]×[h] 검정 캔버스 가운데에 놓는다. */
internal fun letterbox(frame: RgbaFrame, w: Int, h: Int): RgbaFrame {
    val px = IntArray(w * h) { 0xFF000000.toInt() }
    val left = (w - frame.width) / 2
    val top = (h - frame.height) / 2
    for (y in 0 until frame.height) frame.pixels.copyInto(px, (top + y) * w + left, y * frame.width, (y + 1) * frame.width)
    return RgbaFrame(w, h, px)
}
