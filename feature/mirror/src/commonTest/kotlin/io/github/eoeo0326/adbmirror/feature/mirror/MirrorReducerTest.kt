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
    private val online = Device("A", DeviceState.Online)
    private val unauthorized = Device("B", DeviceState.Unauthorized)

    private fun MirrorState.apply(vararg results: MirrorResult) =
        results.fold(this) { s, r -> MirrorReducer.reduce(s, r) }

    @Test
    fun singleDeviceIsNeverAutoSelected() {
        val state = MirrorState().apply(MirrorResult.DevicesLoaded(listOf(online)))
        assertNull(state.selectedSerial)
        assertFalse(state.canConnect)
        assertEquals(Screen.DeviceList, state.screen)
    }

    @Test
    fun onlyOnlineDevicesCanBeSelected() {
        val base = MirrorState().apply(MirrorResult.DevicesLoaded(listOf(online, unauthorized)))
        assertNull(base.apply(MirrorResult.DeviceSelected("B")).selectedSerial)
        assertNull(base.apply(MirrorResult.DeviceSelected("missing")).selectedSerial)
        val selected = base.apply(MirrorResult.DeviceSelected("A"))
        assertEquals("A", selected.selectedSerial)
        assertTrue(selected.canConnect)
    }

    @Test
    fun selectionIsClearedWhenDeviceGoesAwayOrBecomesUnavailable() {
        val selected = MirrorState().apply(MirrorResult.DevicesLoaded(listOf(online)), MirrorResult.DeviceSelected("A"))
        assertNull(selected.apply(MirrorResult.DevicesLoaded(emptyList())).selectedSerial)
        assertNull(selected.apply(MirrorResult.DevicesLoaded(listOf(Device("A", DeviceState.Offline)))).selectedSerial)
        assertEquals("A", selected.apply(MirrorResult.DevicesLoaded(listOf(online, unauthorized))).selectedSerial)
    }

    @Test
    fun connectFlowFillsSessionInfo() {
        val size = VideoSize(340, 720)
        val state = MirrorState().apply(
            MirrorResult.ConnectStarted("A"),
            MirrorResult.DeviceNameReceived("SM-N976N"),
            MirrorResult.VideoSizeChanged(size),
        )
        assertEquals(Screen.Mirror, state.screen)
        assertEquals(Connection.Mirroring("A", "SM-N976N", size), state.connection)
        assertFalse(state.canConnect)
    }

    @Test
    fun rotationReplacesVideoSize() {
        val state = MirrorState().apply(
            MirrorResult.ConnectStarted("A"),
            MirrorResult.VideoSizeChanged(VideoSize(340, 720)),
            MirrorResult.VideoSizeChanged(VideoSize(720, 340)),
        )
        assertEquals(VideoSize(720, 340), (state.connection as Connection.Mirroring).videoSize)
    }

    @Test
    fun lateSessionEventsAfterEndAreIgnored() {
        val ended = MirrorState().apply(MirrorResult.ConnectStarted("A"), MirrorResult.SessionEnded(null))
        assertEquals(ended, ended.apply(MirrorResult.VideoSizeChanged(VideoSize(1, 1))))
        assertEquals(Connection.Idle, ended.connection)
        assertEquals(Screen.DeviceList, ended.screen)
    }

    @Test
    fun lateRecordingResultsAfterSessionEndAreIgnored() {
        val ended = MirrorState().apply(
            MirrorResult.ConnectStarted("A"),
            MirrorResult.DeviceNameReceived("X"),
            MirrorResult.RecordingStarting,
            MirrorResult.SessionEnded(null),
        )
        assertEquals(RecordingState.Idle, ended.recording)
        assertEquals(ended, ended.apply(MirrorResult.RecordingStarted(10)))
        assertEquals(ended, ended.apply(MirrorResult.RecordingStopping))

        // 다음 세션에서는 녹화를 다시 시작할 수 있어야 한다.
        val next = ended.apply(MirrorResult.ConnectStarted("A"), MirrorResult.DeviceNameReceived("X"), MirrorResult.RecordingStarting)
        assertEquals(RecordingState.Starting, next.recording)
    }

    @Test
    fun recordingStartedWithoutStartingIsIgnored() {
        val mirroring = MirrorState().apply(MirrorResult.ConnectStarted("A"), MirrorResult.DeviceNameReceived("X"))
        assertEquals(RecordingState.Idle, mirroring.apply(MirrorResult.RecordingStarted(1)).recording)
        assertEquals(RecordingState.Idle, mirroring.apply(MirrorResult.RecordingStopping).recording)
    }

    @Test
    fun sessionEndWithErrorReturnsToListAndStopsRecording() {
        val state = MirrorState().apply(
            MirrorResult.ConnectStarted("A"),
            MirrorResult.VideoSizeChanged(VideoSize(340, 720)),
            MirrorResult.RecordingStarting,
            MirrorResult.RecordingStarted(1_000),
            MirrorResult.SessionEnded("USB 분리"),
        )
        assertEquals(Connection.Error("USB 분리"), state.connection)
        assertEquals(Screen.DeviceList, state.screen)
        assertEquals(RecordingState.Idle, state.recording)
    }

    @Test
    fun connectFailureAllowsRetry() {
        val state = MirrorState().apply(
            MirrorResult.DevicesLoaded(listOf(online)),
            MirrorResult.DeviceSelected("A"),
            MirrorResult.ConnectStarted("A"),
            MirrorResult.ConnectFailed("서버 연결 실패"),
        )
        assertEquals(Screen.DeviceList, state.screen)
        assertTrue(state.canConnect)
    }

    @Test
    fun recordingCanOnlyStartWhileMirroringAndIdle() {
        assertEquals(RecordingState.Idle, MirrorState().apply(MirrorResult.RecordingStarting).recording)
        val mirroring = MirrorState().apply(MirrorResult.ConnectStarted("A"), MirrorResult.DeviceNameReceived("X"))
        val starting = mirroring.apply(MirrorResult.RecordingStarting)
        assertEquals(RecordingState.Starting, starting.recording)
        val recording = starting.apply(MirrorResult.RecordingStarted(5))
        assertEquals(recording, recording.apply(MirrorResult.RecordingStarting))
        assertEquals(
            RecordingState.Idle,
            recording.apply(MirrorResult.RecordingStopping, MirrorResult.RecordingStopped).recording,
        )
    }

    @Test
    fun conversionProgressIsClampedAndTerminates() {
        val s = MirrorState()
        assertEquals(ConversionState.Converting(1f), s.apply(MirrorResult.ConversionProgressed(1.4f)).conversion)
        assertEquals(ConversionState.Converting(0f), s.apply(MirrorResult.ConversionProgressed(-1f)).conversion)
        assertEquals(ConversionState.Done("a.gif"), s.apply(MirrorResult.ConversionFinished("a.gif")).conversion)
        assertEquals(ConversionState.Idle, s.apply(MirrorResult.ConversionProgressed(0.5f), MirrorResult.ConversionCancelled).conversion)
    }

    @Test
    fun statusMessageShowsAndClears() {
        val shown = MirrorState().apply(MirrorResult.StatusShown("스크린샷 저장"))
        assertEquals("스크린샷 저장", shown.statusMessage)
        assertNull(shown.apply(MirrorResult.StatusCleared).statusMessage)
    }
}
