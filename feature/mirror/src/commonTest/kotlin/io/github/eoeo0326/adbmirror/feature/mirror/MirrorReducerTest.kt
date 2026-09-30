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

    private fun mirroring() = MirrorState(device).apply(MirrorResult.ConnectStarted, MirrorResult.DeviceNameReceived("X"))

    @Test
    fun connectFlowFillsSessionInfo() {
        val size = VideoSize(340, 720)
        val state = MirrorState(device).apply(
            MirrorResult.ConnectStarted,
            MirrorResult.DeviceNameReceived("SM-N976N"),
            MirrorResult.VideoSizeChanged(size),
        )
        assertEquals(Connection.Mirroring("SM-N976N", size), state.connection)
        assertFalse(state.canConnect)
    }

    @Test
    fun rotationReplacesVideoSize() {
        val state = MirrorState(device).apply(
            MirrorResult.ConnectStarted,
            MirrorResult.VideoSizeChanged(VideoSize(340, 720)),
            MirrorResult.VideoSizeChanged(VideoSize(720, 340)),
        )
        assertEquals(VideoSize(720, 340), (state.connection as Connection.Mirroring).videoSize)
    }

    @Test
    fun lateSessionEventsAfterEndAreIgnored() {
        val ended = MirrorState(device).apply(MirrorResult.ConnectStarted, MirrorResult.SessionEnded(null))
        assertEquals(ended, ended.apply(MirrorResult.VideoSizeChanged(VideoSize(1, 1))))
        assertEquals(Connection.Idle, ended.connection)
        assertTrue(ended.canConnect)
    }

    @Test
    fun lateRecordingResultsAfterSessionEndAreIgnored() {
        val ended = mirroring().apply(MirrorResult.RecordingStarting, MirrorResult.SessionEnded(null))
        assertEquals(RecordingState.Idle, ended.recording)
        assertEquals(ended, ended.apply(MirrorResult.RecordingStarted(10)))
        assertEquals(ended, ended.apply(MirrorResult.RecordingStopping))
        val next = ended.apply(MirrorResult.ConnectStarted, MirrorResult.DeviceNameReceived("X"), MirrorResult.RecordingStarting)
        assertEquals(RecordingState.Starting, next.recording)
    }

    @Test
    fun recordingStartedWithoutStartingIsIgnored() {
        assertEquals(RecordingState.Idle, mirroring().apply(MirrorResult.RecordingStarted(1)).recording)
        assertEquals(RecordingState.Idle, mirroring().apply(MirrorResult.RecordingStopping).recording)
    }

    @Test
    fun sessionEndWithErrorStopsRecordingAndAllowsReconnect() {
        val state = mirroring().apply(
            MirrorResult.RecordingStarting,
            MirrorResult.RecordingStarted(1_000),
            MirrorResult.SessionEnded("USB 분리"),
        )
        assertEquals(Connection.Error("USB 분리"), state.connection)
        assertEquals(RecordingState.Idle, state.recording)
        assertTrue(state.canConnect)
    }

    @Test
    fun connectFailureAllowsRetry() {
        val state = MirrorState(device).apply(MirrorResult.ConnectStarted, MirrorResult.ConnectFailed("서버 연결 실패"))
        assertEquals(Connection.Error("서버 연결 실패"), state.connection)
        assertTrue(state.canConnect)
    }

    @Test
    fun recordingCanOnlyStartWhileMirroringAndIdle() {
        assertEquals(RecordingState.Idle, MirrorState(device).apply(MirrorResult.RecordingStarting).recording)
        val starting = mirroring().apply(MirrorResult.RecordingStarting)
        assertEquals(RecordingState.Starting, starting.recording)
        val recording = starting.apply(MirrorResult.RecordingStarted(5))
        assertEquals(recording, recording.apply(MirrorResult.RecordingStarting))
        assertEquals(RecordingState.Idle, recording.apply(MirrorResult.RecordingStopping, MirrorResult.RecordingStopped).recording)
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
}
