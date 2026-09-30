package io.github.eoeo0326.adbmirror.feature.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.eoeo0326.adbmirror.core.domain.usecase.GetDevicesUseCase
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class DeviceListViewModel(getDevices: GetDevicesUseCase) : ViewModel() {
    private val _state = MutableStateFlow(DeviceListState())
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
        }
    }

    private fun reduce(result: DeviceListResult) = _state.update { DeviceListReducer.reduce(it, result) }
}
