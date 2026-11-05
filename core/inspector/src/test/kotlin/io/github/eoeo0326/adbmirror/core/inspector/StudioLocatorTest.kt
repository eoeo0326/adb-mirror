package io.github.eoeo0326.adbmirror.core.inspector

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StudioLocatorTest {
    private val temp: File = Files.createTempDirectory("studio-locator").toFile()

    /** macOS `.app` 구조. [skip]에 든 파일은 만들지 않는다. */
    private fun macApp(dir: File, name: String, build: String, dataDir: String? = null, skip: Set<String> = emptySet()): File {
        val app = File(dir, name)
        writeInstall(File(app, "Contents"), File(app, "Contents/Resources/product-info.json"), build, dataDir, skip)
        return app
    }

    /** Windows·Linux 설치 폴더 구조. */
    private fun plainInstall(dir: File, build: String, dataDir: String? = null): File {
        writeInstall(dir, File(dir, "product-info.json"), build, dataDir, emptySet())
        return dir
    }

    private fun writeInstall(base: File, productInfo: File, build: String, dataDir: String?, skip: Set<String>) {
        productInfo.parentFile.mkdirs()
        // 실제 파일처럼 최상위 키 뒤에 같은 이름의 키가 반복되는 플러그인 목록을 둔다.
        productInfo.writeText(
            """
            {
              "name": "Android Studio",
              "version": "AI-$build",
              "buildNumber": "$build",
              ${dataDir?.let { "\"dataDirectoryName\": \"$it\"," } ?: ""}
              "bundledPlugins": [ { "name": "Git4Idea", "buildNumber": "1.0" } ]
            }
            """.trimIndent(),
        )
        val resources = File(base, "plugins/android/resources")
        val install = StudioInstall(base, resources, build, "x")
        install.requiredFiles()
            .filter { it.relativeTo(resources).invariantSeparatorsPath !in skip }
            .forEach { it.parentFile.mkdirs(); it.writeText("bin") }
    }

    private fun macEnv() = StudioLocator.Environment("Mac OS X", File(temp, "home"), emptyMap())

    @Test
    fun inspectReadsMacAppAndLabelFromDataDirectoryName() {
        val app = macApp(temp, "Android Studio.app", "262.9437.185.2621.16467767", "AndroidStudio2026.2.1")
        val install = assertNotNull(StudioLocator.inspect(app))
        assertEquals("262.9437.185.2621.16467767", install.buildNumber)
        assertEquals("Android Studio 2026.2.1", install.label)
        assertEquals(app, install.root)
        assertEquals(File(app, "Contents/plugins/android/resources"), install.resources)
        // .app/Contents를 줘도 같은 설치다.
        assertEquals(app, StudioLocator.inspect(File(app, "Contents"))?.root)
    }

    @Test
    fun inspectReadsPlainInstallAndFallsBackToBuildNumberLabel() {
        val dir = plainInstall(File(temp, "android-studio"), "253.1.2")
        val install = assertNotNull(StudioLocator.inspect(dir))
        assertEquals("Android Studio 253.1.2", install.label)
        assertEquals(File(dir, "plugins/android/resources/transport/x86_64/transport"), install.daemon("x86_64"))
    }

    @Test
    fun inspectRejectsInstallMissingAnyRequiredFile() {
        val app = macApp(temp, "Android Studio.app", "262.1", skip = setOf("transport/native/agent/x86/libjvmtiagent.so"))
        assertNull(StudioLocator.inspect(app))
        assertNull(StudioLocator.inspect(File(temp, "nothing-here")))
    }

    @Test
    fun locatePicksHighestBuildAmongDefaultLocations() {
        val apps = File(temp, "home/Applications")
        macApp(apps, "Android Studio Panda 4 2025.3.4 Patch 1.app", "253.32098.37.2534.15336583", "AndroidStudio2025.3.4")
        macApp(apps, "Android Studio.app", "262.9437.185.2621.16467767", "AndroidStudio2026.2.1")
        macApp(apps, "Android Studio Preview.app", "999.1", skip = setOf("perfa.jar"))
        val found = assertNotNull(StudioLocator.locate(null, macEnv()))
        assertEquals("262.9437.185.2621.16467767", found.buildNumber)
    }

    @Test
    fun locatePrefersConfiguredPathWhenUsable() {
        val apps = File(temp, "home/Applications")
        val older = macApp(apps, "Android Studio Old.app", "253.1")
        macApp(apps, "Android Studio.app", "262.1")
        assertEquals("253.1", StudioLocator.locate(older.path, macEnv())?.buildNumber)
        // 설정한 위치를 쓸 수 없으면 기본 위치로 돌아간다.
        assertEquals("262.1", StudioLocator.locate(File(temp, "missing").path, macEnv())?.buildNumber)
    }

    @Test
    fun windowsCandidatesIncludeToolboxInstalls() {
        val local = File(temp, "local")
        val toolbox = plainInstall(File(local, "JetBrains/Toolbox/apps/AndroidStudio/ch-0/262.1"), "262.1")
        val env = StudioLocator.Environment("Windows 11", File(temp, "home"), mapOf("LOCALAPPDATA" to local.path, "ProgramFiles" to File(temp, "pf").path))
        val candidates = StudioLocator.candidates(env)
        assertTrue(toolbox in candidates, "$candidates")
        assertEquals(toolbox, StudioLocator.locate(null, env)?.root)
    }

    @Test
    fun linuxCandidatesIncludeToolboxInstalls() {
        val home = File(temp, "home")
        val toolbox = plainInstall(File(home, ".local/share/JetBrains/Toolbox/apps/android-studio"), "262.2")
        val env = StudioLocator.Environment("Linux", home, emptyMap())
        assertEquals(toolbox, StudioLocator.locate(null, env)?.root)
    }

    @Test
    fun buildNumbersCompareNumerically() {
        val c = StudioLocator.BuildNumberComparator
        assertTrue(c.compare("262.9437.185", "253.32098.37") > 0)
        assertTrue(c.compare("262.10", "262.9") > 0)
        assertTrue(c.compare("262.9", "262.9.1") < 0)
        assertEquals(0, c.compare("262.9", "262.9.0"))
    }

    @Test
    fun topLevelStringUsesFirstMatch() {
        val json = """{"buildNumber": "1.2", "plugins": [{"buildNumber": "9.9"}]}"""
        assertEquals("1.2", StudioLocator.topLevelString(json, "buildNumber"))
        assertNull(StudioLocator.topLevelString(json, "dataDirectoryName"))
    }
}
