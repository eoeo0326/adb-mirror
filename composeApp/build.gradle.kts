import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvmToolchain(21)
    jvm("desktop")
    android {
        namespace = "io.github.eoeo0326.adbmirror.app"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser { commonWebpackConfig { outputFileName = "adb-mirror.js" } }
        binaries.executable()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(projects.feature.mirror)
            implementation(projects.core.data)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.material3)
        }
        getByName("desktopMain").dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.swing)
        }
    }
}

/**
 * 패키지 버전은 MAJOR.MINOR.PATCH(`gradle.properties`의 appVersion).
 * macOS(jpackage)는 첫 숫자가 0이면 거부하므로, 1.0 전까지 macOS 패키지 메타데이터에만 첫 숫자를 1로 올려 쓴다(0.3.0 → 1.3.0).
 * Windows MSI는 MAJOR·MINOR ≤ 255, PATCH ≤ 65535를 넘지 않게 올린다.
 */
val appVersion: String = providers.gradleProperty("appVersion").get()
val macPackageVersion: String = appVersion.split('.').let { (major, minor, patch) ->
    if (major == "0") "1.$minor.$patch" else appVersion
}

compose.desktop {
    application {
        mainClass = "io.github.eoeo0326.adbmirror.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb, TargetFormat.Rpm)
            packageName = "ADB Mirror"
            packageVersion = appVersion
            description = "adb로 연결한 Android 기기 화면 미러링·스크린샷·녹화"
            vendor = "eoeo0326"
            copyright = "Copyright 2026 eoeo0326. Apache License 2.0."
            // licenseFile은 두지 않는다. 넣으면 dmg를 열 때마다 동의 창이 뜬다(Apache-2.0은 동의가 필요 없음).
            // suggestRuntimeModules 결과. JavaCPP(FFmpeg)가 jdk.unsupported를 쓴다.
            modules("java.instrument", "java.management", "jdk.unsupported")

            macOS {
                bundleID = "io.github.eoeo0326.adbmirror"
                dockName = "ADB Mirror"
                appCategory = "public.app-category.developer-tools"
                iconFile.set(rootProject.file("assets/icon/AppIcon.icns"))
                packageVersion = macPackageVersion
                dmgPackageVersion = macPackageVersion
                packageBuildVersion = macPackageVersion
            }
            windows {
                iconFile.set(rootProject.file("assets/icon/icon.ico"))
                menuGroup = "ADB Mirror"
                perUserInstall = true // 관리자 권한 없이 설치(%LOCALAPPDATA%)
                dirChooser = true
                shortcut = true
                // 한 번 정하면 바꾸지 않는다. 바뀌면 새 버전이 이전 설치를 업그레이드하지 못한다.
                upgradeUuid = "6f1c3b2a-8d4e-4c6b-9a57-2e1f0d8b7c43"
            }
            linux {
                iconFile.set(rootProject.file("assets/icon/png/icon_512.png"))
                packageName = "adb-mirror"
                menuGroup = "Development"
                appCategory = "Development"
                shortcut = true
                debMaintainer = "eoeo0326@gmail.com"
                rpmLicenseType = "ASL 2.0"
            }
        }
    }
}
