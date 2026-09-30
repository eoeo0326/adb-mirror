plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kmp.library) apply false
}

/** 빌드하는 머신의 JavaCPP 플랫폼 이름. 설치 파일도 OS마다 따로 만든다(크로스 컴파일 불가). */
val javacppPlatform: String by extra(
    run {
        val os = System.getProperty("os.name").lowercase()
        val arch = if (System.getProperty("os.arch") in setOf("aarch64", "arm64")) "arm64" else "x86_64"
        when {
            os.contains("mac") -> "macosx-$arch"
            os.contains("win") -> "windows-x86_64" // FFmpeg windows-arm64 빌드가 없어 x64(에뮬레이션)
            else -> "linux-$arch"
        }
    },
)
