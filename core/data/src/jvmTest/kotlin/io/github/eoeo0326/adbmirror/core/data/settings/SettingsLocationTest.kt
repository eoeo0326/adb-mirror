package io.github.eoeo0326.adbmirror.core.data.settings

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsLocationTest {
    private fun resolve(
        os: String,
        env: Map<String, String> = emptyMap(),
        appPath: String? = null,
        markers: Set<String> = emptySet(),
    ) = SettingsLocation.resolve(env, os, "/home/u", appPath) { it.path in markers }

    @Test
    fun osStandardLocations() {
        assertEquals(File("/home/u/Library/Application Support/ADB Mirror"), resolve("Mac OS X").dir)
        assertEquals(File("C:\\Users\\u\\AppData\\Roaming", "ADB Mirror"), resolve("Windows 11", mapOf("APPDATA" to "C:\\Users\\u\\AppData\\Roaming")).dir)
        assertEquals(File("/home/u/.config/adb-mirror"), resolve("Linux").dir)
        assertEquals(File("/xdg/adb-mirror"), resolve("Linux", mapOf("XDG_CONFIG_HOME" to "/xdg")).dir)
        assertFalse(resolve("Linux").portable)
    }

    @Test
    fun portableMarkerNextToExecutable() {
        val loc = resolve("Windows 11", appPath = "/apps/ADB Mirror/ADB Mirror.exe", markers = setOf("/apps/ADB Mirror/portable"))
        assertTrue(loc.portable)
        assertEquals(File("/apps/ADB Mirror/data"), loc.dir)
        assertEquals(File("/apps/ADB Mirror/data/settings.properties"), loc.settingsFile)
    }

    @Test
    fun portableMarkerAboveLinuxBinDir() {
        val loc = resolve("Linux", appPath = "/opt/adb-mirror/bin/ADB Mirror", markers = setOf("/opt/adb-mirror/portable"))
        assertEquals(File("/opt/adb-mirror/data"), loc.dir)
    }

    @Test
    fun withoutMarkerInstalledAppUsesOsLocation() {
        val loc = resolve("Mac OS X", appPath = "/Applications/ADB Mirror.app/Contents/MacOS/ADB Mirror")
        assertFalse(loc.portable)
        assertEquals(File("/home/u/Library/Application Support/ADB Mirror"), loc.dir)
    }

    @Test
    fun envOverrideWins() {
        val loc = resolve("Linux", mapOf("ADB_MIRROR_DATA_DIR" to "/tmp/d"), appPath = "/opt/a/bin/a", markers = setOf("/opt/a/portable"))
        assertEquals(File("/tmp/d"), loc.dir)
    }
}
