package io.github.eoeo0326.adbmirror.core.data.device

import io.github.eoeo0326.adbmirror.core.adb.WirelessAdbTransport
import io.github.eoeo0326.adbmirror.core.data.storage.TextStore
import io.github.eoeo0326.adbmirror.core.domain.model.Device
import io.github.eoeo0326.adbmirror.core.domain.model.WirelessEndpoint
import io.github.eoeo0326.adbmirror.core.domain.repository.WirelessDeviceRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 연결했던 기기는 [knownStore]에 한 줄에 `<호스트> <포트>`로, 최근 것부터 [maxKnown]개까지 기억한다.
 * 호스트마다 하나만 둔다(무선 디버깅을 다시 켜면 포트만 바뀐다).
 */
class WirelessDeviceRepositoryImpl(
    private val transport: WirelessAdbTransport,
    private val knownStore: TextStore? = null,
    private val maxKnown: Int = 8,
) : WirelessDeviceRepository {
    private val lock = Mutex()

    override suspend fun pair(host: String, port: Int, code: String) = transport.pair(host, port, code)

    override suspend fun connect(host: String, port: Int): Device {
        val device = DeviceRepositoryImpl.toDevice(transport.connect(host, port))
        lock.withLock { save((listOf(WirelessEndpoint(host, port)) + load().filter { it.host != host }).take(maxKnown)) }
        return device
    }

    override suspend fun disconnect(serial: String) {
        transport.disconnect(serial)
        val host = serial.substringBeforeLast(':', "")
        if (host.isNotEmpty()) lock.withLock { save(load().filter { it.host != host }) }
    }

    override suspend fun known(): List<WirelessEndpoint> = lock.withLock { load() }

    private suspend fun load(): List<WirelessEndpoint> = knownStore?.read().orEmpty().lines().mapNotNull { line ->
        val parts = line.trim().split(' ')
        val port = parts.getOrNull(1)?.toIntOrNull()
        if (parts.size == 2 && parts[0].isNotEmpty() && port != null && port in 1..65535) WirelessEndpoint(parts[0], port) else null
    }.distinctBy { it.host }

    private suspend fun save(endpoints: List<WirelessEndpoint>) {
        knownStore?.write(endpoints.joinToString("\n") { "${it.host} ${it.port}" })
    }
}
