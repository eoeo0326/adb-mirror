package io.github.eoeo0326.adbmirror.core.inspector

import java.io.File

/** 찾은 Android Studio 설치 하나. 기기에 올릴 transport·에이전트·inspector 파일 위치를 안다. */
class StudioInstall(
    /** 사용자가 고르는 단위(macOS는 `.app`, 그 밖은 설치 폴더). */
    val root: File,
    /** `plugins/android/resources`. */
    val resources: File,
    /** `product-info.json`의 `buildNumber`(예: `262.9437.185.2621.16467767`). */
    val buildNumber: String,
    /** 상태줄에 보일 이름(예: `Android Studio 2026.2.1`). */
    val label: String,
) {
    fun daemon(abi: String) = File(resources, "transport/$abi/transport")
    fun agent(abi: String) = File(resources, "transport/native/agent/$abi/libjvmtiagent.so")
    val perfaJar get() = File(resources, "perfa.jar")
    val viewInspectorJar get() = File(resources, "app-inspection/layoutinspector-view-inspection.jar")

    /** 기기에 올리는 데 필요한 파일 전부. */
    fun requiredFiles(): List<File> =
        listOf(perfaJar, viewInspectorJar) + DeviceFiles.ABIS.flatMap { listOf(daemon(it), agent(it)) }

    override fun toString() = "$label ($buildNumber) at $root"
}

/** 사용자 PC에 설치된 Android Studio를 찾는다. 기기 쪽 바이너리는 레포·배포물에 넣지 않고 여기서 가져다 쓴다. */
object StudioLocator {
    /** 기본 위치를 찾을 때 쓰는 환경. 테스트에서 바꿔 끼운다. */
    class Environment(val osName: String, val home: File, val env: Map<String, String>) {
        val isMac get() = osName.lowercase().startsWith("mac")
        val isWindows get() = osName.lowercase().startsWith("windows")

        companion object {
            fun current() = Environment(System.getProperty("os.name"), File(System.getProperty("user.home")), System.getenv())
        }
    }

    /**
     * [configuredPath](설정의 Android Studio 위치)가 쓸 수 있는 설치면 그것을, 아니면 플랫폼 기본 위치 중
     * 필요한 파일이 모두 있는 설치 가운데 `buildNumber`가 가장 큰 것을 돌려준다. 없으면 null.
     */
    fun locate(configuredPath: String? = null, environment: Environment = Environment.current()): StudioInstall? {
        configuredPath?.takeIf { it.isNotBlank() }?.let { inspect(File(it)) }?.let { return it }
        return candidates(environment).mapNotNull(::inspect).maxWithOrNull(compareBy(BuildNumberComparator) { it.buildNumber })
    }

    /** [root]가 쓸 수 있는 설치면 [StudioInstall]. `.app`, `.app/Contents`, 설치 폴더 어느 것을 줘도 된다. */
    fun inspect(root: File): StudioInstall? {
        val base = if (File(root, "Contents").isDirectory) File(root, "Contents") else root
        val productInfo = listOf(File(base, "Resources/product-info.json"), File(base, "product-info.json")).firstOrNull { it.isFile }
            ?: return null
        val json = runCatching { productInfo.readText() }.getOrNull() ?: return null
        val buildNumber = topLevelString(json, "buildNumber") ?: return null
        val install = StudioInstall(
            root = if (base.name == "Contents") base.parentFile else base,
            resources = File(base, "plugins/android/resources"),
            buildNumber = buildNumber,
            label = label(topLevelString(json, "dataDirectoryName"), buildNumber),
        )
        return install.takeIf { candidate -> candidate.requiredFiles().all { it.isFile } }
    }

    /** 플랫폼 기본 설치 위치들. 있는지·쓸 수 있는지는 [inspect]가 거른다. */
    fun candidates(environment: Environment): List<File> = with(environment) {
        when {
            isMac -> listOf(File("/Applications"), File(home, "Applications"))
                .flatMap { dir -> dir.listFiles { f -> f.name.startsWith("Android Studio") && f.name.endsWith(".app") }.orEmpty().sortedBy { it.name } }

            isWindows -> {
                val programFiles = env["ProgramFiles"]?.let { File(it, "Android/Android Studio") }
                val localAppData = env["LOCALAPPDATA"]?.let(::File)
                listOfNotNull(
                    programFiles,
                    localAppData?.let { File(it, "Programs/Android Studio") },
                ) + localAppData?.let { installsUnder(File(it, "JetBrains/Toolbox/apps/AndroidStudio")) }.orEmpty()
            }

            else -> listOf(File("/opt/android-studio"), File(home, "android-studio")) +
                File(home, ".local/share/JetBrains/Toolbox/apps").listFiles { f -> f.name.startsWith("android-studio") }.orEmpty()
                    .sortedBy { it.name }
                    .flatMap { listOf(it) + installsUnder(it) }
        }
    }

    /** JetBrains Toolbox처럼 채널·버전 폴더 아래 설치가 있는 경우, `product-info.json`이 있는 폴더를 찾는다. */
    private fun installsUnder(dir: File, depth: Int = 4): List<File> {
        if (!dir.isDirectory || depth < 0) return emptyList()
        if (File(dir, "product-info.json").isFile) return listOf(dir)
        return dir.listFiles { f -> f.isDirectory }.orEmpty().sortedBy { it.name }.flatMap { installsUnder(it, depth - 1) }
    }

    /**
     * `product-info.json`의 최상위 문자열 값. JSON 라이브러리를 들이지 않으려고 정규식으로 읽는다.
     * 최상위 키가 플러그인 목록(같은 이름의 키가 반복됨)보다 앞에 오므로 첫 번째 일치를 쓴다.
     */
    internal fun topLevelString(json: String, key: String): String? =
        Regex("\"${Regex.escape(key)}\"\\s*:\\s*\"([^\"]*)\"").find(json)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }

    /** `dataDirectoryName`(`AndroidStudio2026.2.1`)에서 사람이 읽는 버전을 뽑는다. `name`은 늘 "Android Studio"라 쓰지 않는다. */
    internal fun label(dataDirectoryName: String?, buildNumber: String): String {
        val version = dataDirectoryName?.removePrefix("AndroidStudio")?.takeIf { it.isNotEmpty() && it.first().isDigit() }
        return "Android Studio ${version ?: buildNumber}"
    }

    /** `262.9437.185.2621.16467767` 같은 점 구분 숫자를 자리별로 비교한다. 숫자가 아닌 자리는 0으로 본다. */
    internal object BuildNumberComparator : Comparator<String> {
        override fun compare(a: String, b: String): Int {
            val x = a.split('.').map { it.toLongOrNull() ?: 0 }
            val y = b.split('.').map { it.toLongOrNull() ?: 0 }
            for (i in 0 until maxOf(x.size, y.size)) {
                val c = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
                if (c != 0) return c
            }
            return 0
        }
    }
}
