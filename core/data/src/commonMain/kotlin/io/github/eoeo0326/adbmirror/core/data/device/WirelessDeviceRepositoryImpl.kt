package io.github.eoeo0326.adbmirror.core.data.device

import io.github.eoeo0326.adbmirror.core.adb.WirelessAdbTransport
import io.github.eoeo0326.adbmirror.core.domain.model.Device
import io.github.eoeo0326.adbmirror.core.domain.repository.WirelessDeviceRepository

class WirelessDeviceRepositoryImpl(private val transport: WirelessAdbTransport) : WirelessDeviceRepository {
    override suspend fun pair(host: String, port: Int, code: String) = transport.pair(host, port, code)

    override suspend fun connect(host: String, port: Int): Device = DeviceRepositoryImpl.toDevice(transport.connect(host, port))

    override suspend fun disconnect(serial: String) = transport.disconnect(serial)
}
