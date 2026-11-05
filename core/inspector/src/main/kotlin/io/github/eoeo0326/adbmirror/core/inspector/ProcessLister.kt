package io.github.eoeo0326.adbmirror.core.inspector

import io.github.eoeo0326.adbmirror.core.domain.model.InspectableProcess

/**
 * 기기의 debuggable 앱 프로세스 목록. 데몬은 `GetProcesses`를 구현하지 않으므로(Android Studio는 ddmlib로 구한다)
 * adb 셸로 만든다.
 *
 * - 앱 프로세스: `ps`에서 사용자가 `u<N>_a<M>`인 것. 부모가 `zygote64`면 64비트, `zygote`면 32비트다.
 * - debuggable: 패키지마다 `run-as <pkg> true`가 성공하는지(한 번의 셸 호출로 묶는다).
 * - 포그라운드: `dumpsys activity activities`의 `topResumedActivity`(없으면 `mResumedActivity`) 패키지.
 */
class ProcessLister(private val shell: DeviceShell) {
    /** [abis]는 기기 ABI 목록(기기 순서). 프로세스 비트에 맞는 첫 ABI로 에이전트 아키텍처를 고른다. */
    suspend fun debuggable(serial: String, abis: List<String>): List<InspectableProcess> {
        val apps = parsePs(shell.shell(serial, "ps -A -o PID,PPID,USER,NAME"))
        if (apps.processes.isEmpty()) return emptyList()
        // 기기에서 읽은 이름을 셸에 넣으므로 패키지 이름 형식만 통과시킨다.
        val packages = apps.processes.map { it.packageName }.distinct().filter { PACKAGE_NAME.matches(it) }
        val script = packages.joinToString("; ") { "run-as $it true >/dev/null 2>&1 && echo $it" } + "; true"
        val debuggable = shell.shell(serial, script).lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val foreground = foregroundPackage(shell.shell(serial, "dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity' || true"))
        return apps.processes.filter { it.packageName in debuggable }.mapNotNull { p ->
            val cpuArch = cpuArch(abis, is64 = p.ppid == apps.zygote64) ?: return@mapNotNull null
            InspectableProcess(p.pid, p.packageName, p.name, cpuArch, foreground = p.packageName == foreground && p.name == p.packageName)
        }.sortedWith(compareByDescending<InspectableProcess> { it.foreground }.thenBy { it.name })
    }

    /** 프로세스가 아직 살아 있는지. */
    suspend fun isAlive(serial: String, pid: Int): Boolean =
        shell.shell(serial, "[ -d /proc/$pid ] && echo alive || true").trim() == "alive"

    internal data class AppProcess(val pid: Int, val ppid: Int, val name: String) {
        val packageName get() = name.substringBefore(':')
    }

    internal class PsResult(val processes: List<AppProcess>, val zygote64: Int?, val zygote32: Int?)

    companion object {
        private val APP_USER = Regex("^u\\d+_a\\d+$")
        private val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

        internal fun parsePs(output: String): PsResult {
            var zygote64: Int? = null
            var zygote32: Int? = null
            val apps = mutableListOf<Pair<AppProcess, Int>>()
            output.lineSequence().drop(1).forEach { line ->
                val cols = line.trim().split(Regex("\\s+"), limit = 4)
                if (cols.size < 4) return@forEach
                val pid = cols[0].toIntOrNull() ?: return@forEach
                val ppid = cols[1].toIntOrNull() ?: return@forEach
                when {
                    cols[3] == "zygote64" -> zygote64 = pid
                    cols[3] == "zygote" -> zygote32 = pid
                    APP_USER.matches(cols[2]) -> apps += AppProcess(pid, ppid, cols[3]) to ppid
                }
            }
            // zygote에서 갈라진 프로세스만 앱으로 본다(웹뷰 zygote의 렌더러 등은 제외).
            val parents = setOfNotNull(zygote64, zygote32)
            return PsResult(apps.filter { it.second in parents }.map { it.first }, zygote64, zygote32)
        }

        private val ACTIVITY = Regex("(?:topResumedActivity|mResumedActivity)[=:].*?\\s([\\w.]+)/")

        /** `topResumedActivity`를 먼저 보고, 없으면 `mResumedActivity`를 본다. */
        internal fun foregroundPackage(dumpsys: String): String? {
            val lines = dumpsys.lines()
            val line = lines.firstOrNull { "topResumedActivity" in it } ?: lines.firstOrNull { "mResumedActivity" in it } ?: return null
            return ACTIVITY.find(line)?.groupValues?.get(1)
        }

        private val ABI_64 = setOf("arm64-v8a", "x86_64")

        internal fun cpuArch(abis: List<String>, is64: Boolean): String? =
            abis.firstOrNull { (it in ABI_64) == is64 }?.let(DeviceFiles::cpuArch)
    }
}
