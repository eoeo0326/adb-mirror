package io.github.eoeo0326.adbmirror.core.data.app

import io.github.eoeo0326.adbmirror.core.adb.AdbException
import io.github.eoeo0326.adbmirror.core.data.mirror.FakeAdbTransport
import io.github.eoeo0326.adbmirror.core.domain.model.InstallResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ApkInstallerTest {
    private val transport = FakeAdbTransport()
    private val installer = ApkInstaller(transport) { ByteArray(4) }

    @Test
    fun pushesInstallsAndRemoves() = runTest {
        transport.shellReply = { if (it.first() == "pm") "Performing Streamed Install\nSuccess" else "" }
        assertEquals(InstallResult.Success, installer.install("S1", "/Users/me/app debug(1).apk"))
        val remote = "/data/local/tmp/adb-mirror-app_debug_1_.apk"
        assertEquals(listOf("S1" to remote), transport.pushed)
        assertEquals(listOf(listOf("pm", "install", "-r", "-t", remote), listOf("rm", "-f", remote)), transport.shellCommands)
    }

    @Test
    fun retriesWithoutTestOnlyOptionOnOldPm() = runTest {
        transport.shellReply = { cmd ->
            when {
                "-t" in cmd -> "Error: Unknown option: -t"
                cmd.first() == "pm" -> "Success"
                else -> ""
            }
        }
        assertEquals(InstallResult.Success, installer.install("S1", "a.apk"))
        assertEquals(listOf("pm", "install", "-r", "/data/local/tmp/adb-mirror-a.apk"), transport.shellCommands[1])
    }

    @Test
    fun failureReasonComesFromPmEvenWhenShellFails() = runTest {
        transport.shellReply = { cmd ->
            if (cmd.first() == "pm") throw AdbException("adb shell 실패 (exit 1): Failure [INSTALL_FAILED_VERSION_DOWNGRADE: Downgrade detected]") else ""
        }
        assertEquals(InstallResult.Failure("INSTALL_FAILED_VERSION_DOWNGRADE: Downgrade detected"), installer.install("S1", "a.apk"))
        assertEquals(listOf("rm", "-f", "/data/local/tmp/adb-mirror-a.apk"), transport.shellCommands.last())
    }

    @Test
    fun parsesPmOutput() {
        assertEquals(PmInstallOutput.Success, PmInstallOutput.parse("Success\n"))
        assertEquals(PmInstallOutput.Failure("INSTALL_FAILED_INSUFFICIENT_STORAGE"), PmInstallOutput.parse("Failure [INSTALL_FAILED_INSUFFICIENT_STORAGE]"))
        assertEquals(PmInstallOutput.UnknownOption, PmInstallOutput.parse("Error: Unknown option: -t"))
        assertEquals(PmInstallOutput.Failure("설치 결과를 알 수 없습니다"), PmInstallOutput.parse(""))
    }
}
