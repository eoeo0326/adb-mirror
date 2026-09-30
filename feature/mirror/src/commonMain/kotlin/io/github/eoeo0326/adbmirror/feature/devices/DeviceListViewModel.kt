package io.github.eoeo0326.adbmirror.feature.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.eoeo0326.adbmirror.core.domain.usecase.ConnectWirelessDeviceUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.DisconnectWirelessDeviceUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.GetDevicesUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.PairDeviceUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 무선 디버깅 기기 추가에 쓰는 UseCase 묶음. 플랫폼이 지원할 때만 넘긴다. */
class WirelessActions(
    val pair: PairDeviceUseCase,
    val connect: ConnectWirelessDeviceUseCase,
    val disconnect: DisconnectWirelessDeviceUseCase,
)

class DeviceListViewModel(
    getDevices: GetDevicesUseCase,
    private val wireless: WirelessActions? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(DeviceListState(wireless = wireless?.let { WirelessForm() }))
    val state: StateFlow<DeviceListState> = _state.asStateFlow()

    private val _effects = Channel<DeviceListEffect>(Channel.BUFFERED)
    val effects: Flow<DeviceListEffect> = _effects.receiveAsFlow()

    init {
        viewModelScope.launch { getDevices().collect { reduce(DeviceListResult.DevicesLoaded(it)) } }
    }

    fun onIntent(intent: DeviceListIntent) {
        when (intent) {
            is DeviceListIntent.Select -> reduce(DeviceListResult.Selected(intent.serial))
            DeviceListIntent.Open -> {
                val current = _state.value
                val device = current.selected?.takeIf { current.canOpen } ?: return
                reduce(DeviceListResult.Opened(device.serial))
                _effects.trySend(DeviceListEffect.OpenMirror(device))
            }
            is DeviceListIntent.MirrorClosed -> reduce(DeviceListResult.Closed(intent.serial))
            is DeviceListIntent.EditWireless -> reduce(DeviceListResult.WirelessEdited(intent.form))
            DeviceListIntent.Pair -> wirelessAction("페어링하는 중…") { w, f ->
                w.pair(f.host, f.pairPort, f.code)
                "페어링했습니다. 이제 연결 포트로 연결하세요"
            }
            DeviceListIntent.ConnectWireless -> wirelessAction("연결하는 중…") { w, f ->
                val device = w.connect(f.host, f.connectPort)
                "${device.model ?: device.serial}에 연결했습니다"
            }
            is DeviceListIntent.Disconnect -> wireless?.let { w -> viewModelScope.launch { runCatching { w.disconnect(intent.serial) } } }
        }
    }

    /** 무선 작업을 하나씩만 돌린다. 입력 오류·연결 실패는 폼 아래 문구로 보여 준다. */
    private fun wirelessAction(progress: String, block: suspend (WirelessActions, WirelessForm) -> String) {
        val w = wireless ?: return
        val form = _state.value.wireless ?: return
        if (form.busy) return
        reduce(DeviceListResult.WirelessStarted(progress))
        viewModelScope.launch {
            val result = try {
                DeviceListResult.WirelessFinished(block(w, form), failed = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DeviceListResult.WirelessFinished(e.message ?: e::class.simpleName ?: "실패했습니다", failed = true)
            }
            reduce(result)
        }
    }

    private fun reduce(result: DeviceListResult) = _state.update { DeviceListReducer.reduce(it, result) }
}
