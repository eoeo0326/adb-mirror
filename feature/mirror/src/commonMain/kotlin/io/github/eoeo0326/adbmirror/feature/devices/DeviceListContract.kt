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
    /** 무선 디버깅 페어링·연결 입력. 지원하지 않는 플랫폼이면 null(화면에 나오지 않음). */
    val wireless: WirelessForm? = null,
) {
    val selected: Device? get() = devices.firstOrNull { it.serial == selectedSerial }
    val canOpen: Boolean get() = selected?.isSelectable == true
}

/**
 * 무선 기기 추가 입력. 기기의 설정 > 개발자 옵션 > 무선 디버깅 화면 값을 옮겨 적는다.
 * 페어링 포트·코드는 "페어링 코드로 기기 페어링" 창의 값, 연결 포트는 무선 디버깅 화면의 "IP 주소 및 포트" 값이다.
 */
data class WirelessForm(
    val host: String = "",
    val pairPort: String = "",
    val code: String = "",
    val connectPort: String = "",
    val busy: Boolean = false,
    val message: String? = null,
    val failed: Boolean = false,
)

sealed interface DeviceListIntent {
    data class Select(val serial: String) : DeviceListIntent
    /** 고른 기기의 미러링 창을 연다(이미 열려 있으면 앞으로). */
    data object Open : DeviceListIntent
    data class MirrorClosed(val serial: String) : DeviceListIntent
    data class EditWireless(val form: WirelessForm) : DeviceListIntent
    data object Pair : DeviceListIntent
    data object ConnectWireless : DeviceListIntent
    data class Disconnect(val serial: String) : DeviceListIntent
}

sealed interface DeviceListResult {
    data class DevicesLoaded(val devices: List<Device>) : DeviceListResult
    data class Selected(val serial: String) : DeviceListResult
    data class Opened(val serial: String) : DeviceListResult
    data class Closed(val serial: String) : DeviceListResult
    data class WirelessEdited(val form: WirelessForm) : DeviceListResult
    data class WirelessStarted(val message: String) : DeviceListResult
    data class WirelessFinished(val message: String, val failed: Boolean) : DeviceListResult
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
        // 입력을 고치면 지난 결과 문구는 지운다. 시도 중에는 입력을 바꾸지 않는다.
        is DeviceListResult.WirelessEdited ->
            state.wireless?.takeIf { !it.busy }?.let { state.copy(wireless = result.form.copy(busy = false, message = null, failed = false)) } ?: state
        is DeviceListResult.WirelessStarted -> state.copy(wireless = state.wireless?.copy(busy = true, message = result.message, failed = false))
        is DeviceListResult.WirelessFinished -> state.copy(wireless = state.wireless?.copy(busy = false, message = result.message, failed = result.failed))
    }
}
