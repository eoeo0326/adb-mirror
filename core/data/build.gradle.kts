plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
}

kotlin {
    jvmToolchain(21)
    jvm()
    android {
        namespace = "io.github.eoeo0326.adbmirror.core.data"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs { browser() }

    sourceSets {
        commonMain.dependencies {
            api(projects.core.domain)
            api(projects.core.adb)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

// jvmTest의 fixture 테스트가 레포 루트의 fixtures/를 읽는다.
tasks.withType<Test>().configureEach {
    systemProperty("fixtures.dir", rootProject.layout.projectDirectory.dir("fixtures").asFile.absolutePath)
}
