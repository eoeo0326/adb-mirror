package io.github.eoeo0326.adbmirror.feature.mirror

import io.github.eoeo0326.adbmirror.core.domain.model.ConversionOptions
import io.github.eoeo0326.adbmirror.core.domain.model.Device
import io.github.eoeo0326.adbmirror.core.domain.model.Settings
import io.github.eoeo0326.adbmirror.core.domain.model.TouchAction
import io.github.eoeo0326.adbmirror.core.domain.model.VideoSize

/**
 * 기기 하나의 미러링 창 MVI 계약. 기기 목록·선택은 `DeviceListState`가 맡는다.
 * 영상 프레임은 초당 수십 번 바뀌므로 [MirrorState]에 넣지 않고 세션의 packets Flow로 따로 흘린다.
 */
data class MirrorState(
    val device: Device,
    val connection: Connection = Connection.Idle,
    val settings: Settings = Settings(),
    val recording: RecordingState = RecordingState.Idle,
    val conversion: ConversionState = ConversionState.Idle,
    val statusMessage: String? = null,
) {
    /** 처음이거나, 끊긴 뒤 다시 연결할 수 있는 상태 */
    val canConnect: Boolean
        get() = connection is Connection.Idle || connection is Connection.Error
}

sealed interface Connection {
    data object Idle : Connection
    data object Connecting : Connection
    data class Mirroring(val deviceName: String? = null, val videoSize: VideoSize? = null) : Connection
    data class Error(val message: String) : Connection
}

sealed interface RecordingState {
    data object Idle : RecordingState
    data object Starting : RecordingState
    data class Recording(val startedAtMs: Long) : RecordingState
    data object Stopping : RecordingState
}

sealed interface ConversionState {
    data object Idle : ConversionState
    data class Converting(val fraction: Float) : ConversionState
    data class Done(val file: String) : ConversionState
    data class Failed(val message: String) : ConversionState
}

/** 사용자 입력. */
sealed interface MirrorIntent {
    /** 연결(끊긴 뒤 다시 연결) */
    data object Connect : MirrorIntent
    data object Disconnect : MirrorIntent
    data class Touch(val action: TouchAction, val x: Int, val y: Int) : MirrorIntent
    data object ToggleViewOnly : MirrorIntent
    data object ToggleTouchEffect : MirrorIntent
    data object ToggleShowTouches : MirrorIntent
    data object CopyScreenshot : MirrorIntent
    data object SaveScreenshot : MirrorIntent
    data object StartRecording : MirrorIntent
    data object StopRecording : MirrorIntent
    data class Convert(val file: String, val options: ConversionOptions) : MirrorIntent
    data object CancelConversion : MirrorIntent
}

/** UseCase 실행 결과. [MirrorReducer]만 이것으로 State를 바꾼다. */
sealed interface MirrorResult {
    data class SettingsLoaded(val settings: Settings) : MirrorResult
    data object ConnectStarted : MirrorResult
    data class ConnectFailed(val message: String) : MirrorResult
    data class DeviceNameReceived(val name: String) : MirrorResult
    data class VideoSizeChanged(val size: VideoSize) : MirrorResult
    data class SessionEnded(val error: String?) : MirrorResult
    data object RecordingStarting : MirrorResult
    data class RecordingStarted(val startedAtMs: Long) : MirrorResult
    data object RecordingStopping : MirrorResult
    data object RecordingStopped : MirrorResult
    data class ConversionProgressed(val fraction: Float) : MirrorResult
    data class ConversionFinished(val file: String) : MirrorResult
    data class ConversionFailed(val message: String) : MirrorResult
    data object ConversionCancelled : MirrorResult
    data class StatusShown(val message: String) : MirrorResult
    data object StatusCleared : MirrorResult
}

/** 한 번만 처리할 이벤트(토스트, 파일 열기 안내 등). State에 남기지 않는다. */
sealed interface MirrorEffect {
    data class ShowMessage(val message: String) : MirrorEffect
    data class ScreenshotSaved(val path: String) : MirrorEffect
    data class RecordingSaved(val files: List<String>) : MirrorEffect
    data class ConversionDone(val file: String) : MirrorEffect
    data object AskShowTouchesForRecording : MirrorEffect
    data class Error(val message: String) : MirrorEffect
}
