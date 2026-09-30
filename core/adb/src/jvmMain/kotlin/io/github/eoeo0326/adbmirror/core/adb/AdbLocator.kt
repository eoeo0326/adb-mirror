package io.github.eoeo0326.adbmirror.core.adb

import java.io.File

/**
 * adb 실행 파일 찾기. 순서: 설정 경로 → PATH → ANDROID_HOME / ANDROID_SDK_ROOT → OS 기본 SDK 위치.
 * 시스템 adb를 먼저 쓰는 이유: 버전이 다른 adb 클라이언트가 붙으면 떠 있는 adb 서버를 재시작해
 * Android Studio 연결이 끊긴다.
 */
object AdbLocator {
    fun locate(
        configuredPath: String? = null,
        env: Map<String, String> = System.getenv(),
        osName: String = System.getProperty("os.name"),
        home: String = System.getProperty("user.home"),
        isExecutable: (File) -> Boolean = { it.isFile && it.canExecute() },
    ): File? = candidates(configuredPath, env, osName, home).firstOrNull(isExecutable)

    internal fun candidates(configuredPath: String?, env: Map<String, String>, osName: String, home: String): List<File> {
        val windows = osName.startsWith("Windows", ignoreCase = true)
        val exe = if (windows) "adb.exe" else "adb"
        val pathSeparator = if (windows) ";" else ":"
        return buildList {
            configuredPath?.takeIf { it.isNotBlank() }?.let { add(File(it)) }
            env["PATH"]?.split(pathSeparator)?.filter { it.isNotBlank() }?.forEach { add(File(it, exe)) }
            listOf("ANDROID_HOME", "ANDROID_SDK_ROOT").forEach { key ->
                env[key]?.let { add(File(File(it, "platform-tools"), exe)) }
            }
            val defaultSdk = when {
                windows -> env["LOCALAPPDATA"]?.let { File(it, "Android/Sdk") }
                osName.startsWith("Mac", ignoreCase = true) -> File(home, "Library/Android/sdk")
                else -> File(home, "Android/Sdk")
            }
            defaultSdk?.let { add(File(File(it, "platform-tools"), exe)) }
        }
    }
}
