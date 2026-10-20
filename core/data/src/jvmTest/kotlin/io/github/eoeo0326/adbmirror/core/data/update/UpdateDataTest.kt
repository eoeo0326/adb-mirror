package io.github.eoeo0326.adbmirror.core.data.update

import io.github.eoeo0326.adbmirror.core.domain.model.AppVersion
import io.github.eoeo0326.adbmirror.core.domain.model.InstallKind
import io.github.eoeo0326.adbmirror.core.domain.model.UpdateInfo
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateDataTest {
    private val v080 = AppVersion(0, 8, 0)
    private val dir: File = Files.createTempDirectory("update-test").toFile()

    @AfterTest fun cleanUp() { dir.deleteRecursively() }

    @Test
    fun latestVersionComesFromRedirectLocation() {
        assertEquals(v080, GitHubReleases.versionFromLocation("https://github.com/eoeo0326/adb-mirror/releases/tag/v0.8.0"))
        assertNull(GitHubReleases.versionFromLocation("https://github.com/eoeo0326/adb-mirror/releases"))
    }

    @Test
    fun sha256SumsParsesTextAndBinaryMode() {
        val hash = "a".repeat(64)
        val sums = GitHubReleases.parseSha256Sums("$hash  ADB-Mirror-0.8.0-macos-arm64.dmg\n${"B".repeat(64)} *SHA-bin.msi\n\nbroken line\n")
        assertEquals(hash, sums["ADB-Mirror-0.8.0-macos-arm64.dmg"])
        assertEquals("b".repeat(64), sums["SHA-bin.msi"])
        assertEquals(2, sums.size)
    }

    @Test
    fun detectsInstallKindAndAsset() {
        fun detect(os: String, arch: String, app: String?, vararg present: String) =
            UpdateTarget.detect(os, arch, app) { it.path.replace('\\', '/') in present.toSet() }

        val mac = detect("Mac OS X", "aarch64", "/Applications/ADB Mirror.app/Contents/MacOS/ADB Mirror")
        assertEquals(InstallKind.MacApp, mac.kind)
        assertEquals(File("/Applications/ADB Mirror.app"), mac.root)
        assertEquals("ADB-Mirror-0.8.0-macos-arm64.dmg", mac.assetName(v080))

        assertEquals("ADB-Mirror-0.8.0-macos-x64.dmg", detect("Mac OS X", "x86_64", "/Applications/ADB Mirror.app/Contents/MacOS/ADB Mirror").assetName(v080))
        assertEquals(InstallKind.Unsupported, detect("Mac OS X", "aarch64", null).kind, "Gradle로 실행하면 교체할 설치본이 없다")
        assertNull(detect("Linux", "amd64", null).assetName(v080))

        val msi = detect("Windows 11", "amd64", "/Users/a/AppData/Local/ADB Mirror/ADB Mirror.exe")
        assertEquals(InstallKind.WindowsMsi, msi.kind)
        assertEquals("ADB-Mirror-0.8.0-windows-x64.msi", msi.assetName(v080))

        // 테스트 호스트(macOS·Linux)에서도 절대 경로가 되도록 /로 시작하는 경로를 쓴다.
        val winPortable = detect("Windows 11", "amd64", "/tools/ADB Mirror/ADB Mirror.exe", "/tools/ADB Mirror/portable")
        assertEquals(InstallKind.WindowsPortable, winPortable.kind)
        assertEquals(File("/tools/ADB Mirror"), winPortable.root)
        assertEquals("ADB-Mirror-0.8.0-windows-x64-portable.zip", winPortable.assetName(v080))

        val linuxPortable = detect("Linux", "aarch64", "/opt/x/ADB Mirror/bin/ADB Mirror", "/opt/x/ADB Mirror/portable")
        assertEquals(InstallKind.LinuxPortable, linuxPortable.kind)
        assertEquals(File("/opt/x/ADB Mirror"), linuxPortable.root)
        assertEquals("ADB-Mirror-0.8.0-linux-arm64-portable.tar.gz", linuxPortable.assetName(v080))

        assertEquals("ADB-Mirror-0.8.0-linux-x64.deb", detect("Linux", "amd64", "/opt/adb-mirror/bin/ADB Mirror", "/etc/debian_version").assetName(v080))
        assertEquals("ADB-Mirror-0.8.0-linux-x64.rpm", detect("Linux", "amd64", "/opt/adb-mirror/bin/ADB Mirror").assetName(v080))
    }

    @Test
    fun scriptsQuotePathsWithSpacesAndQuotes() {
        assertEquals("'/a b/it'\\''s.app'", UpdateScripts.sh("/a b/it's.app"))
        assertEquals("'C:\\a b\\it''s.exe'", UpdateScripts.ps("C:\\a b\\it's.exe"))
        val mac = UpdateScripts.mac(42, File("/tmp/u/x.dmg"), File("/Applications/ADB Mirror.app"))
        assertTrue("pid=42" in mac)
        assertTrue("app='/Applications/ADB Mirror.app'" in mac)
        assertTrue("open \"\$app\"" in mac)
        val portable = UpdateScripts.windowsPortable(7, File("C:\\u\\x.zip"), File("D:\\ADB Mirror"), File("D:\\ADB Mirror\\ADB Mirror.exe"))
        assertTrue("Wait-Process -Id 7" in portable)
        assertTrue("/XD data" in portable, "포터블 설정 폴더는 덮어쓰지 않는다")
    }

    private class FakeHttp(val files: Map<String, ByteArray>, val location: String? = null) : UpdateHttp {
        val requested = mutableListOf<String>()
        override fun redirectLocation(url: String) = location
        override fun getText(url: String) = String(files.getValue(url.substringAfterLast('/')))
        override fun <T> stream(url: String, block: (InputStream, Long) -> T): T {
            requested += url.substringAfterLast('/')
            val bytes = files.getValue(url.substringAfterLast('/'))
            return block(ByteArrayInputStream(bytes), bytes.size.toLong())
        }
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private val mac = UpdateTarget(InstallKind.MacApp, "arm64", File("/Applications/ADB Mirror.app"), null)
    private val name = "ADB-Mirror-0.8.0-macos-arm64.dmg"
    private val info = UpdateInfo(v080, "notes")

    @Test
    fun downloadVerifiesHashAndReusesGoodFile() = runTest {
        val dmg = "dmg-bytes".toByteArray()
        val http = FakeHttp(mapOf(name to dmg, "SHA256SUMS" to "${sha(dmg)}  $name\n".toByteArray()))
        val repo = GitHubUpdateRepository(mac, dir, http = http)
        val progress = mutableListOf<Float?>()
        val got = repo.download(info) { progress += it }
        assertEquals(File(dir, name).path, got.file)
        assertEquals(1f, progress.last())
        assertFalse(File(dir, "$name.part").exists())

        repo.download(info) {}
        assertEquals(1, http.requested.size, "해시가 맞는 파일이 있으면 다시 받지 않는다")
    }

    @Test
    fun hashMismatchIsRejectedAndNothingIsLeft() = runTest {
        val http = FakeHttp(mapOf(name to "tampered".toByteArray(), "SHA256SUMS" to "${"0".repeat(64)}  $name\n".toByteArray()))
        val repo = GitHubUpdateRepository(mac, dir, http = http)
        assertFailsWith<IllegalStateException> { repo.download(info) {} }
        assertFalse(File(dir, name).exists())
        assertFalse(File(dir, "$name.part").exists())
    }

    @Test
    fun latestReadsRedirect() = runTest {
        val repo = GitHubUpdateRepository(mac, dir, http = FakeHttp(emptyMap(), "https://github.com/eoeo0326/adb-mirror/releases/tag/v0.9.1"))
        assertEquals(UpdateInfo(AppVersion(0, 9, 1), "https://github.com/eoeo0326/adb-mirror/releases/tag/v0.9.1"), repo.latest())
    }

    @Test
    fun powershellScriptIsWrittenWithBomSoKoreanPathsSurvive() = runTest {
        val launched = mutableListOf<List<String>>()
        val home = File(dir, "사용자/ADB Mirror")
        val msi = UpdateTarget(InstallKind.WindowsMsi, "x64", null, File(home, "ADB Mirror.exe"))
        val file = File(dir, "ADB-Mirror-0.8.0-windows-x64.msi").apply { writeText("x") }
        GitHubUpdateRepository(msi, dir, http = FakeHttp(emptyMap()), pid = 5, launch = { cmd, _ -> launched += cmd })
            .install(io.github.eoeo0326.adbmirror.core.domain.model.DownloadedUpdate(info, file.path))
        val bytes = File(dir, "update.ps1").readBytes()
        assertEquals(listOf(0xEF, 0xBB, 0xBF), bytes.take(3).map { it.toInt() and 0xFF }, "UTF-8 BOM")
        assertTrue("사용자" in String(bytes, Charsets.UTF_8))
        assertEquals("powershell.exe", launched.single().first())
    }

    @Test
    fun installWritesScriptAndLaunchesIt() = runTest {
        val launched = mutableListOf<List<String>>()
        val linux = UpdateTarget(InstallKind.LinuxPortable, "x64", File(dir, "ADB Mirror"), File(dir, "ADB Mirror/bin/ADB Mirror"))
        val archive = File(dir, "ADB-Mirror-0.8.0-linux-x64-portable.tar.gz").apply { writeText("x") }
        val repo = GitHubUpdateRepository(linux, dir, http = FakeHttp(emptyMap()), pid = 99, launch = { cmd, _ -> launched += cmd })
        repo.install(io.github.eoeo0326.adbmirror.core.domain.model.DownloadedUpdate(info, archive.path))
        val script = File(dir, "update.sh")
        assertEquals(listOf(listOf("/bin/bash", script.path)), launched)
        assertTrue("pid=99" in script.readText())

        val deb = GitHubUpdateRepository(UpdateTarget(InstallKind.LinuxDeb, "x64", null, null), dir, http = FakeHttp(emptyMap()))
        assertEquals("sudo apt install '/tmp/a b.deb'", deb.manualCommand(io.github.eoeo0326.adbmirror.core.domain.model.DownloadedUpdate(info, "/tmp/a b.deb")))
    }
}
