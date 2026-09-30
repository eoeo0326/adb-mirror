plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

val javacppPlatform: String by rootProject.extra

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
            implementation(compose.desktop.currentOs) // YUV 셰이더 테스트가 Skia(skiko) 네이티브를 쓴다
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
