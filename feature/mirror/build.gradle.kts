plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
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
            implementation(projects.core.domain)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.material3)
            implementation(libs.androidx.lifecycle.viewmodel.compose)
        }
    }
}

// wasm UI 테스트는 아직 없다. 실행 바이너리가 없는 라이브러리 모듈이라 CMP-4906 검사가 빌드를 막으므로 끈다.
tasks.matching { it.name.startsWith("checkComposeUiTestConfiguration") }.configureEach { enabled = false }
