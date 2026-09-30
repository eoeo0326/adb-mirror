package io.github.eoeo0326.adbmirror.feature.mirror

import io.github.eoeo0326.adbmirror.core.domain.model.isSelectable

/** 부수효과 없는 상태 전이. 같은 입력이면 항상 같은 State를 돌려준다. */
object MirrorReducer {
    fun reduce(state: MirrorState, result: MirrorResult): MirrorState = when (result) {
        is MirrorResult.DevicesLoaded -> {
            // 고른 기기가 사라지거나 선택할 수 없는 상태가 되면 선택을 푼다. 새로 자동 선택하지는 않는다.
            val stillSelectable = result.devices.any { it.serial == state.selectedSerial && it.isSelectable }
            state.copy(devices = result.devices, selectedSerial = state.selectedSerial.takeIf { stillSelectable })
        }

        is MirrorResult.SettingsLoaded -> state.copy(settings = result.settings)

        is MirrorResult.DeviceSelected -> {
            val selectable = state.devices.any { it.serial == result.serial && it.isSelectable }
            if (selectable) state.copy(selectedSerial = result.serial) else state
        }

        is MirrorResult.ConnectStarted ->
            state.copy(screen = Screen.Mirror, connection = Connection.Connecting(result.serial))

        is MirrorResult.ConnectFailed ->
            state.copy(screen = Screen.DeviceList, connection = Connection.Error(result.message))

        is MirrorResult.DeviceNameReceived -> state.updateMirroring { it.copy(deviceName = result.name) }

        is MirrorResult.VideoSizeChanged -> state.updateMirroring { it.copy(videoSize = result.size) }

        is MirrorResult.SessionEnded -> state.copy(
            screen = Screen.DeviceList,
            connection = result.error?.let(Connection::Error) ?: Connection.Idle,
            // 세션이 끝나면 녹화도 끝난다(파일 정리는 Repository 몫).
            recording = RecordingState.Idle,
        )

        MirrorResult.RecordingStarting ->
            if (state.connection is Connection.Mirroring && state.recording == RecordingState.Idle) {
                state.copy(recording = RecordingState.Starting)
            } else {
                state
            }

        is MirrorResult.RecordingStarted -> state.copy(recording = RecordingState.Recording(result.startedAtMs))
        MirrorResult.RecordingStopping -> state.copy(recording = RecordingState.Stopping)
        MirrorResult.RecordingStopped -> state.copy(recording = RecordingState.Idle)

        is MirrorResult.ConversionProgressed ->
            state.copy(conversion = ConversionState.Converting(result.fraction.coerceIn(0f, 1f)))

        is MirrorResult.ConversionFinished -> state.copy(conversion = ConversionState.Done(result.file))
        is MirrorResult.ConversionFailed -> state.copy(conversion = ConversionState.Failed(result.message))
        MirrorResult.ConversionCancelled -> state.copy(conversion = ConversionState.Idle)

        is MirrorResult.StatusShown -> state.copy(statusMessage = result.message)
        MirrorResult.StatusCleared -> state.copy(statusMessage = null)
    }

    /** 연결 중이거나 미러링 중일 때만 세션 정보를 채운다. 끝난 세션의 늦은 이벤트는 무시한다. */
    private inline fun MirrorState.updateMirroring(transform: (Connection.Mirroring) -> Connection.Mirroring): MirrorState {
        val current = when (val c = connection) {
            is Connection.Connecting -> Connection.Mirroring(c.serial)
            is Connection.Mirroring -> c
            else -> return this
        }
        return copy(connection = transform(current))
    }
}
