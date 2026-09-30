package io.github.eoeo0326.adbmirror.core.adb

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AdbLocatorTest {
    @Test
    fun macCandidatesInOrder() {
        val c = AdbLocator.candidates("/opt/custom/adb", mapOf("PATH" to "/usr/bin:/bin", "ANDROID_HOME" to "/sdk"), "Mac OS X", "/Users/me")
        assertEquals(
            listOf("/opt/custom/adb", "/usr/bin/adb", "/bin/adb", "/sdk/platform-tools/adb", "/Users/me/Library/Android/sdk/platform-tools/adb"),
            c.map { it.path },
        )
    }

    @Test
    fun windowsUsesExeSemicolonAndLocalAppData() {
        val c = AdbLocator.candidates(null, mapOf("PATH" to "C:\\tools;C:\\bin", "LOCALAPPDATA" to "C:\\Users\\me\\AppData\\Local"), "Windows 11", "C:\\Users\\me")
        assertEquals(File("C:\\tools", "adb.exe").path, c.first().path)
        assertEquals(File(File(File("C:\\Users\\me\\AppData\\Local", "Android/Sdk"), "platform-tools"), "adb.exe").path, c.last().path)
    }

    @Test
    fun linuxDefaultSdk() {
        val c = AdbLocator.candidates(null, emptyMap(), "Linux", "/home/me")
        assertEquals(listOf("/home/me/Android/Sdk/platform-tools/adb"), c.map { it.path })
    }

    @Test
    fun firstExecutableWins() {
        val found = AdbLocator.locate(null, mapOf("PATH" to "/a:/b"), "Linux", "/home/me") { it.path == "/b/adb" }
        assertEquals("/b/adb", found?.path)
        assertNull(AdbLocator.locate(null, emptyMap(), "Linux", "/home/me") { false })
    }
}
