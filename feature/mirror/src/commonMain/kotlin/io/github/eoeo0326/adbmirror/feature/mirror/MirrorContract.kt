package io.github.eoeo0326.adbmirror.feature.mirror

import io.github.eoeo0326.adbmirror.core.domain.model.ConversionOptions
import io.github.eoeo0326.adbmirror.core.domain.model.Device
import io.github.eoeo0326.adbmirror.core.domain.model.Settings
import io.github.eoeo0326.adbmirror.core.domain.model.TouchAction
import io.github.eoeo0326.adbmirror.core.domain.model.VideoSize

/**
 * 미러링 화면의 MVI 계약.
 * 영상 프레임은 초당 수십 번 바뀌므로 [MirrorState]에 넣지 않고 세션의 packets Flow로 따로 흘린다.
 */
data class MirrorState(
    val screen: Screen = Screen.DeviceList,
    val devices: List<Device> = emptyList(),
    /** 사용자가 목록에서 고른 기기. 기기가 하나여도 자동으로 채우지 않는다. */
    val selectedSerial: String? = null,
    val connection: Connection = Connection.Idle,
    val settings: Settings = Settings(),
    val recording: RecordingState = RecordingState.Idle,
    val conversion: ConversionState = ConversionState.Idle,
    val statusMessage: String? = null,
) {
    val canConnect: Boolean
        get() = selectedSerial != null &&
            (connection is Connection.Idle || connection is Connection.Error)
}

enum class Screen { DeviceList, Mirror }

sealed interface Connection {
    data object Idle : Connection
    data class Connecting(val serial: String) : Connection
    data class Mirroring(val serial: String, val deviceName: String? = null, val videoSize: VideoSize? = null) : Connection
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
    data object RefreshDevices : MirrorIntent
    data class SelectDevice(val serial: String) : MirrorIntent
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

    /** Android: 무선 디버깅 페어링 */
    data class PairDevice(val host: String, val port: Int, val code: String) : MirrorIntent

    /** Web: WebUSB 기기 선택 창 열기 (사용자 제스처 안에서만 호출 가능) */
    data object RequestUsbDevice : MirrorIntent
}

/** UseCase 실행 결과. [MirrorReducer]만 이것으로 State를 바꾼다. */
sealed interface MirrorResult {
    data class DevicesLoaded(val devices: List<Device>) : MirrorResult
    data class SettingsLoaded(val settings: Settings) : MirrorResult
    data class DeviceSelected(val serial: String) : MirrorResult
    data class ConnectStarted(val serial: String) : MirrorResult
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
