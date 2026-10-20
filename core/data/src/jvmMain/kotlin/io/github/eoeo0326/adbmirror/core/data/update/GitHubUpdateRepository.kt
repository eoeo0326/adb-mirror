package io.github.eoeo0326.adbmirror.core.data.update

import io.github.eoeo0326.adbmirror.core.domain.model.DownloadedUpdate
import io.github.eoeo0326.adbmirror.core.domain.model.InstallKind
import io.github.eoeo0326.adbmirror.core.domain.model.UpdateInfo
import io.github.eoeo0326.adbmirror.core.domain.repository.UpdateRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** 업데이트에 쓰는 HTTP. 테스트에서 바꿔 끼운다. */
interface UpdateHttp {
    /** 리다이렉트를 따라가지 않고 `Location` 헤더를 돌려준다. 리다이렉트가 아니면 null. */
    fun redirectLocation(url: String): String?

    fun getText(url: String): String

    /** 본문 스트림과 길이(모르면 -1). */
    fun <T> stream(url: String, block: (InputStream, Long) -> T): T
}

/** `java.base`의 [HttpURLConnection]만 쓴다(jlink 런타임에 `java.net.http`가 없다). */
object UrlConnectionHttp : UpdateHttp {
    private const val TIMEOUT_MS = 15_000

    private fun open(url: String, follow: Boolean): HttpURLConnection =
        (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = follow
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("User-Agent", "ADB-Mirror-Updater")
        }

    override fun redirectLocation(url: String): String? {
        val c = open(url, follow = false)
        try {
            return if (c.responseCode in 300..399) c.getHeaderField("Location") else null
        } finally {
            c.disconnect()
        }
    }

    override fun getText(url: String): String = stream(url) { input, _ -> input.readBytes().toString(Charsets.UTF_8) }

    override fun <T> stream(url: String, block: (InputStream, Long) -> T): T {
        val c = open(url, follow = true)
        try {
            val code = c.responseCode
            if (code !in 200..299) throw IllegalStateException("서버 응답 $code ($url)")
            return c.inputStream.use { block(it, c.contentLengthLong) }
        } finally {
            c.disconnect()
        }
    }
}

/**
 * GitHub Releases로 업데이트한다. 받은 파일은 [dir]에 두고, 릴리즈의 `SHA256SUMS`와 맞을 때만 쓴다.
 * 설치는 앱이 끝난 뒤 도는 스크립트([UpdateScripts])를 띄워서 한다.
 */
class GitHubUpdateRepository(
    private val target: UpdateTarget,
    private val dir: File,
    private val releases: GitHubReleases = GitHubReleases(),
    private val http: UpdateHttp = UrlConnectionHttp,
    private val pid: Long = ProcessHandle.current().pid(),
    private val launch: (List<String>, File) -> Unit = ::launchDetached,
) : UpdateRepository {
    override val installKind: InstallKind get() = target.kind

    override suspend fun latest(): UpdateInfo = withContext(Dispatchers.IO) {
        val location = http.redirectLocation(releases.latestUrl) ?: error("최신 릴리즈를 찾지 못했습니다")
        val version = GitHubReleases.versionFromLocation(location) ?: error("최신 릴리즈 버전을 읽지 못했습니다: $location")
        UpdateInfo(version, releases.notesUrl(version))
    }

    override suspend fun download(info: UpdateInfo, onProgress: (Float?) -> Unit): DownloadedUpdate = withContext(Dispatchers.IO) {
        val name = target.assetName(info.version) ?: error("이 실행 방식에서는 업데이트를 내려받을 수 없습니다")
        val expected = GitHubReleases.parseSha256Sums(http.getText(releases.assetUrl(info.version, "SHA256SUMS")))[name]
            ?: error("SHA256SUMS에 $name 이(가) 없습니다")
        dir.mkdirs()
        val file = File(dir, name)
        // 전에 받아 둔 파일이 맞으면 다시 받지 않는다.
        if (file.isFile && sha256(file) == expected) return@withContext DownloadedUpdate(info, file.path)
        val part = File(dir, "$name.part")
        try {
            http.stream(releases.assetUrl(info.version, name)) { input, length ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        ensureActive() // 취소되면(앱 종료 등) 받기를 멈춘다
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        total += n
                        onProgress(if (length > 0) (total.toFloat() / length).coerceIn(0f, 1f) else null)
                    }
                }
            }
            ensureActive()
            val actual = sha256(part)
            check(actual == expected) { "내려받은 파일이 릴리즈와 다릅니다(SHA-256 불일치)" }
            Files.move(part.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            part.delete()
        }
        // 지난 버전 파일은 지운다.
        dir.listFiles()?.filter { it.isFile && it.name.startsWith("ADB-Mirror-") && it.name != name }?.forEach { it.delete() }
        DownloadedUpdate(info, file.path)
    }

    override suspend fun install(update: DownloadedUpdate) = withContext(Dispatchers.IO) {
        val file = File(update.file)
        check(file.isFile) { "내려받은 파일이 없습니다: ${file.path}" }
        val exe = target.executable
        when (target.kind) {
            InstallKind.MacApp -> {
                val bundle = target.root ?: error("앱 위치를 알 수 없습니다")
                if (!bundle.parentFile.canWrite()) {
                    // 응용 프로그램 폴더에 쓸 수 없으면 설치 파일을 열어 사용자가 직접 옮기게 한다.
                    ProcessBuilder("open", file.path).start()
                    error("${bundle.parent}에 쓸 수 없어 설치 파일을 열었습니다. 앱을 응용 프로그램 폴더로 끌어다 놓아 바꿔 주세요")
                }
                run("update.sh", UpdateScripts.mac(pid, file, bundle), shell = listOf("/bin/bash"))
            }
            InstallKind.WindowsMsi ->
                run("update.ps1", UpdateScripts.windowsMsi(pid, file, exe ?: error("실행 파일 위치를 알 수 없습니다")), shell = POWERSHELL)
            InstallKind.WindowsPortable -> run(
                "update.ps1",
                UpdateScripts.windowsPortable(pid, file, target.root ?: error("앱 폴더를 알 수 없습니다"), exe ?: error("실행 파일 위치를 알 수 없습니다")),
                shell = POWERSHELL,
            )
            InstallKind.LinuxPortable -> run(
                "update.sh",
                UpdateScripts.linuxPortable(pid, file, target.root ?: error("앱 폴더를 알 수 없습니다"), exe ?: error("실행 파일 위치를 알 수 없습니다")),
                shell = listOf("/bin/bash"),
            )
            InstallKind.LinuxDeb, InstallKind.LinuxRpm, InstallKind.Unsupported -> error("이 설치 형태는 앱에서 직접 설치할 수 없습니다")
        }
    }

    override fun manualCommand(update: DownloadedUpdate): String {
        val path = UpdateScripts.sh(update.file)
        return when (target.kind) {
            InstallKind.LinuxDeb -> "sudo apt install $path"
            InstallKind.LinuxRpm -> "sudo dnf install $path"
            else -> update.file
        }
    }

    private fun run(name: String, script: String, shell: List<String>) {
        val file = File(dir, name)
        // Windows PowerShell 5.1은 BOM 없는 스크립트를 시스템 코드페이지(예: cp949)로 읽어 한글 경로가 깨진다.
        if (name.endsWith(".ps1")) file.writeText("\uFEFF" + script, Charsets.UTF_8) else file.writeText(script, Charsets.UTF_8)
        launch(shell + file.path, File(dir, "update.log"))
    }

    private companion object {
        val POWERSHELL = listOf("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-File")

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        /** 앱이 끝나도 계속 도는 프로세스로 띄운다. 출력은 [log]에 남긴다. */
        fun launchDetached(command: List<String>, log: File) {
            ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
                .start()
        }
    }
}
