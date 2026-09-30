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
    /** adb 실행 파일. null이면 PATH·ANDROID_HOME·기본 SDK 위치에서 찾는다. */
    val adbPath: String? = null,
) {
    /** 저장 파일을 손으로 고쳤거나 옛 값이 남아 있어도 서버가 받을 수 있는 값으로 맞춘다. */
    fun sanitized(): Settings {
        val defaults = Settings()
        return copy(
            maxSize = maxSize.takeIf { it == 0 || it in MAX_SIZE_RANGE } ?: defaults.maxSize,
            maxFps = maxFps.takeIf { it in FPS_RANGE } ?: defaults.maxFps,
            outputDir = outputDir?.takeIf { it.isNotBlank() },
            adbPath = adbPath?.takeIf { it.isNotBlank() },
        )
    }

    companion object {
        val MAX_SIZE_RANGE = 240..4096
        val FPS_RANGE = 1..120

        /** 설정 화면에서 고를 수 있는 값. 0은 원본 해상도. */
        val MAX_SIZE_CHOICES = listOf(720, 1080, 1280, 1920, 0)
        val FPS_CHOICES = listOf(30, 60)
    }
}
