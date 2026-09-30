package io.github.eoeo0326.adbmirror.core.domain.usecase

import io.github.eoeo0326.adbmirror.core.domain.model.Device
import io.github.eoeo0326.adbmirror.core.domain.model.DeviceState
import io.github.eoeo0326.adbmirror.core.domain.repository.DeviceRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 기기 목록. 선택할 수 있는 기기(Online)를 먼저, 그 안에서는 serial 순으로 정렬한다. */
class GetDevicesUseCase(private val devices: DeviceRepository) {
    operator fun invoke(): Flow<List<Device>> = devices.devices().map { list ->
        list.sortedWith(compareBy<Device>({ it.state != DeviceState.Online }, { it.serial }))
    }
}

/** 기기의 show_touches를 바꾸고 원래 값을 돌려준다. 연결을 끊을 때 그 값으로 다시 호출해 복원한다. */
class SetShowTouchesUseCase(private val devices: DeviceRepository) {
    suspend operator fun invoke(serial: String, enabled: Boolean): Boolean =
        devices.setShowTouches(serial, enabled)
}
