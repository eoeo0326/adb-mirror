package io.github.eoeo0326.adbmirror.feature.mirror

import io.github.eoeo0326.adbmirror.core.domain.model.Device
import io.github.eoeo0326.adbmirror.core.domain.model.DeviceState
import io.github.eoeo0326.adbmirror.core.domain.model.EncodedPacket
import io.github.eoeo0326.adbmirror.core.domain.model.MirrorOptions
import io.github.eoeo0326.adbmirror.core.domain.model.MirrorSession
import io.github.eoeo0326.adbmirror.core.domain.model.SessionEvent
import io.github.eoeo0326.adbmirror.core.domain.model.Settings
import io.github.eoeo0326.adbmirror.core.domain.model.TouchAction
import io.github.eoeo0326.adbmirror.core.domain.model.TouchEvent
import io.github.eoeo0326.adbmirror.core.domain.model.VideoSize
import io.github.eoeo0326.adbmirror.core.domain.repository.DeviceRepository
import io.github.eoeo0326.adbmirror.core.domain.repository.MirrorRepository
import io.github.eoeo0326.adbmirror.core.domain.repository.SettingsRepository
import io.github.eoeo0326.adbmirror.core.domain.usecase.GetDevicesUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.GetSettingsUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.SendTouchUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.StartMirroringUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.StopMirroringUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.UpdateSettingsUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
        override suspend fun sendTouch(event: TouchEvent) { touches += event }
        override suspend fun requestKeyFrame() {}
        override suspend fun stop() { stopped = true; eventFlow.emit(SessionEvent.Ended(null)) }
    }

    private val devices = MutableStateFlow(listOf(Device("A", DeviceState.Online, "SM N976N")))
    private val settings = MutableStateFlow(Settings())
    private val session = FakeSession()
    private var failStart: String? = null
    private var startedWith: MirrorOptions? = null

    private val deviceRepo = object : DeviceRepository {
        override fun devices() = devices
        override suspend fun setShowTouches(serial: String, enabled: Boolean) = false
    }
    private val settingsRepo = object : SettingsRepository {
        override val settings = this@MirrorViewModelTest.settings
        override suspend fun update(transform: (Settings) -> Settings) = this@MirrorViewModelTest.settings.update(transform)
    }
    private val mirrorRepo = object : MirrorRepository {
        override suspend fun start(serial: String, options: MirrorOptions): MirrorSession {
            failStart?.let { error(it) }
            startedWith = options
            return session
        }
    }

    private fun viewModel() = MirrorViewModel(
        getDevices = GetDevicesUseCase(deviceRepo),
        getSettings = GetSettingsUseCase(settingsRepo),
        startMirroring = StartMirroringUseCase(mirrorRepo, settingsRepo),
        stopMirroring = StopMirroringUseCase(),
        sendTouch = SendTouchUseCase(settingsRepo),
        updateSettings = UpdateSettingsUseCase(settingsRepo),
    )

    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test
    fun loadsDevicesWithoutAutoSelecting() = runTest {
        val vm = viewModel()
        assertEquals(listOf("A"), vm.state.value.devices.map { it.serial })
        assertNull(vm.state.value.selectedSerial)
        vm.onIntent(MirrorIntent.Connect) // 선택 전에는 연결되지 않는다
        assertNull(startedWith)
    }

    @Test
    fun connectExposesSessionAndReflectsEvents() = runTest {
        val vm = viewModel()
        vm.onIntent(MirrorIntent.SelectDevice("A"))
        vm.onIntent(MirrorIntent.Connect)
        assertSame(session, vm.session.value)
        session.eventFlow.emit(SessionEvent.DeviceName("SM-N976N"))
        session.eventFlow.emit(SessionEvent.VideoSizeChanged(VideoSize(340, 720)))
        assertEquals(Connection.Mirroring("A", "SM-N976N", VideoSize(340, 720)), vm.state.value.connection)
        assertEquals(Screen.Mirror, vm.state.value.screen)
    }

    @Test
    fun errorEndReturnsToListWithEffect() = runTest {
        val vm = viewModel()
        vm.onIntent(MirrorIntent.SelectDevice("A"))
        vm.onIntent(MirrorIntent.Connect)
        session.eventFlow.emit(SessionEvent.Ended("영상 스트림이 끊겼습니다"))
        assertEquals(Screen.DeviceList, vm.state.value.screen)
        assertNull(vm.session.value)
        assertEquals(MirrorEffect.Error("영상 스트림이 끊겼습니다"), vm.effects.first())
    }

    @Test
    fun connectFailureShowsErrorAndAllowsRetry() = runTest {
        failStart = "scrcpy 서버에 연결하지 못했습니다"
        val vm = viewModel()
        vm.onIntent(MirrorIntent.SelectDevice("A"))
        vm.onIntent(MirrorIntent.Connect)
        assertIs<Connection.Error>(vm.state.value.connection)
        assertTrue(vm.state.value.canConnect)
        assertEquals(MirrorEffect.Error("scrcpy 서버에 연결하지 못했습니다"), vm.effects.first())
    }

    @Test
    fun touchUsesCurrentVideoSizeAndRespectsViewOnly() = runTest {
        val vm = viewModel()
        vm.onIntent(MirrorIntent.SelectDevice("A"))
        vm.onIntent(MirrorIntent.Connect)
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
    fun disconnectStopsSession() = runTest {
        val vm = viewModel()
        vm.onIntent(MirrorIntent.SelectDevice("A"))
        vm.onIntent(MirrorIntent.Connect)
        vm.onIntent(MirrorIntent.Disconnect)
        assertTrue(session.stopped)
        assertEquals(Connection.Idle, vm.state.value.connection)
        assertNull(vm.session.value)
    }
}
