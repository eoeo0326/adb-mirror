package io.github.eoeo0326.adbmirror.core.domain

import io.github.eoeo0326.adbmirror.core.domain.model.Device
import io.github.eoeo0326.adbmirror.core.domain.model.DeviceState
import io.github.eoeo0326.adbmirror.core.domain.model.WirelessEndpoint
import io.github.eoeo0326.adbmirror.core.domain.model.WirelessPairing
import io.github.eoeo0326.adbmirror.core.domain.model.WirelessReconnect
import io.github.eoeo0326.adbmirror.core.domain.model.WirelessService
import io.github.eoeo0326.adbmirror.core.domain.repository.WirelessDeviceRepository
import io.github.eoeo0326.adbmirror.core.domain.usecase.ConnectWirelessDeviceUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.PairDeviceUseCase
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class WirelessUseCaseTest {
    private val calls = mutableListOf<String>()
    private val repo = object : WirelessDeviceRepository {
        override suspend fun pair(host: String, port: Int, code: String) { calls += "pair $host $port $code" }
        override suspend fun connect(host: String, port: Int) = Device("$host:$port", DeviceState.Online).also { calls += "connect $host $port" }
        override suspend fun disconnect(serial: String) { calls += "disconnect $serial" }
        override suspend fun known() = emptyList<WirelessEndpoint>()
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

    @Test
    fun reconnectUsesFoundPortAndSkipsConnectedOrTried() {
        val known = listOf(WirelessEndpoint("10.0.0.1", 40001), WirelessEndpoint("10.0.0.2", 40002), WirelessEndpoint("10.0.0.3", 40003))
        val services = listOf(
            WirelessService(WirelessService.Kind.Connect, "a", "10.0.0.1", 45000),
            WirelessService(WirelessService.Kind.Pairing, "b", "10.0.0.3", 41000), // 페어링 포트로는 연결하지 않는다
        )
        val targets = WirelessReconnect.targets(known, services, connectedSerials = setOf("10.0.0.2:39999", "R3CM90"), tried = setOf(WirelessEndpoint("10.0.0.3", 40003)))
        assertEquals(listOf(WirelessEndpoint("10.0.0.1", 45000)), targets)
    }

    @Test
    fun pairingPrefersThisDeviceThenTheOnlyOne() {
        fun p(host: String) = WirelessService(WirelessService.Kind.Pairing, host, host, 41000)
        val connect = WirelessService(WirelessService.Kind.Connect, "c", "10.0.0.5", 40000)
        assertEquals(p("10.0.0.5"), WirelessPairing.choose(listOf(p("10.0.0.7"), p("10.0.0.5"), connect), setOf("10.0.0.5")))
        assertEquals(p("10.0.0.7"), WirelessPairing.choose(listOf(p("10.0.0.7"), connect), setOf("10.0.0.5")))
        assertNull(WirelessPairing.choose(listOf(p("10.0.0.7"), p("10.0.0.8")), setOf("10.0.0.5")), "어느 것인지 모르면 고르지 않는다")
    }
}
