package io.github.eoeo0326.adbmirror.feature.mirror

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.eoeo0326.adbmirror.core.domain.model.ConversionOptions
import io.github.eoeo0326.adbmirror.core.domain.model.ConversionProgress
import io.github.eoeo0326.adbmirror.core.domain.model.Device
import io.github.eoeo0326.adbmirror.core.domain.model.Screenshot
import io.github.eoeo0326.adbmirror.core.domain.model.MirrorSession
import io.github.eoeo0326.adbmirror.core.domain.model.SessionEvent
import io.github.eoeo0326.adbmirror.core.domain.model.TouchEvent
import io.github.eoeo0326.adbmirror.core.domain.usecase.CaptureScreenshotUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.ConvertRecordingUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.CopyScreenshotUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.GetConversionFormatsUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.GetSettingsUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.GetVideoInfoUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.SaveScreenshotUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.SendTouchUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.SetShowTouchesUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.StartMirroringUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.StartRecordingUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.StopMirroringUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.StopRecordingUseCase
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile
import kotlin.time.TimeSource

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
    private val setShowTouches: SetShowTouchesUseCase,
    private val captureScreenshot: CaptureScreenshotUseCase,
    private val copyScreenshot: CopyScreenshotUseCase,
    private val saveScreenshot: SaveScreenshotUseCase,
    private val startRecording: StartRecordingUseCase,
    private val stopRecording: StopRecordingUseCase,
    private val getVideoInfo: GetVideoInfoUseCase,
    private val getConversionFormats: GetConversionFormatsUseCase,
    private val convertRecording: ConvertRecordingUseCase,
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

    /** 기기의 show_touches를 켜기 전 값. 우리가 켜 둔 동안에만 null이 아니다. */
    private var originalShowTouches: Boolean? = null
    private val showTouchesLock = Mutex()

    /** 녹화 시작·정지를 한 줄로 세운다. 시작하는 도중 세션이 끝나도 시작이 끝난 뒤에 정지해 녹화가 남지 않게 한다. */
    private val recordingLock = Mutex()
    private val clock = TimeSource.Monotonic.markNow()
    private var conversionJob: Job? = null
    private var sessionSeq = 0

    init {
        viewModelScope.launch {
            getSettings().collect { settings ->
                val changed = settings.showTouches != _state.value.settings.showTouches
                reduce(MirrorResult.SettingsLoaded(settings))
                if (changed && _session.value != null) launch { syncShowTouches() }
            }
        }
        connect()
    }

    fun onIntent(intent: MirrorIntent) {
        when (intent) {
            MirrorIntent.Connect -> connect()
            MirrorIntent.Disconnect -> viewModelScope.launch { _session.value?.let { stopMirroring(it) } }
            is MirrorIntent.Touch -> touch(intent)
            MirrorIntent.ToggleViewOnly -> viewModelScope.launch { updateSettings { it.copy(viewOnly = !it.viewOnly) } }
            MirrorIntent.ToggleTouchEffect -> viewModelScope.launch { updateSettings { it.copy(touchEffect = !it.touchEffect) } }
            MirrorIntent.CopyScreenshot -> screenshot { copyScreenshot(it); MirrorEffect.ShowMessage("스크린샷을 클립보드에 복사했습니다") }
            MirrorIntent.SaveScreenshot -> screenshot { MirrorEffect.ScreenshotSaved(saveScreenshot(it)) }
            MirrorIntent.StartRecording -> beginRecording()
            MirrorIntent.StopRecording -> endRecording()
            MirrorIntent.ToggleShowTouches -> viewModelScope.launch { updateSettings { it.copy(showTouches = !it.showTouches) } }
            is MirrorIntent.OpenConversion -> openConversion(intent.files)
            is MirrorIntent.ChangeConversionOptions -> reduce(MirrorResult.ConversionOptionsChanged(intent.options))
            MirrorIntent.Convert -> convert()
            MirrorIntent.CancelConversion -> cancelConversion()
            MirrorIntent.CloseConversion -> {
                cancelConversion()
                reduce(MirrorResult.ConversionClosed)
            }
        }
    }

    private fun connect() {
        val current = _state.value
        if (!current.canConnect) return
        val device = current.device
        if (shuttingDown) return
        val id = ++sessionSeq
        reduce(MirrorResult.ConnectStarted(id))
        sessionJob?.cancel()
        val started = CompletableDeferred<Unit>().also { pendingStart = it }
        sessionJob = viewModelScope.launch {
            // started는 "세션을 저장했거나, 늦게 생긴 세션 정리까지 끝났거나, 실패했다"를 뜻한다.
            // shutdown()은 이것을 기다린 뒤 _session을 보므로, 정리가 끝나기 전에 신호를 보내면 안 된다.
            try {
                val session = try {
                    startMirroring(device)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val message = e.message ?: "연결에 실패했습니다"
                    reduce(MirrorResult.ConnectFailed(id, message))
                    _effects.trySend(MirrorEffect.Error(message))
                    return@launch
                }
                // 연결하는 사이에 창이 닫혔으면 바로 끝낸다(서버·forward를 남기지 않음).
                if (shuttingDown) {
                    withContext(NonCancellable) { stopMirroring(session) }
                    return@launch
                }
                _session.value = session
                syncShowTouches()
                started.complete(Unit)
                session.events.collect { event ->
                    when (event) {
                        is SessionEvent.DeviceName -> reduce(MirrorResult.DeviceNameReceived(id, event.name))
                        is SessionEvent.VideoSizeChanged -> reduce(MirrorResult.VideoSizeChanged(id, event.size))
                        is SessionEvent.Ended -> {
                            _session.value = null
                            withContext(NonCancellable) {
                                finishRecording(session, id)
                                syncShowTouches()
                            }
                            reduce(MirrorResult.SessionEnded(id, event.error))
                            event.error?.let { _effects.trySend(MirrorEffect.Error(it)) }
                            sessionJob?.cancel()
                        }
                    }
                }
            } finally {
                started.complete(Unit)
            }
        }
        // 본문이 시작되기도 전에 취소돼 finally가 돌지 않는 경우에도 shutdown()이 영원히 기다리지 않게 한다.
        sessionJob?.invokeOnCompletion { started.complete(Unit) }
    }

    /** 기기 원본 해상도로 한 장 받아 [deliver]로 넘긴다. 미러링 연결과 무관하게 adb만 되면 된다. */
    private fun screenshot(deliver: suspend (Screenshot) -> MirrorEffect) {
        if (_state.value.capturingScreenshot) return
        reduce(MirrorResult.ScreenshotStarted)
        viewModelScope.launch {
            try {
                _effects.trySend(deliver(captureScreenshot(_state.value.device.serial)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _effects.trySend(MirrorEffect.Error("스크린샷 실패: ${e.message ?: e::class.simpleName}"))
            } finally {
                reduce(MirrorResult.ScreenshotFinished)
            }
        }
    }

    private fun beginRecording() {
        val session = _session.value ?: return
        val s = _state.value
        if (s.connection !is Connection.Mirroring || s.recording != RecordingState.Idle) return
        val id = s.sessionId
        reduce(MirrorResult.RecordingStarting(id))
        viewModelScope.launch {
            recordingLock.withLock {
                try {
                    startRecording(session)
                    reduce(MirrorResult.RecordingStarted(id, clock.elapsedNow().inWholeMilliseconds))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    reduce(MirrorResult.RecordingStopped(id))
                    _effects.trySend(MirrorEffect.Error("녹화를 시작하지 못했습니다: ${e.message ?: e::class.simpleName}"))
                }
            }
        }
    }

    private fun endRecording() {
        val session = _session.value ?: return
        if (_state.value.recording !is RecordingState.Recording) return
        val id = _state.value.sessionId
        reduce(MirrorResult.RecordingStopping(id))
        viewModelScope.launch { withContext(NonCancellable) { finishRecording(session, id) } }
    }

    /** 그 세션의 녹화를 끝내고 결과를 알린다. 녹화 중이 아니면 아무것도 하지 않는다. */
    private suspend fun finishRecording(session: MirrorSession, sessionId: Int) {
        val recording = try {
            recordingLock.withLock { stopRecording(session) }
        } catch (e: Exception) {
            reduce(MirrorResult.RecordingStopped(sessionId))
            _effects.trySend(MirrorEffect.Error("녹화를 마무리하지 못했습니다: ${e.message ?: e::class.simpleName}"))
            return
        } ?: return
        reduce(MirrorResult.RecordingStopped(sessionId))
        if (recording.files.isNotEmpty()) reduce(MirrorResult.RecordingSaved(recording.files))
        _effects.trySend(
            if (recording.files.isEmpty()) MirrorEffect.ShowMessage("녹화된 화면이 없어 파일을 만들지 않았습니다") else MirrorEffect.RecordingSaved(recording.files, recording.locations),
        )
    }

    private fun openConversion(files: List<String>) {
        if (files.isEmpty() || _state.value.conversion is ConversionState.Converting) return
        viewModelScope.launch {
            try {
                val formats = getConversionFormats()
                if (formats.isEmpty()) {
                    _effects.trySend(MirrorEffect.ShowMessage("이 플랫폼에서는 GIF·WebP 변환을 지원하지 않습니다"))
                    return@launch
                }
                val info = getVideoInfo(files)
                val options = ConversionOptions(width = minOf(ConversionOptions().width, info.width))
                reduce(MirrorResult.ConversionOpened(ConversionDraft(files, info, options, formats)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _effects.trySend(MirrorEffect.Error("녹화 파일을 읽지 못했습니다: ${e.message ?: e::class.simpleName}"))
            }
        }
    }

    private fun convert() {
        val draft = _state.value.conversionDraft ?: return
        if (_state.value.conversion is ConversionState.Converting || draft.problems.isNotEmpty()) return
        reduce(MirrorResult.ConversionProgressed(0f))
        conversionJob = viewModelScope.launch {
            try {
                convertRecording(draft.files, draft.options).collect { progress ->
                    when (progress) {
                        is ConversionProgress.Running -> reduce(MirrorResult.ConversionProgressed(progress.fraction))
                        is ConversionProgress.Done -> {
                            reduce(MirrorResult.ConversionFinished(progress.file))
                            _effects.trySend(MirrorEffect.ConversionDone(progress.file))
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reduce(MirrorResult.ConversionFailed(e.message ?: e::class.simpleName ?: "변환에 실패했습니다"))
            }
        }
    }

    /** 흐름을 취소하면 저장소가 쓰던 파일을 지운다. */
    private fun cancelConversion() {
        val job = conversionJob ?: return
        conversionJob = null
        if (job.isActive) {
            job.cancel()
            reduce(MirrorResult.ConversionCancelled)
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
        conversionJob?.cancel()
        pendingStart?.await()
        _session.value?.let {
            finishRecording(it, _state.value.sessionId)
            stopMirroring(it)
        }
        _session.value = null
        syncShowTouches()
    }

    /**
     * 기기의 show_touches를 설정·세션 상태에 맞춘다. 켜야 하면 원래 값을 기억해 두고 켜고,
     * 아니면(설정 끔·세션 끝·창 닫힘) 기억해 둔 값으로 되돌린다. 여러 경로에서 불러도 한 번씩만 바뀐다.
     * 기기 설정을 바꾼 뒤 원래 값을 저장하기 전에 취소되면 복원할 수 없으므로 취소되지 않게 끝까지 돈다.
     */
    private suspend fun syncShowTouches() = withContext(NonCancellable) { showTouchesLock.withLock {
        val want = !shuttingDown && _session.value != null && _state.value.settings.showTouches
        val serial = _state.value.device.serial
        val original = originalShowTouches
        try {
            if (want && original == null) {
                originalShowTouches = setShowTouches(serial, true)
            } else if (!want && original != null) {
                originalShowTouches = null // 복원이 실패해도(기기 분리 등) 다시 시도하지 않는다
                setShowTouches(serial, original)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _effects.trySend(MirrorEffect.ShowMessage("기기 터치 표시를 바꾸지 못했습니다: ${e.message}"))
        }
    } }

    private fun reduce(result: MirrorResult) = _state.update { MirrorReducer.reduce(it, result) }
}
