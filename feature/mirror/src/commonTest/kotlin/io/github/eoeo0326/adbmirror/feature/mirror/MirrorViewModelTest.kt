package io.github.eoeo0326.adbmirror.feature.mirror

import io.github.eoeo0326.adbmirror.core.domain.model.Device
import io.github.eoeo0326.adbmirror.core.domain.model.DeviceState
import io.github.eoeo0326.adbmirror.core.domain.model.EncodedPacket
import io.github.eoeo0326.adbmirror.core.domain.model.MirrorOptions
import io.github.eoeo0326.adbmirror.core.domain.model.MirrorSession
import io.github.eoeo0326.adbmirror.core.domain.model.AnimatedFormat
import io.github.eoeo0326.adbmirror.core.domain.model.ConversionOptions
import io.github.eoeo0326.adbmirror.core.domain.model.VideoInfo
import io.github.eoeo0326.adbmirror.core.domain.model.ConversionProgress
import io.github.eoeo0326.adbmirror.core.domain.model.Recording
import io.github.eoeo0326.adbmirror.core.domain.model.Screenshot
import io.github.eoeo0326.adbmirror.core.domain.model.SessionEvent
import io.github.eoeo0326.adbmirror.core.domain.model.Settings
import io.github.eoeo0326.adbmirror.core.domain.model.TouchAction
import io.github.eoeo0326.adbmirror.core.domain.model.TouchEvent
import io.github.eoeo0326.adbmirror.core.domain.model.VideoSize
import io.github.eoeo0326.adbmirror.core.domain.repository.DeviceRepository
import io.github.eoeo0326.adbmirror.core.domain.repository.MirrorRepository
import io.github.eoeo0326.adbmirror.core.domain.repository.RecordingRepository
import io.github.eoeo0326.adbmirror.core.domain.repository.ScreenshotRepository
import io.github.eoeo0326.adbmirror.core.domain.repository.SettingsRepository
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import androidx.lifecycle.ViewModelStore
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MirrorViewModelTest {
    private class FakeSession : MirrorSession {
        override val serial = "A"
        val eventFlow = MutableSharedFlow<SessionEvent>(replay = 8)
        override val events: Flow<SessionEvent> = eventFlow
        override val packets: Flow<EncodedPacket> = emptyFlow()
        val touches = mutableListOf<TouchEvent>()
        var stopped = false
        /** 설정하면 stop이 이 값이 완료될 때까지 멈춘다(서버 정리에 시간이 걸리는 상황). */
        var stopGate: CompletableDeferred<Unit>? = null
        override suspend fun sendTouch(event: TouchEvent) { touches += event }
        var keyFrameRequests = 0
        override suspend fun requestKeyFrame() { keyFrameRequests++ }
        override suspend fun stop() { stopGate?.await(); stopped = true; eventFlow.emit(SessionEvent.Ended(null)) }
    }

    private val device = Device("A", DeviceState.Online, "SM N976N")
    private val settings = MutableStateFlow(Settings())
    private val session = FakeSession()
    private var failStart: String? = null
    /** 설정하면 start가 이 값이 완료될 때까지 멈춘다(연결 중 상태 재현). */
    private var startGate: CompletableDeferred<Unit>? = null
    private var startedWith: MirrorOptions? = null

    private val settingsRepo = object : SettingsRepository {
        override val settings = this@MirrorViewModelTest.settings
        override suspend fun update(transform: (Settings) -> Settings) = this@MirrorViewModelTest.settings.update(transform)
    }
    private val mirrorRepo = object : MirrorRepository {
        override suspend fun start(serial: String, options: MirrorOptions): MirrorSession {
            startGate?.await()
            failStart?.let { error(it) }
            startedWith = options
            return session
        }
    }

    /** 기기의 show_touches 값. 사용자가 원래 켜 두었을 수도 있다. */
    private var deviceShowTouches = false
    private val showTouchesCalls = mutableListOf<Boolean>()
    private var showTouchesGate: CompletableDeferred<Unit>? = null
    private val deviceRepo = object : DeviceRepository {
        override fun devices() = flowOf(listOf(device))
        override suspend fun setShowTouches(serial: String, enabled: Boolean): Boolean {
            showTouchesCalls += enabled
            val previous = deviceShowTouches
            deviceShowTouches = enabled
            showTouchesGate?.await() // 기기에는 이미 반영됐고 응답만 늦는 상황
            return previous
        }
    }

    private var captureGate: CompletableDeferred<Unit>? = null
    private var failCapture: String? = null
    private var captures = 0
    private val copiedShots = mutableListOf<Screenshot>()
    private val savedShots = mutableListOf<Pair<Screenshot, String?>>()
    private val screenshotRepo = object : ScreenshotRepository {
        override suspend fun capture(serial: String): Screenshot {
            captures++
            captureGate?.await()
            failCapture?.let { error(it) }
            return Screenshot(serial, byteArrayOf(1))
        }
        override suspend fun copyToClipboard(screenshot: Screenshot) { copiedShots += screenshot }
        override suspend fun save(screenshot: Screenshot, outputDir: String?): String {
            savedShots += screenshot to outputDir
            return "${outputDir ?: "~/Desktop"}/shot.png"
        }
    }

    /** serial → 녹화 중 여부. 파일은 녹화 1건당 하나로 흉내 낸다. */
    private val recordingActive = mutableSetOf<String>()
    private var recordStartGate: CompletableDeferred<Unit>? = null
    private var failRecordStart: String? = null
    private var recordedFiles = listOf("/out/a.mp4")
    private var recordedLocations: List<String>? = null
    private val recordingRepo = object : RecordingRepository {
        override suspend fun start(session: MirrorSession, outputDir: String?) {
            recordStartGate?.await()
            failRecordStart?.let { error(it) }
            check(recordingActive.add(session.serial)) { "이미 녹화 중" }
        }
        override suspend fun stop(serial: String): Recording? =
            if (recordingActive.remove(serial)) Recording(serial, recordedFiles, 1000, recordedLocations ?: recordedFiles) else null
        override suspend fun info(file: String) = VideoInfo(4_000, 340, 720)
        override fun supportedFormats() = formats
        override fun convert(files: List<String>, options: ConversionOptions): Flow<ConversionProgress> = flow {
            convertedWith = options
            convertedFiles = files
            emit(ConversionProgress.Running(0.5f))
            conversionGate?.await()
            failConversion?.let { error(it) }
            emit(ConversionProgress.Done(files.first().removeSuffix(".mp4") + ".gif"))
        }.onCompletion { cause -> if (cause is kotlinx.coroutines.CancellationException) conversionCancelled = true }
    }
    private var formats = AnimatedFormat.entries.toSet()
    private var convertedWith: ConversionOptions? = null
    private var convertedFiles: List<String>? = null
    private var conversionGate: CompletableDeferred<Unit>? = null
    private var failConversion: String? = null
    private var conversionCancelled = false

    private fun viewModel() = MirrorViewModel(
        device = device,
        getSettings = GetSettingsUseCase(settingsRepo),
        startMirroring = StartMirroringUseCase(mirrorRepo, settingsRepo),
        stopMirroring = StopMirroringUseCase(),
        sendTouch = SendTouchUseCase(settingsRepo),
        updateSettings = UpdateSettingsUseCase(settingsRepo),
        setShowTouches = SetShowTouchesUseCase(deviceRepo),
        captureScreenshot = CaptureScreenshotUseCase(screenshotRepo),
        copyScreenshot = CopyScreenshotUseCase(screenshotRepo),
        saveScreenshot = SaveScreenshotUseCase(screenshotRepo, settingsRepo),
        startRecording = StartRecordingUseCase(recordingRepo, settingsRepo),
        stopRecording = StopRecordingUseCase(recordingRepo),
        getVideoInfo = GetVideoInfoUseCase(recordingRepo),
        getConversionFormats = GetConversionFormatsUseCase(recordingRepo),
        convertRecording = ConvertRecordingUseCase(recordingRepo),
    )

    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test
    fun connectsAsSoonAsCreated() = runTest {
        val vm = viewModel()
        assertSame(session, vm.session.value)
        // 세션은 생겼지만 첫 세션 이벤트 전까지는 연결 중
        assertEquals(Connection.Connecting, vm.state.value.connection)
        assertTrue(startedWith!!.control)
    }

    @Test
    fun connectExposesSessionAndReflectsEvents() = runTest {
        val vm = viewModel()
        assertSame(session, vm.session.value)
        session.eventFlow.emit(SessionEvent.DeviceName("SM-N976N"))
        session.eventFlow.emit(SessionEvent.VideoSizeChanged(VideoSize(340, 720)))
        assertEquals(Connection.Mirroring("SM-N976N", VideoSize(340, 720)), vm.state.value.connection)
    }

    @Test
    fun errorEndShowsErrorAndReconnects() = runTest {
        val vm = viewModel()
        session.eventFlow.emit(SessionEvent.Ended("영상 스트림이 끊겼습니다"))
        assertEquals(Connection.Error("영상 스트림이 끊겼습니다"), vm.state.value.connection)
        assertNull(vm.session.value)
        assertEquals(MirrorEffect.Error("영상 스트림이 끊겼습니다"), vm.effects.first())

        session.eventFlow.resetReplayCache()
        vm.onIntent(MirrorIntent.Connect)
        assertSame(session, vm.session.value)
        assertEquals(Connection.Connecting, vm.state.value.connection)
    }

    @Test
    fun connectFailureShowsErrorAndAllowsRetry() = runTest {
        failStart = "scrcpy 서버에 연결하지 못했습니다"
        val vm = viewModel()
        assertIs<Connection.Error>(vm.state.value.connection)
        assertTrue(vm.state.value.canConnect)
        assertEquals(MirrorEffect.Error("scrcpy 서버에 연결하지 못했습니다"), vm.effects.first())
    }

    @Test
    fun touchUsesCurrentVideoSizeAndRespectsViewOnly() = runTest {
        val vm = viewModel()
        vm.onIntent(MirrorIntent.Touch(TouchAction.Down, 10, 20)) // 해상도 전: 무시
        session.eventFlow.emit(SessionEvent.VideoSizeChanged(VideoSize(340, 720)))
        vm.onIntent(MirrorIntent.Touch(TouchAction.Down, 10, 20))
        assertEquals(listOf(TouchEvent(TouchAction.Down, 10, 20, VideoSize(340, 720))), session.touches)

        vm.onIntent(MirrorIntent.ToggleViewOnly)
        assertTrue(vm.state.value.settings.viewOnly)
        vm.onIntent(MirrorIntent.Touch(TouchAction.Up, 10, 20))
        assertEquals(1, session.touches.size)
    }

    @Test
    fun shutdownWhileConnectingWaitsUntilLateSessionIsFullyStopped() = runTest {
        val startGate = CompletableDeferred<Unit>().also { this@MirrorViewModelTest.startGate = it }
        val stopGate = CompletableDeferred<Unit>().also { session.stopGate = it }
        val vm = viewModel()
        assertEquals(Connection.Connecting, vm.state.value.connection)

        val shutdown = launch { vm.shutdown() }
        testScheduler.advanceUntilIdle()
        assertFalse(shutdown.isCompleted, "연결 시도가 끝날 때까지 기다려야 한다")

        startGate.complete(Unit) // 창을 닫은 뒤에야 서버가 떴다
        testScheduler.advanceUntilIdle()
        assertFalse(shutdown.isCompleted, "늦게 생긴 세션의 정리가 끝나기 전에 반환하면 안 된다")

        stopGate.complete(Unit)
        shutdown.join()
        assertTrue(session.stopped)
        assertNull(vm.session.value)
        vm.shutdown() // 두 번 불러도 안전
    }

    @Test
    fun shutdownAfterConnectedStopsSession() = runTest {
        val vm = viewModel()
        vm.shutdown()
        assertTrue(session.stopped)
    }

    @Test
    fun disconnectStopsSession() = runTest {
        val vm = viewModel()
        vm.onIntent(MirrorIntent.Disconnect)
        assertTrue(session.stopped)
        assertEquals(Connection.Idle, vm.state.value.connection)
        assertNull(vm.session.value)
    }

    @Test
    fun showTouchesOffLeavesDeviceSettingAlone() = runTest {
        val vm = viewModel()
        vm.onIntent(MirrorIntent.Disconnect)
        vm.shutdown()
        assertEquals(emptyList(), showTouchesCalls)
    }

    @Test
    fun showTouchesIsTurnedOnWhileConnectedAndRestoredOnDisconnect() = runTest {
        settings.value = Settings(showTouches = true)
        val vm = viewModel()
        assertEquals(listOf(true), showTouchesCalls)
        assertTrue(deviceShowTouches)

        vm.onIntent(MirrorIntent.Disconnect)
        assertEquals(listOf(true, false), showTouchesCalls)
        assertFalse(deviceShowTouches)
    }

    @Test
    fun showTouchesRestoresTheUsersOwnValue() = runTest {
        deviceShowTouches = true // 사용자가 기기에서 직접 켜 둠
        settings.value = Settings(showTouches = true)
        val vm = viewModel()
        vm.shutdown()
        assertEquals(listOf(true, true), showTouchesCalls)
        assertTrue(deviceShowTouches)
    }

    @Test
    fun toggleShowTouchesAppliesImmediatelyAndOffRestores() = runTest {
        val vm = viewModel()
        vm.onIntent(MirrorIntent.ToggleShowTouches)
        assertTrue(vm.state.value.settings.showTouches)
        assertTrue(deviceShowTouches)

        vm.onIntent(MirrorIntent.ToggleShowTouches)
        assertFalse(deviceShowTouches)
        assertEquals(listOf(true, false), showTouchesCalls)
    }

    @Test
    fun showTouchesIsRestoredWhenSessionEndsByItself() = runTest {
        settings.value = Settings(showTouches = true)
        viewModel()
        session.eventFlow.emit(SessionEvent.Ended("영상 스트림이 끊겼습니다"))
        assertFalse(deviceShowTouches)
    }

    @Test
    fun shutdownRestoresShowTouchesBeforeReturning() = runTest {
        settings.value = Settings(showTouches = true)
        val vm = viewModel()
        vm.shutdown()
        assertFalse(deviceShowTouches)
        // 창을 닫은 뒤 설정이 바뀌어도 다시 켜지 않는다
        settings.update { it.copy(showTouches = false) }
        settings.update { it.copy(showTouches = true) }
        assertEquals(listOf(true, false), showTouchesCalls)
    }

    @Test
    fun showTouchesIsRestoredEvenIfCancelledWhileTurningOn() = runTest {
        val vm = viewModel()
        val store = ViewModelStore().apply { put("mirror", vm) }
        val gate = CompletableDeferred<Unit>().also { showTouchesGate = it }
        vm.onIntent(MirrorIntent.ToggleShowTouches)
        assertTrue(deviceShowTouches)

        store.clear() // 켜는 응답을 받기 전에 viewModelScope가 취소됨
        gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        vm.shutdown()
        assertFalse(deviceShowTouches, "켜 둔 기기 설정을 원래 값으로 되돌려야 한다")
    }

    @Test
    fun copyScreenshotCapturesFromDeviceAndReports() = runTest {
        val vm = viewModel()
        vm.onIntent(MirrorIntent.CopyScreenshot)
        assertEquals("A", copiedShots.single().serial)
        assertEquals(MirrorEffect.ShowMessage("스크린샷을 클립보드에 복사했습니다"), vm.effects.first())
        assertFalse(vm.state.value.capturingScreenshot)
    }

    @Test
    fun saveScreenshotUsesOutputDirAndReportsPath() = runTest {
        settings.value = Settings(outputDir = "/shots")
        val vm = viewModel()
        vm.onIntent(MirrorIntent.SaveScreenshot)
        assertEquals("/shots", savedShots.single().second)
        assertEquals(MirrorEffect.ScreenshotSaved("/shots/shot.png"), vm.effects.first())
    }

    @Test
    fun screenshotWorksWithoutMirroringSession() = runTest {
        failStart = "scrcpy 서버에 연결하지 못했습니다"
        val vm = viewModel()
        vm.effects.first() // 연결 실패 알림
        vm.onIntent(MirrorIntent.CopyScreenshot)
        assertEquals(1, copiedShots.size)
    }

    @Test
    fun screenshotIgnoresRepeatedRequestsWhileCapturing() = runTest {
        val gate = CompletableDeferred<Unit>().also { captureGate = it }
        val vm = viewModel()
        vm.onIntent(MirrorIntent.SaveScreenshot)
        assertTrue(vm.state.value.capturingScreenshot)
        vm.onIntent(MirrorIntent.SaveScreenshot)
        vm.onIntent(MirrorIntent.CopyScreenshot)
        gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals(1, captures)
        assertEquals(1, savedShots.size)
        assertEquals(emptyList(), copiedShots)
        assertFalse(vm.state.value.capturingScreenshot)
    }

    @Test
    fun screenshotFailureShowsErrorAndAllowsRetry() = runTest {
        failCapture = "device offline"
        val vm = viewModel()
        vm.onIntent(MirrorIntent.CopyScreenshot)
        assertEquals(MirrorEffect.Error("스크린샷 실패: device offline"), vm.effects.first())
        assertFalse(vm.state.value.capturingScreenshot)

        failCapture = null
        vm.onIntent(MirrorIntent.CopyScreenshot)
        assertEquals(1, copiedShots.size)
    }

    @Test
    fun effectMessages() {
        assertEquals("스크린샷을 저장했습니다: /a.png", MirrorEffect.ScreenshotSaved("/a.png").message())
        assertEquals("x", MirrorEffect.Error("x").message())
        assertNull(MirrorEffect.AskShowTouchesForRecording.message())
    }

    private suspend fun mirroringViewModel(): MirrorViewModel = viewModel().also {
        session.eventFlow.emit(SessionEvent.VideoSizeChanged(VideoSize(340, 720)))
    }

    @Test
    fun recordingStartsAfterSubscribingAndStopsWithSavedFiles() = runTest {
        val vm = mirroringViewModel()
        vm.onIntent(MirrorIntent.StartRecording)
        assertIs<RecordingState.Recording>(vm.state.value.recording)
        assertTrue("A" in recordingActive)
        assertEquals(1, session.keyFrameRequests)

        vm.onIntent(MirrorIntent.StopRecording)
        assertEquals(RecordingState.Idle, vm.state.value.recording)
        assertEquals(MirrorEffect.RecordingSaved(listOf("/out/a.mp4")), vm.effects.first())
        assertTrue(recordingActive.isEmpty())
    }

    @Test
    fun recordingSavedShowsStoredLocations() = runTest {
        // Android: 캐시의 파일로 변환하고, 메시지에는 MediaStore 위치를 보여준다.
        recordedLocations = listOf("Movies/ADB Mirror/a.mp4")
        val vm = mirroringViewModel()
        vm.onIntent(MirrorIntent.StartRecording)
        vm.onIntent(MirrorIntent.StopRecording)
        assertEquals(MirrorEffect.RecordingSaved(listOf("/out/a.mp4"), listOf("Movies/ADB Mirror/a.mp4")), vm.effects.first())
        assertEquals(listOf("/out/a.mp4"), vm.state.value.lastRecording)
    }

    @Test
    fun recordingNeedsMirroring() = runTest {
        val vm = viewModel() // 아직 영상 크기 전(연결 중)
        vm.onIntent(MirrorIntent.StartRecording)
        assertEquals(RecordingState.Idle, vm.state.value.recording)
        assertTrue(recordingActive.isEmpty())
    }

    @Test
    fun sessionEndWhileRecordingSavesRecording() = runTest {
        val vm = mirroringViewModel()
        vm.onIntent(MirrorIntent.StartRecording)
        session.eventFlow.emit(SessionEvent.Ended("영상 스트림이 끊겼습니다"))
        assertTrue(recordingActive.isEmpty())
        assertEquals(RecordingState.Idle, vm.state.value.recording)
        assertEquals(MirrorEffect.RecordingSaved(listOf("/out/a.mp4")), vm.effects.first())
    }

    @Test
    fun sessionEndWhileRecordingIsStartingLeavesNoOrphanRecording() = runTest {
        val gate = CompletableDeferred<Unit>().also { recordStartGate = it }
        val vm = mirroringViewModel()
        vm.onIntent(MirrorIntent.StartRecording)
        assertEquals(RecordingState.Starting, vm.state.value.recording)

        val ended = launch { session.eventFlow.emit(SessionEvent.Ended(null)) }
        testScheduler.advanceUntilIdle()
        gate.complete(Unit) // 세션이 끝난 뒤에야 녹화가 시작됨
        testScheduler.advanceUntilIdle()
        ended.join()
        assertTrue(recordingActive.isEmpty(), "시작이 끝난 뒤 정지돼야 한다")
        assertEquals(RecordingState.Idle, vm.state.value.recording)
    }

    @Test
    fun shutdownFinishesRecordingBeforeReturning() = runTest {
        val vm = mirroringViewModel()
        vm.onIntent(MirrorIntent.StartRecording)
        vm.shutdown()
        assertTrue(recordingActive.isEmpty())
        assertTrue(session.stopped)
    }

    @Test
    fun recordingStartFailureShowsErrorAndReturnsToIdle() = runTest {
        failRecordStart = "디스크가 가득 찼습니다"
        val vm = mirroringViewModel()
        vm.onIntent(MirrorIntent.StartRecording)
        assertEquals(RecordingState.Idle, vm.state.value.recording)
        assertEquals(MirrorEffect.Error("녹화를 시작하지 못했습니다: 디스크가 가득 찼습니다"), vm.effects.first())
    }

    @Test
    fun emptyRecordingIsReportedAsMessage() = runTest {
        recordedFiles = emptyList()
        val vm = mirroringViewModel()
        vm.onIntent(MirrorIntent.StartRecording)
        vm.onIntent(MirrorIntent.StopRecording)
        assertEquals(MirrorEffect.ShowMessage("녹화된 화면이 없어 파일을 만들지 않았습니다"), vm.effects.first())
    }

    @Test
    fun elapsedFormat() {
        assertEquals("0:00", formatElapsed(999))
        assertEquals("1:05", formatElapsed(65_000))
        assertEquals("1:00:01", formatElapsed(3_601_000))
    }

    @Test
    fun recordingSavedIsRememberedForConversion() = runTest {
        val vm = mirroringViewModel()
        vm.onIntent(MirrorIntent.StartRecording)
        vm.onIntent(MirrorIntent.StopRecording)
        assertEquals(listOf("/out/a.mp4"), vm.state.value.lastRecording)
    }

    @Test
    fun openConversionLoadsInfoAndClampsWidth() = runTest {
        val vm = viewModel()
        vm.onIntent(MirrorIntent.OpenConversion(listOf("/out/a.mp4")))
        val draft = vm.state.value.conversionDraft!!
        assertEquals(VideoInfo(4_000, 340, 720), draft.info)
        assertEquals(340, draft.options.width) // 기본 480이지만 원본보다 키우지 않는다
        assertEquals(AnimatedFormat.entries.toSet(), draft.formats)
    }

    @Test
    fun convertReportsProgressAndDone() = runTest {
        val gate = CompletableDeferred<Unit>().also { conversionGate = it }
        val vm = viewModel()
        vm.onIntent(MirrorIntent.OpenConversion(listOf("/out/a.mp4")))
        vm.onIntent(MirrorIntent.ChangeConversionOptions(vm.state.value.conversionDraft!!.options.copy(fps = 15)))
        vm.onIntent(MirrorIntent.Convert)
        assertEquals(ConversionState.Converting(0.5f), vm.state.value.conversion)
        assertEquals(15, convertedWith!!.fps)

        // 변환하는 동안에는 옵션을 바꾸지 않는다
        vm.onIntent(MirrorIntent.ChangeConversionOptions(convertedWith!!.copy(fps = 30)))
        assertEquals(15, vm.state.value.conversionDraft!!.options.fps)

        gate.complete(Unit)
        assertEquals(ConversionState.Done("/out/a.gif"), vm.state.value.conversion)
        assertEquals(MirrorEffect.ConversionDone("/out/a.gif"), vm.effects.first())
        // 옵션을 바꾸면 지난 결과 문구는 사라진다
        vm.onIntent(MirrorIntent.ChangeConversionOptions(convertedWith!!.copy(fps = 5)))
        assertEquals(ConversionState.Idle, vm.state.value.conversion)
    }

    @Test
    fun cancelStopsConversionFlow() = runTest {
        conversionGate = CompletableDeferred()
        val vm = viewModel()
        vm.onIntent(MirrorIntent.OpenConversion(listOf("/out/a.mp4")))
        vm.onIntent(MirrorIntent.Convert)
        vm.onIntent(MirrorIntent.CancelConversion)
        assertEquals(ConversionState.Idle, vm.state.value.conversion)
        assertTrue(conversionCancelled)
        assertTrue(vm.state.value.conversionDraft != null, "취소해도 화면은 남아 다시 변환할 수 있다")
    }

    @Test
    fun closeWhileConvertingCancelsAndCloses() = runTest {
        conversionGate = CompletableDeferred()
        val vm = viewModel()
        vm.onIntent(MirrorIntent.OpenConversion(listOf("/out/a.mp4")))
        vm.onIntent(MirrorIntent.Convert)
        vm.onIntent(MirrorIntent.CloseConversion)
        assertTrue(conversionCancelled)
        assertNull(vm.state.value.conversionDraft)
    }

    @Test
    fun conversionFailureIsShownInPanel() = runTest {
        failConversion = "디코딩 실패"
        val vm = viewModel()
        vm.onIntent(MirrorIntent.OpenConversion(listOf("/out/a.mp4")))
        vm.onIntent(MirrorIntent.Convert)
        assertEquals(ConversionState.Failed("디코딩 실패"), vm.state.value.conversion)
    }

    @Test
    fun unsupportedFormatBlocksConvert() = runTest {
        formats = setOf(AnimatedFormat.Gif)
        val vm = viewModel()
        vm.onIntent(MirrorIntent.OpenConversion(listOf("/out/a.mp4")))
        vm.onIntent(MirrorIntent.ChangeConversionOptions(vm.state.value.conversionDraft!!.options.copy(format = AnimatedFormat.WebP)))
        assertTrue(vm.state.value.conversionDraft!!.problems.isNotEmpty())
        vm.onIntent(MirrorIntent.Convert)
        assertNull(convertedWith)
    }

    @Test
    fun sizeFormatting() {
        assertEquals("4.4초", seconds(4_430))
        assertEquals("0.5MB", megabytes(512 * 1024))
        assertEquals("25MB", megabytes(25L * 1024 * 1024))
    }

    @Test
    fun rotatedRecordingPartsAreConvertedTogether() = runTest {
        val vm = viewModel()
        vm.onIntent(MirrorIntent.OpenConversion(listOf("/out/a.mp4", "/out/a_part2.mp4")))
        val draft = vm.state.value.conversionDraft!!
        assertEquals(8_000, draft.info.durationMs) // part 두 개 길이의 합
        vm.onIntent(MirrorIntent.Convert)
        assertEquals(listOf("/out/a.mp4", "/out/a_part2.mp4"), convertedFiles)
    }
}
