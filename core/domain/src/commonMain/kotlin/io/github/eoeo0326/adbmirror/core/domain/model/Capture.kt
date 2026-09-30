package io.github.eoeo0326.adbmirror.core.domain.model

/** 기기 원본 해상도 PNG 스크린샷. */
class Screenshot(val serial: String, val png: ByteArray)

/** 끝난 녹화. 녹화 중 회전하면 파일이 여러 개(`_part2`, `_part3` …)가 된다. */
data class Recording(val serial: String, val files: List<String>, val durationMs: Long)

enum class AnimatedFormat { Gif, WebP }

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

    companion object {
        val FPS_RANGE = 5..30
        val WIDTH_RANGE = 240..1080
    }
}

sealed interface ConversionProgress {
    /** 0.0~1.0 */
    data class Running(val fraction: Float) : ConversionProgress
    data class Done(val file: String) : ConversionProgress
}
