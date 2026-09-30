package io.github.eoeo0326.adbmirror.core.domain.model

/** 기기 원본 해상도 PNG 스크린샷. */
class Screenshot(val serial: String, val png: ByteArray)

/** 끝난 녹화. 녹화 중 회전하면 파일이 여러 개(`_part2`, `_part3` …)가 된다. */
data class Recording(val serial: String, val files: List<String>, val durationMs: Long)

enum class AnimatedFormat { Gif, WebP }

/** 녹화 파일 정보. 변환 화면의 구간·너비 기본값과 예상 크기에 쓴다. */
data class VideoInfo(val durationMs: Long, val width: Int, val height: Int)

data class ConversionOptions(
    val format: AnimatedFormat = AnimatedFormat.Gif,
    val startMs: Long = 0,
    /** null이면 녹화 끝까지. */
    val endMs: Long? = null,
    val fps: Int = 15,
    val width: Int = 480,
    /** 0이면 무한 반복. */
    val loopCount: Int = 0,
    /** WebP 품질(0~100). GIF에서는 쓰지 않는다. */
    val quality: Int = 75,
    /** GIF 디더링(색 번짐을 줄이지만 파일이 커진다). WebP에서는 쓰지 않는다. */
    val dither: Boolean = false,
) {
    /** 허용 범위를 벗어난 항목의 설명. 비어 있으면 유효하다. */
    fun problems(): List<String> = buildList {
        if (startMs < 0) add("시작 시각은 0 이상이어야 한다")
        if (endMs != null && endMs <= startMs) add("끝 시각은 시작보다 뒤여야 한다")
        if (fps !in FPS_RANGE) add("fps는 ${FPS_RANGE.first}~${FPS_RANGE.last}")
        if (width !in WIDTH_RANGE) add("너비는 ${WIDTH_RANGE.first}~${WIDTH_RANGE.last}px")
        if (loopCount < 0) add("반복 횟수는 0 이상이어야 한다")
        if (quality !in 0..100) add("품질은 0~100")
    }

    /** 출력 크기(px). 원본보다 크게 늘리지 않고, 높이는 비율을 따른다. */
    fun outputSize(info: VideoInfo): Pair<Int, Int> {
        val w = minOf(width, info.width)
        return w to maxOf(1, (info.height.toLong() * w / info.width).toInt())
    }

    /**
     * 대략의 출력 크기(바이트). 화면 녹화는 정지 구간이 많아 실제로는 더 작은 경우가 많다.
     * 픽셀당 바이트는 실기기 녹화로 잰 대략값(GIF 0.5, 디더링 0.8, WebP 품질 75에서 0.08)이다.
     */
    fun estimatedBytes(info: VideoInfo): Long {
        val (w, h) = outputSize(info)
        val durationMs = (endMs ?: info.durationMs).coerceAtMost(info.durationMs) - startMs
        val frames = maxOf(1L, durationMs * fps / 1000)
        val perPixel = when (format) {
            AnimatedFormat.Gif -> if (dither) 0.8 else 0.5
            AnimatedFormat.WebP -> 0.02 + 0.12 * quality / 100.0
        }
        return (w.toLong() * h * frames * perPixel).toLong()
    }

    companion object {
        /** 이보다 크면 경고한다(메신저·이슈 첨부 한도가 보통 10~25MB). */
        const val LARGE_OUTPUT_BYTES = 20L * 1024 * 1024
        val FPS_RANGE = 5..30
        val WIDTH_RANGE = 240..1080
    }
}

sealed interface ConversionProgress {
    /** 0.0~1.0 */
    data class Running(val fraction: Float) : ConversionProgress
    data class Done(val file: String) : ConversionProgress
}
