package io.github.eoeo0326.adbmirror.core.domain.model

enum class TouchAction { Down, Move, Up }

/** 영상 좌표계 기준 터치 한 번. 서버는 [videoSize]가 현재 세션 크기와 다르면 이벤트를 버린다. */
data class TouchEvent(
    val action: TouchAction,
    val x: Int,
    val y: Int,
    val videoSize: VideoSize,
)
