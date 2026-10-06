package io.github.eoeo0326.adbmirror.core.domain.model

/** 기기 원본 해상도 PNG 스크린샷. */
class Screenshot(val serial: String, val png: ByteArray)

/**
 * 끝난 녹화. 녹화 중 회전하면 파일이 여러 개(`_part2`, `_part3` …)가 된다.
 * [files]는 변환에 넘기는 녹화 파일, [locations]는 사용자에게 보여줄 저장 위치(같은 순서).
 * Android는 앱 캐시의 파일을 변환하고, 사용자에게는 MediaStore에 올린 위치를 보여준다.
 */
data class Recording(val serial: String, val files: List<String>, val durationMs: Long, val locations: List<String> = files)

enum class AnimatedFormat { Gif, WebP }

/** 녹화 파일 정보. 변환 화면의 구간·너비 기본값과 예상 크기에 쓴다. */
data class VideoInfo(val durationMs: Long, val width: Int, val height: Int) {
    companion object {
        /**
         * 회전으로 나뉜 part들을 이어 붙인 정보. 길이는 합이고, 크기는 첫 part(캔버스) 기준이다.
         * 다른 방향 part는 이 캔버스 안에 비율을 지켜 맞춘다.
         */
        fun joined(parts: List<VideoInfo>): VideoInfo {
            require(parts.isNotEmpty())
            return VideoInfo(parts.sumOf { it.durationMs }, parts.first().width, parts.first().height)
        }
    }
}

data class ConversionOptions(
    val format: AnimatedFormat = AnimatedFormat.Gif,
    val startMs: Long = 0,
    /** null이면 녹화 끝까지. */
    val endMs: Long? = null,
    val fps: Int = 15,
    val width: Int = 480,
    /** 재생 횟수. 0이면 무한 반복. */
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
     * 대략의 출력 크기(바이트). 넉넉하게(크게) 잡는다.
     * 실기기 설정 화면 스크롤 녹화(480px·15fps)가 픽셀당 GIF 0.04, WebP(품질 75) 0.013바이트였고,
     * 영상·게임처럼 화면이 많이 바뀌는 경우를 생각해 약 2.5배로 잡았다.
     */
    fun estimatedBytes(info: VideoInfo): Long {
        val (w, h) = outputSize(info)
        val durationMs = (endMs ?: info.durationMs).coerceAtMost(info.durationMs) - startMs
        val frames = maxOf(1L, durationMs * fps / 1000)
        val perPixel = when (format) {
            AnimatedFormat.Gif -> if (dither) 0.2 else 0.1
            AnimatedFormat.WebP -> 0.01 + 0.04 * quality / 100.0
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
    /**
     * [file]은 보여줄 저장 위치, [uri]는 다른 앱으로 열 때 쓰는 주소(Android의 content Uri).
     * [uri]가 null이면 [file]이 그대로 열 수 있는 경로다(Desktop). 열 수 없는 플랫폼도 있다(Web 다운로드).
     */
    data class Done(val file: String, val uri: String? = null) : ConversionProgress
}
