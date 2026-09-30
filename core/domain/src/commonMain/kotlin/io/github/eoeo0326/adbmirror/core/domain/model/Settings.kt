package io.github.eoeo0326.adbmirror.core.domain.model

data class Settings(
    /** 영상 긴 변 최대 픽셀. 0이면 원본. */
    val maxSize: Int = 1280,
    val maxFps: Int = 60,
    val viewOnly: Boolean = false,
    /** 클릭 지점 파문 이펙트 */
    val touchEffect: Boolean = true,
    /** 기기 자체의 터치 표시(show_touches). 녹화에도 남는다. */
    val showTouches: Boolean = false,
    /** 스크린샷·녹화 저장 폴더. null이면 플랫폼 기본 위치. */
    val outputDir: String? = null,
)
