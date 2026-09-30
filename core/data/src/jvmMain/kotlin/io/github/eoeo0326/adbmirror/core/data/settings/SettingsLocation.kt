package io.github.eoeo0326.adbmirror.core.data.settings

import java.io.File

/**
 * 설정 파일을 둘 폴더.
 * - 실행 파일 폴더(또는 그 부모, Linux 앱 이미지의 `bin/` 위)에 `portable` 파일이 있으면 그 옆의 `data/` (포터블)
 * - 아니면 OS 표준 위치: macOS `~/Library/Application Support/ADB Mirror`, Windows `%APPDATA%\ADB Mirror`,
 *   Linux `$XDG_CONFIG_HOME/adb-mirror`(없으면 `~/.config/adb-mirror`)
 * - `ADB_MIRROR_DATA_DIR`이 있으면 그것을 먼저 쓴다(개발·테스트용).
 */
data class SettingsLocation(val dir: File, val portable: Boolean) {
    val settingsFile: File get() = File(dir, "settings.properties")

    companion object {
        const val PORTABLE_MARKER = "portable"
        private const val APP_NAME = "ADB Mirror"

        fun resolve(
            env: Map<String, String> = System.getenv(),
            osName: String = System.getProperty("os.name").orEmpty(),
            home: String = System.getProperty("user.home").orEmpty(),
            /** jpackage 런처가 넣어 주는 실행 파일 경로. Gradle로 실행하면 없다. */
            appPath: String? = System.getProperty("jpackage.app-path"),
            exists: (File) -> Boolean = File::isFile,
        ): SettingsLocation {
            env["ADB_MIRROR_DATA_DIR"]?.takeIf { it.isNotBlank() }?.let { return SettingsLocation(File(it), portable = false) }
            appPath?.takeIf { it.isNotBlank() }?.let { File(it).absoluteFile.parentFile }?.let { exeDir ->
                listOfNotNull(exeDir, exeDir.parentFile)
                    .firstOrNull { exists(File(it, PORTABLE_MARKER)) }
                    ?.let { return SettingsLocation(File(it, "data"), portable = true) }
            }
            val os = osName.lowercase()
            val dir = when {
                os.startsWith("mac") -> File(home, "Library/Application Support/$APP_NAME")
                os.startsWith("windows") -> File(env["APPDATA"]?.takeIf { it.isNotBlank() } ?: "$home\\AppData\\Roaming", APP_NAME)
                else -> File(env["XDG_CONFIG_HOME"]?.takeIf { it.isNotBlank() } ?: "$home/.config", "adb-mirror")
            }
            return SettingsLocation(dir, portable = false)
        }
    }
}
