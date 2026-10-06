package io.github.eoeo0326.adbmirror.feature.mirror

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
    /** 이 창에서 마지막으로 저장한 녹화 파일(회전으로 나뉘면 여러 개) */
    val lastRecording: List<String> = emptyList(),
    val statusMessage: String? = null,
    /** 지금 연결 시도(세션)의 번호. 연결할 때마다 늘고, [SessionScoped] 결과는 이 번호가 같을 때만 반영한다. */
    val sessionId: Int = 0,
    /** 스크린샷을 받는 중. 연달아 눌러도 한 장씩만 받는다. */
    val capturingScreenshot: Boolean = false,
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
    /** 녹화 파일로 변환 화면을 연다. 회전으로 나뉜 녹화면 part를 모두 넘겨 이어서 변환한다. */
    data class OpenConversion(val files: List<String>) : MirrorIntent
}

/**
 * 한 세션(연결 시도)에 속한 결과. 연결·녹화는 비동기라 이전 세션의 결과가 늦게 올 수 있어,
 * [MirrorReducer]는 [sessionId]가 [MirrorState.sessionId]와 다르면 버린다.
 */
sealed interface SessionScoped {
    val sessionId: Int
}

/** UseCase 실행 결과. [MirrorReducer]만 이것으로 State를 바꾼다. */
sealed interface MirrorResult {
    data class SettingsLoaded(val settings: Settings) : MirrorResult
    /** 새 세션을 시작한다. 이 결과만 [MirrorState.sessionId]를 바꾼다. */
    data class ConnectStarted(val sessionId: Int) : MirrorResult
    data class ConnectFailed(override val sessionId: Int, val message: String) : MirrorResult, SessionScoped
    data class DeviceNameReceived(override val sessionId: Int, val name: String) : MirrorResult, SessionScoped
    data class VideoSizeChanged(override val sessionId: Int, val size: VideoSize) : MirrorResult, SessionScoped
    data class SessionEnded(override val sessionId: Int, val error: String?) : MirrorResult, SessionScoped
    data class RecordingStarting(override val sessionId: Int) : MirrorResult, SessionScoped
    data class RecordingStarted(override val sessionId: Int, val startedAtMs: Long) : MirrorResult, SessionScoped
    data class RecordingStopping(override val sessionId: Int) : MirrorResult, SessionScoped
    data class RecordingStopped(override val sessionId: Int) : MirrorResult, SessionScoped
    data class RecordingSaved(val files: List<String>) : MirrorResult
    data object ScreenshotStarted : MirrorResult
    data object ScreenshotFinished : MirrorResult
    data class StatusShown(val message: String) : MirrorResult
    data object StatusCleared : MirrorResult
}

/** 한 번만 처리할 이벤트(토스트, 파일 열기 안내 등). State에 남기지 않는다. */
sealed interface MirrorEffect {
    data class ShowMessage(val message: String) : MirrorEffect
    data class ScreenshotSaved(val path: String) : MirrorEffect
    /** [files]는 변환에 넘길 파일, [locations]는 보여줄 저장 위치. */
    data class RecordingSaved(val files: List<String>, val locations: List<String> = files) : MirrorEffect
    /** 변환 화면을 연다. 화면(창)은 미러링과 따로 사는 ConversionViewModel이 맡는다. */
    data class OpenConversion(val files: List<String>) : MirrorEffect
    data object AskShowTouchesForRecording : MirrorEffect
    data class Error(val message: String) : MirrorEffect
}
