package io.github.eoeo0326.adbmirror.core.data.device

import io.github.eoeo0326.adbmirror.core.adb.AdbDevice
import io.github.eoeo0326.adbmirror.core.adb.AdbTransport
import io.github.eoeo0326.adbmirror.core.adb.WirelessAdbTransport
import io.github.eoeo0326.adbmirror.core.data.mirror.FakeAdbTransport
import io.github.eoeo0326.adbmirror.core.data.storage.TextStore
import io.github.eoeo0326.adbmirror.core.domain.model.WirelessEndpoint
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class WirelessDeviceRepositoryImplTest {
    private class MemStore(var text: String? = null) : TextStore {
        override suspend fun read() = text
        override suspend fun write(text: String) { this.text = text }
    }

    private val transport = object : WirelessAdbTransport, AdbTransport by FakeAdbTransport() {
        override suspend fun pair(host: String, port: Int, code: String) {}
        override suspend fun connect(host: String, port: Int) = AdbDevice("$host:$port", "device")
        override suspend fun disconnect(serial: String) {}
    }

    @Test
    fun remembersConnectedDevicesNewestFirstOnePerHost() = runTest {
        val store = MemStore()
        val repo = WirelessDeviceRepositoryImpl(transport, store, maxKnown = 2)
        repo.connect("10.0.0.1", 40001)
        repo.connect("10.0.0.2", 40002)
        repo.connect("10.0.0.1", 40011) // 무선 디버깅을 다시 켜 포트가 바뀜
        assertEquals(listOf(WirelessEndpoint("10.0.0.1", 40011), WirelessEndpoint("10.0.0.2", 40002)), repo.known())
        repo.connect("10.0.0.3", 40003)
        assertEquals(listOf("10.0.0.3", "10.0.0.1"), repo.known().map { it.host }, "최근 maxKnown개만")
    }

    @Test
    fun disconnectForgetsThatHost() = runTest {
        val store = MemStore("10.0.0.1 40001\n10.0.0.2 40002\nbroken line\n10.0.0.3 99999")
        val repo = WirelessDeviceRepositoryImpl(transport, store)
        repo.disconnect("10.0.0.1:40001")
        assertEquals(listOf(WirelessEndpoint("10.0.0.2", 40002)), repo.known())
    }
}
