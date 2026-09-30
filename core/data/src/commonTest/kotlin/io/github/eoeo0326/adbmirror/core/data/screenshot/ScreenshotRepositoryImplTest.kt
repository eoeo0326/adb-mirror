package io.github.eoeo0326.adbmirror.core.data.screenshot

import io.github.eoeo0326.adbmirror.core.data.mirror.FakeAdbTransport
import io.github.eoeo0326.adbmirror.core.domain.model.Screenshot
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ScreenshotRepositoryImplTest {
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3)
    private val transport = FakeAdbTransport()
    private val saved = mutableListOf<Triple<ByteArray, String?, String>>()
    private val copied = mutableListOf<ByteArray>()
    private val sink = object : ScreenshotSink {
        override suspend fun copyToClipboard(png: ByteArray) { copied += png }
        override suspend fun save(png: ByteArray, dir: String?, baseName: String): String {
            saved += Triple(png, dir, baseName)
            return "$dir/$baseName.png"
        }
    }
    private val repo = ScreenshotRepositoryImpl(transport, sink)

    @Test
    fun capturesWithScreencapOverExecOut() = runTest {
        var command: List<String>? = null
        transport.execOutReply = { command = it; png }
        val shot = repo.capture("R3CM90LKDDJ")
        assertEquals(listOf("screencap", "-p"), command)
        assertEquals("R3CM90LKDDJ", shot.serial)
        assertContentEquals(png, shot.png)
    }

    @Test
    fun rejectsNonPngOutput() = runTest {
        transport.execOutReply = { "error: no devices".encodeToByteArray() }
        assertFailsWith<IllegalArgumentException> { repo.capture("A") }
        transport.execOutReply = { ByteArray(0) }
        assertFailsWith<IllegalArgumentException> { repo.capture("A") }
    }

    @Test
    fun savesWithFileSafeSerial() = runTest {
        val path = repo.save(Screenshot("192.168.0.5:5555", png), "/out")
        assertEquals("/out/adb-mirror_192.168.0.5_5555.png", path)
        assertEquals("/out", saved.single().second)
    }

    @Test
    fun copiesPngBytes() = runTest {
        repo.copyToClipboard(Screenshot("A", png))
        assertContentEquals(png, copied.single())
    }
}
