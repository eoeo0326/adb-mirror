package io.github.eoeo0326.adbmirror.feature.mirror

import io.github.eoeo0326.adbmirror.core.domain.model.Device
import io.github.eoeo0326.adbmirror.core.domain.model.DeviceState
import io.github.eoeo0326.adbmirror.core.domain.model.VideoSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MirrorReducerTest {
    private val device = Device("A", DeviceState.Online)

    private fun MirrorState.apply(vararg results: MirrorResult) =
        results.fold(this) { s, r -> MirrorReducer.reduce(s, r) }

    private fun mirroring() = MirrorState(device).apply(MirrorResult.ConnectStarted(1), MirrorResult.DeviceNameReceived(1, "X"))

    @Test
    fun connectFlowFillsSessionInfo() {
        val size = VideoSize(340, 720)
        val state = MirrorState(device).apply(
            MirrorResult.ConnectStarted(1),
            MirrorResult.DeviceNameReceived(1, "SM-N976N"),
            MirrorResult.VideoSizeChanged(1, size),
        )
        assertEquals(Connection.Mirroring("SM-N976N", size), state.connection)
        assertFalse(state.canConnect)
    }

    @Test
    fun rotationReplacesVideoSize() {
        val state = MirrorState(device).apply(
            MirrorResult.ConnectStarted(1),
            MirrorResult.VideoSizeChanged(1, VideoSize(340, 720)),
            MirrorResult.VideoSizeChanged(1, VideoSize(720, 340)),
        )
        assertEquals(VideoSize(720, 340), (state.connection as Connection.Mirroring).videoSize)
    }

    @Test
    fun lateSessionEventsAfterEndAreIgnored() {
        val ended = MirrorState(device).apply(MirrorResult.ConnectStarted(1), MirrorResult.SessionEnded(1, null))
        assertEquals(ended, ended.apply(MirrorResult.VideoSizeChanged(1, VideoSize(1, 1))))
        assertEquals(Connection.Idle, ended.connection)
        assertTrue(ended.canConnect)
    }

    @Test
    fun lateRecordingResultsAfterSessionEndAreIgnored() {
        val ended = mirroring().apply(MirrorResult.RecordingStarting(1), MirrorResult.SessionEnded(1, null))
        assertEquals(RecordingState.Idle, ended.recording)
        assertEquals(ended, ended.apply(MirrorResult.RecordingStarted(1, 10)))
        assertEquals(ended, ended.apply(MirrorResult.RecordingStopping(1)))
        val next = ended.apply(MirrorResult.ConnectStarted(2), MirrorResult.DeviceNameReceived(2, "X"), MirrorResult.RecordingStarting(2))
        assertEquals(RecordingState.Starting, next.recording)
    }

    @Test
    fun recordingStartedWithoutStartingIsIgnored() {
        assertEquals(RecordingState.Idle, mirroring().apply(MirrorResult.RecordingStarted(1, 1)).recording)
        assertEquals(RecordingState.Idle, mirroring().apply(MirrorResult.RecordingStopping(1)).recording)
    }

    @Test
    fun sessionEndWithErrorStopsRecordingAndAllowsReconnect() {
        val state = mirroring().apply(
            MirrorResult.RecordingStarting(1),
            MirrorResult.RecordingStarted(1, 1_000),
            MirrorResult.SessionEnded(1, "USB 분리"),
        )
        assertEquals(Connection.Error("USB 분리"), state.connection)
        assertEquals(RecordingState.Idle, state.recording)
        assertTrue(state.canConnect)
    }

    @Test
    fun connectFailureAllowsRetry() {
        val state = MirrorState(device).apply(MirrorResult.ConnectStarted(1), MirrorResult.ConnectFailed(1, "서버 연결 실패"))
        assertEquals(Connection.Error("서버 연결 실패"), state.connection)
        assertTrue(state.canConnect)
    }

    @Test
    fun recordingCanOnlyStartWhileMirroringAndIdle() {
        assertEquals(RecordingState.Idle, MirrorState(device).apply(MirrorResult.RecordingStarting(1)).recording)
        val starting = mirroring().apply(MirrorResult.RecordingStarting(1))
        assertEquals(RecordingState.Starting, starting.recording)
        val recording = starting.apply(MirrorResult.RecordingStarted(1, 5))
        assertEquals(recording, recording.apply(MirrorResult.RecordingStarting(1)))
        assertEquals(RecordingState.Idle, recording.apply(MirrorResult.RecordingStopping(1), MirrorResult.RecordingStopped(1)).recording)
    }

    @Test
    fun conversionProgressIsClampedAndTerminates() {
        val s = MirrorState(device)
        assertEquals(ConversionState.Converting(1f), s.apply(MirrorResult.ConversionProgressed(1.4f)).conversion)
        assertEquals(ConversionState.Converting(0f), s.apply(MirrorResult.ConversionProgressed(-1f)).conversion)
        assertEquals(ConversionState.Done("a.gif"), s.apply(MirrorResult.ConversionFinished("a.gif")).conversion)
        assertEquals(ConversionState.Idle, s.apply(MirrorResult.ConversionProgressed(0.5f), MirrorResult.ConversionCancelled).conversion)
    }

    @Test
    fun statusMessageShowsAndClears() {
        val shown = MirrorState(device).apply(MirrorResult.StatusShown("스크린샷 저장"))
        assertEquals("스크린샷 저장", shown.statusMessage)
        assertNull(shown.apply(MirrorResult.StatusCleared).statusMessage)
    }

    @Test
    fun lateResultsOfPreviousSessionDoNotTouchNewSession() {
        // 세션 1에서 녹화 정지를 눌렀는데, 그 결과가 오기 전에 끊기고 다시 연결해 세션 2에서 녹화를 시작했다.
        val session2 = mirroring().apply(
            MirrorResult.SessionEnded(1, null),
            MirrorResult.ConnectStarted(2),
            MirrorResult.VideoSizeChanged(2, VideoSize(340, 720)),
            MirrorResult.RecordingStarting(2),
            MirrorResult.RecordingStarted(2, 5_000),
        )
        val late = session2.apply(
            MirrorResult.RecordingStopped(1),
            MirrorResult.RecordingStarted(1, 1),
            MirrorResult.VideoSizeChanged(1, VideoSize(1, 1)),
            MirrorResult.SessionEnded(1, "늦게 온 종료"),
        )
        assertEquals(session2, late)
        assertEquals(RecordingState.Recording(5_000), late.recording)
    }

    @Test
    fun connectStartedSwitchesSessionAndResetsRecording() {
        val s = mirroring().apply(MirrorResult.RecordingStarting(1), MirrorResult.ConnectStarted(2))
        assertEquals(2, s.sessionId)
        assertEquals(Connection.Connecting, s.connection)
        assertEquals(RecordingState.Idle, s.recording)
    }
}
