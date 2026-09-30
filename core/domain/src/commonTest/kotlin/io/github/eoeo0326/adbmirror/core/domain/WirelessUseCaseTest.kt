package io.github.eoeo0326.adbmirror.core.domain

import io.github.eoeo0326.adbmirror.core.domain.model.Device
import io.github.eoeo0326.adbmirror.core.domain.model.DeviceState
import io.github.eoeo0326.adbmirror.core.domain.repository.WirelessDeviceRepository
import io.github.eoeo0326.adbmirror.core.domain.usecase.ConnectWirelessDeviceUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.PairDeviceUseCase
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WirelessUseCaseTest {
    private val calls = mutableListOf<String>()
    private val repo = object : WirelessDeviceRepository {
        override suspend fun pair(host: String, port: Int, code: String) { calls += "pair $host $port $code" }
        override suspend fun connect(host: String, port: Int) = Device("$host:$port", DeviceState.Online).also { calls += "connect $host $port" }
        override suspend fun disconnect(serial: String) { calls += "disconnect $serial" }
    }

    @Test
    fun validInputIsPassedTrimmed() = runTest {
        PairDeviceUseCase(repo)(" 192.168.0.5 ", "37123", "123456")
        val device = ConnectWirelessDeviceUseCase(repo)("192.168.0.5", "40555")
        assertEquals(listOf("pair 192.168.0.5 37123 123456", "connect 192.168.0.5 40555"), calls)
        assertEquals("192.168.0.5:40555", device.serial)
    }

    @Test
    fun invalidInputIsRejectedBeforeCallingRepository() = runTest {
        assertFailsWith<IllegalArgumentException> { PairDeviceUseCase(repo)("", "37123", "123456") }
        assertFailsWith<IllegalArgumentException> { PairDeviceUseCase(repo)("h", "0", "123456") }
        assertFailsWith<IllegalArgumentException> { PairDeviceUseCase(repo)("h", "37123", "12345") }
        assertFailsWith<IllegalArgumentException> { PairDeviceUseCase(repo)("h", "37123", "12a456") }
        assertFailsWith<IllegalArgumentException> { ConnectWirelessDeviceUseCase(repo)("h", "70000") }
        assertEquals(emptyList(), calls)
    }
}
