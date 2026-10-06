package io.github.eoeo0326.adbmirror.core.data.app

import io.github.eoeo0326.adbmirror.core.adb.AdbException
import io.github.eoeo0326.adbmirror.core.adb.AdbTransport
import io.github.eoeo0326.adbmirror.core.domain.model.InstallResult
import io.github.eoeo0326.adbmirror.core.domain.repository.AppRepository
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * APK를 기기 임시 폴더에 올린 뒤 `pm install`로 설치하고 지운다. [AdbTransport]의 push·shell만 쓰므로
 * adb 실행 파일(Desktop)·Kadb·WebUSB 어느 전송에서도 같다. [readFile]은 플랫폼이 APK 파일을 읽는 방법이다.
 */
class ApkInstaller(
    private val transport: AdbTransport,
    private val readFile: suspend (path: String) -> ByteArray,
) : AppRepository {
    override suspend fun install(serial: String, apkPath: String): InstallResult {
        val name = apkPath.substringAfterLast('/').substringAfterLast('\\')
        val remote = "$REMOTE_DIR/adb-mirror-" + name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        transport.push(serial, readFile(apkPath), remote)
        try {
            // -t: Android Studio로 만든 테스트용(testOnly) APK도 받는다. Android 6 이하 pm은 이 옵션을 몰라 빼고 다시 한다.
            val first = PmInstallOutput.parse(pm(serial, listOf("pm", "install", "-r", "-t", remote)))
            val output = if (first == PmInstallOutput.UnknownOption) PmInstallOutput.parse(pm(serial, listOf("pm", "install", "-r", remote))) else first
            return when (output) {
                PmInstallOutput.Success -> InstallResult.Success
                is PmInstallOutput.Failure -> InstallResult.Failure(output.reason)
                PmInstallOutput.UnknownOption -> InstallResult.Failure("이 기기의 pm이 설치 옵션을 지원하지 않습니다")
            }
        } finally {
            withContext(NonCancellable) { runCatching { transport.shell(serial, listOf("rm", "-f", remote)) } }
        }
    }

    /** 실패해도 출력을 돌려준다(adb 버전에 따라 pm의 실패 exit 코드가 [AdbException]으로 온다). */
    private suspend fun pm(serial: String, command: List<String>): String = try {
        transport.shell(serial, command)
    } catch (e: AdbException) {
        e.message.orEmpty()
    }

    private companion object {
        const val REMOTE_DIR = "/data/local/tmp"
    }
}

/** `pm install` 출력 해석. */
internal sealed interface PmInstallOutput {
    data object Success : PmInstallOutput
    data object UnknownOption : PmInstallOutput
    data class Failure(val reason: String) : PmInstallOutput

    companion object {
        private val FAILURE = Regex("""Failure \[([^\]]+)]""")

        fun parse(output: String): PmInstallOutput {
            val lines = output.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
            if (lines.any { it == "Success" }) return Success
            if (lines.any { it.contains("Unknown option", ignoreCase = true) }) return UnknownOption
            FAILURE.find(output)?.let { return Failure(it.groupValues[1]) }
            return Failure(lines.lastOrNull() ?: "설치 결과를 알 수 없습니다")
        }
    }
}
