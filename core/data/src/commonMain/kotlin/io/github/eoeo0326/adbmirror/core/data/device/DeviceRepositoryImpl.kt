package io.github.eoeo0326.adbmirror.core.data.device

import io.github.eoeo0326.adbmirror.core.adb.AdbDevice
import io.github.eoeo0326.adbmirror.core.adb.AdbTransport
import io.github.eoeo0326.adbmirror.core.domain.model.Device
import io.github.eoeo0326.adbmirror.core.domain.model.DeviceState
import io.github.eoeo0326.adbmirror.core.domain.repository.DeviceRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen

class DeviceRepositoryImpl(
    private val transport: AdbTransport,
    private val retryDelayMs: Long = 1_000,
) : DeviceRepository {

    /** adb 서버가 재시작되면 추적 스트림이 끊기므로 잠시 뒤 다시 붙는다. */
    override fun devices(): Flow<List<Device>> = transport.trackDevices()
        .map { list -> list.map(::toDevice) }
        .retryWhen { _, _ -> delay(retryDelayMs); true }

    override suspend fun setShowTouches(serial: String, enabled: Boolean): Boolean {
        val previous = transport.shell(serial, listOf("settings", "get", "system", "show_touches")).trim() == "1"
        transport.shell(serial, listOf("settings", "put", "system", "show_touches", if (enabled) "1" else "0"))
        return previous
    }

    internal companion object {
        fun toDevice(d: AdbDevice) = Device(
            serial = d.serial,
            state = when (d.state) {
                "device" -> DeviceState.Online
                "unauthorized" -> DeviceState.Unauthorized
                else -> DeviceState.Offline
            },
            model = d.model,
        )
    }
}
