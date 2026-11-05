package io.github.eoeo0326.adbmirror.core.inspector

import io.github.eoeo0326.adbmirror.core.domain.model.InspectableProcess
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProcessListerTest {
    private val ps = """
          PID  PPID USER         NAME
            1     0 root         init
          874     1 root         zygote64
          875     1 root         zygote
         7129   874 u0_a523      com.example.debug
         7130   874 u0_a523      com.example.debug:remote
         7360   874 webview_zygote webview_zygote
         8000  7360 u0_i9        com.android.webview:sandboxed_process0
        16529   875 u0_a525      com.legacy.app
        20000   874 u0_a100      com.release.app
        20001  1234 u0_a101      com.not.from.zygote
    """.trimIndent()

    private val dumpsys = """
          topResumedActivity=ActivityRecord{2fcac2d u0 com.example.debug/.MainActivity t1497}
          mResumedActivity: ActivityRecord{1 u0 com.other/.A t1}
    """.trimIndent()

    private class Shell(val ps: String, val dumpsys: String, val debuggable: Set<String>) : DeviceShell by FakeDeviceShell() {
        val scripts = mutableListOf<String>()
        override suspend fun shell(serial: String, command: String): String = when {
            command.startsWith("ps ") -> ps
            command.startsWith("dumpsys") -> dumpsys
            command.startsWith("run-as") -> {
                scripts += command
                debuggable.filter { "run-as $it true" in command }.joinToString("\n")
            }
            else -> ""
        }
    }

    @Test
    fun listsDebuggableAppProcessesWithBitnessAndForeground() = runBlocking {
        val shell = Shell(ps, dumpsys, setOf("com.example.debug", "com.legacy.app"))
        val list = ProcessLister(shell).debuggable("S1", listOf("arm64-v8a", "armeabi-v7a"))
        assertEquals(
            listOf(
                InspectableProcess(7129, "com.example.debug", "com.example.debug", "arm64", foreground = true),
                InspectableProcess(7130, "com.example.debug", "com.example.debug:remote", "arm64"),
                InspectableProcess(16529, "com.legacy.app", "com.legacy.app", "arm"),
            ),
            list,
        )
        // 패키지마다 run-as를 한 번의 셸 호출로 묶는다.
        assertEquals(1, shell.scripts.size)
    }

    @Test
    fun foregroundFallsBackToResumedActivity() {
        assertEquals("com.other", ProcessLister.foregroundPackage("  mResumedActivity: ActivityRecord{1 u0 com.other/.A t1}"))
        assertNull(ProcessLister.foregroundPackage(""))
    }

    @Test
    fun skipsBitnessWithoutMatchingAbi() {
        assertNull(ProcessLister.cpuArch(listOf("arm64-v8a"), is64 = false))
        assertEquals("x86_64", ProcessLister.cpuArch(listOf("x86_64", "x86"), is64 = true))
    }

    @Test
    fun dropsNamesThatAreNotPackageNamesBeforeBuildingShellScript() = runBlocking {
        val evil = """
              PID  PPID USER         NAME
              874     1 root         zygote64
             9000   874 u0_a1        com.ok.app
             9001   874 u0_a2        x; reboot
             9002   874 u0_a3        com.bad$(id)
        """.trimIndent()
        val shell = Shell(evil, "", setOf("com.ok.app"))
        val list = ProcessLister(shell).debuggable("S1", listOf("arm64-v8a"))
        assertEquals(listOf("com.ok.app"), list.map { it.packageName })
        val script = shell.scripts.single()
        assertEquals("run-as com.ok.app true >/dev/null 2>&1 && echo com.ok.app; true", script)
    }
}
