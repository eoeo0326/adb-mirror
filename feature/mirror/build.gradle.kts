plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

/** 빌드하는 머신의 JavaCPP 플랫폼 이름. 설치 파일도 OS마다 따로 만든다(크로스 컴파일 불가). */
val javacppPlatform: String = run {
    val os = System.getProperty("os.name").lowercase()
    val arch = if (System.getProperty("os.arch") in setOf("aarch64", "arm64")) "arm64" else "x86_64"
    when {
        os.contains("mac") -> "macosx-$arch"
        os.contains("win") -> "windows-x86_64" // FFmpeg windows-arm64 빌드가 없어 x64(에뮬레이션)
        else -> "linux-$arch"
    }
}

kotlin {
    jvmToolchain(21)
    jvm()
    android {
        namespace = "io.github.eoeo0326.adbmirror.feature.mirror"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs { browser() }

    sourceSets {
        commonMain.dependencies {
            api(projects.core.domain)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.material3)
            api(libs.androidx.lifecycle.viewmodel.compose) // MirrorViewModel이 공개 API로 ViewModel을 드러낸다
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(projects.core.data) // fixture 파서
        }
        jvmMain.dependencies {
            // FFmpeg(LGPL 빌드) H.264 디코더. 네이티브 라이브러리는 빌드하는 OS·아키텍처 것만 넣는다.
            implementation(libs.bytedeco.ffmpeg)
            implementation("org.bytedeco:ffmpeg:${libs.versions.bytedeco.ffmpeg.get()}:$javacppPlatform")
            implementation("org.bytedeco:javacpp:${libs.versions.bytedeco.javacpp.get()}:$javacppPlatform")
        }
    }
}

// wasm UI 테스트는 아직 없다. 실행 바이너리가 없는 라이브러리 모듈이라 CMP-4906 검사가 빌드를 막으므로 끈다.
tasks.matching { it.name.startsWith("checkComposeUiTestConfiguration") }.configureEach { enabled = false }

// jvmTest의 디코더 테스트가 레포 루트의 fixtures/를 읽는다.
tasks.withType<Test>().configureEach {
    systemProperty("fixtures.dir", rootProject.layout.projectDirectory.dir("fixtures").asFile.absolutePath)
}
