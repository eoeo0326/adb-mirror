package io.github.eoeo0326.adbmirror.core.adb.protocol

import io.github.eoeo0326.adbmirror.core.adb.AdbException
import io.github.eoeo0326.adbmirror.core.adb.EndOfStreamException
import io.github.eoeo0326.adbmirror.core.adb.SocketNotReadyException
import io.github.eoeo0326.adbmirror.core.adb.crypto.AdbRsaKey
import io.github.eoeo0326.adbmirror.core.adb.crypto.AdbRsaKeyVectorKey
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AdbConnectionTest {
    private val key = AdbRsaKey(hex(AdbRsaKeyVectorKey.N), hex(AdbRsaKeyVectorKey.D))

    private fun TestScope.setUp(knowsKey: Boolean = true) = fakeDevice(backgroundScope, knowsKey)

    @Test
    fun knownKeyConnectsWithSignatureOnly() = runTest {
        val (host, device) = setUp()
        var prompted = false
        val c = AdbConnection.connect(host, key, "me@test", backgroundScope) { prompted = true }
        assertEquals("SM-N976N", c.model)
        assertEquals(listOf("CNXN", "AUTH"), device.received)
        assertEquals(false, prompted)
    }

    @Test
    fun headerAndPayloadAreSeparateWrites() = runTest {
        // USB에서는 adbd가 헤더(24B)와 payload를 각각 한 전송으로 받는다. 합쳐 보내면 기기 쪽에서 넘친다.
        val (host, _) = setUp()
        val sizes = mutableListOf<Int>()
        val recording = object : AdbChannel by host {
            override suspend fun write(bytes: ByteArray) {
                sizes += bytes.size
                host.write(bytes)
            }
        }
        AdbConnection.connect(recording, key, "me@test", backgroundScope)
        assertEquals(24, sizes[0], "CNXN 헤더")
        assertEquals(24, sizes[2], "AUTH 헤더")
        assertEquals(256, sizes[3], "AUTH 서명 payload")
    }

    @Test
    fun unknownKeySendsPublicKeyAndWaitsForUser() = runTest {
        val (host, device) = setUp(knowsKey = false)
        var prompted = false
        AdbConnection.connect(host, key, "me@test", backgroundScope) { prompted = true }
        assertTrue(prompted)
        assertTrue(device.publicKey!!.endsWith(" me@test"))
    }

    @Test
    fun shellCollectsOutputSentRightAfterOkay() = runTest {
        val (host, _) = setUp()
        val c = AdbConnection.connect(host, key, "me@test", backgroundScope)
        assertEquals("hi\n", c.shell("echo hi"))
    }

    @Test
    fun pushSendsFileInChunksSmallerThanMaxPayload() = runTest {
        val (host, device) = setUp()
        val c = AdbConnection.connect(host, key, "me@test", backgroundScope)
        val data = ByteArray(10_000) { (it % 251).toByte() } // 기기 maxdata 4096보다 커서 WRTE 여러 번
        c.push(data, "/data/local/tmp/x.jar")
        assertContentEquals(data, device.files["/data/local/tmp/x.jar,33188"])
    }

    @Test
    fun refusedLocalAbstractIsSocketNotReady() = runTest {
        val (host, _) = setUp()
        val c = AdbConnection.connect(host, key, "me@test", backgroundScope)
        assertFailsWith<SocketNotReadyException> { c.open("localabstract:scrcpy_1") }
        assertFailsWith<AdbException> { c.open("localabstract:scrcpy_1") }
        assertEquals("hi\n", c.shell("echo hi"), "거절 뒤에도 연결은 쓸 수 있다")
    }

    @Test
    fun writeFailsInsteadOfHangingWhenDeviceClosesStream() = runTest {
        val (host, _) = setUp()
        val c = AdbConnection.connect(host, key, "me@test", backgroundScope)
        val stream = c.open("close-on-write:")
        assertFailsWith<EndOfStreamException> { stream.write(ByteArray(10_000)) }
        assertFailsWith<EndOfStreamException> { stream.write(ByteArray(1)) }
        assertEquals("hi\n", c.shell("echo hi"))
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
}
