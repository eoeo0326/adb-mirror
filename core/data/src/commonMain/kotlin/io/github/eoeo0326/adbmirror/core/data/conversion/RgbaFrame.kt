package io.github.eoeo0326.adbmirror.core.data.conversion

/** 디코딩된 프레임 하나. [pixels]는 행 우선 ARGB(0xAARRGGBB). */
class RgbaFrame(val width: Int, val height: Int, val pixels: IntArray) {
    init {
        require(width > 0 && height > 0 && pixels.size == width * height) { "프레임 크기 불일치: ${width}x$height, ${pixels.size}" }
    }
}
