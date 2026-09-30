package io.github.eoeo0326.adbmirror.feature.mirror

/** 부수효과 없는 상태 전이. 같은 입력이면 항상 같은 State를 돌려준다. */
object MirrorReducer {
    fun reduce(state: MirrorState, result: MirrorResult): MirrorState = when (result) {
        is MirrorResult.SettingsLoaded -> state.copy(settings = result.settings)

        MirrorResult.ConnectStarted -> state.copy(connection = Connection.Connecting)

        is MirrorResult.ConnectFailed -> state.copy(connection = Connection.Error(result.message))

        is MirrorResult.DeviceNameReceived -> state.updateMirroring { it.copy(deviceName = result.name) }

        is MirrorResult.VideoSizeChanged -> state.updateMirroring { it.copy(videoSize = result.size) }

        is MirrorResult.SessionEnded -> state.copy(
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

        // 녹화 시작·정지는 비동기라 세션이 먼저 끝날 수 있다. 끝난 세션의 늦은 결과로 녹화 상태를 되살리지 않는다.
        is MirrorResult.RecordingStarted ->
            if (state.connection is Connection.Mirroring && state.recording == RecordingState.Starting) {
                state.copy(recording = RecordingState.Recording(result.startedAtMs))
            } else {
                state
            }

        MirrorResult.RecordingStopping ->
            if (state.recording is RecordingState.Recording) state.copy(recording = RecordingState.Stopping) else state

        MirrorResult.RecordingStopped -> state.copy(recording = RecordingState.Idle)

        is MirrorResult.ConversionProgressed ->
            state.copy(conversion = ConversionState.Converting(result.fraction.coerceIn(0f, 1f)))

        is MirrorResult.ConversionFinished -> state.copy(conversion = ConversionState.Done(result.file))
        is MirrorResult.ConversionFailed -> state.copy(conversion = ConversionState.Failed(result.message))
        MirrorResult.ConversionCancelled -> state.copy(conversion = ConversionState.Idle)

        MirrorResult.ScreenshotStarted -> state.copy(capturingScreenshot = true)
        MirrorResult.ScreenshotFinished -> state.copy(capturingScreenshot = false)

        is MirrorResult.StatusShown -> state.copy(statusMessage = result.message)
        MirrorResult.StatusCleared -> state.copy(statusMessage = null)
    }

    /** 연결 중이거나 미러링 중일 때만 세션 정보를 채운다. 끝난 세션의 늦은 이벤트는 무시한다. */
    private inline fun MirrorState.updateMirroring(transform: (Connection.Mirroring) -> Connection.Mirroring): MirrorState {
        val current = when (val c = connection) {
            Connection.Connecting -> Connection.Mirroring()
            is Connection.Mirroring -> c
            else -> return this
        }
        return copy(connection = transform(current))
    }
}
