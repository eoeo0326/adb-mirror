package io.github.eoeo0326.adbmirror.feature.mirror.video

import io.github.eoeo0326.adbmirror.core.domain.model.VideoSize

/** 화면에 영상이 비율을 유지하며 들어간 영역(레터박스 제외), 픽셀 단위. */
data class FitRect(val left: Float, val top: Float, val width: Float, val height: Float)

fun fitRect(viewWidth: Float, viewHeight: Float, video: VideoSize): FitRect {
    val scale = minOf(viewWidth / video.width, viewHeight / video.height)
    val w = video.width * scale
    val h = video.height * scale
    return FitRect((viewWidth - w) / 2, (viewHeight - h) / 2, w, h)
}

/**
 * 뷰 좌표 → 영상 좌표. [clamp]가 false면 레터박스(영상 밖) 지점은 null.
 * 드래그 중에는 clamp=true로 가장자리에 붙인다.
 */
fun videoPoint(x: Float, y: Float, viewWidth: Float, viewHeight: Float, video: VideoSize, clamp: Boolean): Pair<Int, Int>? {
    val r = fitRect(viewWidth, viewHeight, video)
    val inside = x >= r.left && x < r.left + r.width && y >= r.top && y < r.top + r.height
    if (!inside && !clamp) return null
    val nx = ((x - r.left) / r.width).coerceIn(0f, 1f)
    val ny = ((y - r.top) / r.height).coerceIn(0f, 1f)
    return minOf((nx * video.width).toInt(), video.width - 1) to minOf((ny * video.height).toInt(), video.height - 1)
}
