package io.github.eoeo0326.adbmirror.core.data.screenshot

import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class DesktopScreenshotSinkTest {
    private val home = Files.createTempDirectory("adbmirror-home").toFile()
    private val sink = DesktopScreenshotSink(home) { LocalDateTime.of(2026, 9, 30, 14, 5, 9) }
    private val png = byteArrayOf(1, 2, 3)

    @Test
    fun savesWithTimestampAndNeverOverwrites() = runTest {
        val dir = File(home, "shots").path
        val first = sink.save(png, dir, "adb-mirror_A")
        val second = sink.save(byteArrayOf(9), dir, "adb-mirror_A")
        assertEquals(File(dir, "adb-mirror_A_20260930_140509.png").absolutePath, first)
        assertEquals(File(dir, "adb-mirror_A_20260930_140509_2.png").absolutePath, second)
        assertContentEquals(png, File(first).readBytes())
    }

    @Test
    fun defaultDirPrefersDesktopThenPicturesThenHome() {
        assertEquals(home, sink.defaultDir())
        File(home, "Pictures").mkdirs()
        assertEquals(File(home, "Pictures"), sink.defaultDir())
        File(home, "Desktop").mkdirs()
        assertEquals(File(home, "Desktop"), sink.defaultDir())
    }

    @Test
    fun nullDirUsesDefault() = runTest {
        File(home, "Desktop").mkdirs()
        val path = sink.save(png, null, "x")
        assertEquals(File(home, "Desktop/x_20260930_140509.png").absolutePath, path)
    }
}
