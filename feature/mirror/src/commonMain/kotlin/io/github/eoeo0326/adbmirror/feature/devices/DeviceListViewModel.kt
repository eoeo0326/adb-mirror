package io.github.eoeo0326.adbmirror.feature.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.eoeo0326.adbmirror.core.domain.model.WirelessEndpoint
import io.github.eoeo0326.adbmirror.core.domain.model.WirelessReconnect
import io.github.eoeo0326.adbmirror.core.domain.model.WirelessService
import io.github.eoeo0326.adbmirror.core.domain.usecase.ConnectWirelessDeviceUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.DisconnectWirelessDeviceUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.DiscoverWirelessServicesUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.GetDevicesUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.GetKnownWirelessDevicesUseCase
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 무선 디버깅 기기 추가에 쓰는 UseCase 묶음. 플랫폼이 지원할 때만 넘긴다. */
class WirelessActions(
    val pair: PairDeviceUseCase,
    val connect: ConnectWirelessDeviceUseCase,
    val disconnect: DisconnectWirelessDeviceUseCase,
    val known: GetKnownWirelessDevicesUseCase,
    /** mDNS 찾기. 지원하지 않으면 null. */
    val discover: DiscoverWirelessServicesUseCase? = null,
    /** 알림 답장으로 페어링 코드를 받을 수 있으면 true([DeviceListEffect.StartNotificationPairing]을 처리해야 함). */
    val notificationPairing: Boolean = false,
)

class DeviceListViewModel(
    getDevices: GetDevicesUseCase,
    private val wireless: WirelessActions? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(DeviceListState(wireless = wireless?.let { WirelessForm(canPairByNotification = it.notificationPairing) }))
    val state: StateFlow<DeviceListState> = _state.asStateFlow()

    private val _effects = Channel<DeviceListEffect>(Channel.BUFFERED)
    val effects: Flow<DeviceListEffect> = _effects.receiveAsFlow()

    private val reconnectLock = Mutex()

    /** 이번 실행에서 다시 연결해 본 주소. 실패한 주소를 되풀이해 시도하지 않는다. */
    private val tried = mutableSetOf<WirelessEndpoint>()

    init {
        viewModelScope.launch { getDevices().collect { reduce(DeviceListResult.DevicesLoaded(it)) } }
        wireless?.let { w ->
            viewModelScope.launch { reconnectKnown(w, emptyList()) }
            w.discover?.let { discover ->
                viewModelScope.launch {
                    discover().collect { services ->
                        reduce(DeviceListResult.ServicesFound(services))
                        reconnectKnown(w, services)
                    }
                }
            }
        }
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
            is DeviceListIntent.UseService -> useService(intent.service)
            DeviceListIntent.PairByNotification ->
                if (wireless?.notificationPairing == true) _effects.trySend(DeviceListEffect.StartNotificationPairing)
        }
    }

    private fun useService(service: WirelessService) {
        val form = _state.value.wireless?.takeIf { !it.busy } ?: return
        val port = service.port.toString()
        when (service.kind) {
            WirelessService.Kind.Pairing -> reduce(DeviceListResult.WirelessEdited(form.copy(host = service.host, pairPort = port)))
            WirelessService.Kind.Connect -> {
                reduce(DeviceListResult.WirelessEdited(form.copy(host = service.host, connectPort = port)))
                onIntent(DeviceListIntent.ConnectWireless)
            }
        }
    }

    /**
     * 연결했던 기기에 조용히 다시 붙는다(실패해도 알리지 않음). 찾은 연결 서비스가 있으면 그 포트를 쓴다.
     * 시작할 때 한 번, 서비스 목록이 바뀔 때마다 부른다. 같은 주소는 한 번만 시도한다.
     */
    private suspend fun reconnectKnown(w: WirelessActions, services: List<WirelessService>) = reconnectLock.withLock {
        val known = try {
            w.known()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return@withLock
        }
        val connected = _state.value.devices.map { it.serial }.toSet()
        for (target in WirelessReconnect.targets(known, services, connected, tried)) {
            tried += target
            try {
                w.connect(target.host, target.port.toString())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
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
