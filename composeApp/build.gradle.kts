import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask

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
        getByName("desktopTest").dependencies {
            implementation(kotlin("test"))
        }
    }
}

// 웹앱은 scrcpy-server를 함께 배포하고 fetch로 받는다(core/data의 fetchScrcpyServer 결과 폴더).
kotlin.sourceSets.getByName("wasmJsMain").resources.srcDir(project(":core:data").layout.buildDirectory.dir("generated/scrcpy-server"))
tasks.matching { it.name == "wasmJsProcessResources" }.configureEach { dependsOn(":core:data:fetchScrcpyServer") }

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
            // WiX 3(MSI)는 기본 코드 페이지 1252라 한글을 넣으면 빌드가 실패한다. 설치 파일 메타데이터는 영어로 둔다.
            description = "Mirror, screenshot and record Android device screens over adb"
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
                infoPlist {
                    // macOS 26 이상은 Contents/Resources/Assets.car의 AppIcon을, 이전 버전은 CFBundleIconFile(icns)을 쓴다.
                    extraKeysRawXml = """
                        <key>CFBundleIconName</key>
                        <string>AppIcon</string>
                    """.trimIndent()
                }
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

// ── 배포 파일 모으기 ─────────────────────────────────────────────
// build/release/ 에 `ADB-Mirror-<appVersion>-<os>-<arch>.<확장자>` 이름으로 모은다(CI Release가 이것을 올린다).
// 설치 파일은 그 OS에서만 만들 수 있다. -Pdist.os=windows|linux|macos 로 바꾸면 이 머신의 결과물로 이름·구조만 확인할 수 있다.

val distOs: String = providers.gradleProperty("dist.os").orNull ?: System.getProperty("os.name").lowercase().let {
    when {
        it.contains("mac") -> "macos"
        it.contains("win") -> "windows"
        else -> "linux"
    }
}
val distArch: String = if (System.getProperty("os.arch") in setOf("aarch64", "arm64")) "arm64" else "x64"
val distBase = "ADB-Mirror-$appVersion-$distOs-$distArch"
val distDir = layout.buildDirectory.dir("release")

// Compose가 패키지 작업을 afterEvaluate에서 등록하므로 그 뒤에 잇는다.
afterEvaluate {
    val appImageDir = tasks.named<AbstractJPackageTask>("createDistributable").flatMap { it.destinationDir }

    // Contents/Resources는 macOS 앱 번들 구조라, -Pdist.os와 무관하게 실제로 macOS에서 빌드할 때만 붙인다.
    if (System.getProperty("os.name").lowercase().contains("mac")) {
        tasks.named<AbstractJPackageTask>("createDistributable") {
            // jpackage가 이 폴더를 Contents/Resources에 합친 뒤 서명하므로 Assets.car도 서명 범위에 든다.
            val macAppContent = rootProject.file("packaging/macos/Resources")
            inputs.dir(macAppContent).withPropertyName("macAppContent").withPathSensitivity(PathSensitivity.RELATIVE)
            // freeArgs는 인자 파일에 따옴표 없이 들어가므로 공백이 있는 경로를 위해 직접 감싼다.
            freeArgs.addAll("--app-content", "\"${macAppContent.absolutePath}\"")
        }
    }

    /**
     * 포터블 배포본: 설치 없이 풀어서 쓰는 앱 이미지 + `portable` 표식 파일. Windows는 zip, Linux는 tar.gz.
     * macOS는 만들지 않는다. .app 안에 쓰면 서명이 깨지고, 받은 앱은 읽기 전용 위치(App Translocation)에서 돌기 때문이다.
     */
    val packagePortableZip = tasks.register<Zip>("packagePortableZip") {
        group = "distribution"
        description = "Windows 포터블 zip"
        enabled = distOs == "windows"
        dependsOn("createDistributable")
        archiveFileName.set("$distBase-portable.zip")
        destinationDirectory.set(distDir)
        useFileSystemPermissions()
        from(appImageDir)
        // 앱 이미지 폴더 이름은 packageName("ADB Mirror")이다. 실행 파일 옆에 둔다.
        from(rootProject.file("packaging/portable")) { into("ADB Mirror") }
    }

    val packagePortableTarGz = tasks.register<Tar>("packagePortableTarGz") {
        group = "distribution"
        description = "Linux 포터블 tar.gz"
        enabled = distOs == "linux"
        dependsOn("createDistributable")
        compression = Compression.GZIP
        archiveFileName.set("$distBase-portable.tar.gz")
        destinationDirectory.set(distDir)
        // Gradle은 기본으로 권한을 정규화한다. bin/·runtime/bin/ 실행 파일의 +x를 지키려면 파일 시스템 권한을 쓴다.
        useFileSystemPermissions()
        from(appImageDir)
        // 앱 이미지 루트(bin/ 위)에 둔다. SettingsLocation이 실행 파일 폴더의 부모도 본다.
        from(rootProject.file("packaging/portable")) { into("ADB Mirror") }
    }

    /** 이 OS의 설치 파일을 만들어 배포 이름으로 복사한다. */
    val stageInstallers = tasks.register<Copy>("stageInstallers") {
        group = "distribution"
        description = "이 OS의 설치 파일(dmg·msi·deb·rpm)을 build/release/로 모은다"
        val packageTasks = when (distOs) {
            "macos" -> listOf("packageDmg")
            "windows" -> listOf("packageMsi")
            else -> listOf("packageDeb", "packageRpm")
        }
        dependsOn(packageTasks)
        // 공증(-Pcompose.desktop.mac.notarization.appleID)할 때는 스테이플까지 끝난 dmg를 모은다.
        // 공증이 dmg를 제자리에서 고치므로, 같은 호출 안에서 그 뒤에 복사해야 한다.
        if (distOs == "macos" && providers.gradleProperty("compose.desktop.mac.notarization.appleID").isPresent) {
            dependsOn("notarizeDmg")
        }
        into(distDir)
        packageTasks.forEach { name ->
            val ext = name.removePrefix("package").lowercase()
            from(tasks.named<AbstractJPackageTask>(name).flatMap { it.destinationDir }) {
                include("*.$ext")
                rename(".*", "$distBase.$ext") // 문자열 규칙이라 스크립트 객체를 붙잡지 않는다(구성 캐시)
            }
        }
    }

    tasks.register("packageDistributions") {
        group = "distribution"
        description = "설치 파일과 포터블 배포본을 build/release/에 만든다"
        dependsOn(stageInstallers, packagePortableZip, packagePortableTarGz)
    }
}
