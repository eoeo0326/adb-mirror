package io.github.eoeo0326.adbmirror.core.data.update

import io.github.eoeo0326.adbmirror.core.data.settings.SettingsLocation
import io.github.eoeo0326.adbmirror.core.domain.model.AppVersion
import io.github.eoeo0326.adbmirror.core.domain.model.InstallKind
import java.io.File

/**
 * 지금 실행 중인 앱의 설치 형태와 교체할 위치.
 * - [root]: macOS는 `.app` 번들, 포터블은 `portable` 표식이 있는 폴더, 그 밖에는 null
 * - [executable]: 업데이트 뒤 다시 열 실행 파일
 */
data class UpdateTarget(val kind: InstallKind, val arch: String, val root: File?, val executable: File?) {
    /** 이 설치 형태가 받을 릴리즈 파일 이름. 빌드(`composeApp/build.gradle.kts`의 `distBase`)와 같은 규칙이다. */
    fun assetName(version: AppVersion): String? {
        val base = "ADB-Mirror-$version"
        return when (kind) {
            InstallKind.MacApp -> "$base-macos-$arch.dmg"
            InstallKind.WindowsMsi -> "$base-windows-x64.msi"
            InstallKind.WindowsPortable -> "$base-windows-x64-portable.zip"
            InstallKind.LinuxDeb -> "$base-linux-$arch.deb"
            InstallKind.LinuxRpm -> "$base-linux-$arch.rpm"
            InstallKind.LinuxPortable -> "$base-linux-$arch-portable.tar.gz"
            InstallKind.Unsupported -> null
        }
    }

    companion object {
        fun detect(
            osName: String = System.getProperty("os.name").orEmpty(),
            osArch: String = System.getProperty("os.arch").orEmpty(),
            /** jpackage 런처가 넣어 주는 실행 파일 경로. Gradle로 실행하면 없다. */
            appPath: String? = System.getProperty("jpackage.app-path"),
            exists: (File) -> Boolean = File::exists,
        ): UpdateTarget {
            val arch = if (osArch in setOf("aarch64", "arm64")) "arm64" else "x64"
            val os = osName.lowercase()
            val exe = appPath?.takeIf { it.isNotBlank() }?.let { File(it).absoluteFile }
                ?: return UpdateTarget(InstallKind.Unsupported, arch, null, null)
            if (os.startsWith("mac")) {
                // …/ADB Mirror.app/Contents/MacOS/ADB Mirror
                val bundle = exe.parentFile?.parentFile?.parentFile?.takeIf { it.name.endsWith(".app") }
                    ?: return UpdateTarget(InstallKind.Unsupported, arch, null, exe)
                return UpdateTarget(InstallKind.MacApp, arch, bundle, exe)
            }
            val exeDir = exe.parentFile
            // SettingsLocation과 같은 규칙: 실행 파일 폴더(Linux는 bin/의 부모)에 portable 표식이 있으면 포터블이다.
            val portableRoot = listOfNotNull(exeDir, exeDir?.parentFile).firstOrNull { exists(File(it, SettingsLocation.PORTABLE_MARKER)) }
            val windows = os.startsWith("windows")
            return when {
                portableRoot != null -> UpdateTarget(if (windows) InstallKind.WindowsPortable else InstallKind.LinuxPortable, arch, portableRoot, exe)
                windows -> UpdateTarget(InstallKind.WindowsMsi, arch, null, exe)
                exists(File("/etc/debian_version")) -> UpdateTarget(InstallKind.LinuxDeb, arch, null, exe)
                else -> UpdateTarget(InstallKind.LinuxRpm, arch, null, exe)
            }
        }
    }
}
