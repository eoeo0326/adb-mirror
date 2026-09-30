package io.github.eoeo0326.adbmirror.feature.mirror

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.eoeo0326.adbmirror.core.domain.model.Device
import io.github.eoeo0326.adbmirror.core.domain.model.MirrorSession
import io.github.eoeo0326.adbmirror.core.domain.model.SessionEvent
import io.github.eoeo0326.adbmirror.core.domain.model.TouchEvent
import io.github.eoeo0326.adbmirror.core.domain.usecase.GetSettingsUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.SendTouchUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.StartMirroringUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.StopMirroringUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.UpdateSettingsUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile

/**
 * 기기 하나의 미러링 창. Intent → UseCase → [MirrorResult] → [MirrorReducer] → [state].
 * 진행 중인 세션은 영상 패킷 때문에 State 밖의 [session]으로 따로 내보낸다.
 * 사용자가 목록에서 고른 기기로 만들어지므로 만들자마자 연결한다.
 */
class MirrorViewModel(
    device: Device,
    getSettings: GetSettingsUseCase,
    private val startMirroring: StartMirroringUseCase,
    private val stopMirroring: StopMirroringUseCase,
    private val sendTouch: SendTouchUseCase,
    private val updateSettings: UpdateSettingsUseCase,
) : ViewModel() {
    private val _state = MutableStateFlow(MirrorState(device))
    val state: StateFlow<MirrorState> = _state.asStateFlow()

    private val _session = MutableStateFlow<MirrorSession?>(null)
    val session: StateFlow<MirrorSession?> = _session.asStateFlow()

    private val _effects = Channel<MirrorEffect>(Channel.BUFFERED)
    val effects: Flow<MirrorEffect> = _effects.receiveAsFlow()

    private var sessionJob: Job? = null

    /** 진행 중인 연결 시도. 끝나면(성공·실패·취소) 완료된다. [shutdown]이 이것을 기다린다. */
    private var pendingStart: CompletableDeferred<Unit>? = null

    @Volatile private var shuttingDown = false

    init {
        viewModelScope.launch { getSettings().collect { reduce(MirrorResult.SettingsLoaded(it)) } }
        connect()
    }

    fun onIntent(intent: MirrorIntent) {
        when (intent) {
            MirrorIntent.Connect -> connect()
            MirrorIntent.Disconnect -> viewModelScope.launch { _session.value?.let { stopMirroring(it) } }
            is MirrorIntent.Touch -> touch(intent)
            MirrorIntent.ToggleViewOnly -> viewModelScope.launch { updateSettings { it.copy(viewOnly = !it.viewOnly) } }
            MirrorIntent.ToggleTouchEffect -> viewModelScope.launch { updateSettings { it.copy(touchEffect = !it.touchEffect) } }
            else -> _effects.trySend(MirrorEffect.ShowMessage("아직 준비 중인 기능입니다"))
        }
    }

    private fun connect() {
        val current = _state.value
        if (!current.canConnect) return
        val device = current.device
        if (shuttingDown) return
        reduce(MirrorResult.ConnectStarted)
        sessionJob?.cancel()
        val started = CompletableDeferred<Unit>().also { pendingStart = it }
        sessionJob = viewModelScope.launch {
            val session = try {
                startMirroring(device)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = e.message ?: "연결에 실패했습니다"
                reduce(MirrorResult.ConnectFailed(message))
                _effects.trySend(MirrorEffect.Error(message))
                return@launch
            } finally {
                started.complete(Unit)
            }
            // 연결하는 사이에 창이 닫혔으면 바로 끝낸다(서버·forward를 남기지 않음).
            if (shuttingDown) {
                withContext(NonCancellable) { stopMirroring(session) }
                return@launch
            }
            _session.value = session
            session.events.collect { event ->
                when (event) {
                    is SessionEvent.DeviceName -> reduce(MirrorResult.DeviceNameReceived(event.name))
                    is SessionEvent.VideoSizeChanged -> reduce(MirrorResult.VideoSizeChanged(event.size))
                    is SessionEvent.Ended -> {
                        _session.value = null
                        reduce(MirrorResult.SessionEnded(event.error))
                        event.error?.let { _effects.trySend(MirrorEffect.Error(it)) }
                        sessionJob?.cancel()
                    }
                }
            }
        }
    }

    private fun touch(intent: MirrorIntent.Touch) {
        val session = _session.value ?: return
        val size = (_state.value.connection as? Connection.Mirroring)?.videoSize ?: return
        viewModelScope.launch { sendTouch(session, TouchEvent(intent.action, intent.x, intent.y, size)) }
    }

    /**
     * 창을 닫거나 앱을 끝내기 직전에 부른다. 연결 중이면 그 시도가 끝나기를 기다렸다가
     * 세션(서버·forward)까지 끝낸다. 여러 번 불러도 안전하다.
     */
    suspend fun shutdown() = withContext(NonCancellable) {
        shuttingDown = true
        pendingStart?.await()
        _session.value?.let { stopMirroring(it) }
        _session.value = null
    }

    private fun reduce(result: MirrorResult) = _state.update { MirrorReducer.reduce(it, result) }
}
