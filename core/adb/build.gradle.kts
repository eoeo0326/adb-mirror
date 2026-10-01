plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
}

kotlin {
    jvmToolchain(21)
    jvm()
    android {
        namespace = "io.github.eoeo0326.adbmirror.core.adb"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs { browser() }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        androidMain.dependencies {
            // 기기 안에서 다른 기기(또는 자기 자신)에 adb로 붙는다: 무선 페어링·연결·스트림
            implementation(libs.kadb)
        }
    }
}

// 실기기 확인(AdbConnectionDeviceTest): -Padbmirror.tcp=<ip>:5555
tasks.withType<Test>().configureEach {
    systemProperty("adbmirror.tcp", providers.gradleProperty("adbmirror.tcp").getOrElse(""))
    systemProperty(
        "adbmirror.tcpKey",
        providers.gradleProperty("adbmirror.tcpKey").getOrElse(layout.buildDirectory.file("adb-test-key").get().asFile.absolutePath),
    )
}
