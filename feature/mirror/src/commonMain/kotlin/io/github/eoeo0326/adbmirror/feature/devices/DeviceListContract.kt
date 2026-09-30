package io.github.eoeo0326.adbmirror.feature.devices

import io.github.eoeo0326.adbmirror.core.domain.model.Device
import io.github.eoeo0326.adbmirror.core.domain.model.isSelectable

/** 기기 목록 화면(창). 기기가 하나여도 자동으로 고르거나 열지 않는다. */
data class DeviceListState(
    val devices: List<Device> = emptyList(),
    /** 사용자가 목록에서 고른 기기 */
    val selectedSerial: String? = null,
    /** 미러링 창이 열려 있는 기기 */
    val openSerials: Set<String> = emptySet(),
) {
    val selected: Device? get() = devices.firstOrNull { it.serial == selectedSerial }
    val canOpen: Boolean get() = selected?.isSelectable == true
}

sealed interface DeviceListIntent {
    data class Select(val serial: String) : DeviceListIntent
    /** 고른 기기의 미러링 창을 연다(이미 열려 있으면 앞으로). */
    data object Open : DeviceListIntent
    data class MirrorClosed(val serial: String) : DeviceListIntent
}

sealed interface DeviceListResult {
    data class DevicesLoaded(val devices: List<Device>) : DeviceListResult
    data class Selected(val serial: String) : DeviceListResult
    data class Opened(val serial: String) : DeviceListResult
    data class Closed(val serial: String) : DeviceListResult
}

sealed interface DeviceListEffect {
    /** 이 기기의 미러링 창을 열거나 앞으로 가져온다. */
    data class OpenMirror(val device: Device) : DeviceListEffect
}

object DeviceListReducer {
    fun reduce(state: DeviceListState, result: DeviceListResult): DeviceListState = when (result) {
        is DeviceListResult.DevicesLoaded -> {
            // 고른 기기가 사라지거나 선택할 수 없는 상태가 되면 선택을 푼다. 새로 자동 선택하지는 않는다.
            val stillSelectable = result.devices.any { it.serial == state.selectedSerial && it.isSelectable }
            state.copy(devices = result.devices, selectedSerial = state.selectedSerial.takeIf { stillSelectable })
        }
        is DeviceListResult.Selected ->
            if (state.devices.any { it.serial == result.serial && it.isSelectable }) state.copy(selectedSerial = result.serial) else state
        is DeviceListResult.Opened -> state.copy(openSerials = state.openSerials + result.serial)
        is DeviceListResult.Closed -> state.copy(openSerials = state.openSerials - result.serial)
    }
}
