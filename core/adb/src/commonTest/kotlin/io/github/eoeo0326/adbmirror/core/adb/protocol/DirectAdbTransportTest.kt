package io.github.eoeo0326.adbmirror.core.adb.protocol

import io.github.eoeo0326.adbmirror.core.adb.AdbDevice
import io.github.eoeo0326.adbmirror.core.adb.AdbException
import io.github.eoeo0326.adbmirror.core.adb.SocketNotReadyException
import io.github.eoeo0326.adbmirror.core.adb.crypto.AdbRsaKey
import io.github.eoeo0326.adbmirror.core.adb.crypto.AdbRsaKeyVectorKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DirectAdbTransportTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    private val key = AdbRsaKey(hex(AdbRsaKeyVectorKey.N), hex(AdbRsaKeyVectorKey.D))

    private suspend fun TestScope.connected(): Pair<DirectAdbTransport, FakeAdbd> {
        val (channel, device) = fakeDevice(backgroundScope)
        val transport = DirectAdbTransport(backgroundScope)
        transport.add("usb-1", AdbConnection.connect(channel, key, "me@test", backgroundScope))
        return transport to device
    }

    /** 메모리 통로 너머의 가짜 기기가 답할 때까지(가상 시간으로) 기다린다. */
    private suspend fun awaitUntil(condition: suspend () -> Boolean) = withTimeout(5_000) { while (!condition()) delay(10) }

    @Test
    fun listsAddedConnectionsWithModel() = runTest {
        val (transport, _) = connected()
        assertEquals(listOf(AdbDevice("usb-1", "device", "SM-N976N")), transport.trackDevices().first())
        transport.remove("usb-1")
        assertEquals(emptyList(), transport.devices())
        assertFailsWith<AdbException> { transport.shell("usb-1", listOf("echo", "hi")) }
    }

    @Test
    fun unpluggedDeviceLeavesListAndReconnectReplacesOldConnection() = runTest {
        val (transport, device) = connected()
        val (channel2, _) = fakeDevice(backgroundScope)
        val second = AdbConnection.connect(channel2, key, "me@test", backgroundScope)
        transport.add("usb-1", second)
        assertEquals(1, transport.devices().size, "같은 기기는 하나만")
        device.unplug() // 첫 연결이 끊겨도 새 연결은 남는다
        delay(100)
        assertEquals(listOf("usb-1"), transport.devices().map { it.serial })
        second.close()
        awaitUntil { transport.devices().isEmpty() }
    }

    @Test
    fun shellJoinsArguments() = runTest {
        val (transport, _) = connected()
        assertEquals("hi\n", transport.shell("usb-1", listOf("echo", "hi")))
    }

    @Test
    fun processDeliversLinesAndStopClosesStream() = runTest {
        val (transport, device) = connected()
        val lines = mutableListOf<String>()
        val process = transport.startProcess("usb-1", listOf("logcat")) { lines += it }
        awaitUntil { lines.size == 2 }
        assertEquals(listOf("line 1", "line 2"), lines, "덩어리 경계와 상관없이 줄로 나눈다")
        process.stop()
        assertEquals(0, process.awaitExit())
        awaitUntil { device.clientClosed.isNotEmpty() }
        assertEquals(1, device.clientClosed.size, "멈추면 기기에 CLSE를 보낸다")
    }

    @Test
    fun localAbstractNotListeningIsSocketNotReady() = runTest {
        val (transport, _) = connected()
        assertFailsWith<SocketNotReadyException> { transport.openLocalAbstract("usb-1", "scrcpy_1234") }
    }
}
